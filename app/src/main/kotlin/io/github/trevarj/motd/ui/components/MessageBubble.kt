package io.github.trevarj.motd.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.model.KeyPath
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.chat.InlineTextSegment
import io.github.trevarj.motd.ui.chat.extractUrls
import io.github.trevarj.motd.ui.chat.parseInlineCode
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.LocalTimestampConfig
import io.github.trevarj.motd.ui.theme.MotdLottieMotion
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdSemanticColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.lottieStrokeColor
import kotlin.math.roundToInt
import java.text.DateFormat as JavaDateFormat

/** Alpha for the per-nick row background wash in TWO_LINE density: matches the COMPACT band so runs
 *  of a nick's messages are trackable, faint enough to stay readable in light and dark themes. */
private const val TWO_LINE_ROW_TINT_ALPHA = 0.10f
internal const val MENTION_ROW_TINT_ALPHA = 0.55f
private const val ACTION_ROW_TINT_ALPHA = 0.22f

internal fun actionAccessibilityLabel(
    sender: String,
    text: String,
): String = if (text.isBlank()) "* $sender" else "* $sender $text"

internal fun bubbleMaxWidthPx(
    availableWidthPx: Int,
    maximumWidthPx: Int,
): Int =
    minOf(
        availableWidthPx.coerceAtLeast(0),
        maximumWidthPx.coerceAtLeast(0),
        (availableWidthPx.coerceAtLeast(0) * 0.82f).roundToInt(),
    )

internal data class MessageBubbleRoleColors(
    val container: Color,
    val content: Color,
)

/** How far an own bubble's container is pulled off `primaryContainer` toward the primary accent. */
private const val SELF_BUBBLE_ACCENT_BLEND = 0.28f

/**
 * Dynamic/accessible schemes flatten `primaryContainer` toward `surfaceContainerHigh`; blend back
 * toward the accent and re-fit the ink against the color actually painted.
 */
private fun selfBubbleRoleColors(scheme: ColorScheme): MessageBubbleRoleColors {
    val container = lerp(scheme.primaryContainer, scheme.primary, SELF_BUBBLE_ACCENT_BLEND)
    return MessageBubbleRoleColors(container, ensureContrast(scheme.onPrimaryContainer, listOf(container)))
}

internal fun messageBubbleRoleColors(
    scheme: ColorScheme,
    isSelf: Boolean,
    mentionHighlighted: Boolean,
    kind: MessageKind,
    semantic: MotdSemanticColors,
): MessageBubbleRoleColors =
    when {
        mentionHighlighted && !isSelf -> {
            // The theme's notice/warning role, not a bespoke color: a mention is an attention cue,
            // and warning already reads that way without success's positive connotation.
            MessageBubbleRoleColors(
                semantic.warningContainer,
                semantic.onWarningContainer,
            )
        }

        isSelf -> {
            selfBubbleRoleColors(scheme)
        }

        kind == MessageKind.NOTICE -> {
            MessageBubbleRoleColors(
                scheme.tertiaryContainer,
                scheme.onTertiaryContainer,
            )
        }

        else -> {
            MessageBubbleRoleColors(scheme.surfaceContainerHigh, scheme.onSurface)
        }
    }

/**
 * Measure against the containing chat pane, not the whole device window.
 *
 * Internal because the send-flight ghost has to lay out at exactly the width its landing bubble
 * will take. Reimplementing the rule there would let the two drift apart silently.
 */
internal fun Modifier.chatBubbleWidth(): Modifier =
    layout { measurable, constraints ->
        val availableWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                560.dp.roundToPx()
            }
        val maximumWidth =
            bubbleMaxWidthPx(availableWidth, 560.dp.roundToPx())
                .coerceAtLeast(constraints.minWidth)
        val placeable =
            measurable.measure(
                constraints.copy(minWidth = 0, maxWidth = maximumWidth),
            )
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

/**
 * Tap + long-press shared by every message density. Indication follows [onClick]: a real tap target
 * (the global feed jump) ripples; an inert chat row keeps none, since its tap does nothing.
 */
@Composable
internal fun Modifier.messageRowClicks(
    onClick: (() -> Unit)?,
    onClickLabel: String?,
    onLongPress: () -> Unit,
    onLongPressLabel: String,
): Modifier =
    combinedClickable(
        // Null lazily avoids an interaction object until something actually needs one.
        interactionSource = null,
        indication = if (onClick != null) LocalIndication.current else null,
        onClick = onClick ?: {},
        onClickLabel = onClickLabel,
        onLongClick = onLongPress,
        onLongClickLabel = onLongPressLabel,
    )

/** Persistent, non-animated mention marker shared by every message density. */
private fun Modifier.mentionHighlight(accent: Color): Modifier =
    drawWithContent {
        drawContent()
        val railWidth = 3.dp.toPx()
        val inset = 2.dp.toPx()
        val railHeight = (size.height - inset * 2).coerceAtLeast(0f)
        val railX =
            if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) {
                0f
            } else {
                size.width - railWidth
            }
        drawRoundRect(
            color = accent,
            topLeft = Offset(railX, inset),
            size = Size(railWidth, railHeight),
            cornerRadius = CornerRadius(railWidth / 2f, railWidth / 2f),
        )
    }

/**
 * One chat bubble. Handles the four rendered kinds (PRIVMSG bubble, NOTICE labelled bubble, ACTION
 * italic no-bubble, plus reply/image/reactions decorations). System-event kinds are rendered by
 * [SystemEventPill] upstream, not here.
 *
 * Grouping: [showSender] identifies a group's first bubble. COMFORTABLE own messages put their nick
 * in the status footer; other sender headers remain nick-colored. A silent nick change (e.g. an
 * identify failure bouncing you to Guest-1234) stays visible on your own messages too. Only the
 * avatar stays other-senders-only. Own bubbles are right-aligned on the primary accent; others left
 * `surfaceContainerHigh`. Corner radii tighten on
 * the grouped inner edge.
 */
