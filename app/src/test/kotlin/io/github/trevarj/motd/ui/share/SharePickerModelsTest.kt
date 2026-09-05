package io.github.trevarj.motd.ui.share

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePickerModelsTest {
    private fun row(
        id: Long,
        name: String,
        type: BufferType = BufferType.CHANNEL,
        archived: Boolean = false,
    ) = ChatListRow(
        bufferId = id,
        networkId = 1,
        networkName = "Libera",
        displayName = name,
        type = type,
        pinned = false,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = 0,
        mentionCount = 0,
        archived = archived,
    )

    private val rows =
        listOf(
            row(1, "#motd"),
            row(2, "Alice", BufferType.QUERY),
            row(3, "#archived", archived = true),
            row(4, "irc.example.org", BufferType.SERVER),
        )

    @Test fun blankQueryKeepsEveryEligibleRowInOrder() {
        assertEquals(listOf(1L, 2L), filterShareTargets(rows, "").map { it.bufferId })
        assertEquals(listOf(1L, 2L), filterShareTargets(rows, "   ").map { it.bufferId })
    }

    @Test fun archivedAndServerRowsAreNeverTargets() {
        val names = filterShareTargets(rows, "").map { it.displayName }
        assertEquals(listOf("#motd", "Alice"), names)
    }

    @Test fun queryMatchesCaseInsensitiveSubstring() {
        assertEquals(listOf(2L), filterShareTargets(rows, "ali").map { it.bufferId })
        assertEquals(listOf(2L), filterShareTargets(rows, "LIC").map { it.bufferId })
        assertEquals(listOf(1L), filterShareTargets(rows, "motd").map { it.bufferId })
    }

    @Test fun queryIsTrimmedBeforeMatching() {
        assertEquals(listOf(2L), filterShareTargets(rows, "  Alice  ").map { it.bufferId })
    }

    @Test fun noMatchYieldsNoTargets() {
        assertEquals(emptyList<Long>(), filterShareTargets(rows, "zzz").map { it.bufferId })
    }

    @Test fun sensitiveContextOnlyAcceptsValidAgentwireChannels() {
        val target =
            BufferEntity(
                id = 9,
                networkId = 1,
                name = "#agent",
                displayName = "#agent",
                type = BufferType.CHANNEL,
                topic = "agentwire:v1;account=controller;agent=host;backend=claude",
            )
        assertTrue(isAgentwireShareTarget(target))
        assertFalse(isAgentwireShareTarget(target.copy(topic = null)))
        assertFalse(isAgentwireShareTarget(target.copy(topic = "agentwire:v1;account=controller")))
        assertFalse(isAgentwireShareTarget(target.copy(type = BufferType.QUERY)))
        assertFalse(isAgentwireShareTarget(target.copy(archived = true)))
    }
}
