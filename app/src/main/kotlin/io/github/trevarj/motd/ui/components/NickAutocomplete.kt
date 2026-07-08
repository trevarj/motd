package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Popup shown above the composer while a nick token or `/` command is being completed. Renders a
 * short candidate list; tapping one calls [onPick]. The parent decides visibility/anchoring; this
 * is just the panel. Empty [candidates] renders nothing.
 */
@Composable
fun AutocompletePanel(
    candidates: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isCommand: Boolean = false,
) {
    if (candidates.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
            items(candidates, key = { it }) { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(candidate) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isCommand) {
                        Avatar(name = candidate, size = 24.dp, modifier = Modifier.padding(end = 10.dp))
                    }
                    Text(
                        text = candidate,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCommand) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun AutocompletePanelPreview() {
    MotdTheme {
        AutocompletePanel(candidates = listOf("alice", "alicia", "Alan"), onPick = {})
    }
}

@Preview
@Composable
private fun AutocompleteCommandPreview() {
    MotdTheme {
        AutocompletePanel(candidates = listOf("/me", "/msg"), onPick = {}, isCommand = true)
    }
}
