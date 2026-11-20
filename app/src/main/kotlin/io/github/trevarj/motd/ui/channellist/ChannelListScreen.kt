package io.github.trevarj.motd.ui.channellist

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.ui.components.EffectDropdownMenu
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.components.linkifiedBody
import io.github.trevarj.motd.ui.theme.LocalAdjustPaletteReadability
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.UiDropdownTrailingIcon
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.readableOutlinedTextFieldColors
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import io.github.trevarj.motd.ui.theme.readingInk
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@Composable
fun ChannelListScreen(
    networkId: Long,
    onBack: () -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    viewModel: ChannelListViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val openChat by rememberUpdatedState(onOpenChat)

    fun isCurrentNetwork() = viewModel.state.value.networkId == state.networkId

    LaunchedEffect(networkId) { viewModel.start() }
    LaunchedEffect(state.networkId, state.openBufferId) {
        state.openBufferId?.let {
            if (isCurrentNetwork() && viewModel.consumeOpen(it)) openChat(it)
        }
    }
    ChannelListContent(
        state = state,
        onBack = onBack,
        onSelectNetwork = { if (isCurrentNetwork()) viewModel.selectNetwork(it) },
        onQueryChange = { if (isCurrentNetwork()) viewModel.onQueryChange(it) },
        onSearch = { if (isCurrentNetwork()) viewModel.search(it) },
        onRefresh = { if (isCurrentNetwork()) viewModel.refresh() },
        onRetryDirectory = { if (isCurrentNetwork()) viewModel.retryDirectory(it) },
        onIncludeSmaller = { if (isCurrentNetwork()) viewModel.setIncludeSmaller(it) },
        onConnect = { if (isCurrentNetwork()) viewModel.connect() },
        onJoin = { name, key, open -> if (isCurrentNetwork()) viewModel.join(name, key, open) },
        onOpenChat = { if (isCurrentNetwork()) viewModel.openJoined(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelListContent(
    state: ChannelListUiState,
    onBack: () -> Unit = {},
    onSelectNetwork: (Long) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetryDirectory: (Boolean) -> Unit = { onRefresh() },
    onIncludeSmaller: (Boolean) -> Unit = {},
    onConnect: () -> Unit = {},
    onJoin: (String, String?, Boolean) -> Unit = { _, _, _ -> },
    onOpenChat: (Long) -> Unit = {},
) {
    key(state.networkId) {
        val focus = LocalFocusManager.current
        val canvas = MaterialTheme.colorScheme.surfaceContainerLowest
        val ready = state.availability == ChannelBrowserAvailability.READY
        Scaffold(
            modifier = Modifier.testTag("channel_list_screen"),
            containerColor = canvas,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = canvas),
                    title = { Text(stringResource(R.string.channel_list_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back))
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = ready && !state.popular.loading && !state.smaller.loading,
                            onClick = onRefresh,
                            modifier = Modifier.testTag("channel_list_refresh"),
                        ) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.channel_list_refresh))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                NetworkSelector(state) { id ->
                    focus.clearFocus()
                    onSelectNetwork(id)
                }
                if (ready) {
                    ChannelDirectory(state, onQueryChange, onSearch, onRetryDirectory, onIncludeSmaller, onJoin, onOpenChat)
                } else {
                    NotReadyState(state, onConnect)
                }
            }
        }
    }
}

