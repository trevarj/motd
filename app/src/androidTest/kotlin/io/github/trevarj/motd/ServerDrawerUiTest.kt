package io.github.trevarj.motd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

        for (networkId in 1L..3L) {
            compose.onNodeWithTag("drawer_network_icon_$networkId", useUnmergedTree = true)
                .assertIsDisplayed()
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
