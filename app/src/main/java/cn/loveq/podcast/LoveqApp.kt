package cn.loveq.podcast

import android.app.Application
import cn.loveq.podcast.data.DownloadManager
import cn.loveq.podcast.data.EpisodeIndexStore
import cn.loveq.podcast.data.LoveqRepository
import cn.loveq.podcast.data.ProgressStore
import cn.loveq.podcast.data.RateLimitGuard
import cn.loveq.podcast.playback.QueueStore
import cn.loveq.podcast.playback.VolumeBoost

class LoveqApp : Application() {

    lateinit var rateLimitGuard: RateLimitGuard
        private set
    lateinit var repository: LoveqRepository
        private set
    lateinit var progressStore: ProgressStore
        private set
    lateinit var indexStore: EpisodeIndexStore
        private set
    lateinit var queueStore: QueueStore
        private set
    lateinit var downloads: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // 限流守卫要先于 repository/indexStore 建好：三者共用同一份冷却状态，
        // 保证「被官网拒过」这件事在整个进程里只记一次、也只等一次
        rateLimitGuard = RateLimitGuard(this)
        repository = LoveqRepository(rateLimitGuard)
        progressStore = ProgressStore(this)
        indexStore = EpisodeIndexStore(this, rateLimitGuard)
        queueStore = QueueStore(this)
        downloads = DownloadManager(this, repository)
        VolumeBoost.apply(progressStore.boostPercent)
    }
}
