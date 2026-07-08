package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberDao
import io.github.trevarj.motd.data.db.MemberEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

// Buffer-level reads and pin/mute toggles. Mark-read is intentionally NOT here: it flows through
// ConnectionManager.markRead (single entry point) so Room advance + MARKREAD stay coupled.
class BufferRepositoryImpl @Inject constructor(
    private val bufferDao: BufferDao,
    private val memberDao: MemberDao,
) : BufferRepository {
    override fun observeChatList(): Flow<List<ChatListRow>> = bufferDao.observeChatList()

    override fun observeBuffer(id: Long): Flow<BufferEntity?> = bufferDao.observe(id)

    override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> =
        memberDao.observe(bufferId)

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        val current = bufferDao.observeById(id) ?: return
        bufferDao.update(current.copy(pinned = pinned))
    }

    override suspend fun setMuted(id: Long, muted: Boolean) {
        val current = bufferDao.observeById(id) ?: return
        bufferDao.update(current.copy(muted = muted))
    }
}
