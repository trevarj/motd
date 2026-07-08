package io.github.trevarj.motd.ui.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Placeholder — WP8 owns and replaces this screen.
@Composable
fun SearchScreen(
    bufferId: Long? = null,
    onBack: () -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
) {
    Text("Search ${bufferId ?: "global"}")
}
