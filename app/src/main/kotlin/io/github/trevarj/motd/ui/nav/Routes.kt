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
// Settings category sub-screens (reached from the top-level Settings list).
@Serializable data object AppearanceSettingsRoute
@Serializable data object ChatSettingsRoute
@Serializable data object DeliverySettingsRoute
@Serializable data object NetworksSettingsRoute
@Serializable data class NetworkSettingsRoute(val networkId: Long)
@Serializable data class SearchRoute(val bufferId: Long? = null)
@Serializable data class ChannelInfoRoute(val bufferId: Long)
@Serializable data class ImageViewerRoute(val url: String)

// Round 4 (plans/13): manage-nicks screens (one screen, three routes).
@Serializable data object FriendsRoute
@Serializable data object FoolsRoute
@Serializable data object NickColorsRoute

/**
 * Notification-tap deep-link target consumed by [MotdNavGraph]: open [bufferId] and jump to the
 * message (via the existing ChatRoute jump path). [jumpToMsgid] may be null and [jumpToTime] 0 when
 * the notification carried no msgid; the buffer still opens.
 */
data class NotificationTarget(
    val bufferId: Long,
    val jumpToMsgid: String?,
    val jumpToTime: Long,
)

// Round 5 (plans/16): app shell / network management.
@Serializable data object AddNetworkRoute
@Serializable data class BouncerNetworksRoute(val rootNetworkId: Long)
@Serializable data class ChannelListRoute(val networkId: Long)
