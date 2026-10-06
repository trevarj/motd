package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        compose.onNodeWithTag("settings_chat_preview_inline_shadows_false").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_switch_chat_shadows", useUnmergedTree = true).performScrollTo().performClick()

        assertEquals(true, enabled)
        compose.onNodeWithTag("settings_chat_preview_inline_shadows_true").performScrollTo().assertIsDisplayed()
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
    fun composerStyleSelectionUpdatesLivePreviewWithoutDismissingSheet() {
        val persistedAppearance = mutableStateOf(AppearanceConfig())
        var selected: ComposerStyle? = null
        setContent(
            settings = Settings(showComposerEmoji = false),
            appearanceProvider = { persistedAppearance.value },
            onComposerStyle = { selected = it },
        )

        compose.onNodeWithTag("settings_composer_style_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_composer_style_compact").performClick().assertIsSelected()
        compose.onNodeWithTag("settings_composer_style_sheet").assertIsDisplayed()
        // Expand the modal itself before scrolling its footer: lazy-list scrolling does not move
        // the sheet's partially expanded offset, which can leave composed controls below the window.
        val sheet = hasTestTag("settings_composer_style_sheet")
        val expandAction = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand) and (sheet or hasAnyAncestor(sheet))
        if (compose.onAllNodes(expandAction, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(expandAction, useUnmergedTree = true).performSemanticsAction(SemanticsActions.Expand) { it() }
        }
        compose.onNodeWithTag("settings_composer_style_sheet_options").performScrollToIndex(4)
        val preview = compose.onNodeWithTag("settings_composer_preview_sheet")
        preview.performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_self_nick", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("alex", useUnmergedTree = true).assertIsDisplayed()
        val tools = compose.onNodeWithTag("chat_composer_tools")
        tools.assertIsDisplayed()
        val toolsBounds = tools.fetchSemanticsNode().boundsInRoot
        val pillBounds = compose.onNodeWithTag("chat_composer_pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Compact tools must fit inside the painted pill", toolsBounds.left >= pillBounds.left && toolsBounds.right <= pillBounds.right)
        assertTrue(toolsBounds.top >= pillBounds.top && toolsBounds.bottom <= pillBounds.bottom)
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
        compose.onNodeWithTag("chat_composer_attachment").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_ai").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ComposerStyle.COMPACT, selected)
            assertEquals(ComposerStyle.COMFORTABLE, persistedAppearance.value.composerStyle)
        }

        val field = compose.onNodeWithTag("chat_composer_field")
        field.performTextReplacement("hello")
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        val collapsedPreviewHeight =
            compose
                .onNodeWithTag("chat_composer_pill")
                .fetchSemanticsNode()
                .boundsInRoot.height
        tools.performClick().assertIsSelected()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        assertTrue(
            "The real preview uses native single-row scrolling",
            compose
                .onNodeWithTag("chat_composer_format_toolbar")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.ScrollBy),
        )
        compose.onNodeWithTag("chat_composer_overflow").assertDoesNotExist()
        val expandedPreviewPill = compose.onNodeWithTag("chat_composer_pill").fetchSemanticsNode().boundsInRoot
        val expandedPreviewTools = compose.onNodeWithTag("chat_composer_format_toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("The real preview must paint one expanded pill around its tools", expandedPreviewPill.height > collapsedPreviewHeight)
        assertTrue(expandedPreviewTools.left >= expandedPreviewPill.left && expandedPreviewTools.right <= expandedPreviewPill.right)
        assertTrue(expandedPreviewTools.top >= expandedPreviewPill.top && expandedPreviewTools.bottom <= expandedPreviewPill.bottom)
        compose.onNodeWithTag("chat_composer_emoji").assertDoesNotExist()
        compose
            .onNodeWithTag("chat_format_bold")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
            .assertIsSelected()
        field.assertTextEquals("hello")
        compose
            .onNodeWithTag("chat_composer_format_expand")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("settings_composer_style_sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").performClick().assertIsNotSelected()
        compose.onNodeWithTag("chat_format_markdown").performScrollTo().assertIsEnabled()
        tools.performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        assertEquals(
            collapsedPreviewHeight,
            compose
                .onNodeWithTag("chat_composer_pill")
                .fetchSemanticsNode()
                .boundsInRoot.height,
            0.5f,
        )

        compose.onNodeWithTag("settings_composer_style_sheet_options").performScrollToIndex(2)
        compose.onNodeWithTag("settings_composer_style_large").performClick().assertIsSelected()
        compose.onNodeWithTag("settings_composer_style_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_composer_style_sheet_options").performScrollToIndex(4)
        preview.performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_self_nick", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat_format_bold").performScrollTo().assertIsSelected()
        field.assertTextEquals("hello")
        compose.runOnIdle {
            assertEquals(ComposerStyle.LARGE, selected)
            persistedAppearance.value = persistedAppearance.value.copy(composerStyle = requireNotNull(selected))
        }
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        field.assertTextEquals("hello")
        compose.onNodeWithTag("settings_composer_style_sheet_options").performScrollToIndex(2)
        compose.onNodeWithTag("settings_composer_style_large").assertIsSelected()
        compose.onNodeWithTag("settings_composer_style_sheet").assertIsDisplayed()
        dismissSheet("settings_composer_style_sheet")
        compose.runOnIdle { assertEquals(ComposerStyle.LARGE, persistedAppearance.value.composerStyle) }
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

    @Test
    fun wallpaperPreviewKeepsImmediateEditsAcrossStaleEmissionsAndExplicitImport() {
        val appearance = mutableStateOf(AppearanceConfig())
        val imported = mutableStateOf<WallpaperSelection?>(null)
        var changes = 0
        setContent(
            appearanceProvider = { appearance.value },
            importedWallpaperProvider = { imported.value },
            onWallpaper = {
                changes++
                appearance.value =
                    AppearanceConfig(
                        wallpaper = WallpaperSelection(ChatWallpaperPreset.NONE, 30),
                        chatShadowsEnabled = changes % 2 == 1,
                    )
            },
        )
        compose.onNodeWithTag("settings_wallpaper_picker").performScrollTo().performClick()
        val list = compose.onNodeWithTag("settings_wallpaper_list")
        list.performScrollToNode(hasTestTag("settings_wallpaper_preset_deep_space"))
        compose.onNodeWithTag("settings_wallpaper_preset_deep_space").performClick()
        list.performScrollToNode(hasTestTag("settings_wallpaper_intensity"))
        compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        compose.runOnIdle {
            imported.value = WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80, "12345678-1234-1234-1234-123456789abc.image")
        }
        list.performScrollToNode(hasTestTag("settings_wallpaper_remove"))
        compose.onNodeWithTag("settings_wallpaper_remove").assertIsEnabled()
        list.performScrollToNode(hasTestTag("settings_wallpaper_done"))
        compose.onNodeWithTag("settings_wallpaper_done").performClick()
        compose.onNodeWithTag("settings_chat_preview_inline_wallpaper_custom_80").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_wallpaper_picker").performScrollTo().performClick()
        list.performScrollToNode(hasTestTag("settings_wallpaper_remove"))
        compose.onNodeWithTag("settings_wallpaper_remove").performClick()
        list.performScrollToNode(hasTestTag("settings_wallpaper_done"))
        compose.onNodeWithTag("settings_wallpaper_done").performClick()
        compose.onNodeWithTag("settings_chat_preview_inline_wallpaper_deep_space_80").performScrollTo().assertIsDisplayed()
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
        appearanceProvider: () -> AppearanceConfig = { appearance },
        onFolderDisplayMode: (FolderDisplayMode) -> Unit = {},
        onShowFolderChatsInAll: (Boolean) -> Unit = {},
        onAvatarStyle: (AvatarStyle) -> Unit = {},
        onLayoutDensity: (LayoutDensity) -> Unit = {},
        onComposerStyle: (ComposerStyle) -> Unit = {},
        onMessageSpacing: (MessageSpacing) -> Unit = {},
        onBubbleCornerStyle: (BubbleCornerStyle) -> Unit = {},
        onChatShadowsEnabled: (Boolean) -> Unit = {},
        onWallpaper: (WallpaperSelection) -> Unit = {},
        importedWallpaperProvider: () -> WallpaperSelection? = { null },
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AppearanceSettingsContent(
                    settings = settings,
                    appearance = appearanceProvider(),
                    onBack = {},
                    onOpenNickColors = {},
                    onThemePreset = {},
                    onTrueBlack = {},
                    onFollowSystem = {},
                    onDynamicColor = {},
                    onLayoutDensity = onLayoutDensity,
                    onComposerStyle = onComposerStyle,
                    onFolderDisplayMode = onFolderDisplayMode,
                    onShowFolderChatsInAll = onShowFolderChatsInAll,
                    onAvatarStyle = onAvatarStyle,
                    onNickColorsEnabled = {},
                    onNickColorPalette = {},
                    onWallpaper = onWallpaper,
                    importedWallpaper = importedWallpaperProvider(),
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
