package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class MessageActionSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private var pixelsPerDp = 1f
    private var pickerSurface = Color.Unspecified

    private val allActionTags =
        listOf(
            "message_action_reply",
            "message_action_translate",
            "message_action_copy",
            "message_action_forward",
            "message_action_share",
            "message_action_quote",
            "message_redact",
        )

    private val allActionLabels =
        listOf(
            R.string.chat_action_reply,
            R.string.chat_action_translate,
            R.string.chat_action_copy,
            R.string.chat_action_forward,
            R.string.chat_action_share,
            R.string.chat_action_quote,
            R.string.chat_action_delete_message,
        )

    @Test
    fun selectTextInvokesTimelineSelectionCallback() {
        var requested = false
        render(canSelectText = { true }, onSelectText = { requested = true })
        compose.onNodeWithTag("message_action_select_text").performClick()
        compose.runOnIdle { assertTrue(requested) }
        compose.onNodeWithTag("message_selection").assertDoesNotExist()
        compose.onNodeWithTag("message_selection_text").assertDoesNotExist()
    }

    @Test
    fun unavailableSelectionDoesNotOfferAction() {
        var available by mutableStateOf(false)
        render(canSelectText = { available })
        compose.onNodeWithTag("message_action_select_text").assertDoesNotExist()
        compose.runOnIdle { available = true }
        compose.onNodeWithTag("message_action_select_text").assertIsDisplayed()
        compose.runOnIdle { available = false }
        compose.onNodeWithTag("message_action_select_text").assertDoesNotExist()
    }

    @Test
    fun tabsAreAbsentWithoutPersistedReactions() {
        render()

        compose.onNodeWithTag("message_action_tabs").assertDoesNotExist()
        compose.onNodeWithTag("message_more_reactions").assertIsDisplayed()
    }

    @Test
    fun reactionsTabRevealsReactorDisplayNames() {
        render(
            reactions =
                listOf(
                    ReactionChip(
                        emoji = "👍",
                        count = 2,
                        mine = false,
                        reactorDisplayNames = listOf("Alice", "bob"),
                    ),
                ),
        )

        compose.onNodeWithTag("message_action_tabs").assertIsDisplayed()
        compose.onNodeWithTag("message_reaction_reactors").assertDoesNotExist()
        compose.onNodeWithTag("message_action_tab_reactions").performClick()

        compose.onNodeWithTag("message_reaction_reactors").assertIsDisplayed()
        compose.onNodeWithText("Alice, bob", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("👍 reacted: Alice, bob").assertIsDisplayed()
    }

    @Test
    fun initialReactionsTabDoesNotOverrideTheUsersSelection() {
        var openOnReactions by mutableStateOf(true)
        render(
            reactions = listOf(ReactionChip("👍", 1, false, listOf("Alice"))),
            initialReactionsTab = { openOnReactions },
        )
        val actions = compose.onNodeWithTag("message_action_tab_actions")
        val reactions = compose.onNodeWithTag("message_action_tab_reactions")
        reactions.assertIsSelected()
        actions.performClick().assertIsSelected()
        compose.runOnIdle { openOnReactions = false }
        actions.assertIsSelected()
        compose.runOnIdle { openOnReactions = true }
        actions.assertIsSelected()
        compose.onNodeWithTag("message_reaction_reactors").assertDoesNotExist()
    }

    @Test
    fun negotiatedRedactionActionInvokesConfirmationRequest() {
        var requested = false
        render(canRedact = true, onRedact = { requested = true })

        compose.onNodeWithTag("message_redact").performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp", fontScale = 2f)
    fun compactActionsRemainAccessibleAcrossFontScales() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        fun labelLayout(
            tag: String,
            label: String,
        ): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNode(
                    hasText(label) and hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        render(
            canRedact = true,
            onTranslate = {},
            onForward = {},
            canSelectText = { true },
        )
        val translateLabel = context.getString(R.string.chat_action_translate)
        val scaledTranslateLayout = labelLayout("message_action_translate", translateLabel)
        assertEquals("The native dialog must actually use 2x fonts", 2f, scaledTranslateLayout.layoutInput.density.fontScale, 0f)

        val viewport = compose.onNodeWithTag("message_action_actions").fetchSemanticsNode().boundsInRoot
        assertEquals("The native sheet must use the actual narrow viewport", 320f, viewport.width / pixelsPerDp, 1f)
        assertTrue("The action viewport must be bounded by the short screen", viewport.height / pixelsPerDp <= 360f)
        val firstBounds = compose.onNodeWithTag(allActionTags.first()).getUnclippedBoundsInRoot()
        val lastBounds = compose.onNodeWithTag(allActionTags.last()).getUnclippedBoundsInRoot()
        assertTrue("Large labels must overflow the short viewport and remain scrollable", (lastBounds.bottom - firstBounds.top).value * pixelsPerDp > viewport.height)

        allActionTags.zip(allActionLabels).forEach { (tag, resource) ->
            val action = compose.onNodeWithTag(tag)
            action.performScrollTo().assertIsDisplayed()
            val label = context.getString(resource)
            action.assertContentDescriptionEquals(label)
            action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals(
                "Each tile has one accessible click action, with no clickable children",
                1,
                compose
                    .onAllNodes(
                        hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .size,
            )
            val layout = labelLayout(tag, label)
            assertEquals("Every complete localized label is laid out", label, layout.layoutInput.text.text)
            assertEquals("Every label uses the native 2x font scale", 2f, layout.layoutInput.density.fontScale, 0f)
            assertFalse("The complete $label label must not overflow", layout.hasVisualOverflow)
            assertTrue("The $label label must not ellipsize", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
            assertEquals("The $label layout includes its final character", label.length, layout.getLineEnd(layout.lineCount - 1))
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue("The $label touch target must be at least 48dp tall", bounds.bottom - bounds.top >= 48.dp)
            assertTrue("The $label touch target must be at least 48dp wide", bounds.right - bounds.left >= 48.dp)
            action.performClick()
        }
        val selection = compose.onNodeWithTag("message_action_select_text").performScrollTo().assertIsDisplayed()
        val selectionBounds = selection.getUnclippedBoundsInRoot()
        assertTrue(selectionBounds.bottom - selectionBounds.top >= 48.dp)
        assertTrue(selectionBounds.right - selectionBounds.left >= 48.dp)
        selection.performClick()
    }

    @Test
    fun actionBackingsLeaveLabelsUnboxedAndTabsHighlightOnlyTheCurrentSelection() {
        render(
            reactions = listOf(ReactionChip("👍", 1, false, listOf("Alice"))),
            canRedact = true,
            themePreset = ColorThemePreset.AYU_LIGHT,
        )
        val action =
            compose
                .onNodeWithTag("message_action_copy")
                .assertIsDisplayed()
                .captureToImage()
                .toPixelMap()
        val padding = ButtonDefaults.TextButtonContentPadding.calculateTopPadding().value
        val backingX = (action.width / 2 - 13f * pixelsPerDp).toInt()
        val backingY = ((padding + 16f) * pixelsPerDp).toInt()
        val plainFill = action[1, action.height / 2]
        assertNotEquals("The action icon has a subtle backing", plainFill, action[backingX, backingY])
        assertEquals("The label remains on the plain sheet, not inside a tile capsule", plainFill, action[action.width / 2, action.height - 2])

        fun tabFill(tag: String): Color {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return image[2, 2]
        }
        compose.onNodeWithTag("message_action_tab_actions").assertIsSelected()
        compose.onNodeWithTag("message_action_tab_reactions").assertIsNotSelected()
        val selectedFill = tabFill("message_action_tab_actions")
        val idleFill = tabFill("message_action_tab_reactions")
        assertNotEquals("The active tab is visibly selected", idleFill, selectedFill)
        assertNotEquals("An ordinary action backing does not impersonate a selected tab", action[backingX, backingY], selectedFill)
        compose.onNodeWithTag("message_action_tab_reactions").performClick().assertIsSelected()
        compose.onNodeWithTag("message_action_tab_actions").assertIsNotSelected()
        assertEquals("The selected fill follows the selected tab", selectedFill, tabFill("message_action_tab_reactions"))
        assertEquals("The old tab returns to its neutral fill", idleFill, tabFill("message_action_tab_actions"))
        compose.onNodeWithTag("message_reaction_reactors").assertIsDisplayed()
    }

    @Test
    fun optionalActionsStayHiddenUnlessOffered() {
        render()
        listOf("message_action_translate", "message_action_forward", "message_redact").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        val bounds =
            listOf("message_action_reply", "message_action_copy", "message_action_share", "message_action_quote")
                .map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        assertTrue(bounds.zipWithNext().all { (left, right) -> left.right <= right.left + 1.dp })
    }

    @Test
    fun serverKeepsTranslateAndCopyShareQuoteWithoutReplyOrReactions() {
        var translated = false
        render(isServerBuffer = true, onTranslate = { translated = true })
        listOf("message_action_reply", "message_more_reactions").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("message_action_translate").assertIsDisplayed().performClick()
        listOf("message_action_copy", "message_action_share", "message_action_quote").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.runOnIdle { assertTrue(translated) }
    }

    @Test
    fun translateInvokesTheOfferedCallback() {
        var translated = false
        render(onTranslate = { translated = true })
        compose.onNodeWithTag("message_action_translate").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(translated) }
    }

    @Test
    fun moreReactionsIsALauncherAndCancelNeverReacts() {
        val picked = mutableListOf<String>()
        render(onReact = { picked += it })
        compose
            .onNodeWithTag("message_more_reactions")
            .assertContentDescriptionEquals("More reactions")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher("No false expansion state") { !it.config.contains(SemanticsProperties.StateDescription) })
            .performClick()

        compose.onNodeWithTag("message_action_sheet").assertDoesNotExist()
        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose.onNodeWithTag("message_reaction_emoji_picker").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("message_reaction_emoji_grid")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("message_reaction_emoji_grid").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true).performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertTrue(picked.isEmpty()) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun moreReactionsUsesComposePickerAndPreservesEmojiSequences() {
        val picked = mutableListOf<String>()
        render(onReact = { picked += it })
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.clickEmoji("animals_nature", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("🦑"), picked) }

        compose.onNodeWithTag("reopen_message_actions").performClick()
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.pickEmojiVariant("people_body", "👍", "👍🏽")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("🦑", "👍🏽"), picked) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun moreReactionsCellSwipesScrollBothWaysWithoutMovingOrSelectingTheSheet() {
        val picked = mutableListOf<String>()
        render(onReact = { picked += it })
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.tapEmojiCategory("food_drink")
        compose.swipeEmojiBody(upward = true)
        compose.swipeEmojiBody(upward = false)
        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose.runOnIdle { assertTrue("Swiping never selects a reaction", picked.isEmpty()) }

        compose.findEmoji("animals_nature", "🦑").performTouchInput { click() }
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("🦑"), picked) }
        compose.onNodeWithTag("reopen_message_actions").performClick()
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.tapEmojiCategory("food_drink")
        compose.swipeEmojiBody(upward = true)
        compose.pickEmojiVariant("people_body", "👍", "👍🏽")
        compose.runOnIdle { assertEquals(listOf("🦑", "👍🏽"), picked) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp-night")
    fun appliedLightPickerCategoriesRenderAgainstPlatformNight() {
        render(themePreset = ColorThemePreset.LIGHT)
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.assertEmojiCategoryVisible("recents", pickerSurface)
        compose.assertEmojiCategoryVisible("food_drink", pickerSurface)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w320dp-h640dp-notnight")
    fun appliedDarkPickerCategoriesRenderAgainstPlatformDayAtLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        render(themePreset = ColorThemePreset.DARK)
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.assertEmojiCategoryVisible("recents", pickerSurface)
        compose.assertEmojiCategoryVisible("food_drink", pickerSurface)
        compose.swipeEmojiBody(upward = true)
        compose.swipeEmojiBody(upward = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun pickerRechecksSelectionPolicyBeforeDismissing() {
        var enabled by mutableStateOf(false)
        val picked = mutableListOf<String>()
        render(onReact = { picked += it }, reactionEnabled = { enabled })
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.clickEmoji("animals_nature", "🦑")

        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose
            .onNodeWithTag("message_reaction_unavailable")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle {
            assertTrue(picked.isEmpty())
            enabled = true
        }
        compose.clickEmoji("animals_nature", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("🦑"), picked) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render(
        reactions: List<ReactionChip> = emptyList(),
        canRedact: Boolean = false,
        onRedact: () -> Unit = {},
        isServerBuffer: Boolean = false,
        onTranslate: (() -> Unit)? = null,
        onForward: (() -> Unit)? = null,
        onAction: (String) -> Unit = {},
        onReact: (String) -> Unit = {},
        reactionEnabled: (String) -> Boolean = { true },
        themePreset: ColorThemePreset = ColorThemePreset.SYSTEM,
        canSelectText: () -> Boolean = { false },
        onSelectText: () -> Unit = {},
        initialReactionsTab: () -> Boolean = { false },
    ) {
        compose.setContent {
            MotdTheme(themePreset = themePreset) {
                pixelsPerDp = LocalDensity.current.density
                pickerSurface = MaterialTheme.colorScheme.surface
                val scope = rememberCoroutineScope()
                val actionState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val pickerState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var actionsOpen by remember { mutableStateOf(true) }
                var pickerOpen by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { actionsOpen = true },
                    modifier =
                        androidx.compose.ui.Modifier
                            .testTag("reopen_message_actions"),
                ) { Text("Open message actions") }
                if (pickerOpen) {
                    MessageReactionPicker(
                        sheetState = pickerState,
                        onDismiss = { pickerOpen = false },
                        onReact = onReact,
                        reactionEnabled = reactionEnabled,
                    )
                }
                if (actionsOpen) {
                    MessageActionSheet(
                        sheetState = actionState,
                        onDismiss = { actionsOpen = false },
                        onReply = { onAction("message_action_reply") },
                        onReact = onReact,
                        onMoreReactions = {
                            scope.launch {
                                actionState.hide()
                                actionsOpen = false
                                pickerOpen = true
                            }
                        },
                        reactionEnabled = reactionEnabled,
                        reactions = reactions,
                        onCopy = { onAction("message_action_copy") },
                        onQuote = { onAction("message_action_quote") },
                        onShare = { onAction("message_action_share") },
                        canRedact = canRedact,
                        onRedact = onRedact,
                        isServerBuffer = isServerBuffer,
                        onTranslate = onTranslate,
                        onForward = onForward,
                        canSelectText = canSelectText(),
                        onSelectText = onSelectText,
                        initialReactionsTab = initialReactionsTab(),
                    )
                }
            }
        }
    }
}

// General Android selection-toolbar traversal, unrelated to the Compose emoji picker.
internal fun View.descendantViews(): Sequence<View> =
    sequence {
        yield(this@descendantViews)
        if (this@descendantViews is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendantViews())
        }
    }

private fun String.emojiCodePointTag(): String = codePoints().toArray().joinToString("_") { it.toString(16) }

internal fun ComposeContentTestRule.tapEmojiCategory(
    category: String,
    tagPrefix: String = "message_reaction",
) {
    // The picker shell precedes metadata loading; off-screen LazyRow items do not exist yet.
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasTestTag("${tagPrefix}_emoji_categories")).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag("${tagPrefix}_emoji_categories")
        .performScrollToNode(hasTestTag("${tagPrefix}_emoji_category_$category"))
    onNodeWithTag("${tagPrefix}_emoji_category_$category")
        .performClick()
        .assertIsDisplayed()
        .assertIsSelected()
    onNodeWithTag("${tagPrefix}_emoji_grid").assertIsDisplayed()
}

