package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.FolderIdentityKind
import io.github.trevarj.motd.data.db.RoomAliasEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnchoredBufferRepositoryTest {
    @Test
    fun deferredAccountPlacementPreservesNewerPinAndUnpinAfterNickQueryMergesIntoOlderAccountRoom() =
        runTest {
            val db = inMemoryDb()
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val store = BufferStore(db)
                val repository =
                    BufferRepositoryImpl(
                        db.bufferDao(),
                        db.memberDao(),
                        db.messageDao(),
                        DataStoreSettingsRepository(context),
                        MessageVisibilityReader(db),
                        LocalAvatarStore(context),
                        bufferStore = store,
                    )
                val networkId = db.networkDao().insert(network())
                for (pinned in listOf(false, true)) {
                    val account = "merged-account-$pinned"
                    val nick = "merged-nick-$pinned"
                    store.restoreChatPlacements(
                        listOf(
                            PortableChatPlacement(
                                networkId,
                                BufferType.QUERY,
                                FolderIdentityKind.ACCOUNT,
                                account,
                                pinned = !pinned,
                                anchorOrder = 0,
                            ),
                        ),
                        replace = false,
                    )
                    val older = store.getOrCreate(networkId, "older-$pinned", "Older-$pinned", BufferType.QUERY)
                    db.roomAliasDao().insertIgnore(
                        RoomAliasEntity(
                            networkId = networkId,
                            namespace = RoomAliasNamespace.ACCOUNT,
                            value = account,
                            roomId = older.id,
                            verified = true,
                        ),
                    )
                    val newer = store.getOrCreate(networkId, nick, nick, BufferType.QUERY)
                    db.bufferDao().setPinned(newer.id, !pinned)
                    repository.setPinned(newer.id, pinned)
                    assertTrue(db.appStateDao().keysLike("pending-chat-placement:%").isNotEmpty())
                    val merged = store.bindQueryIdentity(newer.id, networkId, nick, nick, account)
                    assertEquals(older.id, merged.id)
                    assertEquals(pinned, merged.pinned)
                    assertEquals(pinned, repository.buffer(newer.id)?.pinned)
                    assertEquals(0, merged.anchorOrder)
                    assertTrue(db.appStateDao().keysLike("chat-placement-pin-edit:${newer.id}:%").isEmpty())
                    assertTrue(db.appStateDao().keysLike("chat-placement-pin-edit:${older.id}:%").isNotEmpty())
                    assertTrue(db.appStateDao().keysLike("pending-chat-placement:%").isEmpty())
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun deferredAccountPlacementPreservesNewerPinAndUnpinWithoutBlockingAnchorRestore() =
        runTest {
            val db = inMemoryDb()
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val store = BufferStore(db)
                val repository =
                    BufferRepositoryImpl(
                        db.bufferDao(),
                        db.memberDao(),
                        db.messageDao(),
                        DataStoreSettingsRepository(context),
                        MessageVisibilityReader(db),
                        LocalAvatarStore(context),
                        bufferStore = store,
                    )
                val networkId = db.networkDao().insert(network())
                for (pinned in listOf(false, true)) {
                    val account = "account-$pinned"
                    val nick = "nick-$pinned"
                    store.restoreChatPlacements(
                        listOf(
                            PortableChatPlacement(
                                networkId,
                                BufferType.QUERY,
                                FolderIdentityKind.ACCOUNT,
                                account,
                                pinned = !pinned,
                                anchorOrder = 0,
                            ),
                        ),
                        replace = false,
                    )
                    val room = store.getOrCreate(networkId, nick, nick, BufferType.QUERY)
                    db.bufferDao().setPinned(room.id, !pinned)
                    repository.setPinned(room.id, pinned)
                    store.bindQueryIdentity(room.id, networkId, nick, nick, account)
                    assertEquals(pinned, repository.buffer(room.id)?.pinned)
                    assertEquals(0, repository.buffer(room.id)?.anchorOrder)
                    assertTrue(db.appStateDao().keysLike("pending-chat-placement:%").isEmpty())
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun pendingAnchorsReserveGlobalSlotsAndNewerUserEditsWinWhenAccountIsLearned() =
        runTest {
            val db = inMemoryDb()
            try {
                val networkId = db.networkDao().insert(network())
                val store = BufferStore(db)
                store.restoreChatPlacements(
                    listOf(
                        PortableChatPlacement(networkId, BufferType.CHANNEL, FolderIdentityKind.CHANNEL, "#later", anchorOrder = 0),
                        PortableChatPlacement(networkId, BufferType.QUERY, FolderIdentityKind.ACCOUNT, "unanchored-account", anchorOrder = 1),
                        PortableChatPlacement(networkId, BufferType.QUERY, FolderIdentityKind.ACCOUNT, "moved-account", anchorOrder = 2),
                    ),
                    replace = true,
                )
                val first = store.getOrCreate(networkId, "#first", "#First", BufferType.CHANNEL)
                val second = store.getOrCreate(networkId, "#second", "#Second", BufferType.CHANNEL)
                store.setAnchored(first.id, true)
                store.setAnchored(second.id, true)
                assertEquals(3, db.bufferDao().observeById(first.id)?.anchorOrder)
                assertEquals(4, db.bufferDao().observeById(second.id)?.anchorOrder)
                store.reorderAnchored(listOf(second.id, first.id))
                assertEquals(4, db.bufferDao().observeById(first.id)?.anchorOrder)
                assertEquals(3, db.bufferDao().observeById(second.id)?.anchorOrder)

                val unanchored = store.getOrCreate(networkId, "unknown", "Unknown", BufferType.QUERY)
                store.setAnchored(unanchored.id, false)
                store.bindQueryIdentity(unanchored.id, networkId, "unknown", "Unknown", "unanchored-account")
                assertNull(db.bufferDao().observeById(unanchored.id)?.anchorOrder)

                val moved = store.getOrCreate(networkId, "moving", "Moving", BufferType.QUERY)
                store.setAnchored(moved.id, true)
                assertEquals(5, db.bufferDao().observeById(moved.id)?.anchorOrder)
                store.reorderAnchored(listOf(moved.id, second.id))
                assertEquals(3, db.bufferDao().observeById(moved.id)?.anchorOrder)
                store.bindQueryIdentity(moved.id, networkId, "moving", "Moving", "moved-account")
                assertEquals(3, db.bufferDao().observeById(moved.id)?.anchorOrder)

                val pending = store.chatPlacementSnapshot().single { it.identityValue == "#later" }
                assertEquals(0, pending.anchorOrder)
                val later = store.getOrCreate(networkId, "#later", "#Later", BufferType.CHANNEL)
                assertEquals(0, later.anchorOrder)
                assertTrue(db.appStateDao().keysLike("pending-chat-placement:%").isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun anchorsPersistAndScopedReorderDoesNotMoveHiddenChatsOrMutatePins() =
        runTest {
            val db = inMemoryDb()
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val repository =
                    BufferRepositoryImpl(
                        db.bufferDao(),
                        db.memberDao(),
                        db.messageDao(),
                        DataStoreSettingsRepository(context),
                        MessageVisibilityReader(db),
                        LocalAvatarStore(context),
                        bufferStore =
                            io.github.trevarj.motd.data.sync
                                .BufferStore(db),
                    )
                val networkId = db.networkDao().insert(network())
                val first = db.bufferDao().insert(buffer(networkId, "#first").copy(pinned = true))
                val hidden = db.bufferDao().insert(buffer(networkId, "#hidden").copy(archived = true))
                val last = db.bufferDao().insert(buffer(networkId, "#last"))
                val redirect = db.bufferDao().insert(buffer(networkId, "#redirect").copy(redirectToRoomId = first))
                repository.setAnchored(redirect, true)
                repository.setAnchored(hidden, true)
                repository.setAnchored(last, true)
                repository.reorderAnchored(listOf(last, first, last, Long.MAX_VALUE))
                val rows = repository.observeChatList().first()
                assertEquals(listOf(last, hidden, first), rows.map { it.bufferId })
                assertEquals(listOf(0, 1, 2), rows.map { it.anchorOrder })
                assertTrue(repository.buffer(first)!!.pinned)
                repository.setAnchored(redirect, false)
                assertNull(repository.buffer(first)!!.anchorOrder)
                assertTrue(repository.buffer(first)!!.pinned)
                assertNull(repository.buffer(redirect)!!.anchorOrder)
            } finally {
                db.close()
            }
        }
}
