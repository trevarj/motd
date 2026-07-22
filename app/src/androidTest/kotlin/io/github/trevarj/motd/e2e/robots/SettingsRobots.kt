package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.test.junit4.ComposeTestRule

internal class SettingsRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun open() = click("chatlist_open_settings")
    fun appearance() = click("settings_category_appearance")
    fun returnToRoot() {
        if (!isPresent("screen_settings")) click("settings_back")
        assertDisplayed("screen_settings")
    }
    fun chat() {
        swipeUntilTag("screen_settings", "settings_category_chat")
        click("settings_category_chat")
    }
    fun networks() = click("settings_category_networks")
}

internal class ThemeSheetRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun selectAyuDarkAndTrueBlack() {
        click("settings_theme_picker")
        scrollContainerTo("settings_theme_list", "settings_theme_ayu_dark")
        click("settings_theme_ayu_dark")
        click("settings_switch_true_black")
    }
}

internal class NetworksRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun openRoot(rootId: Long) = click("settings_network_row_$rootId")
}

internal class BouncerRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun assertPanels() {
        click("network_settings_bouncer_networks")
        click("bouncer_tab_networks")
        assertDisplayed("bouncer_networks_panel")
        click("bouncer_tab_channels")
        assertDisplayed("bouncer_channels_panel")
        click("bouncer_tab_account")
        assertDisplayed("bouncer_account_panel")
        click("bouncer_tab_console")
        assertDisplayed("bouncer_console_panel")
    }
}
