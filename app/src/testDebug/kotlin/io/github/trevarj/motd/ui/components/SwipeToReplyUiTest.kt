package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SwipeToReplyUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun disablingSwipeStopsGestureButKeepsOtherReplyActions() {
        val enabled = mutableStateOf(true)
        var replies = 0
        var longPresses = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SwipeToReplyContainer(onReply = { replies++ }, enabled = enabled.value) { modifier ->
                    Box(
                        modifier =
                            modifier
                                .size(width = 300.dp, height = 64.dp)
                                .testTag("reply_row")
                                .combinedClickable(onClick = {}, onLongClick = { longPresses++ }),
                    )
                }
            }
        }

        fun swipeRow() {
            val start = with(compose.density) { Offset(75.dp.toPx(), 32.dp.toPx()) }
            val end = with(compose.density) { Offset(270.dp.toPx(), 32.dp.toPx()) }
            compose.onNodeWithTag("reply_row").performTouchInput {
                swipe(start = start, end = end, durationMillis = 300)
            }
        }

        swipeRow()
        compose.runOnIdle { assertEquals(1, replies) }
        compose.runOnIdle { enabled.value = false }
        swipeRow()
        compose.runOnIdle { assertEquals(1, replies) }
        compose.onNodeWithTag("reply_row").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, longPresses) }
        val accessibleReply =
            compose
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Reply" }
        compose.runOnIdle { assertTrue(accessibleReply.action()) }
        compose.runOnIdle { assertEquals(2, replies) }
    }
}