internal fun botDisplayName(
    sender: String,
    isBot: Boolean,
): String = if (isBot) "$sender 🤖" else sender

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
    displaySender: String = sender,
    isBot: Boolean = false,
    hasMention: Boolean = false,
    networkId: Long? = null,
    senderAccount: String? = null,
    formattedTime: String? = null,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    onReplyClick: (() -> Unit)? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    linkPreviewResolved: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    // Normalized nicks known in the current buffer; @mentions of these in the body are colored with
    // the nick's own color. Empty = no mention coloring.
    knownNicks: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    onLongPress: () -> Unit = {},
    // Plain tap; null (chat) = inert and no press feedback, non-null (global feed) = a jump target
    // that ripples. Label it so TalkBack names the destination.
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    // Tapping the sender name/avatar opens the nick sheet; null (self / non-first bubbles) = inert.
    onSenderClick: (() -> Unit)? = null,
) {
    // Production timelines pass a string from one list-scoped formatter. The fallback keeps
    // previews/direct callers source-compatible without making every real row query system time
    // settings and construct its own formatter.
    val displayedTime = formattedTime ?: formatTime(timeMs)
    // Density tokens + nick-color scheme flow through CompositionLocals; no signature churn.
    val spacing = LocalSpacing.current
    val nickColors = LocalNickColors.current
    // EventProcessor never marks self messages as mentions. Keep the UI defensive so a malformed
    // or legacy row cannot style an own message as an incoming highlight.
    val mentionHighlighted = hasMention && !isSelf
    val renderedModifier =
        if (mentionHighlighted && kind != MessageKind.ACTION) {
            modifier.mentionHighlight(accent = LocalMotdSemanticColors.current.warning)
        } else {
            modifier
        }

    if (kind == MessageKind.ACTION) {
        // COMFORTABLE renders emotes as a thin content-sized bubble: a small inline avatar stands
        // in for the `* ` marker and the nick opens the flowing message text; COMPACT and TWO_LINE
        // keep the classic full-width `* nick action` banner row.
        if (spacing.compact || spacing.twoLine) {
            ActionMessageRow(
                sender = sender,
                displaySender = displaySender,
                networkId = networkId,
                text = text,
                formattedTime = displayedTime,
                isSelf = isSelf,
                isBot = isBot,
                nickColors = nickColors,
                modifier = renderedModifier,
                hasMention = mentionHighlighted,
                senderIsFriend = senderIsFriend,
                failed = failed,
                pending = pending,
                reply = reply,
                onReplyClick = onReplyClick,
                imageUrl = imageUrl,
                linkPreview = linkPreview,
                linkPreviewLoading = linkPreviewLoading,
                linkPreviewResolved = linkPreviewResolved,
                reactions = reactions,
                knownNicks = knownNicks,
                identityRules = identityRules,
                onLongPress = onLongPress,
                onClick = onClick,
                onClickLabel = onClickLabel,
                onReact = onReact,
                onImageClick = onImageClick,
                onLinkPreviewClick = onLinkPreviewClick,
                onSenderClick = onSenderClick,
            )
        } else {
            ComfortableActionBubble(
                sender = sender,
                displaySender = displaySender,
                text = text,
                formattedTime = displayedTime,
                isSelf = isSelf,
                isBot = isBot,
                nickColors = nickColors,
                spacing = spacing,
                networkId = networkId,
                senderAccount = senderAccount,
                modifier = renderedModifier,
                showSender = showSender,
                hasMention = mentionHighlighted,
                senderIsFriend = senderIsFriend,
                failed = failed,
                pending = pending,
                reply = reply,
                onReplyClick = onReplyClick,
                imageUrl = imageUrl,
                linkPreview = linkPreview,
                linkPreviewLoading = linkPreviewLoading,
                linkPreviewResolved = linkPreviewResolved,
                reactions = reactions,
                knownNicks = knownNicks,
                identityRules = identityRules,
                onLongPress = onLongPress,
                onClick = onClick,
                onClickLabel = onClickLabel,
                onReact = onReact,
                onImageClick = onImageClick,
                onLinkPreviewClick = onLinkPreviewClick,
                onSenderClick = onSenderClick,
            )
        }
        return
    }

    // COMPACT density = classic single-line IRC rendering (`nick: text`). Delegate the whole row to
    // the inline renderer; bubbles/avatars/alignment are the COMFORTABLE/TWO_LINE paradigm only.
    if (spacing.compact) {
        CompactMessageRow(
            sender = sender,
            displaySender = displaySender,
            networkId = networkId,
            text = text,
            formattedTime = displayedTime,
            isSelf = isSelf,
            isBot = isBot,
            kind = kind,
            nickColors = nickColors,
            modifier = renderedModifier,
            hasMention = mentionHighlighted,
            senderIsFriend = senderIsFriend,
            failed = failed,
            pending = pending,
            reply = reply,
            onReplyClick = onReplyClick,
            imageUrl = imageUrl,
            linkPreview = linkPreview,
            linkPreviewLoading = linkPreviewLoading,
            linkPreviewResolved = linkPreviewResolved,
            reactions = reactions,
            knownNicks = knownNicks,
            identityRules = identityRules,
            showSender = showSender,
            onLongPress = onLongPress,
            onClick = onClick,
            onClickLabel = onClickLabel,
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
            displaySender = displaySender,
            networkId = networkId,
            senderAccount = senderAccount,
            text = text,
            formattedTime = displayedTime,
            isSelf = isSelf,
            isBot = isBot,
            kind = kind,
            nickColors = nickColors,
            spacing = spacing,
            modifier = renderedModifier,
            hasMention = mentionHighlighted,
            senderIsFriend = senderIsFriend,
            failed = failed,
            pending = pending,
            reply = reply,
            onReplyClick = onReplyClick,
            imageUrl = imageUrl,
            linkPreview = linkPreview,
            linkPreviewLoading = linkPreviewLoading,
            linkPreviewResolved = linkPreviewResolved,
            reactions = reactions,
            knownNicks = knownNicks,
            identityRules = identityRules,
            showSender = showSender,
            onLongPress = onLongPress,
            onClick = onClick,
            onClickLabel = onClickLabel,
            onReact = onReact,
            onImageClick = onImageClick,
            onLinkPreviewClick = onLinkPreviewClick,
            onSenderClick = onSenderClick,
        )
        return
    }

    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant

    val scheme = MaterialTheme.colorScheme
    val semantic = LocalMotdSemanticColors.current
    // Keyed by color value, not scheme identity: Material3 mutates its retained ColorScheme in place.
    val bubbleRoles =
        remember(
            scheme.primaryContainer,
            scheme.primary,
            scheme.onPrimaryContainer,
            isSelf,
            mentionHighlighted,
            kind,
            semantic,
        ) { messageBubbleRoleColors(scheme, isSelf, mentionHighlighted, kind, semantic) }
    val bubbleColor = bubbleRoles.container
    val textColor = bubbleRoles.content
    // Tighten the inner grouped edge while retaining the shared outer silhouette.
    val groupedCorner = spacing.bubbleGroupedCorner
    val topCorner = if (showSender) spacing.bubbleCorner else groupedCorner
    val shape =
        if (isSelf) {
            RoundedCornerShape(topStart = spacing.bubbleCorner, topEnd = topCorner, bottomEnd = groupedCorner, bottomStart = spacing.bubbleCorner)
        } else {
            RoundedCornerShape(topStart = topCorner, topEnd = spacing.bubbleCorner, bottomEnd = spacing.bubbleCorner, bottomStart = groupedCorner)
        }

    Row(
        modifier =
            renderedModifier.fillMaxWidth().padding(
                horizontal = spacing.messageOuterHPad,
                vertical = spacing.bubbleRowVPad,
            ),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        // Left avatar column for others, only on a group's first bubble. With avatars hidden the
        // whole column goes, spacer included, so bubbles use the full row width.
        if (!isSelf && !avatarsHidden()) {
            if (showSender) {
                val avatarMod =
                    Modifier
                        .padding(end = 8.dp, top = 2.dp)
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
            modifier =
                Modifier
                    .chatBubbleWidth()
                    .clip(shape)
                    .background(bubbleColor)
                    .messageRowClicks(
                        onClick = onClick,
                        onClickLabel = onClickLabel,
                        onLongPress = onLongPress,
                        onLongPressLabel = actionsLabel,
                    ).padding(horizontal = spacing.bubbleInnerHPad, vertical = spacing.bubbleInnerVPad),
        ) {
            if (showSender && !isSelf) {
                val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (onSenderClick != null) Modifier.clickable(onClick = onSenderClick) else Modifier,
                ) {
                    Text(
                        text = botDisplayName(displaySender, isBot),
                        color = nameColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        // Friend tint: a subtle theme-primary rounded background behind the name,
                        // layered under the nick color.
                        modifier = if (senderIsFriend) Modifier.friendNickTint() else Modifier,
                    )
                    if (senderIsFriend) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = nameColor,
                            modifier =
                                Modifier
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
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Medium,
                )
            }

            reply?.let { ReplyMiniBubble(it, nickColors, onReplyClick) }

            if (text.isNotBlank()) {
                // Linkify http(s) URLs so the body is tappable even when the preview fails
                // ; LinkAnnotation.Url uses the platform URI open handler. Known-nick
                // @mentions are colored with the nick's own color. Body build is memoized per
                // (text, mention inputs) so it doesn't re-run every recomposition/scroll frame.
                val linkColor =
                    if (isSelf || mentionHighlighted || kind == MessageKind.NOTICE) {
                        textColor
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                val mentionColor = rememberMentionColor(knownNicks, nickColors, identityRules)
                val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
                val body =
                    remember(
                        text,
                        imageUrl,
                        linkColor,
                        mentionsActive,
                        mentionColor,
                        codeBackground,
                        codeColor,
                    ) {
                        linkifiedBody(
                            text,
                            linkColor,
                            mentionsActive,
                            mentionColor,
                            codeBackground,
                            codeColor,
                        ).withoutMediaPreviewUrl(imageUrl)
                    }
                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            imageUrl?.let { url ->
                InlineMediaPreview(
                    url = url,
                    networkId = networkId,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                    // Reserve a 4:3 box until the bitmap lands so rows don't jump the reversed-list
                    // anchor.
                    modifier =
                        Modifier
                            .padding(vertical = 2.dp)
                            .heightIn(max = 280.dp)
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(12.dp)),
                )
                MediaOriginCaption(
                    url,
                    color = if (isSelf || mentionHighlighted || kind == MessageKind.NOTICE) textColor else scheme.onSurfaceVariant,
                )
            }

            if (shouldShowLinkPreview(linkPreview, linkPreviewLoading, linkPreviewResolved)) {
                Box(Modifier.padding(top = 4.dp)) {
                    LinkPreviewCard(
                        preview = linkPreview,
                        networkId = networkId,
                        loading = linkPreviewLoading,
                        onClick = onLinkPreviewClick,
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.End).testTag("message_metadata"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val metadataColor = if (failed) MaterialTheme.colorScheme.error else textColor
                if (isSelf && showSender) {
                    Text(
                        text = botDisplayName(displaySender, isBot),
                        color = textColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .padding(end = 6.dp)
                                .testTag("self_sender_label"),
                    )
                }
                MessageStatusIcon(
                    isSelf = isSelf,
                    pending = pending,
                    failed = failed,
                    contentColor = metadataColor,
                )
                if (LocalTimestampConfig.current.show) {
                    Text(
                        text = displayedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = metadataColor,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }

            ReactionRow(reactions = reactions, onReact = onReact, isSelf = isSelf)
        }
    }
}

/**
 * COMFORTABLE ACTION renderer: a thin content-sized tinted bubble. A small inline avatar stands in
 * for the `* ` marker; the nick opens the message text itself (bold, upright) with the italic body
 * flowing after it, via [buildActionLine] without the star. Keeps the tertiary tint so emotes stay
 * visually distinct from ordinary bubbles; links/mentions/code keep working as in a normal message.
 */
@Composable
private fun ComfortableActionBubble(
    sender: String,
    displaySender: String,
    text: String,
    formattedTime: String,
    isSelf: Boolean,
    isBot: Boolean,
    nickColors: NickColorScheme,
    spacing: io.github.trevarj.motd.ui.theme.MotdSpacing,
    networkId: Long?,
    senderAccount: String?,
    modifier: Modifier = Modifier,
    showSender: Boolean = true,
    hasMention: Boolean = false,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    onReplyClick: (() -> Unit)? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    linkPreviewResolved: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    onLongPress: () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    onSenderClick: (() -> Unit)? = null,
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    val actionDescription = stringResource(R.string.chat_action_message)
    val actionLabel = remember(displaySender, text) { actionAccessibilityLabel(displaySender, text) }
    val semanticColors = LocalMotdSemanticColors.current
    val rowColor =
        if (hasMention) {
            semanticColors.warningContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    val bodyColor =
        if (hasMention) {
            semanticColors.onWarningContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        }
    val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurface)
    val linkColor = bodyColor
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val mentionColor = rememberMentionColor(knownNicks, nickColors, identityRules)
    val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
    val friendTint =
        if (senderIsFriend) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Unspecified
        }
    val senderLink =
        remember(onSenderClick) {
            onSenderClick?.let { callback ->
                LinkAnnotation.Clickable(
                    tag = "action-sender",
                    linkInteractionListener = { callback() },
                )
            }
        }

    // Tighten the inner (grouped) top corner like an ordinary bubble. ACTION always opens a new
    // group (showsSender), so this is the full corner in practice, but mirror the bubble logic.
    val groupedCorner = spacing.bubbleGroupedCorner
    val topCorner = if (showSender) spacing.bubbleCorner else groupedCorner
    val shape =
        if (isSelf) {
            RoundedCornerShape(topStart = spacing.bubbleCorner, topEnd = topCorner, bottomEnd = groupedCorner, bottomStart = spacing.bubbleCorner)
        } else {
            RoundedCornerShape(topStart = topCorner, topEnd = spacing.bubbleCorner, bottomEnd = spacing.bubbleCorner, bottomStart = groupedCorner)
        }

    // One flowing `nick action` paragraph: bold upright tappable nick, italic rich-text body. No
    // `* ` prefix — the inline avatar is the emote marker in this layout. When avatars are hidden
    // the marker would vanish with them, so the classic `* ` prefix comes back instead.
    val hideAvatar = avatarsHidden()
    val actionLine =
        remember(
            displaySender,
            isBot,
            text,
            imageUrl,
            nameColor,
            bodyColor,
            linkColor,
            friendTint,
            mentionsActive,
            mentionColor,
            codeBackground,
            codeColor,
            senderLink,
            hideAvatar,
        ) {
            buildActionLine(
                sender = botDisplayName(displaySender, isBot),
                text = text,
                accentColor = bodyColor,
                nameColor = nameColor,
                bodyColor = bodyColor,
                linkColor = linkColor,
                friendTint = friendTint,
                mentionsActive = mentionsActive,
                mentionColor = mentionColor,
                codeBackground = codeBackground,
                codeColor = codeColor,
                senderLink = senderLink,
                includeStar = hideAvatar,
            ).withoutMediaPreviewUrl(imageUrl)
        }

    Row(
        modifier =
            modifier.fillMaxWidth().padding(
                horizontal = spacing.messageOuterHPad,
                vertical = spacing.bubbleRowVPad,
            ),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .chatBubbleWidth()
                    .clip(shape)
                    .background(rowColor)
                    .testTag("chat_action_row")
                    .semantics {
                        contentDescription = actionLabel
                        stateDescription = actionDescription
                    }.messageRowClicks(
                        onClick = onClick,
                        onClickLabel = onClickLabel,
                        onLongPress = onLongPress,
                        onLongPressLabel = actionsLabel,
                    ).padding(horizontal = spacing.bubbleInnerHPad, vertical = spacing.bubbleInnerVPad),
        ) {
            reply?.let { ReplyMiniBubble(it, nickColors, onReplyClick) }

            Row(verticalAlignment = Alignment.Top) {
                if (!hideAvatar) {
                    val avatarMod =
                        Modifier
                            .padding(end = 6.dp, top = 2.dp)
                            .size(20.dp)
                            .let { if (onSenderClick != null) it.clickable(onClick = onSenderClick) else it }
                    Avatar(
                        name = sender,
                        size = 20.dp,
                        modifier = avatarMod,
                        networkId = networkId,
                        account = senderAccount,
                    )
                }
                Text(
                    text = actionLine,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontStyle = FontStyle.Italic,
                            fontSynthesis = FontSynthesis.Style,
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("chat_action_text"),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .align(Alignment.Bottom)
                            .padding(start = 8.dp, bottom = 1.dp),
                ) {
                    val metadataColor = if (failed) MaterialTheme.colorScheme.error else bodyColor
                    MessageStatusIcon(
                        isSelf = isSelf,
                        pending = pending,
                        failed = failed,
                        contentColor = metadataColor,
                    )
                    if (LocalTimestampConfig.current.show) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = metadataColor,
                        )
                    }
                }
            }

            imageUrl?.let { url ->
                InlineMediaPreview(
                    url = url,
                    networkId = networkId,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                    modifier =
                        Modifier
                            .padding(top = 2.dp)
                            .widthIn(max = 280.dp)
                            .heightIn(max = 240.dp)
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(10.dp)),
                )
                MediaOriginCaption(url, color = bodyColor, modifier = Modifier.widthIn(max = 280.dp))
            }

            if (shouldShowLinkPreview(linkPreview, linkPreviewLoading, linkPreviewResolved)) {
                Box(Modifier.padding(top = 4.dp)) {
                    LinkPreviewCard(
                        preview = linkPreview,
                        networkId = networkId,
                        loading = linkPreviewLoading,
                        onClick = onLinkPreviewClick,
                    )
                }
            }

            ReactionRow(reactions = reactions, onReact = onReact, isSelf = isSelf)
        }
    }
}

