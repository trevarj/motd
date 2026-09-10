package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.OutputStream

/**
 * Coverage for [HistoryGapFillCoordinator], the demand-driven interior gap fill.
 *
 * The coordinator OWNS the gap direction outright. Paging3 only asks a RemoteMediator to APPEND when
 * the local source runs dry, and on an unbounded timeline that happens past the oldest retained row
 * and nowhere else — never at an interior seam. So this is the only driver of a gap-scoped older
 * cascade left, and [gapCascadeWalksItsPinnedBoundaryLadder] states the ladder it walks and the
 * classification it stops on as literals rather than by comparison with anything.
 *
 * [mediatorAppendPagesTheGlobalLadderInsteadOfTheGapTheCoordinatorOwns] is the other half of that
 * ownership: the same fixture, driven through the mediator, asks for backlog below the whole
 * timeline and leaves the seam untouched.
 *
 * The gap-selection and gap-boundary pins that used to live in `ChatHistoryRemoteMediatorTest`
 * moved here with their fixtures and their reasoning intact; they describe the gap direction, and
 * the gap direction is this class's.
 *
 * Everything else here covers what the coordinator adds on top: the per-gap page budget, the
 * per-room single flight, and the divider's in-flight state.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryGapFillCoordinatorTest {
    private val fixtures = mutableListOf<Fixture>()

    @After fun tearDown() {
        fixtures.forEach { it.db.close() }
    }

    private suspend fun newFixture(): Fixture =
        Fixture().also {
            fixtures += it
            it.open()
        }

    // =============================================================================================
    // The gap-scoped older cascade.
    // =============================================================================================

    @Test
    fun gapCascadeWalksItsPinnedBoundaryLadder() =
        runTest {
            scenarios().forEach { scenario ->
                val walked = coordinatorLadder(scenario)

                assertEquals("${scenario.name}: boundary ladder", scenario.boundaries, walked.boundaries)
                assertEquals("${scenario.name}: end reason", scenario.endReason, walked.endReason)
            }
        }

    /**
     * Wire scripts whose older cascade terminates on a GAP-SCOPED verdict. A ladder that ends by
     * closing its gap is covered separately — see
     * [aPageThatClosesTheGapEndsTheFillInsteadOfSpendingTheBudgetElsewhere].
     */
    private fun scenarios(): List<Scenario> =
        listOf(
            Scenario(
                // soju's wire: MSGREFTYPES=timestamp, so every boundary is a bare timestamp and every
                // saturated page trips the loader's per-fetch cursor guard. The cascade must page on
                // anyway, and stop only when the terminal page proves the gap's remainder empty.
                name = "timestamp-only ladder ending on a server-proven-empty remainder",
                referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                pageSize = 2,
                boundaries =
                    listOf(
                        "timestamp=1970-01-01T00:00:00.212Z",
                        "timestamp=1970-01-01T00:00:00.200Z",
                        "timestamp=1970-01-01T00:00:00.180Z",
                    ),
                endReason = "exhausted_focused_gap",
                seed = { fixture ->
                    fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
                    fixture.processor.process(fixture.networkId, chatMsg("row212", 212))
                    fixture.db.historyGapDao().insert(
                        HistoryGapEntity(
                            roomId = fixture.roomId,
                            olderMsgid = null,
                            olderServerTime = 10,
                            newerMsgid = null,
                            newerServerTime = 212,
                        ),
                    )
                },
                script = {
                    pageScript(
                        ScriptedPage(listOf(chatMsg("row200", 200), chatMsg("row210", 210))),
                        ScriptedPage(listOf(chatMsg("row180", 180), chatMsg("row190", 190))),
                        ScriptedPage(listOf(chatMsg("row100", 100)), endOfHistory = true),
                    )
                },
            ),
            Scenario(
                // The same ladder on a msgid wire, where the saturation guard never fires: pins that the
                // equivalence is not an artifact of one reference-type regime.
                name = "msgid ladder ending on a server-proven-empty remainder",
                referenceTypes = setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                pageSize = 2,
                boundaries = listOf("msgid=row212", "msgid=row200", "msgid=row180"),
                endReason = "exhausted_focused_gap",
                seed = { fixture ->
                    fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
                    fixture.processor.process(fixture.networkId, chatMsg("row212", 212))
                    fixture.db.historyGapDao().insert(
                        HistoryGapEntity(
                            roomId = fixture.roomId,
                            olderMsgid = "marker",
                            olderServerTime = 10,
                            newerMsgid = "row212",
                            newerServerTime = 212,
                        ),
                    )
                },
                script = {
                    pageScript(
                        ScriptedPage(listOf(chatMsg("row200", 200), chatMsg("row210", 210))),
                        ScriptedPage(listOf(chatMsg("row180", 180), chatMsg("row190", 190))),
                        ScriptedPage(listOf(chatMsg("row100", 100)), endOfHistory = true),
                    )
                },
            ),
            Scenario(
                // Anti-livelock: the server echoes only the boundary row forever, so nothing lands and
                // the next request would repeat verbatim. Both drivers must stop after ONE page, and the
                // timestamp-only strip of the boundary's msgid must not read as progress.
                name = "non-advancing timestamp-only page",
                referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                pageSize = 2,
                boundaries = listOf("timestamp=1970-01-01T00:00:00.212Z"),
                endReason = "no_append_progress",
                seed = { fixture ->
                    fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
                    fixture.processor.process(fixture.networkId, chatMsg("row212", 212))
                    fixture.db.historyGapDao().insert(
                        HistoryGapEntity(
                            roomId = fixture.roomId,
                            olderMsgid = null,
                            olderServerTime = 10,
                            newerMsgid = "row212",
                            newerServerTime = 212,
                        ),
                    )
                },
                script = { { ScriptedPage(listOf(chatMsg("row212", 212))) } },
            ),
        )

    /** Drive the coordinator's fill, re-tapping while the only thing stopping it is the budget. */
    private suspend fun coordinatorLadder(scenario: Scenario): Ladder {
        val fixture = newFixture()
        scenario.seed(fixture)
        val history = FakeHistory(scenario.script(), scenario.referenceTypes)
        val coordinator = fixture.coordinator()
        repeat(MAX_LADDER_STEPS) {
            val fill = coordinator.fill(fixture.roomId, focused(), history, scenario.pageSize)
            if (fill.endReason != "page_budget") {
                return fixture.ladder(history, fill.endReason)
            }
        }
        error("${scenario.name}: coordinator never finished the gap")
    }

    @Test
    fun windowFetchPagesOlderUntilTheRoomReachesTheFloor() =
        runTest {
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("row300", 300))
            fixture.processor.process(fixture.networkId, chatMsg("row310", 310))
            val history =
                FakeHistory(
                    pageScript(
                        ScriptedPage(listOf(chatMsg("row200", 200), chatMsg("row210", 210))),
                        ScriptedPage(listOf(chatMsg("row100", 100), chatMsg("row110", 110))),
                        ScriptedPage(listOf(chatMsg("row050", 50)), endOfHistory = true),
                    ),
                )
            val coordinator = fixture.coordinator()

            val fetch = coordinator.fetchRoomWindow(fixture.roomId, floorMs = 150, source = history, pageSize = 2)

            // Two pages bring the oldest row to 100 <= 150; the third page is never requested.
            assertEquals("window_reached", fetch.endReason)
            assertEquals(2, fetch.pagesLoaded)
            assertEquals(4, fetch.insertedCount)
            assertEquals(2, history.requests.size)
            assertEquals(6, fixture.db.messageDao().countForBuffer(fixture.roomId))

            // Already at the floor: nothing is requested.
            val again = coordinator.fetchRoomWindow(fixture.roomId, floorMs = 150, source = history, pageSize = 2)
            assertEquals("window_reached", again.endReason)
            assertEquals(0, again.pagesLoaded)
        }

    @Test
    fun mediatorAppendPagesTheGlobalLadderInsteadOfTheGapTheCoordinatorOwns() =
        runTest {
            // The other half of the ownership. Same fixture, same wire, driven through the mediator: it
            // does not walk the gap ladder at all. The timeline is unbounded, so the APPEND Paging asks
            // for is a request for backlog below the OLDEST retained row — here `marker`, on the far side
            // of the seam that is now visible.
            val scenario = scenarios().first()
            val fixture = newFixture()
            scenario.seed(fixture)
            // The scenario's own script answers with rows INSIDE the gap regardless of what was asked
            // for, which would only muddy this assertion; the request is what is under test, so the
            // reply is a page from where the request actually points — below the whole timeline.
            val history =
                FakeHistory(
                    pageScript(ScriptedPage(listOf(chatMsg("row5", 5)))),
                    scenario.referenceTypes,
                )
            val mediator = fixture.mediator(history, scenario.pageSize)

            mediator.load(LoadType.APPEND, emptyPagingState())

            assertEquals(
                listOf("timestamp=1970-01-01T00:00:00.010Z"),
                history.requests.map { it.bound1 },
            )
            // Grounds the claim above: the ladder the coordinator walks for this same fixture starts at
            // the gap's newer edge, which is a different request entirely.
            assertEquals(
                "the gap ladder starts somewhere else",
                "timestamp=1970-01-01T00:00:00.212Z",
                scenario.boundaries.first(),
            )
            // The seam is untouched by that page: still open, still recoverable, still where it was.
            val gap =
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .single()
            assertTrue(gap.recoverable)
            assertEquals(212L, gap.newerServerTime)
        }

    // =============================================================================================
    // Gap-direction pins moved here from ChatHistoryRemoteMediatorTest, fixtures unchanged.
    // =============================================================================================

    @Test
    fun gapFillPagesBeforeTheGapNewerEdgeInsteadOfTheGlobalOldestCursor() =
        runTest {
            // Was `recentAppendPagesBeforeTheRecentIslandInsteadOfTheGlobalOldestCursor`. The property is
            // unchanged and is exactly why the seam is fillable at all: a fill asks for the interval
            // under the gap, not for backlog under the whole timeline, so it starts at the gap's NEWER
            // edge even though an older local row exists below the gap.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("old", 100))
            fixture.processor.process(fixture.networkId, chatMsg("recent-boundary", 851))
            fixture.db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = fixture.roomId,
                    olderMsgid = "old",
                    olderServerTime = 100,
                    newerMsgid = "recent-boundary",
                    newerServerTime = 851,
                ),
            )
            val history =
                FakeHistory(
                    pageScript(ScriptedPage(listOf(chatMsg("older-page", 801), chatMsg("newer-page", 850)))),
                )

            val fill = fixture.coordinator().fill(fixture.roomId, focused(), history, pageSize = 2)

            assertEquals("msgid=recent-boundary", history.requests.first().bound1)
            assertTrue("the fill made progress", fill.insertedCount > 0)
            val gap =
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .single()
            assertEquals(100L, gap.olderServerTime)
            assertEquals(801L, gap.newerServerTime)
        }

    @Test
    fun focusedFillSelectsTheUnidentifiableEqualTimeGapEdge() =
        runTest {
            // Was `pinnedCurrentBehavior_recentAppendSelectsTheUnidentifiableEqualTimeGapEdge`, verbatim
            // apart from the driver. focusedOlderGap ranks gaps by `asFocusNewerPosition`, whose
            // last-resort projection for an edge that resolves to no local row is the cohort CEILING.
            // Two gaps share newerServerTime 500: one edge names a retained row, the other names nothing
            // at all. The ceiling makes the UNIDENTIFIABLE gap win selection, so the fill pages from its
            // bare timestamp instead of the retained row's msgid.
            //
            // This is the opposite convention from the window/seam projection, which puts an
            // unidentifiable newer edge at the cohort FLOOR so it does not clamp (and so the seam is
            // drawn under its whole equal-time cohort). Both are intentional: selection wants the
            // unlocatable gap to win, placement wants it not to hide or split anything.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("anchorRow", 500))
            fixture.db.historyGapDao().insert(HistoryGapEntity(0, fixture.roomId, "a", 100, "anchorRow", 500))
            fixture.db.historyGapDao().insert(HistoryGapEntity(0, fixture.roomId, "b", 200, null, 500))
            val history = FakeHistory(pageScript(ScriptedPage(listOf(chatMsg("older-page", 450)))))

            fixture.coordinator().fill(fixture.roomId, focused(), history, pageSize = 2)

            assertEquals("timestamp=1970-01-01T00:00:00.500Z", history.requests.first().bound1)
        }

    @Test
    fun timestampFallbackMarksOnlyTheExactSelectedEqualTimeGapExhausted() =
        runTest {
            // Was a mediator test with the identical fixture. The msgid selector is rejected, the
            // advertised timestamp fallback is retried, and the terminal empty page proves ONLY the
            // selected gap's remainder gone — the equal-timestamp sibling the client did not page must
            // keep its recoverable seam.
            val fixture = newFixture()
            val firstId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(0, fixture.roomId, "a", 100, "b", 500),
                )
            val secondId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(0, fixture.roomId, "c", 200, "d", 500),
                )
            val history =
                FakeHistory(
                    pageScript(),
                    failureFor = { request ->
                        IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "try timestamp")
                            .takeIf { request.bound1?.startsWith("msgid=") == true }
                    },
                )

            val fill = fixture.coordinator().fill(fixture.roomId, focused(), history, pageSize = 2)

            assertEquals("exhausted_focused_gap", fill.endReason)
            val selectedMsgid =
                history.requests
                    .first()
                    .bound1
                    ?.removePrefix("msgid=")
            val gaps =
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .associateBy { it.id }
            val selectedId = if (selectedMsgid == "b") firstId else secondId
            val untouchedId = if (selectedId == firstId) secondId else firstId
            assertFalse(checkNotNull(gaps[selectedId]).recoverable)
            assertTrue(checkNotNull(gaps[untouchedId]).recoverable)
            assertEquals("timestamp=1970-01-01T00:00:00.500Z", history.requests.last().bound1)
        }

    @Test
    fun serverProvenEmptyRemainderStopsTheFillEvenAfterTheBoundaryReceded() =
        runTest {
            // Was `serverProvenEmptyGapRemainderStopsAppendEvenAfterTheBoundaryReceded`. Progress must
            // not outrank a server-proven-empty remainder: the page advanced the gap's newer edge AND
            // persisted a row, but the terminal response that never reached the older boundary marks the
            // remainder unrecoverable, so the fill is genuinely finished and the seam becomes permanent.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
            fixture.processor.process(fixture.networkId, chatMsg("row212", 212))
            fixture.db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = fixture.roomId,
                    olderMsgid = "marker",
                    olderServerTime = 10,
                    newerMsgid = "row212",
                    newerServerTime = 212,
                ),
            )
            val history =
                FakeHistory(
                    pageScript(ScriptedPage(listOf(chatMsg("row200", 200)), endOfHistory = true)),
                )

            val fill = fixture.coordinator().fill(fixture.roomId, focused(), history, pageSize = 2)

            assertEquals("exhausted_focused_gap", fill.endReason)
            assertEquals(1, fill.pagesLoaded)
            assertEquals(3, rowCount(fixture))
            val gap =
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .single()
            assertFalse(gap.recoverable)
            assertEquals(200L, gap.newerServerTime)
        }

    // =============================================================================================
    // What the coordinator adds on top of the inherited cascade.
    // =============================================================================================

    @Test
    fun budgetStopsTheFillWithTheSeamStillOpenAndTheNextTapResumesFromThere() =
        runTest {
            // The property being defended: before divider rows existed, nothing ever fetched a 10k
            // message gap unprompted. One tap must stay bounded, leave the seam visible and RECOVERABLE,
            // and the next tap must resume from the boundary this one reached — not restart the ladder.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
            fixture.processor.process(fixture.networkId, chatMsg("row900", 900))
            val gapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = "marker",
                        olderServerTime = 10,
                        newerMsgid = "row900",
                        newerServerTime = 900,
                    ),
                )
            val history =
                FakeHistory(
                    pageScript(
                        ScriptedPage(listOf(chatMsg("r800", 800), chatMsg("r850", 850))),
                        ScriptedPage(listOf(chatMsg("r700", 700), chatMsg("r750", 750))),
                        ScriptedPage(listOf(chatMsg("r600", 600), chatMsg("r650", 650))),
                        ScriptedPage(listOf(chatMsg("r500", 500), chatMsg("r550", 550))),
                        ScriptedPage(listOf(chatMsg("r400", 400), chatMsg("r450", 450))),
                    ),
                )
            val coordinator = fixture.coordinator()

            val first = coordinator.fill(fixture.roomId, byId(gapId), history, pageSize = 2)

            assertEquals("page_budget", first.endReason)
            assertEquals(HistoryGapFillCoordinator.PAGE_BUDGET, first.pagesLoaded)
            assertEquals(6, first.insertedCount)
            assertEquals(
                listOf("msgid=row900", "msgid=r800", "msgid=r700"),
                history.requests.map { it.bound1 },
            )
            val parked =
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .single()
            assertTrue("the seam stays fillable", parked.recoverable)
            assertEquals(600L, parked.newerServerTime)

            val second = coordinator.fill(fixture.roomId, byId(gapId), history, pageSize = 2)

            // The scripted wire runs out after two more pages, and the server's empty page proves the
            // remainder gone — a gap-scoped stop, not the budget.
            assertEquals("exhausted_focused_gap", second.endReason)
            assertEquals(
                listOf("msgid=row900", "msgid=r800", "msgid=r700", "msgid=r600", "msgid=r500", "msgid=r400"),
                history.requests.map { it.bound1 },
            )
            assertFalse(
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .single()
                    .recoverable,
            )
        }

    @Test
    fun unrecoverableGapNeverTouchesTheWire() =
        runTest {
            // `recoverable = false` means the server itself proved the interval is gone. Tapping its
            // divider must cost nothing at all: no request, no page, and the classification comes
            // straight out of olderPageability rather than a local re-statement of the rule.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
            fixture.processor.process(fixture.networkId, chatMsg("row900", 900))
            val gapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = "marker",
                        olderServerTime = 10,
                        newerMsgid = "row900",
                        newerServerTime = 900,
                        recoverable = false,
                    ),
                )
            val history = FakeHistory(pageScript(ScriptedPage(listOf(chatMsg("never", 500)))))
            val coordinator = fixture.coordinator()

            val byIdFill = coordinator.fill(fixture.roomId, byId(gapId), history, pageSize = 2)
            val focusedFill = coordinator.fill(fixture.roomId, focused(), history, pageSize = 2)

            assertEquals("unrecoverable_focused_gap", byIdFill.endReason)
            assertEquals("unrecoverable_focused_gap", focusedFill.endReason)
            assertEquals(0, byIdFill.pagesLoaded)
            assertEquals(0, focusedFill.pagesLoaded)
            assertTrue("an unrecoverable gap issues no requests", history.requests.isEmpty())
            assertEquals(2, rowCount(fixture))
        }

    @Test
    fun nonAdvancingPageStopsInsteadOfSpendingTheWholeBudgetOnOneBoundary() =
        runTest {
            // The inherited anti-livelock rule, seen from the coordinator's side: an uncapped loop over
            // a wire that keeps echoing the boundary row would burn the budget on three identical
            // requests. The progress verdict stops it after one — and the timestamp-only wire stripping
            // the boundary's msgid must not be mistaken for the boundary having moved.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
            fixture.processor.process(fixture.networkId, chatMsg("row212", 212))
            val gapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = null,
                        olderServerTime = 10,
                        newerMsgid = "row212",
                        newerServerTime = 212,
                    ),
                )
            val history =
                FakeHistory(
                    { ScriptedPage(listOf(chatMsg("row212", 212))) },
                    setOf(HistoryReferenceType.TIMESTAMP),
                )
            val coordinator = fixture.coordinator()

            val fill = coordinator.fill(fixture.roomId, byId(gapId), history, pageSize = 2)

            assertEquals("no_append_progress", fill.endReason)
            assertEquals(1, fill.pagesLoaded)
            assertEquals(listOf("timestamp=1970-01-01T00:00:00.212Z"), history.requests.map { it.bound1 })
            val gap =
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .single()
            assertEquals(gapId, gap.id)
            assertTrue("a stalled boundary is not proof the interval is gone", gap.recoverable)
            assertEquals(212L, gap.newerServerTime)
            // Stopping this cascade is right; reporting it as EXHAUSTION to whoever armed it is not.
            // Nothing landed and the seam is where it was, so the interval is still owed.
            assertEquals(GapFillProgress.STALLED, fill.progress)
        }

    @Test
    fun onlyABrokenAttemptIsReportedAsAFailure() =
        runTest {
            // The timeline loads history as the reader scrolls toward a seam, so the only classification
            // that raises an affordance is FAILED. It has to be exactly the ends that broke: anything
            // wider turns a quiet stop into an error message about history that is not missing.
            fun fill(
                pages: Int,
                inserted: Int,
                reason: String,
                error: Throwable? = null,
            ) = HistoryGapFillCoordinator.GapFill(
                gapId = 1,
                pagesLoaded = pages,
                insertedCount = inserted,
                endReason = reason,
                error = error,
            )

            assertEquals(GapFillProgress.STALLED, fill(1, 0, "no_append_progress").progress)
            // The budget is a quantum, not exhaustion: the reader scrolls on and it loads again.
            assertEquals(GapFillProgress.MOVED, fill(3, 150, "page_budget").progress)
            assertEquals(GapFillProgress.MOVED, fill(1, 50, "gap_filled").progress)
            assertEquals(GapFillProgress.MOVED, fill(1, 0, "exhausted_focused_gap").progress)
            // A page that inserted rows and still could not advance is progress, not contention.
            assertEquals(GapFillProgress.MOVED, fill(1, 12, "no_append_progress").progress)
            // Broken: the wire errored, or the network cannot serve history at all. A fill that landed
            // rows and THEN broke is still broken — the reader was mid-load and the load stopped.
            val cause = IrcDisconnectedException("CHATHISTORY", null)
            assertEquals(GapFillProgress.FAILED, fill(1, 0, "page_failed", cause).progress)
            assertEquals(GapFillProgress.FAILED, fill(1, 0, "history_unavailable", cause).progress)
            assertEquals(GapFillProgress.FAILED, fill(2, 40, "error", cause).progress)
            assertEquals(GapFillProgress.FAILED, fill(1, 0, "history_unsupported").progress)

            // The ends that never pin a gap carry no gap id, and they are the ones that reach here as
            // pure contention: the room's single flight was already taken, or there was no gap left.
            fun unpinned(reason: String) = HistoryGapFillCoordinator.GapFill(gapId = null, pagesLoaded = 0, insertedCount = 0, endReason = reason)

            assertEquals(GapFillProgress.DROPPED, unpinned("already_filling").progress)
            assertEquals(GapFillProgress.DROPPED, unpinned("no_gap").progress)
            assertEquals(GapFillProgress.DROPPED, unpinned("missing_room").progress)
        }

    @Test
    fun concurrentFillsOfTheSameRoomDoNotDoubleFetch() =
        runTest {
            // HistoryPageLoader already serializes the WIRE per network. What it cannot prevent is a
            // second fill computing its boundary from a store the first is halfway through moving, so
            // the second call is dropped outright rather than queued behind the first.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
            fixture.processor.process(fixture.networkId, chatMsg("row900", 900))
            val gapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = "marker",
                        olderServerTime = 10,
                        newerMsgid = "row900",
                        newerServerTime = 900,
                    ),
                )
            // The gate parks the first fill inside the wire; the loader's own request timeout is pushed
            // out of the way so runTest's virtual clock cannot fire it while the fill is parked.
            fixture.loader.requestTimeoutMs = Long.MAX_VALUE / 4
            val gate = CompletableDeferred<Unit>()
            val history =
                FakeHistory(
                    pageScript(ScriptedPage(listOf(chatMsg("r800", 800), chatMsg("r850", 850)))),
                    onRequest = { gate.await() },
                )
            val coordinator = fixture.coordinator()

            val inFlight = async { coordinator.fill(fixture.roomId, byId(gapId), history, pageSize = 2) }
            testScheduler.runCurrent()
            val rejected = coordinator.fill(fixture.roomId, byId(gapId), history, pageSize = 2)
            gate.complete(Unit)
            val completed = inFlight.await()

            assertEquals("already_filling", rejected.endReason)
            assertEquals(0, rejected.pagesLoaded)
            assertEquals(
                "every request on the wire belongs to the fill that held the room",
                completed.pagesLoaded,
                history.requests.size,
            )
        }

    @Test
    fun inFlightGapIdsAppearDuringTheFillAndClearAfterward() =
        runTest {
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
            fixture.processor.process(fixture.networkId, chatMsg("row900", 900))
            val gapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = "marker",
                        olderServerTime = 10,
                        newerMsgid = "row900",
                        newerServerTime = 900,
                    ),
                )
            val coordinator = fixture.coordinator()
            val observed = mutableListOf<Set<Long>>()
            val history =
                FakeHistory(
                    pageScript(ScriptedPage(listOf(chatMsg("r800", 800), chatMsg("r850", 850)))),
                    onRequest = { observed += coordinator.fillsInFlight.value },
                )

            assertEquals(emptySet<Long>(), coordinator.fillsInFlight.value)
            val fill = coordinator.fill(fixture.roomId, byId(gapId), history, pageSize = 2)

            assertEquals("the divider spins for its own gap while its pages are on the wire", setOf(gapId), observed.first())
            assertEquals(emptySet<Long>(), coordinator.fillsInFlight.value)
            assertTrue(fill.pagesLoaded > 0)
        }

    @Test
    fun aPageThatClosesTheGapEndsTheFillInsteadOfSpendingTheBudgetElsewhere() =
        runTest {
            // The gap is pinned for the whole fill. A page that crosses the older edge closes the seam,
            // and the remaining budget is NOT quietly spent on the next gap down or on the global cursor
            // ladder (which stays the mediator's job).
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("ancient", 5))
            fixture.processor.process(fixture.networkId, chatMsg("marker", 100))
            fixture.processor.process(fixture.networkId, chatMsg("row900", 900))
            val olderGapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = "ancient",
                        olderServerTime = 5,
                        newerMsgid = "marker",
                        newerServerTime = 100,
                    ),
                )
            val focusedGapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = "marker",
                        olderServerTime = 100,
                        newerMsgid = "row900",
                        newerServerTime = 900,
                    ),
                )
            val history =
                FakeHistory(
                    pageScript(ScriptedPage(listOf(chatMsg("marker", 100), chatMsg("r500", 500)))),
                )
            val coordinator = fixture.coordinator()

            val fill = coordinator.fill(fixture.roomId, focused(), history, pageSize = 2)

            assertEquals("gap_filled", fill.endReason)
            assertEquals(focusedGapId, fill.gapId)
            assertEquals(1, fill.pagesLoaded)
            assertEquals(listOf("msgid=row900"), history.requests.map { it.bound1 })
            assertEquals(
                "the untouched seam below is left for its own tap",
                listOf(olderGapId),
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.roomId)
                    .map { it.id },
            )
        }

    @Test
    fun endedFillsAreJournalledUnderChatHistoryWithAnEndReasonField() =
        runTest {
            // `end_reason`, never `reason`: DiagnosticLogger redacts any field literally named `reason`
            // because IRC quit/kick reasons are user content. This classification is not.
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("marker", 10))
            fixture.processor.process(fixture.networkId, chatMsg("row900", 900))
            val gapId =
                fixture.db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = fixture.roomId,
                        olderMsgid = "marker",
                        olderServerTime = 10,
                        newerMsgid = "row900",
                        newerServerTime = 900,
                        recoverable = false,
                    ),
                )
            val coordinator = fixture.coordinator()

            coordinator.fill(fixture.roomId, byId(gapId), FakeHistory(pageScript()), pageSize = 2)

            val ended = fixture.diagnostics.events.last { it.event == "gap_fill_ended" }
            assertEquals("chat_history", ended.component)
            assertEquals("unrecoverable_focused_gap", ended.fields["end_reason"])
            assertEquals(gapId, ended.fields["gap_id"])
            assertTrue(
                "gap-fill events must not carry a redacted `reason` field",
                fixture.diagnostics.events.none { it.event.startsWith("gap_fill") && "reason" in it.fields },
            )
        }

    @Test
    fun aRoomWithoutTheRequestedGapIsANoop() =
        runTest {
            val fixture = newFixture()
            fixture.processor.process(fixture.networkId, chatMsg("row900", 900))
            val history = FakeHistory(pageScript(ScriptedPage(listOf(chatMsg("never", 500)))))
            val coordinator = fixture.coordinator()

            val missing = coordinator.fill(fixture.roomId, byId(4242L), history, pageSize = 2)
            val focused = coordinator.fill(fixture.roomId, focused(), history, pageSize = 2)

            assertEquals("no_gap", missing.endReason)
            assertEquals("no_gap", focused.endReason)
            assertTrue(history.requests.isEmpty())
        }

    // =============================================================================================
    // Fixture
    // =============================================================================================

    private fun byId(gapId: Long) = HistoryGapFillCoordinator.GapSelection.ById(gapId)

    private fun focused() = HistoryGapFillCoordinator.GapSelection.Newest

    private suspend fun rowCount(fixture: Fixture): Int = fixture.db.messageDao().countForBuffer(fixture.roomId)

    /** The comparable shape of one older cascade: what it asked the wire, and why it stopped. */
    private data class Ladder(
        val boundaries: List<String?>,
        val endReason: String?,
        val rows: Int,
    )

    private class Scenario(
        val name: String,
        val referenceTypes: Set<HistoryReferenceType>,
        val pageSize: Int,
        /** The selectors this cascade is expected to walk, and the classification it stops on. */
        val boundaries: List<String>,
        val endReason: String,
        val seed: suspend (Fixture) -> Unit,
        /** Rebuilt per driver so both see an identical, independently consumed wire. */
        val script: () -> (ChatHistoryRequest) -> ScriptedPage,
    )

    private class Fixture {
        val db: MotdDatabase =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    MotdDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        val diagnostics = RecordingDiagnostics()
        val processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        val loader = HistoryPageLoader(processor, diagnostics)
        var networkId = 0L
        var roomId = 0L

        suspend fun open() {
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "h",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(networkId, "me", emptyMap())
            db.bufferDao().insert(
                BufferEntity(
                    networkId = networkId,
                    name = "#chan",
                    displayName = "#chan",
                    type = BufferType.CHANNEL,
                ),
            )
            roomId = db.bufferDao().byName(networkId, "#chan")!!.id
        }

        fun coordinator() =
            HistoryGapFillCoordinator(
                NoClientConnectionManager,
                db.bufferDao(),
                db.messageDao(),
                db.historyCursorDao(),
                db.historyGapDao(),
                loader,
                diagnostics,
            )

        fun mediator(
            history: FakeHistory,
            pageSize: Int,
        ) = ChatHistoryRemoteMediator(
            bufferId = roomId,
            bufferDao = db.bufferDao(),
            messageDao = db.messageDao(),
            processor = processor,
            history = history,
            pageSize = pageSize,
            historyCursorDao = db.historyCursorDao(),
            historyGapDao = db.historyGapDao(),
            loader = loader,
            diagnostics = diagnostics,
        )

        suspend fun ladder(
            history: FakeHistory,
            endReason: String?,
        ) = Ladder(
            history.requests.map { it.bound1 },
            endReason,
            db.messageDao().countForBuffer(roomId),
        )
    }

    /** One scripted CHATHISTORY response. */
    private data class ScriptedPage(
        val events: List<IrcEvent>,
        val endOfHistory: Boolean = false,
    )

    private fun pageScript(vararg pages: ScriptedPage): (ChatHistoryRequest) -> ScriptedPage {
        val queue = ArrayDeque(pages.toList())
        // A drained script answers like a server that has nothing older: an empty, terminal page.
        return { queue.removeFirstOrNull() ?: ScriptedPage(emptyList()) }
    }

    /**
     * Scripted wire shared by both drivers. Implements the coordinator's and the mediator's source
     * seams at once — they are the same two methods off [HistoryPageLoader.HistorySource].
     */
    private class FakeHistory(
        private val script: (ChatHistoryRequest) -> ScriptedPage,
        private val referenceTypes: Set<HistoryReferenceType> =
            setOf(
                HistoryReferenceType.TIMESTAMP,
                HistoryReferenceType.MSGID,
            ),
        private val onRequest: (suspend (ChatHistoryRequest) -> Unit)? = null,
        /** Per-request rejection, for the msgid→timestamp fallback ladder. */
        private val failureFor: ((ChatHistoryRequest) -> Throwable?)? = null,
    ) : HistoryGapFillCoordinator.HistorySource,
        ChatHistoryRemoteMediator.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()

        override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(referenceTypes, 100)

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            requests += req
            onRequest?.invoke(req)
            failureFor?.invoke(req)?.let { throw it }
            val page = script(req)
            return messages(page.events, page.endOfHistory)
        }
    }

    private class RecordedEvent(
        val component: String,
        val event: String,
        val fields: Map<String, Any?>,
    )

    private class RecordingDiagnostics : DiagnosticLogger {
        val events = mutableListOf<RecordedEvent>()
        override val enabled: StateFlow<Boolean> = MutableStateFlow(true)

        override fun setEnabled(enabled: Boolean) = Unit

        override fun record(
            component: String,
            event: String,
            fields: () -> Map<String, Any?>,
        ) {
            events += RecordedEvent(component, event, fields())
        }

        override fun fingerprint(value: String?): String? = value

        override suspend fun exportTo(output: OutputStream) = Unit

        fun lastEndReason(event: String): String? = events.lastOrNull { it.event == event }?.fields?.get("end_reason") as? String
    }

    /** No live clients: every test drives the coordinator through its explicit source seam. */
    private object NoClientConnectionManager : NoopConnectionManager()

    private companion object {
        /** Loop guard: no scenario here needs anywhere near this many drives. */
        const val MAX_LADDER_STEPS = 12

        /** Newer than every gap edge in this file, so Around focus always selects the fixture gap. */
        const val FOCUS_ANCHOR_TIME = 10_000L
    }
}

private fun chatMsg(
    msgid: String,
    time: Long,
) = IrcEvent.ChatMessage(
    ctx = MessageContext(msgid, time, null, "b", null),
    kind = IrcEvent.ChatKind.PRIVMSG,
    source = Prefix("alice"),
    target = "#chan",
    text = msgid,
    isSelf = false,
    replyToMsgid = null,
)

private fun messages(
    events: List<IrcEvent>,
    endOfHistory: Boolean = false,
): ChatHistoryResponse.Messages {
    val references =
        events.mapNotNull { event ->
            val ctx =
                when (event) {
                    is IrcEvent.ChatMessage -> event.ctx
                    is IrcEvent.TagMessage -> event.ctx
                    else -> null
                } ?: return@mapNotNull null
            ChatHistoryReference(ctx.msgid, ctx.serverTime)
        }
    return ChatHistoryResponse.Messages(
        events,
        oldest = references.firstOrNull(),
        newest = references.lastOrNull(),
        endOfHistory = endOfHistory,
        primaryMessageCount = references.size,
    )
}

private fun emptyPagingState() =
    PagingState<Int, MessageEntity>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = 50, prefetchDistance = 25, enablePlaceholders = false),
        leadingPlaceholderCount = 0,
    )
