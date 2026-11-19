package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.FolderIdentityKind
import io.github.trevarj.motd.data.prefs.WallpaperChatChoice
import kotlinx.serialization.Serializable

const val MAX_CHAT_WALLPAPER_ASSIGNMENTS = 2_000

@Serializable
data class PortableChatWallpaper(
    val networkId: Long,
    val chatType: BufferType,
    val identityKind: FolderIdentityKind,
    val identityValue: String,
    val choice: WallpaperChatChoice,
    val restoreRevision: Long = 0,
)

internal fun isPortableChatIdentity(
    chatType: BufferType,
    identityKind: FolderIdentityKind,
    identityValue: String,
): Boolean =
    (
        (chatType == BufferType.CHANNEL && identityKind == FolderIdentityKind.CHANNEL) ||
            (chatType == BufferType.QUERY && identityKind != FolderIdentityKind.CHANNEL)
    ) &&
        identityValue.isNotBlank() && identityValue.length <= 512 && identityValue.none(Char::isISOControl)

fun validatePortableChatIdentity(
    chatType: BufferType,
    identityKind: FolderIdentityKind,
    identityValue: String,
) {
    require(isPortableChatIdentity(chatType, identityKind, identityValue)) { "Backup contains an invalid chat identity." }
}