/**
 * COMPACT/TWO_LINE ACTION renderer. The accent band distinguishes emotes from ordinary chat without
 * turning them into another bubble type; the traditional `* nick action` shape remains intact and
 * the body keeps the same rich-text behavior as a normal message.
 */
@Composable
private fun ActionMessageRow(
    sender: String,
    displaySender: String,
    networkId: Long?,
    text: String,
    formattedTime: String,
    isSelf: Boolean,
    isBot: Boolean,
    nickColors: NickColorScheme,
    modifier: Modifier = Modifier,
    hasMention: Boolean = false,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    onReplyClick: (() -> Unit)? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    linkPreviewResolved: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    onLongPress: () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    onSenderClick: (() -> Unit)? = null,
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    val actionDescription = stringResource(R.string.chat_action_message)
    val actionLabel = remember(displaySender, text) { actionAccessibilityLabel(displaySender, text) }
    val spacing = LocalSpacing.current
    val semanticColors = LocalMotdSemanticColors.current
    val accent =
        if (hasMention) {
            semanticColors.warning
        } else {
            MaterialTheme.colorScheme.tertiary
        }
    val rowColor =
        if (hasMention) {
            semanticColors.warningContainer.copy(alpha = MENTION_ROW_TINT_ALPHA)
        } else {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = ACTION_ROW_TINT_ALPHA)
        }
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurface)
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val friendTint =
        if (senderIsFriend) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Unspecified
        }
    val mentionColor = rememberMentionColor(knownNicks, nickColors, identityRules)
    val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
    val senderLink =
        remember(onSenderClick) {
            onSenderClick?.let { callback ->
                LinkAnnotation.Clickable(
                    tag = "action-sender",
                    linkInteractionListener = { callback() },
                )
            }
        }
    val actionLine =
        remember(
            displaySender,
            isBot,
            text,
            imageUrl,
            accent,
            nameColor,
            bodyColor,
            linkColor,
            friendTint,
            mentionsActive,
            mentionColor,
            codeBackground,
            codeColor,
            senderLink,
        ) {
            buildActionLine(
                sender = botDisplayName(displaySender, isBot),
                text = text,
                accentColor = accent,
                nameColor = nameColor,
                bodyColor = bodyColor,
                linkColor = linkColor,
                friendTint = friendTint,
                mentionsActive = mentionsActive,
                mentionColor = mentionColor,
                codeBackground = codeBackground,
                codeColor = codeColor,
                senderLink = senderLink,
            ).withoutMediaPreviewUrl(imageUrl)
        }

    // The caller's modifier carries the stable per-message semantics. Keep the ACTION-specific
    // identity on a nested layout node so its test tag does not compete with that message tag.
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("chat_action_row")
                    .semantics {
                        contentDescription = actionLabel
                        stateDescription = actionDescription
                    }.background(rowColor)
                    .actionAccentRail(accent)
                    .messageRowClicks(
                        onClick = onClick,
                        onClickLabel = onClickLabel,
                        onLongPress = onLongPress,
                        onLongPressLabel = actionsLabel,
                    ).padding(
                        horizontal = spacing.messageOuterHPad,
                        vertical = spacing.actionVPad,
                    ),
        ) {
            reply?.let { ReplyMiniBubble(it, nickColors, onReplyClick) }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = actionLine,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontStyle = FontStyle.Italic,
                            fontSynthesis = FontSynthesis.Style,
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("chat_action_text"),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, bottom = 1.dp),
                ) {
                    MessageStatusIcon(isSelf = isSelf, pending = pending, failed = failed)
                    if (LocalTimestampConfig.current.show) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }

            imageUrl?.let { url ->
                InlineMediaPreview(
                    url = url,
                    networkId = networkId,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                    modifier =
                        Modifier
                            .padding(top = 2.dp)
                            .widthIn(max = 280.dp)
                            .heightIn(max = 240.dp)
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(10.dp)),
                )
                MediaOriginCaption(url, modifier = Modifier.widthIn(max = 280.dp))
            }

            if (shouldShowLinkPreview(linkPreview, linkPreviewLoading, linkPreviewResolved)) {
                Box(Modifier.padding(top = 4.dp)) {
                    LinkPreviewCard(
                        preview = linkPreview,
                        networkId = networkId,
                        loading = linkPreviewLoading,
                        onClick = onLinkPreviewClick,
                    )
                }
            }

            ReactionRow(reactions = reactions, onReact = onReact, isSelf = isSelf)
        }
    }
}

