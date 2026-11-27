package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.TypingIndicatorPlacement
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatPresentation
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class TypingPlacementUiTest {
    private val motionScale =
        object : MotionDurationScale {
            override var scaleFactor by mutableFloatStateOf(1f)
        }

    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule(effectContext = motionScale)

    private var nicks by mutableStateOf(emptyList<String>())
    private var placement by mutableStateOf(Settings().typingIndicatorPlacement)
    private lateinit var timeline: LazyListState

    private fun setContent() {
        val room = BufferEntity(id = 1, networkId = 1, name = "#room", displayName = "#room", type = BufferType.CHANNEL, joined = true)
        val pages =
            flowOf(
                PagingData.from(
                    (100L downTo 1L).map { id ->
                        MessageEntity(
                            id = id,
                            bufferId = room.id,
                            msgid = "typing-history-$id",
                            serverTime = id * 1_000,
                            sender = "historian",
                            kind = MessageKind.PRIVMSG,
                            text = "History message $id",
                            dedupKey = "typing-history-$id",
                            timelineOrder = id,
                        )
                    },
                ),
            )
        compose.setContent {
            val items = pages.collectAsLazyPagingItems()
            val listState = rememberLazyListState()
            val presentation = remember(items, listState) { ChatPresentation(items, listState, mutableFloatStateOf(1f)) }
            SideEffect { timeline = listState }
            MotdTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    ChatContent(
                        state = ChatState(buffer = room, connState = IrcClientState.Ready("me", emptySet(), emptyMap()), typingNicks = nicks, memberCount = 3),
                        items = items,
                        presentation = presentation,
                        composerEnabled = true,
                        onBack = {},
                        onOpenChannelInfo = {},
                        onOpenSearch = {},
                        onOpenMedia = { _, _ -> },
                        onOpenPalette = { _, _ -> },
                        nickNormalizer = { it.lowercase() },
                        onSubmit = { _, _ -> },
                        onTyping = {},
                        onSetReply = {},
                        onReact = { _, _ -> },
                        onRetry = {},
                        loadPreview = { _, _ -> null },
                        typingIndicatorPlacement = placement,
                        collapseTitleBar = false,
                        entryState = EntryPositionState.Settled,
                        viewportReadEnabled = false,
                        showImages = false,
                        showLinkPreviews = false,
                    )
                }
            }
        }
        compose.waitUntil(5_000) { timeline.layoutInfo.totalItemsCount >= 100 }
        compose.waitForIdle()
    }

    @Test
    fun defaultHeaderPlacementDoesNotAlsoRenderAboveComposer() {
        nicks = listOf("alice")
        setContent()

        assertEquals(TypingIndicatorPlacement.HEADER, placement)
        compose.onNodeWithText("alice is typing…").assertIsDisplayed()
        compose.onNodeWithTag("chat_typing_above_composer").assertDoesNotExist()

        compose.runOnIdle { placement = TypingIndicatorPlacement.ABOVE_COMPOSER }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_typing_above_composer").assertIsDisplayed()
        compose.onAllNodesWithText("alice is typing…").assertCountEquals(1)

        compose.runOnIdle { placement = TypingIndicatorPlacement.HEADER }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_typing_above_composer").assertDoesNotExist()
        compose.onAllNodesWithText("alice is typing…").assertCountEquals(1)
    }

    @Test
    fun typingDotsPulseInBothPlacementsAndStopUnderReducedMotion() {
        nicks = listOf("a", "b", "c")
        motionScale.scaleFactor = 0f
        setContent()

        val placements =
            listOf(
                TypingIndicatorPlacement.HEADER to "chat_title",
                TypingIndicatorPlacement.ABOVE_COMPOSER to "chat_typing_above_composer",
            )

        for ((location, hostTag) in placements) {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { placement = location }
            compose.onNodeWithText("a, b and others are typing…").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { motionScale.scaleFactor = 1f }
            compose.mainClock.advanceTimeBy(64)
            val host = compose.onNodeWithTag(hostTag, useUnmergedTree = true)
            val dots =
                compose
                    .onNode(hasTestTag("chat_typing_dots") and hasAnyAncestor(hasTestTag(hostTag)), useUnmergedTree = true)
                    .assertIsDisplayed()
            val bounds = dots.getUnclippedBoundsInRoot()
            val hostBounds = host.getUnclippedBoundsInRoot()
            val initial = dots.captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(128)
            assertFalse("$location must paint a new typing frame", initial.sameAs(dots.captureToImage().asAndroidBitmap()))
            assertEquals(bounds, dots.getUnclippedBoundsInRoot())
            assertEquals(hostBounds, host.getUnclippedBoundsInRoot())

            compose.runOnIdle { motionScale.scaleFactor = 0f }
            compose.mainClock.advanceTimeBy(48)
            val still = dots.captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(512)
            assertTrue("$location must keep the same dots when Android motion is off", still.sameAs(dots.captureToImage().asAndroidBitmap()))
            assertEquals(bounds, dots.getUnclippedBoundsInRoot())
        }

        compose.mainClock.autoAdvance = true
        compose.runOnIdle { nicks = emptyList() }
        compose.onNodeWithTag("chat_typing_dots", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("3 members").assertIsDisplayed()
    }

    @Test
    fun appearanceAndRemovalRestoreExactHostHeightAndNativePadding() {
        placement = TypingIndicatorPlacement.ABOVE_COMPOSER
        setContent()
        val host = compose.onNodeWithTag("chat_bottom_host")
        val restingBounds = host.getUnclippedBoundsInRoot()
        val restingPadding = compose.runOnIdle { timeline.layoutInfo.beforeContentPadding }
        compose.onNodeWithTag("chat_typing_above_composer").assertDoesNotExist()
        val latestPosition = compose.runOnIdle { timeline.firstVisibleItemIndex to timeline.firstVisibleItemScrollOffset }
        compose.mainClock.autoAdvance = false

        compose.runOnIdle { nicks = listOf("alice") }
        var hostExpanded = false
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val currentBounds = host.getUnclippedBoundsInRoot()
            val currentHeight = currentBounds.bottom - currentBounds.top
            val restingHeight = restingBounds.bottom - restingBounds.top
            if (currentHeight > restingHeight) hostExpanded = true
            if (hostExpanded) {
                compose.onNodeWithTag("chat_typing_above_composer").assertExists()
                assertTrue("An active typing host must not collapse through live-end feedback", currentHeight > restingHeight)
            }
            compose.runOnIdle {
                assertEquals(latestPosition, timeline.firstVisibleItemIndex to timeline.firstVisibleItemScrollOffset)
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val indicator = compose.onNodeWithTag("chat_typing_above_composer").assertIsDisplayed().getUnclippedBoundsInRoot()
        val expandedBounds = host.getUnclippedBoundsInRoot()
        assertEquals(restingBounds.bottom, expandedBounds.bottom)
        assertEquals(indicator.bottom, restingBounds.top)
        assertEquals(indicator.bottom - indicator.top, restingBounds.top - expandedBounds.top)
        compose.runOnIdle {
            assertTrue("The measured typing row must reach native list padding", timeline.layoutInfo.beforeContentPadding > restingPadding)
        }

        compose.runOnIdle { nicks = emptyList() }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_typing_above_composer").assertDoesNotExist()
        assertEquals("An idle typing host must reserve no space", restingBounds, host.getUnclippedBoundsInRoot())
        compose.runOnIdle { assertEquals(restingPadding, timeline.layoutInfo.beforeContentPadding) }
    }

    @Test
    fun removingOneTypistUpdatesTheSameBottomHostToTheRemainingTypist() {
        placement = TypingIndicatorPlacement.ABOVE_COMPOSER
        nicks = listOf("alice", "bob")
        setContent()
        val host = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
        compose.onNodeWithText("alice and bob are typing…").assertIsDisplayed()

        compose.runOnIdle { nicks = listOf("bob") }
        compose.waitForIdle()
        compose.onNodeWithText("alice and bob are typing…").assertDoesNotExist()
        compose.onNodeWithText("bob is typing…").assertIsDisplayed()
        compose.onAllNodesWithText("bob is typing…").assertCountEquals(1)
        assertEquals(host, compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot())
    }

    @Test
    fun parkedHistoryKeepsItsPaintedRowAndOffsetThroughoutTypingTransitions() {
        placement = TypingIndicatorPlacement.ABOVE_COMPOSER
        setContent()
        compose.runOnIdle { runBlocking { timeline.scrollToItem(45, 11) } }
        compose.waitForIdle()
        val parked =
            compose.runOnIdle {
                assertTrue("The fixture must be parked away from latest", timeline.firstVisibleItemIndex > 0)
                Triple(
                    timeline.firstVisibleItemIndex,
                    timeline.firstVisibleItemScrollOffset,
                    timeline.layoutInfo.visibleItemsInfo
                        .first()
                        .key,
                )
            }
        val rowId = 100 - parked.first
        val row = compose.onNodeWithTag("chat_message_typing-history-$rowId", useUnmergedTree = true)
        val bounds = row.assertIsDisplayed().getUnclippedBoundsInRoot()
        val itemCount = compose.runOnIdle { timeline.layoutInfo.totalItemsCount }
        compose.mainClock.autoAdvance = false

        for (typists in listOf(listOf("alice", "bob"), listOf("bob"), emptyList())) {
            compose.runOnIdle { nicks = typists }
            // Sample the actual native traversal throughout AnimatedContent, not only its settled end.
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals("Typing must not move the parked message on screen", bounds, row.getUnclippedBoundsInRoot())
                compose.onNodeWithTag("chat_typing_above_composer").assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals("Typing must not insert decorative timeline rows", itemCount, timeline.layoutInfo.totalItemsCount)
                    assertEquals(
                        parked,
                        Triple(
                            timeline.firstVisibleItemIndex,
                            timeline.firstVisibleItemScrollOffset,
                            timeline.layoutInfo.visibleItemsInfo
                                .first()
                                .key,
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag("chat_typing_above_composer").assertDoesNotExist()

        compose.runOnIdle { nicks = listOf("bob") }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_typing_above_composer").assertDoesNotExist()
        assertEquals(bounds, row.getUnclippedBoundsInRoot())
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { runBlocking { timeline.scrollToItem(0) } }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_typing_above_composer").assertIsDisplayed()
        compose.onNodeWithText("bob is typing…").assertIsDisplayed()
    }
}
