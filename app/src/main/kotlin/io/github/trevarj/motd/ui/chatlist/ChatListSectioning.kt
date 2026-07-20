package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

/**
 * Chat-list priority sectioning. Pure and unit-tested.
 *
 * Pinned rows always lead the list, regardless of friend/fool membership. The remaining rows are
 * ordered as friends, regular chats, then fools. Only [BufferType.QUERY] rows classify by nick;
 * channels are never friends/fools. Input is already activity-ordered by the query, so each tier
 * preserves its input order.
 *
 * Note: QUERY `displayName` is the nick today (`ensureQueryBuffer`); if display renaming ever
 * lands, classification should switch to the underlying buffer name (plans/13 Risks §9).
 */
data class ChatListSections(
    val pinned: List<ChatListRow>,
    val friends: List<ChatListRow>,
    val regular: List<ChatListRow>,
    val fools: List<ChatListRow>,
) {
    /** A label is useful only when regular rows follow an earlier visible priority tier. */
    val showRecentHeader: Boolean
        get() = regular.isNotEmpty() && (pinned.isNotEmpty() || friends.isNotEmpty())
}

/** Whether [row] keeps the friend presentation, including when it is globally pinned. */
internal fun isFriendQuery(row: ChatListRow, friends: Set<String>): Boolean =
    row.type == BufferType.QUERY && row.identityRules.matchesConfiguredNick(row.displayName, friends)

fun sectionChatList(
    rows: List<ChatListRow>,
    friends: Set<String>,
    fools: Set<String>,
): ChatListSections {
    val pinnedRows = ArrayList<ChatListRow>()
    val friendRows = ArrayList<ChatListRow>()
    val regular = ArrayList<ChatListRow>()
    val foolRows = ArrayList<ChatListRow>()

    for (row in rows) {
        when {
            row.pinned -> pinnedRows.add(row)
            isFriendQuery(row, friends) -> friendRows.add(row)
            row.type == BufferType.QUERY &&
                row.identityRules.matchesConfiguredNick(row.displayName, fools) -> foolRows.add(row)
            else -> regular.add(row)
        }
    }

    return ChatListSections(
        pinned = pinnedRows,
        friends = friendRows,
        regular = regular,
        fools = foolRows,
    )
}

/**
 * Count unread activity in rendered chat rows strictly above [firstVisibleItemIndex]. Section
 * headers occupy lazy-list indices but never contribute activity; collapsed fool rows do neither.
 */
internal fun unreadActivityBeforeDisplayIndex(
    sections: ChatListSections,
    foolsExpanded: Boolean,
    firstVisibleItemIndex: Int,
): Int {
    if (firstVisibleItemIndex <= 0) return 0
    var displayIndex = 0
    var unread = 0L

    fun consumeHeader() {
        displayIndex++
    }

    fun consumeRows(rows: List<ChatListRow>) {
        rows.forEach { row ->
            if (displayIndex < firstVisibleItemIndex) {
                unread += maxOf(row.unreadCount, row.mentionCount).coerceAtLeast(0)
            }
            displayIndex++
        }
    }

    consumeRows(sections.pinned)
    if (sections.friends.isNotEmpty()) {
        consumeHeader()
        consumeRows(sections.friends)
    }
    if (sections.showRecentHeader) consumeHeader()
    consumeRows(sections.regular)
    if (sections.fools.isNotEmpty()) {
        consumeHeader()
        if (foolsExpanded) consumeRows(sections.fools)
    }

    return unread.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private val ChatListRow.identityRules: IrcIdentityRules
    get() = IrcIdentityRules.from(caseMapping, chanTypes)
