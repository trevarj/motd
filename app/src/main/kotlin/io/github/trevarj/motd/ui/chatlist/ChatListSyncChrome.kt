package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.SyncPassProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Grace before any sync chrome may appear, so a pass that resolves in a few hundred milliseconds
 * (the common case on a healthy socket) shows nothing at all.
 */
internal const val SYNC_CHROME_APPEARANCE_DELAY_MS = 500L

/** Once shown, the header stays up this long even if the pass settles immediately after. */
internal const val SYNC_CHROME_MIN_VISIBLE_MS = 1_000L

/**
 * Aggregate chat-list sync chrome: one gate driving both the pinned header and the per-row queued
 * cues, so the list never shows dimmed rings without the line that explains them.
 *
 * What reaches this gate is already filtered upstream, and deliberately so. A catch-up pass
 * publishes progress only once it is both eligible (its connection actually died, or has never
 * converged) and has proven from CHATHISTORY TARGETS that at least one room moved; a re-verification
 * of a live socket, and a reconnect that finds nothing changed, publish nothing at all. So this
 * class no longer has to decide whether a pass is worth showing — by the time it sees one, it is.
 */
sealed interface ChatListSyncChrome {
    data object Hidden : ChatListSyncChrome

    /** At least one network pass is live; counts are summed across every network. */
    data class Syncing(
        val done: Int,
        val total: Int,
        /** Some live pass is a user-requested window fetch, so the header says "backfilling". */
        val backfill: Boolean = false,
    ) : ChatListSyncChrome

    /** Buffers are queued but no pass can run: nothing is connected yet. */
    data class Waiting(
        val queued: Int,
    ) : ChatListSyncChrome
}

/**
 * Raw (un-debounced) chrome for the current engine state.
 *
 * Counts come from the engine's per-network [SyncPassProgress] rather than a UI high-water mark:
 * settled buffers leave [HistorySyncStatus] entirely, so the denominator is not reconstructible
 * from the status map once passes on several networks overlap. A live pass always wins over
 * waiting entries — some of those may belong to a network still offline while another syncs.
 */
internal fun syncChromeSnapshot(
    passProgress: Map<Long, SyncPassProgress>,
    statuses: Map<Long, HistorySyncStatus>,
): ChatListSyncChrome =
    when {
        passProgress.isNotEmpty() -> {
            ChatListSyncChrome.Syncing(
                done = passProgress.values.sumOf(SyncPassProgress::settled),
                total = passProgress.values.sumOf(SyncPassProgress::total),
                backfill = passProgress.values.any(SyncPassProgress::backfill),
            )
        }

        else -> {
            statuses
                .count { it.value == HistorySyncStatus.AwaitingConnection }
                .let { queued -> if (queued > 0) ChatListSyncChrome.Waiting(queued) else ChatListSyncChrome.Hidden }
        }
    }

/**
 * Anti-flash debouncer for [ChatListSyncChrome], in the `FooterStatePresenter` idiom: pure, driven
 * by an injected clock so tests step the windows deterministically.
 *
 * - Chrome may not appear until it has been wanted for [SYNC_CHROME_APPEARANCE_DELAY_MS].
 * - Once shown it stays for [SYNC_CHROME_MIN_VISIBLE_MS] even if the pass settles immediately.
 * - Waiting <-> Syncing swaps update the visible content without resetting either window: a
 *   reconnect that turns waiting rows into a running pass is one continuous episode, not two.
 */
internal class SyncChromePresenter {
    private var presented: ChatListSyncChrome = ChatListSyncChrome.Hidden
    private var candidate: ChatListSyncChrome = ChatListSyncChrome.Hidden
    private var activeSinceMs: Long? = null
    private var shownSinceMs: Long? = null

    fun resolve(
        snapshot: ChatListSyncChrome,
        nowMs: Long,
    ): ChatListSyncChrome {
        candidate = snapshot
        if (snapshot == ChatListSyncChrome.Hidden) {
            activeSinceMs = null
            val shownFor = shownSinceMs?.let { nowMs - it }
            // Collapsing away from visible chrome waits out its minimum-visible window.
            if (shownFor != null && shownFor < SYNC_CHROME_MIN_VISIBLE_MS) return presented
            shownSinceMs = null
            presented = ChatListSyncChrome.Hidden
            return presented
        }
        if (activeSinceMs == null) activeSinceMs = nowMs
        if (presented != ChatListSyncChrome.Hidden) {
            // Already visible (possibly only because the min-visible window has not expired): adopt
            // the new content outright and keep the original appearance moment.
            presented = snapshot
            return presented
        }
        if (nowMs - (activeSinceMs ?: nowMs) < SYNC_CHROME_APPEARANCE_DELAY_MS) return presented
        shownSinceMs = nowMs
        presented = snapshot
        return presented
    }

    /**
     * Wall-clock instant at which [resolve] could answer differently for the last snapshot, or null
     * when the presented state already agrees with it and no timer is pending.
     */
    fun nextDeadlineMs(nowMs: Long): Long? =
        when {
            candidate != ChatListSyncChrome.Hidden && presented == ChatListSyncChrome.Hidden -> {
                activeSinceMs?.plus(SYNC_CHROME_APPEARANCE_DELAY_MS)
            }

            candidate == ChatListSyncChrome.Hidden && presented != ChatListSyncChrome.Hidden -> {
                shownSinceMs?.plus(SYNC_CHROME_MIN_VISIBLE_MS)
            }

            else -> {
                null
            }
        }?.takeIf { it > nowMs }
}

/**
 * Drives [SyncChromePresenter] off raw snapshots: each snapshot resolves immediately and then again
 * at the presenter's pending deadline, so an appearance grace or a minimum-visible hold still
 * settles when no further engine emission arrives. A fresh presenter per collection keeps the
 * windows scoped to the subscription rather than to the ViewModel's lifetime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<ChatListSyncChrome>.presentSyncChrome(nowMs: () -> Long): Flow<ChatListSyncChrome> =
    flow {
        val presenter = SyncChromePresenter()
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
