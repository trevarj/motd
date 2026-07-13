package io.github.trevarj.motd.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.chat.extractUrls
import io.github.trevarj.motd.ui.chat.InlineTextSegment
import io.github.trevarj.motd.ui.chat.parseInlineCode
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.LocalConversationFontScale
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import java.text.DateFormat as JavaDateFormat

/** Alpha for the per-nick row background wash in TWO_LINE density: matches the COMPACT band so runs
 *  of a nick's messages are trackable, faint enough to stay readable in light and dark themes. */
private const val TWO_LINE_ROW_TINT_ALPHA = 0.10f

/**
 * One chat bubble. Handles the four rendered kinds (PRIVMSG bubble, NOTICE labelled bubble, ACTION
 * italic no-bubble, plus reply/image/reactions decorations). System-event kinds are rendered by
 * [SystemEventPill] upstream, not here.
 *
 * Grouping: [showSender] draws the nick-colored name + avatar (own messages omit the name). Own
 * bubbles are right-aligned `primaryContainer`; others left `surfaceContainerHigh`. Corner radii
 * tighten on the grouped inner edge (plans/07).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    sender: String,
    text: String,
    timeMs: Long,
    isSelf: Boolean,
    kind: MessageKind,
    showSender: Boolean,
    modifier: Modifier = Modifier,
    networkId: Long? = null,
    senderAccount: String? = null,
    formattedTime: String? = null,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    // Normalized nicks known in the current buffer; @mentions of these in the body are colored with
    // the nick's own color (plans/17). Empty = no mention coloring.
    knownNicks: Set<String> = emptySet(),
    onLongPress: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    // Tapping the sender name/avatar opens the nick sheet; null (self / non-first bubbles) = inert.
    onSenderClick: (() -> Unit)? = null,
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    // Production timelines pass a string from one list-scoped formatter. The fallback keeps
    // previews/direct callers source-compatible without making every real row query system time
    // settings and construct its own formatter.
    val displayedTime = formattedTime ?: formatTime(timeMs)
    // A shared no-op onClick with a null indication removes the dead ripple on plain taps; long-press
    // is the only action entry, labeled for TalkBack (plans/15 #31).
    // Density tokens + nick-color scheme flow through CompositionLocals; no signature churn.
    val spacing = LocalSpacing.current
    val conversationFontScale = LocalConversationFontScale.current
    val nickColors = LocalNickColors.current
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant

    // COMPACT density = classic single-line IRC rendering (`nick: text`). Delegate the whole row to
    // the inline renderer; bubbles/avatars/alignment are the COMFORTABLE/TWO_LINE paradigm only.
    if (spacing.compact) {
        CompactMessageRow(
            sender = sender,
            text = text,
            formattedTime = displayedTime,
            isSelf = isSelf,
            kind = kind,
            nickColors = nickColors,
            modifier = modifier,
            senderIsFriend = senderIsFriend,
            failed = failed,
            pending = pending,
            reply = reply,
            imageUrl = imageUrl,
            linkPreview = linkPreview,
            linkPreviewLoading = linkPreviewLoading,
            reactions = reactions,
            knownNicks = knownNicks,
            showSender = showSender,
            onLongPress = onLongPress,
            onReact = onReact,
            onImageClick = onImageClick,
            onLinkPreviewClick = onLinkPreviewClick,
            onSenderClick = onSenderClick,
        )
        return
    }

    // TWO_LINE density = a compact header line (avatar + nick + own-sent check + time) over the body
    // line. Not a bubble; delegates to the dedicated two-line renderer.
    if (spacing.twoLine) {
        TwoLineMessageRow(
            sender = sender,
            networkId = networkId,
            senderAccount = senderAccount,
            text = text,
            formattedTime = displayedTime,
            isSelf = isSelf,
            kind = kind,
            nickColors = nickColors,
            spacing = spacing,
            modifier = modifier,
            senderIsFriend = senderIsFriend,
            failed = failed,
            pending = pending,
            reply = reply,
            imageUrl = imageUrl,
            linkPreview = linkPreview,
            linkPreviewLoading = linkPreviewLoading,
            reactions = reactions,
            knownNicks = knownNicks,
            showSender = showSender,
            onLongPress = onLongPress,
            onReact = onReact,
            onImageClick = onImageClick,
            onLinkPreviewClick = onLinkPreviewClick,
            onSenderClick = onSenderClick,
        )
        return
    }

    // ACTION renders as centered-left italic text, no bubble (plans/07). Still carries reactions +
    // failed + timestamp decorations (plans/15 #16).
    if (kind == MessageKind.ACTION) {
        val linkColor = MaterialTheme.colorScheme.primary
        val mentionColor = rememberMentionColor(knownNicks, nickColors)
        val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
        val richBody = remember(
            text, linkColor, mentionsActive, mentionColor, codeBackground, codeColor,
        ) {
            linkifiedBody(
                text,
                linkColor,
                mentionsActive,
                mentionColor,
                codeBackground,
                codeColor,
            )
        }
        val actionLine = remember(sender, richBody) {
            buildAnnotatedString {
                append("* $sender ")
                append(richBody)
            }
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    // No ripple is drawn, so a MutableInteractionSource would only allocate per row.
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                    onLongClick = onLongPress,
                    onLongClickLabel = actionsLabel,
                )
                .padding(horizontal = 16.dp, vertical = spacing.actionVPad),
        ) {
            reply?.let { ReplyMiniBubble(it, nickColors) }
            Text(
                text = actionLine,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MessageStatusIcon(isSelf = isSelf, pending = pending, failed = failed)
                Text(
                    text = displayedTime,
                    fontSize = 10.sp * conversationFontScale,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            ReactionRow(reactions = reactions, onReact = onReact)
        }
        return
    }

    // Window width in dp = container px / density; keeps the 0.78 bubble max-width behavior.
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    val maxWidth = with(density) { (containerWidthPx * 0.78f).toDp() }
    val bubbleColor = if (isSelf) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isSelf) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    // Tighten the inner (grouped) top corner: 4dp when this bubble continues a group.
    val topCorner = if (showSender) spacing.bubbleCorner else 4.dp
    val shape = if (isSelf) {
        RoundedCornerShape(topStart = spacing.bubbleCorner, topEnd = topCorner, bottomEnd = 4.dp, bottomStart = spacing.bubbleCorner)
    } else {
        RoundedCornerShape(topStart = topCorner, topEnd = spacing.bubbleCorner, bottomEnd = spacing.bubbleCorner, bottomStart = 4.dp)
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = spacing.bubbleRowVPad),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        // Left avatar column for others, only on a group's first bubble.
        if (!isSelf) {
            if (showSender) {
                val avatarMod = Modifier.padding(end = 8.dp, top = 2.dp)
                    .let { if (onSenderClick != null) it.clickable(onClick = onSenderClick) else it }
                Avatar(
                    name = sender,
                    size = spacing.bubbleAvatar,
                    modifier = avatarMod,
                    networkId = networkId,
                    account = senderAccount,
                )
            } else {
                Box(Modifier.width(spacing.bubbleAvatarColumn))
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(bubbleColor)
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                    onLongClick = onLongPress,
                    onLongClickLabel = actionsLabel,
                )
                .padding(horizontal = spacing.bubbleInnerHPad, vertical = spacing.bubbleInnerVPad),
        ) {
            if (showSender && !isSelf) {
                val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (onSenderClick != null) Modifier.clickable(onClick = onSenderClick) else Modifier,
                ) {
                    Text(
                        text = sender,
                        color = nameColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        // Friend tint: a subtle theme-primary rounded background behind the name,
                        // layered under the nick color (plans/13 confirmed decision #4).
                        modifier = if (senderIsFriend) Modifier.friendNickTint() else Modifier,
                    )
                    if (senderIsFriend) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = nameColor,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(12.dp),
                        )
                    }
                }
            }
            if (kind == MessageKind.NOTICE) {
                Text(
                    text = stringResource(R.string.chat_notice_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            reply?.let { ReplyMiniBubble(it, nickColors) }

            imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    // Reserve a 4:3 box until the bitmap lands so rows don't jump the reversed-list
                    // anchor (plans/15 #34).
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .widthIn(max = maxWidth)
                        .heightIn(max = 280.dp)
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { onImageClick(url) }, onLongClick = onLongPress),
                )
            }

            if (text.isNotBlank()) {
                // Linkify http(s) URLs so the body is tappable even when the preview fails
                // (plans/15 #11); LinkAnnotation.Url uses the platform URI open handler. Known-nick
                // @mentions are colored with the nick's own color. Body build is memoized per
                // (text, mention inputs) so it doesn't re-run every recomposition/scroll frame.
                val linkColor = MaterialTheme.colorScheme.primary
                val mentionColor = rememberMentionColor(knownNicks, nickColors)
                val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
                val body = remember(
                    text, linkColor, mentionsActive, mentionColor, codeBackground, codeColor,
                ) {
                    linkifiedBody(
                        text,
                        linkColor,
                        mentionsActive,
                        mentionColor,
                        codeBackground,
                        codeColor,
                    )
                }
                Text(
                    text = body,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (linkPreview != null || linkPreviewLoading) {
                Box(Modifier.padding(top = 4.dp)) {
                    LinkPreviewCard(
                        preview = linkPreview,
                        loading = linkPreviewLoading,
                        onClick = onLinkPreviewClick,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                MessageStatusIcon(isSelf = isSelf, pending = pending, failed = failed)
                Text(
                    text = displayedTime,
                    fontSize = 10.sp * conversationFontScale,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else textColor.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            ReactionRow(reactions = reactions, onReact = onReact)
        }
    }
}

/**
 * TWO_LINE density renderer (plans/13): a compact two-line message row.
 *  - Line 1: a small avatar, the nick-colored name (friend tint/star preserved), the own-message
 *    sent check ([MessageStatusIcon], own messages only), and the timestamp.
 *  - Line 2: the message body (linkified), plus the reply preview, inline image, link preview, and
 *    the now-compact reactions.
 *
 * ACTION renders as `* nick text` italic (like the bubble/IRC renderers); NOTICE gets its label.
 * Uniformly left-aligned — no own-message right alignment or bubble background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoLineMessageRow(
    sender: String,
    networkId: Long?,
    senderAccount: String?,
    text: String,
    formattedTime: String,
    isSelf: Boolean,
    kind: MessageKind,
    nickColors: NickColorScheme,
    spacing: io.github.trevarj.motd.ui.theme.MotdSpacing,
    showSender: Boolean,
    modifier: Modifier = Modifier,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    onLongPress: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    onSenderClick: (() -> Unit)? = null,
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    val conversationFontScale = LocalConversationFontScale.current
    val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurfaceVariant)
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Per-nick row wash (same treatment as COMPACT): a faint tint of the sender's own nick color
    // behind the whole row so runs of a nick's messages are trackable by speaker.
    val rowTint = nameColor.copy(alpha = TWO_LINE_ROW_TINT_ALPHA)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Tint fills the full row width (behind the horizontal padding) so the speaker band is
            // unbroken edge to edge, matching COMPACT.
            .background(rowTint)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
                onLongClickLabel = actionsLabel,
            )
            .padding(horizontal = 12.dp, vertical = spacing.bubbleRowVPad),
    ) {
        // Line 1 (header): avatar + nick + (own) sent check + timestamp — only on a group's first
        // message. Continuations (showSender == false) omit the header and indent the body under it.
        if (showSender) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Center the small avatar against the header line rather than top-pinning it.
                val avatarMod = Modifier.padding(end = 6.dp)
                    .align(Alignment.CenterVertically)
                    .let { if (onSenderClick != null) it.clickable(onClick = onSenderClick) else it }
                Avatar(
                    name = sender,
                    size = spacing.bubbleAvatar,
                    modifier = avatarMod,
                    networkId = networkId,
                    account = senderAccount,
                )
                Text(
                    text = sender,
                    color = nameColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = (if (senderIsFriend) Modifier.friendNickTint() else Modifier)
                        .let { if (onSenderClick != null) it.clickable(onClick = onSenderClick) else it },
                )
                if (senderIsFriend) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = nameColor,
                        modifier = Modifier.padding(start = 4.dp).size(12.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 6.dp),
                ) {
                    MessageStatusIcon(isSelf = isSelf, pending = pending, failed = failed)
                    Text(
                        text = formattedTime,
                        fontSize = 10.sp * conversationFontScale,
                        color = if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }

        // Line 2 always starts under the nick text column. The previous first-row special case
        // started at the row edge while continuations reserved the avatar, producing a visible
        // zig-zag and misaligning rich children within the same sender group.
        val bodyIndent = twoLineBodyIndent(spacing)
        Column(
            modifier = Modifier
                .padding(
                    start = bodyIndent,
                    top = if (showSender) spacing.bubbleInnerVPad else 0.dp,
                )
                .testTag("message_two_line_body"),
        ) {
            reply?.let { ReplyMiniBubble(it, nickColors) }

            if (kind == MessageKind.NOTICE) {
                Text(
                    text = stringResource(R.string.chat_notice_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .widthIn(max = 280.dp)
                        .heightIn(max = 240.dp)
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { onImageClick(url) }, onLongClick = onLongPress),
                )
            }

            if (text.isNotBlank()) {
                val linkColor = MaterialTheme.colorScheme.primary
                val mentionColor = rememberMentionColor(knownNicks, nickColors)
                val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
                // Memoized body build (linkify + mention coloring) so it doesn't re-run per frame.
                val richBody = remember(
                    text, linkColor, mentionsActive, mentionColor, codeBackground, codeColor,
                ) {
                    linkifiedBody(
                        text,
                        linkColor,
                        mentionsActive,
                        mentionColor,
                        codeBackground,
                        codeColor,
                    )
                }
                val body = remember(kind, sender, richBody) {
                    if (kind != MessageKind.ACTION) richBody else buildAnnotatedString {
                        append("* $sender ")
                        append(richBody)
                    }
                }
                Text(
                    text = body,
                    color = if (kind == MessageKind.ACTION) MaterialTheme.colorScheme.onSurfaceVariant else bodyColor,
                    fontStyle = if (kind == MessageKind.ACTION) FontStyle.Italic else FontStyle.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (linkPreview != null || linkPreviewLoading) {
                Box(Modifier.padding(top = 4.dp)) {
                    LinkPreviewCard(
                        preview = linkPreview,
                        loading = linkPreviewLoading,
                        onClick = onLinkPreviewClick,
                    )
                }
            }

            ReactionRow(reactions = reactions, onReact = onReact)
        }
    }
}

/** Horizontal start shared by first and grouped TWO_LINE bodies: avatar width plus header gap. */
internal fun twoLineBodyIndent(spacing: io.github.trevarj.motd.ui.theme.MotdSpacing) =
    spacing.bubbleAvatar + 6.dp

