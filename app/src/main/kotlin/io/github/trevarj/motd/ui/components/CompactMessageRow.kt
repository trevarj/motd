package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.LocalConversationFontScale
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import androidx.compose.ui.res.stringResource

/** Alpha for the per-nick row background wash in COMPACT density: strong enough to band messages
 *  by speaker, faint enough to stay readable in light and dark themes. */
private const val COMPACT_ROW_TINT_ALPHA = 0.10f

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
    formattedTime: String,
    isSelf: Boolean,
    kind: MessageKind,
    nickColors: NickColorScheme,
    modifier: Modifier = Modifier,
    hasMention: Boolean = false,
    // Group continuation flag: false = omit the `nick:` prefix so the line reads as a continued run.
    showSender: Boolean = true,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    // Normalized nicks known in the current buffer; @mentions of these are colored (plans/17).
    knownNicks: Set<String> = emptySet(),
    onLongPress: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    onSenderClick: (() -> Unit)? = null,
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    val spacing = LocalSpacing.current
    val conversationFontScale = LocalConversationFontScale.current
    val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurfaceVariant)
    // Self text stays on the default body color; others too (IRC is uniform). The nick carries color.
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nickFontSize = MaterialTheme.typography.bodyMedium.fontSize
    // Friend highlight: a low-alpha primary background behind the nick, layered under the nick color.
    val friendTint = if (senderIsFriend) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Unspecified
    // Per-nick row wash: a faint tint of the sender's own nick color behind the whole row so runs of
    // messages are visually trackable by speaker (stable per nick — no fragile list-wide parity).
    val rowTint = if (hasMention) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = MENTION_ROW_TINT_ALPHA)
    } else {
        nameColor.copy(alpha = COMPACT_ROW_TINT_ALPHA)
    }

    // The `nick: text` (or `* nick text`) content is a single flowing AnnotatedString so it wraps as
    // one paragraph like a real IRC line, with the nick colored (+ friend tint) and URLs linkified.
    // Continuation lines (showSender == false) drop the `nick:` prefix so a run reads as one speaker.
    // Known-nick @mentions in the body are colored via the memoized resolver.
    val mentionColor = rememberMentionColor(knownNicks, nickColors)
    val line = remember(
        sender, text, kind, nameColor, bodyColor, linkColor, senderIsFriend, showSender,
        mentionColor, codeBackground, codeColor, nickFontSize,
    ) {
        buildCompactLine(
            sender, text, kind, nameColor, bodyColor, linkColor, friendTint, showSender,
            mentionColor, codeBackground, codeColor, nickFontSize = nickFontSize,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Tint fills the full row width (behind the horizontal padding) so the speaker band is
            // unbroken edge to edge.
            .background(rowTint)
            .combinedClickable(
                // Null lazily avoids an interaction object because this row has no indication.
                interactionSource = null,
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
                MessageStatusIcon(isSelf = isSelf, pending = pending, failed = failed)
                Text(
                    text = formattedTime,
                    fontSize = 10.sp * conversationFontScale,
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
 * is colored (+ optional friend background); URLs in the body are linkified and known-nick
 * @mentions ([mentionColor]) are colored. When [showSender] is false the row is a group
 * continuation: the `nick:`/`* nick`/`-nick-` prefix is dropped so the run reads as one speaker
 * (the body alone is rendered). Pure/testable.
 */
internal fun buildCompactLine(
    sender: String,
    text: String,
    kind: MessageKind,
    nameColor: Color,
    bodyColor: Color,
    linkColor: Color,
    friendTint: Color,
    showSender: Boolean = true,
    mentionColor: (String) -> Color? = { null },
    codeBackground: Color = Color.Unspecified,
    codeColor: Color = Color.Unspecified,
    nickFontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
): AnnotatedString = buildAnnotatedString {
    val nickStyle = SpanStyle(
        color = nameColor,
        fontSize = nickFontSize,
        fontWeight = FontWeight.Bold,
        background = friendTint,
    )
    // Continuation lines omit the sender prefix entirely; ACTION keeps the leading `* ` marker so an
    // action line stays recognizable even mid-run.
    if (showSender) {
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
    } else if (kind == MessageKind.ACTION) {
        withStyle(SpanStyle(color = bodyColor)) { append("* ") }
    }
    appendRichText(
        text = text,
        plainStyle = SpanStyle(color = bodyColor),
        linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        codeStyle = SpanStyle(
            color = codeColor,
            background = codeBackground,
            fontFamily = FontFamily.Monospace,
            fontStyle = FontStyle.Normal,
        ),
        mentionColor = mentionColor,
    )
}

@Preview
@Composable
private fun CompactMessageRowPreview() {
    // COMPACT theme so the bubble renderer routes into CompactMessageRow.
    MotdTheme(layoutDensity = io.github.trevarj.motd.data.prefs.LayoutDensity.COMPACT) {
        Column {
            MessageBubble(
                sender = "alice", text = "me: welcome to the channel!",
                timeMs = 0L, isSelf = false, kind = MessageKind.PRIVMSG, showSender = true,
                hasMention = true,
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