/** Italic rich-text body shared by every ACTION renderer; links/mentions/code keep working. */
internal fun buildActionBody(
    text: String,
    bodyColor: Color,
    linkColor: Color,
    mentionsActive: Boolean = true,
    mentionColor: (String) -> Color? = { null },
    codeBackground: Color = Color.Unspecified,
    codeColor: Color = Color.Unspecified,
): AnnotatedString =
    buildAnnotatedString {
        // fontSynthesis is explicit because some OEM system fonts (e.g. Nothing OS) ship no italic
        // face; with the default null synthesis, FontStyle.Italic is honored by layout but the glyphs
        // render upright. FontSynthesis.Style forces an oblique slant on the plain and link runs so the
        // emote reads as italic regardless of the resolved typeface. Code stays upright by design.
        appendRichText(
            text = text,
            plainStyle =
                SpanStyle(
                    color = bodyColor,
                    fontStyle = FontStyle.Italic,
                    fontSynthesis = FontSynthesis.Style,
                ),
            linkStyle =
                SpanStyle(
                    color = linkColor,
                    fontStyle = FontStyle.Italic,
                    fontSynthesis = FontSynthesis.Style,
                    textDecoration = TextDecoration.Underline,
                ),
            codeStyle =
                SpanStyle(
                    color = codeColor,
                    background = codeBackground,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Normal,
                ),
            mentionColor = if (mentionsActive) mentionColor else ({ null }),
        )
    }

