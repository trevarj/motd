package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.components.ReasonPresetChips
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NickSheetKickUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun defaultIdentity_exposesExistingActions() {
        val rawNick = "Alice/discord"
        var invites = 0
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = rawNick,
                    isSelf = false,
                    isFriend = false,
                    isFool = false,
                    canModerate = true,
                    whois = WhoisInfo(nick = rawNick, realname = "Alice Example"),
                    onDismiss = {},
                    onMessage = {},
                    onMention = {},
                    onToggleFriend = {},
                    onToggleFool = {},
                    onInviteToChannel = { invites++ },
                    onOp = {},
                    onVoice = {},
                    onKick = {},
                    onBan = { _, _ -> },
                    canEditConversationAvatar = true,
                )
            }
        }

        compose.onNodeWithText(rawNick).assertExists()
        compose.onNodeWithText("Alice Example").assertExists()
        listOf(
            "Message",
            "Mention",
            "Add to friends",
            "Add to fools",
            "Ignore on this network",
            "Give op",
            "Take op",
            "Give voice",
            "Take voice",
            "Kick",
            "Ban",
        ).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithTag("nick_sheet_avatar").assertExists()
        compose.onNodeWithTag("nick_sheet_edit_avatar").assertExists()
        compose.onNodeWithTag("nick_sheet_invite_to_channel").performClick()
        compose.runOnIdle { assertEquals(1, invites) }
    }

    @Test
    fun relayIdentity_exposesOnlyUsableActionsWithRawCallbacks() {
        val rawNick = "Alice/discord"
        val callbacks = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = rawNick,
                    isSelf = false,
                    isFriend = false,
                    isFool = false,
                    canModerate = true,
                    whois = WhoisInfo(nick = rawNick, realname = "Hidden relay WHOIS"),
                    onDismiss = {},
                    onMessage = { callbacks += "message" to rawNick },
                    onMention = { callbacks += "mention" to rawNick },
                    onToggleFriend = { callbacks += "friend" to rawNick },
                    onToggleFool = { callbacks += "fool" to rawNick },
                    onIgnoreNetwork = { callbacks += "ignore" to rawNick },
                    onInviteToChannel = { callbacks += "invite" to rawNick },
                    onOp = { callbacks += "op" to rawNick },
                    onVoice = { callbacks += "voice" to rawNick },
                    onKick = { callbacks += "kick" to rawNick },
                    onBan = { _, _ -> callbacks += "ban" to rawNick },
                    canEditConversationAvatar = true,
                    onEditConversationAvatar = { callbacks += "avatar" to rawNick },
                    relayIdentity = true,
                )
            }
        }

        compose.onNodeWithText("Alice").assertExists()
        compose.onNodeWithText(rawNick).assertDoesNotExist()
        compose.onNodeWithText("Discord relay identity").assertExists()
        listOf("Mention", "Add to friends", "Add to fools", "Ignore on this network").forEach {
            compose.onNodeWithText(it).performClick()
        }
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "mention" to rawNick,
                    "friend" to rawNick,
                    "fool" to rawNick,
                    "ignore" to rawNick,
                ),
                callbacks,
            )
        }

        compose.onNodeWithText("Hidden relay WHOIS").assertDoesNotExist()
        compose.onNodeWithTag("nick_sheet_avatar").assertDoesNotExist()
        compose.onNodeWithTag("nick_sheet_edit_avatar").assertDoesNotExist()
        compose.onNodeWithTag("nick_sheet_invite_to_channel").assertDoesNotExist()
        listOf(
            "Message",
            "Invite to channel",
            "Edit chat avatar",
            "Give op",
            "Take op",
            "Give voice",
            "Take voice",
            "Kick",
            "Ban",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun inviteToChannelAction_isHiddenForSelf() {
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = "me",
                    isSelf = true,
                    isFriend = false,
                    isFool = false,
                    canModerate = false,
                    whois = null,
                    onDismiss = {},
                    onMessage = {},
                    onMention = {},
                    onToggleFriend = {},
                    onToggleFool = {},
                    onInviteToChannel = {},
                    onOp = {},
                    onVoice = {},
                    onKick = {},
                    onBan = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("nick_sheet_invite_to_channel").assertDoesNotExist()
    }

    @Test
    fun kickDialog_presetChipSelectsTheReason() {
        var reason = ""
        compose.setContent {
            MotdTheme {
                ReasonPresetChips(
                    current = reason,
                    onSelect = { reason = it },
                    tagPrefix = "nick_sheet_kick_chip",
                )
            }
        }

        compose.onNodeWithTag("nick_sheet_kick_chip_flooding").performClick()
        assertEquals("Flooding", reason)
    }
}
