package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class TimelineSenderContextTest {
    private val alex = TimelineSender("Alex", "@Alex", "alex", "alex", false)
    private val robin = TimelineSender("Robin", "Robin", "robin", null, false)

    @Test fun wholeViewportOmittingNativeNickKeepsSenderContext() {
        val rows = listOf(item(0, -25, 70), item(1, 45, 70))
        val body = mapOf(1L to body(alex, 70), 2L to body(alex, 70, nick = 0..12, opensGroup = true))
        assertEquals(alex, viewportSender(rows, 0, 100, 8, body::get))
        assertNull(viewportSender(rows, 0, 108, 8, body::get))
    }

    @Test fun oneTallMessageRetainsContextOnlyWhenItsNickIsClipped() {
        val rows = listOf(item(0, -75, 250))
        val metrics = body(alex, 250, nick = 224..242, opensGroup = true)
        assertEquals(alex, viewportSender(rows, 0, 100, 8) { metrics })
        metrics.senderNick = 119..135
        assertNull(viewportSender(rows, 0, 100, 8) { metrics })
    }

    @Test fun acceptsABuriedNeighborButNotAnotherVisibleSenderOrRun() {
        val rows = listOf(item(0, -40, 40), item(1, 0, 50), item(2, 50, 58))
        val body = mapOf(1L to body(robin, 40), 2L to body(alex, 50), 3L to body(alex, 58))
        assertEquals(alex, viewportSender(rows, 0, 100, 8, body::get))
        assertNull(viewportSender(rows, -1, 100, 8, body::get))
        body.getValue(2L).senderRow = TimelineSenderRow(alex, opensGroup = true, hasNick = false, hasLeadingDecoration = false)
        assertNull(viewportSender(rows, 0, 100, 8, body::get))
    }

    @Test fun brushAgainstAnOrdinaryGapAtTheComposerDoesNotClearTheChip() {
        val rows = listOf(item(0, -38, 40), item(1, 2, 51), item(2, 53, 52))
        val hiddenBody = body(alex, 40).apply { senderBody = 3..40 }
        val body = mapOf(1L to hiddenBody, 2L to body(alex, 51), 3L to body(alex, 52))
        assertEquals(alex, viewportSender(rows, 0, 100, 8, body::get))
    }

    @Test fun dividerAndPlaceholderDisqualifyOnlyWhenVisible() {
        val rows = listOf(item(0, -15, 65), item(1, 50, 78), item(2, 128, 60, key = "placeholder"))
        val older =
            body(alex, 78).apply {
                senderBody = 20..78
                senderRow = TimelineSenderRow(alex, opensGroup = false, hasNick = false, hasLeadingDecoration = true)
            }
        val body = mapOf(1L to body(alex, 65), 2L to older)
        assertEquals(alex, viewportSender(rows, 0, 100, 8, body::get))
        assertNull(viewportSender(rows, 0, 110, 8, body::get))
        assertNull(viewportSender(listOf(item(0, 0, 100, key = "placeholder")), 0, 100, 8, body::get))
    }

    @Test fun dateLooksUpOnlyThePhysicalTopRowOfTheUsableReverseViewport() {
        val rows =
            listOf(
                item(5, 100, 32, key = "under-title"),
                item(2, 50, 70),
                item(4, 96, 0, key = "empty"),
                item(0, -25, 35, key = "under-composer"),
                item(1, 70, 5, key = "lower-skeleton"),
            )
        assertEquals(
            3_000L,
            viewportDateTime(rows, bottom = 10, top = 100) { index ->
                assertEquals(2, index)
                message(3)
            },
        )
    }

    @Test fun aLeadingSkeletonOrSyntheticRowSuppressesTheDate() {
        for (key in listOf("placeholder", 0L, -1L)) {
            val rows = listOf(item(0, 0, 50), item(1, 50, 50, key = key))
            assertNull(viewportDateTime(rows, bottom = 0, top = 100) { error("No lookup for $key") })
        }
    }

    @Test fun rejectsMissingOrStaleSnapshotsWithoutFallingBackToALowerRow() {
        val rows = listOf(item(0, 0, 50), item(1, 50, 50))
        assertNull(viewportDateTime(rows, bottom = 0, top = 100) { null })
        assertNull(viewportDateTime(rows, bottom = 0, top = 100) { message(1) })
        assertEquals(2_000L, viewportDateTime(rows, bottom = 0, top = 100) { message(2) })
    }

    @Test fun aFullyOccludedViewportDoesNotLookUpADate() {
        val rows = listOf(item(0, 10, 100))
        assertNull(viewportDateTime(rows, bottom = 50, top = 50) { error("Nothing is visible") })
        assertNull(viewportDateTime(rows, bottom = 0, top = 10) { error("Nothing intersects") })
    }

    @Test fun dateStateRetainsItsTimestampWithinALocalDayAcrossDst() {
        val first = Instant.parse("2024-03-10T05:00:00Z").toEpochMilli()
        val last = Instant.parse("2024-03-11T03:59:59Z").toEpochMilli()
        val next = Instant.parse("2024-03-11T04:00:00Z").toEpochMilli()
        var time by mutableStateOf<Long?>(first)
        val day by derivedStateOf(timelineDatePolicy(TimeZone.getTimeZone("America/New_York"))) { time }

        assertEquals(first, day)
        time = last
        assertEquals(first, day)
        time = next
        assertEquals(next, day)
        time = null
        assertNull(day)
    }

    private fun message(id: Long): MessageEntity =
        MessageEntity(
            id = id,
            bufferId = 1,
            msgid = null,
            serverTime = id * 1_000,
            sender = "alice",
            kind = MessageKind.PRIVMSG,
            text = "message",
            dedupKey = "d$id",
        )

    private fun item(
        row: Int,
        start: Int,
        height: Int,
        key: Any = row + 1L,
    ): LazyListItemInfo =
        object : LazyListItemInfo {
            override val index = row
            override val key = key
            override val offset = start
            override val size = height
            override val contentType: Any? = null
        }

    private fun body(
        sender: TimelineSender,
        height: Int,
        nick: IntRange? = null,
        opensGroup: Boolean = false,
    ): TimelineRowContentMetrics =
        TimelineRowContentMetrics().apply {
            senderRow = TimelineSenderRow(sender, opensGroup, nick != null, hasLeadingDecoration = false)
            senderBody = 0..height
            senderNick = nick
        }
}