/**
 * Build the styled `* nick action` paragraph shared by every ACTION renderer. COMPACT/TWO_LINE keep
 * the classic `* ` prefix; the COMFORTABLE bubble passes [includeStar] = false because its inline
 * avatar already plays the emote-marker role.
 */
internal fun buildActionLine(
    sender: String,
    text: String,
    accentColor: Color,
    nameColor: Color,
    bodyColor: Color,
    linkColor: Color,
    friendTint: Color = Color.Unspecified,
    mentionsActive: Boolean = true,
    mentionColor: (String) -> Color? = { null },
    codeBackground: Color = Color.Unspecified,
    codeColor: Color = Color.Unspecified,
    senderLink: LinkAnnotation? = null,
    includeStar: Boolean = true,
): AnnotatedString =
    buildAnnotatedString {
        if (includeStar) {
            withStyle(SpanStyle(color = accentColor, fontStyle = FontStyle.Normal)) { append("* ") }
        }
        val senderStyle =
            SpanStyle(
                color = nameColor,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Normal,
                background = friendTint,
            )
        if (senderLink != null) {
            withLink(senderLink) { withStyle(senderStyle) { append(sender) } }
        } else {
            withStyle(senderStyle) { append(sender) }
        }
        append(" ")
        append(
            buildActionBody(
                text = text,
                bodyColor = bodyColor,
                linkColor = linkColor,
                mentionsActive = mentionsActive,
                mentionColor = mentionColor,
                codeBackground = codeBackground,
                codeColor = codeColor,
            ),
        )
    }

/** Theme-aware leading rail shared by ordinary and mention-highlighted ACTION rows. */
private fun Modifier.actionAccentRail(accent: Color): Modifier =
    drawWithContent {
        drawContent()
        val railWidth = 3.dp.toPx()
        val inset = 2.dp.toPx()
        val railX =
            if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) {
                0f
            } else {
                size.width - railWidth
            }
        drawRoundRect(
            color = accent,
            topLeft = Offset(railX, inset),
            size = Size(railWidth, (size.height - inset * 2).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(railWidth / 2f, railWidth / 2f),
        )
    }

/**
 * TWO_LINE density renderer: a compact two-line message row.
 *  - Line 1: a small avatar, the nick-colored name (friend tint/star preserved), the own-message
 *    sent check ([MessageStatusIcon], own messages only), and the timestamp.
 *  - Line 2: the message body (linkified), plus the reply preview, inline image, link preview, and
 *    the now-compact reactions.
 *
 * NOTICE gets its label. ACTION is handled by the shared accent-row renderer before this function.
 * Uniformly left-aligned — no own-message right alignment or bubble background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoLineMessageRow(
    sender: String,
    displaySender: String,
    networkId: Long?,
    senderAccount: String?,
    text: String,
    formattedTime: String,
    isSelf: Boolean,
    isBot: Boolean,
    kind: MessageKind,
    nickColors: NickColorScheme,
    spacing: io.github.trevarj.motd.ui.theme.MotdSpacing,
    showSender: Boolean,
    modifier: Modifier = Modifier,
    hasMention: Boolean = false,
    senderIsFriend: Boolean = false,
    failed: Boolean = false,
    pending: Boolean = false,
    reply: ReplyPreviewData? = null,
    onReplyClick: (() -> Unit)? = null,
    imageUrl: String? = null,
    linkPreview: LinkPreview? = null,
    linkPreviewLoading: Boolean = false,
    linkPreviewResolved: Boolean = false,
    reactions: List<ReactionChip> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    onLongPress: () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onReact: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkPreviewClick: () -> Unit = {},
    onSenderClick: (() -> Unit)? = null,
) {
    val actionsLabel = stringResource(R.string.chat_bubble_actions)
    val hideAvatar = avatarsHidden()
    val nameColor = nickColors.nick(sender, MaterialTheme.colorScheme.onSurfaceVariant)
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Per-nick row wash (same treatment as COMPACT): a faint tint of the sender's own nick color
    // behind the whole row so runs of a nick's messages are trackable by speaker.
    val rowTint =
        if (hasMention) {
            LocalMotdSemanticColors.current.warningContainer.copy(alpha = MENTION_ROW_TINT_ALPHA)
        } else {
            nameColor.copy(alpha = TWO_LINE_ROW_TINT_ALPHA)
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // Tint fills the full row width (behind the horizontal padding) so the speaker band is
                // unbroken edge to edge, matching COMPACT.
                .background(rowTint)
                .messageRowClicks(
                    onClick = onClick,
                    onClickLabel = onClickLabel,
                    onLongPress = onLongPress,
                    onLongPressLabel = actionsLabel,
                ).padding(horizontal = spacing.messageOuterHPad, vertical = spacing.bubbleRowVPad),
    ) {
        // Line 1 (header): avatar + nick + (own) sent check + timestamp — only on a group's first
        // message. Continuations (showSender == false) omit the header and indent the body under it.
        if (showSender) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!hideAvatar) {
                    // Center the small avatar against the header line rather than top-pinning it.
                    val avatarMod =
                        Modifier
                            .padding(end = 6.dp)
                            .align(Alignment.CenterVertically)
                            .let { if (onSenderClick != null) it.clickable(onClick = onSenderClick) else it }
                    Avatar(
                        name = sender,
                        size = spacing.bubbleAvatar,
                        modifier = avatarMod,
                        networkId = networkId,
                        account = senderAccount,
                    )
                }
                Text(
                    text = botDisplayName(displaySender, isBot),
                    color = nameColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        (if (senderIsFriend) Modifier.friendNickTint() else Modifier)
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
                    if (LocalTimestampConfig.current.show) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }

        // Line 2 always starts under the nick text column. The previous first-row special case
        // started at the row edge while continuations reserved the avatar, producing a visible
        // zig-zag and misaligning rich children within the same sender group.
        val bodyIndent = twoLineBodyIndent(spacing, hideAvatar)
        Column(
            modifier =
                Modifier
                    .padding(
                        start = bodyIndent,
                        top = if (showSender) spacing.bubbleInnerVPad else 0.dp,
                    ).testTag("message_two_line_body"),
        ) {
            reply?.let { ReplyMiniBubble(it, nickColors, onReplyClick) }

            if (kind == MessageKind.NOTICE) {
                Text(
                    text = stringResource(R.string.chat_notice_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (text.isNotBlank()) {
                val linkColor = MaterialTheme.colorScheme.primary
                val mentionColor = rememberMentionColor(knownNicks, nickColors, identityRules)
                val mentionsActive = knownNicks.isNotEmpty() && nickColors.enabled
                // Memoized body build (linkify + mention coloring) so it doesn't re-run per frame.
                val richBody =
                    remember(
                        text,
                        imageUrl,
                        linkColor,
                        mentionsActive,
                        mentionColor,
                        codeBackground,
                        codeColor,
                    ) {
                        linkifiedBody(
                            text,
                            linkColor,
                            mentionsActive,
                            mentionColor,
                            codeBackground,
                            codeColor,
                        ).withoutMediaPreviewUrl(imageUrl)
                    }
                if (richBody.isNotBlank()) {
                    Text(
                        text = richBody,
                        color = bodyColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            imageUrl?.let { url ->
                InlineMediaPreview(
                    url = url,
                    networkId = networkId,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                    modifier =
                        Modifier
                            .padding(vertical = 2.dp)
                            .widthIn(max = 280.dp)
                            .heightIn(max = 240.dp)
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(10.dp)),
                )
                MediaOriginCaption(url, modifier = Modifier.widthIn(max = 280.dp))
            }

            if (shouldShowLinkPreview(linkPreview, linkPreviewLoading, linkPreviewResolved)) {
                Box(Modifier.padding(top = 4.dp)) {
                    LinkPreviewCard(
                        preview = linkPreview,
                        networkId = networkId,
                        loading = linkPreviewLoading,
                        onClick = onLinkPreviewClick,
                    )
                }
            }

            ReactionRow(reactions = reactions, onReact = onReact, isSelf = isSelf)
        }
    }
}

/**
 * Horizontal start shared by first and grouped TWO_LINE bodies: avatar width plus header gap. With
 * avatars hidden there is nothing to clear, so the body aligns flush with the header nick.
 */
