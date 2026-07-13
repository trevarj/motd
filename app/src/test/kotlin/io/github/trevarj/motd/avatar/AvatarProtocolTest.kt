package io.github.trevarj.motd.avatar

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun metadata_limits_distinguish_receive_and_publish_support() {
        val receiveOnly = setOf(
            "batch",
            "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1",
        )
        assertTrue(supportsAvatarSubscription(receiveOnly))
        assertFalse(supportsAvatarMutation(receiveOnly))
        assertFalse(supportsAvatarPublishing(receiveOnly))

        val capable = setOf("draft/metadata-2=max-subs=4,max-keys=2,max-value-bytes=128")
        assertTrue(supportsAvatarSubscription(capable))
        assertTrue(supportsAvatarMutation(capable))
        assertTrue(supportsAvatarPublishing(capable, "https://example.com/avatar.png"))
        assertFalse(supportsAvatarPublishing(capable, "https://example.com/${"x".repeat(128)}"))
    }

    @Test fun absent_limits_allow_and_zero_or_malformed_limits_deny() {
        assertTrue(supportsAvatarSubscription(setOf("draft/metadata-2")))
        assertTrue(supportsAvatarPublishing(setOf("draft/metadata-2")))
        assertFalse(supportsAvatarSubscription(setOf("draft/metadata-2=max-subs=0")))
        assertFalse(supportsAvatarPublishing(setOf("draft/metadata-2=max-value-bytes=oops")))
        assertFalse(supportsAvatarSubscription(setOf("batch")))
        assertFalse(supportsAvatarMutation(setOf("batch")))
        assertFalse(supportsAvatarPublishing(setOf("batch")))
    }
}
