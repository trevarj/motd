package io.github.trevarj.motd.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.emoji2.emojipicker.EmojiPickerView
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcEditorDocument
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.chat.COMMAND_HINTS
import io.github.trevarj.motd.ui.chat.EmojiSearchEntry
import io.github.trevarj.motd.ui.chat.messageFormattingRange
import io.github.trevarj.motd.ui.chat.searchSystemEmojis
import io.github.trevarj.motd.ui.chat.systemEmojiSearchEntries
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ensureContrast
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.items as lazyItems

data class ComposerReply(
    val sender: String,
    val text: String,
)

internal enum class ComposerPanel { NONE, AUTOCOMPLETE, EMOJI }

internal fun composerPanel(
    showEmoji: Boolean,
    hasAutocomplete: Boolean,
): ComposerPanel =
    when {
        showEmoji -> ComposerPanel.EMOJI
        hasAutocomplete -> ComposerPanel.AUTOCOMPLETE
        else -> ComposerPanel.NONE
    }

internal fun composerToolsAvailable(
    showEmojiTool: Boolean,
    showFormattingTools: Boolean,
    ircFormattingEnabled: Boolean,
): Boolean = showEmojiTool || (showFormattingTools && ircFormattingEnabled)

internal fun composerToolsRotation(open: Boolean): Float = if (open) 45f else 0f

internal fun autocompletePopupPosition(
    anchorBounds: IntRect,
    popupContentSize: IntSize,
    layoutDirection: LayoutDirection,
): IntOffset =
    IntOffset(
        x =
            if (layoutDirection == LayoutDirection.Ltr) {
                anchorBounds.left
            } else {
                anchorBounds.right - popupContentSize.width
            },
        y = anchorBounds.top - popupContentSize.height,
    )

private object AutocompletePopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = autocompletePopupPosition(anchorBounds, popupContentSize, layoutDirection)
}

/**
 * The picker has two visual phases. While [OPEN], it fills the space released by the IME. While
 * [RESTORING_IME], that same space shrinks as the IME returns, so the composer row stays put.
 */
internal enum class EmojiPickerPhase { OPEN, RESTORING_IME }

internal data class EmojiPickerSession(
    val capturedImeHeightPx: Int,
    val restoresKeyboard: Boolean,
    val phase: EmojiPickerPhase = EmojiPickerPhase.OPEN,
)

internal fun openEmojiPickerSession(
    imeHeightPx: Int,
    lastVisibleImeHeightPx: Int,
    inputFocused: Boolean,
    compactPickerHeightPx: Int,
): EmojiPickerSession {
    val visibleImeHeightPx = imeHeightPx.coerceAtLeast(0)
    val rememberedImeHeightPx = lastVisibleImeHeightPx.coerceAtLeast(0)
    val replacesKeyboard = visibleImeHeightPx > 0 || inputFocused && rememberedImeHeightPx > 0
    return EmojiPickerSession(
        capturedImeHeightPx =
            when {
                replacesKeyboard -> maxOf(visibleImeHeightPx, rememberedImeHeightPx)
                else -> compactPickerHeightPx.coerceAtLeast(0)
            },
        restoresKeyboard = replacesKeyboard,
    )
}

internal fun closeEmojiPickerSession(session: EmojiPickerSession): EmojiPickerSession? = if (session.restoresKeyboard) session.copy(phase = EmojiPickerPhase.RESTORING_IME) else null

internal fun reopenEmojiPickerSession(session: EmojiPickerSession): EmojiPickerSession = session.copy(phase = EmojiPickerPhase.OPEN)

/**
 * As the consumed IME inset falls, this complementary height grows by the same amount, keeping the
 * input row stable throughout the keyboard-to-picker handoff.
 */