internal fun twoLineBodyIndent(
    spacing: io.github.trevarj.motd.ui.theme.MotdSpacing,
    avatarsHidden: Boolean = false,
) = if (avatarsHidden) 0.dp else spacing.bubbleAvatar + 6.dp

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
internal fun messageStatus(
    isSelf: Boolean,
    pending: Boolean,
    failed: Boolean,
): MsgStatus =
    when {
        !isSelf -> MsgStatus.NONE
        failed -> MsgStatus.FAILED
        pending -> MsgStatus.PENDING
        isSelf -> MsgStatus.SENT
        else -> MsgStatus.NONE
    }

/**
 * The two one-shot morphs the status glyph can owe, one grammar with two endings: the pending clock
 * rotates out and either the delivery check or the failure cross rotates in.
 *
 * Two sibling assets rather than one multi-beat asset. Their frame ranges are identical (0..23,
 * clock at frame 0, settled glyph at the last), so the call site needs no clip spec, no per-beat
 * parked frame and no beat-dependent progress arithmetic -- only which raw resource and which
 * second keypath to recolor. The delivery asset and its pinned frame range are untouched.
 */
internal enum class StatusMorph(
    val rawRes: Int,
    val glyphLayer: String,
) {
    DELIVERED(R.raw.status_delivered, "check"),
    FAILED(R.raw.status_failed, "cross"),
}

/**
 * Which morph, if any, a status change owes.
 *
 * Both endings go through [MotdLottieMotion.playOnceOnTransition], so a row first observed already
 * sent or already failed -- scrollback -- plays nothing and renders its settled vector instead.
 * A retry that lands as sent still earns the delivery morph: it left [MsgStatus.SENT] and returned.
 */
internal fun statusMorph(
    previous: MsgStatus?,
    current: MsgStatus,
): StatusMorph? =
    when {
        MotdLottieMotion.playOnceOnTransition(previous, current, MsgStatus.SENT) -> StatusMorph.DELIVERED
        MotdLottieMotion.playOnceOnTransition(previous, current, MsgStatus.FAILED) -> StatusMorph.FAILED
        else -> null
    }

/**
 * Renders the single leading status glyph (clock / cross / sent-check) for the timestamp row.
 *
 * A row that actually transitions morphs in place through one Lottie node; everything else -- fresh
 * and scrollback rows, animations-off, a failure retried back to pending -- keeps the static vector
 * glyphs and swaps them through [AnimatedContent], exactly as before Lottie existed. The morph is
 * itself the transition, so it deliberately renders outside that [AnimatedContent] rather than
 * being cross-faded away halfway through.
 */
@Composable
internal fun MessageStatusIcon(
    isSelf: Boolean,
    pending: Boolean,
    failed: Boolean,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val status = messageStatus(isSelf, pending, failed)
    if (status == MsgStatus.NONE) return

    val motionEnabled = LocalLottieMotionEnabled.current
    // Hoisted above the AnimatedContent below: the pending clock and the failure cross live on
    // opposite sides of that swap, and per-branch state would forget the transition between them.
    val latch = remember { StatusMorphLatch() }
    // Decided during composition, not in an effect. An effect resolves one frame late, and that
    // frame is enough for AnimatedContent to start cross-fading toward the static failure glyph
    // before the morph tears it back out. Plain fields, not snapshot state: this is derived from
    // `status`, which already invalidates this scope, so observing it would only add a pass.
    val morph = latch.advance(status, motionEnabled)
    if (morph != null) {
        // A fresh animatable per beat. Without the key, a direct failed-to-sent step (a late echo
        // arriving after a reconnect, with no pending in between) would reuse the finished
        // animatable, whose progress is already 1, and the cross would snap to a check.
        key(morph) { StatusMorphIcon(morph, contentColor) }
        return
    }

    AnimatedContent(
        targetState = status == MsgStatus.FAILED,
        transitionSpec = {
            (fadeIn(MotdMotion.microFadeIn) + scaleIn(initialScale = 0.85f, animationSpec = MotdMotion.softSpring))
                .togetherWith(
                    fadeOut(MotdMotion.microFadeOut) +
                        scaleOut(targetScale = 0.95f, animationSpec = MotdMotion.softSpring),
                )
        },
        label = "message_status",
    ) { showFailure ->
        // Mounting Lottie for these would flash a differently proportioned mark for a frame on
        // every row scrolled in, and would keep a LottieDrawable alive per row. Only the row that
        // actually morphs pays for one.
        when {
            showFailure -> FailedIcon()
            status == MsgStatus.SENT -> SentIcon(contentColor)
            else -> PendingIcon(contentColor)
        }
    }
}

/**
 * Per-row memory of the last status rendered, and the beat that status change earned.
 *
 * Once chosen, a beat is latched until the status moves again, so the morph stays mounted on its
 * settled last frame instead of being swapped for a static glyph the instant it finishes.
 */
private class StatusMorphLatch {
    private var previous: MsgStatus? = null
    private var morph: StatusMorph? = null

    fun advance(
        status: MsgStatus,
        motionEnabled: Boolean,
    ): StatusMorph? {
        if (previous != status) {
            morph = if (motionEnabled) statusMorph(previous, status) else null
            previous = status
        }
        return morph
    }
}

/**
 * One clock-out/glyph-in morph. Frame 0 of either asset is the pending clock and its last frame is
 * the settled glyph, so the whole composition is the beat and it plays exactly once.
 */
