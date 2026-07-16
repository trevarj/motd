package io.github.trevarj.motd.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.chat.systemEmojiPages
import io.github.trevarj.motd.ui.chat.EmojiSearchEntry
import io.github.trevarj.motd.ui.chat.searchSystemEmojis
import io.github.trevarj.motd.ui.chat.systemEmojiSearchEntries
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ComposerReply(val sender: String, val text: String)

internal enum class ComposerPanel { NONE, AUTOCOMPLETE, EMOJI }

internal fun composerPanel(showEmoji: Boolean, hasAutocomplete: Boolean): ComposerPanel = when {
    showEmoji -> ComposerPanel.EMOJI
    hasAutocomplete -> ComposerPanel.AUTOCOMPLETE
    else -> ComposerPanel.NONE
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
    compactPickerHeightPx: Int,
): EmojiPickerSession {
    val visibleImeHeightPx = imeHeightPx.coerceAtLeast(0)
    return EmojiPickerSession(
        capturedImeHeightPx = if (visibleImeHeightPx > 0) visibleImeHeightPx else compactPickerHeightPx.coerceAtLeast(0),
        restoresKeyboard = visibleImeHeightPx > 0,
    )
}

internal fun closeEmojiPickerSession(session: EmojiPickerSession): EmojiPickerSession? =
    if (session.restoresKeyboard) session.copy(phase = EmojiPickerPhase.RESTORING_IME) else null

internal fun reopenEmojiPickerSession(session: EmojiPickerSession): EmojiPickerSession =
    session.copy(phase = EmojiPickerPhase.OPEN)

/**
 * With adjustResize the window bottom rises by [currentImeHeightPx]. Keeping this complementary
 * space below the input row makes its screen position independent of the IME animation.
 */
internal fun emojiPickerReplacementHeight(
    capturedImeHeightPx: Int,
    currentImeHeightPx: Int,
): Int = (capturedImeHeightPx.coerceAtLeast(0) - currentImeHeightPx.coerceAtLeast(0)).coerceAtLeast(0)

private const val EMOJI_IME_RESTORE_TIMEOUT_MILLIS = 1_000L
private val COMPACT_EMOJI_PICKER_HEIGHT = 250.dp

