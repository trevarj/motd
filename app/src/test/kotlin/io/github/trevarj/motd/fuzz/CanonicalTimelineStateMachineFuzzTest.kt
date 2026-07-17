package io.github.trevarj.motd.fuzz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.TimelineObservation
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CanonicalTimelineStateMachineFuzzTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun generatedDeliveryAndHistorySequencesPreserveCanonicalInvariants() = runTest {
        SeededFuzz.runSuspending(
            target = "canonical-timeline",
            version = 1,
            prCases = 24,
            nightlyCases = 1_500,
            replayTest = javaClass.name,
        ) { fuzz ->
            val databaseName = "canonical-fuzz-${fuzz.seed.hashCode()}-${fuzz.index}.db"
            context.deleteDatabase(databaseName)
            var db: MotdDatabase? = null
            try {
                val initialDb = open(databaseName)
                db = initialDb
                val networkId = initialDb.networkDao().insert(network())
                val roomId = initialDb.bufferDao().insert(room(networkId, "#room"))
                val logical = List(4) { index ->
                    LogicalEvent(
                        index = index,
                        msgid = if (index % 2 == 0) "Case-${fuzz.index}-$index" else "case-${fuzz.index}-$index",
                        text = "fuzztoken${fuzz.index}event$index",
                        serverTime = 20_000L + fuzz.index * 1_000L + index * 100L,
                    )
                }
                val returnedIds = logical.associateWith { mutableSetOf<Long>() }
                val mandatory = buildList {
                    logical.forEach { event ->
                        add(Operation.Single(event, Delivery.LIVE_LOCAL))
                        add(Operation.Single(event, Delivery.PUSH_TAGGED))
                        add(Operation.Single(event, Delivery.ECHO_TAGGED))
                    }
                    add(Operation.History(logical.take(2)))
                    add(Operation.History(logical.drop(1)))
                    add(Operation.History(logical))
                }.shuffled(fuzz.random)
                val replayable = buildList {
                    logical.forEach { event ->
                        add(Operation.Single(event, Delivery.PUSH_TAGGED))
                        add(Operation.Single(event, Delivery.ECHO_TAGGED))
                    }
                    add(Operation.History(logical))
                    add(Operation.History(logical.takeLast(3)))
                }
                val operationCount = fuzzSteps(pr = 32, nightly = 128)
                val reopenAt = setOf(
                    fuzz.random.nextInt(1, operationCount),
                    fuzz.random.nextInt(1, operationCount),
                )

                repeat(operationCount) { step ->
                    if (step in reopenAt) {
                        fuzz.record("reopen step=$step")
                        db?.close()
                        db = open(databaseName)
                    }
                    val operation = mandatory.getOrNull(step) ?: replayable.random(fuzz.random)
                    val store = CanonicalTimelineStore(checkNotNull(db))
                    when (operation) {
                        is Operation.Single -> {
                            fuzz.record("single step=$step delivery=${operation.delivery} logical=${operation.event.index}")
                            val result = store.ingest(
                                observation(networkId, roomId, operation.event, operation.delivery),
                            )
                            returnedIds.getValue(operation.event) += result.event.id
                        }
                        is Operation.History -> {
                            fuzz.record("history step=$step logical=${operation.events.map { it.index }}")
                            val results = store.ingestBatch(
                                operation.events.map { event ->
                                    observation(networkId, roomId, event, Delivery.HISTORY_TAGGED)
                                },
                            )
                            operation.events.zip(results).forEach { (event, result) ->
                                returnedIds.getValue(event) += result.event.id
                            }
                        }
                    }
                    assertCanonicalIntegrity(checkNotNull(db))
                }

                val activeDb = checkNotNull(db)
                val store = CanonicalTimelineStore(activeDb)
                logical.forEach { event ->
                    val canonical = checkNotNull(
                        activeDb.canonicalTimelineDao().eventByAlias(
                            networkId,
                            EventAliasNamespace.MSGID,
                            event.msgid.toByteArray(StandardCharsets.UTF_8),
                        ),
                    )
                    assertEquals(event.text, canonical.text)
                    assertEquals(event.serverTime, canonical.serverTime)
                    assertTrue(canonical.serverTimeAuthoritative)
                    returnedIds.getValue(event).forEach { observedId ->
                        assertEquals(canonical.id, activeDb.canonicalTimelineDao().canonicalEventId(observedId))
                    }
                    assertEquals(1, scalar(activeDb, "SELECT COUNT(*) FROM messages WHERE text = ?", event.text))
                    assertEquals(
                        1,
                        scalar(
                            activeDb,
                            "SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?",
                            event.text,
                        ),
                    )
                    assertTrue(store.claimSound(canonical.id))
                    assertFalse(store.claimSound(canonical.id))
                    assertTrue(store.claimNotification(canonical.id))
                    store.completeNotification(canonical.id)
                    assertFalse(store.claimNotification(canonical.id))
                }

                exerciseCaseSensitiveMsgids(fuzz, activeDb, store, networkId, roomId)
                exerciseGenerationScopedLabels(fuzz, activeDb, store, networkId, roomId)
                exerciseIdentityFreeOverlaps(fuzz, activeDb, store, networkId, roomId)
                exercisePresentationRollback(fuzz, activeDb, store, networkId, roomId)
                assertUnreadProjection(activeDb, roomId)
                exerciseRollback(fuzz, activeDb, networkId)
                assertCanonicalIntegrity(activeDb)
            } finally {
                db?.close()
                context.deleteDatabase(databaseName)
            }
        }
    }

    private suspend fun exerciseCaseSensitiveMsgids(
        fuzz: FuzzCase,
        db: MotdDatabase,
        store: CanonicalTimelineStore,
        networkId: Long,
        roomId: Long,
    ) {
        val text = "caseconflict${fuzz.index}"
        val time = 100_000L + fuzz.index
        fun tagged(msgid: String) = TimelineObservation(
            networkId,
            message(roomId, msgid, time, text),
            ObservationOrigin.HISTORY,
            1,
            batchId = "case-conflict",
            timeProvenance = TimeProvenance.SERVER_TAG,
        )
        val upper = store.ingest(tagged("Conflict-${fuzz.index}"))
        val lower = store.ingest(tagged("conflict-${fuzz.index}"))
        val different = store.ingest(tagged("different-${fuzz.index}"))
        fuzz.record("case-sensitive-msgids ids=${listOf(upper.event.id, lower.event.id, different.event.id)}")
        assertEquals(3, setOf(upper.event.id, lower.event.id, different.event.id).size)
        assertEquals(3, scalar(db, "SELECT COUNT(*) FROM messages WHERE text = ?", text))
    }

    private suspend fun exerciseGenerationScopedLabels(
        fuzz: FuzzCase,
        db: MotdDatabase,
        store: CanonicalTimelineStore,
        networkId: Long,
        roomId: Long,
    ) {
        suspend fun attempt(generation: Long, msgid: String, text: String): Pair<Long, Long> {
            val label = "same-label-${fuzz.index}"
            val pending = store.ingest(
                TimelineObservation(
                    networkId,
                    message(roomId, null, 110_000 + generation, text).copy(
                        isSelf = true,
                        pendingLabel = label,
                        serverTimeAuthoritative = false,
                    ),
                    ObservationOrigin.LOCAL_SEND,
                    generation,
                    label = label,
                    batchId = null,
                    timeProvenance = TimeProvenance.LOCAL_CLOCK,
                ),
            )
            val echo = store.ingest(
                TimelineObservation(
                    networkId,
                    message(roomId, msgid, 111_000 + generation, text).copy(isSelf = true),
                    ObservationOrigin.LIVE,
                    generation,
                    label = label,
                    batchId = null,
                    timeProvenance = TimeProvenance.SERVER_TAG,
                ),
            )
            return pending.event.id to echo.event.id
        }
        val first = attempt(7, "label-msgid-${fuzz.index}-a", "labelattempt${fuzz.index}a")
        val second = attempt(8, "label-msgid-${fuzz.index}-b", "labelattempt${fuzz.index}b")
        fuzz.record("labels first=$first second=$second")
        assertEquals(first.first, first.second)
        assertEquals(second.first, second.second)
        assertNotEquals(first.second, second.second)
        assertEquals(2, scalar(db, "SELECT COUNT(*) FROM messages WHERE text LIKE ?", "labelattempt${fuzz.index}%"))
    }

    private suspend fun exerciseIdentityFreeOverlaps(
        fuzz: FuzzCase,
        db: MotdDatabase,
        store: CanonicalTimelineStore,
        networkId: Long,
        roomId: Long,
    ) {
        val text = "identityfreerepeat${fuzz.index}"
        val event = message(roomId, null, 120_000L + fuzz.index, text)
        val pageSizes = List(5) { fuzz.random.nextInt(1, 5) }
        pageSizes.forEachIndexed { page, size ->
            fuzz.record("identity-free page=$page size=$size")
            store.ingestBatch(
                List(size) {
                    TimelineObservation(
                        networkId,
                        event,
                        ObservationOrigin.HISTORY,
                        1,
                        batchId = "identity-page-$page",
                        timeProvenance = TimeProvenance.SERVER_TAG,
                    )
                },
            )
            assertEquals(
                pageSizes.take(page + 1).max(),
                scalar(db, "SELECT COUNT(*) FROM messages WHERE text = ?", text),
            )
        }
    }

    private suspend fun assertUnreadProjection(db: MotdDatabase, roomId: Long) {
        val expected = scalar(
            db,
            """SELECT COUNT(*) FROM messages
               WHERE bufferId = ? AND isSelf = 0 AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')""",
            roomId,
        )
        val room = db.bufferDao().observeChatList().first().single { it.bufferId == roomId }
        assertEquals(expected, room.unreadCount)
    }

    private suspend fun exercisePresentationRollback(
        fuzz: FuzzCase,
        db: MotdDatabase,
        store: CanonicalTimelineStore,
        networkId: Long,
        roomId: Long,
    ) {
        val event = store.ingest(
            TimelineObservation(
                networkId,
                message(
                    roomId,
                    "presentation-${fuzz.index}",
                    125_000L + fuzz.index,
                    "presentationrollback${fuzz.index}",
                ),
                ObservationOrigin.HISTORY,
                1,
                batchId = "presentation-rollback",
                timeProvenance = TimeProvenance.SERVER_TAG,
            ),
        ).event
        val sound = fuzz.index % 2 == 0
        val column = if (sound) "soundHandled" else "notificationClaimed"
        val trigger = "presentation_fuzz_abort_${fuzz.index}"
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $trigger BEFORE UPDATE OF $column ON messages " +
                "WHEN OLD.id = ${event.id} BEGIN SELECT RAISE(ABORT, 'generated presentation rollback'); END",
        )
        val failed = try {
            runCatching {
                if (sound) store.claimSound(event.id) else store.claimNotification(event.id)
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
        }
        fuzz.record("presentation-rollback column=$column failure=${failed.exceptionOrNull()?.javaClass?.simpleName}")
        assertTrue(failed.isFailure)
        assertEquals(
            0,
            scalar(
                db,
                "SELECT COUNT(*) FROM messages WHERE id = ? AND (soundHandled != 0 OR notificationClaimed != 0)",
                event.id,
            ),
        )
    }

    private suspend fun exerciseRollback(
        fuzz: FuzzCase,
        db: MotdDatabase,
        networkId: Long,
    ) {
        val rollbackRoom = db.bufferDao().insert(room(networkId, "#rollback-${fuzz.index}"))
        val tables = listOf("messages", "event_aliases", "event_observations", "history_cursors")
        val table = tables[fuzz.index % tables.size]
        val before = snapshot(db)
        val trigger = "fuzz_abort_${fuzz.index}"
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $trigger BEFORE INSERT ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'generated rollback'); END",
        )
        val result = try {
            runCatching {
                CanonicalTimelineStore(db).ingestBatch(
                    listOf(
                        TimelineObservation(
                            networkId,
                            message(
                                rollbackRoom,
                                "rollback-${fuzz.index}",
                                130_000L + fuzz.index,
                                "rollbacktoken${fuzz.index}",
                            ),
                            ObservationOrigin.HISTORY,
                            1,
                            batchId = "rollback",
                            timeProvenance = TimeProvenance.SERVER_TAG,
                        ),
                    ),
                )
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
        }
        fuzz.record("rollback table=$table failure=${result.exceptionOrNull()?.javaClass?.simpleName}")
        assertTrue(result.isFailure)
        assertEquals(before, snapshot(db))
    }

    private fun assertCanonicalIntegrity(db: MotdDatabase) {
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        assertEquals(
            0,
            scalar(
                db,
                """SELECT COUNT(*) FROM (
                       SELECT networkId, namespace, value, COUNT(*) c FROM event_aliases
                       GROUP BY networkId, namespace, value HAVING c > 1
                   )""",
            ),
        )
        assertEquals(
            0,
            scalar(
                db,
                """SELECT COUNT(*) FROM (
                       SELECT timelineEventId, COUNT(DISTINCT hex(value)) c FROM event_aliases
                       WHERE namespace = 'MSGID' GROUP BY timelineEventId HAVING c > 1
                   )""",
            ),
        )
        assertEquals(
            0,
            scalar(
                db,
                """SELECT COUNT(*) FROM event_redirects r
                   LEFT JOIN messages m ON m.id = r.canonicalEventId
                   LEFT JOIN event_redirects chained ON chained.losingEventId = r.canonicalEventId
                   WHERE m.id IS NULL OR chained.losingEventId IS NOT NULL""",
            ),
        )
    }

    private fun snapshot(db: MotdDatabase): Map<String, Int> = listOf(
        "buffers",
        "messages",
        "messages_fts",
        "event_aliases",
        "event_redirects",
        "event_observations",
        "history_cursors",
        "reactions",
    ).associateWith { table -> scalar(db, "SELECT COUNT(*) FROM $table") }

    private fun scalar(db: MotdDatabase, query: String, vararg args: Any?): Int =
        db.openHelper.readableDatabase.query(query, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun open(name: String): MotdDatabase =
        Room.databaseBuilder(context, MotdDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun observation(
        networkId: Long,
        roomId: Long,
        logical: LogicalEvent,
        delivery: Delivery,
    ): TimelineObservation {
        val tagged = delivery != Delivery.LIVE_LOCAL
        val origin = when (delivery) {
            Delivery.LIVE_LOCAL, Delivery.ECHO_TAGGED -> ObservationOrigin.LIVE
            Delivery.PUSH_TAGGED -> ObservationOrigin.PUSH
            Delivery.HISTORY_TAGGED -> ObservationOrigin.HISTORY
        }
        return TimelineObservation(
            networkId,
            message(
                roomId,
                if (tagged) logical.msgid else null,
                if (tagged) logical.serverTime else logical.serverTime - 17,
                logical.text,
            ).copy(serverTimeAuthoritative = tagged),
            origin,
            1,
            batchId = if (origin == ObservationOrigin.HISTORY) "history" else null,
            timeProvenance = if (tagged) TimeProvenance.SERVER_TAG else TimeProvenance.LOCAL_CLOCK,
        )
    }

    private fun network() = NetworkEntity(
        name = "fuzz-network",
        role = NetworkRole.DIRECT,
        host = "irc.example",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )

    private fun room(networkId: Long, name: String) = BufferEntity(
        networkId = networkId,
        name = name,
        displayName = name,
        type = BufferType.CHANNEL,
    )

    private fun message(roomId: Long, msgid: String?, time: Long, text: String) = MessageEntity(
        bufferId = roomId,
        msgid = msgid,
        serverTime = time,
        sender = "alice",
        normalizedActor = "alice",
        kind = MessageKind.PRIVMSG,
        text = text,
        dedupKey = "generated-diagnostic",
    )

    private data class LogicalEvent(
        val index: Int,
        val msgid: String,
        val text: String,
        val serverTime: Long,
    )

    private enum class Delivery { LIVE_LOCAL, PUSH_TAGGED, ECHO_TAGGED, HISTORY_TAGGED }

    private sealed interface Operation {
        data class Single(val event: LogicalEvent, val delivery: Delivery) : Operation
        data class History(val events: List<LogicalEvent>) : Operation
    }
}
