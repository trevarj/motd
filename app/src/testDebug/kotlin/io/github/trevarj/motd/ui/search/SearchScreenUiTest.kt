package io.github.trevarj.motd.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.ui.theme.MotdTheme
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

    private fun group(hit: SearchHit) =
        SearchGroup(
            bufferId = hit.message.bufferId,
            bufferDisplayName = "#kotlin",
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
    ) = SearchHit(
        message =
            MessageEntity(
                id = id,
                bufferId = 1,
                serverTime = 1_000L,
                msgid = msgid,
                sender = "alice",
                kind = MessageKind.PRIVMSG,
                text = text,
                dedupKey = "key-$id",
            ),
        bufferDisplayName = "#kotlin",
        networkName = "Libera",
        bufferType = BufferType.CHANNEL,
        networkId = 1,
    )
}
