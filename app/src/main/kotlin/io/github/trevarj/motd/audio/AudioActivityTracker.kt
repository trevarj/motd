package io.github.trevarj.motd.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioActivityTracker
    @Inject
    constructor() {
        private val _recording = MutableStateFlow(false)
        val recording: StateFlow<Boolean> = _recording.asStateFlow()
        internal var stopIncomingReading: (() -> Unit)? = null

        fun stopReadingBeforeRecording() {
            stopIncomingReading?.invoke()
        }

        fun setRecording(active: Boolean) {
            _recording.value = active
        }
    }
