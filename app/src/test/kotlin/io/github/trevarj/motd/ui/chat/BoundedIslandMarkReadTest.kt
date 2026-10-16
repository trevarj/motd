package io.github.trevarj.motd.ui.chat

import androidx.paging.PagingSource
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.repo.MESSAGE_PAGING_CONFIG
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.service.resolveAndAdvanceCurrentReadTarget
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

/**
 * The viewport mark-read contract, over a real store and the real PagingSource.
 *
 * The defect this file exists for is broadcast-visible: `ChatContent`'s mark-read effect reads
 * at-bottom from the CURRENT paging snapshot but acknowledges `rawNewestAnchor`, the newest row of
 * the whole ROOM, and an advance there uploads a MARKREAD that clears unread on every other client.
 * It was first found inside a bounded deep-jump island, whose index 0 was the island's bottom rather
 * than the room's, and was gated by a second flag derived from that island's window bounds.
 *
 * Islands are retired — there is one unbounded timeline — and the second flag is deliberately gone
 * rather than pinned to `false`, because the same disagreement reappears through a different hole.
 * A deep jump is now a global-index jump: the viewport settles a few hundred indices deep, Paging's
 * `maxSize` drops the newest pages, and everything below the viewport is an unloaded placeholder. So
 * the whole contract now rests on ONE predicate, and these tests state it as four invariants:
 *
 *  1. [deepParkedViewportWithPlaceholdersBelowNeverAcknowledges] — unknown is not "already read".
 *  2. [aViewportWhoseMaterializedRowsReachIndexZeroAcknowledges] — the honest bottom still acks.
 *  3. [aMaterializedIgnoredTailIsStillSkippedPastAndAcknowledged] — the ignorable-tail rule survives.
 *  4. [recentWindowBottomStillMarksTheWholeRoomRead] — sitting at the bottom of a room with an
 *     unfilled interior gap acknowledges `rawNewestAnchor`, INCLUDING the interval the gap covers
 *     that was never stored. That is today's behavior and it is pinned deliberately.
 *
 * Each runs the real gate ([shouldMarkReadFromViewport]) over a window materialized by the real
 * PagingSource, then lets the real [resolveAndAdvanceCurrentReadTarget] run exactly when the gate
 * permits it.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(androidx.paging.ExperimentalPagingApi::class)
class BoundedIslandMarkReadTest {
    private lateinit var db: MotdDatabase
    private var networkId = 0L
    private var roomId = 0L
    private val spec = MessageVisibilitySpec()
    private val policy = MessageVisibilityPolicy(spec)

    // Older island (below the gap).
    private var older1 = 0L
    private var older2 = 0L

    // Newer island (above the gap).
    private var newer1 = 0L
    private var newest = 0L

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            networkId = db.networkDao().insert(network())
            roomId = db.bufferDao().insert(buffer(networkId, "#chan"))
            older1 = insert("old-1", serverTime = 1_000)
            older2 = insert("old-2", serverTime = 1_010)
            newer1 = insert("new-1", serverTime = 5_000)
            newest = insert("new-2", serverTime = 5_010)
            // A retained gap between the two islands, exactly as reconnect catch-up records one. The
            // interval it covers — (1_010, 5_000) — is NEVER stored by this fixture.
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = roomId,
                    olderMsgid = null,
                    olderServerTime = 1_010,
                    olderEventId = older2,
                    olderTimelineOrder = older2,
                    newerMsgid = null,
                    newerServerTime = 5_000,
                    newerEventId = newer1,
                    newerTimelineOrder = newer1,
                ),
            )
        }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(
        text: String,
        serverTime: Long,
        sender: String = "alice",
    ): Long =
        db
            .messageDao()
            .insertAll(
                listOf(message(roomId, text, sender = sender, serverTime = serverTime, dedupKey = text)),
            ).single()

    /**
     * One Paging snapshot, presented the way `LazyPagingItems` presents it: `itemCount` spans the
     * whole query and `peek` returns null for every index outside the loaded page. That null is the
     * placeholder the predicate has to refuse to treat as read.
     */
    private class Snapshot(
        val itemCount: Int,
        private val firstLoadedIndex: Int,
        private val loaded: List<MessageEntity>,
    ) {
        val peek: (Int) -> MessageEntity? = { index ->
            loaded.getOrNull(index - firstLoadedIndex)
        }
    }

    /** Load one page at [key] with placeholders, exactly as the shipped Pager configures it. */
    private suspend fun snapshot(
        key: Int? = null,
        loadSize: Int = 50,
    ): Snapshot {
        val page =
            db.messageDao().pagingSource(messagePagingQuery(roomId, spec)).load(
                PagingSource.LoadParams.Refresh(key = key, loadSize = loadSize, placeholdersEnabled = true),
            ) as PagingSource.LoadResult.Page<Int, MessageEntity>
        val before = page.itemsBefore.coerceAtLeast(0)
        return Snapshot(before + page.data.size + page.itemsAfter.coerceAtLeast(0), before, page.data)
    }

    private suspend fun unreadCount(): Int =
        db.messageDao().rawCount(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM buffers b JOIN messages m ON m.bufferId = b.id " +
                    "WHERE b.id = ? AND m.serverTime > COALESCE(b.localReadAnchorTime, 0)",
                arrayOf(roomId),
            ),
        )

    /** Run the production sequence: the gate decides, then the real read-target advance. */
    private suspend fun acknowledgeIfPermitted(atBottom: Boolean): Boolean {
        val ackable =
            shouldMarkReadFromViewport(
                atBottom = atBottom,
                initialPositionSettled = true,
                viewportReadEnabled = true,
            )
        if (ackable) {
            val rawNewest = checkNotNull(MessageVisibilityReader(db).latestRawAnchor(roomId))
            resolveAndAdvanceCurrentReadTarget(db, roomId, rawNewest)
        }
        return ackable
    }

    @Test
    fun deepParkedViewportWithPlaceholdersBelowNeverAcknowledges() =
        runTest {
            // A notification deep jump into a long room. The jump is a global index, the entry scroll
            // settles the viewport there, and `initialPositionSettled` is therefore already true — the
            // gate's other preconditions are all satisfied. What must stop the acknowledgement is the
            // fact that the user has been shown nothing below index 250.
            (1..400).forEach { insert("row$it", serverTime = 10_000L + it) }
            val targetIndex = 250
            val snapshot = snapshot(key = targetIndex, loadSize = MESSAGE_PAGING_CONFIG.pageSize)

            // The scenario really is the placeholder one, not a materialized-and-ignorable tail: every
            // index below the viewport is unknown. This is what `maxSize` leaves under a deep jump.
            assertTrue(
                "the fixture must park the viewport above unloaded rows",
                (0 until targetIndex).all { snapshot.peek(it) == null },
            )
            assertTrue("the target row itself is materialized", snapshot.peek(targetIndex) != null)

            val atBottom =
                isAtEffectiveBottom(
                    firstVisibleIndex = targetIndex,
                    firstVisibleOffset = 0,
                    itemCount = snapshot.itemCount,
                    peek = snapshot.peek,
                    policy = policy,
                )
            assertFalse("unloaded rows below the viewport are not 'already read'", atBottom)

            assertFalse(acknowledgeIfPermitted(atBottom))
            assertNull(
                "a deep-parked viewport must not advance the durable anchor",
                db.bufferDao().observeById(roomId)?.localReadAnchorTime,
            )
            assertEquals("nothing in the room is silently marked read", 404, unreadCount())
        }

    @Test
    fun aViewportWhoseMaterializedRowsReachIndexZeroAcknowledges() =
        runTest {
            // The counterpart. Same shape of room, but the viewport sits where the newest rows really
            // are loaded, so every index below it is a row the timeline has presented.
            (1..400).forEach { insert("row$it", serverTime = 10_000L + it) }
            val snapshot = snapshot(key = null, loadSize = MESSAGE_PAGING_CONFIG.initialLoadSize)
            val firstVisible = 3
            assertTrue(
                "the fixture must materialize every row below the viewport",
                (0 until firstVisible).all { snapshot.peek(it) != null },
            )

            val atBottom =
                isAtEffectiveBottom(
                    firstVisibleIndex = firstVisible,
                    firstVisibleOffset = 0,
                    itemCount = snapshot.itemCount,
                    peek = snapshot.peek,
                    policy = policy,
                )
            // Rows 0..2 are meaningful and below the viewport, so this is NOT the bottom either — the
            // predicate is unchanged for materialized rows.
            assertFalse(atBottom)

            val atRealBottom =
                isAtEffectiveBottom(
                    firstVisibleIndex = 0,
                    firstVisibleOffset = 0,
                    itemCount = snapshot.itemCount,
                    peek = snapshot.peek,
                    policy = policy,
                )
            assertTrue("index 0 of the one unbounded timeline is the room's newest row", atRealBottom)

            assertTrue(acknowledgeIfPermitted(atRealBottom))
            assertEquals(10_400L, db.bufferDao().observeById(roomId)?.localReadAnchorTime)
            assertEquals("reaching the real bottom clears unread", 0, unreadCount())
        }

    @Test
    fun aMaterializedIgnoredTailIsStillSkippedPastAndAcknowledged() =
        runTest {
            // The rule the hardened predicate must NOT have broken: rows the policy ignores are settled
            // by definition, so a viewport above temporarily revealed Hidden rows is still at bottom
            // and still acknowledges the room's newest raw row. Collapsed placeholders are not ignored.
            val foolSpec = MessageVisibilitySpec(fools = setOf("fool"), foolsMode = FoolsMode.HIDE, revealHiddenFools = true)
            val foolPolicy = MessageVisibilityPolicy(foolSpec)
            insert("noise-1", serverTime = 6_000, sender = "fool")
            insert("noise-2", serverTime = 6_010, sender = "fool")
            val snapshot = snapshot()

            assertEquals(
                listOf("noise-2", "noise-1", "new-2", "new-1", "old-2", "old-1"),
                (0 until snapshot.itemCount).map { snapshot.peek(it)?.text },
            )

            val atBottom =
                isAtEffectiveBottom(
                    firstVisibleIndex = 2,
                    firstVisibleOffset = 0,
                    itemCount = snapshot.itemCount,
                    peek = snapshot.peek,
                    policy = foolPolicy,
                )
            assertTrue("a materialized ignored tail is already settled", atBottom)

            assertTrue(acknowledgeIfPermitted(atBottom))
            // The raw tail is what gets acknowledged: only the room's newest stored row retires it.
            assertEquals(6_010L, db.bufferDao().observeById(roomId)?.localReadAnchorTime)
            assertEquals(0, unreadCount())
        }

    @Test
    fun recentWindowBottomStillMarksTheWholeRoomRead() =
        runTest {
            // Today's behavior, pinned deliberately rather than discovered later. The room has an
            // unfilled interior gap whose interval — (1_010, 5_000) — was never stored, and sitting at
            // the bottom acknowledges `rawNewestAnchor`, a timestamp anchor ABOVE it. IRC read markers
            // are timestamp-only, so that ack necessarily covers the gap's whole interval: the user is
            // saying "I am current in this room", not "I read each of these rows".
            val snapshot = snapshot()
            assertEquals(
                "both islands are presented, with the seam drawn between them",
                listOf("new-2", "new-1", "old-2", "old-1"),
                (0 until snapshot.itemCount).map { snapshot.peek(it)?.text },
            )
            assertEquals("the gap is still open", 1, db.historyGapDao().forRoom(roomId).size)

            val atBottom =
                isAtEffectiveBottom(
                    firstVisibleIndex = 0,
                    firstVisibleOffset = 0,
                    itemCount = snapshot.itemCount,
                    peek = snapshot.peek,
                    policy = policy,
                )
            assertTrue(atBottom)

            assertTrue(acknowledgeIfPermitted(atBottom))
            assertEquals(5_010L, db.bufferDao().observeById(roomId)?.localReadAnchorTime)
            assertEquals("reaching the real bottom clears unread", 0, unreadCount())
            // The gap itself is untouched by the acknowledgement: the seam stays tappable, so filling it
            // later restores the history even though the marker already sits above it.
            assertTrue(
                db
                    .historyGapDao()
                    .forRoom(roomId)
                    .single()
                    .recoverable,
            )
        }

    /**
     * The resume leg of the same defect: the mark-read effect is keyed on `viewportReadEnabled`, so
     * coming back restarts it, and by then the backlog that arrived while the screen was paused is
     * already in `rawNewestAnchor` and in the Paging snapshot while nothing has measured it.
     */
    @Test
    fun aResumedViewportAcknowledgesTheRenderedBottomNotThePausedBacklog() =
        runTest {
            val snapshot = snapshot()

            // What the timeline was showing, proved by the laid-out row's own key rather than by an
            // index that a later prepend would silently reassign.
            val rendered =
                renderedBottomAnchor(
                    renderedIndex = 0,
                    renderedKey = checkNotNull(snapshot.peek(0)).id,
                    itemCount = snapshot.itemCount,
                    peek = snapshot.peek,
                    policy = policy,
                )
            assertEquals(newest, rendered?.eventId)

            // Two messages land while the screen is paused. No measure pass runs for them.
            insert("paused-1", serverTime = 6_000)
            val pausedNewest = insert("paused-2", serverTime = 6_010)
            val rawNewest = MessageVisibilityReader(db).latestRawAnchor(roomId)
            assertEquals(pausedNewest, rawNewest?.eventId)

            // At-bottom is still measured against the pre-pause layout, so the gate itself still opens:
            // the anchor, not the gate, is what stops the unseen backlog being acknowledged.
            val atBottom =
                isAtEffectiveBottom(
                    firstVisibleIndex = 0,
                    firstVisibleOffset = 0,
                    itemCount = snapshot.itemCount,
                    peek = snapshot.peek,
                    policy = policy,
                )
            assertTrue(
                shouldMarkReadFromViewport(
                    atBottom = atBottom,
                    initialPositionSettled = true,
                    viewportReadEnabled = true,
                ),
            )
            val acknowledged =
                checkNotNull(
                    viewportMarkReadAnchor(rawNewest = rawNewest, renderedNewest = rendered, resumed = true),
                )
            assertEquals(newest, acknowledged.eventId)

            resolveAndAdvanceCurrentReadTarget(db, roomId, acknowledged)
            assertEquals(5_010L, db.bufferDao().observeById(roomId)?.localReadAnchorTime)
            assertEquals("the backlog that arrived while paused stays unread", 2, unreadCount())
        }
}
