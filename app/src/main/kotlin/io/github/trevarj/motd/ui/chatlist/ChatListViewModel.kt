package io.github.trevarj.motd.ui.chatlist

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InvitationEventRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.dickord.DickordLabsPrefs
import io.github.trevarj.motd.dickord.isDickordPortalConversation
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.service.markChatsRead
import io.github.trevarj.motd.service.unreadBufferIds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Per-row chat-list sync affordance; coarser than [HistorySyncStatus] so unrelated reason-string
 * churn (e.g. a retried [HistorySyncStatus.Partial] with a new reason) never invalidates a row. */
enum class ChatListSyncIndicator { NONE, QUEUED, WAITING, SYNCING, ERROR, UNAVAILABLE }

/**
 * Pure mapper from the coordinator's per-buffer status map to the chat list's coarser indicator.
 * [HistorySyncStatus.Idle] settles to [ChatListSyncIndicator.NONE] and is dropped from the result
 * map entirely, so a row with no entry never renders a badge.
 *
 * [queuedCuesVisible] is the shared sync-chrome gate (see [ChatListSyncChrome]): while it is closed
 * the optimistic states paint nothing, so a pass that resolves inside the anti-flash window never
 * churns the list. [HistorySyncStatus.Partial] is deliberately unpainted here — the in-chat stale
 * chip carries it, and a list dot that reads identical to a hard failure overstated it.
 */
internal fun chatListSyncIndicators(
    statuses: Map<Long, HistorySyncStatus>,
    queuedCuesVisible: Boolean,
): Map<Long, ChatListSyncIndicator> =
    statuses
        .mapNotNull { (bufferId, status) ->
            val indicator =
                when (status) {
                    HistorySyncStatus.Queued -> {
                        if (queuedCuesVisible) ChatListSyncIndicator.QUEUED else ChatListSyncIndicator.NONE
                    }

                    HistorySyncStatus.AwaitingConnection -> {
                        if (queuedCuesVisible) ChatListSyncIndicator.WAITING else ChatListSyncIndicator.NONE
                    }

                    HistorySyncStatus.Syncing -> {
                        ChatListSyncIndicator.SYNCING
                    }

                    is HistorySyncStatus.Failed -> {
                        ChatListSyncIndicator.ERROR
                    }

                    HistorySyncStatus.Unavailable -> {
                        ChatListSyncIndicator.UNAVAILABLE
                    }

                    is HistorySyncStatus.Partial, HistorySyncStatus.Idle -> {
                        ChatListSyncIndicator.NONE
                    }
                }
            (bufferId to indicator).takeIf { indicator != ChatListSyncIndicator.NONE }
        }.toMap()

/** Identity-bearing local nickname results; mismatched network/prefix results are never rendered. */
data class NickSuggestions(
    val networkId: Long? = null,
    val prefix: String = "",
    val candidates: List<String> = emptyList(),
)

private data class NickSuggestionRequest(
    val networkId: Long,
    val prefix: String,
    val selfNick: String,
)

