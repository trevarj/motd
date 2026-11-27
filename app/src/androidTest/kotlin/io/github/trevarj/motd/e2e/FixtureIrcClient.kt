package io.github.trevarj.motd.e2e

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Small upstream client for deterministic fixture traffic; it never participates in app state. */
class FixtureIrcClient private constructor(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val writer: BufferedWriter,
    val nick: String,
) : Closeable {
    companion object {
        private val sequence = AtomicInteger()

        fun connect(
            args: FixtureArgs,
            messageTags: Boolean = false,
        ): FixtureIrcClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(args.host, args.ergoPort), 5_000)
                socket.soTimeout = 500
                val suffix = args.runId.filter(Char::isLetterOrDigit).takeLast(5)
                val nick = "j${suffix}${sequence.incrementAndGet()}"
                return FixtureIrcClient(
                    socket,
                    BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)),
                    BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)),
                    nick,
                ).apply {
                    if (messageTags) {
                        send("CAP REQ :message-tags")
                        val cap =
                            IrcMessage.parse(
                                await("message-tags capability") {
                                    val message = IrcMessage.parse(it)
                                    message.command == "CAP" && message.params.getOrNull(1) in listOf("ACK", "NAK")
                                },
                            )
                        check(cap.params.drop(1) == listOf("ACK", "message-tags")) { "Reaction peer needs message-tags" }
                        send("CAP END")
                    }
                    send("NICK $nick")
                    send("USER journey 0 * :motd headless journey")
                    await("registration") { it.contains(" 001 $nick ") }
                    send("NICKSERV IDENTIFY ${args.ergoUser} ${args.ergoPassword}")
                    if (messageTags) {
                        send("WHOIS $nick")
                        val result =
                            await("authenticated reaction peer") { it.contains(" 330 $nick $nick ") || it.contains(" 318 $nick $nick ") }
                        check(result.contains(" 330 $nick $nick ${args.ergoUser} ")) { "Reaction peer did not authenticate" }
                    }
                    send("JOIN ${args.channel}")
                    await("own channel join") {
                        val message = IrcMessage.parse(it)
                        message.command == "JOIN" && message.source?.nick == nick && message.params.firstOrNull() == args.channel
                    }
                }
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }
    }

    fun sendMessage(
        target: String,
        text: String,
    ) {
        require('\r' !in text && '\n' !in text)
        send("PRIVMSG $target :$text")
    }

    fun sendReaction(
        target: String,
        msgid: String,
        emoji: String,
        remove: Boolean = false,
    ) {
        val reactionTag = if (remove) "+draft/unreact" else "+draft/react"
        send(IrcMessage(command = "TAGMSG", params = listOf(target), tags = mapOf("+reply" to msgid, reactionTag to emoji)).serialize())
    }

    /** A PING barrier proves Ergo consumed every prior message before the app reconnects. */
    fun flushThroughServer(token: String) {
        send("PING :$token")
        // Ergo may serialize the final PONG parameter with or without a leading colon.
        await("PING barrier") {
            it.contains(" PONG ") && it.substringAfterLast(' ').removePrefix(":") == token
        }
    }

    override fun close() {
        runCatching { send("QUIT :journey complete") }
        runCatching { socket.close() }
    }

    private fun send(line: String) {
        writer.write(line)
        writer.write("\r\n")
        writer.flush()
    }

    private fun await(
        label: String,
        timeoutMs: Long = 15_000,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val tail = ArrayDeque<String>()
        while (System.nanoTime() < deadline) {
            val line =
                try {
                    reader.readLine()
                } catch (_: SocketTimeoutException) {
                    continue
                } ?: error("Ergo closed while waiting for $label")
            if (line.startsWith("PING ")) send("PONG ${line.substringAfter(' ')}")
            if (tail.size == 12) tail.removeFirst()
            tail.addLast(line)
            if (predicate(line)) return line
        }
        throw AssertionError("timed out waiting for $label; tail=${tail.joinToString(" | ")}")
    }
}