internal fun ComposeContentTestRule.emojiScrollPosition(
    tagPrefix: String = "message_reaction",
): Float =
    onNodeWithTag("${tagPrefix}_emoji_grid")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()

internal fun ComposeContentTestRule.swipeEmojiBody(
    upward: Boolean,
    tagPrefix: String = "message_reaction",
) {
    val grid = onNodeWithTag("${tagPrefix}_emoji_grid")
    val picker = onNodeWithTag("${tagPrefix}_emoji_picker")
    val bounds = picker.fetchSemanticsNode().boundsInRoot
    val before = emojiScrollPosition(tagPrefix)
    grid.performTouchInput {
        if (upward) swipeUp() else swipeDown()
    }
    val after = emojiScrollPosition(tagPrefix)
    assertTrue("Cell swipes scroll content in the requested direction: $before -> $after", if (upward) after > before else after < before)
    assertEquals("Scrolling content does not drag its enclosing picker", bounds, picker.fetchSemanticsNode().boundsInRoot)
    picker.assertIsDisplayed()
}

internal fun ComposeContentTestRule.assertEmojiCategoryVisible(
    category: String,
    surface: Color,
    tagPrefix: String = "message_reaction",
) {
    tapEmojiCategory(category, tagPrefix)
    val categoryNode = onNodeWithTag("${tagPrefix}_emoji_category_$category")
    categoryNode.assertHasClickAction().assertIsSelected()
    val title = onNodeWithTag("${tagPrefix}_emoji_category_title").assertIsDisplayed()
    val layouts = mutableListOf<TextLayoutResult>()
    title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    val layout = layouts.single()
    assertFalse(
        "The category title remains readable at the configured font scale: " +
            "text=${layout.layoutInput.text.text}, size=${layout.size}, " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "constraints=${layout.layoutInput.constraints}, " +
            "ellipsized=${(0 until layout.lineCount).any { layout.isLineEllipsized(it) }}",
        layout.hasVisualOverflow,
    )
    val pixels = title.captureToImage().toPixelMap()
    var contrastingPixels = 0
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            if (ColorUtils.calculateContrast(pixels[x, y].toArgb(), surface.toArgb()) >= 4.5) contrastingPixels++
        }
    }
    assertTrue("The category title has visible contrasting text against the applied theme", contrastingPixels >= 8)
}

