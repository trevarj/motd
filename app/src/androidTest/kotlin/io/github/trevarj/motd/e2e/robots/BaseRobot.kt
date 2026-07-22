package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsProperties

internal open class BaseRobot(protected val compose: ComposeTestRule) {
    fun isPresent(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun awaitTag(tag: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) { isPresent(tag) }
    }

    fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    fun clickPrefix(prefix: String, timeoutMs: Long = 30_000) {
        val matcher = SemanticsMatcher("test tag starts with '$prefix'") { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
        }
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(matcher, useUnmergedTree = true)[0].performClick()
    }

    fun swipeUntilTag(containerTag: String, itemTag: String, timeoutMs: Long = 10_000) {
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

    fun replace(tag: String, value: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(value)
    }

    fun assertDisplayed(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    fun scrollContainerTo(containerTag: String, itemTag: String) =
        scrollContainerTo(containerTag, hasTestTag(itemTag))

    /** Lazy descendants are retried until paging has made the requested item addressable. */
    fun scrollContainerTo(containerTag: String, matcher: SemanticsMatcher) {
        awaitTag(containerTag)
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag(containerTag, useUnmergedTree = true).performScrollToNode(matcher)
            }.isSuccess
        }
    }
}
