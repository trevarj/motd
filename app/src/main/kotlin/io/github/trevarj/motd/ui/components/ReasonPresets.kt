package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** A canned moderation reason. [key] only names the test tag; [label] is the text that is sent. */
enum class ReasonPreset(
    val key: String,
    private val label: Int,
) {
    SPAM("spam", R.string.moderation_reason_spam),
    FLOODING("flooding", R.string.moderation_reason_flooding),
    DISRUPTIVE("disruptive", R.string.moderation_reason_disruptive),
    OFFTOPIC("offtopic", R.string.moderation_reason_offtopic),
    ;

    @Composable
    fun label(): String = stringResource(label)
}

/**
 * Preset reason chips shared by the kick dialog and the operator KILL field. A chip fills the
 * reason field rather than replacing it: the text stays editable, and selection is mirrored by an
 * exact text match so typing over a preset visibly deselects it.
 */
@Composable
fun ReasonPresetChips(
    current: String,
    onSelect: (String) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReasonPreset.entries.forEach { preset ->
            val label = preset.label()
            FilterChip(
                selected = current == label,
                onClick = { onSelect(label) },
                label = { Text(label) },
                leadingIcon = if (current == label) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                modifier = Modifier.testTag("${tagPrefix}_${preset.key}"),
            )
        }
    }
}
