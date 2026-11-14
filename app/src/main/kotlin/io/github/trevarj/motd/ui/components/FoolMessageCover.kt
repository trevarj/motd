package io.github.trevarj.motd.ui.components

import android.os.Build
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalSurfaceEffects
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

internal const val FOOL_BODY_RANGE = "fool_body"

internal data class FoolBodyConcealment(
    val concealed: Boolean = false,
    val onReveal: () -> Unit = {},
    val tag: String = "fool_reveal",
)

internal val LocalFoolBodyConcealment = staticCompositionLocalOf { FoolBodyConcealment() }

internal val LocalMessageSelection = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalMessageSelectionBounds = staticCompositionLocalOf<(androidx.compose.ui.geometry.Rect) -> Unit> { {} }

/** Scope only: authors, media, metadata and their input/semantics are never covered. */
@Composable
internal fun FoolMessageScope(
    concealed: Boolean,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "fool_reveal",
    content: @Composable () -> Unit,
) {
    val state = remember(concealed, onReveal, tag) { FoolBodyConcealment(concealed, onReveal, tag) }
    CompositionLocalProvider(LocalFoolBodyConcealment provides state) {
        Box(modifier) { content() }
    }
}

/** Deregister selected raw text on Restore without remounting the transcript/translation. */
@Composable
internal fun FoolBodySelection(content: @Composable () -> Unit) {
    val currentContent by rememberUpdatedState(content)
    val body = remember { movableContentOf { currentContent() } }
    val concealed = LocalFoolBodyConcealment.current.concealed
    CompositionLocalProvider(LocalMessageSelection provides null) {
        SelectionContainer {
            if (concealed) DisableSelection { body() } else body()
        }
    }
}

/** The original single paragraph is laid out once. Only its annotated body range is concealed. */
@Composable
internal fun FoolBodyText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    concealment: FoolBodyConcealment,
    inlineBody: Boolean = false,
) {
    val ranges = remember(text) { text.getStringAnnotations(FOOL_BODY_RANGE, 0, text.length) }
    val start = ranges.minOfOrNull { it.start } ?: if (inlineBody) text.length else 0
    val end = ranges.maxOfOrNull { it.end } ?: text.length
    val concealed = concealment.concealed && start < end
    val reveal = stringResource(R.string.chat_fool_reveal)
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    val nativeBlur = Build.VERSION.SDK_INT >= 31 && LocalSurfaceEffects.current.enabled
    val radius = with(LocalDensity.current) { 40.dp.toPx() }
    val blurLayer = if (nativeBlur && concealed) rememberGraphicsLayer() else null
    val blurEffect = remember(nativeBlur, concealed, radius) { if (nativeBlur && concealed) BlurEffect(radius, radius, TileMode.Decal) else null }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var revealFocused by remember { mutableStateOf(false) }
    val pathLayout = layout
    val bodyPath =
        remember(pathLayout, text.text, start, end, concealed) {
            if (concealed && pathLayout != null && pathLayout.layoutInput.text.text == text.text) pathLayout.getPathForRange(start, end) else null
        }
    val senderLink = remember(text, start, concealed) { if (concealed) text.getLinkAnnotations(0, start).firstOrNull()?.item as? LinkAnnotation.Clickable else null }
    // Retain characters, styles and paragraph metrics, but remove body links and focus nodes.
    val displayed =
        remember(text, concealed, start, end) {
            if (!concealed) {
                text
            } else {
                buildAnnotatedString {
                    append(text.subSequence(0, start))
                    val body = text.subSequence(start, end)
                    append(AnnotatedString(body.text, body.spanStyles, body.paragraphStyles))
                    append(text.subSequence(end, text.length))
                }
            }
        }
    val body: @Composable (Modifier) -> Unit = { textModifier ->
    Text(
        text = displayed,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it },
        modifier =
            textModifier
                .then(
                    if (!concealed) {
                        Modifier
                    } else {
                        Modifier
                            .testTag(concealment.tag)
                            .onPreviewKeyEvent {
                                if (revealFocused && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                                    if (it.type == KeyEventType.KeyUp) concealment.onReveal()
                                    true
                                } else {
                                    false
                                }
                            }.onFocusChanged { revealFocused = it.isFocused }
                            .focusable()
                            .clearAndSetSemantics {
                                contentDescription =
                                    text.text
                                        .substring(0, start)
                                        .trim()
                                        .let { if (it.isEmpty()) reveal else "$it, $reveal" }
                                onClick(label = reveal) {
                                    concealment.onReveal()
                                    true
                                }
                                senderLink?.let { link ->
                                    customActions =
                                        listOf(
                                            CustomAccessibilityAction(text.text.substring(0, start).trim()) {
                                                link.linkInteractionListener?.onClick(link)
                                                true
                                            },
                                        )
                                }
                            }.pointerInput(concealed, start, end, concealment.onReveal) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val result = layout
                                    val offset = result?.getOffsetForPosition(down.position)
                                    if ((start == 0 && end == text.length) ||
                                        (offset != null && offset in start until end && result.getBoundingBox(offset).contains(down.position))
                                    ) {
                                        down.consume()
                                        var released: Boolean
                                        var cancelled = false
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val pointer = event.changes.firstOrNull { it.id == down.id }
                                            cancelled = cancelled || pointer == null ||
                                                (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                                event.changes.any { it.id != down.id && it.pressed }
                                            released = event.changes.none { it.pressed }
                                            if (released && !cancelled) event.changes.forEach { it.consume() }
                                        } while (!released)
                                        if (!cancelled) concealment.onReveal()
                                    }
                                }
                            }.drawWithContent {
                                val result = layout
                                if (result == null || result.layoutInput.text.text != displayed.text) {
                                    // Never paint a readable frame while a new paragraph waits for layout.
                                    return@drawWithContent
                                }
                                val path = bodyPath?.takeIf { result === pathLayout } ?: result.getPathForRange(start, end)
                                clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
                                clipPath(path) {
                                    if (blurLayer != null) {
                                        blurLayer.renderEffect = blurEffect
                                        blurLayer.record {
                                            clipPath(path) { this@drawWithContent.drawContent() }
                                        }
                                        drawLayer(blurLayer)
                                    } else {
                                        drawRect(fill)
                                    }
                                }
                            }
                    },
                ),
    )
    }
    val dismissSelection = LocalMessageSelection.current.takeUnless { concealed || start >= end }
    if (dismissSelection == null) {
        body(modifier)
    } else {
        val selectionState = rememberSelectionState()
        val latestDismiss by rememberUpdatedState(dismissSelection)
        val onBounds = LocalMessageSelectionBounds.current
        SelectionContainer(
            state = selectionState,
            modifier = modifier.testTag("message_selection").onGloballyPositioned { onBounds(it.boundsInWindow()) },
        ) {
            body(Modifier.testTag("message_selection_text"))
        }
        LaunchedEffect(selectionState, text) {
            snapshotFlow { layout }.first { it?.layoutInput?.text == displayed }
            withFrameNanos {}
            selectionState.select(TextRange(start, end))
            snapshotFlow { selectionState.selectedTexts.isEmpty() }.collect { empty ->
                if (empty) latestDismiss()
            }
        }
        DisposableEffect(selectionState) {
            onDispose { latestDismiss() }
        }
    }
}

@Composable
internal fun FoolBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    concealment: FoolBodyConcealment,
) = FoolBodyText(remember(text) { AnnotatedString(text) }, modifier, color, style, maxLines, overflow, concealment)
