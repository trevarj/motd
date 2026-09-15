package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.avatar.validateAvatarUrl
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberDao
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MonitorQueryRow
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal fun nickSuggestionLikePattern(prefix: String): String = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

// Buffer-level reads and pin/mute toggles. Mark-read is intentionally NOT here: it flows through
// ConnectionManager.markRead (single entry point) so Room advance + MARKREAD stay coupled.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BufferRepositoryImpl
    @Inject
    constructor(
        private val bufferDao: BufferDao,
        private val memberDao: MemberDao,
        private val messageDao: MessageDao,
        private val settings: SettingsRepository,
        private val visibilityReader: MessageVisibilityReader,
        private val localAvatarStore: LocalAvatarStore,
        private val networkIdentityDao: NetworkIdentityDao? = null,
    ) : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> =
            combine(
                bufferDao.observeChatList(),
                settings.settings.map(MessageVisibilitySpec::from).distinctUntilChanged(),
            ) { rows, spec -> visibilityReader.resolveChatList(rows, spec) }

        override fun observeQueryConversations(): Flow<List<MonitorQueryRow>> = bufferDao.observeMonitorQueryRows().distinctUntilChanged()

        override fun observeNickSuggestions(
            networkId: Long,
            prefix: String,
            selfNick: String,
            limit: Int,
        ): Flow<List<String>> =
            (networkIdentityDao?.observe(networkId) ?: flowOf(null))
                .flatMapLatest { identity ->
                    val rules = identity?.identityRules ?: IrcIdentityRules()
                    memberDao.observeNickSuggestions(
                        networkId = networkId,
                        pattern = nickSuggestionLikePattern(rules.normalize(prefix)),
                        selfKey = rules.normalize(identity?.selfNick ?: selfNick),
                        caseMapping = rules.caseMapping.rawName,
                        limit = limit.coerceIn(1, 10),
                    )
                }.distinctUntilChanged()

        override suspend fun canonicalBufferId(id: Long): Long? = bufferDao.canonicalId(id)

        override fun observeInvitations() = messageDao.observeInvitations()

        override fun observeJoinedChannelNames(networkId: Long): Flow<Set<String>> = bufferDao.observeJoinedChannelNames(networkId).map { it.toSet() }.distinctUntilChanged()

        override fun observeJoinedChannels(networkId: Long) = bufferDao.observeJoinedChannels(networkId).distinctUntilChanged()

        override fun observeNotificationChannels() = bufferDao.observeNotificationChannels().distinctUntilChanged()

        override suspend fun joinedBufferId(
            networkId: Long,
            normalizedChannel: String,
        ): Long? = bufferDao.byName(networkId, normalizedChannel)?.takeIf { it.joined }?.id

        override fun observeBuffer(id: Long): Flow<BufferEntity?> = bufferDao.observe(id)

        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> =
            bufferDao.observe(bufferId).flatMapLatest { room ->
                memberDao.observe(room?.id ?: bufferId)
            }

        override fun observeMemberNicks(bufferId: Long): Flow<List<String>> =
            bufferDao.observe(bufferId).flatMapLatest { room ->
                memberDao.observeNicks(room?.id ?: bufferId)
            }

        override fun observeLastSpokeByNick(bufferId: Long): Flow<Map<String, Long>> =
            bufferDao.observe(bufferId).flatMapLatest { room ->
                messageDao
                    .observeLastSpoke(room?.id ?: bufferId)
                    .map { rows -> rows.associate { it.nick to it.lastSpokeAt } }
            }

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) {
            bufferDao.setPinned(bufferDao.canonicalId(id) ?: id, pinned)
        }

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = bufferDao.setMuted(bufferDao.canonicalId(id) ?: id, muted)

        override suspend fun restoreMuteBacklog(suppression: MuteBacklogSuppression) {
            // The suppression already carries the canonical id setMuted wrote to.
            bufferDao.restoreLocalUnreadFloor(suppression.bufferId, suppression.previousFloorTime)
        }

        override suspend fun setArchived(
            id: Long,
            archived: Boolean,
        ) {
            bufferDao.setArchived(id, archived)
        }

        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: LayoutDensity?,
        ): Boolean = bufferDao.setLayoutDensityOverride(id, layout) == 1

        override suspend fun setPresenceModeOverride(
            id: Long,
            mode: PresenceMode?,
        ): Boolean = bufferDao.setPresenceModeOverride(id, mode) == 1

        override suspend fun setAvatarOverride(
            id: Long,
            model: String?,
        ): Boolean {
            if (model != null && validateAvatarUrl(model) == null && !localAvatarStore.owns(model)) return false
            return bufferDao.setAvatarOverride(id, model) == 1
        }

        // QUERY rows become hidden cursor shells and retain their avatar; deleted CHANNEL files do not.
        override suspend fun deleteBuffer(id: Long) {
            val canonicalId = bufferDao.canonicalId(id) ?: id
            val room = bufferDao.rawById(canonicalId)
            bufferDao.deleteBuffer(canonicalId)
            if (room?.type == io.github.trevarj.motd.data.db.BufferType.CHANNEL) {
                localAvatarStore.delete(room.avatarOverrideModel)
            }
        }
    }
