package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.FolderIconKind
import io.github.trevarj.motd.data.db.IgnoredAutoGroupPatternEntity
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.matchedChannelDevicon

/** Visible folder rollup. Expanded headers intentionally render only name/count. */
data class ChatFolderSummary(
    val visibleCount: Int,
    val previewText: String?,
    val previewSender: String?,
    val previewTime: Long?,
    val unreadCount: Int,
    val mentionCount: Int,
    val unreadIncomplete: Boolean,
    val mentionIncomplete: Boolean,
    val advertisedActivity: Boolean,
)

data class PresentedChatFolder(
    val folder: ChatFolderEntity,
    val children: List<ChatListRow>,
    val summary: ChatFolderSummary,
    val temporarilyExpanded: Boolean,
) {
    val expanded: Boolean get() = folder.expanded || temporarilyExpanded
}

data class FolderChatListPresentation(
    val pinned: List<ChatListRow>,
    val folders: List<PresentedChatFolder>,
    val remaining: ChatListSections,
)

fun presentChatFolders(
    rows: List<ChatListRow>,
    folders: List<ChatFolderEntity>,
    friends: Set<String>,
    fools: Set<String>,
    activeBufferId: Long? = null,
): FolderChatListPresentation {
    val pinned = rows.filter(ChatListRow::pinned)
    val unpinned = rows.filterNot(ChatListRow::pinned)
    val folderRows = unpinned.filter { it.folderId != null }.groupBy(ChatListRow::folderId)
    val presented =
        folders.mapNotNull { folder ->
            val children = folderRows[folder.id].orEmpty()
            if (children.isEmpty()) return@mapNotNull null
            PresentedChatFolder(
                folder = folder,
                children = children,
                summary = summarizeFolder(children),
                temporarilyExpanded = !folder.expanded && children.any { it.bufferId == activeBufferId },
            )
        }
    val knownFolderIds = folders.mapTo(mutableSetOf(), ChatFolderEntity::id)
    val remaining = unpinned.filter { it.folderId == null || it.folderId !in knownFolderIds }
    return FolderChatListPresentation(pinned, presented, sectionChatList(remaining, friends, fools))
}

internal fun folderAllRows(
    rows: List<ChatListRow>,
    includeFolderChats: Boolean,
): List<ChatListRow> = if (includeFolderChats) rows else rows.filter { it.folderId == null }

/** Non-empty tabs from already-scoped active rows. Raw assignments, pins, and stored order survive. */
fun presentFolderTabs(
    rows: List<ChatListRow>,
    folders: List<ChatFolderEntity>,
): List<PresentedChatFolder> {
    val byFolder = rows.filter { it.folderId != null }.groupBy(ChatListRow::folderId)
    return folders.mapNotNull { folder ->
        val children = byFolder[folder.id].orEmpty()
        if (children.isEmpty()) null else PresentedChatFolder(folder, children, summarizeFolder(children), temporarilyExpanded = false)
    }
}