internal fun ComposeContentTestRule.findEmoji(
    category: String,
    emoji: String,
    tagPrefix: String = "message_reaction",
): SemanticsNodeInteraction {
    tapEmojiCategory(category, tagPrefix)
    val base =
        if (category == "recents") {
            emoji
        } else {
            emoji
                .codePoints()
                .filter { it !in 0x1f3fb..0x1f3ff }
                .toArray()
                .let { String(it, 0, it.size) }
        }
    val cellTag = "${tagPrefix}_emoji_cell_${base.emojiCodePointTag()}"
    onNodeWithTag("${tagPrefix}_emoji_grid").performScrollToNode(hasTestTag(cellTag))
    return onNodeWithTag(cellTag).assertIsDisplayed().assertHasClickAction()
}

internal fun ComposeContentTestRule.clickEmoji(
    category: String,
    emoji: String,
    tagPrefix: String = "message_reaction",
) {
    findEmoji(category, emoji, tagPrefix).performTouchInput { click() }
}

internal fun ComposeContentTestRule.pickEmojiVariant(
    category: String,
    base: String,
    variant: String,
    tagPrefix: String = "message_reaction",
) {
    findEmoji(category, base, tagPrefix).performTouchInput { longClick() }
    onNodeWithTag("${tagPrefix}_emoji_variants").assertIsDisplayed()
    onNodeWithTag("${tagPrefix}_emoji_variant_${variant.emojiCodePointTag()}")
        .assertIsDisplayed()
        .performTouchInput { click() }
    onNodeWithTag("${tagPrefix}_emoji_variants").assertDoesNotExist()
}
