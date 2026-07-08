package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.nickColor

/**
 * Circular avatar: initials (first two significant chars) over a [nickColor] background derived
 * from [name]. [isChannel] uses the name as-is (channels keep the leading `#`); queries fall back
 * to their nick.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    isChannel: Boolean = false,
) {
    val isDark = isSystemInDarkTheme()
    val bg = nickColor(name, isDark)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name, isChannel),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/** First two significant characters. Skips channel sigils and non-letter/digit leading chars. */
private fun initials(name: String, isChannel: Boolean): String {
    val stripped = name.trimStart('#', '&', '@', '+', '~', '%', '!').ifEmpty { name }
    val words = stripped.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    val chars = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        stripped.length >= 2 -> stripped.take(2)
        else -> stripped.take(1).ifEmpty { "?" }
    }
    return chars.uppercase()
}

@Preview
@Composable
private fun AvatarPreview() {
    MotdTheme {
        Avatar(name = "alice")
    }
}

@Preview
@Composable
private fun AvatarChannelPreview() {
    MotdTheme {
        Avatar(name = "#libera", isChannel = true)
    }
}
