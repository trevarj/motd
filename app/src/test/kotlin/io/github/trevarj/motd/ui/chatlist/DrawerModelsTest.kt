package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerModelsTest {

    private fun net(
        id: Long,
        name: String = "net$id",
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
    ) = NetworkEntity(
        id = id, name = name, role = role, parentId = parentId,
        host = "h", port = 6697, nick = "me", username = "me", realname = "Me",
    )

    private fun row(
        id: Long,
        networkId: Long,
        muted: Boolean = false,
        unread: Int = 0,
        mentions: Int = 0,
        type: BufferType = BufferType.CHANNEL,
    ) = ChatListRow(
        bufferId = id, networkId = networkId, networkName = "net",
        displayName = "#c$id", type = type, pinned = false, muted = muted,
        lastMessageText = null, lastMessageSender = null, lastMessageTime = null,
        unreadCount = unread, mentionCount = mentions,
    )

    @Test
    fun `absent state maps to Disconnected and nick null`() {
        val rows = buildDrawerRows(listOf(net(1)), emptyList(), emptyMap())
        assertEquals(1, rows.size)
        assertEquals(IrcClientState.Disconnected, rows[0].state)
        assertNull(rows[0].nick)
    }

    @Test
    fun `ready state exposes nick`() {
        val states = mapOf(1L to IrcClientState.Ready("neo", emptySet(), emptyMap()))
        val rows = buildDrawerRows(listOf(net(1)), emptyList(), states)
        assertEquals("neo", rows[0].nick)
    }

    @Test
    fun `muted rows excluded from unread but mentions still count`() {
        val rows = listOf(
            row(1, networkId = 1, muted = false, unread = 3, mentions = 1),
            row(2, networkId = 1, muted = true, unread = 5, mentions = 2),
        )
        val drawer = buildDrawerRows(listOf(net(1)), rows, emptyMap())
        assertEquals(3, drawer[0].unread) // muted row's 5 unread dropped
        assertEquals(3, drawer[0].mentions) // muted row's 2 mentions still count
    }

    @Test
    fun `bouncer root aggregates children counts and children follow indented`() {
        val networks = listOf(
            net(1, "soju", NetworkRole.BOUNCER_ROOT),
            net(2, "OFTC", NetworkRole.BOUNCER_CHILD, parentId = 1),
            net(3, "Rizon", NetworkRole.BOUNCER_CHILD, parentId = 1),
        )
        val rows = listOf(
            row(10, networkId = 1, unread = 1, mentions = 0), // root's own buffer
            row(20, networkId = 2, unread = 4, mentions = 1),
            row(30, networkId = 3, unread = 2, mentions = 3),
        )
        val drawer = buildDrawerRows(networks, rows, emptyMap())

        // Order: root, then its children in DB order.
        assertEquals(listOf(1L, 2L, 3L), drawer.map { it.networkId })
        assertEquals(listOf(0, 1, 1), drawer.map { it.depth })

        // Root aggregates own(1) + child unread(4+2)=7; mentions own(0) + (1+3)=4.
        assertEquals(7, drawer[0].unread)
        assertEquals(4, drawer[0].mentions)
        // Children keep their own counts.
        assertEquals(4, drawer[1].unread)
        assertEquals(2, drawer[2].unread)
    }

    @Test
    fun `direct networks stay flat in db order`() {
        val networks = listOf(net(1, "A"), net(2, "B"))
        val drawer = buildDrawerRows(networks, emptyList(), emptyMap())
        assertEquals(listOf(1L, 2L), drawer.map { it.networkId })
        assertTrue(drawer.all { it.depth == 0 })
    }

    // -- scopeRows --

    @Test
    fun `null selection is identity`() {
        val rows = listOf(row(1, networkId = 1), row(2, networkId = 2))
        assertEquals(rows, scopeRows(rows, null, listOf(net(1), net(2))))
    }

    @Test
    fun `direct id filters to that network`() {
        val rows = listOf(row(1, networkId = 1), row(2, networkId = 2))
        val out = scopeRows(rows, 1, listOf(net(1), net(2)))
        assertEquals(listOf(1L), out.map { it.bufferId })
    }

    @Test
    fun `root id includes its children rows`() {
        val networks = listOf(
            net(1, "soju", NetworkRole.BOUNCER_ROOT),
            net(2, "OFTC", NetworkRole.BOUNCER_CHILD, parentId = 1),
        )
        val rows = listOf(
            row(1, networkId = 1),
            row(2, networkId = 2),
            row(3, networkId = 9), // unrelated network
        )
        val out = scopeRows(rows, 1, networks)
        assertEquals(listOf(1L, 2L), out.map { it.bufferId })
    }

    @Test
    fun `mark all selection includes muted unread and excludes server and read rows`() {
        val rows = listOf(
            row(1, networkId = 1, unread = 2),
            row(2, networkId = 1, muted = true, unread = 4),
            row(3, networkId = 1, unread = 0),
            row(4, networkId = 1, unread = 7, type = BufferType.SERVER),
        )

        assertEquals(listOf(1L, 2L), unreadBufferIds(rows))
        assertEquals(6, ChatListState(rows = rows).scopedUnreadCount)
    }
}
