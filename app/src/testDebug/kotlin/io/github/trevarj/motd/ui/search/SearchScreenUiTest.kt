package io.github.trevarj.motd.ui.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ai.text.TextTermination
import io.github.trevarj.motd.ai.text.TextTransformResult
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.irc.ext.SearchResultKind
import io.github.trevarj.motd.ui.ai.AiTextSheet
import io.github.trevarj.motd.ui.ai.AiTextSource
import io.github.trevarj.motd.ui.ai.AiTextUiState
import io.github.trevarj.motd.ui.ai.source
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class SearchScreenUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun localAndTransientServerHoldsUseFullSelectedText() {
        val fullText = "The report is ready.\nSecond paragraph.\nThird line.\nFourth line beyond the row preview."
        val local = hit(42, "local", fullText)
        val server = ServerHitUi(7, "nick", fullText, SearchResultKind.PRIVMSG, 123, null)
        var state by mutableStateOf(SearchUiState(rawQuery = "report", groups = listOf(group(local))))
        var ai by mutableStateOf<AiTextUiState>(AiTextUiState.Closed)
        var opened: Long? = null
        compose.setContent {
            LaunchedEffect(state, ai.source()) {
                val selected = ai.source() as? AiTextSource.TransientMessage ?: return@LaunchedEffect
                if (state.serverTranslationBody(selected.key) != selected.text) ai = AiTextUiState.Closed
            }
            MotdTheme {
                SearchContent(
                    state,
                    onQueryChange = {
                        state = state.copy(rawQuery = it, server = ServerSearchState.Idle)
                        ai = AiTextUiState.Closed
                    },
                    onScopeChange = {},
                    onBack = {},
                    onOpenHit = { opened = it.message.id },
                    onTranslateLocalHit = { ai = AiTextUiState.Choosing(AiTextSource.StoredMessage(it.message.bufferId, it.message.id, it.message.msgid, it.message.text)) },
                    onTranslateServerHit = { hit, generation, index -> ai = AiTextUiState.Choosing(AiTextSource.TransientMessage("server-search:$generation:$index", hit.text, hit.bufferId, hit.msgid)) },
                )
                AiTextSheet(ai, emptyList(), null, onGenerate = {
                    val source = (ai as AiTextUiState.Choosing).source
                    ai = AiTextUiState.Result(1, source, fullText, TextTransformResult("Le rapport est prêt.", TextTermination.EOG), 1)
                }, onTargetSelected = {}, onDismiss = { ai = AiTextUiState.Closed }, onOpenSetup = {}, onManageStyles = {})
            }
        }
        compose.onNodeWithTag("search_result_local").performClick()
        compose.runOnIdle { assertEquals(42L, opened) }
        compose.onNodeWithTag("search_result_local").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("ai_text_source").assertTextEquals(fullText)
        compose.onNodeWithTag("ai_text_translate").performClick()
        compose.onNodeWithText("Le rapport est prêt.").assertIsDisplayed()
        compose.onNodeWithTag("ai_text_apply").assertDoesNotExist()
        compose.onNodeWithTag("ai_text_close").performClick()
        compose.runOnIdle { state = state.copy(scope = SearchScope.SERVER, server = ServerSearchState.Results(listOf(server), false, 9)) }
        compose.onNodeWithTag("search_result_123").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("ai_text_source").assertTextEquals(fullText)
        compose.runOnIdle { assertEquals("server-search:9:0", ((ai as AiTextUiState.Choosing).source as AiTextSource.TransientMessage).key) }
        compose.onNodeWithTag("ai_text_translate").performClick()
        compose.onNodeWithText("Le rapport est prêt.").assertIsDisplayed()
        // Even identical replacement text belongs to a different server response lease.
        compose.runOnIdle { state = state.copy(server = ServerSearchState.Results(listOf(server), false, 10)) }
        compose.onNodeWithTag("ai_text_sheet").assertDoesNotExist()
        compose.onNodeWithTag("search_field").performTextClearance()
        compose.onNodeWithTag("search_field").performTextInput("replacement")
        compose.onNodeWithTag("search_result_123").assertDoesNotExist()
    }

    @Test
    fun localQueryChange_hidesStaleExternalRowsUntilMatchingResultsArrive() {
        var state by mutableStateOf(
            SearchUiState(
                rawQuery = "alpha",
                groups = listOf(group(hit(1, "alpha", "alpha-only result"))),
            ),
        )
        compose.setContent {
            MotdTheme {
                // Keep this callback intentionally inert so the external state remains alpha.
                SearchContent(state, onQueryChange = {}, onScopeChange = {}, onBack = {}, onOpenHit = {})
            }
        }

        compose.onAllNodesWithTag("search_result_alpha").assertCountEquals(1)
        val field = compose.onNodeWithTag("search_field")
        field.performTextClearance()
        field.performTextInput("beta")

        field.assertTextEquals("beta")
        compose.onAllNodesWithTag("search_loading").assertCountEquals(1)
        compose.onAllNodesWithTag("search_results").assertCountEquals(0)
        compose.onAllNodesWithTag("search_result_alpha").assertCountEquals(0)

        compose.runOnIdle {
            state =
                SearchUiState(
                    rawQuery = "beta",
                    groups = listOf(group(hit(2, "beta", "beta-only result"))),
                )
        }

        compose.onAllNodesWithTag("search_loading").assertCountEquals(0)
        compose.onAllNodesWithTag("search_result_alpha").assertCountEquals(0)
        compose.onAllNodesWithTag("search_result_beta").assertCountEquals(1)
        compose.onAllNodesWithText("beta-only result").assertCountEquals(1)
    }

    @Test
    fun dickordLabels_enabled_arePresentationOnly() {
        val rawHit =
            hit(
                id = 2,
                msgid = "dickord",
                text = "discord result",
                sender = "Alice/discord",
                bufferDisplayName = "#discord.me.chat.alice",
            )
        var opened: SearchHit? = null
        compose.setContent {
            CompositionLocalProvider(LocalDickordLabsEnabled provides true) {
                MotdTheme {
                    SearchContent(
                        state =
                            SearchUiState(
                                rawQuery = "discord",
                                bufferMatches = listOf(bufferMatch("#discord.me.chat.alice")),
                                groups = listOf(group(rawHit, "#discord.me.chat.alice")),
                            ),
                        onQueryChange = {},
                        onScopeChange = {},
                        onBack = {},
                        onOpenHit = { opened = it },
                    )
                }
            }
        }

        compose.onAllNodesWithText("#me.chat.alice").assertCountEquals(2)
        compose.onAllNodesWithText("Alice").assertCountEquals(1)
        compose.onAllNodesWithText("#discord.me.chat.alice").assertCountEquals(0)
        compose.onAllNodesWithText("Alice/discord").assertCountEquals(0)

        compose.onNodeWithTag("search_result_dickord").performClick()
        compose.runOnIdle { assertEquals("Alice/discord", opened?.message?.sender) }
    }

    @Test
    fun dickordLabels_disabled_remainRaw() {
        val rawHit =
            hit(
                id = 3,
                msgid = "dickord-disabled",
                text = "discord result",
                sender = "Alice/discord",
                bufferDisplayName = "#discord.me.chat.alice",
            )
        compose.setContent {
            MotdTheme {
                SearchContent(
                    state =
                        SearchUiState(
                            rawQuery = "discord",
                            bufferMatches = listOf(bufferMatch("#discord.me.chat.alice")),
                            groups = listOf(group(rawHit, "#discord.me.chat.alice")),
                        ),
                    onQueryChange = {},
                    onScopeChange = {},
                    onBack = {},
                    onOpenHit = {},
                )
            }
        }

        compose.onAllNodesWithText("#discord.me.chat.alice").assertCountEquals(2)
        compose.onAllNodesWithText("Alice/discord").assertCountEquals(1)
        compose.onAllNodesWithText("#me.chat.alice").assertCountEquals(0)
        compose.onAllNodesWithText("Alice").assertCountEquals(0)
    }

    private fun group(
        hit: SearchHit,
        bufferDisplayName: String = "#kotlin",
    ) = SearchGroup(
        bufferId = hit.message.bufferId,
        bufferDisplayName = bufferDisplayName,
        networkName = "Libera",
        bufferType = BufferType.CHANNEL,
        networkId = 1,
        avatarOverrideModel = null,
        hits = listOf(hit),
    )

    private fun hit(
        id: Long,
        msgid: String,
        text: String,
        sender: String = "alice",
        bufferDisplayName: String = "#kotlin",
    ) = SearchHit(
        message =
            MessageEntity(
                id = id,
                bufferId = 1,
                serverTime = 1_000L,
                msgid = msgid,
                sender = sender,
                kind = MessageKind.PRIVMSG,
                text = text,
                dedupKey = "key-$id",
            ),
        bufferDisplayName = bufferDisplayName,
        networkName = "Libera",
        bufferType = BufferType.CHANNEL,
        networkId = 1,
    )

    private fun bufferMatch(displayName: String) =
        ChatListRow(
            bufferId = 1,
            networkId = 1,
            networkName = "Libera",
            displayName = displayName,
            type = BufferType.CHANNEL,
            pinned = false,
            muted = false,
            lastMessageText = null,
            lastMessageSender = null,
            lastMessageTime = null,
            unreadCount = 0,
            mentionCount = 0,
        )
}
