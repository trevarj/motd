package io.github.trevarj.motd.profile

import io.github.trevarj.motd.avatar.METADATA_CAP
import io.github.trevarj.motd.avatar.METADATA_V3_CAP
import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileProtocolTest {
    @Test fun profile_values_respect_the_registry_and_wire_budget() {
        val pronouns = "  she/her (妳/她)  "
        assertEquals(pronouns, validProfileValue(ProfileKey.PRONOUNS, pronouns))
        assertEquals("PacketBot v1.2", validProfileValue(ProfileKey.BOT, "PacketBot v1.2"))
        assertEquals("#09aBcD", validProfileValue(ProfileKey.COLOR, "#09aBcD"))
        assertNull(validProfileValue(ProfileKey.COLOR, "#abc"))
        assertNull(validProfileValue(ProfileKey.STATUS, "hello\u0000world"))
        assertNull(validProfileValue(ProfileKey.HOMEPAGE, "javascript:alert(1)"))
        assertNull(validProfileValue(ProfileKey.HOMEPAGE, "https://someone@example.com/"))
        assertEquals("http://example.com/", validProfileValue(ProfileKey.HOMEPAGE, "http://example.com/"))
        assertNull(validProfileValue(ProfileKey.AVATAR, "http://example.com/avatar.png"))
        assertEquals("https://example.com/{size}.png", validProfileValue(ProfileKey.AVATAR, "https://example.com/{size}.png"))
        assertFalse(profileWireFits(metadataSet("*", ProfileKey.STATUS, "🙋".repeat(128))))
        assertTrue(profileWireFits(metadataSet("*", ProfileKey.STATUS, "🙋".repeat(120))))
    }

    @Test fun metadata_recipient_is_not_the_metadata_target() {
        val line = IrcMessage.parse(":srv 761 other alice display-name * :Alice Human")
        assertNull(profileValue(line, METADATA_CAP, "alice", String::lowercase))
        val wildcard = IrcMessage.parse(":srv 761 * alice display-name * :Alice Human")
        assertNull(profileValue(wildcard, METADATA_CAP, "me", String::lowercase))
        assertEquals(
            ProfileValue("alice", ProfileKey.DISPLAY_NAME, "Alice Human"),
            profileValue(wildcard, METADATA_V3_CAP, "me", String::lowercase),
        )
        assertEquals(
            ProfileValue("alice", ProfileKey.DISPLAY_NAME, null),
            profileValue(IrcMessage.parse(":srv METADATA alice display-name *"), METADATA_CAP, "me", String::lowercase),
        )
    }
}
