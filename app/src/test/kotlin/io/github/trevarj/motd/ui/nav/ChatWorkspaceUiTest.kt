package io.github.trevarj.motd.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1100dp-h600dp-land-mdpi")
class ChatWorkspaceUiTest {
    @get:Rule val compose = createComposeRule()

    private val windowWidthDp = mutableIntStateOf(1_000)
    private val destination = mutableIntStateOf(0)
    private var touchSlop = 0f

    @Test
    fun `dragged list width returns after constrained fixed and single pane layouts`() {
        showWorkspace()
        listPane().assertWidthIsEqualTo(320.dp)
        divider().performTouchInput {
            down(center)
            moveBy(Offset(200.dp.toPx(), 0f))
            up()
        }
        listPane().assertWidthIsEqualTo(420.dp)

        resizeTo(840)
        listPane().assertWidthIsEqualTo(408.dp)
        divider().performTouchInput {
            down(center)
            moveBy(Offset(50.dp.toPx(), 0f))
            up()
        }
        listPane().assertWidthIsEqualTo(408.dp)

        resizeTo(780)
        listPane().assertWidthIsEqualTo(280.dp)
        divider().assertDoesNotExist()

        resizeTo(700)
        listPane().assertDoesNotExist()
        compose.onNodeWithTag("detailWithBack").assertWidthIsEqualTo(700.dp)

        resizeTo(1_000)
        listPane().assertWidthIsEqualTo(420.dp)

        compose.runOnIdle { destination.intValue++ }
        listPane().assertWidthIsEqualTo(420.dp)
    }

    @Test
    fun `RTL drag reverses at the bound and starts from the visible width`() {
        showWorkspace(LayoutDirection.Rtl)
        divider().performTouchInput {
            down(center)
            moveBy(Offset(-200.dp.toPx(), 0f))
            moveBy(Offset(8.dp.toPx(), 0f))
            up()
        }
        listPane().assertWidthIsEqualTo(412.dp)

        resizeTo(840)
        listPane().assertWidthIsEqualTo(408.dp)
        divider().performTouchInput {
            down(center)
            moveBy(Offset(touchSlop + 20.dp.toPx(), 0f))
            up()
        }
        listPane().assertWidthIsEqualTo(388.dp)

        resizeTo(1_000)
        listPane().assertWidthIsEqualTo(388.dp)
    }

    private fun showWorkspace(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            val listWidth = rememberSaveable { mutableFloatStateOf(CHAT_LIST_INITIAL_DP) }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme {
                    Box(
                        Modifier
                            .wrapContentWidth()
                            .width(windowWidthDp.intValue.dp)
                            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    ) {
                        key(destination.intValue) {
                            ChatWorkspace(
                                listWidth = listWidth,
                                listPane = { Box(Modifier.fillMaxSize().testTag("list")) },
                                detailPane = { showBack ->
                                    Box(Modifier.fillMaxSize().testTag(if (showBack) "detailWithBack" else "detail"))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun resizeTo(widthDp: Int) = compose.runOnIdle { windowWidthDp.intValue = widthDp }

    private fun listPane() = compose.onNodeWithTag("list")

    private fun divider() = compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_workspace_resize))
}
