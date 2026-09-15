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
    ): TimelineEventId =
        observed(
            message(bufferId, text, serverTime = serverTime, dedupKey = text, hasMention = true)
                .copy(notificationEligible = true, notificationEligibilityResolved = true),
            ObservationOrigin.LIVE,
        )

    private suspend fun observed(
        event: TimelineEventEntity,
        origin: ObservationOrigin,
    ): TimelineEventId {
        val dao = db.canonicalTimelineDao()
        val id = dao.insertEvent(event)
        dao.insertObservation(
            EventObservationEntity(
                networkId = networkId,
                timelineEventId = id,
                origin = origin,
                connectionGeneration = null,
                receiveOrder = id,
                batchId = null,
                timeProvenance = TimeProvenance.SERVER_TAG,
                semanticFingerprint = event.text.toByteArray(),
                batchExactOrdinal = null,
                observedAt = event.serverTime,
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

    @Test
    fun recoveryUsesFrozenEligibilityAndTheLivePushBoundaryWhileHistoryRemainsContext() =
        runTest {
            val queryId = db.bufferDao().insert(buffer(networkId, "alice").copy(type = BufferType.QUERY))
            val eligible =
                message(bufferId, "eligible", serverTime = 1_000, dedupKey = "eligible")
                    .copy(notificationEligible = true, notificationEligibilityResolved = true)
            val liveAll = observed(eligible.copy(text = "live all"), ObservationOrigin.LIVE)
            val pushAll = observed(eligible.copy(text = "push all"), ObservationOrigin.PUSH)
            val history = observed(eligible.copy(text = "history", notificationEligibilityResolved = false), ObservationOrigin.HISTORY)
            val unresolved = observed(eligible.copy(text = "unresolved", notificationEligibilityResolved = false), ObservationOrigin.LIVE)
            observed(eligible.copy(text = "frozen off", hasMention = true, notificationEligible = false), ObservationOrigin.LIVE)
            val pushMention = observed(eligible.copy(text = "push mention", hasMention = true), ObservationOrigin.PUSH)
            val pushDm = observed(eligible.copy(bufferId = queryId, text = "push dm"), ObservationOrigin.PUSH)
            val pushWatch = observed(eligible.copy(text = "push watched", notificationWatched = true), ObservationOrigin.PUSH)
            observed(eligible.copy(text = "self", isSelf = true), ObservationOrigin.LIVE)
            observed(eligible.copy(text = "failed", failed = true), ObservationOrigin.LIVE)

            assertEquals(
                listOf(liveAll, pushMention, pushDm, pushWatch),
                db.canonicalTimelineDao().pendingNotifications(20, window = Long.MAX_VALUE / 2, maxRows = Int.MAX_VALUE).map { it.id },
            )
            assertEquals(
                listOf(pushWatch, pushMention, unresolved, history, pushAll, liveAll),
                db.messageDao().recentNotifiable(bufferId, Long.MIN_VALUE, Long.MIN_VALUE, -1, 20).map { it.id },
            )
        }
}
