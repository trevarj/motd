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
