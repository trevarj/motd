package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatForegroundLifecycleGateTest {
    @Test
    fun homeAndResume_clearThenRestoreForegroundState() {
        val transitions = mutableListOf<String>()
        val gate = ChatForegroundLifecycleGate(
            onResume = { transitions += "resume" },
            onPause = { transitions += "pause" },
        )

        gate.onEvent(Lifecycle.Event.ON_RESUME)
        gate.onEvent(Lifecycle.Event.ON_PAUSE)
        gate.onEvent(Lifecycle.Event.ON_STOP)
        gate.onEvent(Lifecycle.Event.ON_RESUME)
        gate.dispose()

        assertEquals(listOf("resume", "pause", "resume", "pause"), transitions)
    }
}
