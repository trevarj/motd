package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

/**
 * Chat-list priority sectioning. Pure and unit-tested.
 *
 * Pinned rows always lead the list, regardless of friend/fool membership. The remaining rows are
 * ordered as friends, regular chats, then fools. Only [BufferType.QUERY] rows classify by nick;
 * channels are never friends/fools. Input is already activity-ordered by the query, so each tier
 * preserves its input order.
 *
 * Note: QUERY `displayName` is the nick today (`ensureQueryBuffer`); if display renaming ever
 * lands, classification should switch to the underlying buffer name.
 */
data class ChatListSections(
    val pinned: List<ChatListRow>,
    val friends: List<ChatListRow>,
    val regular: List<ChatListRow>,
    val fools: List<ChatListRow>,
) {
    /** A label is useful only when regular rows follow an earlier visible priority tier. */
    val showRecentHeader: Boolean
        get() = regular.isNotEmpty() && (pinned.isNotEmpty() || friends.isNotEmpty())
}

/** Splits the already-scoped projection before badges, drawer rollups, or sectioning run. */
internal fun partitionArchivedRows(rows: List<ChatListRow>): Pair<List<ChatListRow>, List<ChatListRow>> = rows.filterNot(ChatListRow::archived) to rows.filter(ChatListRow::archived)

/** Active empty state must not hide the only route to an existing archived folder. */
internal fun shouldRenderChatList(
    archiveMode: Boolean,
    activeRows: List<ChatListRow>,
    archivedRows: List<ChatListRow>,
): Boolean =
    if (archiveMode) {
        archivedRows.isNotEmpty()
    } else {
        activeRows.isNotEmpty() || archivedRows.isNotEmpty()
    }

/** The folder is hidden above active chats until pull-revealed, but remains the only-row route. */
internal fun shouldShowArchiveFolder(
    archiveMode: Boolean,
    hasActiveRows: Boolean,
    hasArchivedRows: Boolean,
    pullRevealed: Boolean,
): Boolean = !archiveMode && hasArchivedRows && (!hasActiveRows || pullRevealed)

/** Fixed Telegram-style archive pull measurements expressed as row-relative geometry. */
internal object ArchiveFolderPull {
    const val RowDp = 56f
    const val HintStartDp = 16f
    const val ArmRatio = .85f
    const val DisarmRatio = .70f
    const val HoldMillis = 1200L
    const val BeyondRowResistance = .2f
    const val ExtraDp = 16f
}

/** Pixel geometry supplied by Compose; invalid geometry makes the reducer safely inert. */
internal data class ArchiveFolderPullGeometry(
    val rowPx: Float,
) {
    val isValid: Boolean get() = rowPx.isFinite() && rowPx > 0f
    val hintStartPx: Float get() = rowPx * (ArchiveFolderPull.HintStartDp / ArchiveFolderPull.RowDp)
    val armPx: Float get() = rowPx * ArchiveFolderPull.ArmRatio
    val disarmPx: Float get() = rowPx * ArchiveFolderPull.DisarmRatio
    val maxExposurePx: Float get() = rowPx * ((ArchiveFolderPull.RowDp + ArchiveFolderPull.ExtraDp) / ArchiveFolderPull.RowDp)
}

internal enum class ArchiveFolderPullPhase { HIDDEN, PULLING, ARMED, REVEALED }

internal enum class ArchiveFolderPullSource { USER_INPUT, NON_USER_INPUT }

/** Transient gesture truth. Revealed is intentionally local UI state, not persisted state. */
internal data class ArchiveFolderPullState(
    val exposurePx: Float = 0f,
    val phase: ArchiveFolderPullPhase = ArchiveFolderPullPhase.HIDDEN,
    val gestureActive: Boolean = false,
    val gestureId: Long = 0L,
    val holdStartedAtMs: Long? = null,
    val hapticEmitted: Boolean = false,
    val gestureStartedRevealed: Boolean = false,
)

