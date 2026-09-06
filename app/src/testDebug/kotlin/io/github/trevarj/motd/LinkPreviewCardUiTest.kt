package io.github.trevarj.motd

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import io.github.trevarj.motd.ui.components.LinkPreviewCard
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
class LinkPreviewCardUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun text_preview_exposes_a_dedicated_body() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                LinkPreviewCard(
                    preview = LinkPreview("https://example.test/note.txt", "note.txt", "line one\nline two", null, "example.test", LinkPreviewKind.TEXT),
                    loading = false,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithTag("link_preview_text_body", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun awaiting_preview_fetches_once_on_tap() {
        var requests = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                LinkPreviewCard(
                    preview = null,
                    loading = false,
                    awaiting = true,
                    onClick = { requests++ },
                )
            }
        }

        compose
            .onNodeWithTag("link_preview_awaiting")
            .assertHasClickAction()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .performTouchInput { click() }
        assertEquals(1, requests)
    }

    @Test fun loading_preview_is_not_clickable() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                LinkPreviewCard(preview = null, loading = true, onClick = {})
            }
        }

        compose.onNodeWithTag("link_preview_awaiting").assertDoesNotExist()
        compose.onNodeWithTag("link_preview_loading").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test fun failedPreviewExposesStableRetryAction() {
        var retries = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                LinkPreviewCard(preview = null, loading = false, failed = true, onClick = { retries++ })
            }
        }

        compose.onNodeWithTag("link_preview_failed").assertIsDisplayed().performTouchInput { click() }
        assertEquals(1, retries)
    }

    @Test fun wikipedia_preview_exposes_its_article_extract() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                LinkPreviewCard(
                    preview =
                        LinkPreview(
                            "https://en.wikipedia.org/wiki/Alan_Turing",
                            "Alan Turing",
                            "Alan Turing was an English mathematician and computer scientist.",
                            "https://upload.wikimedia.org/turing.jpg",
                            "Wikipedia",
                            LinkPreviewKind.WIKIPEDIA,
                        ),
                    loading = false,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithTag("link_preview_wikipedia_body", useUnmergedTree = true).assertIsDisplayed()
    }
}
