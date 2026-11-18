package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.FolderIdentityKind
import kotlinx.serialization.Serializable

@Serializable
data class PortableChatPlacement(
    val networkId: Long,
    val chatType: BufferType,
    val identityKind: FolderIdentityKind,
    val identityValue: String,
    val pinned: Boolean = false,
    val anchorOrder: Int? = null,
    val restoreRevision: Long = 0,
)
