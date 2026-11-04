package io.github.trevarj.motd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import io.github.trevarj.motd.ui.components.LinkPreviewCard
import io.github.trevarj.motd.ui.components.LinkPreviewTransitionKey
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.components.RoutedInlineMediaFixture
import io.github.trevarj.motd.ui.components.assertMediaCornerPixels
import io.github.trevarj.motd.ui.components.solidMediaResponse
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ensureContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class LinkPreviewCardUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun mediaCornerPresetsClipEveryStateAndLoadedAndSkeletonThumbnailsAndRestoreDefault() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(solidMediaResponse())
            val state = mutableStateOf(LinkPreviewTransitionKey.AVAILABLE)
            val kind = mutableStateOf(LinkPreviewKind.WEB)
            val corners = mutableStateOf(UiCornerStyle.DEFAULT)
            val fill = Color(0xFFCCDDEE)
            var skeletonFill = Color.Unspecified
            var pixelsPerDp = 1f
            compose.setContent {
                pixelsPerDp = LocalDensity.current.density
                MotdTheme(dynamicColor = false, mediaCornerStyle = corners.value) {
                    val scheme = MaterialTheme.colorScheme
                    skeletonFill = scheme.onSurfaceVariant.copy(alpha = .18f).compositeOver(fill)
                    MaterialTheme(colorScheme = scheme.copy(surfaceContainerHighest = fill)) {
                        CompositionLocalProvider(
                            LocalAutomaticRemoteMedia provides true,
                            LocalNetworkMediaHttp provides fixture.http,
                        ) {
                            Box(Modifier.width(340.dp).background(Color.Cyan).padding(16.dp)) {
                                LinkPreviewCard(
                                    preview =
                                        if (state.value == LinkPreviewTransitionKey.AVAILABLE) {
                                            LinkPreview(
                                                "https://media.invalid/article",
                                                "Title",
                                                "Description",
                                                "http://media.invalid/link-mask.png",
                                                "media.invalid",
                                                kind.value,
                                            )
                                        } else {
                                            null
                                        },
                                    loading = state.value == LinkPreviewTransitionKey.LOADING,
                                    awaiting = state.value == LinkPreviewTransitionKey.AWAITING,
                                    failed = state.value == LinkPreviewTransitionKey.FAILED,
                                    networkId = fixture.networkId,
                                    onClick = {},
                                    modifier = Modifier.testTag("shape_card"),
                                )
                            }
                        }
                    }
                }
            }
            for (
            (renderState, previewKind) in
            LinkPreviewTransitionKey.entries.map { it to LinkPreviewKind.WEB } +
                (LinkPreviewTransitionKey.AVAILABLE to LinkPreviewKind.VIDEO)
            ) {
                compose.runOnIdle {
                    state.value = renderState
                    kind.value = previewKind
                    corners.value = UiCornerStyle.DEFAULT
                }
                val thumbnail =
                    if (renderState == LinkPreviewTransitionKey.AVAILABLE) {
                        compose.onNodeWithTag(if (previewKind == LinkPreviewKind.VIDEO) "link_preview_video_thumbnail" else "link_preview_thumbnail", useUnmergedTree = true)
                    } else {
                        null
                    }
                if (thumbnail != null) {
                    compose.waitUntil(10_000) {
                        val pixels = thumbnail.captureToImage().asAndroidBitmap()
                        pixels.getPixel(pixels.width / 2, (2 * pixelsPerDp).roundToInt()) == Color.Magenta.toArgb()
                    }
                }
                val card = compose.onNodeWithTag("shape_card", useUnmergedTree = true)
                val baseline = card.captureToImage().asAndroidBitmap()
                val bounds = card.fetchSemanticsNode().boundsInRoot
                for (preset in UiCornerStyle.entries + UiCornerStyle.DEFAULT) {
                    compose.runOnIdle { corners.value = preset }
                    val label = "$renderState $previewKind $preset"
                    val radius =
                        when (preset) {
                            UiCornerStyle.DEFAULT -> 12
                            UiCornerStyle.SQUARE -> 0
                            UiCornerStyle.SOFT -> 8
                            UiCornerStyle.ROUNDED -> 24
                        }
                    val thumbnailRadius = if (preset == UiCornerStyle.DEFAULT) 8 else radius
                    val pixels = card.captureToImage().asAndroidBitmap()
                    assertMediaCornerPixels(pixels, radius * pixelsPerDp, fill, pixelsPerDp, "$label card")
                    if (thumbnail != null) {
                        assertMediaCornerPixels(thumbnail.captureToImage().asAndroidBitmap(), thumbnailRadius * pixelsPerDp, Color.Magenta, pixelsPerDp, "$label loaded thumbnail")
                    }
                    if (renderState == LinkPreviewTransitionKey.LOADING) {
                        assertMediaCornerPixels(
                            pixels,
                            thumbnailRadius * pixelsPerDp,
                            skeletonFill,
                            pixelsPerDp,
                            "$label skeleton thumbnail",
                            IntRect(
                                (8 * pixelsPerDp).roundToInt(),
                                (8 * pixelsPerDp).roundToInt(),
                                (64 * pixelsPerDp).roundToInt(),
                                (64 * pixelsPerDp).roundToInt(),
                            ),
                        )
                    }
                    assertEquals("$label unchanged bounds", bounds, card.fetchSemanticsNode().boundsInRoot)
                    if (preset == UiCornerStyle.DEFAULT) assertTrue("$label restored pixels", baseline.sameAs(pixels))
                }
            }
            assertTrue("thumbnail must really load through the controlled route", fixture.server.requestCount > 0)
            assertTrue(fixture.selectedNetworks.all { it == fixture.networkId })
        }
    }

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
