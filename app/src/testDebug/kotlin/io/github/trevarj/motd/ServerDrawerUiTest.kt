package io.github.trevarj.motd

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chatlist.DrawerRow
import io.github.trevarj.motd.ui.chatlist.ServerDrawerContent
import io.github.trevarj.motd.ui.theme.MotdTheme
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
class ServerDrawerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ircNetworkIcons_remainVisibleAcrossConnectionStates() {
        var scanned = false
        var contactInviteNetworkId: Long? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows =
                        listOf(
                            drawerRow(1, IrcClientState.Ready("alice", emptySet(), emptyMap())),
                            drawerRow(2, IrcClientState.Connecting),
                            drawerRow(3, IrcClientState.Disconnected),
                        ),
                    selectedNetworkId = 1,
                    allUnread = 0,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    globalFeedEnabled = true,
                    onMarkAllRead = {},
                    onCreateContactInvite = { contactInviteNetworkId = it },
                    onScanInvite = { scanned = true },
                )
            }
        }

        val createInvite = compose.onNodeWithTag("drawer_create_contact_invite").assertIsDisplayed()
        val scanInvite = compose.onNodeWithTag("drawer_scan_invite").assertIsDisplayed()
        assertTrue(createInvite.fetchSemanticsNode().boundsInRoot.top < scanInvite.fetchSemanticsNode().boundsInRoot.top)
        createInvite.performClick()
        assertTrue(contactInviteNetworkId == 1L)
        scanInvite.performClick()
        assertTrue(scanned)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedStates =
            mapOf(
                1L to context.getString(R.string.drawer_state_connected),
                2L to context.getString(R.string.drawer_state_disconnected),
                3L to context.getString(R.string.drawer_state_disconnected),
            )
        for ((networkId, state) in expectedStates) {
            compose
                .onNodeWithTag("drawer_network_icon_$networkId", useUnmergedTree = true)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        }
        compose.onNodeWithTag("drawer_open_feed").assertIsDisplayed()
    }

    /** The feed lives behind the Global Feed lab, so its row is absent until the lab is on. */
    @Test
    fun feedRow_isAbsentWhileTheGlobalFeedLabIsOff() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = listOf(drawerRow(1, IrcClientState.Ready("alice", emptySet(), emptyMap()))),
                    selectedNetworkId = null,
                    allUnread = 0,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    onMarkAllRead = {},
                )
            }
        }

        compose.onNodeWithTag("drawer_open_feed").assertDoesNotExist()
    }

    private fun drawerRow(
        networkId: Long,
        state: IrcClientState,
    ) = DrawerRow(
        networkId = networkId,
        name = "Network $networkId",
        role = NetworkRole.DIRECT,
        depth = 0,
        state = state,
        nick = (state as? IrcClientState.Ready)?.nick,
        unread = 0,
        mentions = 0,
    )
}
