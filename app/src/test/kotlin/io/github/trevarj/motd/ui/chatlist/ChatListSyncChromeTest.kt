package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.SyncPassProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListSyncChromeTest {
    // --- snapshot derivation -------------------------------------------------------------------

    @Test
    fun a_live_pass_sums_progress_across_every_network() {
        val snapshot =
            syncChromeSnapshot(
                passProgress = mapOf(1L to SyncPassProgress(total = 12, settled = 5), 2L to SyncPassProgress(30, 7)),
                statuses = mapOf(9L to HistorySyncStatus.Syncing),
            )

        assertEquals(ChatListSyncChrome.Syncing(done = 12, total = 42), snapshot)
    }

    @Test
    fun a_backfill_pass_marks_the_whole_snapshot_as_backfilling() {
        val snapshot =
            syncChromeSnapshot(
                passProgress = mapOf(1L to SyncPassProgress(total = 3, settled = 1), 2L to SyncPassProgress(70, 9, backfill = true)),
                statuses = emptyMap(),
            )

        assertEquals(ChatListSyncChrome.Syncing(done = 10, total = 73, backfill = true), snapshot)
    }

    @Test
    fun a_live_pass_wins_over_buffers_still_waiting_on_another_network() {
        val snapshot =
            syncChromeSnapshot(
                passProgress = mapOf(1L to SyncPassProgress(total = 4, settled = 1)),
                statuses =
                    mapOf(
                        7L to HistorySyncStatus.AwaitingConnection,
                        8L to HistorySyncStatus.AwaitingConnection,
                    ),
            )

        assertEquals(ChatListSyncChrome.Syncing(done = 1, total = 4), snapshot)
    }

    @Test
    fun waiting_counts_only_awaiting_connection_entries_and_settles_to_hidden() {
        val statuses =
            mapOf(
                1L to HistorySyncStatus.AwaitingConnection,
                2L to HistorySyncStatus.AwaitingConnection,
                3L to HistorySyncStatus.Failed("timed out"),
            )

        assertEquals(ChatListSyncChrome.Waiting(queued = 2), syncChromeSnapshot(emptyMap(), statuses))
        assertEquals(
            ChatListSyncChrome.Hidden,
            syncChromeSnapshot(emptyMap(), mapOf(3L to HistorySyncStatus.Failed("timed out"))),
        )
        assertEquals(ChatListSyncChrome.Hidden, syncChromeSnapshot(emptyMap(), emptyMap()))
    }

    // --- presenter windows ---------------------------------------------------------------------

    @Test
    fun chrome_stays_hidden_until_the_appearance_grace_elapses() {
        val presenter = SyncChromePresenter()
        val candidate = ChatListSyncChrome.Syncing(done = 0, total = 4)

        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(candidate, nowMs = 0))
        assertEquals(500L, presenter.nextDeadlineMs(nowMs = 0))
        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(candidate, nowMs = 499))
        assertEquals(candidate, presenter.resolve(candidate, nowMs = 500))
        assertNull(presenter.nextDeadlineMs(nowMs = 500))
    }

    @Test
    fun a_pass_that_settles_inside_the_grace_never_shows_and_the_next_one_earns_a_fresh_grace() {
        val presenter = SyncChromePresenter()
        val first = ChatListSyncChrome.Syncing(done = 0, total = 2)

        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(first, nowMs = 0))
        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(ChatListSyncChrome.Hidden, nowMs = 300))
        assertNull(presenter.nextDeadlineMs(nowMs = 300))

        val second = ChatListSyncChrome.Syncing(done = 0, total = 9)
        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(second, nowMs = 400))
        assertEquals(900L, presenter.nextDeadlineMs(nowMs = 400))
        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(second, nowMs = 899))
        assertEquals(second, presenter.resolve(second, nowMs = 900))
    }

    @Test
    fun shown_chrome_survives_its_minimum_visible_window() {
        val presenter = SyncChromePresenter()
        val candidate = ChatListSyncChrome.Syncing(done = 3, total = 3)
        presenter.resolve(candidate, nowMs = 0)
        assertEquals(candidate, presenter.resolve(candidate, nowMs = 500))

        assertEquals(candidate, presenter.resolve(ChatListSyncChrome.Hidden, nowMs = 520))
        assertEquals(1_500L, presenter.nextDeadlineMs(nowMs = 520))
        assertEquals(candidate, presenter.resolve(ChatListSyncChrome.Hidden, nowMs = 1_499))
        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(ChatListSyncChrome.Hidden, nowMs = 1_500))
        assertNull(presenter.nextDeadlineMs(nowMs = 1_500))
    }

    @Test
    fun swapping_between_waiting_and_syncing_neither_resets_visibility_nor_the_windows() {
        val presenter = SyncChromePresenter()
        val waiting = ChatListSyncChrome.Waiting(queued = 6)
        presenter.resolve(waiting, nowMs = 0)
        assertEquals(waiting, presenter.resolve(waiting, nowMs = 500))

        // The connection came up: the same episode continues as a running pass, visible at once.
        val syncing = ChatListSyncChrome.Syncing(done = 0, total = 6)
        assertEquals(syncing, presenter.resolve(syncing, nowMs = 600))
        assertNull(presenter.nextDeadlineMs(nowMs = 600))
        // ...and the minimum-visible window still counts from the original appearance.
        assertEquals(syncing, presenter.resolve(ChatListSyncChrome.Hidden, nowMs = 1_499))
        assertEquals(ChatListSyncChrome.Hidden, presenter.resolve(ChatListSyncChrome.Hidden, nowMs = 1_500))
    }

    @Test
    fun chrome_hidden_during_the_minimum_visible_hold_reappears_instantly() {
        val presenter = SyncChromePresenter()
        val first = ChatListSyncChrome.Syncing(done = 1, total = 1)
        presenter.resolve(first, nowMs = 0)
        presenter.resolve(first, nowMs = 500)
        assertEquals(first, presenter.resolve(ChatListSyncChrome.Hidden, nowMs = 600))

        val second = ChatListSyncChrome.Syncing(done = 0, total = 5)
        assertEquals(second, presenter.resolve(second, nowMs = 700))
    }

    // --- driver --------------------------------------------------------------------------------

    @Test
    fun the_driver_resolves_its_own_deadlines_without_further_engine_emissions() =
        runTest {
            val snapshots = MutableStateFlow<ChatListSyncChrome>(ChatListSyncChrome.Hidden)
            val seen = mutableListOf<ChatListSyncChrome>()
            backgroundScope.launch {
                snapshots.presentSyncChrome { testScheduler.currentTime }.toList(seen)
            }
            runCurrent()
            assertEquals(listOf(ChatListSyncChrome.Hidden), seen)

            val syncing = ChatListSyncChrome.Syncing(done = 0, total = 4)
            snapshots.value = syncing
            runCurrent()
            assertEquals(listOf(ChatListSyncChrome.Hidden), seen)

            // No new engine emission here: the appearance timer is the driver's own.
            advanceTimeBy(SYNC_CHROME_APPEARANCE_DELAY_MS + 1)
            assertEquals(listOf(ChatListSyncChrome.Hidden, syncing), seen)

            snapshots.value = ChatListSyncChrome.Hidden
            runCurrent()
            assertEquals(listOf(ChatListSyncChrome.Hidden, syncing), seen)

            advanceTimeBy(SYNC_CHROME_MIN_VISIBLE_MS + 1)
            assertEquals(listOf(ChatListSyncChrome.Hidden, syncing, ChatListSyncChrome.Hidden), seen)
        }

    @Test
    fun the_driver_keeps_publishing_progress_while_the_chrome_is_up() =
        runTest {
            val snapshots = MutableStateFlow<ChatListSyncChrome>(ChatListSyncChrome.Waiting(queued = 3))
            val seen = mutableListOf<ChatListSyncChrome>()
            backgroundScope.launch {
                snapshots.presentSyncChrome { testScheduler.currentTime }.toList(seen)
            }
            advanceTimeBy(SYNC_CHROME_APPEARANCE_DELAY_MS + 1)
            assertEquals(listOf(ChatListSyncChrome.Hidden, ChatListSyncChrome.Waiting(queued = 3)), seen)

            snapshots.value = ChatListSyncChrome.Syncing(done = 1, total = 3)
            runCurrent()
            snapshots.value = ChatListSyncChrome.Syncing(done = 2, total = 3)
            runCurrent()

            assertEquals(
                listOf(
                    ChatListSyncChrome.Hidden,
                    ChatListSyncChrome.Waiting(queued = 3),
                    ChatListSyncChrome.Syncing(done = 1, total = 3),
                    ChatListSyncChrome.Syncing(done = 2, total = 3),
                ),
                seen,
            )
        }
}
