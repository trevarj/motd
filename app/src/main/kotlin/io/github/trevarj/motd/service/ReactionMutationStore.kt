package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.irc.proto.IrcMessage

internal interface ReactionMutationStore {
    suspend fun findOwn(
        bufferId: Long,
        targetMsgid: String,
        actorKeys: List<String>,
        emoji: String,
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
        actorKeys: List<String>,
        emoji: String,
    ): ReactionEntity? = db.reactionDao().find(bufferId, targetMsgid, actorKeys, emoji)

    override suspend fun upsert(reaction: ReactionEntity) {
        db.reactionDao().upsert(reaction)
    }

    override suspend fun remove(reaction: ReactionEntity) {
        db.reactionDao().delete(
            reaction.bufferId,
            reaction.targetMsgid,
            reaction.actorKey,
            reaction.emoji,
        )
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
    val kind = if (previous != null) {
        store.remove(previous)
        ReactionMutationKind.REMOVE
    } else {
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
        }
        throw failure
    }
    return kind
}
