package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.ui.components.ReactionChip

/** Grouping window: consecutive same-sender messages within this span share one header. */
const val GROUP_WINDOW_MS: Long = 3 * 60 * 1000

/**
 * Aggregate raw [ReactionEntity] rows into per-msgid chip lists: one chip per emoji with its count
 * and whether [myNick] is among the reactors. Ordered by first appearance for stability.
 *
 * Own-nick matching uses IRC casefolding ([normalizer]), not plain ASCII case-insensitivity, so
 * the `[]{}|~` equivalence (`nick[]` == `nick{}` under rfc1459) is honored. Callers should pass the
 * live client's isupport normalizer; the default applies rfc1459 folding, which is the safe
 * superset used elsewhere when no live client is reachable.
 */
fun aggregateReactions(
    reactions: List<ReactionEntity>,
    myNick: String?,
    normalizer: (String) -> String = ::foldNickRfc1459,
): Map<String, List<ReactionChip>> {
    val myNormalized = myNick?.let(normalizer)
    // msgid -> emoji -> (count, mine)
    val byMsg = LinkedHashMap<String, LinkedHashMap<String, MutableReactionAgg>>()
    for (r in reactions) {
        val emojiMap = byMsg.getOrPut(r.targetMsgid) { LinkedHashMap() }
        val agg = emojiMap.getOrPut(r.emoji) { MutableReactionAgg() }
        agg.count++
        if (myNormalized != null && normalizer(r.sender) == myNormalized) agg.mine = true
    }
    return byMsg.mapValues { (_, emojiMap) ->
        emojiMap.map { (emoji, agg) -> ReactionChip(emoji, agg.count, agg.mine) }
    }
}

/**
 * Pure rfc1459 nick casefolding fallback (uppercase → lowercase plus the `[]\~` → `{}|^` mapping).
 * Mirrors `Isupport.normalize` for the rfc1459 case; used when no live client normalizer is
 * available so own-nick reaction matching still folds the IRC-equivalence characters.
 */
fun foldNickRfc1459(name: String): String {
    val sb = StringBuilder(name.length)
    for (c in name) {
        sb.append(
            when {
                c in 'A'..'Z' -> c + 32
                c == '[' -> '{'
                c == ']' -> '}'
                c == '\\' -> '|'
                c == '~' -> '^'
                else -> c
            },
        )
    }
    return sb.toString()
}

private class MutableReactionAgg(var count: Int = 0, var mine: Boolean = false)
