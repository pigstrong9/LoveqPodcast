package cn.loveq.podcast

import android.app.Application
import cn.loveq.podcast.data.ApkInstaller
import cn.loveq.podcast.data.DownloadManager
import cn.loveq.podcast.data.EpisodeIndexStore
import cn.loveq.podcast.data.FixtureReleaseSource
import cn.loveq.podcast.data.GithubReleases
import cn.loveq.podcast.data.LoveqRepository
import cn.loveq.podcast.data.PrefsSourceMemory
import cn.loveq.podcast.data.ProgressStore
import cn.loveq.podcast.data.RateLimitGuard
import cn.loveq.podcast.data.ReleaseSource
import cn.loveq.podcast.data.UpdateCenter
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
    internal lateinit var updates: UpdateCenter
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

        // 夹具构建整个换掉 ReleaseSource，而不是给 GithubReleases 塞一个假基址：
        // 这样真实现里没有一行测试代码，release 里 R8 把这条分支连夹具类一起删掉
        // 「上次哪个源成功了」的两处（元数据 / 资产）共用同一份 prefs 访问
        val sourceMemory = PrefsSourceMemory(this)
        val releaseSource: ReleaseSource =
            if (BuildConfig.UPDATE_USE_FIXTURE) FixtureReleaseSource(this)
            else GithubReleases(sourceMemory)

        updates = UpdateCenter(
            context = this,
            source = releaseSource,
            installer = ApkInstaller(this),
            memory = sourceMemory
        )

        VolumeBoost.apply(progressStore.boostPercent)
    }
}
