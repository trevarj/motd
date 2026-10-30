package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ScenicWallpaperRuntimeTest {
    @Test
    fun countrysideSeasonFollowsPhoneLocalMeteorologicalMonths() {
        assertEquals(CountrysideSeason.WINTER, countrysideSeason(LocalDate.of(2026, 1, 1)))
        assertEquals(CountrysideSeason.WINTER, countrysideSeason(LocalDate.of(2024, 2, 29)))
        assertEquals(CountrysideSeason.WINTER, countrysideSeason(LocalDate.of(2026, 2, 28)))
        assertEquals(CountrysideSeason.SPRING, countrysideSeason(LocalDate.of(2026, 3, 1)))
        assertEquals(CountrysideSeason.SUMMER, countrysideSeason(LocalDate.of(2026, 6, 1)))
        assertEquals(CountrysideSeason.AUTUMN, countrysideSeason(LocalDate.of(2026, 9, 1)))
        assertEquals(CountrysideSeason.WINTER, countrysideSeason(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun staticThumbnailKeyLoadsOnlyTheCurrentImageWithoutEventPayload() {
        val key =
            scenicKey(
                wallpaper = WallpaperSelection(ChatWallpaperPreset.JAPANESE_COUNTRYSIDE),
                date = LocalDate.of(2026, 9, 1),
                systemDark = true,
                appDark = false,
                loadEvents = false,
            )
        assertEquals(CountrysideSeason.AUTUMN, key.season)
        assertEquals(true, key.dark)
        assertFalse(key.loadEvents)
    }

    @Test
    fun schedulerUsesAuthoredFramesAndRestoresTheStaticFrameOnCancellation() =
        runTest {
            val eventFrame = ScenicFrame(120, listOf(ScenicPixels(4, 5, 0xff224466.toInt())))
            val restoreFrame = ScenicFrame(80, emptyList())
            var frame: ScenicFrame? = null
            val job =
                launch {
                    playScenicEvents(
                        initialDelayMs = 20L..20L,
                        quietIntervalMs = 45L..45L,
                        events = listOf(ScenicEvent(listOf(eventFrame, restoreFrame))),
                        onFrame = { frame = it },
                        delayFor = { delay -> kotlinx.coroutines.delay(delay) },
                        selectEvent = { it.single() },
                    )
                }

            runCurrent()
            assertNull(frame)
            advanceTimeBy(20)
            runCurrent()
            assertEquals(eventFrame, frame)
            advanceTimeBy(120)
            runCurrent()
            assertNull(frame)
            advanceTimeBy(80 + 45)
            runCurrent()
            assertEquals(eventFrame, frame)
            job.cancelAndJoin()
            assertNull(frame)
        }
}
