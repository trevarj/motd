package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.prefs.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TypingPrivacyTest {
    @Test
    fun hidingTypingIndicatorsImmediatelyRemovesTrackedPeers() {
        assertEquals(emptyList<String>(), visibleTypingNicks(listOf("alice", "bob"), showTypingIndicators = false))
        assertEquals(listOf("alice", "bob"), visibleTypingNicks(listOf("alice", "bob"), showTypingIndicators = true))
    }

    @Test
    fun incomingTypingFlowHidesExistingPeersWhenPreferenceFlips() =
        runTest {
            val tracked = MutableStateFlow(listOf("alice"))
            val settings = MutableStateFlow(Settings(showTypingIndicators = true))
            val visible = visibleTypingNicks(tracked, settings)

            assertEquals(listOf("alice"), visible.first())
            val hidden = async { visible.first { it.isEmpty() } }
            settings.value = settings.value.copy(showTypingIndicators = false)
            assertEquals(emptyList<String>(), hidden.await())
        }
}
