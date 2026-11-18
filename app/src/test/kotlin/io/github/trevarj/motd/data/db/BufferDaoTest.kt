package io.github.trevarj.motd.data.db

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BufferDaoTest {
    private lateinit var db: MotdDatabase
    private var networkId: Long = 0

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            networkId = db.networkDao().insert(network())
        }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `deleting then reviving a query does not resurrect its anchor`() =
        runTest {
            val dao = db.bufferDao()
            val query = dao.insert(buffer(networkId, "alice", type = BufferType.QUERY).copy(pinned = true))
            dao.setAnchored(query, true)
            dao.deleteBuffer(query)
            assertNull(dao.rawById(query)!!.anchorOrder)
            assertTrue(dao.anchoredRooms().isEmpty())
            dao.reviveQuery(query)
            val revived = dao.observeChatList().first().single { it.bufferId == query }
            assertNull(revived.anchorOrder)
            assertFalse(revived.pinned)
        }

    @Test
    fun `anchors append globally preserve pin state and clear to null through redirects`() =
        runTest {
            val dao = db.bufferDao()
            val first = dao.insert(buffer(networkId, "#first").copy(pinned = true, ordering = 17))
            val hidden = dao.insert(buffer(networkId, "#hidden").copy(archived = true))
            val secondNetwork = db.networkDao().insert(network("second"))
            val last = dao.insert(buffer(secondNetwork, "alice", type = BufferType.QUERY))
            dao.setAnchored(first, true)
            dao.setAnchored(hidden, true)
            dao.setAnchored(first, true)
            dao.setAnchored(last, true)
            assertEquals(listOf(0, 1, 2), dao.anchoredRooms().map { it.anchorOrder })
            assertTrue(dao.rawById(first)!!.pinned)
            assertEquals(17, dao.rawById(first)!!.ordering)
            val redirect = dao.insert(buffer(networkId, "#redirect").copy(redirectToRoomId = first))
            dao.setAnchored(redirect, false)
            assertNull(dao.rawById(first)!!.anchorOrder)
            assertTrue(dao.rawById(first)!!.pinned)
            dao.setAnchored(redirect, true)
            assertEquals(3, dao.rawById(first)!!.anchorOrder)
            assertEquals(
                3,
                dao
                    .observeChatList()
                    .first()
                    .single { it.bufferId == first }
                    .anchorOrder,
            )
        }

    @Test
    fun `scoped anchor reorder retains hidden global slots and drops stale duplicates`() =
        runTest {
            val dao = db.bufferDao()
            val ids = (0..4).map { dao.insert(buffer(networkId, "#$it").copy(archived = it == 1)) }
            ids.forEach { dao.setAnchored(it, true) }
            val unanchored = dao.insert(buffer(networkId, "#unanchored"))
            dao.reorderAnchored(listOf(ids[4], -1, ids[0], ids[4], unanchored, ids[2]))
            assertEquals(listOf(ids[4], ids[1], ids[0], ids[3], ids[2]), dao.anchoredRooms().map { it.id })
            assertEquals(listOf(0, 1, 2, 3, 4), dao.anchoredRooms().map { it.anchorOrder })
            assertNull(dao.rawById(unanchored)!!.anchorOrder)
            dao.setAnchored(ids[0], false)
            dao.reorderAnchored(listOf(ids[0], ids[2], ids[4]))
            assertEquals(listOf(ids[2], ids[1], ids[3], ids[4]), dao.anchoredRooms().map { it.id })
            assertNull(dao.rawById(ids[0])!!.anchorOrder)
        }

    @Test
    fun `joined channel observer emits only visible raw display names`() =
        runTest {
            val joined =
                db.bufferDao().insert(
                    buffer(networkId, "#{joined}").copy(
                        displayName = "#[JOINED]",
                        joined = true,
                        avatarOverrideModel = "https://example.test/channel.png",
                    ),
                )
            db.bufferDao().insert(buffer(networkId, "#leaving").copy(joined = true, pendingCloseAt = 1L))
            val redirect = db.bufferDao().insert(buffer(networkId, "#redirect").copy(joined = true))
            db.bufferDao().update(db.bufferDao().rawById(redirect)!!.copy(redirectToRoomId = joined))
            db.bufferDao().insert(buffer(networkId, "#not-joined"))
            db.bufferDao().insert(buffer(networkId, "someone", type = BufferType.QUERY).copy(joined = true))

            assertEquals(
                setOf("#[JOINED]"),
                db
                    .bufferDao()
                    .observeJoinedChannelNames(networkId)
                    .first()
                    .toSet(),
            )
            assertEquals(
                listOf(
                    JoinedChannelRow(
                        joined,
                        networkId,
                        "#[JOINED]",
                        "https://example.test/channel.png",
                    ),
                ),
                db.bufferDao().observeJoinedChannels(networkId).first(),
            )
        }

    @Test
    fun `chat list projects archive state and canonical archive writes follow redirects`() =
        runTest {
            val winner = db.bufferDao().insert(buffer(networkId, "alice", type = BufferType.QUERY))
            val loser = db.bufferDao().insert(buffer(networkId, "alice-old", type = BufferType.QUERY))
            db.bufferDao().update(db.bufferDao().rawById(loser)!!.copy(redirectToRoomId = winner))

            assertEquals(1, db.bufferDao().setArchived(loser, true))
            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.bufferId == winner }
            assertTrue(row.archived)

            db.bufferDao().setMuted(winner, true)
            assertEquals(0, db.bufferDao().unarchiveIfUnmuted(winner))
            assertTrue(db.bufferDao().observeById(winner)!!.archived)
            db.bufferDao().setMuted(winner, false)
            assertEquals(1, db.bufferDao().unarchiveIfUnmuted(winner))
            assertFalse(db.bufferDao().observeById(winner)!!.archived)
        }

    @Test
    fun `channel message writes do not invalidate monitor projection`() =
        runTest {
            val query = db.bufferDao().insert(buffer(networkId, "alice", type = BufferType.QUERY))
            val channel = db.bufferDao().insert(buffer(networkId, "#room", type = BufferType.CHANNEL))

            db.bufferDao().observeMonitorQueryRows().test {
                assertEquals(1, awaitItem().size)
                db.messageDao().insertAll(listOf(message(channel, "channel", serverTime = 100, dedupKey = "channel")))
                db.bufferDao().refreshMonitorActivity(channel)
                expectNoEvents()

                db.messageDao().insertAll(listOf(message(query, "query", serverTime = 200, dedupKey = "query")))
                db.bufferDao().refreshMonitorActivity(query)
                assertEquals(200L, awaitItem().single().lastMessageTime)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `monitor activity is query-only and ignores presence rows`() =
        runTest {
            val query = db.bufferDao().insert(buffer(networkId, "alice", type = BufferType.QUERY))
            val channel = db.bufferDao().insert(buffer(networkId, "#room", type = BufferType.CHANNEL))
            db.messageDao().insertAll(
                listOf(
                    message(query, "chat", serverTime = 100, dedupKey = "query-chat"),
                    message(query, "join", serverTime = 200, dedupKey = "query-join", kind = MessageKind.JOIN),
                    message(query, "away", serverTime = 250, dedupKey = "query-away", kind = MessageKind.AWAY),
                    message(channel, "channel", serverTime = 300, dedupKey = "channel-chat"),
                ),
            )

            assertEquals(1, db.bufferDao().refreshMonitorActivity(query))
            assertEquals(0, db.bufferDao().refreshMonitorActivity(channel))

            assertEquals(
                listOf(MonitorQueryRow(networkId, "alice", pinned = false, lastMessageTime = 100)),
                db.bufferDao().observeMonitorQueryRows().first(),
            )
            assertNull(db.bufferDao().rawById(channel)?.monitorActivityTime)
        }

    @Test
    fun chatList_unreadAndMentionMath_relativeToReadMarker() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            // readMarker at t=100: messages after count as unread.
            val bid = bufDao.insert(buffer(networkId, "#chan", readMarkerTime = 100))

            msgDao.insertAll(
                listOf(
                    // before marker → read.
                    message(bid, "old", serverTime = 50, dedupKey = "a"),
                    // after marker, not self, chat kind → unread.
                    message(bid, "new1", serverTime = 150, dedupKey = "b"),
                    // after marker + mention → unread AND mention.
                    message(bid, "hey me", serverTime = 160, dedupKey = "c", hasMention = true),
                    // after marker but self → NOT unread.
                    message(bid, "my own", sender = "me", serverTime = 170, dedupKey = "d", isSelf = true),
                    // after marker but system kind → NOT unread.
                    message(bid, "joined", serverTime = 180, dedupKey = "e", kind = MessageKind.JOIN),
                ),
            )

            val row = bufDao.observeChatList().first().single()
            assertEquals(2, row.unreadCount)
            assertEquals(1, row.mentionCount)
            // JOIN is timeline-only; the newest eligible message supplies every preview field.
            assertEquals("my own", row.lastMessageText)
            assertEquals("me", row.lastMessageSender)
            assertEquals(170L, row.lastMessageTime)
        }

    @Test
    fun `same timestamp opaque gap keeps chat list counts explicitly incomplete`() =
        runTest {
            val bid =
                db.bufferDao().insert(
                    buffer(networkId, "#same-time").copy(
                        localReadAnchorTime = 100,
                        localReadAnchorEventId = Long.MIN_VALUE,
                    ),
                )
            db.historyGapDao().insert(
                HistoryGapEntity(0, bid, "older", 100, "newer", 100),
            )

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertTrue(row.unreadCountIncomplete)
            assertTrue(row.mentionCountIncomplete)
        }

    @Test
    fun `same timestamp msgidless tuple gap keeps chat list counts explicitly incomplete`() =
        runTest {
            val bid =
                db.bufferDao().insert(
                    buffer(networkId, "#same-time-tuple").copy(
                        localReadAnchorTime = 100,
                        localReadAnchorEventId = 10,
                    ),
                )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bid,
                    olderMsgid = null,
                    olderServerTime = 100,
                    newerMsgid = null,
                    newerServerTime = 100,
                    olderEventId = 10,
                    olderTimelineOrder = 10,
                    newerEventId = 20,
                    newerTimelineOrder = 20,
                ),
            )

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertTrue(row.unreadCountIncomplete)
            assertTrue(row.mentionCountIncomplete)
        }

    @Test
    fun `exhausted identical boundary at the unread floor keeps counts incomplete`() =
        runTest {
            val bid =
                db.bufferDao().insert(
                    buffer(networkId, "#saturated-floor").copy(
                        localReadAnchorTime = 100,
                        localReadAnchorEventId = 10,
                    ),
                )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bid,
                    olderMsgid = "opaque",
                    olderServerTime = 100,
                    newerMsgid = "opaque",
                    newerServerTime = 100,
                    recoverable = false,
                    olderEventId = 10,
                    olderTimelineOrder = 10,
                    newerEventId = 10,
                    newerTimelineOrder = 10,
                ),
            )

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertTrue(row.unreadCountIncomplete)
            assertTrue(row.mentionCountIncomplete)
        }

    @Test
    fun `spanning gap whose exact newer tuple follows the read marker keeps counts incomplete`() =
        runTest {
            val bid =
                db.bufferDao().insert(
                    buffer(networkId, "#spanning-floor").copy(
                        localReadAnchorTime = 900,
                        localReadAnchorEventId = 50,
                    ),
                )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bid,
                    olderMsgid = "old",
                    olderServerTime = 100,
                    newerMsgid = "newer-tie",
                    newerServerTime = 900,
                    olderEventId = 10,
                    olderTimelineOrder = 10,
                    newerEventId = 100,
                    newerTimelineOrder = 100,
                ),
            )

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertTrue(row.unreadCountIncomplete)
            assertTrue(row.mentionCountIncomplete)
        }

    @Test
    fun `dominating mute floor treats its equal-time tuple as fully acknowledged`() =
        runTest {
            val bid =
                db.bufferDao().insert(
                    buffer(networkId, "#mute-floor").copy(
                        localReadAnchorTime = 800,
                        localReadAnchorEventId = 50,
                        localUnreadFloorTime = 900,
                    ),
                )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bid,
                    olderMsgid = "old",
                    olderServerTime = 100,
                    newerMsgid = "newer-tie",
                    newerServerTime = 900,
                    olderEventId = 10,
                    olderTimelineOrder = 10,
                    newerEventId = 100,
                    newerTimelineOrder = 100,
                ),
            )

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertFalse(row.unreadCountIncomplete)
            assertFalse(row.mentionCountIncomplete)
        }

    @Test
    fun `canonicalized newer gap boundary does not leave a false incomplete badge`() =
        runTest {
            val bid = db.bufferDao().insert(buffer(networkId, "#canonical-gap"))
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bid, "winner", serverTime = 900, dedupKey = "winner"),
                        message(bid, "loser", serverTime = 900, dedupKey = "loser"),
                    ),
                )
            db.bufferDao().update(
                checkNotNull(db.bufferDao().rawById(bid)).copy(
                    localReadAnchorTime = 900,
                    localReadAnchorEventId = ids[0],
                ),
            )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bid,
                    olderMsgid = "old",
                    olderServerTime = 100,
                    newerMsgid = "loser",
                    newerServerTime = 900,
                    newerEventId = ids[1],
                    newerTimelineOrder = ids[1],
                ),
            )

            db.historyGapDao().repointEventBoundary(ids[1], ids[0], 900, ids[0])
            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertFalse(row.unreadCountIncomplete)
            assertFalse(row.mentionCountIncomplete)
        }

    @Test
    fun `reordered canonical boundary uses its repaired timeline tuple for badges`() =
        runTest {
            val bid = db.bufferDao().insert(buffer(networkId, "#reordered-gap"))
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bid, "marker", serverTime = 900, dedupKey = "marker"),
                        message(bid, "boundary", serverTime = 900, dedupKey = "boundary"),
                    ),
                )
            db.bufferDao().update(
                checkNotNull(db.bufferDao().rawById(bid)).copy(
                    localReadAnchorTime = 900,
                    localReadAnchorEventId = ids[0],
                ),
            )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bid,
                    olderMsgid = "old",
                    olderServerTime = 100,
                    newerMsgid = "boundary",
                    newerServerTime = 900,
                    newerEventId = ids[1],
                    newerTimelineOrder = 100,
                ),
            )

            db.historyGapDao().repointEventBoundary(ids[1], ids[1], 900, 0)
            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertFalse(row.unreadCountIncomplete)
            assertFalse(row.mentionCountIncomplete)
        }

    @Test
    fun `partial history without a gap keeps counts incomplete`() =
        runTest {
            val bid =
                db.bufferDao().insert(
                    buffer(networkId, "#partial").copy(
                        localReadAnchorTime = 100,
                        historyComplete = false,
                        oldestFetchedTime = 200,
                    ),
                )

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()

            assertTrue(row.unreadCountIncomplete)
            assertTrue(row.mentionCountIncomplete)
        }

    @Test
    fun `another rooms gap does not control partial-history count fallback`() =
        runTest {
            val complete =
                db.bufferDao().insert(
                    buffer(networkId, "#complete").copy(
                        localReadAnchorTime = 100,
                        historyComplete = true,
                        oldestFetchedTime = 200,
                    ),
                )
            val other = db.bufferDao().insert(buffer(networkId, "#other"))
            db.historyGapDao().insert(HistoryGapEntity(0, other, "a", 100, "b", 500))

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.bufferId == complete }

            assertFalse(row.unreadCountIncomplete)
            assertFalse(row.mentionCountIncomplete)
        }

    @Test
    fun `same millisecond local anchor uses event id ordering`() =
        runTest {
            val room = db.bufferDao().insert(buffer(networkId, "#same-ms"))
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(room, "first", serverTime = 100, dedupKey = "same-1"),
                        message(room, "second", serverTime = 100, dedupKey = "same-2"),
                    ),
                )

            db.bufferDao().advanceLocalReadAnchor(room, 100, ids.first())

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()
            assertEquals(1, row.unreadCount)
            assertEquals(ids.first(), db.bufferDao().observeById(room)?.localReadAnchorEventId)
        }

    @Test
    fun `deleting anchored event falls back to prior exact timeline row`() =
        runTest {
            val room = db.bufferDao().insert(buffer(networkId, "#fallback"))
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(room, "first", serverTime = 100, dedupKey = "fallback-1"),
                        message(room, "second", serverTime = 100, dedupKey = "fallback-2"),
                    ),
                )
            db.bufferDao().advanceLocalReadAnchor(room, 100, ids.last())

            db.messageDao().deleteWithAnchorFallback(ids.last())

            val buffer = db.bufferDao().observeById(room)
            assertEquals(100L, buffer?.localReadAnchorTime)
            assertEquals(ids.first(), buffer?.localReadAnchorEventId)
        }

    @Test
    fun chatList_presenceEventsNeverReplacePreviewOrActivity() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val kinds =
                listOf(
                    MessageKind.JOIN,
                    MessageKind.PART,
                    MessageKind.QUIT,
                    MessageKind.AWAY,
                    MessageKind.BACK,
                    MessageKind.NETSPLIT,
                    MessageKind.NETJOIN,
                )

            kinds.forEachIndexed { index, kind ->
                val bufferId = bufDao.insert(buffer(networkId, "#ignored-$index"))
                msgDao.insertAll(
                    listOf(
                        message(
                            bufferId,
                            "meaningful-$index",
                            sender = "sender-$index",
                            serverTime = 100L + index,
                            dedupKey = "meaningful-$index",
                        ),
                        message(
                            bufferId,
                            "ignored-$kind",
                            sender = "system-$index",
                            serverTime = 1_000L + index,
                            dedupKey = "ignored-$index",
                            kind = kind,
                        ),
                    ),
                )
            }

            val rows = bufDao.observeChatList().first().associateBy(ChatListRow::displayName)
            kinds.indices.forEach { index ->
                val row = checkNotNull(rows["#ignored-$index"])
                assertEquals("meaningful-$index", row.lastMessageText)
                assertEquals("sender-$index", row.lastMessageSender)
                assertEquals(100L + index, row.lastMessageTime)
            }
        }

    @Test
    fun chatList_presenceOnlyBufferHasBlankPreviewAndNoActivity() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bufferId = bufDao.insert(buffer(networkId, "#only-ignored"))
            msgDao.insertAll(
                listOf(
                    message(bufferId, "join", serverTime = 100, dedupKey = "join", kind = MessageKind.JOIN),
                    message(bufferId, "part", serverTime = 200, dedupKey = "part", kind = MessageKind.PART),
                    message(bufferId, "quit", serverTime = 300, dedupKey = "quit", kind = MessageKind.QUIT),
                    message(bufferId, "away", serverTime = 350, dedupKey = "away", kind = MessageKind.AWAY),
                    message(bufferId, "back", serverTime = 375, dedupKey = "back", kind = MessageKind.BACK),
                    message(bufferId, "split", serverTime = 400, dedupKey = "split", kind = MessageKind.NETSPLIT),
                    message(bufferId, "join", serverTime = 500, dedupKey = "netjoin", kind = MessageKind.NETJOIN),
                ),
            )

            val row = bufDao.observeChatList().first().single()
            assertNull(row.lastMessageText)
            assertNull(row.lastMessageSender)
            assertNull(row.lastMessageTime)
        }

    @Test
    fun chatList_retainsOtherSystemKindsAndUsesOneSelectedRow() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val kinds =
                listOf(
                    MessageKind.KICK,
                    MessageKind.NICK,
                    MessageKind.MODE,
                    MessageKind.TOPIC,
                    MessageKind.ERROR,
                )

            kinds.forEachIndexed { index, kind ->
                val bufferId = bufDao.insert(buffer(networkId, "#retained-$index"))
                msgDao.insertAll(
                    listOf(
                        message(
                            bufferId,
                            "older-$index",
                            sender = "older-sender-$index",
                            serverTime = 100,
                            dedupKey = "older-$index",
                        ),
                        message(
                            bufferId,
                            "selected-$kind",
                            sender = "selected-sender-$index",
                            serverTime = 500,
                            dedupKey = "selected-$index",
                            kind = kind,
                        ),
                    ),
                )
            }

            val rows = bufDao.observeChatList().first().associateBy(ChatListRow::displayName)
            kinds.forEachIndexed { index, kind ->
                val row = checkNotNull(rows["#retained-$index"])
                assertEquals("selected-$kind", row.lastMessageText)
                assertEquals("selected-sender-$index", row.lastMessageSender)
                assertEquals(500L, row.lastMessageTime)
            }
        }

    @Test
    fun chatList_excludesServerBuffers_andSortsPinnedFirstThenActivity() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()

            val server = bufDao.insert(buffer(networkId, "server", type = BufferType.SERVER))
            val quiet = bufDao.insert(buffer(networkId, "#quiet"))
            val busy = bufDao.insert(buffer(networkId, "#busy"))
            val pinned = bufDao.insert(buffer(networkId, "#pinned", pinned = true))

            msgDao.insertAll(listOf(message(server, "motd", serverTime = 999, dedupKey = "s")))
            msgDao.insertAll(listOf(message(quiet, "hi", serverTime = 10, dedupKey = "q")))
            msgDao.insertAll(listOf(message(busy, "yo", serverTime = 500, dedupKey = "bz")))
            // pinned has no messages → still first via pinned sort.

            val rows = bufDao.observeChatList().first()
            // SERVER buffer excluded.
            assertNull(rows.firstOrNull { it.bufferId == server })
            assertEquals(3, rows.size)
            // pinned first, then by activity DESC (busy=500 > quiet=10).
            assertEquals(pinned, rows[0].bufferId)
            assertEquals(busy, rows[1].bufferId)
            assertEquals(quiet, rows[2].bufferId)
        }

    @Test
    fun advanceReadMarker_isMaxOnly() =
        runTest {
            val bufDao = db.bufferDao()
            val bid = bufDao.insert(buffer(networkId, "#chan"))

            bufDao.advanceReadMarker(bid, 200)
            assertEquals(200L, bufDao.observeById(bid)!!.readMarkerTime)

            // lower value ignored.
            bufDao.advanceReadMarker(bid, 100)
            assertEquals(200L, bufDao.observeById(bid)!!.readMarkerTime)

            // higher value advances.
            bufDao.advanceReadMarker(bid, 300)
            assertEquals(300L, bufDao.observeById(bid)!!.readMarkerTime)
        }

    @Test
    fun unmute_clears_local_backlog_without_advancing_remote_read_marker() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#muted"))
            bufDao.setMuted(bid, true)
            msgDao.insertAll(
                listOf(
                    message(bid, "one", serverTime = 100, dedupKey = "mute-1"),
                    message(bid, "two", serverTime = 200, dedupKey = "mute-2", hasMention = true),
                    message(bid, "three", serverTime = 300, dedupKey = "mute-3"),
                ),
            )
            assertEquals(
                3,
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .unreadCount,
            )

            bufDao.setMuted(bid, false)

            val unmuted = checkNotNull(bufDao.observeById(bid))
            assertEquals(null, unmuted.readMarkerTime)
            assertEquals(300L, unmuted.localUnreadFloorTime)
            assertEquals(
                0,
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .unreadCount,
            )

            msgDao.insertAll(listOf(message(bid, "new", serverTime = 400, dedupKey = "mute-4")))
            assertEquals(
                1,
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .unreadCount,
            )
        }

    @Test
    fun `unmute reports the backlog it hid and the reported floor restores it`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#undo"))
            bufDao.setMuted(bid, true)
            msgDao.insertAll(
                listOf(
                    message(bid, "one", serverTime = 100, dedupKey = "undo-1"),
                    message(bid, "two", serverTime = 200, dedupKey = "undo-2"),
                ),
            )

            val suppression = bufDao.setMuted(bid, false)

            assertEquals(MuteBacklogSuppression(bid, previousFloorTime = null), suppression)
            assertEquals(
                0,
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .unreadCount,
            )

            bufDao.restoreLocalUnreadFloor(bid, checkNotNull(suppression).previousFloorTime)

            assertNull(bufDao.observeById(bid)!!.localUnreadFloorTime)
            assertEquals(
                2,
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .unreadCount,
            )
        }

    @Test
    fun `undoing a later unmute restores the earlier floor, not the whole history`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#twice"))
            bufDao.setMuted(bid, true)
            msgDao.insertAll(listOf(message(bid, "old", serverTime = 100, dedupKey = "twice-1")))
            bufDao.setMuted(bid, false)

            bufDao.setMuted(bid, true)
            msgDao.insertAll(listOf(message(bid, "new", serverTime = 200, dedupKey = "twice-2")))
            val suppression = checkNotNull(bufDao.setMuted(bid, false))

            assertEquals(100L, suppression.previousFloorTime)
            bufDao.restoreLocalUnreadFloor(bid, suppression.previousFloorTime)

            // Only the second mute's backlog comes back; the first dismissal stays dismissed.
            assertEquals(
                1,
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .unreadCount,
            )
        }

    @Test
    fun `advertised activity is a forward-only high-water mark`() =
        runTest {
            val bufDao = db.bufferDao()
            val bid = bufDao.insert(buffer(networkId, "#advertised"))

            bufDao.advanceAdvertisedLatest(bid, 500)
            bufDao.advanceAdvertisedLatest(bid, 400)

            assertEquals(500L, bufDao.rawById(bid)!!.advertisedLatestTime)

            bufDao.advanceAdvertisedLatest(bid, 900)
            assertEquals(900L, bufDao.rawById(bid)!!.advertisedLatestTime)
        }

    @Test
    fun `advertised activity badges without re-sorting until real rows arrive`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val recentLocal = bufDao.insert(buffer(networkId, "#local"))
            val advertisedOnly = bufDao.insert(buffer(networkId, "#advertised"))
            val quiet = bufDao.insert(buffer(networkId, "#quiet"))
            msgDao.insertAll(
                listOf(
                    message(recentLocal, "here", serverTime = 500_000, dedupKey = "local-1"),
                    message(advertisedOnly, "old", serverTime = 100_000, dedupKey = "adv-0"),
                ),
            )

            // Discovery says #advertised moved more recently than anything this device holds, but the
            // advertisement may describe an event this device will never show. Until the fetch lands a
            // visible row, the row keeps its place and only wears the count-less dot.
            bufDao.advanceAdvertisedLatest(advertisedOnly, 900_000)

            val rows = bufDao.observeChatList().first()
            assertEquals(listOf(recentLocal, advertisedOnly, quiet), rows.map { it.bufferId })
            assertTrue(rows[1].advertisedUnread)
            // A room with no rows at all still sorts last rather than at epoch-zero.
            assertNull(rows.last().lastMessageTime)

            // The fetch delivers the advertised activity as an actual row: NOW the room is promoted.
            msgDao.insertAll(listOf(message(advertisedOnly, "arrived", serverTime = 900_000, dedupKey = "adv-1")))
            assertEquals(
                listOf(advertisedOnly, recentLocal, quiet),
                bufDao.observeChatList().first().map { it.bufferId },
            )
        }

    @Test
    fun `advertised unread stands in for a count and extinguishes itself`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#pending"))
            bufDao.advanceAdvertisedLatest(bid, 900_000)

            val pending = bufDao.observeChatList().first().single()
            assertTrue(pending.advertisedUnread)
            assertEquals(0, pending.unreadCount)

            // The pass fetches the page the advertisement was describing: the room now HAS the rows, so
            // the cue is gone and an ordinary unread count takes over. No write cleared the column.
            msgDao.insertAll(listOf(message(bid, "arrived", serverTime = 900_000, dedupKey = "pending-1")))
            val fetched = bufDao.observeChatList().first().single()
            assertFalse(fetched.advertisedUnread)
            assertEquals(1, fetched.unreadCount)
            assertEquals(900_000L, bufDao.rawById(bid)!!.advertisedLatestTime)
        }

    @Test
    fun `an advertisement inside the stored row's second is already reached`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#precision"))
            // The row this device stored carries a second-precision server-time tag; TARGETS advertised
            // the same event in milliseconds. Compared strictly, that difference is a permanent unread
            // dot on a room that is fully caught up — the reason every other advertised comparison in
            // the app is made at second granularity.
            msgDao.insertAll(listOf(message(bid, "tail", serverTime = 900_000, dedupKey = "precision-1")))
            bufDao.advanceAdvertisedLatest(bid, 900_640)

            assertFalse(
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )
        }

    @Test
    fun `reading past an advertisement extinguishes its cue without any rows arriving`() =
        runTest {
            val bufDao = db.bufferDao()
            val bid = bufDao.insert(buffer(networkId, "#read"))
            bufDao.advanceAdvertisedLatest(bid, 900_000)
            assertTrue(
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )

            // Mark-read past the advertised instant: whatever the server was describing is behind the
            // reader now, so there is nothing pending to hint at.
            bufDao.update(bufDao.rawById(bid)!!.copy(localReadAnchorTime = 900_000))

            assertFalse(
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )
        }

    @Test
    fun `clamping retires an advertisement the room can never show`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#unreachable"))
            // Everything this room can show is at t=500s; the newest SERVER event is a JOIN at t=900s
            // (or an event replay simply never returns). Mark-read cannot reach the advertisement — the
            // anchor lands on the newest LOCAL row — so without the clamp the dot is permanent.
            msgDao.insertAll(
                listOf(
                    message(bid, "tail", serverTime = 500_000, dedupKey = "clamp-1"),
                    message(bid, "joined", serverTime = 900_000, dedupKey = "clamp-2", kind = MessageKind.JOIN),
                ),
            )
            bufDao.advanceAdvertisedLatest(bid, 900_000)
            assertTrue(
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )

            bufDao.clampAdvertisedLatestToVisible(bid, provenLatest = 900_000)

            val row = bufDao.observeChatList().first().single()
            assertFalse(row.advertisedUnread)
            // Clamped onto the room's newest VISIBLE row, matching the truth the list shows.
            assertEquals(500_000L, bufDao.rawById(bid)!!.advertisedLatestTime)
            assertEquals(500_000L, row.lastMessageTime)
        }

    @Test
    fun `clamping a room with nothing to show clears the advertisement entirely`() =
        runTest {
            val bufDao = db.bufferDao()
            val bid = bufDao.insert(buffer(networkId, "#empty"))
            bufDao.advanceAdvertisedLatest(bid, 900_000)

            bufDao.clampAdvertisedLatestToVisible(bid, provenLatest = 900_000)

            assertNull(bufDao.rawById(bid)!!.advertisedLatestTime)
            assertFalse(
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )
        }

    @Test
    fun `clamping leaves a converged advertisement alone`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#pending-clamp"))
            msgDao.insertAll(listOf(message(bid, "tail", serverTime = 900_000, dedupKey = "keep-1")))
            // Within the stored row's second: the cue is already dark, and the high-water mark must not
            // be rewritten (and the chat list must not be invalidated) for a room that is converged.
            bufDao.advanceAdvertisedLatest(bid, 900_400)

            bufDao.clampAdvertisedLatestToVisible(bid, provenLatest = 900_400)

            assertEquals(900_400L, bufDao.rawById(bid)!!.advertisedLatestTime)
        }

    @Test
    fun `clamping never lowers a value the caller's response says nothing about`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#newer-report"))
            msgDao.insertAll(listOf(message(bid, "tail", serverTime = 500_000, dedupKey = "newer-1")))
            // A newer discovery already advertised t=900s. A pass settling an OLDER advertisement has
            // disproved nothing about it, and lowering it here would be the backwards walk the
            // forward-only write exists to prevent.
            bufDao.advanceAdvertisedLatest(bid, 900_000)

            bufDao.clampAdvertisedLatestToVisible(bid, provenLatest = 600_000)

            assertEquals(900_000L, bufDao.rawById(bid)!!.advertisedLatestTime)
            assertTrue(
                bufDao
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )
        }

    @Test
    fun `unmute stays silent when the floor advance hides nothing`() =
        runTest {
            val bufDao = db.bufferDao()
            val msgDao = db.messageDao()
            val bid = bufDao.insert(buffer(networkId, "#quiet"))
            msgDao.insertAll(listOf(message(bid, "seen", serverTime = 100, dedupKey = "quiet-1")))
            // The floor already covers the newest message, so the advance is a no-op with nothing to lose.
            bufDao.update(bufDao.rawById(bid)!!.copy(localUnreadFloorTime = 100))
            bufDao.setMuted(bid, true)

            assertNull(bufDao.setMuted(bid, false))
            // Muting an empty conversation has nothing to report either.
            val empty = bufDao.insert(buffer(networkId, "#empty"))
            assertNull(bufDao.setMuted(empty, false))
            assertNull(bufDao.setMuted(bid, true))
        }
}
