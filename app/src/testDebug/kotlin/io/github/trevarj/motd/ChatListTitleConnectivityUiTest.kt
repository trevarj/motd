package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.ui.chatlist.CHAT_LIST_TITLE_ACTIVITY_TAG
import io.github.trevarj.motd.ui.chatlist.CHAT_LIST_TITLE_ATTENTION_TAG
import io.github.trevarj.motd.ui.chatlist.CHAT_LIST_TITLE_PROGRESS_TAG
import io.github.trevarj.motd.ui.chatlist.CHAT_LIST_TITLE_STATUS_TEXT_TAG
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListState
import io.github.trevarj.motd.ui.chatlist.ChatListSyncChrome
import io.github.trevarj.motd.ui.chatlist.ChatListTitleActivityStatus
import io.github.trevarj.motd.ui.chatlist.NetworkActivityAction
import io.github.trevarj.motd.ui.chatlist.NetworkActivityIssue
import io.github.trevarj.motd.ui.chatlist.NetworkActivityKind
import io.github.trevarj.motd.ui.chatlist.NetworkActivityState
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatListTitleConnectivityUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ringTracksCountsClampsBoundsAndKeepsAnnouncementsSeparate() {
        compose.mainClock.autoAdvance = false
        val connecting = mutableStateOf(false)
        val syncing = mutableStateOf<ChatListSyncChrome.Syncing?>(null)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListTitleActivityStatus(connecting = connecting.value, syncing = syncing.value)
            }
        }
        val resources = RuntimeEnvironment.getApplication().resources
        val syncingLabel = resources.getString(R.string.network_activity_syncing)
        val connectingLabel = resources.getString(R.string.network_activity_connecting)
        val status = compose.onNodeWithTag(CHAT_LIST_TITLE_ACTIVITY_TAG, useUnmergedTree = true)
        val ring = compose.onNodeWithTag(CHAT_LIST_TITLE_PROGRESS_TAG, useUnmergedTree = true)
        val text = compose.onNodeWithTag(CHAT_LIST_TITLE_STATUS_TEXT_TAG, useUnmergedTree = true)
        val restingBounds = status.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.ProgressBarRangeInfo))
        ring.assertDoesNotExist()
        text.assertDoesNotExist()
        for ((done, total, expectedPercent) in listOf(
            Triple(1, 4, 25),
            Triple(3, 4, 75),
            Triple(1, 3, 33),
            Triple(2, 3, 66),
            Triple(9, 100, 9),
            Triple(10, 100, 10),
            Triple(-1, 4, 0),
            Triple(5, 4, 100),
            Triple(Int.MIN_VALUE, Int.MAX_VALUE, 0),
            Triple(Int.MAX_VALUE - 1, Int.MAX_VALUE, 99),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, 100),
            Triple(2, 0, null),
            Triple(2, -1, null),
        )) {
            compose.runOnUiThread {
                syncing.value = ChatListSyncChrome.Syncing(done, total)
                // A paused Compose clock does not pump global snapshot apply notifications.
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
            assertEquals(restingBounds, status.fetchSemanticsNode().boundsInRoot)
            status.assertContentDescriptionEquals(syncingLabel)
            val announcement = status.fetchSemanticsNode().config
            assertEquals(LiveRegionMode.Polite, announcement[SemanticsProperties.LiveRegion])
            assertFalse(announcement.contains(SemanticsProperties.ProgressBarRangeInfo))
            assertFalse(announcement.contains(SemanticsProperties.Text))
            text.assertIsDisplayed().assertTextEquals(expectedPercent?.let { resources.getString(R.string.chatlist_sync_progress, it) } ?: syncingLabel)
            assertFalse(text.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
            assertTrue(text.fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
            val progress = ring.assertIsDisplayed().fetchSemanticsNode().config
            val expectedRange = if (total > 0) ProgressBarRangeInfo((done.toFloat() / total).coerceIn(0f, 1f), 0f..1f) else ProgressBarRangeInfo.Indeterminate
            assertEquals(expectedRange, progress[SemanticsProperties.ProgressBarRangeInfo])
            assertFalse(progress.contains(SemanticsProperties.LiveRegion))
            assertFalse(progress.contains(SemanticsProperties.Text))
            assertFalse(progress.contains(SemanticsProperties.HideFromAccessibility))
        }
        compose.runOnUiThread {
            syncing.value = ChatListSyncChrome.Syncing(3, 4)
            connecting.value = true
            Snapshot.sendApplyNotifications()
        }
        advanceTwoFrames()
        status.assertContentDescriptionEquals(syncingLabel)
        text.assertIsDisplayed().assertTextEquals(resources.getString(R.string.chatlist_sync_progress, 75))
        assertEquals(LiveRegionMode.Polite, status.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        assertEquals(ProgressBarRangeInfo(0.75f, 0f..1f), ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        assertFalse(ring.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        compose.runOnUiThread {
            syncing.value = ChatListSyncChrome.Syncing(3, 0)
            Snapshot.sendApplyNotifications()
        }
        advanceTwoFrames()
        status.assertContentDescriptionEquals(connectingLabel)
        text.assertTextEquals(connectingLabel)
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        compose.runOnUiThread {
            connecting.value = false
            Snapshot.sendApplyNotifications()
        }
        advanceTwoFrames()
        status.assertContentDescriptionEquals(syncingLabel)
        text.assertTextEquals(syncingLabel)
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        compose.runOnUiThread {
            syncing.value = ChatListSyncChrome.Syncing(3, 4)
            Snapshot.sendApplyNotifications()
        }
        advanceTwoFrames()
        text.assertTextEquals(resources.getString(R.string.chatlist_sync_progress, 75))
        assertEquals(ProgressBarRangeInfo(0.75f, 0f..1f), ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        val outgoingRingBounds = ring.fetchSemanticsNode().boundsInRoot
        val outgoingTextBounds = text.fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            syncing.value = null
            Snapshot.sendApplyNotifications()
        }
        advanceTwoFrames()
        assertEquals(restingBounds, status.fetchSemanticsNode().boundsInRoot)
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.ProgressBarRangeInfo))
        // Outgoing visuals keep their positions, but idle semantics clear before the fade ends.
        assertEquals(outgoingRingBounds, ring.fetchSemanticsNode().boundsInRoot)
        assertEquals(outgoingTextBounds, text.fetchSemanticsNode().boundsInRoot)
        assertFalse(ring.fetchSemanticsNode().config.contains(SemanticsProperties.ProgressBarRangeInfo))
        assertFalse(text.fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        assertFalse(text.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        ring.assertDoesNotExist()
        text.assertDoesNotExist()
        assertEquals(restingBounds, status.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun activityTransitionsResizeOnlyDefaultWordmarkAndKeepViewportFixed() {
        val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
        val row = ChatListRow(bufferId = 7, networkId = 1, networkName = "Libera", displayName = "#kotlin", type = BufferType.CHANNEL, pinned = false, muted = false, lastMessageText = "hi", lastMessageSender = "alice", lastMessageTime = 1, unreadCount = 0, mentionCount = 0)
        val state = mutableStateOf(ChatListState(rows = listOf(row), networks = listOf(network), loading = false))
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Hidden)
        val connecting = mutableStateOf(false)
        val activity = mutableStateOf(NetworkActivityState())
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val fontScale = mutableStateOf(1f)
        val snackbar = SnackbarHostState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                MotdTheme(dynamicColor = false, layoutDensity = density.value) {
                    Box(Modifier.width(280.dp)) {
                        ChatListContent(
                            state = state.value,
                            syncChrome = chrome.value,
                            titleConnecting = connecting.value,
                            networkActivity = activity.value,
                            snackbarHostState = snackbar,
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
        }
        val resources = RuntimeEnvironment.getApplication().resources
        for ((mode, layout, scale) in listOf(
            Triple("default", LayoutDensity.COMPACT, 1f),
            Triple("default", LayoutDensity.COMFORTABLE, 2f),
            Triple("scoped", LayoutDensity.COMFORTABLE, 2f),
            Triple("selection", LayoutDensity.COMFORTABLE, 2f),
        )) {
            compose.runOnIdle {
                state.value = state.value.copy(selectedNetworkId = if (mode == "default") null else 1L)
                density.value = layout
                fontScale.value = scale
            }
            if (mode == "selection") compose.onNodeWithTag("chatlist_row_7").performTouchInput { longClick() }
            val selection = mode == "selection"
            val barTag = if (selection) "chatlist_selection_top_app_bar" else "chatlist_top_app_bar"
            val titleTag = if (selection) "chatlist_selection_title" else "chatlist_title"
            val tags = listOf(titleTag, "chatlist_row_7", barTag, "chatlist_header", CHAT_LIST_TITLE_ACTIVITY_TAG)
            val initial =
                tags.map {
                    compose
                        .onNodeWithTag(it, true)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                }
            val idleTitleLayout = textLayout(titleTag)
            val title =
                compose
                    .onNodeWithTag(titleTag, true)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text
            when (mode) {
                "default" -> assertEquals(resources.getString(R.string.app_name), title)
                "scoped" -> assertEquals("Libera", title)
                else -> assertEquals(resources.getQuantityString(R.plurals.chatlist_selected_count, 1, 1), title)
            }
            val actions =
                if (selection) {
                    listOf("chatlist_selection_mark_read", "chatlist_selection_mute", "chatlist_selection_remove", "chatlist_selection_more").map {
                        compose.onNodeWithTag(it, true)
                    }
                } else {
                    listOf(compose.onNodeWithContentDescription(resources.getString(R.string.chatlist_search)), compose.onNodeWithTag("chatlist_more", true))
                } + compose.onNodeWithTag("chatlist_selection_close", true)
            val actionBounds = actions.map { it.assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            if (mode == "default") {
                assertEquals("Idle motd must center on native controls", actionBounds.last().center.y, initial[0].center.y, 1f)
                assertEquals("Idle motd retains the title's start alignment", initial[3].left, initial[0].left, 1f)
            }
            var activeRingBounds: Rect? = null
            var activeTextOrigin: Offset? = null
            for ((nextChrome, nextConnecting, expectedText) in listOf(
                Triple(ChatListSyncChrome.Syncing(9, 100), false, resources.getString(R.string.chatlist_sync_progress, 9)),
                Triple(ChatListSyncChrome.Syncing(10, 100), false, resources.getString(R.string.chatlist_sync_progress, 10)),
                Triple(ChatListSyncChrome.Syncing(100, 100), false, resources.getString(R.string.chatlist_sync_progress, 100)),
                Triple(ChatListSyncChrome.Syncing(3, 0), false, resources.getString(R.string.network_activity_syncing)),
                Triple(ChatListSyncChrome.Syncing(3, 4), true, resources.getString(R.string.chatlist_sync_progress, 75)),
                Triple(ChatListSyncChrome.Syncing(3, 0), true, resources.getString(R.string.network_activity_connecting)),
                Triple(ChatListSyncChrome.Hidden, true, resources.getString(R.string.network_activity_connecting)),
                Triple(ChatListSyncChrome.Hidden, false, null),
            )) {
                compose.runOnIdle {
                    chrome.value = nextChrome
                    connecting.value = nextConnecting
                }
                val currentBounds = tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot }
                assertEquals("$mode row/bar/header/status", initial.drop(1), currentBounds.drop(1))
                if (mode != "default") assertEquals("$mode contextual title", initial[0], currentBounds[0])
                val currentTitleLayout = textLayout(titleTag)
                if (mode == "default" && expectedText != null) {
                    assertTrue("Active motd glyphs must actually become smaller", currentTitleLayout.layoutInput.style.fontSize < idleTitleLayout.layoutInput.style.fontSize)
                    assertTrue("Active motd text layout must actually become shorter", currentTitleLayout.size.height < idleTitleLayout.size.height)
                } else {
                    assertEquals("$mode idle/context title font restored", idleTitleLayout.layoutInput.style.fontSize, currentTitleLayout.layoutInput.style.fontSize)
                    assertEquals("$mode idle/context title layout restored", idleTitleLayout.size, currentTitleLayout.size)
                    if (mode == "default") {
                        assertEquals("Restored idle motd must center on native controls", actionBounds.last().center.y, currentBounds[0].center.y, 1f)
                        assertEquals("Restored idle wordmark bounds", initial[0], currentBounds[0])
                    }
                }
                assertEquals("$mode contextual actions", actionBounds, actions.map { it.assertIsDisplayed().fetchSemanticsNode().boundsInRoot })
                compose.onNodeWithTag(titleTag, true).assertTextEquals(title)
                val status = compose.onNodeWithTag(CHAT_LIST_TITLE_ACTIVITY_TAG, true)
                val semantics = status.fetchSemanticsNode().config
                val text = compose.onNodeWithTag(CHAT_LIST_TITLE_STATUS_TEXT_TAG, true)
                assertFalse(semantics.contains(SemanticsProperties.ProgressBarRangeInfo))
                if (expectedText == null) {
                    assertFalse(semantics.contains(SemanticsProperties.ContentDescription))
                    assertFalse(semantics.contains(SemanticsProperties.LiveRegion))
                    text.assertDoesNotExist()
                    compose.onNodeWithTag(CHAT_LIST_TITLE_PROGRESS_TAG, true).assertDoesNotExist()
                } else {
                    val determinate = (nextChrome as? ChatListSyncChrome.Syncing)?.total?.let { it > 0 } == true
                    status.assertContentDescriptionEquals(resources.getString(if (nextConnecting && !determinate) R.string.network_activity_connecting else R.string.network_activity_syncing))
                    assertEquals(LiveRegionMode.Polite, semantics[SemanticsProperties.LiveRegion])
                    if (selection) {
                        text.assertDoesNotExist()
                    } else {
                        text.assertIsDisplayed().assertTextEquals(expectedText)
                        val textNode = text.fetchSemanticsNode()
                        assertFalse(textNode.config.contains(SemanticsProperties.LiveRegion))
                        assertTrue(textNode.config.contains(SemanticsProperties.HideFromAccessibility))
                        val layouts = mutableListOf<TextLayoutResult>()
                        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        val measuredText = layouts.single()
                        assertFalse("$mode status must not clip at 280dp/$scale font", measuredText.hasVisualOverflow)
                        for (line in 0 until measuredText.lineCount) {
                            assertFalse("$mode status must not ellipsize line $line", measuredText.isLineEllipsized(line))
                        }
                        assertEquals("$mode entire status must remain readable", expectedText.length, measuredText.getLineEnd(measuredText.lineCount - 1, visibleEnd = true))
                        assertTrue("$mode subtitle below contextual title", textNode.boundsInRoot.top >= currentBounds[0].bottom)
                        assertTrue("$mode subtitle inside appbar", textNode.boundsInRoot.bottom <= initial[2].bottom)
                        assertTrue("$mode subtitle inside title column", textNode.boundsInRoot.bottom <= initial[3].bottom)
                        if (activeTextOrigin == null) {
                            activeTextOrigin = textNode.boundsInRoot.topLeft
                        } else {
                            assertEquals("$mode text origin cannot move", activeTextOrigin, textNode.boundsInRoot.topLeft)
                        }
                    }
                    val ring = compose.onNodeWithTag(CHAT_LIST_TITLE_PROGRESS_TAG, true).assertIsDisplayed().fetchSemanticsNode()
                    if (activeRingBounds == null) {
                        activeRingBounds = ring.boundsInRoot
                    } else {
                        assertEquals("$mode ring cannot move", activeRingBounds, ring.boundsInRoot)
                    }
                    val sync = nextChrome as? ChatListSyncChrome.Syncing
                    val expected =
                        if (sync != null && sync.total > 0) {
                            ProgressBarRangeInfo((sync.done.toFloat() / sync.total).coerceIn(0f, 1f), 0f..1f)
                        } else {
                            ProgressBarRangeInfo.Indeterminate
                        }
                    assertEquals(expected, ring.config[SemanticsProperties.ProgressBarRangeInfo])
                    assertFalse(ring.config.contains(SemanticsProperties.LiveRegion))
                }
                compose.runOnIdle { assertNull(snackbar.currentSnackbarData) }
            }
            if (mode == "default") {
                for (count in listOf(1, 2, 9, 10, 99, 100)) {
                    compose.runOnIdle {
                        activity.value =
                            NetworkActivityState(
                                active =
                                    List(count) { index ->
                                        NetworkActivityIssue(episodeId = index.toLong(), revision = 0, networkId = 1, bufferId = index.toLong(), networkName = "Libera", chatName = "#$index", kind = NetworkActivityKind.HISTORY_FAILED, reason = "History request failed", firstSeen = 1, lastSeen = 2)
                                    },
                            )
                    }
                    val label = resources.getQuantityString(R.plurals.network_activity_issue_count, count, count)
                    val text = compose.onNodeWithTag(CHAT_LIST_TITLE_STATUS_TEXT_TAG, true).assertIsDisplayed().assertTextEquals(label)
                    val measured = textLayout(CHAT_LIST_TITLE_STATUS_TEXT_TAG)
                    assertFalse("$count issues must not clip at 280dp/$scale font", measured.hasVisualOverflow)
                    for (line in 0 until measured.lineCount) assertFalse(measured.isLineEllipsized(line))
                    assertEquals(label.length, measured.getLineEnd(measured.lineCount - 1, visibleEnd = true))
                    compose.onNodeWithTag(CHAT_LIST_TITLE_ACTIVITY_TAG, true).assertContentDescriptionEquals(resources.getString(R.string.network_activity_issues))
                    val warning = compose.onNodeWithTag(CHAT_LIST_TITLE_ATTENTION_TAG, true).assertIsDisplayed().assertContentDescriptionEquals(label)
                    assertFalse(warning.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
                    assertEquals("$count issues cannot move viewport", initial.drop(1), tags.drop(1).map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })
                    assertEquals("$count issues cannot move actions", actionBounds, actions.map { it.fetchSemanticsNode().boundsInRoot })
                    assertTrue("Issues must compact the actual motd glyphs", textLayout(titleTag).layoutInput.style.fontSize < idleTitleLayout.layoutInput.style.fontSize)
                }
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread {
                    activity.value = NetworkActivityState()
                    Snapshot.sendApplyNotifications()
                }
                advanceTwoFrames()
                val outgoingText = compose.onNodeWithTag(CHAT_LIST_TITLE_STATUS_TEXT_TAG, true).fetchSemanticsNode()
                val outgoingTitle = compose.onNodeWithTag(titleTag, true).fetchSemanticsNode()
                assertTrue("Keep the wordmark compact until status has faded", textLayout(titleTag).layoutInput.style.fontSize < idleTitleLayout.layoutInput.style.fontSize)
                assertTrue("Outgoing subtitle cannot overlap the wordmark", outgoingText.boundsInRoot.top >= outgoingTitle.boundsInRoot.bottom)
                assertFalse(
                    compose
                        .onNodeWithTag(CHAT_LIST_TITLE_ACTIVITY_TAG, true)
                        .fetchSemanticsNode()
                        .config
                        .contains(SemanticsProperties.LiveRegion),
                )
                assertEquals("Fade cannot move viewport", initial.drop(1), tags.drop(1).map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })
                compose.mainClock.advanceTimeBy(300)
                compose.waitForIdle()
                compose.mainClock.autoAdvance = true
                compose.onNodeWithTag(CHAT_LIST_TITLE_STATUS_TEXT_TAG, true).assertDoesNotExist()
                compose.onNodeWithTag(CHAT_LIST_TITLE_ATTENTION_TAG, true).assertDoesNotExist()
                assertEquals("Recovered idle motd uses original full-size font", idleTitleLayout.layoutInput.style.fontSize, textLayout(titleTag).layoutInput.style.fontSize)
                assertEquals("Recovered idle motd uses original glyph layout", idleTitleLayout.size, textLayout(titleTag).size)
                assertEquals(
                    "Recovered idle motd must center on native controls",
                    actionBounds.last().center.y,
                    compose
                        .onNodeWithTag(titleTag, true)
                        .fetchSemanticsNode()
                        .boundsInRoot
                        .center
                        .y,
                    1f,
                )
                assertEquals("Recovery cannot move viewport", initial.drop(1), tags.drop(1).map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })
            }
        }
    }

    @Test
    fun countedEpisodesSurviveSeenRecountOnAcknowledgementAndKeepSelectionCues() {
        val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
        val row = ChatListRow(bufferId = 7, networkId = 1, networkName = "Libera", displayName = "#kotlin", type = BufferType.CHANNEL, pinned = false, muted = false, lastMessageText = "hi", lastMessageSender = "alice", lastMessageTime = 1, unreadCount = 0, mentionCount = 0)
        val issue = NetworkActivityIssue(episodeId = 1, revision = 0, networkId = 2, bufferId = 99, networkName = "OFTC", chatName = "#elsewhere", kind = NetworkActivityKind.HISTORY_FAILED, reason = "History request failed", firstSeen = 1, lastSeen = 2, occurrences = 100)
        val secondIssue = issue.copy(episodeId = 2, bufferId = 100, chatName = "#another", occurrences = 1)
        val acknowledged = issue.copy(episodeId = 3, acknowledged = true)
        val recent = issue.copy(episodeId = 4)
        val activity = mutableStateOf(NetworkActivityState(active = listOf(issue, acknowledged), recent = listOf(recent)))
        val unseen = mutableStateOf(true)
        val chrome = mutableStateOf<ChatListSyncChrome>(ChatListSyncChrome.Syncing(1, 0))
        val connecting = mutableStateOf(false)
        val snackbar = SnackbarHostState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MotdTheme(dynamicColor = false, layoutDensity = LayoutDensity.COMFORTABLE) {
                    Box(Modifier.width(280.dp)) {
                        ChatListContent(
                            state = ChatListState(rows = listOf(row), networks = listOf(network, network.copy(id = 2, name = "OFTC")), selectedNetworkId = 1, loading = false),
                            networkActivity = activity.value,
                            hasUnseenNetworkActivity = unseen.value,
                            onNetworkActivitySeen = { unseen.value = false },
                            onActivityAction = { target, action ->
                                if (action == NetworkActivityAction.ACKNOWLEDGE) {
                                    activity.value = activity.value.copy(active = activity.value.active.map { if (it.episodeId == target.episodeId) it.copy(acknowledged = true) else it })
                                }
                            },
                            syncChrome = chrome.value,
                            titleConnecting = connecting.value,
                            snackbarHostState = snackbar,
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
        }
        val resources = RuntimeEnvironment.getApplication().resources
        val attention = resources.getString(R.string.network_activity_issues)
        val oneIssue = resources.getQuantityString(R.plurals.network_activity_issue_count, 1, 1)
        val twoIssues = resources.getQuantityString(R.plurals.network_activity_issue_count, 2, 2)
        val status = compose.onNodeWithTag(CHAT_LIST_TITLE_ACTIVITY_TAG, true)
        val text = compose.onNodeWithTag(CHAT_LIST_TITLE_STATUS_TEXT_TAG, true)
        val ring = compose.onNodeWithTag(CHAT_LIST_TITLE_PROGRESS_TAG, true)
        val warning = compose.onNodeWithTag(CHAT_LIST_TITLE_ATTENTION_TAG, true)
        val tags = listOf("chatlist_title", "chatlist_top_app_bar", "chatlist_header", "chatlist_row_7", CHAT_LIST_TITLE_ACTIVITY_TAG)
        val restingBounds = tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot }
        val textBounds = text.fetchSemanticsNode().boundsInRoot
        // The scoped list retains the old card's global issue policy, not an invented scope reason.
        compose.onNodeWithTag("chatlist_title", true).assertTextEquals("Libera")
        status.assertContentDescriptionEquals(attention)
        assertEquals(LiveRegionMode.Polite, status.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.ProgressBarRangeInfo))
        text.assertIsDisplayed().assertTextEquals(oneIssue)
        ring.assertContentDescriptionEquals(oneIssue)
        val attentionLayouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(attentionLayouts) }
        val attentionLayout = attentionLayouts.single()
        assertFalse("Counted issues must genuinely fit at 280dp/2x font", attentionLayout.hasVisualOverflow)
        for (line in 0 until attentionLayout.lineCount) assertFalse(attentionLayout.isLineEllipsized(line))
        assertEquals(oneIssue.length, attentionLayout.getLineEnd(attentionLayout.lineCount - 1, visibleEnd = true))
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        assertFalse(ring.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        warning.assertDoesNotExist()
        // Two different sources on the same network count as two episodes, never 101 occurrences.
        compose.runOnIdle { activity.value = activity.value.copy(active = listOf(issue, secondIssue, acknowledged)) }
        text.assertTextEquals(twoIssues)
        ring.assertContentDescriptionEquals(twoIssues)
        status.assertContentDescriptionEquals(attention)
        assertEquals(textBounds, text.fetchSemanticsNode().boundsInRoot)
        assertEquals(restingBounds, tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })

        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        compose.runOnIdle { assertFalse(unseen.value) }
        status.assertContentDescriptionEquals(attention)
        text.assertTextEquals(twoIssues)
        compose.onNodeWithTag("chatlist_more_network_activity_new_dot", true).assertDoesNotExist()
        assertEquals(restingBounds, tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })

        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_acknowledge"))
        compose.onNodeWithTag("network_activity_issue_1_acknowledge").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        status.assertContentDescriptionEquals(attention)
        text.assertTextEquals(oneIssue)
        ring.assertContentDescriptionEquals(oneIssue)
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_2_acknowledge"))
        compose.onNodeWithTag("network_activity_issue_2_acknowledge").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        status.assertContentDescriptionEquals(resources.getString(R.string.network_activity_syncing))
        text.assertTextEquals(resources.getString(R.string.network_activity_syncing))
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        assertEquals(textBounds, text.fetchSemanticsNode().boundsInRoot)
        assertEquals(restingBounds, tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })

        compose.runOnIdle {
            connecting.value = true
            chrome.value = ChatListSyncChrome.Syncing(3, 4)
        }
        text.assertTextEquals(resources.getString(R.string.chatlist_sync_progress, 75))
        status.assertContentDescriptionEquals(resources.getString(R.string.network_activity_syncing))
        assertEquals(ProgressBarRangeInfo(0.75f, 0f..1f), ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        compose.runOnIdle { activity.value = activity.value.copy(active = listOf(issue.copy(occurrences = 100))) }
        val unresolved = activity.value
        text.assertTextEquals(resources.getString(R.string.chatlist_sync_progress, 75))
        status.assertContentDescriptionEquals(resources.getString(R.string.network_activity_syncing))
        assertFalse(ring.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        assertEquals(ProgressBarRangeInfo(0.75f, 0f..1f), ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        compose.runOnIdle {
            assertEquals(unresolved, activity.value)
            chrome.value = ChatListSyncChrome.Syncing(3, 0)
        }
        text.assertTextEquals(oneIssue)
        ring.assertContentDescriptionEquals(oneIssue)
        status.assertContentDescriptionEquals(attention)
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        assertEquals(textBounds, text.fetchSemanticsNode().boundsInRoot)
        assertEquals(restingBounds, tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_network_activity").performClick()
        compose.onNodeWithTag("network_activity_list").performScrollToNode(hasTestTag("network_activity_issue_1_acknowledge"))
        compose.onNodeWithTag("network_activity_issue_1_acknowledge").performClick()
        compose.onNodeWithTag("network_activity_close").performClick()
        status.assertContentDescriptionEquals(resources.getString(R.string.network_activity_connecting))
        text.assertTextEquals(resources.getString(R.string.network_activity_connecting))
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        compose.runOnIdle {
            assertEquals(unresolved.copy(active = unresolved.active.map { it.copy(acknowledged = true) }), activity.value)
            connecting.value = false
        }
        status.assertContentDescriptionEquals(resources.getString(R.string.network_activity_syncing))
        text.assertTextEquals(resources.getString(R.string.network_activity_syncing))
        assertFalse(ring.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))

        compose.runOnIdle {
            activity.value = activity.value.copy(active = listOf(issue))
            chrome.value = ChatListSyncChrome.Hidden
            connecting.value = false
        }
        text.assertTextEquals(oneIssue)
        warning.assertIsDisplayed().assertContentDescriptionEquals(oneIssue)
        ring.assertDoesNotExist()
        assertFalse(warning.fetchSemanticsNode().config.contains(SemanticsProperties.ProgressBarRangeInfo))
        assertEquals(restingBounds, tags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })
        compose.onNodeWithTag("chatlist_row_7").performTouchInput { longClick() }
        status.assertContentDescriptionEquals(attention)
        warning.assertIsDisplayed().assertContentDescriptionEquals(oneIssue)
        text.assertDoesNotExist()
        val selectionTags = listOf("chatlist_selection_title", "chatlist_selection_top_app_bar", "chatlist_header", "chatlist_row_7", CHAT_LIST_TITLE_ACTIVITY_TAG, "chatlist_selection_mark_read", "chatlist_selection_mute", "chatlist_selection_remove", "chatlist_selection_more")
        val selectionBounds =
            selectionTags.map {
                compose
                    .onNodeWithTag(it, true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            }
        compose.runOnIdle {
            chrome.value = ChatListSyncChrome.Syncing(3, 4)
            connecting.value = true
        }
        status.assertContentDescriptionEquals(resources.getString(R.string.network_activity_syncing))
        text.assertDoesNotExist()
        warning.assertDoesNotExist()
        assertEquals(ProgressBarRangeInfo(0.75f, 0f..1f), ring.assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        assertFalse(ring.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        compose.runOnIdle { chrome.value = ChatListSyncChrome.Syncing(3, 0) }
        status.assertContentDescriptionEquals(attention)
        ring.assertContentDescriptionEquals(oneIssue)
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        assertEquals(selectionBounds, selectionTags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })
        compose.runOnIdle {
            activity.value = NetworkActivityState(recent = listOf(issue))
            chrome.value = ChatListSyncChrome.Hidden
            connecting.value = false
        }
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        assertFalse(status.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        ring.assertDoesNotExist()
        warning.assertDoesNotExist()
        assertEquals(selectionBounds, selectionTags.map { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot })
        compose.runOnIdle { assertNull(snackbar.currentSnackbarData) }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun advanceTwoFrames() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
}