internal sealed interface ArchiveFolderPullEvent {
    data class StartGesture(
        val timestampMs: Long,
    ) : ArchiveFolderPullEvent

    data class DragDelta(
        val deltaY: Float,
        val timestampMs: Long,
        val source: ArchiveFolderPullSource,
        val listAtTop: Boolean,
    ) : ArchiveFolderPullEvent

    data class Tick(
        val timestampMs: Long,
        val gestureId: Long,
    ) : ArchiveFolderPullEvent

    data class Release(
        val timestampMs: Long,
    ) : ArchiveFolderPullEvent

    data object Cancel : ArchiveFolderPullEvent

    data object RevealedRowHidden : ArchiveFolderPullEvent

    data object RevealAccessibilityAction : ArchiveFolderPullEvent

    data object Reset : ArchiveFolderPullEvent
}

internal sealed interface ArchiveFolderPullEffect {
    data object HapticThresholdActivated : ArchiveFolderPullEffect

    data object HapticRevealActivated : ArchiveFolderPullEffect

    data object AnnounceShown : ArchiveFolderPullEffect

    data object AnnounceHidden : ArchiveFolderPullEffect
}

/** The raw nested-scroll portion consumed by the archive visual, plus one-shot effects. */
internal data class ArchiveFolderPullResult(
    val state: ArchiveFolderPullState,
    val consumedY: Float = 0f,
    val effects: List<ArchiveFolderPullEffect> = emptyList(),
)

/**
 * Pure state machine. Crossing 85% emits threshold feedback; holding there (or above the 70%
 * disarm boundary) continuously for 1200ms reveals while still pressed. The visual uses a 1:1
 * measured row range and a 0.2x, 16dp-capped continuation after that row.
 */
