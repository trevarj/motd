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

// Round 4 (plans/13): manage-nicks screens (one screen, three routes).
@Serializable data object FriendsRoute
@Serializable data object FoolsRoute
@Serializable data object NickColorsRoute

// Round 5 (plans/16): app shell / network management.
@Serializable data object AddNetworkRoute
@Serializable data class BouncerNetworksRoute(val rootNetworkId: Long)
@Serializable data class ChannelListRoute(val networkId: Long)
