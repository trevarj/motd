package io.github.trevarj.motd.ui.chat

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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

    private val allActionTags =
        listOf(
            "message_action_reply",
            "message_prepare_thread_context",
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
            R.string.agent_context_thread,
            R.string.chat_action_translate,
            R.string.chat_action_copy,
            R.string.chat_action_forward,
            R.string.chat_action_share,
            R.string.chat_action_quote,
            R.string.chat_action_delete_message,
        )

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
    fun negotiatedRedactionActionInvokesConfirmationRequest() {
        var requested = false
        render(canRedact = true, onRedact = { requested = true })

        compose.onNodeWithTag("message_redact").performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test
    fun threadContextActionInvokesPreparation() {
        var requested = false
        render(canPrepareThreadContext = true, onPrepareThreadContext = { requested = true })

        compose.onNodeWithTag("message_prepare_thread_context").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test
    fun threadContextActionStaysHiddenWithoutEligibleContext() {
        render()
        compose.onNodeWithTag("message_prepare_thread_context").assertDoesNotExist()
    }

    @Test
    fun threadContextActionStaysHiddenOnServer() {
        render(canPrepareThreadContext = true, isServerBuffer = true)
        compose.onNodeWithTag("message_prepare_thread_context").assertDoesNotExist()
    }

    @Test
    fun compactActionsUseQuarterWidthCellsAndPreserveActionOrder() {
        render(
            canPrepareThreadContext = true,
            canRedact = true,
            onTranslate = {},
            onForward = {},
        )
        assertCompactActionGeometry()
    }

    @Test
    fun partialLastRowKeepsQuarterWidthCells() {
        render(canPrepareThreadContext = true, onTranslate = {}, onForward = {})
        assertCompactActionGeometry(allActionTags.dropLast(1))
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp", fontScale = 2f)
    fun compactActionsRemainAccessibleAcrossFontScales() {
        val invoked = mutableListOf<String>()
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
            canPrepareThreadContext = true,
            canRedact = true,
            onPrepareThreadContext = { invoked += "message_prepare_thread_context" },
            onRedact = { invoked += "message_redact" },
            onTranslate = { invoked += "message_action_translate" },
            onForward = { invoked += "message_action_forward" },
            onAction = { invoked += it },
        )
        assertCompactActionGeometry()
        val threadLabel = context.getString(R.string.agent_context_thread)
        val scaledThreadLayout = labelLayout("message_prepare_thread_context", threadLabel)
        assertEquals("The native dialog must actually use 2x fonts", 2f, scaledThreadLayout.layoutInput.density.fontScale, 0f)
        assertTrue("The narrow 2x label must wrap beyond a two-line cap", scaledThreadLayout.lineCount > 2)

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
            val padding = ButtonDefaults.TextButtonContentPadding
            val completeContentHeight =
                layout.size.height / pixelsPerDp + 24f +
                    padding.calculateTopPadding().value + padding.calculateBottomPadding().value
            assertTrue("The $label tile must fit its icon and every label line", (bounds.bottom - bounds.top).value + 1f >= completeContentHeight)
            action.performClick()
        }
        compose.runOnIdle { assertEquals(allActionTags, invoked) }
    }

    @Test
    fun optionalActionsStayHiddenUnlessOffered() {
        render()
        listOf("message_prepare_thread_context", "message_action_translate", "message_action_forward", "message_redact").forEach {
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
        render(isServerBuffer = true, canPrepareThreadContext = true, onTranslate = { translated = true })
        listOf("message_action_reply", "message_prepare_thread_context", "message_more_reactions").forEach {
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
        compose.onNodeWithTag("message_reaction_emoji_grid").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true).performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertTrue(picked.isEmpty()) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun moreReactionsUsesNativePickerAndPreservesEmojiSequences() {
        val picked = mutableListOf<String>()
        render(onReact = { picked += it })
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.clickNativeEmoji("ANIMALS AND NATURE", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("🦑"), picked) }

        compose.onNodeWithTag("reopen_message_actions").performClick()
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.pickNativeEmojiVariant("SMILEYS AND EMOTIONS", "👍", "👍🏽")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("🦑", "👍🏽"), picked) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun nativePickerRechecksSelectionPolicyBeforeDismissing() {
        var enabled by mutableStateOf(false)
        val picked = mutableListOf<String>()
        render(onReact = { picked += it }, reactionEnabled = { enabled })
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.clickNativeEmoji("ANIMALS AND NATURE", "🦑")

        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose
            .onNodeWithTag("message_reaction_unavailable")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle {
            assertTrue(picked.isEmpty())
            enabled = true
        }
        compose.clickNativeEmoji("ANIMALS AND NATURE", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("🦑"), picked) }
    }

    private fun assertCompactActionGeometry(tags: List<String> = allActionTags) {
        val viewport = compose.onNodeWithTag("message_action_actions").fetchSemanticsNode().boundsInRoot
        val bounds = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        val quarterWidth = (viewport.width / pixelsPerDp - 16f) / 4f
        bounds.forEach {
            assertEquals("Every action, including the last row, occupies a quarter-width cell", quarterWidth, (it.right - it.left).value, 1f)
            assertTrue("Touch width is at least 48dp", it.right - it.left >= 48.dp)
            assertTrue("Touch height is at least 48dp", it.bottom - it.top >= 48.dp)
        }
        bounds.chunked(4).forEach { row ->
            assertTrue("Action order is left to right without overlapping tiles", row.zipWithNext().all { (left, right) -> left.right <= right.left + 1.dp })
            row.forEach { assertEquals(row.first().top.value, it.top.value, 1f) }
        }
        assertTrue("The second row starts below every tile in the first row", bounds.drop(4).all { next -> bounds.take(4).all { it.bottom <= next.top + 1.dp } })
        assertEquals("The grid has an 8dp leading inset", viewport.left / pixelsPerDp + 8f, bounds.first().left.value, 1f)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render(
        reactions: List<ReactionChip> = emptyList(),
        canRedact: Boolean = false,
        onRedact: () -> Unit = {},
        canPrepareThreadContext: Boolean = false,
        onPrepareThreadContext: () -> Unit = {},
        isServerBuffer: Boolean = false,
        onTranslate: (() -> Unit)? = null,
        onForward: (() -> Unit)? = null,
        onAction: (String) -> Unit = {},
        onReact: (String) -> Unit = {},
        reactionEnabled: (String) -> Boolean = { true },
    ) {
        compose.setContent {
            MotdTheme {
                pixelsPerDp = LocalDensity.current.density
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
                        canPrepareThreadContext = canPrepareThreadContext,
                        onPrepareThreadContext = onPrepareThreadContext,
                        isServerBuffer = isServerBuffer,
                        onTranslate = onTranslate,
                        onForward = onForward,
                    )
                }
            }
        }
    }
}

