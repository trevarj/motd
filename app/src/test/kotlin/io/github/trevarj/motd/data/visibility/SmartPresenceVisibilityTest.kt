package io.github.trevarj.motd.data.visibility

import androidx.paging.PagingSource
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.SMART_PRESENCE_WINDOW_MS
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The smart presence rule lives in SQL because it depends on neighboring rows, so it is exercised
 * against a real database through the same paging query the timeline uses.
 */
@RunWith(RobolectricTestRunner::class)
class SmartPresenceVisibilityTest {
    private lateinit var db: MotdDatabase
    private var bufferId = 0L
    private var otherBufferId = 0L
    private var nextKey = 0

    private val base = 1_700_000_000_000L
    private val commonWindowMs = 7 * 24 * 60 * 60 * 1000L

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            val networkId = db.networkDao().insert(network())
            bufferId = db.bufferDao().insert(buffer(networkId, "#smart"))
            otherBufferId = db.bufferDao().insert(buffer(networkId, "#other"))
        }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(
        kind: MessageKind,
        sender: String,
        atOffsetMs: Long,
        isSelf: Boolean = false,
        room: Long = bufferId,
        normalizedActor: String = sender,
    ) {
        db.messageDao().insertAll(
            listOf(
                message(
                    bufferId = room,
                    text = "${kind.name} $sender",
                    sender = sender,
                    serverTime = base + atOffsetMs,
                    dedupKey = "key-${nextKey++}",
                    kind = kind,
                    isSelf = isSelf,
                ).copy(normalizedActor = normalizedActor),
            ),
        )
    }

    private suspend fun visibleRows(mode: PresenceMode): List<MessageEntity> {
        val source =
            db.messageDao().pagingSource(
                messagePagingQuery(bufferId, MessageVisibilitySpec(presenceMode = mode)),
            )
        val page =
            source.load(
                PagingSource.LoadParams.Refresh(null, 100, false),
            ) as PagingSource.LoadResult.Page
        return page.data
    }

    private suspend fun visibleKinds(mode: PresenceMode): List<Pair<MessageKind, String>> = visibleRows(mode).map { it.kind to it.sender }

    @Test
    fun `smart keeps presence rows for a user who spoke inside the window`() =
        runTest {
            insert(MessageKind.PRIVMSG, "alice", 0)
            insert(MessageKind.QUIT, "alice", SMART_PRESENCE_WINDOW_MS - 1)

            assertEquals(
                listOf(MessageKind.QUIT to "alice", MessageKind.PRIVMSG to "alice"),
                visibleKinds(PresenceMode.SMART),
            )
        }

    @Test
    fun `smart drops presence rows for a user who never spoke`() =
        runTest {
            insert(MessageKind.PRIVMSG, "alice", 0)
            insert(MessageKind.JOIN, "lurker", 1_000)
            insert(MessageKind.PART, "lurker", 2_000)
            insert(MessageKind.NICK, "lurker", 3_000)

            assertEquals(listOf(MessageKind.PRIVMSG to "alice"), visibleKinds(PresenceMode.SMART))
        }

    @Test
    fun `smart drops presence rows once the speech falls outside the window`() =
        runTest {
            insert(MessageKind.PRIVMSG, "alice", 0)
            insert(MessageKind.QUIT, "alice", SMART_PRESENCE_WINDOW_MS + 1)

            assertEquals(listOf(MessageKind.PRIVMSG to "alice"), visibleKinds(PresenceMode.SMART))
        }

    @Test
    fun `smart reveals a join only after its normalized actor speaks`() =
        runTest {
            insert(MessageKind.JOIN, "ALICE", 0, normalizedActor = "alice")
            assertEquals(emptyList<Pair<MessageKind, String>>(), visibleKinds(PresenceMode.SMART))

            insert(MessageKind.PRIVMSG, "Alice", 1_000, normalizedActor = "alice")

            assertEquals(
                listOf(MessageKind.PRIVMSG to "Alice", MessageKind.JOIN to "ALICE"),
                visibleKinds(PresenceMode.SMART),
            )
            assertEquals(listOf(MessageKind.PRIVMSG to "Alice"), visibleKinds(PresenceMode.HIDDEN))
        }

    @Test
    fun `smart forward join window includes five minutes but excludes one millisecond later`() =
        runTest {
            insert(MessageKind.JOIN, "boundary", 0)
            insert(MessageKind.JOIN, "expired", 0)
            insert(MessageKind.NOTICE, "boundary", SMART_PRESENCE_WINDOW_MS)
            insert(MessageKind.ACTION, "expired", SMART_PRESENCE_WINDOW_MS + 1)

            assertEquals(
                listOf(
                    MessageKind.ACTION to "expired",
                    MessageKind.NOTICE to "boundary",
                    MessageKind.JOIN to "boundary",
                ),
                visibleKinds(PresenceMode.SMART),
            )
        }

    @Test
    fun `smart forward join speech must match actor and room`() =
        runTest {
            insert(MessageKind.JOIN, "alice", 0)
            insert(MessageKind.PRIVMSG, "alice", 1_000, normalizedActor = "bob")
            insert(MessageKind.PRIVMSG, "alice", 1_000, room = otherBufferId)

            assertEquals(listOf(MessageKind.PRIVMSG to "alice"), visibleKinds(PresenceMode.SMART))
        }

    @Test
    fun `smart ignores future speech for non join presence`() =
        runTest {
            listOf(MessageKind.PART, MessageKind.QUIT, MessageKind.NICK, MessageKind.AWAY, MessageKind.BACK)
                .forEachIndexed { index, kind -> insert(kind, "alice", index.toLong()) }
            insert(MessageKind.PRIVMSG, "alice", 1_000)

            assertEquals(listOf(MessageKind.PRIVMSG to "alice"), visibleKinds(PresenceMode.SMART))
        }

    @Test
    fun `smart covers nick changes for a participating user`() =
        runTest {
            insert(MessageKind.PRIVMSG, "alice", 0)
            insert(MessageKind.NICK, "alice", 1_000)
            insert(MessageKind.NICK, "quietguy", 2_000)

            assertEquals(
                listOf(MessageKind.NICK to "alice", MessageKind.PRIVMSG to "alice"),
                visibleKinds(PresenceMode.SMART),
            )
        }

    @Test
    fun `smart keeps away and back for a recent speaker`() =
        runTest {
            insert(MessageKind.PRIVMSG, "alice", 0)
            insert(MessageKind.AWAY, "alice", 1_000)
            insert(MessageKind.BACK, "alice", 2_000)
            insert(MessageKind.AWAY, "lurker", 3_000)
            insert(MessageKind.BACK, "lurker", 4_000)

            assertEquals(
                listOf(
                    MessageKind.BACK to "alice",
                    MessageKind.AWAY to "alice",
                    MessageKind.PRIVMSG to "alice",
                ),
                visibleKinds(PresenceMode.SMART),
            )
        }

    @Test
    fun `smart keeps away and back for a common chatter beyond five minutes`() =
        runTest {
            repeat(5) { insert(MessageKind.PRIVMSG, "alice", -600_000L + it * 1_000) }
            insert(MessageKind.AWAY, "alice", 0)
            insert(MessageKind.BACK, "alice", 1_000)

            val rows = visibleRows(PresenceMode.SMART)
            assertEquals(
                listOf(MessageKind.BACK, MessageKind.AWAY) + List(5) { MessageKind.PRIVMSG },
                rows.map { it.kind },
            )
            val reader = MessageVisibilityReader(db)
            val spec = MessageVisibilitySpec(presenceMode = PresenceMode.SMART)
            val lastSpeech = rows.first { it.kind == MessageKind.PRIVMSG }
            assertEquals(2, reader.countTimelineNewer(bufferId, lastSpeech.serverTime, lastSpeech.id, spec))
            assertEquals(rows.first().id, reader.latestEffectiveAnchor(bufferId, spec)?.id)
            assertEquals(5, visibleRows(PresenceMode.HIDDEN).size)
        }

    @Test
    fun `smart common chatter requires five conversation rows from that actor`() =
        runTest {
            repeat(4) { insert(MessageKind.PRIVMSG, "four", -600_000L + it * 1_000) }
            insert(MessageKind.JOIN, "four", -500_000)
            listOf(
                MessageKind.PRIVMSG,
                MessageKind.NOTICE,
                MessageKind.ACTION,
                MessageKind.PRIVMSG,
                MessageKind.NOTICE,
            ).forEachIndexed { index, kind -> insert(kind, "five", -600_000L + index * 1_000) }
            insert(MessageKind.AWAY, "four", 0)
            insert(MessageKind.AWAY, "five", 1_000)

            assertEquals(
                listOf(MessageKind.AWAY to "five"),
                visibleKinds(PresenceMode.SMART).filter { it.first == MessageKind.AWAY },
            )
        }

    @Test
    fun `smart common chatter includes seven day boundary but excludes older speech`() =
        runTest {
            repeat(4) {
                insert(MessageKind.PRIVMSG, "boundary", -600_000L + it * 1_000)
                insert(MessageKind.PRIVMSG, "expired", -600_000L + it * 1_000)
            }
            insert(MessageKind.PRIVMSG, "boundary", -commonWindowMs)
            insert(MessageKind.PRIVMSG, "expired", -commonWindowMs - 1)
            insert(MessageKind.AWAY, "boundary", 0)
            insert(MessageKind.AWAY, "expired", 0)

            assertEquals(
                listOf(MessageKind.AWAY to "boundary"),
                visibleKinds(PresenceMode.SMART).filter { it.first == MessageKind.AWAY },
            )
        }

    @Test
    fun `smart common chatter does not combine speech across rooms`() =
        runTest {
            repeat(4) { insert(MessageKind.PRIVMSG, "split", -600_000L + it * 1_000) }
            insert(MessageKind.PRIVMSG, "split", -600_000, room = otherBufferId)
            repeat(5) { insert(MessageKind.PRIVMSG, "elsewhere", -600_000L + it * 1_000, room = otherBufferId) }
            insert(MessageKind.AWAY, "split", 0)
            insert(MessageKind.BACK, "elsewhere", 1_000)

            assertEquals(
                List(4) { MessageKind.PRIVMSG to "split" },
                visibleKinds(PresenceMode.SMART),
            )
        }

    @Test
    fun `smart common chatter ignores the fifth message after a presence row`() =
        runTest {
            repeat(4) { insert(MessageKind.PRIVMSG, "alice", -600_000L + it * 1_000) }
            insert(MessageKind.AWAY, "alice", 0)
            assertEquals(4, visibleRows(PresenceMode.SMART).size)
            insert(MessageKind.PRIVMSG, "alice", 1_000)

            assertEquals(
                List(5) { MessageKind.PRIVMSG to "alice" },
                visibleKinds(PresenceMode.SMART),
            )
        }

    @Test
    fun `smart common chatter matches normalized actor rather than display nick`() =
        runTest {
            repeat(5) {
                insert(MessageKind.PRIVMSG, "Alice", -600_000L + it * 1_000, normalizedActor = "alice")
            }
            insert(MessageKind.AWAY, "ALICE", 0, normalizedActor = "alice")

            assertEquals(
                listOf(MessageKind.AWAY to "ALICE") + List(5) { MessageKind.PRIVMSG to "Alice" },
                visibleKinds(PresenceMode.SMART),
            )
        }

    @Test
    fun `smart always keeps our own presence rows`() =
        runTest {
            insert(MessageKind.JOIN, "me", 0, isSelf = true)

            assertEquals(listOf(MessageKind.JOIN to "me"), visibleKinds(PresenceMode.SMART))
        }

    /** Aggregates carry no single actor, so only HIDDEN may remove them. */
    @Test
    fun `smart keeps netsplit and netjoin aggregates`() =
        runTest {
            insert(MessageKind.NETSPLIT, "", 0)
            insert(MessageKind.NETJOIN, "", 1_000)

            assertEquals(2, visibleRows(PresenceMode.SMART).size)
            assertEquals(0, visibleRows(PresenceMode.HIDDEN).size)
        }

    @Test
    fun `speech in another room does not reveal a presence row`() =
        runTest {
            insert(MessageKind.PRIVMSG, "alice", 0, room = otherBufferId)
            insert(MessageKind.QUIT, "alice", 1_000)

            assertEquals(emptyList<Pair<MessageKind, String>>(), visibleKinds(PresenceMode.SMART))
        }

    @Test
    fun `a presence row is not its own evidence of participation`() =
        runTest {
            insert(MessageKind.JOIN, "alice", 0)
            insert(MessageKind.PART, "alice", 1_000)

            assertEquals(emptyList<Pair<MessageKind, String>>(), visibleKinds(PresenceMode.SMART))
        }

    @Test
    fun `all shows every presence row and hidden removes them`() =
        runTest {
            insert(MessageKind.PRIVMSG, "alice", 0)
            insert(MessageKind.JOIN, "lurker", 1_000)
            insert(MessageKind.NICK, "lurker", 2_000)

            assertEquals(3, visibleRows(PresenceMode.ALL).size)
            assertEquals(listOf(MessageKind.PRIVMSG to "alice"), visibleKinds(PresenceMode.HIDDEN))
        }
}
