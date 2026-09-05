package io.github.trevarj.motd.gesture

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.gesture.radial.GESTURE_MENU_A11Y_DIALOG_TAG
import io.github.trevarj.motd.gesture.radial.GESTURE_MENU_SCRIM_TAG
import io.github.trevarj.motd.gesture.radial.GESTURE_ORB_TAG
import io.github.trevarj.motd.gesture.radial.GestureOrbSurface
import io.github.trevarj.motd.gesture.radial.OrbPlacement
import io.github.trevarj.motd.gesture.radial.RadialEntry
import io.github.trevarj.motd.gesture.radial.gestureMenuSliceTag
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gesture orb overlay: gated by the lab flag, opening its ring on a hold, and swapping to a list
 * dialog when touch exploration is on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class GestureOrbUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    private val menu =
        RadialEntry(
            id = "root",
            label = "Menu",
            icon = GestureIcon.MENU,
            children =
                listOf(
                    RadialEntry("jump", "Chats", GestureIcon.CHAT, action = GestureAction.OpenChatList),
                    RadialEntry(
                        id = "tools",
                        label = "Tools",
                        icon = GestureIcon.FOLDER,
                        children =
                            listOf(
                                RadialEntry("search", "Search", GestureIcon.SEARCH, action = GestureAction.OpenSearch),
                            ),
                    ),
                ),
        )

    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun setSurface(
        enabled: Boolean = true,
        accessible: Boolean = false,
        executed: MutableList<GestureAction> = mutableListOf(),
    ) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MotdTheme(dynamicColor = false) {
                GestureOrbSurface(
                    enabled = enabled,
                    placement = OrbPlacement(),
                    accent = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    resolveMenu = { menu },
                    onExecute = { executed += it },
                    onPlacementChange = {},
                    accessible = accessible,
                )
            }
        }
    }

    @Test
    fun labOff_showsNoOrbAtAll() {
        setSurface(enabled = false)

        assertEquals(0, compose.onAllNodesWithTag(GESTURE_ORB_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun labOn_docksTheOrbAtTheScreenEdge() {
        setSurface()

        compose.onNodeWithTag(GESTURE_ORB_TAG).assertIsDisplayed()
    }

    @Test
    fun holdingTheOrb_opensTheRingWithASliceForEveryEntry() {
        setSurface()

        compose.onNodeWithTag(GESTURE_ORB_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithTag(GESTURE_MENU_SCRIM_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(GESTURE_MENU_SCRIM_TAG).assertIsDisplayed()
        compose.onNodeWithTag(gestureMenuSliceTag("jump"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(gestureMenuSliceTag("tools"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun backWhileTheRingIsOpen_closesItWithoutExecuting() {
        val executed = mutableListOf<GestureAction>()
        setSurface(executed = executed)

        compose.onNodeWithTag(GESTURE_ORB_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithTag(GESTURE_MENU_SCRIM_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        // A back racing an open ring (3-button nav, or an edge swipe past the exclusion rect) must
        // only dismiss the menu; navigating underneath the finger is how the blank-screen race began.
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithTag(GESTURE_MENU_SCRIM_TAG).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(GESTURE_ORB_TAG).performTouchInput { up() }

        assertEquals(emptyList<GestureAction>(), executed)
    }

    @Test
    fun touchExploration_swapsTheOrbForTheListDialog() {
        setSurface(accessible = true)

        compose.onNodeWithTag(GESTURE_ORB_TAG).performClick()
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithTag(GESTURE_MENU_A11Y_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(GESTURE_MENU_A11Y_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(gestureMenuSliceTag("jump"), useUnmergedTree = true).assertIsDisplayed()
        // No ring is ever drawn on this path: the dialog is the whole menu, not a companion to it.
        assertEquals(0, compose.onAllNodesWithTag(GESTURE_MENU_SCRIM_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun tappingAnAccessibleEntry_runsItsAction() {
        val executed = mutableListOf<GestureAction>()
        setSurface(accessible = true, executed = executed)

        compose.onNodeWithTag(GESTURE_ORB_TAG).performClick()
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithTag(GESTURE_MENU_A11Y_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(gestureMenuSliceTag("jump"), useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(listOf(GestureAction.OpenChatList), executed)
        assertEquals(0, compose.onAllNodesWithTag(GESTURE_MENU_A11Y_DIALOG_TAG).fetchSemanticsNodes().size)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
