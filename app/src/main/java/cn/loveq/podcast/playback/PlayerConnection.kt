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
                pendingPlay?.let { (episodes, index) ->
                    pendingPlay = null
                    doPlayQueue(episodes, index)
                }
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
