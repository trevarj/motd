package io.github.trevarj.motd.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.LinkPreviewFetchPolicy
import io.github.trevarj.motd.service.PinningTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkMediaHttpTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val releases = AtomicInteger()
    private val relaxed = LinkPreviewFetchPolicy(enforceDestinationPolicy = false)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `proxy redirects keep ranges and conditional reads anonymous while untagged calls stay ordinary`() {
        val http = NetworkMediaHttp({ id -> route(id, proxy = httpProxy()) }, relaxed)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://other.invalid/media"))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/8").setBody("abc"))
        val request =
            Request
                .Builder()
                .url("http://user:password@unresolvable.invalid/media")
                .header("Authorization", "Basic caller-secret")
                .header("Proxy-Authorization", "Basic proxy-secret")
                .header("Cookie", "session=secret")
                .header("Range", "bytes=2-4")
                .header("If-None-Match", "etag")
                .build()

        http.callFactory(7).newCall(request).execute().use { response ->
            assertEquals(0, releases.get())
            assertEquals(206, response.code)
            assertEquals("bytes 2-4/8", response.header("Content-Range"))
            assertEquals("abc", response.body.string())
        }
        assertEquals(1, releases.get())
        for (host in listOf("unresolvable.invalid", "other.invalid")) {
            val received = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(received.requestLine.contains(host))
            assertNull(received.getHeader("Authorization"))
            assertNull(received.getHeader("Proxy-Authorization"))
            assertNull(received.getHeader("Cookie"))
            assertEquals("bytes=2-4", received.getHeader("Range"))
            assertEquals("etag", received.getHeader("If-None-Match"))
            assertTrue(!received.requestLine.contains("password"))
        }

        server.enqueue(MockResponse().setBody("ordinary"))
        http.client
            .newCall(
                Request
                    .Builder()
                    .url(server.url("/avatar"))
                    .header("Authorization", "ordinary-auth")
                    .build(),
            ).execute()
            .use {
                assertEquals("ordinary", it.body.string())
            }
        assertEquals("ordinary-auth", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals(1, releases.get())
    }

    @Test
    fun `missing broken and cache-only routes never open a connection`() {
        val resolutions = AtomicInteger()
        val http =
            NetworkMediaHttp(
                { id ->
                    resolutions.incrementAndGet()
                    if (id == 1L) null else route(id, proxyError = "SOCKS unavailable")
                },
                relaxed,
            )
        val request = Request.Builder().url(server.url("/must-not-fetch")).build()
        for (id in listOf(null, 1L, 2L)) {
            assertThrows(IOException::class.java) {
                http
                    .callFactory(id)
                    .newCall(request)
                    .execute()
                    .close()
            }
        }
        assertEquals(2, resolutions.get())
        assertEquals(1, releases.get())
        http.callFactory(2).newCall(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()).execute().use {
            assertEquals(504, it.code)
        }
        assertEquals(2, resolutions.get())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redirect cap closes every response and releases one lease`() {
        val http = NetworkMediaHttp({ id -> route(id, proxy = httpProxy()) }, relaxed.copy(maxRedirects = 1))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again").setBody("redirect")) }
        assertThrows(IOException::class.java) {
            http
                .callFactory(7)
                .newCall(Request.Builder().url("http://unresolvable.invalid/loop").build())
                .execute()
                .close()
        }
        assertEquals(2, server.requestCount)
        assertEquals(1, releases.get())
    }

    @Test
    fun `canceling an unread response cancels the streaming call and releases once`() {
        val http = NetworkMediaHttp({ id -> route(id, proxy = httpProxy()) }, relaxed)
        server.enqueue(MockResponse().setBody("unread bytes").setBodyDelay(1, TimeUnit.DAYS))
        val call = http.callFactory(7).newCall(Request.Builder().url("http://unresolvable.invalid/slow").build())
        val response = call.execute()
        assertEquals(0, releases.get())
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        call.cancel()
        assertEquals(1, releases.get())
        assertThrows(IOException::class.java) { response.body.source().readByte() }
        response.close()
        call.cancel()
        assertEquals(1, releases.get())
    }

    @Test
    fun `canceling while the route is acquired cancels resolution without opening HTTP`() {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val http =
            NetworkMediaHttp(
                {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { canceled.countDown() }
                        entered.countDown()
                    }
                },
                relaxed,
            )
        val call = http.callFactory(7).newCall(Request.Builder().url(server.url("/never")).build())
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    failed.countDown()
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    response.close()
                }
            },
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            call.cancel()
            assertTrue(canceled.await(5, TimeUnit.SECONDS))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(0, server.requestCount)
        } finally {
            call.cancel()
        }
    }

    @Test
    fun `pinned HTTPS uses SOCKS remote DNS and genuine TLS metadata with Coil conditional disk caching`() =
        runTest(dispatcher) {
            val certificate = enableTls()
            SocksProxy(server.port).use { socks ->
                val resolutions = AtomicInteger()
                val pin = PinningTrustManager.sha256Hex(certificate)
                val http =
                    NetworkMediaHttp({ id ->
                        resolutions.incrementAndGet()
                        route(id, proxy = socks.proxy, pin = pin)
                    })
                val url = "https://media-test.invalid:${server.port}/image.png"
                server.enqueue(pngResponse())
                http.callFactory(7).newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(listOf(certificate), it.handshake!!.peerCertificates)
                    assertEquals(0, releases.get())
                    assertArrayEquals(PNG, it.body.bytes())
                }
                assertEquals("media-test.invalid", socks.destinations.single())
                assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))

                val context = ApplicationProvider.getApplicationContext<Context>()
                val loader =
                    ImageLoader
                        .Builder(context)
                        .okHttpClient(http.client)
                        .memoryCache(null)
                        .diskCache(DiskCache.Builder().directory(temporaryFolder.newFolder()).build())
                        .build()
                try {
                    val image =
                        ImageRequest
                            .Builder(context)
                            .networkMediaData(url, 7)
                            .allowHardware(false)
                            .build()
                    server.enqueue(pngResponse().setHeader("Cache-Control", "max-age=0, must-revalidate").setHeader("ETag", "media-etag"))
                    assertTrue(loader.execute(image) is SuccessResult)
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600"))
                    assertTrue(loader.execute(image) is SuccessResult)
                    assertEquals("media-etag", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
                    val cacheOnly =
                        image
                            .newBuilder()
                            .networkMediaData(url, 7, retry = 1)
                            .networkCachePolicy(CachePolicy.DISABLED)
                            .build()
                    assertEquals(DataSource.DISK, (loader.execute(cacheOnly) as SuccessResult).dataSource)
                    assertTrue(loader.execute(cacheOnly.newBuilder().networkMediaData(url, 8).build()) is ErrorResult)
                    assertTrue(loader.execute(cacheOnly.newBuilder().networkMediaData(url, null).build()) is ErrorResult)
                    assertEquals(3, resolutions.get())
                    assertEquals(3, releases.get())
                    assertEquals(3, server.requestCount)
                } finally {
                    loader.shutdown()
                }
            }
        }

    @Test
    fun `HTTPS redirects cannot downgrade enter private addresses or borrow the original hosts pin`() {
        val certificate = enableTls()
        SocksProxy(server.port).use { socks ->
            val http = NetworkMediaHttp({ id -> route(id, proxy = socks.proxy, pin = PinningTrustManager.sha256Hex(certificate)) })
            val request = Request.Builder().url("https://media-test.invalid:${server.port}/redirect").build()
            for (target in listOf("http://media-test.invalid/file", "https://127.0.0.1/file", "https://other.invalid:${server.port}/file")) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target).setBody("redirect"))
                assertThrows(IOException::class.java) {
                    http
                        .callFactory(7)
                        .newCall(request)
                        .execute()
                        .close()
                }
            }
            assertEquals(listOf("media-test.invalid", "media-test.invalid", "media-test.invalid", "other.invalid"), socks.destinations.toList())
            assertEquals(3, releases.get())
        }
    }

    private fun httpProxy() = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port))

    private fun route(
        id: Long,
        proxy: Proxy? = null,
        proxyError: String? = null,
        pin: String? = null,
    ) = NetworkMediaRoute(
        networkId = id,
        endpoint = NetworkEntity(name = "test", host = "media-test.invalid", port = 6697, role = NetworkRole.DIRECT, nick = "nick", username = "user", realname = "real"),
        proxy = proxy,
        proxyError = proxyError,
        authorizationHeader = "Basic IRC-secret",
        endpointPinnedSha256 = pin,
        release = { releases.incrementAndGet() },
    )

    private fun enableTls(): X509Certificate {
        val password = "media-test".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        NetworkMediaHttpTest::class.java.getResourceAsStream("/network-media.p12")!!.use { keyStore.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        server.useHttps(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.socketFactory, false)
        return keyStore.getCertificate("media") as X509Certificate
    }

    private fun pngResponse() = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(PNG))

    /** Same SOCKS5 DOMAIN handshake as OkioLineTransportProxyTest, forwarding to the real TLS fixture. */
    private class SocksProxy(
        private val originPort: Int,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        val destinations = ConcurrentLinkedQueue<String>()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", listener.localPort))

        init {
            executor.execute {
                try {
                    while (!listener.isClosed) {
                        val socket = listener.accept().also { sockets.add(it) }
                        executor.execute { forward(socket) }
                    }
                } catch (_: IOException) {
                    // Closing the listening socket ends accept.
                }
            }
        }

        private fun forward(socket: Socket) {
            try {
                socket.use {
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                    output.write(byteArrayOf(5, 0))
                    check(input.readUnsignedByte() == 5)
                    check(input.readUnsignedByte() == 1)
                    input.readUnsignedByte()
                    check(input.readUnsignedByte() == 3) // Domain, never a locally resolved address.
                    val host = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                    check(input.readUnsignedShort() == originPort)
                    destinations.add(host)
                    Socket("127.0.0.1", originPort).also { sockets.add(it) }.use { origin ->
                        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        executor.execute {
                            try {
                                input.copyTo(origin.getOutputStream())
                            } catch (_: IOException) {
                                // The client may cancel or reject the certificate.
                            }
                        }
                        origin.getInputStream().copyTo(output)
                    }
                }
            } catch (_: IOException) {
                // Normal teardown, cancellation, or a rejected TLS handshake closes the tunnel.
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { it.close() }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private companion object {
        val PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}
