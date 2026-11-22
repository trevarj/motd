package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListOnboardingTest {
    @Test
    fun freshEmptyInstallOpensOnboardingAfterLoading() {
        val state = ChatListState(loading = false)
        assertTrue(shouldOpenOnboarding(state))
        assertFalse(state.isNormalChatListReady)
    }

    @Test
    fun pendingInviteSuppressesManualOnboardingRace() {
        val state = ChatListState(loading = false)
        assertFalse(shouldOpenOnboarding(state, suppressed = true))
        assertFalse(state.isNormalChatListReady)
    }

    @Test
    fun completedOnboardingKeepsEmptyMainScreen() {
        val state = ChatListState(loading = false, onboardingComplete = true)
        assertFalse(shouldOpenOnboarding(state))
        assertTrue(state.isNormalChatListReady)
    }

    @Test
    fun configuredOrStillLoadingStateDoesNotOpenOnboarding() {
        val stillLoading = ChatListState()
        assertFalse(shouldOpenOnboarding(stillLoading))
        assertFalse(stillLoading.isNormalChatListReady)
        val configured =
            ChatListState(
                networks =
                    listOf(
                        NetworkEntity(
                            name = "Libera",
                            role = NetworkRole.DIRECT,
                            host = "irc.example",
                            port = 6697,
                            nick = "me",
                            username = "me",
                            realname = "Me",
                        ),
                    ),
                loading = false,
            )
        assertFalse(shouldOpenOnboarding(configured))
        assertTrue(configured.isNormalChatListReady)
    }
}
