package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.ui.components.dayLabel
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class TimelineSenderContextUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun manualTravelReplacesSenderWithDateAndRestoresSenderAfterward() {
        val senderTag = "chat_timeline_sender"
        val dateTag = "chat_timeline_date"
        val userScrolling = mutableStateOf(false)
        val snapshotReady = mutableStateOf(true)
        val message =
            MessageEntity(
                id = 1,
                bufferId = 1,
                msgid = null,
                serverTime = 1_600_000_000_000L,
                sender = "alice",
                kind = MessageKind.PRIVMSG,
                text = "message",
                dedupKey = "d1",
            )
        compose.setContent {
            val listState = rememberLazyListState()
            val rowHeight = 160.dp
            val heightPx = with(LocalDensity.current) { rowHeight.roundToPx() }
            val metrics =
                remember(heightPx) {
                    val sender = TimelineSender("alice", "alice", "alice", null, false)
                    mapOf(
                        message.id to
                            TimelineRowContentMetrics().apply {
                                senderRow = TimelineSenderRow(sender, opensGroup = false, hasNick = false, hasLeadingDecoration = false)
                                senderBody = 0..heightPx
                            },
                    )
                }
            MaterialTheme {
                CompositionLocalProvider(LocalAvatarStyle provides AvatarStyle.NONE) {
                    Box {
                        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                            item(key = message.id) { Spacer(Modifier.height(rowHeight)) }
                        }
                        TimelineSenderContext(
                            listState = listState,
                            metrics = metrics,
                            networkId = null,
                            readTopHostHeightPx = { 0 },
                            readBottomHostHeightPx = { 0 },
                            precedingHeightPx = 0,
                            edgeSlackPx = 0,
                            userScrolling = userScrolling.value,
                            visibleMessageAt = { index -> message.takeIf { index == 0 && snapshotReady.value } },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag(senderTag).assertIsDisplayed()
        compose.onNodeWithTag(dateTag).assertDoesNotExist()

        compose.runOnIdle { userScrolling.value = true }
        compose.onNodeWithTag(dateTag).assertIsDisplayed()
        compose.onNodeWithText(dayLabel(message.serverTime)).assertIsDisplayed()
        compose.onNodeWithTag(senderTag).assertDoesNotExist()

        compose.runOnIdle { snapshotReady.value = false }
        compose.onNodeWithTag(dateTag).assertDoesNotExist()
        compose.onNodeWithTag(senderTag).assertDoesNotExist()

        compose.runOnIdle { userScrolling.value = false }
        compose.onNodeWithTag(senderTag).assertIsDisplayed()
        compose.onNodeWithTag(dateTag).assertDoesNotExist()
    }
}
