package cn.loveq.podcast.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaSessionService
import cn.loveq.podcast.LoveqApp
import cn.loveq.podcast.R
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.EpisodeAudio
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: LoveqApp

    private var mediaSession: MediaSession? = null
    private val player: Player get() = mediaSession!!.player

    private var queue: List<Episode> = emptyList()

    /** 上次进程存活时落盘的连播队列，用于恢复「从搜索/历史点进来」那集的连播上下文 */
    private var restoredQueue: QueueStore.Snapshot? = null
    private var currentTitle = ""
    private var urlRetries = 0

    /** 断网时挂的网络回调，恢复或销毁时必须注销 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentJob: Job? = null

    /** 直链过期后的退避重取，与解析任务分开，避免互相取消 */
    private var retryJob: Job? = null
    private var lastSavedId: Long? = null
    private var lastSavedPosition = -1L

    /** 内存里最新的位置：直链过期重解析后按它续播，而不是退回上次落盘的旧位置 */
    private var lastKnownPosition = -1L
    private var pendingLoad = false

    /** 正在解析音频地址的队列下标，-1 表示空闲；避免占位地址报错时重复触发解析 */
    private var resolvingIndex = -1

    override fun onCreate() {
        super.onCreate()
        app = applicationContext as LoveqApp

        val cache = MediaCache.get(this)
        val cachingFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(CacheKeyFactory { dataSpec -> MediaCache.keyOf(dataSpec.uri) })
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // 包一层按 scheme 分流：http(s) 走带缓存的上游，file:// 直接走本地文件
        // （离线缓存好的整集不再需要签名直链，没网也能播）
        val dataSourceFactory = DefaultDataSource.Factory(this, cachingFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(BoostRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
        exoPlayer.playbackParameters = PlaybackParameters(app.progressStore.speed)
        exoPlayer.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, cn.loveq.podcast.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .build()

        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                saveProgress()
            }
        }
        restoredQueue = app.queueStore.load()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) {
                return super.onConnect(session, controller)
            }
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PLAY_QUEUE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            mediaButtonEvent: Intent
        ): Boolean {
            if (mediaButtonEvent.action == Intent.ACTION_MEDIA_BUTTON) {
                @Suppress("DEPRECATION")
                val keyEvent = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            session.player.seekForward()
                            return true
                        }

                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            session.player.seekBack()
                            return true
                        }
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, mediaButtonEvent)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_PLAY_QUEUE) {
                val items = args.getStringArrayList(EXTRA_ITEMS).orEmpty().mapNotNull { encoded ->
                    val parts = encoded.split(SEP)
                    val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val date = parts.getOrNull(1).orEmpty()
                    val title = parts.getOrNull(2).orEmpty()
                    Episode(id, date, title)
                }
                val start = args.getInt(EXTRA_START, 0)
                if (items.isNotEmpty()) {
                    playQueue(items, start)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private fun playQueue(items: List<Episode>, startIndex: Int) {
        if (items.isEmpty()) return
        // 从搜索/历史点进来的那一集，ViewModel 那边可能只有它自己（不在当前列表、
        // 也不在全库索引覆盖的分类里）。若它在上次落盘的队列里，就沿用原队列，
        // 连播上下文不至于丢掉。
        var resolved = items
        var start = startIndex
        if (items.size == 1) {
            val snapshot = restoredQueue
            val at = snapshot?.episodes?.indexOfFirst { it.id == items[0].id } ?: -1
            if (snapshot != null && at >= 0) {
                resolved = snapshot.episodes
                start = at
            }
        }
        queue = resolved
        urlRetries = 0
        app.queueStore.save(resolved, start)
        // 一次性把整条队列交给播放器：通知栏因此知道「有下一期」，才会显示切集按钮。
        // 音频地址（带签名、有时效）先留空，播放到某一期时再解析并替换进去。
        pendingLoad = true
        if (start > 0) player.seekToDefaultPosition(start)
        player.setMediaItems(resolved.map { lazyItem(it) }, start, 0L)
        // 起始条目的真实地址由 resolveAndPlay 解析并替换，这里不 prepare 占位条目
        resolveAndPlay(start)
    }

    /**
     * 队列项的占位条目：只有 id 与元数据，没有真实音频地址。
     * ExoPlayer 不接受 uri 为空的 MediaItem，所以挂一个「尚未解析」的占位地址，
     * 播放到这一期时会立刻被换掉，不会真去加载。
     * 艺术家一栏放节目名（通知栏需要），日期改放 albumTitle；
     * 应用内播放面板的日期由 PlayerConnection 从 mediaId 查队列得到。
     */
    private fun lazyItem(episode: Episode): MediaItem = MediaItem.Builder()
        .setMediaId(episode.id.toString())
        .setUri(PLACEHOLDER_URI)
        .setMediaMetadata(episodeMetadata(episode.title.ifBlank { episode.date }, episode.date))
        .build()

    private fun episodeMetadata(title: String, date: String): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(PODCAST_NAME)
            .setAlbumTitle(date)
            .setArtworkUri(artworkUri)
            .build()

    /**
     * 解析某一期的音频地址并立即播放；地址过期重试也走这里。
     *
     * 注意这里**不能**重置 urlRetries：重试路径正是本函数，一清零计数就永远到不了上限，
     * 持续 405 会变成无退避的死循环抓站。计数只在真正换期或播放恢复正常时清。
     */
    private fun resolveAndPlay(
        index: Int,
        autoplay: Boolean = true,
        resumePositionMs: Long = -1L
    ) {
        val episode = queue.getOrNull(index) ?: return
        if (resolvingIndex == index) return
        resolvingIndex = index
        pendingLoad = true
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                // 离线缓存过的整集直接播本地文件：不取签名直链，断网也能播
                val local = app.downloads.localFile(episode.id)
                val audio = local?.let { EpisodeAudio(Uri.fromFile(it).toString(), null) }
                    ?: app.repository.fetchAudio(episode.id)
                resolvingIndex = -1
                if (index != player.currentMediaItemIndex) return@launch
                val item = lazyItem(episode)
                    .buildUpon()
                    .setUri(audio.url)
                    .setMediaMetadata(
                        episodeMetadata(
                            title = audio.title ?: episode.title.ifBlank { episode.date },
                            date = episode.date
                        )
                    )
                    .build()
                MediaCache.setActiveKey(item.localConfiguration?.uri)
                player.replaceMediaItem(index, item)
                player.prepare()
                // 重解析用内存里的最新位置续上；首次点播才回落到上次落盘的进度
                if (resumePositionMs > 0) player.seekTo(resumePositionMs)
                else startAtSavedPosition(episode)
                if (autoplay) player.play()
                pendingLoad = false
            } catch (e: CancellationException) {
                // 只有仍归自己时才清标记：被新任务取消的旧任务若无条件清掉，
                // 会把新任务的「解析中」状态误清空，导致对同一期重复解析
                if (resolvingIndex == index) resolvingIndex = -1
                throw e
            } catch (e: Exception) {
                resolvingIndex = -1
                pendingLoad = false
                PlaybackEvents.report("加载节目失败，请检查网络后重试")
            }
        }
    }

    /** 续播：有进度且在有效范围内就跳到上次位置 */
    private fun startAtSavedPosition(episode: Episode) {
        val saved = app.progressStore.get(episode.id)
        val startPos = when {
            saved == null || saved.done -> 0L
            saved.durationMs > 0 && saved.positionMs >= saved.durationMs - RESUME_EPSILON_MS -> 0L
            saved.positionMs > RESUME_EPSILON_MS -> saved.positionMs
            else -> 0L
        }
        if (startPos > 0) player.seekTo(startPos)
    }

    /** 断网时挂起等待：网络一恢复就重新解析直链、从内存最新位置续播 */
    private fun waitForNetworkAndResume() {
        if (networkCallback != null) return
        PlaybackEvents.report("网络中断，恢复后会自动继续播放")
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    stopNetworkWatch()
                    resumeCurrentWithFreshUrl()
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { stopNetworkWatch() }
    }

    private fun stopNetworkWatch() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
    }

    /** 退避一段时间后重新解析并续播（直链过期、网络抖动共用） */
    private fun scheduleResume(delayMs: Long, notice: String?) {
        if (notice != null) PlaybackEvents.report(notice)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            resumeCurrentWithFreshUrl()
        }
    }

    private fun resumeCurrentWithFreshUrl() {
        // 地址过期/断流要重新解析，先清掉「正在解析」标记，否则会被自己的守卫挡掉
        resolvingIndex = -1
        // playWhenReady 才代表用户的播放意图：出错时 isPlaying 已经是 false，
        // 用它判断会把「用户主动暂停」也误恢复成播放
        val wantedPlaying = player.playWhenReady
        resolveAndPlay(
            index = player.currentMediaItemIndex,
            autoplay = wantedPlaying,
            resumePositionMs = lastKnownPosition
        )
    }

    /** 按 mediaId 反查条目：队列 → 全库索引 → 已存进度。任一命中就能继续记进度 */
    private fun episodeOf(id: Long): Episode? =
        queue.firstOrNull { it.id == id }
            ?: app.indexStore.episodes.value.firstOrNull { it.id == id }
            ?: app.progressStore.get(id)?.let { Episode(id, it.date, it.title) }

    private fun saveProgress() {
        val player = mediaSession?.player ?: return
        val item = player.currentMediaItem ?: return
        val id = item.mediaId.toLongOrNull() ?: return
        // 原先依赖 playingEpisode，而它只在 onMediaItemTransition 里赋值；
        // 队列没对上（进程重启、外部切集）时这里会静默 return，进度从此不再落盘
        val episode = episodeOf(id) ?: return
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0 || position <= 0) return
        if (id == lastSavedId && position == lastSavedPosition) return
        lastSavedId = id
        lastSavedPosition = position
        lastKnownPosition = position
        val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: episode.title.ifBlank { episode.date }
        app.progressStore.save(
            id = id,
            positionMs = position,
            durationMs = duration,
            title = title,
            date = episode.date
        )
    }

    private val playerListener = object : Player.Listener {
        /**
         * 系统通知栏/车机的切集按钮会直接切换队列项，此时条目还没解析出音频地址，
         * 在这里补上解析；用户点播走的也是这条路（switch 后 currentMediaItem 无 uri）。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player.currentMediaItemIndex
            val episode = queue.getOrNull(index) ?: return
            currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: episode.title
            app.queueStore.save(queue, index)
            if (mediaItem?.localConfiguration?.uri?.toString() == PLACEHOLDER_URI &&
                resolvingIndex != index
            ) {
                resolveAndPlay(index)
            }
            // 重试计数不在这里清零：重解析成功后 replaceMediaItem 也会回调到这里，
            // 在那儿清零等于没设上限。真正恢复出声（STATE_READY）或用户重新点播才归零
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // 能正常出声音说明直链和网络都恢复了，两类重试计数归零
                urlRetries = 0
                stopNetworkWatch()
                return
            }
            if (playbackState == Player.STATE_ENDED) {
                val endedId = player.currentMediaItem?.mediaId?.toLongOrNull()
                val ended = endedId?.let { episodeOf(it) }
                if (ended != null) {
                    app.progressStore.markDone(ended.id, currentTitle, ended.date)
                    // 离线缓存的节目听完就删：车机存储有限，留着也没用了
                    app.downloads.remove(ended.id)
                }
                // 仅当结束的正是当前队列项且没有用户点播在途时才自动连播
                val canAdvance = !pendingLoad && endedId != null &&
                    endedId == queue.getOrNull(player.currentMediaItemIndex)?.id
                if (canAdvance && player.currentMediaItemIndex + 1 < queue.size) {
                    player.seekToDefaultPosition(player.currentMediaItemIndex + 1)
                    resolveAndPlay(player.currentMediaItemIndex)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 占位地址（还没解析出真实直链）导致的加载失败，不算真错误
            if (pendingLoad) return
            val code = error.errorCode
            when {
                code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    urlRetries < MAX_URL_RETRIES -> {
                    urlRetries++
                    // 退避后再重取直链：连续 405 时不要立刻再抓一遍站
                    scheduleResume(URL_RETRY_BACKOFF_MS * urlRetries, null)
                }
                // 行车途中最常见的是隧道/地库这类几十秒到几分钟的断网。
                // 固定次数的退避重试会在网络回来之前就耗尽，之后永远停在错误态，
                // 所以这里挂网络回调，恢复即续播
                code in NETWORK_ERRORS && player.playWhenReady ->
                    waitForNetworkAndResume()
                else -> PlaybackEvents.report("播放出错：${error.errorCodeName}")
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            app.progressStore.speed = playbackParameters.speed
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession
        if (session == null || !session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveProgress()
        retryJob?.cancel()
        stopNetworkWatch()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CMD_PLAY_QUEUE = "cn.loveq.podcast.action.PLAY_QUEUE"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_START = "start"
        const val SEP = "\u0001"

        /** 艺术家一栏固定显示节目名，通知栏因此显示「一些事一些情」而不是日期 */
        private const val PODCAST_NAME = "一些事一些情"

        /** 队列占位地址：ExoPlayer 不接受 uri 为空的条目，播放前会被真实直链替换 */
        private const val PLACEHOLDER_URI = "about:blank"
        private const val SEEK_STEP_MS = 30_000L
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val RESUME_EPSILON_MS = 30_000L
        private const val MAX_URL_RETRIES = 2
        private const val URL_RETRY_BACKOFF_MS = 2_000L

        /** 断网/超时这类可以等网络回来自己续上，和「直链过期」要分开处理 */
        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
    }

    /** 通知栏/锁屏的专辑图：直接用应用内的主持人封面 */
    private val artworkUri: Uri
        get() = Uri.parse("android.resource://$packageName/${R.drawable.hosts_cover}")
}

/** 把音量增强处理链挂进 DefaultAudioSink，其余沿用默认装配 */
private class BoostRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        // 处理链固定吃 16-bit PCM，避免车机上协商出 float/offload 路径导致 AudioTrack 建不出来
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf<AudioProcessor>(VolumeBoostProcessor()))
        .build()
}
