package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.avatar.AvatarRecord
import io.github.trevarj.motd.avatar.AvatarStore
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.di.NotificationPermissionStatus
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.gesture.radial.GestureOrbHost
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ConnectionManagerImpl
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.IrcForegroundService
import io.github.trevarj.motd.service.MotdNotifications
import io.github.trevarj.motd.service.startForegroundSafely
import io.github.trevarj.motd.ui.chat.PreloadChatWallpaperTile
import io.github.trevarj.motd.ui.components.CertPromptViewModel
import io.github.trevarj.motd.ui.components.CertTrustDialog
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.RemoteAvatarState
import io.github.trevarj.motd.ui.components.RemoteMediaNetwork
import io.github.trevarj.motd.ui.components.RemoteMediaNetworkMonitor
import io.github.trevarj.motd.ui.components.automaticRemoteMediaAllowed
import io.github.trevarj.motd.ui.nav.MotdNavGraph
import io.github.trevarj.motd.ui.nav.NotificationTarget
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.share.PendingShareStore
import io.github.trevarj.motd.ui.share.parseSharedContent
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SystemBarThemeHost
import io.github.trevarj.motd.ui.theme.TimestampConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity :
    ComponentActivity(),
    SystemBarThemeHost {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var appearancePrefs: AppearancePrefs

    @Inject lateinit var avatarPrefs: AvatarPrefs

    @Inject lateinit var avatarStore: AvatarStore

    @Inject lateinit var contentPreviewPrefs: ContentPreviewPrefs

    @Inject lateinit var remoteMediaNetworkMonitor: RemoteMediaNetworkMonitor

    @Inject lateinit var networkMediaHttp: NetworkMediaHttp

    @Inject lateinit var customFontStore: CustomFontStore

    @Inject lateinit var db: MotdDatabase

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var notificationPermission: NotificationPermissionStatus

    @Inject lateinit var pendingShareStore: PendingShareStore

    @Inject lateinit var diagnostics: DiagnosticLogger

    // POST_NOTIFICATIONS is a delivery concern: report its result immediately to Settings state.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationPermission.onPermissionResult(it)
        }

    // Latest notification-tap deep-link target. Seeded from the launch intent (cold start) and
    // updated by onNewIntent (warm start); the nav graph consumes it and clears it after routing.
    private var notificationTarget by mutableStateOf<NotificationTarget?>(null)

    // Latest inbound ACTION_SEND payload, seeded/updated the same way. The payload itself is parked
    // in [pendingShareStore]; this state only drives the navigation to the chat picker.
    private var pendingShare by mutableStateOf<PendingShare?>(null)

    // Validated external motd://invite payload. Empty means an invite URI was present but invalid,
    // so the destination can render a safe error instead of silently dropping the user's scan.
    private var pendingJoinInvite by mutableStateOf<String?>(null)

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
        enableEdgeToEdge()

        requestPostNotificationsIfNeeded()
        maybeStartForegroundService()
        // Cold start: the launcher created the activity with the notification's content intent.
        notificationTarget = parseNotificationTarget(intent)
        acceptShareFrom(intent)
        acceptJoinInviteFrom(intent)
        acceptInvitationFrom(intent)

        setContent {
            // Theme changes can replace the MaterialTheme subtree. Keep navigation above that
            // boundary so applying a palette never resets the user to the start destination.
            val navController = rememberNavController()
            val uiState by rootUiState.collectAsStateWithLifecycle()
            val remoteMediaNetwork by
                remoteMediaNetworkMonitor.network.collectAsStateWithLifecycle(
                    initialValue = RemoteMediaNetwork.UNAVAILABLE,
                )
            val automaticRemoteMedia =
                automaticRemoteMediaAllowed(remoteMediaNetwork, uiState.contentPreviews)
            val settings = uiState.settings
            val appearance = uiState.appearance
            // Re-check the on-disk font whenever the imported name changes (a fresh import or a
            // backup restore that cleared it), or when CustomFontStore's revision bumps (a
            // same-name re-import changes no persisted state, so the name alone would miss it).
            val fontRevision by customFontStore.revision.collectAsStateWithLifecycle()
            val customFontFile =
                remember(appearance.customFontName, fontRevision) {
                    customFontStore.installedFile()
                }
            MotdTheme(
                themePreset = appearance.theme,
                trueBlack = appearance.trueBlack,
                dynamicColor = settings.dynamicColor,
                followSystem = appearance.followSystem,
                layoutDensity = settings.layoutDensity,
                nickColorsEnabled = settings.nickColorsEnabled,
                nickColorPalette = settings.nickColorPalette,
                nickColorOverrides = settings.nickColorOverrides,
                avatarStyle = settings.avatarStyle,
                uiFontScalePercent = appearance.uiFontScalePercent,
                fontChoice = appearance.fontChoice,
                customFontFile = customFontFile,
                timestampConfig =
                    TimestampConfig(
                        appearance.showTimestamps,
                        appearance.timeFormat,
                        appearance.customTimeFormatPattern,
                    ),
                messageSpacing = appearance.messageSpacing,
                bubbleCornerStyle = appearance.bubbleCornerStyle,
            ) {
                CompositionLocalProvider(
                    LocalAutomaticRemoteMedia provides automaticRemoteMedia,
                    LocalNetworkMediaHttp provides networkMediaHttp,
                    LocalRemoteAvatars provides
                        RemoteAvatarState(
                            enabled = uiState.avatarConfig.showSharedAvatars && uiState.contentPreviews.showImages,
                            records = uiState.avatarRecords,
                        ),
                ) {
                    // Root Surface paints the themed background under every screen (incl.
                    // non-Scaffold ones like onboarding) so the window follows the color scheme.
                    // Expose test tags once here for the uiautomator E2E harness.
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                // Edge-to-edge windows receive IME insets instead of being resized.
                                // Consume them once here so every destination stays above the keyboard.
                                .imePadding()
                                .semantics { testTagsAsResourceId = true },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        PreloadChatWallpaperTile(appearance.wallpaper)
                        MotdNavGraph(
                            appearance = appearance,
                            showComposerEmoji = settings.showComposerEmoji,
                            showComposerFormattingTools = settings.showComposerFormattingTools,
                            navController = navController,
                            notificationTarget = notificationTarget,
                            onNotificationTargetHandled = ::consumeNotificationTarget,
                            pendingShare = pendingShare,
                            onPendingShareHandled = ::consumePendingShare,
                            pendingJoinInvite = pendingJoinInvite,
                            onPendingJoinInviteHandled = ::consumePendingJoinInvite,
                        )
                        // Global TOFU cert-trust dialog host, above the whole navigation graph.
                        CertTrustDialogHost()
                        // Labs gesture orb, above every destination and inert while the lab is off.
                        GestureOrbHost(navController)
                    }
                }
            }
        }
    }

    /** Warm start: the running activity is re-delivered the tapped notification's content intent. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        warmNotificationEntry(
            intent,
            onTarget = { notificationTarget = it },
            // The manager reconnects stale actors and skips the pass for any connection that never
            // died, so repeated taps do not storm the wire. The tapped buffer is named so it is
            // reconciled first rather than behind whatever the checkpoint decides.
            onCheckpointHistory = { bufferId ->
                lifecycleScope.launch { connectionManager.checkpointHistory(bufferId) }
            },
        )
        acceptShareFrom(intent)
        acceptJoinInviteFrom(intent)
        acceptInvitationFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        notificationPermission.refresh()
    }

    /** Prevent recreation from replaying a notification whose navigation was already consumed. */
    private fun consumeNotificationTarget() {
        notificationTarget = null
        if (intent.action == MotdNotifications.ACTION_OPEN_BUFFER ||
            intent.action == MotdNotifications.ACTION_ACCEPT_INVITE
        ) {
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    /** Prevent recreation from replaying a share whose picker was already opened. */
    private fun consumePendingShare() {
        pendingShare = null
        if (intent.action == Intent.ACTION_SEND) {
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    /** Park a single-item share payload for the picker; ignores every non-share intent. */
    private fun acceptShareFrom(intent: Intent?) {
        parseSharedContent(intent)?.let {
            pendingShareStore.set(it)
            pendingShare = it
        }
    }

    private fun acceptJoinInviteFrom(intent: Intent?) {
        parseJoinInvitePayload(intent)?.let { pendingJoinInvite = it }
    }

    private fun consumePendingJoinInvite() {
        pendingJoinInvite = null
        if (intent.action == Intent.ACTION_VIEW) {
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    private fun acceptInvitationFrom(intent: Intent?) {
        if (intent?.action != MotdNotifications.ACTION_ACCEPT_INVITE) return
        val messageId = intent.getLongExtra(MotdNotifications.EXTRA_INVITE_MESSAGE_ID, -1L)
        if (messageId >= 0) lifecycleScope.launch { connectionManager.acceptInvite(messageId) }
    }

    /** Make at most one automatic POST_NOTIFICATIONS request; later denial routes to Settings. */
    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val rationaleAvailable = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (notificationPermission.shouldRequestAutomatically(rationaleAvailable)) {
            notificationPermission.markRequestLaunched()
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Start the persistent-socket foreground service when delivery mode is PERSISTENT_SOCKET and
     * at least one auto-connect network exists. Under UNIFIED_PUSH the socket stays
     * down and pushes drive delivery, so we skip it.
     */
    private fun maybeStartForegroundService() {
        lifecycleScope.launch {
            val persistent =
                settingsRepository.settings.first().deliveryMode == DeliveryMode.PERSISTENT_SOCKET
            val hasNetworks = db.networkDao().connectable().isNotEmpty()
            if (ConnectionManagerImpl.shouldRunService(persistent, hasNetworks)) {
                // Two suspension points precede this call, so the activity may already be
                // backgrounded by the time it runs — and Android 12+ answers a background start
                // with ForegroundServiceStartNotAllowedException. Losing the keeper here is
                // recoverable (the next foreground re-arms it); crashing on it is not.
                startForegroundSafely(diagnostics, source = "activity") {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, IrcForegroundService::class.java),
                    )
                }
            }
        }
    }
}

/** Null means unrelated intent; empty means matching motd invite with invalid untrusted data. */
internal fun parseJoinInvitePayload(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW || intent.data?.scheme != "motd" || intent.data?.host != "invite") return null
    return runCatching { JoinInviteCodec.encode(JoinInviteCodec.parse(intent.data.toString())) }.getOrDefault("")
}

/**
 * Extract the deep-jump target from a notification content intent, or null when the intent isn't
 * one (e.g. a plain launcher launch). The msgid is optional — a null/missing msgid still opens the
 * buffer and the AROUND fallback handles a not-yet-cached target.
 */
internal fun parseNotificationTarget(intent: Intent?): NotificationTarget? {
    if (intent?.action != MotdNotifications.ACTION_OPEN_BUFFER &&
        intent?.action != MotdNotifications.ACTION_ACCEPT_INVITE
    ) {
        return null
    }
    val bufferId = intent.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1L)
    if (bufferId < 0) return null
    return NotificationTarget(
        bufferId = bufferId,
        jumpToMsgid = intent.getStringExtra(MotdNotifications.EXTRA_JUMP_MSGID),
        jumpToTime = intent.getLongExtra(MotdNotifications.EXTRA_JUMP_TIME, 0L),
        jumpToEventId =
            intent
                .getLongExtra(MotdNotifications.EXTRA_EVENT_ID, -1L)
                .takeIf { it >= 0L },
    )
}

/**
 * Warm-start entry decision for a re-delivered intent.
 *
 * A notification content intent is both a deep jump and a history checkpoint: it is the one
 * foreground entry ProcessLifecycleOwner never reports (the process was already foregrounded), so
 * nothing else on this path re-checks what the server accepted while the process sat idle. Every
 * other warm intent — a share, a plain launcher relaunch — is neither.
 *
 * The checkpoint is handed the TAPPED buffer, not just told to run: the one conversation the user
 * is opening is the one that has to be current, and it must not be discovered somewhere inside a
 * network-wide pass (or skipped entirely by a checkpoint that decides the socket never died).
 *
 * Hoisted out of [MainActivity.onNewIntent] because that call site is only reachable with an
 * instrumented activity, while the pairing itself is what the checkpoint depends on.
 */
internal fun warmNotificationEntry(
    intent: Intent?,
    onTarget: (NotificationTarget) -> Unit,
    onCheckpointHistory: (bufferId: Long) -> Unit,
) {
    val target = parseNotificationTarget(intent) ?: return
    onTarget(target)
    onCheckpointHistory(target.bufferId)
}

internal data class MainActivityUiState(
    val settings: Settings = Settings(),
    val appearance: AppearanceConfig = AppearanceConfig(),
    val avatarConfig: AvatarConfig = AvatarConfig(),
    val avatarRecords: List<AvatarRecord> = emptyList(),
    val contentPreviews: ContentPreviewConfig =
        ContentPreviewConfig(
            showImages = false,
            showLinkPreviews = false,
            autoLoadOnUnmetered = false,
            autoLoadOnMetered = false,
        ),
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
