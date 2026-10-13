package cn.loveq.podcast.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import cn.loveq.podcast.data.Episode
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
    private var queueIndex = -1
    private var playingEpisode: Episode? = null
    private var currentTitle = ""
    private var urlRetries = 0
    private var currentJob: Job? = null
    private var lastSavedId: Long? = null
    private var lastSavedPosition = -1L
    private var pendingLoad = false

    override fun onCreate() {
        super.onCreate()
        app = applicationContext as LoveqApp

        val cache = MediaCache.get(this)
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(CacheKeyFactory { dataSpec -> MediaCache.keyOf(dataSpec.uri) })
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

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
        queue = items
        loadAndPlay(startIndex)
    }

    private fun loadAndPlay(index: Int) {
        queueIndex = index
        urlRetries = 0
        val episode = queue.getOrNull(index) ?: return
        pendingLoad = true
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                val audio = app.repository.fetchAudio(episode.id)
                setItemAndPlay(episode, audio.url, audio.title ?: episode.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingLoad = false
                PlaybackEvents.tryEmit("加载节目失败，请检查网络后重试")
            }
        }
    }

    private fun setItemAndPlay(episode: Episode, url: String, title: String) {
        pendingLoad = false
        playingEpisode = episode
        currentTitle = title
        val item = MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(episode.date)
                    .build()
            )
            .build()
        MediaCache.setActiveKey(item.localConfiguration?.uri)
        player.setMediaItem(item)
        player.prepare()

        val saved = app.progressStore.get(episode.id)
        val startPos = when {
            saved == null || saved.done -> 0L
            saved.durationMs > 0 && saved.positionMs >= saved.durationMs - RESUME_EPSILON_MS -> 0L
            saved.positionMs > RESUME_EPSILON_MS -> saved.positionMs
            else -> 0L
        }
        if (startPos > 0) player.seekTo(startPos)
        player.play()
    }

    private fun resumeCurrentWithFreshUrl() {
        val episode = queue.getOrNull(queueIndex) ?: return
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                val audio = app.repository.fetchAudio(episode.id)
                setItemAndPlay(episode, audio.url, audio.title ?: episode.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackEvents.tryEmit("节目地址已过期且刷新失败，请稍后重试")
            }
        }
    }

    private fun saveProgress() {
        val player = mediaSession?.player ?: return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val episode = playingEpisode?.takeIf { it.id == id } ?: return
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0 || position <= 0) return
        if (id == lastSavedId && position == lastSavedPosition) return
        lastSavedId = id
        lastSavedPosition = position
        app.progressStore.save(
            id = id,
            positionMs = position,
            durationMs = duration,
            title = currentTitle,
            date = episode.date
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val endedId = player.currentMediaItem?.mediaId?.toLongOrNull()
                val ended = playingEpisode?.takeIf { it.id == endedId }
                if (ended != null) {
                    app.progressStore.markDone(ended.id, currentTitle, ended.date)
                }
                // 仅当结束的正是当前队列项且没有用户点播在途时才自动连播
                val canAdvance = !pendingLoad && endedId != null &&
                    endedId == queue.getOrNull(queueIndex)?.id
                if (canAdvance && queueIndex + 1 < queue.size) {
                    loadAndPlay(queueIndex + 1)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val httpError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            if (httpError && urlRetries < MAX_URL_RETRIES) {
                urlRetries++
                resumeCurrentWithFreshUrl()
            } else {
                PlaybackEvents.tryEmit("播放出错：${error.errorCodeName}")
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
        private const val SEEK_STEP_MS = 30_000L
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val RESUME_EPSILON_MS = 30_000L
        private const val MAX_URL_RETRIES = 2
    }
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
