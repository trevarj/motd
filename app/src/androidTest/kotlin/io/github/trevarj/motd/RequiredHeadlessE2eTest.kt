package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.trevarj.motd.ai.WhisperNativeAssertions
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.VoiceSendRequest
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.e2e.BootstrappedNetwork
import io.github.trevarj.motd.e2e.BufferProbe
import io.github.trevarj.motd.e2e.ConnectionProbe
import io.github.trevarj.motd.e2e.E2eBootstrap
import io.github.trevarj.motd.e2e.E2eFailureArtifactRule
import io.github.trevarj.motd.e2e.E2eMilestoneRecorder
import io.github.trevarj.motd.e2e.FixtureIrcClient
import io.github.trevarj.motd.e2e.HistorySyncProbe
import io.github.trevarj.motd.e2e.MessageLifecycleProbe
import io.github.trevarj.motd.e2e.MessageRunProbe
import io.github.trevarj.motd.e2e.ScenarioHolder
import io.github.trevarj.motd.e2e.TimelineDiagnostics
import io.github.trevarj.motd.e2e.robots.BouncerRobot
import io.github.trevarj.motd.e2e.robots.ChatListRobot
import io.github.trevarj.motd.e2e.robots.ChatRobot
import io.github.trevarj.motd.e2e.robots.NetworksRobot
import io.github.trevarj.motd.e2e.robots.OnboardingRobot
import io.github.trevarj.motd.e2e.robots.SettingsRobot
import io.github.trevarj.motd.e2e.robots.ThemeSheetRobot
import io.github.trevarj.motd.e2e.robots.TimelineRobot
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.MotdNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.TestClass
import java.io.File

/** Marks the real-stack, isolated journeys required by the headless API34 gate. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FastHeadlessE2e

/**
 * Headroom the Compose pump wait gets on top of the probe budget it hosts.
 *
 * The PROBE has to be the wait that expires. Its `AssertionError` carries `snapshots=/count=/
 * oldest=/newest=`, which is what tells a genuinely stalled product apart from a green baseline; a
 * `ComposeTimeoutException` would say only that a boolean never flipped.
 */
private const val COMPOSE_PUMP_GRACE_MS = 15_000L

/**
 * Budget for a wait on the history WINDOW reaching its terminal shape, shared by the probe and the
 * Compose pump that hosts it.
 *
 * Not named for the post-open step alone: the post-recreate newest-200 wait is the same kind of wait
 * — a directional paging restore driven by composition-scoped effects, at the same network scale —
 * and calling that budget a "post-open settle" made the second site read like a copied constant.
 */
