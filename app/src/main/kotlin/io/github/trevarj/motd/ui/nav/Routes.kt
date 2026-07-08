package io.github.trevarj.motd.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object ChatListRoute
@Serializable data class ChatRoute(
    val bufferId: Long,
    val jumpToMsgid: String? = null,   // search deep-jump target
    val jumpToTime: Long = 0,          // epoch ms of target; 0 = no jump
)
@Serializable data object OnboardingRoute
@Serializable data object AboutRoute
@Serializable data object SettingsRoute
@Serializable data class NetworkSettingsRoute(val networkId: Long)
@Serializable data class SearchRoute(val bufferId: Long? = null)
@Serializable data class ChannelInfoRoute(val bufferId: Long)
@Serializable data class ImageViewerRoute(val url: String)
