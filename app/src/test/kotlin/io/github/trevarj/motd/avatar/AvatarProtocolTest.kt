package io.github.trevarj.motd.avatar

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarProtocolTest {
    @Test fun builds_subscribe_sync_publish_and_remove_commands() {
        assertEquals("METADATA * SUB avatar", subscribeMetadataMessage(AVATAR_KEY).serialize())
        assertEquals("METADATA * UNSUB avatar", unsubscribeMetadataMessage(AVATAR_KEY).serialize())
        assertEquals("METADATA #chat SYNC", syncMetadataMessage("#chat").serialize())
        assertEquals(
            "METADATA * SET avatar https://example.com/a/{size}.png",
            publishAvatarMessage("https://example.com/a/{size}.png").serialize(),
        )
        assertEquals("METADATA * SET avatar", publishAvatarMessage(null).serialize())
        assertEquals(
            "METADATA #chat SET avatar https://example.com/channel.png",
            channelAvatarMessage("#chat", "https://example.com/channel.png").serialize(),
        )
        assertEquals("METADATA #chat SET avatar", channelAvatarMessage("#chat", null).serialize())
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

    @Test fun rejectsIncompleteAvatarMetadata() {
        listOf(
            IrcMessage(command = "METADATA", params = listOf("#chat", AVATAR_KEY, "*")),
            IrcMessage(command = "761", params = listOf("me", "#chat", AVATAR_KEY, "*")),
            IrcMessage(command = "766", params = listOf("me", "#chat", AVATAR_KEY)),
        ).forEach { assertNull(parseAvatarMetadata(it)) }
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
        val receiveOnly =
            setOf(
                "batch",
                "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1",
            )
        assertTrue(supportsMetadataSubscription(receiveOnly))
        assertFalse(supportsAvatarMutation(receiveOnly))
        assertFalse(supportsAvatarPublishing(receiveOnly))

        val capable = setOf("draft/metadata-2=max-subs=4,max-keys=2,max-value-bytes=128")
        assertTrue(supportsMetadataSubscription(capable))
        assertTrue(supportsAvatarMutation(capable))
        assertTrue(supportsAvatarPublishing(capable, "https://example.com/avatar.png"))
        assertFalse(supportsAvatarPublishing(capable, "https://example.com/${"x".repeat(128)}"))
    }

    @Test fun detects_labeled_metadata_rejections() {
        assertTrue(avatarMetadataRejected(listOf(IrcMessage(command = "769", params = listOf("me")))))
        assertTrue(avatarMetadataRejected(listOf(IrcMessage(command = "FAIL", params = listOf("METADATA")))))
        assertFalse(avatarMetadataRejected(listOf(IrcMessage(command = "761", params = listOf("me")))))
        assertFalse(avatarMetadataRejected(listOf(IrcMessage(command = "766", params = listOf("me")))))
    }

    @Test fun absent_limits_allow_and_zero_or_malformed_limits_deny() {
        assertTrue(supportsMetadataSubscription(setOf("draft/metadata-2")))
        assertTrue(supportsAvatarPublishing(setOf("draft/metadata-2")))
        assertFalse(supportsMetadataSubscription(setOf("draft/metadata-2=max-subs=0")))
        assertFalse(supportsAvatarPublishing(setOf("draft/metadata-2=max-value-bytes=oops")))
        assertFalse(supportsMetadataSubscription(setOf("batch")))
        assertFalse(supportsAvatarMutation(setOf("batch")))
        assertFalse(supportsAvatarPublishing(setOf("batch")))
    }

    @Test fun v3_prefers_its_own_limits_and_counts_utf8_bytes() {
        val v3 = "$METADATA_V3_CAP=before-connect=ignored,max-subs=0,max-key-bytes=6,max-value-bytes=13,unknown=0"
        val legacy = "$METADATA_CAP=max-value-bytes=1"
        val caps = setOf("batch", legacy, v3)
        assertFalse(supportsMetadataSubscription(caps))
        assertTrue(supportsAvatarMutation(caps))
        assertTrue(supportsAvatarPublishing(caps, "https://a.b/a"))
        assertFalse(supportsAvatarPublishing(caps, "https://a.b/é"))
        assertFalse(supportsAvatarPublishing(setOf("batch", "$METADATA_V3_CAP=max-key-bytes=5")))
        assertFalse(supportsAvatarMutation(setOf("batch", "$METADATA_V3_CAP=max-key-bytes=5")))
        assertFalse(supportsAvatarPublishing(setOf(METADATA_V3_CAP)))
        assertTrue(supportsAvatarPublishing(setOf("batch", METADATA_V3_CAP)))
        val noNewKeys = setOf("batch", "$METADATA_V3_CAP=max-keys=0")
        assertFalse(supportsAvatarPublishing(noNewKeys))
        assertTrue(supportsAvatarMutation(noNewKeys))
    }

    @Test fun v3_only_accepts_numeric_notifications_and_well_formed_sync_delays() {
        assertEquals(
            AvatarMetadataEvent.Changed("alice", "https://example.com/avatar.png"),
            parseAvatarMetadata(IrcMessage.parse(":srv 761 me alice avatar * :https://example.com/avatar.png"), v3 = true),
        )
        assertEquals(
            AvatarMetadataEvent.Removed("alice"),
            parseAvatarMetadata(IrcMessage.parse(":srv 766 me alice avatar :not set"), v3 = true),
        )
        assertEquals(
            AvatarMetadataEvent.SyncLater("*ALL", 0),
            parseAvatarMetadata(IrcMessage.parse(":srv 774 me *ALL 0 :Try later"), v3 = true),
        )
        listOf(
            ":srv METADATA alice avatar * :https://example.com/avatar.png",
            ":srv 761 me alice avatar :https://example.com/avatar.png",
            ":srv 774 me *ALL 3",
            ":srv 774 me *ALL -1 :Try later",
        ).forEach { assertNull(parseAvatarMetadata(IrcMessage.parse(it), v3 = true)) }
    }
}
