package io.github.trevarj.motd.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Placeholder — WP7 owns and replaces this screen.
@Composable
fun ChatScreen(
    bufferId: Long,
    onBack: () -> Unit = {},
    onOpenChannelInfo: (Long) -> Unit = {},
    onOpenSearch: (Long) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
) {
    Text("Chat $bufferId")
}
