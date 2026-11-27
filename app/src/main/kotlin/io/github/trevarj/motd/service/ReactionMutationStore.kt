package io.github.trevarj.motd.service

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal interface ReactionMutationStore {
    suspend fun findOwn(
        bufferId: Long,
        targetMsgid: String,
        actorKeys: List<String>,
        emoji: String,
    ): ReactionEntity?

    suspend fun upsert(reaction: ReactionEntity): ReactionEntity?

    suspend fun remove(reaction: ReactionEntity): ReactionEntity?
}

internal class RoomReactionMutationStore(
    private val db: MotdDatabase,
) : ReactionMutationStore {
    override suspend fun findOwn(
        bufferId: Long,
        targetMsgid: String,
        actorKeys: List<String>,
        emoji: String,
    ): ReactionEntity? = db.reactionDao().find(bufferId, targetMsgid, actorKeys, emoji)

    override suspend fun upsert(reaction: ReactionEntity): ReactionEntity? =
        db.withTransaction {
            val canonical = atCurrentParent(reaction) ?: return@withTransaction null
            db.reactionDao().upsert(canonical)
            canonical
        }

    override suspend fun remove(reaction: ReactionEntity): ReactionEntity? =
        db.withTransaction {
            val canonical = atCurrentParent(reaction) ?: return@withTransaction null
            val deleted = db.reactionDao().delete(canonical.bufferId, canonical.targetMsgid, canonical.actorKey, canonical.emoji)
            canonical.takeIf { deleted > 0 }
        }

    private suspend fun atCurrentParent(reaction: ReactionEntity): ReactionEntity? {
        val messages = db.messageDao()
        val parent =
            if (reaction.targetEventId != null) {
                messages.byCanonicalId(reaction.targetEventId)
            } else {
                val roomId = db.bufferDao().canonicalId(reaction.bufferId) ?: return null
                messages.byMsgid(roomId, reaction.targetMsgid)
            } ?: return null
        if (parent.kind == MessageKind.REDACTED) return null
        return reaction.copy(bufferId = parent.bufferId, targetEventId = parent.id)
    }
}

internal enum class ReactionMutationKind { ADD, REMOVE }

internal fun reactionTagMessage(
    target: String,
    targetMsgid: String,
    emoji: String,
    kind: ReactionMutationKind,
    replyTag: String,
): IrcMessage =
    IrcMessage(
        tags =
            mapOf(
                (if (kind == ReactionMutationKind.REMOVE) "+draft/unreact" else "+draft/react") to emoji,
                replyTag to targetMsgid,
            ),
        command = "TAGMSG",
        params = listOf(target),
    )

/** Apply one optimistic mutation and restore exactly its prior local row if wire sending fails. */
internal suspend fun mutateReaction(
    store: ReactionMutationStore,
    previous: ReactionEntity?,
    reaction: ReactionEntity,
    send: suspend (ReactionMutationKind) -> Unit,
): ReactionMutationKind {
    val kind = if (previous == null) ReactionMutationKind.ADD else ReactionMutationKind.REMOVE
    // Pin the mutation to the parent ID before suspending on the wire. A later history fetch
    // with the same MSGID is not a canonical redirect of a deleted parent.
    val applied = if (previous == null) store.upsert(reaction) else store.remove(previous)
    try {
        send(kind)
    } catch (failure: Throwable) {
        if (applied != null) {
            try {
                withContext(NonCancellable) {
                    if (kind == ReactionMutationKind.REMOVE) store.upsert(applied) else store.remove(applied)
                }
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    }
    return kind
}
