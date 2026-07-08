package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.ui.components.ReactionChip

/** Grouping window: consecutive same-sender messages within this span share one header. */
const val GROUP_WINDOW_MS: Long = 3 * 60 * 1000

/**
 * Aggregate raw [ReactionEntity] rows into per-msgid chip lists: one chip per emoji with its count
 * and whether [myNick] is among the reactors. Ordered by first appearance for stability.
 */
fun aggregateReactions(
    reactions: List<ReactionEntity>,
    myNick: String?,
): Map<String, List<ReactionChip>> {
    // msgid -> emoji -> (count, mine)
    val byMsg = LinkedHashMap<String, LinkedHashMap<String, MutableReactionAgg>>()
    for (r in reactions) {
        val emojiMap = byMsg.getOrPut(r.targetMsgid) { LinkedHashMap() }
        val agg = emojiMap.getOrPut(r.emoji) { MutableReactionAgg() }
        agg.count++
        if (myNick != null && r.sender.equals(myNick, ignoreCase = true)) agg.mine = true
    }
    return byMsg.mapValues { (_, emojiMap) ->
        emojiMap.map { (emoji, agg) -> ReactionChip(emoji, agg.count, agg.mine) }
    }
}

private class MutableReactionAgg(var count: Int = 0, var mine: Boolean = false)