private const val HISTORY_WINDOW_SETTLE_TIMEOUT_MS = 45_000L

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class RequiredHeadlessE2eTest {
    private val milestones = E2eMilestoneRecorder()
    private val scenario = ScenarioHolder()
    private val artifacts = E2eFailureArtifactRule(scenario, milestones)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
            .around(artifacts)
            .around(compose)

    private fun launchBootstrapped(requiredCaps: Set<String> = emptySet()): Pair<E2eBootstrap, BootstrappedNetwork> {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val network = runBlocking { bootstrap.connectedSojuNetwork() }
        val probe = ConnectionProbe(bootstrap.seams.connections(), milestones)
        runBlocking {
            probe.awaitReady(network.rootId, emptySet())
            probe.awaitReady(network.childId, requiredCaps)
        }
        scenario.launch()
        return bootstrap to network
    }

    @Test
    fun onboardingTrustsEphemeralTlsAndImportsNetwork() {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        scenario.launch()
        OnboardingRobot(compose).importSoju(bootstrap.args)
        val rows =
            runBlocking {
                bootstrap.seams
                    .networks()
                    .observeNetworks()
                    .first()
            }
        val root = rows.single { it.role == NetworkRole.BOUNCER_ROOT }
        val child =
            rows.single {
                it.role == NetworkRole.BOUNCER_CHILD && it.parentId == root.id &&
                    it.name == "libera" && !it.bouncerNetId.isNullOrBlank()
            }
        runBlocking { ConnectionProbe(bootstrap.seams.connections(), milestones).awaitReady(child.id, emptySet()) }
        assertTrue(runBlocking { bootstrap.seams.certTrust().isPinned(bootstrap.args.host, bootstrap.args.port, bootstrap.args.fingerprint) })
        compose.onAllNodesWithTag("cert_trust_dialog", useUnmergedTree = true).assertCountEquals(0)
        milestones.record("onboarding_imported", "root=${root.id} child=${child.id}")
    }

    @Test
    fun sendEchoPersistsVisibleRowAndReconnects() {
        val (bootstrap, network) =
            launchBootstrapped(
                setOf(
                    "echo-message",
                    "draft/chathistory",
                    "batch",
                    "message-tags",
                    "server-time",
                ),
            )
        val bufferId = runBlocking { BufferProbe(bootstrap.seams.buffers(), milestones).awaitJoinedChannel(network.childId, bootstrap.args.channel) }
        ChatListRobot(compose).open(bufferId)
        val token = "required${bootstrap.args.runId.filter(Char::isLetterOrDigit).takeLast(16)}"
        val probe = MessageLifecycleProbe(bootstrap.seams.search(), milestones)
        val canonical =
            runBlocking {
                coroutineScope {
                    val observed = async(start = CoroutineStart.UNDISPATCHED) { probe.awaitCanonical(token, bufferId) }
                    ChatRobot(compose).send(token)
                    observed.await()
                }
            }
        TimelineRobot(compose).assertMessageVisible(canonical.tag())
        runBlocking {
            bootstrap.seams.connections().disconnect(network.childId)
            bootstrap.seams.connections().connect(network.childId)
            ConnectionProbe(bootstrap.seams.connections(), milestones).awaitReady(
                network.childId,
                setOf(
                    "echo-message",
                    "draft/chathistory",
                    "batch",
                    "message-tags",
                    "server-time",
                ),
            )
        }
        val after = runBlocking { probe.awaitCanonical(token, bufferId) }
        assertEquals(canonical.id, after.id)
        TimelineRobot(compose).assertMessageVisible(after.tag())

        WhisperNativeAssertions.assertMalformedModelsDoNotAbortOrPoisonLaterInspections()

        val fixture =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "required-${bootstrap.args.runId}.ogg",
            )
        fixture.delete()
        assertTrue(fixture.createNewFile())
        try {
            val client = checkNotNull(bootstrap.seams.connections().clientFor(network.childId))
            val (upload, echo) =
                runBlocking {
                    coroutineScope {
                        val echoed =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                client.broadcastEvents
                                    .filterIsInstance<IrcEvent.ChatMessage>()
                                    .first {
                                        it.isSelf &&
                                            it.ctx.clientTags["+trevarj.github.io/audio"] == "1"
                                    }
                            }
                        val uploaded =
                            bootstrap.seams
                                .voiceMessages()
                                .send(
                                    VoiceSendRequest(
                                        bufferId = bufferId,
                                        file = fixture,
                                        durationMs = 1_000,
                                        mimeType = "audio/ogg",
                                        extension = ".ogg",
                                        sizeBytes = 0,
                                        encrypt = false,
                                    ),
                                ).filterIsInstance<VoiceSendProgress.Complete>()
                                .first()
                        uploaded to echoed.await()
                    }
                }
            val expectedBody = "[voice 0:01 audio/ogg] ${upload.url}"
            assertEquals("1", echo.ctx.clientTags["+trevarj.github.io/audio"])
            assertEquals(expectedBody, echo.text)
            assertTrue("expires=" !in echo.text)
            val voice = runBlocking { probe.awaitCanonicalContaining("voice", upload.url, bufferId) }
            assertEquals(echo.text, voice.text)
            // This is the journey's historically opaque failure: the row is provably in Room, yet
            // the timeline neither composes it nor resolves its Paging key. Snapshot the presented
            // list, the key map, Room, and the history window on both outcomes so the next run
            // reports which of those disagrees instead of only that the wait expired.
            TimelineRobot(compose).assertCompactAudioPlayer(
                voice.tag(),
                voice.id,
                diagnostics =
                    TimelineDiagnostics(
                        compose = compose,
                        targetContext = InstrumentationRegistry.getInstrumentation().targetContext,
                        artifactPrefix = artifacts.artifactPrefix(),
                        milestones = milestones,
                        bufferId = bufferId,
                        probedEventId = voice.id,
                        probedMsgid = voice.msgid,
                    ),
            )
            milestones.record("filehost_audio_rendered", "buffer=$bufferId")
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun unreadHistoryEntersAtMarkerAndRemainsCanonical() {
        val (bootstrap, network) =
            launchBootstrapped(
                setOf("draft/chathistory", "draft/read-marker", "batch", "message-tags", "server-time"),
            )
        val connectionProbe = ConnectionProbe(bootstrap.seams.connections(), milestones)
        val bufferId =
            runBlocking {
                BufferProbe(bootstrap.seams.buffers(), milestones).awaitJoinedChannel(network.childId, bootstrap.args.channel)
            }
        val token = "unread${bootstrap.args.runId.filter(Char::isLetterOrDigit).takeLast(14)}"
        val lifecycle = MessageLifecycleProbe(bootstrap.seams.search(), milestones)
        val runProbe = MessageRunProbe(bootstrap.seams.search(), milestones)

        val marker =
            FixtureIrcClient.connect(bootstrap.args).use { fixture ->
                fixture.sendMessage(bootstrap.args.channel, "$token marker")
                fixture.flushThroughServer("${token}marker")
                runBlocking { lifecycle.awaitCanonicalFromAnySender("$token marker", bufferId) }
            }
        val markerAnchor = TimelineAnchor(marker.serverTime, marker.id, marker.timelineOrder)
        runBlocking {
            bootstrap.seams.connections().markRead(bufferId, markerAnchor)
            awaitMarkerAtLeast(bootstrap, bufferId, markerAnchor, requireRemote = true)
            bootstrap.seams.connections().disconnect(network.childId)
            connectionProbe.awaitDisconnected(network.childId)
            awaitWallClockAfter(markerAnchor.serverTime)
        }

        FixtureIrcClient.connect(bootstrap.args).use { fixture ->
            (1..260).forEach { ordinal ->
                fixture.sendMessage(bootstrap.args.channel, "$token row${ordinal.toString().padStart(3, '0')}")
                // Timestamp injectivity: the PONG barrier proves Ergo stamped this row at some T,
                // and the sleep keeps the next row from reaching the server before T + 2 ms, so
                // every fixture row lands on a distinct millisecond. Timestamp-only CHATHISTORY
                // paging (soju advertises MSGREFTYPES=timestamp; BEFORE is strictly-less-than)
                // silently skips same-millisecond peers at page boundaries, so an unpaced burst
                // (3+ rows per millisecond) makes deep paging lossy by construction — a fixture
                // artifact, not app behavior.
                fixture.flushThroughServer("${token}p$ordinal")
                Thread.sleep(2)
            }
            fixture.flushThroughServer("${token}gap")
        }
        runBlocking {
            coroutineScope {
                // Subscribed BEFORE the reconnect, and it has to stay that way: the probe waits for
                // this buffer to become active and then settle, and per-buffer status is a
                // conflating StateFlow, so a late subscriber can miss the whole episode.
                //
                // The active window no longer opens when the pass starts. A catch-up pass is latent
                // until CHATHISTORY TARGETS proves at least one room moved — a reconnect that finds
                // nothing changed is not something the user should watch — so the first active
                // status here is published at discovery's first changed page rather than at
                // registration. This buffer IS changed (260 rows landed while the socket was down),
                // so the episode still runs Queued -> Syncing -> settled, with a real round trip
                // between the reveal and the settle. The 45s budget is unchanged and still covers
                // one discovery round trip plus one newest page.
                val historySettled =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        HistorySyncProbe(bootstrap.seams.history(), milestones).awaitCycle(bufferId)
                    }
                bootstrap.seams.connections().connect(network.childId)
                connectionProbe.awaitReady(
                    network.childId,
                    setOf("draft/chathistory", "draft/read-marker", "batch", "message-tags", "server-time"),
                )
                historySettled.await()
            }
        }
        // Automatic CHATHISTORY fetches one 50-event newest page; chat-only search omits the
        // replayed state event in that page.
        val recentWindow =
            runBlocking {
                runProbe.awaitStableRecentRows(
                    token = token,
                    bufferId = bufferId,
                    minimumCount = 49,
                    maximumCount = 49,
                    expectedNewestOrdinal = 260,
                    requiredText = "$token row260",
                    excludedText = "$token row001",
                )
            }
        val newest = recentWindow.single { it.text == "$token row260" }
        val orderedRecent = recentWindow.sortedBy { it.anchor() }
        val oldestLoaded = orderedRecent.first()
        val secondLoaded = orderedRecent[1]
        assertTrue(recentWindow.none { it.text == "$token row001" })
        assertMarkerAtLeast(bootstrap, bufferId, marker)
        val roomBeforeEntry =
            runBlocking {
                bootstrap.seams
                    .buffers()
                    .observeBuffer(bufferId)
                    .first { it != null }
            }
        assertEquals(markerAnchor.serverTime, roomBeforeEntry?.localReadAnchorTime)
        assertEquals(Long.MAX_VALUE, roomBeforeEntry?.localReadAnchorEventId)
        val listBeforeEntry =
            runBlocking {
                withTimeout(10_000) {
                    bootstrap.seams.buffers().observeChatList().first { rows ->
                        rows.singleOrNull { it.bufferId == bufferId }?.let { row ->
                            row.unreadCount == 49 && row.unreadCountIncomplete
                        } == true
                    }
                }
            }
        val boundedRow = listBeforeEntry.single { it.bufferId == bufferId }
        assertEquals(49, boundedRow.unreadCount)
        assertTrue(boundedRow.unreadCountIncomplete)

        ChatListRobot(compose).open(bufferId)
        val timeline = TimelineRobot(compose)
        timeline.assertUnreadEntry(
            oldestLoaded.tag(),
            secondLoaded.tag(),
            expectedLabel = "49+ new messages",
        )
        compose.waitForIdle()
        assertMarkerAtLeast(bootstrap, bufferId, marker)
        // Opening the timeline is what loads across the seam, and it is the ordinary rule rather
        // than a reconnect special case: normal entry parks the first unread row at the top of the
        // viewport, that row is the one on the NEWER side of the catch-up gap, so the seam is inside
        // the same prefetch reach that makes scrolling to the END of the list append. One approach
        // fetches one quantum — HistoryGapFillCoordinator.PAGE_BUDGET (3) at the 50-row page size —
        // and then the seam has receded 150 rows, far outside SEAM_PREFETCH_DISTANCE (25), so a
        // viewport that does not move fetches nothing more. The wire is timestamp-only (soju
        // advertises MSGREFTYPES=timestamp): the catch-up gap must stay RECOVERABLE across
        // msgid-less saturated pages for the fill to run past its FIRST page at all — the regression
        // this pins, and the reason the count is 199 rather than 99.
        //
        // This step is also the negative control for the seam-prefetch signal itself. Loading has
        // exactly one demand source now, so a signal that never reports the seam — a viewport range
        // that is never derived, a debounce that never settles, a set never handed to the ViewModel
        // — leaves the window at 49 and fails here loudly rather than degrading quietly.
        //
        // An EXACT count, because the outcome is deterministic: 49 + 3 * 50 = 199. It used to be a
        // range only because two demand sources were both aiming at this interval — the seam's own
        // load and Paging's Recent APPEND — and which of them claimed a boundary first decided how
        // many of the three pages landed anything. That contention is gone structurally: Recent
        // APPEND is clamped strictly below every open gap and can no longer name an interval a gap
        // owns, and the loader no longer coalesces a gap-directed fetch with the bottom-of-timeline
        // ladder, so neither can be handed the other's page. Seam loads are serialized on one
        // collector as well, so no second seam can be loading beside this one. What pins the count
        // to ONE approach is the rule's own bound: a gap already fetched at this viewport depth is
        // refused until the reader scrolls further into history, and nothing here scrolls. A range
        // would now hide exactly the defect it was papering over.
        //
        // 199 is also the most this surface can observe: the chat-only search caps at newest-200
        // rows, and the marker row occupies the 200th slot.
        //
        // Paging's own APPEND still auto-fires with no scroll — the initial source load of an
        // under-initialLoadSize store returns nextKey == null, which is unconditional Paging3
        // behavior — but it aims BELOW the oldest retained row, where this fixture has no token
        // rows, so it cannot move this count either way.
        //
        // Hosted through [awaitWhileComposing] rather than `runBlocking`, and that is not a style
        // choice: everything this step waits for is produced by composition-scoped effects, which
        // the compose rule can only run while the instrumentation thread is inside a Compose wait.
        val postOpenWindow =
            awaitWhileComposing(
                "settled post-open history window for buffer=$bufferId",
                probeTimeoutMs = HISTORY_WINDOW_SETTLE_TIMEOUT_MS,
            ) {
                runProbe.awaitStableRecentRows(
                    token = token,
                    bufferId = bufferId,
                    minimumCount = 199,
                    maximumCount = 199,
                    expectedNewestOrdinal = 260,
                    // row062 is the OLDEST row the budgeted fill reaches, so it exists only in the
                    // terminal state: the probe cannot settle on a mid-cascade snapshot, and a row from
                    // the pre-open window would have settled it immediately and asserted nothing.
                    requiredText = "$token row062",
                    excludedText = "$token row001",
                    // The three pages are one uninterrupted cascade now, but a slow hosted emulator can
                    // still quiesce between them. A longer quiet window keeps the settle off a
                    // pre-terminal snapshot that would hand the reopen divider stale oldest-row anchors.
                    // Wall-clock, on the probe's own dispatcher, not the rule's test clock.
                    stableMs = 4_000,
                    // Pinned rather than defaulted so the probe and the Compose pump hosting it are
                    // provably the same budget; see awaitWhileComposing.
                    timeoutMs = HISTORY_WINDOW_SETTLE_TIMEOUT_MS,
                )
            }
        // The reopen entry re-resolves against the grown island: its two oldest rows become the
        // next divider anchor.
        val orderedPostOpen = postOpenWindow.sortedBy { it.anchor() }
        val reopenOldest = orderedPostOpen.first()
        val reopenSecond = orderedPostOpen[1]

        // Reopening anchors a bounded entry on the settled window, not the backlog. The divider
        // count equals the visible unread window rows (fixture rows plus k), so no exact label is
        // pinned here; the frozen "49+" assertion above already proves the bounded-catch-up label.
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ChatListRobot(compose).apply {
            awaitTag("chatlist_row_$bufferId")
            open(bufferId)
        }
        timeline.assertUnreadEntry(
            reopenOldest.tag(),
            reopenSecond.tag(),
        )
        assertMarkerAtLeast(bootstrap, bufferId, marker)
        // No search pin after the reopen: its entry lands ON the window's oldest unread row (the
        // APPEND boundary), so the boundary hint appends one more bounded page, which can saturate
        // the chat-only search surface (newest-200 cap) and become indistinguishable from deeper
        // windows there. The backfill mechanics are already pinned by the ranged settle above;
        // deeper paging is verified by row001 becoming reachable plus the terminal canonicality and
        // newest-200 cap assertions below.
        //
        // Reaching row001 is now a two-step journey, and the order is the contract. The oldest
        // fixture rows are on the far side of the seam, and PAGING CANNOT FETCH THEM: Paging's
        // APPEND pages below the bottom of the timeline, never through an interior gap. What closes
        // the gap is the reader SCROLLING TOWARD IT — the reopen's entry parks on its newer row, and
        // the sweeps below approach it again — so the pair is one assertion: if scrolling toward a
        // seam no longer loads across it, the first call runs out of approaches and, failing that,
        // `scrollOlderUntil` exhausts its 48 deliberate steps. Both fail loudly.
        //
        // No tap is issued, and that is the point rather than an omission: a seam behaves like the
        // end of the list, so the divider offers a tap only when a fetch actually FAILED. A journey
        // that tapped it would be exercising the error path and calling it the ordinary one. The
        // divider's rendering, placement, states and the coordinator's quantum are covered
        // deterministically by TimelineSeamPresentationTest, ReconnectGapPresentationTest and
        // HistoryGapFillCoordinatorTest; the loading rule itself by ChatModelsTest.
        //
        // Note the ordering constraint too: this is the first step allowed to walk the timeline
        // looking for the divider, because a `performScrollToNode` miss resets to index 0 — the
        // newest row — and the viewport mark-read effect reads that as reaching the bottom. Every
        // assertion above depends on the room still having unread rows.
        timeline.awaitGapFilledByScrolling()
        timeline.scrollOlderUntil("$token row001")
        val (firstUnread, secondUnread) =
            runBlocking {
                lifecycle.awaitCanonicalFromAnySender("$token row001", bufferId) to
                    lifecycle.awaitCanonicalFromAnySender("$token row002", bufferId)
            }
        assertTrue(markerAnchor < firstUnread.anchor())
        assertTrue(firstUnread.anchor() < secondUnread.anchor())
        assertTrue(secondUnread.anchor() < newest.anchor())
        timeline.assertMessageVisible(firstUnread.tag())

        timeline.scrollToBottom()
        // The write this waits for is issued by the viewport mark-read effect off the rendered
        // newest anchor — both composition-scoped — so the thread that would block here is the same
        // one that has to keep frames coming. Budget matches the helper's own withTimeout.
        awaitWhileComposing(
            "room marker advanced to the newest row for buffer=$bufferId",
            probeTimeoutMs = 20_000,
        ) {
            awaitMarkerAtLeast(bootstrap, bufferId, newest.anchor())
        }
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ChatListRobot(compose).apply {
            awaitTag("chatlist_row_$bufferId")
            open(bufferId)
        }
        timeline.assertNoUnreadDivider()
        timeline.assertMessage("$token row260")

        scenario.scenario?.onActivity { activity ->
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                activity,
                Intent(activity, MainActivity::class.java)
                    .setAction(MotdNotifications.ACTION_OPEN_BUFFER)
                    .putExtra(MotdNotifications.EXTRA_BUFFER_ID, bufferId)
                    .putExtra(MotdNotifications.EXTRA_JUMP_MSGID, firstUnread.msgid)
                    .putExtra(MotdNotifications.EXTRA_JUMP_TIME, firstUnread.serverTime)
                    .putExtra(MotdNotifications.EXTRA_EVENT_ID, firstUnread.id),
            )
        }
        // Entry is veiled until positioning settles, and ui-test cannot see alpha, so wait the veil
        // out first: otherwise the row assertion below is satisfied by a pane the user cannot see.
        timeline.awaitEntryVeilLifted(timeoutMs = 45_000)
        // The notification deep jump is a cold cross-activity entry: a new route, a fresh Pager
        // generation, a placeholder request at a GLOBAL index ~260 rows deep in the one unbounded
        // timeline, its materialization, and the entry scroll must all complete before the row is
        // displayed. Budget it like the suite's other navigation/network-scale waits (20-45s) rather
        // than the generic 10s component wait, which is a slow-hosted-emulator flake edge here.
        //
        // Headroom, not a fix: the app's own materialization cap is TARGET_MATERIALIZATION_TIMEOUT_MS
        // (30s), so a 30s test budget ties with the production deadline and loses by construction
        // whenever the deep jump legitimately needs its full cap. 45s leaves the app room to finish
        // and still fails loudly if it never does.
        timeline.assertMessageVisible(firstUnread.tag(), timeoutMs = 45_000)
        // The deep jump no longer builds a narrow window around its target; it parks the viewport a
        // few hundred indices into the ONE unbounded list. This asserts the app still knows that is
        // not the bottom of the conversation — the FAB is the only externally observable statement
        // of `isAtEffectiveBottom`, which is the same predicate the viewport mark-read effect gates
        // on, i.e. the gate that decides whether a room-wide MARKREAD reaches every other client.
        //
        // Scope, stated honestly because it was measured: this room holds ~265 rows and `maxSize` is
        // 500, so Paging can retain the WHOLE room and the rows below this viewport are materialized.
        // The assertion therefore pins "a deep-jump viewport is not the bottom" and NOT the
        // placeholder rule that makes that true in a room too large to retain — a deliberately
        // reverted predicate still passes here. That rule is pinned where it can actually fail, on a
        // 400-row store with real placeholders: see `BoundedIslandMarkReadTest` and `ChatModelsTest`,
        // both of which do fail against the reverted predicate. Growing this fixture past `maxSize`
        // would move the pinned 199/200 row counts above, so the split is intentional.
        timeline.assertNotAtConversationBottom()
        scenario.scenario?.onActivity { it.recreate() }
        // Activity recreation replays the same deep entry from scratch on the same cold budget,
        // veil included.
        timeline.awaitEntryVeilLifted(timeoutMs = 45_000)
        timeline.assertMessageVisible(firstUnread.tag(), timeoutMs = 45_000)
        // Directional paging restores older rows; search then exposes its exact newest-200 cap. The
        // restore is driven by the recreated deep-jump viewport, so it too needs the test thread
        // pumping frames rather than blocking on the result.
        awaitWhileComposing(
            "newest-200 search cap for buffer=$bufferId",
            probeTimeoutMs = HISTORY_WINDOW_SETTLE_TIMEOUT_MS,
        ) {
            runProbe.awaitRows(
                token = token,
                bufferId = bufferId,
                count = 200,
                expectedExtras = emptySet(),
                expectedNewestOrdinal = 260,
                timeoutMs = HISTORY_WINDOW_SETTLE_TIMEOUT_MS,
            )
        }
        milestones.record("notification_restore_stable", "buffer=$bufferId event=${firstUnread.id}")
    }

    @Test
    fun bootstrappedNavigationSettingsAndBouncerSmoke() {
        val (bootstrap, network) = launchBootstrapped()
        SettingsRobot(compose).apply {
            open()
            appearance()
        }
        ThemeSheetRobot(compose).selectAyuDarkAndTrueBlack()
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onAllNodesWithTag("settings_theme_sheet", useUnmergedTree = true).assertCountEquals(0)
        // Return from Appearance to Settings, then exercise the category and bouncer routes.
        SettingsRobot(compose).apply {
            returnToRoot()
            searchPresence()
            returnToPresenceSearch()
            returnToRoot()
            networks()
        }
        NetworksRobot(compose).openRoot(network.rootId)
        BouncerRobot(compose).assertPanels()
        milestones.record("settings_bouncer_smoke", "root=${network.rootId}")
    }

    private suspend fun awaitMarkerAtLeast(
        bootstrap: E2eBootstrap,
        bufferId: Long,
        expected: TimelineAnchor,
        requireRemote: Boolean = false,
    ) {
        withTimeout(20_000) {
            bootstrap.seams.buffers().observeBuffer(bufferId).first { room ->
                room != null && markerAtLeast(room.localReadAnchorTime, room.localReadAnchorEventId, expected) &&
                    (!requireRemote || (room.readMarkerTime ?: Long.MIN_VALUE) >= expected.serverTime)
            }
        }
    }

    private fun assertMarkerAtLeast(
        bootstrap: E2eBootstrap,
        bufferId: Long,
        marker: io.github.trevarj.motd.data.db.MessageEntity,
    ) {
        val room =
            runBlocking {
                bootstrap.seams
                    .buffers()
                    .observeBuffer(bufferId)
                    .first { it != null }
            }
        assertTrue(markerAtLeast(room?.localReadAnchorTime, room?.localReadAnchorEventId, marker.anchor()))
    }

    private fun markerAtLeast(
        time: Long?,
        eventId: Long?,
        expected: TimelineAnchor,
    ): Boolean =
        time != null && eventId != null &&
            (time > expected.serverTime || (time == expected.serverTime && eventId >= expected.eventId))

    /**
     * Runs a readiness probe off the instrumentation thread while that thread keeps Compose running.
     *
     * `createEmptyComposeRule()` (androidx.compose.ui.test.junit4.v2) is not a passive observer of
     * the app. Constructing it swaps the process-wide `WindowRecomposerPolicy` factory, so the
     * ACTIVITY UNDER TEST composes on the rule's `TestMonotonicFrameClock` over a
     * `StandardTestDispatcher` rather than on the Choreographer. That scheduler only advances while
     * the instrumentation thread is inside a Compose/Espresso wait: `waitUntil` calls
     * `MainTestClock.runCurrent()` and `advanceTimeByFrame()` once per 10 ms iteration, and
     * `waitForIdle()` pumps until the app is idle and then stops.
     *
     * A bare `runBlocking { probe }` therefore parks the ONLY thread that can advance the app's
     * frames, and every composition-scoped continuation queued after the last Compose wait is
     * stranded for the whole wait: the entry-settle chain (its top-alignment loop suspends in
     * `withFrameNanos` and has no timeout), the seam demand effect (its debounce is VIRTUAL time),
     * the Paging generation watchers. Service-side work keeps running on real dispatchers —
     * connections, `EventProcessor`, Room — so a probe reading Room still watches rows land while
     * the UI that was supposed to ask for them cannot execute a line. That asymmetry is also why it
     * fails intermittently: it turns on whether the app happened to be parked on a real-time link at
     * the instant the last Compose wait called it idle.
     *
     * So the probe runs on a real dispatcher and the test thread sits in `waitUntil`, producing
     * frames until the probe finishes. This weakens NOTHING:
     *  * the probe is unchanged, and its assertion is still the one that decides the outcome;
     *  * its quiet window and budget stay WALL-CLOCK — `debounce` resolves against the collector's
     *    delay, which is [Dispatchers.Default] here and was `runBlocking`'s event loop before, never
     *    a `TestCoroutineScheduler`;
     *  * [COMPOSE_PUMP_GRACE_MS] keeps the pump strictly longer than the probe, so a real product
     *    stall still fails inside the probe with its snapshot counts intact;
     *  * the condition polls a [kotlinx.coroutines.Deferred] and touches no node, so — unlike a
     *    `performScrollToNode` poll (see `BaseRobot.scrollContainerTo`) — the wait cannot move the
     *    viewport whose demand is the thing under test.
     *
     * Do NOT simplify this back into `runBlocking { … }`. It reads as an equivalent wait and is not.
     */
    private fun <T> awaitWhileComposing(
        description: String,
        probeTimeoutMs: Long,
        probe: suspend () -> T,
    ): T {
        val probeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val probed = probeScope.async { probe() }
        return try {
            compose.waitUntil(description, probeTimeoutMs + COMPOSE_PUMP_GRACE_MS) { probed.isCompleted }
            // Completed either way by now, so this rethrows the probe's own AssertionError unchanged.
            runBlocking { probed.await() }
        } finally {
            probeScope.cancel()
        }
    }

    private suspend fun awaitWallClockAfter(serverTime: Long) {
        // IRC read markers are timestamp-only and therefore include every message in the same
        // millisecond. Keep the fixture's unread burst outside that intentionally inclusive tie.
        withTimeout(5_000) {
            while (System.currentTimeMillis() <= serverTime) delay(1)
        }
    }

    private fun io.github.trevarj.motd.data.db.MessageEntity.anchor(): TimelineAnchor = TimelineAnchor(serverTime, id, timelineOrder)

    private fun io.github.trevarj.motd.data.db.MessageEntity.tag(): String = "chat_message_${msgid ?: id}"

    companion object {
        /**
         * The four journeys share one hermetic soju/ergo stack and one channel, so their execution
         * order is load-bearing rather than incidental: `unreadHistory…` seeds ~260 backlog rows
         * that `sendEcho…` then sends and pages against, and the deep-jump steps depend on that
         * depth existing. JUnit's default sorter orders methods by name hashCode, so renaming,
         * adding, or removing a journey silently permutes the sequence and quietly changes what
         * every later journey runs against — the kind of change that surfaces as an unexplained
         * required-gate flake rather than a failing assertion.
         *
         * Per-journey channel isolation would be the stronger fix, but the fixture stack pre-joins
         * a single channel (`FixtureArgs.channel`, also used directly by `FixtureIrcClient`), so
         * deriving a channel per journey would mean new join plumbing on both the app seam and the
         * harness. Pinning the order is the least invasive change that still removes the silent
         * breakage: any permutation fails here, once, with an explicit message.
         */
        private val JOURNEY_ORDER =
            listOf(
                "unreadHistoryEntersAtMarkerAndRemainsCanonical",
                "bootstrappedNavigationSettingsAndBouncerSmoke",
                "sendEchoPersistsVisibleRowAndReconnects",
                "onboardingTrustsEphemeralTlsAndImportsNetwork",
            )

        /** `TestClass` applies the same sorter `BlockJUnit4ClassRunner.computeTestMethods` uses. */
        @BeforeClass
        @JvmStatic
        fun pinJourneyOrder() {
            val actual =
                TestClass(RequiredHeadlessE2eTest::class.java)
                    .getAnnotatedMethods(Test::class.java)
                    .map { it.name }
            assertEquals(
                "required E2E journey order changed: these journeys share one channel and the " +
                    "later ones depend on the backlog the earlier ones seed. Re-pin JOURNEY_ORDER " +
                    "only after confirming the new sequence still satisfies those dependencies.",
                JOURNEY_ORDER,
                actual,
            )
        }
    }
}
