package io.github.trevarj.motd.ui.chatlist

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InvitationEventRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.dickord.DickordLabsPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListSectioningTest {
    @Test
    fun `invitation projection decodes typed payload and distinguishes handled state`() {
        fun event(state: InviteState) =
            InvitationEventRow(
                messageId = 7,
                bufferId = 8,
                networkId = 9,
                networkName = "libera",
                text = "alice invited you",
                eventPayload = InvitePayloadV1("alice", "me", "#secret").encode(),
                inviteState = state,
                serverTime = 10,
            )

        val pending = requireNotNull(toChatListInvitation(event(InviteState.PENDING)))
        val joined = requireNotNull(toChatListInvitation(event(InviteState.JOINED)))

        assertEquals("alice", pending.inviter)
        assertEquals("#secret", pending.channel)
        assertTrue(pending.actionable)
        assertFalse(joined.actionable)
        assertEquals(null, toChatListInvitation(event(InviteState.PENDING).copy(eventPayload = "broken")))
    }

    @Test
    fun `archive action icon matches the action direction`() {
        assertEquals(Icons.Outlined.Archive, archiveActionIcon(archiveMode = false))
        assertEquals(Icons.Outlined.Unarchive, archiveActionIcon(archiveMode = true))
    }

    private fun row(
        id: Long,
        name: String,
        type: BufferType = BufferType.QUERY,
        pinned: Boolean = false,
        networkId: Long = 1,
        caseMapping: String? = null,
        unreadCount: Int = 0,
        mentionCount: Int = 0,
    ) = ChatListRow(
        bufferId = id,
        networkId = networkId,
        networkName = "net",
        displayName = name,
        type = type,
        pinned = pinned,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = unreadCount,
        mentionCount = mentionCount,
        caseMapping = caseMapping,
    )

    @Test
    fun `archived rows are excluded before active sectioning`() {
        val active = row(id = 1, name = "#active", type = BufferType.CHANNEL)
        val archived = row(id = 2, name = "alice", type = BufferType.QUERY).copy(archived = true)

        val (activeRows, archivedRows) = partitionArchivedRows(listOf(active, archived))

        assertEquals(listOf(active), activeRows)
        assertEquals(listOf(archived), archivedRows)
        assertEquals(listOf(active), sectionChatList(activeRows, emptySet(), emptySet()).regular)
    }

    @Test
    fun `optimistic archive overrides move rows before room emits`() {
        val active = row(id = 1, name = "alice", type = BufferType.QUERY)
        val remaining = row(id = 2, name = "#active", type = BufferType.CHANNEL)

        val (activeRows, archivedRows) =
            partitionArchivedRows(
                applyArchiveOverrides(listOf(active, remaining), mapOf(active.bufferId to true)),
            )

        assertEquals(listOf(remaining), activeRows)
        assertEquals(listOf(active.copy(archived = true)), archivedRows)
    }

    @Test
    fun `optimistic unarchive overrides move rows before room emits`() {
        val archived = row(id = 1, name = "alice", type = BufferType.QUERY).copy(archived = true)
        val remainingArchived = row(id = 2, name = "#old", type = BufferType.CHANNEL).copy(archived = true)

        val (activeRows, archivedRows) =
            partitionArchivedRows(
                applyArchiveOverrides(listOf(archived, remainingArchived), mapOf(archived.bufferId to false)),
            )

        assertEquals(listOf(archived.copy(archived = false)), activeRows)
        assertEquals(listOf(remainingArchived), archivedRows)
    }

    @Test
    fun `archive overrides settle when room projection matches`() {
        val archived = row(id = 1, name = "alice", type = BufferType.QUERY).copy(archived = true)
        val active = row(id = 2, name = "bob", type = BufferType.QUERY)

        assertEquals(
            setOf(archived.bufferId),
            settledArchiveOverrideIds(
                rows = listOf(archived, active),
                overrides = mapOf(archived.bufferId to true, active.bufferId to true),
            ),
        )
    }

    @Test
    fun `archive folder remains reachable when active scope is empty`() {
        val archived = row(id = 2, name = "alice").copy(archived = true)

        assertTrue(shouldRenderChatList(archiveMode = false, activeRows = emptyList(), archivedRows = listOf(archived)))
        assertFalse(shouldRenderChatList(archiveMode = false, activeRows = emptyList(), archivedRows = emptyList()))
        assertTrue(shouldRenderChatList(archiveMode = true, activeRows = emptyList(), archivedRows = listOf(archived)))
        assertFalse(shouldRenderChatList(archiveMode = true, activeRows = emptyList(), archivedRows = emptyList()))
    }

    @Test
    fun `archive folder visibility preserves active archived-only and archive-mode routes`() {
        val active = row(id = 1, name = "#active", type = BufferType.CHANNEL)
        val archived = row(id = 2, name = "alice").copy(archived = true)

        assertFalse(
            shouldShowArchiveFolder(
                archiveMode = false,
                hasActiveRows = true,
                hasArchivedRows = true,
                pullRevealed = false,
            ),
        )
        assertTrue(
            shouldShowArchiveFolder(
                archiveMode = false,
                hasActiveRows = listOf(active).isNotEmpty(),
                hasArchivedRows = listOf(archived).isNotEmpty(),
                pullRevealed = true,
            ),
        )
        assertTrue(
            shouldShowArchiveFolder(
                archiveMode = false,
                hasActiveRows = false,
                hasArchivedRows = true,
                pullRevealed = false,
            ),
        )
        assertFalse(
            shouldShowArchiveFolder(
                archiveMode = true,
                hasActiveRows = true,
                hasArchivedRows = true,
                pullRevealed = true,
            ),
        )
    }

    private val pullGeometry = ArchiveFolderPullGeometry(rowPx = 56f)

    private fun reduce(
        state: ArchiveFolderPullState,
        event: ArchiveFolderPullEvent,
    ): ArchiveFolderPullResult = reduceArchiveFolderPull(state, event, pullGeometry)

    @Test
    fun `archive pull is one to one through row then damped and capped`() {
        val started = reduce(ArchiveFolderPullState(), ArchiveFolderPullEvent.StartGesture(0)).state
        val row = reduce(started, ArchiveFolderPullEvent.DragDelta(56f, 0, ArchiveFolderPullSource.USER_INPUT, true))
        assertEquals(56f, row.state.exposurePx, 0f)
        assertEquals(56f, row.consumedY, 0f)

        val capped = reduce(row.state, ArchiveFolderPullEvent.DragDelta(200f, 0, ArchiveFolderPullSource.USER_INPUT, true))
        assertEquals(72f, capped.state.exposurePx, 0f)
        assertEquals(80f, capped.consumedY, 0f)
    }

    @Test
    fun `dwell is continuous and stationary tick arms only after time and distance`() {
        val started = reduce(ArchiveFolderPullState(), ArchiveFolderPullEvent.StartGesture(0)).state
        val near = reduce(started, ArchiveFolderPullEvent.DragDelta(4f, 10, ArchiveFolderPullSource.USER_INPUT, true)).state
        assertEquals(10L, near.dwellStartedAtMs)
        val reset = reduce(near, ArchiveFolderPullEvent.DragDelta(-1f, 20, ArchiveFolderPullSource.USER_INPUT, true)).state
        assertEquals(null, reset.dwellStartedAtMs)
        val ready = reduce(reset, ArchiveFolderPullEvent.DragDelta(50f, 30, ArchiveFolderPullSource.USER_INPUT, true)).state
        assertEquals(30L, ready.dwellStartedAtMs)
        assertEquals(ArchiveFolderPullPhase.PULLING, reduce(ready, ArchiveFolderPullEvent.Tick(229)).state.phase)
        val armed = reduce(ready, ArchiveFolderPullEvent.Tick(230))
        assertEquals(ArchiveFolderPullPhase.ARMED, armed.state.phase)
        assertEquals(listOf(ArchiveFolderPullEffect.HapticThresholdActivated), armed.effects)
    }

    @Test
    fun `distance before time and time before distance both require both thresholds`() {
        val started = reduce(ArchiveFolderPullState(), ArchiveFolderPullEvent.StartGesture(0)).state
        val far = reduce(started, ArchiveFolderPullEvent.DragDelta(56f, 0, ArchiveFolderPullSource.USER_INPUT, true)).state
        assertEquals(ArchiveFolderPullPhase.PULLING, reduce(far, ArchiveFolderPullEvent.Tick(199)).state.phase)
        assertEquals(ArchiveFolderPullPhase.ARMED, reduce(far, ArchiveFolderPullEvent.Tick(200)).state.phase)

        val slow = reduce(started, ArchiveFolderPullEvent.DragDelta(4f, 0, ArchiveFolderPullSource.USER_INPUT, true)).state
        val waited = reduce(slow, ArchiveFolderPullEvent.Tick(300)).state
        assertEquals(ArchiveFolderPullPhase.PULLING, waited.phase)
        assertEquals(ArchiveFolderPullPhase.ARMED, reduce(waited, ArchiveFolderPullEvent.DragDelta(44f, 300, ArchiveFolderPullSource.USER_INPUT, true)).state.phase)
    }

    @Test
    fun `armed hysteresis and haptic latch survive a disarm rearm`() {
        val started = reduce(ArchiveFolderPullState(), ArchiveFolderPullEvent.StartGesture(0)).state
        val pulled = reduce(started, ArchiveFolderPullEvent.DragDelta(56f, 0, ArchiveFolderPullSource.USER_INPUT, true)).state
        val armed = reduce(pulled, ArchiveFolderPullEvent.Tick(200)).state
        assertEquals(ArchiveFolderPullPhase.ARMED, armed.phase)
        val disarmed = reduce(armed, ArchiveFolderPullEvent.DragDelta(-17f, 201, ArchiveFolderPullSource.USER_INPUT, true)).state
        assertEquals(ArchiveFolderPullPhase.PULLING, disarmed.phase)
        val rearmed = reduce(disarmed, ArchiveFolderPullEvent.DragDelta(17f, 202, ArchiveFolderPullSource.USER_INPUT, true))
        assertEquals(ArchiveFolderPullPhase.ARMED, rearmed.state.phase)
        assertTrue(rearmed.effects.isEmpty())
    }

    @Test
    fun `release commits only armed and cancel never commits`() {
        val started = reduce(ArchiveFolderPullState(), ArchiveFolderPullEvent.StartGesture(0)).state
        val pulling = reduce(started, ArchiveFolderPullEvent.DragDelta(56f, 0, ArchiveFolderPullSource.USER_INPUT, true)).state
        assertEquals(ArchiveFolderPullPhase.HIDDEN, reduce(pulling, ArchiveFolderPullEvent.Release(100)).state.phase)

        val far = reduce(started, ArchiveFolderPullEvent.DragDelta(56f, 0, ArchiveFolderPullSource.USER_INPUT, true)).state
        val armed = reduce(far, ArchiveFolderPullEvent.Tick(200)).state
        val revealed = reduce(armed, ArchiveFolderPullEvent.Release(201))
        assertEquals(ArchiveFolderPullPhase.REVEALED, revealed.state.phase)
        assertEquals(listOf(ArchiveFolderPullEffect.AnnounceShown), revealed.effects)
        assertEquals(ArchiveFolderPullPhase.HIDDEN, reduce(armed, ArchiveFolderPullEvent.Cancel).state.phase)
    }

    @Test
    fun `non user input and invalid geometry are inert`() {
        val started = reduce(ArchiveFolderPullState(), ArchiveFolderPullEvent.StartGesture(0)).state
        assertEquals(
            started,
            reduce(started, ArchiveFolderPullEvent.DragDelta(100f, 0, ArchiveFolderPullSource.NON_USER_INPUT, true)).state,
        )
        assertEquals(
            started,
            reduce(started, ArchiveFolderPullEvent.DragDelta(100f, 0, ArchiveFolderPullSource.USER_INPUT, false)).state,
        )
        val invalid =
            reduceArchiveFolderPull(
                ArchiveFolderPullState(exposurePx = Float.NaN),
                ArchiveFolderPullEvent.DragDelta(10f, 0, ArchiveFolderPullSource.USER_INPUT, true),
                ArchiveFolderPullGeometry(Float.NaN),
            )
        assertEquals(ArchiveFolderPullState(), invalid.state)
        assertEquals(0f, invalid.consumedY, 0f)
    }

    @Test
    fun `revealed row collapse resets and announces hidden`() {
        val revealed = ArchiveFolderPullState(exposurePx = 56f, phase = ArchiveFolderPullPhase.REVEALED)
        val hidden = reduce(revealed, ArchiveFolderPullEvent.RevealedRowHidden)
        assertEquals(ArchiveFolderPullPhase.HIDDEN, hidden.state.phase)
        assertEquals(listOf(ArchiveFolderPullEffect.AnnounceHidden), hidden.effects)
    }

    @Test
    fun `revealed archive folder scrolls with chats without changing list content`() {
        val partiallyHidden = scrollRevealedArchiveFolder(56f, -20f, pullGeometry)
        assertEquals(36f, partiallyHidden.exposurePx, 0f)
        assertEquals(-20f, partiallyHidden.consumedY, 0f)
        assertFalse(partiallyHidden.hidden)

        val restored = scrollRevealedArchiveFolder(36f, 30f, pullGeometry)
        assertEquals(56f, restored.exposurePx, 0f)
        assertEquals(20f, restored.consumedY, 0f)
        assertFalse(restored.hidden)

        val hidden = scrollRevealedArchiveFolder(10f, -30f, pullGeometry)
        assertEquals(0f, hidden.exposurePx, 0f)
        assertEquals(-10f, hidden.consumedY, 0f)
        assertTrue(hidden.hidden)
    }

    @Test
    fun `hint alpha and settle target are bounded`() {
        assertEquals(0f, archiveFolderPullHintAlpha(16f, pullGeometry), 0f)
        assertEquals(1f, archiveFolderPullHintAlpha(56f, pullGeometry), 0f)
        assertEquals(0f, archiveFolderPullSettleTarget(ArchiveFolderPullState(), pullGeometry), 0f)
        assertEquals(
            56f,
            archiveFolderPullSettleTarget(ArchiveFolderPullState(56f, ArchiveFolderPullPhase.REVEALED), pullGeometry),
            0f,
        )
    }

    @Test
    fun `classifies queries into friends and fools, regular otherwise`() {
        val rows =
            listOf(
                row(1, "alice"),
                row(2, "bob"),
                row(3, "carol"),
            )
        val s = sectionChatList(rows, friends = setOf("alice"), fools = setOf("bob"))
        assertEquals(listOf(1L), s.friends.map { it.bufferId })
        assertEquals(listOf(2L), s.fools.map { it.bufferId })
        assertEquals(listOf(3L), s.regular.map { it.bufferId })
    }

    @Test
    fun `channels never classify even if name matches a friend or fool`() {
        val rows =
            listOf(
                row(1, "#alice", type = BufferType.CHANNEL),
                row(2, "#bob", type = BufferType.CHANNEL),
            )
        val s = sectionChatList(rows, friends = setOf("#alice"), fools = setOf("#bob"))
        assertEquals(emptyList<Long>(), s.friends.map { it.bufferId })
        assertEquals(emptyList<Long>(), s.fools.map { it.bufferId })
        assertEquals(listOf(1L, 2L), s.regular.map { it.bufferId })
    }

    @Test
    fun `pinned rows override friend and fool tiers while preserving friend membership`() {
        val rows =
            listOf(
                row(1, "alice", pinned = true),
                row(2, "bob", pinned = true),
                row(3, "carol"),
            )
        val s = sectionChatList(rows, friends = setOf("alice", "carol"), fools = setOf("bob"))
        assertEquals(listOf(1L, 2L), s.pinned.map { it.bufferId })
        assertEquals(listOf(3L), s.friends.map { it.bufferId })
        assertEquals(emptyList<Long>(), s.fools.map { it.bufferId })
        assertTrue(isFriendQuery(s.pinned.single { it.bufferId == 1L }, setOf("alice", "carol")))
    }

    @Test
    fun `classification is case-insensitive via normalizeNick`() {
        val s = sectionChatList(listOf(row(1, "Alice")), friends = setOf("alice"), fools = emptySet())
        assertEquals(listOf(1L), s.friends.map { it.bufferId })
    }

    @Test
    fun `tiers preserve activity order and have global priority`() {
        // Input is descending activity. Tiering may move a row ahead of a newer lower-priority
        // row, but must never reorder two rows that remain in the same tier.
        val rows =
            listOf(
                row(10, "pinned-regular", pinned = true),
                row(11, "alice", pinned = true),
                row(12, "bob", pinned = true),
                row(20, "regular-newer"),
                row(21, "carol"),
                row(22, "regular-older"),
                row(23, "dave"),
                row(24, "eve"),
            )
        val s =
            sectionChatList(
                rows,
                friends = setOf("alice", "carol", "dave"),
                fools = setOf("bob", "eve"),
            )

        assertEquals(listOf(10L, 11L, 12L), s.pinned.map { it.bufferId })
        assertEquals(listOf(21L, 23L), s.friends.map { it.bufferId })
        assertEquals(listOf(20L, 22L), s.regular.map { it.bufferId })
        assertEquals(listOf(24L), s.fools.map { it.bufferId })
        assertEquals(
            listOf(10L, 11L, 12L, 21L, 23L, 20L, 22L, 24L),
            (s.pinned + s.friends + s.regular + s.fools).map { it.bufferId },
        )
    }

    @Test
    fun `empty friends and fools leaves everything regular`() {
        val rows = listOf(row(1, "alice"), row(2, "#chan", type = BufferType.CHANNEL))
        val s = sectionChatList(rows, friends = emptySet(), fools = emptySet())
        assertEquals(listOf(1L, 2L), s.regular.map { it.bufferId })
        assertEquals(emptyList<Long>(), s.friends + s.fools)
    }

    @Test
    fun `recent header appears only after pinned or friend rows`() {
        assertFalse(
            sectionChatList(
                rows = listOf(row(1, "regular")),
                friends = emptySet(),
                fools = emptySet(),
            ).showRecentHeader,
        )
        assertTrue(
            sectionChatList(
                rows = listOf(row(1, "pinned", pinned = true), row(2, "regular")),
                friends = emptySet(),
                fools = emptySet(),
            ).showRecentHeader,
        )
        assertTrue(
            sectionChatList(
                rows = listOf(row(1, "alice"), row(2, "regular")),
                friends = setOf("alice"),
                fools = emptySet(),
            ).showRecentHeader,
        )
        assertFalse(
            sectionChatList(
                rows = listOf(row(1, "alice")),
                friends = setOf("alice"),
                fools = emptySet(),
            ).showRecentHeader,
        )
    }

    @Test
    fun `sectioning applies each rows casemapping conservatively and keeps tier order`() {
        val rows =
            listOf(
                row(1, "friend^", networkId = 1, caseMapping = "rfc1459-strict"),
                row(2, "friend^", networkId = 2, caseMapping = "rfc1459"),
                row(3, "{helper}", networkId = 3, caseMapping = "vendor-unicode"),
                row(4, "[helper]", networkId = 3, caseMapping = "vendor-unicode"),
            )

        val sections =
            sectionChatList(
                rows,
                friends = linkedSetOf("friend~", "[helper]"),
                fools = emptySet(),
            )

        assertEquals(listOf(2L, 4L), sections.friends.map { it.bufferId })
        assertEquals(listOf(1L, 3L), sections.regular.map { it.bufferId })
    }

    @Test
    fun `activity above viewport follows rendered section indices`() {
        val sections =
            sectionChatList(
                rows =
                    listOf(
                        row(1, "pinned", pinned = true, unreadCount = 2),
                        row(2, "friend", unreadCount = 3),
                        row(3, "regular-new", unreadCount = 4),
                        row(4, "regular-old", mentionCount = 2),
                        row(5, "fool", unreadCount = 5),
                    ),
                friends = setOf("friend"),
                fools = setOf("fool"),
            )

        assertEquals(0, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = false, 0))
        assertEquals(2, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = false, 1))
        assertEquals(2, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = false, 2))
        assertEquals(5, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = false, 3))
        assertEquals(5, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = false, 4))
        assertEquals(9, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = false, 5))
        assertEquals(11, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = false, 7))
        assertEquals(16, unreadActivityBeforeDisplayIndex(sections, foolsExpanded = true, 8))
    }

    private fun invitation(
        id: Long,
        state: InviteState = InviteState.PENDING,
    ) = ChatListInvitation(
        messageId = id,
        bufferId = 1,
        networkId = 1,
        networkName = "net",
        inviter = "alice",
        channel = "#chan",
        text = "invite",
        state = state,
        serverTime = id,
    )

    @Test
    fun `top item key mirrors the lazy column emit order`() {
        val pinned = row(id = 1, name = "#pinned", type = BufferType.CHANNEL, pinned = true)
        val friend = row(id = 2, name = "ally")
        val regular = row(id = 3, name = "#chan", type = BufferType.CHANNEL)
        val fool = row(id = 4, name = "troll")

        fun sections(rows: List<ChatListRow>) = sectionChatList(rows, setOf("ally"), setOf("troll"))
        val all = sections(listOf(pinned, friend, regular, fool))
        val folder =
            PresentedChatFolder(
                folder = ChatFolderEntity(id = 7, displayName = "Real", normalizedName = "real", ordering = 0),
                children = listOf(regular),
                summary = summarizeFolder(listOf(regular)),
                temporarilyExpanded = false,
            )

        assertEquals(1L, chatListTopItemKey(false, emptyList(), 0, all))
        assertEquals("friends-header", chatListTopItemKey(false, emptyList(), 0, sections(listOf(friend, regular, fool))))
        assertEquals(3L, chatListTopItemKey(false, emptyList(), 0, sections(listOf(regular, fool))))
        assertEquals("fools-header", chatListTopItemKey(false, emptyList(), 0, sections(listOf(fool))))
        assertEquals(null, chatListTopItemKey(false, emptyList(), 0, sections(emptyList())))
        assertEquals("folder-7", chatListTopItemKey(false, emptyList(), 0, sections(listOf(friend, regular, fool)), listOf(folder)))
        assertEquals(1L, chatListTopItemKey(false, emptyList(), 0, all, listOf(folder)))
        // An actionable invitations folder leads every chat tier.
        assertEquals("invitations-folder", chatListTopItemKey(false, listOf(invitation(7)), 1, all, listOf(folder)))
        // Invitation mode presents invitation rows (or the empty state), never chat rows.
        assertEquals("invitation-7", chatListTopItemKey(true, listOf(invitation(7)), 1, all, listOf(folder)))
        assertEquals("invitations-empty", chatListTopItemKey(true, emptyList(), 0, all, listOf(folder)))
    }

    @Test
    fun `repin fires only for a changed top key while resting at the true top`() {
        assertTrue(shouldRepinChatListTop(1L, 2L, canScrollBackward = false, scrollInProgress = false))
        // Same top row: nothing was displaced.
        assertFalse(shouldRepinChatListTop(1L, 1L, canScrollBackward = false, scrollInProgress = false))
        // Away from the top the key anchor is the behavior the user wants.
        assertFalse(shouldRepinChatListTop(1L, 2L, canScrollBackward = true, scrollInProgress = false))
        // A repin cancels scrolls, so never fire during one.
        assertFalse(shouldRepinChatListTop(1L, 2L, canScrollBackward = false, scrollInProgress = true))
        // The list first appearing or emptying displaces nothing.
        assertFalse(shouldRepinChatListTop(null, 2L, canScrollBackward = false, scrollInProgress = false))
        assertFalse(shouldRepinChatListTop(1L, null, canScrollBackward = false, scrollInProgress = false))
    }
}

internal fun fakeDickordLabsPrefs(enabledFlow: Flow<Boolean> = flowOf(false)) =
    object : DickordLabsPrefs(ApplicationProvider.getApplicationContext<Context>()) {
        override val enabled = enabledFlow
    }
