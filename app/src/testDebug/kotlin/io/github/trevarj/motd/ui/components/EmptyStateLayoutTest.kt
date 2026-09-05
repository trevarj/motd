package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The ghost rows are a fixed-size illustration, not a banner.
 *
 * `fillMaxWidth().widthIn(max = ...)` is the classic no-op ordering: by the time `widthIn` runs, the
 * incoming constraint is already pinned to the parent's width and the cap has nothing left to
 * narrow. On a phone the two orders look identical, which is exactly why this needs a test on a
 * container far wider than the cap.
 *
 * Lives in `testDebug` rather than `test`: [createComposeRule] launches a `ComponentActivity`, which
 * only the debug manifest declares (androidx's ui-test-manifest is debug-scoped). In `test` this
 * passes under `testDebugUnitTest` and fails `testReleaseUnitTest` with an unresolved-activity
 * intent, which only the release-parity run catches. [ChatListScrollPlacementTest] sits here for the
 * same reason.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmptyStateLayoutTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theGhostRowsKeepTheirIntrinsicWidthOnATabletWideContainer() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                // requiredSize, not size: it ignores the Robolectric screen's own constraints, so
                // the container really is wider than the cap whatever device config is in force.
                Box(Modifier.requiredSize(width = 1_280.dp, height = 800.dp)) {
                    EmptyState(
                        icon = Icons.Outlined.Forum,
                        title = "No conversations yet",
                        message = "Connect to a network to start chatting.",
                        ghostRows = true,
                    )
                }
            }
        }

        val node =
            compose
                .onNodeWithTag("empty_state_ghost_rows", useUnmergedTree = true)
                .fetchSemanticsNode()
        val width = with(compose.density) { node.size.width.toDp() }

        assertTrue("ghost rows stretched to $width", width <= EmptyStateGhostRows.MaxWidth)
        // And they do fill up to the cap, so the assertion above cannot pass by collapsing to zero.
        assertTrue("ghost rows collapsed to $width", width >= EmptyStateGhostRows.MaxWidth - 1.dp)
    }
}