@Composable
private fun StatusMorphIcon(
    morph: StatusMorph,
    contentColor: Color,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(morph.rawRes))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = true,
        iterations = 1,
        restartOnPlay = false,
    )
    // The clock reads as metadata, so it keeps the timestamp row's own ink rather than the theme
    // accent: an own bubble is already primaryContainer and an accent check would sit on top of it.
    // The cross earns the same error tint the static [FailedIcon] carries.
    val clockColor = contentColor.toArgb()
    val glyphColor =
        when (morph) {
            StatusMorph.DELIVERED -> contentColor
            StatusMorph.FAILED -> MaterialTheme.colorScheme.error
        }.toArgb()
    val dynamicProperties =
        remember(morph, clockColor, glyphColor) {
            // Built directly rather than through rememberLottieDynamicProperty: that helper keys on the
            // vararg keypath array's identity, so every recomposition would rebuild the properties and
            // force a keypath re-resolution plus an extra draw pass.
            LottieDynamicProperties(
                listOf(
                    lottieStrokeColor(clockColor, KeyPath("clock", "**")),
                    lottieStrokeColor(glyphColor, KeyPath(morph.glyphLayer, "**")),
                ),
            )
        }
    val description =
        stringResource(
            when (morph) {
                StatusMorph.DELIVERED -> R.string.chat_sent
                StatusMorph.FAILED -> R.string.chat_failed
            },
        )
    LottieAnimation(
        composition = composition,
        // No parked pre-transition frame is needed: the beat is chosen during the same composition
        // that first renders the new status, and `key(morph)` above hands it a fresh animatable, so
        // frame 0 is always the pending clock.
        progress = { progress },
        dynamicProperties = dynamicProperties,
        modifier =
            Modifier
                .padding(end = 4.dp)
                .size(12.dp)
                .semantics { contentDescription = description },
    )
}

/** Small check glyph shown next to the timestamp once the bouncer has echoed an own message back. */
@Composable
internal fun SentIcon(contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(
        Icons.Filled.Done,
        contentDescription = stringResource(R.string.chat_sent),
        tint = contentColor,
        modifier =
            Modifier
                .padding(end = 4.dp)
                .heightIn(max = 12.dp)
                .width(12.dp),
    )
}

/** Small clock glyph shown next to the timestamp while a message is still sending. */
@Composable
internal fun PendingIcon(contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(
        Icons.Filled.Schedule,
        contentDescription = stringResource(R.string.chat_sending),
        tint = contentColor,
        modifier =
            Modifier
                .padding(end = 4.dp)
                .heightIn(max = 12.dp)
                .width(12.dp),
    )
}

/**
 * Small error glyph shown next to the timestamp of a failed message.
 *
 * A cross, in the same 12dp slot as the clock and the check, because that is where
 * [StatusMorph.FAILED] settles: a row that morphed clock-to-cross and is then scrolled back to must
 * show the mark it morphed into, not a differently shaped badge in a wider slot.
 */
@Composable
internal fun FailedIcon() {
    Icon(
        Icons.Filled.Close,
        contentDescription = stringResource(R.string.chat_failed),
        tint = MaterialTheme.colorScheme.error,
        modifier =
            Modifier
                .padding(end = 4.dp)
                .heightIn(max = 12.dp)
                .width(12.dp),
    )
}

/** Remove only the preview's linked occurrence; code, formatting and the stored message stay intact. */
internal fun AnnotatedString.withoutMediaPreviewUrl(url: String?): AnnotatedString {
    if (url == null) return this
    val link = getLinkAnnotations(0, length).firstOrNull { (it.item as? LinkAnnotation.Url)?.url == url } ?: return this
    if (mediaOriginLabel(url) == null) return this
    var end = link.end
    if (link.start > 0 && this[link.start - 1] == ' ') {
        while (end < length && this[end] == ' ') end++
    }
    val remaining =
        buildAnnotatedString {
            append(this@withoutMediaPreviewUrl.subSequence(0, link.start))
            append(this@withoutMediaPreviewUrl.subSequence(end, this@withoutMediaPreviewUrl.length))
        }
    val first = remaining.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return AnnotatedString("")
    return remaining.subSequence(first, remaining.indexOfLast { !it.isWhitespace() } + 1)
}

/**
 * Build an [AnnotatedString] where each http(s) URL in [text] is a tappable [LinkAnnotation.Url]
 * and each @mention of a known nick is colored with that nick's own color. URL
 * boundaries come from [extractUrls], matched left-to-right in the raw text; the runs between URLs
 * get mention coloring via [appendMentionColored]. [mentionColor] returns the nick's color for a
 * known token (matched with the active IRC identity rules) or null for a plain word; when null
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
    // when neither link annotations, mention styling, nor mIRC formatting can affect the result.
    if (!mentionsActive && !text.contains("http://") && !text.contains("https://") && !text.contains('`') &&
        text.none { it.code in 0x01..0x1F }
    ) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        appendRichText(
            text = text,
            plainStyle = SpanStyle(),
            linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            codeStyle =
                SpanStyle(
                    color = codeColor,
                    background = codeBackground,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Normal,
                ),
            mentionColor = if (mentionsActive) mentionColor else ({ null }),
        )
    }
}

/**
 * mIRC formatting codes are resolved first since they can wrap around code spans/URLs/mentions;
 * code segmentation then precedes URL and mention annotation so code contents stay inert.
 */
internal fun androidx.compose.ui.text.AnnotatedString.Builder.appendRichText(
    text: String,
    plainStyle: SpanStyle,
    linkStyle: SpanStyle,
    codeStyle: SpanStyle,
    mentionColor: (String) -> androidx.compose.ui.graphics.Color? = { null },
) {
    val formatted = parseIrcFormatting(text)
    val segments = parseInlineCode(formatted.visibleText)
    val renderedOffsets = inlineCodeRenderedOffsets(formatted.visibleText, segments)
    val bodyStart = length
    for (segment in segments) {
        when (segment) {
            is InlineTextSegment.Code -> {
                withStyle(codeStyle) { append(segment.text) }
            }

            is InlineTextSegment.Plain -> {
                appendPlainLinksAndMentions(
                    segment.text,
                    plainStyle,
                    linkStyle,
                    mentionColor,
                )
            }
        }
    }
    formatted.runs.forEach { run ->
        val start = renderedOffsets[run.start]
        val end = renderedOffsets[run.end]
        if (start < end) addStyle(run.state.toSpanStyle(), bodyStart + start, bodyStart + end)
    }
}

private fun inlineCodeRenderedOffsets(
    source: String,
    segments: List<InlineTextSegment>,
): IntArray {
    val offsets = IntArray(source.length + 1)
    var sourceOffset = 0
    var renderedOffset = 0
    segments.forEach { segment ->
        val segmentStart = source.indexOf(segment.text, sourceOffset).coerceAtLeast(sourceOffset)
        while (sourceOffset < segmentStart) offsets[++sourceOffset] = renderedOffset
        segment.text.indices.forEach {
            sourceOffset++
            renderedOffset++
            offsets[sourceOffset] = renderedOffset
        }
    }
    while (sourceOffset < source.length) offsets[++sourceOffset] = renderedOffset
    return offsets
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
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): (String) -> androidx.compose.ui.graphics.Color? =
    remember(knownNicks, nickColors, identityRules) {
        if (knownNicks.isEmpty() || !nickColors.enabled) {
            { null }
        } else {
            { token ->
                // Mentions always resolve to the nick's own color; Unspecified fallback is never
                // hit because membership is checked first.
                if (matchesKnownMention(token, knownNicks, identityRules)) {
                    nickColors.nick(token, androidx.compose.ui.graphics.Color.Unspecified)
                } else {
                    null
                }
            }
        }
    }

