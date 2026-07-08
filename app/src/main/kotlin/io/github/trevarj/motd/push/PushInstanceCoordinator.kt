package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.DeliveryMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var started = false

    /** Idempotent: begins collecting the mode/connectable stream and reconciling registrations. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(settingsRepository.settings, networkDao.observeAll()) { s, nets ->
                s.deliveryMode to nets.filter { it.autoConnect }.map { it.id }.toSet()
            }.distinctUntilChanged().collect { (mode, ids) -> reconcile(mode, ids) }
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
        val desired = if (mode == DeliveryMode.UNIFIED_PUSH) connectable else emptySet()
        if (desired.isNotEmpty() && up.getAckDistributor() == null) {
            // No installed distributor: nothing to register against — silent no-op until one exists.
            val distributor = up.getDistributors().firstOrNull() ?: return
            up.saveDistributor(distributor)
        }
        for (id in desired) up.registerApp(id.toString())
        for (id in (pushPrefs.endpoints().keys + connectable) - desired) {
            up.unregisterApp(id.toString())
        }
    }
}