/**
 * Delivery status of an own message, in priority order. IRC has no per-recipient read receipt
 * (`draft/read-marker` is a personal, self-only marker), so [SENT] — the bouncer echoed it back —
 * is as far as the ladder goes: there is no "read by them" state to render.
 */
internal enum class MsgStatus { NONE, PENDING, FAILED, SENT }

/**
 * Pure status decision shared by every render site. Incoming messages ([isSelf] false) are always
 * [NONE]; they're never pending/failed and must not show a check.
 */
internal fun messageStatus(isSelf: Boolean, pending: Boolean, failed: Boolean): MsgStatus = when {
    failed -> MsgStatus.FAILED
    pending -> MsgStatus.PENDING
    isSelf -> MsgStatus.SENT
    else -> MsgStatus.NONE
}

/** Renders the single leading status glyph (clock / error / sent-check) for the timestamp row. */
@Composable
internal fun MessageStatusIcon(isSelf: Boolean, pending: Boolean, failed: Boolean) {
    when (messageStatus(isSelf, pending, failed)) {
        MsgStatus.FAILED -> FailedIcon()
        MsgStatus.PENDING -> PendingIcon()
        MsgStatus.SENT -> SentIcon()
        MsgStatus.NONE -> {}
    }
}

/** Small check glyph shown next to the timestamp once the bouncer has echoed an own message back. */
@Composable
internal fun SentIcon() {
    Icon(
        Icons.Filled.Done,
        contentDescription = stringResource(R.string.chat_sent),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .padding(end = 4.dp)
            .heightIn(max = 12.dp)
            .width(12.dp),
    )
}

