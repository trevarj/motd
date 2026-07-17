package io.github.trevarj.motd.irc.fuzz

import io.github.trevarj.motd.irc.client.EventMapper
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.proto.Prefix
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMapperGenerativeTest {
    @Test
    fun generatedProtocolMessagesMapWithoutCrashesAndPreserveStrongMetadata() {
        SeededFuzz.run(
            target = "event-mapper",
            version = 1,
            prCases = 2_000,
            nightlyCases = 75_000,
            replayTest = javaClass.name,
        ) { fuzz ->
            val now = 1_700_000_000_000L + fuzz.index
            val mapper = EventMapper({ "me" }, { Isupport() }, { now })
            when (fuzz.random.nextInt(3)) {
                0 -> fuzz.validChatCase(mapper, now)
                1 -> fuzz.validReactionCase(mapper)
                else -> {
                    val message = fuzz.random.structuralMessage()
                    fuzz.record("structural command=${message.command} params=${message.params.map { it.quotedSummary() }}")
                    val replies = mutableListOf<IrcMessage>()
                    runCatching { mapper.map(message, batchId = "batch-${fuzz.index}", replies::add) }
                        .getOrElse { throw AssertionError("EventMapper threw for a structural message", it) }
                    assertTrue(replies.size <= 1)
                }
            }
        }
    }
}

private fun FuzzCase.validReactionCase(mapper: EventMapper) {
    val emoji = listOf("👍", "👩‍💻", "A", "\u202e🙂\u2069").random(random)
    val targetMsgid = if (random.nextBoolean()) "Target-${index}" else "target-${index}"
    val unreact = random.nextInt(5) == 0
    val modern = random.nextBoolean()
    val reactionKey = when {
        unreact && modern -> "+unreact"
        unreact -> "draft/unreact"
        modern -> "+react"
        else -> "draft/react"
    }
    val replyKey = if (modern) "+reply" else "draft/reply"
    val message = IrcMessage(
        tags = linkedMapOf(
            "msgid" to "reaction-event-${index}",
            reactionKey to emoji,
            replyKey to targetMsgid,
        ),
        source = Prefix("alice"),
        command = "TAGMSG",
        params = listOf("#room"),
    )
    record("reaction key=$reactionKey emoji=${emoji.quotedSummary()} target=$targetMsgid")
    val event = mapper.map(message)
    if (unreact) {
        assertTrue(event is IrcEvent.Raw)
    } else {
        event as IrcEvent.TagMessage
        assertEquals(emoji, event.reactEmoji)
        assertEquals(targetMsgid, event.reactTargetMsgid)
        assertEquals("reaction-event-${index}", event.ctx.msgid)
    }
}

private fun FuzzCase.validChatCase(mapper: EventMapper, now: Long) {
    val actor = listOf("alice", "ALICE", "a[]\\~", "Женя").random(random)
    val target = if (random.nextBoolean()) "#room" else "me"
    val msgid = if (random.nextBoolean()) "Case-${index}" else "case-${index}"
    val reply = "parent-${index}"
    val validTime = random.nextBoolean()
    val time = if (validTime) "2026-07-16T19:09:19.123Z" else "not-a-time-${index}"
    val mode = random.nextInt(5)
    val body = random.mapperText(if (random.nextInt(32) == 0) 65_536 else random.nextInt(0, 96))
    val command = if (random.nextBoolean()) "PRIVMSG" else "NOTICE"
    val wireBody = when (mode) {
        1 -> "\u0001ACTION $body\u0001"
        2 -> "\u0001ACTION\u0001"
        3 -> "\u0001VERSION\u0001"
        4 -> "\u0001UNKNOWN $body\u0001"
        else -> body
    }
    val message = IrcMessage(
        tags = linkedMapOf(
            "msgid" to msgid,
            "label" to "label-${index}",
            "account" to "account-${index}",
            "time" to time,
            "draft/reply" to reply,
        ),
        source = Prefix(actor, "user", "host"),
        command = command,
        params = listOf(target, wireBody),
    )
    record("chat command=$command actor=$actor target=$target mode=$mode msgid=$msgid body=${body.quotedSummary()}")
    val replies = mutableListOf<IrcMessage>()
    val event = mapper.map(message, ctcpReply = replies::add)

    if (mode == 3 && command == "PRIVMSG") {
        assertNull(event)
        assertEquals(1, replies.size)
        return
    }
    if (mode == 3 || mode == 4) {
        assertNull(event)
        assertTrue(replies.isEmpty())
        return
    }

    event as IrcEvent.ChatMessage
    assertEquals(msgid, event.ctx.msgid)
    assertEquals("label-${index}", event.ctx.label)
    assertEquals("account-${index}", event.ctx.account)
    assertEquals(reply, event.replyToMsgid)
    assertEquals(if (validTime) ServerTimeSource.TAG else ServerTimeSource.LOCAL, event.ctx.serverTimeSource)
    assertEquals(if (validTime) 1_784_228_959_123L else now, event.ctx.serverTime)
    assertEquals(actor, event.source.nick)
    assertEquals(target, event.target)
    assertEquals(if (mode == 1) body else if (mode == 2) "" else body, event.text)
    assertEquals(
        when (mode) {
            1, 2 -> IrcEvent.ChatKind.ACTION
            else -> if (command == "NOTICE") IrcEvent.ChatKind.NOTICE else IrcEvent.ChatKind.PRIVMSG
        },
        event.kind,
    )
}

private fun Random.structuralMessage(): IrcMessage {
    val commands = listOf(
        "PRIVMSG", "NOTICE", "TAGMSG", "JOIN", "PART", "QUIT", "KICK", "NICK", "TOPIC",
        "MODE", "INVITE", "AWAY", "ACCOUNT", "CHGHOST", "SETNAME", "MARKREAD", "BOUNCER",
        "353", "366", "354", "730", "731", "732", "733", "734", "001", "999", "UNKNOWN",
    )
    val tags = buildMap {
        if (nextBoolean()) put("time", if (nextBoolean()) "2026-07-16T19:09:19Z" else mapperText(24))
        if (nextBoolean()) put("msgid", mapperText(16))
        if (nextBoolean()) put("+react", mapperText(8))
        if (nextBoolean()) put("+unreact", mapperText(8))
        if (nextBoolean()) put("+reply", mapperText(12))
    }
    val count = nextInt(0, 7)
    return IrcMessage(
        tags = tags,
        source = if (nextBoolean()) Prefix(mapperText(nextInt(0, 24))) else null,
        command = commands.random(this),
        params = List(count) { mapperText(if (nextInt(64) == 0) 65_536 else nextInt(0, 80)) },
    )
}

private fun Random.mapperText(length: Int): String = buildString(length) {
    val controls = listOf('\u0000', '\u0001', '\u0002', '\u000f', '\u202a', '\u202e', '\u2066', '\u2069')
    repeat(length) {
        append(if (nextInt(8) == 0) controls.random(this@mapperText) else nextInt(0x20, 0xd800).toChar())
    }
}
