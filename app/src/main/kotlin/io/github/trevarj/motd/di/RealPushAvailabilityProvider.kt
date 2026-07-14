package io.github.trevarj.motd.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.push.NetworkPushHealth
import io.github.trevarj.motd.push.PushCapability
import io.github.trevarj.motd.push.PushHealthStore
import io.github.trevarj.motd.push.PushRegistrationState
import io.github.trevarj.motd.push.UnifiedPushApi
import io.github.trevarj.motd.push.WebPushRegistrar
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.settings.PushAvailability
import io.github.trevarj.motd.ui.settings.PushAvailabilityProvider
import io.github.trevarj.motd.ui.settings.PushDistributor
import io.github.trevarj.motd.ui.settings.PushSetupStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

class RealPushAvailabilityProvider(
    private val connectionManager: ConnectionManager,
    private val hasDistributor: () -> Boolean,
    private val networks: Flow<List<NetworkEntity>> = flowOf(emptyList()),
    private val health: Flow<Map<Long, NetworkPushHealth>> = flowOf(emptyMap()),
    private val distributors: () -> List<PushDistributor> = { emptyList() },
    private val selectedDistributor: () -> String? = { null },
    private val notificationsGranted: () -> Boolean = { true },
) : PushAvailabilityProvider() {

    /** Retained for focused unit tests and callers that only need live-cap availability. */
    constructor(
        connectionManager: ConnectionManager,
        hasDistributor: () -> Boolean,
    ) : this(
        connectionManager = connectionManager,
        hasDistributor = hasDistributor,
        networks = flowOf(emptyList()),
        health = flowOf(emptyMap()),
    )

    constructor(
        context: Context,
        connectionManager: ConnectionManager,
        networkDao: NetworkDao,
        healthStore: PushHealthStore,
        unifiedPush: UnifiedPushApi,
    ) : this(
        connectionManager = connectionManager,
        hasDistributor = { unifiedPush.getDistributors().isNotEmpty() },
        networks = networkDao.observeAll(),
        health = healthStore.health,
        distributors = {
            unifiedPush.getDistributors().map { packageName ->
                PushDistributor(packageName, context.applicationLabel(packageName))
            }
        },
        selectedDistributor = unifiedPush::getAckDistributor,
        notificationsGranted = {
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
    )

    override fun availability(): Flow<PushAvailability> = combine(
        connectionManager.connectionStates,
        networks,
        health,
    ) { states, networkRows, healthByNetwork ->
        buildAvailability(
            states = states,
            networks = networkRows,
            health = healthByNetwork,
            installed = distributors(),
            selected = selectedDistributor(),
            hasDistributor = hasDistributor(),
            notificationsGranted = notificationsGranted(),
        )
    }.distinctUntilChanged()

    private fun buildAvailability(
        states: Map<Long, IrcClientState>,
        networks: List<NetworkEntity>,
        health: Map<Long, NetworkPushHealth>,
        installed: List<PushDistributor>,
        selected: String?,
        hasDistributor: Boolean,
        notificationsGranted: Boolean,
    ): PushAvailability {
        val eligible = networks.filter { it.autoConnect && it.role != NetworkRole.BOUNCER_ROOT }
        val liveWebpush = states.values.any {
            it is IrcClientState.Ready && it.caps.hasCap(WebPushRegistrar.WEBPUSH_CAP)
        }
        val durableWebpush = health.values.any { it.capability == PushCapability.SUPPORTED }
        val protected = eligible.count { health[it.id]?.registrationState == PushRegistrationState.ACTIVE }
        val latest = eligible.mapNotNull { row ->
            health[row.id]?.let { maxOf(it.lastDeliveryAt ?: 0L, it.probeAt ?: 0L, it.registeredAt ?: 0L) }
        }.maxOrNull()?.takeIf { it > 0L }
        val error = eligible.mapNotNull { health[it.id]?.errorCode }.firstOrNull()
        val status = when {
            selected == null && installed.size > 1 -> PushSetupStatus.CHOOSE_DISTRIBUTOR
            !hasDistributor -> PushSetupStatus.CHOOSE_DISTRIBUTOR
            eligible.isEmpty() -> PushSetupStatus.NEEDS_ATTENTION
            eligible.any { health[it.id]?.registrationState == PushRegistrationState.VERIFYING } ->
                PushSetupStatus.VERIFYING
            protected == eligible.size -> PushSetupStatus.ACTIVE
            protected > 0 -> PushSetupStatus.PARTIAL_FALLBACK
            error != null -> PushSetupStatus.NEEDS_ATTENTION
            else -> PushSetupStatus.REQUESTING_ENDPOINT
        }
        return PushAvailability(
            bouncerWebpush = liveWebpush || durableWebpush,
            distributorInstalled = hasDistributor,
            fcmAvailable = BuildConfig.FCM_AVAILABLE,
            distributors = installed,
            selectedDistributor = selected,
            setupStatus = status,
            protectedNetworks = protected,
            eligibleNetworks = eligible.size,
            lastSuccessAt = latest,
            errorCode = error,
            notificationsGranted = notificationsGranted,
        )
    }
}

private fun Set<String>.hasCap(cap: String): Boolean = any { it == cap || it.startsWith("$cap=") }

private fun Context.applicationLabel(packageName: String): String = runCatching {
    val info = packageManager.getApplicationInfo(packageName, 0)
    packageManager.getApplicationLabel(info).toString()
}.getOrDefault(packageName)
