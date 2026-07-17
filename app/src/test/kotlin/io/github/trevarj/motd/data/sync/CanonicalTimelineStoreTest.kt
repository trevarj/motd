package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.TimeProvenance
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CanonicalTimelineStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseNames = mutableListOf<String>()

    @After
    fun tearDown() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun livePushAndHistoryPermutations_convergeAcrossDatabaseReopen() = runTest {
        val permutations = listOf(
            ObservationSpec(ObservationOrigin.LIVE, null, 10_000, TimeProvenance.LOCAL_CLOCK) to
                ObservationSpec(ObservationOrigin.HISTORY, "m-1", 11_000, TimeProvenance.SERVER_TAG),
            ObservationSpec(ObservationOrigin.HISTORY, "m-1", 11_000, TimeProvenance.SERVER_TAG) to
                ObservationSpec(ObservationOrigin.LIVE, null, 10_000, TimeProvenance.LOCAL_CLOCK),
            ObservationSpec(ObservationOrigin.PUSH, "m-1", 11_000, TimeProvenance.SERVER_TAG) to
                ObservationSpec(ObservationOrigin.HISTORY, "m-1", 11_000, TimeProvenance.SERVER_TAG),
            ObservationSpec(ObservationOrigin.HISTORY, "m-1", 11_000, TimeProvenance.SERVER_TAG) to
                ObservationSpec(ObservationOrigin.PUSH, null, 11_000, TimeProvenance.SERVER_TAG),
        )

        permutations.forEachIndexed { index, (first, second) ->
            val name = "canonical-permutation-$index.db"
            val setup = openSetup(name)
            val firstResult = setup.store.ingest(first.observation(setup.networkId, setup.roomId))
            setup.db.close()

            val reopened = open(name)
            val secondResult = CanonicalTimelineStore(reopened).ingest(
                second.observation(setup.networkId, setup.roomId),
            )
            val rows = rows(reopened, setup.roomId)
            assertEquals("permutation $index", 1, rows.size)
            assertEquals(firstResult.event.id, secondResult.event.id)
            assertEquals("m-1", rows.single().msgid)
            assertEquals(11_000, rows.single().serverTime)
            assertEquals(2, scalar(reopened, "SELECT COUNT(*) FROM event_observations"))
            reopened.close()
        }
    }

    @Test
    fun livePushEchoAndHistoryEveryOrder_haveOneCanonicalEvent() = runTest {
        val observations = listOf(
            ObservationSpec(ObservationOrigin.LIVE, null, 40_000, TimeProvenance.LOCAL_CLOCK),
            ObservationSpec(ObservationOrigin.PUSH, "all-orders", 41_000, TimeProvenance.SERVER_TAG),
            ObservationSpec(ObservationOrigin.LIVE, "all-orders", 41_000, TimeProvenance.SERVER_TAG),
            ObservationSpec(ObservationOrigin.HISTORY, "all-orders", 41_000, TimeProvenance.SERVER_TAG),
        )

        observations.permutations().forEachIndexed { index, order ->
            val setup = openSetup("canonical-all-orders-$index.db")
            val ids = order.map { spec ->
                setup.store.ingest(spec.observation(setup.networkId, setup.roomId)).event.id
            }

            assertEquals("permutation $index", 1, ids.distinct().size)
            assertEquals("permutation $index", 1, rows(setup.db, setup.roomId).size)
            assertEquals(
                "permutation $index",
                4,
                scalar(setup.db, "SELECT COUNT(*) FROM event_observations"),
            )
            setup.db.close()
        }
    }

    @Test
    fun everyTimelineKindReplaysIdempotentlyThroughTypedIdentity() = runTest {
        val setup = openSetup("canonical-system-kinds.db")

        MessageKind.entries.forEachIndexed { index, kind ->
            val observation = TimelineObservation(
                networkId = setup.networkId,
                event = event(setup.roomId, null, 50_000L + index, kind.name).copy(
                    kind = kind,
                    eventKey = "typed:${kind.name}:$index",
                    eventPayload = "v1:${kind.name}",
                ),
                origin = ObservationOrigin.HISTORY,
                connectionGeneration = 1,
                batchId = "typed-replay",
                timeProvenance = TimeProvenance.SERVER_TAG,
            )
            val first = setup.store.ingest(observation)
            val replay = setup.store.ingest(observation)
            assertEquals(kind.name, first.event.id, replay.event.id)
        }

        assertEquals(MessageKind.entries.size, rows(setup.db, setup.roomId).size)
        setup.db.close()
    }

    @Test
    fun msgidsAreNetworkScopedCaseSensitiveAndNeverMergeConflicts() = runTest {
        val setup = openSetup("canonical-msgids.db")
        val secondNetwork = setup.db.networkDao().insert(network("other"))
        val secondRoom = setup.db.bufferDao().insert(room(secondNetwork, "#room"))

        val first = setup.store.ingest(
            tagged(setup.networkId, setup.roomId, "Case-ID", 20_000, "same"),
        )
        val sameNetworkOtherRoom = setup.db.bufferDao().insert(room(setup.networkId, "#rewrite"))
        val rewritten = setup.store.ingest(
            tagged(setup.networkId, sameNetworkOtherRoom, "Case-ID", 20_000, "same"),
        )
        val caseDistinct = setup.store.ingest(
            tagged(setup.networkId, setup.roomId, "case-id", 20_000, "same"),
        )
        val conflicting = setup.store.ingest(
            tagged(setup.networkId, setup.roomId, "different", 20_000, "same"),
        )
        val otherNetwork = setup.store.ingest(
            tagged(secondNetwork, secondRoom, "Case-ID", 20_000, "same"),
        )

        assertEquals(first.event.id, rewritten.event.id)
        assertNotEquals(first.event.id, caseDistinct.event.id)
        assertNotEquals(first.event.id, conflicting.event.id)
        assertNotEquals(first.event.id, otherNetwork.event.id)
        assertEquals(3, rows(setup.db, setup.roomId).size)
        assertEquals(1, rows(setup.db, secondRoom).size)
        assertEquals(0, rows(setup.db, sameNetworkOtherRoom).size)
        setup.db.close()
    }

    @Test
    fun labeledModifiedEchoEnrichesPendingRowWithoutChangingCanonicalId() = runTest {
        val setup = openSetup("canonical-label.db")
        val pending = setup.store.ingest(
            TimelineObservation(
                networkId = setup.networkId,
                event = event(setup.roomId, null, 30_000, "draft").copy(
                    isSelf = true,
                    pendingLabel = "label-1",
                    serverTimeAuthoritative = false,
                ),
                origin = ObservationOrigin.LOCAL_SEND,
                connectionGeneration = 7,
                label = "label-1",
                batchId = null,
                timeProvenance = TimeProvenance.LOCAL_CLOCK,
            ),
        )
        val echo = setup.store.ingest(
            TimelineObservation(
                networkId = setup.networkId,
                event = event(setup.roomId, "echo-1", 31_000, "server-final").copy(isSelf = true),
                origin = ObservationOrigin.LIVE,
                connectionGeneration = 7,
                label = "label-1",
                batchId = null,
                timeProvenance = TimeProvenance.SERVER_TAG,
            ),
        )

        assertEquals(pending.event.id, echo.event.id)
        val row = rows(setup.db, setup.roomId).single()
        assertEquals("server-final", row.text)
        assertEquals("echo-1", row.msgid)
        assertNull(row.pendingLabel)
        setup.db.close()
    }

    @Test
    fun delayedHistoryReconcilesUniqueProvisionalEventAcrossReopen() = runTest {
        val name = "canonical-delayed-reopen.db"
        val setup = openSetup(name)
        val live = setup.store.ingest(
            TimelineObservation(
                networkId = setup.networkId,
                event = event(setup.roomId, null, 1_000, "same delayed event"),
                origin = ObservationOrigin.LIVE,
                connectionGeneration = 1,
                batchId = null,
                timeProvenance = TimeProvenance.LOCAL_CLOCK,
            ),
        )
        setup.db.openHelper.writableDatabase.execSQL(
            "UPDATE event_observations SET observedAt = 0 WHERE timelineEventId = ?",
            arrayOf<Any?>(live.event.id),
        )
        setup.db.close()

        val reopened = open(name)
        val history = CanonicalTimelineStore(reopened).ingest(
            tagged(
                setup.networkId,
                setup.roomId,
                "delayed-msgid",
                31L * 24 * 60 * 60 * 1_000,
                "same delayed event",
            ),
        )

        assertEquals(live.event.id, history.event.id)
        assertEquals(1, rows(reopened, setup.roomId).size)
        assertEquals("delayed-msgid", reopened.messageDao().byId(live.event.id)?.msgid)
        reopened.close()
    }

    @Test
    fun repeatedTextWithMultipleProvisionalCandidatesRemainsDistinct() = runTest {
        val setup = openSetup("canonical-ambiguous-repeat.db")
        repeat(2) { index ->
            setup.store.ingest(
                TimelineObservation(
                    networkId = setup.networkId,
                    event = event(setup.roomId, null, 1_000L + index, "same"),
                    origin = ObservationOrigin.LIVE,
                    connectionGeneration = 1,
                    batchId = null,
                    timeProvenance = TimeProvenance.LOCAL_CLOCK,
                ),
            )
        }

        val history = setup.store.ingest(
            tagged(setup.networkId, setup.roomId, "ambiguous-msgid", 5_000, "same"),
        )

        assertEquals(3, rows(setup.db, setup.roomId).size)
        assertEquals("ambiguous-msgid", history.event.msgid)
        setup.db.close()
    }

    @Test
    fun deviceClockSkewDoesNotPreventFreshLiveToHistoryReconciliation() = runTest {
        val setup = openSetup("canonical-clock-skew.db")
        val serverTime = 50_000L
        val live = setup.store.ingest(
            TimelineObservation(
                networkId = setup.networkId,
                event = event(setup.roomId, null, serverTime + 5 * 60_000, "!tell trev skew"),
                origin = ObservationOrigin.LIVE,
                connectionGeneration = 1,
                batchId = null,
                timeProvenance = TimeProvenance.LOCAL_CLOCK,
            ),
        )
        val history = setup.store.ingest(
            tagged(setup.networkId, setup.roomId, "skew-msgid", serverTime, "!tell trev skew"),
        )

        assertEquals(live.event.id, history.event.id)
        assertEquals(1, rows(setup.db, setup.roomId).size)
        assertEquals("skew-msgid", rows(setup.db, setup.roomId).single().msgid)
        setup.db.close()
    }

    @Test
    fun identicalMsgidlessEventsAtSameTaggedTimeRemainDistinctWithinBatch() = runTest {
        val setup = openSetup("canonical-same-time-repeats.db")
        val repeated = TimelineObservation(
            networkId = setup.networkId,
            event = event(setup.roomId, null, 70_000, "genuine repeat"),
            origin = ObservationOrigin.HISTORY,
            connectionGeneration = 1,
            batchId = "repeat-batch",
            timeProvenance = TimeProvenance.SERVER_TAG,
        )

        val results = setup.store.ingestBatch(listOf(repeated, repeated))

        assertEquals(2, results.map { it.event.id }.distinct().size)
        assertEquals(2, rows(setup.db, setup.roomId).size)
        setup.db.close()
    }

    @Test
    fun repeatedExactHistoryOverlapsConvergeForSubsetAndSupersetAcrossReopen() = runTest {
        suspend fun runCase(name: String, firstCount: Int, secondCount: Int) {
            val setup = openSetup(name)
            val repeated = TimelineObservation(
                networkId = setup.networkId,
                event = event(setup.roomId, null, 71_000, "overlapping repeat"),
                origin = ObservationOrigin.HISTORY,
                connectionGeneration = 1,
                batchId = "first-page",
                timeProvenance = TimeProvenance.SERVER_TAG,
            )
            setup.store.ingestBatch(List(firstCount) { repeated })
            setup.db.close()

            val reopened = open(name)
            CanonicalTimelineStore(reopened).ingestBatch(
                List(secondCount) { repeated.copy(batchId = "overlapping-page") },
            )

            assertEquals(maxOf(firstCount, secondCount), rows(reopened, setup.roomId).size)
            reopened.close()
        }

        runCase("canonical-overlap-subset.db", firstCount = 2, secondCount = 1)
        runCase("canonical-overlap-superset.db", firstCount = 1, secondCount = 2)
    }

    @Test
    fun notificationClaimReleaseFollowsEventCoalescenceRedirect() = runTest {
        val setup = openSetup("canonical-notification-claim-redirect.db")
        val older = setup.store.ingest(
            tagged(setup.networkId, setup.roomId, "claim-msgid", 80_000, "server variant")
                .let { it.copy(origin = ObservationOrigin.PUSH, event = it.event.copy(hasMention = true)) },
        )
        val claimed = setup.store.ingest(
            TimelineObservation(
                networkId = setup.networkId,
                event = event(setup.roomId, null, 79_000, "final presentation"),
                origin = ObservationOrigin.LIVE,
                connectionGeneration = 1,
                batchId = null,
                timeProvenance = TimeProvenance.LOCAL_CLOCK,
            ),
        )
        assertNotEquals(older.event.id, claimed.event.id)
        assertEquals(true, setup.store.claimNotification(claimed.event.id))

        val merged = setup.store.ingest(
            tagged(
                setup.networkId,
                setup.roomId,
                "claim-msgid",
                80_000,
                "final presentation",
            ),
        )
        setup.store.releaseNotification(claimed.event.id)

        assertEquals(older.event.id, merged.event.id)
        assertEquals(
            older.event.id,
            setup.db.canonicalTimelineDao().canonicalEventId(claimed.event.id),
        )
        assertEquals(
            listOf(older.event.id),
            setup.db.canonicalTimelineDao().pendingNotifications(10).map { it.id },
        )
        setup.db.close()
    }

    @Test
    fun startupRecoveryDoesNotReleaseClaimOwnedByCurrentProcess() = runTest {
        val setup = openSetup("canonical-current-notification-claim.db")
        val event = setup.store.ingest(
            tagged(setup.networkId, setup.roomId, "active-claim", 81_000, "active")
                .let { it.copy(origin = ObservationOrigin.PUSH, event = it.event.copy(hasMention = true)) },
        )
        assertEquals(true, setup.store.claimNotification(event.event.id))

        setup.db.canonicalTimelineDao().releaseInterruptedNotificationClaims(
            NotificationClaimSession.owner,
        )
        assertEquals(false, setup.store.claimNotification(event.event.id))

        setup.db.canonicalTimelineDao().releaseInterruptedNotificationClaims("next-process")
        assertEquals(true, setup.store.claimNotification(event.event.id))
        setup.db.close()
    }

    @Test
    fun connectionGenerationSurvivesReopenAndScopesReusedLabels() = runTest {
        val name = "canonical-generation-reopen.db"
        val setup = openSetup(name)
        val firstProcessor = EventProcessor(setup.db, TypingTrackerImpl(), MessageNotifier.Noop)
        firstProcessor.onRegistered(setup.networkId, "me", emptyMap())
        val firstId = firstProcessor.insertPending(
            setup.roomId, "motd-1", "me", "first attempt", null, MessageKind.PRIVMSG,
        )
        setup.db.close()

        val reopened = open(name)
        val secondProcessor = EventProcessor(reopened, TypingTrackerImpl(), MessageNotifier.Noop)
        secondProcessor.onRegistered(setup.networkId, "me", emptyMap())
        val secondId = secondProcessor.insertPending(
            setup.roomId, "motd-1", "me", "second attempt", null, MessageKind.PRIVMSG,
        )

        assertNotEquals(firstId, secondId)
        assertEquals(2, rows(reopened, setup.roomId).size)
        assertEquals(2, scalar(reopened, "SELECT generation FROM connection_generations"))
        reopened.close()
    }

    private data class ObservationSpec(
        val origin: ObservationOrigin,
        val msgid: String?,
        val time: Long,
        val provenance: TimeProvenance,
    ) {
        fun observation(networkId: Long, roomId: Long) = TimelineObservation(
            networkId = networkId,
            event = event(roomId, msgid, time, "!tell trev canonical"),
            origin = origin,
            connectionGeneration = 1,
            batchId = if (origin == ObservationOrigin.HISTORY) "history" else null,
            timeProvenance = provenance,
        )
    }

    private data class Setup(
        val db: MotdDatabase,
        val store: CanonicalTimelineStore,
        val networkId: Long,
        val roomId: Long,
    )

    private suspend fun openSetup(name: String): Setup {
        val db = open(name)
        val networkId = db.networkDao().insert(network("network"))
        val roomId = db.bufferDao().insert(room(networkId, "#room"))
        return Setup(db, CanonicalTimelineStore(db), networkId, roomId)
    }

    private fun open(name: String): MotdDatabase {
        databaseNames += name
        return Room.databaseBuilder(context, MotdDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
    }

    private fun tagged(
        networkId: Long,
        roomId: Long,
        msgid: String,
        time: Long,
        text: String,
    ) = TimelineObservation(
        networkId = networkId,
        event = event(roomId, msgid, time, text),
        origin = ObservationOrigin.HISTORY,
        connectionGeneration = 1,
        batchId = "history",
        timeProvenance = TimeProvenance.SERVER_TAG,
    )

    private suspend fun rows(db: MotdDatabase, roomId: Long): List<MessageEntity> =
        db.messageDao().pagingSource(roomId).load(
            PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as PagingSource.LoadResult.Page).data }

    private fun scalar(db: MotdDatabase, query: String): Int =
        db.openHelper.readableDatabase.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun <T> List<T>.permutations(): List<List<T>> = when (size) {
        0, 1 -> listOf(this)
        else -> flatMapIndexed { index, value ->
            (take(index) + drop(index + 1)).permutations().map { listOf(value) + it }
        }
    }

    private companion object {
        fun network(name: String) = NetworkEntity(
            name = name,
            role = NetworkRole.DIRECT,
            host = "irc.example",
            port = 6697,
            nick = "me",
            username = "me",
            realname = "Me",
        )

        fun room(networkId: Long, name: String) = BufferEntity(
            networkId = networkId,
            name = name,
            displayName = name,
            type = BufferType.CHANNEL,
        )

        fun event(roomId: Long, msgid: String?, time: Long, text: String) = MessageEntity(
            bufferId = roomId,
            msgid = msgid,
            serverTime = time,
            sender = "alice",
            normalizedActor = "alice",
            kind = MessageKind.PRIVMSG,
            text = text,
            dedupKey = "diagnostic-only",
        )
    }
}
