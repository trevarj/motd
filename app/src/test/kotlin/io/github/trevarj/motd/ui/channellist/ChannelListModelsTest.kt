package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.irc.client.ChannelListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListModelsTest {

    @Test
    fun `sortListings orders by userCount descending`() {
        val input = listOf(
            ChannelListing("#a", 10, ""),
            ChannelListing("#b", 500, ""),
            ChannelListing("#c", 42, ""),
        )
        val sorted = sortListings(input)
        assertEquals(listOf("#b", "#c", "#a"), sorted.map { it.name })
    }

    @Test
    fun `sortListings is stable for ties`() {
        val input = listOf(
            ChannelListing("#first", 100, ""),
            ChannelListing("#second", 100, ""),
            ChannelListing("#third", 100, ""),
        )
        // Equal counts keep input order (Kotlin's sort is stable).
        assertEquals(listOf("#first", "#second", "#third"), sortListings(input).map { it.name })
    }

    // -- fetch gating (Confirmed decision #6): ELIST 'U' -> auto-fetch; else require a mask --

    @Test
    fun `canAutoFetch true when ELIST contains U`() {
        assertTrue(canAutoFetch("CTU"))
        assertTrue(canAutoFetch("u")) // case-insensitive
    }

    @Test
    fun `canAutoFetch false without U or when absent`() {
        assertFalse(canAutoFetch("CMNT"))
        assertFalse(canAutoFetch(""))
        assertFalse(canAutoFetch(null))
    }

    // -- LIST arg resolution --

    @Test
    fun `listArgsFor blank query auto-fetches busiest with default min-users`() {
        val args = listArgsFor("   ")
        assertNull(args.mask)
        assertEquals(DEFAULT_MIN_USERS, args.minUsers)
    }

    @Test
    fun `listArgsFor non-blank query uses substring mask and no min-users`() {
        val args = listArgsFor("kotlin")
        assertEquals("*kotlin*", args.mask)
        assertNull(args.minUsers)
    }

    @Test
    fun `listArgsFor trims the query`() {
        assertEquals("*linux*", listArgsFor("  linux  ").mask)
    }
}
