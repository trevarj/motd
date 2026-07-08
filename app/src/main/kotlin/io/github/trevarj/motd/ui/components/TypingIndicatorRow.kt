package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * "alice is typing…" line above the composer. Caps at two named nicks then "…and others"
 * (plans/07). Enters/exits with a vertical expand+fade so the list height change doesn't pop
 * (plans/15 #32); the last non-empty text is retained while collapsing so it fades out gracefully.
 */
@Composable
fun TypingIndicatorRow(nicks: List<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Keep the text stable during the exit animation (nicks empties before the fade completes).
    val lastText = remember { arrayOfNulls<String>(1) }
    if (nicks.isNotEmpty()) lastText[0] = typingText(context, nicks)
    AnimatedVisibility(
        visible = nicks.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TypingDots()
            Text(
                text = lastText[0].orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Localized typing line: one/two names verbatim, more collapse to "…and others". Uses string
 * resources (plans/15 #25); the [Context] overload lets non-Compose callers (the TopBar subtitle)
 * reuse the same wording.
 */
fun typingText(context: android.content.Context, nicks: List<String>): String = when (nicks.size) {
    0 -> ""
    1 -> context.getString(R.string.chat_typing_one, nicks[0])
    2 -> context.getString(R.string.chat_typing_two, nicks[0], nicks[1])
    else -> context.getString(R.string.chat_typing_many, nicks[0], nicks[1])
}

/**
 * Non-localized fallback kept for compatibility with the TopBar subtitle caller (ChatScreen).
 * Prefer the [Context] overload for user-visible text. TODO(agent C): switch the subtitle to the
 * Context overload so the TopBar wording also localizes.
 */
fun typingText(nicks: List<String>): String = when (nicks.size) {
    0 -> ""
    1 -> "${nicks[0]} is typing…"
    2 -> "${nicks[0]} and ${nicks[1]} are typing…"
    else -> "${nicks[0]}, ${nicks[1]} and others are typing…"
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(5.dp)
                    .alpha(a)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

@Preview
@Composable
private fun TypingIndicatorRowPreview() {
    MotdTheme {
        TypingIndicatorRow(nicks = listOf("alice", "bob", "carol"))
    }
}
