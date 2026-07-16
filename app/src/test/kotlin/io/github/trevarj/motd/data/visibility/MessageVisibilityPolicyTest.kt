package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.FoolsMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageVisibilityPolicyTest {
    @Test
    fun `join part quit follow timeline setting but never preview or activity`() {
        for (kind in JOIN_PART_QUIT_KINDS) {
            val message = message(kind = kind)
            val shown = MessageVisibilityPolicy(MessageVisibilitySpec(showJoinPartQuit = true))
            val hidden = MessageVisibilityPolicy(MessageVisibilitySpec(showJoinPartQuit = false))

            assertTrue(shown.timeline(message))
            assertFalse(hidden.timeline(message))
            assertFalse(shown.preview(message))
            assertFalse(shown.activity(message))
            assertFalse(shown.visibleUnread(message))
            assertTrue(shown.anchor(message))
            assertTrue(shown.effectiveBottom(message))
            assertFalse(hidden.anchor(message))
            assertFalse(hidden.effectiveBottom(message))
        }
    }

    @Test
    fun `collapsed fool is presented and searchable but excluded from state consumers`() {
        val fool = message(sender = "Alice")
        val policy = policy(FoolsMode.COLLAPSE)

        assertTrue(policy.timeline(fool))
        assertTrue(policy.search(fool))
        assertTrue(policy.isFool(fool))
        assertFalse(policy.preview(fool))
        assertFalse(policy.activity(fool))
        assertFalse(policy.visibleUnread(fool))
        assertFalse(policy.anchor(fool))
        assertFalse(policy.effectiveBottom(fool))
    }

    @Test
    fun `hidden fool is excluded everywhere`() {
        val fool = message(sender = "alice")
        val policy = policy(FoolsMode.HIDE)

        assertFalse(policy.timeline(fool))
        assertFalse(policy.search(fool))
        assertFalse(policy.preview(fool))
        assertFalse(policy.visibleUnread(fool))
        assertFalse(policy.anchor(fool))
    }

    @Test
    fun `temporarily revealed hidden fool is timeline-only`() {
        val fool = message(sender = "alice")
        val policy = MessageVisibilityPolicy(
            MessageVisibilitySpec(
                fools = setOf("alice"),
                foolsMode = FoolsMode.HIDE,
                revealHiddenFools = true,
            ),
        )

        assertTrue(policy.timeline(fool))
        assertFalse(policy.search(fool))
        assertFalse(policy.preview(fool))
        assertFalse(policy.activity(fool))
        assertFalse(policy.visibleUnread(fool))
        assertFalse(policy.anchor(fool))
        assertFalse(policy.effectiveBottom(fool))
    }

    @Test
    fun `own and system rows are never treated as fools`() {
        val policy = policy(FoolsMode.HIDE)
        val own = message(sender = "alice", isSelf = true)
        val topic = message(sender = "alice", kind = MessageKind.TOPIC)

        assertFalse(policy.isFool(own))
        assertTrue(policy.timeline(own))
        assertTrue(policy.preview(own))
        assertFalse(policy.visibleUnread(own))
        assertFalse(policy.isFool(topic))
        assertTrue(policy.preview(topic))
    }

    private fun policy(mode: FoolsMode) = MessageVisibilityPolicy(
        MessageVisibilitySpec(fools = setOf("alice"), foolsMode = mode),
    )

    private fun message(
        sender: String = "bob",
        kind: MessageKind = MessageKind.PRIVMSG,
        isSelf: Boolean = false,
    ) = MessageEntity(
        id = 1,
        bufferId = 1,
        serverTime = 1,
        sender = sender,
        kind = kind,
        text = "text",
        isSelf = isSelf,
        dedupKey = "key",
    )
}
