package cn.loveq.podcast.playback

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

object PlaybackEvents {
    val errors = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun tryEmit(message: String) {
        errors.tryEmit(message)
    }
}
