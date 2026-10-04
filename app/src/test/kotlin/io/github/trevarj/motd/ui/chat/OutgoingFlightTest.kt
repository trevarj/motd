package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.service.SendAcceptance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flight reducer decides how long a real timeline row stays hidden behind the ghost that is
 * standing in for it, so every transition here has a row's visibility riding on it.
 */
class OutgoingFlightTest {
    @Test
    fun `a launched flight has no target until the send is accepted`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        assertEquals("hello", flight.text)
        assertTrue(flight.eventIds.isEmpty())
    }

    @Test
    fun `an accepted send adopts the row identity it produced`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        val attached = attachOutgoingFlightEvents(flight, token = 1, eventIds = listOf(7L))

        assertEquals(setOf(7L), attached?.eventIds)
    }

    @Test
    fun `a stale accept cannot retarget the ghost of a newer tap`() {
        val newer = launchOutgoingFlight(token = 2, text = "second", replyTo = null, nowMs = 0)

        val attached = attachOutgoingFlightEvents(newer, token = 1, eventIds = listOf(4L))

        assertEquals(newer, attached)
    }

    @Test
    fun `an accept with no rows settles the flight rather than aiming it at nothing`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        assertNull(attachOutgoingFlightEvents(flight, token = 1, eventIds = emptyList()))
    }

    @Test
    fun `an unaccepted flight recognises its pending row by what it says`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        // The row exists in Room before the send is accepted. Matching it only once event ids
        // arrive would let it appear, animate, and then be pulled back under the ghost.
        assertTrue(flight.matches(row(id = 5, text = "hello", pendingLabel = "motd-1")))
        assertFalse(flight.matches(row(id = 5, text = "hello", pendingLabel = null)))
        assertFalse(flight.matches(row(id = 5, text = "different", pendingLabel = "motd-1")))
        assertFalse(flight.matches(row(id = 5, text = "hello", pendingLabel = "motd-1", isSelf = false)))
    }

    @Test
    fun `pending flight identity compares exact formatted payload`() {
        val formatted = "\u0002hello\u0002"
        val flight = launchOutgoingFlight(token = 1, text = formatted, replyTo = null, nowMs = 0)

        assertTrue(flight.matches(row(id = 5, text = "hello", pendingLabel = "motd-1", ircFormattedText = formatted)))
        assertFalse(flight.matches(row(id = 6, text = "hello", pendingLabel = "motd-2")))
        assertTrue(acceptedRowMatchesFlight(accepted(listOf(5L), listOf(formatted)), formatted))
    }

    @Test
    fun `a repeat of the same text cannot capture the previous send's pending row`() {
        // Sending "lol" twice before the first echo returns leaves two identical pending rows.
        // Without the launch stamp the second ghost claims the first row, slamming it shut.
        val first = row(id = 5, text = "lol", pendingLabel = "motd-1", serverTime = 100)
        val second = launchOutgoingFlight(token = 2, text = "lol", replyTo = null, nowMs = 200)

        assertFalse(second.matches(first))
        assertTrue(second.matches(row(id = 6, text = "lol", pendingLabel = "motd-2", serverTime = 200)))
    }

    @Test
    fun `a flight claims an accepted row only when the pipeline stored what it shows`() {
        val sent = "hello"

        // The ordinary case: one row, stored verbatim.
        assertTrue(accepted(listOf(1L), listOf(sent)).let { acceptedRowMatchesFlight(it, sent) })
        // A reply without the client tag is stored with a visible prefix.
        assertFalse(
            acceptedRowMatchesFlight(accepted(listOf(1L), listOf("alice: hello")), sent),
        )
        // Newlines split one submission across rows; a single ghost cannot stand in for several.
        assertFalse(
            acceptedRowMatchesFlight(accepted(listOf(1L, 2L), listOf("hello", "there")), sent),
        )
        // An unreporting seam is treated as matching, so a single row still flies.
        assertTrue(acceptedRowMatchesFlight(accepted(listOf(1L), emptyList()), sent))
    }

    @Test
    fun `an accepted flight trusts its event ids over a lookalike row`() {
        val flight =
            attachOutgoingFlightEvents(
                launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0),
                token = 1,
                eventIds = listOf(5L),
            )!!

        assertTrue(flight.matches(row(id = 5, text = "anything", pendingLabel = null)))
        assertFalse(flight.matches(row(id = 6, text = "hello", pendingLabel = "motd-1")))
    }

    @Test
    fun `settling clears only the flight it names`() {
        val flight = launchOutgoingFlight(token = 3, text = "hello", replyTo = null, nowMs = 0)

        assertEquals(flight, settleOutgoingFlight(flight, token = 2))
        assertNull(settleOutgoingFlight(flight, token = 3))
    }

    @Test
    fun `the runway opens with the lift and the landing gap absorbs it`() {
        // Runway target 66 (ghost 60 + gap 6): opens in lockstep with the lift...
        assertEquals(0f, sendFlightListShift(66f, 0f, 0f), 0.001f)
        assertEquals(33f, sendFlightListShift(66f, 0.5f, 0f), 0.001f)
        assertEquals(66f, sendFlightListShift(66f, 1f, 0f), 0.001f)
        // ...and drains as the landing row's own gap reveals, clamped at closed.
        assertEquals(46f, sendFlightListShift(66f, 1f, 20f), 0.001f)
        assertEquals(0f, sendFlightListShift(66f, 1f, 66f), 0.001f)
        assertEquals(0f, sendFlightListShift(66f, 1f, 80f), 0.001f)
        // The lift spring's overshoot stays on the bubble; the timeline never nods past target.
        assertEquals(66f, sendFlightListShift(66f, 1.1f, 0f), 0.001f)
    }

    @Test
    fun `a collapsing composer's foot drop is absorbed by the shift immediately`() {
        // A 3-line draft (field shrinks 40px on the tap frame) with a 200px ghost, runway 206:
        // the shift starts at the full drop so the neighbour never dips toward the collapsed
        // bar, springs up the remaining travel, and drains through the reveal as usual.
        assertEquals(40f, sendFlightListShift(206f, 0f, 0f, footDrop = 40f), 0.001f)
        assertEquals(123f, sendFlightListShift(206f, 0.5f, 0f, footDrop = 40f), 0.001f)
        assertEquals(206f, sendFlightListShift(206f, 1f, 0f, footDrop = 40f), 0.001f)
        assertEquals(0f, sendFlightListShift(206f, 1f, 206f, footDrop = 40f), 0.001f)
        // A short message after a huge draft (drop exceeds the runway): the neighbour is held,
        // then eased DOWN to its genuinely lower resting slot rather than jumped.
        assertEquals(80f, sendFlightListShift(66f, 0f, 0f, footDrop = 80f), 0.001f)
        assertEquals(66f, sendFlightListShift(66f, 1f, 0f, footDrop = 80f), 0.001f)
        assertEquals(0f, sendFlightListShift(66f, 1f, 66f, footDrop = 80f), 0.001f)
    }

    @Test
    fun `the hover rise is shortened by the foot drop so a tall ghost cannot overshoot its slot`() {
        // Pinned field top 900, ghost 200, field collapsed by 40: the resting slot sits 40 lower
        // than a fixed-foot rise assumes, so the full hover is 160, not 200.
        assertEquals(820f, sendFlightGhostTop(900f, 200f, 40f, null, null, 0f, 0.5f, footDrop = 40f), 0.001f)
        assertEquals(740f, sendFlightGhostTop(900f, 200f, 80f, null, null, 0f, 1f, footDrop = 40f), 0.001f)
        // A drop taller than the ghost pins the hover at the field: the slot is BELOW the start.
        assertEquals(900f, sendFlightGhostTop(900f, 60f, 0f, null, null, 0f, 1f, footDrop = 80f), 0.001f)
    }

    @Test
    fun `the runway handoff keeps the total vacated space continuous`() {
        // The neighbour's edge sits at shift + revealed below its resting spot; across the whole
        // handoff that total is max(runway, revealed), so it can only grow, never dip.
        var previous = 0f
        for (revealed in listOf(0f, 10f, 30f, 50f, 66f, 70f)) {
            val total = sendFlightListShift(66f, 1f, revealed) + revealed
            assertTrue(total >= previous)
            previous = total
        }
    }

    @Test
    fun `with no landing the lift hovers the ghost one bubble height above the composer`() {
        // Composer top at 900, ghost 60 tall: the runway has vacated more than a bubble height
        // beneath it, so the full-height hover rides entirely in empty space.
        assertEquals(900f, sendFlightGhostTop(900f, 60f, 0f, null, null, 0f, 0f), 0.001f)
        assertEquals(870f, sendFlightGhostTop(900f, 60f, 33f, null, null, 0f, 0.5f), 0.001f)
        assertEquals(840f, sendFlightGhostTop(900f, 60f, 66f, null, null, 0f, 1f), 0.001f)
    }

    @Test
    fun `the flight aims at the resting foot, not the shifted one`() {
        // The reported landing rides the runway shift (reported bottom 834 = resting 900 - 66),
        // so the target adds the shift back and stays stationary while the shift drains.
        assertEquals(840f, sendFlightGhostTop(900f, 60f, 66f, 768f, 834f, 1f, 1f), 0.001f)
        assertEquals(840f, sendFlightGhostTop(900f, 60f, 33f, 834f, 867f, 1f, 1f), 0.001f)
        assertEquals(840f, sendFlightGhostTop(900f, 60f, 0f, 834f, 900f, 1f, 1f), 0.001f)
    }

    @Test
    fun `the flight spring's overshoot is bounded by the gap the row opened`() {
        // A break-gap landing (row 66 tall, gap top 834) leaves room for the 6px overshoot.
        assertEquals(834f, sendFlightGhostTop(900f, 60f, 0f, 834f, 900f, 1.1f, 1f), 0.001f)
        // A burst landing exactly the ghost's height does not: the bounce stops at the vacated
        // edge instead of poking into the neighbour.
        assertEquals(840f, sendFlightGhostTop(900f, 60f, 0f, 840f, 900f, 1.1f, 1f), 0.001f)
    }

    @Test
    fun `the flight replica materializes over the composer on the lift's first stretch`() {
        assertEquals(0f, sendFlightEntryFade(0f), 0.001f)
        assertEquals(0.5f, sendFlightEntryFade(0.175f), 0.001f)
        assertEquals(1f, sendFlightEntryFade(0.35f), 0.001f)
        // The lift spring's overshoot never pushes the fade past opaque.
        assertEquals(1f, sendFlightEntryFade(1.015f), 0.001f)
    }

    @Test
    fun `launch geometry survives the field clearing before the flight composes`() {
        val anchors = SendFlightAnchors()
        val field = Rect(20f, 900f, 380f, 980f)
        val textOrigin = Offset(32f, 912f)
        anchors.composerField = field
        anchors.composerTextOrigin = textOrigin
        anchors.captureLaunch()

        anchors.composerField = Rect(20f, 940f, 380f, 980f)
        anchors.composerTextOrigin = Offset(32f, 952f)
        anchors.beginFlight()

        assertEquals(field, anchors.launchField)
        assertEquals(textOrigin, anchors.launchTextOrigin)
        assertEquals(40f, anchors.composerShrink(), 0.001f)
        // An externally launched next flight cannot reuse the previous tap's pinned geometry.
        anchors.beginFlight()
        assertEquals(anchors.composerField, anchors.launchField)
        assertEquals(anchors.composerTextOrigin, anchors.launchTextOrigin)
    }

    @Test
    fun `a submission without a new flight does not move the hovering bubble`() {
        val anchors = SendFlightAnchors()
        val field = Rect(20f, 900f, 380f, 980f)
        val textOrigin = Offset(32f, 912f)
        anchors.composerField = field
        anchors.composerTextOrigin = textOrigin
        anchors.captureLaunch()
        anchors.composerField = Rect(20f, 940f, 380f, 980f)
        anchors.composerTextOrigin = Offset(32f, 952f)
        anchors.beginFlight()

        fun hoverTop() =
            sendFlightGhostTop(
                anchors.launchField!!.top,
                200f,
                206f,
                null,
                null,
                0f,
                1f,
                footDrop = anchors.composerShrink(),
            )
        assertEquals(740f, hoverTop(), 0.001f)

        // An emote/command is submitted from the collapsed field but creates no new flight.
        anchors.captureLaunch()
        assertEquals(field, anchors.launchField)
        assertEquals(textOrigin, anchors.launchTextOrigin)
        assertEquals(40f, anchors.composerShrink(), 0.001f)
        assertEquals(740f, hoverTop(), 0.001f)

        // A subsequent actual flight adopts its own tap geometry, not the previous command's.
        anchors.composerField = Rect(20f, 920f, 380f, 980f)
        anchors.composerTextOrigin = Offset(32f, 932f)
        anchors.captureLaunch()
        anchors.beginFlight()
        assertEquals(920f, anchors.launchField!!.top, 0.001f)
        assertEquals(Offset(32f, 932f), anchors.launchTextOrigin)
        assertEquals(0f, anchors.composerShrink(), 0.001f)
        assertEquals(720f, hoverTop(), 0.001f)
    }

    private fun accepted(
        eventIds: List<Long>,
        storedTexts: List<String>,
    ) = SendAcceptance.Accepted(eventIds = eventIds, storedTexts = storedTexts)

    private fun row(
        id: Long,
        text: String,
        pendingLabel: String?,
        isSelf: Boolean = true,
        serverTime: Long = 0,
        ircFormattedText: String? = null,
    ) = MessageEntity(
        id = id,
        bufferId = 1,
        msgid = null,
        serverTime = serverTime,
        sender = "me",
        kind = MessageKind.PRIVMSG,
        text = text,
        ircFormattedText = ircFormattedText,
        isSelf = isSelf,
        pendingLabel = pendingLabel,
        dedupKey = "d$id",
    )
}
