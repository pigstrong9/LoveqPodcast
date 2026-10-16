package cn.loveq.podcast.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import cn.loveq.podcast.data.Episode
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 启动自动续播该走哪条路。
 *
 * 抽成纯函数是因为这里有个**容易做错、错了又很难察觉**的点：服务里可能已经装着队列
 * （用户按了返回键退出界面、音乐还在放），此时若照着「继续播放」重建队列，
 * 位置会退回上一次落盘点（最多十几秒），听着就是「节目自己往回跳」。
 */
internal enum class LaunchResume {
    /** 用户已经点过播：自动行为让开，什么都不做 */
    Skip,

    /** 服务里已经有队列：只在暂停时接着播，绝不重建 */
    Continue,

    /** 服务里没有内容：按上次收听的那期起播 */
    StartFresh
}

internal fun launchResumeAction(playRequested: Boolean, hasQueue: Boolean): LaunchResume = when {
    playRequested -> LaunchResume.Skip
    hasQueue -> LaunchResume.Continue
    else -> LaunchResume.StartFresh
}

class PlayerConnection(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private var pendingPlay: Pair<List<Episode>, Int>? = null

    val isPlaying = MutableStateFlow(false)
    val mediaId = MutableStateFlow<String?>(null)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val speed = MutableStateFlow(1.0f)
    val nowTitle = MutableStateFlow<String?>(null)
    val nowDate = MutableStateFlow<String?>(null)

    /** 与服务连上、并且已同步过一次状态（[refreshStaticState]）。只给 [resumeOnLaunch] 用，不外露 */
    private val connected = MutableStateFlow(false)

    /**
     * 连上后执行一次的动作，见 [resumeOnLaunch]。
     *
     * 只挂一个：再挂会悄悄顶掉前一个（当前只有 ViewModel 在 init 里挂一次）。
     * 要是哪天多出第二个调用方，这里改成 `check(onConnected == null)` 直接炸出来更好。
     */
    private var onConnected: (() -> Unit)? = null

    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
    ).buildAsync()

    init {
        controllerFuture.addListener(
            {
                val c = try {
                    controllerFuture.get()
                } catch (e: Exception) {
                    PlaybackEvents.report("播放服务启动失败，请重启应用")
                    return@addListener
                }
                controller = c
                c.addListener(listener)
                refreshStaticState()
                // 顺序是有意的，两步都不能换位：
                // · 自动续播要排在 pendingPlay 之前 —— 用户已经点下的播优先于自动行为，
                //   而「已经点过」正是看 pendingPlay != null（playQueue 在没连上时存这里）。
                // · 它又必须在 refreshStaticState() 之后 —— resumeOnLaunch 用 mediaId 判断
                //   「服务里已经有队列了吗」，mediaId 就是上一步填的，不能提前读。
                // 这里读到的不是过期值：media3 保证 connect 回调触发时播放器状态已同步完
                // （MediaControllerHolder 要 controller 与 accepted 齐了才完成 future，
                // 而 accepted 是在 onConnected 读完 playerInfo 之后才置位）。
                onConnected?.let { it() }
                onConnected = null
                pendingPlay?.let { (episodes, index) ->
                    pendingPlay = null
                    doPlayQueue(episodes, index)
                }
                connected.value = true
            },
            ContextCompat.getMainExecutor(appContext)
        )

        scope.launch {
            // 只在播放中轮询进度：暂停/空闲时直接挂起，不再每 500ms 唤醒一次。
            // 暂停时的位置由 onPositionDiscontinuity（拖动/跳转）和 onIsPlayingChanged
            // 各补一次，所以「暂停后拖进度条」进度也不会停在上一拍。
            isPlaying.collectLatest { playing ->
                if (!playing) return@collectLatest
                while (true) {
                    syncPosition()
                    delay(POSITION_TICK_MS)
                }
            }
        }
    }

    fun playQueue(episodes: List<Episode>, index: Int) {
        val c = controller
        if (c == null) {
            pendingPlay = episodes to index
            return
        }
        doPlayQueue(episodes, index)
    }

    private fun doPlayQueue(episodes: List<Episode>, index: Int) {
        val args = Bundle().apply {
            putStringArrayList(
                PlaybackService.EXTRA_ITEMS,
                ArrayList(episodes.map { encode(it) })
            )
            putInt(PlaybackService.EXTRA_START, index)
        }
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_PLAY_QUEUE, Bundle.EMPTY),
            args
        )
    }

    /**
     * 启动自动续播：等服务连上后，按**服务里的实际情况**接续（开关见 ProgressStore.autoResume）。
     *
     * 为什么不直接照着「继续播放」重建队列：服务里可能已经装着队列 ——
     * 用户按返回键退出界面但音乐还在放，再点开应用时若重建队列，位置会退回上一次落盘点
     * （最多十几秒），听着就是「节目自己往回跳」。所以：
     * · 用户已经点过播（连上之前点的）→ 什么都不做，手比自动优先
     * · 服务里已经有内容 → 只在暂停时接着播，绝不动队列
     * · 服务刚起来没内容 → 交给 [startFresh] 决定起播哪一期
     *
     * @param startFresh 服务里没有内容时怎么起播
     */
    fun resumeOnLaunch(startFresh: () -> Unit) {
        val act: () -> Unit = {
            when (launchResumeAction(pendingPlay != null, mediaId.value != null)) {
                LaunchResume.Skip -> Unit
                LaunchResume.Continue -> if (!isPlaying.value) controller?.play()
                LaunchResume.StartFresh -> startFresh()
            }
        }
        if (connected.value) act() else onConnected = act
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBack() {
        controller?.seekBack()
    }

    fun seekForward() {
        controller?.seekForward()
    }

    fun setSpeed(value: Float) {
        controller?.setPlaybackSpeed(value)
    }

    fun release() {
        scope.cancel()
        MediaController.releaseFuture(controllerFuture)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@PlayerConnection.isPlaying.value = isPlaying
            // 暂停的一刻轮询已经停了，补一次位置，否则进度条会停在上一拍
            if (!isPlaying) syncPosition()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // 拖动进度条（含暂停时拖动）走这里，立即把新位置同步给界面
            syncPosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaId.value = mediaItem?.mediaId
            nowTitle.value = mediaItem?.mediaMetadata?.title?.toString()
            // 日期放在 albumTitle（通知栏的「艺术家」一栏被节目名占用了）
            nowDate.value = mediaItem?.mediaMetadata?.albumTitle?.toString()
            positionMs.value = 0
            durationMs.value = 0
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speed.value = playbackParameters.speed
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_READY && c.duration > 0) {
                durationMs.value = c.duration
            }
            isPlaying.value = c.isPlaying
        }
    }

    private fun refreshStaticState() {
        val c = controller ?: return
        isPlaying.value = c.isPlaying
        mediaId.value = c.currentMediaItem?.mediaId
        speed.value = c.playbackParameters.speed
        syncPosition()
        c.currentMediaItem?.let { item ->
            nowTitle.value = item.mediaMetadata.title?.toString()
            nowDate.value = item.mediaMetadata.albumTitle?.toString()
        }
    }

    private fun syncPosition() {
        val c = controller ?: return
        positionMs.value = c.currentPosition.coerceAtLeast(0)
        if (c.duration > 0) durationMs.value = c.duration
    }

    private fun encode(episode: Episode): String =
        "${episode.id}${PlaybackService.SEP}${episode.date}${PlaybackService.SEP}${episode.title}"

    private companion object {
        const val POSITION_TICK_MS = 500L
    }
}
