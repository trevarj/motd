package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The placeholder height estimator.
 *
 * Every case here is about the same defect: a skeleton that is not the size of the row it stands in
 * for reflows the whole list below it on each swap. The estimate can never be exact — a placeholder
 * slot carries no data at all — so what is pinned is that it tracks the rows actually on screen, is
 * not moved by the rows that occupy no space, is not dragged by a single tall outlier, and does not
 * churn on sub-step noise.
 */
class TimelineRowHeightTest {
    private val bounds = TimelineRowHeightBounds(minPx = 60, maxPx = 480, stepPx = 24)

    @Test
    fun onlyRealMessageRowsAreSampled() {
        // Row keys are entity ids (Long). Paging's placeholder key and the append footer are not.
        assertTrue(isTimelineRowHeightSample(11L, 120, bounds.minPx))
        assertFalse(isTimelineRowHeightSample("append-state", 90, bounds.minPx))
        assertFalse(isTimelineRowHeightSample(PlaceholderKeyStandIn(3), 90, bounds.minPx))
    }

    @Test
    fun suppressedRunMembersDoNotDragTheEstimateDown() {
        // Suppressed rows measure 1dp instead of zero so LazyColumn cannot measure hundreds of
        // them in a frame. They must not shrink skeletons for the real rows they surround.
        assertFalse(isTimelineRowHeightSample(1L, 3, bounds.minPx))
        assertTrue(isTimelineRowHeightSample(4L, 132, bounds.minPx))
    }

    @Test
    fun theStatisticIsAMedianSoOneTallRowCannotInflateEverySkeleton() {
        // One link-preview row among ordinary lines. A mean would land near 190px; the median stays
        // with the rows the reader is actually looking at.
        val heights = listOf(96, 100, 104, 108, 900)

        assertEquals(104, medianTimelineRowHeightPx(heights))
    }

    @Test
    fun anEmptySampleYieldsNoStatistic() {
        assertNull(medianTimelineRowHeightPx(emptyList()))
    }

    @Test
    fun anEvenSampleAveragesTheMiddlePair() {
        assertEquals(102, medianTimelineRowHeightPx(listOf(104, 96, 108, 100)))
    }

    @Test
    fun theFirstSampleReplacesTheFallbackOutright() {
        val next =
            nextTimelineRowHeightPx(
                currentPx = UNSAMPLED_ROW_HEIGHT_PX,
                sampledPx = 148,
                bounds = bounds,
            )

        // 148 snaps to the nearest 24px lattice point.
        assertEquals(144, next)
        assertTrue("a cold timeline must stop using the fallback", next != UNSAMPLED_ROW_HEIGHT_PX)
    }

    @Test
    fun aMissingSampleHoldsTheStandingEstimate() {
        // An all-placeholder viewport has nothing to measure. Holding is right: the last real rows
        // this conversation drew are still the best available description of the next ones.
        assertEquals(144, nextTimelineRowHeightPx(currentPx = 144, sampledPx = null, bounds = bounds))
    }

    @Test
    fun subStepDriftDoesNotResizeTheSkeletons() {
        // The median wobbles by a few pixels as rows scroll through the viewport. Adopting that
        // would resize every on-screen skeleton on every measure pass.
        val drifted = nextTimelineRowHeightPx(currentPx = 144, sampledPx = 152, bounds = bounds)

        assertEquals(144, drifted)
    }

    @Test
    fun aSampleStraddlingALatticeBoundaryDoesNotFlipTheEstimate() {
        // 157px is nearer the 168px lattice point than the standing 144px one, so snapping alone
        // would move it — and move it back on the next pass when the median reads 155 again. The
        // band is measured against the raw sample precisely to stop that flip.
        assertEquals(144, nextTimelineRowHeightPx(currentPx = 144, sampledPx = 157, bounds = bounds))
        assertEquals(144, nextTimelineRowHeightPx(currentPx = 144, sampledPx = 155, bounds = bounds))
    }

    @Test
    fun aRealChangeInRowSizeIsAdopted() {
        val grown = nextTimelineRowHeightPx(currentPx = 144, sampledPx = 240, bounds = bounds)
        val shrunk = nextTimelineRowHeightPx(currentPx = 144, sampledPx = 72, bounds = bounds)

        assertEquals(240, grown)
        assertEquals(72, shrunk)
    }

    @Test
    fun theEstimateStaysWithinItsBounds() {
        val tiny = nextTimelineRowHeightPx(currentPx = UNSAMPLED_ROW_HEIGHT_PX, sampledPx = 4, bounds = bounds)
        val huge = nextTimelineRowHeightPx(currentPx = UNSAMPLED_ROW_HEIGHT_PX, sampledPx = 4_000, bounds = bounds)

        assertTrue("floor was breached: $tiny", tiny >= bounds.minPx)
        assertTrue("ceiling was breached: $huge", huge <= bounds.maxPx)
    }

    @Test
    fun repeatedPassesOverAStableViewportSettle() {
        // The sampler re-runs on every resting measure pass. A value that keeps moving would repaint
        // the timeline indefinitely, so a stable viewport must reach a fixed point.
        var current = UNSAMPLED_ROW_HEIGHT_PX
        val viewport = listOf(1L to 130, 2L to 3, 3L to 138, 4L to 700, 5L to 126)
        val settled =
            (0 until 8).map {
                current =
                    nextTimelineRowHeightPx(
                        currentPx = current,
                        sampledPx =
                            medianTimelineRowHeightPx(
                                viewport
                                    .filter { (key, size) -> isTimelineRowHeightSample(key, size, bounds.minPx) }
                                    .map { it.second },
                            ),
                        bounds = bounds,
                    )
                current
            }

        assertEquals(1, settled.distinct().size)
    }

    @Test
    fun rowKeysAreDiscriminatedFromEverythingElseInTheList() {
        assertTrue(isTimelineRowKey(42L))
        assertFalse(isTimelineRowKey("append-state"))
        assertFalse(isTimelineRowKey(null))
        assertFalse(isTimelineRowKey(7))
        assertFalse(isTimelineRowKey(PlaceholderKeyStandIn(7)))
    }

    /** Paging's placeholder key is internal to the library; its only relevant property is not-Long. */
    private data class PlaceholderKeyStandIn(
        val index: Int,
    )
}
