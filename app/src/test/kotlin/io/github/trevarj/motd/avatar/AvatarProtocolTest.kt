package io.github.trevarj.motd.avatar

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarProtocolTest {
    @Test fun builds_subscribe_sync_publish_and_remove_commands() {
        assertEquals("METADATA * SUB avatar", subscribeAvatarMessage().serialize())
        assertEquals("METADATA #chat SYNC", syncAvatarMessage("#chat").serialize())
        assertEquals(
            "METADATA * SET avatar https://example.com/a/{size}.png",
            publishAvatarMessage("https://example.com/a/{size}.png").serialize(),
        )
        assertEquals("METADATA * SET avatar", publishAvatarMessage(null).serialize())
    }

    @Test fun parses_notification_snapshot_removal_and_delayed_sync() {
        assertEquals(
            AvatarMetadataEvent.Changed("alice", "https://example.com/a.png"),
            parseAvatarMetadata(
                IrcMessage(command = "METADATA", params = listOf("alice", "avatar", "*", "https://example.com/a.png")),
            ),
        )
        assertEquals(
            AvatarMetadataEvent.Changed("alice", "https://example.com/a.png"),
            parseAvatarMetadata(
                IrcMessage(command = "761", params = listOf("me", "alice", "avatar", "*", "https://example.com/a.png")),
            ),
        )
        assertEquals(
            AvatarMetadataEvent.Removed("alice"),
            parseAvatarMetadata(IrcMessage(command = "766", params = listOf("me", "alice", "avatar", "not set"))),
        )
        assertEquals(
            AvatarMetadataEvent.SyncLater("#chat", 30),
            parseAvatarMetadata(IrcMessage(command = "774", params = listOf("me", "#chat", "30"))),
        )
    }

    @Test fun ignores_other_keys_and_treats_invalid_avatar_values_as_removal() {
        assertNull(
            parseAvatarMetadata(
                IrcMessage(command = "METADATA", params = listOf("alice", "homepage", "*", "https://example.com")),
            ),
        )
        assertEquals(
            AvatarMetadataEvent.Removed("alice"),
            parseAvatarMetadata(
                IrcMessage(command = "METADATA", params = listOf("alice", "avatar", "*", "http://example.com/a.png")),
            ),
        )
    }

    @Test fun validates_https_and_expands_bounded_size_placeholder() {
        assertEquals("https://example.com/a/{size}.png", validateAvatarUrl("https://example.com/a/{size}.png"))
        assertEquals("https://example.com/a/96.png", expandAvatarUrl("https://example.com/a/{size}.png", 96))
        assertEquals("https://example.com/a/512.png", expandAvatarUrl("https://example.com/a/{size}.png", 900))
        assertNull(validateAvatarUrl("http://example.com/a.png"))
        assertNull(validateAvatarUrl("https://user:pass@example.com/a.png"))
        assertNull(validateAvatarUrl("not a url"))
    }
}