/** Small clock glyph shown next to the timestamp while a message is still sending (plans/15 #21). */
@Composable
internal fun PendingIcon() {
    Icon(
        Icons.Filled.Schedule,
        contentDescription = stringResource(R.string.chat_sending),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .padding(end = 4.dp)
            .heightIn(max = 12.dp)
            .width(12.dp),
    )
}

/** Small error glyph shown next to the timestamp of a failed message. */
@Composable
internal fun FailedIcon() {
    Icon(
        Icons.Filled.Error,
        contentDescription = stringResource(R.string.chat_failed),
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(end = 4.dp)
            .heightIn(max = 14.dp)
            .width(14.dp),
    )
}

/**
 * Build an [AnnotatedString] where each http(s) URL in [text] is a tappable [LinkAnnotation.Url]
 * (plans/15 #11) and each @mention of a known nick is colored with that nick's own color. URL
 * boundaries come from [extractUrls], matched left-to-right in the raw text; the runs between URLs
 * get mention coloring via [appendMentionColored]. [mentionColor] returns the nick's color for a
 * known token (matched case-insensitively via [normalizeNick]) or null for a plain word; when null
 * for everything (no known nicks) the body is a single unstyled run.
 */
internal fun linkifiedBody(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
    mentionsActive: Boolean = true,
    mentionColor: (String) -> androidx.compose.ui.graphics.Color? = { null },
    codeBackground: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    codeColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
): AnnotatedString {
    // Most chat rows are plain text. Avoid the URL regex, nick token walk, and builder allocation
    // when neither link annotations nor mention styling can affect the result.
    if (!mentionsActive && !text.contains("http://") && !text.contains("https://") && !text.contains('`')) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        appendRichText(
            text = text,
            plainStyle = SpanStyle(),
            linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            codeStyle = SpanStyle(
                color = codeColor,
                background = codeBackground,
                fontFamily = FontFamily.Monospace,
                fontStyle = FontStyle.Normal,
            ),
            mentionColor = if (mentionsActive) mentionColor else ({ null }),
        )
    }
}

