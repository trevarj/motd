package io.github.trevarj.motd.ui.channellist

import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ensureContrast
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
    fun directoryDescriptionsUseNeutralInkOnListFillWithoutChangingLinksOrUserCount() {
        val preset = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val contrastingFill = mutableStateOf(false)
        val topic = "Reading https://example.test \u000304red\u000f \u000399,00paper\u000f"
        var fill = Color.Unspecified
        var linkInk = Color.Unspecified
        var metadataInk = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset.value) {
                val scheme = MaterialTheme.colorScheme
                val rowColor =
                    if (contrastingFill.value) {
                        if (preset.value == ColorThemePreset.AYU_LIGHT) Color(0xFF182836) else Color(0xFFF4E3D1)
                    } else {
                        scheme.surface
                    }
                MaterialTheme(colorScheme = scheme.copy(surface = rowColor)) {
                    fill = ListItemDefaults.colors().containerColor.compositeOver(scheme.background)
                    linkInk = ensureContrast(scheme.primary, listOf(fill), MESSAGE_TEXT_CONTRAST)
                    metadataInk = scheme.onSurfaceVariant
                    ChannelListContent(
                        state =
                            ChannelListUiState(
                                networkId = 2,
                                initialized = true,
                                connState = IrcClientState.Ready("trev", emptySet(), emptyMap()),
                                loaded = true,
                                listings = listOf(ChannelListing("#reading", 30, topic)),
                            ),
                        onBack = {},
                        onQueryChange = {},
                        onSearch = {},
                        onJoin = {},
                    )
                }
            }
        }

        fun layoutOf(
            value: String,
            substring: Boolean = false,
        ): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value, substring = substring, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
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
                assertEquals("$label link target", "https://example.test", (text.getLinkAnnotations(0, text.length).single().item as androidx.compose.ui.text.LinkAnnotation.Url).url)
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
