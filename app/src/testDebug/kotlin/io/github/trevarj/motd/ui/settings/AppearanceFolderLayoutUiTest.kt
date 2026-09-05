package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceFolderLayoutUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun folderLayoutControlsInvokeCallbacks() {
        var selected: FolderDisplayMode? = null
        var showFolderChatsInAll: Boolean? = null
        setContent(
            settings = Settings(folderDisplayMode = FolderDisplayMode.TABS),
            onFolderDisplayMode = { selected = it },
            onShowFolderChatsInAll = { showFolderChatsInAll = it },
        )

        compose.onNodeWithTag("settings_folder_layout_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_folder_layout_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_folder_layout_tabs").performClick()
        compose.onNodeWithTag("settings_switch_show_folder_chats_in_all", useUnmergedTree = true).performScrollTo().performClick()

        assertEquals(FolderDisplayMode.TABS, selected)
        assertEquals(false, showFolderChatsInAll)
    }

    @Test
    fun inlineLayoutExplainsDisabledFolderChatsToggle() {
        setContent(settings = Settings(folderDisplayMode = FolderDisplayMode.INLINE))

        compose
            .onNodeWithText("Choose Tabs for folder layout to configure this.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setContent(
        settings: Settings,
        onFolderDisplayMode: (FolderDisplayMode) -> Unit = {},
        onShowFolderChatsInAll: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AppearanceSettingsContent(
                    settings = settings,
                    appearance = AppearanceConfig(),
                    onBack = {},
                    onOpenNickColors = {},
                    onThemePreset = {},
                    onTrueBlack = {},
                    onFollowSystem = {},
                    onDynamicColor = {},
                    onLayoutDensity = {},
                    onFolderDisplayMode = onFolderDisplayMode,
                    onShowFolderChatsInAll = onShowFolderChatsInAll,
                    onAvatarStyle = {},
                    onNickColorsEnabled = {},
                    onNickColorPalette = {},
                    onWallpaper = {},
                    onUiFontScale = {},
                    onConversationFontScale = {},
                    onFontChoice = {},
                    onShowTimestamps = {},
                    onTimeFormat = {},
                    onCustomTimeFormatPattern = {},
                    onMessageSpacing = {},
                    onBubbleCornerStyle = {},
                    onLauncherIcon = {},
                )
            }
        }
    }
}
