package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDeviconBadgeTest {
    @Test fun channel_names_match_the_intended_devicons_or_guix_mark() {
        assertEquals(ChannelDevicon.GUIX, matchedChannelDevicon("#guix"))
        assertEquals(ChannelDevicon.DEBIAN, matchedChannelDevicon("#debian-devel"))
        assertEquals(ChannelDevicon.EMACS, matchedChannelDevicon("#doomEmacs"))
        assertEquals(ChannelDevicon.NEOVIM, matchedChannelDevicon("#neovim"))
        assertEquals(ChannelDevicon.RUST, matchedChannelDevicon("#rust-lang"))
        assertEquals(ChannelDevicon.KUBERNETES, matchedChannelDevicon("#k8s"))
    }

    @Test fun matching_is_conservative_for_short_or_unrelated_tokens() {
        assertEquals(ChannelDevicon.GO, matchedChannelDevicon("#go"))
        assertNull(matchedChannelDevicon("#mango"))
        assertNull(matchedChannelDevicon("#general-chat"))
    }

    @Test fun every_bundled_channel_mark_has_parseable_vector_source() {
        ChannelDevicon.entries.forEach { icon ->
            assertTrue("${icon.name} should contain parseable path data", icon.hasParseablePathData())
        }
    }
}
