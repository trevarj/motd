package io.github.trevarj.motd.irc.format

data class IrcEditorRawValue(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/** Visible-text editor model. IRC controls exist only at parse/serialization boundaries. */
data class IrcEditorDocument(
    val text: String,
    val states: List<IrcFormatState>,
    val pendingState: IrcFormatState = IrcFormatState(),
) {
    init {
        require(states.size == text.length)
    }

    val runs: List<IrcStyleRun>
        get() {
            if (states.isEmpty()) return emptyList()
            val result = ArrayList<IrcStyleRun>()
            var start = 0
            var state = states.first()
            for (index in 1 until states.size) {
                if (states[index] != state) {
                    result += IrcStyleRun(start, index, state)
                    start = index
                    state = states[index]
                }
            }
            result += IrcStyleRun(start, states.size, state)
            return result
        }

    fun stateAtCaret(offset: Int): IrcFormatState {
        val caret = offset.coerceIn(0, text.length)
        return states.getOrNull(caret - 1) ?: states.getOrNull(caret) ?: IrcFormatState()
    }

    fun moveCaret(offset: Int): IrcEditorDocument = copy(pendingState = stateAtCaret(offset))

    fun replaceText(newText: String): IrcEditorDocument {
        if (newText == text) return this
        var prefix = 0
        while (prefix < text.length && prefix < newText.length && text[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (
            suffix < text.length - prefix &&
            suffix < newText.length - prefix &&
            text[text.lastIndex - suffix] == newText[newText.lastIndex - suffix]
        ) {
            suffix++
        }
        val insertedLength = newText.length - prefix - suffix
        val nextStates = ArrayList<IrcFormatState>(newText.length)
        nextStates += states.subList(0, prefix)
        repeat(insertedLength) { nextStates += pendingState }
        nextStates += states.subList(text.length - suffix, text.length)
        return copy(text = newText, states = nextStates)
    }

    fun toggleStyle(
        selectionStart: Int,
        selectionEnd: Int,
        style: IrcTextStyle,
    ): IrcEditorDocument {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        if (start == end) return copy(pendingState = pendingState.with(style, !pendingState.enabled(style)))
        val enabled = states.subList(start, end).all { it.enabled(style) }
        return replaceStates(start, end) { it.with(style, !enabled) }
    }

    /** Colors must be ready for one IRC control: a background needs a foreground of the same type. */
    fun applyColors(
        selectionStart: Int,
        selectionEnd: Int,
        foreground: IrcColor?,
        background: IrcColor?,
    ): IrcEditorDocument {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)

        fun IrcFormatState.colored() =
            copy(
                foreground = foreground,
                background = background,
            )
        if (start == end) return copy(pendingState = pendingState.colored())
        return replaceStates(start, end, IrcFormatState::colored)
    }

    fun clearFormatting(
        selectionStart: Int,
        selectionEnd: Int,
    ): IrcEditorDocument {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        if (start == end) return copy(pendingState = IrcFormatState())
        return replaceStates(start, end) { IrcFormatState() }
    }

    /** Replace Markdown delimiters in one visible range while preserving existing IRC styling. */
    fun formatMarkdown(
        selectionStart: Int,
        selectionEnd: Int,
    ): IrcEditorDocument {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        if (start == end) return this
        val selectedRaw = serializeVisibleIrc(text.substring(start, end), states.subList(start, end))
        val formatted = fromRaw(markdownToIrcFormatting(selectedRaw)).first
        return copy(
            text = text.substring(0, start) + formatted.text + text.substring(end),
            states = states.subList(0, start) + formatted.states + states.subList(end, text.length),
        )
    }

    fun isStyleSelected(
        selectionStart: Int,
        selectionEnd: Int,
        style: IrcTextStyle,
    ): Boolean {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        return if (start == end) pendingState.enabled(style) else states.subList(start, end).all { it.enabled(style) }
    }

    fun toRawValue(
        selectionStart: Int,
        selectionEnd: Int,
    ): IrcEditorRawValue {
        val raw = serializeVisibleIrc(text, states)
        val parsed = parseIrcFormatting(raw)
        return IrcEditorRawValue(
            text = raw,
            selectionStart = parsed.rawOffset(selectionStart.coerceIn(0, text.length)),
            selectionEnd = parsed.rawOffset(selectionEnd.coerceIn(0, text.length)),
        )
    }

    private fun replaceStates(
        start: Int,
        end: Int,
        transform: (IrcFormatState) -> IrcFormatState,
    ): IrcEditorDocument {
        val next = states.toMutableList()
        for (index in start until end) next[index] = transform(next[index])
        return copy(states = next, pendingState = next.getOrNull(end) ?: next.getOrNull(end - 1) ?: pendingState)
    }

    companion object {
        fun fromRaw(
            raw: String,
            rawSelectionStart: Int = raw.length,
            rawSelectionEnd: Int = rawSelectionStart,
        ): Pair<IrcEditorDocument, IntRange> {
            val parsed = parseIrcFormatting(raw)
            val visibleStart = parsed.visibleOffset(rawSelectionStart)
            val visibleEnd = parsed.visibleOffset(rawSelectionEnd)
            val pending = ircStateAtRawOffset(raw, rawSelectionStart)
            return IrcEditorDocument(parsed.visibleText, parsed.characterStates, pending) to
                (visibleStart..visibleEnd)
        }
    }
}
