package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.repo.SUPPRESS_UNREAD
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadAnchorResolutionTest {
    private lateinit var db: MotdDatabase
    private var networkId = 0L
    private var roomId = 0L

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            networkId = db.networkDao().insert(network())
            roomId = db.bufferDao().insert(buffer(networkId, "#room"))
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun `stale notification tuple resolves coalesced event at its current time`() =
        runTest {
            val loserId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(roomId, "same", serverTime = 100, dedupKey = "loser")),
                    ).single()
            val winnerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(roomId, "same", serverTime = 500, dedupKey = "winner")),
                    ).single()
            val winner = db.messageDao().byId(winnerId)!!
            db.messageDao().update(winner.copy(serverTime = 700))
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
            db.messageDao().deleteById(loserId)

            val resolved = resolveAndAdvanceCurrentReadTarget(db, roomId, TimelineAnchor(100, loserId))

            assertEquals(TimelineAnchor(700, winnerId), resolved?.anchor)
            assertEquals(700L, db.bufferDao().observeById(roomId)?.localReadAnchorTime)
            assertEquals(winnerId, db.bufferDao().observeById(roomId)?.localReadAnchorEventId)
            assertEquals(TimelineAnchor(700, winnerId), resolveLatestNotificationAnchor(db, roomId, listOf(loserId)))
            assertEquals(700L, resolved?.authoritative?.timestamp)
            assertEquals(winnerId, resolved?.authoritative?.eventId)
        }

    @Test
    fun viewportDoesNotChooseAnUnreadSuppressedLocalOrWireAnchor() =
        runTest {
            val (ordinaryId, tiedQuietId, laterQuietId, localId) =
                db.messageDao().insertAll(
                    listOf(
                        message(roomId, "ordinary", serverTime = 500, dedupKey = "ordinary").copy(timelineOrder = 1),
                        message(roomId, "quiet at tie", serverTime = 500, dedupKey = "tied").copy(
                            timelineOrder = 2,
                            suppressedSignals = SUPPRESS_UNREAD,
                        ),
                        message(roomId, "quiet later", serverTime = 700, dedupKey = "later").copy(suppressedSignals = SUPPRESS_UNREAD),
                        message(roomId, "local", serverTime = 800, dedupKey = "local").copy(serverTimeAuthoritative = false),
                    ),
                )
            val clamped = resolveCurrentReadTarget(db, roomId, TimelineAnchor(500, tiedQuietId), fromViewport = true)
            assertEquals(TimelineAnchor(500, ordinaryId, 1), clamped?.anchor)
            assertEquals(ordinaryId, clamped?.authoritative?.eventId)

            db.bufferDao().seedHistoryUnreadFloor(roomId, 500)
            assertNull(resolveAndAdvanceCurrentReadTarget(db, roomId, TimelineAnchor(500, tiedQuietId), fromViewport = true))
            assertNull(db.bufferDao().observeById(roomId)?.localReadAnchorEventId)
            val baseline = resolveAndAdvanceCurrentReadTarget(db, roomId, TimelineAnchor(500, ordinaryId), fromViewport = true)
            assertEquals(TimelineAnchor(500, ordinaryId, 1), baseline?.anchor)
            assertEquals(ordinaryId, baseline?.authoritative?.eventId)
            assertNull(resolveAndAdvanceCurrentReadTarget(db, roomId, TimelineAnchor(500, tiedQuietId), fromViewport = true))
            assertNull(resolveAndAdvanceCurrentReadTarget(db, roomId, TimelineAnchor(700, laterQuietId), fromViewport = true))
            assertEquals(ordinaryId, db.bufferDao().observeById(roomId)?.localReadAnchorEventId)
            assertEquals(500L, db.bufferDao().observeById(roomId)?.localUnreadFloorTime)
            val acknowledged = db.bufferDao().storedReadMarkers(networkId).single()
            assertEquals(ordinaryId, acknowledged.eventId)

            val manual = resolveCurrentReadTarget(db, roomId, TimelineAnchor(700, laterQuietId))
            assertEquals(laterQuietId, manual?.anchor?.eventId)
            assertEquals(laterQuietId, manual?.authoritative?.eventId)
            val fromLocal = resolveAndAdvanceCurrentReadTarget(db, roomId, TimelineAnchor(800, localId), fromViewport = true)
            assertEquals(localId, fromLocal?.anchor?.eventId)
            assertEquals(laterQuietId, fromLocal?.authoritative?.eventId)
            assertEquals(localId, db.bufferDao().observeById(roomId)?.localReadAnchorEventId)
            val throughLaterRead = db.bufferDao().storedReadMarkers(networkId).single()
            assertEquals(laterQuietId, throughLaterRead.eventId)
        }

    @Test
    fun `missing zero stale and wrong-room event anchors are rejected`() =
        runTest {
            val currentId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(roomId, "current", serverTime = 700, dedupKey = "current")),
                    ).single()
            db.bufferDao().advanceLocalReadAnchor(roomId, 700, currentId)
            val olderId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(roomId, "older", serverTime = 600, dedupKey = "older")),
                    ).single()
            val otherRoomId = db.bufferDao().insert(buffer(networkId, "#other"))
            val otherId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(otherRoomId, "other", serverTime = 800, dedupKey = "other")),
                    ).single()

            assertNull(resolveCurrentReadTarget(db, roomId, TimelineAnchor(0, currentId)))
            assertNull(resolveCurrentReadTarget(db, roomId, TimelineAnchor(700, 0)))
            assertNull(resolveCurrentReadTarget(db, roomId, TimelineAnchor(700, Long.MAX_VALUE - 1)))
            assertNull(resolveCurrentReadTarget(db, roomId, TimelineAnchor(600, olderId)))
            assertNull(resolveCurrentReadTarget(db, roomId, TimelineAnchor(800, otherId)))
        }
}
