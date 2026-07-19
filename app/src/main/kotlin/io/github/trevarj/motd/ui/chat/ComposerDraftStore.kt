package io.github.trevarj.motd.ui.chat

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.ComposerDraftEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineEventId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/** Room-backed composer state; only transient navigation prefills remain process-local. */
@Singleton
class ComposerDraftStore @Inject constructor(
    private val db: MotdDatabase,
) {
    private val prefills = ConcurrentHashMap<Long, String>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(bufferId: Long): Flow<ComposerDraftEntity?> =
        db.bufferDao().observe(bufferId).flatMapLatest { room ->
            db.composerDraftDao().observe(room?.id ?: bufferId)
        }

    suspend fun loadDraft(bufferId: Long): ComposerDraftEntity? {
        val roomId = db.bufferDao().canonicalId(bufferId) ?: bufferId
        return db.composerDraftDao().byRoom(roomId)
    }

    /** Persist text and reply identity as one versioned value, including reply-only drafts. */
    suspend fun saveDraft(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId?,
    ): ComposerDraftEntity? = db.withTransaction {
        val room = db.bufferDao().observeById(bufferId) ?: return@withTransaction null
        val roomId = room.id
        val dao = db.composerDraftDao()
        if (room.dismissed) {
            dao.delete(roomId)
            return@withTransaction null
        }
        val canonicalReplyId = replyToEventId?.let {
            db.canonicalTimelineDao().canonicalEventId(it)
        }
        if (text.isBlank() && canonicalReplyId == null) {
            dao.delete(roomId)
            return@withTransaction null
        }
        val previous = dao.byRoom(roomId)
        val draft = ComposerDraftEntity(
            roomId = roomId,
            text = text,
            replyToEventId = canonicalReplyId,
            updatedAt = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L),
        )
        dao.upsert(draft)
        draft
    }

    /** Clear only the exact accepted version; a concurrent edit/reply change wins. */
    suspend fun clearIfUnchanged(draft: ComposerDraftEntity): Boolean = db.withTransaction {
        val roomId = db.bufferDao().canonicalId(draft.roomId) ?: draft.roomId
        val canonicalReplyId = draft.replyToEventId?.let {
            db.canonicalTimelineDao().canonicalEventId(it)
        }
        db.composerDraftDao().deleteIfUnchanged(
            roomId = roomId,
            text = draft.text,
            replyToEventId = canonicalReplyId,
            updatedAt = draft.updatedAt,
        ) == 1
    }

    /** Append [text] to any queued prefill for [bufferId]. */
    fun push(bufferId: Long, text: String) {
        prefills.merge(bufferId, text) { old, new -> old + new }
    }

    /** Return and remove the queued prefill for [bufferId]. */
    fun consume(bufferId: Long): String? = prefills.remove(bufferId)
}
