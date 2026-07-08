package io.github.trevarj.motd.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Placeholder — WP8 owns and replaces these screens.
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenNetwork: (Long) -> Unit = {},
) {
    Text("Settings")
}

@Composable
fun NetworkSettingsScreen(
    networkId: Long,
    onBack: () -> Unit = {},
) {
    Text("NetworkSettings $networkId")
}
