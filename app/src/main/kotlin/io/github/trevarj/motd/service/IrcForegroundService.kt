package io.github.trevarj.motd.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.MainActivity
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.di.NotificationPermissionStatus
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground-service keeper for the connection subsystem. Thin [LifecycleService]:
 * onStartCommand → startForeground(status) + connectionManager.startAll(); explicit [ACTION_STOP] →
 * stopAll() + stopSelf(). Service removal itself never stops the manager (see the note above the
 * companion). START_STICKY so Android restarts it after a kill while PERSISTENT_SOCKET is in
 * effect; a user-initiated stop returns START_NOT_STICKY instead.
 */
@AndroidEntryPoint
class IrcForegroundService : LifecycleService() {
    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var notifications: MotdNotifications

    @Inject lateinit var diagnostics: DiagnosticLogger

    @Inject lateinit var networkRepository: NetworkRepository

    @Inject lateinit var notificationPermission: NotificationPermissionStatus

    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private var networkNames = emptyMap<Long, String>()
    private var foreground = false

    private val contentIntent by lazy { statusContentIntent(this) }
    private val disconnectIntent by lazy { statusDisconnectIntent(this) }

    /**
     * The shape currently on screen, whichever entry point put it there. Confined to the main
     * thread: [onStartCommand] is a main-thread callback and [lifecycleScope] dispatches there.
     */
    private var postedShape: StatusNotificationShape? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            val names = networkRepository.observeNetworks().map { networks -> networks.associate { it.id to it.name } }
            combine(
                connectionManager.connectionActivity,
                names.onStart { emit(emptyMap()) },
                defaultNetworkAvailability(),
                notificationPermission.granted,
            ) { activity, latestNames, hasDefaultNetwork, _ ->
                networkNames = latestNames
                statusNotificationShape(activity, latestNames, hasDefaultNetwork)
            }.collect(::updateStatus)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch {
                connectionManager.stopAll()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startAsForeground()
        lifecycleScope.launch { connectionManager.startAll() }
        return START_STICKY
    }

    // The merged manifest declares specialUse; AGP lint loses that declaration when analyzing the
    // service in isolation, so the check is suppressed here rather than disabled project-wide.
    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        // Re-entry may find live sockets before either metadata or connection state emits again.
        val shape =
            statusNotificationShape(
                connectionManager.connectionActivity.value,
                networkNames,
                connectivity?.let { it.activeNetwork != null },
            )
        if (foreground) {
            updateStatus(shape)
            return
        }
        val notification = buildStatusNotification(this, shape, contentIntent, disconnectIntent)
        val started =
            startForegroundSafely(diagnostics, source = "service") {
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an API 34 constant; only pass the type on 34+.
                // On 29-33 use the 2-arg overload (the manifest still declares foregroundServiceType).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(STATUS_ID, notification)
                }
            }
        if (started) {
            foreground = true
            postedShape = shape.takeIf { notificationPermission.granted.value }
        }
    }

    private fun updateStatus(shape: StatusNotificationShape) {
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            postedShape = null
            return
        }
        if (shape == postedShape) return
        val notification = buildStatusNotification(this, shape, contentIntent, disconnectIntent)
        if (runCatching { NotificationManagerCompat.from(this).notify(STATUS_ID, notification) }.isSuccess) {
            postedShape = shape
        }
    }

    private fun defaultNetworkAvailability(): Flow<Boolean?> =
        callbackFlow {
            val manager = connectivity
            if (manager == null) {
                trySend(null)
                close()
                return@callbackFlow
            }
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(true)
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
            val registered = runCatching { manager.registerDefaultNetworkCallback(callback, Handler(mainLooper)) }.isSuccess
            trySend(manager.activeNetwork != null)
            awaitClose { if (registered) manager.unregisterNetworkCallback(callback) }
        }

    // Service removal during a fully verified push hand-off must not disable the singleton
    // connection subsystem. Explicit ACTION_STOP performs stopAll above; process death naturally
    // tears down both service and manager together.

    companion object {
        const val STATUS_ID = 1
        const val ACTION_STOP = "io.github.trevarj.motd.service.STOP"
    }
}

private const val FOREGROUND_START_TAG = "ForegroundStart"

/**
 * Run a foreground-service start that the platform is allowed to refuse, and survive the refusal.
 *
 * Android 12+ throws `ForegroundServiceStartNotAllowedException` (and 14+ adds
 * `MissingForegroundServiceTypeException`/`SecurityException`) whenever the caller is no longer
 * eligible to hold a foreground service — a service entered from a background start, or an activity
 * that was backgrounded while its launch coroutine suspended. Losing the socket keeper is a
 * degradation; crashing the process over it is not, so the refusal is contained the same way
 * `IrcForegroundService.updateStatus` already contains its `notify`. Recorded as well as logged
 * because the diagnostic journal is where a keeper that never armed becomes explainable.
 *
 * Returns whether the start was accepted.
 */
