package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.R

/**
 * Localized typing line: one/two names verbatim, more collapse to "…and others". Uses string
 * resources (plans/15 #25). Consumed by the chat TopBar subtitle (ChatScreen); the redundant
 * above-composer indicator was removed since the header already surfaces the same info.
 */
fun typingText(context: android.content.Context, nicks: List<String>): String = when (nicks.size) {
    0 -> ""
    1 -> context.getString(R.string.chat_typing_one, nicks[0])
    2 -> context.getString(R.string.chat_typing_two, nicks[0], nicks[1])
    else -> context.getString(R.string.chat_typing_many, nicks[0], nicks[1])
}
