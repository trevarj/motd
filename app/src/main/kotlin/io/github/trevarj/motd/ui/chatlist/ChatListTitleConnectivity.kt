package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * The chat-list title's connectivity cue: true while at least one in-scope socket is actively
 * being established, so the reader knows the list they are looking at is about to be refreshed.
 *
 * One state on purpose. Connecting, registering, and the backoff wait between retries (the actor
 * republishes Connecting there — see ConnectionActor) all mean the same actionable thing from the
 * list: "sockets are coming up". Everything else is deliberately someone else's chrome:
 *  - a terminal [IrcClientState.Failed] (fatal or awaiting cert trust) is not progress, and
 *    painting a spinner over it would misreport it — the network-activity banner and drawer carry the
 *    reason;
 *  - a plain [IrcClientState.Disconnected] is quiescent (a manually disconnected network), not an
 *    episode;
 *  - history sync already has the unified activity banner and per-row badges; repeating it in the title
 *    would be a second spinner for the same fact.
 *
 * The predicate is the one the status notification's `statusNotificationShape` already uses for
 * its "reconnecting" wording, minus that shape's connected==0 collapse: the notification hides
 * a reconnecting bouncer child behind "Connected to N networks", which is exactly the window (roots
 * Ready, children still dialing) this cue exists to make visible.
 *
 * [scopeIds] is [scopeNetworkIds]' answer: null reports on every network, a scoped list reports
 * only on the sockets whose rows it is showing, so a cue beside a network's name can never mean
 * some other network.
 */
internal fun titleConnectingSnapshot(
    states: Map<Long, IrcClientState>,
    scopeIds: Set<Long>? = null,
): Boolean =
    states.any { (networkId, state) ->
        (scopeIds == null || networkId in scopeIds) &&
            (state is IrcClientState.Connecting || state is IrcClientState.Registering)
    }

/**
 * Anti-flash windows for the title cue, in exactly [SyncChromePresenter]'s shape and on its
 * constants, so the title and the sync header one row below share one timing vocabulary:
 *
 * - The cue may not appear until it has been wanted for [SYNC_CHROME_APPEARANCE_DELAY_MS]
 *   continuously — a reconnect that resolves inside the window (the healthy-socket common case,
 *   and every sub-500 ms flap) is never shown at all.
 * - Once shown it stays for [SYNC_CHROME_MIN_VISIBLE_MS] even if the socket comes Ready
 *   immediately after, so a flapping connection reads as one steady episode instead of a strobe.
 * - A re-connect that begins during the minimum-visible hold continues the same episode: the cue
 *   simply stays up, with no fresh appearance moment.
 *
 * Not the activity banner's 3 s grace on purpose: the banner interrupts with a full-width line and
 * earns a long fuse, while this is a 12 dp glyph after the title — cheap enough to tell the truth
 * early, which is the point during a multi-second bouncer bring-up.
 */
internal class TitleConnectingPresenter {
    private var candidate = false
    private var presented = false
    private var activeSinceMs: Long? = null
    private var shownSinceMs: Long? = null

    fun resolve(
        connecting: Boolean,
        nowMs: Long,
    ): Boolean {
        candidate = connecting
        if (!connecting) {
            activeSinceMs = null
            val shownFor = shownSinceMs?.let { nowMs - it }
            // Collapsing away from a visible cue waits out its minimum-visible window.
            if (shownFor != null && shownFor < SYNC_CHROME_MIN_VISIBLE_MS) return presented
            shownSinceMs = null
            presented = false
            return presented
        }
        if (activeSinceMs == null) activeSinceMs = nowMs
        // Already visible (possibly only because the min-visible window has not expired): the
        // resumed episode keeps the original appearance moment.
        if (presented) return presented
        if (nowMs - (activeSinceMs ?: nowMs) < SYNC_CHROME_APPEARANCE_DELAY_MS) return presented
        shownSinceMs = nowMs
        presented = true
        return presented
    }

    /**
     * Wall-clock instant at which [resolve] could answer differently for the last snapshot, or
     * null when the presented state already agrees with it and no timer is pending.
     */
    fun nextDeadlineMs(nowMs: Long): Long? =
        when {
            candidate && !presented -> activeSinceMs?.plus(SYNC_CHROME_APPEARANCE_DELAY_MS)
            !candidate && presented -> shownSinceMs?.plus(SYNC_CHROME_MIN_VISIBLE_MS)
            else -> null
        }?.takeIf { it > nowMs }
}

/**
 * Drives [TitleConnectingPresenter] off raw snapshots, in [presentSyncChrome]'s idiom: each
 * snapshot resolves immediately and then again at the presenter's pending deadline, so an
 * appearance grace or a minimum-visible hold still settles when no further connection emission
 * arrives. A fresh presenter per collection keeps the windows scoped to the subscription.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.presentTitleConnecting(nowMs: () -> Long): Flow<Boolean> =
    flow {
        val presenter = TitleConnectingPresenter()
        emitAll(
            transformLatest { snapshot ->
                while (true) {
                    emit(presenter.resolve(snapshot, nowMs()))
                    val deadline = presenter.nextDeadlineMs(nowMs()) ?: break
                    delay((deadline - nowMs()).coerceAtLeast(0L))
                }
            }.distinctUntilChanged(),
        )
    }
