package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.normalizeNick

/**
 * Chat-list sectioning by friend/fool membership (plans/13 §3.5). Pure and unit-tested.
 *
 * Only [BufferType.QUERY] rows classify (by `normalizeNick(displayName)`); channels are never
 * friends/fools. Precedence: pinned > friend > fool > regular — a pinned friend/fool stays under
 * Pinned. Input order is preserved within each section.
 *
 * Note: QUERY `displayName` is the nick today (`ensureQueryBuffer`); if display renaming ever
 * lands, classification should switch to the underlying buffer name (plans/13 Risks §9).
 */
data class ChatListSections(
    val pinned: List<ChatListRow>,
    val friends: List<ChatListRow>,
    val regular: List<ChatListRow>,
    val fools: List<ChatListRow>,
)

fun sectionChatList(
    rows: List<ChatListRow>,
    friends: Set<String>,
    fools: Set<String>,
): ChatListSections {
    val pinned = ArrayList<ChatListRow>()
    val friendRows = ArrayList<ChatListRow>()
    val regular = ArrayList<ChatListRow>()
    val foolRows = ArrayList<ChatListRow>()

    for (row in rows) {
        when {
            row.pinned -> pinned.add(row) // pinned wins over friend/fool
            row.type == BufferType.QUERY && normalizeNick(row.displayName) in friends -> friendRows.add(row)
            row.type == BufferType.QUERY && normalizeNick(row.displayName) in fools -> foolRows.add(row)
            else -> regular.add(row)
        }
    }

    return ChatListSections(pinned = pinned, friends = friendRows, regular = regular, fools = foolRows)
}
