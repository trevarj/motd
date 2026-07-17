package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.avatar.AvatarRecord
import io.github.trevarj.motd.avatar.AvatarStore
import io.github.trevarj.motd.service.ConnectionManagerImpl
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.IrcForegroundService
import io.github.trevarj.motd.service.MotdNotifications
import io.github.trevarj.motd.ui.components.CertPromptViewModel
import io.github.trevarj.motd.ui.components.CertTrustDialog
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.RemoteAvatarState
import io.github.trevarj.motd.ui.nav.MotdNavGraph
import io.github.trevarj.motd.ui.nav.NotificationTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appearancePrefs: AppearancePrefs
    @Inject lateinit var avatarPrefs: AvatarPrefs
    @Inject lateinit var avatarStore: AvatarStore
    @Inject lateinit var contentPreviewPrefs: ContentPreviewPrefs
    @Inject lateinit var db: MotdDatabase
    @Inject lateinit var connectionManager: ConnectionManager

    // POST_NOTIFICATIONS runtime permission (API 33+); result is advisory, no action needed.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory */ }

    // Latest notification-tap deep-link target. Seeded from the launch intent (cold start) and
    // updated by onNewIntent (warm start); the nav graph consumes it and clears it after routing.
    private var notificationTarget by mutableStateOf<NotificationTarget?>(null)

    private val rootUiState by lazy {
        combine(
            settingsRepository.settings,
            appearancePrefs.config,
            avatarPrefs.config,
            avatarStore.records,
            contentPreviewPrefs.config,
        ) { settings, appearance, avatarConfig, avatarRecords, contentPreviews ->
            MainActivityUiState(settings, appearance, avatarConfig, avatarRecords, contentPreviews)
        }.stateIn(
            scope = lifecycleScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainActivityUiState(),
        )
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch/splash theme for the app theme before drawing Compose content.
        setTheme(R.style.Theme_Motd)
        super.onCreate(savedInstanceState)

        requestPostNotificationsIfNeeded()
        maybeStartForegroundService()
        // Cold start: the launcher created the activity with the notification's content intent.
        notificationTarget = parseNotificationTarget(intent)
        acceptInvitationFrom(intent)

        setContent {
            val uiState by rootUiState.collectAsStateWithLifecycle()
            val settings = uiState.settings
            val appearance = uiState.appearance
            MotdTheme(
                themePreset = appearance.theme,
                dynamicColor = settings.dynamicColor,
                layoutDensity = settings.layoutDensity,
                nickColorsEnabled = settings.nickColorsEnabled,
                nickColorPalette = settings.nickColorPalette,
                nickColorOverrides = settings.nickColorOverrides,
                avatarStyle = settings.avatarStyle,
                uiFontScalePercent = appearance.uiFontScalePercent,
            ) {
                CompositionLocalProvider(
                    LocalRemoteAvatars provides RemoteAvatarState(
                        enabled = uiState.avatarConfig.showSharedAvatars && uiState.contentPreviews.showImages,
                        records = uiState.avatarRecords,
                    ),
                ) {
                    // Root Surface paints the themed background under every screen (incl.
                    // non-Scaffold ones like onboarding) so the window follows the color scheme.
                    // Expose test tags once here for the uiautomator E2E harness.
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        MotdNavGraph(
                            notificationTarget = notificationTarget,
                            onNotificationTargetHandled = { notificationTarget = null },
                        )
                        // Global TOFU cert-trust dialog host, above the whole navigation graph.
                        CertTrustDialogHost()
                    }
                }
            }
        }
    }

    /** Warm start: the running activity is re-delivered the tapped notification's content intent. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseNotificationTarget(intent)?.let { notificationTarget = it }
        acceptInvitationFrom(intent)
    }

    private fun acceptInvitationFrom(intent: Intent?) {
        if (intent?.action != MotdNotifications.ACTION_ACCEPT_INVITE) return
        val messageId = intent.getLongExtra(MotdNotifications.EXTRA_INVITE_MESSAGE_ID, -1L)
        if (messageId >= 0) lifecycleScope.launch { connectionManager.acceptInvite(messageId) }
    }

    /**
     * Extract the deep-jump target from a notification content intent, or null when the intent
     * isn't one (e.g. a plain launcher launch). The msgid is optional — a null/missing msgid still
     * opens the buffer and the AROUND fallback handles a not-yet-cached target.
     */
    private fun parseNotificationTarget(intent: Intent?): NotificationTarget? {
        if (intent?.action != MotdNotifications.ACTION_OPEN_BUFFER &&
            intent?.action != MotdNotifications.ACTION_ACCEPT_INVITE
        ) return null
        val bufferId = intent.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1L)
        if (bufferId < 0) return null
        return NotificationTarget(
            bufferId = bufferId,
            jumpToMsgid = intent.getStringExtra(MotdNotifications.EXTRA_JUMP_MSGID),
            jumpToTime = intent.getLongExtra(MotdNotifications.EXTRA_JUMP_TIME, 0L),
            jumpToEventId = intent.getLongExtra(MotdNotifications.EXTRA_EVENT_ID, -1L)
                .takeIf { it >= 0L },
        )
    }

    /** Ask for POST_NOTIFICATIONS at first launch on API 33+ (needed for message/status notifs). */
    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Start the persistent-socket foreground service when delivery mode is PERSISTENT_SOCKET and
     * at least one auto-connect network exists (plans/05). Under UNIFIED_PUSH the socket stays
     * down and pushes drive delivery, so we skip it.
     */
    private fun maybeStartForegroundService() {
        lifecycleScope.launch {
            val persistent =
                settingsRepository.settings.first().deliveryMode == DeliveryMode.PERSISTENT_SOCKET
            val hasNetworks = db.networkDao().connectable().isNotEmpty()
            if (ConnectionManagerImpl.shouldRunService(persistent, hasNetworks)) {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, IrcForegroundService::class.java),
                )
            }
        }
    }
}

internal data class MainActivityUiState(
    val settings: Settings = Settings(),
    val appearance: AppearanceConfig = AppearanceConfig(),
    val avatarConfig: AvatarConfig = AvatarConfig(),
    val avatarRecords: List<AvatarRecord> = emptyList(),
    val contentPreviews: ContentPreviewConfig = ContentPreviewConfig(),
)

/** Collects the ConnectionManager's cert prompts and shows the dialog for the first pending one. */
@Composable
private fun CertTrustDialogHost(viewModel: CertPromptViewModel = hiltViewModel()) {
    val prompts by viewModel.certPrompts.collectAsStateWithLifecycle()
    val prompt = prompts.firstOrNull() ?: return
    CertTrustDialog(
        prompt = prompt,
        onTrust = { viewModel.trust(prompt) },
        onCancel = { viewModel.dismiss(prompt) },
    )
}
