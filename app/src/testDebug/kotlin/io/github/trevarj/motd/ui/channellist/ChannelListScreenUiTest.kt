package io.github.trevarj.motd.ui.channellist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
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
class ChannelListScreenUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyboardSearch_submitsVisibleTextWhenExternalQueryIsStale() {
        var submittedQuery: String? = null
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state =
                        ChannelListUiState(
                            networkId = 2,
                            initialized = true,
                            connState = IrcClientState.Ready("trev", emptySet(), emptyMap()),
                        ),
                    onBack = {},
                    onQueryChange = {},
                    onSearch = { submittedQuery = it },
                    onJoin = {},
                )
            }
        }

        val search = compose.onNodeWithTag("channel_list_search_field")
        search.performTextInput("bitcoin")
        search.performImeAction()

        compose.runOnIdle { assertEquals("bitcoin", submittedQuery) }
    }

    @Test
    fun loadingEmptyAndErrorStates_keepTheSubmittedQueryForRetry() {
        var state by mutableStateOf(
            ChannelListUiState(
                networkId = 2,
                initialized = true,
                connState = IrcClientState.Ready("trev", emptySet(), emptyMap()),
                query = "bitcoin",
                loading = true,
            ),
        )
        var submittedQuery: String? = null
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state = state,
                    onBack = {},
                    onQueryChange = {},
                    onSearch = { submittedQuery = it },
                    onJoin = {},
                )
            }
        }

        compose.onNodeWithText("Loading channels…").assertExists()
        compose.runOnIdle { state = state.copy(loading = false, loaded = true) }
        compose.onNodeWithText("No channels found").assertExists()
        compose.runOnIdle { state = state.copy(error = "fixture timeout") }
        compose.onNodeWithText("Couldn't load channels").assertExists()
        compose.onNodeWithText("Try again").performClick()

        compose.runOnIdle { assertEquals("bitcoin", submittedQuery) }
    }

    @Test
    fun resultRows_dispatchOnlyJoinableChannels() {
        var joinedChannel: String? = null
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state =
                        ChannelListUiState(
                            networkId = 2,
                            initialized = true,
                            connState = IrcClientState.Ready("trev", emptySet(), emptyMap()),
                            loaded = true,
                            listings =
                                listOf(
                                    ChannelListing("#open", 30, "Open fixture"),
                                    ChannelListing("#pending", 20, "Pending fixture"),
                                    ChannelListing("#joined", 10, "Joined fixture"),
                                ),
                            pendingChannels = setOf("#pending"),
                            joinedChannels = setOf("#joined"),
                        ),
                    onBack = {},
                    onQueryChange = {},
                    onSearch = {},
                    onJoin = { joinedChannel = it },
                )
            }
        }

        compose.onNodeWithTag("channel_list_join_open").assertIsEnabled().performClick()
        compose.onNodeWithTag("channel_list_join_pending").assertIsNotEnabled()
        compose.onNodeWithTag("channel_list_join_joined").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("#open", joinedChannel) }
    }
}
