package io.github.trevarj.motd.ui.channelinfo

import androidx.activity.ComponentDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.client.ChannelBanEntry
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChannelBanSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val room = BufferEntity(1, 1, "+room", "+room", BufferType.CHANNEL, joined = true)
    private val entry = ChannelBanEntry("\$a:MiX[ed]", "op!~user@host", 1_700_000_000)

    @Test
    fun joinedNonOperatorCanInspectBansWithoutLoadingAgainOnRecomposition() {
        var state by mutableStateOf(ChannelInfoUiState(buffer = room.copy(joined = false), connected = true))
        var sheet by mutableStateOf<ChannelBanSheetState>(ChannelBanSheetState.Closed)
        var opens = 0
        var closes = 0
        showChannelInfo(
            state = { state },
            banList = { sheet },
            onOpen = {
                opens++
                sheet = ChannelBanSheetState.Loading
            },
            onClose = {
                closes++
                sheet = ChannelBanSheetState.Closed
            },
        )

        val open = compose.onNodeWithTag("channelinfo_ban_list_open")
        open.assertDoesNotExist()
        compose.runOnIdle { state = state.copy(buffer = room, connected = false) }
        open.assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(connected = true) }
        open.assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("channelinfo_ban_list_status").assertTextEquals("Loading active bans…")
        compose.runOnIdle { sheet = ChannelBanSheetState.Ready(listOf(entry)) }
        compose.onNodeWithTag("channelinfo_ban_list_mask").assertTextEquals(entry.mask)
        compose.onNodeWithText("Set by ${entry.setter}").assertIsDisplayed()
        compose.onNodeWithText("Set on ", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("channelinfo_ban_list_remove").assertDoesNotExist()
        compose.onNodeWithTag("channelinfo_controls_section").assertDoesNotExist()
        compose.runOnIdle { sheet = ChannelBanSheetState.Ready(listOf(entry.copy(setter = null, setAtEpochSeconds = null))) }
        compose.onNodeWithText("Set by ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Set on ", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("channelinfo_ban_list_close").performClick()
        compose.onNodeWithTag("channelinfo_ban_list_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(1, 1), listOf(opens, closes)) }
    }

    @Test
    fun completeEmptyAndReadFailuresAreDistinctAndRefreshRequiresConnection() {
        var state by mutableStateOf(ChannelInfoUiState(buffer = room, connected = true))
        var sheet by mutableStateOf<ChannelBanSheetState>(ChannelBanSheetState.Ready(emptyList()))
        var refreshes = 0
        showChannelInfo(state = { state }, banList = { sheet }, onRefresh = { refreshes++ })

        val status = compose.onNodeWithTag("channelinfo_ban_list_status")
        val refresh = compose.onNodeWithTag("channelinfo_ban_list_refresh")
        status.assertTextEquals("No active bans")
        compose.runOnIdle { sheet = ChannelBanSheetState.Denied }
        status.assertTextEquals("The server denied access to the ban list.")
        refresh.assertIsEnabled().performClick()
        compose.runOnIdle { sheet = ChannelBanSheetState.Incomplete }
        status.assertTextEquals("The server did not send a complete ban list.")
        refresh.assertIsEnabled().performClick()
        compose.runOnIdle {
            sheet = ChannelBanSheetState.Unavailable
            state = state.copy(connected = false)
        }
        status.assertTextEquals("The ban list is unavailable.")
        refresh.assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(connected = true) }
        status.assertTextEquals("The ban list is unavailable.")
        refresh.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(3, refreshes) }
    }

    @Test
    fun removeConfirmsLiteralMaskRechecksPrivilegeAndKeepsRowUntilServerRefresh() {
        var state by mutableStateOf(ChannelInfoUiState(buffer = room, connected = true, canModerate = true, canRemoveActiveBan = true))
        val ready = ChannelBanSheetState.Ready(listOf(entry))
        var sheet by mutableStateOf<ChannelBanSheetState>(ready)
        val removed = mutableListOf<String>()
        showChannelInfo(
            state = { state },
            banList = { sheet },
            onRemove = { mask ->
                removed += mask
                sheet = ready.copy(removingMask = mask)
            },
        )

        val remove = compose.onNodeWithTag("channelinfo_ban_list_remove")
        val confirm = compose.onNodeWithTag("channelinfo_ban_list_confirm")
        val dialog = compose.onNodeWithTag("channelinfo_ban_list_confirm_dialog")
        remove.performClick()
        compose.onNodeWithTag("channelinfo_ban_list_confirm_mask").assertTextEquals(entry.mask)
        compose.runOnIdle { state = state.copy(canRemoveActiveBan = false) }
        confirm.assertIsNotEnabled()
        compose.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            assertEquals(emptyList<String>(), removed)
        }
        dialog.assertDoesNotExist()
        compose.runOnIdle { state = state.copy(canRemoveActiveBan = true) }
        remove.performClick()
        confirm.assertIsEnabled().performClick()
        dialog.assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(entry.mask), removed) }
        compose.onNodeWithTag("channelinfo_ban_list_mask").assertTextEquals(entry.mask)
        remove.assertIsNotEnabled()
        compose.onNodeWithTag("channelinfo_ban_list_refresh").assertIsNotEnabled()
        compose.runOnIdle { sheet = ready.copy(removalFailed = true) }
        compose.onNodeWithTag("channelinfo_ban_list_remove_failed").assertIsDisplayed()
        compose.onNodeWithTag("channelinfo_ban_list_mask").assertTextEquals(entry.mask)
        remove.assertIsEnabled().performClick()
        confirm.performClick()
        compose.runOnIdle { sheet = ChannelBanSheetState.Ready(emptyList()) }
        compose.onNodeWithTag("channelinfo_ban_list_mask").assertDoesNotExist()
        compose.onNodeWithTag("channelinfo_ban_list_status").assertTextEquals("No active bans")
        compose.runOnIdle { assertEquals(listOf(entry.mask, entry.mask), removed) }
    }

    private fun showChannelInfo(
        state: () -> ChannelInfoUiState,
        banList: () -> ChannelBanSheetState,
        onOpen: () -> Unit = {},
        onClose: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onRemove: (String) -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChannelInfoContent(
                    state = state(),
                    onBack = {},
                    onSetPinned = {},
                    onSetMuted = {},
                    onLeave = {},
                    banList = banList(),
                    onOpenBanList = onOpen,
                    onCloseBanList = onClose,
                    onRefreshBanList = onRefresh,
                    onRemoveActiveBan = onRemove,
                )
            }
        }
    }
}
