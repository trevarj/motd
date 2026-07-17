package io.github.trevarj.motd.ui.channellist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.theme.MotdTheme

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
    ChannelListContent(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::fetch,
        onJoin = { name -> viewModel.join(name, onDone = onBack) },
    )
}

/** Stateless body — previewable without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelListContent(
    state: ChannelListUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onJoin: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.channel_list_title))
                        if (state.networkName.isNotBlank()) {
                            Text(
                                state.networkName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
                actions = {
                    if (state.availability == ChannelBrowserAvailability.READY && !state.loading) {
                        IconButton(
                            onClick = onSearch,
                            enabled = !state.requiresQuery || state.query.isNotBlank(),
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.channel_list_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.availability != ChannelBrowserAvailability.READY -> NotReadyState(state)

                else -> {
                    // Visible query lives in local IME state so keystrokes aren't dropped and the
                    // cursor is preserved; the ViewModel query drives the fetch mask only. Seeded
                    // once from the incoming state.
                    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue(state.query))
                    }
                    // Search field: substring-mask fetch on the IME search action.
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; onQueryChange(it.text) },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.channel_list_search_hint)) },
                        trailingIcon = {
                            IconButton(
                                onClick = onSearch,
                                enabled = !state.loading && (!state.requiresQuery || text.text.isNotBlank()),
                            ) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.channel_list_search_action),
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (state.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ResultsBody(state = state, onSearch = onSearch, onJoin = onJoin)
                }
            }
        }
    }
}

@Composable
private fun ResultsBody(
    state: ChannelListUiState,
    onSearch: () -> Unit,
    onJoin: (String) -> Unit,
) {
    when {
        state.error != null && state.listings.isEmpty() && !state.loading ->
            EmptyState(
                icon = Icons.Outlined.Forum,
                title = stringResource(R.string.channel_list_error_title),
                message = state.error,
                actionLabel = stringResource(R.string.channel_list_retry),
                onAction = onSearch,
            )

        state.loading && state.listings.isEmpty() -> ChannelListLoading()

        // No fetch yet: either gated on a search mask (no ELIST 'U') or waiting on the auto-fetch.
        !state.loaded && !state.loading -> {
            val msg = if (state.requiresQuery) {
                stringResource(R.string.channel_list_gated)
            } else {
                stringResource(R.string.channel_list_search_ready)
            }
            EmptyState(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.channel_list_search_title),
                message = msg,
            )
        }

        state.loaded && state.listings.isEmpty() && !state.loading ->
            EmptyState(
                icon = Icons.Outlined.Forum,
                title = stringResource(R.string.channel_list_empty),
                message = stringResource(R.string.channel_list_empty_message),
            )

        else -> Column(Modifier.fillMaxSize()) {
            Text(
                pluralStringResource(
                    R.plurals.channel_list_results,
                    state.listings.size,
                    state.listings.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            state.joinError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ChannelList(
                listings = state.listings,
                joiningChannel = state.joiningChannel,
                onJoin = onJoin,
            )
        }
    }
}

@Composable
private fun ChannelListLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.channel_list_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelList(
    listings: List<ChannelListing>,
    joiningChannel: String?,
    onJoin: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // Keyed by channel name for stable recomposition over large lists (cap 2000).
        items(listings, key = { it.name }) { listing ->
            ListItem(
                headlineContent = { Text(listing.name) },
                supportingContent = {
                    if (listing.topic.isNotBlank()) {
                        Text(
                            listing.topic,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            pluralStringResource(
                                R.plurals.channel_list_users,
                                listing.userCount,
                                listing.userCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { onJoin(listing.name) },
                            enabled = joiningChannel == null,
                        ) {
                            if (joiningChannel == listing.name) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(R.string.channel_list_join))
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NotReadyState(state: ChannelListUiState) {
    val (title, message) = when (state.availability) {
        ChannelBrowserAvailability.INITIALIZING ->
            R.string.channel_list_checking to R.string.channel_list_checking_message
        ChannelBrowserAvailability.ROOT_UNAVAILABLE ->
            R.string.channel_list_title to R.string.channel_list_root_cant_browse
        ChannelBrowserAvailability.CONNECTING ->
            R.string.channel_list_connecting to R.string.channel_list_connecting_message
        ChannelBrowserAvailability.FAILED ->
            R.string.channel_list_unavailable to R.string.channel_list_offline_message
        ChannelBrowserAvailability.OFFLINE ->
            R.string.channel_list_offline to R.string.channel_list_offline_message
        ChannelBrowserAvailability.READY -> return
    }
    EmptyState(
        icon = Icons.Outlined.Forum,
        title = stringResource(title),
        message = stringResource(message),
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
                initialized = true,
                listings = PREVIEW_LISTINGS,
                loaded = true,
            ),
            onBack = {},
            onQueryChange = {},
            onSearch = {},
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
            state = ChannelListUiState(
                connState = IrcClientState.Disconnected,
                initialized = true,
            ),
            onBack = {},
            onQueryChange = {},
            onSearch = {},
            onJoin = {},
        )
    }
}
