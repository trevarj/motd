package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class InviteUserSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val current = JoinedChannelRow(1, 7, "#current", null)
    private val other = JoinedChannelRow(2, 7, "#other", "https://example.test/other.png")

    @Test
    fun nickFirst_excludesOriginAndReturnsSelectedChannel() {
        var invited: Pair<Long, String>? = null
        compose.setContent {
            MotdTheme {
                InviteUserSheet(
                    target = InviteSheetTarget.Nick("alice", excludedBufferId = current.bufferId),
                    joinedChannels = listOf(current, other),
                    friends = emptySet(),
                    presence = emptyMap(),
                    memberNicks = emptyList(),
                    selfNick = "me",
                    identityRules = IrcIdentityRules(),
                    connected = true,
                    onDismiss = {},
                    onInvite = { channel, nick -> invited = channel.bufferId to nick },
                )
            }
        }

        compose.onNodeWithTag("invite_channel_1").assertDoesNotExist()
        compose.onNodeWithTag("invite_channel_2").performClick()
        compose.runOnIdle { assertEquals(2L to "alice", invited) }
    }

    @Test
    fun channelFirst_sortsFiltersAndAcceptsManualNickWhileBlockingPresentUsers() {
        var invited: Pair<Long, String>? = null
        val presence =
            mapOf(
                PresenceKey(7, "bob") to PresenceState.OFFLINE,
                PresenceKey(7, "zoe") to PresenceState.ONLINE,
            )
        compose.setContent {
            MotdTheme {
                InviteUserSheet(
                    target = InviteSheetTarget.Channel(current),
                    joinedChannels = listOf(current),
                    friends = linkedSetOf("bob", "me", "alice", "zoe"),
                    presence = presence,
                    memberNicks = listOf("alice"),
                    selfNick = "me",
                    identityRules = IrcIdentityRules(),
                    connected = true,
                    onDismiss = {},
                    onInvite = { channel, nick -> invited = channel.bufferId to nick },
                )
            }
        }

        val onlineTop =
            compose
                .onNodeWithTag("invite_friend_zoe")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val offlineTop =
            compose
                .onNodeWithTag("invite_friend_bob")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(onlineTop < offlineTop)
        compose.onNodeWithTag("invite_friend_zoe").performClick()
        compose.runOnIdle {
            assertEquals(1L to "zoe", invited)
            invited = null
        }
        compose.onNodeWithTag("invite_friend_me").assertIsNotEnabled()
        compose.onNodeWithTag("invite_friend_alice").assertIsNotEnabled()
        compose.onNodeWithTag("invite_nick_input").performTextInput("alice")
        compose.onNodeWithTag("invite_nick_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("invite_nick_input").performTextClearance()
        compose.onNodeWithTag("invite_nick_input").performTextInput("two nicks")
        compose.onNodeWithTag("invite_nick_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("invite_nick_input").performTextClearance()

        compose.onNodeWithTag("invite_nick_input").performTextInput("zo")
        compose.onNodeWithTag("invite_friend_zoe").assertExists()
        compose.onNodeWithTag("invite_friend_bob").assertDoesNotExist()

        compose.onNodeWithTag("invite_nick_input").performTextClearance()
        compose.onNodeWithTag("invite_nick_input").performTextInput("keyboard-user")
        compose.onNodeWithTag("invite_nick_input").performImeAction()
        compose.runOnIdle { assertEquals(1L to "keyboard-user", invited) }

        compose.runOnIdle { invited = null }
        compose.onNodeWithTag("invite_nick_input").performTextClearance()
        compose.onNodeWithTag("invite_nick_input").performTextInput("outsider")
        compose.onNodeWithTag("invite_nick_confirm").performClick()
        compose.runOnIdle { assertEquals(1L to "outsider", invited) }
    }

    @Test
    fun emptyAndDisconnectedStatesStayExplicit() {
        compose.setContent {
            MotdTheme {
                InviteUserSheet(
                    target = InviteSheetTarget.Nick("alice"),
                    joinedChannels = emptyList(),
                    friends = emptySet(),
                    presence = emptyMap(),
                    memberNicks = emptyList(),
                    selfNick = "me",
                    identityRules = IrcIdentityRules(),
                    connected = false,
                    onDismiss = {},
                    onInvite = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("invite_disconnected").assertExists()
        compose.onNodeWithTag("invite_no_channels").assertExists()
    }

    @Test
    fun disconnectedChannelRowsAreDisabled() {
        compose.setContent {
            MotdTheme {
                InviteUserSheet(
                    target = InviteSheetTarget.Nick("alice"),
                    joinedChannels = listOf(other),
                    friends = emptySet(),
                    presence = emptyMap(),
                    memberNicks = emptyList(),
                    selfNick = "me",
                    identityRules = IrcIdentityRules(),
                    connected = false,
                    onDismiss = {},
                    onInvite = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("invite_channel_2").assertIsNotEnabled()
    }
}
