package io.github.trevarj.motd.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A bouncer root must remove its local child mirrors, not leave their channels in the chat list. */
@RunWith(RobolectricTestRunner::class)
class DeleteNetworkTreeDaoTest {
    private lateinit var db: MotdDatabase

    @Before
    fun setUp() {
        db = inMemoryDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleteLocalTree_removesRootChildrenAndAllLocalContent() = runTest {
        val networks = db.networkDao()
        val buffers = db.bufferDao()
        val messages = db.messageDao()
        val members = db.memberDao()
        val reactions = db.reactionDao()
        val users = db.userDao()
        val rootId = networks.insert(network("bouncer").copy(role = NetworkRole.BOUNCER_ROOT))
        val childOne = networks.insert(network("libera").copy(
            role = NetworkRole.BOUNCER_CHILD,
            parentId = rootId,
            bouncerNetId = "libera",
        ))
        val childTwo = networks.insert(network("oftc").copy(
            role = NetworkRole.BOUNCER_CHILD,
            parentId = rootId,
            bouncerNetId = "oftc",
        ))
        val unrelated = networks.insert(network("unrelated"))
        val rootBuffer = buffers.insert(buffer(rootId, "#root"))
        val childBuffer = buffers.insert(buffer(childOne, "#libera"))
        val childTwoBuffer = buffers.insert(buffer(childTwo, "#oftc"))
        val survivor = buffers.insert(buffer(unrelated, "#keep"))

        messages.insertAll(listOf(
            message(rootBuffer, "root", serverTime = 1, dedupKey = "root", msgid = "root"),
            message(childBuffer, "child", serverTime = 2, dedupKey = "child", msgid = "child"),
            message(childTwoBuffer, "child-two", serverTime = 3, dedupKey = "child-two", msgid = "child-two"),
            message(survivor, "keep", serverTime = 4, dedupKey = "keep", msgid = "keep"),
        ))
        members.upsert(MemberEntity(childBuffer, "alice"))
        reactions.upsert(ReactionEntity(
            bufferId = childBuffer,
            targetMsgid = "child",
            actorKey = "nick:alice",
            sender = "alice",
            emoji = "👍",
            serverTime = 2,
        ))
        users.upsert(UserEntity(childOne, "alice"))
        users.upsert(UserEntity(unrelated, "bob"))

        val deleted = networks.deleteLocalTree(rootId)

        assertEquals(setOf(rootId, childOne, childTwo), deleted.toSet())
        assertNull(networks.byId(rootId))
        assertNull(networks.byId(childOne))
        assertNull(networks.byId(childTwo))
        assertEquals(unrelated, networks.byId(unrelated)?.id)
        assertNull(buffers.observeById(rootBuffer))
        assertNull(buffers.observeById(childBuffer))
        assertNull(buffers.observeById(childTwoBuffer))
        assertNull(messages.byMsgid(childBuffer, "child"))
        assertTrue(members.observe(childBuffer).first().isEmpty())
        assertTrue(reactions.observeForBuffer(childBuffer).first().isEmpty())
        assertNull(users.byNick(childOne, "alice"))
        assertEquals("bob", users.byNick(unrelated, "bob")?.nick)
        assertEquals(listOf("#keep"), buffers.observeChatList().first().map { it.displayName })
    }
}
