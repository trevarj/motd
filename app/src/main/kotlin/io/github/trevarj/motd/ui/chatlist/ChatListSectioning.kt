package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.normalizeNick

/**
 * Chat-list sectioning by friend/fool membership (plans/13 §3.5). Pure and unit-tested.
 *
 * Only [BufferType.QUERY] rows classify (by `normalizeNick(displayName)`); channels are never
 * friends/fools. Precedence: friend > fool > regular. Pinned rows are NOT pulled into a separate
 * section — they classify normally and are marked inline with a pin icon on the row; the query
 * already orders pinned-first, so pinned rows lead their section. Input order is preserved within
 * each section.
 *
 * Note: QUERY `displayName` is the nick today (`ensureQueryBuffer`); if display renaming ever
 * lands, classification should switch to the underlying buffer name (plans/13 Risks §9).
 */
data class ChatListSections(
    val friends: List<ChatListRow>,
    val regular: List<ChatListRow>,
    val fools: List<ChatListRow>,
)

fun sectionChatList(
    rows: List<ChatListRow>,
    friends: Set<String>,
    fools: Set<String>,
): ChatListSections {
    val friendRows = ArrayList<ChatListRow>()
    val regular = ArrayList<ChatListRow>()
    val foolRows = ArrayList<ChatListRow>()

    for (row in rows) {
        when {
            row.type == BufferType.QUERY && normalizeNick(row.displayName) in friends -> friendRows.add(row)
            row.type == BufferType.QUERY && normalizeNick(row.displayName) in fools -> foolRows.add(row)
            else -> regular.add(row)
        }
    }

    return ChatListSections(friends = friendRows, regular = regular, fools = foolRows)
}
