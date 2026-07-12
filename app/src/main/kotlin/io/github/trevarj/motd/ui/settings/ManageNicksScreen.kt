package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.hueColor

/**
 * Sanitize raw add-nick input. Returns the trimmed nick, or null when unusable: blank, contains
 * whitespace or a comma, or starts with a channel sigil (`#`/`&`). Pure — unit-tested.
 */
fun sanitizeNickInput(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it.isWhitespace() } || trimmed.contains(',')) return null
    if (trimmed.startsWith('#') || trimmed.startsWith('&')) return null
    return trimmed
}

/** Stateful entry: one screen serves FRIENDS / FOOLS / COLORS routes. */
@Composable
fun ManageNicksScreen(
    kind: NickListKind,
    onBack: () -> Unit = {},
    viewModel: ManageNicksViewModel = hiltViewModel(),
) {
    LaunchedEffect(kind) { viewModel.init(kind) }
    val state by viewModel.state.collectAsState()
    ManageNicksContent(
        state = state,
        onBack = onBack,
        onAdd = viewModel::add,
        onRemove = viewModel::remove,
        onSetHue = viewModel::setHue,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageNicksContent(
    state: ManageNicksUiState,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetHue: (String, Int) -> Unit,
) {
    val scheme = LocalNickColors.current
    val titleRes = when (state.kind) {
        NickListKind.FRIENDS -> R.string.friends_title
        NickListKind.FOOLS -> R.string.fools_title
        NickListKind.COLORS -> R.string.nick_colors_title
    }

    var input by remember { mutableStateOf("") }
    // Nick whose hue is being edited (COLORS): existing row tap or a freshly-added nick.
    var editingNick by remember { mutableStateOf<String?>(null) }

    editingNick?.let { nick ->
        NickHuePickerDialog(
            nick = nick,
            currentHue = state.overrides[nick],
            onPick = { hue ->
                if (hue != null) onSetHue(nick, hue) else onRemove(nick)
                editingNick = null
            },
            onDismiss = { editingNick = null },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Add row -----------------------------------------------------------------------
            val sanitized = sanitizeNickInput(input)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.manage_add_nick_hint)) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = sanitized != null,
                    onClick = {
                        val nick = sanitized ?: return@TextButton
                        if (state.kind == NickListKind.COLORS) {
                            // Persist only once a swatch is picked in the dialog.
                            editingNick = nick
                        } else {
                            onAdd(nick)
                        }
                        input = ""
                    },
                ) {
                    Text(stringResource(R.string.manage_add))
                }
            }
            }

            // List --------------------------------------------------------------------------
            if (state.nicks.isEmpty()) {
                val emptyRes = when (state.kind) {
                    NickListKind.FRIENDS -> R.string.manage_empty_friends
                    NickListKind.FOOLS -> R.string.manage_empty_fools
                    NickListKind.COLORS -> R.string.manage_empty_colors
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(emptyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.nicks, key = { it }) { nick ->
                        val rowModifier = if (state.kind == NickListKind.COLORS) {
                            Modifier.clickable { editingNick = nick }
                        } else {
                            Modifier
                        }
                        ListItem(
                            headlineContent = { Text(nick) },
                            leadingContent = {
                                if (state.kind == NickListKind.COLORS) {
                                    // Color chip previewing the resolved override color.
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(
                                                hueColor(
                                                    state.overrides[nick] ?: 0,
                                                    scheme.isDark,
                                                    scheme.palette,
                                                ),
                                            ),
                                    )
                                } else {
                                    Avatar(name = nick, size = 36.dp)
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { onRemove(nick) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.manage_remove),
                                    )
                                }
                            },
                            modifier = rowModifier,
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
                }
            }
        }
        }
    }
}

@Preview
@Composable
private fun ManageNicksFriendsPreview() {
    MotdTheme {
        ManageNicksContent(
            state = ManageNicksUiState(
                kind = NickListKind.FRIENDS,
                nicks = listOf("alice", "bob"),
            ),
            onBack = {}, onAdd = {}, onRemove = {}, onSetHue = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun ManageNicksColorsPreview() {
    MotdTheme {
        ManageNicksContent(
            state = ManageNicksUiState(
                kind = NickListKind.COLORS,
                nicks = listOf("alice", "carol"),
                overrides = mapOf("alice" to 210, "carol" to 90),
            ),
            onBack = {}, onAdd = {}, onRemove = {}, onSetHue = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun ManageNicksEmptyPreview() {
    MotdTheme {
        ManageNicksContent(
            state = ManageNicksUiState(kind = NickListKind.FOOLS),
            onBack = {}, onAdd = {}, onRemove = {}, onSetHue = { _, _ -> },
        )
    }
}
