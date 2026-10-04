package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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

    private var actions = 0
    private var touchSlop = 0f

    @Test
    fun `swipe requires sixty percent of row width including the boundary`() {
        val threshold = chatListSwipePositionalThreshold(200f)
        assertEquals(120f, threshold, 0.001f)
        assertEquals(0.60f, CHAT_LIST_SWIPE_THRESHOLD_FRACTION, 0f)
        assertFalse(isChatListSwipePastThreshold(-threshold + 0.01f, 200f))
        assertTrue(isChatListSwipePastThreshold(-threshold, 200f))
        assertTrue(isChatListSwipePastThreshold(-threshold - 0.01f, 200f))
        assertFalse(isChatListSwipePastThreshold(threshold, 200f))
    }

    @Test
    fun `fast short flick springs toward origin without traveling offscreen`() {
        showRow()
        val origin = surfaceBounds()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput {
            down(Offset(width * 0.95f, center.y))
            moveTo(Offset(width * 0.7f, center.y), delayMillis = 16L)
        }
        composeRule.mainClock.advanceTimeByFrame()
        val released = surfaceBounds()
        assertTrue(released.left < origin.left)
        assertTrue(released.right > origin.left)
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput { up() }

        var previousLeft = released.left.value
        repeat(24) {
            composeRule.mainClock.advanceTimeByFrame()
            val bounds = surfaceBounds()
            assertTrue("The row must return, not first fling away: $bounds", bounds.left.value >= previousLeft - 0.5f)
            assertTrue("The foreground must remain onscreen: $bounds", bounds.right > origin.left)
            assertEquals((origin.right - origin.left).value, (bounds.right - bounds.left).value, 0.5f)
            assertEquals(0, actions)
            previousLeft = bounds.left.value
        }
        assertTrue(previousLeft > released.left.value)
        finishSettling()
        assertEquals(origin.left.value, surfaceBounds().left.value, 0.5f)
        assertEquals(0, actions)
    }

    @Test
    fun `release just below threshold returns and just above threshold acts once`() {
        showRow()
        val origin = surfaceBounds()
        composeRule.mainClock.autoAdvance = false
        dragToFraction(0.599f)
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue(surfaceBounds().left < origin.left)
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput { up() }
        finishSettling()
        assertEquals(0, actions)
        assertEquals(origin.left.value, surfaceBounds().left.value, 0.5f)

        dragToFraction(0.601f)
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput { up() }
        finishSettling()
        assertEquals(1, actions)
        assertEquals(origin.left.value, surfaceBounds().left.value, 0.5f)
        finishSettling()
        assertEquals(1, actions)
    }

    @Test
    fun `full drag settles left then resets before the row can be swiped again`() {
        showRow()
        val origin = surfaceBounds()
        composeRule.mainClock.autoAdvance = false
        dragToFraction(0.8f)
        composeRule.mainClock.advanceTimeByFrame()
        val released = surfaceBounds()
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(64L)
        assertTrue(surfaceBounds().left < released.left)
        assertEquals(0, actions)
        finishSettling()
        assertEquals(1, actions)
        assertEquals(origin.left.value, surfaceBounds().left.value, 0.5f)

        dragToFraction(0.8f)
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput { up() }
        finishSettling()
        assertEquals(2, actions)
        assertEquals(origin.left.value, surfaceBounds().left.value, 0.5f)
    }

    @Test
    fun `crossing threshold then retreating below it returns without acting`() {
        showRow()
        val origin = surfaceBounds()
        composeRule.mainClock.autoAdvance = false
        dragToFraction(0.8f)
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput {
            moveTo(Offset(width * 0.7f, center.y), delayMillis = 16L)
            up()
        }
        finishSettling()
        assertEquals(0, actions)
        assertEquals(origin.left.value, surfaceBounds().left.value, 0.5f)
    }

    @Test
    fun `cancel after crossing threshold returns without acting`() {
        showRow()
        val origin = surfaceBounds()
        composeRule.mainClock.autoAdvance = false
        dragToFraction(0.8f)
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput { cancel() }
        finishSettling()
        assertEquals(0, actions)
        assertEquals(origin.left.value, surfaceBounds().left.value, 0.5f)
    }

    private fun surfaceBounds() = composeRule.onNodeWithTag("chatlist_row_surface_1").getUnclippedBoundsInRoot()

    private fun finishSettling() {
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
    }

    private fun dragToFraction(fraction: Float) {
        composeRule.onNodeWithTag("chatlist_swipe_1").performTouchInput {
            val startX = width * 0.95f
            down(Offset(startX, center.y))
            // The native recognizer starts displacement after horizontal touch slop.
            moveTo(Offset(startX - width * fraction - touchSlop, center.y), delayMillis = 300L)
        }
    }

    private fun showRow() {
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
            touchSlop = LocalViewConfiguration.current.touchSlop
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
    }
}
