package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    @Test
    fun avatarStylePickerSelectsIrcSprites() {
        var selected: AvatarStyle? = null
        setContent(onAvatarStyle = { selected = it })

        compose.onNodeWithTag("settings_avatar_style_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_avatar_style_irc_sprite").performClick()

        assertEquals(AvatarStyle.IRC_SPRITE, selected)
    }

    @Test
    fun chatShadowSwitchUpdatesPreviewAndInvokesCallback() {
        var enabled: Boolean? = null
        setContent(onChatShadowsEnabled = { enabled = it })

        compose.onNodeWithTag("settings_chat_preview_inline_shadows_true").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_switch_chat_shadows", useUnmergedTree = true).performScrollTo().performClick()

        assertEquals(false, enabled)
        compose.onNodeWithTag("settings_chat_preview_inline_shadows_false").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun dismissingThemeSheetKeepsAppearanceControlsAvailable() {
        setContent()

        compose.onNodeWithTag("settings_theme_picker").performClick()
        val themeSheet = hasTestTag("settings_theme_sheet")
        val dismissAction =
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
                (themeSheet or hasAnyAncestor(themeSheet))
        compose
            .onAllNodes(dismissAction, useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.Dismiss)

        compose.onNodeWithTag("settings_theme_sheet", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("settings_avatar_style_picker").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chatLayoutPreviewUpdatesWhileEachChoiceSheetRemainsOpen() {
        var selectedDensity: LayoutDensity? = null
        var selectedSpacing: MessageSpacing? = null
        var selectedCorners: BubbleCornerStyle? = null
        setContent(
            onLayoutDensity = { selectedDensity = it },
            onMessageSpacing = { selectedSpacing = it },
            onBubbleCornerStyle = { selectedCorners = it },
        )

        compose.onNodeWithTag("settings_density_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_density_compact").performClick()
        compose.onNodeWithTag("settings_density_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_density_sheet_options").performScrollToIndex(4)
        compose.onNodeWithTag("settings_chat_preview_sheet_compact_default_rounded").assertExists()
        assertEquals(LayoutDensity.COMPACT, selectedDensity)
        dismissSheet("settings_density_sheet")

        compose.onNodeWithTag("settings_message_spacing_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_message_spacing_relaxed").performClick()
        compose.onNodeWithTag("settings_message_spacing_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_message_spacing_sheet_options").performScrollToIndex(4)
        compose.onNodeWithTag("settings_chat_preview_sheet_compact_relaxed_rounded").assertExists()
        assertEquals(MessageSpacing.RELAXED, selectedSpacing)
        dismissSheet("settings_message_spacing_sheet")

        compose.onNodeWithTag("settings_bubble_corner_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_bubble_corner_square").performClick()
        compose.onNodeWithTag("settings_bubble_corner_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_bubble_corner_sheet_options").performScrollToIndex(4)
        compose.onNodeWithTag("settings_chat_preview_sheet_comfortable_relaxed_square").assertExists()
        assertEquals(BubbleCornerStyle.SQUARE, selectedCorners)
    }

    @Test
    fun chatPreviewAppliesMessageAppearanceConfiguration() {
        val expectedTime = previewTimeText()
        setContent(
            settings = Settings(avatarStyle = AvatarStyle.NONE, nickColorsEnabled = false),
            appearance =
                AppearanceConfig(
                    conversationFontScalePercent = 140,
                    fontChoice = FontChoice.MONOSPACE,
                    showTimestamps = false,
                    timeFormat = TimeFormat.H24,
                    messageSpacing = MessageSpacing.RELAXED,
                    bubbleCornerStyle = BubbleCornerStyle.SQUARE,
                    chatShadowsEnabled = false,
                    wallpaper = WallpaperSelection(ChatWallpaperPreset.NONE),
                ),
        )

        compose.onNodeWithTag("settings_chat_preview_inline_comfortable_relaxed_square").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_chat_preview_inline_wallpaper_none_50").assertExists()
        compose.onNodeWithTag("settings_chat_preview_inline_timestamps_false_h24").assertExists()
        compose.onNodeWithTag("settings_chat_preview_inline_shadows_false").assertExists()
        compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(expectedTime, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun avatarAndTimeSelectionsUpdateInlinePreviewBeforeSettingsEmit() {
        val expectedTime = previewTimeText()
        setContent()

        compose.onNodeWithTag("settings_avatar_style_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_avatar_style_none").performClick()
        compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag("settings_time_format_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_time_format_h24").performClick()
        compose.onNodeWithTag("settings_chat_preview_inline_timestamps_true_h24").performScrollTo().assertExists()
        compose.onNodeWithText(expectedTime, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun dismissSheet(tag: String) {
        val sheet = hasTestTag(tag)
        val dismissAction = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet))
        compose.onAllNodes(dismissAction, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun previewTimeText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(PREVIEW_MESSAGE_TIME_MILLIS))

    private fun setContent(
        settings: Settings = Settings(),
        appearance: AppearanceConfig = AppearanceConfig(),
        onFolderDisplayMode: (FolderDisplayMode) -> Unit = {},
        onShowFolderChatsInAll: (Boolean) -> Unit = {},
        onAvatarStyle: (AvatarStyle) -> Unit = {},
        onLayoutDensity: (LayoutDensity) -> Unit = {},
        onMessageSpacing: (MessageSpacing) -> Unit = {},
        onBubbleCornerStyle: (BubbleCornerStyle) -> Unit = {},
        onChatShadowsEnabled: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AppearanceSettingsContent(
                    settings = settings,
                    appearance = appearance,
                    onBack = {},
                    onOpenNickColors = {},
                    onThemePreset = {},
                    onTrueBlack = {},
                    onFollowSystem = {},
                    onDynamicColor = {},
                    onLayoutDensity = onLayoutDensity,
                    onFolderDisplayMode = onFolderDisplayMode,
                    onShowFolderChatsInAll = onShowFolderChatsInAll,
                    onAvatarStyle = onAvatarStyle,
                    onNickColorsEnabled = {},
                    onNickColorPalette = {},
                    onWallpaper = {},
                    onUiFontScale = {},
                    onConversationFontScale = {},
                    onFontChoice = {},
                    onShowTimestamps = {},
                    onTimeFormat = {},
                    onCustomTimeFormatPattern = {},
                    onMessageSpacing = onMessageSpacing,
                    onBubbleCornerStyle = onBubbleCornerStyle,
                    onChatShadowsEnabled = onChatShadowsEnabled,
                    onLauncherIcon = {},
                )
            }
        }
    }
}

private const val PREVIEW_MESSAGE_TIME_MILLIS = 1_704_110_040_000L
