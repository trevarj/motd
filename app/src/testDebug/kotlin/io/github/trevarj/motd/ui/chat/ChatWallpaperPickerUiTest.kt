package io.github.trevarj.motd.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
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
class ChatWallpaperPickerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun presetAndIntensitySaveImmediatelyAndKeepEditorOpen() {
        var current by mutableStateOf(WallpaperSelection(ChatWallpaperPreset.MOTD, 50))
        val changes = mutableListOf<WallpaperSelection>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatWallpaperPicker(current = current, onChange = {
                    changes += it
                    // An older preference emission must not replace the user's active edit.
                    current = WallpaperSelection(ChatWallpaperPreset.NONE, 30)
                })
            }
        }

        compose.onNodeWithTag("settings_wallpaper_picker").performClick()
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_wallpaper_preset_deep_space").performClick()
        assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 50), changes.last())
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()

        compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80), changes.last())
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()

        compose.onNodeWithTag("settings_wallpaper_done").performClick()
        compose.onNodeWithTag("settings_wallpaper_sheet").assertDoesNotExist()
    }
}
