package io.github.trevarj.motd.ui.channellist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.readingInk

/**
 * Channel browser. LIST/ELIST-backed, scoped to [networkId].
 *
 * The busiest channels are auto-fetched on entry. ELIST 'U' is used when available; otherwise
 * the client retains a bounded top set from the LIST stream. Browsing is disabled for an unbound
 * soju BOUNCER_ROOT (its connection can't LIST). Join delegates to
 * ConnectionManager.joinChannel and remains open; Room self-JOIN persistence is authoritative.
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
        onJoin = viewModel::join,
    )
}

/** Stateless body — previewable without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelListContent(
    state: ChannelListUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onJoin: (String) -> Unit,
) {
    // The saved editing value can be restored before the ViewModel query. Submit this visible
    // value directly so IME search, refresh, and retry cannot issue a stale or blank LIST.
    var text by rememberSaveable(state.networkId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query))
    }
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
                    // Fade only, both specs explicit: the RowScope defaults would add a
                    // horizontal expansion that sweeps the fixed top-bar chrome sideways.
                    AnimatedVisibility(
                        visible =
                            state.availability == ChannelBrowserAvailability.READY &&
                                !state.loading,
                        enter = fadeIn(MotdMotion.microFadeIn),
                        exit = fadeOut(MotdMotion.microFadeOut),
                    ) {
                        IconButton(
                            onClick = { onSearch(text.text) },
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
                state.availability != ChannelBrowserAvailability.READY -> {
                    NotReadyState(state)
                }

                else -> {
                    // Search field: substring-mask fetch on the IME search action.
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onQueryChange(it.text)
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.channel_list_search_hint)) },
                        trailingIcon = {
                            IconButton(
                                onClick = { onSearch(text.text) },
                                enabled = !state.loading,
                            ) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.channel_list_search_action),
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(
                                onSearch = { onSearch(text.text) },
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("channel_list_search_field"),
                    )
                    // Ease the bar's height in and out so the results below never jump
                    // by the indicator height twice per fetch.
                    AnimatedVisibility(
                        visible = state.loading,
                        enter =
                            fadeIn(MotdMotion.microFadeIn) +
                                expandVertically(animationSpec = MotdMotion.contentSize),
                        exit =
                            fadeOut(MotdMotion.microFadeOut) +
                                shrinkVertically(animationSpec = MotdMotion.contentSize),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ResultsBody(
                        state = state,
                        onSearch = { onSearch(text.text) },
                        onJoin = onJoin,
                    )
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
        state.error != null && state.listings.isEmpty() && !state.loading -> {
            EmptyState(
                icon = Icons.Outlined.Forum,
                title = stringResource(R.string.channel_list_error_title),
                message = state.error,
                actionLabel = stringResource(R.string.channel_list_retry),
                onAction = onSearch,
            )
        }

        state.loading && state.listings.isEmpty() -> {
            ChannelListLoading()
        }

        // No fetch yet: waiting for the entry auto-fetch to begin.
        !state.loaded && !state.loading -> {
            EmptyState(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.channel_list_search_title),
                message = stringResource(R.string.channel_list_search_ready),
            )
        }

        state.loaded && state.listings.isEmpty() && !state.loading -> {
            EmptyState(
                icon = Icons.Outlined.Forum,
                title = stringResource(R.string.channel_list_empty),
                message = stringResource(R.string.channel_list_empty_message),
            )
        }

        else -> {
            Column(Modifier.fillMaxSize()) {
                Text(
                    if (state.query.isBlank()) {
                        stringResource(R.string.channel_list_popular)
                    } else {
                        pluralStringResource(
                            R.plurals.channel_list_results,
                            state.listings.size,
                            state.listings.size,
                        )
                    },
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
                    pendingChannels = state.pendingChannels,
                    joinedChannels = state.joinedChannels,
                    identityRules = state.identityRules,
                    onJoin = onJoin,
                )
            }
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
    pendingChannels: Set<String>,
    joinedChannels: Set<String>,
    identityRules: io.github.trevarj.motd.irc.proto.IrcIdentityRules,
    onJoin: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val topicFill = ListItemDefaults.colors().containerColor.compositeOver(scheme.background)
    val readingInk = ensureContrast(readingInk(scheme), listOf(topicFill), MESSAGE_TEXT_CONTRAST)
    LazyColumn(Modifier.fillMaxSize()) {
        // Keyed by channel name for stable recomposition over large lists (cap 2000).
        items(listings, key = { it.name }) { listing ->
            val joinStatus =
                channelJoinStatus(
                    listing.name,
                    pendingChannels,
                    joinedChannels,
                    identityRules,
                )
            ListItem(
                headlineContent = { Text(listing.name) },
                supportingContent = {
                    if (listing.topic.isNotBlank()) {
                        Text(
                            io.github.trevarj.motd.ui.components.linkifiedBody(
                                text = listing.topic,
                                linkColor = MaterialTheme.colorScheme.primary,
                                mentionsActive = false,
                                containerColor = topicFill,
                                contentColor = readingInk,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = readingInk,
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
                            enabled = joinStatus == ChannelJoinStatus.JOIN,
                            modifier =
                                Modifier.testTag(
                                    "channel_list_join_${listing.name.removePrefix("#").lowercase()}",
                                ),
                        ) {
                            Text(
                                stringResource(
                                    when (joinStatus) {
                                        ChannelJoinStatus.JOIN -> R.string.channel_list_join
                                        ChannelJoinStatus.JOINING -> R.string.channel_list_joining
                                        ChannelJoinStatus.JOINED -> R.string.channel_list_joined
                                    },
                                ),
                            )
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
    val (title, message) =
        when (state.availability) {
            ChannelBrowserAvailability.INITIALIZING -> {
                R.string.channel_list_checking to R.string.channel_list_checking_message
            }

            ChannelBrowserAvailability.ROOT_UNAVAILABLE -> {
                R.string.channel_list_title to R.string.channel_list_root_cant_browse
            }

            ChannelBrowserAvailability.CONNECTING -> {
                R.string.channel_list_connecting to R.string.channel_list_connecting_message
            }

            ChannelBrowserAvailability.FAILED -> {
                R.string.channel_list_unavailable to R.string.channel_list_offline_message
            }

            ChannelBrowserAvailability.OFFLINE -> {
                R.string.channel_list_offline to R.string.channel_list_offline_message
            }

            ChannelBrowserAvailability.READY -> {
                return
            }
        }
    EmptyState(
        icon = Icons.Outlined.Forum,
        title = stringResource(title),
        message = stringResource(message),
    )
}

// --- previews (fake state, no ViewModel) ---

private val PREVIEW_LISTINGS =
    listOf(
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
            state =
                ChannelListUiState(
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
            state =
                ChannelListUiState(
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
