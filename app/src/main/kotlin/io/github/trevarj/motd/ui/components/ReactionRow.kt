package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.theme.MotdTheme

/** One aggregated reaction: an emoji, its count, and whether the current user reacted. */
data class ReactionChip(val emoji: String, val count: Int, val mine: Boolean)

/**
 * Chip row under a bubble. Tapping an unowned chip adds the reaction; tapping an owned chip asks
 * the caller to remove it with `draft/unreact`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(
    reactions: List<ReactionChip>,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    FlowRow(
        // Tight top gap so chips sit snugly under the message body without ballooning row height.
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        reactions.forEach { chip ->
            ReactionChipView(chip = chip, onClick = { onReact(chip.emoji) })
        }
    }
}

@Composable
private fun ReactionChipView(chip: ReactionChip, onClick: () -> Unit) {
    val bg = if (chip.mine) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (chip.mine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.Row(
        // Compact chip: a fixed short height keeps reactions snug under the message instead of the
        // 48dp minimumInteractiveComponentSize that ballooned the row; the tap target stays usable
        // via the chip's own horizontal/vertical padding (plans/15 #24).
        modifier = Modifier
            .testTag("chat_reaction_chip_${chip.emoji}")
            .wrapContentWidth()
            .heightIn(min = 24.dp)
            .background(bg, RoundedCornerShape(50))
            .then(
                if (chip.mine) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                else Modifier,
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = chip.emoji, color = Color.Unspecified, style = MaterialTheme.typography.bodySmall)
        Text(
            text = chip.count.toString(),
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Preview
@Composable
private fun ReactionRowPreview() {
    MotdTheme {
        ReactionRow(
            reactions = listOf(
                ReactionChip("👍", 3, mine = true),
                ReactionChip("❤️", 1, mine = false),
                ReactionChip("😂", 5, mine = false),
            ),
            onReact = {},
        )
    }
}
