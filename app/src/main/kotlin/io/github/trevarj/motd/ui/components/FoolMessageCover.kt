package io.github.trevarj.motd.ui.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalEmojiRenderer
import io.github.trevarj.motd.ui.theme.LocalSurfaceEffects
import io.github.trevarj.motd.ui.theme.MotdShapes
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import io.github.trevarj.motd.ui.components.EmojiText as Text

internal const val FOOL_BODY_RANGE = "fool_body"
private const val MAX_TEXT_CUSTOM_ACTIONS = 31

internal data class FoolBodyConcealment(
    val concealed: Boolean = false,
    val onReveal: () -> Unit = {},
    val tag: String = "fool_reveal",
)

internal val LocalFoolBodyConcealment = staticCompositionLocalOf { FoolBodyConcealment() }

internal val LocalMessageSelection = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalMessageSelectionBounds = staticCompositionLocalOf<(androidx.compose.ui.geometry.Rect) -> Unit> { {} }

/** Only the body is painted as concealed; preview links also respect the concealment state. */
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
    onLinkLongPress: (() -> Unit)? = null,
    onSenderLongClick: (() -> Unit)? = null,
    onMentionLongClick: ((String) -> Unit)? = null,
) {
    val selecting = LocalMessageSelection.current != null
    val nativeLayout = remember(text, selecting) { mutableStateOf<TextLayoutResult?>(null) }
    val chips = remember(text) { text.getStringAnnotations(NICK_CHIP_RANGE, 0, text.length) }
    if (chips.isEmpty()) {
        FoolBodyTextHost(text, modifier, color, style, maxLines, overflow, concealment, inlineBody, onLinkLongPress, nativeLayout, onSenderLongClick = onSenderLongClick, onMentionLongClick = onMentionLongClick)
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val emojiRenderer = LocalEmojiRenderer.current
    val resolvedStyle = rememberEmojiTextStyle(LocalTextStyle.current.merge(style).let { if (color == Color.Unspecified) it else it.copy(color = color) })
    val layoutDirection = LocalLayoutDirection.current
    val layoutSemantics =
        if (concealment.concealed) {
            Modifier
        } else {
            Modifier.semantics {
                getTextLayoutResult { results ->
                    nativeLayout.value?.let { results.add(it) } ?: false
                }
            }
        }
    BoxWithConstraints(modifier.then(layoutSemantics)) {
        val width = constraints.maxWidth
        val prepared =
            remember(text, chips, width, density, measurer, resolvedStyle, layoutDirection, emojiRenderer) {
                val horizontal = with(density) { 4.dp.toPx() }
                val vertical = with(density) { 1.dp.toPx() }
                val available = (width - horizontal * 2).toInt().coerceAtLeast(1)
                val builder = AnnotatedString.Builder(text)
                val content = mutableMapOf<String, InlineTextContent>()
                val captionStyle = resolvedStyle.copy(lineHeight = TextUnit.Unspecified)
                chips.forEach { chip ->
                    val caption = text.subSequence(chip.start, chip.end).let { emojiRenderer?.annotate(it) ?: it }
                    val wrapped = measurer.measure(caption, captionStyle, constraints = Constraints(maxWidth = available))
                    for (line in 0 until wrapped.lineCount) {
                        val from = wrapped.getLineStart(line)
                        val to = wrapped.getLineEnd(line)
                        if (from == to) continue
                        val fragment = if (wrapped.lineCount == 1) caption else caption.subSequence(from, to)
                        val measured = if (wrapped.lineCount == 1) wrapped else measurer.measure(fragment, captionStyle, softWrap = false)
                        val id = "nick:${chip.start}:$from"
                        val placeholder =
                            Placeholder(
                                with(density) { (measured.size.width + horizontal * 2).toSp() },
                                with(density) { (measured.size.height + vertical * 2).toSp() },
                                PlaceholderVerticalAlign.TextCenter,
                            )
                        val annotation = buildAnnotatedString { appendInlineContent(id, fragment.text) }
                        annotation.getStringAnnotations(0, annotation.length).forEach {
                            builder.addStringAnnotation(it.tag, it.item, chip.start + from + it.start, chip.start + from + it.end)
                        }
                        content[id] =
                            InlineTextContent(placeholder) {
                                Canvas(Modifier.fillMaxSize().background(Color(chip.item.toULong()), MotdShapes.tag).clearAndSetSemantics {}) {
                                    drawText(measured, topLeft = Offset(horizontal, vertical))
                                }
                            }
                    }
                }
                builder.toAnnotatedString() to content
            }
        FoolBodyTextHost(prepared.first, Modifier, color, style, maxLines, overflow, concealment, inlineBody, onLinkLongPress, nativeLayout, prepared.second, onSenderLongClick, onMentionLongClick)
    }
}

@Composable
private fun FoolBodyTextHost(
    text: AnnotatedString,
    modifier: Modifier,
    color: Color,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    concealment: FoolBodyConcealment,
    inlineBody: Boolean,
    onLinkLongPress: (() -> Unit)?,
    layoutState: MutableState<TextLayoutResult?>,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onSenderLongClick: (() -> Unit)? = null,
    onMentionLongClick: ((String) -> Unit)? = null,
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
    val dismissSelection = LocalMessageSelection.current.takeUnless { concealed || start >= end }
    var layout by layoutState
    var revealFocused by remember { mutableStateOf(false) }
    val pathLayout = layout
    val bodyPath =
        remember(pathLayout, text.text, start, end, concealed) {
            if (concealed && pathLayout != null && pathLayout.layoutInput.text.text == text.text) pathLayout.getPathForRange(start, end) else null
        }
    val senderLink = remember(text, start, concealed) { if (concealed) text.getLinkAnnotations(0, start).firstOrNull()?.item as? LinkAnnotation.Clickable else null }
    val linksEnabled = LocalChatLinksEnabled.current && LocalMessageSelection.current == null
    val actionsEnabled = LocalMessageSelection.current == null && LocalMessageActionsEnabled.current
    val openIrcLink = LocalOpenIrcChannelLink.current
    val hasIrcOwner = openIrcLink != null
    val nonWebLinks =
        remember(text, concealed, start, end, linksEnabled, hasIrcOwner) {
            if (linksEnabled) {
                val ranges = text.getStringAnnotations(NON_WEB_LINK_RANGE, 0, text.length)
                if (ranges.isNotEmpty() && (concealed || !hasIrcOwner)) {
                    ranges.filterNot {
                        concealed && it.start < end && it.end > start ||
                            !hasIrcOwner && (it.item.startsWith("irc://", ignoreCase = true) || it.item.startsWith("ircs://", ignoreCase = true))
                    }
                } else {
                    ranges
                }
            } else {
                emptyList()
            }
        }
    val openNonWebLink =
        if (nonWebLinks.isNotEmpty()) {
            val context = LocalContext.current
            val latestOpenIrcLink by rememberUpdatedState(openIrcLink)
            remember(context) {
                LinkInteractionListener { link -> context.openChatNonWebLink((link as LinkAnnotation.Url).url, latestOpenIrcLink) }
            }
        } else {
            null
        }
    val displayed =
        remember(text, concealed, start, end, linksEnabled, nonWebLinks, openNonWebLink) {
            val visible =
                if (!concealed && linksEnabled) {
                    text
                } else {
                    text.flatMapAnnotations { annotation ->
                        val hidden = concealed && annotation.item is LinkAnnotation && annotation.start < end && annotation.end > start
                        if (hidden || (!linksEnabled && annotation.item is LinkAnnotation.Url)) emptyList() else listOf(annotation)
                    }
                }
            if (openNonWebLink == null) {
                visible
            } else {
                val builder = AnnotatedString.Builder(visible)
                for (range in nonWebLinks) {
                    builder.addLink(LinkAnnotation.Url(range.item, linkInteractionListener = openNonWebLink), range.start, range.end)
                }
                builder.toAnnotatedString()
            }
        }
    val latestLinkLongPress by rememberUpdatedState(onLinkLongPress)
    val latestSenderLongClick by rememberUpdatedState(onSenderLongClick)
    val latestMentionLongClick by rememberUpdatedState(onMentionLongClick)
    val links = remember(displayed) { displayed.getLinkAnnotations(0, displayed.length) }
    val linkUrls =
        if (actionsEnabled && !concealed && linksEnabled && links.isNotEmpty()) {
            remember(links) { links.mapNotNull { (it.item as? LinkAnnotation.Url)?.url }.distinct() }
        } else {
            emptyList()
        }
    val linkActionTargets = if (linkUrls.isNotEmpty()) remember(displayed) { mutableStateOf<List<String>?>(null) } else null
    val linkMenuLabel = if (linkActionTargets != null) stringResource(R.string.chat_link_actions) else ""
    val userMenuLabel = stringResource(R.string.settings_nickname_tap_user_menu)
    val accessibilityActions =
        remember(displayed, onSenderLongClick, onMentionLongClick, userMenuLabel, actionsEnabled, senderLink, linkActionTargets, linkMenuLabel) {
            if (!actionsEnabled || links.isEmpty()) {
                emptyList()
            } else {
                buildList {
                    if (linkActionTargets != null) {
                        val label = linkUrls.singleOrNull()?.let { "$linkMenuLabel: $it" } ?: linkMenuLabel
                        add(
                            CustomAccessibilityAction(label) {
                                linkActionTargets.value = linkUrls
                                true
                            },
                        )
                    }
                    val limit = MAX_TEXT_CUSTOM_ACTIONS - if (senderLink != null) 1 else 0
                    for (range in links) {
                        if (size == limit) break
                        val tag = (range.item as? LinkAnnotation.Clickable)?.tag
                        val callback: (() -> Unit)? =
                            when {
                                tag == "action-sender" -> onSenderLongClick
                                tag?.startsWith("mention:") == true -> onMentionLongClick?.let { action -> { action(tag.removePrefix("mention:")) } }
                                else -> null
                            }
                        if (callback != null) {
                            add(
                                CustomAccessibilityAction("$userMenuLabel: ${displayed.text.substring(range.start, range.end)}") {
                                    callback()
                                    true
                                },
                            )
                        }
                    }
                }
            }
        }
    val haptic = LocalHapticFeedback.current
    val linkLongPressModifier =
        if (actionsEnabled &&
            (linkActionTargets != null || onLinkLongPress != null || onSenderLongClick != null || onMentionLongClick != null) &&
            links.isNotEmpty()
        ) {
            Modifier.pointerInput(displayed, concealed, linkActionTargets, haptic) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
                    if (currentEvent.changes.size != 1) return@awaitEachGesture
                    val result = layout ?: return@awaitEachGesture
                    if (result.layoutInput.text.text != displayed.text) return@awaitEachGesture
                    val offset = result.nativeOffsetAt(down.position) ?: return@awaitEachGesture
                    if (offset >= displayed.length) return@awaitEachGesture
                    val link = displayed.getLinkAnnotations(offset, offset + 1).firstOrNull()?.item
                    // A native link child consumes down in Main before this text host; the
                    // message row ancestor runs afterward. Final would include the row's own
                    // consumption and incorrectly give an ordinary hold two owners.
                    // Keep ordinary glyphs in expanded native link targets routed to the row action.
                    if (link == null && (concealed || latestLinkLongPress == null)) return@awaitEachGesture
                    val nativeDown = awaitPointerEvent(PointerEventPass.Main)
                    if (nativeDown.changes.none { it.id == down.id && it.isConsumed }) return@awaitEachGesture
                    val releasedOrMoved =
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size != 1 ||
                                    event.changes.any {
                                        it.isConsumed || !it.pressed || (it.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                            it.position.x < 0 || it.position.y < 0 || it.position.x > size.width || it.position.y > size.height
                                    }
                                ) {
                                    return@withTimeoutOrNull true
                                }
                            }
                        }
                    if (releasedOrMoved == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val tag = (link as? LinkAnnotation.Clickable)?.tag
                        val url = (link as? LinkAnnotation.Url)?.url
                        when {
                            tag == "action-sender" && latestSenderLongClick != null -> latestSenderLongClick?.invoke()
                            tag?.startsWith("mention:") == true && latestMentionLongClick != null -> latestMentionLongClick?.invoke(tag.removePrefix("mention:"))
                            linkActionTargets != null && url != null -> linkActionTargets.value = listOf(url)
                            else -> latestLinkLongPress?.invoke()
                        }
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
        } else {
            Modifier
        }
    val body: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            text = displayed,
            inlineContent = inlineContent,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier =
                textModifier
                    .then(linkLongPressModifier)
                    .then(if (accessibilityActions.isEmpty() || concealed) Modifier else Modifier.semantics { customActions = accessibilityActions })
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
                                            ) + accessibilityActions
                                    }
                                }.pointerInput(concealed, start, end, concealment.onReveal) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        val result = layout
                                        val offset = result?.nativeOffsetAt(down.position)
                                        if ((start == 0 && end == text.length) || (offset != null && offset in start until end)) {
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
                                    // Selection paths can exceed the paragraph width; RenderEffect output can too.
                                    // Keep the blurred layer inside the same bounds as the opaque fallback.
                                    clipRect {
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
                                }
                        },
                    ),
        )
    }
    if (dismissSelection == null) {
        body(modifier)
    } else {
        val selectionState = rememberSelectionState()
        val latestDismiss by rememberUpdatedState(dismissSelection)
        var positioned by remember(selectionState) { mutableStateOf(false) }
        val onBounds = LocalMessageSelectionBounds.current
        SelectionContainer(
            state = selectionState,
            modifier =
                modifier.testTag("message_selection").onGloballyPositioned {
                    onBounds(it.boundsInWindow())
                    positioned = true
                },
        ) {
            body(Modifier.testTag("message_selection_text"))
        }
        LaunchedEffect(selectionState, text) {
            // Native link styling adds span annotations during layout; compare the characters,
            // not the caller's annotations, before selecting the complete laid-out body.
            snapshotFlow { positioned && layout?.layoutInput?.text?.text == displayed.text }.first { it }
            selectionState.select(TextRange(start, end))
            snapshotFlow { selectionState.selectedTexts.isEmpty() }.collect { empty ->
                if (empty) latestDismiss()
            }
        }
        DisposableEffect(selectionState) {
            onDispose { latestDismiss() }
        }
    }
    linkActionTargets?.value?.let { urls -> LinkActionsSheet(urls, onDismiss = { linkActionTargets.value = null }) }
}

private fun TextLayoutResult.nativeOffsetAt(position: Offset): Int? {
    placeholderRects.forEachIndexed { index, rect ->
        if (rect?.contains(position) == true) return layoutInput.placeholders[index].start
    }
    val offset = getOffsetForPosition(position)
    return offset.takeIf { it < layoutInput.text.length && getBoundingBox(it).contains(position) }
        ?: (offset - 1).takeIf { it >= 0 && getBoundingBox(it).contains(position) }
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
