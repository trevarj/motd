package io.github.trevarj.motd.data.db

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Startup notification recovery must stay bounded by the recovery window measured from the newest
 * stored event, so a large archive never turns process start into a full `messages` scan.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationRecoveryDaoTest {
    private lateinit var db: MotdDatabase
    private var networkId: Long = 0
    private var bufferId: Long = 0

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            networkId = db.networkDao().insert(network())
            bufferId = db.bufferDao().insert(buffer(networkId, "#chan"))
        }

    @After
    fun tearDown() = db.close()

    private suspend fun liveMention(
        serverTime: Long,
        text: String,
    ): TimelineEventId {
        val dao = db.canonicalTimelineDao()
        val id = dao.insertEvent(message(bufferId, text, serverTime = serverTime, dedupKey = text, hasMention = true))
        dao.insertObservation(
            EventObservationEntity(
                networkId = networkId,
                timelineEventId = id,
                origin = ObservationOrigin.LIVE,
                connectionGeneration = null,
                receiveOrder = id,
                batchId = null,
                timeProvenance = TimeProvenance.SERVER_TAG,
                semanticFingerprint = text.toByteArray(),
                batchExactOrdinal = null,
                observedAt = serverTime,
            ),
        )
        return id
    }

    @Test
    fun recoveryOnlyConsidersEventsWithinTheWindowOfTheNewestOne() =
        runTest {
            val dao = db.canonicalTimelineDao()
            val stale = liveMention(1_000, "stale")
            val recent = liveMention(1_000_000, "recent")
            assertEquals(1, dao.claimNotification(stale, "dead-process"))
            assertEquals(1, dao.claimNotification(recent, "dead-process"))

            dao.releaseInterruptedNotificationClaims("me", window = 10_000, maxRows = Int.MAX_VALUE)

            // Only the claim inside the window is released; the stale one stays claimed and is never
            // re-presented, and pendingNotifications ignores it even if it were released.
            assertEquals(listOf(recent), dao.pendingNotifications(10, window = 10_000, maxRows = Int.MAX_VALUE).map { it.id })
            assertEquals(0, dao.claimNotification(stale, "me"))
            assertEquals(1, dao.claimNotification(recent, "me"))

            // An unbounded window proves the window, not the claim state, gated the stale row.
            dao.releaseInterruptedNotificationClaims("me", window = Long.MAX_VALUE / 2, maxRows = Int.MAX_VALUE)
            assertEquals(1, dao.claimNotification(stale, "me"))
        }

    @Test
    fun recoveryNeverLooksPastTheNewestMaxRowsEvenInsideTheTimeWindow() =
        runTest {
            val dao = db.canonicalTimelineDao()
            // Three events one millisecond apart: all inside any sane time window.
            val oldest = liveMention(1_000, "oldest")
            val middle = liveMention(1_001, "middle")
            val newest = liveMention(1_002, "newest")
            listOf(oldest, middle, newest).forEach { assertEquals(1, dao.claimNotification(it, "dead-process")) }

            dao.releaseInterruptedNotificationClaims("me", window = Long.MAX_VALUE / 2, maxRows = 2)

            assertEquals(listOf(middle, newest), dao.pendingNotifications(10, window = Long.MAX_VALUE / 2, maxRows = 2).map { it.id })
            assertEquals(0, dao.claimNotification(oldest, "me"))
        }
}
