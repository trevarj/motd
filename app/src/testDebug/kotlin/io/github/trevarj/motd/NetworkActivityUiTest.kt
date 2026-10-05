package io.github.trevarj.motd

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.sync.NoopHistoryGapFiller
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.FakeReadMarkers
import io.github.trevarj.motd.gesture.FakeSettings
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListInvitation
import io.github.trevarj.motd.ui.chatlist.ChatListState
import io.github.trevarj.motd.ui.chatlist.ChatListSyncChrome
import io.github.trevarj.motd.ui.chatlist.ChatListViewModel
import io.github.trevarj.motd.ui.chatlist.NetworkActivityAction
import io.github.trevarj.motd.ui.chatlist.NetworkActivityBanner
import io.github.trevarj.motd.ui.chatlist.NetworkActivityChat
import io.github.trevarj.motd.ui.chatlist.NetworkActivityDisposition
import io.github.trevarj.motd.ui.chatlist.NetworkActivityIssue
import io.github.trevarj.motd.ui.chatlist.NetworkActivityKind
import io.github.trevarj.motd.ui.chatlist.NetworkActivityNetwork
import io.github.trevarj.motd.ui.chatlist.NetworkActivityState
import io.github.trevarj.motd.ui.chatlist.fakeDickordLabsPrefs
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NetworkActivityUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()
    private val ready = IrcClientState.Ready("me", emptySet(), emptyMap())
    private val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")

    private fun issue(
        reason: String = "bad certificate",
        buffer: Long? = null,
        fatal: Boolean = true,
        episode: Long = 1,
    ) = NetworkActivityIssue(episode, 0, 1, buffer, "Libera", buffer?.let { "#kotlin" }, if (buffer == null) NetworkActivityKind.CONNECTION else NetworkActivityKind.HISTORY_FAILED, reason, fatal, 1_000, 2_000, occurrences = 3)

    private fun activity(issues: List<NetworkActivityIssue> = listOf(issue())) = NetworkActivityState(networks = listOf(NetworkActivityNetwork(1, "Libera", if (issues.any { it.bufferId == null }) IrcClientState.Failed("bad certificate", true) else ready)), active = issues)

    private fun row(archived: Boolean = false) = ChatListRow(bufferId = 7, networkId = 1, networkName = "Libera", displayName = "#kotlin", type = BufferType.CHANNEL, pinned = false, muted = false, lastMessageText = "hi", lastMessageSender = "alice", lastMessageTime = 1, unreadCount = 0, mentionCount = 0, archived = archived)

    @Test fun oneBannerKeepsErrorAndIndependentProgressWithOnlyHeadlineLive() {
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(12, 42, true))
        setList(chrome = { chrome.value })
        compose.onAllNodesWithTag("chatlist_status_banner").assertCountEquals(1)
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Libera: bad certificate")
        compose.onNodeWithTag("chatlist_status_count", true).assertTextEquals("12/42")
        val progress = compose.onNodeWithTag("chatlist_status_progress", true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(12f / 42, progress.current, 0.001f)
        assertEquals(LiveRegionMode.Polite, compose.onNodeWithTag("chatlist_status_label", true).fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        assertFalse(
            compose
                .onNodeWithTag("chatlist_status_banner")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.LiveRegion),
        )
        for (tag in listOf("chatlist_status_count", "chatlist_status_progress", "chatlist_status_summary")) {
            assertFalse(
                compose
                    .onNodeWithTag(tag, true)
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.LiveRegion),
            )
        }
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(13, 42, true) }
        compose.onNodeWithTag("chatlist_status_count", true).assertTextEquals("13/42")
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Libera: bad certificate")
    }

    @Test fun hideAndRestoreBannerKeepsInspectorAndLatestActivityAvailable() {
        val fullReason = "Server rejected authentication. " + "Keep every detail inspectable. ".repeat(8) + "END OF REASON"
        val snapshot =
            mutableStateOf(
                activity(listOf(issue(fullReason), issue("Queued history still retains this full error", 7, false, 2))).copy(
                    networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Failed(fullReason, true), listOf(NetworkActivityChat(7, "#kotlin", HistorySyncStatus.Queued)))),
                ),
            )
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(12, 42, true))
        val calls = mutableListOf<NetworkActivityAction>()
        setList(
            activity = { snapshot.value },
            chrome = { chrome.value },
            listState = ChatListState(rows = (7L..12L).map { row().copy(bufferId = it, displayName = "#chat-$it") }, networks = listOf(network), loading = false),
            onAction = { _, action -> calls += action },
        )
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        assertEquals(
            listOf("Open network activity"),
            compose.onNodeWithTag("chatlist_status_banner").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        compose
            .onNodeWithContentDescription("Hide network activity banner")
            .assertTouchHeightIsEqualTo(48.dp)
            .assertTouchWidthIsEqualTo(48.dp)
        val beforeHide = snapshot.value
        // Real pointer input must be consumed by Hide, not also open its clickable parent.
        compose.onNodeWithTag("chatlist_status_hide").performTouchInput { click() }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_row_7").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(beforeHide, snapshot.value)
            assertEquals(emptyList<NetworkActivityAction>(), calls)
        }

        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertTextEquals("Show network activity banner")
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithText("#kotlin: Queued for history sync").assertIsDisplayed()
        for ((episode, reason) in listOf(1 to fullReason, 2 to "Queued history still retains this full error")) {
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_${episode}_reason"))
            compose.onNodeWithTag("network_activity_issue_${episode}_reason", true).assertTextEquals(reason)
        }
        compose.runOnIdle {
            snapshot.value =
                snapshot.value.copy(
                    active = snapshot.value.active + issue("New failure while hidden", buffer = 9, fatal = true, episode = 3).copy(chatName = "#chat-9"),
                    networks = snapshot.value.networks.map { it.copy(history = listOf(NetworkActivityChat(7, "#kotlin", HistorySyncStatus.Syncing))) },
                )
            chrome.value = ChatListSyncChrome.Syncing(31, 42, true)
        }
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_network_1"))
        compose.onNodeWithText("#kotlin: Syncing history…").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_3_reason"))
        compose.onNodeWithTag("network_activity_issue_3_reason", true).assertTextEquals("New failure while hidden")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        // Let healthy idle and a new episode pass while hidden; neither may auto-restore it.
        val latest = snapshot.value.copy(active = snapshot.value.active.filter { it.episodeId != 1L })
        compose.runOnIdle {
            snapshot.value = activity(emptyList())
            chrome.value = ChatListSyncChrome.Hidden
        }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.runOnIdle {
            snapshot.value = latest
            chrome.value = ChatListSyncChrome.Syncing(32, 42, true)
        }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").performClick()
        compose.onNodeWithTag("chatlist_network_activity").assertDoesNotExist()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("#chat-9 on Libera: New failure while hidden")
        compose.onNodeWithTag("chatlist_status_issue_count", true).assertTextEquals("2 issues")
        compose.onNodeWithTag("chatlist_status_count", true).assertTextEquals("32/42")
        val progress = compose.onNodeWithTag("chatlist_status_progress", true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(32f / 42, progress.current, 0.001f)
        compose.runOnIdle {
            assertEquals(latest, snapshot.value)
            assertEquals(emptyList<NetworkActivityAction>(), calls)
            assertFalse(snapshot.value.active.any { it.acknowledged })
        }
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
    }

    @Test fun hiddenBannerSurvivesSaveableRecreationAndRestoresExplicitly() {
        val restoration = StateRestorationTester(compose)
        val snapshot = mutableStateOf(activity())
        restoration.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(state = ChatListState(networks = listOf(network), loading = false), networkActivity = snapshot.value, connectionNoticeVisible = true, onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {}, onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> }, onJoinChannel = { _, _, _ -> }, onMessageUser = { _, _ -> })
            }
        }
        compose.onNodeWithTag("chatlist_status_hide").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { snapshot.value = activity(listOf(issue("Failure after recreation", episode = 2))) }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("Failure after recreation")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").performClick()
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Libera: Failure after recreation")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
    }

    @Test fun scopedAndSelectionOverflowRestoreWithoutChangingArchiveOrInvitationMode() {
        val calls = mutableListOf<NetworkActivityAction>()
        val invitation = ChatListInvitation(11, 111, 1, "Libera", "alice", "#other", "invite", InviteState.PENDING, 1)
        setList(
            activity = { activity(listOf(issue(), issue("Full history reason in every mode", 7, false, 2))) },
            chrome = { ChatListSyncChrome.Syncing(1, 3) },
            listState = ChatListState(rows = listOf(row()), archivedRows = listOf(row(true).copy(bufferId = 8)), invitations = listOf(invitation), networks = listOf(network), selectedNetworkId = 1, loading = false),
            onAction = { _, action -> calls += action },
        )
        compose.onNodeWithTag("chatlist_status_hide").performClick()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_status_hide").performClick()
        compose.onNodeWithTag("chatlist_row_7").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("Full history reason in every mode")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").performClick()
        compose.onNodeWithTag("chatlist_network_activity").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_count", true).assertTextEquals("1/3")
        compose.onNodeWithTag("chatlist_status_hide").performClick()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        val revealLabel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.chatlist_archived_reveal_action)
        val reveal =
            compose
                .onNodeWithTag("chatlist_archive_pull_target")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == revealLabel }
        compose.runOnIdle { check(reveal.action()) }
        compose.onNodeWithTag("chatlist_archived_folder").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("Full history reason in every mode")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_8").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_issue_count", true).assertTextEquals("1 issue")
        compose.onNodeWithTag("chatlist_status_progress", true).assertDoesNotExist()
        compose.onNodeWithTag("chatlist_status_hide").performClick()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_invitations_folder").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").performClick()
        compose.onNodeWithTag("chatlist_status_count", true).assertTextEquals("1/3")
        compose.runOnIdle { assertEquals(emptyList<NetworkActivityAction>(), calls) }
    }

    @Test fun narrowBannerKeepsTwoTextRowsAndReservesProgressFraction() {
        val longReason = "Authentication rejected with a detailed reason. ".repeat(12)
        val snapshot =
            activity(listOf(issue(longReason))).copy(
                networks =
                    listOf(
                        NetworkActivityNetwork(1, "Libera", IrcClientState.Failed(longReason, true)),
                        NetworkActivityNetwork(2, "A very long network display name that must not displace the progress count", IrcClientState.Connecting),
                        NetworkActivityNetwork(3, "OFTC", ready),
                    ),
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.width(240.dp)) {
                    NetworkActivityBanner(snapshot, ChatListSyncChrome.Syncing(12, 42, true), connectionNoticeVisible = true, includeHistory = true, onInspect = {}, onHide = {})
                }
            }
        }

        fun layout(tag: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val headline = layout("chatlist_status_label")
        val supporting = layout("chatlist_status_summary")
        check(headline.lineCount <= 1)
        check(supporting.lineCount <= 1)
        check(headline.isLineEllipsized(0))
        check(supporting.isLineEllipsized(0))
        val count = compose.onNodeWithTag("chatlist_status_count", true)
        count.assertIsDisplayed().assertTextEquals("12/42")
        val fraction = layout("chatlist_status_count")
        // String Text semantics reconstruct a parent-width paragraph, not its painted intrinsic
        // frame. Protect the measured fraction's actual content extents instead of that frame.
        check(fraction.lineCount == 1 && !fraction.isLineEllipsized(0)) {
            "Fraction lines=${fraction.lineCount}, ellipsized=${fraction.isLineEllipsized(0)}"
        }
        check(fraction.getLineEnd(0, visibleEnd = true) == fraction.layoutInput.text.length) {
            "Fraction visible end=${fraction.getLineEnd(0, visibleEnd = true)} of ${fraction.layoutInput.text.length}"
        }
        check(fraction.getLineLeft(0) >= 0f && fraction.getLineRight(0) <= fraction.size.width.toFloat()) {
            "Fraction line ${fraction.getLineLeft(0)}..${fraction.getLineRight(0)} exceeds ${fraction.size.width}px"
        }
        for (offset in 0 until fraction.layoutInput.text.length) {
            val glyph = fraction.getBoundingBox(offset)
            check(glyph.left >= 0f && glyph.right <= fraction.size.width.toFloat()) {
                "Fraction glyph $offset at ${glyph.left}..${glyph.right} exceeds ${fraction.size.width}px"
            }
        }
        check(layout("chatlist_status_issue_count").lineCount <= 1)
        assertFalse(count.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        val summaryBounds = compose.onNodeWithTag("chatlist_status_summary", true).fetchSemanticsNode().boundsInRoot
        check(
            kotlin.math.abs(
                summaryBounds.center.y -
                    count
                        .fetchSemanticsNode()
                        .boundsInRoot.center.y,
            ) <= 1f,
        )
        val textNodes =
            compose
                .onAllNodes(
                    hasAnyAncestor(hasTestTag("chatlist_status_banner")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        val rows = mutableListOf<Float>()
        for (node in textNodes.sortedBy { it.boundsInRoot.center.y }) {
            val center = node.boundsInRoot.center.y
            if (rows.none { kotlin.math.abs(it - center) <= 1f }) rows += center
        }
        check(rows.size <= 2) { "Banner rendered ${rows.size} distinct text rows" }
        compose.onNodeWithTag("chatlist_status_progress", true).assertIsDisplayed()
    }

    @Test fun connectActionClosesInspectorAndRecoversThroughTheViewModel() {
        var connects = 0
        val connections =
            object : NoopConnectionManager() {
                override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Failed("observed connection failure", true)))

                override suspend fun connect(networkId: Long) {
                    connects++
                    connectionStates.value = mapOf(networkId to ready)
                }
            }
        val model =
            ChatListViewModel(
                bufferRepository = FakeBuffers(),
                networkRepository = FakeNetworks(listOf(network)),
                connectionManager = connections,
                historyResync =
                    object : HistoryResyncController {
                        override suspend fun reconcileBuffer(
                            buffer: BufferEntity,
                            client: IrcClient,
                            preserveUnread: Boolean,
                            isCurrent: () -> Boolean,
                        ) = HistoryResyncState.Idle

                        override suspend fun reconcilePendingMessage(
                            buffer: BufferEntity,
                            client: IrcClient,
                            isCurrent: () -> Boolean,
                        ) = HistoryResyncState.Idle
                    },
                gapFiller = NoopHistoryGapFiller,
                channelCloseCoordinator =
                    object : ChannelCloseCoordinator {
                        override fun start() = Unit

                        override suspend fun requestClose(bufferId: Long) = Unit
                    },
                readMarkerRepository = FakeReadMarkers(),
                settingsRepository = FakeSettings(),
                onboardingPrefs =
                    object : OnboardingPrefs {
                        override val completed = flowOf(true)

                        override suspend fun markCompleted() = Unit
                    },
                globalFeedPrefs =
                    object : GlobalFeedPrefs {
                        override val enabled = flowOf(false)

                        override suspend fun setEnabled(enabled: Boolean) = Unit
                    },
                dickordLabsPrefs = fakeDickordLabsPrefs(),
                savedStateHandle = SavedStateHandle(),
                appVisibility =
                    object : AppVisibility {
                        override val onScreen = MutableStateFlow(false)
                    },
            )
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                val activity by model.networkActivity.collectAsState()
                MotdTheme(dynamicColor = false) {
                    ChatListContent(
                        state = state,
                        networkActivity = activity,
                        connectionNoticeVisible = true,
                        onActivityAction = { observed, action -> model.networkActivityAction(observed, action, {}, {}) },
                        onActivityNetworkAction = { id, action -> model.networkActivityNetworkAction(id, action, {}, {}) },
                        onOpenBuffer = {},
                        onOpenSettings = {},
                        onOpenSearch = {},
                        onSetPinned = { _, _ -> },
                        onSetMuted = { _, _ -> },
                        onJoinChannel = { _, _, _ -> },
                        onMessageUser = { _, _ -> },
                    )
                }
            }
            compose.waitUntil(5_000) { model.networkActivity.value.active.size == 1 }
            compose.onNodeWithTag("chatlist_status_banner").performClick()
            compose.onNodeWithTag("network_activity_network_1_connect").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) {
                model.networkActivity.value.active
                    .isEmpty() && connects == 1
            }
            compose.onAllNodesWithTag("network_activity_sheet").assertCountEquals(0)
            compose.onAllNodesWithTag("chatlist_status_banner").assertCountEquals(0)
            compose.runOnIdle {
                assertEquals(1, connects)
                assertEquals(
                    NetworkActivityDisposition.CONNECTED,
                    model.networkActivity.value.recent
                        .single()
                        .disposition,
                )
                assertEquals(
                    "observed connection failure",
                    model.networkActivity.value.recent
                        .single()
                        .reason,
                )
            }
        } finally {
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test fun highestSeverityThenOldestIssueWinsWithoutRotation() {
        val advisory = issue("advisory", 7, false, 1).copy(kind = NetworkActivityKind.HISTORY_PARTIAL)
        val ordinary = issue("timeout", fatal = false, episode = 2)
        val fatal = issue("action required", episode = 3)
        setList(activity = { activity(listOf(advisory, ordinary, fatal, issue("newer fatal", episode = 4))) })
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Libera: action required")
        compose.onNodeWithTag("chatlist_status_issue_count", true).assertTextEquals("4 issues")
    }

    @Test fun healthyIdleHidesAndAcknowledgedRecordsDoNotPromoteButProgressStillDoes() {
        val state = mutableStateOf(activity(listOf(issue().copy(acknowledged = true))))
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Hidden)
        setList(activity = { state.value }, chrome = { chrome.value })
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(1, 3) }
        compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_issue_count", true).assertDoesNotExist()
        compose.runOnIdle {
            chrome.value = ChatListSyncChrome.Hidden
            state.value = activity(emptyList())
        }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
    }

    @Test fun acknowledgementIsExplicitAndKeepsActiveRecordInspectable() {
        val state = mutableStateOf(activity(listOf(issue("history warning", 7, false))))
        setList(activity = { state.value }, onAction = { observed, action ->
            assertEquals(NetworkActivityAction.ACKNOWLEDGE, action)
            state.value = state.value.copy(active = listOf(observed.copy(acknowledged = true, revision = observed.revision + 1)))
        })
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_acknowledge"))
        compose.onNodeWithContentDescription("Acknowledge issue for #kotlin on Libera").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithText("Acknowledged · still unresolved").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_reason"))
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals("history warning")
    }

    @Test fun historyRetryUsesExplicitActionAndClosesInspector() {
        val calls = mutableListOf<NetworkActivityAction>()
        setList(activity = { activity(listOf(issue("history warning", 7, false))) }, onAction = { _, action -> calls += action })
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_retry"))
        compose.onNodeWithContentDescription("Retry history for #kotlin on Libera").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(NetworkActivityAction.RETRY_HISTORY), calls) }
    }

    @Test fun inspectionShowsSelectableFullReasonOccurrencesAndExistingAction() {
        val full = "Server rejected authentication. " + "The full reason must stay available. ".repeat(15) + "END OF REASON"
        val calls = mutableListOf<NetworkActivityAction>()
        setList(activity = { activity(listOf(issue(full))) }, onAction = { _, action -> calls += action })
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_reason"))
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals(full)
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_times"))
        compose.onNodeWithText("Occurrences: 3", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_settings"))
        assertEquals(listOf("Network settings for Libera"), compose.onNodeWithTag("network_activity_issue_1_settings").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        compose.onNodeWithTag("network_activity_issue_1_settings").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(NetworkActivityAction.SETTINGS), calls) }
    }

    @Test fun recentActivityHasHonestDispositionAndDeletedTargetsHaveNoActions() {
        val connected = issue("earlier timeout").copy(disposition = NetworkActivityDisposition.CONNECTED)
        val removed = issue("full earlier history reason", 7, false, 2).copy(disposition = NetworkActivityDisposition.NO_LONGER_REPORTED, targetAvailable = false)
        val state = activity(emptyList()).copy(recent = listOf(removed, connected))
        val calls = mutableListOf<NetworkActivityAction>()
        setList(activity = { state }, onAction = { _, action -> calls += action })
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_2_reason"))
        compose.onNodeWithTag("network_activity_recent_2_reason", true).assertTextEquals("full earlier history reason")
        compose.onNodeWithText("No longer reported").assertIsDisplayed()
        compose.onNodeWithText("Target removed · actions unavailable").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_recent_2_open").assertDoesNotExist()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_1_settings"))
        compose.onNodeWithTag("network_activity_recent_1_settings").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(NetworkActivityAction.SETTINGS), calls) }
    }

    @Test fun sheetBackAndClosePreserveScopeSelectionAndDoNotAcknowledge() {
        val calls = mutableListOf<NetworkActivityAction>()
        setList(listState = ChatListState(rows = listOf(row()), networks = listOf(network), selectedNetworkId = 1, loading = false), onAction = { _, action -> calls += action })
        compose.onNodeWithTag("chatlist_row_7").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<NetworkActivityAction>(), calls) }
    }

    @Test fun historyActionsHaveTargetLabelsAndPendingCertificateConnectIsDisabled() {
        val issues = listOf(issue("bad certificate"), issue("history reason", 7, false, 2))
        val state = activity(issues).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", ready, listOf(NetworkActivityChat(7, "#kotlin", HistorySyncStatus.Failed("history reason"))), true)))
        val calls = mutableListOf<NetworkActivityAction>()
        setList(activity = { state }, onAction = { _, action -> calls += action })
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_connect"))
        compose.onNodeWithTag("network_activity_issue_1_connect").assertIsNotEnabled()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_open"))
        compose.onNodeWithContentDescription("Open chat #kotlin on Libera").performClick()
        compose.runOnIdle { assertEquals(listOf(NetworkActivityAction.OPEN_CHAT), calls) }
    }

    @Test fun archiveAndInvitationsOmitHistoryPromotionAndProgressButInspectorStaysGlobal() {
        val state = mutableStateOf(activity(listOf(issue(), issue("history reason", 7, false, 2))).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", ready), NetworkActivityNetwork(2, "OFTC", ready))))
        val invitation = ChatListInvitation(11, 111, 1, "Libera", "alice", "#other", "invite", InviteState.PENDING, 1)
        setList(activity = { state.value }, chrome = { ChatListSyncChrome.Syncing(1, 3) }, listState = ChatListState(rows = listOf(row()), archivedRows = listOf(row(true).copy(bufferId = 8)), invitations = listOf(invitation), networks = listOf(network), loading = false))
        val revealArchiveLabel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.chatlist_archived_reveal_action)
        val revealArchive =
            compose
                .onNodeWithTag("chatlist_archive_pull_target")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == revealArchiveLabel }
        compose.runOnIdle { check(revealArchive.action()) }
        compose.onNodeWithTag("chatlist_archived_folder").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_progress", true).assertDoesNotExist()
        compose.onNodeWithTag("chatlist_status_issue_count", true).assertTextEquals("1 issue")
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_network_2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(active = state.value.active.filter { it.bufferId != null }) }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("history reason")
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_invitations_folder").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(active = listOf(issue())) }
        compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_progress", true).assertDoesNotExist()
    }

    @Test fun waitingBypassesConnectionGraceAndDescribesQueuedHistory() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                NetworkActivityBanner(NetworkActivityState(networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Connecting))), ChatListSyncChrome.Waiting(5), connectionNoticeVisible = true, includeHistory = true, onInspect = {}, onHide = {})
            }
        }
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Connecting to Libera…")
        compose.onNodeWithTag("chatlist_status_summary", true).assertTextEquals(ApplicationProvider.getApplicationContext<Context>().getString(R.string.network_activity_queued))
    }

    private fun setList(
        activity: () -> NetworkActivityState = { this.activity() },
        chrome: () -> ChatListSyncChrome = { ChatListSyncChrome.Hidden },
        listState: ChatListState = ChatListState(networks = listOf(network), loading = false),
        onAction: (NetworkActivityIssue, NetworkActivityAction) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(state = listState, networkActivity = activity(), connectionNoticeVisible = true, syncChrome = chrome(), onActivityAction = onAction, onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {}, onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> }, onJoinChannel = { _, _, _ -> }, onMessageUser = { _, _ -> })
            }
        }
    }
}