internal fun reduceArchiveFolderPull(
    state: ArchiveFolderPullState,
    event: ArchiveFolderPullEvent,
    geometry: ArchiveFolderPullGeometry,
): ArchiveFolderPullResult {
    if (!geometry.isValid) return ArchiveFolderPullResult(ArchiveFolderPullState())

    fun hidden(): ArchiveFolderPullState = ArchiveFolderPullState(gestureId = state.gestureId)

    fun normalized(input: ArchiveFolderPullState): ArchiveFolderPullState {
        if (!input.exposurePx.isFinite()) return hidden()
        val exposure = input.exposurePx.coerceIn(0f, geometry.maxExposurePx)
        val phase =
            when {
                input.phase == ArchiveFolderPullPhase.REVEALED -> ArchiveFolderPullPhase.REVEALED
                exposure <= 0f -> ArchiveFolderPullPhase.HIDDEN
                input.phase == ArchiveFolderPullPhase.ARMED && exposure >= geometry.disarmPx -> ArchiveFolderPullPhase.ARMED
                else -> ArchiveFolderPullPhase.PULLING
            }
        return input.copy(
            exposurePx = if (phase == ArchiveFolderPullPhase.REVEALED) geometry.rowPx else exposure,
            phase = phase,
            holdStartedAtMs = if (phase == ArchiveFolderPullPhase.ARMED) input.holdStartedAtMs else null,
        )
    }

    val current = normalized(state)
    when (event) {
        is ArchiveFolderPullEvent.StartGesture -> {
            if (event.timestampMs < 0L) return ArchiveFolderPullResult(current)
            return ArchiveFolderPullResult(
                current.copy(
                    gestureActive = current.phase != ArchiveFolderPullPhase.REVEALED,
                    gestureId = current.gestureId + 1,
                    hapticEmitted = false,
                    holdStartedAtMs = null,
                    gestureStartedRevealed = current.phase == ArchiveFolderPullPhase.REVEALED,
                ),
            )
        }

        is ArchiveFolderPullEvent.DragDelta -> {
            if (event.source != ArchiveFolderPullSource.USER_INPUT || !event.listAtTop ||
                !current.gestureActive || event.timestampMs < 0L || !event.deltaY.isFinite()
            ) {
                return ArchiveFolderPullResult(current)
            }
            if (current.phase == ArchiveFolderPullPhase.REVEALED) return ArchiveFolderPullResult(current, event.deltaY)

            val rawBefore = exposureToRaw(current.exposurePx, geometry)
            val rawAfter = (rawBefore + event.deltaY).coerceIn(0f, exposureToRaw(geometry.maxExposurePx, geometry))
            val exposure = rawToExposure(rawAfter, geometry)
            return evaluateArming(
                current.copy(exposurePx = exposure),
                geometry,
                event.timestampMs,
                rawAfter - rawBefore,
            )
        }

        is ArchiveFolderPullEvent.Tick -> {
            val holdStart = current.holdStartedAtMs
            if (!current.gestureActive || current.phase != ArchiveFolderPullPhase.ARMED ||
                event.gestureId != current.gestureId || holdStart == null ||
                event.timestampMs < holdStart || event.timestampMs - holdStart < ArchiveFolderPull.HoldMillis
            ) {
                return ArchiveFolderPullResult(current)
            }
            return ArchiveFolderPullResult(
                current.copy(
                    exposurePx = geometry.rowPx,
                    phase = ArchiveFolderPullPhase.REVEALED,
                    holdStartedAtMs = null,
                ),
                effects = listOf(ArchiveFolderPullEffect.HapticRevealActivated, ArchiveFolderPullEffect.AnnounceShown),
            )
        }

        is ArchiveFolderPullEvent.Release -> {
            if (!current.gestureActive || event.timestampMs < 0L) return ArchiveFolderPullResult(current)
            return ArchiveFolderPullResult(
                if (current.phase == ArchiveFolderPullPhase.REVEALED) current.copy(gestureActive = false) else hidden(),
            )
        }

        ArchiveFolderPullEvent.Cancel -> {
            return ArchiveFolderPullResult(
                if (current.phase == ArchiveFolderPullPhase.REVEALED) current.copy(gestureActive = false) else hidden(),
            )
        }

        ArchiveFolderPullEvent.RevealedRowHidden -> {
            return if (current.phase == ArchiveFolderPullPhase.REVEALED) {
                ArchiveFolderPullResult(hidden(), effects = listOf(ArchiveFolderPullEffect.AnnounceHidden))
            } else {
                ArchiveFolderPullResult(current)
            }
        }

        ArchiveFolderPullEvent.RevealAccessibilityAction -> {
            return if (current.phase != ArchiveFolderPullPhase.REVEALED) {
                ArchiveFolderPullResult(
                    current.copy(
                        exposurePx = geometry.rowPx,
                        phase = ArchiveFolderPullPhase.REVEALED,
                        gestureActive = false,
                        gestureStartedRevealed = true,
                    ),
                    effects = listOf(ArchiveFolderPullEffect.AnnounceShown),
                )
            } else {
                ArchiveFolderPullResult(current)
            }
        }

        ArchiveFolderPullEvent.Reset -> {
            return ArchiveFolderPullResult(hidden())
        }
    }
}

/** Visible hint alpha, kept pure so frame rendering never creates a layout dependency. */
internal fun archiveFolderPullHintAlpha(
    exposurePx: Float,
    geometry: ArchiveFolderPullGeometry,
): Float {
    if (!geometry.isValid || !exposurePx.isFinite()) return 0f
    val denominator = (geometry.armPx - geometry.hintStartPx).coerceAtLeast(.0001f)
    return ((exposurePx - geometry.hintStartPx) / denominator).coerceIn(0f, 1f)
}

internal fun archiveFolderPullSettleTarget(
    state: ArchiveFolderPullState,
    geometry: ArchiveFolderPullGeometry,
): Float = if (geometry.isValid && state.phase == ArchiveFolderPullPhase.REVEALED) geometry.rowPx else 0f

/** Keep the overlay through the committed-row anchor handoff so no blank frame can appear. */
internal data class RevealedArchiveFolderScrollResult(
    val exposurePx: Float,
    val consumedY: Float,
    val hidden: Boolean,
)

