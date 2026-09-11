package io.github.trevarj.motd.dickord

import io.github.trevarj.motd.data.db.ChatListRow

internal const val DICKORD_PORTAL_DMS_KEY = "dms"
internal const val DICKORD_PORTAL_PENDING_KEY = "pending"

internal data class DickordPortalConversation(
    val row: ChatListRow,
    val descriptor: DickordChannelDescriptor?,
)

internal data class DickordPortalGroup(
    val key: String,
    val networkId: Long?,
    val guildId: String?,
    val displayName: String?,
    val iconUrl: String?,
    val conversations: List<DickordPortalConversation>,
)

internal fun presentDickordPortal(
    rows: List<ChatListRow>,
    archived: Boolean,
): List<DickordPortalGroup> {
    val dms = mutableListOf<DickordPortalConversation>()
    val pending = mutableListOf<DickordPortalConversation>()
    val guilds = linkedMapOf<Pair<Long, String>, MutableList<DickordPortalConversation>>()

    rows
        .asSequence()
        .filter { it.archived == archived && isDickordPortalConversation(it.type, it.displayName) }
        .map { DickordPortalConversation(it, decodeDickordChannelDescriptor(it.dickordChannelJson)) }
        .forEach { conversation ->
            val descriptor = conversation.descriptor
            when {
                descriptor == null -> {
                    pending += conversation
                }

                isDiscordDirectMessage(descriptor.channelType) -> {
                    dms += conversation
                }

                descriptor.guildId != null -> {
                    guilds.getOrPut(conversation.row.networkId to descriptor.guildId) { mutableListOf() } += conversation
                }

                else -> {
                    pending += conversation
                }
            }
        }

    val servers =
        guilds
            .map { (identity, conversations) ->
                val descriptors = conversations.map { requireNotNull(it.descriptor) }.sortedWith(descriptorChannelOrder)
                val presentation = descriptors.firstOrNull { it.channelName != null } ?: descriptors.first()
                DickordPortalGroup(
                    key = "guild:${identity.first}:${identity.second}",
                    networkId = identity.first,
                    guildId = identity.second,
                    displayName = presentation.guildName,
                    iconUrl = presentation.guildIconUrl,
                    conversations = conversations.sortedWith(serverConversationOrder),
                )
            }.sortedWith(serverGroupOrder)

    return buildList {
        add(
            DickordPortalGroup(
                key = DICKORD_PORTAL_DMS_KEY,
                networkId = null,
                guildId = null,
                displayName = null,
                iconUrl = null,
                conversations = dms.sortedWith(dmConversationOrder),
            ),
        )
        addAll(servers)
        if (pending.isNotEmpty()) {
            add(
                DickordPortalGroup(
                    key = DICKORD_PORTAL_PENDING_KEY,
                    networkId = null,
                    guildId = null,
                    displayName = null,
                    iconUrl = null,
                    conversations = pending.sortedWith(serverConversationOrder),
                ),
            )
        }
    }
}

private val dmConversationOrder =
    compareByDescending<DickordPortalConversation> { it.row.pinned }
        .thenBy { it.row.lastMessageTime == null }
        .thenByDescending { it.row.lastMessageTime ?: Long.MIN_VALUE }
        .thenByDescending { it.row.bufferId }

private val serverConversationOrder =
    Comparator<DickordPortalConversation> { left, right ->
        when {
            left.row.pinned != right.row.pinned -> {
                right.row.pinned.compareTo(left.row.pinned)
            }

            left.descriptor?.channelName == null && right.descriptor?.channelName != null -> {
                1
            }

            left.descriptor?.channelName != null && right.descriptor?.channelName == null -> {
                -1
            }

            else -> {
                val byName =
                    String.CASE_INSENSITIVE_ORDER.compare(
                        left.descriptor?.channelName.orEmpty(),
                        right.descriptor?.channelName.orEmpty(),
                    )
                if (byName != 0) byName else left.row.bufferId.compareTo(right.row.bufferId)
            }
        }
    }

private val serverGroupOrder =
    Comparator<DickordPortalGroup> { left, right ->
        val byName =
            String.CASE_INSENSITIVE_ORDER.compare(
                left.displayName.orEmpty(),
                right.displayName.orEmpty(),
            )
        when {
            byName != 0 -> {
                byName
            }

            left.networkId != right.networkId -> {
                requireNotNull(left.networkId).compareTo(requireNotNull(right.networkId))
            }

            else -> {
                compareSnowflakes(requireNotNull(left.guildId), requireNotNull(right.guildId))
            }
        }
    }

private val descriptorChannelOrder =
    Comparator<DickordChannelDescriptor> { left, right ->
        compareSnowflakes(left.channelId, right.channelId)
    }

private fun compareSnowflakes(
    left: String,
    right: String,
): Int = left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
