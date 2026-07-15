package io.github.trevarj.motd.service

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.flow.first

internal interface ReactionMutationStore {
    suspend fun findOwn(
        bufferId: Long,
        targetMsgid: String,
        sender: String,
        normalizeNick: (String) -> String,
    ): ReactionEntity?

    suspend fun upsert(reaction: ReactionEntity)
    suspend fun remove(reaction: ReactionEntity)
}

internal class RoomReactionMutationStore(
    private val db: MotdDatabase,
) : ReactionMutationStore {
    override suspend fun findOwn(
        bufferId: Long,
        targetMsgid: String,
        sender: String,
        normalizeNick: (String) -> String,
    ): ReactionEntity? {
        val normalizedSender = normalizeNick(sender)
        return db.reactionDao().observeForBuffer(bufferId).first().firstOrNull {
            it.targetMsgid == targetMsgid && normalizeNick(it.sender) == normalizedSender
        }
    }

    override suspend fun upsert(reaction: ReactionEntity) {
        db.reactionDao().upsert(reaction)
    }

    override suspend fun remove(reaction: ReactionEntity) {
        // Keep mutation methods outside the frozen DAO contract. Running through Room's
        // transaction still drives invalidation for the reaction Flow observed by chat.
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM reactions WHERE bufferId = ? AND targetMsgid = ? AND sender = ?",
                arrayOf<Any>(reaction.bufferId, reaction.targetMsgid, reaction.sender),
            )
        }
    }
}

internal enum class ReactionMutationKind { ADD, REMOVE }

internal fun reactionTagMessage(
    target: String,
    targetMsgid: String,
    emoji: String,
    kind: ReactionMutationKind,
): IrcMessage = IrcMessage(
    tags = mapOf(
        (if (kind == ReactionMutationKind.REMOVE) "+draft/unreact" else "+draft/react") to emoji,
        "+reply" to targetMsgid,
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
    val kind = if (previous?.emoji == reaction.emoji) {
        store.remove(previous)
        ReactionMutationKind.REMOVE
    } else {
        // Reactions are uniquely keyed by their raw sender spelling for compatibility with the
        // existing Room schema, while IRC nick matching is casefolded. Remove a prior case-variant
        // row before replacing its emoji so a server-side nick-case change cannot leave two rows.
        previous?.let { store.remove(it) }
        store.upsert(reaction)
        ReactionMutationKind.ADD
    }
    try {
        send(kind)
    } catch (failure: Throwable) {
        if (kind == ReactionMutationKind.REMOVE) {
            checkNotNull(previous)
            store.upsert(previous)
        } else {
            store.remove(reaction)
            previous?.let { store.upsert(it) }
        }
        throw failure
    }
    return kind
}