/**
 * Moves the revealed pull surface and chat list together without changing LazyColumn content.
 * Negative deltas hide the folder; positive deltas can restore a partially hidden folder.
 */
internal fun scrollRevealedArchiveFolder(
    exposurePx: Float,
    deltaY: Float,
    geometry: ArchiveFolderPullGeometry,
): RevealedArchiveFolderScrollResult {
    if (!geometry.isValid || !exposurePx.isFinite() || !deltaY.isFinite()) {
        return RevealedArchiveFolderScrollResult(0f, 0f, hidden = false)
    }
    val current = exposurePx.coerceIn(0f, geometry.rowPx)
    val next = (current + deltaY).coerceIn(0f, geometry.rowPx)
    return RevealedArchiveFolderScrollResult(
        exposurePx = next,
        consumedY = next - current,
        hidden = deltaY < 0f && next <= 0f,
    )
}

private fun evaluateArming(
    input: ArchiveFolderPullState,
    geometry: ArchiveFolderPullGeometry,
    timestampMs: Long,
    consumedY: Float,
): ArchiveFolderPullResult {
    val state =
        if (input.phase == ArchiveFolderPullPhase.ARMED && input.exposurePx < geometry.disarmPx) {
            input.copy(
                phase = if (input.exposurePx <= 0f) ArchiveFolderPullPhase.HIDDEN else ArchiveFolderPullPhase.PULLING,
                holdStartedAtMs = null,
            )
        } else if (input.exposurePx <= 0f) {
            input.copy(phase = ArchiveFolderPullPhase.HIDDEN, holdStartedAtMs = null)
        } else if (input.phase != ArchiveFolderPullPhase.ARMED) {
            input.copy(phase = ArchiveFolderPullPhase.PULLING)
        } else {
            input
        }
    val eligible = state.phase != ArchiveFolderPullPhase.ARMED && state.exposurePx >= geometry.armPx
    return if (eligible) {
        val armed = state.copy(phase = ArchiveFolderPullPhase.ARMED, holdStartedAtMs = timestampMs)
        ArchiveFolderPullResult(
            armed.copy(hapticEmitted = true),
            consumedY,
            effects = if (state.hapticEmitted) emptyList() else listOf(ArchiveFolderPullEffect.HapticThresholdActivated),
        )
    } else {
        ArchiveFolderPullResult(state, consumedY)
    }
}

private fun exposureToRaw(
    exposurePx: Float,
    geometry: ArchiveFolderPullGeometry,
): Float =
    if (exposurePx <= geometry.rowPx) {
        exposurePx
    } else {
        geometry.rowPx +
            (exposurePx - geometry.rowPx) / ArchiveFolderPull.BeyondRowResistance
    }

private fun rawToExposure(
    rawPx: Float,
    geometry: ArchiveFolderPullGeometry,
): Float =
    if (rawPx <= geometry.rowPx) {
        rawPx
    } else {
        geometry.rowPx +
            (rawPx - geometry.rowPx) * ArchiveFolderPull.BeyondRowResistance
    }

/** Whether [row] keeps the friend presentation, including when it is globally pinned. */
internal fun isFriendQuery(
    row: ChatListRow,
    friends: Set<String>,
): Boolean = row.type == BufferType.QUERY && row.identityRules.matchesConfiguredNick(row.displayName, friends)

fun sectionChatList(
    rows: List<ChatListRow>,
    friends: Set<String>,
    fools: Set<String>,
): ChatListSections {
    val pinnedRows = ArrayList<ChatListRow>()
    val friendRows = ArrayList<ChatListRow>()
    val regular = ArrayList<ChatListRow>()
    val foolRows = ArrayList<ChatListRow>()

    for (row in rows) {
        when {
            row.pinned -> pinnedRows.add(row)

            isFriendQuery(row, friends) -> friendRows.add(row)

            row.type == BufferType.QUERY &&
                row.identityRules.matchesConfiguredNick(row.displayName, fools) -> foolRows.add(row)

            else -> regular.add(row)
        }
    }

    return ChatListSections(
        pinned = pinnedRows,
        friends = friendRows,
        regular = regular,
        fools = foolRows,
    )
}

