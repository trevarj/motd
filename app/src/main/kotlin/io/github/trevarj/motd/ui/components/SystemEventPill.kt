package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * One line of system-event text (a JOIN/PART/QUIT/etc. summary), already formatted by the caller.
 */
data class SystemEvent(
    val text: String,
)

/**
 * Centered pill summarizing a consecutive run of system events. A single event shows its text; a
 * run collapses to a summary ("3 joined · 1 left") that expands inline on tap to list each line
 *. Lines remain lazy until expansion, while content refreshes keep the user's current
 * expanded/collapsed choice.
 */
@Composable
fun SystemEventPill(
    summary: String,
    lineCount: Int,
    loadLines: () -> List<String>,
    /** Changes whenever the backing collapsed chunk changes so expanded lines are refreshed. */
    contentKey: Any,
    modifier: Modifier = Modifier,
    forceCollapsible: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onLineLongPress: ((Int) -> Unit)? = null,
    lineKey: ((Int) -> Any)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    SystemEventPill(
        summary = summary,
        lineCount = lineCount,
        loadLines = loadLines,
        contentKey = contentKey,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        forceCollapsible = forceCollapsible,
        onLongPress = onLongPress,
        onLineLongPress = onLineLongPress,
        lineKey = lineKey,
    )
}

/** Controlled variant used when expansion must survive Paging row replacement. */
@Composable
internal fun SystemEventPill(
    summary: String,
    lineCount: Int,
    loadLines: () -> List<String>,
    contentKey: Any,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    forceCollapsible: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onLineLongPress: ((Int) -> Unit)? = null,
    lineKey: ((Int) -> Any)? = null,
) {
    // Materializing every line of a large JOIN/PART burst while scrolling is expensive. Keep the
    // collapsed path to its bounded summary and build lines only if the user opens the pill.
    val collapsible = forceCollapsible || lineCount > 1
    val showLines = expanded && collapsible
    val lines =
        remember(contentKey, showLines) {
            if (showLines) loadLines() else emptyList()
        }
    val expansionState =
        stringResource(
            if (showLines) R.string.system_event_expanded else R.string.system_event_collapsed,
        )
    // Capsule for the short collapsed pill; once expanded the percent radius would be 50% of the
    // smaller dimension and overrun the 14 dp text padding, pushing top/bottom lines outside the
    // background. Cap it to a Dp radius that stays within the padding when expanded.
    val pillShape = if (showLines) RoundedCornerShape(14.dp) else RoundedCornerShape(50)
    Row(
        modifier =
            modifier.fillMaxWidth().padding(
                vertical = LocalSpacing.current.systemPillVPad,
                horizontal = LocalSpacing.current.messageOuterHPad,
            ),
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        pillShape,
                    ).then(
                        if (collapsible) {
                            Modifier.semantics {
                                role = Role.Button
                                contentDescription = summary
                                stateDescription = expansionState
                            }
                        } else {
                            Modifier
                        },
                    ).then(
                        if (collapsible || onLongPress != null) {
                            Modifier.combinedClickable(
                                onClick = { if (collapsible) onExpandedChange(!showLines) },
                                onLongClickLabel = stringResource(R.string.ai_text_translate),
                                onLongClick = onLongPress,
                            )
                        } else {
                            Modifier
                        },
                    ).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            // Keep exactly one content tree mounted. AnimatedContent briefly layered the collapsed
            // summary over the expanding lines while LazyColumn remeasured a tall JOIN/PART run,
            // producing a full-row flash on physical devices.
            if (showLines) {
                Column {
                    lines.forEachIndexed { index, line ->
                        key(lineKey?.invoke(index) ?: index) {
                            Text(
                                text = line,
                                modifier =
                                    Modifier
                                        .then(lineKey?.let { Modifier.testTag("chat_system_event_${it(index)}") } ?: Modifier)
                                        .then(
                                            onLineLongPress?.let { callback ->
                                                Modifier.combinedClickable(
                                                    onClick = {},
                                                    onLongClickLabel = stringResource(R.string.ai_text_translate),
                                                    onLongClick = { callback(index) },
                                                )
                                            } ?: Modifier,
                                        ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
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
            summary = "alice joined",
            lineCount = 1,
            loadLines = { listOf("alice joined") },
            contentKey = "alice",
        )
    }
}

@Preview
@Composable
private fun SystemEventPillCollapsedPreview() {
    MotdTheme {
        SystemEventPill(
            summary = "3 joined · 1 left",
            lineCount = 4,
            loadLines = { listOf("alice joined", "bob joined", "carol joined", "dave left") },
            contentKey = "preview",
        )
    }
}
