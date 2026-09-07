package io.github.trevarj.motd.ui.imageviewer

import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.LinkPreviewFetchPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImageSaveOperationTest {
    @Test fun `oversized content length is rejected before insert`() =
        runTest {
            val connection = FakeConnection(contentLength = 5, input = ByteArrayInputStream(byteArrayOf(1)))
            val store = FakeStore()

            assertEquals(ImageSaveResult.Failed, operation(connection, store, maxBytes = 4).save("https://example.test/image"))

            assertEquals(0, store.insertCalls)
            assertTrue(connection.disconnected)
        }

    @Test fun `HTTP failure is rejected before insert`() =
        runTest {
            val connection = FakeConnection(responseCode = 404, input = ByteArrayInputStream(byteArrayOf(1)))
            val store = FakeStore()

            assertEquals(ImageSaveResult.Failed, operation(connection, store).save("https://example.test/image"))

            assertEquals(0, store.insertCalls)
            assertTrue(connection.disconnected)
        }

    @Test fun `explicit non image response is rejected before insert`() =
        runTest {
            val connection = FakeConnection(contentType = "text/html", input = ByteArrayInputStream(byteArrayOf(1)))
            val store = FakeStore()

            assertEquals(ImageSaveResult.Failed, operation(connection, store).save("https://example.test/image"))

            assertEquals(0, store.insertCalls)
            assertTrue(connection.disconnected)
        }

    @Test fun `absent and misleading content length still enforce the streaming cap`() =
        runTest {
            val absentLength = FakeConnection(contentLength = -1, input = ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            val successfulStore = FakeStore()

            assertEquals(ImageSaveResult.Saved, operation(absentLength, successfulStore, maxBytes = 4).save("https://example.test/image"))
            assertArrayEquals(byteArrayOf(1, 2, 3), successfulStore.bytes())

            val endless = EndlessInputStream()
            val misleadingLength = FakeConnection(contentLength = 1, input = endless)
            val failedStore = FakeStore()

            assertEquals(ImageSaveResult.Failed, operation(misleadingLength, failedStore, maxBytes = 4).save("https://example.test/image"))
            assertEquals(1, endless.readCalls)
            assertEquals(1, failedStore.deleteCalls)
        }

    @Test fun `insert failure does not create a deletion target`() =
        runTest {
            val connection = FakeConnection(input = ByteArrayInputStream(byteArrayOf(1)))
            val store = FakeStore(insertResult = null)

            assertEquals(ImageSaveResult.Failed, operation(connection, store).save("https://example.test/image"))

            assertEquals(1, store.insertCalls)
            assertEquals(0, store.deleteCalls)
            assertTrue(connection.disconnected)
        }

    @Test fun `null output stream deletes the pending row exactly once`() =
        runTest {
            val connection = FakeConnection(input = ByteArrayInputStream(byteArrayOf(1)))
            val store = FakeStore(output = null)

            assertEquals(ImageSaveResult.Failed, operation(connection, store).save("https://example.test/image"))

            assertEquals(1, store.deleteCalls)
            assertFalse(store.published)
        }

    @Test fun `mid copy exception closes resources and deletes the pending row exactly once`() =
        runTest {
            val input =
                object : InputStream() {
                    var reads = 0
                    var closed = false

                    override fun read(): Int = throw UnsupportedOperationException()

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int =
                        when (reads++) {
                            0 -> 1.also { buffer[offset] = 7 }
                            else -> throw IOException("network dropped")
                        }

                    override fun close() {
                        closed = true
                    }
                }
            val output = CloseTrackingOutputStream()
            val connection = FakeConnection(input = input)
            val store = FakeStore(output = output)

            assertEquals(ImageSaveResult.Failed, operation(connection, store).save("https://example.test/image"))

            assertTrue(input.closed)
            assertTrue(output.closed)
            assertEquals(1, store.deleteCalls)
            assertTrue(connection.disconnected)
        }

    @Test fun `input open failure closes the already opened output and deletes the pending row`() =
        runTest {
            val output = CloseTrackingOutputStream()
            val connection = FakeConnection(inputFailure = IOException("cannot open response"))
            val store = FakeStore(output = output)

            assertEquals(ImageSaveResult.Failed, operation(connection, store).save("https://example.test/image"))

            assertTrue(output.closed)
            assertEquals(1, store.deleteCalls)
            assertTrue(connection.disconnected)
        }

    @Test fun `output write failure deletes the pending row exactly once`() =
        runTest {
            val output =
                object : OutputStream() {
                    var closed = false

                    override fun write(value: Int) = throw IOException("storage full")

                    override fun write(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) = throw IOException("storage full")

                    override fun close() {
                        closed = true
                    }
                }
            val connection = FakeConnection(input = ByteArrayInputStream(byteArrayOf(1)))
            val store = FakeStore(output = output)

            assertEquals(ImageSaveResult.Failed, operation(connection, store).save("https://example.test/image"))

            assertTrue(output.closed)
            assertEquals(1, store.deleteCalls)
            assertTrue(connection.disconnected)
        }

    @Test fun `cancellation interrupts a blocked read and deletes the pending row exactly once`() =
        runBlocking {
            val reading = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val input =
                object : InputStream() {
                    override fun read(): Int = throw UnsupportedOperationException()

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        reading.countDown()
                        closed.await(5, TimeUnit.SECONDS)
                        throw IOException("stream closed")
                    }

                    override fun close() {
                        closed.countDown()
                    }
                }
            val connection = FakeConnection(input = input)
            val store = FakeStore()
            val savingJob = launch(Dispatchers.IO) { operation(connection, store).save("https://example.test/image") }

            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS))
                savingJob.cancel()
                withTimeout(2_000) { savingJob.join() }

                assertTrue(savingJob.isCancelled)
                assertEquals(1, store.deleteCalls)
                assertFalse(store.published)
                assertTrue(connection.disconnected)
            } finally {
                closed.countDown()
                savingJob.cancelAndJoin()
            }
        }

    @Test fun `finalize failure deletes pending row and only reports failure`() =
        runTest {
            val store = FakeStore(publishResult = false)

            assertEquals(
                ImageSaveResult.Failed,
                operation(FakeConnection(input = ByteArrayInputStream(byteArrayOf(1, 2))), store).save("https://example.test/image"),
            )

            assertTrue(store.publishCalled)
            assertEquals(1, store.deleteCalls)
            assertEquals(ImageSaveFeedback.FAILED, ImageSaveResult.Failed.feedback())
        }

    @Test fun `success preserves exact bytes then publishes the pending row`() =
        runTest {
            val store = FakeStore()
            val connection =
                FakeConnection(
                    contentType = "image/png; charset=binary",
                    contentDisposition = "attachment; filename=../../kitten.jpeg",
                    input = ByteArrayInputStream(byteArrayOf(2, 4, 6, 8)),
                )

            assertEquals(ImageSaveResult.Saved, operation(connection, store).save("https://example.test/not-the-name.jpg"))

            assertArrayEquals(byteArrayOf(2, 4, 6, 8), store.bytes())
            assertEquals(ImageSaveMetadata("kitten.png", "image/png"), store.metadata)
            assertTrue(store.published)
            assertEquals(0, store.deleteCalls)
            assertEquals(ImageSaveFeedback.SAVED, ImageSaveResult.Saved.feedback())
        }

    @Test fun `viewer supported bmp and heif responses keep their real media types`() =
        runTest {
            listOf(
                "image/bmp" to ImageSaveMetadata("motd-image.bmp", "image/bmp"),
                "image/heic" to ImageSaveMetadata("motd-image.heic", "image/heic"),
                "image/heif" to ImageSaveMetadata("motd-image.heif", "image/heif"),
            ).forEach { (contentType, expected) ->
                val store = FakeStore()

                assertEquals(
                    ImageSaveResult.Saved,
                    operation(FakeConnection(contentType = contentType, input = ByteArrayInputStream(byteArrayOf(1))), store)
                        .save("https://example.test/image"),
                )
                assertEquals(expected, store.metadata)
            }
        }

    @Test fun `save traverses the selected proxy without SASL and never falls back to the origin`() =
        runTest {
            val origin = MockWebServer().apply { start() }
            val proxy = MockWebServer().apply { start() }
            val bytes = byteArrayOf(0, 1, 2, 127, -1, 42)
            val endpoint =
                NetworkEntity(
                    id = 42,
                    name = "proxied",
                    role = NetworkRole.DIRECT,
                    host = origin.url("/").host,
                    port = 6697,
                    nick = "nick",
                    username = "user",
                    realname = "User",
                    saslMechanism = "PLAIN",
                    saslUser = "sasl-user",
                    saslPassword = "sasl-secret",
                )
            val mediaHttp =
                NetworkMediaHttp(
                    MediaRouteResolver { id ->
                        if (id != 42L && id != 43L) {
                            null
                        } else {
                            NetworkMediaRoute(
                                networkId = id,
                                endpoint = endpoint,
                                proxy = if (id == 42L) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)) else null,
                                proxyError = if (id == 43L) "Tunnel unavailable" else null,
                                authorizationHeader = Credentials.basic("sasl-user", "sasl-secret"),
                            )
                        }
                    },
                    // Only the destination check is relaxed for the loopback HTTP proxy fixture.
                    LinkPreviewFetchPolicy(enforceDestinationPolicy = false),
                )
            try {
                origin.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("wrong direct bytes"))
                proxy.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
                val url = origin.url("/upload/user/account/123-image.png").toString()
                val store = FakeStore()

                assertEquals(
                    ImageSaveResult.Saved,
                    ImageSaveOperation(OkHttpImageSaveConnectionFactory(mediaHttp.callFactory(42)), store).save(url),
                )
                assertArrayEquals(bytes, store.bytes())
                assertTrue(store.published)
                assertEquals(0, store.deleteCalls)
                val request = requireNotNull(proxy.takeRequest(1, TimeUnit.SECONDS))
                assertTrue(request.requestLine.startsWith("GET $url "))
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Proxy-Authorization"))
                assertEquals(0, origin.requestCount)

                for (networkId in listOf(null, 999L, 43L)) {
                    val rejectedStore = FakeStore()
                    assertEquals(
                        ImageSaveResult.Failed,
                        ImageSaveOperation(OkHttpImageSaveConnectionFactory(mediaHttp.callFactory(networkId)), rejectedStore).save(url),
                    )
                    assertEquals(0, rejectedStore.insertCalls)
                    assertFalse(rejectedStore.published)
                }
                assertEquals(1, proxy.requestCount)
                assertEquals(0, origin.requestCount)
            } finally {
                proxy.shutdown()
                origin.shutdown()
            }
        }

    private fun operation(
        connection: FakeConnection,
        store: FakeStore,
        maxBytes: Long = IMAGE_SAVE_MAX_BYTES,
    ) = ImageSaveOperation(ImageSaveConnectionFactory { connection }, store, maxBytes)

    private class FakeConnection(
        override val responseCode: Int = 200,
        override val contentLength: Long = -1,
        override val contentType: String? = "image/jpeg",
        private val contentDisposition: String? = null,
        private val input: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val inputFailure: IOException? = null,
    ) : ImageSaveConnection {
        var disconnected = false

        override fun header(name: String): String? = if (name == "Content-Disposition") contentDisposition else null

        override fun openInputStream(): InputStream {
            inputFailure?.let { throw it }
            return input
        }

        override fun disconnect() {
            disconnected = true
            input.close()
        }
    }

    private class FakeStore(
        private val insertResult: String? = "content://image/1",
        private val output: OutputStream? = CloseTrackingOutputStream(),
        private val publishResult: Boolean = true,
    ) : ImageSaveStore<String> {
        var insertCalls = 0
        var deleteCalls = 0
        var publishCalled = false
        var published = false
        var metadata: ImageSaveMetadata? = null

        override fun insert(metadata: ImageSaveMetadata): String? {
            insertCalls++
            this.metadata = metadata
            return insertResult
        }

        override fun openOutputStream(location: String): OutputStream? = output

        override fun publish(location: String): Boolean {
            publishCalled = true
            published = publishResult
            return publishResult
        }

        override fun delete(location: String) {
            deleteCalls++
        }

        fun bytes(): ByteArray = (output as ByteArrayOutputStream).toByteArray()
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class EndlessInputStream : InputStream() {
        var readCalls = 0

        override fun read(): Int = 0

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readCalls++
            buffer.fill(1, offset, offset + length)
            return length
        }
    }
}
