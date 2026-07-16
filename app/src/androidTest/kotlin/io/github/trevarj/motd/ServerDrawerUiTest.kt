package io.github.trevarj.motd

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chatlist.DrawerRow
import io.github.trevarj.motd.ui.chatlist.ServerDrawerContent
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test

class ServerDrawerUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ircNetworkIcons_remainVisibleAcrossConnectionStates() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = listOf(
                        drawerRow(1, IrcClientState.Ready("alice", emptySet(), emptyMap())),
                        drawerRow(2, IrcClientState.Connecting),
                        drawerRow(3, IrcClientState.Disconnected),
                    ),
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedStates = mapOf(
            1L to context.getString(R.string.drawer_state_connected),
            2L to context.getString(R.string.drawer_state_disconnected),
            3L to context.getString(R.string.drawer_state_disconnected),
        )
        for ((networkId, state) in expectedStates) {
            compose.onNodeWithTag("drawer_network_icon_$networkId", useUnmergedTree = true)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        }
    }

    private fun drawerRow(networkId: Long, state: IrcClientState) = DrawerRow(
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