/** Single UI state for the chat list screen. */
data class ChatListState(
    val rows: List<ChatListRow> = emptyList(),
    /** Scoped archived rows; all global badges and drawer rollups deliberately exclude these. */
    val archivedRows: List<ChatListRow> = emptyList(),
    val invitations: List<ChatListInvitation> = emptyList(),
    val folders: List<ChatFolderEntity> = emptyList(),
    val folderDisplayMode: FolderDisplayMode = FolderDisplayMode.INLINE,
    val showFolderChatsInAll: Boolean = true,
    val connection: Map<Long, IrcClientState> = emptyMap(),
    val queryPresence: Map<Long, PresenceState> = emptyMap(),
    val networks: List<NetworkEntity> = emptyList(),
    val loading: Boolean = true,
    val onboardingComplete: Boolean = false,
    // Round 4: global friend/fool sets drive chat-list sectioning.
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    // Round 5: drawer server selector + scoping.
    val selectedNetworkId: Long? = null,
    val drawerRows: List<DrawerRow> = emptyList(),
    val allUnread: Int = 0, // "All chats" unread rollup (non-muted)
    val allMentions: Int = 0, // "All chats" mention rollup
    /** Global Feed lab flag; off hides both entry points into the feed. */
    val globalFeedEnabled: Boolean = false,
    /** Lab-gated portal navigation state emitted atomically with the ordinary row partition. */
    val dickordEnabled: Boolean = false,
    val dickordUnreadSummary: ChatFolderSummary? = null,
) {
    val allUnreadIncomplete: Boolean
        get() =
            drawerRows.takeIf(List<DrawerRow>::isNotEmpty)?.any(DrawerRow::unreadIncomplete)
                ?: rows.any { !it.muted && it.unreadCountIncomplete }
    val allMentionsIncomplete: Boolean
        get() =
            drawerRows.takeIf(List<DrawerRow>::isNotEmpty)?.any(DrawerRow::mentionsIncomplete)
                ?: rows.any { !it.muted && it.mentionCountIncomplete }

    /** Effective unread count for the current drawer scope; muted activity stays row-local. */
    val scopedUnreadCount: Int
        get() = rows.filterNot { it.type == BufferType.SERVER || it.muted }.sumOf { it.unreadCount }

    /** The scoped network's name, or null when unscoped (drives the top-bar title/chip). */
    val selectedNetworkName: String?
        get() = selectedNetworkId?.let { id -> networks.firstOrNull { it.id == id }?.name }

    /** Every network is absent from the map or Disconnected -> the "Go online" affordance shows. */
    val allOffline: Boolean
        get() = networks.all { connection[it.id].let { s -> s == null || s is IrcClientState.Disconnected } }
}

