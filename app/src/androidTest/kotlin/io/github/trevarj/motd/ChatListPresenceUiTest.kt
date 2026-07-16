package io.github.trevarj.motd

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatListPresenceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun queryPresence_usesBadgeAndRowStateDescription() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Column {
                    PresenceState.entries.forEachIndexed { index, presence ->
                        ChatListRowItem(
                            row = queryRow().copy(bufferId = index + 1L),
                            showNetworkChip = false,
                            onClick = {},
                            onLongClick = {},
                            presence = presence,
                        )
                    }
                }
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((index, presence) in PresenceState.entries.withIndex()) {
            val description = context.getString(
                when (presence) {
                    PresenceState.ONLINE -> R.string.presence_online
                    PresenceState.OFFLINE -> R.string.presence_offline
                    PresenceState.UNKNOWN -> R.string.presence_unknown
                },
            )
            compose.onNodeWithTag("chatlist_presence_${presence.name.lowercase()}", useUnmergedTree = true)
                .assertIsDisplayed()
            compose.onNodeWithTag("chatlist_row_${index + 1}")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
        }
    }

    @Test
    fun untrackedQuery_hasNoPresenceBadge() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListRowItem(
                    row = queryRow(),
                    showNetworkChip = false,
                    onClick = {},
                    onLongClick = {},
                    presence = null,
                )
            }
        }

        assertNoPresenceBadge()
    }

    @Test
    fun channel_ignoresPresenceState() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListRowItem(
                    row = queryRow().copy(displayName = "#motd", type = BufferType.CHANNEL),
                    showNetworkChip = false,
                    onClick = {},
                    onLongClick = {},
                    presence = PresenceState.ONLINE,
                )
            }
        }

        assertNoPresenceBadge()
    }

    private fun assertNoPresenceBadge() {
        listOf("online", "offline", "unknown").forEach { state ->
            assertEquals(
                0,
                compose.onAllNodesWithTag("chatlist_presence_$state", useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    private fun queryRow() = ChatListRow(
        bufferId = 1,
        networkId = 1,
        networkName = "Libera",
        displayName = "alice",
        type = BufferType.QUERY,
        pinned = false,
        muted = false,
        lastMessageText = "hello",
        lastMessageSender = "alice",
        lastMessageTime = 1L,
        unreadCount = 0,
        mentionCount = 0,
    )
}
