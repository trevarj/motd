package io.github.trevarj.motd.e2e.robots

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
        awaitTag("settings_switch_theme_text_colors_row")
        compose.onNodeWithTag("settings_switch_theme_text_colors_row", useUnmergedTree = true).performScrollTo()
        turnOnPrefix("settings_switch_theme_text_colors_row")
    }

    fun assertThemeTextColorsEnabled() {
        awaitTag("settings_switch_theme_text_colors_row")
        compose.onNodeWithTag("settings_switch_theme_text_colors_row", useUnmergedTree = true).performScrollTo().assertIsOn()
    }

    fun disableNicknameBackgrounds() {
        awaitTag("settings_switch_nickname_backgrounds_row")
        compose
            .onNodeWithTag("settings_switch_nickname_backgrounds_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOn()
            .performClick()
            .assertIsOff()
    }

    fun restoreNicknameBackgrounds() {
        awaitTag("settings_switch_nickname_backgrounds_row")
        compose
            .onNodeWithTag("settings_switch_nickname_backgrounds_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
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
            assertDisplayed(sheet)
            dismissChoiceSheet(sheet)
            scrollToAndClick("settings_${surface}_shape_picker")
            scrollContainerTo("${sheet}_options", default)
            compose.onNodeWithTag(default).assertIsSelected()
            dismissChoiceSheet(sheet)
        }
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
        val modes = listOf("dimmed", "collapse", "hide")
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
        click("fool_visibility_collapse")
        awaitFoolSelection("fool_visibility_collapse")
        scrollToAndClick("fool_notify_mentions")
        awaitFoolSwitch("fool_notify_mentions", true)
        compose.onNodeWithTag("fool_notify_direct_messages", useUnmergedTree = true).assertIsOff()
        dismissFoolSettings()
        click("manage_nick_$nick")
        awaitFoolSelection("fool_visibility_collapse")
        awaitFoolSwitch("fool_notify_mentions", true)
        scrollToAndClick("fool_notify_direct_messages")
        awaitFoolSwitch("fool_notify_direct_messages", true)
        scrollToAndClick("fool_notify_mentions")
        awaitFoolSwitch("fool_notify_mentions", false)
        click("fool_visibility_inherit")
        awaitFoolSelection("fool_visibility_inherit")
        dismissFoolSettings()
        click("settings_back")
        selectDefaultFoolVisibility("hide")
        scrollToAndClick("settings_fools")
        click("manage_nick_$nick")
        awaitFoolSelection("fool_visibility_inherit")
        compose.onNodeWithText("Follow global (Hidden)", useUnmergedTree = true).assertExists()
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

internal class ThemeSheetRobot(
    compose: ComposeTestRule,
) : BaseRobot(compose) {
    fun selectAyuDarkAndTrueBlack() {
        click("settings_theme_picker")
        scrollContainerTo("settings_theme_list", "settings_theme_ayu_dark")
        click("settings_theme_ayu_dark")
        click("settings_switch_true_black")
    }

    fun dismiss() {
        val themeSheet = hasTestTag("settings_theme_sheet")
        val dismissAction =
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
                (themeSheet or hasAnyAncestor(themeSheet))
        compose
            .onAllNodes(dismissAction, useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.Dismiss)
        awaitTagGone("settings_theme_sheet")
        awaitTag("settings_avatar_style_picker")
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
