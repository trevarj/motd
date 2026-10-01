package io.github.trevarj.motd

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import io.github.trevarj.motd.testing.ReadAloudHarness
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@Config(qualifiers = "w320dp-h891dp")
class ReadAloudPlayerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private fun withChat(
        type: BufferType = BufferType.CHANNEL,
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
                    val network = db.networkDao().insert(NetworkEntity(name = "Speech surface", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
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
            val pages = flowOf(PagingData.empty<MessageEntity>())
            compose.setContent {
                val state by f.controller.state.collectAsState()
                val config by f.controller.config.collectAsState()
                val voices by f.controller.voices.collectAsState()
                val items = pages.collectAsLazyPagingItems()
                MotdTheme(dynamicColor = false) {
                    Box(Modifier.width(320.dp).fillMaxSize().testTag("reader_surface_root")) {
                        ChatContent(
                            state = ChatState(buffer = room),
                            items = items,
                            composerEnabled = true,
                            onBack = {},
                            onOpenChannelInfo = {},
                            onOpenSearch = {},
                            onOpenImage = {},
                            nickNormalizer = { it.lowercase() },
                            onSubmit = {},
                            onTyping = {},
                            onSetReply = {},
                            onReact = { _, _ -> },
                            onRetry = {},
                            loadPreview = { _, _ -> null },
                            entryState = EntryPositionState.Settled,
                            readAloudState = state,
                            readAloudConfig = config,
                            readAloudVoices = voices,
                            onReadAloudToggle = { f.controller.setEnabled(room.id, !state.enabled) },
                            onReadAloudPrevious = f.controller::previous,
                            onReadAloudPauseResume = f.controller::togglePaused,
                            onReadAloudSkip = f.controller::skip,
                            onReadAloudLatest = f.controller::latest,
                            onReadAloudStop = f.controller::stop,
                            onReadAloudOptions = { f.controller.openVoiceOptions() },
                            onReadAloudSaveOptions = f.controller::saveVoiceOptions,
                            onReadAloudPreview = { f.controller.preview(room.id, it) },
                        )
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

    private fun admit(
        f: ReadAloudHarness,
        room: BufferEntity,
    ) {
        compose.runOnIdle {
            f.controller.setEnabled(room.id, true)
            (1L..3L).forEach { id ->
                f.controller.onIncoming(
                    MessageEntity(
                        id = id,
                        bufferId = room.id,
                        sender = "trev",
                        normalizedActor = "trev",
                        serverTime = id,
                        kind = MessageKind.PRIVMSG,
                        text = if (id == 1L) "hello how are you" else "message $id",
                        dedupKey = "surface-$id",
                    ),
                )
            }
        }
        compose.waitUntil(10_000) {
            // Match the real Room/Main fixtures: frame-clock advancement does not drain Android's paused looper.
            compose.waitForIdle()
            f.output.played.isNotEmpty()
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
            compose.onNodeWithTag("chat_read_aloud_toggle").performClick()
            compose.waitUntil { f.controller.state.value.enabled }
            assertEquals(0, f.controller.state.value.total)
            compose.onNodeWithTag("chat_overflow").performClick()
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
