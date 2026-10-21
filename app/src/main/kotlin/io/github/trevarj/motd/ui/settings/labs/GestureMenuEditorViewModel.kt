package io.github.trevarj.motd.ui.settings.labs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.gesture.DEFAULT_PROVIDER_LIMIT
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureIcon
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GestureMenuViolation
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GesturePrefs
import io.github.trevarj.motd.gesture.GestureProviderKind
import io.github.trevarj.motd.gesture.MAX_RING_SLICES
import io.github.trevarj.motd.gesture.addChild
import io.github.trevarj.motd.gesture.bindAction
import io.github.trevarj.motd.gesture.defaultGestureMenu
import io.github.trevarj.motd.gesture.findNode
import io.github.trevarj.motd.gesture.moveAmongSiblings
import io.github.trevarj.motd.gesture.newGestureNodeId
import io.github.trevarj.motd.gesture.parentIdOf
import io.github.trevarj.motd.gesture.removeNode
import io.github.trevarj.motd.gesture.reparent
import io.github.trevarj.motd.gesture.updateNode
import io.github.trevarj.motd.gesture.validateGestureMenu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A chat the action sheet can point an `OpenChat` binding at. */
data class GestureChatChoice(
    val bufferId: Long,
    val label: String,
    val networkName: String,
)

/** A network the action sheet can point a network-scoped binding at. */
data class GestureNetworkChoice(
    val networkId: Long,
    val name: String,
)

/** What kind of node the "add child" affordance creates. */
enum class GestureNodeKind { SUBMENU, LEAF, PROVIDER }

/**
 * One flattened row of the menu tree.
 *
 * The `can…` flags are decided here rather than in the row composable because they are exactly the
 * questions the commit-4 algebra would answer with "refused": an affordance that the algebra would
 * ignore should not be offered in the first place.
 */
data class GestureEditorRow(
    val node: GestureNode,
    val depth: Int,
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
    val canIndent: Boolean = false,
    val canOutdent: Boolean = false,
    val canAddChild: Boolean = false,
    val canDelete: Boolean = false,
    val canRename: Boolean = false,
    val violations: List<GestureMenuViolation> = emptyList(),
)

data class GestureEditorUiState(
    val loaded: Boolean = false,
    val rows: List<GestureEditorRow> = emptyList(),
    val violations: List<GestureMenuViolation> = emptyList(),
    val dirty: Boolean = false,
    val canSave: Boolean = false,
    val isDefault: Boolean = true,
    val chats: List<GestureChatChoice> = emptyList(),
    val networks: List<GestureNetworkChoice> = emptyList(),
    val previewRoot: GestureNode.Submenu? = null,
)

/** Labels a freshly added node wears until the user renames it. */
data class GestureEditorLabels(
    val submenu: String,
    val leaf: String,
    val provider: String,
)

/**
 * Working copy of the gesture menu graph plus every edit the editor screen can make.
 *
 * The copy is deliberately detached: it is read once at construction and written back only on an
 * explicit save, so a half-finished tree never reaches the orb and a stray provider refresh cannot
 * rearrange rows under the user's thumb. Every mutation goes through the pure commit-4 algebra —
 * this class never rebuilds a tree by hand — which is what keeps "refused edit" meaning the same
 * thing in the editor and in the model tests.
 */
