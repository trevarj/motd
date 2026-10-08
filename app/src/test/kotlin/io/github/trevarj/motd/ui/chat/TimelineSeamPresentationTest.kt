package io.github.trevarj.motd.ui.chat

import androidx.paging.PagingSource
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.ui.components.HistoryGapState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The seam pipeline end to end — repository → ViewModel state → [rowSeam] → divider — over a real
 * store and the real paging query.
 *
 * This file used to assert the opposite. While the window clamped AT the gap, the row adjacent to a
 * seam had no materialized older neighbor, `seamAbove` abstained on the undecidable slot, and
 * NOTHING could render however correctly it was wired. The timeline is unbounded now, so those cases
 * invert: the seam is drawn, once, between the two islands.
 *
 * Deliberately kept: a slice with nothing materialized above it renders no seam. That is the live
 * statement of the abstention rule, not leftover dark coverage.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(androidx.paging.ExperimentalPagingApi::class)
class TimelineSeamPresentationTest {
    private lateinit var db: MotdDatabase
    private var roomId = 0L

    private val spec = MessageVisibilitySpec()

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
            val networkId = db.networkDao().insert(network())
            roomId = db.bufferDao().insert(buffer(networkId, "#chan"))
            older1 = insert("old-1", serverTime = 1_000)
            older2 = insert("old-2", serverTime = 1_010)
            newer1 = insert("new-1", serverTime = 5_000)
            newest = insert("new-2", serverTime = 5_010)
            // A retained gap between the two islands, exactly as reconnect catch-up records one. Both
            // edges resolve exactly, so nothing here depends on the cohort-sentinel rules.
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
    ): Long =
        db
            .messageDao()
            .insertAll(
                listOf(message(roomId, text, serverTime = serverTime, dedupKey = text)),
            ).single()

    /**
     * A presence row for an actor who never speaks in this room, so [PresenceMode.SMART] — the
     * default — excludes it from the presented timeline while it stays durable in Room.
     */
    private suspend fun insertPresence(
        text: String,
        serverTime: Long,
        kind: MessageKind,
    ): Long =
        db
            .messageDao()
            .insertAll(
                listOf(
                    message(
                        roomId,
                        text,
                        sender = "lurker",
                        serverTime = serverTime,
                        dedupKey = text,
                        kind = kind,
                    ),
                ),
            ).single()

    private fun repository() =
        MessageRepositoryImpl(
            bufferDao = db.bufferDao(),
            networkIdentityDao = db.networkIdentityDao(),
            db = db,
            reactionDao = db.reactionDao(),
            mediatorFactory = ChatHistoryMediatorFactory { _, _, _ -> error("paging not exercised") },
            historyGapDao = db.historyGapDao(),
        )

    /** Materialize the timeline exactly as the screen does (reverse layout: index 0 = newest). */
    private suspend fun loadWindow(visibility: MessageVisibilitySpec = spec): List<MessageEntity> =
        (
            db.messageDao().pagingSource(messagePagingQuery(roomId, visibility)).load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = true),
            ) as PagingSource.LoadResult.Page<Int, MessageEntity>
        ).data

    /**
     * Walk the window the way [MessageList] composes it: index 0 is newest, the older neighbor is
     * the NEXT index, and the last row's neighbor is null because nothing below it is materialized.
     * Returns `(row text, rendered seam)` for every slot that draws one.
     */
    private fun renderedSeams(
        rows: List<MessageEntity>,
        seams: TimelineSeamState,
    ): List<Pair<String, RowSeam>> =
        rows.mapIndexedNotNull { index, row ->
            rowSeam(row, rows.getOrNull(index + 1), seams)?.let { row.text to it }
        }

    /**
     * The steady state of a room the reader is looking at: a transport is up and nothing has failed,
     * so a recoverable seam nothing is fetching renders idle. `historyUnavailable` defaults to true
     * (a caller that models nothing gets a tappable seam rather than an endless spinner), which is
     * not the case these placement tests are about.
     */
    private suspend fun seamState(
        filling: Set<Long> = emptySet(),
        failed: Set<Long> = emptySet(),
        visibility: MessageVisibilitySpec = spec,
    ) = TimelineSeamState(
        seams = repository().observeTimelineSeams(roomId, visibility).first(),
        filling = filling,
        historyUnavailable = false,
        failed = failed,
    )

    // --- the wiring exists ------------------------------------------------------------------------

    @Test
    fun `the repository publishes a seam for the stored gap`() =
        runTest {
            val state = seamState()

            // Everything below asserts about placement; without this the dark tests could pass simply
            // because nothing ever reached the UI.
            val seam = state.seams.single()
            assertEquals(newer1, seam.position.eventId)
            assertTrue("a fillable gap must publish a recoverable seam", seam.recoverable)
        }

    // --- lit: the seam renders in the real timeline ------------------------------------------------

    @Test
    fun `the timeline renders the seam between the two islands`() =
        runTest {
            // The inversion, stated at its cause: the presented query carries no boundary at all, so the
            // far side of the gap is materialized and the seam has a decidable slot. Reintroducing a
            // boundary anywhere upstream puts this file straight back to rendering nothing.
            val rows = loadWindow()
            assertEquals(listOf("new-2", "new-1", "old-2", "old-1"), rows.map { it.text })

            val rendered = renderedSeams(rows, seamState())

            // Exactly one slot, above the first row at or after the gap — not above the last row on the
            // far side, and not once per island.
            assertEquals(listOf("new-1"), rendered.map { it.first })
            // Nothing is fetching this gap in this scenario, so the honest divider is idle, not spinning.
            assertEquals(HistoryGapState.Idle, rendered.single().second.state)
            assertEquals(
                newer1,
                seamState()
                    .seams
                    .single()
                    .position.eventId,
            )

            // The abstention rule is unchanged and still applies at the BOTTOM of the loaded list: a
            // null older neighbor is an unmaterialized placeholder, not a proven edge.
            assertNull(
                "an unmaterialized older neighbor makes the slot undecidable, so the seam abstains",
                rowSeam(rows.last(), null, seamState()),
            )
        }

    @Test
    fun `a deep-jump viewport parked below the gap renders no seam of its own`() =
        runTest {
            // A notification/search/permalink jump below the gap. The timeline is one list, so the jump
            // is a global index into it and the rows the viewport materializes around that index all sit
            // on the older side of the seam. Modelled the way Paging presents it: the far pages are gone
            // (maxSize), so the slice starts at the older island with nothing materialized above it.
            val rows = loadWindow().takeLast(2)
            assertEquals(listOf("old-2", "old-1"), rows.map { it.text })

            assertEquals(emptyList<Pair<String, RowSeam>>(), renderedSeams(rows, seamState()))
        }

    @Test
    fun `no rendered slot is added or moved by the seam wiring`() =
        runTest {
            val repository = repository()
            val rows = loadWindow()

            // A seam is drawn INSIDE its row's composition, never as its own list item, so the
            // "countNewerThan == list index" contract ChatJumpResolver depends on is untouched: every
            // row's index in the presented window is still exactly its count of strictly-newer rows.
            rows.forEachIndexed { index, row ->
                assertEquals(
                    "row ${row.text} must keep its jump index",
                    index,
                    repository.countNewerThan(roomId, row.serverTime, row.id, spec),
                )
            }
        }

    // --- the state each seam carries ---------------------------------------------------------------

    @Test
    fun `a fill in flight renders that seam as loading`() =
        runTest {
            val rows = loadWindow()
            val gapId = seamState().seams.single().gapId

            val rendered = renderedSeams(rows, seamState(filling = setOf(gapId))).single()

            assertEquals("new-1", rendered.first)
            assertEquals(gapId, rendered.second.gapId)
            assertEquals(HistoryGapState.Loading, rendered.second.state)
        }

    @Test
    fun `an unrelated fill does not change this seam`() =
        runTest {
            val rows = loadWindow()
            val gapId = seamState().seams.single().gapId

            val rendered = renderedSeams(rows, seamState(filling = setOf(gapId + 1))).single()

            // This gap's own id is not in `filling`, so it stays idle rather than borrowing the spinner
            // of the gap that actually is loading.
            assertEquals(HistoryGapState.Idle, rendered.second.state)
        }

    @Test
    fun `a failed attempt is what turns this seam's tap into a retry`() =
        runTest {
            val rows = loadWindow()
            val gapId = seamState().seams.single().gapId

            // An unrelated gap's failure must not raise the retry state here.
            assertEquals(
                HistoryGapState.Failed,
                renderedSeams(rows, seamState(failed = setOf(gapId))).single().second.state,
            )
            // Still tappable, but as an idle "load", not a retry — nothing failed for THIS gap.
            assertEquals(
                HistoryGapState.Idle,
                renderedSeams(rows, seamState(failed = setOf(gapId + 1))).single().second.state,
            )
        }

    @Test
    fun `an unrecoverable gap renders the permanent seam`() =
        runTest {
            val stored = db.historyGapDao().forRoom(roomId).single()
            db.historyGapDao().update(stored.copy(recoverable = false))
            val rows = loadWindow()

            // Still a seam: suppressing it is what used to hide the user's own stored history behind a
            // break that can never close. It simply stops offering a fill.
            val rendered = renderedSeams(rows, seamState()).single()
            assertEquals("new-1", rendered.first)
            assertEquals(HistoryGapState.Unrecoverable, rendered.second.state)
        }

    @Test
    fun `an unrecoverable gap ignores a stale in-flight id`() =
        runTest {
            val stored = db.historyGapDao().forRoom(roomId).single()
            db.historyGapDao().update(stored.copy(recoverable = false))
            val rows = loadWindow()

            val rendered = renderedSeams(rows, seamState(filling = setOf(stored.id))).single()

            assertEquals(HistoryGapState.Unrecoverable, rendered.second.state)
        }

    @Test
    fun `a closed gap removes its seam`() =
        runTest {
            db.historyGapDao().delete(
                db
                    .historyGapDao()
                    .forRoom(roomId)
                    .single()
                    .id,
            )
            val rows = loadWindow()

            val state = seamState()
            assertTrue("a filled gap publishes no seam", state.seams.isEmpty())
            assertEquals(emptyList<Pair<String, RowSeam>>(), renderedSeams(rows, state))
        }

    // --- a gap whose newer side the filter removes -------------------------------------------------
    //
    // The wedge, from a real install: reconnect catch-up fetched a page that was entirely JOIN/QUIT
    // for actors who had not spoken, so PresenceMode.SMART excluded every row of it, and the gap
    // recorded against that island had its newer edge above every PRESENTED row.
    //
    // Both consumers bound a seam by `position <= newest presented row`, so the seam matched
    // nothing: no divider (seamAbove) and no demand (seamsWithinPrefetch), hence no hands-free fill
    // and no tap to recover with, while the missing messages stayed fetchable the whole time. The
    // install proved the mechanism from the other side — flipping that one setting to "show all"
    // put the island back into the presentation and the gap filled immediately.
    //
    // Both halves are asserted together on purpose. A seam that renders but is never demanded still
    // needs the reader to notice and tap it, and a seam that is demanded but never renders fetches
    // with no spinner and no error surface.

    /** Replace the fixture's gap with one whose newer edge lands on filtered-out presence rows. */
    private suspend fun wedgeTheGapBehindHiddenPresenceRows(): Long {
        db.historyGapDao().delete(
            db
                .historyGapDao()
                .forRoom(roomId)
                .single()
                .id,
        )
        val island =
            listOf(
                insertPresence("join-0", serverTime = 9_000, kind = MessageKind.JOIN),
                insertPresence("quit-1", serverTime = 9_010, kind = MessageKind.QUIT),
                insertPresence("join-2", serverTime = 9_020, kind = MessageKind.JOIN),
            )
        // Exactly the shape EventProcessor records for reconnect catch-up: the older edge is the
        // newest row the client already held, the newer edge is the fetched page's OLDEST row.
        return db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = roomId,
                olderMsgid = null,
                olderServerTime = 5_010,
                olderEventId = newest,
                olderTimelineOrder = newest,
                newerMsgid = null,
                newerServerTime = 9_000,
                newerEventId = island.first(),
                newerTimelineOrder = island.first(),
            ),
        )
    }

    /** The viewport resting at the bottom of the timeline, which is where a room opens. */
    private fun demand(
        rows: List<MessageEntity>,
        seams: TimelineSeamState,
    ): Set<Long> =
        seamsWithinPrefetch(
            firstVisibleIndex = 0,
            lastVisibleIndex = rows.lastIndex,
            itemCount = rows.size,
            peek = { rows.getOrNull(it) },
            seams = seams.seams,
        )

    @Test
    fun `a gap hidden behind filtered presence rows still draws and still demands`() =
        runTest {
            val gapId = wedgeTheGapBehindHiddenPresenceRows()
            val rows = loadWindow()

            // The precondition, stated rather than assumed: the whole newer side of the gap is gone
            // from the presented list, so the newest row on screen is OLDER than the gap's newer edge.
            assertEquals(listOf("new-2", "new-1", "old-2", "old-1"), rows.map { it.text })

            val state = seamState()
            // Drawn: the only slot the presented list has for it, at the top of the timeline. Raw
            // geometry renders nothing at all here, which is the half of the defect the reader sees.
            assertEquals(
                "the gap must be drawn somewhere the reader can tap it",
                listOf("new-2"),
                renderedSeams(rows, state).map { it.first },
            )
            // Demanded: the autopilot has something to fill, so recovery does not depend on a tap.
            assertEquals(
                "the gap must be within loading reach of a viewport resting at the bottom",
                setOf(gapId),
                demand(rows, state),
            )
            // The mechanism behind both: the position is clamped into the presented coordinate space
            // instead of naming the island's first row (serverTime 9_000), which no presented row
            // reaches.
            assertEquals(TimelineAnchor(5_010, newest, newest), state.seams.single().position)
            assertEquals(HistoryGapState.Idle, renderedSeams(rows, state).single().second.state)
        }

    @Test
    fun `showing all presence rows reaches the same gap through its raw position`() =
        runTest {
            val gapId = wedgeTheGapBehindHiddenPresenceRows()
            val showAll = MessageVisibilitySpec(presenceMode = PresenceMode.ALL)
            val rows = loadWindow(showAll)
            val state = seamState(visibility = showAll)

            // The install's own experiment: with the island presented, the newest row is above the
            // gap's newer edge, so nothing is clamped and the seam keeps its exact stored position.
            assertEquals("join-2", rows.first().text)
            assertEquals(
                9_000,
                state.seams
                    .single()
                    .position.serverTime,
            )
            // Same gap, same two answers — under this spec from the raw position, under SMART from the
            // clamped one. Whether history can be recovered must not depend on a presence setting.
            assertEquals(listOf("join-0"), renderedSeams(rows, state).map { it.first })
            assertEquals(setOf(gapId), demand(rows, state))
        }
}
