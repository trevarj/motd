package io.github.trevarj.motd.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
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
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.nickColor
import java.text.DateFormat as JavaDateFormat

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
    failed: Boolean = false,
    reply: ReplyPreviewData? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    onLongPress: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    // A shared no-op onClick with a null indication removes the dead ripple on plain taps; long-press
    // is the only action entry, labeled for TalkBack (plans/15 #31).
    val interaction = remember { MutableInteractionSource() }

    // ACTION renders as centered-left italic text, no bubble (plans/07). Still carries reactions +
    // failed + timestamp decorations (plans/15 #16).
    if (kind == MessageKind.ACTION) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {},
                    onLongClick = onLongPress,
                    onLongClickLabel = actionsLabel,
                )
                .padding(horizontal = 16.dp, vertical = 3.dp),
        ) {
            reply?.let { ReplyMiniBubble(it, isAppliedThemeDark()) }
            Text(
                text = "* $sender $text",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (failed) FailedIcon()
                Text(
                    text = formatTime(timeMs),
                    fontSize = 10.sp,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            ReactionRow(reactions = reactions, onReact = onReact)
        }
        return
    }

    val isDark = isAppliedThemeDark()
    // Window width in dp = container px / density; keeps the 0.78 bubble max-width behavior.
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    val maxWidth = with(density) { (containerWidthPx * 0.78f).toDp() }
    val bubbleColor = if (isSelf) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isSelf) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    // Tighten the inner (grouped) top corner: 4dp when this bubble continues a group.
    val topCorner = if (showSender) 18.dp else 4.dp
    val shape = if (isSelf) {
        RoundedCornerShape(topStart = 18.dp, topEnd = topCorner, bottomEnd = 4.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = topCorner, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        // Left avatar column for others, only on a group's first bubble.
        if (!isSelf) {
            if (showSender) {
                Avatar(name = sender, size = 32.dp, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
            } else {
                Box(Modifier.width(40.dp))
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(bubbleColor)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {},
                    onLongClick = onLongPress,
                    onLongClickLabel = actionsLabel,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (showSender && !isSelf) {
                Text(
                    text = sender,
                    color = nickColor(sender, isDark),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (kind == MessageKind.NOTICE) {
                Text(
                    text = stringResource(R.string.chat_notice_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            reply?.let { ReplyMiniBubble(it, isDark) }

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
                // (plans/15 #11); LinkAnnotation.Url uses the platform URI open handler.
                Text(
                    text = linkifiedBody(text, MaterialTheme.colorScheme.primary),
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
                if (failed) FailedIcon()
                Text(
                    text = formatTime(timeMs),
                    fontSize = 10.sp,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else textColor.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            ReactionRow(reactions = reactions, onReact = onReact)
        }
    }
}

/** Small error glyph shown next to the timestamp of a failed message. */
@Composable
private fun FailedIcon() {
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
 * (plans/15 #11). URL boundaries come from [extractUrls], matched left-to-right in the raw text.
 */
private fun linkifiedBody(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    val urls = extractUrls(text)
    if (urls.isEmpty()) return AnnotatedString(text)
    val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    return buildAnnotatedString {
        var cursor = 0
        for (url in urls) {
            val at = text.indexOf(url, cursor)
            if (at < 0) continue
            append(text.substring(cursor, at))
            withLink(LinkAnnotation.Url(url)) {
                withStyle(linkStyle) { append(url) }
            }
            cursor = at + url.length
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

/** Resolved reply target for the quoted mini-bubble. */
data class ReplyPreviewData(val sender: String, val text: String)

@Composable
private fun ReplyMiniBubble(reply: ReplyPreviewData, isDark: Boolean) {
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
                .background(nickColor(reply.sender, isDark)),
        )
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                text = reply.sender,
                color = nickColor(reply.sender, isDark),
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
private fun formatTime(ms: Long): String {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val formatter = remember(is24, java.util.Locale.getDefault()) {
        // getTimeFormat honors the 12/24h system setting; not thread-safe but only used on the UI thread.
        DateFormat.getTimeFormat(context) as? JavaDateFormat
            ?: JavaDateFormat.getTimeInstance(JavaDateFormat.SHORT)
    }
    return formatter.format(java.util.Date(ms))
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
private fun MessageBubbleNoticePreview() {
    MotdTheme {
        MessageBubble(
            sender = "ChanServ", text = "This channel is registered.",
            timeMs = System.currentTimeMillis(), isSelf = false,
            kind = MessageKind.NOTICE, showSender = true,
        )
    }
}
