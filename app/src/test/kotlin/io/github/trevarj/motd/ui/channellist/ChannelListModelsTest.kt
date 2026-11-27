package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelListModelsTest {
    @Test
    fun directorySortUsesNetworkNamesForOrderingAndPopulationTies() {
        val bracket = ChannelListing("#[room]", 10, "")
        val zed = ChannelListing("#Z", 10, "")
        val quiet = ChannelListing("#a", 1, "")
        val rows = listOf(bracket, zed, quiet, bracket.copy(name = "#{room}"))
        val rules = IrcIdentityRules()
        assertEquals(listOf(zed, bracket, quiet), sortedDirectoryRows(rows, DirectorySort.POPULATION, rules, mutableSetOf()))
        assertEquals(listOf(quiet, zed, bracket), sortedDirectoryRows(rows, DirectorySort.POPULATION, rules, mutableSetOf(), smaller = true))
        assertEquals(listOf(quiet, zed, bracket), sortedDirectoryRows(rows, DirectorySort.NAME, rules, mutableSetOf()))
        val ascii = IrcIdentityRules.from("ascii", null)
        assertEquals(listOf(bracket, quiet, zed), sortedDirectoryRows(rows.take(3), DirectorySort.NAME, ascii, mutableSetOf()))
    }

    @Test
    fun sectionPopulationSelectsAliasRepresentativeRegardlessOfDisplaySort() {
        val busy = ChannelListing("#[Room]", 20, "Busy topic")
        val quiet = ChannelListing("#{room}", 2, "Quiet topic")
        val otherAlias = quiet.copy(name = "#[room]", topic = "Later topic")
        for (sort in DirectorySort.entries) {
            val rules = IrcIdentityRules()
            val seen = mutableSetOf<String>()
            assertEquals(listOf(quiet), sortedDirectoryRows(listOf(busy, quiet, otherAlias), sort, rules, seen, smaller = true))
            assertEquals(emptyList<ChannelListing>(), sortedDirectoryRows(listOf(busy), sort, rules, seen))
            assertEquals(listOf(busy), sortedDirectoryRows(listOf(quiet, busy), sort, rules, mutableSetOf()))
        }
    }

    @Test
    fun `live ready client wins while manager snapshot is absent or stale`() {
        val ready = IrcClientState.Ready("me", emptySet(), mapOf("ELIST" to "U"))

        assertEquals(ready, channelBrowserConnectionState(null, ready))
        assertEquals(ready, channelBrowserConnectionState(IrcClientState.Disconnected, ready))
        assertEquals(
            IrcClientState.Disconnected,
            channelBrowserConnectionState(ready, IrcClientState.Disconnected),
        )
    }

    @Test
    fun `browser does not present offline state before initialization`() {
        assertEquals(
            ChannelBrowserAvailability.INITIALIZING,
            channelBrowserAvailability(false, false, IrcClientState.Disconnected),
        )
        assertEquals(
            ChannelBrowserAvailability.READY,
            channelBrowserAvailability(true, false, IrcClientState.Ready("me", emptySet(), emptyMap())),
        )
    }

    @Test
    fun `local filter finds formatted topic text and respects channel case mapping`() {
        val byName = ChannelListing("#[Linux]", 10, "")
        val byTopic = ChannelListing("#other", 9, "Daily Lin\u0002ux news")
        val unrelated = ChannelListing("#nothing", 8, "Other topics")
        val listings = listOf(byName, byTopic, unrelated)

        assertEquals(listOf(byName), filterLoadedChannels(listings, "{linux}", IrcIdentityRules()))
        assertEquals(emptyList<ChannelListing>(), filterLoadedChannels(listings, "{linux}", IrcIdentityRules.from("ascii", null)))
        assertEquals(listOf(byTopic), filterLoadedChannels(listings, "linux NEWS", IrcIdentityRules()))
    }

    @Test
    fun `exact join preserves explicit supported prefixes and rejects multi-target inputs`() {
        val rules = IrcIdentityRules.from("ascii", "#&")
        assertEquals("##Room", exactChannelTarget(" ##Room ", rules))
        assertEquals("&Room", exactChannelTarget("&Room", rules))
        for (input in listOf("#", "Room", "!Room", "#one,#two", "#room key", "#room\r\nJOIN #other")) {
            assertNull(exactChannelTarget(input, rules))
        }
    }

    @Test
    fun `exact join accepts a single nonempty server scope`() {
        val rules = IrcIdentityRules.from("ascii", "#&")
        assertEquals("#ops:*.example.net", exactChannelTarget("#ops:*.example.net", rules))
        for (input in listOf("#:*.example.net", "#ops:", "#ops:one:two", "#ops:one,two", "#ops:one two")) {
            assertNull(exactChannelTarget(input, rules))
        }
    }

    @Test
    fun `phrase search stays local and network name search keeps punctuation literal`() {
        assertEquals("#Room?", networkSearchTerm("  #Room?  "))
        assertNull(networkSearchTerm("room topic"))
        assertNull(networkSearchTerm("one,two"))
    }
}
