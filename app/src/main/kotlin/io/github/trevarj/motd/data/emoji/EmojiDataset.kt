package io.github.trevarj.motd.data.emoji

import java.util.Collections

class EmojiEntry internal constructor(
    val sequence: String,
    val name: String,
    val group: String,
    val subgroup: String,
    val baseSequence: String,
    supportedSetIds: Collection<String>,
) {
    val supportedSetIds: Set<String> = Collections.unmodifiableSet(supportedSetIds.toSet())
}

data class EmojiMatch(
    val start: Int,
    val end: Int,
    val entry: EmojiEntry,
)

internal data class EmojiAlias(
    val sequence: String,
    val entry: String,
    val support: Collection<String>,
)

/** Unicode content is never rewritten; match offsets use UTF-16, like Compose spans. */
class EmojiDataset internal constructor(
    entries: List<EmojiEntry>,
    groups: List<String>,
    aliases: List<EmojiAlias>,
) {
    val entries: List<EmojiEntry> = Collections.unmodifiableList(entries.toList())
    val groups: List<String> = Collections.unmodifiableList(groups.toList())
    private val bySequence = entries.associateBy { it.sequence }
    private val variants =
        entries.groupBy { it.baseSequence }.mapValues { (_, family) ->
            Collections.unmodifiableList(family)
        }
    private val root = Node()

    init {
        entries.forEach { add(it.sequence, it, it.supportedSetIds) }
        aliases.forEach { alias ->
            add(alias.sequence, requireNotNull(bySequence[alias.entry]), alias.support.toSet())
        }
    }

    fun entry(sequence: String): EmojiEntry? = bySequence[sequence] ?: terminal(sequence)?.entry

    fun variantsOf(sequence: String): List<EmojiEntry> {
        val entry = entry(sequence) ?: return emptyList()
        return variants[entry.baseSequence].orEmpty()
    }

    /** Longest complete recognition precedes support filtering: unsupported families stay native. */
    fun matches(
        text: CharSequence,
        setId: String,
    ): List<EmojiMatch> {
        if (setId == EmojiSets.SYSTEM_ID || text.isEmpty()) return emptyList()
        var matches: MutableList<EmojiMatch>? = null
        var start = 0
        while (start < text.length) {
            var node = root
            var cursor = start
            var end = start
            var terminal: Terminal? = null
            while (cursor < text.length) {
                val codePoint = Character.codePointAt(text, cursor)
                node = node.children[codePoint] ?: break
                cursor += Character.charCount(codePoint)
                val found = node.terminal
                if (found != null) {
                    terminal = found
                    end = cursor
                }
            }
            if (terminal == null) {
                start = continuationEnd(text, start + Character.charCount(Character.codePointAt(text, start)))
                continue
            }
            val completeEnd = continuationEnd(text, end)
            if (completeEnd == end && setId in terminal.support && !hasTextSelector(text, start, end)) {
                if (matches == null) matches = mutableListOf()
                matches.add(EmojiMatch(start, end, terminal.entry))
            }
            start = completeEnd
        }
        return matches ?: emptyList()
    }

    private fun add(
        sequence: String,
        entry: EmojiEntry,
        support: Set<String>,
    ) {
        var node = root
        var index = 0
        while (index < sequence.length) {
            val cp = sequence.codePointAt(index)
            node = node.children.getOrPut(cp) { Node() }
            index += Character.charCount(cp)
        }
        check(node.terminal == null) { "Duplicate emoji sequence" }
        node.terminal = Terminal(entry, support)
    }

    private fun terminal(sequence: String): Terminal? {
        var node = root
        var index = 0
        while (index < sequence.length) {
            val cp = sequence.codePointAt(index)
            node = node.children[cp] ?: return null
            index += Character.charCount(cp)
        }
        return node.terminal
    }

    private fun continuationEnd(
        text: CharSequence,
        offset: Int,
    ): Int {
        var end = offset
        while (end < text.length) {
            val cp = Character.codePointAt(text, end)
            if (cp == 0x200D) {
                end++
                if (end < text.length) end += Character.charCount(Character.codePointAt(text, end))
            } else if (cp == 0xFE0E || cp == 0xFE0F || cp == 0x20E3 ||
                cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
            ) {
                end += Character.charCount(cp)
            } else {
                break
            }
        }
        return end
    }

    private fun hasTextSelector(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Boolean {
        for (index in start until end) if (text[index] == '\uFE0E') return true
        return false
    }

    private class Node {
        val children = HashMap<Int, Node>()
        var terminal: Terminal? = null
    }

    private class Terminal(
        val entry: EmojiEntry,
        val support: Set<String>,
    )
}
