package io.github.trevarj.motd

import android.graphics.Bitmap
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.history.TimelineSeam
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import io.github.trevarj.motd.data.sync.COMMAND_RESPONSE_PAYLOAD_PREFIX
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.NetworkBatchPayloadV1
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.ui.chat.InlineTranslation
import io.github.trevarj.motd.ui.chat.MessageList
import io.github.trevarj.motd.ui.chat.MessageUrlCache
import io.github.trevarj.motd.ui.chat.OutgoingFlight
import io.github.trevarj.motd.ui.chat.ReplyTarget
import io.github.trevarj.motd.ui.chat.TimelineSeamState
import io.github.trevarj.motd.ui.chat.isSystemRunChunkBoundary
import io.github.trevarj.motd.ui.chat.messageUrls
import io.github.trevarj.motd.ui.chat.viewportDateTime
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.dayLabel
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.nav.PalettePreviewRoute
import io.github.trevarj.motd.ui.settings.PalettePreviewScreen
import io.github.trevarj.motd.ui.settings.PalettePreviewViewModel
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.resolvedMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class MessageTimelineUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun collapsedPaletteLinkDoesNotStartPreviewOrAudioWork() {
        var previews = 0
        var probes = 0
        val row =
            message(
                1,
                100,
                MessageKind.PRIVMSG,
                "https://files.example/private.motd-palette.json https://files.example/audio",
            )
        render(
            flowOf(PagingData.from(listOf(row))),
            fools = setOf(row.sender),
            richContentReady = true,
            showLinkPreviews = true,
            automaticRemoteMedia = true,
            loadPreview = { _, _ ->
                previews++
                null
            },
            loadAudioMetadata = { _, _ ->
                probes++
                null
            },
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Preview palette", true).assertDoesNotExist()
        assertEquals(0, previews)
        assertEquals(0, probes)
    }

    @Test fun flightHiddenPaletteRowDefersPreviewAndAudioUntilLanding() {
        var previews = 0
        var probes = 0
        val audio = "https://files.example/flight-audio"
        val row =
            message(
                1,
                100,
                MessageKind.PRIVMSG,
                "https://files.example/flight.motd-palette.json $audio",
                self = true,
                pending = true,
            )
        var flight by mutableStateOf<OutgoingFlight?>(
            OutgoingFlight(token = 1, text = row.text, launchedAtMs = 100, eventIds = setOf(row.id)),
        )
        MessageUrlCache.put(row.text, messageUrls(row.text))
        render(
            flowOf(PagingData.from(listOf(row))),
            richContentReady = true,
            showLinkPreviews = true,
            automaticRemoteMedia = true,
            outgoingFlight = { flight },
            loadPreview = { _, _ ->
                previews++
                null
            },
            loadAudioMetadata = { url, _ ->
                assertEquals(audio, url)
                probes++
                null
            },
        )
        compose.runOnIdle {
            assertEquals("Flight-hidden palette must not fetch a preview", 0, previews)
            assertEquals("Flight-hidden palette must not start auxiliary audio work", 0, probes)
            flight = null
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            previews == 1 && probes == 1
        }
        assertEquals(1, previews)
        assertEquals(1, probes)
    }

    @Test fun disabledPalettePreviewsSuppressEvenCachedCards() {
        val document =
            decodePalette(
                requireNotNull(
                    javaClass.getResourceAsStream(
                        "/palettes/duplicate-last-valid.motd-palette.json",
                    ),
                ).use { it.readBytes() },
            )
        val url = "https://files.example/private.motd-palette.json"
        var requests = 0
        render(
            flowOf(PagingData.from(listOf(message(1, 100, MessageKind.PRIVMSG, url)))),
            richContentReady = true,
            cachedPreview = { _, _ ->
                io.github.trevarj.motd.data.repo.CachedLinkPreview(
                    LinkPreview(url, document.name, null, null, null, LinkPreviewKind.PALETTE, document),
                )
            },
            loadPreview = { _, _ ->
                requests++
                null
            },
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Preview palette", true).assertDoesNotExist()
        assertEquals(0, requests)
    }

    @Test fun selectedPaletteCardCannotOpenPreview() {
        val document =
            decodePalette(
                requireNotNull(
                    javaClass.getResourceAsStream(
                        "/palettes/duplicate-last-valid.motd-palette.json",
                    ),
                ).use { it.readBytes() },
            )
        val url = "https://files.example/selected.motd-palette.json"
        var opens = 0
        render(
            flowOf(PagingData.from(listOf(message(1, 100, MessageKind.PRIVMSG, url)))),
            richContentReady = true,
            showLinkPreviews = true,
            selectionMessageId = 1,
            cachedPreview = { _, _ ->
                io.github.trevarj.motd.data.repo.CachedLinkPreview(
                    LinkPreview(url, document.name, null, null, null, LinkPreviewKind.PALETTE, document),
                )
            },
            onOpenPalette = { _, _ -> opens++ },
        )
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("link_preview_palette", true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("link_preview_palette", true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, opens) }
    }

    @Test fun comfortablePaletteCardLoadsPreviewsCancelsAndApplies() = paletteJourney(LayoutDensity.COMFORTABLE)

    @Test fun compactPaletteCardLoadsPreviewsCancelsAndApplies() = paletteJourney(LayoutDensity.COMPACT)

    @Test fun twoLinePaletteCardLoadsPreviewsCancelsAndApplies() = paletteJourney(LayoutDensity.TWO_LINE)

    @Test fun actionPaletteCardLoadsPreviewsCancelsAndApplies() = paletteJourney(LayoutDensity.COMFORTABLE, MessageKind.ACTION)

    private fun paletteJourney(
        layout: LayoutDensity,
        kind: MessageKind = MessageKind.PRIVMSG,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppearancePrefsImpl(context)
        val settings = DataStoreSettingsRepository(context)
        val original = runBlocking { context.appearanceDataStore.data.first() }
        val models = ViewModelStore()
        val pagingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val document =
            decodePalette(
                requireNotNull(
                    javaClass.getResourceAsStream(
                        "/palettes/duplicate-last-valid.motd-palette.json",
                    ),
                ).use { it.readBytes() },
            )
        val url = "https://files.example/${document.id}.motd-palette.json?download=1"
        val preview = LinkPreview(url, document.name, null, null, null, LinkPreviewKind.PALETTE, document)
        var loads = 0
        var probes = 0
        var openedUrls = 0
        lateinit var nav: NavHostController
        lateinit var source: androidx.navigation.NavBackStackEntry
        lateinit var timeline: LazyListState
        var cached: LinkPreview? = null

        fun await(condition: () -> Boolean) =
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                condition()
            }

        fun current() = runBlocking { prefs.config.first() }

        fun capture(name: String) {
            val file = File("build/reports/palettes/irc-${layout.name.lowercase()}-${kind.name.lowercase()}-$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            runBlocking { context.appearanceDataStore.edit { it.clear() } }
            val baseline = current()
            val rows =
                (40L downTo 1L).map { id ->
                    message(
                        id,
                        id * 100,
                        if (id == 26L) kind else MessageKind.PRIVMSG,
                        if (id == 26L) url else "History message $id",
                    )
                }
            val pages = flowOf(PagingData.from(rows)).cachedIn(pagingScope)
            compose.setContent {
                val appearance by prefs.config.collectAsState(AppearanceConfig())
                val selection = appearance.paletteSelection
                val mode = selection.resolvedMode(isSystemInDarkTheme())
                val choice = selection.choiceFor(mode == PaletteMode.DARK)
                MotdTheme(
                    themePreset = choice.builtin,
                    paletteMode = mode,
                    customPalette = appearance.customPalette(choice),
                    layoutDensity = layout,
                    dynamicColor = false,
                    syncSystemBars = false,
                ) {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = ChatRoute(42)) {
                        composable<ChatRoute> { entry ->
                            source = entry
                            var draft by rememberSaveable { mutableStateOf("") }
                            timeline = rememberLazyListState()
                            Column {
                                CompositionLocalProvider(LocalAutomaticRemoteMedia provides false) {
                                    MessageList(
                                        items = pages.collectAsLazyPagingItems(Dispatchers.Main.immediate),
                                        listState = timeline,
                                        networkId = 1,
                                        readMarkerTime = null,
                                        modifier = Modifier.weight(1f),
                                        onLongPress = {},
                                        onReply = {},
                                        onReact = { _, _ -> },
                                        onOpenMedia = { _, _ -> },
                                        onRetry = {},
                                        loadPreview = { _, _ ->
                                            loads++
                                            preview.also { cached = it }
                                        },
                                        cachedPreview = { _, _ ->
                                            cached?.let {
                                                io.github.trevarj.motd.data.repo
                                                    .CachedLinkPreview(it)
                                            }
                                        },
                                        loadAudioMetadata = { _, _ ->
                                            probes++
                                            null
                                        },
                                        richContentReady = true,
                                        showImages = false,
                                        showLinkPreviews = true,
                                        onOpenLink = { openedUrls++ },
                                        onOpenPalette = { palette, originalUrl ->
                                            nav.navigate(PalettePreviewRoute(encodePalette(palette).decodeToString(), sourceLink = originalUrl))
                                        },
                                    )
                                }
                                TextField(draft, { draft = it }, Modifier.testTag("palette_source_composer"))
                            }
                        }
                        composable<PalettePreviewRoute> { entry ->
                            val route = entry.toRoute<PalettePreviewRoute>()
                            val model =
                                remember(entry) {
                                    PalettePreviewViewModel(
                                        SavedStateHandle(
                                            mapOf(
                                                "paletteJson" to route.paletteJson,
                                                "sourceLink" to route.sourceLink,
                                            ),
                                        ),
                                        prefs,
                                        settings,
                                    ).also { models.put(entry.id, it) }
                                }
                            PalettePreviewScreen(onBack = { nav.popBackStack() }, onInstalled = { nav.popBackStack() }, viewModel = model)
                        }
                    }
                }
            }
            await { compose.onAllNodesWithTag("chat_timeline", true).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { runBlocking { timeline.scrollToItem(12, 23) } }
            await { compose.onAllNodesWithTag("link_preview_awaiting", true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("palette_source_composer").performTextInput("unsent source draft")
            val sourceEntry = source
            assertEquals(0, loads)
            compose.onNodeWithTag("link_preview_awaiting", true).performClick()
            await { compose.onAllNodesWithContentDescription("Preview palette", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            capture("card")
            assertEquals(1, loads)
            assertEquals(0, probes)
            val sourceViewport = timeline.firstVisibleItemIndex to timeline.firstVisibleItemScrollOffset
            assertTrue("Exercise a scrolled source channel", sourceViewport.first > 0 && sourceViewport.second > 0)
            compose.onNodeWithContentDescription("Preview palette", true).performClick()
            await { nav.currentDestination?.hasRoute<PalettePreviewRoute>() == true }
            await { compose.onAllNodesWithText(document.name).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(baseline, current())
            compose.onNodeWithTag("palette_preview_list").performScrollToNode(hasTestTag("palette_preview_sample_light"))
            compose.onNodeWithTag("palette_preview_sample_light", true).assertIsDisplayed()
            capture("preview-light")
            compose.onNodeWithTag("palette_preview_list").performScrollToNode(hasTestTag("palette_preview_sample_dark"))
            compose.onNodeWithTag("palette_preview_sample_dark", true).assertIsDisplayed()
            capture("preview-dark")
            compose.onNodeWithTag("palette_preview_cancel").performClick()
            await { nav.currentDestination?.hasRoute<ChatRoute>() == true }
            assertTrue(sourceEntry === nav.currentBackStackEntry)
            compose.onNodeWithTag("palette_source_composer").assertTextEquals("unsent source draft")
            assertEquals(sourceViewport, timeline.firstVisibleItemIndex to timeline.firstVisibleItemScrollOffset)
            compose.onNodeWithContentDescription("Preview palette", true).performClick()
            await { nav.currentDestination?.hasRoute<PalettePreviewRoute>() == true }
            await { compose.onAllNodesWithText(document.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("palette_preview_apply").performClick()
            await { nav.currentDestination?.hasRoute<ChatRoute>() == true }
            val applied = current()
            assertEquals(document.id, applied.paletteSelection.light.installedId)
            assertEquals(document.id, applied.paletteSelection.dark.installedId)
            assertEquals(baseline.paletteSelection.mode, applied.paletteSelection.mode)
            val installed = current().customPalettes.single()
            assertEquals(url, installed.sourceLink)
            assertEquals(null, installed.catalogId)
            assertEquals(null, installed.catalogRevision)
            assertTrue(sourceEntry === nav.currentBackStackEntry)
            compose.onNodeWithTag("palette_source_composer").assertTextEquals("unsent source draft")
            assertEquals(sourceViewport, timeline.firstVisibleItemIndex to timeline.firstVisibleItemScrollOffset)
            assertEquals(1, loads)
            assertEquals(0, probes)
            assertEquals(0, openedUrls)
        } finally {
            models.clear()
            pagingScope.cancel()
            runBlocking {
                context.appearanceDataStore.edit { target ->
                    target.clear()
                    original.asMap().forEach { (key, value) ->
                        @Suppress("UNCHECKED_CAST")
                        target[key as Preferences.Key<Any>] = value
                    }
                }
            }
        }
    }

    @Test
    fun canonicalVariantsRenderOnceInTimelineOrder() {
        val rows =
            listOf(
                message(6, 600, MessageKind.PRIVMSG, "failed", self = true, failed = true),
                message(5, 500, MessageKind.PRIVMSG, "sending", self = true, pending = true),
                message(4, 400, MessageKind.NOTICE, "notice"),
                message(3, 300, MessageKind.ACTION, "waves"),
                message(2, 200, MessageKind.JOIN, "joined"),
                message(1, 100, MessageKind.PRIVMSG, "oldest"),
            )
        render(flowOf(PagingData.from(rows)), TimelineAnchor(250, 2))

        listOf(6L, 5L, 4L, 3L, 1L).forEach { assertMessageOnce(it) }
        scrollTo("chat_system_pill")
        compose.onAllNodesWithTag("chat_system_pill", useUnmergedTree = true).assertCountEquals(1)
        scrollTo("chat_read_marker_divider")
        compose.onAllNodesWithTag("chat_read_marker_divider", useUnmergedTree = true).assertCountEquals(1)
        val divider = bounds("chat_read_marker_divider")
        val firstUnread = bounds(messageTag(3))
        assertTrue("read marker divider must render above the first unread row", divider.bottom <= firstUnread.top)

        scrollTo(messageTag(4))
        val notice = bounds(messageTag(4))
        val action = bounds(messageTag(3))
        assertTrue("newer NOTICE must render below older ACTION in the reversed timeline", notice.top > action.top)
    }

    @Test
    fun failedMessageCanRetryAndOnlyDeletesAfterConfirmation() {
        val failed = message(1, 100, MessageKind.PRIVMSG, "failed message", self = true, failed = true).copy(msgid = null)
        val survivor = failed.copy(id = 2, serverTime = 200, text = "other failed message")
        var retriedId: Long? = null
        val deletedIds = mutableListOf<Long>()
        render(
            pages = flowOf(PagingData.from(listOf(survivor, failed))),
            onRetry = { retriedId = it.id },
            onDelete = { deletedIds += it.id },
        )

        fun recoveryAction(
            label: String,
            row: MessageEntity,
        ) = compose.onAllNodesWithContentDescription(label).let { buttons ->
            val bottom = bounds("chat_message_${row.id}").bottom
            val closest =
                buttons
                    .fetchSemanticsNodes()
                    .withIndex()
                    .filter { it.value.boundsInRoot.center.y >= bottom }
                    .minBy { it.value.boundsInRoot.center.y }
            buttons[closest.index].assertHeightIsAtLeast(48.dp)
        }
        recoveryAction("Retry", failed).performClick()
        assertEquals(failed.id, retriedId)

        fun delete(row: MessageEntity) = recoveryAction("Delete", row).performClick()
        val confirm = compose.onNodeWithTag("chat_failed_delete_confirm")
        delete(failed)
        assertTrue(deletedIds.isEmpty())
        compose.onNodeWithTag("chat_failed_delete_cancel").performClick()
        confirm.assertDoesNotExist()
        assertTrue(deletedIds.isEmpty())
        delete(survivor)
        delete(failed)
        confirm.assertHeightIsAtLeast(48.dp).performClick()
        confirm.assertDoesNotExist()
        assertEquals(listOf(failed.id), deletedIds)
        recoveryAction("Retry", survivor).assertIsDisplayed()
    }

    @Test
    fun differentNickAfterReplyStartsANewGroupEvenWithTheSameAccount() {
        val reply =
            message(1, 100, MessageKind.PRIVMSG, "first reply").copy(
                senderAccount = "shared",
                replyToMsgid = "parent",
            )
        val nextReply =
            message(2, 200, MessageKind.PRIVMSG, "second reply").copy(
                sender = "bob",
                normalizedActor = "bob",
                senderAccount = "shared",
                replyToMsgid = "parent",
            )
        val continuation = nextReply.copy(id = 3, msgid = "m3", serverTime = 300, text = "continued", replyToMsgid = null)
        render(flowOf(PagingData.from(listOf(continuation, nextReply, reply))))

        scrollTo(messageTag(2))
        compose
            .onNode(hasText("bob") and hasAnyAncestor(hasTestTag(messageTag(2))), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNode(hasText("parent text") and hasAnyAncestor(hasTestTag(messageTag(2))), useUnmergedTree = true)
            .assertIsDisplayed()
        scrollTo(messageTag(3))
        compose
            .onNode(hasText("bob") and hasAnyAncestor(hasTestTag(messageTag(3))), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun eventOnlyReplyKeepsItsQuoteAndResolvesALateParent() {
        val target = ReplyTarget(eventId = 41)
        val parent = MutableStateFlow<ReplyPreviewData?>(null)
        val previews = mapOf(target to parent)
        var opened: ReplyTarget? = null
        val reply =
            message(2, 200, MessageKind.PRIVMSG, "child body", self = true).copy(
                replyToEventId = target.eventId,
                replyToMsgid = null,
            )
        val unrelated = message(3, 300, MessageKind.PRIVMSG, "unrelated body")
        render(
            flowOf(PagingData.from(listOf(unrelated, reply))),
            replyPreview = previews::getValue,
            onReplyPreviewClick = { opened = it },
        )

        scrollTo(messageTag(2))
        compose
            .onNodeWithTag("chat_reply_preview", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasNoClickAction()
        val unavailable = RuntimeEnvironment.getApplication().getString(R.string.chat_reply_target_unavailable)
        compose
            .onNode(hasText(unavailable) and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("child body", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnIdle {
            parent.value = ReplyPreviewData(sender = "parent nick", text = "original parent body")
        }

        compose
            .onNode(hasText("parent nick") and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNode(hasText("original parent body") and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(unavailable, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("child body", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithTag("chat_reply_preview", useUnmergedTree = true)
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(ReplyTarget(eventId = 41), opened) }

        scrollTo(messageTag(3))
        compose.onNodeWithText("unrelated body", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNode(hasTestTag("chat_reply_preview") and hasAnyAncestor(hasTestTag(messageTag(3))), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun typedEventsExposeStableActionsAndFallbacks() {
        var joins = 0
        var dismissals = 0
        val rows =
            listOf(
                message(4, 400, MessageKind.DCC_TRANSFER, "file offer", payload = null),
                message(
                    3,
                    300,
                    MessageKind.NETJOIN,
                    "network healed",
                    payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice", "bob")).encode(),
                ),
                message(
                    2,
                    200,
                    MessageKind.NETSPLIT,
                    "network split",
                    payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice")).encode(),
                ),
                message(
                    1,
                    100,
                    MessageKind.INVITE,
                    "alice invited you",
                    payload = InvitePayloadV1("alice", "me", "#journey").encode(),
                    inviteState = InviteState.PENDING,
                ),
            )
        render(
            flowOf(PagingData.from(rows)),
            onAcceptInvite = { joins++ },
            onDismissInvite = { dismissals++ },
        )

        scrollTo("chat_invite_card_1")
        compose.onNodeWithTag("chat_invite_join_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("chat_invite_dismiss_1", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, joins)
            assertEquals(1, dismissals)
        }
        listOf("chat_network_batch_netsplit_2", "chat_network_batch_netjoin_3", "chat_dcc_transfer_compact_4")
            .forEach { tag ->
                scrollTo(tag)
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
            }
    }

    @Test
    fun expandedNetworkJoinSurvivesSeamRemovalAndAddition() {
        val join =
            message(
                2,
                5_000,
                MessageKind.NETJOIN,
                "network healed",
                payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice", "bob")).encode(),
            )
        val withSeam =
            TimelineSeamState(
                seams = listOf(TimelineSeam(1, TimelineAnchor(join.serverTime, join.id, join.timelineOrder), recoverable = true)),
                historyUnavailable = false,
            )
        var seams by mutableStateOf(withSeam)
        // Keep the entity, PagingData and native presenter unchanged; only gap presentation changes.
        val pages = flowOf(PagingData.from(listOf(join, message(1, 1_010, MessageKind.PRIVMSG, "older island"))))
        render(pages, timelineSeams = { seams })
        val pill = hasTestTag("chat_network_batch_netjoin_2")
        val toggle = pill and hasAnyDescendant(hasText("2 users rejoined (a.example ↔ b.example)"))
        compose.onNodeWithTag("chat_gap_divider", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(toggle, useUnmergedTree = true).performClick()

        fun assertExpanded() {
            compose.onAllNodes(pill, useUnmergedTree = true).assertCountEquals(1)
            for (nick in listOf("alice", "bob")) {
                val line = hasText(nick) and hasAnyAncestor(pill)
                compose.onAllNodes(line, useUnmergedTree = true).assertCountEquals(1)
                compose.onNode(line, useUnmergedTree = true).assertIsDisplayed()
            }
        }

        assertExpanded()
        for (next in listOf(TimelineSeamState(), withSeam)) {
            compose.runOnIdle { seams = next }
            compose.onAllNodesWithTag("chat_gap_divider", useUnmergedTree = true).assertCountEquals(if (next.seams.isEmpty()) 0 else 1)
            assertExpanded()
            // The preserved pill must still respond, not merely leave stale expanded pixels behind.
            compose.onNodeWithContentDescription("2 users rejoined (a.example ↔ b.example)", useUnmergedTree = true).performClick()
            for (nick in listOf("alice", "bob")) {
                compose.onAllNodes(hasText(nick) and hasAnyAncestor(pill), useUnmergedTree = true).assertCountEquals(0)
            }
            compose.onNode(toggle, useUnmergedTree = true).performClick()
            assertExpanded()
        }
    }

    @Test
    fun singleCommandReplyStartsCollapsedAndExpands() {
        val reply =
            message(
                1,
                100,
                MessageKind.SERVER_INFO,
                "Message of the day complete",
                payload = "${COMMAND_RESPONSE_PAYLOAD_PREFIX}motd-session",
            ).copy(sender = "/motd")
        render(flowOf(PagingData.from(listOf(reply))))

        scrollTo("chat_system_pill")
        compose.onNodeWithText("/motd · 1 reply", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Message of the day complete", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("chat_system_pill", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Message of the day complete", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun dickordWrappedStandaloneAudioRendersOnlyThePlayer() {
        val url = "https://files.example/voice.ogg?ex=abc&is=def&hm=123"
        val row = message(1, 100, MessageKind.PRIVMSG, "<$url>")
        render(
            flowOf(PagingData.from(listOf(row))),
            dickordEnabled = true,
            conversationName = "Alice Smith",
            hideInlineAudioLinks = true,
            directMessage = true,
            richContentReady = true,
        )

        scrollTo(messageTag(row.id))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun dickordAudioOnlyReplyKeepsItsQuoteWithoutUrlBody() {
        val url = "https://files.example/voice.ogg?ex=abc&is=def&hm=123"
        val row = message(1, 100, MessageKind.PRIVMSG, "<$url>").copy(replyToMsgid = "parent")
        render(
            flowOf(PagingData.from(listOf(row))),
            dickordEnabled = true,
            conversationName = "Alice Smith",
            hideInlineAudioLinks = true,
            richContentReady = true,
        )
        scrollTo(messageTag(row.id))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("parent text", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun standaloneAudioKeepsUrlWhileDickordIsDisabled() {
        val url = "https://files.example/clip.mp3"
        val row = message(1, 100, MessageKind.PRIVMSG, url)
        render(
            flowOf(PagingData.from(listOf(row))),
            conversationName = "#discord.me.chat.alice",
            richContentReady = true,
        )

        scrollTo(messageTag(row.id))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(url, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun dickordModeCleansTimelineLabelsAndAudioOrigin() {
        val expanded =
            message(2, 400_000, MessageKind.PRIVMSG, "listen https://files.example/clip.mp3")
                .copy(
                    sender = "Alice/discord",
                    normalizedActor = "alice/discord",
                    replyToMsgid = "parent",
                )
        val collapsed =
            message(1, 100, MessageKind.PRIVMSG, "hidden")
                .copy(sender = "Carol/discord", normalizedActor = "carol/discord")
        var played: AudioPlaybackRequest? = null
        render(
            flowOf(PagingData.from(listOf(expanded, collapsed))),
            replyPreview = { MutableStateFlow(ReplyPreviewData("Bob/discord", "parent text")) },
            dickordEnabled = true,
            conversationName = "Alice Smith",
            hideInlineAudioLinks = true,
            richContentReady = true,
            fools = setOf("Alice/discord", "Carol/discord"),
            foolExpanded = { it == expanded.id },
            onAudioToggle = { played = it },
        )

        scrollTo(messageTag(expanded.id))
        compose
            .onNode(hasText("Alice") and hasAnyAncestor(hasTestTag(messageTag(expanded.id))), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNode(hasText("Bob") and hasAnyAncestor(hasTestTag("chat_reply_preview")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_fool_collapse, "Alice"))
            .assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("listen", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("https://files.example/clip.mp3", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals("Alice", played?.origin?.sender)
            assertEquals("Alice Smith", played?.origin?.conversation)
            assertEquals("https://files.example/clip.mp3", played?.attachment?.url)
        }

        scrollTo(messageTag(collapsed.id))
        compose
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_fool_hidden, "Carol"))
            .assertIsDisplayed()
        listOf("Alice/discord", "Bob/discord", "Carol/discord").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun dickordLabelsStayRawWhileModeIsDisabled() {
        val row =
            message(1, 100, MessageKind.PRIVMSG, "child")
                .copy(
                    sender = "Alice/discord",
                    normalizedActor = "alice/discord",
                    replyToMsgid = "parent",
                )
        render(
            flowOf(PagingData.from(listOf(row))),
            replyPreview = { MutableStateFlow(ReplyPreviewData("Bob/discord", "parent text")) },
            conversationName = "#discord.me.chat.alice",
        )

        scrollTo(messageTag(row.id))
        compose.onNodeWithText("Alice/discord", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Bob/discord", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Alice", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Bob", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun firstPagingRowsKeepNewestVisibleAfterAnEmptyTimelineWasMeasured() {
        val pages = MutableStateFlow(PagingData.empty<MessageEntity>())
        render(pages)
        // Let the real LazyColumn measure its empty presentation before Paging publishes rows.
        // Reusing the empty footer's key at the loaded history tail must not move this viewport.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_timeline").assertIsDisplayed()

        compose.runOnIdle {
            pages.value =
                PagingData.from(
                    (24L downTo 1L).map { id ->
                        message(id, id * 1_000, MessageKind.PRIVMSG, "Message $id\nConversation context.")
                    },
                )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        // No scrollTo helper: an assertion must not repair the position being tested.
        compose.onNodeWithTag(messageTag(24), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun pagingReplacementKeepsTheVisibleMessageAnchorStable() {
        val systemRun =
            listOf(
                message(1_002, 72_000, MessageKind.JOIN, "alice joined"),
                message(1_001, 71_000, MessageKind.PART, "bob left"),
            )
        val messages =
            (70L downTo 1L).map { id ->
                message(id, id * 1_000, MessageKind.PRIVMSG, "row $id")
            }
        val original = systemRun + messages
        val pages = MutableStateFlow(PagingData.from(original))
        render(pages)
        scrollTo("chat_system_pill")
        compose.onNodeWithTag("chat_system_pill", useUnmergedTree = true).performClick()
        compose.onNodeWithText("alice joined", useUnmergedTree = true).assertIsDisplayed()
        scrollTo(messageTag(25))
        val before = bounds(messageTag(25))

        pages.value =
            PagingData.from(
                listOf(message(1_003, 73_000, MessageKind.PRIVMSG, "row 71")) +
                    systemRun +
                    message(1_000, 70_500, MessageKind.QUIT, "carol quit") +
                    messages,
            )
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(messageTag(25), useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        }
        val after = bounds(messageTag(25))
        assertTrue("Paging replacement moved the visible keyed row", abs(after.top - before.top) <= 2f)
        compose.onAllNodesWithTag(messageTag(25), useUnmergedTree = true).assertCountEquals(1)
        assertTrue(bounds(messageTag(26)).top > after.top)
        assertTrue(bounds(messageTag(24)).top < after.top)

        scrollTo("chat_system_pill")
        compose.onNodeWithText("alice joined", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("bob left", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("carol quit", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun temporaryFoolRevealFadesNativePixelsInAndOutWithoutFadingTheAnchor() {
        val ordinary = message(3, 86_403_000, MessageKind.PRIVMSG, "untouched anchor").copy(sender = "ordinary", normalizedActor = "ordinary")
        val hidden = message(2, 1_000, MessageKind.PRIVMSG, "revealed fool\nsecond line\nthird line\nfourth line")
        val pages = MutableStateFlow(PagingData.from(listOf(ordinary)))
        var revealed by mutableStateOf(false)
        var motion by mutableStateOf(false)
        var restoreRequested by mutableStateOf(false)
        lateinit var visibility: Transition<Boolean>
        val timelineState = LazyListState()
        render(
            pages,
            fools = setOf("alice"),
            foolsMode = FoolsMode.HIDE,
            foolExpanded = { revealed },
            temporaryFoolVisibilityMotion = { motion },
            hiddenFoolVisibility = {
                val transition = updateTransition(!restoreRequested, label = "test_hidden_fools")
                SideEffect { visibility = transition }
                transition
            },
            preserveOutgoingHiddenBodies = { restoreRequested },
            listState = timelineState,
        )
        compose.mainClock.autoAdvance = false
        val timeline = compose.onNodeWithTag("chat_timeline")
        val rootBounds = timeline.fetchSemanticsNode().boundsInRoot
        val anchorBounds = bounds(messageTag(3))

        fun crop(
            image: Bitmap,
            area: Rect,
        ): Bitmap =
            Bitmap.createBitmap(
                image,
                (area.left - rootBounds.left).toInt(),
                (area.top - rootBounds.top).toInt(),
                area.width.toInt(),
                area.height.toInt(),
            )

        fun screen(): Bitmap = timeline.captureToImage().asAndroidBitmap()

        val before = screen()
        val anchorPaint = crop(before, anchorBounds)
        compose.runOnIdle {
            revealed = true
            motion = true
            pages.value = PagingData.from(listOf(ordinary, hidden))
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onAllNodesWithTag(messageTag(2), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        val affected = bounds(messageTag(2))
        val showingFrame = screen()
        val showing = crop(showingFrame, affected)
        assertTrue("Show must not fade the ordinary root-painted bubble", anchorPaint.sameAs(crop(showingFrame, anchorBounds)))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val shownFrame = screen()
        val shown = crop(shownFrame, affected)
        assertTrue("a fully shown fool must not displace or fade the ordinary anchor", anchorPaint.sameAs(crop(shownFrame, anchorBounds)))
        val absent = crop(before, affected)
        assertFalse("Show must paint an intermediate affected frame", showing.sameAs(absent))
        assertFalse("Show must not jump directly to opaque fool content", showing.sameAs(shown))

        // Native Hidden hosts exit before their owner changes exact SQL visibility.
        compose.runOnIdle { motion = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            revealed = false
            motion = true
            restoreRequested = true
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            !visibility.targetState && visibility.isRunning &&
                compose.onAllNodesWithTag(messageTag(2)).fetchSemanticsNodes().isEmpty()
        }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        // Semantics/input are gone immediately, while the native visibility exit still paints.
        assertTrue("the sampled Restore must still be running", visibility.isRunning)
        val restoringFrame = screen()
        val restoring = crop(restoringFrame, affected)
        assertFalse("the explicit Restore must paint an intermediate native exit", restoring.sameAs(absent))
        assertFalse("Restore must have a genuinely translucent affected frame", restoring.sameAs(shown))
        assertTrue("Restore must not fade the ordinary root-painted bubble", anchorPaint.sameAs(crop(restoringFrame, anchorBounds)))
        assertTrue("the native exit must still own this intermediate frame", visibility.isRunning)
        assertTrue(visibility.currentState)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertFalse(visibility.currentState)
        assertFalse(visibility.isRunning)
        compose.runOnIdle { pages.value = PagingData.from(listOf(ordinary)) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val restoredFrame = screen()
        assertTrue("the completed exit must not leave fool pixels", absent.sameAs(crop(restoredFrame, affected)))
        assertEquals(anchorBounds, bounds(messageTag(3)))
        compose.runOnIdle { restoreRequested = false }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        // Reverse an in-flight native exit; the latest Show owns the final row.
        compose.runOnIdle {
            revealed = true
            pages.value = PagingData.from(listOf(ordinary, hidden))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            revealed = false
            restoreRequested = true
        }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.runOnIdle {
            revealed = true
            restoreRequested = false
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(anchorPaint.sameAs(crop(screen(), anchorBounds)))
        compose.runOnIdle {
            motion = false
            pages.value = PagingData.from(listOf(ordinary))
        }
        compose.waitUntil("native provider applies the canonical row deletion", 10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val visible = timelineState.layoutInfo.visibleItemsInfo
            visible.any { it.key == ordinary.id } && visible.none { it.key == hidden.id }
        }
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).assertDoesNotExist()
        assertTrue("canonical removal cannot retain native exit pixels", absent.sameAs(crop(screen(), affected)))
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun historyReplacementEasesSurvivingRowsWithoutFadingOrMovingTheAnchor() {
        val original =
            (6L downTo 1L).map { id ->
                message(id, id * 1_000, MessageKind.PRIVMSG, "row $id")
            }
        val pages = MutableStateFlow(PagingData.from(original))
        render(pages)
        compose.mainClock.autoAdvance = false
        val anchorBefore = bounds(messageTag(6))
        val before = bounds(messageTag(2))

        fun paintedRow(
            id: Long,
            textOnly: Boolean = false,
        ): Bitmap {
            // Capture the timeline so ancestor/item alpha, not just row semantics, is exercised.
            val timeline = compose.onNodeWithTag("chat_timeline")
            val root = timeline.fetchSemanticsNode().boundsInRoot
            // Moving rows can expose recovered history through their outer padding. Compare their
            // text and opaque fill, not that transparent gutter; the anchored row keeps its full crop.
            val row =
                if (textOnly) {
                    compose.onNodeWithText("row $id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                } else {
                    bounds(messageTag(id))
                }
            return Bitmap.createBitmap(
                timeline.captureToImage().asAndroidBitmap(),
                (row.left - root.left).toInt() + 1,
                (row.top - root.top).toInt() + 1,
                row.width.toInt() - 2,
                row.height.toInt() - 2,
            )
        }

        val anchorPaint = paintedRow(6)
        val rowPaint = paintedRow(2, textOnly = true)
        compose.runOnIdle {
            pages.value =
                PagingData.from(
                    original.take(4) +
                        message(25, 2_500, MessageKind.PRIVMSG, "Recovered history\none\ntwo\nthree") +
                        original.drop(4),
                )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val intermediate = bounds(messageTag(2))
        compose.onAllNodesWithTag(messageTag(2), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).assertIsDisplayed()
        val intermediatePaint = paintedRow(2, textOnly = true)
        assertTrue("History refresh must not fade the anchored timeline", anchorPaint.sameAs(paintedRow(6)))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val settled = bounds(messageTag(2))
        val settledPaint = paintedRow(2, textOnly = true)

        assertTrue("Recovered history must displace the surviving older row", before.top - settled.top > 16f)
        assertTrue("The intermediate frame must move toward the new placement", intermediate.top < before.top - 2f)
        assertTrue("The intermediate frame must not jump straight to the new placement", intermediate.top > settled.top + 2f)
        assertEquals("The newest keyed anchor must not move", anchorBefore, bounds(messageTag(6)))
        assertTrue("A moving survivor must stay fully painted", rowPaint.sameAs(intermediatePaint))
        assertTrue("A settled survivor must keep the same painted content", rowPaint.sameAs(settledPaint))
        listOf(6L, 2L, 25L).forEach {
            compose.onAllNodesWithTag(messageTag(it), useUnmergedTree = true).assertCountEquals(1)
        }
    }

    @Test
    fun liveEntryGapOwnsPlacementUntilItCompletesThenHistoryCanEase() {
        val original = (6L downTo 1L).map { id -> message(id, id * 1_000, MessageKind.PRIVMSG, "row $id") }
        val pages = MutableStateFlow(PagingData.from(original))
        val listState = LazyListState()
        var liveIds by mutableStateOf(emptySet<Long>())
        render(pages, listState = listState, liveEntryIds = { liveIds })
        val before = bounds(messageTag(6))
        val liveRow = message(7, 7_000, MessageKind.PRIVMSG, "live row")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            liveIds = setOf(liveRow.id)
            pages.value = PagingData.from(listOf(liveRow) + original)
            listState.requestScrollToItem(0)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val openingHeight =
            compose.runOnIdle {
                listState.layoutInfo.visibleItemsInfo
                    .single { it.key == liveRow.id }
                    .size
            }
        assertTrue("The live entrance must have begun opening its gap", openingHeight > 0)
        assertEquals("Older rows must follow the live gap without a second spring", before.top - openingHeight, bounds(messageTag(6)).top, 1f)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val finalHeight =
            compose.runOnIdle {
                listState.layoutInfo.visibleItemsInfo
                    .single { it.key == liveRow.id }
                    .size
            }
        assertTrue("The sampled frame must precede completion of the live entrance", openingHeight < finalHeight)
        assertEquals(before.top - finalHeight, bounds(messageTag(6)).top, 1f)

        // The live ID deliberately remains registered, as it does until the real row is disposed.
        val historyBefore = bounds(messageTag(2))
        compose.runOnIdle {
            pages.value =
                PagingData.from(
                    listOf(liveRow) + original.take(4) +
                        message(25, 2_500, MessageKind.PRIVMSG, "Recovered history\none\ntwo\nthree") +
                        original.drop(4),
                )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val intermediate = bounds(messageTag(2))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val settled = bounds(messageTag(2))
        assertTrue("Completed live motion must release history placement", intermediate.top > settled.top + 2f && intermediate.top < historyBefore.top - 2f)
    }

    @Test
    fun arrivedImageExpandsWithoutSnappingOlderBubble() {
        val image = message(2, 2_000, MessageKind.PRIVMSG, "https://example.invalid/late-preview.png")
        val older = message(1, 1_000, MessageKind.PRIVMSG, "older bubble")
        MessageUrlCache.put(image.text, messageUrls(image.text))
        val listState = LazyListState()
        var showImages by mutableStateOf(false)
        render(
            flowOf(PagingData.from(listOf(image, older))),
            listState = listState,
            liveEntryIds = { setOf(image.id) },
            richContentReady = true,
            showImages = { showImages },
            automaticRemoteMedia = false,
        )

        fun sample() =
            Triple(
                compose.runOnIdle {
                    listState.layoutInfo
                        .visibleItemsInfo
                        .single { it.key == image.id }
                        .size
                },
                bounds(messageTag(image.id)),
                bounds(messageTag(older.id)),
            )

        compose.mainClock.autoAdvance = false
        // Keep the live ID registered, but finish its one-shot entrance before revealing the image.
        compose.mainClock.advanceTimeBy(1_000)
        val (beforeHeight, _, beforeOlder) = sample()
        compose.runOnIdle { showImages = true }
        compose.mainClock.advanceTimeByFrame()
        val (immediateHeight, _, immediateOlder) = sample()
        compose.mainClock.advanceTimeBy(96)
        val (intermediateHeight, intermediateImage, intermediateOlder) = sample()
        compose.mainClock.advanceTimeBy(1_000)
        val (settledHeight, _, settledOlder) = sample()

        assertEquals("The image slot must not snap open", beforeHeight, immediateHeight)
        assertEquals("The older bubble must not jump on the first frame", beforeOlder.top, immediateOlder.top, 1f)
        assertTrue("The image slot must expand through an intermediate height", intermediateHeight in (beforeHeight + 1) until settledHeight)
        assertTrue("The growing image must not overlap the older bubble", intermediateOlder.bottom <= intermediateImage.top + 1f)
        assertEquals(
            "The older bubble must move with the growing slot, not lag behind it",
            beforeOlder.top - (intermediateHeight - beforeHeight),
            intermediateOlder.top,
            1f,
        )
        assertEquals(
            "The older bubble must make room for the full expansion",
            beforeOlder.top - (settledHeight - beforeHeight),
            settledOlder.top,
            1f,
        )
    }

    @Test
    fun sendLandingKeepsItsWindowAnchorWhileTheGapOpens() {
        val sent = message(2, 2_000, MessageKind.PRIVMSG, "outgoing", self = true, pending = true)
        val previous = message(1, 1_000, MessageKind.PRIVMSG, "previous")
        val flight = OutgoingFlight(token = 1, text = sent.text, launchedAtMs = sent.serverTime, eventIds = setOf(sent.id))
        var progress by mutableStateOf(0f)
        var landing: Rect? = null
        render(
            flowOf(PagingData.from(listOf(sent, previous))),
            outgoingFlight = { flight },
            flightProgress = { progress },
            onFlightRowPositioned = { id, rect -> if (id == sent.id) landing = rect },
        )
        compose.onNodeWithText(previous.text, useUnmergedTree = true).assertIsDisplayed()
        val closed = compose.runOnIdle { checkNotNull(landing) }
        assertEquals(0f, closed.height, 0f)

        compose.runOnIdle { progress = 0.5f }
        val halfway = compose.runOnIdle { checkNotNull(landing) }
        compose.runOnIdle { progress = 1f }
        val open = compose.runOnIdle { checkNotNull(landing) }

        assertTrue(open.height > 0f)
        assertEquals(open.height / 2f, halfway.height, 1f)
        assertEquals(open.bottom, halfway.bottom, 1f)
        assertEquals(open.bottom, closed.bottom, 1f)
    }

    @Test
    fun specialMessageHoldsPreserveNestedActions() {
        var joined = 0L
        var dismissed = 0L
        var rejected = 0L
        var held: MessageEntity? = null
        var foolOpen by mutableStateOf(false)
        val transfer =
            DccTransferEntity(
                id = 8,
                networkId = 1,
                timelineEventId = 8,
                offerKey = "offer-8",
                direction = DccDirection.INCOMING,
                protocol = DccTransferProtocol.SEND,
                peerNick = "alice",
                normalizedPeer = "alice",
                filename = "report.txt",
                displayFilename = "report.txt",
                address = "8.8.8.8",
                addressKind = DccAddressKind.IPV4_DOTTED,
                port = 9000,
                sizeBytes = 100,
                token = null,
                state = DccTransferState.OFFERED,
                bytesTransferred = 0,
                destinationUri = null,
                createdAt = 1,
                expiresAt = null,
                updatedAt = 1,
            )
        val rows =
            listOf(
                message(8, 800, MessageKind.DCC_TRANSFER, "active file offer"),
                message(7, 700, MessageKind.NETJOIN, "network healed", payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice", "bob")).encode()),
                message(6, 600, MessageKind.INVITE, "historical invite", inviteState = InviteState.HISTORICAL),
                message(5, 500, MessageKind.INVITE, "alice invited you", payload = InvitePayloadV1("alice", "me", "#room").encode(), inviteState = InviteState.PENDING),
                message(4, 400, MessageKind.NETSPLIT, "network split fallback"),
                message(3, 300, MessageKind.DCC_TRANSFER, "file offer fallback"),
                message(2, 200, MessageKind.PRIVMSG, "concealed fool prose").copy(normalizedActor = "alice"),
                message(1, 100, MessageKind.PRIVMSG, "quiet ebook prose").copy(sender = "bob", normalizedActor = "bob"),
            )
        render(
            flowOf(PagingData.from(rows)),
            onAcceptInvite = { joined = it },
            onDismissInvite = { dismissed = it },
            onLongPress = { held = it },
            dccTransfer = { MutableStateFlow(if (it.id == 8L) transfer else null) },
            onRejectDccTransfer = { rejected = it },
            fools = setOf("alice"),
            foolExpanded = { foolOpen },
            onToggleFool = { foolOpen = !foolOpen },
            quietFeed = true,
        )
        scrollTo("chat_invite_card_5")
        compose.onNodeWithTag("chat_invite_join_5").performClick()
        compose.onNodeWithTag("chat_invite_dismiss_5").performClick()
        compose.runOnIdle {
            assertEquals(5L, joined)
            assertEquals(5L, dismissed)
        }
        scrollTo("chat_dcc_transfer_8")
        compose.onNodeWithTag("chat_dcc_reject_8").performClick()
        compose.runOnIdle { assertEquals(8L, rejected) }
        listOf(
            "chat_dcc_transfer_8" to rows[0],
            "chat_network_batch_netjoin_7" to rows[1],
            "chat_invite_compact_6" to rows[2],
            "chat_invite_card_5" to rows[3],
            "chat_network_batch_4" to rows[4],
            "chat_dcc_transfer_compact_3" to rows[5],
        ).forEach { (tag, row) ->
            scrollTo(tag)
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { longClick() }
            compose.runOnIdle {
                assertEquals(row, held)
                held = null
            }
        }
        scrollTo(messageTag(2))
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(null, held) }
        // Quiet-feed concealment remains a second deliberate reveal, even for an expanded fool.
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(null, held) }
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(rows[6], held)
            held = null
        }
        scrollTo(messageTag(1))
        compose.onNodeWithTag(messageTag(1), useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(null, held) }
        compose.onNodeWithTag(messageTag(1), useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(rows[7], held) }
    }

    @Test
    fun groupedDuplicateLinesKeepExactIdentityAfterPaging() {
        val rows = listOf(message(1002, 300, MessageKind.JOIN, "duplicate"), message(1001, 200, MessageKind.PART, "duplicate"))
        val pages = MutableStateFlow(PagingData.from(rows))
        var held: MessageEntity? = null
        render(pages, onLongPress = { held = it })
        scrollTo("chat_system_pill")
        compose.onNodeWithTag("chat_system_pill", useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(null, held) }
        rows.asReversed().forEach { row ->
            compose.onNodeWithTag("chat_system_event_${row.id}", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnLongClick)
            compose.runOnIdle { assertEquals(row, held) }
        }
        val older = message((900L until 1001L).last { !isSystemRunChunkBoundary(it) }, 100, MessageKind.QUIT, "older")
        compose.runOnIdle { pages.value = PagingData.from(rows + older) }
        compose.onNodeWithTag("chat_system_event_${older.id}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_system_event_1001", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { assertEquals(rows[1], held) }
    }

    @Test
    fun groupedSystemEventsKeepFloatingDateAndSeparatorsAcrossLocalMidnight() {
        val midnight =
            LocalDate
                .of(2024, 2, 6)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val ids =
            (1L..100L)
                .asSequence()
                .filterNot(::isSystemRunChunkBoundary)
                .take(4)
                .toList()
        val rows =
            listOf(
                message(ids[3], midnight + 120_000, MessageKind.JOIN, "00:02 join"),
                message(ids[2], midnight + 60_000, MessageKind.JOIN, "00:01 join"),
                message(ids[1], midnight - 60_000, MessageKind.PART, "23:59 part"),
                message(ids[0], midnight - 120_000, MessageKind.PART, "23:58 part"),
            )
        val listState = LazyListState()
        render(flowOf(PagingData.from(rows)), listState = listState)
        compose.onAllNodesWithTag("chat_system_pill", useUnmergedTree = true).assertCountEquals(2)
        for (summary in listOf("2 left", "2 joined")) {
            compose.onNodeWithText(summary, useUnmergedTree = true).performTouchInput { click() }
        }

        val newerDay = dayLabel(rows[0].serverTime)
        val olderDay = dayLabel(rows[2].serverTime)
        val newerDayBounds = compose.onNodeWithText(newerDay).fetchSemanticsNode().boundsInRoot
        val olderDayBounds = compose.onNodeWithText(olderDay).fetchSemanticsNode().boundsInRoot
        val (first, second, third, fourth) = ids.map { bounds("chat_system_event_$it") }
        assertTrue(olderDayBounds.bottom <= first.top)
        assertTrue(first.bottom <= second.top)
        assertTrue(second.bottom <= newerDayBounds.top)
        assertTrue(newerDayBounds.bottom <= third.top)
        assertTrue(third.bottom <= fourth.top)

        compose.runOnIdle {
            val layout = listState.layoutInfo
            for (head in listOf(rows[2], rows[0])) {
                val slot = layout.visibleItemsInfo.single { it.key == head.id }
                // Put the usable top inside this expanded chunk, as during a drag past midnight.
                val time =
                    viewportDateTime(
                        layout.visibleItemsInfo,
                        bottom = layout.viewportStartOffset,
                        top = slot.offset + slot.size / 2,
                        visibleMessageAt = rows::getOrNull,
                    )
                assertEquals(head.serverTime, requireNotNull(time))
            }
        }
    }

    @Test
    fun externalTranslationDuplicateTextKeepsExactOwnershipAcrossPagingAndDensities() {
        val source = message(1, 100, MessageKind.PRIVMSG, "duplicate")
        val other = message(2, 200, MessageKind.PRIVMSG, "duplicate")
        val pages = MutableStateFlow(PagingData.from(listOf(other, source)))
        val translations = mapOf(source.id to InlineTranslation(1, source.id, source.text, "日本語 translation\nالعربية"))
        var density by mutableStateOf(LayoutDensity.COMFORTABLE)
        render(pages, externalTranslations = { translations }, layoutDensity = { density }, fontScale = 1.5f)
        for (variant in listOf(LayoutDensity.COMFORTABLE, LayoutDensity.COMPACT, LayoutDensity.TWO_LINE)) {
            compose.runOnIdle { density = variant }
            scrollTo("message_translation_1")
            compose.onNodeWithTag("message_translation_2").assertDoesNotExist()
            assertTrue(bounds(messageTag(1)).bottom <= bounds("message_translation_1").top)
            assertTranslationContained(1)
            assertTrue(bounds("message_translation_1").bottom <= bounds(messageTag(2)).top)
        }
        compose.runOnIdle {
            pages.value =
                PagingData.from(
                    listOf(other.copy(serverTime = 201), source.copy(sender = "paging-actor", normalizedActor = "paging-actor", serverTime = 101)),
                )
        }
        scrollTo(messageTag(1))
        compose.onNode(hasText("paging-actor", substring = true) and hasAnyAncestor(hasTestTag(messageTag(1))), useUnmergedTree = true).assertIsDisplayed()
        scrollTo("message_translation_1")
        compose.onNodeWithTag("message_translation_2").assertDoesNotExist()
        assertTrue(bounds(messageTag(1)).bottom <= bounds("message_translation_1").top)
        assertTranslationContained(1)
        compose.onNodeWithText("日本語 translation\nالعربية", useUnmergedTree = true).assertTextEquals("日本語 translation\nالعربية")
    }

    @Test
    fun externalTranslationDefensiveGuardsHideRedactedChangedWrongRoomAndWrongEventResults() {
        val sources = (1L..5L).map { message(it, it * 100, MessageKind.PRIVMSG, "source $it") }
        val pages = MutableStateFlow(PagingData.from(sources.reversed()))
        val translations =
            sources.associate { source ->
                source.id to
                    InlineTranslation(
                        roomId = if (source.id == 3L) 2 else source.bufferId,
                        eventId = if (source.id == 4L) 999 else source.id,
                        originalText = source.text,
                        text = "translation ${source.id}",
                    )
            }
        render(pages, externalTranslations = { translations })
        scrollTo("message_translation_1")
        scrollTo("message_translation_2")
        compose.runOnIdle {
            pages.value =
                PagingData.from(
                    sources
                        .map {
                            when (it.id) {
                                1L -> it.copy(kind = MessageKind.REDACTED)
                                2L -> it.copy(text = "Changed source")
                                else -> it
                            }
                        }.reversed(),
                )
        }
        for (id in 1L..4L) {
            scrollTo(messageTag(id))
            compose.onNodeWithTag("message_translation_$id", useUnmergedTree = true).assertDoesNotExist()
        }
        scrollTo("message_translation_5")
        compose.onNodeWithText("translation 5", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [26], qualifiers = "w411dp-h891dp")
    fun externalTranslationGroupedSystemAppearsOnlyBelowExpandedExactLineAcrossPaging() {
        val newer = message(1002, 300, MessageKind.JOIN, "duplicate")
        val source = message(1001, 200, MessageKind.PART, "duplicate")
        val older = message((900L until source.id).last { !isSystemRunChunkBoundary(it) }, 100, MessageKind.QUIT, "older")
        val pages = MutableStateFlow(PagingData.from(listOf(newer, source)))
        val translations = mapOf(source.id to InlineTranslation(1, source.id, source.text, "Exact older line translation"))
        var holds = 0
        render(pages, externalTranslations = { translations }, onLongPress = { holds++ })
        scrollTo("chat_system_pill")
        compose.onNodeWithTag("message_translation_1001").assertDoesNotExist()
        compose.onNodeWithTag("chat_system_pill", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message_translation_1001", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(bounds("chat_system_event_1001").bottom <= bounds("message_translation_1001").top)
        assertTrue(bounds("message_translation_1001").bottom <= bounds("chat_system_event_1002").top)
        assertTranslationContained(1001, hostId = 1002)
        val resultText = compose.onNodeWithText("Exact older line translation", useUnmergedTree = true)
        assertNoSourceActions(resultText.fetchSemanticsNode())
        resultText.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, holds) }
        compose.onNodeWithTag("message_translation_1002").assertDoesNotExist()
        compose.runOnIdle { pages.value = PagingData.from(listOf(newer, source, older)) }
        compose.onNodeWithTag("chat_system_event_${older.id}", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(bounds("chat_system_event_1001").bottom <= bounds("message_translation_1001").top)
        assertTrue(bounds("message_translation_1001").bottom <= bounds("chat_system_event_1002").top)
        compose.onNodeWithTag("chat_system_event_1002", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message_translation_1001").assertDoesNotExist()
        compose.onNodeWithTag("chat_system_pill", useUnmergedTree = true).performClick()
        scrollTo("message_translation_1001")
        assertTrue(bounds("chat_system_event_1001").bottom <= bounds("message_translation_1001").top)
        assertTrue(bounds("message_translation_1001").bottom <= bounds("chat_system_event_1002").top)
    }

    @Test
    fun externalTranslationSingleSystemAndForcedSingleReplyKeepExactVerticalOwnership() {
        val single = message(1, 100, MessageKind.JOIN, "alice joined")
        val separator = message(2, 200, MessageKind.PRIVMSG, "ordinary separator")
        val forced =
            message(3, 300, MessageKind.SERVER_INFO, "Message of the day complete", payload = "${COMMAND_RESPONSE_PAYLOAD_PREFIX}translation-motd")
                .copy(sender = "/motd")
        val translations =
            listOf(single, forced).associate { it.id to InlineTranslation(it.bufferId, it.id, it.text, "Translation ${it.id}") }
        render(flowOf(PagingData.from(listOf(forced, separator, single))), externalTranslations = { translations })
        scrollTo("message_translation_1")
        val singleBounds = compose.onNodeWithText("alice joined", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(singleBounds.bottom <= bounds("message_translation_1").top)
        assertTranslationContained(1)
        compose.onNodeWithTag("message_translation_3").assertDoesNotExist()
        compose.onNodeWithTag("chat_timeline", useUnmergedTree = true).performScrollToNode(hasText("/motd · 1 reply"))
        compose.onNode(hasTestTag("chat_system_pill") and hasAnyDescendant(hasText("/motd · 1 reply")), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message_translation_3", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(bounds("chat_system_event_3").bottom <= bounds("message_translation_3").top)
        assertTranslationContained(3)
    }

    @Test
    fun externalTranslationSpecialCardsRetainControlsInsideTheirPaintedHosts() {
        val transfer =
            DccTransferEntity(
                id = 8,
                networkId = 1,
                timelineEventId = 8,
                offerKey = "offer-8",
                direction = DccDirection.INCOMING,
                protocol = DccTransferProtocol.SEND,
                peerNick = "alice",
                normalizedPeer = "alice",
                filename = "report.txt",
                displayFilename = "report.txt",
                address = "8.8.8.8",
                addressKind = DccAddressKind.IPV4_DOTTED,
                port = 9000,
                sizeBytes = 100,
                token = null,
                state = DccTransferState.OFFERED,
                bytesTransferred = 0,
                destinationUri = null,
                createdAt = 1,
                expiresAt = null,
                updatedAt = 1,
            )
        val transferState = MutableStateFlow<DccTransferEntity?>(transfer)
        val rows =
            listOf(
                message(8, 800, MessageKind.DCC_TRANSFER, "active file offer"),
                message(7, 700, MessageKind.INVITE, "resolved invite", payload = InvitePayloadV1("alice", "me", "#resolved").encode(), inviteState = InviteState.JOINED),
                message(6, 600, MessageKind.INVITE, "historical invite", inviteState = InviteState.HISTORICAL),
                message(5, 500, MessageKind.NETSPLIT, "network fallback"),
                message(4, 400, MessageKind.DCC_TRANSFER, "file offer"),
                message(3, 300, MessageKind.NETJOIN, "network healed", payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice", "bob")).encode()),
                message(2, 200, MessageKind.NETSPLIT, "network split", payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("alice")).encode()),
                message(1, 100, MessageKind.INVITE, "alice invited you", payload = InvitePayloadV1("alice", "me", "#room").encode(), inviteState = InviteState.PENDING),
            )
        var translations by mutableStateOf(rows.associate { it.id to InlineTranslation(1, it.id, it.text, "Translation ${it.id}") })
        var joined = 0L
        var dismissed = 0L
        var rejected = 0L
        render(
            flowOf(PagingData.from(rows)),
            externalTranslations = { translations },
            onDismissExternalTranslation = { translations = translations - it },
            onAcceptInvite = { joined = it },
            onDismissInvite = { dismissed = it },
            dccTransfer = { if (it.id == 8L) transferState else MutableStateFlow(null) },
            onRejectDccTransfer = {
                rejected = it
                transferState.value = null
            },
        )
        for (source in rows) {
            scrollTo("message_translation_${source.id}")
            assertTranslationContained(source.id)
        }
        scrollTo("chat_network_batch_netjoin_3")
        compose.onNodeWithText("2 users rejoined (a.example ↔ b.example)", useUnmergedTree = true).performClick()
        compose.onNodeWithText("bob", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("message_translation_3", useUnmergedTree = true).assertCountEquals(1)
        assertTranslationContained(3)
        scrollTo("chat_dcc_transfer_8")
        compose.onNodeWithTag("chat_dcc_reject_8").performClick()
        compose.runOnIdle { assertEquals(8L, rejected) }
        compose.waitForIdle()
        scrollTo("message_translation_8")
        compose.onAllNodesWithTag("message_translation_8", useUnmergedTree = true).assertCountEquals(1)
        assertTranslationContained(8)
        scrollTo("chat_invite_card_1")
        compose.onNodeWithTag("chat_invite_join_1").performClick()
        compose.onNodeWithTag("chat_invite_dismiss_1").performClick()
        compose.runOnIdle {
            assertEquals(1L, joined)
            assertEquals(1L, dismissed)
        }
        scrollTo("message_translation_1")
        compose.onNodeWithTag("message_translation_dismiss_1").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("message_translation_1").assertDoesNotExist()
        compose.onNodeWithTag("chat_invite_card_1", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1L, joined)
            assertEquals(1L, dismissed)
        }
    }

    @Test
    fun externalTranslationConcealedFoolAndQuietRowsDoNotRevealResultsUntilExpanded() {
        val fool = message(2, 200, MessageKind.PRIVMSG, "concealed fool prose")
        val quiet = message(1, 100, MessageKind.PRIVMSG, "quiet ebook prose").copy(sender = "bob", normalizedActor = "bob")
        val translations = listOf(fool, quiet).associate { it.id to InlineTranslation(1, it.id, it.text, "Translation ${it.id}") }
        var foolOpen by mutableStateOf(false)
        render(
            flowOf(PagingData.from(listOf(fool, quiet))),
            externalTranslations = { translations },
            fools = setOf("alice"),
            foolExpanded = { foolOpen },
            onToggleFool = { foolOpen = !foolOpen },
            quietFeed = true,
        )
        scrollTo(messageTag(2))
        compose.onNodeWithTag("message_translation_2").assertDoesNotExist()
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message_translation_2").assertDoesNotExist()
        compose.onNodeWithTag(messageTag(2), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message_translation_2").assertIsDisplayed()
        compose.runOnIdle { foolOpen = false }
        compose.onNodeWithTag("message_translation_2").assertDoesNotExist()
        scrollTo(messageTag(1))
        compose.onNodeWithTag("message_translation_1").assertDoesNotExist()
        compose.onNodeWithTag(messageTag(1), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message_translation_1").assertIsDisplayed()
    }

    @Test
    // API26 is supported by MOTD and exercises real selection without Robolectric's broken native magnifier surface.
    @Config(sdk = [26], qualifiers = "w411dp-h891dp")
    fun externalTranslationReturnedTextIsPlainAndNativelySelectable() {
        val source = message(1, 100, MessageKind.PRIVMSG, "source")
        val output = "**Hello** https://invalid.example/a"
        val translations = mapOf(source.id to InlineTranslation(1, source.id, source.text, output))
        var replies = 0
        var holds = 0
        render(
            flowOf(PagingData.from(listOf(source))),
            externalTranslations = { translations },
            onLongPress = { holds++ },
            onReply = { replies++ },
        )
        scrollTo("message_translation_1")
        val text = compose.onNodeWithText(output, useUnmergedTree = true)
        text.assertTextEquals(output).assertHasNoClickAction()
        assertTranslationContained(1)
        assertNoSourceActions(text.fetchSemanticsNode())
        text.performTouchInput { swipe(Offset(width * 0.15f, centerY), Offset(width * 0.85f, centerY), durationMillis = 400) }
        compose.runOnIdle { assertEquals(0, replies) }
        // Native SelectionContainer exposes real selection handles under this semantics key.
        val selectionHandle = SemanticsMatcher("native text selection handle") { node -> node.config.any { it.key.name == "SelectionHandleInfo" } }
        compose.onAllNodes(selectionHandle, useUnmergedTree = true).assertCountEquals(0)
        text.performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodes(selectionHandle, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, holds) }
        val sourceDrag = with(compose.density) { 160.dp.toPx() }
        compose.onNodeWithTag(messageTag(1), useUnmergedTree = true).performTouchInput {
            // Source content can be narrower than the armed drag distance; capture inside it.
            swipe(Offset(centerX, centerY), Offset(centerX + sourceDrag, centerY), durationMillis = 400)
        }
        compose.runOnIdle { assertEquals(1, replies) }
        compose.onNodeWithTag("message_translation_dismiss_1").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun externalTranslationFullTextStaysContainedAcrossRendererRolesAtAccessibleScale() {
        val output = "日本語の翻訳です。 Bonjour à toutes et à tous.\nالعربية محفوظة هنا.\nПривет, мир — Ελληνικά.\nLast visible line: END-TRANSLATION"
        val samples =
            LayoutDensity.entries.flatMap { density ->
                listOf(
                    Triple(density, MessageKind.PRIVMSG, true),
                    Triple(density, MessageKind.ACTION, density != LayoutDensity.COMPACT),
                    Triple(density, MessageKind.NOTICE, false),
                    Triple(density, MessageKind.NOTICE, true),
                )
            } + Triple(LayoutDensity.COMFORTABLE, MessageKind.ACTION, false)
        var sample by mutableStateOf(samples.first())

        fun rows() =
            listOf(
                message(2, 200, sample.second, "source", self = sample.third),
                message(1, 100, sample.second, "previous source", self = sample.third),
            )
        val pages = MutableStateFlow(PagingData.from(rows()))
        render(
            pages,
            externalTranslations = { mapOf(2L to InlineTranslation(1, 2, "source", output)) },
            layoutDensity = { sample.first },
            avatarStyle = { if (sample.second == MessageKind.NOTICE || sample.second == MessageKind.ACTION && !sample.third) AvatarStyle.NONE else AvatarStyle.IRC_SPRITE },
            bubbleCornerStyle = {
                when (sample.second) {
                    MessageKind.PRIVMSG -> BubbleCornerStyle.SQUARE
                    MessageKind.ACTION -> BubbleCornerStyle.SUBTLE
                    else -> BubbleCornerStyle.ROUNDED
                }
            },
            messageSpacing = { if (sample.third) MessageSpacing.RELAXED else MessageSpacing.COMPACT },
            fontScale = 1.5f,
            paneWidth = 320.dp,
        )
        for (variant in samples) {
            compose.runOnIdle {
                sample = variant
                pages.value = PagingData.from(rows())
            }
            scrollTo("message_translation_2")
            assertTranslationContained(2)
            assertTrue(bounds(messageTag(2)).bottom <= bounds("message_translation_2").top)
            assertFullTranslationText(output)
            if (variant.first == LayoutDensity.TWO_LINE && variant.second != MessageKind.ACTION) {
                val body =
                    compose
                        .onNode(
                            hasTestTag("message_two_line_body") and hasAnyAncestor(hasTestTag(messageTag(2))),
                            useUnmergedTree = true,
                        ).fetchSemanticsNode()
                        .boundsInRoot
                assertTrue("two-line result must follow the actual body indent", abs(body.left - bounds("message_translation_2").left) <= 1f)
            }
        }
    }

    @Test
    fun blurredAudioTranslationAndReplyAreCoveredUntilFirstTapAcrossDensities() {
        val source = message(1, 100, MessageKind.PRIVMSG, "<https://files.example/clip.mp3>").copy(replyToMsgid = "parent")
        var expanded by mutableStateOf(false)
        var density by mutableStateOf(LayoutDensity.COMFORTABLE)
        var played: AudioPlaybackRequest? = null
        render(
            flowOf(PagingData.from(listOf(source))),
            externalTranslations = { mapOf(1L to InlineTranslation(1, 1, source.text, "Concealed translation")) },
            dickordEnabled = true,
            conversationName = "Alice",
            hideInlineAudioLinks = true,
            richContentReady = true,
            fools = setOf(source.sender),
            foolsMode = FoolsMode.BLUR,
            foolExpanded = { expanded },
            onToggleFool = { expanded = !expanded },
            onAudioToggle = { played = it },
            layoutDensity = { density },
        )
        for (mode in LayoutDensity.entries) {
            compose.runOnIdle {
                density = mode
                expanded = false
                played = null
            }
            scrollTo("chat_fool_reveal_${source.msgid ?: source.id}")
            compose.onNodeWithText("Concealed translation").assertDoesNotExist()
            compose.onNodeWithTag("chat_fool_reveal_${source.msgid ?: source.id}").performTouchInput { click() }
            compose.runOnIdle { assertEquals(null, played) }
            compose.onNodeWithText("Concealed translation").assertIsDisplayed()
            compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).performTouchInput { click() }
            compose.runOnIdle { assertEquals(1L, played?.origin?.eventId) }
            compose.onNodeWithTag("chat_fool_collapse_${source.msgid ?: source.id}").performClick()
            compose.onNodeWithText("Concealed translation").assertDoesNotExist()
        }
    }

    @Test
    fun externalTranslationStandaloneAudioStaysInsideFirstPlayerWithoutChangingPlaybackOwnership() {
        val url = "https://files.example/clip.mp3"
        val source = message(1, 100, MessageKind.PRIVMSG, "<$url>")
        var played: AudioPlaybackRequest? = null
        render(
            flowOf(PagingData.from(listOf(source))),
            externalTranslations = { mapOf(1L to InlineTranslation(1, 1, source.text, "Translated visible audio URL")) },
            dickordEnabled = true,
            conversationName = "Alice Smith",
            hideInlineAudioLinks = true,
            richContentReady = true,
            onAudioToggle = { played = it },
        )
        scrollTo("message_translation_1")
        compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)
        assertTranslationContained(1, paintedHostTag = "audio_player")
        assertTrue(bounds(messageTag(1)).bottom <= bounds("message_translation_1").top)
        compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1L, played?.origin?.eventId)
            assertEquals(url, played?.attachment?.url)
        }
    }

    @Test
    fun externalTranslationOwnAudioPlayersKeepAlignmentAndControlsAcrossSourceHosts() {
        val urls = listOf("https://files.example/first.mp3", "https://files.example/second.mp3")
        var source by mutableStateOf(message(1, 100, MessageKind.PRIVMSG, "listen ${urls.joinToString(" ") { "<$it>" }}", self = true))
        var translated by mutableStateOf(false)
        val pages = MutableStateFlow(PagingData.from(listOf(source)))
        val played = mutableListOf<AudioPlaybackRequest>()
        render(
            pages,
            externalTranslations = { if (translated) mapOf(1L to InlineTranslation(1, 1, source.text, "Returned audio-source text")) else emptyMap() },
            dickordEnabled = true,
            conversationName = "Alice Smith",
            hideInlineAudioLinks = true,
            richContentReady = true,
            onAudioToggle = { played += it },
        )

        fun audioBounds(): List<Rect> {
            scrollTo(messageTag(1))
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
            return compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        }
        for (standalone in listOf(false, true)) {
            if (standalone) {
                compose.runOnIdle {
                    translated = false
                    source = source.copy(text = urls.joinToString("\n") { "<$it>" })
                    pages.value = PagingData.from(listOf(source))
                }
            }
            val before = audioBounds()
            compose.runOnIdle { translated = true }
            scrollTo("message_translation_1")
            val after = audioBounds()
            before.zip(after).forEachIndexed { index, (original, returned) ->
                assertEquals("own player $index must keep its left anchor", original.left, returned.left, 1f)
                assertEquals("own player $index must keep its right anchor", original.right, returned.right, 1f)
            }
            assertEquals(after.first().right, after.last().right, 1f)
            compose.onAllNodesWithTag("message_translation_1", useUnmergedTree = true).assertCountEquals(1)
            assertTranslationContained(1, paintedHostTag = if (standalone) "audio_player" else "message_translation_host_1")
            for (index in urls.indices) {
                compose.onAllNodesWithTag("audio_player_toggle", useUnmergedTree = true)[index].performClick()
                compose.runOnIdle {
                    assertEquals(urls[index], played.last().attachment.url)
                    assertEquals(1L, played.last().origin?.eventId)
                    assertEquals(true, played.last().origin?.isSelf)
                }
            }
        }
        compose.runOnIdle { assertEquals(4, played.size) }
    }

    @Test
    @Config(sdk = [30], qualifiers = "w411dp-h891dp")
    fun externalTranslationSourceSwipeUsesWindowEdgesInBothDirections() {
        val source = message(1, 100, MessageKind.PRIVMSG, "offset source")
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var translated by mutableStateOf(true)
        var density by mutableStateOf(LayoutDensity.COMFORTABLE)
        var replies = 0
        val insetPx = with(compose.density) { 24.dp.roundToPx() }
        render(
            flowOf(PagingData.from(listOf(source))),
            externalTranslations = { if (translated) mapOf(1L to InlineTranslation(1, 1, source.text, "Returned text")) else emptyMap() },
            layoutDensity = { density },
            layoutDirection = { direction },
            systemGestureInsetPx = insetPx,
            onReply = { replies++ },
        )
        compose.waitUntil(5_000) { observedSystemGestureInsets == (insetPx to insetPx) }
        val sourceDrag = with(compose.density) { 160.dp.toPx() }
        for (value in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle {
                direction = value
                translated = true
                density = LayoutDensity.COMFORTABLE
            }
            scrollTo(messageTag(1))
            val content = bounds(messageTag(1))
            val timeline = bounds("chat_timeline")
            if (value == LayoutDirection.Ltr) {
                assertTrue("translated source starts beyond the physical back edge", content.left > timeline.left + insetPx)
            } else {
                assertTrue("translated source ends before the physical back edge", content.right < timeline.right - insetPx)
            }
            compose.onNodeWithTag(messageTag(1), useUnmergedTree = true).performTouchInput {
                val start = if (value == LayoutDirection.Ltr) 1f else width - 1f
                val end = start + if (value == LayoutDirection.Ltr) sourceDrag else -sourceDrag
                swipe(Offset(start, centerY), Offset(end, centerY), durationMillis = 400)
            }
            compose.runOnIdle { assertEquals(if (value == LayoutDirection.Ltr) 1 else 2, replies) }
            compose.runOnIdle {
                translated = false
                density = LayoutDensity.COMPACT
            }
            scrollTo(messageTag(1))
            val edgeSource = bounds(messageTag(1))
            assertEquals(timeline.left, edgeSource.left, 1f)
            assertEquals(timeline.right, edgeSource.right, 1f)
            compose.onNodeWithTag(messageTag(1), useUnmergedTree = true).performTouchInput {
                val start = if (value == LayoutDirection.Ltr) 1f else width - 1f
                val end = start + if (value == LayoutDirection.Ltr) sourceDrag else -sourceDrag
                swipe(Offset(start, centerY), Offset(end, centerY), durationMillis = 400)
            }
            compose.runOnIdle { assertEquals(if (value == LayoutDirection.Ltr) 1 else 2, replies) }
        }
    }

    private var observedSystemGestureInsets: Pair<Int, Int>? = null

    private fun render(
        pages: Flow<PagingData<MessageEntity>>,
        marker: TimelineAnchor? = null,
        onAcceptInvite: (Long) -> Unit = {},
        onDismissInvite: (Long) -> Unit = {},
        replyPreview: (ReplyTarget) -> StateFlow<ReplyPreviewData?> = { MutableStateFlow(ReplyPreviewData("parent nick", "parent text")) },
        onReplyPreviewClick: (ReplyTarget) -> Unit = {},
        dickordEnabled: Boolean = false,
        conversationName: String? = null,
        hideInlineAudioLinks: Boolean = false,
        directMessage: Boolean = false,
        richContentReady: Boolean = false,
        fools: Set<String> = emptySet(),
        foolsMode: FoolsMode = FoolsMode.COLLAPSE,
        temporaryFoolVisibilityMotion: () -> Boolean = { false },
        hiddenFoolVisibility: (@Composable () -> Transition<Boolean>)? = null,
        preserveOutgoingHiddenBodies: () -> Boolean = { false },
        foolExpanded: (Long) -> Boolean = { false },
        onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
        onLongPress: (MessageEntity) -> Unit = {},
        dccTransfer: (MessageEntity) -> StateFlow<DccTransferEntity?> = { MutableStateFlow(null) },
        onRejectDccTransfer: (Long) -> Unit = {},
        onToggleFool: (Long) -> Unit = {},
        quietFeed: Boolean = false,
        externalTranslations: () -> Map<Long, InlineTranslation> = { emptyMap() },
        onDismissExternalTranslation: (Long) -> Unit = {},
        layoutDensity: () -> LayoutDensity = { LayoutDensity.COMFORTABLE },
        fontScale: Float? = null,
        avatarStyle: () -> AvatarStyle = { AvatarStyle.IRC_SPRITE },
        bubbleCornerStyle: () -> BubbleCornerStyle = { BubbleCornerStyle.ROUNDED },
        messageSpacing: () -> MessageSpacing = { MessageSpacing.DEFAULT },
        paneWidth: Dp? = null,
        onReply: (MessageEntity) -> Unit = {},
        layoutDirection: () -> LayoutDirection = { LayoutDirection.Ltr },
        systemGestureInsetPx: Int? = null,
        listState: LazyListState? = null,
        liveEntryIds: () -> Set<Long> = { emptySet() },
        timelineSeams: () -> TimelineSeamState = { TimelineSeamState() },
        showImages: () -> Boolean = { false },
        showLinkPreviews: Boolean = false,
        loadPreview: suspend (String, Long?) -> LinkPreview? = { _, _ -> null },
        cachedPreview: (String, Long?) -> io.github.trevarj.motd.data.repo.CachedLinkPreview? = { _, _ -> null },
        selectionMessageId: Long? = null,
        onOpenPalette: (PaletteDocument, String) -> Unit = { _, _ -> },
        automaticRemoteMedia: Boolean = false,
        loadAudioMetadata: suspend (String, Long?) -> AudioMetadata? = { _, _ -> null },
        outgoingFlight: () -> OutgoingFlight? = { null },
        flightProgress: () -> Float = { 1f },
        onFlightRowPositioned: (Long, Rect) -> Unit = { _, _ -> },
        onRetry: (MessageEntity) -> Unit = {},
        onDelete: (MessageEntity) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDickordLabsEnabled provides dickordEnabled,
                LocalAutomaticRemoteMedia provides automaticRemoteMedia,
                LocalDensity provides (fontScale?.let { Density(LocalDensity.current.density, it) } ?: LocalDensity.current),
                LocalLayoutDirection provides layoutDirection(),
            ) {
                if (systemGestureInsetPx != null) {
                    val view = LocalView.current
                    LaunchedEffect(view, systemGestureInsetPx) {
                        ViewCompat.dispatchApplyWindowInsets(
                            view,
                            WindowInsetsCompat
                                .Builder()
                                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(systemGestureInsetPx, 0, systemGestureInsetPx, 0))
                                .build(),
                        )
                    }
                    val insets = WindowInsets.systemGestures
                    val left = insets.getLeft(LocalDensity.current, LocalLayoutDirection.current)
                    val right = insets.getRight(LocalDensity.current, LocalLayoutDirection.current)
                    SideEffect { observedSystemGestureInsets = left to right }
                }
                MotdTheme(
                    dynamicColor = false,
                    layoutDensity = layoutDensity(),
                    avatarStyle = avatarStyle(),
                    bubbleCornerStyle = bubbleCornerStyle(),
                    messageSpacing = messageSpacing(),
                ) {
                    val collected = pages.collectAsLazyPagingItems(context = Dispatchers.Unconfined)
                    val timelineState = listState ?: rememberLazyListState()
                    MessageList(
                        modifier = paneWidth?.let { Modifier.width(it) } ?: Modifier,
                        items = collected,
                        listState = timelineState,
                        selectionMessageId = selectionMessageId,
                        liveEntryIds = liveEntryIds(),
                        networkId = 1,
                        bufferId = 1,
                        ebooksQuietFeed = quietFeed,
                        onToggleFool = onToggleFool,
                        conversationName = conversationName,
                        hideInlineAudioLinks = hideInlineAudioLinks,
                        directMessage = directMessage,
                        readMarkerTime = marker,
                        timelineSeams = timelineSeams(),
                        onLongPress = onLongPress,
                        externalTranslations = externalTranslations(),
                        onDismissExternalTranslation = onDismissExternalTranslation,
                        onReply = onReply,
                        onReact = { _, _ -> },
                        onOpenMedia = { _, _ -> },
                        onRetry = onRetry,
                        onDelete = onDelete,
                        loadPreview = loadPreview,
                        cachedPreview = cachedPreview,
                        loadAudioMetadata = loadAudioMetadata,
                        outgoingFlight = outgoingFlight(),
                        flightProgress = flightProgress,
                        onFlightRowPositioned = onFlightRowPositioned,
                        richContentReady = richContentReady,
                        showImages = showImages(),
                        showLinkPreviews = showLinkPreviews,
                        onOpenLink = {},
                        onOpenPalette = onOpenPalette,
                        onAudioToggle = onAudioToggle,
                        fools = fools,
                        foolsMode = foolsMode,
                        temporaryFoolVisibilityMotion = temporaryFoolVisibilityMotion(),
                        hiddenFoolVisibility = hiddenFoolVisibility?.invoke(),
                        preserveOutgoingHiddenBodies = preserveOutgoingHiddenBodies(),
                        foolExpanded = foolExpanded,
                        onAcceptInvite = onAcceptInvite,
                        replyPreview = replyPreview,
                        onReplyPreviewClick = onReplyPreviewClick,
                        onDismissInvite = onDismissInvite,
                        dccTransfer = dccTransfer,
                        onRejectDccTransfer = onRejectDccTransfer,
                    )
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("chat_timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertMessageOnce(id: Long) {
        val tag = messageTag(id)
        scrollTo(tag)
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("chat_timeline", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertTranslationContained(
        eventId: Long,
        hostId: Long = eventId,
        paintedHostTag: String = "message_translation_host_$hostId",
    ) {
        val resultTag = "message_translation_$eventId"
        val result = compose.onNodeWithTag(resultTag, useUnmergedTree = true)
        val paintedHost = hasTestTag(paintedHostTag) and hasAnyDescendant(hasTestTag(resultTag))
        compose.onAllNodes(paintedHost, useUnmergedTree = true).assertCountEquals(1)
        compose
            .onNode(
                hasTestTag(resultTag) and hasAnyAncestor(paintedHost),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        val host = compose.onNode(paintedHost, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inset = result.fetchSemanticsNode().boundsInRoot
        assertTrue("result must stay horizontally inside its painted source host", inset.left >= host.left && inset.right <= host.right)
        assertTrue("result must stay vertically inside its painted source host", inset.top >= host.top && inset.bottom <= host.bottom)
    }

    private fun assertFullTranslationText(output: String) {
        val text = compose.onNodeWithText(output, useUnmergedTree = true).assertTextEquals(output)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val layout = layouts.single()
        assertTrue("regression text must exercise more than ReplyMiniBubble's two lines", layout.lineCount > 2)
        assertFalse("returned text must not visually overflow", layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) assertFalse("line $line must not be ellipsized", layout.isLineEllipsized(line))
        val lastLine = layout.lineCount - 1
        assertEquals(output.length, layout.getLineEnd(lastLine))
        assertTrue(layout.getLineBottom(lastLine) <= layout.size.height)
        assertTrue(layout.getBoundingBox(output.lastIndex).bottom <= layout.size.height)
        assertTrue("the last rendered line must lie inside measured text bounds", layout.size.height <= text.fetchSemanticsNode().boundsInRoot.height + 1f)
    }

    private fun assertNoSourceActions(text: androidx.compose.ui.semantics.SemanticsNode) {
        generateSequence(text) { it.parent }.forEach { node ->
            assertFalse("source Reply must not wrap returned text", node.config.contains(SemanticsActions.CustomActions))
            assertFalse("source hold must not wrap returned text", node.config.contains(SemanticsActions.OnLongClick))
        }
    }

    private fun messageTag(id: Long) = "chat_message_m$id"

    private fun message(
        id: Long,
        time: Long,
        kind: MessageKind,
        text: String,
        self: Boolean = false,
        failed: Boolean = false,
        pending: Boolean = false,
        payload: String? = null,
        inviteState: InviteState? = null,
    ) = MessageEntity(
        id = id,
        bufferId = 1,
        msgid = "m$id",
        serverTime = time,
        sender = if (self) "me" else "alice",
        kind = kind,
        text = text,
        isSelf = self,
        pendingLabel = if (pending) "pending-$id" else null,
        failed = failed,
        dedupKey = "dedup-$id",
        eventPayload = payload,
        inviteState = inviteState,
        timelineOrder = id,
    )
}
