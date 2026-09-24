package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Unread count pill (primary). Renders "99+" for large counts. */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    lowerBound: Boolean = false,
) {
    // CD carries the real count (the visible text caps at "99+") so the e2e harness can read it.
    val cd =
        pluralStringResource(
            if (lowerBound) R.plurals.badge_unread_at_least else R.plurals.badge_unread,
            count,
            count,
        )
    CountBadge(
        text =
            when {
                count > 99 -> "99+"
                lowerBound -> "$count+"
                else -> count.toString()
            },
        background = MaterialTheme.colorScheme.primary,
        foreground = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
        contentDescription = cd,
    )
}

/**
 * Count-less unread cue: the server has advertised newer activity for this chat than the device
 * holds, so there IS something unread but nothing local to count. Same primary color as
 * [UnreadBadge] because it means the same thing to the reader; a dot rather than a pill because the
 * only honest number here is none.
 */
@Composable
fun AdvertisedActivityDot(modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.badge_unread_pending)
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .clearAndSetSemantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

/**
 * The fetched window has not reached the reader's anchor yet. This deliberately differs from
 * [AdvertisedActivityDot]: an incomplete window may contain only presence events, so it cannot
 * claim that a chat message is waiting.
 */
@Composable
fun HistoryIncompleteBadge(modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.chat_history_partial_chip)
    Icon(
        imageVector = Icons.Outlined.History,
        contentDescription = cd,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .padding(2.dp),
    )
}

/** Mention badge (secondary, "@" glyph). */
@Composable
fun MentionBadge(
    count: Int,
    modifier: Modifier = Modifier,
    lowerBound: Boolean = false,
) {
    val cd =
        pluralStringResource(
            if (lowerBound) R.plurals.badge_mention_at_least else R.plurals.badge_mention,
            count,
            count,
        )
    CountBadge(
        text =
            when {
                lowerBound -> "@$count+"
                count > 1 -> "@$count"
                else -> "@"
            },
        background = MaterialTheme.colorScheme.secondary,
        foreground = MaterialTheme.colorScheme.onSecondary,
        modifier = modifier,
        contentDescription = cd,
    )
}

/** Subdued total activity count shown only on a muted chat row. */
@Composable
fun MutedActivityBadge(
    count: Int,
    modifier: Modifier = Modifier,
    lowerBound: Boolean = false,
) {
    val cd =
        pluralStringResource(
            if (lowerBound) R.plurals.badge_unread_at_least else R.plurals.badge_unread,
            count,
            count,
        )
    CountBadge(
        text =
            when {
                count > 99 -> "99+"
                lowerBound -> "$count+"
                else -> count.toString()
            },
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        foreground = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        contentDescription = cd,
    )
}

/** Quiet metadata naming the network; shown when more than one network is present. */
@Composable
fun NetworkChip(
    name: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    emphasized: Boolean = false,
) {
    val container =
        when {
            !dimmed -> MaterialTheme.colorScheme.surfaceContainerHigh
            emphasized -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    val label =
        when {
            !dimmed -> MaterialTheme.colorScheme.onSurface
            emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Box(
        modifier =
            modifier
                .widthIn(max = 92.dp)
                .background(
                    container,
                    MotdShapes.tag,
                ).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = name,
            color = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CountBadge(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .background(background, CircleShape)
                .padding(horizontal = 6.dp, vertical = 1.dp)
                // Expose the real count as one CD node ("N unread"/"N mentions") for the e2e harness,
                // replacing the visually-capped inner Text ("99+", "@") in the a11y tree.
                .then(
                    if (contentDescription != null) {
                        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut))
                    .using(
                        SizeTransform(
                            sizeAnimationSpec = { _, _ -> MotdMotion.contentSize },
                        ),
                    )
            },
            label = "badge_count",
        ) { currentText ->
            Text(
                text = currentText,
                color = foreground,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Preview
@Composable
private fun BadgesPreview() {
    MotdTheme {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement
                    .spacedBy(8.dp),
        ) {
            UnreadBadge(count = 4)
            UnreadBadge(count = 128)
            MentionBadge(count = 1)
            MentionBadge(count = 3)
            NetworkChip(name = "Libera")
        }
    }
}
