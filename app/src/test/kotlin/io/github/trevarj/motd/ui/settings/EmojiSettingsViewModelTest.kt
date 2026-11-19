package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.emoji.EmojiSetStore
import io.github.trevarj.motd.data.emoji.EmojiSets
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.WallpaperWorkshopStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmojiSettingsViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application: Context = ApplicationProvider.getApplicationContext()
    private val context by lazy {
        object : ContextWrapper(application) {
            override fun getFilesDir(): File = temporary.root
        }
    }
    private val font by lazy { application.resources.openRawResource(R.font.jetbrains_mono_wght).use { it.readBytes() } }
    private val models = mutableListOf<AppearanceSettingsViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private inner class Fixture(
        initial: String = EmojiSets.SYSTEM_ID,
    ) {
        val config = MutableStateFlow(AppearanceConfig(emojiSetId = initial))
        val connections = AtomicInteger()
        var rejectSelection = false
        var response: (URL) -> HttpURLConnection = { Connection(it, font) }
        val prefs =
            object : AppearancePrefs by AppearancePrefsImpl(context) {
                override val config = this@Fixture.config

                override suspend fun setEmojiSetId(id: String) {
                    if (rejectSelection) throw IOException("Preference write failed")
                    config.value = config.value.copy(emojiSetId = id)
                }
            }
        val store =
            EmojiSetStore(
                context,
                EmojiSets.all.map { spec ->
                    spec.copy(
                        sizeBytes = font.size.toLong(),
                        sha256 = MessageDigest.getInstance("SHA-256").digest(font).joinToString("") { "%02x".format(it) },
                    )
                },
                { url ->
                    connections.incrementAndGet()
                    response(url)
                },
                renderSample = "A",
                requireColor = false,
            )

        suspend fun model(): AppearanceSettingsViewModel {
            val model =
                AppearanceSettingsViewModel(
                    DataStoreSettingsRepository(context),
                    prefs,
                    CustomFontStore(context),
                    WallpaperWorkshopStore(context, prefs, CustomWallpaperStore(context)),
                    store,
                )
            models += model
            await { !model.emojiState.value.loading }
            return model
        }

        fun assertNoTemporaryFiles() {
            assertFalse(File(context.filesDir, "emoji-sets").listFiles().orEmpty().any { it.extension == "tmp" })
        }
    }

    @Test fun selectingSystemSupersedesAnInFlightDownload() =
        runBlocking {
            val fixture = Fixture("openmoji")
            fixture.store.download("openmoji").getOrThrow()
            val model = fixture.model()
            val blocked = BlockedInput()
            fixture.response = { Connection(it, font, blocked) }
            try {
                model.downloadEmojiSet("noto")
                blocked.awaitRead()
                assertEquals("noto", model.emojiState.value.downloadingId)
                assertEquals("noto", model.emojiProgress.value?.id)

                model.selectEmojiSet(EmojiSets.SYSTEM_ID)
                await { fixture.config.value.emojiSetId == EmojiSets.SYSTEM_ID }
                assertNull(fixture.store.installedFile("noto"))
                assertNull(model.emojiState.value.downloadingId)
                assertNull(model.emojiProgress.value)
                assertFalse(model.emojiState.value.error)
                assertTrue(blocked.closed.await(5, TimeUnit.SECONDS))
                fixture.assertNoTemporaryFiles()
            } finally {
                blocked.close()
            }
        }

    @Test fun newerDownloadWinsOverCancelledOlderDownload() =
        runBlocking {
            val fixture = Fixture()
            val model = fixture.model()
            val blocked = BlockedInput()
            fixture.response = { url -> Connection(url, font, if (url.toString() == EmojiSets.find("noto")!!.downloadUrl) blocked else ByteArrayInputStream(font)) }
            try {
                model.downloadEmojiSet("noto")
                blocked.awaitRead()
                model.downloadEmojiSet("openmoji")
                await { fixture.config.value.emojiSetId == "openmoji" && model.emojiState.value.downloadingId == null }
                assertNull(fixture.store.installedFile("noto"))
                assertArrayEquals(font, requireNotNull(fixture.store.installedFile("openmoji")).readBytes())
                assertTrue(
                    model.emojiState.value.installed
                        .containsKey("openmoji"),
                )
                assertFalse(model.emojiState.value.error)
                assertNull(model.emojiProgress.value)
                fixture.assertNoTemporaryFiles()
            } finally {
                blocked.close()
            }
        }

    @Test fun explicitCancelPreservesSelectionAndInstalledFontWithoutReportingFailure() =
        runBlocking {
            val fixture = Fixture("openmoji")
            val previous = fixture.store.download("openmoji").getOrThrow()
            val model = fixture.model()
            val blocked = BlockedInput()
            fixture.response = { Connection(it, font, blocked) }
            try {
                model.downloadEmojiSet("noto")
                blocked.awaitRead()
                model.cancelEmojiDownload()
                assertNull(fixture.store.installedFile("noto"))
                assertEquals("openmoji", fixture.config.value.emojiSetId)
                assertArrayEquals(font, previous.readBytes())
                assertFalse(model.emojiState.value.error)
                assertNull(model.emojiState.value.downloadingId)
                assertNull(model.emojiProgress.value)
                assertTrue(blocked.closed.await(5, TimeUnit.SECONDS))
                fixture.assertNoTemporaryFiles()
            } finally {
                blocked.close()
            }
        }

    @Test fun failedDownloadPreservesPriorSelectionAndInstalledBytes() =
        runBlocking {
            val fixture = Fixture("openmoji")
            val previous = fixture.store.download("openmoji").getOrThrow()
            val model = fixture.model()
            fixture.response = { url ->
                Connection(
                    url,
                    font,
                    object : InputStream() {
                        override fun read(): Int = throw IOException("Offline")
                    },
                )
            }
            model.downloadEmojiSet("noto")
            await { model.emojiState.value.error && model.emojiState.value.downloadingId == null }
            assertEquals("openmoji", fixture.config.value.emojiSetId)
            assertArrayEquals(font, previous.readBytes())
            assertNull(fixture.store.installedFile("noto"))
            assertNull(model.emojiProgress.value)
            fixture.assertNoTemporaryFiles()
        }

    @Test fun failedPreferenceWriteDoesNotReplacePriorSelection() =
        runBlocking {
            val fixture = Fixture("openmoji")
            fixture.store.download("openmoji").getOrThrow()
            val model = fixture.model()
            fixture.rejectSelection = true
            model.downloadEmojiSet("noto")
            await { model.emojiState.value.error && model.emojiState.value.downloadingId == null }
            assertEquals("openmoji", fixture.config.value.emojiSetId)
            assertNotNull(fixture.store.installedFile("openmoji"))
            assertNotNull(fixture.store.installedFile("noto"))
            fixture.assertNoTemporaryFiles()
        }

    @Test fun installedSetCanBeSelectedOfflineWithoutDownloadingAgain() =
        runBlocking {
            val fixture = Fixture()
            val installed = fixture.store.download("noto").getOrThrow()
            val model = fixture.model()
            val requests = fixture.connections.get()
            fixture.response = { throw IOException("Offline") }
            model.selectEmojiSet("noto")
            await { fixture.config.value.emojiSetId == "noto" }
            assertEquals(requests, fixture.connections.get())
            assertEquals(installed, model.emojiState.value.installed["noto"])
            assertFalse(model.emojiState.value.error)
            assertNull(model.emojiState.value.downloadingId)
            assertNull(model.emojiProgress.value)
        }

    @Test fun restoredSelectionWithoutFontIsDetectedAndCanBeDownloadedThenRemoved() =
        runBlocking {
            val fixture = Fixture("noto")
            val model = fixture.model()
            assertEquals("noto", fixture.config.value.emojiSetId)
            assertFalse(
                model.emojiState.value.installed
                    .containsKey("noto"),
            )
            model.selectEmojiSet("noto")
            await { model.emojiState.value.error }
            assertEquals(0, fixture.connections.get())
            assertEquals("noto", fixture.config.value.emojiSetId)

            model.downloadEmojiSet("noto")
            await {
                model.emojiState.value.installed
                    .containsKey("noto") && model.emojiState.value.downloadingId == null
            }
            assertFalse(model.emojiState.value.error)
            val installed = requireNotNull(model.emojiState.value.installed["noto"])
            assertTrue(installed.exists())
            model.removeEmojiSet("noto")
            await {
                !model.emojiState.value.installed
                    .containsKey("noto")
            }
            assertFalse(installed.exists())
            assertEquals("noto", fixture.config.value.emojiSetId)
            model.selectEmojiSet("noto")
            await { model.emojiState.value.error }
            assertEquals(1, fixture.connections.get())
            fixture.assertNoTemporaryFiles()
        }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(1)
        }
    }

    private class BlockedInput : InputStream() {
        private val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            if (!closed.await(10, TimeUnit.SECONDS)) throw IOException("Read was not cancelled")
            throw IOException("Closed")
        }

        override fun close() {
            closed.countDown()
        }

        fun awaitRead() {
            assertTrue("Download never reached the input stream", entered.await(5, TimeUnit.SECONDS))
        }
    }

    private class Connection(
        url: URL,
        private val bytes: ByteArray,
        private val source: InputStream = ByteArrayInputStream(bytes),
    ) : HttpURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getResponseCode() = HTTP_OK

        override fun getContentLengthLong() = bytes.size.toLong()

        override fun getInputStream() = source
    }
}
