package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Unread count pill (primary). Renders "99+" for large counts. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    // CD carries the real count (the visible text caps at "99+") so the e2e harness can read it.
    val cd = pluralStringResource(R.plurals.badge_unread, count, count)
    CountBadge(
        text = if (count > 99) "99+" else count.toString(),
        background = MaterialTheme.colorScheme.primary,
        foreground = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
        contentDescription = cd,
    )
}

/** Mention badge (tertiary, "@" glyph). */
@Composable
fun MentionBadge(count: Int, modifier: Modifier = Modifier) {
    val cd = pluralStringResource(R.plurals.badge_mention, count, count)
    CountBadge(
        text = if (count > 1) "@$count" else "@",
        background = MaterialTheme.colorScheme.tertiary,
        foreground = MaterialTheme.colorScheme.onTertiary,
        modifier = modifier,
        contentDescription = cd,
    )
}

/** Small outlined chip naming the network; shown when more than one network is present. */
@Composable
fun NetworkChip(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                CircleShape,
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
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
        modifier = modifier
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
        Text(
            text = text,
            color = foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Preview
@Composable
private fun BadgesPreview() {
    MotdTheme {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            UnreadBadge(count = 4)
            UnreadBadge(count = 128)
            MentionBadge(count = 1)
            MentionBadge(count = 3)
            NetworkChip(name = "Libera")
        }
    }
}
