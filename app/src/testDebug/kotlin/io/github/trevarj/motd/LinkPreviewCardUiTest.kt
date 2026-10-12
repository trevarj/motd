package io.github.trevarj.motd

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import io.github.trevarj.motd.ui.components.LinkPreviewCard
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
class LinkPreviewCardUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun titlesDescriptionsAndTextExcerptsFollowTextColorChoiceWhileSiteAndFailureStaySemantic() {
        val preset = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val kind = mutableStateOf(LinkPreviewKind.TEXT)
        val contrastingFill = mutableStateOf(false)
        val urlTitle = mutableStateOf(false)
        val failed = mutableStateOf(false)
        val useThemeTextColors = mutableStateOf(false)
        val url = "https://example.test/note"
        var fill = Color.Unspecified
        var siteInk = Color.Unspecified
        var errorInk = Color.Unspecified
        var paletteReadingInk = Color.Unspecified
        var failedLabel = ""
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset.value, useThemeTextColors = useThemeTextColors.value) {
                val scheme = MaterialTheme.colorScheme
                paletteReadingInk = scheme.onBackground
                val cardColor =
                    if (contrastingFill.value) {
                        if (preset.value == ColorThemePreset.AYU_LIGHT) Color(0xFF182836) else Color(0xFFF4E3D1)
                    } else {
                        scheme.surfaceContainerHighest
                    }
                fill = cardColor.compositeOver(scheme.background)
                siteInk = scheme.primary
                errorInk = scheme.error
                failedLabel =
                    androidx.compose.ui.res
                        .stringResource(R.string.chat_link_preview_failed)
                MaterialTheme(colorScheme = scheme.copy(surfaceContainerHighest = cardColor)) {
                    LinkPreviewCard(
                        preview =
                            if (failed.value) {
                                null
                            } else {
                                LinkPreview(url, if (urlTitle.value) null else "Reading title", "Reading description\nsecond line", null, "example.test", kind.value)
                            },
                        loading = false,
                        failed = failed.value,
                        onClick = {},
                    )
                }
            }
        }

        fun layoutOf(text: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        for (theme in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK)) {
            for (themed in listOf(false, true)) {
                for (previewKind in LinkPreviewKind.entries) {
                    for (contrasting in listOf(false, true)) {
                        compose.runOnIdle {
                            preset.value = theme
                            useThemeTextColors.value = themed
                            kind.value = previewKind
                            contrastingFill.value = contrasting
                            urlTitle.value = previewKind == LinkPreviewKind.FILE
                            failed.value = false
                        }
                        compose.waitForIdle()
                        val seed =
                            when {
                                themed -> paletteReadingInk
                                theme == ColorThemePreset.AYU_LIGHT -> Color(0xFF171717)
                                else -> Color(0xFFF5F5F5)
                            }
                        val title = layoutOf(if (previewKind == LinkPreviewKind.FILE) url else "Reading title")
                        val description = layoutOf("Reading description\nsecond line")
                        val label = "$theme themed=$themed $previewKind contrastingFill=$contrasting"
                        val readingInk = ensureContrast(seed, listOf(fill), MESSAGE_TEXT_CONTRAST)
                        assertEquals("$label title", readingInk, title.layoutInput.style.color)
                        assertEquals("$label description", readingInk, description.layoutInput.style.color)
                        assertEquals("$label site metadata", siteInk, layoutOf("example.test").layoutInput.style.color)
                        assertEquals("$label text excerpt font", if (previewKind == LinkPreviewKind.TEXT) FontFamily.Monospace else FontFamily.Default, description.layoutInput.style.fontFamily)
                        if (previewKind == LinkPreviewKind.TEXT) compose.onNodeWithTag("link_preview_text_body", useUnmergedTree = true).assertIsDisplayed()
                    }
                }
                compose.runOnIdle { failed.value = true }
                assertEquals("$theme themed=$themed failure status", errorInk, layoutOf(failedLabel).layoutInput.style.color)
            }
        }
    }

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
