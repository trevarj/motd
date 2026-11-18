package io.github.trevarj.motd.attachment

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.ui.chat.AttachmentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttachmentUploaderMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun unsupportedAttemptSurvivesNewScreenOwnerButCancelDiscardsIt() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val store = ViewModelStore()
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val uploader = AttachmentUploaderImpl(context, NoopConnectionManager(), MediaRouteResolver { null }, UploadMetadataSanitizer(context))
                val factory =
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = AttachmentViewModel(AttachmentPrefsImpl(context), uploader) as T
                    }
                val firstScreen = ViewModelProvider(store, factory)[AttachmentViewModel::class.java]
                val original = temporary.newFile("private.pdf").apply { writeText("%PDF-1.7 private author") }
                val source = AttachmentSource.LocalFile(original, original.name, "application/pdf")
                val config = PasteBackendConfig(backend = AttachmentBackend.CUSTOM_0X0, customEndpoint = "https://unreachable.invalid/upload")
                firstScreen.upload(source, config, AttachmentUploadContext(37), replaceDraft = true) {
                    fail("Unsupported file reached upload completion")
                }
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { firstScreen.error.filterNotNull().first() }
                }
                val recreatedScreen = ViewModelProvider(store, factory)[AttachmentViewModel::class.java]
                assertSame(firstScreen, recreatedScreen)
                val request = recreatedScreen.attempt.filterNotNull().first()
                assertSame(source, request.source)
                assertEquals(config, request.config)
                assertEquals(37L, request.context.networkId)
                assertTrue(request.replaceDraft)
                assertFalse(request.context.allowOriginalMetadata)
                assertTrue(recreatedScreen.error.value!!.metadataNotSupported)
                recreatedScreen.clearError()
                recreatedScreen.clearAttempt()
                assertNull(recreatedScreen.attempt.value)
                assertNull(recreatedScreen.error.value)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test fun actualRequestContainsSanitizedMediaAndConsentDoesNotSurviveAnotherAttempt() =
        runBlocking {
            val cache = temporary.newFolder()
            val context =
                object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                    override fun getCacheDir(): File = cache
                }
            val uploader = AttachmentUploaderImpl(context, NoopConnectionManager(), MediaRouteResolver { null }, UploadMetadataSanitizer(context))
            val password = "media-test".toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            javaClass.getResourceAsStream("/network-media.p12")!!.use { store.load(it, password) }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
            val previousTls = HttpsURLConnection.getDefaultSSLSocketFactory()
            val previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            val server = MockWebServer()
            server.useHttps(tls.socketFactory, false)
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            try {
                server.start()
                val config = PasteBackendConfig(backend = AttachmentBackend.CUSTOM_0X0, customEndpoint = server.url("/upload").toString())
                val original = temporary.newFile("private-location.png")
                val encoded = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
                val privateText = "Author\u0000private-author-location".toByteArray()
                val chunk =
                    ByteArrayOutputStream()
                        .also { bytes ->
                            DataOutputStream(bytes).use {
                                it.writeInt(privateText.size)
                                it.writeBytes("tEXt")
                                it.write(privateText)
                                val crc =
                                    CRC32().apply {
                                        update("tEXt".toByteArray())
                                        update(privateText)
                                    }
                                it.writeInt(crc.value.toInt())
                            }
                        }.toByteArray()
                original.writeBytes(encoded.copyOfRange(0, encoded.size - 12) + chunk + encoded.takeLast(12))
                val before = original.readBytes()
                server.enqueue(MockResponse().setBody("https://example.com/clean.png"))
                val record = (uploader.upload(AttachmentSource.LocalFile(original, original.name, "image/png"), config).last() as UploadProgress.Complete).record
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                val body = request.body.readByteArray()
                val text = body.toString(Charsets.ISO_8859_1)
                assertFalse(text.contains("private-author-location"))
                assertFalse(text.contains("private-location"))
                assertTrue(text.contains("filename=\"${record.displayName}\""))
                assertTrue(text.contains("Content-Type: image/png"))
                val headerEnd = text.indexOf("\r\n\r\n", text.indexOf("filename=")) + 4
                val payload = body.copyOfRange(headerEnd, headerEnd + record.sizeBytes!!.toInt())
                val decoded = ImageIO.read(payload.inputStream())
                assertEquals(2, decoded.width)
                assertEquals(3, decoded.height)
                assertArrayEquals(before, original.readBytes())
                assertTrue(cache.listFiles().orEmpty().isEmpty())

                val unknown = temporary.newFile("report.pdf").apply { writeText("%PDF-1.7\n/Author(private)") }
                val source = AttachmentSource.LocalFile(unknown, unknown.name, "application/pdf")

                suspend fun refuse() {
                    try {
                        uploader.upload(source, config).last()
                        fail("Unsupported file uploaded without consent")
                    } catch (_: MetadataNotSupportedException) {
                        assertTrue(cache.listFiles().orEmpty().isEmpty())
                    }
                }
                refuse()
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setBody("https://example.com/report.pdf"))
                uploader.upload(source, config, AttachmentUploadContext(allowOriginalMetadata = true)).last()
                val originalRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertTrue(originalRequest.body.readUtf8().contains("%PDF-1.7\n/Author(private)"))
                assertEquals(2, server.requestCount)
                refuse()
                assertEquals(2, server.requestCount)
                assertTrue(cache.listFiles().orEmpty().isEmpty())

                val malformed =
                    temporary.newFile("private-broken.png").apply {
                        writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 73, 72, 68, 82))
                    }
                val malformedSource = AttachmentSource.LocalFile(malformed, malformed.name, "image/png", 1)
                server.enqueue(MockResponse().setBody("https://example.com/original.png"))
                val originalRecord = (uploader.upload(malformedSource, config.copy(stripMetadata = false)).last() as UploadProgress.Complete).record
                val malformedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
                val originalBody = malformedRequest.body.readByteArray()
                val originalText = originalBody.toString(Charsets.ISO_8859_1)
                assertTrue(originalText.contains("filename=\"${malformed.name}\""))
                assertTrue(originalText.contains("Content-Type: image/png"))
                val originalHeaderEnd = originalText.indexOf("\r\n\r\n", originalText.indexOf("filename=")) + 4
                assertArrayEquals(malformed.readBytes(), originalBody.copyOfRange(originalHeaderEnd, originalHeaderEnd + malformed.length().toInt()))
                assertEquals(malformed.length(), originalRecord.sizeBytes)
                assertEquals(malformed.name, originalRecord.displayName)
                assertEquals(3, server.requestCount)
                assertTrue(cache.listFiles().orEmpty().isEmpty())
                try {
                    uploader.upload(malformedSource, config.copy(stripMetadata = true)).last()
                    fail("Re-enabled stripping accepted malformed media")
                } catch (_: java.io.IOException) {
                    assertEquals(3, server.requestCount)
                    assertTrue(cache.listFiles().orEmpty().isEmpty())
                }

                for (stripMetadata in listOf(true, false)) {
                    val received = CountDownLatch(1)
                    val completions =
                        java.util.concurrent.atomic
                            .AtomicInteger()
                    val releaseResponse = CountDownLatch(1)
                    server.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse {
                                received.countDown()
                                releaseResponse.await()
                                return MockResponse().setBody("https://example.com/delayed.png")
                            }
                        }
                    val stalled =
                        launch(Dispatchers.IO) {
                            uploader.upload(AttachmentSource.LocalFile(original, original.name, "image/png"), config.copy(stripMetadata = stripMetadata)).collect {
                                if (it is UploadProgress.Complete) completions.incrementAndGet()
                            }
                        }
                    try {
                        assertTrue("Upload never reached the stalled response", withContext(Dispatchers.IO) { received.await(5, TimeUnit.SECONDS) })
                        assertTrue(cache.listFiles().orEmpty().isNotEmpty())
                        withTimeout(2_000) { stalled.cancelAndJoin() }
                        assertTrue("Cancellation retained staged media", cache.listFiles().orEmpty().isEmpty())
                        assertEquals("Cancelled upload emitted Complete", 0, completions.get())
                    } finally {
                        releaseResponse.countDown()
                        withContext(NonCancellable) { stalled.cancelAndJoin() }
                    }
                }
            } finally {
                server.shutdown()
                HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
                HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier)
            }
        }
}
