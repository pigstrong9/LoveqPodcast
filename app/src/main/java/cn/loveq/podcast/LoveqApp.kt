package cn.loveq.podcast

import android.app.Application
import cn.loveq.podcast.data.EpisodeIndexStore
import cn.loveq.podcast.data.LoveqRepository
import cn.loveq.podcast.data.ProgressStore

class LoveqApp : Application() {

    lateinit var repository: LoveqRepository
        private set
    lateinit var progressStore: ProgressStore
        private set
    lateinit var indexStore: EpisodeIndexStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LoveqRepository()
        progressStore = ProgressStore(this)
        indexStore = EpisodeIndexStore(this)
    }
}
