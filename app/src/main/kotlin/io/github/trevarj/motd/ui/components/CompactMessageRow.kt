package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import androidx.compose.ui.res.stringResource

/**
 * Classic single-line IRC rendering used in COMPACT density: `nick: text` on one wrapping line,
 * nick colored via [NickColorScheme.nick] (friend tint preserved), a small trailing timestamp, no
 * avatar, no bubble, minimal vertical padding, uniformly left-aligned (IRC has no own-message
 * right-alignment). ACTION renders as `* nick text` (italic); NOTICE gets a subtle `-nick-` marker.
 *
 * Sender grouping is per-line here (every row shows its nick), matching irssi/HexChat. Reply,
 * inline image, link preview, reactions, and pending/failed decorations are still shown, just
 * compactly (reactions/image/preview flow onto following lines).
 */
@Composable
internal fun CompactMessageRow(
    sender: String,
    text: String,
    timeMs: Long,
    isSelf: Boolean,
    kind: MessageKind,
    nickColors: NickColorScheme,
    modifier: Modifier = Modifier,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    onLongPress: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    onSenderClick: (() -> Unit)? = null,
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    val interaction = remember { MutableInteractionSource() }
    val spacing = LocalSpacing.current
    val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurfaceVariant)
    // Self text stays on the default body color; others too (IRC is uniform). The nick carries color.
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    // Friend highlight: a low-alpha primary background behind the nick, layered under the nick color.
    val friendTint = if (senderIsFriend) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Unspecified

    // The `nick: text` (or `* nick text`) content is a single flowing AnnotatedString so it wraps as
    // one paragraph like a real IRC line, with the nick colored (+ friend tint) and URLs linkified.
    val line = remember(sender, text, kind, nameColor, bodyColor, linkColor, senderIsFriend) {
        buildCompactLine(sender, text, kind, nameColor, bodyColor, linkColor, friendTint)
    }

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
            .padding(horizontal = 12.dp, vertical = spacing.compactRowVPad),
    ) {
        reply?.let { ReplyMiniBubble(it, nickColors) }

        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                // ACTION reads as italic prose the way irssi shows `* nick …`.
                fontStyle = if (kind == MessageKind.ACTION) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier
                    .weight(1f)
                    // Tapping the row's nick opens the nick sheet; the whole line is the target since
                    // the nick is inline (bubble-style per-name tap isn't meaningful on one line).
                    .let { if (onSenderClick != null) it.padding(end = 6.dp) else it },
            )
            // Trailing timestamp keeps the IRC "right gutter" feel without a bubble.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (failed) FailedIcon()
                if (pending && !failed) PendingIcon()
                Text(
                    text = formatTime(timeMs),
                    fontSize = 10.sp,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .widthIn(max = 280.dp)
                    .heightIn(max = 200.dp)
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { onImageClick(url) }, onLongClick = onLongPress),
            )
        }

        if (linkPreview != null || linkPreviewLoading) {
            Box(Modifier.padding(top = 2.dp)) {
                LinkPreviewCard(preview = linkPreview, loading = linkPreviewLoading, onClick = onLinkPreviewClick)
            }
        }

        // Reactions on the next line (kept, just compact).
        ReactionRow(reactions = reactions, onReact = onReact)
    }
}

/**
 * Build the single-line `nick: text` content for COMPACT rendering. Prefix depends on kind:
 * ACTION → `* nick ` (no colon), NOTICE → `-nick- ` (subtle marker), else `nick: `. The nick span
 * is colored (+ optional friend background); URLs in the body are linkified. Pure/testable.
 */
internal fun buildCompactLine(
    sender: String,
    text: String,
    kind: MessageKind,
    nameColor: Color,
    bodyColor: Color,
    linkColor: Color,
    friendTint: Color,
): AnnotatedString = buildAnnotatedString {
    val nickStyle = SpanStyle(
        color = nameColor,
        fontWeight = FontWeight.SemiBold,
        background = friendTint,
    )
    when (kind) {
        MessageKind.ACTION -> {
            withStyle(SpanStyle(color = bodyColor)) { append("* ") }
            withStyle(nickStyle) { append(sender) }
            append(" ")
        }
        MessageKind.NOTICE -> {
            // irssi-style notice marker: `-nick-`.
            withStyle(nickStyle) { append("-$sender-") }
            append(" ")
        }
        else -> {
            withStyle(nickStyle) { append(sender) }
            withStyle(SpanStyle(color = bodyColor)) { append(": ") }
        }
    }
    appendLinkified(text, bodyColor, linkColor)
}

/** Append [text] with http(s) URLs turned into tappable links, rest in [bodyColor]. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLinkified(
    text: String,
    bodyColor: Color,
    linkColor: Color,
) {
    val urls = extractUrls(text)
    if (urls.isEmpty()) {
        withStyle(SpanStyle(color = bodyColor)) { append(text) }
        return
    }
    val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    var cursor = 0
    for (url in urls) {
        val at = text.indexOf(url, cursor)
        if (at < 0) continue
        withStyle(SpanStyle(color = bodyColor)) { append(text.substring(cursor, at)) }
        withLink(LinkAnnotation.Url(url)) { withStyle(linkStyle) { append(url) } }
        cursor = at + url.length
    }
    if (cursor < text.length) withStyle(SpanStyle(color = bodyColor)) { append(text.substring(cursor)) }
}

@Preview
@Composable
private fun CompactMessageRowPreview() {
    // COMPACT theme so the bubble renderer routes into CompactMessageRow.
    MotdTheme(layoutDensity = io.github.trevarj.motd.data.prefs.LayoutDensity.COMPACT) {
        Column {
            MessageBubble(
                sender = "alice", text = "hey, welcome to the channel!",
                timeMs = 0L, isSelf = false, kind = MessageKind.PRIVMSG, showSender = true,
                senderIsFriend = true,
                reactions = listOf(ReactionChip("👍", 2, mine = false)),
            )
            MessageBubble(
                sender = "alice", text = "check https://example.com out",
                timeMs = 0L, isSelf = false, kind = MessageKind.PRIVMSG, showSender = false,
            )
            MessageBubble(
                sender = "bob", text = "waves hello", timeMs = 0L,
                isSelf = false, kind = MessageKind.ACTION, showSender = true,
            )
            MessageBubble(
                sender = "ChanServ", text = "This channel is registered.", timeMs = 0L,
                isSelf = false, kind = MessageKind.NOTICE, showSender = true,
            )
            MessageBubble(
                sender = "me", text = "my own reply, still left-aligned in IRC style",
                timeMs = 0L, isSelf = true, kind = MessageKind.PRIVMSG, showSender = false,
            )
        }
    }
}
