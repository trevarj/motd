package io.github.trevarj.motd.e2e.robots

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.e2e.E2eArtifactSink
import org.json.JSONArray
import org.json.JSONObject

internal class SettingsRobot(
    compose: ComposeTestRule,
) : BaseRobot(compose) {
    fun open() {
        click("chatlist_more")
        click("chatlist_open_settings")
    }

    fun notifications() {
        scrollToAndClick("settings_category_notifications")
        assertDisplayed("screen_notification_settings")
    }

    fun selectBalancedHistorySync() {
        scrollToAndClick("settings_category_history")
        scrollToAndClick("settings_history_sync_mode")
        assertDisplayed("settings_history_sync_sheet")
        click("settings_history_sync_balanced")
        awaitTagGone("settings_history_sync_sheet")
    }

    fun appearance() = scrollToAndClick("settings_category_appearance")

    fun selectThemeTextColors() {
        awaitTag("settings_switch_adjust_palette_readability_row")
        compose.onNodeWithTag("settings_switch_adjust_palette_readability_row", useUnmergedTree = true).performScrollTo()
        turnOnPrefix("settings_switch_adjust_palette_readability_row")
        awaitTag("settings_switch_theme_text_colors_row")
        compose.onNodeWithTag("settings_switch_theme_text_colors_row", useUnmergedTree = true).performScrollTo()
        turnOnPrefix("settings_switch_theme_text_colors_row")
    }

    fun assertThemeTextColorsEnabled() {
        awaitTag("settings_switch_theme_text_colors_row")
        compose.onNodeWithTag("settings_switch_adjust_palette_readability_row", useUnmergedTree = true).performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings_switch_theme_text_colors_row", useUnmergedTree = true).performScrollTo().assertIsOn()
    }

    fun disableNicknameBackgrounds(
        opacityPercent: Int,
        blurIntensity: Int,
    ) {
        selectMessageLayout(LayoutDensity.COMPACT)
        awaitTag("settings_switch_nickname_backgrounds_row")
        compose
            .onNodeWithTag("settings_switch_nickname_backgrounds_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        scrollToAndClick("settings_surface_effects")
        setMessageBackgroundOpacity(opacityPercent)
        setMessageBackgroundBlur(blurIntensity)
        click("settings_back")
    }

    fun restoreNicknameBackgrounds(
        expectedOpacityPercent: Int,
        originalOpacityPercent: Int,
        expectedBlurIntensity: Int,
        originalBlurIntensity: Int,
        originalLayout: LayoutDensity,
    ) {
        scrollToAndClick("settings_surface_effects")
        scrollContainerTo("surface_effects_page", "settings_message_background_opacity")
        compose.onNodeWithTag("settings_message_background_opacity").assert(
            SemanticsMatcher("Persisted message background opacity") {
                it.config[SemanticsProperties.ProgressBarRangeInfo].current == expectedOpacityPercent.toFloat()
            },
        )
        scrollContainerTo("surface_effects_page", "settings_message_background_blur")
        compose.onNodeWithTag("settings_message_background_blur").assert(
            SemanticsMatcher("Persisted message background blur") {
                it.config[SemanticsProperties.ProgressBarRangeInfo].current == expectedBlurIntensity.toFloat()
            },
        )
        setMessageBackgroundOpacity(0)
        scrollContainerTo("surface_effects_page", "settings_message_background_blur")
        compose.onNodeWithTag("settings_message_background_blur").assert(
            SemanticsMatcher("Opacity does not reset blur") {
                it.config[SemanticsProperties.ProgressBarRangeInfo].current == expectedBlurIntensity.toFloat()
            },
        )
        setMessageBackgroundBlur(0)
        setMessageBackgroundOpacity(originalOpacityPercent)
        setMessageBackgroundBlur(originalBlurIntensity)
        click("settings_back")
        awaitTag("settings_switch_nickname_backgrounds_row")
        compose
            .onNodeWithTag("settings_switch_nickname_backgrounds_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        scrollToAndClick("settings_surface_effects")
        scrollContainerTo("surface_effects_page", "settings_message_background_opacity")
        compose.onNodeWithTag("settings_message_background_opacity").assertExists()
        click("settings_back")
        selectMessageLayout(originalLayout)
    }

    private fun setMessageBackgroundOpacity(percent: Int) {
        scrollContainerTo("surface_effects_page", "settings_message_background_opacity")
        compose.onNodeWithTag("settings_message_background_opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(percent.toFloat()) }
        compose.onNodeWithTag("settings_message_background_opacity").assert(
            SemanticsMatcher("Message background opacity $percent") {
                it.config[SemanticsProperties.ProgressBarRangeInfo].current == percent.toFloat()
            },
        )
    }

    private fun setMessageBackgroundBlur(intensity: Int) {
        scrollContainerTo("surface_effects_page", "settings_message_background_blur")
        compose.onNodeWithTag("settings_message_background_blur").performSemanticsAction(SemanticsActions.SetProgress) { it(intensity.toFloat()) }
        compose.onNodeWithTag("settings_message_background_blur").assert(
            SemanticsMatcher("Message background blur $intensity") {
                it.config[SemanticsProperties.ProgressBarRangeInfo].current == intensity.toFloat()
            },
        )
    }

    private fun selectMessageLayout(layout: LayoutDensity) {
        scrollToAndClick("settings_density_picker")
        val tag = "settings_density_${layout.name.lowercase()}"
        scrollContainerTo("settings_density_sheet_options", tag)
        click(tag)
        scrollContainerTo("settings_density_sheet_options", tag)
        compose.onNodeWithTag(tag).assertIsSelected()
        dismissChoiceSheet("settings_density_sheet")
    }

    fun exerciseConversationBackingInheritance() {
        fun openSheet() {
            click("chat_overflow")
            click("chat_layout_menu")
            awaitTag("chat_layout_sheet")
        }

        fun setAndAwait(
            setting: String,
            value: Int,
        ) {
            val tag = "chat_message_background_$setting"
            scrollContainerTo("chat_layout_sheet_options", tag)
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
            compose.waitUntil("Room accepts conversation $setting", 10_000) {
                compose
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo]
                    .current == value.toFloat()
            }
            scrollContainerTo("chat_layout_sheet_options", "chat_layout_global_${setting}_row")
            compose.onNodeWithTag("chat_layout_global_${setting}_row", useUnmergedTree = true).assertIsOff()
        }

        fun assertSaved(
            setting: String,
            expected: Int,
        ) {
            val tag = "chat_message_background_$setting"
            scrollContainerTo("chat_layout_sheet_options", tag)
            compose.onNodeWithTag(tag).assert(
                SemanticsMatcher("Persisted local $setting") { it.config[SemanticsProperties.ProgressBarRangeInfo].current == expected.toFloat() },
            )
        }
        openSheet()
        setAndAwait("opacity", 37)
        setAndAwait("blur", 63)
        assertSaved("opacity", 37)
        dismissChoiceSheet("chat_layout_sheet")
        openSheet()
        assertSaved("opacity", 37)
        assertSaved("blur", 63)
        setAndAwait("blur", 0)
        for (setting in listOf("opacity", "blur")) {
            val tag = "chat_layout_global_${setting}_row"
            scrollContainerTo("chat_layout_sheet_options", tag)
            compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            compose.waitUntil("Room restores $setting inheritance", 10_000) {
                compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
                    androidx.compose.ui.state.ToggleableState.On
            }
            if (setting == "opacity") assertSaved("blur", 0)
        }
        dismissChoiceSheet("chat_layout_sheet")
    }

    fun selectIrcSprites() {
        scrollToAndClick("settings_avatar_style_picker")
        assertDisplayed("settings_avatar_style_sheet")
        scrollContainerTo("settings_avatar_style_sheet_options", "settings_avatar_style_irc_sprite")
        click("settings_avatar_style_irc_sprite")
        compose.onNodeWithTag("settings_avatar_style_irc_sprite").assertIsSelected()
        assertDisplayed("settings_avatar_style_sheet")
        scrollContainerTo("settings_avatar_style_sheet_options", "settings_avatar_preview_irc_sprite")
        assertDisplayed("settings_avatar_preview_irc_sprite")
        val sheet = hasTestTag("settings_avatar_style_sheet")
        val dismiss = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet))
        compose.onAllNodes(dismiss, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.Dismiss)
        awaitTagGone("settings_avatar_style_sheet")
    }

    fun selectSurfaceShapes() {
        for ((surface, preset) in surfaceShapeChoices) {
            scrollToAndClick("settings_${surface}_shape_picker")
            val sheet = "settings_${surface}_shape_sheet"
            assertDisplayed(sheet)
            val option = "settings_${surface}_shape_$preset"
            scrollContainerTo("${sheet}_options", option)
            click(option)
            compose.onNodeWithTag(option).assertIsSelected()
            assertDisplayed(sheet)
            dismissChoiceSheet(sheet)
        }
    }

    fun assertSurfaceShapesPersistedAndRestoreDefaults() {
        for ((surface, preset) in surfaceShapeChoices) {
            scrollToAndClick("settings_${surface}_shape_picker")
            val sheet = "settings_${surface}_shape_sheet"
            scrollContainerTo("${sheet}_options", "settings_${surface}_shape_$preset")
            compose.onNodeWithTag("settings_${surface}_shape_$preset").assertIsSelected()
            val default = "settings_${surface}_shape_default"
            scrollContainerTo("${sheet}_options", default)
            click(default)
            compose.onNodeWithTag(default).assertIsSelected()
            assertResolvedShapeBaseline(surface, default)
            assertDisplayed(sheet)
            dismissChoiceSheet(sheet)
            scrollToAndClick("settings_${surface}_shape_picker")
            scrollContainerTo("${sheet}_options", default)
            compose.onNodeWithTag(default).assertIsSelected()
            assertResolvedShapeBaseline(surface, default)
            dismissChoiceSheet(sheet)
        }
    }

    private fun assertResolvedShapeBaseline(
        surface: String,
        tag: String,
    ) {
        val resources =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources
        val expected =
            when (surface) {
                "input" -> listOf(16, 28).map { resources.getString(io.github.trevarj.motd.R.string.settings_shape_radius, it) }
                "chat_card" -> listOf(resources.getString(io.github.trevarj.motd.R.string.settings_shape_radius, 16))
                "avatar" -> listOf(resources.getString(io.github.trevarj.motd.R.string.settings_avatar_shape_baseline))
                else -> listOf(resources.getString(io.github.trevarj.motd.R.string.settings_media_shape_baseline))
            }
        val text =
            compose
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .map { it.text }
        org.junit.Assert.assertTrue("The persisted baseline exposes its actual rendered geometry", expected.any { it in text })
    }

    private val surfaceShapeChoices = listOf("input" to "square", "avatar" to "circle", "chat_card" to "soft", "media" to "rounded")

    fun selectLucideIcons() {
        scrollToAndClick("settings_icon_set_picker")
        val sheet = "settings_icon_set_sheet"
        scrollContainerTo("${sheet}_options", "settings_icon_set_lucide")
        click("settings_icon_set_lucide")
        compose.onNodeWithTag("settings_icon_set_lucide").assertIsSelected()
        assertDisplayed(sheet)
        dismissChoiceSheet(sheet)
    }

    fun assertLucideIconsPersistedAndRestoreMaterial() {
        scrollToAndClick("settings_icon_set_picker")
        val sheet = "settings_icon_set_sheet"
        scrollContainerTo("${sheet}_options", "settings_icon_set_lucide")
        compose.onNodeWithTag("settings_icon_set_lucide").assertIsSelected()
        scrollContainerTo("${sheet}_options", "settings_icon_set_material")
        click("settings_icon_set_material")
        compose.onNodeWithTag("settings_icon_set_material").assertIsSelected()
        assertDisplayed(sheet)
        dismissChoiceSheet(sheet)
        scrollToAndClick("settings_icon_set_picker")
        scrollContainerTo("${sheet}_options", "settings_icon_set_material")
        compose.onNodeWithTag("settings_icon_set_material").assertIsSelected()
        dismissChoiceSheet(sheet)
    }

    fun selectWallpaperAndIntensity() {
        scrollToAndClick("settings_wallpaper_picker")
        assertDisplayed("settings_wallpaper_sheet")
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_preset_systemcrafters")
        click("settings_wallpaper_preset_systemcrafters")
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_intensity")
        compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_outline_color")
        click("settings_wallpaper_outline_color")
        compose.onNodeWithTag("settings_wallpaper_color_hex").performScrollTo()
        replace("settings_wallpaper_color_hex", "#FFFFFF")
        click("settings_wallpaper_color_apply")
        awaitTagGone("settings_wallpaper_color_dialog")
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_done")
        click("settings_wallpaper_done")
        awaitTagGone("settings_wallpaper_sheet")
        assertDisplayed("settings_wallpaper_picker")
        // Reopening reads the saved color, not a dialog-local draft.
        scrollToAndClick("settings_wallpaper_picker")
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_outline_color")
        click("settings_wallpaper_outline_color")
        compose.onNodeWithTag("settings_wallpaper_color_hex").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("#FFFFFF")))
        click("settings_wallpaper_color_cancel")
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_done")
        click("settings_wallpaper_done")
        awaitTagGone("settings_wallpaper_sheet")
        // Scenic controls are persisted separately from the existing motif outline color.
        scrollToAndClick("settings_wallpaper_picker")
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_preset_japanese_countryside")
        click("settings_wallpaper_preset_japanese_countryside")
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_scenic_follow_system")
        compose.onNodeWithTag("settings_wallpaper_scenic_follow_system").assertIsOn()
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_scenic_animation")
        compose.onNodeWithTag("settings_wallpaper_scenic_animation").assertIsOn()
        scrollContainerTo("settings_wallpaper_list", "settings_wallpaper_done")
        click("settings_wallpaper_done")
        awaitTagGone("settings_wallpaper_sheet")
    }

    fun selectComfortableBubbleColors() {
        scrollToAndClick("settings_bubble_color_picker")
        assertDisplayed("settings_bubble_color_sheet")
        compose.onNodeWithTag("settings_bubble_color_primary").performScrollTo().performClick()
        compose
            .onNodeWithTag("settings_bubble_color_received")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("settings_bubble_color_tertiary_container").performScrollTo().performClick()
        scrollToAndClick("settings_bubble_color_done")
        awaitTagGone("settings_bubble_color_sheet")
        // The persisted Appearance state must restore when the editor is reopened.
        scrollToAndClick("settings_bubble_color_picker")
        compose.onNodeWithTag("settings_bubble_color_primary").performScrollTo().assertIsSelected()
        compose
            .onNodeWithTag("settings_bubble_color_received")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("settings_bubble_color_tertiary_container").performScrollTo().assertIsSelected()
        scrollToAndClick("settings_bubble_color_done")
        awaitTagGone("settings_bubble_color_sheet")
    }

    fun returnToRoot() {
        if (!isPresent("screen_settings")) click("settings_back")
        assertDisplayed("screen_settings")
        if (isPresent("settings_search_field")) click("settings_search_action")
    }

    fun chat() {
        swipeUntilTag("screen_settings", "settings_category_chat")
        click("settings_category_chat")
    }

    fun exerciseFoolSettings(nick: String) {
        chat()
        scrollToAndClick("settings_fools_mode_picker")
        val modes = listOf("dimmed", "blur", "collapse", "hide")
        val original =
            modes.single { mode ->
                scrollContainerTo("settings_fools_mode_sheet_options", "settings_fools_mode_$mode")
                compose
                    .onNodeWithTag("settings_fools_mode_$mode", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.Selected) == true
            }
        scrollContainerTo("settings_fools_mode_sheet_options", "settings_fools_mode_dimmed")
        click("settings_fools_mode_dimmed")
        awaitFoolSelection("settings_fools_mode_dimmed")
        dismissChoiceSheet("settings_fools_mode_sheet")
        scrollToAndClick("settings_fools")
        replace("manage_nick_input", nick)
        click("manage_nick_add")
        click("manage_nick_$nick")
        compose.onNodeWithTag("fool_visibility_inherit", useUnmergedTree = true).assertIsSelected()
        click("fool_visibility_blur")
        awaitFoolSelection("fool_visibility_blur")
        scrollToAndClick("fool_notify_mentions")
        awaitFoolSwitch("fool_notify_mentions", true)
        compose.onNodeWithTag("fool_notify_direct_messages", useUnmergedTree = true).assertIsOff()
        dismissFoolSettings()
        click("manage_nick_$nick")
        awaitFoolSelection("fool_visibility_blur")
        awaitFoolSwitch("fool_notify_mentions", true)
        scrollToAndClick("fool_notify_direct_messages")
        awaitFoolSwitch("fool_notify_direct_messages", true)
        scrollToAndClick("fool_notify_mentions")
        awaitFoolSwitch("fool_notify_mentions", false)
        click("fool_visibility_inherit")
        awaitFoolSelection("fool_visibility_inherit")
        dismissFoolSettings()
        click("settings_back")
        selectDefaultFoolVisibility("blur")
        scrollToAndClick("settings_fools")
        click("manage_nick_$nick")
        awaitFoolSelection("fool_visibility_inherit")
        compose.onNodeWithText("Follow global (Blurred until tapped)", useUnmergedTree = true).assertExists()
        awaitFoolSwitch("fool_notify_mentions", false)
        awaitFoolSwitch("fool_notify_direct_messages", true)
        dismissFoolSettings()
        click("manage_remove_$nick")
        awaitTagGone("manage_nick_$nick")
        click("settings_back")
        selectDefaultFoolVisibility(original)
        returnToRoot()
    }

    private fun selectDefaultFoolVisibility(mode: String) {
        scrollToAndClick("settings_fools_mode_picker")
        scrollContainerTo("settings_fools_mode_sheet_options", "settings_fools_mode_$mode")
        click("settings_fools_mode_$mode")
        awaitFoolSelection("settings_fools_mode_$mode")
        dismissChoiceSheet("settings_fools_mode_sheet")
        // Reopen to prove this is persisted selection, not an old picker-local value.
        scrollToAndClick("settings_fools_mode_picker")
        scrollContainerTo("settings_fools_mode_sheet_options", "settings_fools_mode_$mode")
        compose.onNodeWithTag("settings_fools_mode_$mode", useUnmergedTree = true).assertIsSelected()
        dismissChoiceSheet("settings_fools_mode_sheet")
    }

    private fun awaitFoolSelection(tag: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsSelected() }.isSuccess
        }
    }

    private fun awaitFoolSwitch(
        tag: String,
        enabled: Boolean,
    ) {
        compose.waitUntil(10_000) {
            runCatching {
                val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
                if (enabled) node.assertIsOn() else node.assertIsOff()
            }.isSuccess
        }
    }

    private fun dismissChoiceSheet(tag: String) {
        val sheet = hasTestTag(tag)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet)), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.Dismiss)
        awaitTagGone(tag)
    }

    private fun dismissFoolSettings() {
        val sheet = hasTestTag("fool_settings_sheet")
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet)), useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.Dismiss)
        awaitTagGone("fool_settings_sheet")
    }

    fun security() {
        scrollToAndClick("settings_category_security")
        assertDisplayed("screen_security_settings")
    }

    fun toggleTypingPrivacy() {
        scrollToAndClick("settings_switch_send_typing_indicators_row")
        scrollToAndClick("settings_switch_show_typing_indicators_row")
    }

    fun searchPresence() {
        click("settings_search_action")
        replace("settings_search_field", "join part")
        click("settings_search_result_page_CHAT_PRESENCE")
        assertDisplayed("settings_target_highlight_PRESENCE")
        assertDisplayed("settings_presence_picker")
    }

    fun returnToPresenceSearch() {
        click("settings_back")
        assertDisplayed("settings_search_field")
        assertDisplayed("settings_search_result_page_CHAT_PRESENCE")
    }

    fun exerciseCustomPresence() {
        scrollToAndClick("settings_presence_picker")
        scrollContainerTo("settings_presence_sheet_options", "settings_presence_mode_custom")
        click("settings_presence_mode_custom")
        scrollContainerTo("settings_presence_sheet_options", "settings_presence_custom_join")
        val join = compose.onNodeWithTag("settings_presence_custom_join", useUnmergedTree = true)
        val wasOn = runCatching { join.assertIsOn() }.isSuccess
        click("settings_presence_custom_join")
        compose.waitUntil(10_000) {
            runCatching { if (wasOn) join.assertIsOff() else join.assertIsOn() }.isSuccess
        }
        val sheet = hasTestTag("settings_presence_sheet")
        val dismiss = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet))
        compose.onAllNodes(dismiss, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.Dismiss)
        awaitTagGone("settings_presence_sheet")
    }

    fun networks() = scrollToAndClick("settings_category_networks")
}

