package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.backup.BackupImportMode
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
class BackupRestoreControlsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun passwordField_exposesVisibilityToggle() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                PasswordField(
                    value = "correct horse battery staple",
                    onValueChange = {},
                    label = "Backup password",
                    modifier = Modifier.testTag("password"),
                )
            }
        }

        compose.onNodeWithContentDescription("Show password").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
    }

    @Test fun importMode_fullRowSelectsTheOption() {
        var selected by mutableStateOf(BackupImportMode.MERGE)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ImportModeRow(selected = selected, onSelected = { selected = it })
            }
        }

        compose.onNodeWithTag("backup_import_mode_replace").performClick()

        compose.runOnIdle { assertEquals(BackupImportMode.REPLACE, selected) }
    }
}
