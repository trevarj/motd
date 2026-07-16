package io.github.trevarj.motd.push

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.prefs.PushProvider
import io.github.trevarj.motd.data.prefs.PushProviderPrefs
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush entry point (connector 3.3.3 `MessagingReceiver`). Distributors deliver endpoint
 * lifecycle callbacks and encrypted push bodies here, one per-network `instance` string.
 *
 * Instance scheme: `instance = networkId.toString()`. Non-numeric or unknown-network instances
 * are logged, ignored, and `unregisterApp`-ed as stale hygiene.
 *
 * Callback signatures for connector 3.3.3:
 *   onNewEndpoint(context, endpoint: PushEndpoint, instance)
 *   onMessage(context, message: PushMessage, instance)
 *   onRegistrationFailed(context, reason: FailedReason, instance)
 *   onUnregistered(context, instance)
 *
 * `MessagingReceiver.onReceive` holds a wakelock for the duration of these callbacks, so each
 * callback runs its suspend work to completion with `runBlocking` on a background dispatcher.
 */
class MotdPushReceiver : MessagingReceiver() {

    /**
     * Connector callbacks include a server acknowledgement round trip. Move the connector's
     * synchronous dispatch off the main thread so a slow relay cannot freeze the application or
     * trigger an input ANR while registration is in progress.
     */
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val pending = goAsync()
        receiverScope.launch {
            try {
                super.onReceive(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PushEntryPoint {
        fun registrar(): WebPushRegistrar
        fun eventHandler(): PushEventHandler
        fun connectionManager(): ConnectionManager
        fun networkDao(): NetworkDao
        fun unifiedPush(): UnifiedPushApi
        fun pushProviderPrefs(): PushProviderPrefs
        fun pushHealthStore(): PushHealthStore
        fun pushDistributorController(): PushDistributorController
    }

    private fun entry(context: Context): PushEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, PushEntryPoint::class.java)

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            val networkId = resolveInstance(e, instance) ?: return@runOnWakelock
            // Mode is user-driven and precedes registration; do NOT flip it here.
            e.registrar().onNewEndpoint(networkId, endpoint.url)
            // Endpoints now exist for this network; let the manager re-evaluate push teardown.
            e.connectionManager().evaluatePushMode()
        }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            val networkId = resolveInstance(e, instance) ?: return@runOnWakelock
            val keys = e.registrar().loadOrCreateKeys()
            e.eventHandler().handle(networkId, message.content, keys, message.decrypted)
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            val networkId = resolveInstance(e, instance) ?: return@runOnWakelock
            e.pushHealthStore().failed(networkId, "DISTRIBUTOR_REGISTRATION_${reason.name}")
            // Preserve the user's UnifiedPush choice, but immediately restore this network's
            // socket-backed fallback so a distributor problem never leaves it dark.
            e.connectionManager().startAll()
            e.connectionManager().evaluatePushMode()
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        val e = entry(context)
        runOnWakelock {
            if (e.pushProviderPrefs().provider.first() != PushProvider.UNIFIED_PUSH) return@runOnWakelock
            val networkId = instance.toLongOrNull()
            if (networkId != null) {
                e.registrar().onUnregisteredNetwork(networkId)
                e.pushDistributorController().onUnregistered(networkId)
            } else {
                Log.w(TAG, "onUnregistered for non-numeric instance '$instance'; ignoring")
            }
            e.connectionManager().startAll()
            e.connectionManager().evaluatePushMode()
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
     * short (decrypt + event dispatch, or a few labeled REGISTER round-trips) so blocking the
     * receiver thread here is acceptable and keeps the callback correct without a bound scope.
     */
    private inline fun runOnWakelock(crossinline block: suspend () -> Unit) {
        runCatching { runBlocking(Dispatchers.Default) { block() } }
            .onFailure { Log.w(TAG, "push callback failed", it) }
    }

    private companion object {
        const val TAG = "MotdPushReceiver"
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
