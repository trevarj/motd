package io.github.trevarj.motd.sidecar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.launchAsync
import io.github.trevarj.motd.sidecar.SidecarJson
import io.github.trevarj.motd.sidecar.SidecarProviderFeature
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private data class WakeRegistration(
    val component: ComponentName,
    val accountId: String,
    val pendingIntent: PendingIntent,
)

@Singleton
class SidecarWakeRegistrar
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val binder: SidecarBinder,
    ) {
        private val registered = ConcurrentHashMap<Long, WakeRegistration>()

        suspend fun reconcile(
            networks: List<NetworkEntity>,
            enabled: Boolean,
        ) {
            val desired =
                if (enabled) {
                    networks.filter {
                        it.connectionTransport == ConnectionTransport.SIDECAR &&
                            !it.sidecarPackage.isNullOrBlank() &&
                            !it.sidecarService.isNullOrBlank() &&
                            !it.sidecarAccountId.isNullOrBlank()
                    }
                } else {
                    emptyList()
                }.associateBy(NetworkEntity::id)

            (registered.keys - desired.keys).forEach { networkId -> clear(networkId) }
            desired.forEach { (networkId, network) ->
                val component = ComponentName(network.sidecarPackage!!, network.sidecarService!!)
                val accountId = network.sidecarAccountId!!
                val current = registered[networkId]
                if (current?.component == component && current.accountId == accountId) return@forEach
                if (current != null) clear(networkId)
                runCatching {
                    binder.bindTrusted(component).use { binding ->
                        val info = SidecarJson.decodeProvider(binding.provider.providerInfoJson)
                        if (SidecarProviderFeature.WAKE !in info.features) return@use
                        val pending = wakeIntent(networkId)
                        binding.provider.setWakeIntent(accountId, pending)
                        registered[networkId] = WakeRegistration(component, accountId, pending)
                    }
                }
            }
        }

        suspend fun clearProvider(component: ComponentName) {
            registered
                .filterValues { it.component == component }
                .keys
                .toList()
                .forEach { clear(it) }
        }

        private suspend fun clear(networkId: Long) {
            val prior = registered.remove(networkId) ?: return
            runCatching {
                binder.bindTrusted(prior.component).use { it.provider.clearWakeIntent(prior.accountId) }
            }
            prior.pendingIntent.cancel()
        }

        private fun wakeIntent(networkId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                networkId.hashCode(),
                Intent(context, SidecarWakeReceiver::class.java).putExtra(SidecarWakeReceiver.EXTRA_NETWORK_ID, networkId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

@AndroidEntryPoint
class SidecarWakeReceiver : BroadcastReceiver() {
    @Inject lateinit var connections: ConnectionManager

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val networkId = intent.getLongExtra(EXTRA_NETWORK_ID, 0L).takeIf { it > 0 } ?: return
        launchAsync(scope, TAG) { connections.checkpointNetwork(networkId) }
    }

    companion object {
        const val EXTRA_NETWORK_ID = "network_id"
        private const val TAG = "MotdSidecarWake"
    }
}
