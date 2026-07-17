package io.github.trevarj.motd.fuzz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventProcessorStateMachineFuzzTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun generatedProcessorSequencesPresentAndResolveCanonicalStateOnce() = runTest {
        SeededFuzz.runSuspending(
            target = "event-processor",
            version = 1,
            prCases = 12,
            nightlyCases = 500,
            replayTest = javaClass.name,
        ) { fuzz ->
            val databaseName = "processor-fuzz-${fuzz.seed.hashCode()}-${fuzz.index}.db"
            context.deleteDatabase(databaseName)
            var db: MotdDatabase? = null
            var processor: EventProcessor? = null
            val notifier = RecordingNotifier()
            val sound = RecordingSound()
            try {
                val initial = open(databaseName)
                db = initial
                val networkId = initial.networkDao().insert(network())
                processor = newProcessor(initial, notifier, sound, networkId)

                val account = "account-${fuzz.index}-a"
                val msgid = "delivery-${fuzz.index}"
                val message = chat(
                    msgid = msgid,
                    time = 200_000L + fuzz.index,
                    source = "Alice",
                    account = account,
                    text = "deliverytoken${fuzz.index}",
                )
                val deliveries = Delivery.entries.shuffled(fuzz.random)
                val restartAfter = fuzz.random.nextInt(1, deliveries.size)
                deliveries.forEachIndexed { index, delivery ->
                    fuzz.record("delivery index=$index kind=$delivery")
                    deliver(checkNotNull(processor), networkId, "Alice", message, delivery)
                    if (index + 1 == restartAfter) {
                        fuzz.record("processor-reopen after=$index")
                        checkNotNull(processor).shutdown()
                        checkNotNull(db).close()
                        val reopened = open(databaseName)
                        db = reopened
                        processor = newProcessor(reopened, notifier, sound, networkId)
                    }
                }

                val activeDb = checkNotNull(db)
                val activeProcessor = checkNotNull(processor)
                val canonicalId = scalarLong(activeDb, "SELECT id FROM messages WHERE msgid = ?", msgid)
                assertEquals(1, scalar(activeDb, "SELECT COUNT(*) FROM messages WHERE msgid = ?", msgid))
                assertEquals(listOf(canonicalId), notifier.eventIds)
                assertEquals(listOf(msgid), sound.msgids)

                exerciseAccountNickRooms(fuzz, activeDb, activeProcessor, networkId, account)
                exerciseLateRepliesAndReactions(fuzz, activeDb, activeProcessor, networkId, account)
                exerciseResolutionRollback(
                    fuzz,
                    activeDb,
                    activeProcessor,
                    networkId,
                    account,
                    notifier,
                    sound,
                )
                exerciseProcessorRollback(fuzz, activeDb, activeProcessor, networkId, notifier, sound)
                assertRoomRedirectIntegrity(activeDb)
                assertEquals(0, scalar(activeDb, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
            } finally {
                processor?.shutdown()
                db?.close()
                context.deleteDatabase(databaseName)
            }
        }
    }

    private suspend fun exerciseAccountNickRooms(
        fuzz: FuzzCase,
        db: MotdDatabase,
        processor: EventProcessor,
        networkId: Long,
        accountA: String,
    ) {
        val before = BufferStore(db).resolveQueryRoom(networkId, "alice", accountA)
        assertNotNull(before)
        val nickEvent = IrcEvent.NickChanged(
            context("nick-${fuzz.index}", 210_000L + fuzz.index),
            from = "Alice",
            to = "Bob",
            isSelf = false,
        )
        processor.process(networkId, nickEvent)
        processor.process(
            networkId,
            chat(
                "nick-follow-${fuzz.index}",
                211_000L + fuzz.index,
                "Bob",
                accountA,
                "sameaccountafterrename${fuzz.index}",
            ),
        )
        val after = BufferStore(db).resolveQueryRoom(networkId, "bob", accountA)
        assertEquals(before?.id, after?.id)
        assertEquals("Bob", after?.displayName)

        val accountB = "account-${fuzz.index}-b"
        processor.process(
            networkId,
            chat(
                "nick-reuse-${fuzz.index}",
                212_000L + fuzz.index,
                "Bob",
                accountB,
                "differentaccountreuse${fuzz.index}",
            ),
        )
        val reused = BufferStore(db).resolveQueryRoom(networkId, "bob", accountB)
        assertNotNull(reused)
        assertNotEquals(after?.id, reused?.id)
        fuzz.record("rooms accountA=${after?.id} accountB=${reused?.id}")
    }

    private suspend fun exerciseLateRepliesAndReactions(
        fuzz: FuzzCase,
        db: MotdDatabase,
        processor: EventProcessor,
        networkId: Long,
        account: String,
    ) {
        val parentMsgid = "parent-${fuzz.index}"
        val childMsgid = "child-${fuzz.index}"
        val parent = chat(parentMsgid, 220_000L + fuzz.index, "Bob", account, "parenttoken${fuzz.index}")
        val child = chat(childMsgid, 220_001L + fuzz.index, "Bob", account, "childtoken${fuzz.index}")
            .copy(replyToMsgid = parentMsgid)
        val reaction = IrcEvent.TagMessage(
            context("reaction-${fuzz.index}", 220_002L + fuzz.index),
            Prefix("Bob"),
            "me",
            typing = null,
            reactEmoji = "👍",
            reactTargetMsgid = parentMsgid,
        )
        val operations = mutableListOf<suspend () -> Unit>(
            { processor.process(networkId, child) },
            { processor.process(networkId, reaction) },
            { processor.process(networkId, parent) },
        )
        operations.shuffle(fuzz.random)
        operations.forEachIndexed { index, operation ->
            fuzz.record("reply-reaction operation=$index")
            operation()
        }
        val parentId = scalarLong(db, "SELECT id FROM messages WHERE msgid = ?", parentMsgid)
        assertEquals(
            parentId,
            scalarLong(db, "SELECT replyToEventId FROM messages WHERE msgid = ?", childMsgid),
        )
        assertEquals(
            parentId,
            scalarLong(db, "SELECT targetEventId FROM reactions WHERE targetMsgid = ?", parentMsgid),
        )
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM reactions WHERE targetMsgid = ?", parentMsgid))
    }

    private suspend fun exerciseProcessorRollback(
        fuzz: FuzzCase,
        db: MotdDatabase,
        processor: EventProcessor,
        networkId: Long,
        notifier: RecordingNotifier,
        sound: RecordingSound,
    ) {
        val beforeState = snapshot(db)
        val beforeNotifications = notifier.eventIds.toList()
        val beforeSounds = sound.msgids.toList()
        val tables = listOf(
            "buffers",
            "room_aliases",
            "messages",
            "event_aliases",
            "event_observations",
            "history_cursors",
        )
        val table = tables[fuzz.index % tables.size]
        val trigger = "processor_fuzz_abort_${fuzz.index}"
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $trigger BEFORE INSERT ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'generated processor rollback'); END",
        )
        val failed = try {
            runCatching {
                processor.process(
                    networkId,
                    IrcEvent.HistoryBatch(
                        "#rollback-${fuzz.index}",
                        listOf(
                            chat(
                                "processor-rollback-${fuzz.index}",
                                230_000L + fuzz.index,
                                "Alice",
                                null,
                                "processorrollback${fuzz.index}",
                                target = "#rollback-${fuzz.index}",
                            ),
                        ),
                    ),
                )
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
        }
        fuzz.record("processor-rollback table=$table failure=${failed.exceptionOrNull()?.javaClass?.simpleName}")
        assertTrue(failed.isFailure)
        assertEquals(beforeState, snapshot(db))
        assertNull(db.bufferDao().byName(networkId, "#rollback-${fuzz.index}"))
        assertEquals(beforeNotifications, notifier.eventIds)
        assertEquals(beforeSounds, sound.msgids)
    }

    private suspend fun exerciseResolutionRollback(
        fuzz: FuzzCase,
        db: MotdDatabase,
        processor: EventProcessor,
        networkId: Long,
        account: String,
        notifier: RecordingNotifier,
        sound: RecordingSound,
    ) {
        val parentMsgid = "resolution-rollback-parent-${fuzz.index}"
        val trigger = "resolution_fuzz_abort_${fuzz.index}"
        val reply = fuzz.index % 2 == 0
        if (reply) {
            processor.process(
                networkId,
                chat(
                    "resolution-rollback-child-${fuzz.index}",
                    225_000L + fuzz.index,
                    "Bob",
                    account,
                    "resolutionrollbackchild${fuzz.index}",
                ).copy(replyToMsgid = parentMsgid),
            )
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER $trigger BEFORE UPDATE OF replyToEventId ON messages " +
                    "BEGIN SELECT RAISE(ABORT, 'generated reply rollback'); END",
            )
        } else {
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    context("resolution-rollback-reaction-${fuzz.index}", 225_000L + fuzz.index),
                    Prefix("Bob"),
                    "me",
                    typing = null,
                    reactEmoji = "🔥",
                    reactTargetMsgid = parentMsgid,
                ),
            )
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER $trigger BEFORE UPDATE OF targetEventId ON reactions " +
                    "BEGIN SELECT RAISE(ABORT, 'generated reaction rollback'); END",
            )
        }
        val beforeState = snapshot(db)
        val beforeNotifications = notifier.eventIds.toList()
        val beforeSounds = sound.msgids.toList()
        val failed = try {
            runCatching {
                processor.process(
                    networkId,
                    chat(
                        parentMsgid,
                        225_001L + fuzz.index,
                        "Bob",
                        account,
                        "resolutionrollbackparent${fuzz.index}",
                    ),
                )
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
        }
        fuzz.record("resolution-rollback kind=${if (reply) "reply" else "reaction"} failure=${failed.exceptionOrNull()?.javaClass?.simpleName}")
        assertTrue(failed.isFailure)
        assertEquals(beforeState, snapshot(db))
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM messages WHERE msgid = ?", parentMsgid))
        if (reply) {
            assertEquals(
                1,
                scalar(
                    db,
                    "SELECT COUNT(*) FROM messages WHERE msgid = ? AND replyToEventId IS NULL",
                    "resolution-rollback-child-${fuzz.index}",
                ),
            )
        } else {
            assertEquals(
                1,
                scalar(
                    db,
                    "SELECT COUNT(*) FROM reactions WHERE targetMsgid = ? AND targetEventId IS NULL",
                    parentMsgid,
                ),
            )
        }
        assertEquals(beforeNotifications, notifier.eventIds)
        assertEquals(beforeSounds, sound.msgids)
    }

    private fun assertRoomRedirectIntegrity(db: MotdDatabase) {
        assertEquals(
            0,
            scalar(
                db,
                """SELECT COUNT(*) FROM buffers losing
                   LEFT JOIN buffers winner ON winner.id = losing.redirectToRoomId
                   WHERE losing.redirectToRoomId IS NOT NULL
                     AND (winner.id IS NULL OR winner.redirectToRoomId IS NOT NULL OR winner.id >= losing.id)""",
            ),
        )
    }

    private suspend fun newProcessor(
        db: MotdDatabase,
        notifier: RecordingNotifier,
        sound: RecordingSound,
        networkId: Long,
    ): EventProcessor = EventProcessor(
        db,
        TypingTrackerImpl(),
        notifier,
        sound,
    ).also { it.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459")) }

    private suspend fun deliver(
        processor: EventProcessor,
        networkId: Long,
        historyTarget: String,
        message: IrcEvent.ChatMessage,
        delivery: Delivery,
    ) {
        when (delivery) {
            Delivery.LIVE -> processor.process(networkId, message)
            Delivery.PUSH -> processor.processPush(networkId, message)
            Delivery.HISTORY -> processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    historyTarget,
                    listOf(message.copy(ctx = message.ctx.copy(batchId = "history"))),
                ),
            )
        }
    }

    private fun chat(
        msgid: String,
        time: Long,
        source: String,
        account: String?,
        text: String,
        target: String = "me",
    ) = IrcEvent.ChatMessage(
        context(msgid, time).copy(account = account),
        IrcEvent.ChatKind.PRIVMSG,
        Prefix(source),
        target,
        text,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun context(msgid: String?, time: Long) = MessageContext(
        msgid = msgid,
        serverTime = time,
        account = null,
        batchId = null,
        label = null,
    )

    private fun open(name: String): MotdDatabase =
        Room.databaseBuilder(context, MotdDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun network() = NetworkEntity(
        name = "fuzz-network",
        role = NetworkRole.DIRECT,
        host = "irc.example",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )

    private fun scalar(db: MotdDatabase, query: String, vararg args: Any?): Int =
        db.openHelper.readableDatabase.query(query, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun scalarLong(db: MotdDatabase, query: String, vararg args: Any?): Long =
        db.openHelper.readableDatabase.query(query, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun snapshot(db: MotdDatabase): Map<String, Int> = listOf(
        "buffers",
        "room_aliases",
        "messages",
        "messages_fts",
        "event_aliases",
        "event_redirects",
        "event_observations",
        "history_cursors",
        "reactions",
    ).associateWith { table -> scalar(db, "SELECT COUNT(*) FROM $table") }

    private class RecordingNotifier : MessageNotifier {
        val eventIds = mutableListOf<Long>()

        override suspend fun onIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            message: IrcEvent.ChatMessage,
        ) = Unit

        override suspend fun onCanonicalIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            eventId: Long,
            message: IrcEvent.ChatMessage,
        ) {
            eventIds += eventId
        }
    }

    private class RecordingSound : ChatSoundPlayer {
        val msgids = mutableListOf<String?>()

        override suspend fun onIncoming(
            bufferId: Long,
            type: BufferType,
            message: IrcEvent.ChatMessage,
        ) {
            msgids += message.ctx.msgid
        }

        override suspend fun onOutgoingAccepted(bufferId: Long) = Unit
    }

    private enum class Delivery { LIVE, PUSH, HISTORY }
}
