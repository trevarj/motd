package io.github.trevarj.motd

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.LayoutDensity
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
import io.github.trevarj.motd.ui.chatlist.NetworkActivityBannerSession
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

    @Test fun hiddenActivityAttentionSmokeUsesVmSessionAcrossModesAllClearAndRestoration() {
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
                val hidden by model.networkActivityBannerHidden.collectAsState()
                MotdTheme(dynamicColor = false) {
                    primary = MaterialTheme.colorScheme.primary.toArgb()
                    ChatListContent(
                        state = listState.value,
                        networkActivity = activity,
                        hasUnseenNetworkActivity = unseen,
                        networkActivityBannerHidden = hidden,
                        onHideNetworkActivityBanner = model::hideNetworkActivityBanner,
                        onNetworkActivitySeen = model::markNetworkActivitySeen,
                        onClearNetworkActivityHistory = model::clearNetworkActivityHistory,
                        connectionNoticeVisible = true,
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
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
            compose.onNodeWithTag("chatlist_status_banner").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { !model.hasUnseenNetworkActivity.value }
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
            compose.onNodeWithTag("chatlist_more").performClick()
            compose.onNodeWithTag("chatlist_network_activity").assertTextEquals("Network activity").performClick()
            compose.onNodeWithTag("network_activity_close").performClick()

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
            compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_more").performClick()
            compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
            compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_network_activity").performClick()
            compose.waitUntil(5_000) { !model.hasUnseenNetworkActivity.value }
            compose.runOnIdle { assertEquals(1, model.networkActivity.value.unacknowledgedCount) }
            compose.onNodeWithTag("network_activity_close").performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
        } finally {
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test fun oneQuietBannerKeepsIndependentProgressWithOnlyStatusLive() {
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(12, 42, true))
        setList(chrome = { chrome.value })
        compose.onAllNodesWithTag("chatlist_status_banner").assertCountEquals(1)
        compose.onNodeWithTag("chatlist_status_title", true).assertTextEquals("Network activity")
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Needs attention")
        compose.onNodeWithTag("chatlist_status_issue_count", true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("1 issue")))
        compose.onNodeWithText("12/42", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Libera: bad certificate", useUnmergedTree = true).assertDoesNotExist()
        val progress = compose.onNodeWithTag("chatlist_status_progress", true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(12f / 42, progress.current, 0.001f)
        assertEquals(0f..1f, progress.range)
        compose
            .onNodeWithTag("chatlist_status_progress", true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("History sync progress")))
        val historyCue = compose.onNodeWithTag("chatlist_status_progress_history", true)
        historyCue.assertIsDisplayed()
        assertFalse(historyCue.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        assertEquals(LiveRegionMode.Polite, compose.onNodeWithTag("chatlist_status_label", true).fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        for (tag in listOf("chatlist_status_banner", "chatlist_status_title", "chatlist_status_progress", "chatlist_status_progress_history", "chatlist_status_issue_count")) {
            assertFalse(
                compose
                    .onNodeWithTag(tag, true)
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.LiveRegion),
            )
        }
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(13, 42, true) }
        assertEquals(
            13f / 42,
            compose
                .onNodeWithTag("chatlist_status_progress", true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
                .current,
            0.001f,
        )
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Needs attention")
        compose.onNodeWithTag("chatlist_status_title", true).assertTextEquals("Network activity")
        val status = compose.onNodeWithTag("chatlist_status_label", true).fetchSemanticsNode().config
        assertFalse(status.contains(SemanticsProperties.ProgressBarRangeInfo))
        assertFalse(status.contains(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals("bad certificate")
    }

    @Test fun shortSwipeCancelsAndTouchTapStillInspects() {
        var seenCalls = 0
        setList(onSeen = { seenCalls++ })
        compose.onNodeWithTag("chatlist_status_banner").performTouchInput {
            swipe(center, center.copy(x = center.x - width * 0.08f), durationMillis = 1_000)
        }
        compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, seenCalls) }
        compose.onNodeWithTag("chatlist_status_banner").performTouchInput { click() }
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, seenCalls) }
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
    }

    @Test fun bothSwipeDirectionsHideOnceEvenAcrossUpdatesAndRestoration() {
        val direction = mutableStateOf(0)
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(1, 3))
        val restoration = StateRestorationTester(compose)
        var hides = 0
        var inspections = 0
        restoration.setContent {
            MotdTheme(dynamicColor = false) {
                key(direction.value) {
                    NetworkActivityBanner(activity(), chrome.value, connectionNoticeVisible = true, includeHistory = true, onInspect = { inspections++ }, onHide = { hides++ })
                }
            }
        }
        compose.onNodeWithTag("chatlist_status_banner").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(1, hides)
            assertEquals(0, inspections)
            chrome.value = ChatListSyncChrome.Syncing(2, 3)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(1, hides)
            direction.value = 1
        }
        compose.onNodeWithTag("chatlist_status_banner").performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertEquals(2, hides)
            assertEquals(0, inspections)
            chrome.value = ChatListSyncChrome.Syncing(3, 3)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(2, hides) }
    }

    @Test fun accessibilityDismissUsesTheSameHideOnceWithoutInspection() {
        val restoration = StateRestorationTester(compose)
        var hides = 0
        var inspections = 0
        restoration.setContent {
            MotdTheme(dynamicColor = false) {
                NetworkActivityBanner(activity(), ChatListSyncChrome.Syncing(1, 3), connectionNoticeVisible = true, includeHistory = true, onInspect = { inspections++ }, onHide = { hides++ })
            }
        }
        compose.onNodeWithTag("chatlist_status_banner").performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithTag("chatlist_status_banner").performSemanticsAction(SemanticsActions.Dismiss)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chatlist_status_banner").performSemanticsAction(SemanticsActions.Dismiss)
        compose.runOnIdle {
            assertEquals(1, hides)
            assertEquals(0, inspections)
        }
    }

    @Test fun bannerMatchesActualFolderCapsuleAcrossCompactAndComfortableDensity() {
        val density = mutableStateOf(LayoutDensity.COMPACT)
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value) {
                Box(Modifier.width(280.dp).testTag("banner_geometry_host")) {
                    ChatListContent(
                        state =
                            ChatListState(
                                rows = listOf(row().copy(folderId = 1)),
                                networks = listOf(network),
                                folders = listOf(ChatFolderEntity(id = 1, displayName = "Dev", normalizedName = "dev", ordering = 0, expanded = false)),
                                folderDisplayMode = FolderDisplayMode.TABS,
                                loading = false,
                            ),
                        networkActivity = activity(),
                        syncChrome = ChatListSyncChrome.Syncing(1, 3),
                        connectionNoticeVisible = true,
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
        }
        for (mode in listOf(LayoutDensity.COMPACT, LayoutDensity.COMFORTABLE)) {
            compose.runOnIdle { density.value = mode }
            val banner = compose.onNodeWithTag("chatlist_status_banner").getUnclippedBoundsInRoot()
            val capsule = compose.onNodeWithTag("chatlist_folder_capsule").getUnclippedBoundsInRoot()
            val host = compose.onNodeWithTag("banner_geometry_host", true).getUnclippedBoundsInRoot()
            val glyph = compose.onNodeWithTag("chatlist_status_glyph", true).getUnclippedBoundsInRoot()
            val title = compose.onNodeWithTag("chatlist_status_title", true).getUnclippedBoundsInRoot()
            val status = compose.onNodeWithTag("chatlist_status_label", true).getUnclippedBoundsInRoot()
            val badge = compose.onNodeWithTag("chatlist_status_issue_count", true).getUnclippedBoundsInRoot()
            assertEquals((banner.top + banner.bottom) / 2, (badge.top + badge.bottom) / 2)
            assertEquals(banner.right - 12.dp, badge.right)
            assertEquals(capsule.left, banner.left)
            assertEquals(capsule.right, banner.right)
            assertEquals(capsule.bottom - capsule.top, banner.bottom - banner.top)
            assertEquals(48.dp, banner.bottom - banner.top)
            assertEquals(host.left + 8.dp, banner.left)
            assertEquals(host.right - 8.dp, banner.right)
            assertEquals(banner.bottom + 8.dp, capsule.top)
            compose.onNodeWithTag("chatlist_status_icon", true).assertDoesNotExist()
            assertEquals(banner.left + 12.dp, glyph.left)
            assertEquals(24.dp, glyph.right - glyph.left)
            assertEquals(24.dp, glyph.bottom - glyph.top)
            assertEquals((banner.top + banner.bottom) / 2, (glyph.top + glyph.bottom) / 2)
            assertEquals(glyph.right + 12.dp, title.left)
            assertEquals(title.left, status.left)
            assertEquals(banner.top + 5.dp, title.top)
            assertEquals(20.dp, title.bottom - title.top)
            assertEquals(title.bottom + 2.dp, status.top)
            assertEquals(16.dp, status.bottom - status.top)
            assertEquals(38.dp, status.bottom - title.top)
            assertEquals(banner.bottom - 5.dp, status.bottom)
            val progress = compose.onNodeWithTag("chatlist_status_progress", true).getUnclippedBoundsInRoot()
            val historyCue = compose.onNodeWithTag("chatlist_status_progress_history", true).getUnclippedBoundsInRoot()
            assertEquals(status.right + 12.dp, historyCue.left)
            assertEquals(16.dp, historyCue.right - historyCue.left)
            assertEquals(16.dp, historyCue.bottom - historyCue.top)
            assertEquals(historyCue.right + 4.dp, progress.left)
            assertEquals(badge.left - 12.dp, progress.right)
            assertEquals(28.dp, progress.right - progress.left)
            assertEquals(48.dp, progress.right - historyCue.left)
            // Material expands progress semantics vertically around the centered painted 2dp track.
            val progressCenter = (progress.top + progress.bottom) / 2
            assertEquals((banner.top + banner.bottom) / 2, progressCenter)
            assertEquals((badge.top + badge.bottom) / 2, progressCenter)
            assertEquals(progressCenter, (historyCue.top + historyCue.bottom) / 2)
        }
    }

    @Test fun bannerGrowsWithAccessibleFontScaleAndKeepsBothLinesInsideCard() {
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                MotdTheme(dynamicColor = false) {
                    Box(Modifier.width(280.dp).testTag("banner_font_host")) {
                        NetworkActivityBanner(activity(), ChatListSyncChrome.Syncing(1, 3), connectionNoticeVisible = true, includeHistory = true, onInspect = {}, onHide = {})
                    }
                }
            }
        }
        val restingHeight = compose.onNodeWithTag("chatlist_status_banner").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val restingBanner = compose.onNodeWithTag("chatlist_status_banner").getUnclippedBoundsInRoot()
        val restingProgress = compose.onNodeWithTag("chatlist_status_progress", true).getUnclippedBoundsInRoot()
        val restingBadge = compose.onNodeWithTag("chatlist_status_issue_count", true).getUnclippedBoundsInRoot()
        assertEquals((restingBanner.top + restingBanner.bottom) / 2, (restingProgress.top + restingProgress.bottom) / 2)
        assertEquals((restingBadge.top + restingBadge.bottom) / 2, (restingProgress.top + restingProgress.bottom) / 2)
        compose.runOnIdle { fontScale.value = 2f }
        val banner = compose.onNodeWithTag("chatlist_status_banner").getUnclippedBoundsInRoot()
        val host = compose.onNodeWithTag("banner_font_host", true).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("chatlist_status_title", true).getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("chatlist_status_label", true).getUnclippedBoundsInRoot()
        val badge = compose.onNodeWithTag("chatlist_status_issue_count", true).getUnclippedBoundsInRoot()
        assertEquals((banner.top + banner.bottom) / 2, (badge.top + badge.bottom) / 2)
        assertEquals(banner.right - 12.dp, badge.right)
        check(badge.top >= banner.top && badge.bottom <= banner.bottom)
        val progress = compose.onNodeWithTag("chatlist_status_progress", true).getUnclippedBoundsInRoot()
        assertEquals(badge.left - 12.dp, progress.right)
        assertEquals((banner.top + banner.bottom) / 2, (progress.top + progress.bottom) / 2)
        assertEquals((badge.top + badge.bottom) / 2, (progress.top + progress.bottom) / 2)
        val historyCue = compose.onNodeWithTag("chatlist_status_progress_history", true).getUnclippedBoundsInRoot()
        assertEquals(status.right + 12.dp, historyCue.left)
        assertEquals(historyCue.right + 4.dp, progress.left)
        assertEquals(28.dp, progress.right - progress.left)
        check(status.right > status.left && title.right <= historyCue.left - 12.dp)
        check(banner.bottom - banner.top > restingHeight)
        assertEquals(host.top + 4.dp, banner.top)
        assertEquals(host.bottom - 4.dp, banner.bottom)
        check(title.top >= banner.top && title.bottom + 2.dp <= status.top && status.bottom <= banner.bottom)
        for (tag in listOf("chatlist_status_title", "chatlist_status_label")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
        }
    }

    @Test fun hideKeepsInspectorAndLatestActivityAvailableWithoutRestore() {
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
        val bannerDescription = compose.onNodeWithTag("chatlist_status_banner").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        check("Open network activity" in bannerDescription)
        compose.onNodeWithTag("chatlist_status_issue_count", true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("2 issues")))
        compose.onNodeWithTag("chatlist_status_hide").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide network activity banner").assertDoesNotExist()
        assertEquals(
            "Hide network activity banner",
            compose
                .onNodeWithTag("chatlist_status_banner")
                .fetchSemanticsNode()
                .config[SemanticsActions.Dismiss]
                .label,
        )
        val beforeHide = snapshot.value
        // A swipe hides without also opening its clickable card.
        compose.onNodeWithTag("chatlist_status_banner").performTouchInput { swipeRight() }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("network_activity_sheet").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_row_7").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(beforeHide, snapshot.value)
            assertEquals(emptyList<NetworkActivityAction>(), calls)
        }

        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithText("#kotlin: Queued for history sync").assertDoesNotExist()
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
        compose.onNodeWithText("#kotlin: Syncing history…").assertDoesNotExist()
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
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_3_reason"))
        compose.onNodeWithTag("network_activity_issue_3_reason", true).assertTextEquals("New failure while hidden")
        compose.runOnIdle {
            assertEquals(latest, snapshot.value)
            assertEquals(emptyList<NetworkActivityAction>(), calls)
            assertFalse(snapshot.value.active.any { it.acknowledged })
        }
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
    }

    @Test fun hiddenProcessSurvivesNewEntryAndSavedStateButFreshProcessRestoresEligibility() {
        val connections =
            object : NoopConnectionManager() {
                override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Failed("original failure", false)))
            }
        val session = NetworkActivityBannerSession()
        val models = mutableListOf<ChatListViewModel>()

        fun entry(owner: NetworkActivityBannerSession): ChatListViewModel = activityModel(connections, owner).also(models::add)
        val current = mutableStateOf(entry(session))
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Hidden)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent {
                val model = current.value
                val snapshot by model.networkActivity.collectAsState()
                val hidden by model.networkActivityBannerHidden.collectAsState()
                val unseen by model.hasUnseenNetworkActivity.collectAsState()
                MotdTheme(dynamicColor = false) {
                    ChatListContent(
                        state = ChatListState(rows = listOf(row()), networks = listOf(network), loading = false),
                        networkActivity = snapshot,
                        networkActivityBannerHidden = hidden,
                        onHideNetworkActivityBanner = model::hideNetworkActivityBanner,
                        hasUnseenNetworkActivity = unseen,
                        onNetworkActivitySeen = model::markNetworkActivitySeen,
                        connectionNoticeVisible = true,
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
            compose.waitUntil(5_000) {
                current.value.networkActivity.value.active
                    .isNotEmpty()
            }
            compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
            compose.onNodeWithTag("chatlist_status_banner").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { !current.value.hasUnseenNetworkActivity.value }
            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("failure after hide", true)) }
            compose.waitUntil(5_000) { current.value.hasUnseenNetworkActivity.value }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
            compose.runOnIdle { current.value = entry(session) }
            // runOnIdle drains before its callback; flush the new VM's Android Main work too.
            compose.waitForIdle()
            compose.waitUntil(5_000) { current.value.hasUnseenNetworkActivity.value }
            compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_more").performClick()
            compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
            compose.onNodeWithText("Show network activity banner").assertDoesNotExist()
            compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_network_activity").performClick()
            compose.waitUntil(5_000) { !current.value.hasUnseenNetworkActivity.value }
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_reason"))
            compose.onNodeWithTag("network_activity_issue_1_reason", true).assertTextEquals("failure after hide")
            compose.onNodeWithTag("network_activity_close").performClick()
            compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()

            // New singleton graph, retaining the same Android/Compose saved state.
            compose.runOnIdle { current.value = entry(NetworkActivityBannerSession()) }
            compose.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) {
                current.value.networkActivity.value.active
                    .isNotEmpty()
            }
            compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Needs attention")
            compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
            compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to ready) }
            compose.waitUntil(5_000) {
                current.value.networkActivity.value.active
                    .isEmpty()
            }
            compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to IrcClientState.Connecting) }
            compose.waitUntil(5_000) {
                current.value.networkActivity.value.networks
                    .singleOrNull()
                    ?.connection == IrcClientState.Connecting
            }
            compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
            compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(1, 3) }
            compose.onNodeWithTag("chatlist_status_progress", true).assertIsDisplayed()
            compose.runOnIdle { connections.connectionStates.value = mapOf(1L to ready) }
            compose.waitUntil(5_000) {
                current.value.networkActivity.value.networks
                    .singleOrNull()
                    ?.connection == ready
            }
            compose.onNodeWithTag("chatlist_status_banner").assertIsDisplayed()
            compose.runOnIdle { chrome.value = ChatListSyncChrome.Hidden }
            compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        } finally {
            compose.runOnIdle { models.forEach { it.viewModelScope.cancel() } }
        }
    }

    @Test fun scopedAndSelectionMenusKeepInspectorWithoutRestoreAcrossArchiveAndInvitations() {
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
        compose.onNodeWithTag("chatlist_status_banner").performTouchInput { swipeRight() }
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_row_7").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("Full history reason in every mode")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
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
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        val beforeArchiveInspection = seenCalls
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.runOnIdle { assertEquals(beforeArchiveInspection + 1, seenCalls) }
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_reason"))
        compose.onNodeWithTag("network_activity_issue_2_reason", true).assertTextEquals("Full history reason in every mode")
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_8").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity_icon", true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_invitations_folder").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_more").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        val beforeInvitationInspection = seenCalls
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.runOnIdle { assertEquals(beforeInvitationInspection + 1, seenCalls) }
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_selection_close").performClick()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_network_activity_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<NetworkActivityAction>(), calls) }
    }

    @Test fun narrowBannerKeepsSingleReadableStatusAndNeutralIssueSurfaceWithInsetProgress() {
        val longReason = "Authentication rejected with a detailed reason. ".repeat(12)
        val snapshot =
            activity(listOf(issue(longReason))).copy(
                networks =
                    listOf(
                        NetworkActivityNetwork(1, "Libera", IrcClientState.Failed(longReason, true)),
                        NetworkActivityNetwork(2, "A very long network display name", IrcClientState.Connecting),
                        NetworkActivityNetwork(3, "OFTC", ready),
                    ),
            )
        val density = mutableStateOf(LayoutDensity.COMPACT)
        var neutral = 0
        var primary = 0
        var warning = 0
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value) {
                neutral = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                primary = MaterialTheme.colorScheme.primary.toArgb()
                warning = MaterialTheme.colorScheme.error.toArgb()
                Box(Modifier.width(280.dp)) {
                    NetworkActivityBanner(snapshot, ChatListSyncChrome.Syncing(12, 42, true), connectionNoticeVisible = true, includeHistory = true, onInspect = {}, onHide = {})
                }
            }
        }
        for (mode in listOf(LayoutDensity.COMPACT, LayoutDensity.COMFORTABLE)) {
            compose.runOnIdle { density.value = mode }
            val label = compose.onNodeWithTag("chatlist_status_label", true)
            label.assertTextEquals("Needs attention")
            val title = compose.onNodeWithTag("chatlist_status_title", true).assertTextEquals("Network activity")
            for (line in listOf(title, label)) {
                val layouts = mutableListOf<TextLayoutResult>()
                line.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                check(layouts.single().lineCount == 1 && !layouts.single().isLineEllipsized(0))
            }
            compose.onNodeWithText("12/42", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("2/3 networks connected", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText(longReason, useUnmergedTree = true).assertDoesNotExist()
            val textNodes =
                compose
                    .onAllNodes(
                        hasAnyAncestor(hasTestTag("chatlist_status_banner")) and
                            !hasAnyAncestor(hasTestTag("chatlist_status_issue_count")) and
                            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
            assertEquals(2, textNodes.size)
            val banner = compose.onNodeWithTag("chatlist_status_banner")
            val bounds = banner.fetchSemanticsNode().boundsInRoot
            val progress = compose.onNodeWithTag("chatlist_status_progress", true).fetchSemanticsNode().boundsInRoot
            val labelBounds = label.fetchSemanticsNode().boundsInRoot
            val historyCue = compose.onNodeWithTag("chatlist_status_progress_history", true).fetchSemanticsNode().boundsInRoot
            check(historyCue.left > labelBounds.right && historyCue.right < progress.left && progress.right < bounds.right)
            assertEquals(bounds.center.y, historyCue.center.y, 0.5f)
            assertEquals(bounds.center.y, progress.center.y, 0.5f)
            val pixels = banner.captureToImage().asAndroidBitmap()
            assertEquals(neutral, pixels.getPixel(pixels.width / 2, 2))
            val progressY = (progress.center.y - bounds.top).toInt()
            val progressX = (progress.left - bounds.left + progress.width * 0.1f).toInt()
            assertEquals(primary, pixels.getPixel(progressX, progressY))
            // No end-stop dot, and no painted bottom-edge stripe.
            assertEquals(neutral, pixels.getPixel(pixels.width / 2, pixels.height - 2))
            val trackEnd = (progress.right - bounds.left - 6).toInt()
            check(pixels.getPixel(trackEnd, progressY) != primary)
            val glyph = compose.onNodeWithTag("chatlist_status_glyph", true).captureToImage().asAndroidBitmap()
            check((0 until glyph.height).any { y -> (0 until glyph.width).any { x -> glyph.getPixel(x, y) == warning } })
        }
    }

    @Test fun progressSemanticsClampBoundariesAndUnknownTotalKeepsZero() {
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(1, 3))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                NetworkActivityBanner(activity(emptyList()), chrome.value, connectionNoticeVisible = false, includeHistory = true, onInspect = {}, onHide = {})
            }
        }
        for ((done, total, expected) in listOf(Triple(1, 3, 1f / 3), Triple(-1, 3, 0f), Triple(0, 3, 0f), Triple(3, 3, 1f), Triple(4, 3, 1f), Triple(0, 0, 0f), Triple(2, 0, 0f), Triple(2, -1, 0f))) {
            compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(done, total) }
            compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Syncing")
            val progress = compose.onNodeWithTag("chatlist_status_progress", true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
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
            compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_network_1_more"))
            compose.onNodeWithContentDescription("Network actions for Libera").performClick()
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

    @Test fun quietStatusPrecedenceAndConnectionGracePreserveEligibleIssues() {
        val connecting = NetworkActivityNetwork(1, "Libera", IrcClientState.Connecting)
        val state = mutableStateOf(activity(listOf(issue("fatal"), issue("history advisory", 7, false, 2).copy(kind = NetworkActivityKind.HISTORY_PARTIAL))).copy(networks = listOf(connecting)))
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Waiting(5))
        val connectionVisible = mutableStateOf(false)
        val includeHistory = mutableStateOf(true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                NetworkActivityBanner(state.value, chrome.value, connectionVisible.value, includeHistory = includeHistory.value, onInspect = {}, onHide = {})
            }
        }
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Needs attention")
        compose.onNodeWithTag("chatlist_status_title", true).assertTextEquals("Network activity")
        compose.onNodeWithTag("chatlist_status_issue_count", true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("2 issues")))
        compose.runOnIdle {
            state.value = state.value.copy(active = state.value.active.filter { it.bufferId != null })
            includeHistory.value = false
        }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.runOnIdle { includeHistory.value = true }
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Needs attention")
        compose.runOnIdle { state.value = state.value.copy(active = listOf(issue("ordinary", fatal = false))) }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.runOnIdle { connectionVisible.value = true }
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Needs attention")
        compose.runOnIdle { state.value = state.value.copy(active = emptyList()) }
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Connecting")
        compose.runOnIdle { state.value = state.value.copy(networks = listOf(connecting.copy(connection = IrcClientState.Registering))) }
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Connecting")
        compose.runOnIdle { connectionVisible.value = false }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(1, 3, true) }
        compose.onNodeWithTag("chatlist_status_label", true).assertTextEquals("Syncing")
        compose.onNodeWithTag("chatlist_status_title", true).assertTextEquals("Network activity")
        compose.onNodeWithTag("chatlist_status_label", true).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Hidden }
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
    }

    @Test fun waitingOnlyHidesCardButInspectorShowsPendingNetworkWork() {
        val pending = listOf(NetworkActivityChat(7, "#kotlin", HistorySyncStatus.AwaitingConnection), NetworkActivityChat(8, "#other", HistorySyncStatus.Queued))
        val snapshot = activity(emptyList()).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Disconnected, pending)))
        setList(activity = { snapshot }, chrome = { ChatListSyncChrome.Waiting(2) })
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
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

    @Test fun acknowledgementIsExplicitAndUsesHonestCompactActiveSummary() {
        val state = mutableStateOf(activity(listOf(issue("history warning", 7, false))))
        setList(activity = { state.value }, onAction = { observed, action ->
            assertEquals(NetworkActivityAction.ACKNOWLEDGE, action)
            state.value = state.value.copy(active = listOf(observed.copy(acknowledged = true, revision = observed.revision + 1)))
        })
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_acknowledge"))
        compose.onNodeWithContentDescription("Acknowledge issue for #kotlin on Libera").performClick()
        compose.onNodeWithTag("network_activity_sheet").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_issue_1").assertDoesNotExist()
        compose.onNodeWithTag("network_activity_acknowledged_summary").assertTextEquals("1 acknowledged issue still active")
        compose.onNodeWithText("No unacknowledged issues").assertIsDisplayed()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_issue_1_reason", true).assertDoesNotExist()
        compose.onNodeWithTag("network_activity_acknowledged_summary").assertTextEquals("1 acknowledged issue still active")
    }

    @Test fun historyRetryUsesExplicitActionAndClosesInspector() {
        val calls = mutableListOf<NetworkActivityAction>()
        val snapshot = mutableStateOf(activity(listOf(issue("history warning", 7, false))).copy(networks = listOf(NetworkActivityNetwork(1, "Libera", IrcClientState.Connecting))))
        setList(activity = { snapshot.value }, onAction = { _, action -> calls += action })
        compose.onNodeWithTag("chatlist_status_banner").performClick()
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
        compose.onNodeWithTag("chatlist_status_banner").performClick()
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
        compose.onNodeWithTag("chatlist_status_banner").assertDoesNotExist()
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
        compose.onNodeWithTag("chatlist_status_issue_count", true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("1 issue")))
        compose.onNodeWithTag("chatlist_status_banner").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_network_2"))
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
        compose.onNodeWithTag("chatlist_status_banner").performTouchInput { swipeLeft() }
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
        bannerSession: NetworkActivityBannerSession = NetworkActivityBannerSession(),
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
        networkActivityBannerSession = bannerSession,
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
        val session = NetworkActivityBannerSession()
        compose.setContent {
            val hidden by session.hidden.collectAsState()
            MotdTheme(dynamicColor = false) {
                ChatListContent(state = listState, networkActivity = activity(), hasUnseenNetworkActivity = unseen, networkActivityBannerHidden = hidden, onHideNetworkActivityBanner = {
                    onSeen()
                    session.hide()
                }, connectionNoticeVisible = true, syncChrome = chrome(), onActivityAction = onAction, onActivityNetworkAction = onNetworkAction, onClearNetworkActivityHistory = onClearRecent, onNetworkActivitySeen = onSeen, onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {}, onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> }, onJoinChannel = { _, _, _ -> }, onMessageUser = { _, _ -> })
            }
        }
    }
}
