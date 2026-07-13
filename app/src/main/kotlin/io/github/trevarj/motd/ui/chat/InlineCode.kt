package io.github.trevarj.motd.ui.chat

sealed interface InlineTextSegment {
    val text: String

    data class Plain(override val text: String) : InlineTextSegment
    data class Code(override val text: String) : InlineTextSegment
}

/**
 * Parse single-backtick and Emacs-style backtick/apostrophe inline code without Markdown rules.
 * Delimiters are omitted from rendered segments; the raw MessageEntity text remains untouched for
 * copy. Runs of two or more backticks and unmatched openers stay literal.
 */
fun parseInlineCode(text: String): List<InlineTextSegment> {
    if ('`' !in text) return listOf(InlineTextSegment.Plain(text))
    val result = mutableListOf<InlineTextSegment>()
    var plainStart = 0
    var cursor = 0
    while (cursor < text.length) {
        if (!text.isSingleBacktick(cursor)) {
            cursor++
            continue
        }

        val backtickClose = (cursor + 1 until text.length).firstOrNull(text::isSingleBacktick)
        val apostropheClose = if (backtickClose == null) text.indexOf('\'', startIndex = cursor + 1) else -1
        val close = backtickClose ?: apostropheClose.takeIf { it >= 0 }
        if (close == null || close == cursor + 1) {
            cursor++
            continue
        }

        if (plainStart < cursor) result.addPlain(text.substring(plainStart, cursor))
        result += InlineTextSegment.Code(text.substring(cursor + 1, close))
        cursor = close + 1
        plainStart = cursor
    }
    if (plainStart < text.length) result.addPlain(text.substring(plainStart))
    return result.ifEmpty { listOf(InlineTextSegment.Plain(text)) }
}

private fun String.isSingleBacktick(index: Int): Boolean =
    this[index] == '`' && getOrNull(index - 1) != '`' && getOrNull(index + 1) != '`'

private fun MutableList<InlineTextSegment>.addPlain(text: String) {
    if (text.isEmpty()) return
    val previous = lastOrNull() as? InlineTextSegment.Plain
    if (previous == null) {
        this += InlineTextSegment.Plain(text)
    } else {
        this[lastIndex] = InlineTextSegment.Plain(previous.text + text)
    }
}