data class ChatListInvitation(
    val messageId: Long,
    val bufferId: Long,
    val networkId: Long,
    val networkName: String,
    val inviter: String,
    val channel: String,
    val text: String,
    val state: InviteState,
    val serverTime: Long,
) {
    val actionable: Boolean
        get() = state == InviteState.PENDING || state == InviteState.JOINING || state == InviteState.FAILED
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatListViewModel
    @Inject
    constructor(
        private val bufferRepository: BufferRepository,
        private val chatFolders: ChatFolderRepository? = null,
        private val networkRepository: NetworkRepository,
        private val connectionManager: ConnectionManager,
        private val historyResync: HistoryResyncController,
        private val channelCloseCoordinator: ChannelCloseCoordinator,
        private val readMarkerRepository: ReadMarkerSnapshotter,
        private val settingsRepository: SettingsRepository,
        onboardingPrefs: OnboardingPrefs,
        globalFeedPrefs: GlobalFeedPrefs,
        dickordLabsPrefs: DickordLabsPrefs,
        private val savedStateHandle: SavedStateHandle,
        private val appVisibility: AppVisibility,
    ) : ViewModel() {
        init {
            // The coordinator is process-scoped and observes persisted pending closes, so creating a
            // fresh ViewModel after process/configuration recreation re-drives any unfinished leaves.
            channelCloseCoordinator.start()
        }

        // One-shot: unmuting marked a muted backlog read, so the screen can report it and offer an undo.
        private val _muteBacklogSuppressions = MutableSharedFlow<List<MuteBacklogSuppression>>(extraBufferCapacity = 1)
        val muteBacklogSuppressions: SharedFlow<List<MuteBacklogSuppression>> = _muteBacklogSuppressions.asSharedFlow()

        // Aggregate header chrome, debounced so a fast pass never flashes. Engine-owned counts; the
        // driver's clock is elapsed real time, which keeps the windows honest across Doze.
        val syncChrome: StateFlow<ChatListSyncChrome> =
            combine(
                historyResync.passProgress,
                historyResync.syncStatuses,
                ::syncChromeSnapshot,
            ).distinctUntilChanged()
                .presentSyncChrome(SystemClock::elapsedRealtime)
                .stateIn(viewModelScope, WhileSubscribed(5_000), ChatListSyncChrome.Hidden)

        // Deliberately kept out of the [state] combine: the enum map already defeats a retried
        // Failed's reason-string churn, but folding it into ChatListState would still recompose every
        // row on any one buffer's sync transition instead of just its own row. Gated by the same
        // chrome flow as the header so queued rings and the explaining line appear together.
        val syncIndicators: StateFlow<Map<Long, ChatListSyncIndicator>> =
            combine(
                historyResync.syncStatuses,
                syncChrome.map { it != ChatListSyncChrome.Hidden }.distinctUntilChanged(),
                ::chatListSyncIndicators,
            ).distinctUntilChanged()
                .stateIn(viewModelScope, WhileSubscribed(5_000), emptyMap())

        // Scope selection survives config changes; null = unified list (default).
        private val selection = MutableStateFlow(savedStateHandle.get<Long?>(KEY_SELECTED))

        private val nickSuggestionRequest = MutableStateFlow<NickSuggestionRequest?>(null)
        val nickSuggestions: StateFlow<NickSuggestions> =
            nickSuggestionRequest
                .flatMapLatest { request ->
                    if (request == null) {
                        flowOf(NickSuggestions())
                    } else {
                        bufferRepository
                            .observeNickSuggestions(request.networkId, request.prefix, request.selfNick)
                            .map { candidates -> NickSuggestions(request.networkId, request.prefix, candidates) }
                    }
                }.stateIn(viewModelScope, WhileSubscribed(5_000), NickSuggestions())

        // Title-bar connectivity cue, kept out of the [state] combine like the sync flows above so a
        // socket transition never rebuilds the row list. connectionStates republishes on every caps/
        // isupport re-snapshot of a Ready connection (the churn ef42ae77 conflated out of the status
        // notification); collapsing to one boolean BEFORE the anti-flash windows keeps all of that from
        // ever reaching composition. Scoped to the selected network's ids so a cue beside a network
        // name reports only that network's sockets.
        val titleConnecting: StateFlow<Boolean> =
            combine(
                connectionManager.connectionStates,
                networkRepository.observeNetworks(),
                selection,
            ) { states, networks, selected ->
                titleConnectingSnapshot(states, scopeNetworkIds(selected, networks))
            }.distinctUntilChanged()
                .presentTitleConnecting(SystemClock::elapsedRealtime)
                .stateIn(viewModelScope, WhileSubscribed(5_000), false)

        // Manual drawer order the user is arranging or that Room has not published back yet. Null means
        // "stored order is authoritative"; see [pendingNetworkOrder] and [commitNetworkOrder].
        private val pendingOrder = MutableStateFlow<List<Long>?>(null)
        private val selectionAndOrder = selection.combine(pendingOrder, ::Pair)
        private val archiveOverrides = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
        private val chatListRows =
            bufferRepository
                .observeChatList()
                .onEach { rows ->
                    val settledIds = settledArchiveOverrideIds(rows, archiveOverrides.value)
                    if (settledIds.isNotEmpty()) archiveOverrides.value = archiveOverrides.value - settledIds
                }.combine(archiveOverrides, ::applyArchiveOverrides)
        private val chatListData =
            combine(chatListRows, bufferRepository.observeInvitations(), chatFolders?.observeFolders() ?: kotlinx.coroutines.flow.flowOf(emptyList())) { rows, invitations, folders ->
                Triple(rows, invitations, folders)
            }
        private val settingsAndLabs =
            combine(
                settingsRepository.settings,
                onboardingPrefs.completed,
                globalFeedPrefs.enabled,
                dickordLabsPrefs.enabled,
            ) { settings, onboardingComplete, globalFeedEnabled, dickordEnabled ->
                Triple(settings, onboardingComplete, globalFeedEnabled) to dickordEnabled
            }

        val state: StateFlow<ChatListState> =
            combine(
                chatListData,
                networkRepository.observeNetworks(),
                connectionManager.connectionStates.combine(connectionManager.presenceStates) { connection, presence ->
                    connection to presence
                },
                settingsAndLabs,
                selectionAndOrder,
            ) { listData, networks, connectionAndPresence, settingsAndLabs, selectionAndOrder ->
                val (rows, invitationEvents, folders) = listData
                val (connection, presence) = connectionAndPresence
                val (settingsAndOnboarding, dickordEnabled) = settingsAndLabs
                val (settings, onboardingComplete, globalFeedEnabled) = settingsAndOnboarding
                val (selected, pending) = selectionAndOrder
                // If the selected network was deleted, fall back to the unified list.
                val validSelection = selected?.takeIf { id -> networks.any { it.id == id } }
                if (validSelection != selected) setSelection(validSelection)

                val storedDrawerRows = buildDrawerRows(networks, rows.filterNot(ChatListRow::archived), connection)
                // The stored rows already display the pending arrangement: drop the overlay so stored
                // state is authoritative again. The settled check tolerates rows that differ from what
                // the write predicted (a network deleted or added in between) — the overlay must always
                // clear eventually, or the drawer is pinned to a stale order forever. compareAndSet,
                // because a further move may have landed while this emission was built.
                if (pending != null && drawerOrderSettled(storedDrawerRows, pending)) {
                    pendingOrder.compareAndSet(pending, null)
                }

                val scopedRows = scopeRows(rows, validSelection, networks)
                val ordinaryScopedRows = ordinaryChatListRows(scopedRows, dickordEnabled)
                val (activeRows, archivedRows) = partitionArchivedRows(ordinaryScopedRows)
                val scopedBufferIds = scopedRows.mapTo(mutableSetOf(), ChatListRow::bufferId)
                ChatListState(
                    rows = activeRows,
                    archivedRows = archivedRows,
                    invitations =
                        invitationEvents
                            .filter { it.bufferId in scopedBufferIds }
                            .mapNotNull(::toChatListInvitation),
                    folders = folders,
                    folderDisplayMode = settings.folderDisplayMode,
                    showFolderChatsInAll = settings.showFolderChatsInAll,
                    connection = connection,
                    queryPresence =
                        scopedRows
                            .asSequence()
                            .filter { it.type == BufferType.QUERY }
                            .mapNotNull { row ->
                                val normalize =
                                    connectionManager.clientFor(row.networkId)?.isupport?.let { it::normalize }
                                        ?: return@mapNotNull null
                                presence[PresenceKey(row.networkId, normalize(row.displayName))]?.let { row.bufferId to it }
                            }.toMap(),
                    networks = networks,
                    loading = false,
                    onboardingComplete = onboardingComplete,
                    friends = settings.friends,
                    fools = settings.fools,
                    selectedNetworkId = validSelection,
                    drawerRows = applyDrawerOrder(storedDrawerRows, pending),
                    allUnread = rows.filterNot { it.muted || it.archived }.sumOf { it.unreadCount },
                    allMentions = rows.filterNot { it.muted || it.archived }.sumOf { it.mentionCount },
                    globalFeedEnabled = globalFeedEnabled,
                    dickordEnabled = dickordEnabled,
                    dickordUnreadSummary =
                        if (dickordEnabled) {
                            summarizeFolder(
                                rows.filter { row ->
                                    !row.archived && isDickordPortalConversation(row.type, row.displayName)
                                },
                            )
                        } else {
                            null
                        },
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ChatListState(),
            )

        /**
         * Hold [state] hot for as long as the app is on screen, not merely while this pane is composed.
         *
         * Navigation composes one destination on a phone, so opening a chat DISPOSES the chat list and
         * drops its only subscriber; five seconds later the whole combine is torn down. The StateFlow
         * keeps serving the last snapshot it produced, which is the one from before the reader cleared
         * the room — so returning composes the pane against stale data, paints the chat bold with its
         * old unread count, and only swaps to the truth once a cold restart (a DataStore read plus the
         * chat-list SQL and the networks query) delivers a fresh emission. That swap is the flash, and
         * the wait for it is the delay before the chat reads as read.
         *
         * Holding it here is bounded on both sides: this ViewModel dies with the chat list's own
         * back-stack entry, so nothing is kept warm once the user leaves that section of the app, and
         * [AppVisibility] stops the queries whenever the app itself leaves the screen — which is the
         * only case the subscription-scoped teardown was protecting.
         *
         * Declared after [state] on purpose: initializers run in declaration order, and an earlier one
         * would capture a null.
         */
        init {
            viewModelScope.launch {
                appVisibility.onScreen.collectLatest { onScreen -> if (onScreen) state.collect {} }
            }
        }

        fun assignFolder(
            bufferIds: Collection<Long>,
            folderId: Long?,
            onResult: (Boolean) -> Unit = {},
        ) {
            viewModelScope.launch {
                runCatching { chatFolders?.assign(bufferIds, folderId) ?: error("Folder storage unavailable.") }
                    .onSuccess { onResult(true) }
                    .onFailure { onResult(false) }
            }
        }

        fun createFolderAndAssign(
            name: String,
            icon: FolderIconRef,
            bufferIds: Collection<Long>,
            onResult: (Boolean) -> Unit = {},
        ) {
            viewModelScope.launch {
                runCatching { chatFolders?.createAndAssign(name, icon, bufferIds) ?: error("Folder storage unavailable.") }
                    .onSuccess { onResult(true) }
                    .onFailure { onResult(false) }
            }
        }

        fun setFolderExpanded(
            folderId: Long,
            expanded: Boolean,
        ) = viewModelScope.launch { chatFolders?.setExpanded(folderId, expanded) }

        fun setPinned(
            bufferId: Long,
            pinned: Boolean,
        ) = setPinned(listOf(bufferId), pinned)

        fun setPinned(
            bufferIds: Collection<Long>,
            pinned: Boolean,
        ) {
            val ids = bufferIds.toList().distinct()
            if (ids.isEmpty()) return
            viewModelScope.launch { ids.forEach { bufferRepository.setPinned(it, pinned) } }
        }

        fun setMuted(
            bufferId: Long,
            muted: Boolean,
        ) = setMuted(listOf(bufferId), muted)

        fun setMuted(
            bufferIds: Collection<Long>,
            muted: Boolean,
        ) {
            val ids = bufferIds.toList().distinct()
            if (ids.isEmpty()) return
            viewModelScope.launch {
                val suppressed = ids.mapNotNull { bufferRepository.setMuted(it, muted) }
                if (suppressed.isNotEmpty()) _muteBacklogSuppressions.emit(suppressed)
            }
        }

        /** Put back the mute backlog floors an unmute advanced past (snackbar undo). */
        fun undoMuteBacklogSuppression(suppressions: List<MuteBacklogSuppression>) =
            viewModelScope.launch {
                suppressions.forEach { bufferRepository.restoreMuteBacklog(it) }
            }

        fun setArchived(
            bufferId: Long,
            archived: Boolean,
        ) = setArchived(listOf(bufferId), archived)

        fun setArchived(
            bufferIds: Collection<Long>,
            archived: Boolean,
        ) {
            val ids = bufferIds.toList().distinct()
            if (ids.isEmpty()) return
            archiveOverrides.value = archiveOverrides.value + ids.associateWith { archived }
            viewModelScope.launch {
                runCatching {
                    ids.forEach { bufferRepository.setArchived(it, archived) }
                }.onFailure {
                    archiveOverrides.value = archiveOverrides.value - ids.toSet()
                }
            }
        }

        fun joinChannel(
            networkId: Long,
            channel: String,
            key: String?,
        ) = viewModelScope.launch {
            connectionManager.joinChannel(networkId, channel, key)
        }

        fun acceptInvitation(messageId: Long) =
            viewModelScope.launch {
                connectionManager.acceptInvite(messageId)
            }

        fun ignoreInvitation(messageId: Long) =
            viewModelScope.launch {
                connectionManager.dismissInvite(messageId)
            }

        /**
         * Delete a chat/buffer from the list. QUERY/SERVER rows are local-only and are removed at once.
         * CHANNEL rows are marked pending immediately (which hides them from every normal projection);
         * the process-scoped coordinator performs the server close and removes history only after it
         * succeeds. Scope selection keys off networkId, never a bufferId, so no scope reset is needed.
         */
        fun deleteBuffer(row: ChatListRow) = deleteBuffers(listOf(row))

        fun deleteBuffers(rows: Collection<ChatListRow>) {
            val targets = rows.toList().distinctBy(ChatListRow::bufferId)
            if (targets.isEmpty()) return
            viewModelScope.launch {
                targets.forEach { row ->
                    if (row.type == BufferType.CHANNEL) {
                        channelCloseCoordinator.requestClose(row.bufferId)
                    } else {
                        bufferRepository.deleteBuffer(row.bufferId)
                    }
                }
            }
        }

        /** Find-or-create a query buffer, then hand the id to [onOpen] for navigation. */
        fun messageUser(
            networkId: Long,
            nick: String,
            onOpen: (Long) -> Unit,
        ) = viewModelScope.launch {
            val bufferId = connectionManager.ensureQueryBuffer(networkId, nick)
            onOpen(bufferId)
        }

        /** Starts or clears the bounded local nickname query used only while the message tab is active. */
        fun queryNickSuggestions(
            networkId: Long?,
            prefix: String,
        ) {
            val trimmed = prefix.trim()
            val network = networkId?.let { id -> state.value.networks.firstOrNull { it.id == id } }
            nickSuggestionRequest.value =
                if (network == null || trimmed.isEmpty()) {
                    null
                } else {
                    NickSuggestionRequest(
                        network.id,
                        trimmed,
                        (connectionManager.connectionStates.value[network.id] as? IrcClientState.Ready)?.nick ?: network.nick,
                    )
                }
        }

        // -- Round 5: drawer selection + per-network / global connectivity --

        /** Scope the list to [networkId] (root includes children); null clears the scope. */
        fun selectNetwork(networkId: Long?) = setSelection(networkId)

        // -- Manual drawer order (see DrawerReorder.kt for the pure move rules) --
        //
        // Persistence timing: a completed intent is written once, immediately. The move actions are one
        // intent each, so they persist as they happen. A drag lives entirely in the composable while the
        // finger is down and arrives here once, as the finished arrangement, on any termination (drop,
        // cancel, drawer dismissed mid-drag) — a write per crossed row would persist arrangements the
        // user was only passing through. So the only order that can be lost is one whose gesture never
        // finished.

        /** Move a drawer entry one position within its sibling list and persist immediately. */
        fun moveNetwork(
            networkId: Long,
            delta: Int,
        ) {
            val moved = movedRows(networkId, delta) ?: return
            persistNetworkOrder(drawerOrderIds(moved))
        }

        /**
         * Persist the arrangement a finished drag is showing. [orderIds] is layered onto the live rows
         * before writing, so a network that appeared mid-drag keeps its place and a deleted id drops
         * out. An arrangement the drawer already shows writes nothing — a drag that only wobbled in
         * place, or returned everything to where it started, is not an intent to reorder.
         */
        fun commitNetworkOrder(orderIds: List<Long>) {
            val current = applyDrawerOrder(state.value.drawerRows, pendingOrder.value)
            val order = drawerOrderIds(applyDrawerOrder(current, orderIds))
            if (order == drawerOrderIds(current)) return
            persistNetworkOrder(order)
        }

        /** Rows after moving [networkId] by [delta], or null when the move is not possible. */
        private fun movedRows(
            networkId: Long,
            delta: Int,
        ): List<DrawerRow>? {
            // Layer the pending order over the published state: consecutive drag steps must not race a
            // recomposition, and a step computed from a stale arrangement would move the wrong row.
            val rows = applyDrawerOrder(state.value.drawerRows, pendingOrder.value)
            if (!canMoveDrawerRow(rows, networkId, delta)) return null
            return moveDrawerRow(rows, networkId, delta)
        }

        private fun persistNetworkOrder(order: List<Long>) {
            // Keep showing the new arrangement until Room publishes it, so the drawer never flickers
            // back through the old order between the write and its invalidation.
            pendingOrder.value = order
            viewModelScope.launch {
                runCatching { networkRepository.reorderNetworks(order) }
                    // A failed write will never be published back; drop the overlay rather than pin
                    // the drawer to an arrangement the database never accepted (archiveOverrides idiom).
                    .onFailure { pendingOrder.compareAndSet(order, null) }
            }
        }

        fun connect(networkId: Long) = viewModelScope.launch { connectionManager.connect(networkId) }

        fun disconnect(networkId: Long) = viewModelScope.launch { connectionManager.disconnect(networkId) }

        /** Global go-offline: disconnect every network (in-memory intent, resets on restart). */
        fun goOffline() =
            viewModelScope.launch {
                state.value.networks.forEach { connectionManager.disconnect(it.id) }
            }

        /** Global go-online: connect everything (explicit "connect all", may include autoConnect=false). */
        fun goOnline() =
            viewModelScope.launch {
                state.value.networks.forEach { connectionManager.connect(it.id) }
            }

        /** Find-or-create the SERVER buffer for [networkId], then navigate to it. */
        fun openServerBuffer(
            networkId: Long,
            onOpen: (Long) -> Unit,
        ) = viewModelScope.launch {
            onOpen(connectionManager.ensureServerBuffer(networkId))
        }

        /** Mark every currently unread chat in the current drawer scope through one Room snapshot. */
        fun markCurrentScopeRead() {
            val bufferIds = unreadBufferIds(state.value.rows)
            if (bufferIds.isEmpty()) return
            viewModelScope.launch { markChatsRead(bufferIds, readMarkerRepository, connectionManager) }
        }

        /** Mark every active Discord portal conversation read across bridge networks. */
        fun markDickordRead() {
            viewModelScope.launch {
                val bufferIds = dickordUnreadBufferIds(bufferRepository.observeChatList().first())
                if (bufferIds.isNotEmpty()) markChatsRead(bufferIds, readMarkerRepository, connectionManager)
            }
        }

        /**
         * Mark an explicit selection read, muted rows included: mark-all deliberately skips muted
         * chats, but a user hand-selecting one is opting it in on purpose.
         */
        fun markSelectedRead(bufferIds: Collection<Long>) {
            val ids = bufferIds.toList().distinct()
            if (ids.isEmpty()) return
            viewModelScope.launch { markChatsRead(ids, readMarkerRepository, connectionManager) }
        }

        private fun setSelection(networkId: Long?) {
            selection.value = networkId
            savedStateHandle[KEY_SELECTED] = networkId
        }

        private companion object {
            const val KEY_SELECTED = "selected_network"
        }
    }

internal fun toChatListInvitation(event: InvitationEventRow): ChatListInvitation? {
    val payload = InvitePayloadV1.decode(event.eventPayload) ?: return null
    return ChatListInvitation(
        messageId = event.messageId,
        bufferId = event.bufferId,
        networkId = event.networkId,
        networkName = event.networkName,
        inviter = payload.inviter,
        channel = payload.channel,
        text = event.text,
        state = event.inviteState,
        serverTime = event.serverTime,
    )
}

/** Pending archive writes should move rows immediately, then disappear once Room agrees. */
internal fun applyArchiveOverrides(
    rows: List<ChatListRow>,
    overrides: Map<Long, Boolean>,
): List<ChatListRow> {
    if (overrides.isEmpty()) return rows
    return rows.map { row ->
        val archived = overrides[row.bufferId] ?: return@map row
        if (row.archived == archived) row else row.copy(archived = archived)
    }
}

/** Override entries are only optimistic; matched Room emissions become authoritative again. */
internal fun settledArchiveOverrideIds(
    rows: List<ChatListRow>,
    overrides: Map<Long, Boolean>,
): Set<Long> {
    if (overrides.isEmpty()) return emptySet()
    val byId = rows.associateBy(ChatListRow::bufferId)
    return overrides.filter { (id, archived) -> byId[id]?.archived == archived }.keys
}

internal fun dickordUnreadBufferIds(rows: List<ChatListRow>): List<Long> =
    unreadBufferIds(
        rows.filter { row ->
            !row.archived && isDickordPortalConversation(row.type, row.displayName)
        },
    )

/** Portal-owned rows leave only the ordinary projection; disabling the Lab is an identity no-op. */
internal fun ordinaryChatListRows(
    rows: List<ChatListRow>,
    dickordEnabled: Boolean,
): List<ChatListRow> =
    if (dickordEnabled) {
        rows.filterNot { row -> isDickordPortalConversation(row.type, row.displayName) }
    } else {
        rows
    }