internal fun emojiPickerReplacementHeight(
    capturedImeHeightPx: Int,
    currentImeHeightPx: Int,
): Int = (capturedImeHeightPx.coerceAtLeast(0) - currentImeHeightPx.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * The composer's ancestor consumes the navigation-bar inset before applying `imePadding()`, so only
 * the part of the keyboard above the navigation bar actually moves the input row. Sampling this in
 * the measure phase — the same phase `imePadding()` reads in — is what keeps
 * `imePadding + panel height == captured` on every frame of the IME animation.
 */
internal fun imeContentHeightPx(
    imeBottomPx: Int,
    navigationBarsBottomPx: Int,
): Int = (imeBottomPx - navigationBarsBottomPx).coerceAtLeast(0)

/**
 * One frame of the residual collapse. A keyboard that comes back shorter than the captured height
 * leaves a strip below the input row; stepping the captured height down to the live IME height
 * animates that strip away instead of snapping the timeline when the session is finally dropped.
 */
internal fun collapseCapturedImeHeightPx(
    capturedImeHeightPx: Int,
    currentImeHeightPx: Int,
    remainingFrames: Int,
): Int {
    val floorPx = currentImeHeightPx.coerceAtLeast(0)
    val capturedPx = capturedImeHeightPx.coerceAtLeast(0)
    if (capturedPx <= floorPx || remainingFrames <= 1) return floorPx
    val stepPx = (capturedPx - floorPx + remainingFrames - 1) / remainingFrames
    return (capturedPx - stepPx).coerceAtLeast(floorPx)
}

/**
 * Follows the animated IME inset outside composition. Heights are snapshot-backed so the restore
 * handoff can await them without depending on a recomposition that may legitimately be skipped.
 *
 * [lastVisibleImeHeightPx] deliberately tracks the last height the keyboard actually *rested* at
 * rather than the largest one ever seen: rotation, a keyboard switch or a suggestion strip all make
 * the resting height shrink, and capturing a stale maximum leaves the panel with residual height.
 */
internal class ImeInsetTracker(
    private val settledFrameCount: Int = IME_SETTLED_FRAME_COUNT,
) {
    var currentImeHeightPx by mutableIntStateOf(0)
        private set

    var lastVisibleImeHeightPx by mutableIntStateOf(0)
        private set

    /** Bumped whenever a height settles, so a restore can await the *next* settle specifically. */
    var settleGeneration by mutableIntStateOf(0)
        private set

    private var candidateHeightPx = 0
    private var stableFrames = settledFrameCount

    /** True once the current height has held still long enough to count as a resting position. */
    val settled: Boolean get() = stableFrames >= settledFrameCount

    fun update(currentHeightPx: Int): Int {
        val heightPx = currentHeightPx.coerceAtLeast(0)
        currentImeHeightPx = heightPx
        if (heightPx != candidateHeightPx) {
            candidateHeightPx = heightPx
            stableFrames = 1
            return heightPx
        }
        if (stableFrames < settledFrameCount) {
            stableFrames++
            if (stableFrames == settledFrameCount) {
                if (heightPx > 0) lastVisibleImeHeightPx = heightPx
                settleGeneration++
            }
        }
        return heightPx
    }
}

private const val EMOJI_IME_RESTORE_TIMEOUT_MILLIS = 1_500L
private const val IME_SETTLED_FRAME_COUNT = 3
private const val IME_RESTORE_REFOCUS_FRAMES = 6
private const val IME_RESIDUAL_COLLAPSE_FRAMES = 8
private const val IME_FOLLOW_FRAME_LIMIT = 240
private val COMPACT_EMOJI_PICKER_HEIGHT = 250.dp

internal enum class VoiceGestureTarget { NONE, CANCEL, LOCK }

private const val VOICE_RECORD_HOLD_DELAY_MILLIS = 500L

// Keep the accidental-touch guard even when a device configures a shorter long-press timeout.
internal fun voiceRecordHoldDelay(longPressTimeoutMillis: Long): Long = maxOf(VOICE_RECORD_HOLD_DELAY_MILLIS, longPressTimeoutMillis)

internal fun voiceGestureTarget(
    holdActivated: Boolean,
    pointerPressed: Boolean,
    deltaX: Float,
    deltaY: Float,
    cancelThreshold: Float,
    lockThreshold: Float,
): VoiceGestureTarget =
    when {
        !holdActivated || !pointerPressed -> VoiceGestureTarget.NONE
        deltaY <= -lockThreshold && -deltaY >= -deltaX -> VoiceGestureTarget.LOCK
        deltaX <= -cancelThreshold -> VoiceGestureTarget.CANCEL
        else -> VoiceGestureTarget.NONE
    }

/** Modern chat composer with embedded tools and a stable, separate primary send action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = true,
    reply: ComposerReply? = null,
    replyVisible: Boolean = reply != null,
    replyWarning: String? = null,
    onCancelReply: () -> Unit = {},
    placeholder: String = stringResource(R.string.chat_composer_placeholder),
    showEmojiTool: Boolean = true,
    showFormattingTools: Boolean = true,
    onAttachment: (() -> Unit)? = null,
    onAi: (() -> Unit)? = null,
    onUploadDraft: (() -> Unit)? = onAttachment,
    voiceEnabled: Boolean = false,
    voiceRecording: Boolean = false,
    onVoiceHoldStart: () -> Unit = {},
    onVoiceAccessibilityStart: () -> Unit = {},
    onVoiceHoldStop: () -> Unit = {},
    onVoiceHoldCancel: () -> Unit = {},
    onVoiceLock: () -> Unit = {},
    // Test seam only. Production leaves this null so the panel samples the real window insets in the
    // measure phase, in lock-step with the ancestor `imePadding()`; a composition-phase value would
    // always trail measure by one frame of keyboard travel and resize the timeline viewport.
    imeHeightPx: Int? = null,
    // Window bounds of the text field, reported on every layout pass. The chat screen flies a
    // sent bubble out of exactly this rect, so it must be the field itself, not the whole panel.
    onFieldPositioned: (Rect) -> Unit = {},
    // Window origin of the draft text itself (inside the field's decoration padding). The morph
    // send animation pins its stand-in line to this point so the typed glyphs never visibly move.
    onFieldTextPositioned: (Offset) -> Unit = {},
    autocomplete: (@Composable () -> Unit)? = null,
    knownNicks: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    ircFormattingEnabled: Boolean = false,
) {
    val initialEditor = remember { IrcEditorDocument.fromRaw(value.text, value.selection.start, value.selection.end) }
    val textFieldState =
        rememberTextFieldState(
            initialText = initialEditor.first.text,
            initialSelection = TextRange(initialEditor.second.first, initialEditor.second.last),
        )
    var editorDocument by remember { mutableStateOf(initialEditor.first) }
    var lastEmittedRaw by remember { mutableStateOf(value.text) }
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(value.text, value.selection) {
        if (value.text != lastEmittedRaw) {
            val external = IrcEditorDocument.fromRaw(value.text, value.selection.start, value.selection.end)
            editorDocument = external.first
            lastEmittedRaw = value.text
            textFieldState.edit {
                replace(0, length, external.first.text)
                selection = TextRange(external.second.first, external.second.last)
            }
        }
    }
    LaunchedEffect(textFieldState) {
        var previous = TextFieldValue(textFieldState.text.toString(), textFieldState.selection)
        snapshotFlow { TextFieldValue(textFieldState.text.toString(), textFieldState.selection) }.collect { current ->
            var nextDocument = editorDocument
            if (current.text != nextDocument.text) nextDocument = nextDocument.replaceText(current.text)
            if (current.selection != previous.selection || current.text != previous.text) {
                nextDocument = nextDocument.moveCaret(current.selection.start)
            }
            editorDocument = nextDocument
            val raw = nextDocument.toRawValue(current.selection.start, current.selection.end)
            val outgoing = TextFieldValue(raw.text, TextRange(raw.selectionStart, raw.selectionEnd))
            lastEmittedRaw = raw.text
            if (outgoing != latestValue) latestOnValueChange(outgoing)
            previous = current
        }
    }
    val editorValue = TextFieldValue(textFieldState.text.toString(), textFieldState.selection)

    var emojiPickerSession by remember { mutableStateOf<EmojiPickerSession?>(null) }
    var toolsOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var colorSheetVisible by remember { mutableStateOf(false) }
    var restoreFocusAfterColor by remember { mutableStateOf(false) }
    var colorSelection by remember { mutableStateOf(TextRange.Zero) }
    var selectedForeground by remember { mutableStateOf<Int?>(null) }
    var selectedBackground by remember { mutableStateOf<Int?>(null) }
    val hasDraftText = plainIrcText(editorValue.text).isNotEmpty()
    val showToolsButton = composerToolsAvailable(showEmojiTool, showFormattingTools, ircFormattingEnabled)
    val toolsSurfaceVisible = toolsOpen || ircFormattingEnabled && expanded
    // Toolbar touches can hide platform selection handles before their click callback runs. Retain
    // the latest field-owned visible range so formatting still targets the user's selection.
    var toolbarSelection by remember { mutableStateOf(editorValue.selection) }
    LaunchedEffect(editorValue.selection, inputFocused, toolsSurfaceVisible) {
        if (inputFocused || !toolsSurfaceVisible) toolbarSelection = editorValue.selection
    }
    val toolsRotation by
        animateFloatAsState(
            targetValue = composerToolsRotation(toolsSurfaceVisible),
            animationSpec = MotdMotion.microFadeIn,
            label = "composer_tools_rotation",
        )
    LaunchedEffect(showToolsButton) {
        if (!showToolsButton) toolsOpen = false
    }
    val editorDensity = LocalDensity.current
    val expandedHeight =
        (
            minOf(
                with(editorDensity) { (LocalWindowInfo.current.containerSize.height * 0.4f).toDp() },
                360.dp,
            ) - 68.dp
        ).coerceAtLeast(148.dp)

    fun selectedRange(): TextRange? {
        val allowed = if (ircFormattingEnabled) messageFormattingRange(editorValue.text) else null
        val selection = editorValue.selection
        val allowedEnd = allowed?.last?.plus(1) ?: return null
        return selection.takeIf {
            if (it.collapsed) it.start in allowed.first..allowedEnd else it.min >= allowed.first && it.max <= allowedEnd
        }
    }

    fun publishDocument(
        next: IrcEditorDocument,
        selection: TextRange = editorValue.selection,
    ) {
        val boundedSelection =
            TextRange(
                selection.start.coerceIn(0, next.text.length),
                selection.end.coerceIn(0, next.text.length),
            )
        editorDocument = next
        if (next.text != editorValue.text) {
            textFieldState.edit {
                replace(0, length, next.text)
                this.selection = boundedSelection
            }
        }
        val raw = next.toRawValue(boundedSelection.start, boundedSelection.end)
        lastEmittedRaw = raw.text
        latestOnValueChange(TextFieldValue(raw.text, TextRange(raw.selectionStart, raw.selectionEnd)))
    }

    fun applyEditorValue(next: TextFieldValue) {
        textFieldState.edit {
            replace(0, length, next.text)
            selection = next.selection
        }
    }

    fun openColorSheet(
        currentDocument: IrcEditorDocument,
        selection: TextRange,
    ) {
        val state = if (selection.collapsed) currentDocument.pendingState else currentDocument.stateAtCaret(selection.start)
        colorSelection = selection
        selectedForeground = (state.foreground as? IrcColor.Numeric)?.code
        selectedBackground = (state.background as? IrcColor.Numeric)?.code
        colorSheetVisible = true
    }
    val emojiQuery = activeEmojiQuery(editorValue)
    val emojiSearchEntries = remember { systemEmojiSearchEntries() }
    val emojiSuggestions =
        remember(emojiQuery, emojiSearchEntries) {
            emojiQuery?.let { searchSystemEmojis(emojiSearchEntries, it.query) }.orEmpty()
        }
    val hasAutocomplete = emojiSuggestions.isNotEmpty() || autocomplete != null
    // Keep autocomplete hidden for the entire restoration handoff. Otherwise it would introduce
    // another independently-sized panel above the input row while the IME is animating.
    val visiblePanel = composerPanel(emojiPickerSession != null, hasAutocomplete)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(colorSheetVisible, restoreFocusAfterColor) {
        if (!colorSheetVisible && restoreFocusAfterColor) {
            focusRequester.requestFocus()
            restoreFocusAfterColor = false
        }
    }
    val density = LocalDensity.current
    val readImeContentHeightPx = rememberImeContentHeightReader(imeHeightPx)
    val imeInsetTracker = remember { ImeInsetTracker() }
    val compactPickerHeightPx = with(density) { COMPACT_EMOJI_PICKER_HEIGHT.roundToPx() }
    val latestInputFocused by rememberUpdatedState(inputFocused)
    // Height the retained picker is measured at. Non-zero once the picker has been opened at least
    // once, which is also what keeps the inflated view alive between sessions.
    var pickerContentHeightPx by remember { mutableIntStateOf(0) }
    val closeEmojiPickerDescription = stringResource(R.string.chat_composer_emoji_close)

    // Follow the IME only while it is actually moving, and only from a coroutine: no composition
    // scope reads the animated inset, so the timeline above the composer stays skippable for the
    // whole transition instead of recomposing once per frame.
    LaunchedEffect(readImeContentHeightPx, density) {
        snapshotFlow { readImeContentHeightPx(density) }.collect { heightPx ->
            imeInsetTracker.update(heightPx)
            var frames = 0
            while (!imeInsetTracker.settled && frames++ < IME_FOLLOW_FRAME_LIMIT) {
                withFrameNanos { }
                imeInsetTracker.update(readImeContentHeightPx(density))
            }
        }
    }

    fun dismissEmojiPicker() {
        val session = emojiPickerSession ?: return
        if (session.phase == EmojiPickerPhase.OPEN) {
            emojiPickerSession = closeEmojiPickerSession(session)
        }
    }

    fun openEmojiPicker() {
        val session =
            openEmojiPickerSession(
                imeHeightPx = imeInsetTracker.currentImeHeightPx,
                lastVisibleImeHeightPx = imeInsetTracker.lastVisibleImeHeightPx,
                inputFocused = inputFocused,
                compactPickerHeightPx = compactPickerHeightPx,
            )
        // Measure the picker content once per session; the restore tail only shrinks the viewport.
        pickerContentHeightPx = session.capturedImeHeightPx
        emojiPickerSession = session
        keyboard?.hide()
    }

    // Re-establish the text input connection on a frame boundary, then keep the picker occupying
    // the complementary space until the IME animation has actually settled. If an IME refuses the
    // request, return to the open picker instead of collapsing both input surfaces.
    val restoringIme = emojiPickerSession?.phase == EmojiPickerPhase.RESTORING_IME
    LaunchedEffect(restoringIme) {
        if (!restoringIme) return@LaunchedEffect
        if (!latestInputFocused) focusRequester.requestFocus()
        withFrameNanos { }
        val startGeneration = imeInsetTracker.settleGeneration
        keyboard?.show()
        // Most keyboards reopen the still-focused input connection immediately. If one ignores that
        // request, create exactly one fresh focus transition instead of repeatedly clearing focus
        // and flashing the entire composer.
        val refocus =
            launch {
                repeat(IME_RESTORE_REFOCUS_FRAMES) { withFrameNanos { } }
                if (imeInsetTracker.currentImeHeightPx == 0) {
                    focusManager.clearFocus(force = true)
                    withFrameNanos { }
                    focusRequester.requestFocus()
                    withFrameNanos { }
                    keyboard?.show()
                }
            }
        // A settle at zero only means the keyboard has not started coming back yet, so keep waiting
        // for a resting height the panel can actually hand its space over to.
        val restoredHeightPx =
            withTimeoutOrNull(EMOJI_IME_RESTORE_TIMEOUT_MILLIS) {
                var generation = startGeneration
                var heightPx = 0
                while (heightPx <= 0) {
                    generation =
                        snapshotFlow { imeInsetTracker.settleGeneration }
                            .first { it != generation }
                    heightPx = imeInsetTracker.currentImeHeightPx
                }
                heightPx
            } ?: 0
        refocus.cancel()
        val settledSession =
            emojiPickerSession
                ?.takeIf { it.phase == EmojiPickerPhase.RESTORING_IME }
                ?: return@LaunchedEffect
        if (restoredHeightPx <= 0) {
            emojiPickerSession = reopenEmojiPickerSession(settledSession)
            return@LaunchedEffect
        }
        // Never drop the panel while it still reports height: a keyboard that returns shorter than
        // the captured one would otherwise snap the timeline by the leftover strip.
        var collapsing: EmojiPickerSession = settledSession
        var remainingFrames = IME_RESIDUAL_COLLAPSE_FRAMES
        while (
            remainingFrames > 0 &&
            emojiPickerReplacementHeight(
                collapsing.capturedImeHeightPx,
                imeInsetTracker.currentImeHeightPx,
            ) > 0
        ) {
            withFrameNanos { }
            if (emojiPickerSession !== collapsing) return@LaunchedEffect
            collapsing =
                collapsing.copy(
                    capturedImeHeightPx =
                        collapseCapturedImeHeightPx(
                            capturedImeHeightPx = collapsing.capturedImeHeightPx,
                            currentImeHeightPx = imeInsetTracker.currentImeHeightPx,
                            remainingFrames = remainingFrames,
                        ),
                )
            emojiPickerSession = collapsing
            remainingFrames--
        }
        if (emojiPickerSession === collapsing) emojiPickerSession = null
    }

    // Dismiss transient surfaces before leaving chat.
    BackHandler(
        enabled = colorSheetVisible || toolsSurfaceVisible || emojiPickerSession?.phase == EmojiPickerPhase.OPEN,
    ) {
        when {
            colorSheetVisible -> {
                colorSheetVisible = false
            }

            emojiPickerSession?.phase == EmojiPickerPhase.OPEN -> {
                dismissEmojiPicker()
            }

            toolsSurfaceVisible -> {
                toolsOpen = false
                expanded = false
            }
        }
    }

    AutocompleteOverlayLayout(
        modifier = modifier.fillMaxWidth(),
        overlay = {
            AnimatedVisibility(
                visible = visiblePanel == ComposerPanel.AUTOCOMPLETE,
                enter =
                    fadeIn(MotdMotion.fadeIn) +
                        slideInVertically(animationSpec = MotdMotion.rowPlacement) { height -> height / 8 },
                exit =
                    fadeOut(MotdMotion.microFadeOut) +
                        slideOutVertically(animationSpec = MotdMotion.rowPlacement) { height -> height / 8 },
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (emojiSuggestions.isNotEmpty() && emojiQuery != null) {
                        EmojiAutocompletePanel(
                            suggestions = emojiSuggestions,
                            onPick = { suggestion ->
                                applyEditorValue(replaceEmojiQuery(editorValue, emojiQuery, suggestion.emoji))
                            },
                        )
                    } else {
                        autocomplete?.invoke()
                    }
                }
            }
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
        ) {
            Column {
                HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)

                AnimatedVisibility(
                    visible = replyVisible && reply != null,
                    enter = expandVertically(animationSpec = MotdMotion.contentSize) + fadeIn(MotdMotion.fadeIn),
                    exit = shrinkVertically(animationSpec = MotdMotion.contentSize) + fadeOut(MotdMotion.microFadeOut),
                ) {
                    Column {
                        replyWarning?.let { warning ->
                            Text(
                                text = warning,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, end = 76.dp, top = 6.dp)
                                        .testTag("chat_composer_reply_warning"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        reply?.let { ReplyBar(it, onCancelReply) }
                    }
                }

                AnimatedVisibility(
                    visible = toolsSurfaceVisible,
                    enter = expandVertically(animationSpec = MotdMotion.contentSize),
                    exit = shrinkVertically(animationSpec = MotdMotion.contentSize),
                ) {
                    ComposerToolsToolbar(
                        value = editorValue.copy(selection = toolbarSelection),
                        document = editorDocument,
                        showEmoji = showEmojiTool,
                        showFormatting = ircFormattingEnabled && (showFormattingTools || expanded),
                        onEmoji = {
                            toolsOpen = false
                            expanded = false
                            openEmojiPicker()
                        },
                        onToggle = { currentDocument, selection, style ->
                            publishDocument(currentDocument.toggleStyle(selection.start, selection.end, style))
                        },
                        onColor = ::openColorSheet,
                        onClear = { currentDocument, selection ->
                            publishDocument(currentDocument.clearFormatting(selection.start, selection.end))
                        },
                        onMarkdown = { currentDocument, range ->
                            val next = currentDocument.formatMarkdown(range.start, range.end)
                            val caret = range.end + next.text.length - currentDocument.text.length
                            publishDocument(next, TextRange(caret))
                        },
                        onUploadDraft =
                            onUploadDraft?.takeIf { ircFormattingEnabled }?.let { upload ->
                                {
                                    keyboard?.hide()
                                    focusManager.clearFocus(force = true)
                                    upload()
                                }
                            },
                    )
                }

                Row(
                    modifier =
                        Modifier
                            .testTag("chat_composer_input_row")
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Surface(
                        modifier = Modifier.weight(1f).testTag("chat_composer_input_area"),
                        shape = MotdShapes.composer,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (showToolsButton) {
                                IconButton(
                                    onClick = {
                                        if (toolsSurfaceVisible) {
                                            toolsOpen = false
                                            expanded = false
                                        } else {
                                            if (emojiPickerSession?.phase == EmojiPickerPhase.OPEN) dismissEmojiPicker()
                                            toolsOpen = true
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .size(48.dp)
                                            .testTag("chat_composer_tools")
                                            .semantics { selected = toolsSurfaceVisible },
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription =
                                            stringResource(
                                                if (toolsSurfaceVisible) {
                                                    R.string.chat_composer_tools_close
                                                } else {
                                                    R.string.chat_composer_tools_open
                                                },
                                            ),
                                        modifier = Modifier.graphicsLayer { rotationZ = toolsRotation },
                                        tint =
                                            if (toolsSurfaceVisible) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                }
                            }

                            Box(
                                Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { onFieldPositioned(it.boundsInWindow()) },
                            ) {
                                ComposerTextField(
                                    state = textFieldState,
                                    document = editorDocument,
                                    knownNicks = knownNicks,
                                    identityRules = identityRules,
                                    placeholder = placeholder,
                                    onFocusChanged = { inputFocused = it },
                                    onFocused = { dismissEmojiPicker() },
                                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                    onTextPositioned = onFieldTextPositioned,
                                    contentStartPadding = if (showToolsButton) 4.dp else 16.dp,
                                    ircFormattingEnabled = ircFormattingEnabled,
                                    expanded = expanded,
                                    expandedHeight = expandedHeight,
                                    onColor = {
                                        selectedRange()?.let { openColorSheet(editorDocument, it) }
                                    },
                                    onToggleStyle = { style, start, end ->
                                        publishDocument(editorDocument.toggleStyle(start, end, style))
                                    },
                                    onClearFormatting = { start, end ->
                                        publishDocument(editorDocument.clearFormatting(start, end))
                                    },
                                )

                                // A physical tap on the text field while the picker is open should
                                // perform the same seamless handoff as the emoji toggle. Letting the
                                // field receive that tap directly can make Android show the keyboard
                                // before the complementary panel has been installed.
                                if (emojiPickerSession?.phase == EmojiPickerPhase.OPEN) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .matchParentSize()
                                                .clickable { dismissEmojiPicker() }
                                                .semantics {
                                                    contentDescription = closeEmojiPickerDescription
                                                },
                                    )
                                }
                            }

                            if (onAi != null) {
                                IconButton(
                                    onClick = onAi,
                                    modifier = Modifier.size(48.dp).testTag("chat_composer_ai"),
                                ) {
                                    Icon(
                                        Icons.Filled.AutoFixHigh,
                                        contentDescription = stringResource(R.string.ai_text_tools),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp).size(20.dp),
                                    )
                                }
                            }

                            when {
                                ircFormattingEnabled && (hasDraftText || expanded) -> {
                                    IconButton(
                                        onClick = { expanded = !expanded },
                                        modifier =
                                            Modifier
                                                .size(48.dp)
                                                .testTag("chat_composer_format_expand")
                                                .semantics { selected = expanded },
                                    ) {
                                        Icon(
                                            if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                            contentDescription = if (expanded) "Collapse rich editor" else "Expand rich editor",
                                            tint =
                                                if (expanded) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                        )
                                    }
                                }

                                onAttachment != null -> {
                                    IconButton(
                                        onClick = {
                                            emojiPickerSession = null
                                            keyboard?.hide()
                                            focusManager.clearFocus(force = true)
                                            onAttachment()
                                        },
                                        modifier = Modifier.size(48.dp).testTag("chat_composer_attachment"),
                                    ) {
                                        Icon(
                                            Icons.Outlined.AttachFile,
                                            contentDescription = stringResource(R.string.chat_composer_attachment),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val canSend = enabled && sendEnabled && plainIrcText(editorValue.text).isNotBlank()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Send and voice remain fixed 48.dp round buttons.
                        Crossfade(
                            targetState = canSend || !voiceEnabled,
                            animationSpec = MotdMotion.microFadeIn,
                            label = "composer_action",
                        ) { showSend ->
                            if (showSend) {
                                FilledIconButton(
                                    onClick = {
                                        dismissEmojiPicker()
                                        toolsOpen = false
                                        expanded = false
                                        onSend()
                                    },
                                    enabled = canSend,
                                    modifier = Modifier.size(48.dp).testTag("chat_composer_send"),
                                    shape = CircleShape,
                                    colors =
                                        IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                                        ),
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            stringResource(R.string.chat_composer_send),
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            } else {
                                VoiceRecordButton(
                                    enabled = enabled,
                                    recording = voiceRecording,
                                    onHoldStart = {
                                        dismissEmojiPicker()
                                        onVoiceHoldStart()
                                    },
                                    onAccessibilityStart = {
                                        dismissEmojiPicker()
                                        onVoiceAccessibilityStart()
                                    },
                                    onHoldStop = onVoiceHoldStop,
                                    onHoldCancel = onVoiceHoldCancel,
                                    onLock = onVoiceLock,
                                )
                            }
                        }
                    }
                }

                EmojiPickerReplacementSurface(
                    session = emojiPickerSession,
                    contentHeightPx = pickerContentHeightPx,
                    readImeContentHeightPx = readImeContentHeightPx,
                    onPick = { emoji -> applyEditorValue(insertAtCursor(editorValue, emoji)) },
                )
            }
        }
    }

    if (ircFormattingEnabled && colorSheetVisible) {
        ComposerColorSheet(
            foreground = selectedForeground,
            background = selectedBackground,
            onForeground = { selectedForeground = it },
            onBackground = { selectedBackground = it },
            onCancel = {
                textFieldState.edit { selection = colorSelection }
                restoreFocusAfterColor = true
                colorSheetVisible = false
            },
            onRemove = {
                publishDocument(editorDocument.applyColors(colorSelection.start, colorSelection.end, null, null))
                restoreFocusAfterColor = true
                colorSheetVisible = false
            },
            onApply = {
                publishDocument(
                    editorDocument.applyColors(
                        colorSelection.start,
                        colorSelection.end,
                        selectedForeground,
                        selectedBackground,
                    ),
                )
                restoreFocusAfterColor = true
                colorSheetVisible = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerToolsToolbar(
    value: TextFieldValue,
    document: IrcEditorDocument,
    showEmoji: Boolean,
    showFormatting: Boolean,
    onEmoji: () -> Unit,
    onToggle: (IrcEditorDocument, TextRange, IrcTextStyle) -> Unit,
    onColor: (IrcEditorDocument, TextRange) -> Unit,
    onClear: (IrcEditorDocument, TextRange) -> Unit,
    onMarkdown: (IrcEditorDocument, TextRange) -> Unit,
    onUploadDraft: (() -> Unit)?,
) {
    val allowed = messageFormattingRange(value.text)
    val allowedEnd = allowed?.last?.plus(1)
    val selection =
        value.selection.takeIf {
            allowed != null && allowedEnd != null &&
                if (it.collapsed) it.start in allowed.first..allowedEnd else it.min >= allowed.first && it.max <= allowedEnd
        }
    val currentDocument = rememberUpdatedState(document)
    val currentSelection = rememberUpdatedState(selection)
    val haptics = LocalHapticFeedback.current
    var overflowExpanded by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("chat_composer_format_toolbar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        fun selected(style: IrcTextStyle): Boolean = selection?.let { document.isStyleSelected(it.start, it.end, style) } == true

        @Composable
        fun ToolButton(
            label: String,
            icon: androidx.compose.ui.graphics.vector.ImageVector,
            tag: String,
            enabled: Boolean = true,
            isSelected: Boolean = false,
            onClick: () -> Unit,
        ) {
            TooltipBox(
                positionProvider =
                    androidx.compose.material3.TooltipDefaults
                        .rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(label) } },
                state = rememberTooltipState(),
            ) {
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                    enabled = enabled,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .testTag(tag)
                            .semantics { selected = isSelected },
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                        ),
                ) {
                    Icon(icon, contentDescription = label)
                }
            }
        }

        @Composable
        fun FormatButton(
            style: IrcTextStyle?,
            label: String,
            icon: androidx.compose.ui.graphics.vector.ImageVector,
            tag: String,
            onClick: () -> Unit,
        ) {
            ToolButton(
                label = label,
                icon = icon,
                tag = tag,
                enabled = selection != null,
                isSelected = style?.let(::selected) == true,
                onClick = onClick,
            )
        }

        if (showEmoji) {
            ToolButton(
                label = stringResource(R.string.chat_composer_emoji),
                icon = Icons.Outlined.Mood,
                tag = "chat_composer_emoji",
                onClick = onEmoji,
            )
        }
        if (showFormatting) {
            FormatButton(IrcTextStyle.BOLD, "Bold", Icons.Filled.FormatBold, "chat_format_bold") {
                currentSelection.value?.let { onToggle(currentDocument.value, it, IrcTextStyle.BOLD) }
            }
            FormatButton(IrcTextStyle.ITALIC, "Italic", Icons.Filled.FormatItalic, "chat_format_italic") {
                currentSelection.value?.let { onToggle(currentDocument.value, it, IrcTextStyle.ITALIC) }
            }
            FormatButton(IrcTextStyle.UNDERLINE, "Underline", Icons.Filled.FormatUnderlined, "chat_format_underline") {
                currentSelection.value?.let { onToggle(currentDocument.value, it, IrcTextStyle.UNDERLINE) }
            }
            FormatButton(IrcTextStyle.MONOSPACE, "Monospace", Icons.Filled.Code, "chat_format_monospace") {
                currentSelection.value?.let { onToggle(currentDocument.value, it, IrcTextStyle.MONOSPACE) }
            }
            FormatButton(null, "Color", Icons.Filled.FormatColorText, "chat_format_color") {
                currentSelection.value?.let { onColor(currentDocument.value, it) }
            }
            FormatButton(null, "Clear formatting", Icons.Filled.FormatClear, "chat_format_clear") {
                currentSelection.value?.let { onClear(currentDocument.value, it) }
            }
        }
        if (showFormatting || onUploadDraft != null) {
            Box {
                IconButton(
                    onClick = { overflowExpanded = true },
                    modifier = Modifier.size(48.dp).testTag("chat_composer_overflow"),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More composer actions")
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    if (showFormatting) {
                        DropdownMenuItem(
                            text = { Text("Format Markdown") },
                            onClick = {
                                overflowExpanded = false
                                if (allowed != null && allowedEnd != null) {
                                    onMarkdown(currentDocument.value, TextRange(allowed.first, allowedEnd))
                                }
                            },
                            enabled = allowed != null,
                            modifier = Modifier.testTag("chat_format_markdown"),
                            leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
                        )
                    }
                    onUploadDraft?.let { upload ->
                        DropdownMenuItem(
                            text = { Text("Upload current draft") },
                            onClick = {
                                overflowExpanded = false
                                upload()
                            },
                            modifier = Modifier.testTag("chat_composer_upload_draft"),
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerColorSheet(
    foreground: Int?,
    background: Int?,
    onForeground: (Int?) -> Unit,
    onBackground: (Int?) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onApply: () -> Unit,
) {
    var editingBackground by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("chat_composer_color_sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("IRC color", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = onApply, modifier = Modifier.testTag("chat_composer_color_apply")) {
                    Text("Apply")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editingBackground = false }) {
                    Text(if (editingBackground) "Text" else "Text ✓")
                }
                TextButton(onClick = { editingBackground = true }) {
                    Text(if (editingBackground) "Background ✓" else "Background")
                }
                if (editingBackground) TextButton(onClick = { onBackground(null) }) { Text("No background") }
            }
            val previewBackground = background?.let(::composerPaletteColor) ?: MaterialTheme.colorScheme.surfaceContainerHigh
            val previewForeground =
                foreground?.let(::composerPaletteColor)
                    ?: background?.let {
                        composerPaletteColor(
                            io.github.trevarj.motd.irc.format
                                .readableForeground(it),
                        )
                    }
                    ?: MaterialTheme.colorScheme.onSurface
            Surface(color = previewBackground, shape = MotdShapes.card) {
                Text(
                    "Formatting preview",
                    color = ensureContrast(previewForeground, listOf(previewBackground)),
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("chat_composer_color_preview"),
                )
            }
            Text("Basic 16", style = MaterialTheme.typography.titleSmall)
            ColorGrid(
                colors = (0..15).toList(),
                selected = if (editingBackground) background else foreground,
                onSelect = if (editingBackground) onBackground else onForeground,
                modifier = Modifier.height(96.dp),
            )
            Text("Extended 16–98", style = MaterialTheme.typography.titleSmall)
            ColorGrid(
                colors = (16..98).toList(),
                selected = if (editingBackground) background else foreground,
                onSelect = if (editingBackground) onBackground else onForeground,
                modifier = Modifier.weight(1f).heightIn(min = 180.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onRemove) { Text("Remove colors") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ColorGrid(
    colors: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(colors, key = { it }) { code ->
            val color = composerPaletteColor(code)
            val labelColor = if (color.luminance() > 0.45f) Color.Black else Color.White
            Surface(
                modifier =
                    Modifier
                        .aspectRatio(1f)
                        .clickable { onSelect(code) }
                        .testTag("chat_color_$code")
                        .semantics { this.selected = selected == code },
                color = color,
                shape = CircleShape,
                border =
                    if (selected == code) {
                        androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(code.toString().padStart(2, '0'), color = labelColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun composerPaletteColor(code: Int): Color = Color(0xFF000000 or MIRC_COLORS[code.coerceIn(MIRC_COLORS.indices)].toLong())

@Composable
private fun VoiceRecordButton(
    enabled: Boolean,
    recording: Boolean,
    onHoldStart: () -> Unit,
    onAccessibilityStart: () -> Unit,
    onHoldStop: () -> Unit,
    onHoldCancel: () -> Unit,
    onLock: () -> Unit,
) {
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val cancelPx = with(density) { 72.dp.toPx() }
    val lockPx = with(density) { 72.dp.toPx() }
    val latestRecording by rememberUpdatedState(recording)
    val latestHoldStart by rememberUpdatedState(onHoldStart)
    val latestAccessibilityStart by rememberUpdatedState(onAccessibilityStart)
    val latestHoldStop by rememberUpdatedState(onHoldStop)
    val latestHoldCancel by rememberUpdatedState(onHoldCancel)
    val latestLock by rememberUpdatedState(onLock)
    // Ease the primary-to-error tint at micro tempo so the color never lags the start haptic.
    val containerColor by animateColorAsState(
        targetValue = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = MotdMotion.colorFade,
        label = "voice_record_container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
        animationSpec = MotdMotion.colorFade,
        label = "voice_record_content",
    )
    FilledIconButton(
        onClick = {
            if (latestRecording) {
                latestHoldStop()
            } else {
                latestAccessibilityStart()
            }
        },
        enabled = enabled,
        modifier =
            Modifier
                .size(48.dp)
                .testTag("chat_composer_voice")
                .pointerInput(enabled, cancelPx, lockPx) {
                    if (!enabled) return@pointerInput
                    coroutineScope {
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                            // Keep the optimized pointer gesture separate from the semantic action:
                            // consuming the down prevents Material's click from turning a quick tap
                            // into a locked recording, while keyboard/accessibility clicks still use onClick.
                            down.consume()
                            if (latestRecording) {
                                waitForUpOrCancellation()
                                latestHoldStop()
                                return@awaitEachGesture
                            }
                            var started = false
                            var cancelled = false
                            var locked = false
                            val longPress =
                                this@coroutineScope.launch {
                                    delay(voiceRecordHoldDelay(viewConfiguration.longPressTimeoutMillis))
                                    started = true
                                    // Confirm the hold threshold itself, when microphone capture begins.
                                    // Quick taps stay silent because they never activate recording.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestHoldStart()
                                }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val delta = change.position - down.position
                                if (!locked) {
                                    when (
                                        voiceGestureTarget(
                                            started,
                                            change.pressed,
                                            delta.x,
                                            delta.y,
                                            cancelPx,
                                            lockPx,
                                        )
                                    ) {
                                        VoiceGestureTarget.LOCK -> {
                                            locked = true
                                            change.consume()
                                            // Confirm the transition to hands-free recording as soon as it locks.
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            latestLock()
                                            break
                                        }

                                        VoiceGestureTarget.CANCEL -> {
                                            cancelled = true
                                            change.consume()
                                            // Use rejection feedback when the destructive slide target
                                            // commits so cancellation is unambiguous without looking.
                                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                            latestHoldCancel()
                                            break
                                        }

                                        VoiceGestureTarget.NONE -> {}
                                    }
                                }
                                if (!change.pressed) break
                            }
                            longPress.cancel()
                            when {
                                !started -> Unit

                                // Pointer taps stay silent; semantic clicks start a locked recording.
                                cancelled -> Unit

                                locked -> Unit

                                else -> latestHoldStop()
                            }
                        }
                    }
                },
        shape = CircleShape,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
    ) {
        // Cross-fade the glyph so the content description always matches the composed branch.
        Crossfade(
            targetState = recording,
            animationSpec = MotdMotion.microFadeIn,
            label = "voice_record_icon",
        ) { isRecording ->
            Icon(
                if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription =
                    stringResource(
                        if (isRecording) R.string.voice_stop_recording else R.string.voice_record,
                    ),
            )
        }
    }
}

/**
 * Hosts suggestions in a real popup immediately above the composer. A popup has its own hit-test
 * bounds, so rows remain clickable without contributing height or moving the input/timeline.
 */
@Composable
private fun AutocompleteOverlayLayout(
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit,
    anchor: @Composable () -> Unit,
) {
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(
        modifier = modifier.onSizeChanged { anchorWidthPx = it.width },
    ) {
        anchor()
        if (anchorWidthPx > 0) {
            Popup(
                popupPositionProvider = AutocompletePopupPositionProvider,
                properties =
                    PopupProperties(
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        clippingEnabled = false,
                    ),
            ) {
                Box(Modifier.width(with(density) { anchorWidthPx.toDp() })) {
                    overlay()
                }
            }
        }
    }
}

/**
 * Samples the live IME height wherever the caller needs it. The returned reader is stable, so
 * handing it to a layout keeps the read in that layout's measure phase instead of pinning the
 * animated inset to a composition scope.
 */
@Composable
private fun rememberImeContentHeightReader(overridePx: Int?): (Density) -> Int {
    val imeInsets = WindowInsets.ime
    val navigationBarsInsets = WindowInsets.navigationBars
    val latestOverridePx by rememberUpdatedState(overridePx)
    return remember(imeInsets, navigationBarsInsets) {
        { density ->
            latestOverridePx ?: imeContentHeightPx(
                imeBottomPx = imeInsets.getBottom(density),
                navigationBarsBottomPx = navigationBarsInsets.getBottom(density),
            )
        }
    }
}

@Composable
private fun EmojiPickerReplacementSurface(
    session: EmojiPickerSession?,
    contentHeightPx: Int,
    readImeContentHeightPx: (Density) -> Int,
    onPick: (String) -> Unit,
) {
    // Stay composed once the picker has been opened once. Re-inflating [EmojiPickerView] restarts
    // its async category load, which is exactly the blank-then-populate flash on the most expensive
    // frame of the handoff.
    if (contentHeightPx <= 0) return
    Layout(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("chat_composer_emoji_panel")
                .clipToBounds(),
        content = {
            // Measure the picker once at the captured keyboard height. Only the clipping viewport
            // changes during the IME animation, so the pager/grid does not relayout every frame.
            EmojiPickerPanel(onPick = onPick, modifier = Modifier.fillMaxWidth())
        },
    ) { measurables, constraints ->
        val fullHeightPx = contentHeightPx.coerceAtMost(constraints.maxHeight)
        // Measure-phase inset read: the ancestor `imePadding()` samples the very same frame, so the
        // input row never moves by one frame of keyboard travel and the timeline viewport is stable.
        val visibleHeightPx =
            session?.let {
                emojiPickerReplacementHeight(
                    capturedImeHeightPx = it.capturedImeHeightPx,
                    currentImeHeightPx = readImeContentHeightPx(this),
                ).coerceAtMost(fullHeightPx)
            } ?: 0
        val placeable =
            measurables.single().measure(
                constraints.copy(minHeight = fullHeightPx, maxHeight = fullHeightPx),
            )
        layout(placeable.width, visibleHeightPx) {
            placeable.placeRelative(0, 0)
        }
    }
}

private fun completedCommandEnd(text: String): Int? {
    if (!text.startsWith("/") || text.startsWith("//")) return null
    val separator = text.indexOfFirst { it.isWhitespace() }
    if (separator > 1) return separator
    return text.length.takeIf { separator == -1 && COMMAND_HINTS.any { hint -> hint.equals(text, ignoreCase = true) } }
}

private fun Char.isComposerNickChar(): Boolean = isLetterOrDigit() || this in "_-[]{}\\|^`"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerTextField(
    state: TextFieldState,
    document: IrcEditorDocument,
    knownNicks: Set<String>,
    identityRules: IrcIdentityRules,
    placeholder: String,
    onFocusChanged: (Boolean) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onTextPositioned: (Offset) -> Unit = {},
    contentStartPadding: Dp = 4.dp,
    ircFormattingEnabled: Boolean = false,
    expanded: Boolean = false,
    expandedHeight: Dp = 148.dp,
    onColor: () -> Unit = {},
    onToggleStyle: (IrcTextStyle, Int, Int) -> Unit = { _, _, _ -> },
    onClearFormatting: (Int, Int) -> Unit = { _, _ -> },
) {
    fun allowedSelection(): TextRange? {
        val text = state.text.toString()
        val allowed = if (ircFormattingEnabled) messageFormattingRange(text) else null
        val selection = state.selection
        val end = allowed?.last?.plus(1) ?: return null
        return selection.takeIf {
            if (it.collapsed) it.start in allowed.first..end else it.min >= allowed.first && it.max <= end
        }
    }

    val selection = allowedSelection()
    val hasSelection = selection?.collapsed == false
    val boldSelected = selection?.let { document.isStyleSelected(it.start, it.end, IrcTextStyle.BOLD) } == true
    val boldLabel = if (hasSelection && boldSelected) "Remove bold" else "Bold"
    val latestDocument = rememberUpdatedState(document)
    val fieldBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val fieldForeground = MaterialTheme.colorScheme.onSurface
    val commandBackground = MaterialTheme.colorScheme.primary
    val commandForeground = MaterialTheme.colorScheme.onPrimary
    val commandFontSize = MaterialTheme.typography.bodyMedium.fontSize
    val nickColors = LocalNickColors.current
    val mentionColor = rememberMentionColor(knownNicks, nickColors, identityRules)
    val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
    val text = state.text.toString()
    val prefixChip =
        if (mentionsActive && !text.startsWith("/")) {
            val end = text.indexOfFirst { !it.isComposerNickChar() }
            if (end > 0 && end < text.length && text[end] == ':') {
                mentionColor(text.substring(0, end))?.let { end + 1 to it }
            } else {
                null
            }
        } else {
            null
        }
    val commandEnd = if (ircFormattingEnabled) completedCommandEnd(text) else null
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val outputTransformation =
        remember(ircFormattingEnabled, fieldBackground, fieldForeground, commandForeground, commandFontSize, mentionsActive, mentionColor) {
            if (!ircFormattingEnabled && !mentionsActive) {
                null
            } else {
                OutputTransformation {
                    val visibleText = toString()
                    if (ircFormattingEnabled) {
                        val displayed = latestDocument.value.let { if (it.text == visibleText) it else it.replaceText(visibleText) }
                        displayed.runs.forEach { run ->
                            addStyle(run.state.toSpanStyle(fieldBackground, fieldForeground), run.start, run.end)
                        }
                        completedCommandEnd(visibleText)?.let { end ->
                            addStyle(SpanStyle(color = commandForeground, fontFamily = FontFamily.Monospace, fontSize = commandFontSize), 0, end)
                        }
                    }
                    if (mentionsActive && !visibleText.startsWith("/")) {
                        var i = 0
                        while (i < visibleText.length) {
                            val start = i
                            val atMention = visibleText[i] == '@' && i + 1 < visibleText.length && visibleText[i + 1].isComposerNickChar()
                            if (atMention) i++
                            if (!visibleText[i].isComposerNickChar()) {
                                i++
                                continue
                            }
                            val nickStart = i
                            while (i < visibleText.length && visibleText[i].isComposerNickChar()) i++
                            val color = mentionColor(visibleText.substring(nickStart, i)) ?: continue
                            addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), start, i)
                        }
                    }
                }
            }
        }
    val contextMenuModifier =
        if (!ircFormattingEnabled) {
            Modifier
        } else if (expanded) {
            // Expanded mode owns formatting actions; suppress the floating selection popup so it
            // cannot cover the persistent toolbar.
            Modifier.filterTextContextMenuComponents { false }
        } else {
            Modifier.appendTextContextMenuComponents {
                if (hasSelection) {
                    separator()

                    fun styleItem(
                        style: IrcTextStyle,
                        label: String,
                    ) {
                        item(key = "irc-${style.name}", label = label) {
                            close()
                            val current = allowedSelection() ?: return@item
                            onToggleStyle(style, current.start, current.end)
                        }
                    }
                    styleItem(IrcTextStyle.BOLD, boldLabel)
                    styleItem(IrcTextStyle.ITALIC, "Italic")
                    styleItem(IrcTextStyle.UNDERLINE, "Underline")
                    styleItem(IrcTextStyle.STRIKETHROUGH, "Strikethrough")
                    styleItem(IrcTextStyle.MONOSPACE, "Monospace")
                    item(key = "irc-color", label = "Color") {
                        close()
                        onColor()
                    }
                    item(key = "irc-clear", label = "Clear formatting") {
                        close()
                        val current = allowedSelection() ?: return@item
                        onClearFormatting(current.start, current.end)
                    }
                }
            }
        }

    BasicTextField(
        state = state,
        modifier =
            modifier
                .then(contextMenuModifier)
                .heightIn(
                    min = if (expanded) expandedHeight else 48.dp,
                    max = if (expanded) expandedHeight else 148.dp,
                ).onFocusChanged {
                    onFocusChanged(it.isFocused)
                    if (it.isFocused) onFocused()
                }.testTag("chat_composer_field"),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = fieldForeground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = if (expanded) Int.MAX_VALUE else 6),
        outputTransformation = outputTransformation,
        onTextLayout = { getResult -> textLayout = getResult() },
        decorator = { inner ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = contentStartPadding,
                            end = 4.dp,
                            top = if (expanded) 8.dp else 12.dp,
                            bottom = if (expanded) 4.dp else 12.dp,
                        ),
                contentAlignment = if (expanded) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (state.text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier =
                        Modifier
                            .then(if (commandEnd != null) Modifier.testTag("chat_composer_command_chip") else Modifier)
                            .then(if (prefixChip != null) Modifier.testTag("chat_composer_nick_chip") else Modifier)
                            .drawBehind {
                                val end = commandEnd ?: prefixChip?.first ?: return@drawBehind
                                val color = if (commandEnd != null) commandBackground else prefixChip!!.second.copy(alpha = 0.16f)
                                val layout = textLayout?.takeIf { it.layoutInput.text.text == text } ?: return@drawBehind
                                val horizontalPadding = 3.dp.toPx()
                                val verticalPadding = 1.dp.toPx()
                                // ponytail: paint behind the same editor glyphs; no second field or draft state.
                                for (line in 0..layout.getLineForOffset(end - 1)) {
                                    val start = layout.getLineStart(line)
                                    val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true))
                                    if (lineEnd <= start) continue
                                    val first = layout.getBoundingBox(start)
                                    val last = layout.getBoundingBox(lineEnd - 1)
                                    drawRoundRect(
                                        color = color,
                                        topLeft = Offset(first.left - horizontalPadding, first.top - verticalPadding),
                                        size =
                                            Size(
                                                last.right - first.left + horizontalPadding * 2,
                                                first.height + verticalPadding * 2,
                                            ),
                                        cornerRadius = CornerRadius(6.dp.toPx()),
                                    )
                                }
                            }.onGloballyPositioned { onTextPositioned(it.positionInWindow()) },
                ) {
                    inner()
                }
            }
        },
    )
}

