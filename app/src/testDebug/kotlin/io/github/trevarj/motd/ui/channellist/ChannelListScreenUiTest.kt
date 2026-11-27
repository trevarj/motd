package io.github.trevarj.motd.ui.channellist

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.irc.client.ChannelListProgress
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ensureContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.DateFormat
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChannelListScreenUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sortChangesOrderWithoutChangingExpandedChannel() {
        val restoration = StateRestorationTester(compose)
        val rows = listOf(ChannelListing("#zed", 300, "Zed topic"), ChannelListing("#alpha", 100, "Alpha topic"))
        restoration.setContent {
            MotdTheme { ChannelListContent(readyState().copy(popular = DirectoryResult(rows, loaded = true))) }
        }
        val alpha = compose.onNodeWithTag("channel_list_row_alpha")
        val zed = compose.onNodeWithTag("channel_list_row_zed")

        fun alphaFirst() = alpha.fetchSemanticsNode().boundsInRoot.top < zed.fetchSemanticsNode().boundsInRoot.top

        assertTrue(!alphaFirst())
        zed.performClick()
        compose.onNodeWithTag("channel_list_sort").performClick()
        compose.onNodeWithTag("channel_list_sort_name").performClick()
        assertTrue(alphaFirst())
        compose.onNodeWithTag("channel_list_topic_zed").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_topic_alpha").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        assertTrue(alphaFirst())
        compose.onNodeWithTag("channel_list_sort").performClick()
        compose.onNodeWithTag("channel_list_sort_population").performClick()
        assertTrue(!alphaFirst())
    }

    @Test
    fun partialRowsAndLastSuccessfulFetchFollowSectionState() {
        val previous = DirectoryResult(listOf(ChannelListing("#previous", 125, "")), loaded = true, fetchedAtMillis = 1000L)
        val partial = ChannelListProgress(listOf(ChannelListing("#new", 100, "")), matchedCount = 130L)
        var result by mutableStateOf(previous.copy(loading = true, progress = partial))
        lateinit var expected: String
        compose.setContent {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            expected = "Last fetched: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(1000L))
            MotdTheme { ChannelListContent(readyState().copy(popular = result)) }
        }
        val fetched = compose.onNodeWithTag("channel_list_fetched_popular")
        val progress = compose.onNodeWithTag("channel_list_progress_popular")
        fetched.assertTextEquals(expected)
        progress.assertTextEquals("Matching channels received: 130")
        compose.onNodeWithText("Loading channels…").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_row_new").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_row_previous").assertDoesNotExist()
        compose.runOnIdle { result = previous.copy(error = "Request failed") }
        fetched.assertTextEquals(expected)
        progress.assertDoesNotExist()
        compose.onNodeWithTag("channel_list_row_new").assertDoesNotExist()
        compose.onNodeWithTag("channel_list_row_previous").assertIsDisplayed()
    }

    @Test
    fun directoryDescriptionsUseNeutralInkOnListFillWithoutChangingLinksOrUserCount() {
        val preset = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val contrastingFill = mutableStateOf(false)
        val topic = "Reading https://example.test \u000304red\u000f \u000399,00paper\u000f"
        var fill = Color.Unspecified
        var linkInk = Color.Unspecified
        var metadataInk = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset.value, adjustPaletteReadability = true) {
                val scheme = MaterialTheme.colorScheme
                val rowColor =
                    if (contrastingFill.value) {
                        if (preset.value == ColorThemePreset.AYU_LIGHT) Color(0xFF182836) else Color(0xFFF4E3D1)
                    } else {
                        scheme.surfaceContainerLow
                    }
                MaterialTheme(colorScheme = scheme.copy(surfaceContainerLow = rowColor)) {
                    fill = rowColor
                    linkInk = ensureContrast(scheme.primary, listOf(fill), MESSAGE_TEXT_CONTRAST)
                    metadataInk = scheme.onSurfaceVariant
                    ChannelListContent(
                        state =
                            readyState().copy(
                                popular = DirectoryResult(listOf(ChannelListing("#reading", 30, topic)), loaded = true),
                            ),
                    )
                }
            }
        }

        fun layoutOf(
            value: String,
            substring: Boolean = false,
        ): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(value, substring = substring, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }

        val snippet = layoutOf("Reading", substring = true).layoutInput.text
        assertTrue(snippet.getLinkAnnotations(0, snippet.length).isEmpty())
        compose.onNodeWithTag("channel_list_row_reading").performClick()
        for (theme in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK)) {
            val seed = if (theme == ColorThemePreset.AYU_LIGHT) Color(0xFF171717) else Color(0xFFF5F5F5)
            for (contrasting in listOf(false, true)) {
                compose.runOnIdle {
                    preset.value = theme
                    contrastingFill.value = contrasting
                }
                val layout = layoutOf("Reading", substring = true)
                val text = layout.layoutInput.text
                val label = "$theme contrastingFill=$contrasting"
                val readingInk = ensureContrast(seed, listOf(fill), MESSAGE_TEXT_CONTRAST)
                assertEquals("$label prose", readingInk, layout.layoutInput.style.color)
                val link = text.spanStyles.last { text.indexOf("https://") in it.start until it.end }.item
                assertEquals("$label link ink", linkInk, link.color)
                assertEquals("$label link underline", TextDecoration.Underline, link.textDecoration)
                assertEquals("$label link target", "https://example.test", (text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url).url)
                val red = text.spanStyles.last { text.indexOf("red") in it.start until it.end && it.item.color != Color.Unspecified }.item
                assertEquals("$label explicit IRC red", ensureContrast(Color.Red, listOf(fill), MESSAGE_TEXT_CONTRAST), red.color)
                val paper = text.spanStyles.last { text.indexOf("paper") in it.start until it.end }.item
                assertEquals("$label explicit IRC background", Color.White, paper.background)
                assertEquals("$label inherited ink on IRC background", ensureContrast(readingInk, listOf(Color.White), MESSAGE_TEXT_CONTRAST), paper.color.takeUnless { it == Color.Unspecified } ?: readingInk)
                assertEquals("$label user-count metadata", metadataInk, layoutOf("30 users").layoutInput.style.color)
            }
        }
    }

    @Test
    fun topicFilteringIsLocalAndSearchActionsNeverJoin() {
        var state by mutableStateOf(
            readyState().copy(
                popular =
                    DirectoryResult(
                        listings = listOf(ChannelListing("#reading", 30, "Among the stars"), ChannelListing("#general", 1, "Chat")),
                        loaded = true,
                    ),
            ),
        )
        val searches = mutableListOf<String>()
        var joins = 0
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state = state,
                    onQueryChange = { state = state.copy(query = it) },
                    onSearch = { searches += it },
                    onJoin = { _, _, _ -> joins++ },
                )
            }
        }

        val search = compose.onNodeWithTag("channel_list_search_field").assertIsNotFocused()
        val networkSearch = compose.onNodeWithTag("channel_list_search_network")
        search.performTextInput("the stars")
        compose.onNodeWithTag("channel_list_row_reading").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_row_general").assertDoesNotExist()
        networkSearch.assertIsNotEnabled()
        search.performImeAction()
        compose.runOnIdle { assertTrue(searches.isEmpty()) }

        search.performTextReplacement("#bitcoin")
        compose.onNodeWithTag("channel_list_join_exact").assertIsEnabled()
        search.performImeAction()
        networkSearch.performClick()

        compose.runOnIdle {
            assertEquals(listOf("#bitcoin", "#bitcoin"), searches)
            assertEquals(0, joins)
        }
    }

    @Test
    fun refreshKeepsRowsAndDirectoryAndNetworkErrorsRetryTheirOwnRequests() {
        val term = "bitcoin"
        val existing = DirectoryResult(listOf(ChannelListing("#bitcoin", 30, "Fixture")), loaded = true)
        var state by mutableStateOf(
            readyState().copy(
                query = term,
                popular = existing.copy(loading = true),
                remote = DirectorySearch(term, DirectoryResult(loading = true)),
            ),
        )
        var refreshes = 0
        var searched: String? = null
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state = state,
                    onRefresh = { refreshes++ },
                    onSearch = { searched = it },
                )
            }
        }

        compose.onAllNodesWithText("Loading channels…").assertCountEquals(2)
        compose.onNodeWithTag("channel_list_row_bitcoin").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_search_empty").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(remote = DirectorySearch(term, DirectoryResult(loaded = true))) }
        compose.onNodeWithTag("channel_list_search_empty").assertIsDisplayed()
        compose.runOnIdle {
            state =
                state.copy(
                    popular = existing.copy(error = "Directory timeout"),
                    remote = DirectorySearch(term, DirectoryResult(error = "Network search timeout")),
                )
        }
        compose.onNodeWithTag("channel_list_row_bitcoin").assertIsDisplayed()
        compose.onNodeWithText("Directory timeout").assertIsDisplayed()
        compose.onNodeWithText("Network search timeout").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_retry_popular").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertNull(searched)
        }
        compose.onNodeWithTag("channel_list_retry_remote").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(term, searched)
        }
    }

    @Test
    fun networkResultsAreDeduplicatedAndHiddenWhenTheQueryChanges() {
        var state by mutableStateOf(
            readyState().copy(
                query = "dev",
                popular = DirectoryResult(listOf(ChannelListing("#Dev[main]", 30, "Local fixture")), loaded = true),
                remote =
                    DirectorySearch(
                        "dev",
                        DirectoryResult(
                            listings = listOf(ChannelListing("#dev{main}", 30, "Duplicate"), ChannelListing("#dev-extra", 15, "Network fixture")),
                            loaded = true,
                        ),
                    ),
            ),
        )
        compose.setContent {
            MotdTheme {
                ChannelListContent(state = state, onQueryChange = { state = state.copy(query = it) })
            }
        }

        compose.onAllNodesWithTag("channel_list_row_dev{main}").assertCountEquals(1)
        compose.onNodeWithText("Duplicate", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("channel_list_row_dev-extra").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_search_field").performTextReplacement("other")
        compose.onNodeWithTag("channel_list_section_remote").assertDoesNotExist()
    }

    @Test
    fun includingSmallerChannelsShowsTheirSectionFirst() {
        var state by mutableStateOf(
            readyState().copy(
                popular = DirectoryResult(listOf(ChannelListing("#popular", 200, "")), loaded = true),
                smaller = DirectoryResult(listOf(ChannelListing("#small", 5, "")), loaded = true),
            ),
        )
        compose.setContent {
            MotdTheme {
                ChannelListContent(state = state, onIncludeSmaller = { state = state.copy(includeSmaller = it) })
            }
        }

        val small = compose.onNodeWithTag("channel_list_row_small")
        small.assertDoesNotExist()
        val popular = compose.onNodeWithTag("channel_list_row_popular").assertIsDisplayed()
        compose.onNodeWithTag("channel_list_include_smaller").performClick()
        assertTrue(small.fetchSemanticsNode().boundsInRoot.top < popular.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    fun resultRowsJoinWithoutNavigatingAndOpenConfirmedMembership() {
        var joinedChannel: Triple<String, String?, Boolean>? = null
        var opened: Long? = null
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state =
                        readyState().copy(
                            popular =
                                DirectoryResult(
                                    loaded = true,
                                    listings =
                                        listOf(
                                            ChannelListing("#open", 30, "Open fixture"),
                                            ChannelListing("#pending", 20, "Pending fixture"),
                                            ChannelListing("#joined", 10, "Joined fixture"),
                                        ),
                                ),
                            pendingChannels = setOf("#pending"),
                            joinedChannels = mapOf("#joined" to 42L),
                        ),
                    onJoin = { name, key, open -> joinedChannel = Triple(name, key, open) },
                    onOpenChat = { opened = it },
                )
            }
        }

        compose.onNodeWithTag("channel_list_row_open").performClick()
        compose.onNodeWithTag("channel_list_topic_open").assertIsDisplayed()
        compose.runOnIdle { assertNull(joinedChannel) }
        compose.onNodeWithTag("channel_list_row_join_open").assertIsEnabled().performClick()
        compose.onNodeWithTag("channel_list_row_join_pending").assertIsNotEnabled()
        compose.onNodeWithTag("channel_list_row_join_joined").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(Triple("#open", null, false), joinedChannel)
            assertEquals(42L, opened)
        }
    }

    @Test
    fun exactJoinWorksWithoutAListingAndOnlyPromptsForItsOwnKeyFailure() {
        var state by mutableStateOf(readyState().copy(query = "#[Private]", popular = DirectoryResult(loaded = true)))
        val joins = mutableListOf<Triple<String, String?, Boolean>>()
        var opened: Long? = null
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state = state,
                    onJoin = { name, key, open ->
                        joins += Triple(name, key, open)
                        state =
                            state.copy(
                                joinErrors = state.joinErrors + ("#{private}" to ChannelJoinFailure("Key required", needsKey = true)),
                            )
                    },
                    onOpenChat = { opened = it },
                )
            }
        }

        val exact = compose.onNodeWithTag("channel_list_join_exact")
        val key = compose.onNodeWithTag("channel_list_join_exact_key")
        compose.runOnIdle { state = state.copy(joinErrors = mapOf("#unrelated" to ChannelJoinFailure("Other failure", needsKey = true))) }
        compose.onNodeWithText("Other failure").assertDoesNotExist()
        key.assertDoesNotExist()
        exact.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("#[Private]", null, true)), joins) }
        compose.onNodeWithText("Key required").assertIsDisplayed()
        exact.assertIsNotEnabled()
        key.performTextInput("previous-key")

        compose.runOnIdle { state = readyState(3).copy(query = "#[Private]", joinErrors = state.joinErrors) }
        exact.assertIsNotEnabled()
        key.assertIsNotFocused().performTextInput("fixture-key")
        exact.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, joins.size)
            assertEquals("#[Private]", joins.last().first)
            assertTrue(joins.last().second == "fixture-key")
            assertTrue(joins.last().third)
        }
        exact.assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(joinedChannels = mapOf("#{private}" to 65L)) }
        key.assertDoesNotExist()
        exact.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, joins.size)
            assertEquals(65L, opened)
        }
    }

    @Test
    fun offlineCanConnectAndSwitchWhileRootCannotRequestAList() {
        val networks = listOf(network(2), network(3))
        var state by mutableStateOf(
            readyState().copy(networks = networks, networkName = networks.first().name, connState = IrcClientState.Disconnected),
        )
        var connects = 0
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state = state,
                    onConnect = { connects++ },
                    onSelectNetwork = { id -> state = readyState(id).copy(networks = networks, networkName = networks.last().name) },
                )
            }
        }

        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { state = state.copy(connectionError = "Fixture connection failure") }
        compose.onNodeWithText("Fixture connection failure").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithTag("channel_list_refresh").assertIsNotEnabled()
        val selector = compose.onNodeWithTag("channel_list_network_selector")
        selector.performClick()
        compose.onNodeWithTag("channel_list_network_option_3").performClick()
        compose.onNodeWithTag("channel_list_search_field").assertIsDisplayed().assertIsNotFocused()
        compose.runOnIdle { state = state.copy(isRoot = true) }
        selector.assertIsEnabled()
        compose.onNodeWithTag("channel_list_search_field").assertDoesNotExist()
        compose.onNodeWithTag("channel_list_refresh").assertIsNotEnabled()
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, connects) }
    }

    private fun readyState(networkId: Long = 2) = ChannelListUiState(networkId = networkId, initialized = true, connState = IrcClientState.Ready("trev", emptySet(), emptyMap()))

    private fun network(id: Long) =
        NetworkEntity(
            id = id,
            name = "Fixture $id",
            role = NetworkRole.DIRECT,
            host = "irc.example.test",
            port = 6697,
            nick = "trev",
            username = "trev",
            realname = "Fixture",
        )
}
