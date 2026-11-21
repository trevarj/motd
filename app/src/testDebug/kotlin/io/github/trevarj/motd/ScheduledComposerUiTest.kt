package io.github.trevarj.motd

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ScheduledMessageState
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ChatViewModel
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import io.github.trevarj.motd.ui.chat.ComposerDraftState
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.ScheduledComposerActions
import io.github.trevarj.motd.ui.chat.ScheduledDraft
import io.github.trevarj.motd.ui.chat.ScheduledDraftCapture
import io.github.trevarj.motd.ui.chat.ScheduledMessagesScreen
import io.github.trevarj.motd.ui.chat.ScheduledMessagesViewModel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class ScheduledComposerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapStillSendsWhenSchedulingIsAvailable() {
        var sent = 0
        var scheduled = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("ordinary tap"),
                    onValueChange = {},
                    onSend = { sent++ },
                    enabled = true,
                    onSchedule = { scheduled++ },
                )
            }
        }
        compose.onNodeWithTag("chat_composer_send").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, sent)
            assertEquals(0, scheduled)
        }
    }

    @Test
    fun conversationOverflowSchedulesOnlyEligibleDraftAndKeepsQueueReachable() {
        val context = RuntimeEnvironment.getApplication()
        val room = BufferEntity(id = 42, networkId = 1, name = "#kotlin", displayName = "#Kotlin", type = BufferType.CHANNEL, joined = true)
        val pages = flowOf(PagingData.empty<MessageEntity>())
        var draft by mutableStateOf(ComposerDraftState(hydrated = true))
        val openedQueues = mutableListOf<Long>()
        compose.setContent {
            MotdTheme {
                ChatContent(
                    state = ChatState(buffer = room, connState = IrcClientState.Disconnected),
                    items = pages.collectAsLazyPagingItems(),
                    composerEnabled = true,
                    onBack = {},
                    onOpenChannelInfo = {},
                    onOpenSearch = {},
                    onOpenImage = {},
                    onOpenPalette = { _, _ -> },
                    nickNormalizer = { it.lowercase() },
                    onSubmit = { error("Overflow must not send the draft") },
                    onTyping = {},
                    onSetReply = {},
                    onReact = { _, _ -> },
                    onRetry = {},
                    loadPreview = { _, _ -> null },
                    composerDraft = draft,
                    onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
                    scheduledComposer =
                        ScheduledComposerActions(
                            capture = { ScheduledDraftCapture.Captured(ScheduledDraft(room, it, draft.revision)) },
                            enqueue = { _, _ -> error("Opening the queue must not schedule the draft") },
                            openList = { openedQueues += it },
                        ),
                )
            }
        }
        val overflow = compose.onNodeWithTag("chat_overflow")
        val scheduleItem = compose.onNodeWithTag("scheduled_open_chat")
        val composer = compose.onNodeWithTag("chat_composer_field")
        val openLabel = context.getString(R.string.scheduled_open)
        val listLabel = context.getString(R.string.scheduled_title)
        overflow.performClick()
        compose.onAllNodesWithTag("scheduled_open_chat").assertCountEquals(1)
        compose.onNodeWithText(openLabel).assertDoesNotExist()
        scheduleItem.assertTextEquals(listLabel).performClick()
        compose.runOnIdle { assertEquals(listOf(room.id), openedQueues) }

        composer.performTextReplacement("/me waves")
        overflow.performClick()
        scheduleItem.assertTextEquals(listLabel).performClick()
        compose.runOnIdle {
            assertEquals(listOf(room.id, room.id), openedQueues)
            assertEquals("/me waves", draft.text)
        }

        val entered = "Ordinary draft"
        composer.performTextReplacement(entered)
        overflow.performClick()
        compose.onAllNodesWithTag("scheduled_open_chat").assertCountEquals(1)
        compose.onNodeWithText(listLabel).assertDoesNotExist()
        scheduleItem.assertTextEquals(openLabel).performClick()
        compose.onNodeWithTag("scheduled_sheet").assertIsDisplayed()
        compose
            .onNode(hasText(entered) and hasAnyAncestor(hasTestTag("scheduled_sheet")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("scheduled_sheet_open_list").assertTextEquals(listLabel).performClick()
        compose.onNodeWithTag("scheduled_sheet").assertDoesNotExist()
        composer.assertTextEquals(entered)
        compose.runOnIdle { assertEquals(listOf(room.id, room.id, room.id), openedQueues) }
    }

    @Test
    fun offlineLongPressQueuesCapturedFormattingAndKeepsNewerDraft() {
        val context = RuntimeEnvironment.getApplication()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val room = BufferEntity(id = 1, networkId = 1, name = "#kotlin", displayName = "#Kotlin", type = BufferType.CHANNEL, joined = true)
        val original = "\u0002Captured\u000f draft\nSecond line"
        val drafts = ComposerDraftStore(db)
        val schedules = ScheduledMessagesViewModel(db, drafts)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        lateinit var chat: ChatViewModel
        val started = CompletableDeferred<Unit>()
        val persist = CompletableDeferred<Unit>()
        val pages = flowOf(PagingData.empty<MessageEntity>())
        var listBufferId by mutableStateOf<Long?>(null)
        val immediateSends = mutableListOf<String>()
        runBlocking {
            db.networkDao().insert(NetworkEntity(id = 1, name = "Test network", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            db.bufferDao().insert(room)
            drafts.saveDraft(room.id, original, null)
        }
        compose.runOnIdle {
            chat = ChatViewModelTest.externalTranslationFixture(db, room, scope, connectionStates = mapOf(1L to IrcClientState.Disconnected))
            store.put("chat", chat)
        }
        val chatJob = chat.viewModelScope.coroutineContext[Job]
        try {
            compose.setContent {
                MotdTheme {
                    val filter = listBufferId
                    if (filter != null) {
                        ScheduledMessagesScreen(filter, { listBufferId = null }, schedules)
                    } else {
                        val state by chat.state.collectAsState()
                        val draft by chat.composerDraft.collectAsState()
                        ChatContent(
                            state = state,
                            items = pages.collectAsLazyPagingItems(),
                            composerEnabled = true,
                            onBack = {},
                            onOpenChannelInfo = {},
                            onOpenSearch = {},
                            onOpenImage = {},
                            onOpenPalette = { _, _ -> },
                            nickNormalizer = { it.lowercase() },
                            onSubmit = { immediateSends += it },
                            onTyping = {},
                            onSetReply = {},
                            onReact = { _, _ -> },
                            onRetry = {},
                            loadPreview = { _, _ -> null },
                            composerDraft = draft,
                            onDraftChanged = chat::saveDraft,
                            scheduledComposer =
                                ScheduledComposerActions(
                                    capture = chat::captureScheduledDraft,
                                    enqueue = { capture, timing ->
                                        chat.scheduleDraft(capture) { persisted ->
                                            started.complete(Unit)
                                            persist.await()
                                            schedules.enqueue(capture, persisted, timing)
                                        }
                                    },
                                    openList = { listBufferId = it },
                                ),
                        )
                    }
                }
            }
            compose.waitUntil(5_000) {
                chat.state.value.connState == IrcClientState.Disconnected && chat.composerDraft.value.hydrated &&
                    compose.onAllNodesWithTag("chat_composer_send").fetchSemanticsNodes().isNotEmpty()
            }
            val actionLabel = context.getString(R.string.scheduled_open)
            val send = compose.onNodeWithTag("chat_composer_send")
            send.assert(
                SemanticsMatcher("labeled schedule long action") {
                    it.config.contains(SemanticsActions.OnLongClick) && it.config[SemanticsActions.OnLongClick].label == actionLabel
                },
            )
            send.performTouchInput { longClick() }
            compose.onNodeWithTag("scheduled_sheet").assertIsDisplayed()
            assertEquals(original, runBlocking { drafts.loadDraft(room.id)?.text })
            compose.onNodeWithTag("scheduled_confirm").performClick()
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                started.isCompleted
            }
            compose.runOnIdle { chat.saveDraft("newer draft") }
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                runBlocking { drafts.loadDraft(room.id)?.text == "newer draft" }
            }
            persist.complete(Unit)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("scheduled_view").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(emptyList<String>(), immediateSends)
            assertEquals("newer draft", chat.composerDraft.value.text)
            val queued =
                runBlocking {
                    assertEquals("newer draft", drafts.loadDraft(room.id)?.text)
                    db.scheduledMessageDao().all().single()
                }
            assertEquals(original, queued.text)
            assertEquals("#Kotlin", queued.target)
            assertEquals(ScheduledMessageState.QUEUED, queued.state)
            assertNull(queued.dueAt)
            compose.onNodeWithTag("scheduled_view").performClick()
            compose.onNodeWithTag("scheduled_list").assertIsDisplayed()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("scheduled_message_${queued.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.scheduled_status_waiting)).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.scheduled_edit)).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.scheduled_cancel_message)).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("chat_composer_send").fetchSemanticsNodes().isNotEmpty()
            }
            send.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithTag("scheduled_confirm").performClick()
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                chat.composerDraft.value.text
                    .isEmpty()
            }
            runBlocking {
                assertNull(drafts.loadDraft(room.id))
                assertEquals(listOf(original, "newer draft"), db.scheduledMessageDao().all().map { it.text })
            }
            compose
                .onNodeWithTag("chat_composer_field")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            assertEquals(emptyList<String>(), immediateSends)
        } finally {
            persist.complete(Unit)
            compose.runOnIdle {
                store.clear()
                scope.cancel()
            }
            compose.waitForIdle()
            runBlocking {
                chatJob?.join()
                scope.coroutineContext[Job]?.join()
            }
            db.close()
        }
    }
}
