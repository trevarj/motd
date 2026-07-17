package io.github.trevarj.motd.irc.fuzz

import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.IrcParseException
import io.github.trevarj.motd.irc.proto.Prefix
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcMessageGenerativeTest {
    @Test
    fun generatedMessagesRoundTripAndMalformedLinesFailOnlyThroughProtocolErrors() {
        SeededFuzz.run(
            target = "irc-message",
            version = 1,
            prCases = 5_000,
            nightlyCases = 200_000,
            replayTest = javaClass.name,
        ) { fuzz ->
            if (fuzz.random.nextInt(3) != 0) {
                val message = fuzz.random.validMessage()
                fuzz.record("roundtrip ${message.summary()}")
                val wire = message.serialize()
                val parsed = IrcMessage.parse(wire)
                assertEquals(message.copy(command = message.command.uppercase()), parsed)
                assertTrue(wire.toByteArray(Charsets.UTF_8).size <= 8_705)
            } else {
                val raw = fuzz.random.rawLine()
                fuzz.record("raw ${raw.quotedSummary()}")
                val parsed = runCatching { IrcMessage.parse(raw) }
                val parseFailure = parsed.exceptionOrNull()
                assertTrue(
                    "Unexpected parser exception ${parseFailure?.javaClass?.name}",
                    parseFailure == null || parseFailure is IrcParseException,
                )
                parsed.getOrNull()?.let { message ->
                    val serialized = runCatching { message.serialize() }
                    val serializeFailure = serialized.exceptionOrNull()
                    assertTrue(
                        "Unexpected serializer exception ${serializeFailure?.javaClass?.name}",
                        serializeFailure == null || serializeFailure is IllegalArgumentException,
                    )
                }
            }
        }
    }
}

private fun Random.validMessage(): IrcMessage {
    val commands = listOf("PRIVMSG", "notice", "TAGMSG", "JOIN", "PART", "MODE", "001", "PING")
    val tags = linkedMapOf<String, String>()
    repeat(nextInt(0, 5)) { index ->
        tags["tag$index"] = wellFormedText(nextInt(0, 25))
    }
    val source = if (nextBoolean()) {
        val host = if (nextBoolean()) safeToken(1, 16) else null
        Prefix(
            nick = safeToken(1, 12),
            user = if (host != null && nextBoolean()) safeToken(1, 10) else null,
            host = host,
        )
    } else {
        null
    }
    val paramCount = nextInt(0, 5)
    val params = buildList {
        repeat(paramCount) { index ->
            val last = index == paramCount - 1
            add(if (last) wellFormedText(nextInt(0, 48)) else safeToken(1, 18))
        }
    }
    return IrcMessage(tags, source, commands.random(this), params)
}

private fun Random.rawLine(): String {
    val edgeLengths = intArrayOf(0, 1, 511, 512, 513, 8_191, 8_192, 65_536)
    val length = if (nextInt(32) == 0) edgeLengths.random(this) else nextInt(0, 384)
    return arbitraryUtf16(length)
}

private fun Random.safeToken(min: Int, max: Int): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#&[]{}|^-_."
    return buildString {
        repeat(nextInt(min, max + 1)) { append(alphabet[nextInt(alphabet.length)]) }
    }
}

private fun Random.wellFormedText(length: Int): String {
    val specials = intArrayOf(
        ' '.code, ':'.code, ';'.code, '\\'.code, '`'.code, '\u0001'.code, '\u0002'.code,
        '\u000f'.code, '\u202a'.code, '\u202e'.code, '\u2066'.code, '\u2069'.code,
        0x03a9, 0x0416, 0x4e2d, 0x1f642,
    )
    return buildString {
        repeat(length) {
            val codePoint = if (nextInt(4) == 0) specials.random(this@wellFormedText) else nextInt(0x21, 0x7f)
            appendCodePoint(codePoint)
        }
    }
}

private fun Random.arbitraryUtf16(length: Int): String = buildString(length) {
    repeat(length) {
        append(
            when (nextInt(12)) {
                0 -> nextInt(0x00, 0x20).toChar()
                1 -> nextInt(0xd800, 0xe000).toChar()
                2 -> listOf('\u202a', '\u202e', '\u2066', '\u2069').random(this@arbitraryUtf16)
                else -> nextInt(0x20, 0xd800).toChar()
            },
        )
    }
}

private fun IrcMessage.summary(): String =
    "command=$command tags=${tags.keys} source=${source?.nick} params=${params.map { it.quotedSummary() }}"

internal fun String.quotedSummary(limit: Int = 160): String {
    val escaped = take(limit).flatMap { char ->
        when (char) {
            '\n' -> listOf('\\', 'n')
            '\r' -> listOf('\\', 'r')
            '\t' -> listOf('\\', 't')
            else -> listOf(char)
        }
    }.joinToString("")
    return "\"$escaped${if (length > limit) "…(${length})" else ""}\""
}
