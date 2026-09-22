package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.prefs.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionManagerTypingPrivacyTest {
    @Test
    fun connectionManagerTypingGateSuppressesEveryTypingStateIncludingDone() =
        runTest {
            val sent = mutableListOf<String>()
            listOf("active", "paused", "done").forEach { state ->
                sendTypingWhenAllowed(Settings(sendTypingIndicators = false), state) { sent += it }
            }
            assertEquals(emptyList<String>(), sent)

            listOf("active", "paused", "done").forEach { state ->
                sendTypingWhenAllowed(Settings(sendTypingIndicators = true), state) { sent += it }
            }
            assertEquals(listOf("active", "paused", "done"), sent)
        }
}
