package io.github.trevarj.motd.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AvatarEditorSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun validatesUrl_resetsLocal_andConfirmsSharedClear() {
        var resets = 0
        var clears = 0
        compose.setContent {
            MotdTheme {
                AvatarEditorSheet(
                    open = true,
                    currentModel = null,
                    isChannel = true,
                    onDismiss = {},
                    onImport = {},
                    onUpload = {},
                    onUrl = {},
                    onReset = { resets += 1 },
                    onClearShared = { clears += 1 },
                )
            }
        }

        compose.onNodeWithTag("avatar_editor_url").performTextInput("http://example.com/avatar.png")
        compose.onNodeWithTag("avatar_editor_url_save").performClick()
        compose.onNodeWithText("Enter valid HTTPS URL.").assertIsDisplayed()

        compose.onNodeWithTag("avatar_editor_reset").performClick()
        compose.onNodeWithTag("avatar_editor_clear_shared").performClick()
        compose.onNodeWithTag("avatar_editor_clear_dialog").assertIsDisplayed()
        compose.onNodeWithTag("avatar_editor_clear_confirm").performClick()

        compose.runOnIdle {
            assertEquals(1, resets)
            assertEquals(1, clears)
        }
    }
}
