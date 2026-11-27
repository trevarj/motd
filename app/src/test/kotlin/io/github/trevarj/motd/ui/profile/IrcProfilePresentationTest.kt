package io.github.trevarj.motd.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IrcProfilePresentationTest {
    @Test
    fun nameOrderKeepsTheActualIrcIdentity() {
        assertEquals("#motd" to "MOTD Lounge", orderedIrcIdentityLabels("#motd", " MOTD Lounge ", false))
        assertEquals("MOTD Lounge" to "#motd", orderedIrcIdentityLabels("#motd", "MOTD Lounge", true))
        assertEquals("alice" to null, orderedIrcIdentityLabels("alice", "alice", true))
        assertEquals("alice" to null, orderedIrcIdentityLabels("alice", " ", true))
        assertEquals("🤖".repeat(15) + "…", compactIrcDisplayName("🤖".repeat(17)))
    }

    @Test
    fun homepageOnlyOpensAsAnOrdinaryWebUrl() {
        assertEquals("https://example.com/about?x=1", safeProfileHomepage("https://example.com/about?x=1"))
        assertEquals("http://example.com/", safeProfileHomepage("http://example.com"))
        assertNull(safeProfileHomepage("intent://example.com/"))
        assertNull(safeProfileHomepage("https://user:pass@example.com/"))
        assertNull(safeProfileHomepage("https://@example.com/"))
        assertNull(safeProfileHomepage("https:///example.com/"))
        assertNull(safeProfileHomepage("https://example.com\n/"))
    }
}
