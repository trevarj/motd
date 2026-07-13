package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow

/** App-owned reply delivery preference kept outside the frozen settings contract. */
data class ReplyConfig(val visibleChannelPrefix: Boolean = false)

interface ReplyPrefs {
    val config: Flow<ReplyConfig>
    suspend fun setVisibleChannelPrefix(enabled: Boolean)
}
