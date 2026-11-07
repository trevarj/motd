package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.FolderIconKind
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import io.github.trevarj.motd.data.prefs.UiIconSet
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.ui.components.FolderIcon
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.UiIcon
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.fixedThemeScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings as AndroidSettings

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceFolderLayoutUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun appearanceKeepsAppIconChoiceWithoutSplashConfiguration() {
        setContent()
        compose.onNodeWithTag("settings_app_icon_picker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_splash_screen_picker").assertDoesNotExist()
    }

    @Test
    fun iconSetPickerChangesNativeArtworkBeforePersistenceAndRestoresMaterial() {
        val persisted = AppearanceConfig(theme = ColorThemePreset.AYU_LIGHT)
        val selected = mutableListOf<UiIconSet>()
        setContent(
            appearance = persisted,
            onUiIconSet = { selected.add(it) },
            targetProvider = { SettingsTarget.UI_ICON_SET },
        )
        compose.onNodeWithTag("settings_target_highlight_UI_ICON_SET").assertIsDisplayed().performClick()
        val sheet = "settings_icon_set_sheet"
        val tags = listOf("menu", "search", "send", "settings", "play", "dropdown").map { "settings_icon_set_preview_$it" }

        fun capture() =
            tags.map { tag ->
                compose.onNodeWithTag("${sheet}_options").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            }
        scrollToSheetNode(sheet, "settings_icon_set_material").assertIsSelected()
        val material = capture()
        scrollToSheetNode(sheet, "settings_icon_set_lucide").performClick().assertIsSelected()
        compose.onNodeWithTag(sheet).assertIsDisplayed()
        val lucide = capture()
        material.zip(lucide).forEachIndexed { index, (before, after) ->
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            assertTrue("${tags[index]} must paint different real geometry", !before.sameAs(after))
        }
        assertEquals(UiIconSet.MATERIAL, persisted.uiIconSet)
        scrollToSheetNode(sheet, "settings_icon_set_material").performClick().assertIsSelected()
        material.zip(capture()).forEach { (before, restored) ->
            assertTrue("Material must restore its exact native pixels", before.sameAs(restored))
        }
        assertEquals(listOf(UiIconSet.LUCIDE, UiIconSet.MATERIAL), selected)
        dismissSheet(sheet)
        compose.onNodeWithTag("settings_target_highlight_UI_ICON_SET").performClick()
        scrollToSheetNode(sheet, "settings_icon_set_material").assertIsSelected()
    }

    @Test
    fun iconPreviewMirrorsDirectionalGlyphsInRtlForBothSetsAndRestoresDefault() {
        val iconSet = mutableStateOf(UiIconSet.MATERIAL)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val forceReflection = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MotdTheme(dynamicColor = false, uiIconSet = iconSet.value) {
                    // Keep placement fixed while changing only the painters' layout direction.
                    Row {
                        CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                            UiIcon(
                                Icons.AutoMirrored.Filled.Send,
                                null,
                                Modifier
                                    .size(32.dp)
                                    .drawWithContent {
                                        if (forceReflection.value) {
                                            scale(-1f, 1f) { this@drawWithContent.drawContent() }
                                        } else {
                                            drawContent()
                                        }
                                    }.testTag("settings_icon_set_preview_send"),
                            )
                            UiIcon(Icons.Filled.Menu, null, Modifier.size(32.dp).testTag("settings_icon_set_preview_menu"))
                        }
                    }
                }
            }
        }

        fun capture(name: String) = compose.onNodeWithTag("settings_icon_set_preview_$name", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val original = capture("send")
        for (set in listOf(UiIconSet.MATERIAL, UiIconSet.LUCIDE, UiIconSet.MATERIAL)) {
            compose.runOnIdle {
                direction.value = LayoutDirection.Ltr
                iconSet.value = set
            }
            val send = capture("send")
            val menu = capture("menu")
            // Use a native reflection of the LTR painter, not byte-reversal of a tinted capture.
            compose.runOnIdle { forceReflection.value = true }
            val expected = capture("send")
            compose.runOnIdle {
                forceReflection.value = false
                direction.value = LayoutDirection.Rtl
            }
            val mirrored = capture("send")
            assertTrue("$set directional artwork must change in RTL", !send.sameAs(mirrored))
            assertTrue("$set must match the native reflection of the LTR vector exactly", expected.sameAs(mirrored))
            assertTrue("$set non-directional artwork must not mirror", menu.sameAs(capture("menu")))
        }
        compose.runOnIdle { direction.value = LayoutDirection.Ltr }
        assertTrue("Returning to Material must not retain Lucide geometry", original.sameAs(capture("send")))
    }

    @Test
    fun genericFolderAffordanceFollowsSetButExplicitSavedMaterialArtworkDoesNot() {
        val iconSet = mutableStateOf(UiIconSet.MATERIAL)
        compose.setContent {
            MotdTheme(dynamicColor = false, uiIconSet = iconSet.value) {
                Row {
                    FolderIcon(FolderIconRef(), null, Modifier.size(48.dp).testTag("generic_folder"))
                    FolderIcon(FolderIconRef(FolderIconKind.MATERIAL, "folder"), null, Modifier.size(48.dp).testTag("explicit_folder"))
                }
            }
        }

        fun capture(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val generic = capture("generic_folder")
        val explicit = capture("explicit_folder")
        compose.runOnIdle { iconSet.value = UiIconSet.LUCIDE }
        assertTrue(!generic.sameAs(capture("generic_folder")))
        assertTrue("Saved Material choices retain their exact pixels", explicit.sameAs(capture("explicit_folder")))
        compose.runOnIdle { iconSet.value = UiIconSet.MATERIAL }
        assertTrue(generic.sameAs(capture("generic_folder")))
        assertTrue(explicit.sameAs(capture("explicit_folder")))
    }

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
        scrollToSheetNode("settings_folder_layout_sheet", "settings_folder_layout_tabs").performClick().assertIsSelected()
        scrollToSheetNode("settings_folder_layout_sheet", "settings_folder_preview_all_switch_row").performClick()
        scrollToSheetNode("settings_folder_layout_sheet", "settings_folder_preview").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_-1").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_folder_tab_-1").performClick()
        compose.onNodeWithTag("chatlist_row_-1").assertExists()

        assertEquals(FolderDisplayMode.TABS, selected)
        assertEquals(false, showFolderChatsInAll)
    }

    @Test
    fun inlineLayoutDisablesFolderChatsToggle() {
        setContent(settings = Settings(folderDisplayMode = FolderDisplayMode.INLINE))

        compose
            .onNodeWithTag("settings_switch_show_folder_chats_in_all_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun searchTargetsScrollToReorganizedControlsAndKeepBothPreviewsLive() {
        val context = RuntimeEnvironment.getApplication()
        val entries = buildSettingsSearchEntries(emptyList(), { context.getString(it) }, { network, section -> "$network · $section" })
        val nicknameTarget = (searchSettings("sender tint", entries).single().destination as SettingsSearchDestination.Page).target
        val bubbleTarget =
            (
                searchSettings("rounded square", entries)
                    .single {
                        it.destination == SettingsSearchDestination.Page(SettingsSearchPage.APPEARANCE, SettingsTarget.BUBBLE_CORNERS)
                    }.destination as SettingsSearchDestination.Page
            ).target
        val target = mutableStateOf(nicknameTarget)
        var backgrounds: Boolean? = null
        var corners: BubbleCornerStyle? = null
        setContent(
            targetProvider = { target.value },
            onNicknameBackgroundsEnabled = { backgrounds = it },
            onBubbleCornerStyle = { corners = it },
        )

        val toggle = compose.onNodeWithTag("settings_target_highlight_NICKNAME_BACKGROUNDS", useUnmergedTree = true)
        toggle
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertEquals(false, backgrounds)

        compose.runOnIdle { target.value = bubbleTarget }
        compose.onNodeWithTag("settings_target_highlight_BUBBLE_CORNERS").assertIsDisplayed().performClick()
        scrollToSheetNode("settings_bubble_corner_sheet", "settings_bubble_corner_square").performClick().assertIsSelected()
        assertEquals(BubbleCornerStyle.SQUARE, corners)
        dismissSheet("settings_bubble_corner_sheet")
        compose.onNodeWithTag("settings_shadow_preview_comfortable_default_square").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_chat_preview_inline_comfortable_default_square").performScrollTo().assertIsDisplayed()

        compose.runOnIdle { target.value = nicknameTarget }
        toggle.assertIsDisplayed().assertIsOff()
    }

    @Test
    fun avatarStylePickerSelectsIrcSprites() {
        var selected: AvatarStyle? = null
        setContent(onAvatarStyle = { selected = it })

        compose.onNodeWithTag("settings_avatar_style_picker").performScrollTo().performClick()
        scrollToSheetNode("settings_avatar_style_sheet", "settings_avatar_style_irc_sprite").performClick().assertIsSelected()
        compose.onNodeWithTag("settings_avatar_style_sheet").assertIsDisplayed()
        scrollToSheetNode("settings_avatar_style_sheet", "settings_avatar_preview_irc_sprite").assertExists()

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
    fun nicknameBackgroundSwitchChangesDensePreviewBeforePersistenceEmits() {
        val persisted = AppearanceConfig(theme = ColorThemePreset.AYU_LIGHT, wallpaper = WallpaperSelection(ChatWallpaperPreset.NONE))
        var selected: Boolean? = null
        setContent(
            settings = Settings(layoutDensity = LayoutDensity.COMPACT, dynamicColor = false),
            appearance = persisted,
            onNicknameBackgroundsEnabled = { selected = it },
        )
        for (density in listOf("compact", "two_line")) {
            if (density == "two_line") {
                compose.onNodeWithTag("settings_density_picker").performScrollTo().performClick()
                scrollToSheetNode("settings_density_sheet", "settings_density_two_line").performClick()
                dismissSheet("settings_density_sheet")
            }
            val preview = compose.onNodeWithTag("settings_chat_preview_inline_${density}_default_rounded")
            val toggle = compose.onNodeWithTag("settings_switch_nickname_backgrounds_row", useUnmergedTree = true)
            // Approach every capture from below so scrolling gives the preview the same viewport.
            toggle.performScrollTo().assertIsOn()
            preview.performScrollTo().assertIsDisplayed()
            val tinted = preview.captureToImage().asAndroidBitmap()
            toggle
                .performScrollTo()
                .assertIsOn()
                .performClick()
                .assertIsOff()
            assertEquals(false, selected)
            assertEquals(true, persisted.nicknameBackgroundsEnabled)
            preview.performScrollTo().assertIsDisplayed()
            val plain = preview.captureToImage().asAndroidBitmap()
            assertTrue("$density live preview must lose its sender wash", !tinted.sameAs(plain))
            toggle.performScrollTo().performClick().assertIsOn()
            assertEquals(true, selected)
            preview.performScrollTo().assertIsDisplayed()
            assertTrue("$density live preview must restore its sender wash", tinted.sameAs(preview.captureToImage().asAndroidBitmap()))
        }
    }

    @Test
    fun messageLayoutOpacityIsAlwaysAvailableAndPreviewsDraftBeforePersistence() {
        val persisted = AppearanceConfig(theme = ColorThemePreset.AYU_LIGHT, wallpaper = WallpaperSelection(ChatWallpaperPreset.NONE))
        val settings = Settings(layoutDensity = LayoutDensity.COMFORTABLE, dynamicColor = false, avatarStyle = AvatarStyle.NONE)
        var selectedOpacity: Int? = null
        var selectedLayout: LayoutDensity? = null
        setContent(
            settings = settings,
            appearance = persisted,
            onLayoutDensity = { selectedLayout = it },
            onMessageBackgroundOpacity = { selectedOpacity = it },
        )
        compose.onNodeWithTag("settings_message_background_opacity").assertDoesNotExist()
        compose.onNodeWithTag("settings_density_picker").performScrollTo().performClick()
        for (layout in LayoutDensity.entries) {
            val option = "settings_density_${layout.name.lowercase()}"
            scrollToSheetNode("settings_density_sheet", option).performClick()
            scrollToSheetNode("settings_density_sheet", option).assertIsSelected()
            assertEquals(layout, selectedLayout)
            val preview = compose.onNodeWithTag("settings_chat_preview_sheet_${layout.name.lowercase()}_default_rounded")
            preview.performScrollTo()
            val baseline = preview.captureToImage().asAndroidBitmap()
            var intermediate: android.graphics.Bitmap? = null
            for (percent in listOf(37, 0, 100)) {
                val slider = scrollToSheetNode("settings_density_sheet", "settings_message_background_opacity")
                slider.assertIsEnabled().performSemanticsAction(SemanticsActions.SetProgress) { it(percent.toFloat()) }
                slider.assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(percent.toFloat(), 0f..100f, 0)),
                )
                assertTrue(slider.fetchSemanticsNode().boundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
                assertEquals(percent, selectedOpacity)
                assertEquals(100, persisted.messageBackgroundOpacityPercent)
                preview.performScrollTo()
                val pixels = preview.captureToImage().asAndroidBitmap()
                when (percent) {
                    37 -> {
                        intermediate = pixels
                        assertTrue("$layout preview changes immediately", !baseline.sameAs(pixels))
                    }

                    0 -> {
                        assertTrue("$layout has continuous backing opacity", !requireNotNull(intermediate).sameAs(pixels))
                        assertTrue("$layout zero differs from baseline", !baseline.sameAs(pixels))
                    }

                    100 -> {
                        assertTrue("$layout resets exactly", baseline.sameAs(pixels))
                    }
                }
            }
        }
        dismissSheet("settings_density_sheet")
        compose
            .onNodeWithTag("settings_switch_nickname_backgrounds_row", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
            .assertIsOff()
        compose.onNodeWithTag("settings_density_picker").performScrollTo().performClick()
        scrollToSheetNode("settings_density_sheet", "settings_message_background_opacity").assertIsEnabled()
        assertEquals(true, persisted.nicknameBackgroundsEnabled)
        assertEquals(LayoutDensity.COMFORTABLE, settings.layoutDensity)
    }

    @Test
    fun opacitySearchOpensMessageLayoutHighlightsFooterAndCanBeDismissed() {
        val persisted = AppearanceConfig(messageBackgroundOpacityPercent = 37)
        val target = mutableStateOf<SettingsTarget?>(SettingsTarget.MESSAGE_BACKGROUND_OPACITY)
        var selectedOpacity: Int? = null
        setContent(
            appearance = persisted,
            targetProvider = { target.value },
            onMessageBackgroundOpacity = { selectedOpacity = it },
        )
        compose.onNodeWithTag("settings_density_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_target_highlight_MESSAGE_BACKGROUND_OPACITY").assertIsDisplayed()
        val slider = scrollToSheetNode("settings_density_sheet", "settings_message_background_opacity")
        slider.assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(37f, 0f..100f, 0)),
        )
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        slider.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                RuntimeEnvironment.getApplication().getString(io.github.trevarj.motd.R.string.settings_message_background_opacity_off),
            ),
        )
        assertEquals(0, selectedOpacity)
        dismissSheet("settings_density_sheet")
        compose.waitForIdle()
        compose.onNodeWithTag("settings_density_sheet").assertDoesNotExist()
        compose.runOnIdle { target.value = null }
        compose.runOnIdle { target.value = SettingsTarget.MESSAGE_BACKGROUND_OPACITY }
        compose.onNodeWithTag("settings_density_sheet").assertIsDisplayed()
        scrollToSheetNode("settings_density_sheet", "settings_message_background_opacity").assertIsEnabled()
        assertEquals(37, persisted.messageBackgroundOpacityPercent)
    }

    @Test
    fun themeTextColorSwitchUpdatesOrdinaryTextAndMessagePreviewLive() {
        val appearance = mutableStateOf(AppearanceConfig(theme = ColorThemePreset.GRUVBOX_DARK))
        var selected: Boolean? = null
        setContent(
            appearanceProvider = { appearance.value },
            onUseThemeTextColors = {
                selected = it
                appearance.value = appearance.value.copy(useThemeTextColors = it)
            },
        )
        val preview = compose.onNodeWithTag("settings_chat_preview_inline_comfortable_default_rounded")
        val body = "See you in the channel!"
        preview.performScrollTo().assertIsDisplayed()
        val static = requireNotNull(fixedThemeScheme(ColorThemePreset.GRUVBOX_DARK))
        val staticBody = inkOf(body, "settings_chat_preview_inline_configuration")
        assertEquals(ensureContrast(Color(0xFFF5F5F5), listOf(static.secondaryContainer), MESSAGE_TEXT_CONTRAST), staticBody)

        compose
            .onNodeWithTag("settings_switch_theme_text_colors_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("settings_switch_theme_text_colors_row", useUnmergedTree = true).assertIsOn()
        assertEquals(true, selected)
        val themed = requireNotNull(fixedThemeScheme(ColorThemePreset.GRUVBOX_DARK, useThemeTextColors = true))
        assertEquals(themed.onSurface, inkOf("Appearance"))
        preview.performScrollTo().assertIsDisplayed()
        assertEquals(ensureContrast(themed.onBackground, listOf(themed.secondaryContainer), MESSAGE_TEXT_CONTRAST), inkOf(body, "settings_chat_preview_inline_configuration"))

        compose
            .onNodeWithTag("settings_switch_theme_text_colors_row", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
            .assertIsOff()
        assertEquals(false, selected)
        preview.performScrollTo().assertIsDisplayed()
        assertEquals(staticBody, inkOf(body, "settings_chat_preview_inline_configuration"))
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
        scrollToSheetNode("settings_density_sheet", "settings_density_compact").performClick()
        compose.onNodeWithTag("settings_density_sheet").assertIsDisplayed()
        scrollToSheetNode("settings_density_sheet", "settings_chat_preview_sheet_compact_default_rounded").assertIsDisplayed()
        assertEquals(LayoutDensity.COMPACT, selectedDensity)
        dismissSheet("settings_density_sheet")

        compose.onNodeWithTag("settings_message_spacing_picker").performScrollTo().performClick()
        scrollToSheetNode("settings_message_spacing_sheet", "settings_message_spacing_relaxed").performClick()
        compose.onNodeWithTag("settings_message_spacing_sheet").assertIsDisplayed()
        scrollToSheetNode("settings_message_spacing_sheet", "settings_chat_preview_sheet_compact_relaxed_rounded").assertIsDisplayed()
        assertEquals(MessageSpacing.RELAXED, selectedSpacing)
        dismissSheet("settings_message_spacing_sheet")

        compose.onNodeWithTag("settings_bubble_corner_picker").performScrollTo().performClick()
        scrollToSheetNode("settings_bubble_corner_sheet", "settings_bubble_corner_square").performClick()
        compose.onNodeWithTag("settings_bubble_corner_sheet").assertIsDisplayed()
        scrollToSheetNode("settings_bubble_corner_sheet", "settings_chat_preview_sheet_comfortable_relaxed_square").assertIsDisplayed()
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
        scrollToSheetNode("settings_composer_style_sheet", "settings_composer_style_compact").performClick().assertIsSelected()
        compose.onNodeWithTag("settings_composer_style_sheet").assertIsDisplayed()
        scrollToSheetNode("settings_composer_style_sheet", "settings_composer_preview_sheet")
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

        scrollToSheetNode("settings_composer_style_sheet", "settings_composer_style_large").performClick().assertIsSelected()
        compose.onNodeWithTag("settings_composer_style_sheet").assertIsDisplayed()
        scrollToSheetNode("settings_composer_style_sheet", "settings_composer_preview_sheet")
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
        scrollToSheetNode("settings_composer_style_sheet", "settings_composer_style_large").assertIsSelected()
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
    fun bubbleColorPickerUpdatesSentAndReceivedColorsAndKeepsCustomDraftUntilApply() {
        setContent()

        compose.onNodeWithTag("settings_bubble_color_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_bubble_color_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_bubble_color_composer_preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_bubble_color_primary").performScrollTo().performClick()
        compose.onNodeWithTag("settings_bubble_color_primary").assertIsSelected()
        compose
            .onNodeWithTag("settings_bubble_color_received")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("settings_bubble_color_tertiary_container").performScrollTo().performClick()
        compose.onNodeWithTag("settings_bubble_color_tertiary_container").assertIsSelected()
        compose.onNodeWithTag("settings_bubble_color_custom").performScrollTo().performClick()
        compose.onNodeWithTag("settings_bubble_color_hex").performScrollTo().performTextReplacement("#bad")
        compose.onNodeWithTag("settings_bubble_color_apply").assertIsNotEnabled()
        compose.onNodeWithTag("settings_bubble_color_cancel").performClick()
        compose.onNodeWithTag("settings_bubble_color_custom").performScrollTo().performClick()
        compose.onNodeWithTag("settings_bubble_color_hex").performScrollTo().performTextReplacement("#12AB34")
        compose.onNodeWithTag("settings_bubble_color_tertiary_container").assertIsSelected()
        compose.onNodeWithTag("settings_bubble_color_apply").performClick()
        compose.onNodeWithTag("settings_bubble_color_custom").performScrollTo().assertIsSelected()
    }

    @Test
    fun bubbleColorPickerShowsLiveSentReceivedAndOptionSwatches() {
        setContent(appearance = AppearanceConfig(theme = ColorThemePreset.GRUVBOX_DARK))
        val scheme = requireNotNull(fixedThemeScheme(ColorThemePreset.GRUVBOX_DARK))

        compose.onNodeWithTag("settings_bubble_color_picker").performScrollTo().performClick()
        assertRoundedSelectedTarget("settings_bubble_color_sent", scheme.secondaryContainer.toArgb())
        val sentDefault = swatchPixel("settings_bubble_color_sent_swatch")
        assertEquals(scheme.secondaryContainer.toArgb(), swatchPixel("settings_bubble_color_received_swatch"))
        assertTrue("Default sent and received fills remain distinguishable", sentDefault != swatchPixel("settings_bubble_color_received_swatch"))
        assertEquals(scheme.primary.toArgb(), swatchPixel("settings_bubble_color_primary_swatch"))

        compose.onNodeWithTag("settings_bubble_color_primary").performScrollTo().performClick()
        assertEquals(scheme.primary.toArgb(), swatchPixel("settings_bubble_color_sent_swatch"))

        compose
            .onNodeWithTag("settings_bubble_color_received")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertRoundedSelectedTarget("settings_bubble_color_received", scheme.secondaryContainer.toArgb())
        assertEquals(scheme.secondaryContainer.toArgb(), swatchPixel("settings_bubble_color_default_swatch"))
        compose
            .onNodeWithTag("settings_bubble_color_custom")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("settings_bubble_color_hex").performScrollTo().performTextReplacement("#12AB34")
        compose.onNodeWithTag("settings_bubble_color_apply").performClick()

        val custom = Color(0xFF12AB34).toArgb()
        assertEquals(custom, swatchPixel("settings_bubble_color_received_swatch"))
        assertEquals(custom, swatchPixel("settings_bubble_color_custom_swatch"))
        assertEquals(scheme.primary.toArgb(), swatchPixel("settings_bubble_color_sent_swatch"))
    }

    @Test
    fun avatarAndTimeSelectionsUpdateInlinePreviewBeforeSettingsEmit() {
        val expectedTime = previewTimeText()
        setContent()

        compose.onNodeWithTag("settings_avatar_style_picker").performScrollTo().performClick()
        scrollToSheetNode("settings_avatar_style_sheet", "settings_avatar_style_none").performClick()
        scrollToSheetNode("settings_avatar_style_sheet", "settings_avatar_preview_none").assertExists()
        dismissSheet("settings_avatar_style_sheet")
        compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag("settings_time_format_picker").performScrollTo().performClick()
        scrollToSheetNode("settings_time_format_sheet", "settings_time_format_h24").performClick().assertIsSelected()
        scrollToSheetNode("settings_time_format_sheet", "settings_time_preview_value").assertExists()
        dismissSheet("settings_time_format_sheet")
        compose.onNodeWithTag("settings_chat_preview_inline_timestamps_true_h24").performScrollTo().assertExists()
        compose.onNode(hasText(expectedTime) and hasAnyAncestor(hasTestTag("settings_chat_preview_inline_configuration")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun deviceTimeFormatLabelsFollowNativeChangesAndResumeWithoutChangingAuto() {
        val context = RuntimeEnvironment.getApplication()
        val resolver = context.contentResolver
        val original = AndroidSettings.System.getString(resolver, AndroidSettings.System.TIME_12_24)
        val persisted = AppearanceConfig(timeFormat = TimeFormat.AUTO)
        val selected = mutableListOf<TimeFormat>()
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val sheet = "settings_time_format_sheet"

        fun label(format: Int) =
            context.getString(
                io.github.trevarj.motd.R.string.settings_time_format_auto,
                context.getString(format),
            )
        val twelveHour = label(io.github.trevarj.motd.R.string.settings_time_format_h12)
        val twentyFourHour = label(io.github.trevarj.motd.R.string.settings_time_format_h24)
        try {
            AndroidSettings.System.putString(resolver, AndroidSettings.System.TIME_12_24, "12")
            setContent(appearance = persisted, onTimeFormat = selected::add, lifecycleOwner = owner)
            val row = compose.onNodeWithTag("settings_time_format_picker")
            row.performScrollTo().assert(hasText(twelveHour))
            compose.runOnIdle { AndroidSettings.System.putString(resolver, AndroidSettings.System.TIME_12_24, "24") }
            row.assert(hasText(twentyFourHour)).performClick()
            scrollToSheetNode(sheet, "settings_time_format_auto").assertIsSelected().assertTextEquals(twentyFourHour)

            // The already-open sheet must refresh without a tap or an appearance emission.
            compose.runOnIdle { AndroidSettings.System.putString(resolver, AndroidSettings.System.TIME_12_24, "12") }
            compose.onNodeWithTag("settings_time_format_auto").assertIsSelected().assertTextEquals(twelveHour)
            dismissSheet(sheet)
            row.assert(hasText(twelveHour))

            // Returning from native settings keeps the same composition and stored AUTO choice.
            compose.runOnIdle {
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                AndroidSettings.System.putString(resolver, AndroidSettings.System.TIME_12_24, "24")
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            row.assert(hasText(twentyFourHour)).performClick()
            scrollToSheetNode(sheet, "settings_time_format_auto").assertIsSelected().assertTextEquals(twentyFourHour)
            assertEquals(TimeFormat.AUTO, persisted.timeFormat)
            assertTrue("Resolving the device setting must not persist a different choice", selected.isEmpty())
        } finally {
            compose.runOnUiThread { AndroidSettings.System.putString(resolver, AndroidSettings.System.TIME_12_24, original) }
        }
    }

    @Test
    fun wallpaperPreviewKeepsImmediateEditsAcrossStaleEmissionsAndExplicitImport() {
        val appearance = mutableStateOf(AppearanceConfig())
        val imported = mutableStateOf<WallpaperSelection?>(null)
        var applied: WallpaperSelection? = null
        var changes = 0
        setContent(
            appearanceProvider = { appearance.value },
            importedWallpaperProvider = { imported.value },
            onWallpaper = {
                changes++
                applied = it
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
        list.performScrollToNode(hasTestTag("settings_wallpaper_outline_color"))
        compose.onNodeWithTag("settings_wallpaper_outline_color").performClick()
        compose.onNodeWithTag("settings_wallpaper_color_hex").performScrollTo().performTextReplacement("#12AB34")
        compose.onNodeWithTag("settings_wallpaper_color_apply").performClick()
        assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80, outlineColor = 0xff12ab34.toInt()), applied)
        compose.runOnIdle {
            imported.value = WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80, "12345678-1234-1234-1234-123456789abc.image", 0xff12ab34.toInt())
        }
        list.performScrollToNode(hasTestTag("settings_wallpaper_remove"))
        compose.onNodeWithTag("settings_wallpaper_remove").assertIsEnabled()
        list.performScrollToNode(hasTestTag("settings_wallpaper_done"))
        compose.onNodeWithTag("settings_wallpaper_done").performClick()
        compose.onNodeWithTag("settings_chat_preview_inline_wallpaper_custom_80").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_wallpaper_picker").performScrollTo().performClick()
        list.performScrollToNode(hasTestTag("settings_wallpaper_remove"))
        compose.onNodeWithTag("settings_wallpaper_remove").performClick()
        assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80, outlineColor = 0xff12ab34.toInt()), applied)
        list.performScrollToNode(hasTestTag("settings_wallpaper_done"))
        compose.onNodeWithTag("settings_wallpaper_done").performClick()
        compose.onNodeWithTag("settings_chat_preview_inline_wallpaper_deep_space_80").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp", fontScale = 2f)
    fun largeFontPickerKeepsLongImportedNameAndChangeActionReachable() {
        val filename = "My readable custom conversation and interface typeface with a long filename.ttf"
        var selected: FontChoice? = null
        setContent(
            appearance = AppearanceConfig(customFontName = filename, uiFontScalePercent = 140),
            onFontChoice = { selected = it },
        )
        compose.onNodeWithTag("settings_font_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_font_options").performScrollToNode(hasTestTag("settings_font_custom"))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(filename, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The full imported name wraps at native large fonts", layouts.single().lineCount > 1)
        assertTrue("The imported name is not clipped or ellipsized", !layouts.single().hasVisualOverflow)
        compose.onNodeWithText("Change").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_font_custom").performClick().assertIsSelected()
        compose.onNodeWithTag("settings_font_sheet").assertIsDisplayed()
        compose.runOnIdle { assertEquals(FontChoice.CUSTOM, selected) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun selectedFontSamplesAndCustomChangeStayReadableInOneDarkThemeText() {
        val filename = "Imported conversation font.ttf"
        val context = RuntimeEnvironment.getApplication()
        val customFont = java.io.File.createTempFile("palette-imported-", ".ttf", context.cacheDir)
        context.resources.openRawResource(io.github.trevarj.motd.R.font.jetbrains_mono_wght).use { source ->
            customFont.outputStream().use { source.copyTo(it) }
        }
        setContent(
            customFontFile = customFont,
            appearance =
                AppearanceConfig(
                    theme = ColorThemePreset.ONE_DARK,
                    useThemeTextColors = true,
                    customFontName = filename,
                ),
        )
        compose.onNodeWithTag("settings_font_picker").performScrollTo().performClick()
        val captures = mutableListOf<android.graphics.Bitmap>()
        for (choice in listOf(FontChoice.SYSTEM, FontChoice.SERIF, FontChoice.CUSTOM, FontChoice.SYSTEM)) {
            val tag = "settings_font_${choice.name.lowercase()}"
            compose.onNodeWithTag("settings_font_options").performScrollToNode(hasTestTag(tag))
            val row = compose.onNodeWithTag(tag).performClick().assertIsSelected()
            compose.onNodeWithTag("settings_font_sheet").assertIsDisplayed()
            for (text in if (choice == FontChoice.CUSTOM) listOf("Aa 0O1lI", filename, "Change") else listOf("Aa 0O1lI")) {
                // A visible lazy row can still have supporting text below the sheet viewport.
                val sample =
                    compose
                        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                        .performScrollTo()
                        .assertIsDisplayed()
                val image = row.captureToImage().asAndroidBitmap()
                val fill = Color(image.getPixel(image.width - 2, image.height / 2))
                assertTrue("$choice $text ink contrasts with the painted selected row", contrastRatio(inkOf(text, tag), fill) >= 4.49)
                val rendered = sample.captureToImage().asAndroidBitmap()
                var paintedContrast = 1.0
                for (y in 0 until rendered.height) {
                    for (x in 0 until rendered.width) {
                        paintedContrast = maxOf(paintedContrast, contrastRatio(Color(rendered.getPixel(x, y)), fill))
                    }
                }
                // Native 8-bit captures may round a contrast-fitted channel by one step.
                assertTrue("$choice $text must paint readable native glyphs", paintedContrast >= 4.49)
            }
            if (choice == FontChoice.CUSTOM) {
                assertEquals("The selected Change action uses the sample's readable ink", inkOf("Aa 0O1lI", tag), inkOf("Change", tag))
            }
            captures +=
                row
                    .performScrollTo()
                    .assertIsDisplayed()
                    .captureToImage()
                    .asAndroidBitmap()
        }
        assertTrue("Restoring System restores its exact selected native pixels", captures.first().sameAs(captures.last()))
        customFont.delete()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun timestampExampleUsesFixedLocalTimeAndNativeInvalidPatternFallback() {
        val appearance = mutableStateOf(AppearanceConfig(timeFormat = TimeFormat.AUTO))
        compose.setContent {
            MotdTheme(dynamicColor = false) { TimestampExample(appearance.value) }
        }
        val system =
            compose
                .onNodeWithTag("settings_time_preview_value")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
                .text
        compose.runOnIdle { appearance.value = appearance.value.copy(timeFormat = TimeFormat.CUSTOM, customTimeFormatPattern = "'") }
        compose.onNodeWithTag("settings_time_preview_value").assertTextEquals(system)
        compose.runOnIdle { appearance.value = appearance.value.copy(customTimeFormatPattern = "yyyy-MM-dd HH:mm:ss") }
        compose.onNodeWithTag("settings_time_preview_value").assertTextEquals("2024-01-02 15:04:05")
    }

    @Test
    fun inputBaselineNameTracksTheDraftLayoutWithoutChangingTheStoredShape() {
        val persisted = AppearanceConfig(theme = ColorThemePreset.AYU_LIGHT)
        val calls = mutableListOf<ComposerStyle>()
        setContent(appearance = persisted, onComposerStyle = calls::add)
        val resources = RuntimeEnvironment.getApplication().resources
        for ((layout, radius) in listOf("large" to 16, "comfortable" to 28, "compact" to 28)) {
            compose.onNodeWithTag("settings_composer_style_picker").performScrollTo().performClick()
            scrollToSheetNode("settings_composer_style_sheet", "settings_composer_style_$layout").performClick().assertIsSelected()
            dismissSheet("settings_composer_style_sheet")
            openShapeSheet("input")
            val baseline = scrollToSheetNode("settings_input_shape_sheet", "settings_input_shape_default").assertIsSelected()
            baseline.assertTextEquals(
                resources.getString(io.github.trevarj.motd.R.string.settings_shape_radius, radius),
                resources.getString(io.github.trevarj.motd.R.string.settings_input_shape_baseline_desc),
            )
            dismissSheet("settings_input_shape_sheet")
            assertEquals(UiCornerStyle.DEFAULT, persisted.inputCornerStyle)
        }
        assertEquals(listOf(ComposerStyle.LARGE, ComposerStyle.COMFORTABLE, ComposerStyle.COMPACT), calls)
    }

    @Test
    fun shapeSheetsUpdateRealCornersImmediatelyIndependentlyAndRestoreDefaults() {
        val persisted = AppearanceConfig(theme = ColorThemePreset.AYU_LIGHT)
        val calls = listOf("input", "avatar", "chat_card", "media").associateWith { mutableListOf<String>() }
        setContent(
            settings = Settings(dynamicColor = false, avatarStyle = AvatarStyle.INITIALS, folderDisplayMode = FolderDisplayMode.INLINE),
            appearance = persisted,
            onInputCornerStyle = { calls.getValue("input").add(it.name) },
            onAvatarShape = { calls.getValue("avatar").add(it.name) },
            onChatCardCornerStyle = { calls.getValue("chat_card").add(it.name) },
            onMediaCornerStyle = { calls.getValue("media").add(it.name) },
        )
        val samples =
            linkedMapOf(
                "input" to listOf("chat_composer_pill"),
                "avatar" to listOf("settings_avatar_preview_person", "settings_avatar_preview_channel"),
                "chat_card" to listOf("chatlist_row_-1"),
                "media" to listOf("settings_media_shape_link", "audio_player"),
            )
        val expected = mutableMapOf<String, List<List<Int>>>()
        val selected = samples.keys.associateWith { "default" }.toMutableMap()
        for ((surface, tags) in samples) {
            openShapeSheet(surface)
            expected[surface] = shapeCorners(surface, tags)
            dismissSheet("settings_${surface}_shape_sheet")
        }
        val baseline = expected.toMap()
        for (preset in listOf("square", "default")) {
            for ((surface, tags) in samples) {
                val sheet = "settings_${surface}_shape_sheet"
                openShapeSheet(surface)
                scrollToSheetNode(sheet, "settings_${surface}_shape_default").assertIsSelectedIf(selected.getValue(surface) == "default")
                for (label in listOf("Square", "Soft", "Rounded") + if (surface == "avatar") listOf("Circle") else emptyList()) {
                    scrollToSheetNode(sheet, "settings_${surface}_shape_${label.lowercase()}")
                    compose.onNode(hasTestTag("settings_${surface}_shape_${label.lowercase()}") and hasText(label)).assertExists()
                }
                scrollToSheetNode(sheet, "settings_${surface}_shape_$preset").performClick().assertIsSelected()
                compose.onNodeWithTag(sheet).assertIsDisplayed()
                selected[surface] = preset
                assertEquals(preset.uppercase(), calls.getValue(surface).last())
                assertEquals(UiCornerStyle.DEFAULT, persisted.inputCornerStyle)
                assertEquals(AvatarShape.DEFAULT, persisted.avatarShape)
                assertEquals(UiCornerStyle.DEFAULT, persisted.chatCardCornerStyle)
                assertEquals(UiCornerStyle.DEFAULT, persisted.mediaCornerStyle)
                val corners = shapeCorners(surface, tags)
                if (preset == "square") {
                    corners.zip(baseline.getValue(surface)).forEachIndexed { index, (actual, original) ->
                        assertTrue("$surface ${tags[index]} must paint a formerly clipped native corner", actual != original)
                    }
                } else {
                    assertEquals("$surface Default restores its own exact baseline", baseline.getValue(surface), corners)
                }
                expected[surface] = corners
                dismissSheet(sheet)
                for ((other, otherTags) in samples.filterKeys { it != surface }) {
                    openShapeSheet(other)
                    scrollToSheetNode("settings_${other}_shape_sheet", "settings_${other}_shape_${selected.getValue(other)}").assertIsSelected()
                    assertEquals("$surface must not alter $other geometry", expected.getValue(other), shapeCorners(other, otherTags))
                    dismissSheet("settings_${other}_shape_sheet")
                }
                for (other in samples.keys) {
                    assertEquals(
                        "$surface must invoke only its own persistence callback",
                        if (selected.getValue(other) == "square") {
                            listOf("SQUARE")
                        } else if (calls.getValue(other).isEmpty()) {
                            emptyList()
                        } else {
                            listOf("SQUARE", "DEFAULT")
                        },
                        calls.getValue(other),
                    )
                }
            }
        }
    }

    @Test
    fun avatarShapeRemainsEnabledWithLabeledInitialsIllustrationWhenAvatarsAreHidden() {
        val settings = Settings(avatarStyle = AvatarStyle.NONE, dynamicColor = false)
        var selected: AvatarShape? = null
        var artChanges = 0
        setContent(settings = settings, onAvatarShape = { selected = it }, onAvatarStyle = { artChanges++ })
        compose
            .onNodeWithTag("settings_avatar_shape_picker")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText("Illustrative initials only: avatars remain hidden in chats.").assertExists()
        scrollToSheetNode("settings_avatar_shape_sheet", "settings_avatar_preview_initials").assertExists()
        val baseline = shapeCorners("avatar", listOf("settings_avatar_preview_person", "settings_avatar_preview_channel"))
        scrollToSheetNode("settings_avatar_shape_sheet", "settings_avatar_shape_square").performClick().assertIsSelected()
        assertEquals(AvatarShape.SQUARE, selected)
        assertTrue(baseline != shapeCorners("avatar", listOf("settings_avatar_preview_person", "settings_avatar_preview_channel")))
        scrollToSheetNode("settings_avatar_shape_sheet", "settings_avatar_shape_circle").performClick().assertIsSelected()
        assertEquals(AvatarShape.CIRCLE, selected)
        compose.onNodeWithTag("settings_avatar_shape_sheet").assertIsDisplayed()
        assertEquals(AvatarStyle.NONE, settings.avatarStyle)
        assertEquals(0, artChanges)
    }

    @Test
    fun shapeSearchTargetsBringEachHighlightedControlIntoViewAndOpenItsSheet() {
        val context = RuntimeEnvironment.getApplication()
        val entries = buildSettingsSearchEntries(emptyList(), { context.getString(it) }, { network, section -> "$network · $section" })
        val target = mutableStateOf<SettingsTarget?>(null)
        setContent(targetProvider = { target.value })
        for ((query, surface, expected) in listOf(
            Triple("composer roundness", "input", SettingsTarget.INPUT_SHAPE),
            Triple("profile image shape", "avatar", SettingsTarget.AVATAR_SHAPE),
            Triple("archive card corners", "chat_card", SettingsTarget.CHAT_CARD_SHAPE),
            Triple("audio corners", "media", SettingsTarget.MEDIA_SHAPE),
        )) {
            val destination = searchSettings(query, entries).single { it.destination == SettingsSearchDestination.Page(SettingsSearchPage.APPEARANCE, expected) }.destination as SettingsSearchDestination.Page
            compose.runOnIdle { target.value = destination.target }
            compose.onNodeWithTag("settings_target_highlight_${expected.name}").assertIsDisplayed().performClick()
            compose.onNodeWithTag("settings_${surface}_shape_sheet").assertIsDisplayed()
            scrollToSheetNode("settings_${surface}_shape_sheet", "settings_${surface}_shape_default").assertIsSelected()
            dismissSheet("settings_${surface}_shape_sheet")
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsSelectedIf(selected: Boolean) {
        if (selected) assertIsSelected() else assertIsNotSelected()
    }

    private fun openShapeSheet(surface: String) {
        compose.onNodeWithTag("settings_${surface}_shape_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_${surface}_shape_sheet").assertIsDisplayed()
    }

    private fun shapeCorners(
        surface: String,
        tags: List<String>,
    ): List<List<Int>> =
        tags.map { tag ->
            val sheet = "settings_${surface}_shape_sheet"
            compose.onNodeWithTag("${sheet}_options").performScrollToNode(hasTestTag(tag))
            val image = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            // Only production corner pixels: text, avatar artwork inside a chat card, and the
            // voice waveform cannot make an unrelated shape look changed.
            (0 until minOf(8, image.height)).flatMap { y ->
                (0 until minOf(8, image.width)).map { x -> image.getPixel(x, y) }
            }
        }

    private fun scrollToSheetNode(
        sheetTag: String,
        nodeTag: String,
    ): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("${sheetTag}_options").performScrollToNode(hasTestTag(nodeTag))
        return compose.onNodeWithTag(nodeTag)
    }

    private fun dismissSheet(tag: String) {
        val sheet = hasTestTag(tag)
        val dismissAction = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet))
        compose.onAllNodes(dismissAction, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun previewTimeText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(PREVIEW_MESSAGE_TIME_MILLIS))

    private fun inkOf(
        text: String,
        within: String? = null,
    ): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        val matcher = if (within == null) hasText(text) else hasText(text) and hasAnyAncestor(hasTestTag(within))
        compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts
            .single()
            .layoutInput.style.color
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setContent(
        settings: Settings = Settings(),
        appearance: AppearanceConfig = AppearanceConfig(),
        appearanceProvider: () -> AppearanceConfig = { appearance },
        customFontFile: java.io.File? = null,
        onFolderDisplayMode: (FolderDisplayMode) -> Unit = {},
        onShowFolderChatsInAll: (Boolean) -> Unit = {},
        onAvatarStyle: (AvatarStyle) -> Unit = {},
        onTimeFormat: (TimeFormat) -> Unit = {},
        lifecycleOwner: LifecycleOwner? = null,
        onLayoutDensity: (LayoutDensity) -> Unit = {},
        onComposerStyle: (ComposerStyle) -> Unit = {},
        onInputCornerStyle: (UiCornerStyle) -> Unit = {},
        onAvatarShape: (AvatarShape) -> Unit = {},
        onChatCardCornerStyle: (UiCornerStyle) -> Unit = {},
        onMediaCornerStyle: (UiCornerStyle) -> Unit = {},
        onUiIconSet: (UiIconSet) -> Unit = {},
        onFontChoice: (FontChoice) -> Unit = {},
        onMessageSpacing: (MessageSpacing) -> Unit = {},
        onBubbleCornerStyle: (BubbleCornerStyle) -> Unit = {},
        onChatShadowsEnabled: (Boolean) -> Unit = {},
        onNicknameBackgroundsEnabled: (Boolean) -> Unit = {},
        onMessageBackgroundOpacity: (Int) -> Unit = {},
        onUseThemeTextColors: (Boolean) -> Unit = {},
        onWallpaper: (WallpaperSelection) -> Unit = {},
        importedWallpaperProvider: () -> WallpaperSelection? = { null },
        targetProvider: () -> SettingsTarget? = { null },
    ) {
        compose.setContent {
            val currentAppearance = appearanceProvider()
            MotdTheme(
                dynamicColor = false,
                themePreset = currentAppearance.theme,
                trueBlack = currentAppearance.trueBlack,
                useThemeTextColors = currentAppearance.useThemeTextColors,
                uiFontScalePercent = currentAppearance.uiFontScalePercent,
                uiIconSet = currentAppearance.uiIconSet,
            ) {
                CompositionLocalProvider(
                    LocalRippleConfiguration provides null,
                    LocalLifecycleOwner provides (lifecycleOwner ?: LocalLifecycleOwner.current),
                ) {
                    AppearanceSettingsContent(
                        settings = settings,
                        customFontFile = customFontFile,
                        appearance = currentAppearance,
                        onBack = {},
                        onOpenNickColors = {},
                        onThemePreset = {},
                        onTrueBlack = {},
                        onFollowSystem = {},
                        onDynamicColor = {},
                        onUseThemeTextColors = onUseThemeTextColors,
                        onLayoutDensity = onLayoutDensity,
                        onComposerStyle = onComposerStyle,
                        onInputCornerStyle = onInputCornerStyle,
                        onAvatarShape = onAvatarShape,
                        onChatCardCornerStyle = onChatCardCornerStyle,
                        onMediaCornerStyle = onMediaCornerStyle,
                        onUiIconSet = onUiIconSet,
                        onFolderDisplayMode = onFolderDisplayMode,
                        onShowFolderChatsInAll = onShowFolderChatsInAll,
                        onAvatarStyle = onAvatarStyle,
                        onNickColorsEnabled = {},
                        onNickColorPalette = {},
                        onWallpaper = onWallpaper,
                        importedWallpaper = importedWallpaperProvider(),
                        onUiFontScale = {},
                        onConversationFontScale = {},
                        onFontChoice = onFontChoice,
                        onShowTimestamps = {},
                        onTimeFormat = onTimeFormat,
                        onCustomTimeFormatPattern = {},
                        onMessageSpacing = onMessageSpacing,
                        onBubbleCornerStyle = onBubbleCornerStyle,
                        onChatShadowsEnabled = onChatShadowsEnabled,
                        onNicknameBackgroundsEnabled = onNicknameBackgroundsEnabled,
                        onMessageBackgroundOpacity = onMessageBackgroundOpacity,
                        onLauncherIcon = {},
                        target = targetProvider(),
                    )
                }
            }
        }
    }

    private fun swatchPixel(tag: String): Int {
        val image =
            compose
                .onNodeWithTag(tag, useUnmergedTree = true)
                .performScrollTo()
                .captureToImage()
                .asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2)
    }

    private fun assertRoundedSelectedTarget(
        tag: String,
        expectedFill: Int,
    ) {
        val image =
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .captureToImage()
                .asAndroidBitmap()
        assertEquals(expectedFill, image.getPixel(image.width / 2, image.height / 2))
        assertTrue("$tag selected fill must leave its corner unpainted", image.getPixel(0, 0) != expectedFill)
    }
}

private const val PREVIEW_MESSAGE_TIME_MILLIS = 1_704_110_040_000L