// Exercise only attached Android Views and public accessibility/click/scroll APIs.
internal fun View.nativeEmojiViews(): Sequence<View> =
    sequence {
        yield(this@nativeEmojiViews)
        if (this@nativeEmojiViews is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).nativeEmojiViews())
        }
    }

private fun View.nativeEmojiVisible(): Boolean = isAttachedToWindow && isShown && width > 0 && height > 0 && getGlobalVisibleRect(Rect())

// Pseudolocalized resources can wrap labels in bidi controls; never normalize emoji code points.
private fun View.nativeEmojiDescription(): String? =
    contentDescription?.toString()?.trim {
        it == '\u061c' || it == '\u200e' || it == '\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
    }

private fun ComposeContentTestRule.nativeEmojiPicker(): EmojiPickerView {
    var picker: EmojiPickerView? = null
    waitUntil(10_000) {
        runOnIdle {
            picker =
                WindowInspector
                    .getGlobalWindowViews()
                    .asSequence()
                    .flatMap { it.nativeEmojiViews() }
                    .filterIsInstance<EmojiPickerView>()
                    .singleOrNull { it.nativeEmojiVisible() }
            picker?.nativeEmojiViews()?.any {
                it.nativeEmojiVisible() && it.isClickable && it.nativeEmojiDescription()?.endsWith(" emoji") == true
            } == true
        }
    }
    return checkNotNull(picker)
}

