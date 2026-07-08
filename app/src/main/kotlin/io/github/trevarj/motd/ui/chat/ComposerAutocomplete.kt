package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** A composer completion candidate: display text plus whether it is a `/` command hint. */
data class Completion(val display: String, val isCommand: Boolean)

/**
 * Compute completions for the current composer [value]: `/`-command hints when the line starts with
 * a single `/` command token, otherwise nick completions for the token under the cursor. Pure glue
 * over [rankNickCompletions] and [COMMAND_HINTS]; the ranking itself is unit-tested separately.
 */
fun autocompleteFor(
    value: TextFieldValue,
    members: List<String>,
    recentSpeakers: List<String>,
    normalize: (String) -> String,
): List<Completion> {
    val text = value.text
    val cursor = value.selection.end

    // Command hints: a leading "/word" with no space yet.
    if (text.startsWith("/") && !text.startsWith("//") && !text.contains(' ')) {
        val prefix = text.lowercase()
        return COMMAND_HINTS.filter { it.startsWith(prefix) }.map { Completion(it, isCommand = true) }
    }

    val token = nickTokenAt(text, cursor) ?: return emptyList()
    // Reduce noise: require >=2 chars before suggesting, unless the user explicitly typed `@`
    // (plans/15 #30). The `@` sigil is stripped from token.text, so detect it from the raw source.
    val atPrefixed = token.start < text.length && text[token.start] == '@'
    if (token.text.length < 2 && !atPrefixed) return emptyList()
    return rankNickCompletions(token.text, members, recentSpeakers, normalize)
        .map { Completion(it, isCommand = false) }
}

/**
 * Apply a picked completion to [value]. For command hints (leading `/`) the whole field becomes
 * "<command> "; for nicks the token under the cursor is replaced per [applyCompletion]'s rules.
 */
fun applyPick(value: TextFieldValue, picked: String): TextFieldValue {
    val text = value.text
    if (picked.startsWith("/")) {
        val next = "$picked "
        return TextFieldValue(next, TextRange(next.length))
    }
    val token = nickTokenAt(text, value.selection.end) ?: return value
    val result = applyCompletion(text, token, picked)
    return TextFieldValue(result.text, TextRange(result.cursor))
}
