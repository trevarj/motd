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
    fun inviteToChannelAction_isExposedForOtherUsers() {
        var invites = 0
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = "bob",
                    isSelf = false,
                    isFriend = false,
                    isFool = false,
                    canModerate = false,
                    whois = null,
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
                )
            }
        }

        compose.onNodeWithTag("nick_sheet_invite_to_channel").performClick()
        compose.runOnIdle { assertEquals(1, invites) }
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
