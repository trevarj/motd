package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.IgnoredAutoGroupPatternEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFoldersTest {
    @Test
    fun approvalThresholdDependsOnDestination() {
        assertFalse(canApproveAutoGroup(null, 1))
        assertTrue(canApproveAutoGroup(null, 2))
        assertTrue(canApproveAutoGroup(7, 1))
    }

    @Test
    fun presentationLetsPinsEscapeAndFoldersOverrideFriendFoolTiers() {
        val folder = ChatFolderEntity(id = 7, displayName = "Dev", normalizedName = "dev", ordering = 0, expanded = false)
        val pinned = row(1, "Alice", BufferType.QUERY, folderId = 7, pinned = true)
        val friend = row(2, "Bob", BufferType.QUERY, folderId = 7)
        val fool = row(3, "Mallory", BufferType.QUERY)

        val result = presentChatFolders(listOf(pinned, friend, fool), listOf(folder), setOf("bob"), setOf("mallory"), activeBufferId = 2)

        assertEquals(listOf(1L), result.pinned.map(ChatListRow::bufferId))
        assertEquals(
            listOf(2L),
            result.folders
                .single()
                .children
                .map(ChatListRow::bufferId),
        )
        assertTrue(result.folders.single().temporarilyExpanded)
        assertEquals(listOf(3L), result.remaining.fools.map(ChatListRow::bufferId))
    }

    @Test
    fun portalPartitionPreservesRawIdentityAndStoredAssignments() {
        val regular = row(1, "#motd")
        val pinnedPortal = row(2, "#discord.guild.pinned", pinned = true, dickord = "{broken")
        val folderPortal = row(3, "#DiScOrD.guild.foldered", folderId = 7)
        val archivedPortal = row(4, "#discord.guild.archived", archived = true)
        val control = row(5, "#DISCORD.CONTROL", folderId = 7)
        val queryLookalike = row(6, "#discord.not-a-channel", BufferType.QUERY)
        val rows = listOf(regular, pinnedPortal, folderPortal, archivedPortal, control, queryLookalike)

        val enabled = ordinaryChatListRows(rows, dickordEnabled = true)

        assertEquals(listOf(regular, control, queryLookalike), enabled)
        assertEquals(listOf("#motd", "#DISCORD.CONTROL", "#discord.not-a-channel"), enabled.map(ChatListRow::displayName))
        assertTrue(pinnedPortal.pinned)
        assertEquals(7L, folderPortal.folderId)
        assertTrue(archivedPortal.archived)
        assertEquals("{broken", pinnedPortal.dickordChannelJson)
        assertEquals(rows, ordinaryChatListRows(rows, dickordEnabled = false))
    }

    @Test
    fun tabsKeepStoredOrderDropEmptyScopedFoldersAndRetainPins() {
        val first = ChatFolderEntity(id = 7, displayName = "First", normalizedName = "first", ordering = 0)
        val second = ChatFolderEntity(id = 8, displayName = "Second", normalizedName = "second", ordering = 1)
        val empty = ChatFolderEntity(id = 9, displayName = "Empty", normalizedName = "empty", ordering = 2)
        val pinned = row(1, "#pinned", folderId = 8, pinned = true)
        val regular = row(2, "#regular", folderId = 7)
        val otherScope = row(3, "#other", network = 2, folderId = 9)

        val tabs = presentFolderTabs(listOf(pinned, regular), listOf(second, first, empty))

        assertEquals(listOf(8L, 7L), tabs.map { it.folder.id })
        assertEquals(listOf(1L), tabs.first().children.map(ChatListRow::bufferId))
        assertTrue(
            tabs
                .first()
                .children
                .single()
                .pinned,
        )
        assertFalse(tabs.first().temporarilyExpanded)
        assertEquals(
            listOf(7L, 8L, 9L),
            presentFolderTabs(listOf(regular, pinned, otherScope), listOf(first, second, empty))
                .map { it.folder.id },
        )
    }

    @Test
    fun collapsedSummaryUsesMutedPreviewButNotMutedBadges() {
        val mutedLatest = row(1, "#one", folderId = 7, muted = true, time = 20, unread = 9, mentions = 3)
        val older = row(2, "#two", folderId = 7, time = 10, unread = 2, incomplete = true)

        val summary = summarizeFolder(listOf(older, mutedLatest))

        assertEquals(2, summary.visibleCount)
        assertEquals("#one preview", summary.previewText)
        assertEquals("alice", summary.previewSender)
        assertEquals(2, summary.unreadCount)
        assertEquals(0, summary.mentionCount)
        assertTrue(summary.unreadIncomplete)
        assertFalse(summary.advertisedActivity)
    }

    @Test
    fun autoGroupMatchesRequiredBoundaryExamplesAndRejectsSubstrings() {
        val rows =
            listOf(
                row(1, "#discord.group.general", network = 1),
                row(2, "#discord.group.random", network = 1),
                row(3, "#systemcrafters", network = 2),
                row(4, "#systemcrafters-live", network = 2),
                row(5, "#unrelatedsystemcrafters", network = 2),
                row(6, "#systemcrafterz", network = 2),
            )

        val proposals = autoGroupProposals(rows)

        assertEquals("discord.group", proposals.first { it.networkId == 1L }.matchedPrefix)
        assertEquals("group", proposals.first { it.networkId == 1L }.suggestedName)
        val system = proposals.first { it.networkId == 2L }
        assertEquals("systemcrafters", system.matchedPrefix)
        assertEquals(setOf(3L, 4L), system.chats.mapTo(mutableSetOf(), ChatListRow::bufferId))
    }

    @Test
    fun autoGroupRequiresFourCharacterTerminalAndHonorsScopeAndIgnoredPrefix() {
        val rows =
            listOf(
                row(1, "#foo-one", network = 1),
                row(2, "#foo-two", network = 1),
                row(3, "#project-one", network = 2),
                row(4, "#project-two", network = 2),
            )

        assertTrue(autoGroupProposals(rows, networkId = 1).isEmpty())
        assertTrue(
            autoGroupProposals(
                rows,
                ignored = listOf(IgnoredAutoGroupPatternEntity(2, "project")),
                networkId = 2,
            ).isEmpty(),
        )
        assertEquals("project", autoGroupProposals(rows, networkId = 2).single().normalizedPrefix)
    }

    @Test
    fun autoGroupUsesDeepestNonOverlappingPrefixAndExcludesArchivedOrGrouped() {
        val rows =
            listOf(
                row(1, "#alpha.team.general"),
                row(2, "#alpha.team.random"),
                row(3, "#alpha.other.general"),
                row(4, "#alpha.other.random", archived = true),
                row(5, "#alpha.team.hidden", folderId = 9),
            )

        val proposal = autoGroupProposals(rows).single()

        assertEquals("alpha.team", proposal.normalizedPrefix)
        assertEquals(setOf(1L, 2L), proposal.chats.mapTo(mutableSetOf(), ChatListRow::bufferId))
    }

    private fun row(
        id: Long,
        name: String,
        type: BufferType = BufferType.CHANNEL,
        network: Long = 1,
        folderId: Long? = null,
        pinned: Boolean = false,
        muted: Boolean = false,
        archived: Boolean = false,
        time: Long = id,
        unread: Int = 0,
        mentions: Int = 0,
        incomplete: Boolean = false,
        dickord: String? = null,
    ) = ChatListRow(
        bufferId = id,
        networkId = network,
        networkName = "net$network",
        displayName = name,
        type = type,
        pinned = pinned,
        muted = muted,
        archived = archived,
        folderId = folderId,
        lastMessageText = "$name preview",
        lastMessageSender = "alice",
        lastMessageTime = time,
        unreadCount = unread,
        mentionCount = mentions,
        unreadCountIncomplete = incomplete,
        dickordChannelJson = dickord,
    )
}
