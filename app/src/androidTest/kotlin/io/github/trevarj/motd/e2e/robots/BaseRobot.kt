package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement

internal open class BaseRobot(protected val compose: ComposeTestRule) {
    fun awaitTag(tag: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    fun replace(tag: String, value: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(value)
    }

    fun assertDisplayed(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    /** Lazy descendants are never queried until their tagged container has materialized them. */
    fun scrollContainerTo(containerTag: String, itemTag: String) {
        awaitTag(containerTag)
        compose.onNodeWithTag(containerTag, useUnmergedTree = true).performScrollToNode(hasTestTag(itemTag))
    }
}
