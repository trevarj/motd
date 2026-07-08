package io.github.trevarj.motd.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.ui.chatlist.relativeChatTime
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel, applies the nav buffer scope, drives navigation. */
@Composable
fun SearchScreen(
    bufferId: Long? = null,
    onBack: () -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    LaunchedEffect(bufferId) { viewModel.init(bufferId) }
    val state by viewModel.state.collectAsState()

    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onScopeChange = viewModel::onScopeChange,
        onBack = onBack,
        // TODO(post-v1): deep-jump via CHATHISTORY AROUND to scroll to the hit; for now just open.
        onOpenHit = { hit -> onOpenBuffer(hit.message.bufferId) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onScopeChange: (SearchScope) -> Unit,
    onBack: () -> Unit,
    onOpenHit: (SearchHit) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
                title = {
                    TextField(
                        value = state.rawQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.hasBufferScope) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.scope == SearchScope.ALL,
                        onClick = { onScopeChange(SearchScope.ALL) },
                        label = { Text(stringResource(R.string.search_chip_all)) },
                    )
                    FilterChip(
                        selected = state.scope == SearchScope.CURRENT,
                        onClick = { onScopeChange(SearchScope.CURRENT) },
                        label = { Text(stringResource(R.string.search_chip_current)) },
                    )
                }
            }

            when {
                state.rawQuery.isBlank() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_prompt_title),
                    message = stringResource(R.string.search_prompt_message),
                )

                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_empty_title),
                    message = stringResource(R.string.search_empty_message),
                )

                else -> SearchResults(
                    groups = state.groups,
                    query = parseSearchQuery(state.rawQuery).text,
                    onOpenHit = onOpenHit,
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    groups: List<SearchGroup>,
    query: String,
    onOpenHit: (SearchHit) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groups.forEach { group ->
            item(key = "h-${group.bufferId}") {
                Text(
                    text = "${group.bufferDisplayName} · ${group.networkName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(group.hits, key = { it.message.id }) { hit ->
                SearchHitRow(hit = hit, query = query, onClick = { onOpenHit(hit) })
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, query: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.message.sender,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = highlightSnippet(hit.message.text, query),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
        }
        Text(
            text = relativeChatTime(hit.message.serverTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Top),
        )
    }
}

/** Bold every case-insensitive occurrence of [query]'s terms in [text]. */
private fun highlightSnippet(text: String, query: String): AnnotatedString {
    val terms = query.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }
    if (terms.isEmpty()) return AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val lower = text.lowercase()
        for (term in terms) {
            val t = term.lowercase()
            var idx = lower.indexOf(t)
            while (idx >= 0) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), idx, idx + term.length)
                idx = lower.indexOf(t, idx + term.length)
            }
        }
    }
}

@Preview
@Composable
private fun SearchContentPreview() {
    MotdTheme {
        SearchContent(
            state = SearchUiState(
                rawQuery = "coroutine",
                hasBufferScope = true,
                scope = SearchScope.ALL,
                groups = listOf(
                    SearchGroup(
                        bufferId = 1, bufferDisplayName = "#kotlin", networkName = "Libera",
                        hits = listOf(
                            SearchHit(
                                message = MessageEntity(
                                    id = 1, bufferId = 1, serverTime = System.currentTimeMillis() - 60_000,
                                    sender = "alice", kind = MessageKind.PRIVMSG,
                                    text = "the new coroutine builder is great", dedupKey = "a",
                                ),
                                bufferDisplayName = "#kotlin", networkName = "Libera",
                            ),
                        ),
                    ),
                ),
            ),
            onQueryChange = {}, onScopeChange = {}, onBack = {}, onOpenHit = {},
        )
    }
}

@Preview
@Composable
private fun SearchEmptyPreview() {
    MotdTheme {
        SearchContent(
            state = SearchUiState(rawQuery = "", hasBufferScope = false),
            onQueryChange = {}, onScopeChange = {}, onBack = {}, onOpenHit = {},
        )
    }
}
