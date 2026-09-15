package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NotificationChannelRow
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.FakeSettings
import io.github.trevarj.motd.gesture.testNetwork
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.NotificationConfig
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.service.NotificationSettingsImpl
import io.github.trevarj.motd.service.NotificationSettingsState
import io.github.trevarj.motd.ui.components.ChannelNotificationSheet
import io.github.trevarj.motd.ui.components.deriveChannelNotificationPresentation
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NotificationSettingsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val viewModels = ViewModelStore()
    private var scope: CoroutineScope? = null
    private var saved = NotificationConfig(channels = mapOf(21L to NotificationMode.OFF))
    private var failRead = false
    private var failWrite = false
    private var loadGate: CompletableDeferred<Unit>? = null
    private var writeGate: CompletableDeferred<Unit>? = null

    @After
    fun tearDown() {
        viewModels.clear()
        scope?.cancel()
    }

    @Test
    fun hierarchyEditsOneScopeAndKeepsSiblingState() {
        openScreen(target = SettingsTarget.NOTIFICATIONS)
        compose.onNodeWithTag("screen_notification_settings").assertIsDisplayed()
        compose.onNodeWithTag("settings_target_highlight_NOTIFICATIONS").assertIsDisplayed()
        row("notification_global").assertTextContains("Mentions and direct messages")
        compose.onNodeWithTag("notification_settings_list").performScrollToNode(hasText(PUSH_DISCLOSURE))
        compose.onNodeWithText(PUSH_DISCLOSURE).assertIsDisplayed()
        compose.onNodeWithTag("notification_channel_11").assertDoesNotExist()

        row("notification_global").performClick()
        compose.onNodeWithTag("notification_mode_mentions").assertIsSelected()
        compose.onNodeWithTag("notification_mode_off").performClick()
        row("notification_server_1").performClick()
        row("notification_channel_11").assertTextContains("Off · Global default", substring = true)
        row("notification_global").performClick()
        compose.onNodeWithTag("notification_mode_mentions").performClick()
        row("notification_channel_11").assertTextContains("Mentions only · Global default", substring = true)

        serverMode("all")
        row("notification_channel_11").assertTextContains("All messages · Server default", substring = true)
        row("notification_channel_12").assertTextContains("All messages · Server default", substring = true)
        channelChoice(11, "mode_off")
        serverMode("mentions")
        row("notification_channel_11").assertTextContains("Off · Channel override", substring = true)
        row("notification_channel_12").assertTextContains("Mentions only · Server default", substring = true)
        row("notification_server_1").assertTextContains("1 channel override", substring = true)

        channelChoice(11, "mode_inherit")
        row("notification_channel_11").assertTextContains("Mentions only · Server default", substring = true)
        row("notification_server_1").assertTextContains("0 channel overrides", substring = true)
        channelChoice(11, "watch_15")
        channelChoice(12, "watch_forever")
        row("notification_server_1").assertTextContains("2 active watches", substring = true)
        row("notification_channel_11")
            .assertTextContains("Mentions only · Server default", substring = true)
            .assertTextContains("Notify on all: 15 min left", substring = true)
            .assertTextContains("Chat muted", substring = true)
        row("notification_channel_12").assertTextContains("Notify on all: Forever", substring = true)

        channelChoice(11, "watch_stop")
        row("notification_server_1").assertTextContains("1 active watch", substring = true)
        row("notification_channel_11").assertTextContains("Mentions only · Server default\nChat muted")
        row("notification_channel_12").assertTextContains("Notify on all: Forever", substring = true)
        serverMode("inherit")
        row("notification_channel_11").assertTextContains("Mentions only · Global default\nChat muted")
        row("notification_channel_12")
            .assertTextContains("Mentions only · Global default", substring = true)
            .assertTextContains("Notify on all: Forever", substring = true)

        row("notification_server_2").performClick()
        compose.onNodeWithTag("notification_channel_11").assertDoesNotExist()
        row("notification_channel_21")
            .assertTextContains("#same")
            .assertTextContains("Off · Channel override")
        row("notification_server_2")
            .assertTextContains("1 channel override", substring = true)
            .assertTextContains("0 active watches", substring = true)
        row("notification_server_3").performClick()
        compose.onNodeWithTag("notification_channel_21").assertDoesNotExist()
        compose.onNodeWithTag("notification_settings_list").performScrollToNode(hasText("No known channels"))
        compose.onNodeWithText("No known channels").assertIsDisplayed()
    }

    @Test
    fun loadingAndUnavailableDisableEditsUntilRetryAndFailedSaveKeepsKnownPolicy() {
        saved = NotificationConfig(global = NotificationMode.ALL, channels = mapOf(21L to NotificationMode.OFF))
        loadGate = CompletableDeferred()
        openScreen()
        row("notification_global")
            .assertIsNotEnabled()
            .assertTextContains("Loading notification settings…")
        compose.onNodeWithTag("notification_settings_loading").assertIsDisplayed()
        compose.runOnIdle {
            failRead = true
            loadGate?.complete(Unit)
        }
        row("notification_global")
            .assertIsNotEnabled()
            .assertTextContains("Notification settings unavailable")
        compose.onNodeWithText("Notification settings could not be loaded. Message alerts are off until retry succeeds.").assertIsDisplayed()
        compose.onNodeWithTag("notification_mode_all").assertDoesNotExist()
        compose.runOnIdle { failRead = false }
        compose.onNodeWithText("Retry").performClick()
        row("notification_global").assertIsEnabled().assertTextContains("All messages")

        compose.runOnIdle {
            failWrite = true
            writeGate = CompletableDeferred()
        }
        row("notification_global").performClick()
        compose.onNodeWithTag("notification_mode_off").performClick()
        row("notification_global").assertTextContains("All messages")
        compose.onNodeWithText("Could not save notification settings.").assertDoesNotExist()
        compose.runOnIdle { writeGate?.complete(Unit) }
        compose.onNodeWithText("Could not save notification settings.").assertIsDisplayed()
        row("notification_global").assertTextContains("All messages")
        compose.onNodeWithContentDescription("Dismiss").performClick()
        row("notification_server_2").performClick()
        row("notification_channel_21").assertTextContains("Off · Channel override")

        compose.runOnIdle { failWrite = false }
        row("notification_global").performClick()
        compose.onNodeWithTag("notification_mode_off").performClick()
        row("notification_global").assertTextContains("Off")
        compose.onNodeWithText("Could not save notification settings.").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun sharedEditorShowsNoLoadingChoicesAndDisablesUnavailableActions() {
        var settingsState by mutableStateOf<NotificationSettingsState>(NotificationSettingsState.Loading)
        var mutations = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChannelNotificationSheet(
                    presentation = deriveChannelNotificationPresentation(settingsState, 1, 11, muted = true, nowMillis = 1_000),
                    onMode = { mutations++ },
                    onStart = { mutations++ },
                    onStop = { mutations++ },
                    onDismiss = {},
                    tagPrefix = "notification",
                )
            }
        }
        compose.onNodeWithTag("notification_notify_loading").assertIsDisplayed()
        compose.onNodeWithTag("notification_mode_inherit").assertDoesNotExist()
        compose.onNodeWithTag("notification_watch_15").assertDoesNotExist()
        compose.runOnIdle { settingsState = NotificationSettingsState.Unavailable }
        compose
            .onNodeWithText("Notification settings unavailable. Retry from Settings → Notifications.")
            .performScrollTo()
            .assertIsDisplayed()
        listOf("mode_inherit", "mode_all", "mode_mentions", "mode_off", "watch_15", "watch_30", "watch_60", "watch_forever").forEach { tag ->
            compose
                .onNodeWithTag("notification_$tag")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotEnabled()
                .performClick()
        }
        compose.runOnIdle { assertEquals(0, mutations) }
    }

    private fun openScreen(target: SettingsTarget? = null) {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = ownerScope
        val clock = AppClock { 1_000L }
        val owner =
            NotificationSettingsImpl(
                scope = ownerScope,
                clock = clock,
                onExpired = {},
                load = {
                    loadGate?.await()
                    if (failRead) throw IOException("read failed")
                    preferencesOf(stringPreferencesKey("config_v1") to Json.encodeToString(saved))
                },
                save = {
                    writeGate?.await()
                    if (failWrite) throw IOException("save failed")
                    saved = it
                },
            )
        val model =
            NotificationSettingsViewModel(
                notificationSettings = owner,
                networkRepository = FakeNetworks(listOf(testNetwork(1, "One"), testNetwork(2, "Two"), testNetwork(3, "Empty"))),
                bufferRepository =
                    object : BufferRepository by FakeBuffers() {
                        override fun observeNotificationChannels() =
                            flowOf(
                                listOf(
                                    NotificationChannelRow(11, 1, "#same", joined = true, archived = false, muted = true),
                                    NotificationChannelRow(12, 1, "#second", joined = false, archived = true, muted = false),
                                    NotificationChannelRow(21, 2, "#same", joined = true, archived = false, muted = false),
                                ),
                            )
                    },
                settingsRepository = FakeSettings().apply { state.value = state.value.copy(deliveryMode = DeliveryMode.UNIFIED_PUSH) },
                clock = clock,
            )
        viewModels.put("notifications", model)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                NotificationSettingsScreen(viewModel = model, target = target)
            }
        }
    }

    private fun row(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("notification_settings_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun serverMode(mode: String) {
        row("notification_server_default_1").performClick()
        compose.onNodeWithTag("notification_mode_$mode").performClick()
    }

    private fun channelChoice(
        bufferId: Long,
        choice: String,
    ) {
        row("notification_channel_$bufferId").performClick()
        compose.onNodeWithTag("notification_$choice").performScrollTo().performClick()
    }

    private companion object {
        const val PUSH_DISCLOSURE =
            "UnifiedPush delivers direct messages and mentions while motd is asleep. All-message alerts for ordinary channel traffic work only while connected."
    }
}
