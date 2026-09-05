package io.github.trevarj.motd.audio

import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadata
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class AudioInputMaterializerTest {
    @Test
    fun `file leases never delete their source and enforce the exact size boundary`() =
        runTest {
            withFixture {
                val source = File(root, "local.opus").apply { writeBytes(byteArrayOf(1, 2, 3)) }

                val lease = materializer.materialize(request("${source.toURI()}#motd-wave=AAAA"))
                lease.close()
                lease.close()

                assertTrue(source.exists())
                assertEquals(source, lease.file)

                RandomAccessFile(source, "rw").use { it.setLength(MAX_AUDIO_INPUT_BYTES) }
                materializer.materialize(request(source.toURI().toString())).close()
                assertTrue(source.exists())

                RandomAccessFile(source, "rw").use { it.setLength(MAX_AUDIO_INPUT_BYTES + 1L) }
                assertEquals(
                    AudioInputFailureKind.TOO_LARGE,
                    expectFailure { materializer.materialize(request(source.toURI().toString())) }.kind,
                )
                assertTrue(source.exists())
            }
        }

    @Test
    fun `successful clear download populates only a hashed cache key and the next lease is a cache hit`() =
        runTest {
            val requests = AtomicInteger()
            val body = ByteArray(96 * 1024) { (it % 251).toByte() }
            val server =
                server { exchange ->
                    requests.incrementAndGet()
                    exchange.respond(200, body)
                }
            try {
                withFixture {
                    val url = "${server.url("/cached.opus?token=private")}#client-only"
                    val progress = mutableListOf<Pair<Long, Long?>>()

                    val first = materializer.materialize(request(url)) { copied, total -> progress += copied to total }
                    val second = materializer.materialize(request(url))

                    assertEquals(1, requests.get())
                    assertNotEquals(first.file, second.file)
                    assertArrayEquals(body, first.file.readBytes())
                    assertArrayEquals(body, second.file.readBytes())
                    assertEquals(0L to body.size.toLong(), progress.first())
                    assertEquals(body.size.toLong() to body.size.toLong(), progress.last())
                    assertEquals(setOf(audioMediaCacheKey(url)), mediaCache.cache.keys)
                    assertFalse(
                        mediaCache.cache.keys
                            .single()
                            .contains("private"),
                    )
                    assertNull(
                        ContentMetadata.getRedirectedUri(
                            mediaCache.cache.getContentMetadata(audioMediaCacheKey(url)),
                        ),
                    )

                    first.close()
                    assertFalse(first.file.exists())
                    assertTrue(second.file.exists())
                    second.close()
                    assertFalse(second.file.exists())
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `http download uses the selected route without credentials and reports byte progress`() =
        runTest {
            val body = "routed audio".repeat(8_192).toByteArray()
            var requestedPath: String? = null
            var authorization: String? = null
            val server =
                server { exchange ->
                    requestedPath = exchange.requestURI.toString()
                    authorization = exchange.requestHeaders.getFirst("Authorization")
                    exchange.respond(200, body)
                }
            var routedNetwork: Long? = null
            var releases = 0
            val resolver =
                MediaRouteResolver { networkId ->
                    routedNetwork = networkId
                    route(networkId, authorizationHeader = "Basic must-not-leak") { releases++ }
                }
            try {
                withFixture(resolver) {
                    val progress = mutableListOf<Pair<Long, Long?>>()
                    val lease =
                        materializer.materialize(request(server.url("/voice.opus#client-only"), networkId = 42L)) { received, total ->
                            progress += received to total
                        }

                    assertEquals(42L, routedNetwork)
                    assertEquals("/voice.opus", requestedPath)
                    assertNull(authorization)
                    assertEquals(1, releases)
                    assertArrayEquals(body, lease.file.readBytes())
                    assertEquals(0L to body.size.toLong(), progress.first())
                    assertEquals(body.size.toLong() to body.size.toLong(), progress.last())
                    lease.close()
                    assertFalse(lease.file.exists())
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `missing non-null network route fails closed without a direct request`() =
        runTest {
            val requests = AtomicInteger()
            val server =
                server { exchange ->
                    requests.incrementAndGet()
                    exchange.respond(200, byteArrayOf(1))
                }
            try {
                withFixture(resolver = MediaRouteResolver { null }) {
                    val error = expectFailure { materializer.materialize(request(server.url("/must-route"), networkId = 77L)) }

                    assertEquals(AudioInputFailureKind.ROUTE_UNAVAILABLE, error.kind)
                    assertEquals(0, requests.get())
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `confirmed clear HTTP uses the narrow socket fallback when platform policy blocks URLConnection`() =
        runTest {
            val body = "confirmed clear audio".toByteArray()
            val requests = AtomicInteger()
            val server =
                server { exchange ->
                    requests.incrementAndGet()
                    exchange.respond(200, body)
                }
            try {
                withFixture(cleartextPermitted = { false }) {
                    val lease = materializer.materialize(request(server.url("/confirmed")))

                    assertEquals(1, requests.get())
                    assertArrayEquals(body, lease.file.readBytes())
                    lease.close()
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `encrypted downloads authenticate and each caller owns separate plaintext`() =
        runTest {
            withFixture {
                val plain = File(root, "plain.opus").apply { writeBytes(ByteArray(128 * 1024) { (it % 239).toByte() }) }
                val encrypted = crypto.encrypt(plain)
                val encryptedBytes = encrypted.file.readBytes()
                val requests = AtomicInteger()
                var requestedPath: String? = null
                val server =
                    server { exchange ->
                        requests.incrementAndGet()
                        requestedPath = exchange.requestURI.toString()
                        exchange.respond(200, encryptedBytes)
                    }
                try {
                    val url = "${server.url("/encrypted")}#${encrypted.keyFragment}&motd-wave=AAAA"
                    val first = materializer.materialize(request(url, encrypted = true))
                    val second = materializer.materialize(request(url, encrypted = true))

                    assertEquals("/encrypted", requestedPath)
                    assertEquals(1, requests.get())
                    assertNotEquals(first.file, second.file)
                    assertArrayEquals(plain.readBytes(), first.file.readBytes())
                    assertArrayEquals(plain.readBytes(), second.file.readBytes())

                    first.close()
                    assertFalse(first.file.exists())
                    assertTrue(second.file.exists())
                    second.close()
                    assertFalse(second.file.exists())
                } finally {
                    server.stop(0)
                }
            }
        }

    @Test
    fun `bad and missing encryption keys are distinct safe failures with no plaintext`() =
        runTest {
            withFixture {
                val plain = File(root, "plain.opus").apply { writeText("private voice") }
                val encrypted = crypto.encrypt(plain)
                val server = server { it.respond(200, encrypted.file.readBytes()) }
                try {
                    val base = server.url("/private-token")
                    materializer.materialize(request("$base#${encrypted.keyFragment}", encrypted = true)).close()
                    val wrongKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
                    val bad = expectFailure { materializer.materialize(request("$base#motd-key=$wrongKey", encrypted = true)) }
                    val malformed =
                        expectFailure {
                            materializer.materialize(request("$base#motd-key=not-base64!", encrypted = true))
                        }
                    val missing = expectFailure { materializer.materialize(request(base, encrypted = true)) }

                    assertEquals(AudioInputFailureKind.AUTHENTICATION_FAILED, bad.kind)
                    assertEquals(AudioInputFailureKind.INVALID_ENCRYPTION_KEY, malformed.kind)
                    assertEquals(AudioInputFailureKind.MISSING_ENCRYPTION_KEY, missing.kind)
                    assertSafe(bad, base, wrongKey, encrypted.keyFragment)
                    assertSafe(malformed, base, "not-base64", encrypted.keyFragment)
                    assertSafe(missing, base, encrypted.keyFragment)
                    assertTrue(leaseFiles().isEmpty())
                } finally {
                    server.stop(0)
                }
            }
        }

    @Test
    fun `unsupported and expired inputs fail before acquisition`() =
        runTest {
            withFixture {
                assertEquals(
                    AudioInputFailureKind.UNSUPPORTED_SCHEME,
                    expectFailure { materializer.materialize(request("content://private/audio")) }.kind,
                )
                val expired =
                    expectFailure {
                        materializer.materialize(
                            request("https://private.example/audio", expiry = "2000-01-01T00:00:00Z"),
                        )
                    }
                assertEquals(AudioInputFailureKind.EXPIRED, expired.kind)
                assertSafe(expired, "private.example")
                assertTrue(leaseFiles().isEmpty())
            }
        }

    @Test
    fun `route http authentication and truncated read failures expose no URL`() =
        runTest {
            var releases = 0
            val resolver =
                MediaRouteResolver { networkId ->
                    route(networkId, proxyError = "proxy failed for https://private.example/#secret") { releases++ }
                }
            withFixture(resolver) {
                val routeFailure =
                    expectFailure {
                        materializer.materialize(request("https://private.example/audio#secret", networkId = 9L))
                    }
                assertEquals(AudioInputFailureKind.ROUTE_UNAVAILABLE, routeFailure.kind)
                assertEquals(1, releases)
                assertSafe(routeFailure, "private.example", "secret")
            }

            val server =
                MockWebServer().apply {
                    enqueue(MockResponse().setResponseCode(401))
                    enqueue(MockResponse().setResponseCode(503))
                    enqueue(
                        MockResponse()
                            .setBody("x".repeat(64))
                            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
                    )
                    start()
                }
            try {
                withFixture {
                    val authError =
                        expectFailure {
                            materializer.materialize(request(server.url("/audio?token=secret#fragment").toString()))
                        }
                    val httpError =
                        expectFailure {
                            materializer.materialize(request(server.url("/audio?token=secret#fragment").toString()))
                        }
                    val readError =
                        expectFailure {
                            materializer.materialize(request(server.url("/audio?token=secret#fragment").toString()))
                        }

                    assertEquals(AudioInputFailureKind.HTTP_AUTHENTICATION_FAILED, authError.kind)
                    assertEquals(AudioInputFailureKind.HTTP_FAILED, httpError.kind)
                    assertEquals(AudioInputFailureKind.READ_FAILED, readError.kind)
                    listOf(authError, httpError, readError).forEach { assertSafe(it, "token=secret", "fragment") }
                    assertTrue(leaseFiles().isEmpty())
                }
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `unknown length transport is bounded by actual one hundred MiB and cleans failure`() =
        runTest {
            val server =
                server { exchange ->
                    exchange.sendResponseHeaders(200, 0L)
                    val chunk = ByteArray(32 * 1024)
                    var remaining = MAX_AUDIO_INPUT_BYTES + 1L
                    try {
                        exchange.responseBody.use { output ->
                            while (remaining > 0L) {
                                val count = minOf(chunk.size.toLong(), remaining).toInt()
                                output.write(chunk, 0, count)
                                remaining -= count
                            }
                        }
                    } catch (_: IOException) {
                        // The client closes as soon as the authoritative streamed limit is crossed.
                    } finally {
                        exchange.close()
                    }
                }
            try {
                withFixture {
                    var lastTotal: Long? = -1L
                    val error =
                        expectFailure {
                            materializer.materialize(request(server.url("/large"))) { _, total -> lastTotal = total }
                        }

                    assertEquals(AudioInputFailureKind.TOO_LARGE, error.kind)
                    assertNull(lastTotal)
                    assertTrue(leaseFiles().isEmpty())
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `cancellation is rethrown and removes encrypted download partials`() =
        runTest {
            val server =
                server { exchange ->
                    exchange.sendResponseHeaders(200, 0L)
                    try {
                        exchange.responseBody.use { output -> repeat(128) { output.write(ByteArray(32 * 1024)) } }
                    } catch (_: IOException) {
                        // Cancellation closes the client connection while this response is in flight.
                    } finally {
                        exchange.close()
                    }
                }
            try {
                withFixture {
                    val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
                    var cancelled = false
                    try {
                        materializer.materialize(request("${server.url("/cancel")}#motd-key=$key", encrypted = true)) { received, _ ->
                            if (received > 0L) throw CancellationException("test cancellation")
                        }
                    } catch (_: CancellationException) {
                        cancelled = true
                    }

                    assertTrue(cancelled)
                    assertTrue(leaseFiles().isEmpty())
                    assertTrue(
                        File(root, "audio-cache")
                            .listFiles()
                            .orEmpty()
                            .none { it.name.startsWith("voice-ciphertext-") || it.name.startsWith("cipher-") },
                    )
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `job cancellation disconnects a stalled HTTP read immediately`() =
        runTest {
            val started = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            val server =
                server { exchange ->
                    exchange.sendResponseHeaders(200, 0L)
                    exchange.responseBody.flush()
                    started.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                    exchange.close()
                }
            try {
                withFixture(cleartextPermitted = { true }) {
                    val finished = CountDownLatch(1)
                    val job =
                        launch(Dispatchers.IO) {
                            try {
                                materializer.materialize(request(server.url("/stall")))
                            } finally {
                                finished.countDown()
                            }
                        }
                    assertTrue(withContext(Dispatchers.IO) { started.await(2, TimeUnit.SECONDS) })

                    job.cancel()
                    val stoppedPromptly = withContext(Dispatchers.IO) { finished.await(1, TimeUnit.SECONDS) }
                    releaseServer.countDown()
                    job.join()

                    assertTrue(stoppedPromptly)
                    assertTrue(leaseFiles().isEmpty())
                }
            } finally {
                releaseServer.countDown()
                server.stop(0)
            }
        }

    @Test
    fun `cancellation after materialization but before dispatcher delivery closes the produced lease`() =
        runTest {
            withFixture {
                val url = "https://cache.invalid/delivery-race"
                val source = File(root, "delivery-source.media").apply { writeText("complete") }
                mediaCache.putComplete(url, source)
                val dispatcher = HoldReturnDispatcher()
                val job =
                    launch(dispatcher) {
                        materializer.materialize(request(url))
                    }

                assertTrue(withContext(Dispatchers.IO) { dispatcher.returnQueued.await(2, TimeUnit.SECONDS) })
                assertEquals(1, leaseFiles().size)
                job.cancel()
                dispatcher.release()
                job.join()

                assertTrue(leaseFiles().isEmpty())
            }
        }

    @Test
    fun `restart prune and user clear remove stale plaintext and raw keys but preserve active leases`() =
        runTest {
            withFixture {
                val inputRoot = File(root, "audio-cache/input-leases").apply { mkdirs() }
                val pcmRoot = File(root, "audio-cache/pcm-leases").apply { mkdirs() }
                val activeInput = cacheStore.inputLease().also { it.file.writeText("active") }
                val staleInput = File(inputRoot, "stale.media").apply { writeText("stale") }
                val stalePcm = File(pcmRoot, "stale.wav").apply { writeText("stale") }
                val legacyInput =
                    File(root, "audio-input-leases/stale.media").apply {
                        parentFile?.mkdirs()
                        writeText("stale")
                    }
                val legacyPcm =
                    File(root, "pcm-audio-leases/stale.wav").apply {
                        parentFile?.mkdirs()
                        writeText("stale")
                    }
                val rawUrl = "https://user:password@example.invalid/audio?token=private"
                seedLegacyMediaCache(rawUrl, byteArrayOf(1, 2, 3))

                cacheStore.pruneStaleLeases()

                assertTrue(activeInput.file.exists())
                assertFalse(staleInput.exists())
                assertFalse(stalePcm.exists())
                assertFalse(legacyInput.exists())
                assertFalse(legacyPcm.exists())
                assertFalse(mediaCache.cache.keys.contains(rawUrl))

                val activePcm = cacheStore.pcmLease().also { it.file.writeText("active") }
                val inactiveInput = File(inputRoot, "inactive.media").apply { writeText("inactive") }
                val inactivePcm = File(pcmRoot, "inactive.wav").apply { writeText("inactive") }

                cacheStore.clear()

                assertFalse(inactiveInput.exists())
                assertFalse(inactivePcm.exists())
                assertTrue(activeInput.file.exists())
                assertTrue(activePcm.file.exists())
                activeInput.close()
                activePcm.close()
                assertFalse(activeInput.file.exists())
                assertFalse(activePcm.file.exists())
            }
        }

    @Test
    fun `user clear surfaces a media deletion failure without exposing its raw key`() =
        runTest {
            withFixture {
                val rawUrl = "https://user:password@example.invalid/audio?token=private"
                seedLegacyMediaCache(rawUrl, byteArrayOf(1, 2, 3))
                val cacheFile =
                    checkNotNull(
                        mediaCache.cache
                            .getCachedSpans(rawUrl)
                            .single()
                            .file,
                    )
                assertTrue(cacheFile.delete())
                assertTrue(cacheFile.mkdir())
                File(cacheFile, "undeletable").writeText("held")

                var failure: Exception? = null
                try {
                    cacheStore.clear()
                } catch (error: Exception) {
                    failure = error
                }

                assertTrue(failure is IOException)
                assertFalse(failure?.message.orEmpty().contains(rawUrl))
                assertTrue(cacheFile.exists())
            }
        }

    private suspend fun <T> withFixture(
        resolver: MediaRouteResolver = MediaRouteResolver { null },
        cleartextPermitted: (String) -> Boolean = { true },
        block: suspend Fixture.() -> T,
    ): T {
        val fixture = Fixture(resolver, cleartextPermitted)
        return try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private suspend fun expectFailure(block: suspend () -> Any?): AudioInputException =
        try {
            block()
            throw AssertionError("Expected AudioInputException")
        } catch (error: AudioInputException) {
            error
        }

    private fun assertSafe(
        error: AudioInputException,
        vararg secrets: String,
    ) {
        secrets.forEach { secret -> assertFalse(error.message.orEmpty().contains(secret)) }
        assertNull(error.cause)
    }

    private fun request(
        url: String,
        encrypted: Boolean = false,
        networkId: Long? = null,
        expiry: String? = null,
    ) = AudioPlaybackRequest(
        attachment =
            AudioAttachment(
                url = url,
                voice = encrypted,
                encrypted = encrypted,
                expiry = expiry,
            ),
        networkId = networkId,
    )

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

    private fun HttpServer.url(path: String): String = "http://127.0.0.1:${address.port}$path"

    private fun HttpExchange.respond(
        status: Int,
        body: ByteArray,
        declaredLength: Long = body.size.toLong(),
    ) {
        sendResponseHeaders(status, declaredLength)
        try {
            if (body.isNotEmpty()) responseBody.use { it.write(body) }
        } finally {
            close()
        }
    }

    private fun route(
        networkId: Long,
        proxyError: String? = null,
        authorizationHeader: String? = null,
        release: () -> Unit = {},
    ) = NetworkMediaRoute(
        networkId = networkId,
        endpoint =
            NetworkEntity(
                name = "test",
                role = NetworkRole.BOUNCER_ROOT,
                host = "127.0.0.1",
                port = 6697,
                nick = "test",
                username = "test",
                realname = "test",
            ),
        proxy = null,
        proxyError = proxyError,
        authorizationHeader = authorizationHeader,
        release = release,
    )

    private class HoldReturnDispatcher : CoroutineDispatcher() {
        private val entered = AtomicBoolean()
        private val released = AtomicBoolean()
        private val queued = ConcurrentLinkedQueue<Runnable>()
        val returnQueued = CountDownLatch(1)

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (entered.compareAndSet(false, true) || released.get()) {
                block.run()
            } else {
                queued += block
                returnQueued.countDown()
            }
        }

        fun release() {
            released.set(true)
            while (true) queued.poll()?.run() ?: return
        }
    }

    private class Fixture(
        resolver: MediaRouteResolver,
        cleartextPermitted: (String) -> Boolean,
    ) : AutoCloseable {
        val root: File = Files.createTempDirectory("audio-materializer-test-").toFile()
        private val base = ApplicationProvider.getApplicationContext<Context>()
        private val context =
            object : ContextWrapper(base) {
                override fun getCacheDir(): File = root
            }
        val mediaCache = AudioMediaCache(context)
        private val waveformRepository = AudioWaveformRepository(context)
        val cacheStore = AudioCacheStore(context, mediaCache, waveformRepository)
        val crypto = VoiceCrypto(cacheStore)
        val materializer =
            AudioInputMaterializer(
                routeResolver = resolver,
                crypto = crypto,
                cacheStore = cacheStore,
                mediaCache = mediaCache,
                ioDispatcher = Dispatchers.IO,
                cleartextPermitted = cleartextPermitted,
            )

        fun seedLegacyMediaCache(
            key: String,
            bytes: ByteArray,
        ) {
            val hole = mediaCache.cache.startReadWrite(key, 0, bytes.size.toLong())
            check(!hole.isCached)
            try {
                val target = mediaCache.cache.startFile(key, 0, bytes.size.toLong())
                target.writeBytes(bytes)
                mediaCache.cache.commitFile(target, bytes.size.toLong())
            } finally {
                mediaCache.cache.releaseHoleSpan(hole)
            }
        }

        fun leaseFiles(): List<File> = File(root, "audio-cache/input-leases").listFiles().orEmpty().toList()

        override fun close() {
            runCatching { mediaCache.cache.release() }
            root.deleteRecursively()
        }
    }
}
