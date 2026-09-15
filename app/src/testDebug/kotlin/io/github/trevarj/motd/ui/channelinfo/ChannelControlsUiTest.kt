package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.service.ChannelWatchDuration
import io.github.trevarj.motd.service.NotificationConfig
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.service.NotificationSettingsImpl
import io.github.trevarj.motd.service.NotificationSettingsState
import io.github.trevarj.motd.ui.components.ChannelNotificationPresentation
import io.github.trevarj.motd.ui.components.deriveChannelNotificationPresentation
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the stateless Channel Info content and the shared ban picker with fake callbacks. The
 * fixture viewer is an op, so the controls section renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChannelControlsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val members = listOf(MemberEntity(1, "me", "@"), MemberEntity(1, "bob", ""))

    @Test
    fun inviteRowOpensSharedPickerRoute() {
        var opened = 0
        compose.setContent {
            Controls(
                catalog = ModeCatalog.DEFAULT,
                onInvite = { opened++ },
            )
        }

        compose.onNodeWithTag("channelinfo_invite_row").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun curatedRowsRender_andExceptionRowsAreAbsentWhenNotAdvertised() {
        compose.setContent { Controls(catalog = ModeCatalog.from(mapOf("CHANMODES" to "b,k,l,imnst"))) }

        compose.onNodeWithTag("channelinfo_controls_section").assertExists()
        listOf('i', 'm', 'n', 't', 's').forEach { letter ->
            compose.onNodeWithTag("channelinfo_mode_row_$letter").performScrollTo().assertExists()
        }
        compose.onNodeWithTag("channelinfo_key_row").performScrollTo().assertExists()
        compose.onNodeWithTag("channelinfo_limit_row").performScrollTo().assertExists()
        compose.onNodeWithTag("channelinfo_bans_row").performScrollTo().assertExists()
        // No EXCEPTS/INVEX token: those rows must be absent entirely, not present but disabled.
        compose.onAllNodesWithTag("channelinfo_excepts_row").assertCountEquals(0)
        compose.onAllNodesWithTag("channelinfo_invex_row").assertCountEquals(0)
    }

    @Test
    fun exceptionRowsAppearWhenTheCatalogAdvertisesThem() {
        compose.setContent {
            Controls(
                catalog =
                    ModeCatalog.from(
                        mapOf("CHANMODES" to "beI,k,l,imnst", "EXCEPTS" to "", "INVEX" to ""),
                    ),
            )
        }

        compose.onNodeWithTag("channelinfo_excepts_row").performScrollTo().assertExists()
        compose.onNodeWithTag("channelinfo_invex_row").performScrollTo().assertExists()
    }

    @Test
    fun banPicker_disablesAddressScopeUntilAHostIsKnown_andThePreviewFollowsIt() {
        var host by mutableStateOf<String?>(null)
        compose.setContent {
            MotdTheme {
                BanTargetPicker(
                    state = rememberBanTargetState("bob"),
                    members = listOf("bob"),
                    resolvedHost = host,
                    hostLoading = true,
                    onNickSelected = {},
                    tagPrefix = "channelinfo_ban",
                )
            }
        }

        compose.onNodeWithTag("channelinfo_ban_preview").assertTextEquals("bob!*@*")
        compose.onNodeWithTag("channelinfo_ban_scope_host").assertIsNotEnabled()

        host = "host.example.net"
        compose.onNodeWithTag("channelinfo_ban_scope_host").performClick()

        compose.onNodeWithTag("channelinfo_ban_preview").assertTextEquals("*!*@host.example.net")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun notificationPolicyAndWatchActionsRemainReachableInShortWindows() {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val initial =
            NotificationConfig(
                servers = mapOf(1L to NotificationMode.ALL),
                watches = mapOf(1L to Long.MAX_VALUE, 2L to Long.MAX_VALUE),
            )
        val notificationSettings =
            NotificationSettingsImpl(
                scope = ownerScope,
                clock = AppClock { 1_000L },
                onExpired = {},
                load = { preferencesOf(stringPreferencesKey("config_v1") to Json.encodeToString(initial)) },
            )
        try {
            compose.setContent {
                val settings by notificationSettings.state.collectAsState()
                Controls(
                    catalog = null,
                    notificationSummary = deriveChannelNotificationPresentation(settings, 1, 1, muted = true, nowMillis = 1_000),
                    onChannelNotificationMode = { ownerScope.launch { notificationSettings.setChannel(1, it) } },
                    onStartWatch = { ownerScope.launch { notificationSettings.startWatch(1, it.millis) } },
                    onStopWatch = { ownerScope.launch { notificationSettings.stopWatch(1) } },
                )
            }

            listOf(NotificationMode.ALL, NotificationMode.MENTIONS, NotificationMode.OFF, null).forEach { mode ->
                openNotifications()
                compose
                    .onNodeWithTag("channelinfo_mode_${mode?.name?.lowercase() ?: "inherit"}")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .performClick()
                compose.onNodeWithTag("channelinfo_notify_dialog").assertDoesNotExist()
                val policy =
                    when (mode) {
                        NotificationMode.ALL, null -> "All messages"
                        NotificationMode.MENTIONS -> "Mentions only"
                        NotificationMode.OFF -> "Off"
                    }
                compose
                    .onNodeWithTag("channelinfo_notify_level")
                    .assertTextContains("$policy · ${if (mode == null) "Server default" else "Channel override"}", substring = true)
                    .assertTextContains("Chat muted", substring = true)
                    .assertTextContains("Notify on all: Forever", substring = true)
            }

            ChannelWatchDuration.entries.forEach { duration ->
                openNotifications()
                compose
                    .onNodeWithTag("channelinfo_watch_${duration.tag}")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .performClick()
                compose.onNodeWithTag("channelinfo_notify_dialog").assertDoesNotExist()
                val remaining = duration.millis?.let { "${it / 60_000} min left" } ?: "Forever"
                compose
                    .onNodeWithTag("channelinfo_notify_level")
                    .assertTextContains("Notify on all: $remaining", substring = true)
                    .assertTextContains("All messages · Server default", substring = true)
                    .assertTextContains("Chat muted", substring = true)
                compose.runOnIdle {
                    val config = (notificationSettings.state.value as NotificationSettingsState.Ready).config
                    assertEquals(mapOf(1L to (duration.millis?.plus(1_000) ?: Long.MAX_VALUE), 2L to Long.MAX_VALUE), config.watches)
                }
            }

            openNotifications()
            compose
                .onNodeWithTag("channelinfo_watch_stop")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("channelinfo_notify_dialog").assertDoesNotExist()
            compose.onNodeWithTag("channelinfo_notify_level").assertTextContains("All messages · Server default\nChat muted")
            compose.runOnIdle {
                val config = (notificationSettings.state.value as NotificationSettingsState.Ready).config
                assertEquals(mapOf(2L to Long.MAX_VALUE), config.watches)
            }
            openNotifications()
            compose.onNodeWithTag("channelinfo_watch_stop").assertDoesNotExist()
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun loadingAndUnavailableExposeNoGuessedPolicyOrEnabledMutations() {
        var settings by mutableStateOf<NotificationSettingsState>(NotificationSettingsState.Loading)
        var mutations = 0
        compose.setContent {
            Controls(
                catalog = null,
                notificationSummary = deriveChannelNotificationPresentation(settings, 1, 1, muted = true, nowMillis = 1_000),
                onChannelNotificationMode = { mutations++ },
                onStartWatch = { mutations++ },
                onStopWatch = { mutations++ },
            )
        }
        openNotifications()
        compose.onNodeWithTag("channelinfo_notify_loading").assertIsDisplayed()
        val actions = listOf("mode_inherit", "mode_all", "mode_mentions", "mode_off", "watch_15", "watch_30", "watch_60", "watch_forever")
        actions.forEach { tag -> compose.onNodeWithTag("channelinfo_$tag").assertDoesNotExist() }
        compose.onNodeWithTag("channelinfo_watch_stop").assertDoesNotExist()

        compose.runOnIdle { settings = NotificationSettingsState.Unavailable }
        compose
            .onNodeWithText("Notification settings unavailable. Retry from Settings → Notifications.")
            .performScrollTo()
            .assertIsDisplayed()
        actions.forEach { tag ->
            compose
                .onNodeWithTag("channelinfo_$tag")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotEnabled()
                .performClick()
        }
        compose.onNodeWithTag("channelinfo_watch_stop").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, mutations) }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithTag("channelinfo_notify_dialog").assertDoesNotExist()
        compose.onNodeWithTag("channelinfo_notify_level").assertTextEquals("Notifications", "Notification settings unavailable")
    }

    private fun openNotifications() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("channelinfo_notify_level"))
        compose.onNodeWithTag("channelinfo_notify_level").performClick()
        compose.onNodeWithTag("channelinfo_notify_dialog").assertIsDisplayed()
    }

    @Composable
    private fun Controls(
        catalog: ModeCatalog?,
        onSetLimit: (Int?) -> Unit = {},
        onInvite: () -> Unit = {},
        notificationSummary: ChannelNotificationPresentation = ChannelNotificationPresentation(),
        onChannelNotificationMode: (NotificationMode?) -> Unit = {},
        onStartWatch: (ChannelWatchDuration) -> Unit = {},
        onStopWatch: () -> Unit = {},
    ) {
        MotdTheme {
            ChannelInfoContent(
                state =
                    ChannelInfoUiState(
                        buffer = BufferEntity(1, 1, "#room", "#room", BufferType.CHANNEL, muted = notificationSummary.muted),
                        sections = sectionMembers(members),
                        canModerate = true,
                        connected = true,
                        modeCatalog = catalog,
                    ),
                onBack = {},
                onSetPinned = {},
                onSetMuted = {},
                onLeave = {},
                onSetLimit = onSetLimit,
                onInvite = onInvite,
                notificationSummary = notificationSummary,
                onChannelNotificationMode = onChannelNotificationMode,
                onStartWatch = onStartWatch,
                onStopWatch = onStopWatch,
            )
        }
    }
}