fun insertAtCursor(
    value: TextFieldValue,
    insertion: String,
): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val text = value.text.replaceRange(start, end, insertion)
    return TextFieldValue(text = text, selection = TextRange(start + insertion.length))
}

internal data class EmojiQuery(
    val start: Int,
    val end: Int,
    val query: String,
)

internal fun activeEmojiQuery(value: TextFieldValue): EmojiQuery? {
    if (!value.selection.collapsed) return null
    val formatted = parseIrcFormatting(value.text)
    val visibleCursor = formatted.visibleOffset(value.selection.start)
    val tokenStart = formatted.visibleText.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), startIndex = visibleCursor - 1) + 1
    if (tokenStart >= visibleCursor || formatted.visibleText.getOrNull(tokenStart) != ':') return null
    val query = formatted.visibleText.substring(tokenStart + 1, visibleCursor)
    if (query.isEmpty() || query.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) return null
    val rawStart = formatted.rawToVisible.indexOfLast { it == tokenStart }.coerceAtLeast(0)
    return EmojiQuery(rawStart, value.selection.start, query)
}

internal fun replaceEmojiQuery(
    value: TextFieldValue,
    query: EmojiQuery,
    emoji: String,
): TextFieldValue {
    val text = value.text.replaceRange(query.start, query.end, emoji)
    return value.copy(text = text, selection = TextRange(query.start + emoji.length))
}

