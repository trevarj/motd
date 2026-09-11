package io.github.trevarj.motd

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
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
class ChatListPresenceUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

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

        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((index, presence) in PresenceState.entries.withIndex()) {
            val description =
                context.getString(
                    when (presence) {
                        PresenceState.ONLINE -> R.string.presence_online
                        PresenceState.OFFLINE -> R.string.presence_offline
                        PresenceState.UNKNOWN -> R.string.presence_unknown
                    },
                )
            compose
                .onNodeWithTag("chatlist_presence_${presence.name.lowercase()}", useUnmergedTree = true)
                .assertIsDisplayed()
            compose
                .onNodeWithTag("chatlist_row_${index + 1}")
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

    @Test
    fun dickordRow_defaultsKeepCleanLabelAndBadge() {
        setDickordRow(enabled = true)

        compose.onAllNodesWithText("#me.chat.alice").assertCountEquals(1)
        compose.onAllNodesWithText("Alice").assertCountEquals(1)
        compose.onAllNodesWithText("Discord").assertCountEquals(1)
        compose.onAllNodesWithText("#discord.me.chat.alice").assertCountEquals(0)
        compose.onAllNodesWithText("Alice/discord").assertCountEquals(0)
    }

    @Test
    fun dickordRow_displayTitleAndBadgeOverridesOnlyVisibleChrome() {
        setDickordRow(enabled = true, displayTitle = "Alice Smith", showDickordBadge = false)

        compose.onAllNodesWithText("Alice Smith").assertCountEquals(1)
        compose.onAllNodesWithText("Alice").assertCountEquals(1)
        compose.onAllNodesWithText("Discord").assertCountEquals(0)
        compose.onAllNodesWithText("#discord.me.chat.alice").assertCountEquals(0)
        compose.onAllNodesWithText("Alice/discord").assertCountEquals(0)
    }

    @Test
    fun dickordRow_disabledKeepsRawDefaults() {
        setDickordRow(enabled = false)

        compose.onAllNodesWithText("#discord.me.chat.alice").assertCountEquals(1)
        compose.onAllNodesWithText("Alice/discord").assertCountEquals(1)
        compose.onAllNodesWithText("Discord").assertCountEquals(0)
    }

    private fun setDickordRow(
        enabled: Boolean,
        displayTitle: String? = null,
        showDickordBadge: Boolean = true,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDickordLabsEnabled provides enabled) {
                MotdTheme(dynamicColor = false) {
                    ChatListRowItem(
                        row = dickordRow(bufferId = 1),
                        showNetworkChip = false,
                        onClick = {},
                        onLongClick = {},
                        displayTitle = displayTitle,
                        showDickordBadge = showDickordBadge,
                    )
                }
            }
        }
    }

    private fun dickordRow(
        bufferId: Long,
        pinned: Boolean = false,
        folderId: Long? = null,
    ) = queryRow().copy(
        bufferId = bufferId,
        displayName = "#discord.me.chat.alice",
        type = BufferType.CHANNEL,
        pinned = pinned,
        folderId = folderId,
        lastMessageSender = "Alice/discord",
    )

    private fun assertNoPresenceBadge() {
        listOf("online", "offline", "unknown").forEach { state ->
            assertEquals(
                0,
                compose
                    .onAllNodesWithTag("chatlist_presence_$state", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
        }
    }

    private fun queryRow() =
        ChatListRow(
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