internal fun matchesKnownMention(
    token: String,
    knownNicks: Set<String>,
    identityRules: IrcIdentityRules,
): Boolean = identityRules.normalize(token) in knownNicks

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
 * Subtle friend highlight behind the sender name: a low-alpha
 * theme-primary rounded pill layered under the nick color. Distinct enough to spot, quiet enough
 * not to fight the nick color or the bubble background.
 */
@Composable
internal fun Modifier.friendNickTint(): Modifier =
    this
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        .padding(horizontal = 4.dp, vertical = 1.dp)

/** Resolved reply target for the quoted mini-bubble. */
data class ReplyPreviewData(
    val sender: String,
    val text: String,
    val ircFormattedText: String? = null,
)

@Composable
internal fun ReplyMiniBubble(
    reply: ReplyPreviewData,
    nickColors: NickColorScheme,
    onClick: (() -> Unit)? = null,
) {
    val accent = nickColors.nick(reply.sender, MaterialTheme.colorScheme.onSurfaceVariant)
    val openLabel = stringResource(R.string.chat_reply_open)
    Row(
        modifier =
            Modifier
                .testTag("chat_reply_preview")
                .animateContentSize(
                    animationSpec = MotdMotion.contentSize,
                    alignment = Alignment.TopStart,
                ).padding(vertical = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                .let { modifier ->
                    if (onClick != null) {
                        modifier.clickable(onClickLabel = openLabel, onClick = onClick)
                    } else {
                        modifier
                    }
                },
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
                text = mircFormattedText(reply.ircFormattedText ?: reply.text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Format [ms] as a short clock time honoring the device 12/24-hour preference. The
 * [JavaDateFormat] is hoisted per Compose context via [LocalContext] and rebuilt only when the
 * device setting/locale changes, avoiding a per-row/per-recomposition allocation.
 */
@Composable
internal fun formatTime(ms: Long): String = rememberMessageTimeFormatter()(ms)

/**
 * One formatter per message-list composition. Android's 12/24-hour lookup can cross framework
 * settings, and DateFormat is stateful; sharing it on the UI thread avoids repeating both costs for
 * every row first composed in a fling.
 */
@Composable
internal fun rememberMessageTimeFormatter(): (Long) -> String {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val timestampConfig = LocalTimestampConfig.current
    val timeFormat = timestampConfig.format
    val customPattern = timestampConfig.customPattern
    val is24 = remember(context, locale) { DateFormat.is24HourFormat(context) }
    val formatter =
        remember(is24, locale, timeFormat, customPattern) {
            when (timeFormat) {
                // getTimeFormat honors the 12/24h system setting; not thread-safe but only used on the
                // UI thread.
                TimeFormat.AUTO -> {
                    DateFormat.getTimeFormat(context)
                        ?: JavaDateFormat.getTimeInstance(JavaDateFormat.SHORT)
                }

                TimeFormat.H24 -> {
                    java.text.SimpleDateFormat("HH:mm", locale)
                }

                TimeFormat.H12 -> {
                    java.text.SimpleDateFormat(
                        DateFormat.getBestDateTimePattern(locale, "hm"),
                        locale,
                    )
                }

                // A malformed user-typed pattern falls back to AUTO rather than crashing the row.
                TimeFormat.CUSTOM -> {
                    runCatching { java.text.SimpleDateFormat(customPattern, locale) }
                        .getOrElse { DateFormat.getTimeFormat(context) ?: JavaDateFormat.getTimeInstance(JavaDateFormat.SHORT) }
                }
            }
        }
    return remember(formatter) {
        { ms: Long -> formatter.format(java.util.Date(ms)) }
    }
}

/**
 * Resolves the user's [TimeFormat] preference to a concrete 12h/24h boolean, given the device's
 * own 12h/24h setting for the AUTO case. Pure and Context-free so it's directly unit-testable.
 */
internal fun resolveIs24Hour(
    format: TimeFormat,
    deviceIs24: Boolean,
): Boolean =
    when (format) {
        TimeFormat.AUTO -> deviceIs24

        TimeFormat.H12 -> false

        TimeFormat.H24 -> true

        // CUSTOM's pattern decides its own hour cycle; this hint isn't consulted for it.
        TimeFormat.CUSTOM -> deviceIs24
    }

@Preview
@Composable
private fun MessageBubbleOthersPreview() {
    MotdTheme {
        Column {
            MessageBubble(
                sender = "alice",
                text = "me: welcome to the channel!",
                timeMs = System.currentTimeMillis(),
                isSelf = false,
                kind = MessageKind.PRIVMSG,
                showSender = true,
                hasMention = true,
                reactions = listOf(ReactionChip("👍", 2, mine = false)),
            )
            MessageBubble(
                sender = "alice",
                text = "grouped follow-up",
                timeMs = System.currentTimeMillis(),
                isSelf = false,
                kind = MessageKind.PRIVMSG,
                showSender = false,
            )
        }
    }
}

@Preview
@Composable
private fun MessageBubbleSelfPreview() {
    MotdTheme {
        MessageBubble(
            sender = "me",
            text = "sending a reply",
            timeMs = System.currentTimeMillis(),
            isSelf = true,
            kind = MessageKind.PRIVMSG,
            showSender = false,
            reply = ReplyPreviewData("alice", "welcome to the channel!"),
        )
    }
}

@PreviewLightDark
@Composable
private fun MessageBubbleActionComfortablePreview() {
    MotdTheme(layoutDensity = io.github.trevarj.motd.data.prefs.LayoutDensity.COMFORTABLE) {
        MessageBubble(
            sender = "bob",
            text = "waves to @alice across the room",
            timeMs = System.currentTimeMillis(),
            isSelf = false,
            kind = MessageKind.ACTION,
            showSender = true,
            knownNicks = setOf("alice"),
            reactions = listOf(ReactionChip("👋", 2, mine = false)),
        )
    }
}

@PreviewLightDark
@Composable
private fun MessageBubbleActionCompactPreview() {
    MotdTheme(layoutDensity = io.github.trevarj.motd.data.prefs.LayoutDensity.COMPACT) {
        MessageBubble(
            sender = "bob",
            text = "waves hello",
            timeMs = System.currentTimeMillis(),
            isSelf = false,
            kind = MessageKind.ACTION,
            showSender = true,
        )
    }
}

@PreviewLightDark
@Composable
private fun MessageBubbleActionTwoLinePreview() {
    MotdTheme(layoutDensity = io.github.trevarj.motd.data.prefs.LayoutDensity.TWO_LINE) {
        MessageBubble(
            sender = "bob",
            text = "waves hello",
            timeMs = System.currentTimeMillis(),
            isSelf = false,
            kind = MessageKind.ACTION,
            showSender = true,
        )
    }
}

@Preview
@Composable
private fun MessageBubbleFailedPreview() {
    MotdTheme {
        MessageBubble(
            sender = "me",
            text = "this one failed",
            timeMs = System.currentTimeMillis(),
            isSelf = true,
            kind = MessageKind.PRIVMSG,
            showSender = false,
            failed = true,
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
                    hasMention = true,
                    reply = ReplyPreviewData("bob", "Earlier message"),
                    imageUrl = "https://example.com/image.png",
                    linkPreview =
                        LinkPreview(
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
            sender = "ChanServ",
            text = "This channel is registered.",
            timeMs = System.currentTimeMillis(),
            isSelf = false,
            kind = MessageKind.NOTICE,
            showSender = true,
        )
    }
}
