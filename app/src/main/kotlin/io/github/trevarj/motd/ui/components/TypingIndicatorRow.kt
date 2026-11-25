package io.github.trevarj.motd.ui.components

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import io.github.trevarj.motd.R

fun typingText(
    context: Context,
    nicks: List<String>,
): String =
    when (nicks.size) {
        0 -> ""
        1 -> context.getString(R.string.chat_typing_one, nicks[0])
        2 -> context.getString(R.string.chat_typing_two, nicks[0], nicks[1])
        else -> context.getString(R.string.chat_typing_many, nicks[0], nicks[1])
    }

@Composable
internal fun TypingIndicatorRow(
    summary: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val text =
        remember(summary) {
            buildAnnotatedString {
                append(summary.removeSuffix("…"))
                appendInlineContent("dots", alternateText = "…")
            }
        }
    val inlineContent =
        remember(color) {
            mapOf("dots" to InlineTextContent(Placeholder(1.1.em, 0.42.em, PlaceholderVerticalAlign.AboveBaseline)) { TypingDots(color) })
        }
    EmojiText(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        inlineContent = inlineContent,
    )
}

@Composable
private fun TypingDots(color: Color) {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val phase =
        if ((motionScale?.scaleFactor ?: 1f) > 0f) {
            rememberInfiniteTransition(label = "typing_dots").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 960, easing = LinearEasing)),
                label = "typing_phase",
            )
        } else {
            null
        }
    Canvas(Modifier.fillMaxSize().testTag("chat_typing_dots")) {
        val progress = phase?.value
        val spacing = size.width / 3f
        for (index in 0..2) {
            val pulse =
                if (progress == null) {
                    1f
                } else {
                    val t = (((progress - index / 6f + 1f) % 1f) * 2f).coerceAtMost(1f)
                    4f * t * (1f - t)
                }
            drawCircle(
                color = color,
                radius = size.height * (0.20f + 0.12f * pulse),
                center = Offset((index + 0.5f) * spacing, size.height / 2f),
                alpha = 0.55f + 0.45f * pulse,
            )
        }
    }
}
