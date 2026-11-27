package io.github.trevarj.motd.ui.chat

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.ComposerDraftEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineEventId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One insertion gesture; nickname punctuation is chosen when applied to the composer. */
data class ComposerPrefill(
    val text: String,
    val isNickname: Boolean = false,
)

/** Room-backed composer state; only transient navigation prefills remain process-local. */
@Singleton
class ComposerDraftStore
    @Inject
    constructor(
        private val db: MotdDatabase,
    ) {
        private val prefills = ConcurrentHashMap<Long, List<ComposerPrefill>>()

        private val _prefillPushes =
            MutableSharedFlow<Long>(
                extraBufferCapacity = PREFILL_PUSH_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /**
         * Buffer ids whose queued prefill just grew.
         *
         * The consume-on-entry path below only drains a prefill when a chat screen is *composed*, which
         * covers the "queue it, then navigate" case. It cannot cover a prefill aimed at the chat that is
         * already open: navigating there is `launchSingleTop`, so no entry effect re-runs and the queued
         * text would sit here until the user happened to leave and come back. An open chat therefore
         * listens for pushes instead of waiting to be entered.
         *
         * Replay is deliberately zero: a screen that was not listening yet has not missed anything,
         * because the prefill itself is still queued for it to consume on entry.
         */
        val prefillPushes: SharedFlow<Long> = _prefillPushes.asSharedFlow()

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
        ): ComposerDraftEntity? =
            db.withTransaction {
                val room = db.bufferDao().observeById(bufferId) ?: return@withTransaction null
                val roomId = room.id
                val dao = db.composerDraftDao()
                if (room.dismissed) {
                    dao.delete(roomId)
                    return@withTransaction null
                }
                val canonicalReplyId =
                    replyToEventId?.let {
                        db.canonicalTimelineDao().canonicalEventId(it)
                    }
                if (text.isBlank() && canonicalReplyId == null) {
                    dao.delete(roomId)
                    return@withTransaction null
                }
                val previous = dao.byRoom(roomId)
                val draft =
                    ComposerDraftEntity(
                        roomId = roomId,
                        text = text,
                        replyToEventId = canonicalReplyId,
                        updatedAt = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L),
                    )
                dao.upsert(draft)
                draft
            }

        internal suspend fun appendSharedLinks(
            bufferId: Long,
            urls: List<String>,
        ): ComposerDraftEntity? =
            db.withTransaction {
                val existing = loadDraft(bufferId)
                saveDraft(bufferId, appendSharedDraftLines(existing?.text.orEmpty(), urls), existing?.replyToEventId)
            }

        /** Atomically append a failed first notification reply without replacing composer edits. */
        suspend fun appendNotificationReply(
            bufferId: Long,
            text: String,
        ): ComposerDraftEntity? =
            db.withTransaction {
                val existing = loadDraft(bufferId)
                saveDraft(bufferId, mergeRejectedReply(existing?.text, text), existing?.replyToEventId)
            }

        /** Remove only an unchanged preserved copy, retaining any other text and reply identity. */
        suspend fun removeNotificationReply(
            bufferId: Long,
            text: String,
        ): Boolean =
            db.withTransaction {
                val existing = loadDraft(bufferId) ?: return@withTransaction false
                val remaining = withoutRetriedReply(existing.text, text) ?: return@withTransaction false
                saveDraft(bufferId, remaining, existing.replyToEventId)
                true
            }

        /** Clear only the exact accepted version; a concurrent edit/reply change wins. */
        suspend fun clearIfUnchanged(draft: ComposerDraftEntity): Boolean =
            db.withTransaction {
                val roomId = db.bufferDao().canonicalId(draft.roomId) ?: draft.roomId
                val canonicalReplyId =
                    draft.replyToEventId?.let {
                        db.canonicalTimelineDao().canonicalEventId(it)
                    }
                db.composerDraftDao().deleteIfUnchanged(
                    roomId = roomId,
                    text = draft.text,
                    replyToEventId = canonicalReplyId,
                    updatedAt = draft.updatedAt,
                ) == 1
            }

        /** Append [prefill] in gesture order and announce it on [prefillPushes]. */
        fun push(
            bufferId: Long,
            prefill: ComposerPrefill,
        ) {
            prefills.merge(bufferId, listOf(prefill)) { old, new -> old + new }
            _prefillPushes.tryEmit(bufferId)
        }

        /** Return and remove all queued gestures for [bufferId], in insertion order. */
        fun consume(bufferId: Long): List<ComposerPrefill>? = prefills.remove(bufferId)

        private companion object {
            /** A prefill is one user gesture; a handful in flight is already more than realistic. */
            const val PREFILL_PUSH_BUFFER = 8
        }
    }

internal fun appendSharedDraftLines(
    existing: String,
    urls: List<String>,
): String =
    buildString {
        append(existing)
        if (isNotEmpty() && last() != '\n') append('\n')
        urls.joinTo(this, "\n")
    }

/** Append a failed reply to whatever the composer already holds, preserving both. */
internal fun mergeRejectedReply(
    existing: String?,
    rejected: String,
): String = if (existing.isNullOrBlank()) rejected else "$existing\n$rejected"

/** Null means the user edited or replaced the preserved copy, so the draft must remain untouched. */
internal fun withoutRetriedReply(
    existing: String?,
    retried: String,
): String? =
    when {
        existing == null -> null
        existing == retried -> ""
        existing.endsWith("\n$retried") -> existing.removeSuffix("\n$retried")
        else -> null
    }