@HiltViewModel
class GestureMenuEditorViewModel internal constructor(
    private val prefs: GesturePrefs,
    private val buffers: BufferRepository,
    private val networks: NetworkRepository,
    private val labels: GestureEditorLabels,
) : ViewModel() {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        prefs: GesturePrefs,
        buffers: BufferRepository,
        networks: NetworkRepository,
    ) : this(
        prefs = prefs,
        buffers = buffers,
        networks = networks,
        labels =
            GestureEditorLabels(
                submenu = context.getString(R.string.gesture_editor_new_submenu),
                leaf = context.getString(R.string.gesture_editor_new_leaf),
                provider = context.getString(R.string.gesture_editor_new_provider),
            ),
    )

    private val working = MutableStateFlow<GestureMenuConfig?>(null)
    private val saved = MutableStateFlow<GestureMenuConfig?>(null)
    private val chats = MutableStateFlow<List<GestureChatChoice>>(emptyList())
    private val networkChoices = MutableStateFlow<List<GestureNetworkChoice>>(emptyList())

    init {
        viewModelScope.launch {
            val stored = prefs.menu.first()
            saved.value = stored
            working.value = stored
        }
        // Picker contents are snapshots for the same reason provider rings are: a list that
        // reshuffles while a radio group is open would move the row under the finger.
        viewModelScope.launch {
            chats.value =
                buffers.observeChatList().first().map { row ->
                    GestureChatChoice(row.bufferId, row.displayName, row.networkName)
                }
        }
        viewModelScope.launch {
            networkChoices.value = networks.observeNetworks().first().map { GestureNetworkChoice(it.id, it.name) }
        }
    }

    val state: StateFlow<GestureEditorUiState> =
        combine(working, saved, chats, networkChoices) { draft, stored, chatRows, networkRows ->
            if (draft == null || stored == null) {
                GestureEditorUiState(chats = chatRows, networks = networkRows)
            } else {
                val violations = validateGestureMenu(draft)
                GestureEditorUiState(
                    loaded = true,
                    rows = gestureEditorRows(draft),
                    previewRoot = draft.root,
                    violations = violations,
                    dirty = draft != stored,
                    // An invalid tree is never savable, however dirty it is.
                    canSave = draft != stored && violations.isEmpty(),
                    isDefault = draft == defaultGestureMenu(),
                    chats = chatRows,
                    networks = networkRows,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GestureEditorUiState())

    fun rename(
        nodeId: String,
        label: String,
    ) = edit { config ->
        config.updateNode(nodeId) { node -> node.withLabel(label) }
    }

    fun setIcon(
        nodeId: String,
        icon: GestureIcon,
    ) = edit { config ->
        config.updateNode(nodeId) { node -> node.withIcon(icon) }
    }

    fun moveUp(nodeId: String) = edit { it.moveAmongSiblings(nodeId, -1) }

    fun moveDown(nodeId: String) = edit { it.moveAmongSiblings(nodeId, 1) }

    /** Push a node into the ring opened by the sibling above it. */
    fun indent(nodeId: String) =
        edit { config ->
            val target = precedingSubmenuId(config, nodeId) ?: return@edit config
            config.reparent(nodeId, target)
        }

    /** Lift a node out of its ring into the one holding its parent, just after that parent. */
    fun outdent(nodeId: String) =
        edit { config ->
            val parentId = config.parentIdOf(nodeId) ?: return@edit config
            val grandparentId = config.parentIdOf(parentId) ?: return@edit config
            val grandparent = config.findNode(grandparentId) as? GestureNode.Submenu ?: return@edit config
            val at = grandparent.children.indexOfFirst { it.id == parentId }
            config.reparent(nodeId, grandparentId, index = if (at < 0) null else at + 1)
        }

    fun delete(nodeId: String) = edit { it.removeNode(nodeId) }

    fun addChild(
        parentId: String,
        kind: GestureNodeKind,
    ) = edit { config ->
        config.addChild(parentId, newNode(kind))
    }

    fun bindAction(
        nodeId: String,
        action: GestureAction,
    ) = edit { it.bindAction(nodeId, action) }

    /** Repoint a dynamic node; the limit is clamped to what a ring can actually show. */
    fun setProvider(
        nodeId: String,
        kind: GestureProviderKind,
        limit: Int,
    ) = edit { config ->
        config.updateNode(nodeId) { node ->
            (node as? GestureNode.Provider)?.copy(kind = kind, limit = limit.coerceIn(1, MAX_RING_SLICES)) ?: node
        }
    }

    /** Throw the working copy away for the stock tree. Still needs an explicit save. */
    fun resetToDefault() = edit { defaultGestureMenu() }

    fun save() {
        val draft = working.value ?: return
        if (validateGestureMenu(draft).isNotEmpty()) return
        viewModelScope.launch {
            prefs.setMenu(draft)
            saved.value = draft
        }
    }

    private fun newNode(kind: GestureNodeKind): GestureNode =
        when (kind) {
            GestureNodeKind.SUBMENU -> {
                GestureNode.Submenu(
                    id = newGestureNodeId(),
                    label = labels.submenu,
                    icon = GestureIcon.FOLDER,
                )
            }

            // A new leaf has to point somewhere; the chat list needs no parameters and cannot misfire.
            GestureNodeKind.LEAF -> {
                GestureNode.Leaf(
                    id = newGestureNodeId(),
                    label = labels.leaf,
                    icon = GestureIcon.BOLT,
                    action = GestureAction.OpenChatList,
                )
            }

            GestureNodeKind.PROVIDER -> {
                GestureNode.Provider(
                    id = newGestureNodeId(),
                    label = labels.provider,
                    icon = GestureIcon.CHAT,
                    kind = GestureProviderKind.PINNED_CHATS,
                    limit = DEFAULT_PROVIDER_LIMIT,
                )
            }
        }

    private fun edit(transform: (GestureMenuConfig) -> GestureMenuConfig) {
        working.value = working.value?.let(transform)
    }
}

/** Id of the ring-opening sibling directly above [nodeId], or null when indenting is impossible. */
internal fun precedingSubmenuId(
    config: GestureMenuConfig,
    nodeId: String,
): String? {
    val parent = config.parentIdOf(nodeId)?.let { config.findNode(it) } as? GestureNode.Submenu ?: return null
    val at = parent.children.indexOfFirst { it.id == nodeId }
    return (parent.children.getOrNull(at - 1) as? GestureNode.Submenu)?.id
}

/**
 * The whole tree as an indented list, root first.
 *
 * Violations are attached per node so the screen can show them beside the row that caused them
 * instead of as one anonymous banner.
 */
internal fun gestureEditorRows(config: GestureMenuConfig): List<GestureEditorRow> {
    val byNode = validateGestureMenu(config).groupBy { it.nodeId }
    val rows = mutableListOf<GestureEditorRow>()

    fun walk(
        node: GestureNode,
        depth: Int,
        siblings: List<GestureNode>,
        index: Int,
        parentIsRoot: Boolean,
    ) {
        val isRoot = depth == 0
        rows +=
            GestureEditorRow(
                node = node,
                depth = depth,
                canMoveUp = !isRoot && index > 0,
                canMoveDown = !isRoot && index < siblings.lastIndex,
                canIndent = !isRoot && siblings.getOrNull(index - 1) is GestureNode.Submenu,
                // The root's own children have nowhere further out to go.
                canOutdent = !isRoot && !parentIsRoot,
                canAddChild = node is GestureNode.Submenu,
                canDelete = !isRoot,
                // An unknown node is kept verbatim, so its text belongs to whichever build wrote it.
                canRename = node !is GestureNode.Unknown,
                violations = byNode[node.id].orEmpty(),
            )
        val children = (node as? GestureNode.Submenu)?.children ?: return
        children.forEachIndexed { childIndex, child ->
            walk(child, depth + 1, children, childIndex, parentIsRoot = isRoot)
        }
    }
    walk(config.root, depth = 0, siblings = emptyList(), index = 0, parentIsRoot = false)
    return rows
}

private fun GestureNode.withLabel(label: String): GestureNode =
    when (this) {
        is GestureNode.Submenu -> copy(label = label)
        is GestureNode.Leaf -> copy(label = label)
        is GestureNode.Provider -> copy(label = label)
        is GestureNode.Unknown -> this
    }

private fun GestureNode.withIcon(icon: GestureIcon): GestureNode =
    when (this) {
        is GestureNode.Submenu -> copy(icon = icon)
        is GestureNode.Leaf -> copy(icon = icon)
        is GestureNode.Provider -> copy(icon = icon)
        is GestureNode.Unknown -> this
    }

// -- action binding ------------------------------------------------------------------------------

/** The action families the binding sheet offers, in the order it lists them. */
enum class GestureActionFamily {
    OPEN_CHAT,
    OPEN_CHAT_LIST,
    NEXT_UNREAD,
    MARK_ALL_READ,
    OPEN_SEARCH,
    CHANNEL_INFO_CURRENT,
    ATTACH_CURRENT,
    INSERT_MENTION,
    INSERT_SNIPPET,
    START_QUERY,
    JOIN_CHANNEL,
    TOGGLE_AWAY,
    TOGGLE_THEME,
    RECONNECT_NETWORK,
    DISCONNECT_NETWORK,
}

/** Which parameter pickers a family needs; drives what the sheet shows below the family list. */
enum class GestureActionParam { CHAT, NETWORK, NICK, TEXT, CHANNEL, KEY, AWAY_MESSAGE }

val GestureActionFamily.params: List<GestureActionParam>
    get() =
        when (this) {
            GestureActionFamily.OPEN_CHAT -> {
                listOf(GestureActionParam.CHAT)
            }

            GestureActionFamily.INSERT_MENTION -> {
                listOf(GestureActionParam.NICK)
            }

            GestureActionFamily.INSERT_SNIPPET -> {
                listOf(GestureActionParam.TEXT)
            }

            GestureActionFamily.START_QUERY -> {
                listOf(GestureActionParam.NETWORK, GestureActionParam.NICK)
            }

            GestureActionFamily.JOIN_CHANNEL -> {
                listOf(GestureActionParam.NETWORK, GestureActionParam.CHANNEL, GestureActionParam.KEY)
            }

            GestureActionFamily.TOGGLE_AWAY -> {
                listOf(GestureActionParam.AWAY_MESSAGE)
            }

            GestureActionFamily.RECONNECT_NETWORK, GestureActionFamily.DISCONNECT_NETWORK -> {
                listOf(GestureActionParam.NETWORK)
            }

            else -> {
                emptyList()
            }
        }

/** Everything the binding sheet collects, before it is turned back into a [GestureAction]. */
data class GestureActionDraft(
    val family: GestureActionFamily = GestureActionFamily.OPEN_CHAT_LIST,
    val bufferId: Long? = null,
    val networkId: Long? = null,
    val text: String = "",
    val secondary: String = "",
)

/**
 * Seed a draft from whatever the leaf currently runs.
 *
 * An action this build cannot interpret has no family to preselect: the sheet starts from its own
 * default and only replaces the unknown action if the user actually saves.
 */
fun gestureActionDraft(action: GestureAction): GestureActionDraft =
    when (action) {
        is GestureAction.OpenChat -> {
            GestureActionDraft(GestureActionFamily.OPEN_CHAT, bufferId = action.bufferId)
        }

        is GestureAction.OpenChatList -> {
            GestureActionDraft(GestureActionFamily.OPEN_CHAT_LIST)
        }

        is GestureAction.NextUnread -> {
            GestureActionDraft(GestureActionFamily.NEXT_UNREAD)
        }

        is GestureAction.MarkAllRead -> {
            GestureActionDraft(GestureActionFamily.MARK_ALL_READ)
        }

        is GestureAction.OpenSearch -> {
            GestureActionDraft(GestureActionFamily.OPEN_SEARCH)
        }

        is GestureAction.ChannelInfoCurrent -> {
            GestureActionDraft(GestureActionFamily.CHANNEL_INFO_CURRENT)
        }

        is GestureAction.AttachCurrent -> {
            GestureActionDraft(GestureActionFamily.ATTACH_CURRENT)
        }

        is GestureAction.InsertMention -> {
            GestureActionDraft(GestureActionFamily.INSERT_MENTION, text = action.nick)
        }

        is GestureAction.InsertSnippet -> {
            GestureActionDraft(GestureActionFamily.INSERT_SNIPPET, text = action.text)
        }

        is GestureAction.StartQuery -> {
            GestureActionDraft(GestureActionFamily.START_QUERY, networkId = action.networkId, text = action.nick)
        }

        is GestureAction.JoinChannel -> {
            GestureActionDraft(
                family = GestureActionFamily.JOIN_CHANNEL,
                networkId = action.networkId,
                text = action.channel,
                secondary = action.key.orEmpty(),
            )
        }

        is GestureAction.ToggleAway -> {
            GestureActionDraft(GestureActionFamily.TOGGLE_AWAY, text = action.message.orEmpty())
        }

        is GestureAction.ToggleTheme -> {
            GestureActionDraft(GestureActionFamily.TOGGLE_THEME)
        }

        is GestureAction.ReconnectNetwork -> {
            GestureActionDraft(GestureActionFamily.RECONNECT_NETWORK, networkId = action.networkId)
        }

        is GestureAction.DisconnectNetwork -> {
            GestureActionDraft(GestureActionFamily.DISCONNECT_NETWORK, networkId = action.networkId)
        }

        is GestureAction.Unknown -> {
            GestureActionDraft()
        }
    }

/**
 * The action a draft describes, or null while a required parameter is still missing.
 *
 * Null is what disables the sheet's save button: a half-filled binding must not land as a leaf that
 * silently does nothing when a finger lifts on it.
 */
fun buildGestureAction(draft: GestureActionDraft): GestureAction? {
    val text = draft.text.trim()
    val secondary = draft.secondary.trim()
    return when (draft.family) {
        GestureActionFamily.OPEN_CHAT -> {
            draft.bufferId?.let(GestureAction::OpenChat)
        }

        GestureActionFamily.OPEN_CHAT_LIST -> {
            GestureAction.OpenChatList
        }

        GestureActionFamily.NEXT_UNREAD -> {
            GestureAction.NextUnread
        }

        GestureActionFamily.MARK_ALL_READ -> {
            GestureAction.MarkAllRead
        }

        GestureActionFamily.OPEN_SEARCH -> {
            GestureAction.OpenSearch
        }

        GestureActionFamily.CHANNEL_INFO_CURRENT -> {
            GestureAction.ChannelInfoCurrent
        }

        GestureActionFamily.ATTACH_CURRENT -> {
            GestureAction.AttachCurrent
        }

        GestureActionFamily.INSERT_MENTION -> {
            text.ifEmpty { null }?.let(GestureAction::InsertMention)
        }

        GestureActionFamily.INSERT_SNIPPET -> {
            text.ifEmpty { null }?.let(GestureAction::InsertSnippet)
        }

        GestureActionFamily.START_QUERY -> {
            draft.networkId?.let { id -> text.ifEmpty { null }?.let { GestureAction.StartQuery(id, it) } }
        }

        GestureActionFamily.JOIN_CHANNEL -> {
            draft.networkId?.let { id ->
                text.ifEmpty { null }?.let { GestureAction.JoinChannel(id, it, secondary.ifEmpty { null }) }
            }
        }

        // A blank away message is not a missing parameter: it means "use the default".
        GestureActionFamily.TOGGLE_AWAY -> {
            GestureAction.ToggleAway(text.ifEmpty { null })
        }

        GestureActionFamily.TOGGLE_THEME -> {
            GestureAction.ToggleTheme
        }

        GestureActionFamily.RECONNECT_NETWORK -> {
            draft.networkId?.let(GestureAction::ReconnectNetwork)
        }

        GestureActionFamily.DISCONNECT_NETWORK -> {
            draft.networkId?.let(GestureAction::DisconnectNetwork)
        }
    }
}
