package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Thin banner under the top bar summarizing connection health across all networks. Hidden when
 * every network is [IrcClientState.Ready]. Derives a single line from the aggregate worst state.
 */
@Composable
fun ConnectionBanner(
    states: Map<Long, IrcClientState>,
    networkName: (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    val status = bannerStatus(states, networkName)
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            val contentTransform = when {
                initialState == null ->
                    (fadeIn(MotdMotion.fadeIn) +
                        expandVertically(animationSpec = MotdMotion.contentSize)) togetherWith
                        ExitTransition.None
                targetState == null ->
                    EnterTransition.None togetherWith
                        (fadeOut(MotdMotion.fadeOut) +
                            shrinkVertically(animationSpec = MotdMotion.contentSize))
                else -> fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
            }
            // expand/shrink already own the null <-> content size change. Disable
            // AnimatedContent's default SizeTransform so the same height is not animated twice.
            contentTransform.using(null)
        },
        modifier = modifier,
        label = "connection_banner",
    ) { current ->
        // AnimatedContent retains this non-null snapshot while it runs the exit transition.
        if (current == null) return@AnimatedContent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (current.error) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!current.error) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = current.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (current.error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class BannerStatus(val text: String, val error: Boolean)

/** null when nothing to report (empty or all Ready). Prefers errors over in-progress states. */
private fun bannerStatus(
    states: Map<Long, IrcClientState>,
    networkName: (Long) -> String?,
): BannerStatus? {
    if (states.isEmpty()) return null

    // Fatal failure wins the banner.
    states.entries.firstOrNull { (_, s) -> s is IrcClientState.Failed }?.let { (id, s) ->
        val failed = s as IrcClientState.Failed
        val name = networkName(id)
        val prefix = name?.let { "$it: " } ?: ""
        return if (failed.fatal) {
            BannerStatus("$prefix${failed.reason}", error = true)
        } else {
            // Non-fatal: still surface the reason so a retry loop is diagnosable, not just "Offline".
            BannerStatus("${prefix}reconnecting — ${failed.reason}", error = true)
        }
    }

    // Only active in-flight states get a progress banner. A plain Disconnected row is quiescent
    // (for example an old imported network or a manually disconnected account); showing it as
    // "Connecting…" makes a healthy bouncer child look stuck.
    val pending = states.entries.firstOrNull { (_, s) ->
        s is IrcClientState.Connecting || s is IrcClientState.Registering
    } ?: return null

    val name = networkName(pending.key)
    return BannerStatus(
        name?.let { "Connecting to $it…" } ?: "Connecting…",
        error = false,
    )
}

@Preview
@Composable
private fun ConnectionBannerConnectingPreview() {
    MotdTheme {
        ConnectionBanner(
            states = mapOf(1L to IrcClientState.Connecting),
            networkName = { "Libera" },
        )
    }
}

@Preview
@Composable
private fun ConnectionBannerOfflinePreview() {
    MotdTheme {
        ConnectionBanner(
            states = mapOf(1L to IrcClientState.Failed("timeout", fatal = false)),
            networkName = { "Libera" },
        )
    }
}
