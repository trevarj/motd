package io.github.trevarj.motd.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ai.text.TextTermination
import io.github.trevarj.motd.ai.text.TextTransformResult
import io.github.trevarj.motd.data.db.ActivityReaction
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.KeywordMatch
import io.github.trevarj.motd.data.prefs.NickMentionStyle
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.visibility.ActivityFilter
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.ui.ai.AiTextSheet
import io.github.trevarj.motd.ui.ai.AiTextSource
import io.github.trevarj.motd.ui.ai.AiTextUiState
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.TimestampConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Presentation contract of the merged stream, driven off a static [PagingData] rather than the
 * Hilt-owning screen. In `testDebug` because [createComposeRule] needs the debug-only
 * `ComponentActivity` — see `EmptyStateLayoutTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobalFeedScreenTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun feedUsesNicknameStyleWithoutInventingReceiptProvenance() {
        assertReceiptTriggerRendering(activityFilter = null)
    }

    @Test
    fun mentionsUsesNicknameStyleWithoutInventingReceiptProvenance() {
        assertReceiptTriggerRendering(activityFilter = ActivityFilter.MENTIONS)
    }

    private fun assertReceiptTriggerRendering(activityFilter: ActivityFilter?) {
        val receipt =
            row(1, text = "Receipt Nick{ then Nick[!").let {
                it.copy(
                    message = it.message.copy(hasMention = true, highlightNick = "Nick[", highlightCaseMapping = "ascii"),
                    caseMapping = "rfc1459",
                )
            }
        val historical =
            row(2, text = "Historical Nick[!").let {
                it.copy(message = it.message.copy(hasMention = true))
            }
        val replyOnly =
            row(3, text = "Reply Nick[!").let {
                it.copy(message = it.message.copy(hasMention = true, replyToMsgid = "self-message"))
            }
        val stream = flowOf(PagingData.from(listOf(receipt, historical, replyOnly)))
        var style by mutableStateOf(NickMentionStyle.BOLD)
        setContent(stream, activityFilter = activityFilter, nickMentionStyle = { style })
        awaitText(receipt.message.text)

        fun assertSpans(
            hit: SearchHit,
            expected: List<Pair<Int, Int>>,
        ) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(hit.message.text, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val rendered = layouts.single().layoutInput.text
            assertEquals(hit.message.text, rendered.text)
            val triggers = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
            assertEquals(expected, triggers.map { it.start to it.end })
            triggers.forEach {
                assertEquals(FontWeight.Bold, it.item.fontWeight)
                assertEquals("Nick[", rendered.text.substring(it.start, it.end))
            }
        }

        val start = receipt.message.text.indexOf("Nick[")
        assertSpans(receipt, listOf(start to start + "Nick[".length))
        assertSpans(historical, emptyList())
        assertSpans(replyOnly, emptyList())
        compose.runOnIdle { style = NickMentionStyle.NONE }
        assertSpans(receipt, emptyList())
        assertSpans(historical, emptyList())
        assertSpans(replyOnly, emptyList())
        compose.runOnIdle { style = NickMentionStyle.BOLD }
        assertSpans(receipt, listOf(start to start + "Nick[".length))
    }

    @Test
    fun optedInBlurredMentionRevealsOnlyBeforeOpeningOrigin() {
        val original = row(42, sender = "fool", text = "Secret mention", kind = MessageKind.ACTION)
        val hit = original.copy(message = original.message.copy(normalizedActor = "fool", hasMention = true))
        val stream = flowOf(PagingData.from(listOf(hit)))
        setContent(stream, activityFilter = ActivityFilter.MENTIONS, visibilitySpec = {
            MessageVisibilitySpec(fools = setOf("fool"), foolsMode = FoolsMode.BLUR, foolPreferences = mapOf("fool" to FoolPreferences(notifyMentions = true)))
        })
        compose.onNodeWithText("Secret mention", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat_action_row", useUnmergedTree = true).assertContentDescriptionEquals("* fool")
        compose
            .onNodeWithContentDescription(
                org.robolectric.RuntimeEnvironment
                    .getApplication()
                    .getString(io.github.trevarj.motd.R.string.chat_fool_reveal),
                substring = true,
                useUnmergedTree = true,
            ).assertIsDisplayed()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(null, opened) }
        compose.onNodeWithText("Secret mention", substring = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Triple(hit.message.bufferId, 42L, hit.message.serverTime), opened) }
    }

    private var opened: Triple<Long, Long, Long>? = null
    private var navigationListState: LazyListState? = null

    private fun row(
        id: Long,
        bufferId: Long = 7L,
        buffer: String = "#kotlin",
        network: String = "Libera",
        sender: String = "nick",
        text: String = "hello there",
        kind: MessageKind = MessageKind.PRIVMSG,
        serverTime: Long = 1_700_000_000_000L,
        isSelf: Boolean = false,
        failed: Boolean = false,
        ircFormattedText: String? = null,
    ) = SearchHit(
        message =
            MessageEntity(
                id = id,
                bufferId = bufferId,
                serverTime = serverTime,
                sender = sender,
                kind = kind,
                text = text,
                isSelf = isSelf,
                failed = failed,
                ircFormattedText = ircFormattedText,
                dedupKey = "dedup-$id",
            ),
        bufferDisplayName = buffer,
        networkName = network,
        bufferType = BufferType.CHANNEL,
        networkId = 1L,
    )

    private fun SearchHit.reacted(
        id: Long,
        sender: String,
        at: Long,
        emoji: String = "👍",
        account: String? = null,
    ): SearchHit =
        copy(
            activityReaction =
                ActivityReaction(
                    id = id,
                    at = at,
                    sender = sender,
                    account = account,
                    emoji = emoji,
                ),
        )

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp")
    fun feedAndActivityHoldsTranslateWithoutChangingOriginClicks() {
        val hit = row(42, text = "The report is ready.")
        val stream = flowOf(PagingData.from(listOf(hit)))
        var filter by mutableStateOf<ActivityFilter?>(null)
        var ai by mutableStateOf<AiTextUiState>(AiTextUiState.Closed)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                GlobalFeedContent(
                    stream.collectAsLazyPagingItems(context = Dispatchers.Unconfined),
                    false,
                    onOpenMessage = { room, event, time -> opened = Triple(room, event, time) },
                    activityFilter = filter,
                    onTranslateMessage = { message ->
                        ai = AiTextUiState.Choosing(AiTextSource.StoredMessage(message.bufferId, message.id, message.msgid, message.text))
                    },
                )
                AiTextSheet(ai, emptyList(), null, onGenerate = {
                    val source = (ai as AiTextUiState.Choosing).source as AiTextSource.StoredMessage
                    ai = AiTextUiState.Result(1, source, source.text, TextTransformResult("Le rapport est prêt.", TextTermination.EOG), 1)
                }, onTargetSelected = {}, onDismiss = { ai = AiTextUiState.Closed }, onOpenSetup = {}, onManageStyles = {})
            }
        }
        for (scope in listOf(null, ActivityFilter.ALL, ActivityFilter.MENTIONS, ActivityFilter.KEYWORDS)) {
            compose.runOnIdle { filter = scope }
            val text = compose.onNodeWithText(hit.message.text)
            text.performClick()
            compose.runOnIdle { assertEquals(Triple(hit.message.bufferId, 42L, hit.message.serverTime), opened) }
            text.performSemanticsAction(SemanticsActions.OnLongClick)
            compose.onNodeWithTag("ai_text_source").assertIsDisplayed()
            compose.runOnIdle { assertEquals(42L, ((ai as AiTextUiState.Choosing).source as AiTextSource.StoredMessage).eventId) }
            compose.onNodeWithTag("ai_text_translate").performClick()
            compose.onNodeWithText("Le rapport est prêt.").assertIsDisplayed()
            compose.onNodeWithTag("ai_text_apply").assertDoesNotExist()
            compose.onNodeWithTag("ai_text_close").performClick()
        }
    }

    /** Source states for a stream that has finished refreshing, however that refresh ended. */
    private fun settled(refresh: LoadState) =
        LoadStates(
            refresh = refresh,
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

    /** The stream is built by the caller, so recomposition cannot restart the collection. */
    private fun setContent(
        stream: Flow<PagingData<SearchHit>>,
        showNetwork: () -> Boolean = { false },
        dickordEnabled: Boolean = false,
        activityFilter: ActivityFilter? = null,
        showActivityNavigation: Boolean = false,
        onActivityViewportAtNewest: ((Boolean) -> Unit)? = null,
        visibilitySpec: () -> MessageVisibilitySpec = { MessageVisibilitySpec() },
        nickMentionStyle: () -> NickMentionStyle = { NickMentionStyle.BOLD },
    ) {
        compose.setContent {
            // Motion off: the caption waits on a Lottie clock a stub composition never advances.
            CompositionLocalProvider(
                LocalLottieMotionEnabled provides false,
                LocalDickordLabsEnabled provides dickordEnabled,
            ) {
                MotdTheme(dynamicColor = false, nickMentionStyle = nickMentionStyle()) {
                    val listState = rememberLazyListState()
                    navigationListState = listState
                    Box(Modifier.fillMaxSize()) {
                        val rows = stream.collectAsLazyPagingItems(context = Dispatchers.Unconfined)
                        GlobalFeedContent(
                            rows = rows,
                            showNetwork = showNetwork(),
                            onOpenMessage = { bufferId, eventId, serverTime ->
                                opened = Triple(bufferId, eventId, serverTime)
                            },
                            activityFilter = activityFilter,
                            listState = listState,
                            visibilitySpec = visibilitySpec(),
                        )
                        onActivityViewportAtNewest?.let { callback ->
                            ReportActivityViewportAtNewest(
                                listState = listState,
                                onAtNewestChanged = callback,
                            )
                        }
                        if (showActivityNavigation) {
                            ActivityNavigationFab(
                                listState = listState,
                                itemCount = rows.itemCount,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                    }
                }
            }
        }
    }

    /** The first paging emission lands after composition, so assertions wait on the branch. */
    private fun awaitText(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitTag(tag: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun aLineShowsItsConversationTagAndFormattedBodyAndReportsTheTappedRow() {
        setContent(
            flowOf(
                PagingData.from(listOf(row(id = 11L, text = "hello there"))),
            ),
        )

        awaitTag("feed_row_11")
        compose.onNodeWithTag("feed_list").assertIsDisplayed()
        compose.onNodeWithText("#kotlin").assertIsDisplayed()
        compose.onNodeWithText("hello there").assertIsDisplayed()
        compose.onNodeWithText("nick").assertIsDisplayed()

        compose.onNodeWithText("hello there").performClick()

        compose.runOnIdle {
            // Canonical row id for identity, serverTime only as the scroll anchor.
            assertEquals(Triple(7L, 11L, 1_700_000_000_000L), opened)
        }
    }

    @Test
    fun keywordModeOpensTheExactStoredMessage() {
        val hit =
            row(id = 21L, text = "daily build").let {
                it.copy(message = it.message.copy(hasMention = true, keywordMatch = KeywordMatch.SILENT))
            }
        setContent(flowOf(PagingData.from(listOf(hit))), activityFilter = ActivityFilter.KEYWORDS)

        awaitTag("activity_row_21")
        compose.onNodeWithTag("activity_list").assertIsDisplayed()
        compose.onNodeWithText("daily build").performClick()

        compose.runOnIdle { assertEquals(Triple(7L, 21L, 1_700_000_000_000L), opened) }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp")
    fun reactionRowsExposeTheirOwnActorEmojiAndTimeButOpenTheExactParent() {
        val parent = row(id = 98L, bufferId = 12L, sender = "me", isSelf = true, ircFormattedText = "\u0002My original message\u0002")
        val at = parent.message.serverTime + 172_800_000L
        val newer = parent.reacted(id = 21L, sender = "Alice", at = at)
        val earlier = parent.reacted(id = 22L, sender = "Bob", at = at - 60_000L, emoji = "🎉")
        // A message ID may equal a reaction ID, and both reactions point to the same parent.
        val mention = row(id = 21L, text = "ordinary activity")
        val stream = flowOf(PagingData.from(listOf(newer, earlier, mention)))
        val pattern = "'Very long custom time label that wraps independently of the conversation name:' yyyy-MM-dd HH:mm"
        val time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
        compose.setContent {
            MotdTheme(
                dynamicColor = false,
                uiFontScalePercent = 150,
                timestampConfig = TimestampConfig(format = TimeFormat.CUSTOM, customPattern = pattern),
            ) {
                GlobalFeedContent(
                    rows = stream.collectAsLazyPagingItems(context = Dispatchers.Unconfined),
                    showNetwork = false,
                    onOpenMessage = { bufferId, eventId, serverTime -> opened = Triple(bufferId, eventId, serverTime) },
                    activityFilter = ActivityFilter.ALL,
                    onTranslateMessage = {},
                )
            }
        }

        awaitTag("activity_row_21")
        val resources = RuntimeEnvironment.getApplication().resources
        val reaction = compose.onNodeWithTag("activity_reaction_21")
        reaction
            .assertIsDisplayed()
            .assertTextContains(resources.getString(R.string.activity_reaction_summary, "Alice", "👍"))
            .assertTextContains("My original message")
            .assertTextContains(time)
        val inReaction = hasAnyAncestor(hasTestTag("activity_reaction_21"))
        for (label in listOf("#kotlin", time)) {
            val text = compose.onNode(hasText(label) and inReaction, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(layouts))
            }
            val layout = layouts.single()
            val size = text.fetchSemanticsNode().size
            // Native wrap-content layout can flag overflow even when all lines fit the rendered node.
            assertFalse("$label lost lines", layout.multiParagraph.didExceedMaxLines)
            assertEquals("$label lost its ending", label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            for (line in 0 until layout.lineCount) {
                assertFalse("$label line $line was ellipsized", layout.isLineEllipsized(line))
                assertTrue(
                    "$label line $line overflowed $size",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= size.width + 1f &&
                        layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= size.height + 1f,
                )
            }
            if (label == time) assertTrue(layout.lineCount > 1)
        }
        compose
            .onNodeWithTag("activity_reaction_22")
            .assertIsDisplayed()
            .assertTextContains(resources.getString(R.string.activity_reaction_summary, "Bob", "🎉"))
        val action = reaction.fetchSemanticsNode().config
        assertEquals(resources.getString(R.string.activity_open_reacted_message), action[SemanticsActions.OnClick].label)
        assertFalse(action.contains(SemanticsActions.OnLongClick))
        val ordinary = compose.onNodeWithText("ordinary activity").fetchSemanticsNode().config
        assertEquals(resources.getString(R.string.activity_open_message), ordinary[SemanticsActions.OnClick].label)
        assertTrue(ordinary.contains(SemanticsActions.OnLongClick))

        reaction.performClick()
        compose.runOnIdle { assertEquals(Triple(12L, 98L, parent.message.serverTime), opened) }
    }

    @Test
    fun reactionVisibilityFollowsTheReactorAndNeverTheParent() {
        val at = 1_700_180_000_000L
        val collapsed = row(41L, isSelf = true, text = "Collapsed preview").reacted(1L, "Alice", at, account = "ignored-account")
        val hidden = row(42L, isSelf = true, text = "Hidden preview").reacted(2L, "Masked", at)
        val visible = row(43L, isSelf = true, sender = "Masked", text = "Visible preview").reacted(3L, "Bob", at)
        val blurred = row(44L, buffer = "#blurred-room", isSelf = true, text = "Blurred preview").reacted(4L, "Blurred", at, emoji = "🚀")
        val spec =
            MessageVisibilitySpec(
                fools = setOf("ignored-account", "MASKED", "blurred"),
                foolPreferences =
                    mapOf(
                        "ignored-account" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true),
                        "MASKED" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                        "blurred" to FoolPreferences(FoolsMode.BLUR, notifyMentions = true),
                    ),
            )
        setContent(
            flowOf(PagingData.from(listOf(collapsed, hidden, visible, blurred))),
            activityFilter = ActivityFilter.REACTIONS,
            visibilitySpec = { spec },
        )

        awaitTag("activity_reaction_3")
        val resources = RuntimeEnvironment.getApplication().resources
        val collapsedRow = compose.onNodeWithTag("activity_reaction_1")
        collapsedRow.assertTextContains(resources.getString(R.string.chat_fool_hidden, "Alice"))
        compose.onNodeWithText("Collapsed preview", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("activity_reaction_2").assertDoesNotExist()
        compose.onNodeWithTag("activity_reaction_3").assertTextContains("Visible preview")
        val blurredRow = compose.onNodeWithTag("activity_reaction_4")
        blurredRow
            .assertTextContains("#blurred-room")
            .assertTextContains(resources.getString(R.string.activity_reaction_concealed_summary, "Blurred"))
            .assertTextContains(resources.getString(R.string.activity_reaction_preview_hidden))
        compose.onNodeWithText("Blurred preview", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("🚀", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose
            .onAllNodes(hasAnyAncestor(hasTestTag("activity_reaction_4")) and hasClickAction(), useUnmergedTree = true)
            .assertCountEquals(0)
        assertEquals(resources.getString(R.string.activity_reaction_reveal), blurredRow.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        blurredRow.performClick()
        compose.runOnIdle { assertEquals(null, opened) }
        blurredRow
            .assertTextContains("Blurred preview")
            .assertTextContains(resources.getString(R.string.activity_reaction_summary, "Blurred", "🚀"))
        assertEquals(resources.getString(R.string.activity_open_reacted_message), blurredRow.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        blurredRow.performClick()
        compose.runOnIdle { assertEquals(Triple(7L, 44L, blurred.message.serverTime), opened) }
        collapsedRow.performClick()
        compose.runOnIdle { assertEquals(Triple(7L, 41L, collapsed.message.serverTime), opened) }
    }

    @Test
    fun reactionVisibilityReevaluatesTheSenderWhenTheCurrentNetworkCasemapChanges() {
        val hit = row(42L, isSelf = true).reacted(1L, "[Alice]", at = 1_700_180_000_000L).copy(caseMapping = "ascii")
        val pages = MutableStateFlow(PagingData.from(listOf(hit)))
        val spec = MessageVisibilitySpec(fools = setOf("{ALICE}"), foolsMode = FoolsMode.HIDE)
        setContent(pages, activityFilter = ActivityFilter.REACTIONS, visibilitySpec = { spec })

        awaitTag("activity_reaction_1")
        compose.runOnIdle { pages.value = PagingData.from(listOf(hit.copy(caseMapping = "rfc1459"))) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("activity_reaction_1").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { pages.value = PagingData.from(listOf(hit)) }
        awaitTag("activity_reaction_1")
    }

    @Test
    fun activityLabelsEveryRowEvenWhenTwoEventsShareAChat() {
        setContent(
            flowOf(PagingData.from(listOf(row(id = 22L, text = "later"), row(id = 21L, text = "earlier")))),
            activityFilter = ActivityFilter.ALL,
        )

        awaitTag("activity_row_21")
        assertEquals(2, compose.onAllNodesWithText("#kotlin").fetchSemanticsNodes().size)
    }

    @Test
    fun collapsedMentionsNeverExposeTheBodyAndOpenTheExactMessageWhileDimmedRemainReadable() {
        var spec by mutableStateOf(
            MessageVisibilitySpec(
                fools = setOf("a", "b", "c"),
                foolPreferences =
                    mapOf(
                        "a" to FoolPreferences(FoolsMode.DIMMED, notifyMentions = true),
                        "b" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true),
                        "c" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                    ),
            ),
        )
        val rows =
            listOf("a", "b", "c").mapIndexed { index, nick ->
                row(id = 31L + index, sender = nick, text = "$nick private body").let {
                    it.copy(message = it.message.copy(normalizedActor = nick, hasMention = true))
                }
            }
        setContent(flowOf(PagingData.from(rows)), activityFilter = ActivityFilter.MENTIONS, visibilitySpec = { spec })
        awaitTag("activity_row_31")
        compose.onNodeWithText("a private body").assertIsDisplayed()
        compose.onNodeWithText("b private body", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("c private body", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("activity_row_33", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("b · hidden").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Triple(7L, 32L, 1_700_000_000_000L), opened) }
        compose.runOnIdle {
            spec = spec.copy(foolPreferences = spec.foolPreferences + ("b" to FoolPreferences(FoolsMode.COLLAPSE)))
        }
        compose.onNodeWithText("b · hidden").assertDoesNotExist()
        compose.onNodeWithText("b private body", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun activityNavigationMovesToAdjacentRowsAndHoldingUpReturnsToNewest() {
        setContent(
            flowOf(PagingData.from((1L..80L).map { row(id = it, text = "mention $it") })),
            activityFilter = ActivityFilter.ALL,
            showActivityNavigation = true,
        )

        awaitTag("activity_navigation_down")
        compose.onNodeWithTag("activity_navigation_down").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { (navigationListState?.firstVisibleItemIndex ?: 0) > 0 }
        val afterDown = checkNotNull(navigationListState).firstVisibleItemIndex

        compose.onNodeWithTag("activity_navigation_up").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            navigationListState?.firstVisibleItemIndex == afterDown - 1
        }

        compose.onNodeWithTag("activity_navigation_down").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { (navigationListState?.firstVisibleItemIndex ?: 0) > 0 }
        compose.onNodeWithTag("activity_navigation_up").performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 10_000) { navigationListState?.firstVisibleItemIndex == 0 }
    }

    @Test
    fun activityViewportReportsNewestOnlyAfterRowsArePresentedAndClearsOnScroll() {
        val viewportStates = mutableListOf<Boolean>()
        setContent(
            flowOf(PagingData.from((1L..80L).map { row(id = it, text = "mention $it") })),
            activityFilter = ActivityFilter.ALL,
            showActivityNavigation = true,
            onActivityViewportAtNewest = viewportStates::add,
        )

        awaitTag("activity_row_1")
        compose.waitUntil(timeoutMillis = 10_000) { viewportStates.lastOrNull() == true }
        compose.onNodeWithTag("activity_navigation_down").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { viewportStates.lastOrNull() == false }
    }

    @Test
    fun activityRefreshPinsTheFirstItemWhenReadingAtNewest() {
        val original = (1L..80L).map { row(id = it, text = "mention $it") }
        val stream = MutableStateFlow(PagingData.from(original))
        val viewportStates = mutableListOf<Boolean>()
        setContent(stream, activityFilter = ActivityFilter.ALL, onActivityViewportAtNewest = viewportStates::add)
        awaitTag("activity_row_1")
        compose.waitUntil(timeoutMillis = 10_000) { viewportStates.lastOrNull() == true }

        compose.runOnIdle {
            stream.value = PagingData.from(listOf(row(100, text = "new activity")) + original)
        }

        awaitTag("activity_row_100")
        compose.onNodeWithTag("activity_row_100").assertIsDisplayed()
        compose.runOnIdle {
            val state = checkNotNull(navigationListState)
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals(listOf(true), viewportStates)
        }
    }

    @Test
    fun activityRefreshKeepsTheVisibleMessageAndOffsetAcrossLiveAndHistoryInsertions() {
        val original = (1L..80L).map { row(id = it, text = "mention $it") }
        val stream = MutableStateFlow(PagingData.from(original))
        setContent(stream, activityFilter = ActivityFilter.ALL)
        awaitTag("activity_row_1")
        compose.onNodeWithTag("activity_list").performScrollToIndex(30)
        compose.waitUntil(timeoutMillis = 10_000) { navigationListState?.firstVisibleItemIndex == 30 }
        var anchor: Any? = null
        var offset = 0
        compose.runOnIdle {
            val state = checkNotNull(navigationListState)
            anchor =
                state.layoutInfo.visibleItemsInfo
                    .first()
                    .key
            offset = state.firstVisibleItemScrollOffset
            stream.value =
                PagingData.from(
                    listOf(row(100, text = "live mention")) +
                        original.take(25) +
                        row(101, text = "history mention") +
                        original.drop(25) +
                        row(102, text = "older history mention"),
                )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            val state = checkNotNull(navigationListState)
            assertEquals(83, state.layoutInfo.totalItemsCount)
            assertEquals(
                anchor,
                state.layoutInfo.visibleItemsInfo
                    .first()
                    .key,
            )
            assertEquals(offset, state.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithTag("activity_row_31").assertIsDisplayed()
    }

    @Test
    fun dickordModeCleansConversationAndSenderLabels() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(row(id = 12L, buffer = "#discord.me.chat.alice", sender = "Alice/discord")),
                ),
            ),
            dickordEnabled = true,
        )

        awaitTag("feed_row_12")
        compose.onNodeWithText("#me.chat.alice").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("#discord.me.chat.alice").assertDoesNotExist()
        compose.onNodeWithText("Alice/discord").assertDoesNotExist()
    }

    @Test
    fun dickordLabelsStayRawWhileModeIsDisabled() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(row(id = 13L, buffer = "#discord.me.chat.alice", sender = "Alice/discord")),
                ),
            ),
        )

        awaitTag("feed_row_13")
        compose.onNodeWithText("#discord.me.chat.alice").assertIsDisplayed()
        compose.onNodeWithText("Alice/discord").assertIsDisplayed()
        compose.onNodeWithText("#me.chat.alice").assertDoesNotExist()
        compose.onNodeWithText("Alice").assertDoesNotExist()
    }

    @Test
    fun aRunFromOneSenderShowsItsNickOnceAndTagsOnlyTheConversationChange() {
        // Newest first: two lines from one nick, then another conversation.
        setContent(
            flowOf(
                PagingData.from(
                    listOf(
                        row(id = 62L, serverTime = 1_700_000_060_000L),
                        row(id = 61L, serverTime = 1_700_000_000_000L),
                        row(id = 60L, bufferId = 9L, buffer = "#nix", sender = "ana", serverTime = 1_699_999_900_000L),
                    ),
                ),
            ),
        )

        awaitTag("feed_row_60")
        // One group: the nick heads the opening line only; the tag marks the buffer change.
        listOf(62L, 61L, 60L).forEach { compose.onNodeWithTag("feed_row_$it").assertIsDisplayed() }
        compose.onNodeWithText("nick").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("nick").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("#kotlin").fetchSemanticsNodes().size)
        compose.onNodeWithText("ana").assertIsDisplayed()
    }

    @Test
    fun theNetworkLineAppearsOnlyWhileMoreThanOneNetworkExists() {
        var showNetwork by mutableStateOf(false)
        setContent(flowOf(PagingData.from(listOf(row(id = 21L)))), showNetwork = { showNetwork })

        awaitTag("feed_row_21")
        compose.onNodeWithText("#kotlin · Libera").assertDoesNotExist()

        showNetwork = true
        compose.waitForIdle()

        compose.onNodeWithText("#kotlin · Libera").assertIsDisplayed()
    }

    @Test
    fun aSettledEmptyStreamShowsTheEmptyState() {
        // Explicit NotLoading: a plain from(emptyList()) leaves the refresh reading as loading.
        setContent(flowOf(PagingData.from(emptyList(), settled(LoadState.NotLoading(endOfPaginationReached = true)))))

        awaitTag("empty_state_ghost_rows")
        compose.onNodeWithTag("feed_list").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun activityChipsExposeSelectionAndChangeTheScopedEmptyMessage() {
        var filter by mutableStateOf(ActivityFilter.ALL)
        val empty = flowOf(PagingData.from<SearchHit>(emptyList(), settled(LoadState.NotLoading(endOfPaginationReached = true))))
        compose.setContent {
            CompositionLocalProvider(LocalLottieMotionEnabled provides false) {
                MotdTheme(dynamicColor = false) {
                    Column {
                        ActivityFilterChips(filter) { filter = it }
                        GlobalFeedContent(
                            rows = empty.collectAsLazyPagingItems(context = Dispatchers.Unconfined),
                            showNetwork = false,
                            onOpenMessage = { _, _, _ -> },
                            activityFilter = filter,
                        )
                    }
                }
            }
        }

        val resources = RuntimeEnvironment.getApplication().resources
        awaitText(resources.getString(R.string.activity_empty))
        val all = compose.onNodeWithTag("activity_filter_all")
        val mentions = compose.onNodeWithTag("activity_filter_mentions")
        val keywords = compose.onNodeWithTag("activity_filter_keywords")
        val reactions = compose.onNodeWithTag("activity_filter_reactions")
        all.assertIsSelected()
        compose.onNodeWithText(resources.getString(R.string.activity_all_empty_message)).assertIsDisplayed()
        mentions.assertIsNotSelected().performClick()
        all.assertIsNotSelected()
        mentions.assertIsSelected()
        compose.onNodeWithText(resources.getString(R.string.activity_mentions_empty)).assertIsDisplayed()
        keywords.assertIsNotSelected().performClick()
        mentions.assertIsNotSelected()
        keywords.assertIsSelected()
        compose.onNodeWithText(resources.getString(R.string.activity_keywords_empty)).assertIsDisplayed()
        reactions
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(resources.getString(R.string.activity_filter_reactions))
            .assertIsNotSelected()
            .performClick()
        keywords.assertIsNotSelected()
        reactions.assertIsSelected()
        compose.onNodeWithText(resources.getString(R.string.activity_reactions_empty)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.activity_reactions_empty_message)).assertIsDisplayed()
    }

    @Test
    fun switchingActivityFilterClearsPreviousRowsDuringLoadingAndFailure() {
        val allPage = ActivityPage(ActivityFilter.ALL, flowOf(PagingData.from((1L..80L).map { row(it) }))) {}
        // Hold the replacement presenter on its first load until the old rows are checked.
        val keywordPages = MutableStateFlow(PagingData.from<SearchHit>(emptyList(), sourceLoadStates = settled(LoadState.Loading)))
        val keywordsPage = ActivityPage(ActivityFilter.KEYWORDS, keywordPages) {}
        var page by mutableStateOf(allPage)
        compose.setContent {
            CompositionLocalProvider(LocalLottieMotionEnabled provides false) {
                MotdTheme(dynamicColor = false) {
                    ActivityFeed(
                        page = page,
                        onFilterSelected = { if (it == ActivityFilter.KEYWORDS) page = keywordsPage },
                        showNetwork = false,
                        visibilitySpec = MessageVisibilitySpec(),
                        onBack = {},
                        onOpenMessage = { _, _, _ -> },
                    )
                }
            }
        }

        awaitTag("activity_row_1")
        awaitTag("activity_navigation_down")
        val keywords = compose.onNodeWithTag("activity_filter_keywords")
        keywords.performClick()
        keywords.assertIsSelected()
        compose.onNodeWithTag("activity_row_1").assertDoesNotExist()
        compose.onNodeWithTag("activity_navigation_down").assertDoesNotExist()

        compose.runOnIdle {
            keywordPages.value =
                PagingData.from(emptyList(), sourceLoadStates = settled(LoadState.Error(IllegalStateException("Keywords load failed"))))
        }
        val resources = RuntimeEnvironment.getApplication().resources
        awaitText(resources.getString(R.string.activity_error_title))
        compose.onNodeWithTag("activity_row_1").assertDoesNotExist()
        compose.onNodeWithTag("activity_navigation_down").assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.feed_retry)).assertIsEnabled()
        compose.runOnIdle {
            keywordPages.value =
                PagingData.from(
                    listOf(row(100, text = "new keyword")),
                    sourceLoadStates = settled(LoadState.NotLoading(endOfPaginationReached = true)),
                )
        }
        awaitTag("activity_row_100")
        compose.onNodeWithTag("activity_row_100").assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.activity_error_title)).assertDoesNotExist()
        keywords.assertIsSelected()
    }

    @Test
    fun retryingAFailedRefreshAsksThePagerForTheStreamAgain() {
        val loads = AtomicInteger()
        val pager =
            Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                FailFirstPagingSource(loads, listOf(row(id = 31L)))
            }
        setContent(pager.flow)

        awaitText("Retry")
        compose.onNodeWithTag("feed_list").assertDoesNotExist()
        compose.onNodeWithText("Couldn't load the global feed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()

        // Proves the tap reached the pager: the second load is what clears the error state.
        awaitTag("feed_row_31")
        compose.onNodeWithText("Couldn't load the global feed").assertDoesNotExist()
        assertEquals(2, loads.get())
    }

    /**
     * The contentType pool already splits SELF_FAILED out, so the row has to render it: a send that
     * failed must look failed here, not like an ordinary delivered line.
     */
    @Test
    fun aFailedSelfLineShowsTheFailureAffordance() {
        setContent(
            flowOf(
                PagingData.from(listOf(row(id = 51L, sender = "me", isSelf = true, failed = true))),
            ),
        )

        awaitTag("feed_row_51")
        compose.onNodeWithContentDescription("Failed").assertIsDisplayed()
    }

    /** Same rule as the chat timeline: the stored IRC-formatted body wins over the raw text. */
    @Test
    fun aLineRendersItsIrcFormattedBodyRatherThanTheRawText() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(row(id = 52L, text = "\u0002bold\u0002 line", ircFormattedText = "bold line")),
                ),
            ),
        )

        awaitTag("feed_row_52")
        compose.onNodeWithText("bold line").assertIsDisplayed()
    }

    @Test
    fun aFailedRefreshKeepsAlreadyLoadedLinesOnScreen() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(row(id = 41L)),
                    settled(LoadState.Error(IllegalStateException("boom"))),
                ),
            ),
        )

        awaitTag("feed_row_41")
        compose.onNodeWithText("Couldn't load the global feed").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}

/** Fails its first load and serves [page] afterwards, so a retry shows up as a state change. */
private class FailFirstPagingSource(
    private val loads: AtomicInteger,
    private val page: List<SearchHit>,
) : PagingSource<Int, SearchHit>() {
    override fun getRefreshKey(state: PagingState<Int, SearchHit>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchHit> =
        if (loads.incrementAndGet() == 1) {
            LoadResult.Error(IllegalStateException("boom"))
        } else {
            LoadResult.Page(data = page, prevKey = null, nextKey = null)
        }
}
