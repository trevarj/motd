package io.github.trevarj.motd.data.emoji

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmojiSetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application: Context = ApplicationProvider.getApplicationContext()
    private val context by lazy {
        object : ContextWrapper(application) {
            override fun getFilesDir(): File = temporary.root
        }
    }
    private val font by lazy { application.resources.openRawResource(R.font.jetbrains_mono_wght).use { it.readBytes() } }

    private fun spec(bytes: ByteArray = font) = EmojiSets.all.first().copy(sizeBytes = bytes.size.toLong(), sha256 = sha256(bytes))

    private fun store(
        spec: EmojiSetSpec = spec(),
        response: () -> Connection,
    ): EmojiSetStore = EmojiSetStore(context, listOf(spec), { response() }, renderSample = "A", requireColor = false)

    private fun installed() = File(context.filesDir, "emoji-sets/noto.ttf")

    private fun assertNoTemps() = assertFalse(File(context.filesDir, "emoji-sets").listFiles().orEmpty().any { it.extension == "tmp" })

    @Test fun catalogPinsEveryDownloadAndSystemIsNotDownloadable() {
        assertEquals(
            EmojiSets.all.size,
            EmojiSets.all
                .map { it.id }
                .toSet()
                .size,
        )
        assertNull(EmojiSets.find(EmojiSets.SYSTEM_ID))
        EmojiSets.all.forEach {
            val url = URL(it.downloadUrl)
            assertEquals("https", url.protocol)
            assertTrue(url.host in setOf("raw.githubusercontent.com", "github.com"))
            assertNull(url.userInfo)
            assertNull(url.ref)
            assertNull(url.query)
            assertTrue(it.sizeBytes > 0)
            assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test fun initialRequestsCannotBypassPinnedReleaseOrRawGithubBoundary() =
        runBlocking {
            val release = EmojiSets.find("twemoji")!!
            val raw = EmojiSets.all.first().downloadUrl
            for (url in listOf(
                release.downloadUrl.replace("v0.0.2", "v0.0.3"),
                "${release.downloadUrl}?token=secret",
                "https://release-assets.githubusercontent.com/github-production-release-asset/921888585/font",
                raw.replace("https:", "http:"),
                raw.replace("https://", "https://user@"),
                raw.replace(".com/", ".com:444/"),
                "$raw?token=secret",
                "$raw#fragment",
            )) {
                var opened = false
                val store =
                    store(release.copy(downloadUrl = url)) {
                        opened = true
                        Connection(font)
                    }
                assertTrue(store.download(release.id).isFailure)
                assertFalse(opened)
                assertNoTemps()
            }
        }

    @Test fun pinnedReleaseRedirectsAcceptOnlyBoundedTrustedAssetsAndRetainInstalledBytes() =
        runBlocking {
            val spec = EmojiSets.find("twemoji")!!.copy(sizeBytes = font.size.toLong(), sha256 = sha256(font))
            val asset = "https://release-assets.githubusercontent.com/github-production-release-asset/921888585/fixture?token=secret"
            var location = asset
            var loop = false
            val opened = mutableListOf<Connection>()
            val store =
                EmojiSetStore(
                    context,
                    listOf(spec),
                    { url ->
                        Connection(
                            font,
                            status = if (url.toExternalForm() == spec.downloadUrl || loop) 302 else 200,
                            responseUrl = url,
                            location = location,
                        ).also { opened.add(it) }
                    },
                    renderSample = "A",
                    requireColor = false,
                )
            val target = store.download("twemoji").getOrThrow()
            assertArrayEquals(font, target.readBytes())
            assertEquals(2, opened.size)
            assertTrue(opened.all { it.disconnected && !it.instanceFollowRedirects })
            val revision = store.revision.value
            for (unsafe in listOf(
                asset.replace("https:", "http:"),
                asset.replace("release-assets.githubusercontent.com", "example.com"),
                asset.replace("release-assets.githubusercontent.com", "release-assets.githubusercontent.com.evil.test"),
                asset.replace("https://", "https://user@"),
                asset.replace(".com/", ".com:444/"),
                asset.replace("/921888585/", "/other/"),
                "$asset#fragment",
                spec.downloadUrl,
            )) {
                location = unsafe
                opened.clear()
                assertTrue(store.download("twemoji").isFailure)
                assertEquals(1, opened.size)
                assertTrue(opened.all { it.disconnected })
                assertArrayEquals(font, target.readBytes())
                assertEquals(revision, store.revision.value)
                assertNoTemps()
            }
            location = asset
            loop = true
            opened.clear()
            assertTrue(store.download("twemoji").isFailure)
            assertEquals(4, opened.size)
            assertTrue(opened.all { it.disconnected })
            assertArrayEquals(font, target.readBytes())
            assertEquals(revision, store.revision.value)
            assertNull(store.progress.value)
            assertNoTemps()
        }

    @Test fun nativeFixtureInstallReinstallAndRemovalOnlyChangeRevisionWhenStateChanges() =
        runBlocking {
            val store = store { Connection(font) }
            assertNull(store.installedFile("noto"))
            assertEquals(0L, store.revision.value)
            val file = store.download("noto").getOrThrow()
            assertArrayEquals(font, file.readBytes())
            assertEquals(file, store.installedFile("noto"))
            val revision = store.revision.value
            assertTrue(revision > 0)
            assertEquals(file, store.download("noto").getOrThrow())
            assertEquals(revision, store.revision.value)
            assertNull(store.progress.value)
            store.remove("noto")
            assertEquals(revision + 1, store.revision.value)
            store.remove("noto")
            assertEquals(revision + 1, store.revision.value)
            assertNull(store.installedFile("noto"))
            assertNoTemps()
        }

    @Test fun strictStreamCapReadsOnlyOneSentinelAndNeverWritesPastPin() {
        var read = 0
        val input =
            object : InputStream() {
                override fun read(): Int {
                    read++
                    return 1
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    b.fill(1, off, off + len)
                    read += len
                    return len
                }
            }
        val output = ByteArrayOutputStream()
        try {
            copyEmojiBytes(input, output, 17, {})
            throw AssertionError("Expected streamed cap rejection")
        } catch (_: IOException) {
            assertEquals(18, read)
            assertTrue(output.size() <= 17)
        }
    }

    @Test fun dishonestUnknownAndIncorrectLengthsCannotReplaceInstalledBytes() =
        runBlocking {
            var connection = Connection(font)
            val store = store { connection }
            store.download("noto").getOrThrow()
            val revision = store.revision.value
            val corrupt = font.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            val responses =
                listOf(
                    Connection(font + byteArrayOf(1), advertised = -1),
                    Connection(font + byteArrayOf(1), advertised = font.size.toLong()),
                    Connection(font.copyOf(font.size - 1), advertised = font.size.toLong()),
                    Connection(font, advertised = 1),
                    Connection(corrupt),
                    Connection(font, status = 302),
                    Connection(font, responseUrl = URL("https://example.com/font.ttf")),
                )
            for (response in responses) {
                connection = response
                assertTrue(store.download("noto").isFailure)
                assertArrayEquals(font, installed().readBytes())
                assertEquals(revision, store.revision.value)
                assertTrue(response.disconnected)
                assertNull(store.progress.value)
                assertNoTemps()
            }
        }

    @Test fun unknownLengthValidStreamIsAcceptedAndInvalidPinnedFontIsRejected() =
        runBlocking {
            val fixtureStore = store { Connection(font, advertised = -1) }
            fixtureStore.download("noto").getOrThrow()
            // A plausible SFNT signature must still fail real platform decoding.
            val garbage = ByteArray(256).apply { this[1] = 1 }
            val invalidStore = store(spec(garbage)) { Connection(garbage) }
            assertTrue(invalidStore.download("noto").isFailure)
            assertArrayEquals(font, installed().readBytes())
            assertNoTemps()
        }

    @Test fun readFailurePreservesInstalledFileAndCleansTemporaryDownload() =
        runBlocking {
            var response = Connection(font)
            val store = store { response }
            store.download("noto").getOrThrow()
            val revision = store.revision.value
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("Injected network failure")
                }
            response = Connection(font, source = broken)
            assertTrue(store.download("noto").isFailure)
            assertArrayEquals(font, installed().readBytes())
            assertEquals(revision, store.revision.value)
            assertNull(store.progress.value)
            assertNoTemps()
        }

    @Test fun externalSameSizeReplacementWithRestoredMtimeInvalidatesMemoizedIdentityOnce() =
        runBlocking {
            val store = store { Connection(font) }
            val target = store.download("noto").getOrThrow()
            assertEquals(target, store.installedFile("noto"))
            val revision = store.revision.value
            val oldTime = Files.getLastModifiedTime(target.toPath())
            val replacement = File(target.parentFile, "external.ttf")
            replacement.writeBytes(font.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            Files.setLastModifiedTime(replacement.toPath(), oldTime)
            Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertNull(store.installedFile("noto"))
            assertEquals(revision + 1, store.revision.value)
            assertNull(store.installedFile("noto"))
            assertEquals(revision + 1, store.revision.value)
        }

    @Test fun cancellationClosesBlockedNetworkReadAndPreservesPreviousFile() =
        runBlocking {
            val entered = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val blocked =
                object : InputStream() {
                    override fun read(): Int {
                        entered.countDown()
                        if (!closed.await(10, TimeUnit.SECONDS)) throw IOException("Read was not closed")
                        throw IOException("Closed")
                    }

                    override fun close() {
                        closed.countDown()
                    }
                }
            var connection = Connection(font)
            val store = store { connection }
            store.download("noto").getOrThrow()
            val revision = store.revision.value
            connection = Connection(font, source = blocked)
            val request = async(Dispatchers.IO) { store.download("noto") }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            request.cancel()
            withTimeout(5_000) {
                try {
                    request.await()
                    throw AssertionError("Cancellation was swallowed")
                } catch (_: CancellationException) {
                }
            }
            // await() can resume before the IO worker's finally; lock acquisition joins that cleanup.
            assertEquals(installed(), store.installedFile("noto"))
            assertEquals(0L, closed.count)
            assertTrue(connection.disconnected)
            assertArrayEquals(font, installed().readBytes())
            assertEquals(revision, store.revision.value)
            assertNull(store.progress.value)
            assertNoTemps()
        }

    private class Connection(
        bytes: ByteArray,
        private val advertised: Long = bytes.size.toLong(),
        private val status: Int = 200,
        private val source: InputStream = ByteArrayInputStream(bytes),
        responseUrl: URL = URL(EmojiSets.all.first().downloadUrl),
        private val location: String? = null,
    ) : HttpURLConnection(responseUrl) {
        @Volatile var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getContentLengthLong() = advertised

        override fun getHeaderField(name: String): String? = if (name.equals("Location", true)) location else null

        override fun getInputStream() = source
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