fun summarizeFolder(rows: List<ChatListRow>): ChatFolderSummary {
    val latest = rows.maxWithOrNull(compareBy<ChatListRow> { it.lastMessageTime ?: Long.MIN_VALUE }.thenBy(ChatListRow::bufferId))
    val badgeRows = rows.filterNot(ChatListRow::muted)
    val unread = badgeRows.sumOf { it.unreadCount.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val mentions = badgeRows.sumOf { it.mentionCount.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return ChatFolderSummary(
        visibleCount = rows.size,
        previewText = latest?.lastMessageText,
        previewSender = latest?.lastMessageSender?.takeIf(String::isNotBlank),
        previewTime = latest?.lastMessageTime,
        unreadCount = unread,
        mentionCount = mentions,
        unreadIncomplete = badgeRows.any(ChatListRow::unreadCountIncomplete),
        mentionIncomplete = badgeRows.any(ChatListRow::mentionCountIncomplete),
        advertisedActivity = unread == 0 && mentions == 0 && badgeRows.any(ChatListRow::advertisedUnread),
    )
}

/** Drop selections hidden by a collapse while preserving selections elsewhere. */
fun pruneCollapsedFolderSelection(
    selectedIds: Collection<Long>,
    folder: PresentedChatFolder,
): List<Long> = if (folder.expanded) selectedIds.toList() else selectedIds.filterNot { id -> folder.children.any { it.bufferId == id } }

fun folderColorSeed(name: String): Int = name.fold(0x811c9dc5.toInt()) { hash, char -> (hash xor char.code) * 0x01000193 }

fun canApproveAutoGroup(
    destinationFolderId: Long?,
    checkedCount: Int,
): Boolean = checkedCount >= if (destinationFolderId == null) 2 else 1

data class AutoGroupProposal(
    val networkId: Long,
    val normalizedPrefix: String,
    val matchedPrefix: String,
    val suggestedName: String,
    val icon: FolderIconRef,
    val chats: List<ChatListRow>,
)

/** Deterministic, flat, boundary-prefix grouping. Input order remains chat activity order. */
fun autoGroupProposals(
    rows: List<ChatListRow>,
    ignored: Collection<IgnoredAutoGroupPatternEntity> = emptyList(),
    networkId: Long? = null,
): List<AutoGroupProposal> {
    val ignoredKeys = ignored.mapTo(mutableSetOf()) { it.networkId to it.normalizedPrefix }
    val candidates =
        rows
            .asSequence()
            .filter { it.type == BufferType.CHANNEL && !it.archived && it.folderId == null }
            .filter { networkId == null || it.networkId == networkId }
            .groupBy(ChatListRow::networkId)
            .flatMap { (id, networkRows) -> proposalsForNetwork(id, networkRows, ignoredKeys) }
    return candidates.sortedWith(compareBy<AutoGroupProposal> { it.networkId }.thenBy { it.normalizedPrefix })
}

private fun proposalsForNetwork(
    networkId: Long,
    rows: List<ChatListRow>,
    ignored: Set<Pair<Long, String>>,
): List<AutoGroupProposal> {
    if (rows.size < 2) return emptyList()
    val names =
        rows.associateWith { row ->
            val rules = IrcIdentityRules.from(row.caseMapping, row.chanTypes)
            rules.normalize(row.displayName.dropWhile { it in rules.chanTypes })
        }
    val prefixes =
        names.values.flatMap(::boundaryPrefixes).distinct().mapNotNull { prefix ->
            val terminal = prefix.substringAfterLastBoundary()
            if (terminal.length < 4 || networkId to prefix in ignored) return@mapNotNull null
            val members = rows.filter { boundaryPrefixMatch(names.getValue(it), prefix) }
            members.takeIf { it.size >= 2 }?.let { prefix to it }
        }
    val deepest =
        prefixes
            .groupBy { (_, members) -> members.map(ChatListRow::bufferId).toSet() }
            .values
            .map { equivalent -> equivalent.maxBy { it.first.length } }
            .sortedWith(compareByDescending<Pair<String, List<ChatListRow>>> { it.first.count(::isBoundary) }.thenByDescending { it.first.length })
    val used = mutableSetOf<Long>()
    return deepest.mapNotNull { (prefix, members) ->
        if (members.any { it.bufferId in used }) return@mapNotNull null
        used += members.map(ChatListRow::bufferId)
        val mark = matchedChannelDevicon(prefix)
        AutoGroupProposal(
            networkId = networkId,
            normalizedPrefix = prefix,
            matchedPrefix = prefix,
            suggestedName = prefix.substringAfterLastBoundary(),
            icon = mark?.let { FolderIconRef(FolderIconKind.DEVICON, it.markName) } ?: FolderIconRef(),
            chats = members,
        )
    }
}

private fun boundaryPrefixes(name: String): List<String> =
    buildList {
        name.forEachIndexed { index, char ->
            if (isBoundary(char) && index > 0) add(name.substring(0, index).trimEnd(::isBoundary))
        }
        if (name.isNotBlank()) add(name.trimEnd(::isBoundary))
    }.filter(String::isNotBlank)

private fun boundaryPrefixMatch(
    name: String,
    prefix: String,
): Boolean = name == prefix || name.startsWith(prefix) && name.getOrNull(prefix.length)?.let(::isBoundary) == true

private fun String.substringAfterLastBoundary(): String = substring(lastIndexOfAny(charArrayOf('.', '-', '_', '/', ':', '+')) + 1)

private fun isBoundary(char: Char): Boolean = !char.isLetterOrDigit()