internal fun ComposeContentTestRule.findNativeEmoji(
    category: String,
    emoji: String,
): View {
    val picker = nativeEmojiPicker()
    val header =
        runOnIdle {
            picker
                .nativeEmojiViews()
                .filterIsInstance<RecyclerView>()
                .single {
                    (it.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.HORIZONTAL
                }.also { it.scrollToPosition(0) }
        }
    waitForIdle()
    while (true) {
        val label =
            runOnIdle {
                header.nativeEmojiViews().firstOrNull {
                    it.nativeEmojiVisible() && it.nativeEmojiDescription() == category
                }
            }
        if (label != null) {
            runOnIdle {
                var item: View = label
                while (!item.isClickable) item = item.parent as? View ?: error("Native category has no clickable ancestor")
                assertTrue(item.nativeEmojiVisible())
                assertTrue(item.performClick())
            }
            break
        }
        val direction = if (header.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val previousOffset =
            runOnIdle {
                assertTrue(
                    "Native category $category absent: ${header.nativeEmojiViews().mapNotNull { it.nativeEmojiDescription() }.toList()}",
                    header.canScrollHorizontally(direction),
                )
                header.computeHorizontalScrollOffset().also {
                    header.scrollBy(direction * (header.width * 3 / 4).coerceAtLeast(1), 0)
                    header.requestLayout()
                }
            }
        waitForIdle()
        runOnIdle {
            assertTrue("Native category header did not scroll", header.computeHorizontalScrollOffset() != previousOffset)
        }
    }
    val body =
        runOnIdle {
            picker.nativeEmojiViews().filterIsInstance<RecyclerView>().single {
                (it.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.VERTICAL
            }
        }
    while (true) {
        waitForIdle()
        waitUntil(10_000) {
            runOnIdle {
                val cells = body.nativeEmojiViews().filter { it.nativeEmojiVisible() && it.isClickable }.toList()
                cells.isNotEmpty() && cells.all { it.nativeEmojiDescription()?.endsWith(" emoji") == true }
            }
        }
        val cell =
            runOnIdle {
                body.nativeEmojiViews().firstOrNull {
                    it.nativeEmojiVisible() && it.isClickable && it.nativeEmojiDescription() == "$emoji emoji"
                }
            }
        if (cell != null) return cell
        val previousOffset =
            runOnIdle {
                assertTrue(
                    "$emoji absent from native $category cells: ${body.nativeEmojiViews().mapNotNull { it.contentDescription }.toList()}",
                    body.canScrollVertically(1),
                )
                body.computeVerticalScrollOffset().also {
                    body.scrollBy(0, (body.height * 3 / 4).coerceAtLeast(1))
                    body.requestLayout()
                }
            }
        waitForIdle()
        runOnIdle {
            assertTrue("Native emoji body did not scroll", body.computeVerticalScrollOffset() > previousOffset)
        }
    }
}

internal fun ComposeContentTestRule.clickNativeEmoji(
    category: String,
    emoji: String,
) {
    val cell = findNativeEmoji(category, emoji)
    runOnIdle {
        assertTrue("The selected native $emoji cell is attached and visible", cell.nativeEmojiVisible())
        assertTrue(cell.performClick())
    }
    waitForIdle()
}

internal fun ComposeContentTestRule.pickNativeEmojiVariant(
    category: String,
    base: String,
    variant: String,
) {
    val cell = findNativeEmoji(category, base)
    runOnIdle {
        assertTrue("The native $base cell has a real long-press variant listener", cell.isLongClickable)
        assertTrue(cell.performLongClick())
    }
    var variantCell: View? = null
    waitUntil(10_000) {
        runOnIdle {
            val popup = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
            variantCell =
                popup?.takeIf { it.isShowing }?.contentView?.nativeEmojiViews()?.firstOrNull {
                    it.nativeEmojiVisible() && it.isClickable && it.nativeEmojiDescription() == "$variant emoji"
                }
            variantCell != null
        }
    }
    runOnIdle {
        val popup = checkNotNull(shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow)
        assertTrue(popup.isShowing)
        assertTrue(checkNotNull(variantCell).performClick())
        assertFalse("Choosing a real variant dismisses its native popup", popup.isShowing)
    }
    waitForIdle()
}
