package io.github.trevarj.motd.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class AttachmentDeletionTest {
    @Test fun crafterbinDeletionUsesItsManagementForm() {
        var method = ""
        var contentType = ""
        var body = ""
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/upload") { exchange ->
                    method = exchange.requestMethod
                    contentType = exchange.requestHeaders.getFirst("Content-Type")
                    body = exchange.requestBody.bufferedReader().use { it.readText() }
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }
        val uploader =
            AttachmentUploaderImpl(
                ApplicationProvider.getApplicationContext<Context>(),
                NoopConnectionManager(),
                MediaRouteResolver { null },
                UploadMetadataSanitizer(ApplicationProvider.getApplicationContext<Context>()),
                AttachmentPrefsImpl(ApplicationProvider.getApplicationContext<Context>()),
            )

        try {
            runBlocking {
                uploader.delete(
                    UploadRecord(
                        url = "http://127.0.0.1:${server.address.port}/upload",
                        backend = AttachmentBackend.CRAFTERBIN,
                        displayName = "photo.png",
                        deletionToken = "token with spaces",
                    ),
                )
            }

            assertEquals("POST", method)
            assertTrue(contentType.startsWith("application/x-www-form-urlencoded"))
            assertEquals("token=token+with+spaces&delete=", body)
        } finally {
            server.stop(0)
        }
    }
}
