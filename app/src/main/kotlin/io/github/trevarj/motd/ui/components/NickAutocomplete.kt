package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
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
    networkId: Long? = null,
    tagPrefix: String = "autocomplete",
) {
    if (candidates.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag("${tagPrefix}_panel"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
            items(candidates.size, key = { candidates[it] }) { index ->
                val candidate = candidates[index]
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // >=48dp touch target for autocomplete rows.
                            .heightIn(min = 48.dp)
                            .clickable { onPick(candidate) }
                            .testTag("${tagPrefix}_item_$index")
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isCommand && !avatarsHidden()) {
                        Avatar(
                            name = candidate,
                            size = 24.dp,
                            modifier = Modifier.padding(end = 10.dp),
                            networkId = networkId,
                        )
                    }
                    if (isCommand) {
                        Column {
                            Text(
                                text = candidate,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            commandDescription(candidate)?.let { description ->
                                Text(
                                    text = stringResource(description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = candidate,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun commandDescription(command: String): Int? =
    when (command) {
        "/me" -> R.string.chat_command_me
        "/join" -> R.string.chat_command_join
        "/part" -> R.string.chat_command_part
        "/hop" -> R.string.chat_command_hop
        "/msg" -> R.string.chat_command_msg
        "/query" -> R.string.chat_command_query
        "/notice" -> R.string.chat_command_notice
        "/nick" -> R.string.chat_command_nick
        "/setname" -> R.string.chat_command_setname
        "/topic" -> R.string.chat_command_topic
        "/mode" -> R.string.chat_command_mode
        "/away" -> R.string.chat_command_away
        "/whois" -> R.string.chat_command_whois
        "/list" -> R.string.chat_command_list
        "/kick" -> R.string.chat_command_kick
        "/ban" -> R.string.chat_command_ban
        "/invite" -> R.string.chat_command_invite
        "/knock" -> R.string.chat_command_knock
        "/ctcp" -> R.string.chat_command_ctcp
        "/motd" -> R.string.chat_command_motd
        "/raw" -> R.string.chat_command_raw
        else -> null
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
