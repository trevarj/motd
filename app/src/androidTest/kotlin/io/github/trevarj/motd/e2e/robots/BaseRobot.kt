package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp

/**
 * Minimum spacing between the bounded `performScrollToNode` sweeps a container wait is allowed to
 * issue. See [BaseRobot.scrollContainerTo] for why an unbounded sweep rate is not merely slow.
 */
private const val CONTAINER_SWEEP_INTERVAL_MS = 5_000L

internal open class BaseRobot(
    protected val compose: ComposeTestRule,
) {
    fun isPresent(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    // 30s, not 10s: on cold 2-core CI runners the first composition of a screen can outlast 10s
    // (the onboarding SERVER→AUTH page did exactly that), and a journey that would have passed in
    // second 11 is a flake, not a signal. Genuine failures still surface well inside the job budget.
    fun awaitTag(
        tag: String,
        timeoutMs: Long = 30_000,
    ) {
        compose.waitUntil(timeoutMs) { isPresent(tag) }
    }

    /** The inverse wait, for transient nodes that leave the composition when their state clears. */
    fun awaitTagGone(
        tag: String,
        timeoutMs: Long = 30_000,
    ) {
        compose.waitUntil(timeoutMs) { !isPresent(tag) }
    }

    fun click(tag: String) {
        awaitTag(tag)
        // A click on a still-disabled control is a silent no-op that only surfaces as a downstream
        // timeout (e.g. a gated wizard Next); wait for enablement so the tap provably lands.
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsEnabled()
            }.isSuccess
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    fun turnOnPrefix(
        prefix: String,
        timeoutMs: Long = 30_000,
    ) {
        val matcher =
            SemanticsMatcher("test tag starts with '$prefix'") { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
            }
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val node = compose.onAllNodes(matcher, useUnmergedTree = true)[0]
        if (runCatching { node.assertIsOn() }.isFailure) node.performClick()
        compose.waitUntil(timeoutMs) {
            runCatching {
                compose.onAllNodes(matcher, useUnmergedTree = true)[0].assertIsOn()
            }.isSuccess
        }
        node.assertIsOn()
    }

    fun scrollToAndClick(tag: String) {
        awaitTag(tag)
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
    }

    fun swipeUntilTag(
        containerTag: String,
        itemTag: String,
        timeoutMs: Long = 10_000,
    ) {
        awaitTag(containerTag)
        compose.waitUntil(timeoutMs) {
            if (isPresent(itemTag)) {
                true
            } else {
                compose.onNodeWithTag(containerTag, useUnmergedTree = true).performTouchInput { swipeUp() }
                false
            }
        }
    }

    fun replace(
        tag: String,
        value: String,
    ) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(value)
    }

    fun assertDisplayed(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    fun scrollContainerTo(
        containerTag: String,
        itemTag: String,
        timeoutMs: Long = 10_000,
    ) = scrollContainerTo(containerTag, hasTestTag(itemTag), timeoutMs)

    /** The container itself, addressed the same way every scroll helper here addresses it. */
    protected fun container(containerTag: String) = compose.onNodeWithTag(containerTag, useUnmergedTree = true)

    /** True once [containerTag] has actually composed a descendant matching [matcher]. */
    fun containerHasNode(
        containerTag: String,
        matcher: SemanticsMatcher,
    ): Boolean =
        compose
            .onAllNodes(matcher and hasAnyAncestor(hasTestTag(containerTag)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /**
     * Waits for a lazy descendant to become addressable and then aligns it, without letting the
     * wait perturb the container it is measuring.
     *
     * `performScrollToNode` short-circuits only when the node is already composed. Otherwise it
     * resets the container to index 0 and sweeps the entire loaded list before throwing. The chat
     * timeline is `reverseLayout = true`, so that sweep parks the viewport on the OLDEST loaded row
     * — exactly the Paging APPEND boundary. Sweeping on every polling interval therefore fetches
     * another page, rebuilds the Pager generation, and churns the very snapshot the poll inspects,
     * so the wait can outlive any budget no matter how large. Poll on composition instead, and
     * rate-limit the sweeps so a failing wait touches that boundary a bounded number of times.
     */
    fun scrollContainerTo(
        containerTag: String,
        matcher: SemanticsMatcher,
        timeoutMs: Long = 10_000,
    ) {
        awaitTag(containerTag)
        var nextSweepAt = 0L
        compose.waitUntil("'$containerTag' scrolled to ${matcher.description}", timeoutMs) {
            if (containerHasNode(containerTag, matcher)) {
                // Composed is not the same as inside the viewport, so still align — but a composed
                // match short-circuits inside performScrollToNode, so this cannot sweep. The success
                // condition stays exactly what it always was: the container is scrolled to the node.
                return@waitUntil runCatching {
                    container(containerTag).performScrollToNode(matcher)
                }.isSuccess
            }
            val now = System.currentTimeMillis()
            if (now < nextSweepAt) return@waitUntil false
            nextSweepAt = now + CONTAINER_SWEEP_INTERVAL_MS
            // A sweep is still the only way to reach a loaded-but-uncomposed row in a container
            // that exposes no key, so keep it available — just not on every interval.
            runCatching { container(containerTag).performScrollToNode(matcher) }.isSuccess
        }
    }

    /**
     * One key-addressed seek, for callers that hold the row's Room id: the chat timeline is keyed
     * by message id. `performScrollToKey` resolves the index through `SemanticsActions.IndexForKey`
     * over the whole loaded item list and scrolls straight there, and a miss throws without moving
     * the viewport. Polling it is therefore safe in a way that polling [scrollContainerTo]'s sweep
     * is not: a miss never drags the list toward the older APPEND boundary.
     *
     * Returns true when the key-addressed seek was dispatched, false when it failed. Callers must
     * still wait for the target node to be composed and visible.
     */
    fun tryScrollContainerToKey(
        containerTag: String,
        key: Any,
    ): Boolean = runCatching { container(containerTag).performScrollToKey(key) }.isSuccess
}
