package io.github.trevarj.motd.ui.channellist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.launch

/**
 * Channel browser (plans/16 §5.7). LIST/ELIST-backed, scoped to [networkId].
 *
 * Per Confirmed decision #6: when the server advertises ELIST 'U' the busiest channels are
 * auto-fetched on entry; otherwise the user must type a search mask first. Browsing is disabled
 * for an unbound soju BOUNCER_ROOT (its connection can't LIST). Join delegates to
 * ConnectionManager.joinChannel and pops back; the buffer appears on the JOIN echo.
 *
 * NOTE: the join flow uses [onBack] (per plan §5.7) — no separate nav-to-channel callback is
 * needed, so ChannelListScreen keeps the WP-V0 (networkId, onBack) signature and NavGraph is
 * untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    networkId: Long,
    onBack: () -> Unit = {},
    viewModel: ChannelListViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val joiningFmt = stringResource(R.string.channel_list_joining)

    ChannelListContent(
        state = state,
        snackbarHost = snackbarHost,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::fetch,
        onConnect = viewModel::connect,
        onJoin = { name ->
            viewModel.join(name, onDone = onBack)
            scope.launch { snackbarHost.showSnackbar(joiningFmt.format(name)) }
        },
    )
}

/** Stateless body — previewable without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelListContent(
    state: ChannelListUiState,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onConnect: () -> Unit,
    onJoin: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channel_list_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                // Root can't LIST, and a disconnected/failed network offers a Connect card.
                state.isRoot || !state.isReady ->
                    NotReadyState(isRoot = state.isRoot, onConnect = onConnect)

                else -> {
                    // Search field: substring-mask fetch on the IME search action.
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.channel_list_search_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (state.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ResultsBody(state = state, onJoin = onJoin)
                }
            }
        }
    }
}

@Composable
private fun ResultsBody(
    state: ChannelListUiState,
    onJoin: (String) -> Unit,
) {
    when {
        // No fetch yet: either gated on a search mask (no ELIST 'U') or waiting on the auto-fetch.
        !state.loaded && !state.loading -> {
            val msg = if (state.gated) {
                stringResource(R.string.channel_list_search_hint)
            } else {
                stringResource(R.string.channel_list_empty)
            }
            EmptyState(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.channel_list_title),
                message = state.error ?: msg,
            )
        }

        state.loaded && state.listings.isEmpty() && !state.loading ->
            EmptyState(
                icon = Icons.Outlined.Forum,
                title = stringResource(R.string.channel_list_empty),
                message = state.error ?: stringResource(R.string.channel_list_empty),
            )

        else -> ChannelList(listings = state.listings, onJoin = onJoin)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelList(
    listings: List<ChannelListing>,
    onJoin: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // Keyed by channel name for stable recomposition over large lists (cap 2000).
        items(listings, key = { it.name }) { listing ->
            ListItem(
                headlineContent = { Text(listing.name) },
                supportingContent = {
                    if (listing.topic.isNotBlank()) {
                        Text(listing.topic, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                },
                trailingContent = {
                    Text(stringResource(R.string.channel_list_users, listing.userCount))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJoin(listing.name) },
            )
        }
    }
}

@Composable
private fun NotReadyState(isRoot: Boolean, onConnect: () -> Unit) {
    // A soju root can't browse; hide the Connect action (connecting won't help).
    EmptyState(
        icon = Icons.Outlined.Forum,
        title = stringResource(R.string.channel_list_title),
        message = stringResource(R.string.channel_list_connect_to_browse),
        actionLabel = if (isRoot) null else stringResource(R.string.channel_list_connect),
        onAction = if (isRoot) null else onConnect,
    )
}

// --- previews (fake state, no ViewModel) ---

private val PREVIEW_LISTINGS = listOf(
    ChannelListing("#linux", 1423, "All things Linux and free software"),
    ChannelListing("#kotlin", 892, "Kotlin programming language"),
    ChannelListing("#archlinux", 640, ""),
)

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun ChannelListLoadedPreview() {
    MotdTheme {
        ChannelListContent(
            state = ChannelListUiState(
                connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                listings = PREVIEW_LISTINGS,
                loaded = true,
            ),
            snackbarHost = remember { SnackbarHostState() },
            onBack = {},
            onQueryChange = {},
            onSearch = {},
            onConnect = {},
            onJoin = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun ChannelListNotReadyPreview() {
    MotdTheme {
        ChannelListContent(
            state = ChannelListUiState(connState = IrcClientState.Disconnected),
            snackbarHost = remember { SnackbarHostState() },
            onBack = {},
            onQueryChange = {},
            onSearch = {},
            onConnect = {},
            onJoin = {},
        )
    }
}
