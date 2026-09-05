package io.github.trevarj.motd.ui.nav

import androidx.annotation.Keep
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

@Serializable data class CreateContactInviteRoute(
    val preferredNetworkId: Long? = null,
)

@Serializable data class AccountSetupRoute(
    val networkId: Long,
    val returnChannel: String? = null,
)

@Serializable data class AboutRoute(
    val target: SettingsTarget? = null,
)

@Serializable data class SettingsRoute(
    val target: SettingsTarget? = null,
)

/** Typed anchors used by global Settings search. */
@Keep
@Serializable
enum class SettingsTarget {
    NETWORKS,
    APPEARANCE,
    CHAT,
    DELIVERY,
    UPLOADS,
    BACKUP,
    LABS,
    AI,
    AI_MODELS,
    AI_TRANSCRIPTION,
    ABOUT,
    THEME,
    FOLLOW_SYSTEM,
    TRUE_BLACK,
    DYNAMIC_COLOR,
    NICK_COLORS,
    NICK_PALETTE,
    NICK_OVERRIDES,
    APP_FONT,
    UI_FONT_SIZE,
    CONVERSATION_FONT_SIZE,
    FOLDER_LAYOUT,
    SHOW_FOLDER_CHATS_IN_ALL,
    MESSAGE_STYLE,
    AVATAR_STYLE,
    TIMESTAMPS,
    TIME_FORMAT,
    MESSAGE_SPACING,
    BUBBLE_CORNERS,
    WALLPAPER,
    LAUNCHER_ICON,
    PRESENCE,
    DELETED_MESSAGES,
    IMAGES,
    LINK_PREVIEWS,
    MEDIA_UNMETERED,
    MEDIA_METERED,
    PROXIED_MEDIA,
    SHARED_AVATARS,
    AUTO_AWAY,
    AUTO_AWAY_DELAY,
    AWAY_MESSAGE,
    CHAT_SOUNDS,
    COMPOSER_EMOJI,
    COMPOSER_FORMATTING,
    REPLY_PREFIX,
    DIRECT_CONNECTIONS,
    VOICE_QUALITY,
    VOICE_NOISE_REDUCTION,
    VOICE_ENCRYPTION,
    AUDIO_CACHE,
    FRIENDS,
    FOOLS,
    FOOLS_MODE,
    PERSISTENT_DELIVERY,
    UNIFIED_PUSH,
    START_ON_BOOT,
    BATTERY,
    UPLOAD_PROVIDER,
    UPLOAD_CONNECTION,
    UPLOAD_PRIVACY,
    UPLOAD_LIMIT,
    EXPORT_BACKUP,
    IMPORT_BACKUP,
    GESTURES,
    AGENTWIRE,
    GLOBAL_FEED,
    DIAGNOSTICS,
    LICENSE,
    PROJECT,
}

@Keep
@Serializable
enum class NetworkSettingsTarget { CONNECTION, AUTHENTICATION, OBFUSCATION, AVATAR, TOOLS }

// Settings category sub-screens (reached from the top-level Settings list).
@Serializable data class AppearanceSettingsRoute(
    val target: SettingsTarget? = null,
)

@Serializable data class ChatSettingsRoute(
    val target: SettingsTarget? = null,
)

@Serializable data object DirectConnectionsRoute

@Serializable data class DeliverySettingsRoute(
    val target: SettingsTarget? = null,
)

@Serializable data class UploadsSettingsRoute(
    val target: SettingsTarget? = null,
)

@Serializable data class NetworksSettingsRoute(
    val target: SettingsTarget? = null,
)

@Serializable data class BackupRestoreRoute(
    val target: SettingsTarget? = null,
)

@Serializable data class ManageFoldersRoute(
    val networkId: Long? = null,
)

@Serializable data class FolderEditorRoute(
    val folderId: Long = 0,
)

@Serializable data class AutoGroupRoute(
    val networkId: Long? = null,
)

// Experimental features (gestures, Agentwire harness) live under their own category.
@Serializable data class LabsRoute(
    val target: SettingsTarget? = null,
)

@Serializable data class AiLabsRoute(
    val target: SettingsTarget? = null,
)

@Serializable data object AiModelLibraryRoute

// The gesture lab's menu-graph editor, reached from Labs > Gestures > Configure menu.
@Serializable data object GestureMenuEditorRoute

@Serializable data class NetworkSettingsRoute(
    val networkId: Long,
    val target: NetworkSettingsTarget? = null,
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