internal class PaletteRobot(
    compose: ComposeTestRule,
) : BaseRobot(compose) {
    fun selectIndependentPalettesAndTrueBlack() {
        scrollToAndClick("settings_palette_hub")
        click("palette_target_light")
        compose.onNodeWithTag("palette_target_light").assertIsSelected()
        scrollContainerTo("palette_list", "palette_builtin_catppuccin_mocha")
        click("palette_builtin_catppuccin_mocha")
        click("palette_target_dark")
        compose.onNodeWithTag("palette_target_dark").assertIsSelected()
        scrollContainerTo("palette_list", "palette_builtin_ayu_dark")
        click("palette_builtin_ayu_dark")
        click("palette_back")
        scrollToAndClick("settings_color_mode_dark")
        scrollToAndClick("settings_switch_true_black")
    }

    fun createPalette(
        name: String,
        apply: Boolean,
    ) {
        scrollToAndClick("settings_palette_hub")
        click("palette_target_dark")
        click("palette_create")
        replace("palette_editor_name", name)
        click(if (apply) "palette_editor_save_apply" else "palette_editor_save")
        awaitTag("palette_hub")
        click("palette_back")
        awaitTag("settings_palette_hub")
    }

    fun shareInstalledPaletteToMotd(
        name: String,
        artifactPrefix: String,
    ) {
        scrollToAndClick("settings_palette_hub")
        compose.onNodeWithText("Yours").performClick()
        replace("palette_search", name)
        scrollToAndClick("palette_share")
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        val context = instrumentation.targetContext
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        var selected = false
        var targetLabelFound = false
        var targetClickableFound = false

        fun findText(
            node: android.view.accessibility.AccessibilityNodeInfo?,
            text: String,
        ): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString().equals(text, ignoreCase = true)) return node
            for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
            return null
        }

        fun clickText(
            root: android.view.accessibility.AccessibilityNodeInfo?,
            text: String,
        ): Boolean {
            var node = findText(root, text)
            if (text == label && node != null) targetLabelFound = true
            while (node != null && !node.isClickable) node = node.parent
            if (text == label && node != null) targetClickableFound = true
            return node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        // Drive the real Android chooser; the receiver reads the production FileProvider grant.
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (selected && root?.packageName?.toString() == context.packageName) break
            if (!selected) {
                selected = clickText(root, label)
            } else {
                clickText(root, "Just once")
            }
            android.os.SystemClock.sleep(100)
        }
        if (!selected) runCatching { captureChooserFailure(artifactPrefix, label, targetLabelFound, targetClickableFound) }
        awaitTag("palette_preview_apply")
    }

    private fun captureChooserFailure(
        artifactPrefix: String,
        expectedLabel: String,
        targetLabelFound: Boolean,
        targetClickableFound: Boolean,
    ) {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        val context = instrumentation.targetContext
        val snapshot =
            JSONObject()
                .put("schema", "palette-chooser/1")
                .put("expectedLabel", expectedLabel)
                .put("targetLabelFound", targetLabelFound)
                .put("targetClickableFound", targetClickableFound)
        val publicLabels = mutableSetOf(expectedLabel, "Just once", "Always", "Cancel", "Share", "More")
        val systemPackages = setOf("android", "com.android.intentresolver", "com.android.systemui")
        runCatching {
            val targets = JSONArray()
            val request = Intent(Intent.ACTION_SEND).setType("application/json")
            context.packageManager.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY).forEach { resolved ->
                val activity = resolved.activityInfo
                val activityLabel = activity.loadLabel(context.packageManager).toString()
                val resolvedLabel = resolved.loadLabel(context.packageManager).toString()
                publicLabels.add(activityLabel)
                publicLabels.add(resolvedLabel)
                targets.put(
                    JSONObject()
                        .put("package", activity.packageName)
                        .put("component", activity.name)
                        .put("activityLabel", activityLabel)
                        .put("resolvedLabel", resolvedLabel)
                        .put("enabled", activity.enabled)
                        .put("exported", activity.exported),
                )
            }
            snapshot.put("resolvedTargets", targets)
        }.onFailure { snapshot.put("targetCaptureError", it::class.java.name) }
        runCatching {
            val power = requireNotNull(context.getSystemService(PowerManager::class.java))
            val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))
            snapshot
                .put("interactive", power.isInteractive)
                .put("keyguardLocked", keyguard.isKeyguardLocked)
                .put("deviceLocked", keyguard.isDeviceLocked)
        }.onFailure { snapshot.put("powerCaptureError", it::class.java.name) }

        fun hierarchy(root: AccessibilityNodeInfo?): JSONObject {
            val nodes = JSONArray()

            fun visit(
                node: AccessibilityNodeInfo,
                parent: Int,
                depth: Int,
            ) {
                if (nodes.length() >= 512 || depth > 24) return
                val index = nodes.length()
                val systemUi = node.packageName?.toString() in systemPackages
                val publicNode = systemUi && !node.isEditable && !node.isPassword
                val label = if (publicNode) node.text?.toString().orEmpty() else ""
                val description = if (publicNode) node.contentDescription?.toString().orEmpty() else ""
                nodes.put(
                    JSONObject()
                        .put("parent", parent)
                        .put("package", node.packageName?.toString())
                        .put("class", node.className?.toString())
                        .put("viewId", if (systemUi) node.viewIdResourceName else JSONObject.NULL)
                        .put("publicLabel", if (publicNode && label in publicLabels) label else JSONObject.NULL)
                        .put("publicDescription", if (publicNode && description in publicLabels) description else JSONObject.NULL)
                        .put("labelMatchesExpected", publicNode && label.equals(expectedLabel, ignoreCase = true))
                        .put("descriptionContainsExpected", publicNode && description.contains(expectedLabel, ignoreCase = true))
                        .put("clickable", node.isClickable)
                        .put("enabled", node.isEnabled)
                        .put("visible", node.isVisibleToUser)
                        .put("scrollable", node.isScrollable)
                        .put("childCount", node.childCount),
                )
                for (childIndex in 0 until node.childCount) {
                    node.getChild(childIndex)?.let { visit(it, index, depth + 1) }
                }
            }
            root?.let { visit(it, -1, 0) }
            return JSONObject().put("rootPresent", root != null).put("nodes", nodes)
        }

        runCatching {
            snapshot.put("activeRoot", hierarchy(instrumentation.uiAutomation.rootInActiveWindow))
        }.onFailure { snapshot.put("activeRootCaptureError", it::class.java.name) }
        runCatching {
            val windows = JSONArray()
            instrumentation.uiAutomation.windows.forEach { window ->
                windows.put(
                    JSONObject()
                        .put("id", window.id)
                        .put("type", window.type)
                        .put("active", window.isActive)
                        .put("focused", window.isFocused)
                        .put("hierarchy", hierarchy(window.root)),
                )
            }
            snapshot.put("windows", windows)
        }.onFailure { snapshot.put("windowCaptureError", it::class.java.name) }
        E2eArtifactSink.write("$artifactPrefix/chooser-failure/semantics.json", snapshot.toString())
    }

    fun cancelSharedPreview() {
        click("palette_preview_cancel")
        awaitTag("palette_hub")
        click("palette_back")
        awaitTag("settings_palette_hub")
    }

    fun applySharedPreview() {
        scrollToAndClick("palette_assign_dark")
        click("palette_preview_apply")
        awaitTag("palette_hub")
        click("palette_back")
        awaitTag("settings_palette_hub")
    }

    fun loadLinkedPalette() {
        click("link_preview_awaiting")
        awaitTag("link_preview_palette")
        assertDisplayed("link_preview_palette_light")
        assertDisplayed("link_preview_palette_dark")
    }

    fun openLinkedPreview() {
        click("link_preview_palette")
        awaitTag("palette_preview_apply")
    }

    fun closeLinkedPreview(apply: Boolean) {
        if (apply) scrollToAndClick("palette_assign_light")
        click(if (apply) "palette_preview_apply" else "palette_preview_cancel")
        awaitTag("chat_timeline")
        awaitTagGone("palette_preview_apply")
    }
}

internal class NetworksRobot(
    compose: ComposeTestRule,
) : BaseRobot(compose) {
    fun openRoot(rootId: Long) = click("settings_network_row_$rootId")
}

internal class BouncerRobot(
    compose: ComposeTestRule,
) : BaseRobot(compose) {
    fun assertPanels() {
        scrollToAndClick("network_settings_bouncer_networks")
        for (section in listOf("networks", "channels", "account", "console")) {
            scrollToAndClick("bouncer_overview_$section")
            assertDisplayed("bouncer_${section}_panel")
            click("settings_back")
            assertDisplayed("bouncer_overview")
        }
    }
}
