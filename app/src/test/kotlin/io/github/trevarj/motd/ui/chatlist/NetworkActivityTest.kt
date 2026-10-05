package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.HistorySyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkActivityTest {
    private val ledger = NetworkActivityLedger()
    private val networks = listOf(network(1), network(2))
    private val buffers = mapOf(10L to room(10, 1), 20L to room(20, 2))
    private var now = 1_000L

    private fun observe(
        connections: Map<Long, IrcClientState> = emptyMap(),
        history: Map<Long, HistorySyncStatus> = emptyMap(),
        saved: List<NetworkEntity> = networks,
        rooms: Map<Long, BufferEntity?> = buffers,
    ) = ledger.observe(saved, connections, history, rooms, emptySet(), now++)

    private fun failure(
        reason: String = "timeout",
        fatal: Boolean = false,
    ) = IrcClientState.Failed(reason, fatal)

    private fun ready() = IrcClientState.Ready("me", emptySet(), emptyMap())

    @Test fun bannerHideIsOneWayAndFreshProcessOwnerStartsUnhidden() {
        val session = NetworkActivityBannerSession()
        assertFalse(session.hidden.value)
        session.hide()
        assertTrue(session.hidden.value)
        session.hide()
        assertTrue(session.hidden.value)
        assertFalse(NetworkActivityBannerSession().hidden.value)
    }

    @Test fun attentionSequenceTracksOnlyNewCausesEpisodesAndSeverityRises() {
        assertEquals(0L, observe().latestAttentionSequence)

        fun history(status: HistorySyncStatus) = observe(history = mapOf(10L to status))
        val partial = HistorySyncStatus.Partial("same cause")
        assertEquals(1L, history(partial).latestAttentionSequence)
        assertEquals(1L, history(partial).latestAttentionSequence)
        assertEquals(1L, history(HistorySyncStatus.Queued).latestAttentionSequence)
        assertEquals(1L, history(HistorySyncStatus.AwaitingConnection).latestAttentionSequence)
        assertEquals(1L, history(HistorySyncStatus.Syncing).latestAttentionSequence)
        val retried = history(partial)
        assertEquals(1L, retried.latestAttentionSequence)
        assertEquals(2, retried.active.single().occurrences)
        val failed = HistorySyncStatus.Failed("same cause")
        val upgraded = history(failed)
        assertEquals(2L, upgraded.latestAttentionSequence)
        assertEquals(retried.active.single().episodeId, upgraded.active.single().episodeId)
        assertEquals(2L, history(failed).latestAttentionSequence)
        assertEquals(
            2L,
            observe(
                history = mapOf(10L to failed),
                saved = networks.map { it.copy(name = "renamed") },
                rooms = buffers + (10L to room(99, 1).copy(displayName = "#redirected")),
            ).latestAttentionSequence,
        )
        assertEquals(2L, ledger.acknowledge(upgraded.active.single()).latestAttentionSequence)
        assertEquals(2L, history(partial).latestAttentionSequence)
        assertEquals(3L, history(failed).latestAttentionSequence)
        assertEquals(3L, history(HistorySyncStatus.Idle).latestAttentionSequence)
        assertEquals(4L, history(partial).latestAttentionSequence)
        assertEquals(5L, history(HistorySyncStatus.Partial("different cause")).latestAttentionSequence)
        assertEquals(5L, history(HistorySyncStatus.Unavailable).latestAttentionSequence)
        assertEquals(6L, history(failed).latestAttentionSequence)
        assertEquals(6L, observe(history = mapOf(10L to failed), rooms = buffers + (10L to null)).latestAttentionSequence)
        assertEquals(7L, observe(mapOf(1L to failure())).latestAttentionSequence)
        assertEquals(8L, observe(mapOf(1L to failure(fatal = true))).latestAttentionSequence)
        assertEquals(8L, observe(mapOf(1L to IrcClientState.Connecting)).latestAttentionSequence)
        assertEquals(8L, observe(mapOf(1L to IrcClientState.Registering)).latestAttentionSequence)
        val fatalRetry = observe(mapOf(1L to failure(fatal = true)))
        assertEquals(8L, fatalRetry.latestAttentionSequence)
        assertEquals(8L, ledger.acknowledge(fatalRetry.active.single()).latestAttentionSequence)
        assertEquals(8L, ledger.stop(1).latestAttentionSequence)
        assertEquals(8L, observe(mapOf(1L to failure(fatal = true))).latestAttentionSequence)
        assertEquals(8L, observe(mapOf(1L to ready())).latestAttentionSequence)
        assertEquals(9L, observe(mapOf(1L to failure(fatal = true))).latestAttentionSequence)
    }

    @Test fun attentionSequenceSurvivesAllClearReconstructionAndRecentEviction() {
        repeat(25) { index ->
            val failed = observe(mapOf(1L to failure("cause $index")))
            assertEquals(index + 1L, failed.latestAttentionSequence)
            val cleared = observe(mapOf(1L to ready()))
            assertTrue(cleared.active.isEmpty())
            assertEquals(index + 1L, cleared.latestAttentionSequence)
        }
        val capped = observe()
        assertEquals(20, capped.recent.size)
        assertEquals(25L, capped.latestAttentionSequence)
        assertEquals(25L, capped.copy(recent = emptyList()).latestAttentionSequence)
        assertEquals(25L, ledger.stop(1).latestAttentionSequence)
        assertEquals(26L, observe(mapOf(1L to failure("cause 0"))).latestAttentionSequence)
    }

    @Test fun retriesAndUnknownAbsenceRetainUntilReady() {
        val first = observe(mapOf(1L to failure())).active.single()
        val absent = observe().active.single()
        assertEquals(first.episodeId, absent.episodeId)
        assertEquals("timeout", absent.reason)
        assertFalse(absent.settled)
        for (state in listOf(IrcClientState.Connecting, IrcClientState.Registering)) {
            val retry = observe(mapOf(1L to state))
            assertTrue(retry.active.single().retrying)
            assertTrue(retry.recent.isEmpty())
        }
        val recovered = observe(mapOf(1L to ready()))
        assertTrue(recovered.active.isEmpty())
        assertEquals(NetworkActivityDisposition.CONNECTED, recovered.recent.single().disposition)
        assertEquals(recovered.recent, observe(mapOf(1L to ready())).recent)
    }

    @Test fun distinctCauseSupersedesPreviousSourceIssueAndSameCauseReentryCounts() {
        val first = observe(mapOf(1L to failure("first"))).active.single()
        assertEquals(first, observe(mapOf(1L to failure("first"))).active.single())
        val distinct = observe(mapOf(1L to failure("second")))
        assertEquals("second", distinct.active.single().reason)
        assertEquals("first", distinct.recent.single().reason)
        assertEquals(NetworkActivityDisposition.SUPERSEDED, distinct.recent.single().disposition)
        assertNull(ledger.current(first))
        assertEquals(1, ledger.acknowledge(first).unacknowledgedCount)
        val second = distinct.active.single()
        observe(mapOf(1L to IrcClientState.Connecting))
        val repeated = observe(mapOf(1L to failure("second"))).active.single()
        assertEquals(second.episodeId, repeated.episodeId)
        assertEquals(second.firstSeen, repeated.firstSeen)
        assertEquals(2, repeated.occurrences)
        assertTrue(repeated.lastSeen > second.lastSeen)
        val replacedAgain = observe(mapOf(1L to failure("first"))).active.single()
        assertNotEquals(first.episodeId, replacedAgain.episodeId)
        assertEquals(1, replacedAgain.occurrences)
    }

    @Test fun acknowledgementHidesPromotionNotRecordAndStaleActionsCannotAcknowledge() {
        val original = observe(mapOf(1L to failure())).active.single()
        val acknowledged = ledger.acknowledge(original)
        assertEquals(0, acknowledged.unacknowledgedCount)
        assertTrue(acknowledged.active.single().acknowledged)
        assertEquals(0, observe(mapOf(1L to failure())).unacknowledgedCount)
        observe(mapOf(1L to IrcClientState.Connecting))
        assertNull(ledger.current(original))
        val retried = observe(mapOf(1L to failure()))
        assertTrue(retried.active.single().acknowledged)
        assertEquals(0, ledger.acknowledge(original).unacknowledgedCount)
        observe(mapOf(1L to ready()))
        val later = observe(mapOf(1L to failure()))
        assertNotEquals(original.episodeId, later.active.single().episodeId)
        assertEquals(1, ledger.acknowledge(original).unacknowledgedCount)
    }

    @Test fun intentionalStopAndDeletionAreNotRecovery() {
        observe(mapOf(1L to failure()), mapOf(10L to HistorySyncStatus.Failed("history")))
        val stopped = ledger.stop(1)
        assertTrue(stopped.active.isEmpty())
        assertEquals(setOf(NetworkActivityDisposition.STOPPED), stopped.recent.map { it.disposition }.toSet())
        assertTrue(observe(mapOf(1L to failure()), mapOf(10L to HistorySyncStatus.Failed("history"))).active.isEmpty())
        observe(mapOf(2L to failure()))
        val removed = observe(saved = networks.take(1))
        assertEquals(NetworkActivityDisposition.REMOVED, removed.recent.first().disposition)
    }

    @Test fun historyReasonsSurviveRetryAndUnavailableAndDisappearanceStayHonest() {
        val full = "CHATHISTORY failed: server explanation\nwith all details"
        val first = observe(history = mapOf(10L to HistorySyncStatus.Failed(full))).active.single()
        for (status in listOf(HistorySyncStatus.Queued, HistorySyncStatus.AwaitingConnection, HistorySyncStatus.Syncing)) {
            val retry = observe(history = mapOf(10L to status))
            assertEquals(full, retry.active.single().reason)
            assertEquals(first.episodeId, retry.active.single().episodeId)
            assertTrue(retry.active.single().retrying)
            assertFalse(
                ledger
                    .acknowledge(first)
                    .active
                    .single()
                    .acknowledged,
            )
        }
        assertEquals(2, observe(history = mapOf(10L to HistorySyncStatus.Failed(full))).active.single().occurrences)
        val partial = observe(history = mapOf(10L to HistorySyncStatus.Partial("partial reason")))
        assertEquals(1, partial.active.size)
        assertEquals(NetworkActivityKind.HISTORY_PARTIAL, partial.active.single().kind)
        assertEquals(NetworkActivityDisposition.SUPERSEDED, partial.recent.single().disposition)
        val unavailable = observe(history = mapOf(10L to HistorySyncStatus.Unavailable))
        assertTrue(unavailable.active.isEmpty())
        assertEquals(NetworkActivityDisposition.UNAVAILABLE, unavailable.recent.first().disposition)
        observe(history = mapOf(20L to HistorySyncStatus.Partial("missing")))
        assertEquals(NetworkActivityDisposition.NO_LONGER_REPORTED, observe().recent.first().disposition)
    }

    @Test fun historySeverityChangesKeepOneCauseEpisodeAndInvalidateOldActions() {
        val first = observe(history = mapOf(10L to HistorySyncStatus.Partial("same cause"))).active.single()
        assertEquals(NetworkActivityKind.HISTORY_PARTIAL, first.kind)
        assertEquals(1, first.severity)
        val failed = observe(history = mapOf(10L to HistorySyncStatus.Failed("same cause"))).active.single()
        assertEquals(first.episodeId, failed.episodeId)
        assertEquals(first.firstSeen, failed.firstSeen)
        assertEquals(NetworkActivityKind.HISTORY_FAILED, failed.kind)
        assertEquals(2, failed.severity)
        assertTrue(failed.revision > first.revision)
        assertNull(ledger.current(first))
        val partialAgain = observe(history = mapOf(10L to HistorySyncStatus.Partial("same cause"))).active.single()
        assertEquals(first.episodeId, partialAgain.episodeId)
        assertEquals(first.firstSeen, partialAgain.firstSeen)
        assertEquals(NetworkActivityKind.HISTORY_PARTIAL, partialAgain.kind)
        assertEquals(1, partialAgain.severity)
        assertTrue(partialAgain.revision > failed.revision)
        assertNull(ledger.current(failed))
        assertEquals(partialAgain, observe(history = mapOf(10L to HistorySyncStatus.Partial("same cause"))).active.single())
        val distinct = observe(history = mapOf(10L to HistorySyncStatus.Failed("different cause")))
        assertEquals(1, distinct.active.size)
        assertEquals(NetworkActivityDisposition.SUPERSEDED, distinct.recent.single().disposition)
        assertNull(ledger.current(partialAgain))
    }

    @Test fun redirectMetadataKeepsStatusKeyIdentityAndDoesNotManufactureSettlement() {
        val status = mapOf(10L to HistorySyncStatus.Failed("same source failure"))
        val beforeMerge = observe(history = status).active.single()
        val redirected = buffers + (10L to room(11, 1))
        val afterMerge = observe(history = status, rooms = redirected)
        assertEquals(beforeMerge.episodeId, afterMerge.active.single().episodeId)
        assertEquals(10L, afterMerge.active.single().bufferId)
        assertEquals("#chat11", afterMerge.active.single().chatName)
        assertEquals(1, afterMerge.active.single().occurrences)
        assertTrue(afterMerge.recent.isEmpty())
        val unchanged = observe(mapOf(1L to ready()), status, rooms = redirected)
        assertEquals(afterMerge.active, unchanged.active)
        assertTrue(unchanged.recent.isEmpty())
        assertTrue(
            ledger
                .acknowledge(unchanged.active.single())
                .active
                .single()
                .acknowledged,
        )
        val fresh = NetworkActivityLedger()
        val first = fresh.observe(networks, emptyMap(), status, redirected, emptySet(), now)
        assertEquals(10L, first.active.single().bufferId)
        assertEquals(first.active, fresh.observe(networks, emptyMap(), status, redirected, emptySet(), now + 1).active)
    }

    @Test fun removedChatRetiresButArchivedChatDoesNot() {
        observe(history = mapOf(10L to HistorySyncStatus.Failed("failure")))
        assertEquals(1, observe(history = mapOf(10L to HistorySyncStatus.Failed("failure")), rooms = buffers + (10L to room(10, 1).copy(archived = true))).active.size)
        assertEquals(NetworkActivityDisposition.REMOVED, observe(history = mapOf(10L to HistorySyncStatus.Failed("failure")), rooms = buffers + (10L to null)).recent.single().disposition)
    }

    @Test fun recentNavigationKeepsDispositionButDisablesDeletedTargets() {
        observe(history = mapOf(10L to HistorySyncStatus.Partial("partial")))
        val ended = observe().recent.single()
        assertEquals(NetworkActivityDisposition.NO_LONGER_REPORTED, ended.disposition)
        assertEquals(ended, ledger.current(ended, includeRecent = true))
        assertNull(ledger.current(ended))
        val removed = observe(rooms = buffers + (10L to null)).recent.single()
        assertEquals(NetworkActivityDisposition.NO_LONGER_REPORTED, removed.disposition)
        assertFalse(removed.targetAvailable)
        assertNull(ledger.current(ended, includeRecent = true))
    }

    @Test fun recentIsNewestFirstCappedTwentyAndActiveIsBoundedPerSource() {
        observe(mapOf(2L to failure("keep")))
        repeat(25) { index ->
            observe(mapOf(1L to failure("failure $index"), 2L to failure("keep")))
            observe(mapOf(1L to ready(), 2L to failure("keep")))
        }
        val state = observe(mapOf(1L to ready(), 2L to failure("keep")))
        assertEquals(20, state.recent.size)
        assertEquals("failure 24", state.recent.first().reason)
        assertEquals("failure 5", state.recent.last().reason)
        assertEquals("keep", state.active.single().reason)
        assertEquals(1, state.active.single().occurrences)
        assertTrue(NetworkActivityLedger().observe(networks, emptyMap(), emptyMap(), buffers, emptySet(), now).recent.isEmpty())
        repeat(25) { index -> observe(mapOf(1L to ready(), 2L to failure("active cause $index"))) }
        val manyActive = observe(mapOf(1L to ready(), 2L to failure("active cause 24")))
        assertEquals(1, manyActive.active.size)
        assertEquals("active cause 24", manyActive.active.single().reason)
        assertEquals(20, manyActive.recent.size)
        assertEquals("active cause 23", manyActive.recent.first().reason)
    }

    @Test fun acknowledgementDuringRetryAndUnknownAbsenceStaysQuietUntilEscalationOrNewEpisode() {
        observe(mapOf(1L to failure()), mapOf(10L to HistorySyncStatus.Partial("partial")))
        val retrying = observe(mapOf(1L to IrcClientState.Connecting), mapOf(10L to HistorySyncStatus.Syncing))
        retrying.active.filter { it.bufferId != null }.forEach { ledger.acknowledge(it) }
        val unknown = observe(history = mapOf(10L to HistorySyncStatus.Syncing))
        val connection = unknown.active.single { it.bufferId == null }
        assertFalse(connection.settled)
        val acknowledgedUnknown = ledger.acknowledge(connection)
        assertEquals(0, acknowledgedUnknown.unacknowledgedCount)
        assertEquals(unknown.networks, acknowledgedUnknown.networks)
        assertTrue(acknowledgedUnknown.recent.isEmpty())
        val retried = observe(mapOf(1L to failure()), mapOf(10L to HistorySyncStatus.Partial("partial")))
        assertEquals(0, retried.unacknowledgedCount)
        assertTrue(retried.active.all { it.occurrences == 2 && it.acknowledged })
        assertEquals(retrying.latestAttentionSequence, retried.latestAttentionSequence)
        val escalated = observe(mapOf(1L to failure(fatal = true)), mapOf(10L to HistorySyncStatus.Failed("partial")))
        assertEquals(2, escalated.unacknowledgedCount)
        assertEquals(retried.latestAttentionSequence + 2, escalated.latestAttentionSequence)
        escalated.active.forEach { ledger.acknowledge(it) }
        val replaced = observe(mapOf(1L to failure("new cause")), mapOf(10L to HistorySyncStatus.Failed("new history cause")))
        assertEquals(2, replaced.unacknowledgedCount)
        assertTrue(replaced.recent.isEmpty())
        replaced.active.forEach { ledger.acknowledge(it) }
        assertTrue(observe(mapOf(1L to ready())).recent.isEmpty())
        assertEquals(1, observe(mapOf(1L to failure("new cause"))).unacknowledgedCount)
    }

    @Test fun acknowledgedFinishStopAndRemovalPruneInsteadOfEnteringRecent() {
        for (terminal in listOf("ready", "stop", "removed", "unavailable", "absent")) {
            val fresh = NetworkActivityLedger()
            val connection = terminal == "ready" || terminal == "stop" || terminal == "removed"
            val state = fresh.observe(networks, if (connection) mapOf(1L to failure()) else emptyMap(), if (connection) emptyMap() else mapOf(10L to HistorySyncStatus.Failed("history")), buffers, emptySet(), now++)
            fresh.acknowledge(state.active.single())
            val finished =
                if (terminal == "stop") {
                    fresh.stop(1)
                } else {
                    fresh.observe(if (terminal == "removed") emptyList() else networks, if (terminal == "ready") mapOf(1L to ready()) else emptyMap(), if (terminal == "unavailable") mapOf(10L to HistorySyncStatus.Unavailable) else emptyMap(), buffers, emptySet(), now++)
                }
            assertTrue(terminal, finished.active.isEmpty())
            assertTrue(terminal, finished.recent.isEmpty())
            assertEquals(state.latestAttentionSequence, finished.latestAttentionSequence)
        }
    }

    @Test fun clearRecentPreservesActiveSuppressionAttentionAndStoppedSources() {
        val first = observe(mapOf(1L to failure("old"), 2L to failure("stopped")), mapOf(10L to HistorySyncStatus.Partial("history")))
        ledger.stop(2)
        val next = observe(mapOf(1L to failure("new"), 2L to failure("stopped")), mapOf(10L to HistorySyncStatus.Syncing))
        val acknowledged = ledger.acknowledge(next.active.single { it.bufferId == 10L })
        val recent = acknowledged.recent.first()
        val cleared = ledger.clearRecent()
        assertEquals(acknowledged.copy(recent = emptyList()), cleared)
        assertEquals(first.latestAttentionSequence + 1, cleared.latestAttentionSequence)
        assertNull(ledger.current(recent, includeRecent = true))
        val observed = observe(mapOf(1L to failure("new"), 2L to failure("stopped")), mapOf(10L to HistorySyncStatus.Partial("history")))
        assertTrue(observed.active.none { it.networkId == 2L })
        assertTrue(observed.active.single { it.bufferId == 10L }.acknowledged)
        assertEquals(2, observed.active.single { it.bufferId == 10L }.occurrences)
        assertEquals(cleared.latestAttentionSequence, observed.latestAttentionSequence)
        assertTrue(observed.recent.isEmpty())
    }

    @Test fun connectionGraceSurvivesRepresentationsAndWaitingOpensCombinedEpisode() {
        val presenter = NetworkActivityPresenter()
        val connecting = NetworkActivityState(networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Connecting)))
        assertFalse(presenter.resolve(connecting, ChatListSyncChrome.Hidden, 0))
        assertEquals(3_000L, presenter.nextDeadline(0))
        val registering = connecting.copy(networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Registering)))
        assertFalse(presenter.resolve(registering, ChatListSyncChrome.Hidden, 2_999))
        assertTrue(presenter.resolve(registering, ChatListSyncChrome.Hidden, 3_000))
        assertTrue(presenter.resolve(registering, ChatListSyncChrome.Waiting(2), 3_100))
        assertTrue(presenter.resolve(connecting, ChatListSyncChrome.Hidden, 3_200))
        assertFalse(presenter.resolve(NetworkActivityState(), ChatListSyncChrome.Hidden, 3_300))
        assertFalse(presenter.resolve(connecting, ChatListSyncChrome.Hidden, 3_400))
    }

    private fun network(id: Long) = NetworkEntity(id = id, name = "Network $id", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")

    private fun room(
        id: Long,
        network: Long,
    ) = BufferEntity(id = id, networkId = network, name = "#chat$id", displayName = "#chat$id", type = BufferType.CHANNEL)
}
