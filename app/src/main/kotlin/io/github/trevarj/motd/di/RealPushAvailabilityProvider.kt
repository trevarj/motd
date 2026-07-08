package io.github.trevarj.motd.di

import android.content.Context
import io.github.trevarj.motd.push.WebPushRegistrar
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.settings.PushAvailabilityProvider
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Real [PushAvailabilityProvider]: UNIFIED_PUSH delivery is selectable only when an installed
 * UnifiedPush distributor exists AND at least one connected client advertises `soju.im/webpush`.
 * The cap check is coarse — any connected network with the cap counts.
 */
class RealPushAvailabilityProvider(
    private val context: Context,
    private val connectionManager: ConnectionManager,
) : PushAvailabilityProvider() {
    override fun isUnifiedPushAvailable(): Boolean {
        val hasDistributor = UnifiedPush.getDistributors(context).isNotEmpty()
        if (!hasDistributor) return false
        return connectionManager.connectionStates.value.keys.any { networkId ->
            connectionManager.clientFor(networkId)?.hasCap(WebPushRegistrar.WEBPUSH_CAP) == true
        }
    }
}
