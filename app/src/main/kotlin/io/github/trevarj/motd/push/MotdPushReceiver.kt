package io.github.trevarj.motd.push

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.PushProvider
import io.github.trevarj.motd.data.prefs.PushProviderPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * UnifiedPush entry point (connector 2.5.0 `MessagingReceiver`). Distributors deliver endpoint
 * lifecycle callbacks and encrypted push bodies here, one per-network `instance` string.
 *
 * Instance scheme: `instance = networkId.toString()`. Non-numeric or unknown-network instances
 * are logged, ignored, and `unregisterApp`-ed as stale hygiene.
 *
 * Callback signatures for connector 2.5.0:
 *   onNewEndpoint(context, endpoint, instance)
 *   onMessage(context, message: ByteArray, instance)
 *   onRegistrationFailed(context, instance)
 *   onUnregistered(context, instance)
 *
 * `MessagingReceiver.onReceive` holds a wakelock for the duration of these callbacks, so each
 * callback runs its suspend work to completion with `runBlocking` on a background dispatcher.
 */
class MotdPushReceiver : MessagingReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PushEntryPoint {
        fun registrar(): WebPushRegistrar
        fun eventHandler(): PushEventHandler
        fun connectionManager(): ConnectionManager
        fun settingsRepository(): SettingsRepository
        fun pushPrefs(): PushPrefs
        fun networkDao(): NetworkDao
        fun unifiedPush(): UnifiedPushApi
        fun pushProviderPrefs(): PushProviderPrefs
    }

    private fun entry(context: Context): PushEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, PushEntryPoint::class.java)

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            val networkId = resolveInstance(e, instance) ?: return@runOnWakelock
            // Mode is user-driven and precedes registration; do NOT flip it here.
            e.registrar().onNewEndpoint(networkId, endpoint)
            // Endpoints now exist for this network; let the manager re-evaluate push teardown.
            e.connectionManager().evaluatePushMode()
        }
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            val networkId = resolveInstance(e, instance) ?: return@runOnWakelock
            val keys = e.registrar().loadOrCreateKeys()
            e.eventHandler().handle(networkId, message, keys)
        }
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        // Conservative full revert: UnifiedPush delivery is unavailable, so return every network
        // to persistent-socket mode and restart the subsystem so no network is left dark.
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            e.settingsRepository().setDeliveryMode(DeliveryMode.PERSISTENT_SOCKET)
            e.connectionManager().startAll()
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            val networkId = instance.toLongOrNull()
            if (networkId != null) {
                e.registrar().onUnregisteredNetwork(networkId)
            } else {
                Log.w(TAG, "onUnregistered for non-numeric instance '$instance'; ignoring")
            }
            // Only revert delivery mode once no network holds a push endpoint anymore.
            if (e.pushPrefs().endpoints().isEmpty()) {
                e.settingsRepository().setDeliveryMode(DeliveryMode.PERSISTENT_SOCKET)
                e.connectionManager().startAll()
            }
        }
    }

    /**
     * Map a UnifiedPush `instance` string to a known network row id. Non-numeric or
     * unknown-network instances are logged, ignored, and `unregisterApp`-ed (stale hygiene).
     */
    private suspend fun resolveInstance(e: PushEntryPoint, instance: String): Long? =
        when (val decision = classifyInstance(instance) { id -> e.networkDao().byId(id) != null }) {
            is InstanceDecision.Known -> decision.networkId
            InstanceDecision.Stale -> {
                Log.w(TAG, "stale/unknown push instance '$instance'; unregistering")
                e.unifiedPush().unregisterApp(instance)
                null
            }
        }

    /**
     * Run [block] to completion inside the connector's wakelock window. The suspend work is
     * short (decrypt + a Room write, or a few labeled REGISTER round-trips) so blocking the
     * receiver thread here is acceptable and keeps the callback correct without a bound scope.
     */
    private inline fun runOnWakelock(crossinline block: suspend () -> Unit) {
        runCatching { runBlocking(Dispatchers.Default) { block() } }
            .onFailure { Log.w(TAG, "push callback failed", it) }
    }

    private companion object {
        const val TAG = "MotdPushReceiver"
    }
}

/** Outcome of classifying a UnifiedPush `instance` string. */
internal sealed interface InstanceDecision {
    data class Known(val networkId: Long) : InstanceDecision
    /** Non-numeric or refers to a network row that does not exist — should be unregistered. */
    data object Stale : InstanceDecision
}

/**
 * Pure classification of a UnifiedPush `instance` string (`instance = networkId.toString()`).
 * Non-numeric instances, or numeric instances whose row is absent, are [InstanceDecision.Stale].
 * Extracted from [MotdPushReceiver] so the stale-instance hygiene is unit-testable without an
 * Android context.
 */
internal suspend inline fun classifyInstance(
    instance: String,
    exists: (Long) -> Boolean,
): InstanceDecision {
    val id = instance.toLongOrNull() ?: return InstanceDecision.Stale
    return if (exists(id)) InstanceDecision.Known(id) else InstanceDecision.Stale
}