@Composable
private fun EmojiAutocompletePanel(
    suggestions: List<EmojiSearchEntry>,
    onPick: (EmojiSearchEntry) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("chat_composer_emoji_autocomplete"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MotdShapes.card,
        tonalElevation = 3.dp,
    ) {
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            lazyItems(suggestions, key = { it.emoji }) { suggestion ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(suggestion) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(suggestion.emoji, fontSize = 24.sp)
                    Text(
                        suggestion.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiPickerPanel(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .testTag("chat_composer_emoji_picker")
                .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                .fillMaxSize(),
        shape = MotdShapes.bubble,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        AndroidView(
            factory = { context ->
                EmojiPickerView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { picker ->
                picker.setOnEmojiPickedListener { item -> onPick(item.emoji) }
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("chat_composer_emoji_grid"),
        )
    }
}

@Composable
private fun ReplyBar(
    reply: ComposerReply,
    onCancel: () -> Unit,
) {
    val accent = LocalNickColors.current.nick(reply.sender, MaterialTheme.colorScheme.primary)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 68.dp, top = 8.dp),
        shape = MotdShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    stringResource(R.string.chat_composer_replying_to, reply.sender),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Text(
                    reply.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.chat_composer_cancel_reply))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ComposerPreview() =
    MotdTheme {
        Composer(TextFieldValue("hello there"), {}, {}, true, onAttachment = {})
    }

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun ComposerNarrowMultilinePreview() =
    MotdTheme {
        Composer(TextFieldValue("A longer draft that wraps across\nmultiple lines"), {}, {}, true, onAttachment = {})
    }

@Preview(showBackground = true)
@Composable
private fun ComposerReplyPreview() =
    MotdTheme {
        Composer(TextFieldValue(""), {}, {}, true, reply = ComposerReply("alice", "welcome to the channel!"), onAttachment = {})
    }
