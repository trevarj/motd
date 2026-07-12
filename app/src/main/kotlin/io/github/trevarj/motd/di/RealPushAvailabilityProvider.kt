package io.github.trevarj.motd.di

import android.content.Context
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.push.WebPushRegistrar
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.settings.PushAvailability
import io.github.trevarj.motd.ui.settings.PushAvailabilityProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Real [PushAvailabilityProvider]: recomputes availability reactively off the connection-state
 * stream so the Settings toggle enables the moment the soju bouncer reaches Ready advertising
 * `soju.im/webpush`.
 *
 * Cap detection reads the ACKed caps carried on each [IrcClientState.Ready] entry (the soju root
 * connection's caps) directly from the reactive state map — the map is the source of truth and only
 * a Ready connection carries caps, so this is both correct and fully reactive. The check is coarse:
 * any Ready network with the cap counts.
 *
 * The distributor list is polled on each connection-state tick; it only becomes actionable around
 * connect time, and Settings re-subscribes on resume, so no separate distributor stream is needed.
 * Distributor presence is a soft requirement surfaced as guidance, not a hard gate.
 *
 * [hasDistributor] is injectable so the reactive logic is unit-testable without the Android-static
 * `UnifiedPush.getDistributors`; the Hilt binding supplies the real static call.
 */
class RealPushAvailabilityProvider(
    private val connectionManager: ConnectionManager,
    private val hasDistributor: () -> Boolean,
) : PushAvailabilityProvider() {

    /** Production ctor: distributor presence from the UnifiedPush connector. */
    constructor(context: Context, connectionManager: ConnectionManager) : this(
        connectionManager = connectionManager,
        hasDistributor = { UnifiedPush.getDistributors(context).isNotEmpty() },
    )

    override fun availability(): Flow<PushAvailability> =
        connectionManager.connectionStates
            .map { states ->
                val bouncerWebpush = states.values.any { it is IrcClientState.Ready && it.caps.hasCap(WebPushRegistrar.WEBPUSH_CAP) }
                PushAvailability(
                    bouncerWebpush = bouncerWebpush,
                    distributorInstalled = hasDistributor(),
                    fcmAvailable = BuildConfig.FCM_AVAILABLE,
                )
            }
            .distinctUntilChanged()
}

/** Cap match mirrors IrcClient.hasCap: exact name or a `name=value` LS entry. */
private fun Set<String>.hasCap(cap: String): Boolean =
    any { it == cap || it.startsWith("$cap=") }
