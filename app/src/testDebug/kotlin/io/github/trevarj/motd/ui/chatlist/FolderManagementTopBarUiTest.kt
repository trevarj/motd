package io.github.trevarj.motd.ui.chatlist

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderManagementTopBarUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun autoGroupPrecedesCreateAndUsesItsCallback() {
        var autoGrouped = false
        var created = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                FolderManagementTopBar(
                    onBack = {},
                    onAutoGroup = { autoGrouped = true },
                    onCreate = { created = true },
                )
            }
        }

        val autoGroup = compose.onNodeWithTag("folders_auto_group")
        val create = compose.onNodeWithTag("folders_create")
        assertTrue(autoGroup.getUnclippedBoundsInRoot().left < create.getUnclippedBoundsInRoot().left)
        autoGroup.performClick()

        compose.runOnIdle {
            assertTrue(autoGrouped)
            assertTrue(!created)
        }
    }
}
