package io.github.trevarj.motd.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.ChannelWatchDuration

/** Shared wording for watch durations. */
@StringRes
fun ChannelWatchDuration.labelRes(): Int =
    when (this) {
        ChannelWatchDuration.MIN_15 -> R.string.chat_watch_15
        ChannelWatchDuration.MIN_30 -> R.string.chat_watch_30
        ChannelWatchDuration.MIN_60 -> R.string.chat_watch_60
        ChannelWatchDuration.FOREVER -> R.string.chat_watch_forever
    }

@Composable
fun ChannelWatchDuration.label(): String = stringResource(labelRes())
