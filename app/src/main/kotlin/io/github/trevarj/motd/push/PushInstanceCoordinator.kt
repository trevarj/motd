package io.github.trevarj.motd.push

import android.util.Log
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.PushProvider
import io.github.trevarj.motd.data.prefs.PushProviderPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.DeliveryMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * THE UnifiedPush registration trigger (plans/11 §B.2). v1 never called `UnifiedPush.registerApp`;
 * this @Singleton watches the delivery mode and the connectable-network set and reconciles the
 * set of registered UnifiedPush instances (one per connectable network, `instance = networkId`).
 *
 * `start()` is idempotent and invoked from [io.github.trevarj.motd.MotdApplication.onCreate].
 */
@Singleton
class PushInstanceCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val networkDao: NetworkDao,
    private val pushPrefs: PushPrefs,
    private val up: UnifiedPushApi,
    private val providerPrefs: PushProviderPrefs = DefaultPushProviderPrefs,
    private val fcm: FcmPushApi = NoopFcmPushApi,
    private val healthStore: PushHealthStore = NoopPushHealthStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val endpointTimeouts = ConcurrentHashMap<Long, Job>()

    @Volatile private var started = false

    /** Idempotent: begins collecting the mode/connectable stream and reconciling registrations. */
    fun start() {
        if (started) return
        started = true
        fcm.start()
        scope.launch {
            combine(settingsRepository.settings, providerPrefs.provider, networkDao.observeAll()) { s, provider, nets ->
                Triple(s.deliveryMode, provider, nets)
            }.distinctUntilChanged().collect { (mode, provider, networks) ->
                val ids = pushEligibleNetworkIds(networks)
                runCatching {
                    healthStore.retain(networks.map { it.id }.toSet())
                    reconcile(mode, provider, ids)
                }
                    .onFailure { Log.w(TAG, "push provider reconciliation failed", it) }
            }
        }
    }

    /**
     * Reconcile registered instances against the desired set:
     *  - desired = connectable networks under UNIFIED_PUSH, else empty.
     *  - auto-select the first distributor when one is desired and none is acked (no-op if none).
     *  - registerApp every desired instance.
     *  - unregisterApp everything we hold an endpoint for, or that is connectable, minus desired
     *    (covers mode-off and network-removed deltas, plus stale endpoint hygiene).
     *
     * Public for direct unit tests with a FakeUnifiedPushApi.
     */
    suspend fun reconcile(mode: DeliveryMode, connectable: Set<Long>) {
        reconcile(mode, PushProvider.UNIFIED_PUSH, connectable)
    }

    suspend fun reconcile(mode: DeliveryMode, provider: PushProvider, connectable: Set<Long>) {
        if (mode != DeliveryMode.UNIFIED_PUSH) {
            cancelEndpointTimeoutsExcept(emptySet())
            fcm.reconcile(emptySet())
            reconcileUnifiedPush(emptySet(), connectable)
            return
        }
        if (provider == PushProvider.FCM) {
            if (!fcm.available) {
                settingsRepository.setDeliveryMode(DeliveryMode.PERSISTENT_SOCKET)
                return
            }
            // Stop connector registrations before replacing their endpoints with relay endpoints.
            for (id in connectable) up.unregisterApp(id.toString())
            fcm.reconcile(connectable)
        } else {
            fcm.reconcile(emptySet())
            reconcileUnifiedPush(connectable, connectable)
        }
    }

    private suspend fun reconcileUnifiedPush(desired: Set<Long>, connectable: Set<Long>) {
        if (desired.isNotEmpty() && up.getAckDistributor() == null) {
            // Auto-select only an unambiguous single distributor. Settings presents a chooser when
            // several are installed instead of silently picking an arbitrary package.
            val installed = up.getDistributors()
            if (installed.size != 1) {
                cancelEndpointTimeoutsExcept(emptySet())
                return
            }
            up.saveDistributor(installed.single())
        }
        cancelEndpointTimeoutsExcept(desired)
        for (id in desired) {
            if (pushPrefs.endpointFor(id) == null) {
                armEndpointTimeout(id)
            } else {
                endpointTimeouts.remove(id)?.cancel()
            }
            up.registerApp(id.toString())
        }
        for (id in (pushPrefs.endpoints().keys + connectable) - desired) {
            up.unregisterApp(id.toString())
        }
    }

    /** Surface a distributor callback that never arrives instead of remaining "requesting" forever. */
    private suspend fun armEndpointTimeout(networkId: Long) {
        if (endpointTimeouts[networkId]?.isActive == true) return
        healthStore.requestingEndpoint(networkId)
        lateinit var timeout: Job
        timeout = scope.launch {
            try {
                delay(ENDPOINT_TIMEOUT_MS)
                if (pushPrefs.endpointFor(networkId) == null && networkDao.byId(networkId) != null) {
                    healthStore.failed(networkId, "ENDPOINT_TIMEOUT")
                }
            } finally {
                endpointTimeouts.remove(networkId, timeout)
            }
        }
        endpointTimeouts.put(networkId, timeout)?.cancel()
    }

    private fun cancelEndpointTimeoutsExcept(keep: Set<Long>) {
        endpointTimeouts.entries.forEach { (networkId, job) ->
            if (networkId !in keep && endpointTimeouts.remove(networkId, job)) job.cancel()
        }
    }

    private companion object {
        const val TAG = "PushCoordinator"
        const val ENDPOINT_TIMEOUT_MS = 45_000L
    }
}

internal fun pushEligibleNetworkIds(networks: List<io.github.trevarj.motd.data.db.NetworkEntity>): Set<Long> =
    networks.asSequence()
        .filter { it.autoConnect && it.role != NetworkRole.BOUNCER_ROOT }
        .map { it.id }
        .toSet()

private object DefaultPushProviderPrefs : PushProviderPrefs {
    override val provider = kotlinx.coroutines.flow.flowOf(PushProvider.UNIFIED_PUSH)
    override suspend fun setProvider(provider: PushProvider) = Unit
    override suspend fun fcmSubscriptions() = emptyMap<Long, io.github.trevarj.motd.data.prefs.FcmSubscription>()
    override suspend fun setFcmSubscription(networkId: Long, subscription: io.github.trevarj.motd.data.prefs.FcmSubscription?) = Unit
}

private object NoopFcmPushApi : FcmPushApi {
    override val available = false
    override fun start() = Unit
    override suspend fun reconcile(connectable: Set<Long>) = Unit
    override suspend fun onTokenChanged(token: String) = Unit
}
