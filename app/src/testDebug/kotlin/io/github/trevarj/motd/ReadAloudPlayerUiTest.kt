package io.github.trevarj.motd

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.audio.ReadAloudConfig
import io.github.trevarj.motd.audio.ReadAloudVoice
import io.github.trevarj.motd.audio.ReadAloudVoices
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.testing.ReadAloudHarness
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.RoutedInlineMediaFixture
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h891dp")
class ReadAloudPlayerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private fun withChat(
        type: BufferType = BufferType.CHANNEL,
        rows: (BufferEntity) -> List<MessageEntity> = { room -> (3L downTo 1L).map { message(room, it) } },
        layoutDensity: () -> LayoutDensity = { LayoutDensity.COMFORTABLE },
        onSetReply: (MessageEntity?) -> Unit = {},
        uriHandler: UriHandler? = null,
        loadPreview: suspend (String, Long?) -> LinkPreview? = { _, _ -> null },
        onOpenImage: (String) -> Unit = {},
        onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
        networkId: Long = 0,
        networkMediaHttp: NetworkMediaHttp? = null,
        block: (ReadAloudHarness, BufferEntity) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val room =
                runBlocking {
                    val network = db.networkDao().insert(NetworkEntity(id = networkId, name = "Speech surface", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
                    val seed = BufferEntity(networkId = network, name = "#room", displayName = "#room", type = type, joined = true)
                    seed.copy(id = db.bufferDao().insert(seed))
                }
            val f = ReadAloudHarness(context, db, scope)
            runBlocking { f.prefs.replaceSystem(ReadAloudConfig()) }
            f.selected.set(room.id)
            f.synth.voices.value =
                ReadAloudVoices(
                    voices = listOf(ReadAloudVoice("real-named-offline", "Named offline voice", "en-US")),
                )
            val pages = flowOf(PagingData.from(rows(room)))
            compose.setContent {
                val state by f.controller.state.collectAsState()
                val config by f.controller.config.collectAsState()
                val voices by f.controller.voices.collectAsState()
                val items = pages.collectAsLazyPagingItems()
                CompositionLocalProvider(
                    LocalAutomaticRemoteMedia provides false,
                    LocalNetworkMediaHttp provides networkMediaHttp,
                    LocalUriHandler provides (uriHandler ?: LocalUriHandler.current),
                ) {
                    MotdTheme(dynamicColor = false, layoutDensity = layoutDensity()) {
                        Box(Modifier.width(320.dp).fillMaxSize().testTag("reader_surface_root")) {
                            ChatContent(
                                state = ChatState(buffer = room),
                                items = items,
                                composerEnabled = true,
                                onBack = {},
                                onOpenChannelInfo = {},
                                onOpenSearch = {},
                                onOpenImage = onOpenImage,
                                nickNormalizer = { it.lowercase() },
                                onSubmit = {},
                                onTyping = {},
                                onSetReply = onSetReply,
                                onReact = { _, _ -> },
                                onRetry = {},
                                loadPreview = loadPreview,
                                onAudioToggle = onAudioToggle,
                                entryState = EntryPositionState.Settled,
                                readAloudState = state,
                                readAloudConfig = config,
                                readAloudVoices = voices,
                                onReadAloudToggle = { f.controller.setEnabled(room.id, !state.enabled) },
                                onReadAloudPrevious = f.controller::previous,
                                onReadAloudPauseResume = f.controller::togglePaused,
                                onReadAloudSkip = f.controller::skip,
                                onReadAloudLatest = f.controller::latest,
                                onReadAloudMessage = f.controller::readMessage,
                                onReadAloudStop = f.controller::stop,
                                onReadAloudOptions = { f.controller.openVoiceOptions() },
                                onReadAloudSaveOptions = f.controller::saveVoiceOptions,
                                onReadAloudPreview = { f.controller.preview(room.id, it) },
                            )
                        }
                    }
                }
            }
            block(f, room)
        } finally {
            compose.runOnIdle { scope.cancel() }
            compose.waitForIdle()
            db.close()
        }
    }

    private fun message(
        room: BufferEntity,
        id: Long,
        text: String = if (id == 1L) "hello how are you" else "message $id",
    ) = MessageEntity(
        id = id,
        bufferId = room.id,
        sender = "trev",
        normalizedActor = "trev",
        serverTime = id,
        kind = MessageKind.PRIVMSG,
        text = text,
        dedupKey = "surface-$id",
    )

    private fun tapText(
        id: Long,
        text: String,
    ) {
        compose.onNodeWithTag("chat_timeline").performScrollToNode(hasTestTag("chat_message_$id"))
        val node =
            compose.onNode(
                hasText(text, substring = true) and hasAnyAncestor(hasTestTag("chat_message_$id")),
                useUnmergedTree = true,
            )
        val layouts = mutableListOf<TextLayoutResult>()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val start =
            layout.layoutInput
                .text.text
                .indexOf(text)
        check(start >= 0)
        val point = layout.getBoundingBox(start + text.length / 2).center
        node.performTouchInput { click(point) }
    }

    private fun awaitSpeech(
        f: ReadAloudHarness,
        id: Long,
        count: Int,
        utterance: String,
        preview: String,
    ) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            f.controller.state.value.current
                ?.id == id && f.output.played.size == count
        }
        assertEquals(utterance, f.synth.utterances.last())
        assertEquals(preview, f.controller.state.value.preview)
        compose.onNode(hasText(preview) and hasAnyAncestor(hasTestTag("read_aloud_player")), useUnmergedTree = true).assertIsDisplayed()
    }

    private fun admit(
        f: ReadAloudHarness,
        room: BufferEntity,
    ) {
        compose.runOnIdle {
            f.controller.setEnabled(room.id, true)
            (1L..3L).forEach { id ->
                f.controller.onIncoming(message(room, id))
            }
        }
        compose.waitUntil(10_000) {
            // Match the real Room/Main fixtures: frame-clock advancement does not drain Android's paused looper.
            compose.waitForIdle()
            f.output.played.isNotEmpty()
        }
    }

    @Test fun actualBodyTapsReadEarlierAndOwnMessagesImmediatelyAcrossBubbleLayouts() {
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        withChat(
            rows = { room ->
                listOf(
                    message(room, 4, "my pending words").copy(isSelf = true, sender = "me", normalizedActor = "me", msgid = null, pendingLabel = "own-pending"),
                    message(room, 3, "waves toward the reader").copy(kind = MessageKind.ACTION),
                    message(room, 2),
                    message(room, 1),
                    message(room, 0, "earlier visible words"),
                )
            },
            layoutDensity = { density.value },
        ) { f, room ->
            tapText(0, "earlier visible words")
            compose.runOnIdle {
                assertFalse(f.controller.state.value.enabled)
                assertTrue(f.synth.utterances.isEmpty())
                assertTrue(f.output.played.isEmpty())
            }
            compose.onNodeWithTag("read_aloud_player").assertDoesNotExist()
            admit(f, room)
            compose.onNodeWithTag("read_aloud_pause_resume").performClick()
            var count = 1
            for (layout in LayoutDensity.entries) {
                compose.runOnIdle { density.value = layout }
                tapText(0, "earlier visible words")
                awaitSpeech(f, 0, ++count, "trev says, earlier visible words", "earlier visible words")
                assertFalse(f.controller.state.value.paused)
                tapText(3, "waves toward the reader")
                awaitSpeech(f, 3, ++count, "trev waves toward the reader", "waves toward the reader")
            }
            tapText(4, "my pending words")
            awaitSpeech(f, 4, ++count, "me says, my pending words", "my pending words")
            val previous = f.output.played.last()
            tapText(4, "my pending words")
            awaitSpeech(f, 4, ++count, "me says, my pending words", "my pending words")
            assertFalse(previous.exists())
            assertEquals(5, f.controller.state.value.total)
            compose.onNodeWithTag("read_aloud_stop").performClick()
            tapText(0, "earlier visible words")
            compose.runOnIdle {
                assertEquals(count, f.output.played.size)
                assertFalse(f.controller.state.value.enabled)
            }
        }
    }

    @Test fun activeReaderBodyLongPressAndSwipeStillOpenActionsAndReplyWithoutSelection() {
        var reply: MessageEntity? = null
        withChat(onSetReply = { reply = it }) { f, room ->
            admit(f, room)
            compose.onNodeWithTag("chat_message_2").performTouchInput {
                swipe(start = Offset(width * .25f, height * .5f), end = Offset(width * .9f, height * .5f), durationMillis = 300)
            }
            compose.runOnIdle {
                assertEquals(2L, reply?.id)
                assertEquals(
                    1L,
                    f.controller.state.value.current
                        ?.id,
                )
                assertEquals(listOf("trev says, hello how are you"), f.synth.utterances)
            }
            compose
                .onNode(hasText("message 2") and hasAnyAncestor(hasTestTag("chat_message_2")), useUnmergedTree = true)
                .performTouchInput { longClick() }
            compose.onNodeWithTag("message_action_sheet").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(
                    1L,
                    f.controller.state.value.current
                        ?.id,
                )
                assertEquals(1, f.output.played.size)
            }
        }
    }

    @Test fun activeReaderLinkPreviewImageAndAudioChildrenKeepTheirOwnPointerActions() {
        RoutedInlineMediaFixture().use { media ->
            media.server.enqueue(
                MockResponse().setHeader("Content-Type", "image/png").setBody(
                    Buffer().write(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")),
                ),
            )
            val url = "https://example.org/reader-${UUID.randomUUID()}"
            val image = "http://media.invalid/reader-${UUID.randomUUID()}.png"
            val audio = "https://files.example/reader-${UUID.randomUUID()}.opus"
            val openedLinks = mutableListOf<String>()
            val openedImages = mutableListOf<String>()
            val audioRequests = mutableListOf<AudioPlaybackRequest>()
            val preview = CompletableDeferred<LinkPreview?>()
            var previewLoads = 0
            withChat(
                rows = { room ->
                    listOf(
                        message(room, 4, audio),
                        message(room, 3, "image caption $image"),
                        message(room, 2, "visit $url"),
                        message(room, 1),
                    )
                },
                networkId = media.networkId,
                networkMediaHttp = media.http,
                uriHandler =
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            openedLinks += uri
                        }
                    },
                loadPreview = { requested, _ ->
                    if (requested == url) {
                        previewLoads++
                        preview.await()
                    } else {
                        null
                    }
                },
                onOpenImage = openedImages::add,
                onAudioToggle = audioRequests::add,
            ) { f, room ->
                admit(f, room)
                tapText(2, url)
                compose.runOnIdle { assertEquals(listOf(url), openedLinks) }
                compose
                    .onNode(hasTestTag("link_preview_awaiting") and hasAnyAncestor(hasTestTag("chat_message_2")), useUnmergedTree = true)
                    .performTouchInput { click() }
                compose.waitUntil(10_000) {
                    compose.waitForIdle()
                    previewLoads == 1
                }
                compose.runOnIdle {
                    preview.complete(LinkPreview(url = url, title = "Reader child preview", description = null, imageUrl = null, siteName = null))
                }
                compose.onNodeWithText("Reader child preview", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
                compose.runOnIdle {
                    val app = ApplicationProvider.getApplicationContext<Application>()
                    assertEquals(url, shadowOf(app).nextStartedActivity.dataString)
                }
                compose.onNodeWithTag("chat_timeline").performScrollToNode(hasTestTag("chat_message_3"))
                compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performTouchInput { click() }
                compose.waitUntil(10_000) {
                    compose.waitForIdle()
                    compose.onAllNodesWithTag("inline_media_loaded", useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("inline_media_loaded", useUnmergedTree = true).performTouchInput { click() }
                compose.runOnIdle { assertEquals(listOf(image), openedImages) }
                compose.onNodeWithTag("chat_timeline").performScrollToNode(hasTestTag("chat_message_4"))
                compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).performTouchInput { click() }
                compose.runOnIdle {
                    assertEquals(audio, audioRequests.single().attachment.url)
                    assertEquals(
                        1L,
                        f.controller.state.value.current
                            ?.id,
                    )
                    assertEquals(listOf("trev says, hello how are you"), f.synth.utterances)
                    assertEquals(1, f.output.played.size)
                }
                compose.onNodeWithTag("audio_player_details", useUnmergedTree = true).performTouchInput { click() }
                compose.onNodeWithText("Audio", useUnmergedTree = true).assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(
                        1L,
                        f.controller.state.value.current
                            ?.id,
                    )
                    assertEquals(1, f.output.played.size)
                }
            }
        }
    }

    @Test fun standaloneVoiceBackgroundReadsItsLabelWhileAudioControlsKeepTheirOwnActions() {
        val audio = "https://files.example/reader-voice-${UUID.randomUUID()}.opus"
        val requests = mutableListOf<AudioPlaybackRequest>()
        withChat(
            rows = { room ->
                listOf(
                    message(room, 1),
                    message(room, 2, "[voice 0:05 audio/ogg] $audio").copy(
                        serverTime = 0,
                        isSelf = true,
                        sender = "me",
                        normalizedActor = "me",
                        pendingLabel = "voice-pending",
                    ),
                )
            },
            onAudioToggle = requests::add,
        ) { f, room ->
            compose.onNodeWithTag("chat_timeline").performScrollToNode(hasTestTag("chat_message_2"))
            val player = compose.onNodeWithTag("audio_player", useUnmergedTree = true)
            player.performTouchInput { click(Offset(width * .5f, height - 2f)) }
            compose.runOnIdle {
                assertFalse(f.controller.state.value.enabled)
                assertTrue(f.synth.utterances.isEmpty())
            }
            compose.runOnIdle {
                f.controller.setEnabled(room.id, true)
                f.controller.onIncoming(message(room, 1))
            }
            awaitSpeech(f, 1, 1, "trev says, hello how are you", "hello how are you")
            compose.onNodeWithTag("chat_timeline").performScrollToNode(hasTestTag("chat_message_2"))
            compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(audio, requests.single().attachment.url)
                assertEquals(listOf("trev says, hello how are you"), f.synth.utterances)
            }
            compose.onNodeWithTag("read_aloud_pause_resume").performClick()
            player.performTouchInput { click(Offset(width * .5f, height - 2f)) }
            awaitSpeech(f, 2, 2, "me says, voice message", "voice message")
            assertFalse(f.controller.state.value.paused)
            assertEquals(2, f.controller.state.value.total)
            player.performTouchInput { click(Offset(width * .5f, height - 2f)) }
            awaitSpeech(f, 2, 3, "me says, voice message", "voice message")
            assertEquals(2, f.controller.state.value.total)
            compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithTag("audio_player_details", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithText("Audio", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(listOf(audio, audio), requests.map { it.attachment.url })
                assertEquals(listOf("trev says, hello how are you", "me says, voice message", "me says, voice message"), f.synth.utterances)
                assertEquals(3, f.output.played.size)
            }
        }
    }

    @Test fun narrowActualChatBannerHasAccessibleControlsAndTruePauseReplayNavigation() =
        withChat { f, room ->
            admit(f, room)
            val controls = listOf("previous", "pause_resume", "skip", "latest", "stop", "options")
            controls.forEach { control ->
                val node = compose.onNodeWithTag("read_aloud_$control")
                node.assertIsDisplayed()
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue("$control has too-small touch target", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            }
            compose.onNodeWithTag("read_aloud_previous").assertIsNotEnabled()
            compose.onNodeWithTag("read_aloud_pause_resume").performClick()
            assertTrue(f.controller.state.value.paused)
            val file = f.output.played.single()
            compose.onNodeWithTag("read_aloud_pause_resume").performClick()
            assertFalse(f.controller.state.value.paused)
            assertEquals(file, f.output.played.single())
            compose.onNodeWithTag("read_aloud_skip").performClick()
            compose.waitUntil {
                compose.waitForIdle()
                f.controller.state.value.current
                    ?.id == 2L && f.output.played.size == 2
            }
            compose.onNodeWithTag("read_aloud_previous").performClick()
            compose.waitUntil {
                compose.waitForIdle()
                f.controller.state.value.current
                    ?.id == 1L && f.output.played.size == 3
            }
            compose.onNodeWithTag("read_aloud_latest").performClick()
            compose.waitUntil {
                compose.waitForIdle()
                f.controller.state.value.current
                    ?.id == 3L && f.output.played.size == 4
            }
            compose.onNodeWithTag("read_aloud_latest").assertIsNotEnabled()
            compose.onNodeWithTag("read_aloud_stop").performClick()
            compose.onNodeWithTag("read_aloud_player").assertDoesNotExist()
            assertTrue(f.output.played.none { it.exists() })
        }

    @Test fun serverOverflowNeverOffersIncomingChatSpeech() =
        withChat(BufferType.SERVER) { _, _ ->
            compose.onNodeWithTag("chat_overflow").performClick()
            compose.onNodeWithTag("chat_read_aloud_toggle").assertDoesNotExist()
            compose.onNodeWithTag("chat_read_aloud_options").assertDoesNotExist()
        }

    @Test fun queryOverflowStartsOnlyNewSessionAndVoicePreviewNeverEnablesReading() =
        withChat(BufferType.QUERY) { f, _ ->
            compose.onNodeWithTag("chat_overflow").performClick()
            compose.onNodeWithText("TTS Reader").assertIsDisplayed()
            compose.onNodeWithTag("chat_read_aloud_toggle").performClick()
            compose.waitUntil { f.controller.state.value.enabled }
            assertEquals(0, f.controller.state.value.total)
            compose.onNodeWithTag("chat_overflow").performClick()
            compose.onNodeWithText("Stop TTS Reader").assertIsDisplayed()
            compose.onNodeWithTag("chat_read_aloud_options").performClick()
            compose.onNodeWithTag("read_aloud_voice_options").assertIsDisplayed()
            compose.onNodeWithTag("read_aloud_preview").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                f.controller.state.value.previewing && f.output.played.isNotEmpty()
            }
            assertFalse(f.controller.state.value.enabled)
            compose.onNodeWithTag("read_aloud_preview").performScrollTo().performClick()
            compose.waitUntil { !f.controller.state.value.previewing }
            assertFalse(f.controller.state.value.enabled)
        }
}
