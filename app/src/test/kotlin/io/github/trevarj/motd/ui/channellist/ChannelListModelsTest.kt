package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelListModelsTest {
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
    fun `phrase search stays local and network name search keeps punctuation literal`() {
        assertEquals("#Room?", networkSearchTerm("  #Room?  "))
        assertNull(networkSearchTerm("room topic"))
        assertNull(networkSearchTerm("one,two"))
    }
}
