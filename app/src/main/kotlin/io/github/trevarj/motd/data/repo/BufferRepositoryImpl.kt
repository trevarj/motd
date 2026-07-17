package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberDao
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

// Buffer-level reads and pin/mute toggles. Mark-read is intentionally NOT here: it flows through
// ConnectionManager.markRead (single entry point) so Room advance + MARKREAD stay coupled.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BufferRepositoryImpl @Inject constructor(
    private val bufferDao: BufferDao,
    private val memberDao: MemberDao,
    private val settings: SettingsRepository,
    private val visibilityReader: MessageVisibilityReader,
) : BufferRepository {
    override fun observeChatList(): Flow<List<ChatListRow>> = combine(
        bufferDao.observeChatList(),
        settings.settings.map(MessageVisibilitySpec::from).distinctUntilChanged(),
    ) { rows, spec -> visibilityReader.resolveChatList(rows, spec) }

    override fun observeBuffer(id: Long): Flow<BufferEntity?> = bufferDao.observe(id)

    override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> =
        bufferDao.observe(bufferId).flatMapLatest { room ->
            memberDao.observe(room?.id ?: bufferId)
        }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        bufferDao.setPinned(bufferDao.canonicalId(id) ?: id, pinned)
    }

    override suspend fun setMuted(id: Long, muted: Boolean) {
        bufferDao.setMuted(bufferDao.canonicalId(id) ?: id, muted)
    }

    // Drop the buffer row (messages cascade via FK) plus its members/reactions in one transaction.
    override suspend fun deleteBuffer(id: Long) = bufferDao.deleteBuffer(bufferDao.canonicalId(id) ?: id)
}
