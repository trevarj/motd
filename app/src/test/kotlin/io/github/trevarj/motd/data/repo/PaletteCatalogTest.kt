package io.github.trevarj.motd.data.repo

import android.content.Context
import android.content.ContextWrapper
import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.data.prefs.encodePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaletteCatalogTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var cache: File

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val directory = temporary.newFolder("private-files")
        context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir(): File = directory
            }
        cache = File(directory, "palette-catalog.json")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun constructionDoesNotFetchOrLoadCache() {
        cache.writeText(index(validFixture()))
        val production = PaletteCatalog(context)
        val local = catalog()
        assertEquals(PaletteCatalogState(), production.state.value)
        assertEquals(PaletteCatalogState(), local.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun validPairPersistsAndReloadsOfflineWithoutAnotherSource() =
        runBlocking {
            val bytes = index(validFixture()).toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val endpoint = server.url("/index.json").toUrl()
            val first = catalog()
            first.refresh()
            val document = decodePalette(validFixture().toByteArray())
            assertEquals(listOf(document), first.state.value.palettes)
            assertFalse(first.state.value.cached)
            assertNull(first.state.value.error)
            assertArrayEquals(bytes, cache.readBytes())
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/index.json", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            server.shutdown()
            val reloaded = PaletteCatalog(context, endpoint)
            reloaded.refresh()
            assertEquals(listOf(document), reloaded.state.value.palettes)
            assertTrue(reloaded.state.value.cached)
            assertNotNull(reloaded.state.value.error)
            assertFalse(reloaded.state.value.loading)
        }

    @Test
    fun invalidResponsesNeverReplaceLastGoodCache() =
        runBlocking {
            val good = index(validFixture())
            server.enqueue(MockResponse().setBody(good))
            val loader = catalog()
            loader.refresh()
            val expected = loader.state.value.palettes
            val singleMode = decodePalette(validFixture().toByteArray()).copy(light = null)
            val badBodies =
                listOf(
                    "{",
                    good.replace("\"version\":1", "\"version\":2"),
                    index(invalidFixture()),
                    index(encodePalette(singleMode).toString(Charsets.UTF_8)),
                    index(validFixture(), validFixture()),
                    good + " ".repeat(1024 * 1024),
                    good.replace("\"palettes\":", "\"unknown\":false,\"palettes\":"),
                )
            for (body in badBodies) {
                server.enqueue(MockResponse().setBody(body))
                loader.refresh()
                assertEquals(expected, loader.state.value.palettes)
                assertTrue(loader.state.value.cached)
                assertNotNull(loader.state.value.error)
                assertFalse(loader.state.value.loading)
                assertEquals(good, cache.readText())
            }
            assertEquals(listOf("palette-catalog.json"), context.filesDir.list()!!.toList())
        }

    @Test
    fun gzipIsDecodedBeforeLimitAndValidation() =
        runBlocking {
            val good = index(validFixture())
            val loader = catalog()
            server.enqueue(gzipResponse(good))
            loader.refresh()
            assertNull(loader.state.value.error)
            assertEquals(1, loader.state.value.palettes.size)
            assertEquals(good, cache.readText())
            for (body in listOf(good + " ".repeat(1024 * 1024), index(invalidFixture()))) {
                server.enqueue(gzipResponse(body))
                loader.refresh()
                assertNotNull(loader.state.value.error)
                assertEquals(good, cache.readText())
            }
        }

    @Test
    fun corruptCacheIsRevalidatedAndNotDisplayed() =
        runBlocking {
            cache.writeText(index(invalidFixture()))
            server.enqueue(MockResponse().setResponseCode(503))
            val loader = catalog()
            loader.refresh()
            assertTrue(
                loader.state.value.palettes
                    .isEmpty(),
            )
            assertFalse(loader.state.value.cached)
            assertNotNull(loader.state.value.error)
            assertEquals(index(invalidFixture()), cache.readText())
            server.enqueue(MockResponse().setBody(index(validFixture())))
            loader.refresh()
            assertEquals(1, loader.state.value.palettes.size)
            assertNull(loader.state.value.error)
        }

    @Test
    fun emptyCatalogIsValidAndErrorsRecover() =
        runBlocking {
            val loader = catalog()
            server.enqueue(MockResponse().setResponseCode(500))
            loader.refresh()
            assertNotNull(loader.state.value.error)
            assertFalse(cache.exists())
            server.enqueue(MockResponse().setBody(index()))
            loader.refresh()
            assertEquals(PaletteCatalogState(), loader.state.value)
            assertEquals(index(), cache.readText())
        }

    @Test
    fun redirectIsRejectedWithoutFollowingLocation() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            val loader = catalog()
            loader.refresh()
            assertNotNull(loader.state.value.error)
            assertEquals(1, server.requestCount)
            assertFalse(cache.exists())
        }

    @Test
    fun sharedDuplicateFixturesUseSameCanonicalTree() {
        val expected = decodePalette(validFixture().toByteArray())
        assertEquals(listOf(expected), decodePaletteCatalog(index(validFixture()).toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            decodePaletteCatalog(index(invalidFixture()).toByteArray())
        }
        val duplicateCatalogKeys = "{\"format\":false,\"format\":\"motd-palette-catalog\",\"version\":false,\"version\":1,\"palettes\":false,\"palettes\":[${validFixture()}]}"
        assertEquals(listOf(expected), decodePaletteCatalog(duplicateCatalogKeys.toByteArray()))
    }

    @Test
    fun strictIndexTypesUtf8SizeAndEntryCountAreEnforced() {
        val empty = index()
        val invalid =
            listOf(
                empty.replace("\"version\":1", "\"version\":true"),
                empty.replace("\"version\":1", "\"version\":\"1\""),
                empty.replace("\"version\":1", "\"version\":1.0"),
                empty.replace("\"format\":\"motd-palette-catalog\"", "\"format\":false"),
                index(*Array(257) { validFixture() }),
            )
        for (body in invalid) {
            assertThrows(IllegalArgumentException::class.java) { decodePaletteCatalog(body.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { decodePaletteCatalog(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { decodePaletteCatalog(ByteArray(1024 * 1024 + 1)) }
        val palette = decodePalette(validFixture().toByteArray())
        val maximum = (1..256).map { encodePalette(palette.copy(id = "fixture-$it")).toString(Charsets.UTF_8) }
        assertEquals(256, decodePaletteCatalog(index(*maximum.toTypedArray()).toByteArray()).size)
        val atCap = empty + " ".repeat(1024 * 1024 - empty.toByteArray().size)
        assertTrue(decodePaletteCatalog(atCap.toByteArray()).isEmpty())
    }

    @Test
    fun cancellingRefreshRethrowsAndClearsLoadingWithoutReplacingCache() =
        runBlocking {
            val good = index(validFixture())
            cache.writeText(good)
            server.enqueue(MockResponse().setBody(good).setBodyDelay(3, TimeUnit.SECONDS))
            val loader = catalog()
            val request = async { loader.refresh() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertFalse(loader.state.value.loading)
            assertNull(loader.state.value.error)
            assertEquals(good, cache.readText())
            server.enqueue(MockResponse().setBody(index()))
            loader.refresh()
            assertEquals(PaletteCatalogState(), loader.state.value)
        }

    @Test
    fun wholeDeadlineBoundsAResponseThatNeverHitsSocketTimeout() =
        runBlocking {
            server.enqueue(MockResponse().setBody(index(validFixture())).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val loader = catalog()
            val started = System.nanoTime()
            withTimeout(20_000) { loader.refresh() }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("Whole deadline exceeded: $elapsed ms", elapsed < 19_000)
            assertNotNull(loader.state.value.error)
            assertFalse(loader.state.value.loading)
            assertFalse(cache.exists())
        }

    @Test
    fun concurrentRefreshesSerializeTheirFetchAndCacheReplacement() =
        runBlocking {
            server.enqueue(MockResponse().setBody(index(validFixture())).setHeadersDelay(700, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(index()))
            val loader = catalog()
            val first = async { loader.refresh() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            val second = async { loader.refresh() }
            assertNull(withContext(Dispatchers.IO) { server.takeRequest(150, TimeUnit.MILLISECONDS) })
            first.await()
            second.await()
            assertEquals(2, server.requestCount)
            assertEquals(PaletteCatalogState(), loader.state.value)
            assertEquals(index(), cache.readText())
        }

    @Test
    fun shareLinkUsesValidatedUpstreamIdentityAndRevision() {
        assertEquals(
            "https://github.com/trevarj/motd/blob/main/palettes/upstream-palette/7.motd-palette.json",
            catalogPaletteLink("upstream-palette", 7),
        )
        assertThrows(IllegalArgumentException::class.java) { catalogPaletteLink("../escape", 1) }
        assertThrows(IllegalArgumentException::class.java) { catalogPaletteLink("valid-id", 0) }
    }

    private fun catalog() = PaletteCatalog(context, server.url("/index.json").toUrl())

    private fun validFixture() = fixture("duplicate-last-valid")

    private fun invalidFixture() = fixture("duplicate-last-invalid")

    private fun fixture(name: String) = javaClass.getResource("/palettes/$name.motd-palette.json")!!.readText()

    private fun index(vararg documents: String) = "{\"format\":\"motd-palette-catalog\",\"version\":1,\"palettes\":[${documents.joinToString(",")}]}"

    private fun gzipResponse(body: String): MockResponse {
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    GZIPOutputStream(output).use { it.write(body.toByteArray()) }
                }.toByteArray()
        return MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(bytes))
    }
}