/** Code segmentation precedes URL and mention annotation, so code contents stay inert. */
internal fun androidx.compose.ui.text.AnnotatedString.Builder.appendRichText(
    text: String,
    plainStyle: SpanStyle,
    linkStyle: SpanStyle,
    codeStyle: SpanStyle,
    mentionColor: (String) -> androidx.compose.ui.graphics.Color? = { null },
) {
    for (segment in parseInlineCode(text)) {
        when (segment) {
            is InlineTextSegment.Code -> withStyle(codeStyle) { append(segment.text) }
            is InlineTextSegment.Plain -> appendPlainLinksAndMentions(
                segment.text,
                plainStyle,
                linkStyle,
                mentionColor,
            )
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendPlainLinksAndMentions(
    text: String,
    plainStyle: SpanStyle,
    linkStyle: SpanStyle,
    mentionColor: (String) -> androidx.compose.ui.graphics.Color?,
) {
    val urls = extractUrls(text)
    if (urls.isEmpty()) {
        withStyle(plainStyle) { appendMentionColored(text, mentionColor) }
        return
    }
    var cursor = 0
    for (url in urls) {
        val at = text.indexOf(url, cursor)
        if (at < 0) continue
        withStyle(plainStyle) { appendMentionColored(text.substring(cursor, at), mentionColor) }
        withLink(LinkAnnotation.Url(url)) { withStyle(linkStyle) { append(url) } }
        cursor = at + url.length
    }
    if (cursor < text.length) {
        withStyle(plainStyle) { appendMentionColored(text.substring(cursor), mentionColor) }
    }
}

/**
 * A stable `nick -> Color?` resolver for @mention coloring: returns the nick's own color when
 * [knownNicks] (normalized) contains the token, else null. Memoized on ([knownNicks], [nickColors])
 * so the same lambda instance is reused across recompositions, keeping the body [remember] cache
 * warm during scroll. Returns a null-op resolver when there are no known nicks.
 */
@Composable
internal fun rememberMentionColor(
    knownNicks: Set<String>,
    nickColors: NickColorScheme,
): (String) -> androidx.compose.ui.graphics.Color? {
    return remember(knownNicks, nickColors) {
        if (knownNicks.isEmpty() || !nickColors.enabled) {
            { null }
        } else {
            { token ->
                // Mentions always resolve to the nick's own color; Unspecified fallback is never
                // hit because membership is checked first.
                if (io.github.trevarj.motd.data.prefs.normalizeNick(token) in knownNicks) {
                    nickColors.nick(token, androidx.compose.ui.graphics.Color.Unspecified)
                } else {
                    null
                }
            }
        }
    }
}

// Chars that can be part of an IRC nick token. Mentions are matched on runs of these, so trailing
// punctuation (`:`, `,`, `!`) and a leading `@` fall outside the token and don't break the match.
private fun isNickChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '-' || c == '[' || c == ']' ||
        c == '{' || c == '}' || c == '\\' || c == '|' || c == '^' || c == '`'

/**
 * Append [text] to the builder, coloring any word-boundary token that resolves to a known-nick
 * color via [mentionColor] (bare `nick`, `nick:`/`nick,` prefix forms, and `@nick`). A leading `@`
 * is consumed as part of the token so `@bob` highlights `bob`. Non-nick runs are appended verbatim.
 * Pure over the builder; no Android runtime needed.
 */
internal fun androidx.compose.ui.text.AnnotatedString.Builder.appendMentionColored(
    text: String,
    mentionColor: (String) -> androidx.compose.ui.graphics.Color?,
) {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        // A token starts at a nick char, or an `@` immediately followed by a nick char.
        val atMention = c == '@' && i + 1 < n && isNickChar(text[i + 1])
        if (isNickChar(c) || atMention) {
            val start = i
            if (atMention) i++ // skip the leading '@' when scanning the nick body
            val nickStart = i
            while (i < n && isNickChar(text[i])) i++
            val nick = text.substring(nickStart, i)
            val color = mentionColor(nick)
            if (color != null) {
                // Color the whole token including a leading '@' so the mention reads as one unit.
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) {
                    append(text.substring(start, i))
                }
            } else {
                append(text.substring(start, i))
            }
        } else {
            // Non-nick run (whitespace/punctuation): append up to the next potential token start.
            val start = i
            while (i < n && !isNickChar(text[i]) && text[i] != '@') i++
            if (i == start) i++ // lone '@' not starting a mention
            append(text.substring(start, i))
        }
    }
}

/**
 * Subtle friend highlight behind the sender name (plans/13 confirmed decision #4): a low-alpha
 * theme-primary rounded pill layered under the nick color. Distinct enough to spot, quiet enough
 * not to fight the nick color or the bubble background.
 */
@Composable
internal fun Modifier.friendNickTint(): Modifier = this
    .clip(RoundedCornerShape(4.dp))
    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
    .padding(horizontal = 4.dp, vertical = 1.dp)

/** Resolved reply target for the quoted mini-bubble. */
data class ReplyPreviewData(val sender: String, val text: String)

@Composable
internal fun ReplyMiniBubble(reply: ReplyPreviewData, nickColors: NickColorScheme) {
    val accent = nickColors.nick(reply.sender, MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 28.dp)
                .background(accent),
        )
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                text = reply.sender,
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = reply.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Format [ms] as a short clock time honoring the device 12/24-hour preference (plans/15 #28). The
 * [JavaDateFormat] is hoisted per Compose context via [LocalContext] and rebuilt only when the
 * device setting/locale changes, avoiding a per-row/per-recomposition allocation.
 */
@Composable
internal fun formatTime(ms: Long): String {
    return rememberMessageTimeFormatter()(ms)
}

/**
 * One formatter per message-list composition. Android's 12/24-hour lookup can cross framework
 * settings, and DateFormat is stateful; sharing it on the UI thread avoids repeating both costs for
 * every row first composed in a fling.
 */
@Composable
internal fun rememberMessageTimeFormatter(): (Long) -> String {
    val context = LocalContext.current
    val locale = java.util.Locale.getDefault()
    val is24 = remember(context, locale) { DateFormat.is24HourFormat(context) }
    val formatter = remember(is24, locale) {
        // getTimeFormat honors the 12/24h system setting; not thread-safe but only used on the UI thread.
        DateFormat.getTimeFormat(context) as? JavaDateFormat
            ?: JavaDateFormat.getTimeInstance(JavaDateFormat.SHORT)
    }
    return remember(formatter) {
        { ms: Long -> formatter.format(java.util.Date(ms)) }
    }
}

@Preview
@Composable
private fun MessageBubbleOthersPreview() {
    MotdTheme {
        Column {
            MessageBubble(
                sender = "alice", text = "hey, welcome to the channel!",
                timeMs = System.currentTimeMillis(), isSelf = false,
                kind = MessageKind.PRIVMSG, showSender = true,
                reactions = listOf(ReactionChip("👍", 2, mine = false)),
            )
            MessageBubble(
                sender = "alice", text = "grouped follow-up",
                timeMs = System.currentTimeMillis(), isSelf = false,
                kind = MessageKind.PRIVMSG, showSender = false,
            )
        }
    }
}

@Preview
@Composable
private fun MessageBubbleSelfPreview() {
    MotdTheme {
        MessageBubble(
            sender = "me", text = "sending a reply", timeMs = System.currentTimeMillis(),
            isSelf = true, kind = MessageKind.PRIVMSG, showSender = false,
            reply = ReplyPreviewData("alice", "welcome to the channel!"),
        )
    }
}

@Preview
@Composable
private fun MessageBubbleActionPreview() {
    MotdTheme {
        MessageBubble(
            sender = "bob", text = "waves hello", timeMs = System.currentTimeMillis(),
            isSelf = false, kind = MessageKind.ACTION, showSender = true,
        )
    }
}

@Preview
@Composable
private fun MessageBubbleFailedPreview() {
    MotdTheme {
        MessageBubble(
            sender = "me", text = "this one failed", timeMs = System.currentTimeMillis(),
            isSelf = true, kind = MessageKind.PRIVMSG, showSender = false, failed = true,
        )
    }
}

@Preview
@Composable
private fun MessageBubbleTwoLinePreview() {
    // TWO_LINE theme so MessageBubble routes into TwoLineMessageRow.
    MotdTheme(layoutDensity = io.github.trevarj.motd.data.prefs.LayoutDensity.TWO_LINE) {
        Box(Modifier.width(280.dp)) {
            Column {
                MessageBubble(
                    sender = "alice",
                    text = "A narrow first message that wraps onto multiple lines without leaving the nick column.",
                    timeMs = 0L,
                    isSelf = false,
                    kind = MessageKind.PRIVMSG,
                    showSender = true,
                    reply = ReplyPreviewData("bob", "Earlier message"),
                    imageUrl = "https://example.com/image.png",
                    linkPreview = LinkPreview(
                        url = "https://example.com",
                        title = "Example preview",
                        description = "Card alignment",
                        imageUrl = null,
                        siteName = "Example",
                    ),
                    reactions = listOf(ReactionChip("👍", 2, mine = false)),
                )
                MessageBubble(
                    sender = "alice",
                    text = "Grouped continuation uses the exact same body column.",
                    timeMs = 0L,
                    isSelf = false,
                    kind = MessageKind.PRIVMSG,
                    showSender = false,
                )
            }
        }
    }
}

@Preview
@Composable
private fun MessageBubbleNoticePreview() {
    MotdTheme {
        MessageBubble(
            sender = "ChanServ", text = "This channel is registered.",
            timeMs = System.currentTimeMillis(), isSelf = false,
            kind = MessageKind.NOTICE, showSender = true,
        )
    }
}