internal fun startForegroundSafely(
    diagnostics: DiagnosticLogger,
    source: String,
    start: () -> Unit,
): Boolean =
    try {
        start()
        true
    } catch (cancelled: CancellationException) {
        // Callers run inside coroutines; swallowing cancellation here would break their teardown.
        throw cancelled
    } catch (error: Exception) {
        Log.w(FOREGROUND_START_TAG, "foreground service start refused (source=$source)", error)
        diagnostics.record("lifecycle", "foreground_start_refused") {
            mapOf("source" to source, "error" to error::class.simpleName)
        }
        false
    }

internal enum class StatusConnection(
    val label: Int,
    val countLabel: Int,
) {
    CONNECTED(R.string.connection_notification_connected, R.plurals.connection_notification_connected_count),
    CONNECTING(R.string.connection_notification_connecting, R.plurals.connection_notification_connecting_count),
    WAITING(R.string.connection_notification_waiting, R.plurals.connection_notification_waiting_count),
    OFFLINE(R.string.connection_notification_offline, R.plurals.connection_notification_offline_count),
    ERROR(R.string.connection_notification_error, R.plurals.connection_notification_error_count),
}

internal data class StatusNotificationRow(
    val name: String?,
    val state: StatusConnection,
)

internal data class StatusNotificationShape(
    val rows: List<StatusNotificationRow>,
    val starting: Boolean,
)

internal fun statusNotificationShape(
    activity: ConnectionActivitySnapshot,
    networkNames: Map<Long, String>,
    hasDefaultNetwork: Boolean?,
): StatusNotificationShape {
    val active = if (hasDefaultNetwork == false) StatusConnection.WAITING else StatusConnection.CONNECTING
    val ids = (networkNames.keys + activity.states.keys + activity.progressing.keys).sorted()
    val rows =
        ids.mapNotNull { id ->
            val state =
                when (activity.states[id]) {
                    is IrcClientState.Ready -> {
                        StatusConnection.CONNECTED
                    }

                    IrcClientState.Connecting, IrcClientState.Registering -> {
                        active
                    }

                    is IrcClientState.Failed -> {
                        StatusConnection.ERROR
                    }

                    IrcClientState.Disconnected, null -> {
                        when {
                            activity.progressing[id] == true -> active
                            activity.states[id] != null || activity.initializationComplete -> StatusConnection.OFFLINE
                            else -> return@mapNotNull null
                        }
                    }
                }
            val name =
                networkNames[id]
                    ?.replace('\r', ' ')
                    ?.replace('\n', ' ')
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            StatusNotificationRow(name, state)
        }
    return StatusNotificationShape(rows, starting = rows.isEmpty() && !activity.initializationComplete)
}

private fun statusContentIntent(context: Context): PendingIntent =
    // The enabled launcher alias can change with the app icon; keep this route explicit.
    PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

private fun statusDisconnectIntent(context: Context): PendingIntent =
    PendingIntent.getService(
        context,
        1,
        Intent(context, IrcForegroundService::class.java).setAction(IrcForegroundService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

internal fun buildStatusNotification(
    context: Context,
    shape: StatusNotificationShape,
    contentIntent: PendingIntent = statusContentIntent(context),
    disconnectIntent: PendingIntent = statusDisconnectIntent(context),
): Notification {
    val counts = shape.rows.groupingBy { it.state }.eachCount()
    val text =
        if (counts.isEmpty()) {
            context.getString(if (shape.starting) R.string.connection_notification_starting else R.string.connection_notification_empty)
        } else {
            StatusConnection.entries
                .mapNotNull { state -> counts[state]?.let { context.resources.getQuantityString(state.countLabel, it, it) } }
                .joinToString(context.getString(R.string.connection_notification_count_separator))
        }

    fun builder() =
        NotificationCompat
            .Builder(context, MotdNotifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification_motd)
            .setContentTitle(context.getString(R.string.app_name))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

    val publicVersion =
        builder()
            .setContentText(context.getString(R.string.connection_notification_public))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    return builder()
        .setContentText(text)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion)
        .apply {
            if (shape.rows.isNotEmpty()) {
                val details =
                    shape.rows.joinToString("\n") { row ->
                        context.getString(
                            R.string.connection_notification_network_state,
                            row.name ?: context.getString(R.string.connection_notification_network),
                            context.getString(row.state.label),
                        )
                    }
                setStyle(NotificationCompat.BigTextStyle().bigText(details))
            }
        }.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.connection_notification_disconnect),
            disconnectIntent,
        ).build()
}
