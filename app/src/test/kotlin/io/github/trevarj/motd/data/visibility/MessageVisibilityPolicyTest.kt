package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageVisibilityPolicyTest {
    @Test
    fun `presence events follow timeline setting but never preview or activity`() {
        for (kind in PRESENCE_KINDS) {
            val message = message(kind = kind)
            val shown = MessageVisibilityPolicy(MessageVisibilitySpec(presenceMode = PresenceMode.ALL))
            val hidden = MessageVisibilityPolicy(MessageVisibilitySpec(presenceMode = PresenceMode.HIDDEN))

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
    fun `redaction tombstones follow their display setting`() {
        val redacted = message(kind = MessageKind.REDACTED)
        val shown = MessageVisibilityPolicy(MessageVisibilitySpec(showRedactedMessages = true))
        val hidden = MessageVisibilityPolicy(MessageVisibilitySpec(showRedactedMessages = false))

        assertTrue(shown.timeline(redacted))
        assertTrue(shown.preview(redacted))
        assertFalse(hidden.timeline(redacted))
        assertFalse(hidden.preview(redacted))
        assertFalse(redacted.kind in CONVERSATION_KINDS)
    }

    @Test
    fun `collapsed fool remains unread and anchorable without preview activity`() {
        val fool = message(sender = "Alice")
        val policy = policy(FoolsMode.COLLAPSE)

        assertTrue(policy.timeline(fool))
        assertTrue(policy.search(fool))
        assertTrue(policy.isFool(fool))
        assertFalse(policy.preview(fool))
        assertFalse(policy.activity(fool))
        assertTrue(policy.visibleUnread(fool))
        assertTrue(policy.anchor(fool))
        assertTrue(policy.effectiveBottom(fool))
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
        val policy =
            MessageVisibilityPolicy(
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

    @Test
    fun `payload-bearing incoming file offer counts as visible unread`() {
        // The chat-list cue counts payload-bearing DCC_TRANSFER rows, so the unread predicate must
        // too — otherwise the badge shows N > 0 for a room whose only unread is a file offer while
        // entry resolves no unread anchor and parks at the bottom with no divider.
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())
        val offer = message(kind = MessageKind.DCC_TRANSFER).copy(eventPayload = "payload-v1")
        val record = message(kind = MessageKind.DCC_TRANSFER)
        val ownOffer =
            message(kind = MessageKind.DCC_TRANSFER, isSelf = true)
                .copy(eventPayload = "payload-v1")

        assertTrue(policy.visibleUnread(offer))
        assertFalse("a payload-less transfer record is not an attention cue", policy.visibleUnread(record))
        assertFalse("own offers were sent, not unread", policy.visibleUnread(ownOffer))
    }

    @Test
    fun `configured fools use the network casemap used by normalized actors`() {
        val stored = message(sender = "[Alice").copy(normalizedActor = "{alice")
        val spec = MessageVisibilitySpec(fools = setOf("[alice"), foolsMode = FoolsMode.HIDE)

        assertTrue(
            MessageVisibilityPolicy(spec, IrcIdentityRules(IrcCaseMapping.Rfc1459)).isFool(stored),
        )
        assertFalse(
            MessageVisibilityPolicy(spec, IrcIdentityRules(IrcCaseMapping.Ascii)).isFool(stored),
        )
    }

    @Test
    fun `account identity keeps fool status across nick changes`() {
        val policy =
            MessageVisibilityPolicy(
                MessageVisibilitySpec(fools = setOf("stable-account")),
            )
        val before = message(sender = "old-nick").copy(senderAccount = "stable-account")
        val after =
            message(sender = "new-nick").copy(
                senderAccount = "stable-account",
                normalizedActor = "new-nick",
            )

        assertTrue(policy.isFool(before))
        assertTrue(policy.isFool(after))
    }

    @Test
    fun `mixed visibility separates ordinary unread from opted in attention`() {
        val spec =
            MessageVisibilitySpec(
                fools = setOf("a", "b", "c"),
                foolPreferences =
                    mapOf(
                        "b" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true),
                        "c" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                    ),
            )
        val rows = listOf("a", "b", "c").map { message(sender = it).copy(hasMention = true) }
        for (reveal in listOf(false, true)) {
            val policy = MessageVisibilityPolicy(spec.copy(revealHiddenFools = reveal))
            assertEquals(if (reveal) listOf("a", "b", "c") else listOf("a", "b"), rows.filter(policy::timeline).map { it.sender })
            assertEquals(listOf("a", "b"), rows.filter(policy::visibleUnread).map { it.sender })
            assertEquals(listOf("a", "b"), rows.filter(policy::anchor).map { it.sender })
            assertEquals(listOf("a", "b"), rows.filter(policy::effectiveBottom).map { it.sender })
            assertEquals(listOf("b"), rows.filter { policy.mentionAttention(it, directMessage = false) }.map { it.sender })
            assertTrue(policy.allowsNotification(null, "c", directMessage = false, hasMention = true))
            assertFalse(policy.mentionAttention(rows[1], directMessage = true))
            assertFalse(policy.allowsNotification(null, "b", directMessage = false, hasMention = false))
        }
    }

    @Test
    fun `account and actor collisions merge visibility and permissions independently of order`() {
        for (reverse in listOf(false, true)) {
            val entries = listOf("actor", "account").let { if (reverse) it.reversed() else it }
            val policy =
                MessageVisibilityPolicy(
                    MessageVisibilitySpec(
                        fools = entries.toSet(),
                        foolPreferences =
                            mapOf(
                                "actor" to FoolPreferences(FoolsMode.DIMMED, notifyMentions = true),
                                "account" to FoolPreferences(FoolsMode.HIDE, notifyDirectMessages = true),
                            ),
                    ),
                )
            assertEquals(FoolPreferences(FoolsMode.HIDE), policy.foolPreferences("account", "actor"))
            assertFalse(policy.allowsNotification("account", "actor", directMessage = false, hasMention = true))
            assertFalse(policy.allowsNotification("account", "actor", directMessage = true, hasMention = true))
            assertNull(policy.foolPreferences(message(sender = "actor", isSelf = true)))
            assertNull(policy.foolPreferences(message(sender = "actor", kind = MessageKind.TOPIC)))
            assertFalse(policy.mentionAttention(message(sender = "actor", isSelf = true).copy(hasMention = true), false))
            assertFalse(policy.mentionAttention(message(sender = "actor", kind = MessageKind.TOPIC).copy(hasMention = true), false))
        }
    }

    @Test
    fun `direct messages use only the direct permission even with a mention`() {
        val policy =
            MessageVisibilityPolicy(
                MessageVisibilitySpec(
                    fools = setOf("alice"),
                    foolPreferences = mapOf("alice" to FoolPreferences(notifyDirectMessages = true)),
                ),
            )
        for (mention in listOf(false, true)) {
            assertTrue(policy.allowsNotification(null, "alice", directMessage = true, hasMention = mention))
            assertFalse(policy.allowsNotification(null, "alice", directMessage = false, hasMention = mention))
        }
        assertTrue(policy.mentionAttention(message(sender = "alice").copy(hasMention = true), true))
        assertTrue(policy.allowsNotification(null, "ordinary", false, false))
        assertNull(policy.foolPreferences(null, "ordinary"))
    }

    @Test
    fun `every casemap resolves equivalent configured records conservatively`() {
        for (mapping in listOf(IrcCaseMapping.Rfc1459, IrcCaseMapping.Rfc1459Strict, IrcCaseMapping.Ascii)) {
            val rules = IrcIdentityRules(mapping)
            val spec =
                MessageVisibilitySpec(
                    fools = setOf("nick[", "nick{", "nick~"),
                    foolPreferences =
                        mapOf(
                            "nick[" to FoolPreferences(FoolsMode.DIMMED, notifyMentions = true, notifyDirectMessages = true),
                            "nick{" to FoolPreferences(FoolsMode.COLLAPSE),
                            "nick~" to FoolPreferences(FoolsMode.HIDE),
                        ),
                )
            val policy = MessageVisibilityPolicy(spec, rules)
            assertEquals(if (mapping == IrcCaseMapping.Ascii) FoolsMode.DIMMED else FoolsMode.COLLAPSE, policy.foolPreferences(null, rules.normalize("nick["))?.visibility)
            assertEquals(mapping == IrcCaseMapping.Ascii, policy.allowsNotification(null, rules.normalize("nick["), false, true))
            assertEquals(mapping == IrcCaseMapping.Rfc1459, policy.isFool(message(sender = "nick^").copy(normalizedActor = rules.normalize("nick^"))))
        }
    }

    private fun policy(mode: FoolsMode) =
        MessageVisibilityPolicy(
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
        normalizedActor = sender.lowercase(),
        kind = kind,
        text = "text",
        isSelf = isSelf,
        dedupKey = "key",
    )
}
