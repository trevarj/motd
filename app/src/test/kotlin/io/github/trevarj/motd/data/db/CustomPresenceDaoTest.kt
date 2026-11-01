package io.github.trevarj.motd.data.db

import io.github.trevarj.motd.data.prefs.PresenceMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomPresenceDaoTest {
    @Test
    fun redirectedCustomSelectionSnapshotsOnceAndRetainsIndependentEmptyChoice() =
        runTest {
            val db = inMemoryDb()
            try {
                val networkId = db.networkDao().insert(network())
                val dao = db.bufferDao()
                val canonical = dao.insert(buffer(networkId, "alice", type = BufferType.QUERY))
                val alias = dao.insert(buffer(networkId, "alice-old", type = BufferType.QUERY).copy(redirectToRoomId = canonical))
                val other = dao.insert(buffer(networkId, "bob", type = BufferType.QUERY))
                val originalGlobal = setOf(MessageKind.JOIN, MessageKind.QUIT)
                assertEquals(1, dao.setPresenceModeOverride(alias, PresenceMode.CUSTOM, originalGlobal))
                assertEquals(originalGlobal, dao.observeById(canonical)!!.presenceCustomKindsOverride)
                assertNull(dao.rawById(alias)!!.presenceCustomKindsOverride)

                // Concurrent toggles must each read the latest canonical selection.
                listOf(MessageKind.JOIN, MessageKind.QUIT)
                    .map { kind ->
                        async { dao.togglePresenceCustomKind(alias, kind, originalGlobal) }
                    }.awaitAll()
                assertEquals(emptySet<MessageKind>(), dao.observeById(canonical)!!.presenceCustomKindsOverride)
                dao.setPresenceModeOverride(alias, null, null)
                assertNull(dao.observeById(canonical)!!.presenceModeOverride)
                dao.setPresenceModeOverride(alias, PresenceMode.SMART, null)
                val changedGlobal = setOf(MessageKind.BACK)
                dao.setPresenceModeOverride(alias, PresenceMode.CUSTOM, changedGlobal)
                assertEquals(emptySet<MessageKind>(), dao.observeById(canonical)!!.presenceCustomKindsOverride)
                dao.setPresenceModeOverride(other, PresenceMode.CUSTOM, changedGlobal)
                assertEquals(changedGlobal, dao.observeById(other)!!.presenceCustomKindsOverride)
                assertEquals(emptySet<MessageKind>(), dao.observeById(canonical)!!.presenceCustomKindsOverride)
                assertEquals(0, dao.togglePresenceCustomKind(Long.MAX_VALUE, MessageKind.JOIN, originalGlobal))
            } finally {
                db.close()
            }
        }

    @Test
    fun storedKindsDistinguishMissingFromEmptyAndIgnoreUnknownTypes() {
        val converter = Converters()
        assertNull(converter.stringToPresenceKinds(null))
        assertEquals(emptySet<MessageKind>(), converter.stringToPresenceKinds(""))
        assertEquals(setOf(MessageKind.JOIN), converter.stringToPresenceKinds("JOIN,PRIVMSG,UNKNOWN"))
        assertEquals("BACK,JOIN", converter.presenceKindsToString(setOf(MessageKind.JOIN, MessageKind.BACK)))
    }
}
