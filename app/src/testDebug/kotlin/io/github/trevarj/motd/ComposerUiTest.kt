package io.github.trevarj.motd

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColor
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColorRole
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.data.prefs.EffectSurface
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PaletteColors
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.SurfaceEffect
import io.github.trevarj.motd.data.prefs.SurfaceEffects
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import io.github.trevarj.motd.data.prefs.UiIconSet
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.ui.chat.assertEmojiCategoryVisible
import io.github.trevarj.motd.ui.chat.emojiScrollPosition
import io.github.trevarj.motd.ui.chat.findEmoji
import io.github.trevarj.motd.ui.chat.pickEmojiVariant
import io.github.trevarj.motd.ui.chat.swipeEmojiBody
import io.github.trevarj.motd.ui.chat.tapEmojiCategory
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.components.composerContainerColor
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComposerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Before
    fun attachClipboardProviderForThisSandbox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.camera"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        // Real attachment refreshes FileProvider's static roots for Robolectric's per-test cache.
        val provider = FileProvider().apply { attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test
    fun iconSetChangesComposerArtworkWithoutChangingTargetsOrActions() {
        val iconSet = mutableStateOf(UiIconSet.MATERIAL)
        var sends = 0
        compose.setContent {
            MotdTheme(dynamicColor = false, uiIconSet = iconSet.value) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Composer(
                        value = TextFieldValue("Draft"),
                        onValueChange = {},
                        onSend = { sends++ },
                        enabled = true,
                    )
                }
            }
        }
        val send = compose.onNodeWithTag("chat_composer_send").assertHasClickAction().assertIsEnabled()
        val tools = compose.onNodeWithTag("chat_composer_tools").assertHasClickAction()
        val sendBounds = send.fetchSemanticsNode().boundsInRoot
        val toolsBounds = tools.fetchSemanticsNode().boundsInRoot
        val materialSend = send.captureToImage().asAndroidBitmap()
        val materialTools = tools.captureToImage().asAndroidBitmap()
        compose.runOnIdle { iconSet.value = UiIconSet.LUCIDE }
        assertEquals(sendBounds, send.fetchSemanticsNode().boundsInRoot)
        assertEquals(toolsBounds, tools.fetchSemanticsNode().boundsInRoot)
        assertTrue("The production send button must use Lucide geometry", !materialSend.sameAs(send.captureToImage().asAndroidBitmap()))
        assertTrue("The production tool button must use Lucide geometry", !materialTools.sameAs(tools.captureToImage().asAndroidBitmap()))
        send.performClick()
        compose.runOnIdle {
            assertEquals(1, sends)
            iconSet.value = UiIconSet.MATERIAL
        }
        assertTrue(materialSend.sameAs(send.captureToImage().asAndroidBitmap()))
        assertTrue(materialTools.sameAs(tools.captureToImage().asAndroidBitmap()))
    }

    @Test
    fun inputCornerPresetsClipEveryLayoutAndRestoreDefault() {
        val layout = mutableStateOf(LayoutDensity.COMFORTABLE)
        val composerStyle = mutableStateOf(ComposerStyle.COMFORTABLE)
        val corners = mutableStateOf(UiCornerStyle.DEFAULT)
        // Explicit translucency exposes the Large field's mask over its same-color host.
        val fill = Color.Blue.copy(alpha = 0.5f)
        val backing = Color.White
        var pixelsPerDp = 1f
        var sends = 0
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            MotdTheme(
                dynamicColor = false,
                layoutDensity = layout.value,
                inputCornerStyle = corners.value,
                receivedBubbleColor = ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, fill.toArgb()),
                surfaceEffects = SurfaceEffects(surfaces = mapOf(EffectSurface.INPUT_BAR to SurfaceEffect(50, 0))),
            ) {
                // Shape snapshots must not include transient interaction ink.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize().background(backing)) {
                        key(layout.value, composerStyle.value) {
                            Composer(
                                value = TextFieldValue("Draft"),
                                onValueChange = {},
                                onSend = { sends++ },
                                enabled = true,
                                composerStyle = composerStyle.value,
                                ircFormattingEnabled = true,
                            )
                        }
                    }
                }
            }
        }

        for (density in LayoutDensity.entries) {
            for (style in ComposerStyle.entries) {
                compose.runOnIdle {
                    layout.value = density
                    composerStyle.value = style
                    corners.value = UiCornerStyle.DEFAULT
                }
                for (expanded in listOf(false, true)) {
                    if (expanded) {
                        if (style == ComposerStyle.COMPACT) compose.onNodeWithTag("chat_composer_tools").performClick()
                        val expand = compose.onNodeWithTag("chat_composer_format_expand")
                        // Comfortable keeps this action in the input row; only relocated tools scroll.
                        if (style != ComposerStyle.COMFORTABLE) expand.performScrollTo()
                        expand.assertIsDisplayed().performClick()
                        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
                    }
                    val pill = compose.onNodeWithTag("chat_composer_pill")
                    val field = compose.onNodeWithTag("chat_composer_field")
                    val send = compose.onNodeWithTag("chat_composer_send")
                    val pillBounds = pill.fetchSemanticsNode().boundsInRoot
                    val fieldBounds = field.fetchSemanticsNode().boundsInRoot
                    val sendBounds = send.fetchSemanticsNode().boundsInRoot
                    val baseline = pill.captureToImage().asAndroidBitmap()
                    val host = if (style == ComposerStyle.LARGE) fill.compositeOver(backing) else backing
                    val painted = fill.compositeOver(host)
                    for (preset in UiCornerStyle.entries + UiCornerStyle.DEFAULT) {
                        compose.runOnIdle { corners.value = preset }
                        val label = "$density $style expanded=$expanded $preset"
                        val image = pill.captureToImage().asAndroidBitmap()
                        val radius =
                            when (preset) {
                                UiCornerStyle.DEFAULT -> if (style == ComposerStyle.LARGE) 16 else 28
                                UiCornerStyle.SQUARE -> 0
                                UiCornerStyle.SOFT -> 8
                                UiCornerStyle.ROUNDED -> 28
                            }
                        assertInputCornerPixels(image, radius * pixelsPerDp, painted, host, pixelsPerDp, label)
                        assertEquals("$label pill bounds", pillBounds, pill.fetchSemanticsNode().boundsInRoot)
                        assertEquals("$label editor bounds", fieldBounds, field.fetchSemanticsNode().boundsInRoot)
                        assertEquals("$label send bounds", sendBounds, send.fetchSemanticsNode().boundsInRoot)
                        field.assertTextEquals("Draft")
                        send.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                        assertTrue("$label send touch target", sendBounds.width >= 48 * pixelsPerDp - 0.5f && sendBounds.height >= 48 * pixelsPerDp - 0.5f)
                        assertTrue("$label editor does not overlap send", !fieldBounds.overlaps(sendBounds))
                        if (style != ComposerStyle.LARGE) {
                            val tools =
                                compose
                                    .onNodeWithTag("chat_composer_tools")
                                    .assertHasClickAction()
                                    .fetchSemanticsNode()
                                    .boundsInRoot
                            assertTrue("$label tools touch target", tools.width >= 48 * pixelsPerDp - 0.5f && tools.height >= 48 * pixelsPerDp - 0.5f)
                            assertTrue("$label tools do not overlap editor", !tools.overlaps(fieldBounds))
                            if (expanded) {
                                val toolbar = compose.onNodeWithTag("chat_composer_format_toolbar").fetchSemanticsNode().boundsInRoot
                                assertTrue("$label toolbar remains inside input surface", toolbar.top >= pillBounds.top && toolbar.bottom <= pillBounds.bottom)
                                assertTrue("$label expanded surface exposes the full 28dp radius", pillBounds.height >= 56 * pixelsPerDp)
                            }
                        }
                        if (preset == UiCornerStyle.DEFAULT) assertTrue("$label restores baseline pixels", baseline.sameAs(image))
                    }
                }
                compose.onNodeWithTag("chat_composer_send").performClick()
            }
        }
        compose.runOnIdle { assertEquals(LayoutDensity.entries.size * ComposerStyle.entries.size, sends) }
    }

    @Test
    fun receivedChatColorPaintsTheComposerAcrossLayoutsAndStyles() {
        val selected = Color(0xFF123ABC)
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val style = mutableStateOf(ComposerStyle.COMFORTABLE)
        val received =
            mutableStateOf(
                ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, selected.toArgb()),
            )
        var defaultFill = Color.Unspecified
        compose.setContent {
            MotdTheme(
                layoutDensity = density.value,
                receivedBubbleColor = received.value,
            ) {
                defaultFill = MaterialTheme.colorScheme.secondaryContainer
                Composer(
                    value = TextFieldValue(),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    sendEnabled = false,
                    placeholder = "",
                    composerStyle = style.value,
                )
            }
        }

        LayoutDensity.entries.forEach { selectedDensity ->
            ComposerStyle.entries.forEach { selectedStyle ->
                compose.runOnIdle {
                    density.value = selectedDensity
                    style.value = selectedStyle
                }
                val image = compose.onNodeWithTag("chat_composer_pill").captureToImage().asAndroidBitmap()
                assertEquals(
                    "$selectedDensity $selectedStyle",
                    selected.toArgb(),
                    image.getPixel(image.width / 2, image.height / 2),
                )
            }
        }

        compose.runOnIdle { received.value = ComfortableBubbleColor() }
        val image = compose.onNodeWithTag("chat_composer_pill").captureToImage().asAndroidBitmap()
        assertEquals(defaultFill.toArgb(), image.getPixel(image.width / 2, image.height / 2))
        assertEquals(selected, composerContainerColor(selected, defaultFill))
        assertEquals(defaultFill, composerContainerColor(null, defaultFill))
    }

    @Test
    fun authoredComposerInkAndNicknameStayExactUntilReadabilityIsEnabled() {
        val adjust = mutableStateOf(false)
        var fill = Color.Unspecified
        val colors =
            PaletteColors(
                primary = "#DDDDDD",
                secondary = "#DDDDDD",
                tertiary = "#DDDDDD",
                background = "#FFFFFF",
                surface = "#FFFFFF",
                surfaceContainer = "#FFFFFF",
                onBackground = "#EEEEEE",
                onSurface = "#EEEEEE",
                onSurfaceVariant = "#EEEEEE",
                outlineVariant = "#DDDDDD",
                nicknameColors = listOf("#EFEFEF"),
            )
        compose.setContent {
            MotdTheme(
                dynamicColor = false,
                customPalette = PaletteDocument("composer-authored", "Composer authored", 1, light = colors),
                paletteMode = PaletteMode.LIGHT,
                useThemeTextColors = true,
                adjustPaletteReadability = adjust.value,
            ) {
                fill = MaterialTheme.colorScheme.secondaryContainer
                Composer(
                    value = TextFieldValue("Draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    composerStyle = ComposerStyle.COMPACT,
                    currentNick = "alice",
                )
            }
        }

        fun nickInk(): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText("alice", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts
                .single()
                .layoutInput.style.color
        }
        assertEquals(Color(0xFFEEEEEE), composerTextLayout().layoutInput.style.color)
        assertEquals(Color(0xFFEFEFEF), nickInk())
        compose.runOnIdle { adjust.value = true }
        assertNotEquals(Color(0xFFEEEEEE), composerTextLayout().layoutInput.style.color)
        assertNotEquals(Color(0xFFEFEFEF), nickInk())
        val target = minOf(MESSAGE_TEXT_CONTRAST, maxOf(contrastRatio(Color.Black, fill), contrastRatio(Color.White, fill)))
        assertTrue(contrastRatio(composerTextLayout().layoutInput.style.color, fill) >= target - 0.00001)
        assertTrue(contrastRatio(nickInk(), fill) >= target - 0.00001)
        compose.runOnIdle { adjust.value = false }
        assertEquals(Color(0xFFEEEEEE), composerTextLayout().layoutInput.style.color)
        assertEquals(Color(0xFFEFEFEF), nickInk())
    }

    @Test
    fun receivedChatColorsKeepComposerTextReadableAndFollowPalette() {
        val received = mutableStateOf(ComfortableBubbleColor())
        val theme = mutableStateOf(ColorThemePreset.LIGHT)
        var paletteColor = Color.Unspecified
        compose.setContent {
            MotdTheme(
                dynamicColor = false,
                themePreset = theme.value,
                receivedBubbleColor = received.value,
                adjustPaletteReadability = false,
            ) {
                paletteColor = MaterialTheme.colorScheme.primary
                Composer(
                    value = TextFieldValue("Draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    sendEnabled = false,
                )
            }
        }

        listOf(Color.Black, Color(0xFFFFF5D7), Color(0xFF7E5CAE)).forEach { color ->
            compose.runOnIdle {
                received.value = ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, color.toArgb())
            }
            compose.waitForIdle()
            val text = composerTextLayout()
            val strongestInk = maxOf(contrastRatio(Color.Black, color), contrastRatio(Color.White, color))
            assertTrue(
                "$color editor ink",
                contrastRatio(text.layoutInput.style.color, color) >= minOf(MESSAGE_TEXT_CONTRAST, strongestInk),
            )
        }

        compose.runOnIdle { received.value = ComfortableBubbleColor(ComfortableBubbleColorRole.PRIMARY) }
        compose.waitForIdle()
        val lightPrimary = paletteColor
        assertComposerFill(lightPrimary, "light primary")

        compose.runOnIdle { theme.value = ColorThemePreset.GRUVBOX_DARK }
        compose.waitForIdle()
        val darkPrimary = paletteColor
        assertNotEquals(lightPrimary, darkPrimary)
        assertComposerFill(darkPrimary, "dark primary")
    }

    @Test
    fun emojiPicker_opensAlongsideTheComposerInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
    }

    @Test
    fun emojiPicker_toggle_keepsTheComposerInputAvailable() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()

        // Tools swaps the picker for the compact strip and restores the keyboard.
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        // The picker stays inflated but reports no height, so closing it frees the space without
        // throwing away the populated emoji grid.
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        assertEquals(
            0,
            compose
                .onNodeWithTag("chat_composer_emoji_panel")
                .fetchSemanticsNode()
                .size.height,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun emojiPicker_reopensWithCategoryAndScrollPositionPreserved() {
        val draft = mutableStateOf(TextFieldValue("draft", TextRange(5)))
        val reply = ComposerReply("alice", "parent")
        var sends = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sends++ },
                    enabled = true,
                    reply = reply,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        val height =
            compose
                .onNodeWithTag("chat_composer_emoji_panel")
                .fetchSemanticsNode()
                .size.height
        compose.tapEmojiCategory("food_drink", tagPrefix = "chat_composer")
        compose.swipeEmojiBody(upward = true, tagPrefix = "chat_composer")
        compose.swipeEmojiBody(upward = false, tagPrefix = "chat_composer")
        compose.swipeEmojiBody(upward = true, tagPrefix = "chat_composer")
        val scrollPosition = compose.emojiScrollPosition("chat_composer")
        assertTrue("Retain an actual scrolled position, not merely the category's start", scrollPosition > 0f)
        compose.runOnIdle {
            assertEquals(TextFieldValue("draft", TextRange(5)), draft.value)
            assertEquals(0, sends)
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        assertEquals(
            0,
            compose
                .onNodeWithTag("chat_composer_emoji_panel")
                .fetchSemanticsNode()
                .size.height,
        )
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.onNodeWithTag("chat_composer_emoji_category_food_drink").assertIsSelected()
        assertEquals(scrollPosition, compose.emojiScrollPosition("chat_composer"))
        assertEquals(
            height,
            compose
                .onNodeWithTag("chat_composer_emoji_panel")
                .fetchSemanticsNode()
                .size.height,
        )
        compose.swipeEmojiBody(upward = true, tagPrefix = "chat_composer")
        compose.swipeEmojiBody(upward = false, tagPrefix = "chat_composer")
        compose.onNodeWithText("Replying to alice").assertIsDisplayed()

        compose.findEmoji("animals_nature", "🦑", tagPrefix = "chat_composer").performTouchInput { click() }
        compose.pickEmojiVariant("people_body", "👍", "👍🏽", tagPrefix = "chat_composer")
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("draft🦑👍🏽")
        compose.runOnIdle { assertEquals("Picker selections insert but never send", 0, sends) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun emojiPicker_panelHeightComplementsTheImeThroughoutTheAnimation() {
        val imeHeightPx = mutableStateOf(200)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    imeHeightPx = imeHeightPx.value,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        // Simulated keyboard fall and rise. The panel is measured from the same inset value the
        // ancestor imePadding() consumes, so their sum — the space below the input row — never moves.
        var expectedSumPx = -1
        listOf(200, 150, 100, 40, 0, 80, 160, 200, 0).forEach { currentImeHeightPx ->
            compose.runOnIdle { imeHeightPx.value = currentImeHeightPx }
            compose.waitForIdle()
            val panelHeightPx =
                compose
                    .onNodeWithTag("chat_composer_emoji_panel")
                    .fetchSemanticsNode()
                    .size
                    .height
            val sumPx = panelHeightPx + currentImeHeightPx
            if (expectedSumPx < 0) expectedSumPx = sumPx else assertEquals(expectedSumPx, sumPx)
            if (currentImeHeightPx == 0) {
                compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
                compose.onNodeWithTag("chat_composer_emoji_grid").assertIsDisplayed()
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp-night")
    fun emojiPicker_appliedThemeSwitchRefreshesColorsAndReopenPreservesSelection() {
        val theme = mutableStateOf(ColorThemePreset.LIGHT)
        var surface = Color.Unspecified
        compose.setContent {
            MotdTheme(themePreset = theme.value) {
                surface = MaterialTheme.colorScheme.surface
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = { error("Theme/toggle changed the draft") },
                    onSend = { error("Theme/toggle sent the draft") },
                    enabled = true,
                    reply = ComposerReply("alice", "parent"),
                )
            }
        }
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.assertEmojiCategoryVisible("food_drink", surface, tagPrefix = "chat_composer")

        compose.runOnIdle { theme.value = ColorThemePreset.DARK }
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_category_food_drink").assertIsSelected()
        compose.assertEmojiCategoryVisible("food_drink", surface, tagPrefix = "chat_composer")
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.onNodeWithTag("chat_composer_emoji_category_food_drink").assertIsSelected()
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("draft")
        compose.onNodeWithText("Replying to alice").assertIsDisplayed()
    }

    @Test
    fun autocompletePopup_rowIsClickableOutsideComposerBounds() {
        var picked: String? = null
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("ali"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    autocomplete = {
                        AutocompletePanel(
                            candidates = listOf("alice"),
                            onPick = { picked = it },
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("alice").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("alice", picked)
        }
    }

    @Test
    fun autocompleteRetainsExitContentAndRapidReversalKeepsInputBoundsAndFocus() {
        val showAutocomplete = mutableStateOf(true)
        val candidates = mutableStateOf(listOf("alice", "alicia"))
        val draftText = mutableStateOf("ali")
        val draft = mutableStateOf(TextFieldValue("ali", TextRange(3)))
        var picked: String? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                val panelCandidates = candidates.value
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    modifier = Modifier.offset(y = 320.dp),
                    autocomplete =
                        if (showAutocomplete.value && panelCandidates.isNotEmpty()) {
                            {
                                AutocompletePanel(
                                    candidates = panelCandidates,
                                    isCommand = panelCandidates.first().startsWith('/'),
                                    onPick = { picked = it },
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        val tags = listOf("chat_composer_field", "chat_composer_pill", "chat_composer_send")
        val bounds = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }

        fun assertInputUnchanged() {
            for (tag in tags) assertEquals(tag, bounds.getValue(tag), compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
            field.assertIsFocused().assertTextEquals(draftText.value)
            compose.runOnIdle { assertEquals(TextRange(draftText.value.length), draft.value.selection) }
        }

        // Match the consumer contract: no candidates means a null autocomplete slot.
        for (removeSlot in listOf(true, false)) {
            val first = if (removeSlot) "alice" else "/join"
            val second = if (removeSlot) "alicia" else "/away"
            val replacement = if (removeSlot) "charlie" else "/me"
            compose.mainClock.autoAdvance = true
            compose.runOnIdle {
                draftText.value = if (removeSlot) "ali" else "/a"
                draft.value = TextFieldValue(draftText.value, TextRange(draftText.value.length))
                candidates.value = listOf(first, second)
                picked = null
                showAutocomplete.value = true
            }
            compose.onNodeWithText(first).assertIsDisplayed()
            val row = compose.onNodeWithTag("autocomplete_item_0")
            val stalePick =
                row
                    .assertHasClickAction()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            val setSelection = field.fetchSemanticsNode().config[SemanticsActions.SetSelection].action!!
            compose.runOnUiThread {
                assertTrue(setSelection(0, 0, false))
                stalePick()
            }
            compose.runOnIdle {
                assertEquals(null, picked)
                assertEquals(TextFieldValue(draftText.value, TextRange.Zero), draft.value)
            }
            val end = draftText.value.length
            field.performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(end, end, false)) }
            assertInputUnchanged()
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                if (removeSlot) showAutocomplete.value = false else candidates.value = emptyList()
            }
            compose.mainClock.advanceTimeUntil(96) {
                compose.onAllNodesWithTag("autocomplete_item_0").fetchSemanticsNodes().singleOrNull()?.let {
                    SemanticsActions.OnClick !in it.config
                } == true
            }
            assertInputUnchanged()
            row.assertIsDisplayed().assertHasNoClickAction()
            val rowCoordinates = row.fetchSemanticsNode().layoutInfo.coordinates
            val fieldCoordinates = field.fetchSemanticsNode().layoutInfo.coordinates
            val rowTop = rowCoordinates.localToScreen(Offset.Zero).y
            val fieldTop = fieldCoordinates.localToScreen(Offset.Zero).y
            assertTrue("The popup tap must land on-screen above the composer", rowTop > 0f && rowTop < fieldTop)
            row.performTouchInput { click() }
            compose.runOnIdle { assertEquals(null, picked) }
            assertInputUnchanged()
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText(first).assertIsDisplayed()
                compose.onNodeWithText(second).assertIsDisplayed()
                assertInputUnchanged()
            }
            compose.runOnUiThread {
                candidates.value = listOf(replacement)
                showAutocomplete.value = true
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(replacement).assertIsDisplayed()
            compose.onNodeWithText(first).assertDoesNotExist()
            assertInputUnchanged()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithTag("autocomplete_item_0").performClick()
            compose.runOnIdle { assertEquals(replacement, picked) }
            assertInputUnchanged()

            compose.runOnUiThread {
                if (removeSlot) showAutocomplete.value = false else candidates.value = emptyList()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(replacement).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithTag("autocomplete_panel").assertDoesNotExist()
            assertInputUnchanged()
        }
    }

    @Test
    fun emojiAutocompleteRejectsAnOldClickAfterTheEditorChangesAndDuringExit() {
        val query = ":grinning_face"
        val draft = mutableStateOf(TextFieldValue(query, TextRange(query.length)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        val label = query.removePrefix(":")
        val suggestion = compose.onNodeWithText(label)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        suggestion.assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(TextFieldValue("😀", TextRange("😀".length)), draft.value)
            draft.value = TextFieldValue(query, TextRange(query.length))
        }
        compose.waitForIdle()
        val stalePick =
            suggestion
                .assertHasClickAction()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val setSelection = field.fetchSemanticsNode().config[SemanticsActions.SetSelection].action!!

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            assertTrue(setSelection(0, 0, false))
            stalePick()
        }
        compose.mainClock.advanceTimeUntil(96) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().singleOrNull()?.let {
                SemanticsActions.OnClick !in it.config
            } == true
        }
        suggestion.assertIsDisplayed().assertHasNoClickAction().performTouchInput { click() }
        compose.runOnIdle { assertEquals(TextFieldValue(query, TextRange.Zero), draft.value) }
        compose.mainClock.advanceTimeBy(1_000)
        suggestion.assertDoesNotExist()
        field.assertTextEquals(query)
    }

    @Test
    fun commandAutocomplete_describesCommandsWithoutChangingNickRowsOrPicks() {
        val command = mutableStateOf(true)
        var picked: String? = null
        compose.setContent {
            MotdTheme {
                AutocompletePanel(
                    candidates = if (command.value) listOf("/join", "/away") else listOf("alice"),
                    onPick = { picked = it },
                    isCommand = command.value,
                )
            }
        }

        compose.onNodeWithText("Join a channel", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Set or clear your away status", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("/join", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
        compose.onNodeWithTag("autocomplete_item_1").performClick()
        compose.runOnIdle { assertEquals("/away", picked) }

        compose.runOnIdle { command.value = false }
        compose.onNodeWithText("alice", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Join a channel", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Set or clear your away status", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("autocomplete_item_0").performClick()
        compose.runOnIdle { assertEquals("alice", picked) }
    }

    @Test
    fun replyWarning_isAboveReplyContentAndInput() {
        val preset = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        var fill = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset.value, adjustPaletteReadability = true) {
                fill = MaterialTheme.colorScheme.primaryContainer
                Composer(
                    value = TextFieldValue("reply"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    reply = ComposerReply("alice", "original"),
                    replyWarning = "alice isn’t currently in this channel",
                )
            }
        }

        val warning =
            compose
                .onNodeWithTag("chat_composer_reply_warning")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val reply =
            compose
                .onNodeWithText("original")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val input = compose.onNodeWithTag("chat_composer_input_row").fetchSemanticsNode().boundsInRoot
        assertTrue(warning.bottom <= reply.top)
        assertTrue(warning.bottom <= input.top)
        val sender = RuntimeEnvironment.getApplication().getString(R.string.chat_composer_replying_to, "alice")
        for (theme in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK)) {
            compose.runOnIdle { preset.value = theme }
            val bodyLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("original", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(bodyLayouts) }
            assertTrue(
                contrastRatio(
                    bodyLayouts
                        .single()
                        .layoutInput.style.color,
                    fill,
                ) >= MESSAGE_TEXT_CONTRAST,
            )
            val senderLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(sender, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(senderLayouts) }
            assertTrue(
                contrastRatio(
                    senderLayouts
                        .single()
                        .layoutInput.style.color,
                    fill,
                ) >= MESSAGE_TEXT_CONTRAST,
            )
        }
    }

    @Test
    fun replyBanner_keepsItsContentWhileSendExitRuns() {
        val replyVisible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("reply"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    reply = ComposerReply("alice", "original"),
                    replyVisible = replyVisible.value,
                )
            }
        }
        compose.onNodeWithText("original").assertIsDisplayed()

        compose.runOnUiThread { replyVisible.value = false }
        compose.mainClock.advanceTimeByFrame()

        // Send starts the exit immediately, while the old quote remains mounted for the fade.
        compose.onNodeWithText("original").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("original").assertDoesNotExist()
    }

    @Test
    fun attachmentMovesToToolsWhileTypingAndReturnsWhenDraftIsCleared() {
        val draft = mutableStateOf(TextFieldValue())
        var uploads = 0
        var attachments = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    composerStyle = ComposerStyle.COMFORTABLE,
                    showEmojiTool = false,
                    ircFormattingEnabled = true,
                    onAttachment = { attachments++ },
                    onUploadDraft = { uploads++ },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        val attachment = compose.onNodeWithTag("chat_composer_attachment")
        val expand = compose.onNodeWithTag("chat_composer_format_expand")
        val toolbar = hasAnyAncestor(hasTestTag("chat_composer_format_toolbar"))
        val inputRow = hasAnyAncestor(hasTestTag("chat_composer_input_row"))
        attachment.assertIsDisplayed().assert(inputRow)
        expand.assertDoesNotExist()

        field.performTextInput("x")
        val typedDraft = compose.runOnIdle { draft.value }
        assertEquals("x", typedDraft.text)
        attachment.assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_tools").performClick().assertIsSelected()
        attachment.assertIsDisplayed().assert(toolbar).performClick()
        compose.runOnIdle {
            assertEquals(1, attachments)
            assertEquals(typedDraft, draft.value)
        }
        val attachmentBounds = attachment.fetchSemanticsNode().boundsInRoot
        val expandBounds = expand.assert(inputRow).fetchSemanticsNode().boundsInRoot
        assertTrue("Attachment and expand keep distinct targets", !attachmentBounds.overlaps(expandBounds))
        expand.assertIsDisplayed().performClick()
        compose
            .onNodeWithTag("chat_composer_upload_draft")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, uploads)
            assertEquals(typedDraft, draft.value)
            draft.value = TextFieldValue()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        expand.assertIsDisplayed()
        attachment.assertIsDisplayed().assert(inputRow).performClick()
        compose.runOnIdle {
            assertEquals(2, attachments)
            assertEquals(TextFieldValue(), draft.value)
        }
    }

    @Test
    fun toolbarLongPressShowsHelpWithoutRunningMarkdownOrUpload() {
        val draft = mutableStateOf(TextFieldValue("**hello**", TextRange(2, 7)))
        var edits = 0
        var uploads = 0
        var attachments = 0
        var aiOpens = 0
        var sends = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = {
                        edits++
                        draft.value = it
                    },
                    onSend = { sends++ },
                    enabled = true,
                    composerStyle = ComposerStyle.LARGE,
                    ircFormattingEnabled = true,
                    onAttachment = { attachments++ },
                    onAi = { aiOpens++ },
                    onUploadDraft = { uploads++ },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 7, false) }
        compose.onNodeWithTag("chat_format_markdown").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_upload_draft").assertIsNotDisplayed()

        for (tag in listOf("chat_format_markdown", "chat_composer_upload_draft")) {
            val action = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val before = compose.runOnIdle { draft.value to edits }
            val uploadsBefore = compose.runOnIdle { uploads }
            compose.mainClock.autoAdvance = false
            action.performTouchInput { longClick() }
            compose.mainClock.advanceTimeBy(200)
            val help = compose.onNodeWithTag("${tag}_tooltip").assertIsDisplayed()
            val helpNode = help.fetchSemanticsNode()
            assertTrue(!helpNode.config.contains(SemanticsActions.OnClick))
            compose.runOnIdle {
                assertEquals(before.first, draft.value)
                assertEquals(before.second, edits)
                assertEquals(uploadsBefore, uploads)
                assertEquals(0, attachments)
                assertEquals(0, aiOpens)
                assertEquals(0, sends)
            }

            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithTag("${tag}_tooltip").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
            action.performTouchInput { click() }
            compose.runOnIdle {
                if (tag == "chat_format_markdown") {
                    val formatted = parseIrcFormatting(draft.value.text)
                    assertEquals("hello", formatted.visibleText)
                    assertTrue(formatted.runs.any { it.start == 0 && it.end == 5 && it.state.enabled(IrcTextStyle.BOLD) })
                    assertEquals(0, uploads)
                } else {
                    assertEquals(1, uploads)
                    assertEquals(before.first, draft.value)
                    assertEquals(before.second, edits)
                }
                assertEquals(0, attachments)
                assertEquals(0, aiOpens)
                assertEquals(0, sends)
            }
        }
        field.assertTextEquals("hello")
    }

    @Test
    fun nativeImagePastePreservesDraftAndSelectionWhileTextPasteReplacesSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = context.cacheDir.resolve("image-clipboard/paste.png")
        image.parentFile!!.mkdirs()
        image.writeBytes(byteArrayOf(1, 2, 3))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", image)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val original = TextFieldValue("before selected after", TextRange(7, 15))
        val draft = mutableStateOf(original)
        val received = mutableListOf<Pair<android.net.Uri, String>>()
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    onImageContent = { imageUri, mime ->
                        received += imageUri to mime
                        true
                    },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(7, 15, false) }
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", uri))
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.runOnIdle {
            assertEquals("Draft after image paste: ${draft.value}", listOf(uri to "image/png"), received)
            assertEquals(original, draft.value)
            clipboard.setPrimaryClip(ClipData.newPlainText("Text", "replacement"))
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.runOnIdle {
            assertEquals(TextFieldValue("before replacement after", TextRange(18)), draft.value)
            assertEquals(listOf(uri to "image/png"), received)
        }
        image.delete()
    }

    @Test
    fun mixedClipboardChecksEachUriAndPastesRemainingText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = context.cacheDir.resolve("image-clipboard").also { it.mkdirs() }
        val image = directory.resolve("mixed.gif").also { it.writeBytes(byteArrayOf(1)) }
        val document = directory.resolve("mixed.txt").also { it.writeText("document text") }
        val imageUri = FileProvider.getUriForFile(context, "${context.packageName}.camera", image)
        val documentUri = FileProvider.getUriForFile(context, "${context.packageName}.camera", document)
        val draft = mutableStateOf(TextFieldValue("draft", TextRange(5)))
        val received = mutableListOf<android.net.Uri>()
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    onImageContent = { uri, _ ->
                        received += uri
                        true
                    },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(5, 5, false) }
        compose.runOnIdle {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData("Mixed", arrayOf("image/gif", "text/plain"), ClipData.Item("document text", null, documentUri)).apply {
                    addItem(ClipData.Item(imageUri))
                    addItem(ClipData.Item(" plus text"))
                },
            )
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.runOnIdle {
            assertEquals("Draft after mixed paste: ${draft.value}", listOf(imageUri), received)
            assertTrue(draft.value.text.startsWith("draftdocument text"))
            assertTrue(draft.value.text.endsWith(" plus text"))
        }
        image.delete()
        document.delete()
    }

    @Test
    fun collapsedCursorOnBlankLineCanEnableFormattingBeforeTyping() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_bold").performClick()
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", formatted.visibleText)
            assertTrue(formatted.stateAtVisible(6).bold)
        }
    }

    @Test
    fun colorActionOpensAtCollapsedCursorOnBlankLine() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performScrollTo().performClick()
        compose.onNodeWithTag("chat_composer_color_sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", formatted.visibleText)
            assertEquals(IrcColor.Numeric(4), formatted.stateAtVisible(6).foreground)
        }
    }

    @Test
    fun clearFormattingIsTheOnlyStrikeoutToolbarAction() {
        val initial = "${IRC_BOLD}hello\nthere$IRC_BOLD"
        val draft = mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onAllNodesWithTag("chat_format_strike").assertCountEquals(0)
        compose.onNodeWithTag("chat_format_clear").performScrollTo().performClick()
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", formatted.visibleText)
            assertTrue(formatted.runs.all { it.state.isDefault })
        }
    }

    @Test
    fun richComposer_expandsAndFormatsSelectionWhilePlainComposerStaysPlain() {
        val draft = mutableStateOf(TextFieldValue("hello\nthere", TextRange(0, 11)))
        val formattingEnabled = mutableStateOf(true)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = formattingEnabled.value,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_format_bold").performClick()
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", formatted.visibleText)
            assertTrue(formatted.runs.all { it.state.bold })
        }
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("hello\nthere", parseIrcFormatting(draft.value.text).visibleText)
            formattingEnabled.value = false
            draft.value = TextFieldValue("plain")
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat_composer_format_expand").assertCountEquals(0)
    }

    @Test
    fun compactAndExpandedModesShareOneToolbar() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun emojiReplacesExpandedToolsAndReturnsClosed() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand rich editor").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close emoji picker").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun completedSlashCommandRendersChipWithoutChangingEditableDraftOrSend() {
        val draft = mutableStateOf(TextFieldValue())
        val formattingEnabled = mutableStateOf(true)
        var sent: String? = null
        var chipColor = Color.Unspecified
        compose.setContent {
            MotdTheme {
                chipColor = MaterialTheme.colorScheme.primary
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sent = draft.value.text },
                    enabled = true,
                    ircFormattingEnabled = formattingEnabled.value,
                )
            }
        }

        val field = compose.onNodeWithTag("chat_composer_field")
        val chip = compose.onNodeWithTag("chat_composer_command_chip", useUnmergedTree = true)
        field.performTextInput("/jo")
        chip.assertDoesNotExist()
        field.performTextInput("in")
        chip.assertIsDisplayed()
        val pixels = chip.captureToImage().asAndroidBitmap()
        assertEquals(chipColor.toArgb(), pixels.getPixel(1, pixels.height / 2))
        field.performTextInput(" ")
        chip.assertIsDisplayed()
        field.performTextInput("#motd")
        field.assertTextEquals("/join #motd")
        compose.runOnIdle {
            assertEquals("/join #motd", draft.value.text)
            assertEquals(TextRange(11), draft.value.selection)
        }
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.runOnIdle { assertEquals("/join #motd", sent) }

        field.performTextReplacement("//join #motd")
        field.assertTextEquals("//join #motd")
        chip.assertDoesNotExist()
        compose.runOnIdle { assertEquals("//join #motd", draft.value.text) }

        compose.runOnIdle {
            formattingEnabled.value = false
            draft.value = TextFieldValue("/join #motd")
        }
        field.assertTextEquals("/join #motd")
        chip.assertDoesNotExist()
    }

    @Test
    fun commandParameterHintFollowsDraftWithoutChangingEditableTextOrSend() {
        val draft = mutableStateOf(TextFieldValue())
        val commandContext = mutableStateOf<Boolean?>(true)
        var sent: String? = null
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sent = draft.value.text },
                    enabled = true,
                    ircFormattingEnabled = true,
                    composerStyle = ComposerStyle.COMPACT,
                    commandContextIsChannel = commandContext.value,
                )
            }
        }

        val field = compose.onNodeWithTag("chat_composer_field")
        val ghost = compose.onNodeWithTag("chat_composer_command_hint", useUnmergedTree = true)
        val chip = compose.onNodeWithTag("chat_composer_command_chip", useUnmergedTree = true)
        val send = compose.onNodeWithTag("chat_composer_send")

        fun assertParameterHint(
            typed: String,
            hint: String,
            inline: Boolean? = null,
        ) {
            field.assertTextEquals(typed)
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, hint))
            ghost.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val draftBounds = chip.fetchSemanticsNode().boundsInRoot
            val caret = layouts.single().getCursorRect(typed.length)
            val ghostBounds = ghost.fetchSemanticsNode().boundsInRoot
            val belowCaret = ghostBounds.top >= draftBounds.top + caret.bottom - 1f
            inline?.let { assertEquals(!it, belowCaret) }
            assertTrue(belowCaret || ghostBounds.left > draftBounds.left + caret.right)
            assertTrue(ghostBounds.right <= field.fetchSemanticsNode().boundsInRoot.right)
        }

        field.performTextInput("/ms")
        ghost.assertDoesNotExist()
        field.performTextInput("g")
        assertParameterHint("/msg", "<nick> <message>")
        val longNickDraft = "/msg motdObserverWithALongName"
        field.performTextReplacement(longNickDraft)
        assertParameterHint(longNickDraft, "<message>", inline = false)
        field.performTextReplacement("/msg")

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        assertParameterHint("/msg", "<nick> <message>", inline = true)
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 4, false) }
        ghost.assertDoesNotExist()
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(4, 4, false) }
        field.performTextInput(" alice")
        assertParameterHint("/msg alice", "<message>", inline = true)
        send.performClick()
        compose.runOnIdle { assertEquals("/msg alice", sent) }

        field.performTextInput(" hello")
        ghost.assertDoesNotExist()
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        field.assertTextEquals("/msg alice hello")
        send.performClick()
        compose.runOnIdle { assertEquals("/msg alice hello", sent) }

        field.performTextReplacement("//msg")
        ghost.assertDoesNotExist()
        field.performTextReplacement("/msg")
        assertParameterHint("/msg", "<nick> <message>", inline = true)
        compose.runOnIdle { commandContext.value = null }
        ghost.assertDoesNotExist()
        field.assertTextEquals("/msg")
    }

    @Test
    fun sendDismissesComposerTools() {
        var sends = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("hello", TextRange(5)),
                    onValueChange = {},
                    onSend = { sends++ },
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun styleToolbarsKeepActionsReachable() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        val style = mutableStateOf(ComposerStyle.COMPACT)
        val formatting = mutableStateOf(false)
        val showEmoji = mutableStateOf(false)
        val showFormatting = mutableStateOf(false)
        val attachmentEnabled = mutableStateOf(true)
        val aiEnabled = mutableStateOf(false)
        val sendEnabled = mutableStateOf(false)
        val imeHeight = mutableStateOf(200)
        var attachments = 0
        var aiOpens = 0
        var sends = 0
        var backs = 0
        var keyboardShows = 0
        var keyboardHides = 0
        var dispatcher: OnBackPressedDispatcher? = null
        var focusManager: FocusManager? = null
        val keyboard =
            object : SoftwareKeyboardController {
                override fun show() {
                    keyboardShows++
                }

                override fun hide() {
                    keyboardHides++
                }
            }
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            focusManager = LocalFocusManager.current
            BackHandler { backs++ }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                MotdTheme(dynamicColor = false) {
                    Composer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {
                            sends++
                            draft.value = TextFieldValue()
                        },
                        enabled = true,
                        sendEnabled = sendEnabled.value,
                        composerStyle = style.value,
                        currentNick = "alex",
                        showEmojiTool = showEmoji.value,
                        showFormattingTools = showFormatting.value,
                        ircFormattingEnabled = formatting.value,
                        onAttachment = if (attachmentEnabled.value) ({ attachments++ }) else null,
                        onAi = if (aiEnabled.value) ({ aiOpens++ }) else null,
                        imeHeightPx = imeHeight.value,
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithTag("chat_composer_attachment").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_tools").performClick().assertIsSelected()
        compose.onNodeWithTag("chat_composer_emoji").assertDoesNotExist()
        compose.onNodeWithTag("chat_format_bold").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_attachment").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, attachments)
            assertEquals("hello", draft.value.text)
            assertTrue(keyboardHides > 0)
            formatting.value = true
            aiEnabled.value = true
        }
        for (tag in listOf("chat_composer_attachment", "chat_composer_ai", "chat_composer_format_expand")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("chat_composer_ai").performScrollTo().performClick()
        compose
            .onNodeWithTag("chat_composer_format_expand")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 4, false) }
        compose.onNodeWithTag("chat_format_bold").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, aiOpens)
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("hello", parsed.visibleText)
            assertTrue(parsed.runs.any { it.start == 1 && it.end == 4 && it.state.enabled(IrcTextStyle.BOLD) })
            assertTrue(parsed.runs.none { (it.start < 1 || it.end > 4) && it.state.enabled(IrcTextStyle.BOLD) })
        }
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, backs)
            style.value = ComposerStyle.LARGE
            showEmoji.value = true
            showFormatting.value = true
            focusManager?.clearFocus(force = true)
        }
        field.assertIsNotFocused()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        for (tag in listOf("chat_composer_attachment", "chat_composer_ai", "chat_composer_format_expand")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
        }
        compose.onNodeWithTag("chat_composer_attachment").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, attachments)
            checkNotNull(dispatcher).onBackPressed()
        }
        compose.runOnIdle {
            assertEquals(1, backs)
            sendEnabled.value = true
        }
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals("", draft.value.text)
            checkNotNull(dispatcher).onBackPressed()
        }
        compose.runOnIdle { assertEquals(2, backs) }
        compose.onNodeWithTag("chat_format_bold").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        field.performClick()
        compose.onNodeWithTag("chat_composer_emoji").performScrollTo().performClick()
        compose.runOnIdle { imeHeight.value = 0 }
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        compose.runOnIdle { imeHeight.value = 200 }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(keyboardShows > 0)
            assertEquals(2, backs)
            style.value = ComposerStyle.COMPACT
        }
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performScrollTo().performClick()
        compose.runOnIdle { imeHeight.value = 0 }
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.runOnIdle { imeHeight.value = 200 }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.runOnIdle {
            attachmentEnabled.value = false
            aiEnabled.value = false
            formatting.value = false
            showEmoji.value = false
            showFormatting.value = false
        }
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle { style.value = ComposerStyle.LARGE }
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun colorSheet_appliesForegroundAndBackgroundAndFormattingOnlyCannotSend() {
        val draft = mutableStateOf(TextFieldValue("hello\nthere", TextRange(0, 11)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performScrollTo().performClick()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithTag("chat_color_1").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val state = parseIrcFormatting(draft.value.text).runs.single().state
            assertEquals(IrcColor.Numeric(4), state.foreground)
            assertEquals(IrcColor.Numeric(1), state.background)
        }

        compose.runOnIdle { draft.value = TextFieldValue("$IRC_BOLD$IRC_BOLD") }
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
    }

    @Test
    fun semanticVoiceActivation_startsOneLockedRecordingAndStopsWhenActive() {
        var starts = 0
        var stops = 0
        var holds = 0
        var pixelsPerDp = 0f
        val recording = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val voiceEnabled = mutableStateOf(true)
        val style = mutableStateOf(ComposerStyle.COMFORTABLE)
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            MotdTheme {
                Composer(
                    value = TextFieldValue(),
                    onValueChange = {},
                    onSend = {},
                    enabled = enabled.value,
                    voiceEnabled = voiceEnabled.value,
                    voiceRecording = recording.value,
                    composerStyle = style.value,
                    onVoiceAccessibilityStart = {
                        starts++
                        recording.value = true
                    },
                    onVoiceHoldStart = {
                        holds++
                        recording.value = true
                    },
                    onVoiceHoldStop = {
                        stops++
                        recording.value = false
                    },
                )
            }
        }
        for (selectedStyle in listOf(ComposerStyle.COMFORTABLE, ComposerStyle.COMPACT)) {
            compose.runOnIdle { style.value = selectedStyle }
            val pill = compose.onNodeWithTag("chat_composer_pill").fetchSemanticsNode().boundsInRoot
            val voice =
                compose
                    .onNodeWithTag("chat_composer_voice")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val field = compose.onNodeWithTag("chat_composer_field").fetchSemanticsNode().boundsInRoot
            val tools = compose.onNodeWithTag("chat_composer_tools").fetchSemanticsNode().boundsInRoot
            assertTrue(voice.left >= pill.left && voice.right < pill.right)
            assertTrue(voice.top >= pill.top && voice.bottom <= pill.bottom)
            assertTrue(voice.width >= 48 * pixelsPerDp - 0.5f && voice.height >= 48 * pixelsPerDp - 0.5f)
            assertTrue(!voice.overlaps(field) && !voice.overlaps(tools))
        }
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { click(Offset(1.dp.toPx(), center.y)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, starts) }

        compose
            .onNodeWithTag("chat_composer_voice")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, starts) }

        compose.onNodeWithTag("chat_composer_voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
        }

        compose.onNodeWithTag("chat_composer_voice").performTouchInput { down(Offset(1.dp.toPx(), center.y)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, holds)
            assertEquals(1, starts)
            assertEquals(2, stops)
        }

        compose.runOnIdle { enabled.value = false }
        compose
            .onNodeWithTag("chat_composer_voice")
            .assertIsNotEnabled()

        compose.runOnIdle {
            enabled.value = true
            voiceEnabled.value = false
        }
        compose.onAllNodesWithTag("chat_composer_voice").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, starts) }
    }

    private fun assertInputCornerPixels(
        image: Bitmap,
        requestedRadius: Float,
        fill: Color,
        backing: Color,
        pixelsPerDp: Float,
        label: String,
    ) {
        val radius = minOf(requestedRadius, image.width / 2f, image.height / 2f)
        val sampleSize = (10 * pixelsPerDp).roundToInt()
        val step = (2 * pixelsPerDp).roundToInt().coerceAtLeast(1)
        for (y in 0 until sampleSize step step) {
            for (x in 0 until sampleSize step step) {
                val distance = hypot((radius - x - 0.5f).coerceAtLeast(0f), (radius - y - 0.5f).coerceAtLeast(0f))
                if (radius > 0 && abs(distance - radius) < 1.5f) continue
                val expected = if (radius == 0f || distance < radius) fill.toArgb() else backing.toArgb()
                val actual = image.getPixel(image.width - 1 - x, y)
                for (shift in listOf(24, 16, 8, 0)) {
                    assertEquals(
                        "$label corner ($x, $y) channel $shift",
                        ((expected ushr shift) and 255).toDouble(),
                        ((actual ushr shift) and 255).toDouble(),
                        1.0,
                    )
                }
            }
        }
    }

    private fun assertComposerFill(
        expected: Color,
        label: String,
    ) {
        val image = compose.onNodeWithTag("chat_composer_pill").captureToImage().asAndroidBitmap()
        assertEquals(label, expected.toArgb(), image.getPixel(image.width / 2, image.height / 2))
    }

    private fun composerTextLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag("chat_composer_field", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
