package io.github.trevarj.motd.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object ChatListRoute

@Serializable data class ChatRoute(
    val bufferId: Long,
    val jumpToMsgid: String? = null, // search deep-jump target
    val jumpToTime: Long = 0, // epoch ms of target; 0 = no jump
    val jumpToEventId: Long? = null, // canonical local identity; notifications prefer this
)

@Serializable data object OnboardingRoute

@Serializable data object QrInviteScannerRoute

@Serializable data class JoinInviteRoute(
    val payload: String,
)

@Serializable data class CreateInviteRoute(
    val bufferId: Long,
)

@Serializable data class AccountSetupRoute(
    val networkId: Long,
    val returnChannel: String? = null,
)

@Serializable data object AboutRoute

@Serializable data object SettingsRoute

// Settings category sub-screens (reached from the top-level Settings list).
@Serializable data object AppearanceSettingsRoute

@Serializable data object ChatSettingsRoute

@Serializable data object DirectConnectionsRoute

@Serializable data object DeliverySettingsRoute

@Serializable data object NetworksSettingsRoute

@Serializable data object BackupRestoreRoute

@Serializable data object ManageFoldersRoute

@Serializable data class FolderEditorRoute(
    val folderId: Long = 0,
)

@Serializable data class AutoGroupRoute(
    val networkId: Long? = null,
)

// Experimental features (gestures, Agentwire harness) live under their own category.
@Serializable data object LabsRoute

@Serializable data object SidecarProvidersRoute

// The gesture lab's menu-graph editor, reached from Labs > Gestures > Configure menu.
@Serializable data object GestureMenuEditorRoute

@Serializable data class NetworkSettingsRoute(
    val networkId: Long,
)

@Serializable data class NetworkToolsRoute(
    val networkId: Long,
)

@Serializable data class SearchRoute(
    val bufferId: Long? = null,
)

// Read-only merged stream of conversation lines from every channel and DM.
@Serializable data object GlobalFeedRoute

@Serializable data class ChannelInfoRoute(
    val bufferId: Long,
)

@Serializable data class ImageViewerRoute(
    val url: String,
)

// Inbound ACTION_SEND: pick the chat that receives the shared payload.
@Serializable data object SharePickerRoute

// Round 4: manage-nicks screens (one screen, three routes).
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
    val jumpToEventId: Long? = null,
)

// Round 5: app shell / network management.
@Serializable data object AddNetworkRoute

@Serializable data class BouncerNetworksRoute(
    val rootNetworkId: Long,
    val importAllByDefault: Boolean = false,
)

@Serializable data class ChannelListRoute(
    val networkId: Long,
)
