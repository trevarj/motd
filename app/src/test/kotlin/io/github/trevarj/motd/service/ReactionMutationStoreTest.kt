package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.ReactionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReactionMutationStoreTest {
    private class FakeStore(initial: ReactionEntity? = null) : ReactionMutationStore {
        var row = initial

        override suspend fun findOwn(
            bufferId: Long,
            targetMsgid: String,
            sender: String,
            normalizeNick: (String) -> String,
        ): ReactionEntity? = row

        override suspend fun upsert(reaction: ReactionEntity) {
            row = reaction
        }

        override suspend fun remove(reaction: ReactionEntity) {
            if (row?.sender == reaction.sender && row?.targetMsgid == reaction.targetMsgid) row = null
        }
    }

    private fun reaction(emoji: String = "👍") = ReactionEntity(
        bufferId = 1,
        targetMsgid = "m1",
        sender = "me",
        emoji = emoji,
        serverTime = 10,
    )

    @Test
    fun `add is optimistic before wire send`() = runTest {
        val store = FakeStore()
        val added = reaction()

        val kind = mutateReaction(store, null, added) {
            assertSame(added, store.row)
        }

        assertEquals(ReactionMutationKind.ADD, kind)
        assertSame(added, store.row)
    }

    @Test
    fun `reaction mutation serializer uses ratified reply reference`() {
        assertEquals(
            "@+draft/react=👍;+reply=m1 TAGMSG #chan",
            reactionTagMessage("#chan", "m1", "👍", ReactionMutationKind.ADD).serialize(),
        )
        assertEquals(
            "@+draft/unreact=👍;+reply=m1 TAGMSG #chan",
            reactionTagMessage("#chan", "m1", "👍", ReactionMutationKind.REMOVE).serialize(),
        )
    }

    @Test
    fun `owned reaction toggles off before wire send`() = runTest {
        val previous = reaction()
        val store = FakeStore(previous)

        val kind = mutateReaction(store, previous, reaction()) {
            assertNull(store.row)
        }

        assertEquals(ReactionMutationKind.REMOVE, kind)
        assertNull(store.row)
    }

    @Test
    fun `failed add and remove restore exactly the prior state`() = runTest {
        val addStore = FakeStore()
        val addFailure = IllegalStateException("send failed")
        runCatching { mutateReaction(addStore, null, reaction()) { throw addFailure } }
        assertNull(addStore.row)

        val previous = reaction()
        val removeStore = FakeStore(previous)
        val observed = runCatching {
            mutateReaction(removeStore, previous, reaction()) { throw addFailure }
        }.exceptionOrNull()
        assertSame(addFailure, observed)
        assertSame(previous, removeStore.row)
    }

    @Test
    fun `failed replacement restores previous emoji`() = runTest {
        val previous = reaction("❤️")
        val store = FakeStore(previous)

        runCatching {
            mutateReaction(store, previous, reaction("👍")) { error("send failed") }
        }

        assertSame(previous, store.row)
    }
}
