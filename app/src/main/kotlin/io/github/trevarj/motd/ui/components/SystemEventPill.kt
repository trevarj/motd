package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * One line of system-event text (a JOIN/PART/QUIT/etc. summary), already formatted by the caller.
 */
data class SystemEvent(val text: String)

/**
 * Centered pill summarizing a consecutive run of system events. A single event shows its text; a
 * run collapses to a summary ("3 joined · 1 left") that expands inline on tap to list each line
 * (plans/07). [summary] and [lines] are pre-computed; this composable owns only expand state.
 */
@Composable
fun SystemEventPill(
    summary: String,
    lineCount: Int,
    loadLines: () -> List<String>,
    /** Changes whenever the backing collapsed chunk changes; resets stale expanded line state. */
    contentKey: Any,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(contentKey) { mutableStateOf(false) }
    // Materializing every line of a large JOIN/PART burst while scrolling is expensive. Keep the
    // collapsed path to its bounded summary and build lines only if the user opens the pill.
    var lines by remember(contentKey) { mutableStateOf<List<String>?>(null) }
    val collapsible = lineCount > 1
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.systemPillVPad, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(50),
                )
                .then(
                    if (collapsible) Modifier.clickable {
                        if (!expanded) lines = loadLines()
                        expanded = !expanded
                    } else Modifier,
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            if (expanded && collapsible) {
                lines.orEmpty().forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview
@Composable
private fun SystemEventPillSinglePreview() {
    MotdTheme {
        SystemEventPill(
            summary = "alice joined", lineCount = 1, loadLines = { listOf("alice joined") },
            contentKey = "alice",
        )
    }
}

@Preview
@Composable
private fun SystemEventPillCollapsedPreview() {
    MotdTheme {
        SystemEventPill(
            summary = "3 joined · 1 left", lineCount = 4,
            loadLines = { listOf("alice joined", "bob joined", "carol joined", "dave left") },
            contentKey = "preview",
        )
    }
}
