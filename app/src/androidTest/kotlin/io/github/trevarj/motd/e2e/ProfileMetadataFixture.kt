package io.github.trevarj.motd.e2e

import android.content.Context
import io.github.trevarj.motd.service.PinningTrustManager
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/** Private loopback IRC endpoint. Only the fixture owns the server state and sends its MODE events. */
internal class ProfileMetadataFixture(
    context: Context,
) : AutoCloseable {
    data class Write(
        val target: String,
        val key: String,
        val value: String?,
        val accepted: Boolean,
    )

    val host = "127.0.0.1"
    val nick = "profilee2e"
    val channel = "#ProfileE2e"
    val initialChannelName = "Original Room"
    val cap = "draft/metadata-3"
    private val password = "media-test".toCharArray()
    private val keys =
        KeyStore.getInstance("PKCS12").apply {
            context.assets.open("palettes/network-media.p12").use { load(it, password) }
        }
    val fingerprint = PinningTrustManager.sha256Hex(keys.getCertificate("media") as X509Certificate)
    private val tls =
        SSLContext.getInstance("TLS").apply {
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            init(managers.keyManagers, null, null)
        }
    private val listener = tls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName(host)) as ServerSocket
    val port: Int get() = listener.localPort
    private val lock = Any()
    private val failure = AtomicReference<Throwable?>()
    private val writes = mutableListOf<Write>()
    private val values = mutableMapOf((channel to "display-name") to initialChannelName)
    private val subscriptions = mutableSetOf<String>()
    private val modes = mutableSetOf<Char>()
    private var writer: BufferedWriter? = null
    private var sequence = 0
    private var joined = false
    private var rejectNext = false
    private var closed = false

    @Volatile private var active: Socket? = null

    private val worker =
        thread(name = "profile-metadata-tls-fixture", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    synchronized(lock) {
                        if (closed) return@thread
                        active = socket
                        writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    }
                    socket.getInputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) synchronized(lock) { handle(line) }
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    if (!closed) failure.set(error)
                }
            }
        }

    fun writeHistory(): List<Write> =
        synchronized(lock) {
            failure.get()?.let { throw AssertionError("Metadata fixture failed", it) }
            writes.toList()
        }

    fun rejectNextWrite() = synchronized(lock) { rejectNext = true }

    fun setMode(
        mode: Char,
        present: Boolean,
    ) {
        synchronized(lock) {
            check(joined && mode in Prefix)
            check(if (present) modes.add(mode) else modes.remove(mode))
            send(":operator!fixture@localhost MODE $channel ${if (present) "+" else "-"}$mode $nick")
        }
    }

    private fun send(
        line: String,
        label: String? = null,
    ) {
        val output = checkNotNull(writer)
        if (label != null) output.write("@label=$label ")
        output.write("$line\r\n")
        output.flush()
    }

    private fun numeric(
        code: String,
        args: String,
    ) = ":profile.test $code $nick $args"

    private fun reply(
        target: String,
        key: String,
        value: String?,
    ) = if (value == null) numeric("766", "$target $key :Key not set") else numeric("761", "$target $key * :$value")

    private fun batch(
        target: String,
        replies: List<String>,
        label: String? = null,
    ) {
        val id = "m${++sequence}"
        send(":profile.test BATCH +$id metadata $target", label)
        for (reply in replies) send("@batch=$id $reply")
        send(":profile.test BATCH -$id")
    }

    private fun names() {
        val prefix = Prefix.filterKeys { it in modes }.values.joinToString("")
        send(numeric("353", "= $channel :$prefix$nick !operator"))
        send(numeric("366", "$channel :End of NAMES"))
    }

    private fun snapshot(
        target: String,
        label: String? = null,
        registration: Boolean = false,
    ) {
        val records =
            values.mapNotNull { (pair, value) ->
                val (owner, key) = pair
                val visible = owner == target || joined && (target == "*ALL" || target == channel && owner == nick)
                reply(owner, key, value).takeIf { visible && (registration || key in subscriptions) }
            }
        batch(target, records, label)
    }

    private fun handle(line: String) {
        val tags = line.takeIf { it.startsWith('@') }?.substringBefore(' ')
        val label =
            tags
                ?.drop(1)
                ?.split(';')
                ?.firstOrNull { it.startsWith("label=") }
                ?.substringAfter('=')
        val body = if (tags == null) line else line.substringAfter(' ')
        val middle = body.substringBefore(" :").split(' ')
        val trailing = body.substringAfter(" :", "").takeIf { " :" in body }
        val args = middle.drop(1) + listOfNotNull(trailing)
        when (middle[0]) {
            "CAP" -> {
                when (args.first()) {
                    "LS" -> {
                        send(":profile.test CAP * LS :batch message-tags labeled-response multi-prefix $cap=max-subs=16,max-key-bytes=32,max-value-bytes=2048")
                    }

                    "REQ" -> {
                        send(":profile.test CAP * ACK :${args.last()}")
                    }

                    "END" -> {
                        send(numeric("001", ":Welcome"))
                        send(numeric("005", "CASEMAPPING=rfc1459 CHANTYPES=# PREFIX=(qov)!@+ CHANMODES=b,k,l,imnst :supported"))
                        snapshot(nick, registration = true)
                        send(numeric("376", ":End of MOTD"))
                    }
                }
            }

            "PING" -> {
                send(":profile.test PONG profile.test :${args.last()}")
            }

            "JOIN" -> {
                if (!joined && args.first().equals(channel, ignoreCase = true)) {
                    joined = true
                    send(":$nick!fixture@localhost JOIN $channel", label)
                    names()
                    snapshot(channel)
                }
            }

            "NAMES" -> {
                if (joined) names()
            }

            "MODE" -> {
                if (args.firstOrNull().equals(channel, ignoreCase = true)) send(numeric("324", "$channel +nt"), label)
            }

            "WHO" -> {
                if (args.firstOrNull().equals(channel, ignoreCase = true)) {
                    val prefix = Prefix.filterKeys { it in modes }.values.joinToString("")
                    send(numeric("352", "$channel fixture localhost profile.test $nick H$prefix :0 Test user"))
                    send(numeric("352", "$channel fixture localhost profile.test operator H! :0 Operator"))
                    send(numeric("315", "$channel :End of WHO"))
                }
            }

            "METADATA" -> {
                metadata(args, label)
            }
        }
    }

    private fun metadata(
        args: List<String>,
        label: String?,
    ) {
        val target =
            when {
                args[0] == "*" -> nick
                args[0].equals(channel, ignoreCase = true) -> channel
                else -> args[0]
            }
        val keys = args.drop(2)
        when (args[1]) {
            "SUB" -> {
                subscriptions += keys
                send(numeric("770", keys.joinToString(" ")), label)
                for ((pair, value) in values) {
                    val (owner, key) = pair
                    if (key in keys && (owner == nick || joined && owner == channel)) send(reply(owner, key, value))
                }
            }

            "UNSUB" -> {
                subscriptions -= keys.toSet()
                send(numeric("771", keys.joinToString(" ")), label)
            }

            "SYNC" -> {
                snapshot(target, label)
            }

            "GET" -> {
                batch(target, keys.map { key -> reply(target, key, values[target to key]) }, label)
            }

            "SET" -> {
                val key = keys.first()
                val value = keys.getOrNull(1)
                val accepted = !rejectNext && (target == nick || target == channel && modes.any { it == 'q' || it == 'o' })
                rejectNext = false
                writes += Write(target, key, value, accepted)
                if (!accepted) {
                    send(":profile.test FAIL METADATA KEY_NO_PERMISSION $target $key :Permission denied", label)
                    return
                }
                if (value == null) values.remove(target to key) else values[target to key] = value
                send(reply(target, key, value), label)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            listener.close()
            active?.close()
        }
        worker.join(2_000)
    }

    private companion object {
        val Prefix = linkedMapOf('q' to '!', 'o' to '@', 'v' to '+')
    }
}
