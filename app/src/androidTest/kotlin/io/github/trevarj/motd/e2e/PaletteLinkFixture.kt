package io.github.trevarj.motd.e2e

import android.content.Context
import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.service.PinningTrustManager
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

internal class PaletteLinkFixture(
    context: Context,
) : AutoCloseable {
    private val bytes = context.assets.open("palettes/duplicate-last-valid.motd-palette.json").use { it.readBytes() }
    val document = decodePalette(bytes)
    val host = "www.youtube.com"
    val url = "https://$host/hosted-fixture.motd-palette.json?fixture=local"
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
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val requests = AtomicInteger()
    private val failure = AtomicReference<Throwable?>()

    @Volatile private var active: Socket? = null
    private val worker =
        thread(name = "palette-link-tls-fixture", isDaemon = true) {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        active = socket
                        socket.soTimeout = 10_000
                        val input = DataInputStream(socket.getInputStream())
                        check(input.readUnsignedByte() == 5)
                        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        check(methods.any { it == 0.toByte() })
                        socket.getOutputStream().apply {
                            write(byteArrayOf(5, 0))
                            flush()
                        }
                        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                        check(input.readUnsignedByte() == 3) { "Fixture requires proxy-side DNS" }
                        val destination = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                        check(destination == host && input.readUnsignedShort() == 443) { "Unexpected proxy destination" }
                        socket.getOutputStream().apply {
                            write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                            flush()
                        }
                        (tls.socketFactory.createSocket(socket, host, 443, true) as SSLSocket).use { secure ->
                            secure.useClientMode = false
                            secure.soTimeout = 10_000
                            secure.startHandshake()
                            val reader = secure.inputStream.bufferedReader(Charsets.US_ASCII)
                            check(reader.readLine() == "GET /hosted-fixture.motd-palette.json?fixture=local HTTP/1.1")
                            while (true) {
                                val line = checkNotNull(reader.readLine())
                                if (line.isEmpty()) break
                                check(!line.startsWith("Authorization:", true) && !line.startsWith("Proxy-Authorization:", true))
                            }
                            requests.incrementAndGet()
                            secure.outputStream.apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                write(bytes)
                                flush()
                            }
                        }
                        active = null
                    }
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            }
        }

    fun assertRequests(expected: Int) {
        failure.get()?.let { throw AssertionError("Local palette HTTPS fixture failed", it) }
        org.junit.Assert.assertEquals("Only the manual palette GET may reach the fixture", expected, requests.get())
    }

    override fun close() {
        server.close()
        active?.close()
        worker.join(2_000)
    }
}