@Composable
private fun NetworkSelector(
    state: ChannelListUiState,
    onSelect: (Long) -> Unit,
) {
    if (state.networks.isEmpty() && state.networkName.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.channel_list_network)
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(
            enabled = state.networks.isNotEmpty(),
            onClick = { expanded = true },
            colors = readableTextButtonColors(),
            modifier = Modifier.testTag("channel_list_network_selector"),
        ) {
            Icon(Icons.Outlined.Public, contentDescription = null)
            Column(Modifier.padding(horizontal = 8.dp).weight(1f, fill = false)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                if (state.networkName.isNotBlank()) {
                    Text(state.networkName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            UiDropdownTrailingIcon(expanded)
        }
        EffectDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.networks.forEach { network ->
                val active = network.id == state.networkId
                val ink = if (active) scheme.onSecondaryContainer else scheme.onSurface
                DropdownMenuItem(
                    text = { Text(network.name, color = ink) },
                    trailingIcon = { if (active) Icon(Icons.Filled.Check, contentDescription = null, tint = ink) },
                    onClick = {
                        expanded = false
                        if (!active) onSelect(network.id)
                    },
                    modifier =
                        Modifier
                            .background(if (active) scheme.secondaryContainer else Color.Transparent)
                            .semantics { selected = active }
                            .testTag("channel_list_network_option_${network.id}"),
                )
            }
        }
    }
}

@Composable
private fun ChannelDirectory(
    state: ChannelListUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRetryDirectory: (Boolean) -> Unit,
    onIncludeSmaller: (Boolean) -> Unit,
    onJoin: (String, String?, Boolean) -> Unit,
    onOpenChat: (Long) -> Unit,
) {
    var query by remember(state.query) { mutableStateOf(state.query) }
    var expandedTarget by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val rules = state.identityRules
    val searchTerm = networkSearchTerm(query)
    val remote = state.remote?.takeIf { it.query == searchTerm }?.result
    val searchEnabled = searchTerm != null && remote?.loading != true
    val submitSearch = {
        if (searchEnabled) {
            focus.clearFocus()
            onSearch(query)
            listState.requestScrollToItem(0)
        }
    }
    val exactTarget = exactChannelTarget(query, rules)
    val (smaller, popular, additional) =
        remember(state.smaller.listings, state.popular.listings, state.includeSmaller, query, rules, remote?.listings) {
            val seen = mutableSetOf<String>()

            fun List<ChannelListing>.unique() = filter { seen.add(rules.normalize(it.name)) }

            Triple(
                if (state.includeSmaller) {
                    filterLoadedChannels(state.smaller.listings, query, rules).sortedBy { it.userCount }.unique()
                } else {
                    emptyList()
                },
                filterLoadedChannels(state.popular.listings, query, rules).sortedByDescending { it.userCount }.unique(),
                remote?.listings.orEmpty().unique(),
            )
        }
    val join = { name: String, secret: String?, openAfter: Boolean ->
        focus.clearFocus()
        onJoin(name, secret, openAfter)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            colors = readableOutlinedTextFieldColors(),
            value = query,
            onValueChange = {
                if (it.length <= 400) {
                    query = it
                    onQueryChange(it)
                    listState.requestScrollToItem(0)
                }
            },
            singleLine = true,
            label = { Text(stringResource(R.string.channel_list_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
            modifier = Modifier.weight(1f).testTag("channel_list_search_field"),
        )
        TextButton(
            colors = readableTextButtonColors(),
            enabled = searchEnabled,
            onClick = submitSearch,
            modifier = Modifier.testTag("channel_list_search_network"),
        ) {
            Text(stringResource(R.string.channel_list_search_action))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("channel_list_results"),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (exactTarget != null) {
            item(key = "exact") {
                key(rules.normalize(exactTarget)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            ChannelAction(exactTarget, state, true, "channel_list_join_exact", join, onOpenChat)
                        }
                    }
                }
            }
        }
        item(key = "include_smaller") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(state.includeSmaller, role = Role.Switch, onValueChange = onIncludeSmaller)
                    .testTag("channel_list_include_smaller"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.channel_list_include_smaller), Modifier.weight(1f))
                Switch(checked = state.includeSmaller, onCheckedChange = null)
            }
        }
        val listingRow: @Composable (ChannelListing) -> Unit = { listing ->
            val target = rules.normalize(listing.name)
            ChannelRow(
                listing = listing,
                state = state,
                expanded = expandedTarget == target,
                onExpand = { expandedTarget = target.takeUnless { expandedTarget == it } },
                onJoin = join,
                onOpenChat = onOpenChat,
            )
        }
        val listingKey: (ChannelListing) -> String = { "channel:${state.networkId}:${rules.normalize(it.name)}" }
        if (remote != null && searchTerm != null) {
            directorySection(
                "remote",
                R.string.channel_list_remote,
                remote,
                additional,
                listingKey,
                submitSearch,
                listingRow,
                emptyLabel = if (remote.listings.isEmpty()) R.string.channel_list_empty else R.string.channel_list_remote_duplicates,
            )
        }
        if (query.isNotBlank()) {
            item(key = "local_matches") {
                Column {
                    SectionHeading(R.string.channel_list_local)
                    Text(
                        stringResource(R.string.channel_list_local_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.includeSmaller) {
            directorySection("smaller", R.string.channel_list_smaller, state.smaller, smaller, listingKey, { onRetryDirectory(true) }, listingRow)
        }
        directorySection("popular", R.string.channel_list_popular, state.popular, popular, listingKey, { onRetryDirectory(false) }, listingRow)
    }
}

private fun LazyListScope.directorySection(
    id: String,
    @StringRes title: Int,
    result: DirectoryResult,
    rows: List<ChannelListing>,
    listingKey: (ChannelListing) -> String,
    onRetry: () -> Unit,
    row: @Composable (ChannelListing) -> Unit,
    @StringRes emptyLabel: Int = R.string.channel_list_empty,
) {
    item(key = "section_$id") {
        Column(Modifier.testTag("channel_list_section_$id")) {
            SectionHeading(title)
            when {
                result.loading -> {
                    Text(stringResource(R.string.channel_list_loading), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }

                result.error != null -> {
                    Text(result.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        colors = readableTextButtonColors(),
                        onClick = onRetry,
                        modifier = Modifier.testTag("channel_list_retry_$id"),
                    ) {
                        Text(stringResource(R.string.channel_list_retry))
                    }
                }

                result.loaded && rows.isEmpty() -> {
                    Text(
                        stringResource(emptyLabel),
                        modifier =
                            if (id == "remote" && result.listings.isEmpty()) {
                                Modifier.testTag("channel_list_search_empty")
                            } else {
                                Modifier
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    items(rows, key = listingKey) { row(it) }
}

@Composable
private fun SectionHeading(
    @StringRes title: Int,
) {
    Text(
        stringResource(title),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun ChannelRow(
    listing: ChannelListing,
    state: ChannelListUiState,
    expanded: Boolean,
    onExpand: () -> Unit,
    onJoin: (String, String?, Boolean) -> Unit,
    onOpenChat: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerLow
    val readable = LocalAdjustPaletteReadability.current
    val seed = readingInk(scheme)
    val ink = remember(seed, fill, readable) { if (readable) ensureContrast(seed, listOf(fill), MESSAGE_TEXT_CONTRAST) else seed }
    val target = state.identityRules.normalize(listing.name)
    val tagTarget = target.removePrefix("#")
    val snippet = remember(listing.topic) { plainIrcText(listing.topic) }
    Surface(color = fill, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (snippet.isNotBlank()) {
                            Modifier.clickable(
                                onClickLabel = stringResource(if (expanded) R.string.channel_list_hide_topic else R.string.channel_list_show_topic),
                                onClick = onExpand,
                            )
                        } else {
                            Modifier
                        },
                    ).testTag("channel_list_row_$tagTarget")
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(listing.name, style = MaterialTheme.typography.titleSmall)
                    if (!expanded && snippet.isNotBlank()) {
                        Text(snippet, color = ink, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        pluralStringResource(R.plurals.channel_list_users, listing.userCount, listing.userCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (!state.joinErrors.containsKey(target)) {
                    ChannelAction(listing.name, state, false, "channel_list_row_join_$tagTarget", onJoin, onOpenChat)
                }
            }
            if (expanded) {
                val linkInk = if (readable) scheme.primary else scheme.readableForeground(scheme.primary, fill)
                val topic =
                    remember(listing.topic, linkInk, fill, ink, readable) {
                        linkifiedBody(
                            text = listing.topic,
                            linkColor = linkInk,
                            mentionsActive = false,
                            containerColor = fill,
                            contentColor = ink,
                            adjustPaletteReadability = readable,
                        )
                    }
                Text(
                    topic,
                    color = ink,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).testTag("channel_list_topic_$tagTarget"),
                )
            }
            if (state.joinErrors.containsKey(target)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ChannelAction(listing.name, state, false, "channel_list_row_join_$tagTarget", onJoin, onOpenChat)
                }
            }
        }
    }
}

@Composable
private fun ChannelAction(
    name: String,
    state: ChannelListUiState,
    exact: Boolean,
    tag: String,
    onJoin: (String, String?, Boolean) -> Unit,
    onOpenChat: (Long) -> Unit,
) {
    val target = state.identityRules.normalize(name)
    val bufferId = state.joinedChannels[target]
    val pending = target in state.pendingChannels && bufferId == null
    val failure = state.joinErrors[target].takeIf { bufferId == null && !pending }
    val secret = if (failure?.needsKey == true) remember(target) { TextFieldState() } else null
    if (failure != null) {
        Text(failure.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    if (secret != null) {
        OutlinedSecureTextField(
            state = secret,
            label = { Text(stringResource(R.string.channel_list_key)) },
            colors = readableOutlinedTextFieldColors(),
            textObfuscationMode = TextObfuscationMode.Hidden,
            modifier = Modifier.fillMaxWidth().testTag("${tag}_key"),
        )
    }
    TextButton(
        colors = readableTextButtonColors(background = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.testTag(tag),
        enabled = !pending && (secret == null || secret.text.isNotEmpty()),
        onClick = {
            if (bufferId != null) {
                onOpenChat(bufferId)
            } else {
                val key = secret?.text?.toString()
                secret?.clearText()
                onJoin(name, key, exact)
            }
        },
    ) {
        Text(
            when {
                pending -> stringResource(R.string.channel_list_joining)
                bufferId != null && exact -> stringResource(R.string.channel_list_open_exact, name)
                bufferId != null -> stringResource(R.string.channel_list_open)
                exact -> stringResource(R.string.channel_list_join_exact, name)
                else -> stringResource(R.string.channel_list_join)
            },
        )
    }
}

@Composable
private fun NotReadyState(
    state: ChannelListUiState,
    onConnect: () -> Unit,
) {
    val availability = state.availability
    val connect = state.networkId != 0L && (availability == ChannelBrowserAvailability.OFFLINE || availability == ChannelBrowserAvailability.FAILED)
    val (title, message) =
        when (availability) {
            ChannelBrowserAvailability.INITIALIZING -> R.string.channel_list_checking to R.string.channel_list_checking_message
            ChannelBrowserAvailability.ROOT_UNAVAILABLE -> R.string.channel_list_title to R.string.channel_list_root_cant_browse
            ChannelBrowserAvailability.CONNECTING -> R.string.channel_list_connecting to R.string.channel_list_connecting_message
            ChannelBrowserAvailability.FAILED -> R.string.channel_list_unavailable to R.string.channel_list_offline_message
            ChannelBrowserAvailability.OFFLINE -> R.string.channel_list_offline to R.string.channel_list_offline_message
            ChannelBrowserAvailability.READY -> return
        }
    EmptyState(
        icon = Icons.Outlined.Forum,
        title = stringResource(title),
        message =
            when {
                !state.initialized || state.networkId != 0L -> state.connectionError ?: stringResource(message)
                else -> stringResource(R.string.channel_list_no_networks)
            },
        actionLabel =
            if (connect) {
                stringResource(
                    if (availability == ChannelBrowserAvailability.FAILED || state.connectionError != null) {
                        R.string.channel_list_retry
                    } else {
                        R.string.channel_list_connect
                    },
                )
            } else {
                null
            },
        onAction = onConnect.takeIf { connect },
        modifier = Modifier.testTag("channel_list_connection_status"),
    )
}

@Preview
@Composable
private fun ChannelListLoadedPreview() {
    MotdTheme {
        ChannelListContent(
            state =
                ChannelListUiState(
                    networkName = "Libera.Chat",
                    connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    initialized = true,
                    popular =
                        DirectoryResult(
                            listings =
                                listOf(
                                    ChannelListing("#linux", 1423, "All things Linux and free software"),
                                    ChannelListing("#kotlin", 892, "Kotlin programming language"),
                                    ChannelListing("#archlinux", 640, ""),
                                ),
                            loaded = true,
                        ),
                ),
        )
    }
}
