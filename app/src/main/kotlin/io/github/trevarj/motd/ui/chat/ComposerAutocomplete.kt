package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.irc.format.containsIrcFormatting
import io.github.trevarj.motd.irc.format.parseIrcFormatting

/** A composer completion candidate: display text plus whether it is a `/` command hint. */
data class Completion(
    val display: String,
    val isCommand: Boolean,
)

/**
 * Compute completions for the current composer [value]: `/`-command hints when the line starts with
 * a single `/` command token, otherwise nick completions for the token under the cursor. Pure glue
 * over [rankNickCompletions] and [commandHintsFor]; the ranking itself is unit-tested separately.
 *
 * [isChannel] narrows the hints to what the current conversation can actually act on, so a query
 * never offers `/kick` or `/topic`.
 */
fun autocompleteFor(
    value: TextFieldValue,
    members: List<String>,
    recentSpeakers: List<String>,
    normalize: (String) -> String,
    isChannel: Boolean = true,
): List<Completion> {
    val formatted = parseIrcFormatting(value.text)
    val text = formatted.visibleText
    val cursor = formatted.visibleOffset(value.selection.end)

    // Command hints: a leading "/word" with no space yet.
    if (text.startsWith("/") && !text.startsWith("//") && !text.contains(' ')) {
        val prefix = text.lowercase()
        return commandHintsFor(isChannel)
            .filter { it.startsWith(prefix) }
            .map { Completion(it, isCommand = true) }
    }

    val token = nickTokenAt(text, cursor) ?: return emptyList()
    // Reduce noise: require >=2 chars before suggesting, unless the user explicitly typed `@`
    // . The `@` sigil is stripped from token.text, so detect it from the raw source.
    val atPrefixed = token.start < text.length && text[token.start] == '@'
    if (token.text.length < 2 && !atPrefixed) return emptyList()
    return rankNickCompletions(token.text, members, recentSpeakers, normalize)
        .map { Completion(it, isCommand = false) }
}

/**
 * Apply a picked completion to [value]. For command hints (leading `/`) the whole field becomes
 * "<command> "; for nicks the token under the cursor is replaced per [applyCompletion]'s rules.
 */
fun applyPick(
    value: TextFieldValue,
    picked: String,
): TextFieldValue {
    if (picked.startsWith("/")) {
        val next = "$picked "
        return TextFieldValue(next, TextRange(next.length))
    }
    val formatted = parseIrcFormatting(value.text)
    val visibleCursor = formatted.visibleOffset(value.selection.end)
    val token = nickTokenAt(formatted.visibleText, visibleCursor) ?: return value
    // Keep the opening controls before the nickname and closing controls with the suffix.
    val rawStart = formatted.rawOffset(token.start)
    val rawEnd = formatted.rawToVisible.indexOfFirst { it == token.end }
    val insertion = picked + nicknameSuffix(formatted.visibleText, token.start, token.end)
    val next =
        buildAnnotatedString {
            append(value.annotatedString.subSequence(0, rawStart))
            append(insertion)
            // Controls inside the replaced token must still affect the untouched suffix.
            for (index in rawStart until rawEnd) {
                if (formatted.rawToVisible[index] == formatted.rawToVisible[index + 1]) {
                    append(value.annotatedString.subSequence(index, index + 1))
                }
            }
            append(value.annotatedString.subSequence(rawEnd, value.text.length))
        }
    return TextFieldValue(next, TextRange(rawStart + insertion.length))
}

/** Insert a nickname at the native cursor, replacing either direction of selection. */
fun insertNickname(
    value: TextFieldValue,
    nick: String,
): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val formatted = if (containsIrcFormatting(value.text)) parseIrcFormatting(value.text) else null
    val visibleText = formatted?.visibleText ?: value.text
    val visibleStart = formatted?.visibleOffset(start) ?: start
    val visibleEnd = formatted?.visibleOffset(end) ?: end
    val previous = visibleText.getOrNull(visibleStart - 1)
    val needsLeadingSpace = previous != null && (previous.isLetterOrDigit() || previous in "-[]\\`_^{}|@")
    val insertion =
        buildString(nick.length + 3) {
            if (needsLeadingSpace) append(' ')
            append(nick)
            append(nicknameSuffix(visibleText, visibleStart, visibleEnd))
        }
    val next =
        buildAnnotatedString {
            append(value.annotatedString.subSequence(0, start))
            append(insertion)
            if (formatted != null) {
                for (index in start until end) {
                    if (formatted.rawToVisible[index] == formatted.rawToVisible[index + 1]) {
                        append(value.annotatedString.subSequence(index, index + 1))
                    }
                }
            }
            append(value.annotatedString.subSequence(end, value.text.length))
        }
    return TextFieldValue(next, TextRange(start + insertion.length))
}
