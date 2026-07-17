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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.delay

/** Avoid flashing a transient reconnect/connecting banner during short network handoffs. */
internal const val CONNECTION_BANNER_GRACE_MS = 3_000L

/**
 * Thin banner under the top bar summarizing connection health across all networks. Hidden when
 * every network is [IrcClientState.Ready] or the user dismisses the current status. Derives a
 * single line from the aggregate worst state.
 */
@Composable
fun ConnectionBanner(
    states: Map<Long, IrcClientState>,
    networkName: (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    val status = bannerStatus(states, networkName)
    var transientGraceElapsed by remember { mutableStateOf(false) }
    var dismissedStatusKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(status?.transient) {
        transientGraceElapsed = false
        if (status?.transient == true) {
            // Connecting -> registering -> retrying is one unhealthy episode. Keying the timer by
            // transientness keeps those internal state changes from restarting the same grace.
            delay(CONNECTION_BANNER_GRACE_MS)
            transientGraceElapsed = true
        }
    }
    LaunchedEffect(status) {
        // Dismissal lasts for this exact state only. Once the connection recovers, a later
        // independent failure/reconnect remains visible instead of inheriting an old dismissal.
        if (status == null) dismissedStatusKey = null
    }
    val visibleStatus = visibleBannerStatus(status, dismissedStatusKey, transientGraceElapsed)
    AnimatedContent(
        targetState = visibleStatus,
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
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (current.error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { dismissedStatusKey = current.dismissalKey },
                modifier = Modifier.size(32.dp).testTag("connection_banner_dismiss"),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal data class BannerStatus(
    val text: String,
    val error: Boolean,
    val transient: Boolean,
) {
    val dismissalKey: String
        get() = "$error:$transient:$text"
}

internal fun visibleBannerStatus(
    status: BannerStatus?,
    dismissedStatusKey: String?,
    transientGraceElapsed: Boolean,
): BannerStatus? = when {
    status == null || status.dismissalKey == dismissedStatusKey -> null
    !status.transient || transientGraceElapsed -> status
    else -> null
}

/** null when nothing to report (empty or all Ready). Prefers errors over in-progress states. */
internal fun bannerStatus(
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
            BannerStatus("$prefix${failed.reason}", error = true, transient = false)
        } else {
            // Non-fatal: still surface the reason so a retry loop is diagnosable, not just "Offline".
            BannerStatus("${prefix}reconnecting — ${failed.reason}", error = true, transient = true)
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
        transient = true,
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
