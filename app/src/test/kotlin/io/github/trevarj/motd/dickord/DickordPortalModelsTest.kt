package io.github.trevarj.motd.dickord

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DickordPortalModelsTest {
    @Test
    fun `same-name guilds stay separate and stored organization does not hide rows`() {
        val rows =
            listOf(
                row(10, networkId = 1, rawName = "#discord.one.release_2Enotes", folderId = 90, descriptor = guild("100", "Example", "110", "release.notes_20")),
                row(20, networkId = 2, rawName = "#discord.two.general", pinned = true, descriptor = guild("200", "Example", "210", "general")),
            )

        val groups = presentDickordPortal(rows, archived = false)

        assertEquals(listOf("dms", "guild:1:100", "guild:2:200"), groups.map { it.key })
        assertEquals(setOf(10L, 20L), groups.flatMap { it.conversations }.mapTo(mutableSetOf()) { it.row.bufferId })
        assertEquals(
            90L,
            groups[1]
                .conversations
                .single()
                .row.folderId,
        )
        assertTrue(
            groups[2]
                .conversations
                .single()
                .row.pinned,
        )
        assertEquals(
            "#discord.one.release_2Enotes",
            groups[1]
                .conversations
                .single()
                .row.displayName,
        )
    }

    @Test
    fun `active and archived presentations are disjoint and DMs always lead`() {
        val active = row(1, descriptor = dm("11", "Alice", "https://cdn.discordapp.com/avatars/7/icon.png"))
        val archived = row(2, archived = true, descriptor = dm("12", "Bob"))

        val activeGroups = presentDickordPortal(listOf(active, archived), archived = false)
        val archivedGroups = presentDickordPortal(listOf(active, archived), archived = true)

        assertEquals(DICKORD_PORTAL_DMS_KEY, activeGroups.first().key)
        assertEquals(DICKORD_PORTAL_DMS_KEY, archivedGroups.first().key)
        assertEquals(listOf(1L), activeGroups.flatMap { it.conversations }.map { it.row.bufferId })
        assertEquals(
            "https://cdn.discordapp.com/avatars/7/icon.png",
            activeGroups
                .first()
                .conversations
                .single()
                .descriptor
                ?.channelIconUrl,
        )
        assertEquals(listOf(2L), archivedGroups.flatMap { it.conversations }.map { it.row.bufferId })
        assertTrue(presentDickordPortal(emptyList(), archived = false).first().conversations.isEmpty())
    }

    @Test
    fun `old descriptors keep their real group while undecodable rows remain pending`() {
        val old = row(7, descriptor = guild("100", "Server", "101", channelName = null))
        val missing = row(8, descriptor = null)
        val invalid = row(9, descriptor = "not-json")

        val groups = presentDickordPortal(listOf(missing, old, invalid), archived = false)
        val server = groups.single { it.key == "guild:1:100" }
        val pending = groups.single { it.key == DICKORD_PORTAL_PENDING_KEY }

        assertEquals(
            7L,
            server.conversations
                .single()
                .row.bufferId,
        )
        assertNull(
            server.conversations
                .single()
                .descriptor
                ?.channelName,
        )
        assertEquals(listOf(8L, 9L), pending.conversations.map { it.row.bufferId })
        assertTrue(pending.conversations.all { it.descriptor == null })
    }

    @Test
    fun `channel and DM ordering is deterministic`() {
        val rows =
            listOf(
                row(1, lastMessageTime = 300, descriptor = dm("11", "Recent")),
                row(2, pinned = true, lastMessageTime = 10, descriptor = dm("12", "Pinned")),
                row(3, lastMessageTime = 100, descriptor = dm("13", "Older")),
                row(30, descriptor = guild("100", "Server", "130", "zeta")),
                row(20, descriptor = guild("100", "Server", "120", "Alpha")),
                row(10, pinned = true, descriptor = guild("100", "Server", "110", "later")),
                row(15, descriptor = guild("100", "Server", "115", "alpha")),
            )

        val groups = presentDickordPortal(rows, archived = false)

        assertEquals(listOf(2L, 1L, 3L), groups.first().conversations.map { it.row.bufferId })
        assertEquals(listOf(10L, 15L, 20L, 30L), groups.single { it.guildId == "100" }.conversations.map { it.row.bufferId })
    }

    @Test
    fun `first current descriptor by channel id owns the server name and explicit null icon`() {
        val old = guild("100", "Old name", "10", channelName = null, iconUrl = "https://example.com/old.png")
        val firstCurrent = guild("100", "Current name", "20", "general", iconUrl = null)
        val laterCurrent = guild("100", "Later name", "30", "random", iconUrl = "https://example.com/later.png")

        val group =
            presentDickordPortal(
                listOf(row(1, descriptor = laterCurrent), row(2, descriptor = old), row(3, descriptor = firstCurrent)),
                archived = false,
            ).single { it.guildId == "100" }

        assertEquals("Current name", group.displayName)
        assertNull(group.iconUrl)
    }

    private fun row(
        id: Long,
        networkId: Long = 1,
        rawName: String = "#discord.target.$id",
        pinned: Boolean = false,
        archived: Boolean = false,
        folderId: Long? = null,
        lastMessageTime: Long? = null,
        descriptor: String?,
    ) = ChatListRow(
        bufferId = id,
        networkId = networkId,
        networkName = "Network $networkId",
        displayName = rawName,
        type = BufferType.CHANNEL,
        pinned = pinned,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = lastMessageTime,
        unreadCount = 0,
        mentionCount = 0,
        archived = archived,
        folderId = folderId,
        dickordChannelJson = descriptor,
    )

    private fun guild(
        guildId: String,
        guildName: String,
        channelId: String,
        channelName: String?,
        iconUrl: String? = null,
    ): String =
        Json.encodeToString(
            DickordChannelDescriptor(
                v = 1,
                guildId = guildId,
                guildName = guildName,
                channelId = channelId,
                channelType = 0,
                parentId = null,
                channelName = channelName,
                guildIconUrl = iconUrl,
            ),
        )

    private fun dm(
        channelId: String,
        channelName: String,
        iconUrl: String? = null,
    ): String =
        Json.encodeToString(
            DickordChannelDescriptor(
                v = 1,
                guildId = null,
                guildName = null,
                channelId = channelId,
                channelType = 1,
                parentId = null,
                channelName = channelName,
                channelIconUrl = iconUrl,
            ),
        )
}