/** Modern chat composer with embedded tools and a stable, separate primary send action. */
@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    reply: ComposerReply? = null,
    onCancelReply: () -> Unit = {},
    placeholder: String = stringResource(R.string.chat_composer_placeholder),
    showEmojiButton: Boolean = true,
    onAttachment: (() -> Unit)? = null,
    autocomplete: (@Composable () -> Unit)? = null,
) {
    var emojiPickerSession by remember { mutableStateOf<EmojiPickerSession?>(null) }
    val emojiQuery = activeEmojiQuery(value)
    val emojiSearchEntries = remember { systemEmojiSearchEntries() }
    val emojiSuggestions = remember(emojiQuery, emojiSearchEntries) {
        emojiQuery?.let { searchSystemEmojis(emojiSearchEntries, it.query) }.orEmpty()
    }
    val hasAutocomplete = emojiSuggestions.isNotEmpty() || autocomplete != null
    // Keep autocomplete hidden for the entire restoration handoff. Otherwise it would introduce
    // another independently-sized panel above the input row while the IME is animating.
    val visiblePanel = composerPanel(emojiPickerSession != null, hasAutocomplete)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val compactPickerHeightPx = with(density) { COMPACT_EMOJI_PICKER_HEIGHT.roundToPx() }
    val restoringSession = emojiPickerSession?.takeIf { it.phase == EmojiPickerPhase.RESTORING_IME }
    val closeEmojiPickerDescription = stringResource(R.string.chat_composer_emoji_close)

    fun dismissEmojiPicker() {
        val session = emojiPickerSession ?: return
        if (session.phase == EmojiPickerPhase.OPEN) {
            emojiPickerSession = closeEmojiPickerSession(session)
        }
    }

    fun openEmojiPicker() {
        emojiPickerSession = openEmojiPickerSession(
            imeHeightPx = imeInsets.getBottom(density),
            compactPickerHeightPx = compactPickerHeightPx,
        )
        keyboard?.hide()
    }

    // Requesting focus and showing the IME from an effect makes the reverse transition reliable
    // even when the emoji icon was tapped while the field's input connection was momentarily idle.
    LaunchedEffect(restoringSession) {
        if (restoringSession != null) {
            focusRequester.requestFocus()
            keyboard?.show()
            withTimeoutOrNull(EMOJI_IME_RESTORE_TIMEOUT_MILLIS) {
                snapshotFlow { imeInsets.getBottom(density) }
                    .first { it >= restoringSession.capturedImeHeightPx }
            }
            if (emojiPickerSession == restoringSession) {
                // Hardware keyboards and failed IME requests have no inset animation. Do not
                // leave the captured keyboard-sized gap below the composer indefinitely.
                emojiPickerSession = null
            }
        }
    }

    // The first Back closes the picker and restores the keyboard only when it replaced one. Once
    // the picker state is gone, the platform handles a second Back normally.
    BackHandler(enabled = emojiPickerSession?.phase == EmojiPickerPhase.OPEN) {
        dismissEmojiPicker()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
    ) {
        Column {
            HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)

            AnimatedVisibility(
                visible = visiblePanel == ComposerPanel.AUTOCOMPLETE,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (emojiSuggestions.isNotEmpty() && emojiQuery != null) {
                        EmojiAutocompletePanel(
                            suggestions = emojiSuggestions,
                            onPick = { suggestion ->
                                onValueChange(replaceEmojiQuery(value, emojiQuery, suggestion.emoji))
                            },
                        )
                    } else {
                        autocomplete?.invoke()
                    }
                }
            }

            AnimatedVisibility(
                visible = reply != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                reply?.let { ReplyBar(it, onCancelReply) }
            }

            Row(
                modifier = Modifier
                    .testTag("chat_composer_input_row")
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (showEmojiButton) {
                            IconButton(
                                onClick = {
                                    when (emojiPickerSession?.phase) {
                                        EmojiPickerPhase.OPEN -> dismissEmojiPicker()
                                        EmojiPickerPhase.RESTORING_IME -> {
                                            emojiPickerSession = emojiPickerSession?.let(::reopenEmojiPickerSession)
                                            keyboard?.hide()
                                        }
                                        null -> openEmojiPicker()
                                    }
                                },
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("chat_composer_emoji")
                                    .semantics { selected = emojiPickerSession?.phase == EmojiPickerPhase.OPEN },
                            ) {
                                Icon(
                                    Icons.Outlined.Mood,
                                    contentDescription = stringResource(
                                        if (emojiPickerSession?.phase == EmojiPickerPhase.OPEN) R.string.chat_composer_emoji_close
                                        else R.string.chat_composer_emoji,
                                    ),
                                    tint = if (emojiPickerSession?.phase == EmojiPickerPhase.OPEN) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Box(Modifier.weight(1f)) {
                            ComposerTextField(
                                value = value,
                                onValueChange = onValueChange,
                                placeholder = placeholder,
                                onFocused = { dismissEmojiPicker() },
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )

                            // A physical tap on the text field while the picker is open should
                            // perform the same seamless handoff as the emoji toggle. Letting the
                            // field receive that tap directly can make Android show the keyboard
                            // before the complementary panel has been installed.
                            if (emojiPickerSession?.phase == EmojiPickerPhase.OPEN) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable { dismissEmojiPicker() }
                                        .semantics {
                                            contentDescription = closeEmojiPickerDescription
                                        },
                                )
                            }
                        }

                        onAttachment?.let { action ->
                            IconButton(
                                onClick = {
                                    emojiPickerSession = null
                                    keyboard?.hide()
                                    focusManager.clearFocus(force = true)
                                    action()
                                },
                                modifier = Modifier.size(52.dp).testTag("chat_composer_attachment"),
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

                val canSend = enabled && value.text.isNotBlank()
                FilledIconButton(
                    onClick = {
                        dismissEmojiPicker()
                        onSend()
                    },
                    enabled = canSend,
                    modifier = Modifier.size(52.dp).testTag("chat_composer_send"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
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
            }

            EmojiPickerReplacementSurface(
                session = emojiPickerSession,
                imeInsets = imeInsets,
                onPick = { emoji -> onValueChange(insertAtCursor(value, emoji)) },
            )
        }
    }
}

@Composable
private fun EmojiPickerReplacementSurface(
    session: EmojiPickerSession?,
    imeInsets: WindowInsets,
    onPick: (String) -> Unit,
) {
    session ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .emojiPickerReplacementHeight(imeInsets, session.capturedImeHeightPx)
            .clipToBounds(),
    ) {
        AnimatedVisibility(
            visible = session.phase == EmojiPickerPhase.OPEN,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            EmojiPickerPanel(
                onPick = onPick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Reads the current animated IME inset during measurement, rather than animating a second size. */
private fun Modifier.emojiPickerReplacementHeight(
    imeInsets: WindowInsets,
    capturedImeHeightPx: Int,
): Modifier = layout { measurable, constraints ->
    val desiredHeightPx = emojiPickerReplacementHeight(capturedImeHeightPx, imeInsets.getBottom(this))
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(
        constraints.copy(minHeight = desiredHeightPx, maxHeight = desiredHeightPx),
    )
    layout(placeable.width, desiredHeightPx) {
        placeable.placeRelative(0, 0)
    }
}

@Composable
private fun ComposerTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 52.dp, max = 148.dp)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .testTag("chat_composer_field"),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default,
        ),
        maxLines = 6,
        interactionSource = interactions,
        decorationBox = { inner ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

fun insertAtCursor(value: TextFieldValue, insertion: String): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val text = value.text.replaceRange(start, end, insertion)
    return TextFieldValue(text = text, selection = TextRange(start + insertion.length))
}

internal data class EmojiQuery(val start: Int, val end: Int, val query: String)

internal fun activeEmojiQuery(value: TextFieldValue): EmojiQuery? {
    if (!value.selection.collapsed) return null
    val cursor = value.selection.start
    val tokenStart = value.text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), startIndex = cursor - 1) + 1
    if (tokenStart >= cursor || value.text.getOrNull(tokenStart) != ':') return null
    val query = value.text.substring(tokenStart + 1, cursor)
    if (query.isEmpty() || query.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) return null
    return EmojiQuery(tokenStart, cursor, query)
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
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
    ) {
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            lazyItems(suggestions, key = { it.emoji }) { suggestion ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(suggestion) }
                        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
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
    val labels = listOf(
        stringResource(R.string.emoji_people),
        stringResource(R.string.emoji_nature),
        stringResource(R.string.emoji_food),
        stringResource(R.string.emoji_activity),
        stringResource(R.string.emoji_travel),
        stringResource(R.string.emoji_objects),
        stringResource(R.string.emoji_symbols),
        stringResource(R.string.emoji_flags),
    )
    val pages = remember(labels) { systemEmojiPages(labels) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Surface(
        modifier = modifier
            .testTag("chat_composer_emoji_picker")
            .padding(start = 8.dp, end = 8.dp, top = 8.dp)
            .fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                pages.forEachIndexed { index, page ->
                    val selected = pager.currentPage == index
                    Surface(
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        modifier = Modifier.size(44.dp).semantics {
                            this.selected = selected
                            contentDescription = page.label
                        },
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(page.icon, fontSize = 20.sp) }
                    }
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).testTag("chat_composer_emoji_pages"),
            ) { pageIndex ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier
                        .testTag("chat_composer_emoji_grid")
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                ) {
                    items(pages[pageIndex].emojis, key = { it }) { emoji ->
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onPick(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 24.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyBar(reply: ComposerReply, onCancel: () -> Unit) {
    val accent = LocalNickColors.current.nick(reply.sender, MaterialTheme.colorScheme.primary)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 68.dp, top = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(36.dp).clip(CircleShape).background(accent))
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
private fun ComposerPreview() = MotdTheme {
    Composer(TextFieldValue("hello there"), {}, {}, true, onAttachment = {})
}

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun ComposerNarrowMultilinePreview() = MotdTheme {
    Composer(TextFieldValue("A longer draft that wraps across\nmultiple lines"), {}, {}, true, onAttachment = {})
}

@Preview(showBackground = true)
@Composable
private fun ComposerReplyPreview() = MotdTheme {
    Composer(TextFieldValue(""), {}, {}, true, reply = ComposerReply("alice", "welcome to the channel!"), onAttachment = {})
}
