package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.ChatListSwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatListSwipeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `swipe requires sixty five percent of row width`() {
        assertEquals(130f, chatListSwipePositionalThreshold(200f), 0f)
        assertEquals(0.65f, CHAT_LIST_SWIPE_THRESHOLD_FRACTION, 0f)
        assertFalse(isChatListSwipePastThreshold(-129f, 200f))
        assertTrue(isChatListSwipePastThreshold(-130f, 200f))
    }

    @Test
    fun `fast short flick does not act but dragging through threshold does`() {
        var actions = 0
        val row =
            ChatListRow(
                bufferId = 1,
                networkId = 1,
                networkName = "network",
                displayName = "chat",
                type = BufferType.QUERY,
                pinned = false,
                muted = false,
                lastMessageText = null,
                lastMessageSender = null,
                lastMessageTime = null,
                unreadCount = 0,
                mentionCount = 0,
            )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    SelectableChatListRow(
                        row = row,
                        presence = null,
                        isFriend = false,
                        multiNetwork = false,
                        onOpenBuffer = {},
                        archiveMode = false,
                        swipeAction = ChatListSwipeAction.MARK_READ,
                        selected = false,
                        active = false,
                        selectionActive = false,
                        onToggleSelection = {},
                        onStartSelection = {},
                        onSwipe = { actions++ },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput {
            val y = center.y
            down(Offset(width * 0.95f, y))
            moveTo(Offset(width * 0.7f, y), delayMillis = 16L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(0, actions)

        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput {
            val y = center.y
            down(Offset(width * 0.95f, y))
            moveTo(Offset(width * 0.05f, y), delayMillis = 300L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, actions)
    }
}
