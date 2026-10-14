package io.github.trevarj.motd

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
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

    @Test fun activityAttentionUsesVmWatermarkAcrossModesAllClearAndRestoration() {
        val connections =
            object : NoopConnectionManager() {
                override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Failed("existing cause", false)))
            }
        val model = activityModel(connections)
        val restoration = StateRestorationTester(compose)
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Hidden)
        val listState = mutableStateOf(ChatListState(rows = listOf(row()), networks = listOf(network), loading = false))
        var primary = 0
        try {
            restoration.setContent {
                val activity by model.networkActivity.collectAsState()
                val unseen by model.hasUnseenNetworkActivity.collectAsState()
                MotdTheme(dynamicColor = false) {
                    primary = MaterialTheme.colorScheme.primary.toArgb()
                    ChatListContent(
                        state = listState.value,
                        networkActivity = activity,
                        hasUnseenNetworkActivity = unseen,
                        onNetworkActivitySeen = model::markNetworkActivitySeen,
                        onClearNetworkActivityHistory = model::clearNetworkActivityHistory,
                        syncChrome = chrome.value,
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
            compose.waitUntil(5_000) { model.hasUnseenNetworkActivity.value }
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertIsDisplayed()
            openInspector()
            compose.waitUntil(5_000) { !model.hasUnseenNetworkActivity.value }
            compose.runOnIdle { assertEquals(1, model.networkActivity.value.unacknowledgedCount) }
            compose.onNodeWithTag("network_activity_close").performClick()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()

            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("new cause", false)) }
            compose.waitUntil(5_000) { model.networkActivity.value.latestAttentionSequence == 2L && model.hasUnseenNetworkActivity.value }
            val dot = compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true)
            dot.assertIsDisplayed()
            val pixels = dot.captureToImage().asAndroidBitmap()
            assertEquals(primary, pixels.getPixel(pixels.width / 2, pixels.height / 2))
            assertFalse(dot.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
            compose
                .onNodeWithContentDescription("More actions")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New network activity"))
                .assertTouchHeightIsEqualTo(48.dp)
                .assertTouchWidthIsEqualTo(48.dp)
            compose.runOnIdle { listState.value = listState.value.copy(selectedNetworkId = 1) }
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_more").performClick()
            compose.onNodeWithTag("chatlist_network_activity").assertTextEquals("Network activity · New")
            compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_network_activity").performClick()
            compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
            compose.waitUntil(5_000) { !model.hasUnseenNetworkActivity.value }
            compose.runOnIdle {
                assertEquals(1, model.networkActivity.value.active.size)
                assertEquals(1, model.networkActivity.value.unacknowledgedCount)
            }
            compose.onNodeWithTag("network_activity_close").performClick()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
            compose.runOnIdle {
                connections.connectionStates.value = mapOf(1L to IrcClientState.Connecting)
                chrome.value = ChatListSyncChrome.Syncing(1, 4)
            }
            compose.waitUntil(5_000) {
                model.networkActivity.value.active
                    .all { it.retrying }
            }
            compose.runOnIdle {
                connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("new cause", false))
                chrome.value = ChatListSyncChrome.Syncing(2, 4)
            }
            compose.waitUntil(5_000) {
                model.networkActivity.value.active
                    .all { it.settled }
            }
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
            compose.runOnIdle { assertEquals(2L, model.networkActivity.value.latestAttentionSequence) }

            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("new cause", true)) }
            compose.waitUntil(5_000) { model.networkActivity.value.latestAttentionSequence == 3L && model.hasUnseenNetworkActivity.value }
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_row_7").performTouchInput { longClick() }
            compose.onNodeWithTag("chatlist_selection_network_activity_new_dot", true).assertIsDisplayed()
            compose
                .onNodeWithTag("chatlist_selection_more")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New network activity"))
                .assertTouchHeightIsEqualTo(48.dp)
                .assertTouchWidthIsEqualTo(48.dp)
                .performClick()
            compose.onNodeWithTag("chatlist_network_activity").assertTextEquals("Network activity · New")
            compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_network_activity").performClick()
            compose.onNodeWithTag("network_activity_close").performClick()
            compose.onNodeWithTag("chatlist_selection_network_activity_new_dot", true).assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, model.networkActivity.value.unacknowledgedCount) }

            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("third cause", true)) }
            compose.waitUntil(5_000) { model.networkActivity.value.latestAttentionSequence == 4L && model.hasUnseenNetworkActivity.value }
            compose.onNodeWithTag("chatlist_selection_network_activity_new_dot", true).assertIsDisplayed()
            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to ready) }
            compose.waitUntil(5_000) {
                model.networkActivity.value.active
                    .isEmpty() && !model.hasUnseenNetworkActivity.value
            }
            compose.onNodeWithTag("chatlist_selection_network_activity_new_dot", true).assertDoesNotExist()
            compose.onNodeWithTag("chatlist_selection_more").performClick()
            compose.onNodeWithTag("chatlist_network_activity").assertTextEquals("Network activity").performClick()
            compose.onNodeWithTag("network_activity_recent_3_reason", true).assertDoesNotExist()
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_toggle"))
            compose.onNodeWithTag("network_activity_recent_toggle").assertTextEquals("Show").performClick()
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_3_reason"))
            compose.onNodeWithTag("network_activity_recent_3_reason", true).assertTextEquals("third cause")
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_clear"))
            compose.onNodeWithContentDescription("Clear recent network activity").performClick()
            compose.waitUntil(5_000) {
                model.networkActivity.value.recent
                    .isEmpty()
            }
            compose.onNodeWithTag("network_activity_recent_heading").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(4L, model.networkActivity.value.latestAttentionSequence)
                assertFalse(model.hasUnseenNetworkActivity.value)
            }
            compose.onNodeWithTag("network_activity_close").performClick()
            compose.onNodeWithTag("chatlist_selection_network_activity_new_dot", true).assertDoesNotExist()
            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("later episode", true)) }
            compose.waitUntil(5_000) { model.networkActivity.value.latestAttentionSequence == 5L && model.hasUnseenNetworkActivity.value }
            compose.onNodeWithTag("chatlist_selection_network_activity_new_dot", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_selection_close").performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_more").performClick()
            compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_network_activity").performClick()
            compose.waitUntil(5_000) { !model.hasUnseenNetworkActivity.value }
            compose.runOnIdle { assertEquals(1, model.networkActivity.value.unacknowledgedCount) }
            compose.onNodeWithTag("network_activity_close").performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
        } finally {
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test fun inspectorKeepsIndependentProgressWithoutLiveAnnouncements() {
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(12, 42, true))
        setList(chrome = { chrome.value })
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val headline = compose.onNodeWithTag("chatlist_title_activity", true)
        val titleProgress = compose.onNodeWithTag("chatlist_title_progress", true)
        headline.assertContentDescriptionEquals(resources.getString(R.string.network_activity_syncing))
        compose.onNodeWithTag("chatlist_title_status_text", true).assertTextEquals(resources.getString(R.string.chatlist_sync_progress, 28))
        assertEquals(12f / 42, titleProgress.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        assertFalse(titleProgress.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        openInspector()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals("bad certificate")
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_history_progress"))
        val progressNode = compose.onNodeWithTag("network_activity_history_progress", true)
        progressNode.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("History sync progress")))
        val progress = progressNode.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(12f / 42, progress.current, 0.001f)
        assertEquals(0f..1f, progress.range)
        assertFalse(progressNode.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("12/42", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(13, 42, true) }
        assertEquals(13f / 42, progressNode.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        assertFalse(progressNode.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(13, 0, true) }
        assertEquals(0f, progressNode.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_reason"))
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals("bad certificate")
        compose.onNodeWithTag("network_activity_close").performClick()
        headline.assertContentDescriptionEquals(resources.getString(R.string.network_activity_issues))
        compose.onNodeWithTag("chatlist_title_status_text", true).assertTextEquals(resources.getQuantityString(R.plurals.network_activity_issue_count, 1, 1))
        titleProgress.assertContentDescriptionEquals(resources.getQuantityString(R.plurals.network_activity_issue_count, 1, 1))
        assertEquals(ProgressBarRangeInfo.Indeterminate, titleProgress.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        openInspector()
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Hidden }
        progressNode.assertDoesNotExist()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
    }

    @Test fun inspectorKeepsFullReasonsAndLatestActivityWithoutAcknowledgement() {
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
        val beforeInspection = snapshot.value
        openInspector()
        for ((episode, reason) in listOf(1 to fullReason, 2 to "Queued history still retains this full error")) {
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_${episode}_reason"))
            compose.onNodeWithTag("network_activity_issue_${episode}_reason", true).assertTextEquals(reason)
        }
        compose.runOnIdle {
            assertEquals(beforeInspection, snapshot.value)
            assertEquals(emptyList<NetworkActivityAction>(), calls)
            snapshot.value =
                snapshot.value.copy(
                    active = snapshot.value.active + issue("New failure during inspection", buffer = 9, fatal = true, episode = 3).copy(chatName = "#chat-9"),
                    networks = snapshot.value.networks.map { it.copy(history = listOf(NetworkActivityChat(7, "#kotlin", HistorySyncStatus.Syncing))) },
                )
            chrome.value = ChatListSyncChrome.Syncing(31, 42, true)
        }
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_3_reason"))
        compose.onNodeWithTag("network_activity_issue_3_reason", true).assertTextEquals("New failure during inspection")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_row_7").assertIsDisplayed()
        val latest = snapshot.value
        openInspector()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_3_reason"))
        compose.onNodeWithTag("network_activity_issue_3_reason", true).assertTextEquals("New failure during inspection")
        compose.runOnIdle {
            assertEquals(latest, snapshot.value)
            assertEquals(emptyList<NetworkActivityAction>(), calls)
            assertFalse(snapshot.value.active.any { it.acknowledged })
        }
    }

    @Test fun scopedAndSelectionMenusKeepInspectorAcrossArchiveAndInvitations() {
        val calls = mutableListOf<NetworkActivityAction>()
        var seenCalls = 0
        val invitation = ChatListInvitation(11, 111, 1, "Libera", "alice", "#other", "invite", InviteState.PENDING, 1)
        setList(
            activity = { activity(listOf(issue(), issue("Full history reason in every mode", 7, false, 2))) },
            chrome = { ChatListSyncChrome.Syncing(1, 3) },
            listState = ChatListState(rows = listOf(row()), archivedRows = listOf(row(true).copy(bufferId = 8)), invitations = listOf(invitation), networks = listOf(network), selectedNetworkId = 1, loading = false),
            onAction = { _, action -> calls += action },
            onSeen = { seenCalls++ },
        )
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_row_7").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("Full history reason in every mode")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
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
        compose.onNodeWithTag("chatlist_more").assertDoesNotExist()
        val beforeArchiveInspection = seenCalls
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.runOnIdle { assertEquals(beforeArchiveInspection + 1, seenCalls) }
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("Full history reason in every mode")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_8").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_invitations_folder").performClick()
        compose.onNodeWithTag("chatlist_more").assertDoesNotExist()
        val beforeInvitationInspection = seenCalls
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.runOnIdle { assertEquals(beforeInvitationInspection + 1, seenCalls) }
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.runOnIdle { assertEquals(emptyList<NetworkActivityAction>(), calls) }
    }

    @Test fun inspectorProgressClampsBoundariesAndUnknownTotalKeepsZero() {
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(1, 3))
        setList(activity = { activity(emptyList()) }, chrome = { chrome.value })
        openInspector()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_history_progress"))
        for ((done, total, expected) in listOf(Triple(1, 3, 1f / 3), Triple(-1, 3, 0f), Triple(0, 3, 0f), Triple(3, 3, 1f), Triple(4, 3, 1f), Triple(0, 0, 0f), Triple(2, 0, 0f), Triple(2, -1, 0f))) {
            compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(done, total) }
            val progress = compose.onNodeWithTag("network_activity_history_progress", true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(expected, progress.current, 0.001f)
            assertEquals(0f..1f, progress.range)
        }
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
        val model = activityModel(connections)
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                val activity by model.networkActivity.collectAsState()
                MotdTheme(dynamicColor = false) {
                    ChatListContent(
                        state = state,
                        networkActivity = activity,
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
            openInspector()
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_network_1_more"))
            compose.onNodeWithContentDescription("Network actions for Libera").performClick()
            compose.onNodeWithTag("network_activity_network_1_connect").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) {
                model.networkActivity.value.active
                    .isEmpty() && connects == 1
            }
            compose.onAllNodesWithTag("network_activity_sheet").assertCountEquals(0)
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

    @Test fun inspectorShowsPendingNetworkWork() {
        val pending = listOf(NetworkActivityChat(7, "#kotlin", HistorySyncStatus.AwaitingConnection), NetworkActivityChat(8, "#other", HistorySyncStatus.Queued))
        val snapshot = activity(emptyList()).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Disconnected, pending)))
        setList(activity = { snapshot }, chrome = { ChatListSyncChrome.Waiting(2) })
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_network_1"))
        compose.onNodeWithText("Libera").assertIsDisplayed()
        compose.onNodeWithText("Waiting for a connection to sync history").assertIsDisplayed()
        compose.onNodeWithText("Queued for history sync").assertIsDisplayed()
        compose.onNodeWithText("#kotlin: Queued for history sync").assertDoesNotExist()
        compose.runOnIdle { assertEquals(pending, snapshot.networks.single().history) }
    }

    @Test fun acknowledgementIsExplicitAndUsesHonestCompactActiveSummary() {
        val state = mutableStateOf(activity(listOf(issue("history warning", 7, false))))
        setList(activity = { state.value }, onAction = { observed, action ->
            assertEquals(NetworkActivityAction.ACKNOWLEDGE, action)
            state.value = state.value.copy(active = listOf(observed.copy(acknowledged = true, revision = observed.revision + 1)))
        })
        openInspector()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_acknowledge"))
        compose.onNodeWithContentDescription("Acknowledge issue for #kotlin on Libera").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_issue_1").assertDoesNotExist()
        compose.onNodeWithTag("network_activity_acknowledged_summary").assertTextEquals("1 acknowledged issue still active")
        compose.onNodeWithText("No unacknowledged issues").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertDoesNotExist()
        compose.onNodeWithTag("network_activity_acknowledged_summary").assertTextEquals("1 acknowledged issue still active")
    }

    @Test fun historyRetryUsesExplicitActionAndClosesInspector() {
        val calls = mutableListOf<NetworkActivityAction>()
        val snapshot = mutableStateOf(activity(listOf(issue("history warning", 7, false))).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Connecting))))
        setList(activity = { snapshot.value }, onAction = { _, action -> calls += action })
        openInspector()
        compose.onNodeWithTag("network_activity_issue_1_retry").assertDoesNotExist()
        compose.runOnIdle {
            snapshot.value = snapshot.value.copy(networks = listOf(NetworkActivityNetwork(1, "Libera", ready)), active = snapshot.value.active.map { it.copy(retrying = true, settled = false) })
        }
        compose.onNodeWithTag("network_activity_issue_1_retry").assertDoesNotExist()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(active = snapshot.value.active.map { it.copy(retrying = false, settled = true) }) }
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_retry"))
        compose.onNodeWithContentDescription("Retry history for #kotlin on Libera").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(NetworkActivityAction.RETRY_HISTORY), calls) }
    }

    @Test fun inspectionShowsSelectableFullReasonOccurrencesAndExistingAction() {
        val full = "Server rejected authentication. " + "The full reason must stay available. ".repeat(15) + "END OF REASON"
        val calls = mutableListOf<NetworkActivityAction>()
        setList(activity = { activity(listOf(issue(full))) }, onAction = { _, action -> calls += action })
        openInspector()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_reason"))
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals(full)
        val preview = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(preview) }
        check(preview.single().lineCount <= 2)
        compose.onNodeWithTag("network_activity_issue_1_times", true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show issue details for Libera").performClick()
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
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_recent_2_reason", true).assertDoesNotExist()
        compose.onNodeWithTag("network_activity_recent_toggle").assertTextEquals("Show").performClick()
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
        openInspector("chatlist_selection_more")
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        openInspector("chatlist_selection_more")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<NetworkActivityAction>(), calls) }
    }

    @Test fun historyActionsHaveTargetLabelsAndPendingCertificateConnectIsDisabled() {
        val issues = listOf(issue("bad certificate"), issue("history reason", 7, false, 2))
        val state = activity(issues).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", ready, listOf(NetworkActivityChat(7, "#kotlin", HistorySyncStatus.Failed("history reason"))), true)))
        val calls = mutableListOf<NetworkActivityAction>()
        setList(activity = { state }, onAction = { _, action -> calls += action })
        openInspector()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_connect"))
        compose.onNodeWithTag("network_activity_issue_1_connect").assertIsNotEnabled()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_open"))
        compose.onNodeWithContentDescription("Open chat #kotlin on Libera").performClick()
        compose.runOnIdle { assertEquals(listOf(NetworkActivityAction.OPEN_CHAT), calls) }
    }

    @Test fun archiveAndInvitationsOmitTitleHistoryButInspectorStaysGlobal() {
        val state = mutableStateOf(activity(listOf(issue("history reason", 7, false, 2))).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", ready), NetworkActivityNetwork(2, "OFTC", ready))))
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
        assertFalse(
            compose
                .onNodeWithTag("chatlist_title_activity", true)
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.ContentDescription),
        )
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val attention = resources.getString(R.string.network_activity_issues)
        val oneIssue = resources.getQuantityString(R.plurals.network_activity_issue_count, 1, 1)
        val archiveBounds = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(active = listOf(issue()) + state.value.active) }
        compose.onNodeWithTag("chatlist_title_activity", true).assertContentDescriptionEquals(attention)
        compose.onNodeWithTag("chatlist_title_status_text", true).assertTextEquals(oneIssue)
        compose.onNodeWithTag("chatlist_title_attention", true).assertIsDisplayed().assertContentDescriptionEquals(oneIssue)
        compose.onNodeWithTag("chatlist_title_progress", true).assertDoesNotExist()
        assertEquals(archiveBounds, compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_history_progress"))
        compose.onNodeWithTag("network_activity_history_progress", true).assertIsDisplayed()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_network_2"))
        compose.onNodeWithTag("network_activity_network_2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(active = state.value.active.filter { it.bufferId != null }) }
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("history reason")
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_invitations_folder").performClick()
        assertFalse(
            compose
                .onNodeWithTag("chatlist_title_activity", true)
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.ContentDescription),
        )
        val invitationBounds = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(active = listOf(issue()) + state.value.active) }
        compose.onNodeWithTag("chatlist_title_activity", true).assertContentDescriptionEquals(attention)
        compose.onNodeWithTag("chatlist_title_status_text", true).assertTextEquals(oneIssue)
        compose.onNodeWithTag("chatlist_title_attention", true).assertIsDisplayed().assertContentDescriptionEquals(oneIssue)
        compose.onNodeWithTag("chatlist_title_progress", true).assertDoesNotExist()
        assertEquals(invitationBounds, compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_history_progress"))
        compose.onNodeWithTag("network_activity_history_progress", true).assertIsDisplayed()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithText("Invitations").assertIsDisplayed()
    }

    @Test fun sheetForegroundsCurrentIssuesAndCollapsesClearableRecent() {
        val full = "History request timed out.\nThe server supplied these complete diagnostic details.\nThe original cause is retained during retry.\nNo history recovery has been inferred."
        val current = issue(full, 7, false).copy(retrying = true, settled = false)
        val acknowledged = issue("Acknowledged connection cause", episode = 2).copy(acknowledged = true)
        val snapshot =
            mutableStateOf(
                activity(listOf(current, acknowledged)).copy(
                    networks =
                        listOf(
                            NetworkActivityNetwork(1, "Libera", ready, (7L..40L).map { NetworkActivityChat(it, "#chat-$it", HistorySyncStatus.Syncing) }),
                            NetworkActivityNetwork(2, "OFTC", IrcClientState.Disconnected),
                        ),
                    recent = listOf(issue("Earlier history failure", 9, false, 4).copy(disposition = NetworkActivityDisposition.NO_LONGER_REPORTED), issue("Earlier connection cause", episode = 3).copy(disposition = NetworkActivityDisposition.SUPERSEDED)),
                    latestAttentionSequence = 4,
                ),
            )
        val networkActions = mutableListOf<Pair<Long, NetworkActivityAction>>()
        setList(activity = { snapshot.value }, unseen = true, onAction = { observed, action ->
            assertEquals(NetworkActivityAction.ACKNOWLEDGE, action)
            snapshot.value = snapshot.value.copy(active = snapshot.value.active.map { if (it.episodeId == observed.episodeId) it.copy(acknowledged = true) else it })
        }, onClearRecent = {
            snapshot.value = snapshot.value.copy(recent = emptyList())
        }, onNetworkAction = { id, action -> networkActions += id to action })
        compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals(full)
        compose.onNodeWithTag("network_activity_issue_1_times", true).assertDoesNotExist()
        compose.onNodeWithTag("network_activity_issue_1_retry").assertDoesNotExist()
        val actionCenters =
            listOf("details", "open", "acknowledge").map {
                compose
                    .onNodeWithTag("network_activity_issue_1_$it")
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y
            }
        check(actionCenters.max() - actionCenters.min() <= 1f) { "Recovering issue actions must share one row" }
        compose.onNodeWithTag("network_activity_issue_2").assertDoesNotExist()
        compose.onNodeWithTag("network_activity_acknowledged_summary").assertTextEquals("1 acknowledged issue still active")
        compose.onNodeWithTag("network_activity_recent_4").assertDoesNotExist()
        val preview = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(preview) }
        check(preview.single().lineCount <= 2)
        compose.onNodeWithText("#chat-7: Syncing history…").assertDoesNotExist()
        val issueTop =
            compose
                .onNodeWithTag("network_activity_issue_1")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val networkTop =
            compose
                .onNodeWithTag("network_activity_network_1")
                .fetchSemanticsNode()
                .boundsInRoot.top
        check(issueTop < networkTop)

        compose.onNodeWithContentDescription("Show issue details for #kotlin on Libera").performClick()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals(full)
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_times"))
        compose.onNodeWithText("Occurrences: 3", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide issue details for #kotlin on Libera").performClick()
        compose.onNodeWithTag("network_activity_issue_1_times", true).assertDoesNotExist()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_toggle"))
        compose.onNodeWithTag("network_activity_recent_toggle").assertTextEquals("Show").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_4_reason"))
        compose.onNodeWithTag("network_activity_recent_4_reason", true).assertTextEquals("Earlier history failure")
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_3_reason"))
        compose.onNodeWithText("Replaced by a newer cause").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_recent_toggle"))
        compose.onNodeWithTag("network_activity_recent_toggle").assertTextEquals("Hide").performClick()
        compose.onNodeWithTag("network_activity_recent_4").assertDoesNotExist()
        val beforeClear = snapshot.value
        compose.onNodeWithContentDescription("Clear recent network activity").performClick()
        compose.onNodeWithTag("network_activity_recent_heading").assertDoesNotExist()
        compose.runOnIdle { assertEquals(beforeClear.copy(recent = emptyList()), snapshot.value) }
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1"))
        compose.onNodeWithTag("network_activity_issue_1_acknowledge").performClick()
        compose.onNodeWithTag("network_activity_issue_1").assertDoesNotExist()
        compose.onNodeWithText("No unacknowledged issues").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_acknowledged_summary").assertTextEquals("2 acknowledged issues still active")
        compose.onNodeWithTag("network_activity_close").performClick()
        // A stale upstream unseen flag cannot promote acknowledged-only content.
        compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
        for ((action, suffix) in listOf(NetworkActivityAction.SETTINGS to "settings", NetworkActivityAction.SERVER_MESSAGES to "server", NetworkActivityAction.CONNECT to "connect")) {
            compose.onNodeWithTag("chatlist_more").performClick()
            compose.onNodeWithTag("chatlist_network_activity").performClick()
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_network_2_more"))
            compose.onNodeWithContentDescription("Network actions for OFTC").assertTouchHeightIsEqualTo(48.dp).performClick()
            compose.onNodeWithTag("network_activity_network_2_menu").assertIsDisplayed()
            compose.onNodeWithContentDescription("Connect to OFTC").assertIsDisplayed()
            compose.onNodeWithContentDescription("Network settings for OFTC").assertIsDisplayed()
            compose.onNodeWithContentDescription("Server messages for OFTC").assertIsDisplayed()
            compose.onNodeWithTag("network_activity_network_2_$suffix").performClick()
            compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
            compose.runOnIdle { assertEquals(2L to action, networkActions.last()) }
        }
    }

    private fun activityModel(
        connections: NoopConnectionManager,
    ) = ChatListViewModel(
        bufferRepository = FakeBuffers(),
        networkRepository = FakeNetworks(listOf(network)),
        connectionManager = connections,
        historyResync =
            object : HistoryResyncController {
                override suspend fun reconcileBuffer(
                    buffer: BufferEntity,
                    client: IrcClient,
                    statusOwnerId: Long,
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

    private fun setList(
        activity: () -> NetworkActivityState = { this.activity() },
        chrome: () -> ChatListSyncChrome = { ChatListSyncChrome.Hidden },
        listState: ChatListState = ChatListState(networks = listOf(network), loading = false),
        onAction: (NetworkActivityIssue, NetworkActivityAction) -> Unit = { _, _ -> },
        onSeen: () -> Unit = {},
        onClearRecent: () -> Unit = {},
        onNetworkAction: (Long, NetworkActivityAction) -> Unit = { _, _ -> },
        unseen: Boolean = false,
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(state = listState, networkActivity = activity(), hasUnseenNetworkActivity = unseen, syncChrome = chrome(), onActivityAction = onAction, onActivityNetworkAction = onNetworkAction, onClearNetworkActivityHistory = onClearRecent, onNetworkActivitySeen = onSeen, onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {}, onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> }, onJoinChannel = { _, _, _ -> }, onMessageUser = { _, _ -> })
            }
        }
    }

    private fun openInspector(menuTag: String = "chatlist_more") {
        compose.onNodeWithTag(menuTag).performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
    }
}
