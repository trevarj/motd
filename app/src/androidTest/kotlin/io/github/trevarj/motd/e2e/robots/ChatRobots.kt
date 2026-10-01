package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.e2e.TimelineDiagnostics
import io.github.trevarj.motd.ui.chat.CHAT_ENTRY_VEIL_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_LOADING_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_MORE_TAG
import io.github.trevarj.motd.ui.components.CHAT_GAP_DIVIDER_TAG
import org.junit.Assert.assertTrue

/** Spacing between the bottom resets a newest-row wait is allowed to issue. */
private const val NEWEST_ROW_RESET_INTERVAL_MS = 5_000L

/** Artifact subdirectory the newest-row snapshots are written under. */
private const val NEWEST_ROW_DIAGNOSTIC_LABEL = "newest_row"

internal class ChatListRobot(
    compose: ComposeTestRule,
) : BaseRobot(compose) {
    fun open(bufferId: Long) = click("chatlist_row_$bufferId")
}

internal class ChatRobot(
    compose: ComposeTestRule,
) : BaseRobot(compose) {
    fun send(text: String) {
        replace("chat_composer_field", text)
        click("chat_composer_send")
    }
}

internal class TimelineRobot(
    private val rule: ComposeTestRule,
) : BaseRobot(rule) {
    fun assertMessage(text: String) {
        scrollContainerTo("chat_timeline", hasText(text, substring = true))
        rule.onNodeWithText(text, substring = true, useUnmergedTree = true).assertTextContains(text, substring = true)
    }

    fun assertCompactAudioPlayer(
        messageTag: String,
        rowId: Long,
        diagnostics: TimelineDiagnostics? = null,
    ) {
        val playerMatcher = hasTestTag("audio_player") and hasAnyAncestor(hasTestTag(messageTag))
        val detailsMatcher = hasTestTag("audio_player_details") and hasAnyAncestor(hasTestTag(messageTag))
        // A freshly uploaded voice row must round-trip through the filehost, the IRC echo, and Room
        // before Paging can present it. Use the journey's network-dependent timeout rather than the
        // generic 10s component wait, which is a cold-emulator flake edge for this row.
        awaitNewestRow(messageTag, rowId, timeoutMs = 30_000, diagnostics = diagnostics)
        val players = rule.onAllNodes(playerMatcher, useUnmergedTree = true).assertCountEquals(1)
        val player = players[0].assertIsDisplayed()
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density
        val heightDp = player.fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("audio player height was ${heightDp}dp", heightDp <= 84f)
        rule.onAllNodes(detailsMatcher, useUnmergedTree = true).assertCountEquals(1)[0].performClick()
        rule.onNodeWithText("Link", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * Waits for a row at the NEWEST end of the timeline, which under `reverseLayout = true` lives
     * at index 0, and leaves it aligned in the viewport.
     *
     * This wait must never sweep. A `performScrollToNode` miss walks the container to the oldest
     * loaded row, which is the Paging APPEND boundary: the append rewrites the history gap, the gap
     * rebuilds the Pager, and the new generation churns the very snapshot the wait is polling — the
     * oracle would keep destroying the state it measures. Instead poll the key path, which resolves
     * the row's index over the loaded list and throws without moving on a miss, and allow at most
     * one bottom reset per interval in case an earlier step parked the viewport in older history.
     *
     * [diagnostics], when supplied, snapshots the presented list, the Paging key map, Room, and the
     * history window on both outcomes. It runs strictly after the wait has decided, is read-only,
     * and swallows its own errors, so it can neither change the verdict nor mask the timeout.
     */
    private fun awaitNewestRow(
        messageTag: String,
        rowId: Long,
        timeoutMs: Long,
        diagnostics: TimelineDiagnostics? = null,
    ) {
        awaitTag("chat_timeline")
        var nextResetAt = 0L
        try {
            rule.waitUntil("timeline scrolled to newest row $messageTag (key $rowId)", timeoutMs) {
                if (isPresent(messageTag)) {
                    // Align only an observed node; a successful key seek may not have composed it yet.
                    return@waitUntil runCatching {
                        rule
                            .onNodeWithTag(messageTag, useUnmergedTree = true)
                            .performScrollTo()
                            .assertIsDisplayed()
                    }.isSuccess
                }
                if (tryScrollContainerToKey("chat_timeline", rowId)) return@waitUntil false
                val now = System.currentTimeMillis()
                if (now >= nextResetAt) {
                    nextResetAt = now + NEWEST_ROW_RESET_INTERVAL_MS
                    // Index 0 is the newest row, so a reset only ever moves toward the newer
                    // (PREPEND) end and can never trip the older APPEND boundary.
                    runCatching { container("chat_timeline").performScrollToIndex(0) }
                }
                false
            }
        } catch (failure: Throwable) {
            // Capture, then rethrow the original failure untouched.
            runCatching {
                diagnostics?.capture(
                    label = NEWEST_ROW_DIAGNOSTIC_LABEL,
                    outcome = "timeout",
                    containerTag = "chat_timeline",
                    targetTag = messageTag,
                    targetKey = rowId,
                    budgetMs = timeoutMs,
                )
            }
            throw failure
        }
        // A green run has to produce the same shape of snapshot, or the red one has nothing to be
        // diffed against.
        runCatching {
            diagnostics?.capture(
                label = NEWEST_ROW_DIAGNOSTIC_LABEL,
                outcome = "pass",
                containerTag = "chat_timeline",
                targetTag = messageTag,
                targetKey = rowId,
                budgetMs = timeoutMs,
            )
        }
    }

    /**
     * The chat timeline is veiled (alpha 0) until entry positioning settles, and ui-test geometry
     * and `assertIsDisplayed` both ignore `graphicsLayer` alpha, so every entry assertion would
     * pass against an invisible pane. Node presence is observable where alpha is not, so wait the
     * veil out: it leaves the composition only once the reveal has finished.
     */
    fun awaitEntryVeilLifted(timeoutMs: Long = 30_000) = awaitTagGone(CHAT_ENTRY_VEIL_TAG, timeoutMs)

    fun assertUnreadEntry(
        firstTag: String,
        secondTag: String,
        expectedLabel: String? = null,
        timeoutMs: Long = 30_000,
    ) {
        rule.waitForIdle()
        awaitEntryVeilLifted(timeoutMs)
        // Atomic history publication can leave Paging materializing the bounded entry window for
        // longer than the generic component timeout on a cold hosted emulator.
        awaitTag("chat_read_marker_divider", timeoutMs)
        awaitTag(firstTag, timeoutMs)
        awaitTag(secondTag, timeoutMs)
        val timeline = rule.onNodeWithTag("chat_timeline", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        expectedLabel?.let {
            rule
                .onNode(
                    hasText(it) and hasAnyAncestor(hasTestTag("chat_read_marker_divider")),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
        }
        val divider =
            rule
                .onNodeWithTag("chat_read_marker_divider", useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val first =
            rule
                .onNodeWithTag(firstTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val second =
            rule
                .onNodeWithTag(secondTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density
        assertTrue("first unread was above the timeline viewport", first.top >= timeline.top - density)
        assertTrue(
            "first unread was not anchored near the viewport top: timeline=${timeline.top}, row=${first.top}",
            first.top <= timeline.top + 96f * density,
        )
        assertTrue("second unread did not follow the first", second.top > first.top)
        assertTrue("unread divider was not attached to the first unread row", divider.bottom <= first.top)
        assertTrue(
            "unread divider was attached to a different row: divider=${divider.bottom}, row=${first.top}",
            first.top - divider.bottom <= 24f * density,
        )
        rule.onAllNodesWithTag(firstTag, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag(secondTag, useUnmergedTree = true).assertCountEquals(1)
    }

    /**
     * One deliberate older-paging step. The timeline is `reverseLayout = true`, so the newest row
     * sits at index 0 and the oldest loaded row at the highest index; scrolling toward the last
     * index moves the older (APPEND) boundary into the prefetch window and drives exactly one
     * Paging APPEND.
     *
     * Determinism: `MESSAGE_PAGING_CONFIG` uses pageSize 50 > prefetchDistance 25. A scroll that
     * stops at the boundary triggers exactly one APPEND; after the 50-row insert the retained
     * viewport anchor sits 50 rows (> 25) above the new boundary, outside the prefetch range, so
     * no second page fires until the next deliberate `scrollToOlderBoundary()` step.
     *
     * What this step canNOT do is cross a history gap. The timeline is presented unbounded, so the
     * local source only runs dry at the true oldest retained row and the APPEND it drives asks for
     * backlog BELOW the whole timeline. An interior gap is closed by the fill path, which this step
     * reaches only indirectly: scrolling a seam within reach is what loads across it
     * ([awaitGapFilledByScrolling]), and APPEND itself never walks through one. Paging3 still
     * auto-fires an APPEND with no scroll
     * whenever the initial source load returns `nextKey == null`, which any store smaller than
     * `initialLoadSize` (pageSize * 3 = 150) does; that is unconditional Paging behavior and is why
     * no step here can promise "exactly one page per user action".
     * `RecentPagingAppendReproTest` pins both mechanics.
     */
    fun scrollToOlderBoundary() {
        awaitTag("chat_timeline")
        val before = timelineItemCount()
        val lastIndex = before - 1
        if (lastIndex > 0) {
            runCatching {
                rule.onNodeWithTag("chat_timeline", useUnmergedTree = true).performScrollToIndex(lastIndex)
            }.onFailure {
                rule
                    .onNodeWithTag("chat_timeline", useUnmergedTree = true)
                    .performTouchInput { swipeDown(durationMillis = 300) }
            }
        } else {
            rule
                .onNodeWithTag("chat_timeline", useUnmergedTree = true)
                .performTouchInput { swipeDown(durationMillis = 300) }
        }
        // The boundary hit composes the footer — the shimmer while an APPEND is actually in flight,
        // the armed status line otherwise — or the row set grows if the fixture page lands before
        // either tag is observed. If none of that happens the step reached the confirmed start of
        // history or loaded instantly; either way settle. The swallowed timeout costs at most 10s
        // per fully-settled step (e.g. paging past the true start of history) and stays bounded:
        // scrollOlderUntil's maximumSwipes cap still throws loudly if the requested row never
        // becomes addressable.
        runCatching {
            rule.waitUntil(10_000) {
                isPresent(CHAT_HISTORY_LOADING_TAG) || isPresent(CHAT_HISTORY_MORE_TAG) ||
                    timelineItemCount() > before
            }
        }
        if (isPresent(CHAT_HISTORY_LOADING_TAG)) {
            rule.waitUntil(45_000) { !isPresent(CHAT_HISTORY_LOADING_TAG) }
        }
        rule.waitForIdle()
    }

    /**
     * Bring the fillable history seam into composition, reporting whether one is reachable at all.
     *
     * The divider is drawn INSIDE the row on the newer side of its gap, never as its own list item,
     * so it exists only once that row AND its older neighbour are materialized — `seamAbove`
     * abstains on an unmaterialized neighbour rather than guessing a position that would jump the
     * moment the placeholder loads. A seam below the initial load therefore has to be PAGED DOWN TO
     * before it can be seen, one deliberate boundary step at a time.
     */
    private fun reachGapDivider(maximumSteps: Int = 3): Boolean {
        awaitTag("chat_timeline")
        repeat(maximumSteps) {
            if (isPresent(CHAT_GAP_DIVIDER_TAG)) return true
            // Same shape as scrollOlderUntil, and for the same reason: a seam sitting mid-list is
            // LOADED but uncomposed, and only a walk composes the rows in between. A plain
            // scroll-to-last-index jumps straight past it and would never bring it into the tree.
            //
            // NOT SIDE-EFFECT FREE, and callers must place it accordingly: a performScrollToNode
            // MISS resets the container to index 0 before sweeping, and index 0 in this reversed
            // list is the newest row — which the viewport mark-read effect reads as "the user is at
            // the bottom" and acknowledges the whole room. Only call this once the journey no longer
            // depends on anything being unread.
            val reached =
                runCatching {
                    container("chat_timeline").performScrollToNode(hasTestTag(CHAT_GAP_DIVIDER_TAG))
                }.isSuccess
            if (reached) return true
            scrollToOlderBoundary()
        }
        return false
    }

    /**
     * Scroll toward the history seam until it closes, never tapping it. Reports whether a seam was
     * ever on screen at all.
     *
     * This is the timeline's one history rule driven end to end: a seam is the end of the list, so
     * scrolling toward it loads across it. Every step here is a scroll and nothing else — no click
     * is issued, so if the rule stops working this cannot silently substitute a tap for it: the seam
     * stays on screen, the row count stops moving, and the sweeps run out.
     *
     * Each sweep is one deliberate approach and therefore one load. That is the rule's bound made
     * visible: the loop cannot drain a gap without scrolling toward it every time, and a caller who
     * stops calling stops the fetching. The wait is on rows landing rather than on a spinner, because
     * a loading, idle, and failed seam all share the one root test tag — three divider states with
     * one control identity, distinguished only by their inner variant tag in the unmerged tree. The
     * seam recedes rather than vanishes, so absence right here only means it left the composed
     * window; page toward it again to tell a closed seam apart from a receded one.
     *
     * Tolerant of finding nothing, deliberately. The room opens AT the seam after a reconnect (the
     * first unread row is the one on its newer side), so the rule may well have closed it before
     * this runs. Asserting a seam is present here would be asserting on a race with the app's own
     * catch-up. What the caller can rely on is the postcondition: after this returns, the far side
     * of the gap is reachable, and no tap was involved in making it so.
     */
    fun awaitGapFilledByScrolling(maximumSweeps: Int = 6): Boolean {
        var everSeen = false
        repeat(maximumSweeps) {
            if (!reachGapDivider()) return everSeen
            everSeen = true
            // Composed is not the same as WITHIN REACH, and reach is what the app measures: it
            // derives the demand from `LazyListState.layoutInfo`, which excludes a divider that a
            // beyond-bounds composition kept alive outside the viewport. `reachGapDivider` can
            // return on the composed check alone, so put the seam in the viewport explicitly.
            runCatching {
                container("chat_timeline").performScrollToNode(hasTestTag(CHAT_GAP_DIVIDER_TAG))
            }
            val before = timelineItemCount()
            runCatching {
                rule.waitUntil(45_000) {
                    !isPresent(CHAT_GAP_DIVIDER_TAG) || timelineItemCount() > before
                }
            }
            rule.waitForIdle()
        }
        throw AssertionError(
            "the history seam was still open after $maximumSweeps deliberate approaches: scrolling " +
                "toward a seam no longer loads across it",
        )
    }

    /** Repeat [scrollToOlderBoundary] until a row containing [text] becomes addressable. */
    fun scrollOlderUntil(
        text: String,
        maximumSwipes: Int = 48,
    ) {
        repeat(maximumSwipes) {
            val reached =
                runCatching {
                    rule
                        .onNodeWithTag("chat_timeline", useUnmergedTree = true)
                        .performScrollToNode(hasText(text, substring = true))
                }.isSuccess
            if (reached) {
                rule
                    .onNodeWithText(text, substring = true, useUnmergedTree = true)
                    .assertTextContains(text, substring = true)
                return
            }
            scrollToOlderBoundary()
        }
        throw AssertionError(
            "older history row \"$text\" did not become addressable after $maximumSwipes deliberate scroll steps",
        )
    }

    private fun timelineItemCount(): Int =
        rule
            .onAllNodesWithTag("chat_timeline", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.CollectionInfo)
            ?.rowCount
            ?: 0

    /**
     * The scroll-to-bottom FAB is shown, i.e. the app does NOT consider this viewport the bottom of
     * the conversation.
     *
     * That is the only externally observable statement of `isAtEffectiveBottom`, and it is the same
     * predicate the viewport mark-read effect gates on: whatever this reports as the bottom is what
     * the app will broadcast a room-wide MARKREAD for.
     */
    fun assertNotAtConversationBottom(timeoutMs: Long = 20_000) {
        awaitTag("chat_scroll_to_bottom_fab", timeoutMs)
    }

    fun scrollToBottom() {
        awaitTag("chat_scroll_to_bottom_fab")
        rule.onNodeWithTag("chat_scroll_to_bottom_fab", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) {
            rule
                .onAllNodesWithTag("chat_scroll_to_bottom_fab", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        rule.waitForIdle()
    }

    fun assertNoUnreadDivider() {
        rule.onAllNodesWithTag("chat_read_marker_divider", useUnmergedTree = true).assertCountEquals(0)
    }

    fun assertMessageVisible(
        tag: String,
        timeoutMs: Long = 10_000,
    ) {
        rule.waitUntil(timeoutMs) {
            runCatching { rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        }
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
    }
}
