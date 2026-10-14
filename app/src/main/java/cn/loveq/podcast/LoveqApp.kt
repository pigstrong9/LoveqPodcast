package cn.loveq.podcast

import android.app.Application
import cn.loveq.podcast.data.EpisodeIndexStore
import cn.loveq.podcast.data.LoveqRepository
import cn.loveq.podcast.data.ProgressStore
import cn.loveq.podcast.playback.QueueStore
import cn.loveq.podcast.playback.VolumeBoost

class LoveqApp : Application() {

    lateinit var repository: LoveqRepository
        private set
    lateinit var progressStore: ProgressStore
        private set
    lateinit var indexStore: EpisodeIndexStore
        private set
    lateinit var queueStore: QueueStore
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        repository = LoveqRepository()
        progressStore = ProgressStore(this)
        indexStore = EpisodeIndexStore(this)
        queueStore = QueueStore(this)
        VolumeBoost.apply(progressStore.boostPercent)
    }
}