/**
 * Count unread activity in rendered chat rows strictly above [firstVisibleItemIndex]. Section
 * headers occupy lazy-list indices but never contribute activity; collapsed fool rows do neither.
 */
internal fun unreadActivityBeforeDisplayIndex(
    sections: ChatListSections,
    foolsExpanded: Boolean,
    firstVisibleItemIndex: Int,
): Int {
    if (firstVisibleItemIndex <= 0) return 0
    var displayIndex = 0
    var unread = 0L

    fun consumeHeader() {
        displayIndex++
    }

    fun consumeRows(rows: List<ChatListRow>) {
        rows.forEach { row ->
            if (displayIndex < firstVisibleItemIndex) {
                unread += maxOf(row.unreadCount, row.mentionCount).coerceAtLeast(0)
            }
            displayIndex++
        }
    }

    consumeRows(sections.pinned)
    if (sections.friends.isNotEmpty()) {
        consumeHeader()
        consumeRows(sections.friends)
    }
    if (sections.showRecentHeader) consumeHeader()
    consumeRows(sections.regular)
    if (sections.fools.isNotEmpty()) {
        consumeHeader()
        if (foolsExpanded) consumeRows(sections.fools)
    }

    return unread.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Key of the first item the chat-list LazyColumn presents, mirroring the emit order in
 * `ChatList`: invitation rows (or the empty state) in invitation mode, otherwise the invitations
 * folder, pinned rows, friends header, friends, recent header, regular rows, then fools. The
 * recent header can never be first because it requires an earlier visible tier. Must stay in
 * lockstep with the LazyColumn content.
 */
internal fun chatListTopItemKey(
    invitationMode: Boolean,
    invitations: List<ChatListInvitation>,
    actionableInvitationCount: Int,
    sections: ChatListSections,
    folders: List<PresentedChatFolder> = emptyList(),
    pinned: List<ChatListRow> = sections.pinned,
): Any? =
    when {
        invitationMode -> {
            invitations.firstOrNull()?.let { "invitation-${it.messageId}" } ?: "invitations-empty"
        }

        actionableInvitationCount > 0 -> {
            "invitations-folder"
        }

        pinned.isNotEmpty() -> {
            pinned.first().bufferId
        }

        folders.isNotEmpty() -> {
            "folder-${folders.first().folder.id}"
        }

        sections.friends.isNotEmpty() -> {
            "friends-header"
        }

        sections.regular.isNotEmpty() -> {
            sections.regular.first().bufferId
        }

        sections.fools.isNotEmpty() -> {
            "fools-header"
        }

        else -> {
            null
        }
    }

/**
 * Whether a change of the top item should re-pin the viewport to index 0. LazyColumn re-anchors
 * to the first visible item's key across dataset changes, so without a re-pin a row promoted to
 * the top lands above a viewport resting at the true top and stays hidden until the user scrolls
 * up to find it. Only a resting true-top viewport re-pins: anywhere else the key anchor is the
 * behavior the user wants, and `requestScrollToItem` would cancel a scroll in progress.
 */
internal fun shouldRepinChatListTop(
    previousTopKey: Any?,
    topKey: Any?,
    canScrollBackward: Boolean,
    scrollInProgress: Boolean,
): Boolean =
    previousTopKey != null &&
        topKey != null &&
        topKey != previousTopKey &&
        !canScrollBackward &&
        !scrollInProgress

/** Plain mutable holder: composition-time change detection without a state backwards write. */
internal class ChatListTopItemTracker(
    var key: Any?,
)

private val ChatListRow.identityRules: IrcIdentityRules
    get() = IrcIdentityRules.from(caseMapping, chanTypes)
