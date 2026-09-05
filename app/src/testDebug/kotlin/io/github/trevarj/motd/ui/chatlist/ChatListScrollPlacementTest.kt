package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.ComposeFoundationWorkarounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Regression coverage for the chat-list glitch where rows visibly sprang into place "for no
 * reason": scroll down, ride the scroll-to-top FAB (`animateScrollToItem`), scroll down again.
 * Foundation 1.11's skip-placement-animation fix (b/493183465) freezes the lazy item animator's
 * bookkeeping during animated scrolls; a row re-sorted while off-screen then gets misclassified
 * as "moving in" on the next user scroll and animates in from outside the viewport.
 * [ComposeFoundationWorkarounds.apply] opts out of that fix; these tests pin the resulting
 * behavior for every way of returning to the top. Rows mirror the chat list's `animateItem`
 * configuration (fades plus a spring placement spec).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListScrollPlacementTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun applyProductionWorkarounds() {
        ComposeFoundationWorkarounds.apply()
    }

    private val rowHeightPx = 100
    private val viewportPx = 500

    private lateinit var listState: LazyListState
    private lateinit var scope: CoroutineScope
    private var order by mutableStateOf((0 until 60).toList())

    private fun setContent(repinTop: Boolean = true) {
        compose.setContent {
            listState = rememberLazyListState()
            scope = rememberCoroutineScope()
            // A single root-scope read that the item lambdas capture, mirroring ChatList, where
            // `sections` is computed in the body: the measure pass can never observe a newer
            // order than the composition that runs the re-pin trigger below.
            val presentedOrder = order
            if (repinTop) {
                // The production wiring from ChatList: re-pin a resting true-top viewport when the
                // top item changes, inside the same composition that presents the new order.
                val topKey = presentedOrder.first()
                val tracker = remember { ChatListTopItemTracker(topKey) }
                if (tracker.key != topKey) {
                    val repin =
                        Snapshot.withoutReadObservation {
                            shouldRepinChatListTop(
                                previousTopKey = tracker.key,
                                topKey = topKey,
                                canScrollBackward = listState.canScrollBackward,
                                scrollInProgress = listState.isScrollInProgress,
                            )
                        }
                    tracker.key = topKey
                    if (repin) listState.requestScrollToItem(0)
                }
            }
            with(LocalDensity.current) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .width(200.toDp())
                            .height(viewportPx.toDp())
                            .testTag("list"),
                ) {
                    items(count = presentedOrder.size, key = { presentedOrder[it] }) { i ->
                        Box(
                            Modifier
                                .width(200.toDp())
                                .height(rowHeightPx.toDp())
                                .testTag("row-${presentedOrder[i]}")
                                .animateItem(
                                    fadeInSpec = tween(90),
                                    fadeOutSpec = tween(90),
                                    placementSpec =
                                        spring(
                                            stiffness = Spring.StiffnessMediumLow,
                                            visibilityThreshold = IntOffset.VisibilityThreshold,
                                        ),
                                ),
                        ) {}
                    }
                }
            }
        }
    }

    private fun launchOnUi(block: suspend CoroutineScope.() -> Unit): Job {
        var job: Job? = null
        compose.runOnUiThread { job = scope.launch { block() } }
        return job!!
    }

    private fun advanceUntil(
        job: Job,
        maxFrames: Int = 600,
    ) {
        var frames = 0
        while (job.isActive && frames < maxFrames) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("job did not finish within $maxFrames frames", job.isCompleted)
    }

    /**
     * Unclipped top position of every currently visible row keyed by row id, in root coordinates,
     * so a row peeking past the viewport edge still reports its true offset.
     */
    private fun visibleRowTops(): Map<Int, Float> {
        val visible =
            compose.runOnUiThread {
                listState.layoutInfo.visibleItemsInfo.map { it.key as Int }
            }
        return visible.associateWith { id ->
            compose
                .onNodeWithTag("row-$id")
                .fetchSemanticsNode()
                .positionInRoot.y
        }
    }

    /** Rows must tile exactly: consecutive visible rows differ by exactly one row height. */
    private fun assertRowsTile(label: String) {
        val tops = visibleRowTops()
        val visible =
            compose.runOnUiThread {
                listState.layoutInfo.visibleItemsInfo
                    .sortedBy { it.offset }
                    .map { it.key as Int }
            }
        visible.zipWithNext().forEach { (a, b) ->
            assertEquals(
                "$label: rows $a/$b not tiled (tops ${tops.getValue(a)}/${tops.getValue(b)})",
                rowHeightPx.toFloat(),
                tops.getValue(b) - tops.getValue(a),
                0.5f,
            )
        }
    }

    /** With no input and no data change, row positions must not move between frames. */
    private fun assertRowsFrozen(
        label: String,
        frames: Int = 40,
    ) {
        val before = visibleRowTops()
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
        val after = visibleRowTops()
        before.keys.intersect(after.keys).forEach { id ->
            assertEquals(
                "$label: row $id moved with no scroll input",
                before.getValue(id),
                after.getValue(id),
                0.5f,
            )
        }
    }

    private fun scrollDownInSteps(
        steps: Int,
        stepPx: Float,
        label: String,
    ) {
        repeat(steps) {
            val job = launchOnUi { listState.scrollBy(stepPx) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(job.isCompleted)
            assertRowsTile("$label step $it")
        }
    }

    /** Shared prologue: scroll down, then re-sort a row that is far above the viewport. */
    private fun scrollDownAndReorderOffscreen() {
        advanceUntil(launchOnUi { listState.scrollBy(2500f) })
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            order = listOf(10) + order.filter { it != 10 }
        }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun scrollDownAfterAnimatedScrollToTop_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false

        advanceUntil(launchOnUi { listState.scrollBy(2500f) })
        compose.mainClock.advanceTimeByFrame()

        advanceUntil(launchOnUi { listState.animateScrollToItem(0) })
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-fab scroll")
        assertRowsFrozen("post-fab settle")
    }

    @Test
    fun offscreenReorderThenAnimatedScrollToTop_thenScrollDown_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false
        scrollDownAndReorderOffscreen()

        advanceUntil(launchOnUi { listState.animateScrollToItem(0) })
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-reorder scroll")
        assertRowsFrozen("post-reorder settle")
    }

    @Test
    fun offscreenReorderThenManualScrollToTop_thenScrollDown_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false
        scrollDownAndReorderOffscreen()

        repeat(25) {
            val job = launchOnUi { listState.scrollBy(-100f) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(job.isCompleted)
        }
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-manual scroll")
        assertRowsFrozen("post-manual settle")
    }

    @Test
    fun offscreenReorderThenSnapScrollToTop_thenScrollDown_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false
        scrollDownAndReorderOffscreen()

        advanceUntil(launchOnUi { listState.scrollToItem(0) })
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-snap scroll")
        assertRowsFrozen("post-snap settle")
    }

    /**
     * Frozen-clock reorder: the snapshot write must be announced explicitly (nothing else pumps
     * apply notifications while the clock is paused), then two frames compose and measure it.
     */
    private fun promoteToTop(id: Int) {
        compose.runOnUiThread {
            order = listOf(id) + order.filter { it != id }
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun visibleKeysInLayout(): List<Int> =
        compose.runOnUiThread {
            listState.layoutInfo.visibleItemsInfo
                .sortedBy { it.offset }
                .map { it.key as Int }
        }

    /**
     * The idle-at-top case: a row re-sorted to the top while the viewport rests at the true top
     * must be presented at index 0 in the same remeasure, travel into the top slot, and push the
     * displaced rows down — never land above the viewport as an unseen scroll-up surprise.
     */
    @Test
    fun reorderToTopWhileRestingAtTop_surfacesPromotedRowAndPushesRowsDown() {
        setContent()
        compose.mainClock.autoAdvance = false
        assertEquals(0f, visibleRowTops().getValue(0), 0.5f)

        promoteToTop(10)

        // The pinned remeasure presents the promoted row at index 0 with no scroll input. The
        // old bottom row may linger in layout info while it animates out, so check the head.
        assertEquals(listOf(10, 0, 1, 2, 3), visibleKeysInLayout().take(5))
        assertEquals(0, compose.runOnUiThread { listState.firstVisibleItemIndex })
        assertFalse(compose.runOnUiThread { listState.canScrollBackward })

        // The displaced old top row springs down one slot; require at least one mid-flight frame.
        var sawMidFlight = false
        var frames = 0
        while (frames < 240) {
            compose.mainClock.advanceTimeByFrame()
            frames++
            val top0 = visibleRowTops().getValue(0)
            if (top0 > 0.5f && top0 < rowHeightPx - 0.5f) sawMidFlight = true
            if (top0 >= rowHeightPx - 0.5f) break
        }
        assertTrue("displaced row never animated toward its new slot", sawMidFlight)
        repeat(60) { compose.mainClock.advanceTimeByFrame() }
        assertRowsTile("post-promotion")
        assertEquals(0f, visibleRowTops().getValue(10), 0.5f)
        assertEquals(rowHeightPx.toFloat(), visibleRowTops().getValue(0), 0.5f)
    }

    /** Away from the top, a promotion must keep the viewport anchored on what the user reads. */
    @Test
    fun reorderToTopWhileScrolledDown_keepsViewportAnchored() {
        setContent()
        compose.mainClock.autoAdvance = false
        advanceUntil(launchOnUi { listState.scrollBy(1000f) })
        compose.mainClock.advanceTimeByFrame()
        val before = visibleRowTops()

        promoteToTop(30)
        repeat(30) { compose.mainClock.advanceTimeByFrame() }

        val after = visibleRowTops()
        assertEquals(before.keys, after.keys)
        before.keys.forEach { id ->
            assertEquals("row $id moved on an above-viewport promotion", before.getValue(id), after.getValue(id), 0.5f)
        }
    }

    /**
     * Foundation baseline without the re-pin: the key anchor keeps the old top row in place and
     * the promoted row lands above the viewport. If this fails after a Compose upgrade, LazyColumn
     * learned to keep a true-top viewport pinned natively and the ChatList re-pin (and this
     * harness's trigger) can be re-evaluated.
     */
    @Test
    fun reorderToTopWithoutRepin_leavesPromotedRowAboveViewport() {
        setContent(repinTop = false)
        compose.mainClock.autoAdvance = false

        promoteToTop(10)
        repeat(10) { compose.mainClock.advanceTimeByFrame() }

        assertEquals(listOf(0, 1, 2, 3, 4), visibleKeysInLayout())
        assertEquals(0f, visibleRowTops().getValue(0), 0.5f)
        assertTrue("row 10 should sit above the viewport", compose.runOnUiThread { listState.canScrollBackward })
        assertEquals(1, compose.runOnUiThread { listState.firstVisibleItemIndex })
    }
}
