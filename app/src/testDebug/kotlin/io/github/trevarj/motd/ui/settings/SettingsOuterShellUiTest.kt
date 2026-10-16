package io.github.trevarj.motd.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.bouncer.BouncerServCapabilities
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.settingsDataStore
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkContent
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkUiState
import io.github.trevarj.motd.ui.settings.bouncer.BouncerControlCallbacks
import io.github.trevarj.motd.ui.settings.bouncer.BouncerControlTab
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetRow
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksContent
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksUiState
import io.github.trevarj.motd.ui.settings.labs.GESTURE_EDITOR_SCREEN_TAG
import io.github.trevarj.motd.ui.settings.labs.GestureEditorCallbacks
import io.github.trevarj.motd.ui.settings.labs.GestureEditorUiState
import io.github.trevarj.motd.ui.settings.labs.GestureMenuEditorContent
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class SettingsOuterShellUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun manage_nicks_uses_shared_settings_shell() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ManageNicksContent(ManageNicksUiState(kind = NickListKind.FRIENDS), {}, {}, {}, { _, _ -> }, { _, _ -> })
            }
        }

        assertSharedShell()
    }

    @Test
    fun management_editor_persists_each_switch_and_live_inheritance_and_closes_on_removal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DataStoreSettingsRepository(context)
        runBlocking {
            context.settingsDataStore.edit { it.clear() }
            repo.setFoolsMode(FoolsMode.DIMMED)
            repo.setFool("managed", true)
        }
        val vm = ManageNicksViewModel(repo)
        compose.setContent { MotdTheme(dynamicColor = false) { ManageNicksScreen(NickListKind.FOOLS, viewModel = vm) } }
        compose.onNodeWithTag("manage_nick_managed").performClick()
        compose.onNodeWithTag("fool_visibility_inherit").assertIsSelected()
        compose.onNodeWithTag("fool_notify_mentions").assertIsOff().performClick()
        awaitFoolPreferences(repo, FoolPreferences(notifyMentions = true))
        compose.onNodeWithTag("fool_notify_direct_messages").assertIsOff()
        compose.onNodeWithTag("fool_visibility_collapse").performClick()
        awaitFoolPreferences(repo, FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true))
        dismissFoolSettings()
        compose.onNodeWithTag("manage_nick_managed").performClick()
        compose.onNodeWithTag("fool_visibility_collapse").assertIsSelected()
        compose.onNodeWithTag("fool_notify_mentions").assertIsOn()
        compose.onNodeWithTag("fool_notify_direct_messages").assertIsOff().performClick()
        awaitFoolPreferences(repo, FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true, notifyDirectMessages = true))
        compose.onNodeWithTag("fool_notify_mentions").performClick()
        awaitFoolPreferences(repo, FoolPreferences(FoolsMode.COLLAPSE, notifyDirectMessages = true))
        compose.onNodeWithTag("fool_visibility_inherit").performClick()
        awaitFoolPreferences(repo, FoolPreferences(notifyDirectMessages = true))
        runBlocking { repo.setFoolsMode(FoolsMode.HIDE) }
        compose.onNodeWithText("Follow global (Hidden)").assertIsDisplayed()
        compose.onNodeWithTag("fool_visibility_inherit").assertIsSelected()
        dismissFoolSettings()
        compose.onNodeWithTag("manage_nick_managed").performClick()
        compose.onNodeWithTag("fool_notify_mentions").assertIsOff()
        compose.onNodeWithTag("fool_notify_direct_messages").assertIsOn()
        runBlocking { repo.setFool("managed", false) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("fool_settings_sheet").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(emptyMap<String, FoolPreferences>(), runBlocking { repo.settings.first() }.foolPreferences)
    }

    private fun awaitFoolPreferences(
        repo: DataStoreSettingsRepository,
        expected: FoolPreferences,
    ) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            runBlocking { repo.settings.first() }.foolPreferences["managed"] == expected
        }
    }

    private fun dismissFoolSettings() {
        val sheet = hasTestTag("fool_settings_sheet")
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet)), useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("fool_settings_sheet").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun add_network_uses_shared_settings_shell() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AddNetworkContent(
                    state = AddNetworkUiState(),
                    onBack = {},
                    onSetKind = {},
                    onSetBouncerKind = {},
                    onSelectPreset = {},
                    onDisplayNameChange = {},
                    onServerChange = {},
                    onAuthChange = {},
                    onSojuLoginChange = {},
                    onZncLoginChange = {},
                    onSubmit = {},
                    onRetry = {},
                    onSaveAnyway = {},
                    onEditForm = {},
                    onAbandon = {},
                    onConfirmPlaintext = {},
                    onDismissPlaintext = {},
                )
            }
        }

        assertSharedShell()
    }

    @Test
    fun bouncer_control_uses_shared_settings_shell_with_overview() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                BouncerNetworksContent(BouncerNetworksUiState(), {}, BouncerControlCallbacks())
            }
        }

        assertSharedShell()
        compose.onNodeWithTag("bouncer_overview").assertIsDisplayed()
        compose.onNodeWithTag("bouncer_tab_networks").assertDoesNotExist()
    }

    @Test
    fun bouncer_dashboard_opens_details_and_returns() {
        var state by mutableStateOf(
            BouncerNetworksUiState(
                root =
                    NetworkEntity(
                        id = 1,
                        name = "Home",
                        role = NetworkRole.BOUNCER_ROOT,
                        host = "soju.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                rootState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                rows =
                    listOf(
                        BouncerNetRow("1", "Libera", "irc.libera.chat", "connected", childNetworkId = 4),
                        BouncerNetRow("2", "OFTC", "irc.oftc.net", "disconnected", childNetworkId = null),
                    ),
                listingLoaded = true,
                capabilities = BouncerServCapabilities(setOf("network create"), verified = true),
            ),
        )
        var routeBack = false
        var probed = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                BouncerNetworksContent(
                    state = state,
                    onBack = { routeBack = true },
                    callbacks =
                        BouncerControlCallbacks(
                            onSelectTab = { state = state.copy(selectedTab = it) },
                            onProbe = { probed = true },
                            onClearFeedback = { state = state.copy(notice = null, error = null) },
                        ),
                )
            }
        }

        compose.onNodeWithTag("bouncer_overview").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithTag("bouncer_overview_admin").assertDoesNotExist()
        compose.onNodeWithTag("bouncer_overview_networks").performClick()
        compose.onNodeWithTag("bouncer_networks_panel").assertIsDisplayed()
        compose.onNodeWithTag("bouncer_overview").assertDoesNotExist()
        compose.runOnIdle {
            state =
                state.copy(
                    notice = "Network updated",
                    error = "Command failed",
                    capabilities = BouncerServCapabilities(),
                )
        }
        compose.onNodeWithTag("bouncer_command_notice").assertIsDisplayed()
        compose.onNodeWithTag("bouncer_command_error").assertIsDisplayed()
        compose.onNodeWithText("Check commands again").performClick()
        org.junit.Assert.assertTrue(probed)
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithTag("bouncer_command_notice").assertDoesNotExist()
        compose.onNodeWithTag("bouncer_command_error").assertDoesNotExist()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("bouncer_overview").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(notice = "Network updated") }
        compose.onNodeWithTag("bouncer_command_notice").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithTag("bouncer_command_notice").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(capabilities = BouncerServCapabilities(setOf("server status"), verified = true))
        }
        compose.onNodeWithTag("bouncer_overview_admin").performClick()
        compose.onNodeWithTag("bouncer_admin_panel").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(selectedTab = BouncerControlTab.OVERVIEW, capabilities = BouncerServCapabilities())
        }
        compose.onNodeWithTag("bouncer_overview_admin").assertDoesNotExist()
        compose.onNodeWithTag("bouncer_overview_channels").performClick()
        compose.onNodeWithTag("bouncer_channels_panel").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("bouncer_overview").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        org.junit.Assert.assertTrue(routeBack)
    }

    @Test
    fun bouncer_dashboard_offline_and_empty_messages() {
        var state by mutableStateOf(BouncerNetworksUiState())
        var refreshCount = 0
        var connected = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                BouncerNetworksContent(
                    state,
                    {},
                    BouncerControlCallbacks(
                        onRefresh = { refreshCount++ },
                        onConnect = { connected = true },
                        onSelectTab = { state = state.copy(selectedTab = it) },
                        onClearFeedback = { state = state.copy(notice = null, error = null) },
                    ),
                )
            }
        }
        compose.onNodeWithText("Connect to load networks").assertIsDisplayed()
        compose.onNodeWithText("Connect").performClick()
        org.junit.Assert.assertTrue(connected)
        compose.runOnIdle { state = state.copy(rootState = IrcClientState.Failed("timeout", fatal = false)) }
        compose.onNodeWithText("Retry").performClick()
        org.junit.Assert.assertTrue(connected)
        compose.onNodeWithTag("bouncer_overview_networks").performClick()
        compose.onNodeWithTag("bouncer_networks_panel").assertIsDisplayed()
        compose.onNodeWithText("Connect to load networks").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()

        compose.runOnIdle {
            state = state.copy(rootState = IrcClientState.Ready("me", emptySet(), emptyMap()), loading = true)
        }
        compose.onNodeWithText("Loading networks…").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(loading = false, listingLoaded = true)
        }
        compose.onNodeWithText("No networks yet. Open Networks to add one.").assertIsDisplayed()
        compose.onNodeWithTag("bouncer_overview_networks").performClick()
        compose.onNodeWithText("No networks yet. Open Networks to add one.").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()

        compose.runOnIdle {
            state =
                state.copy(
                    listingFailed = true,
                    error = "Listing failed",
                    capabilities = BouncerServCapabilities(setOf("server status"), verified = true),
                )
        }
        compose.onNodeWithTag("bouncer_command_error").assertIsDisplayed()
        compose.onNodeWithText("Could not load networks").assertIsDisplayed()
        compose.onNodeWithTag("bouncer_overview_admin").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        org.junit.Assert.assertEquals(1, refreshCount)
        compose.onNodeWithTag("bouncer_overview_networks").performClick()
        compose.onNodeWithTag("bouncer_command_error").assertIsDisplayed()
        compose.onNodeWithText("Could not load networks").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        org.junit.Assert.assertEquals(2, refreshCount)
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithTag("bouncer_command_error").assertDoesNotExist()
        compose.onNodeWithText("Could not load networks").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.runOnIdle {
            state =
                state.copy(
                    rows = listOf(BouncerNetRow("3", "Libera", null, "connected", childNetworkId = 4)),
                    rootState = IrcClientState.Disconnected,
                    listingFailed = false,
                )
        }
        compose.runOnIdle {
            state = state.copy(rootState = IrcClientState.Ready("me", emptySet(), emptyMap()), listingFailed = true, error = "Timed out")
        }
        compose.onNodeWithTag("bouncer_overview_networks").performClick()
        compose.onNodeWithText("Could not load networks").assertIsDisplayed()
    }

    @Test
    fun gesture_editor_uses_shared_settings_shell_without_losing_actions() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                GestureMenuEditorContent(GestureEditorUiState(loaded = true), {}, GestureEditorCallbacks())
            }
        }

        assertSharedShell()
        compose.onNodeWithTag(GESTURE_EDITOR_SCREEN_TAG).assertIsDisplayed()
    }

    private fun assertSharedShell() {
        compose.onNodeWithTag("settings_back").assertIsDisplayed()
        compose.onNodeWithTag("settings_scroll").assertIsDisplayed()
    }
}
