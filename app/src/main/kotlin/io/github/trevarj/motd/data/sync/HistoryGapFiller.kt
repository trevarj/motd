package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.RoomId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The two things the timeline needs from [HistoryGapFillCoordinator]: fill a NAMED gap, and know
 * which gaps are filling right now so their dividers can show progress.
 *
 * Every fill names its gap id, whoever asked for it. There is no "fill whichever seam is newest"
 * entry point any more: the timeline decides which seam to work on from what the reader has scrolled
 * to, so by the time it calls here the gap is already chosen. The returned [GapFillProgress] is what
 * tells the caller whether the attempt broke, which is the only outcome the divider has to report.
 *
 * Narrow on purpose, mirroring how `HistoryResyncController` fronts its own coordinator. The
 * coordinator owns a live per-network wire, a page budget, and a diagnostics journal; a ViewModel
 * depending on all of that could not be built in a unit test without standing up a transport.
 */
interface HistoryGapFiller {
    /** Gap ids with a fill in flight, for the spinner on their divider rows. */
    val fillsInFlight: StateFlow<Set<Long>>

    /** Automatic demand may stop at wire admission when saved policy becomes Lazy; taps stay explicit. */
    suspend fun fillGap(
        roomId: RoomId,
        gapId: Long,
        automatic: Boolean,
    ): GapFillProgress
}

/**
 * What one load attempt across a seam achieved.
 *
 * The timeline loads history as the reader scrolls toward a seam, so the only outcome it has to act
 * on is the one that has to STOP that and show something: a genuine failure. Everything else leaves
 * the seam loading, and the distinctions below exist so a fill that came back empty-handed is never
 * mistaken for one that broke.
 */
enum class GapFillProgress {
    /** The fill inserted rows, moved its boundary, or settled the question. */
    MOVED,

    /** Nothing landed and the boundary did not move: the interval is still owed. */
    STALLED,

    /**
     * No work was admitted: the room was already filling, the gap had closed, the room cannot hold
     * one, or saved policy declined automatic work. Like [STALLED] this describes the attempt,
     * not a broken seam.
     */
    DROPPED,

    /** The attempt broke: a transport error, or no history transport at all. The one retryable end. */
    FAILED,
}

/** No history transport at all: nothing ever fills, so every seam keeps the state it already has. */
object NoopHistoryGapFiller : HistoryGapFiller {
    override val fillsInFlight: StateFlow<Set<Long>> = MutableStateFlow(emptySet())

    override suspend fun fillGap(
        roomId: RoomId,
        gapId: Long,
        automatic: Boolean,
    ) = GapFillProgress.MOVED
}
