package io.github.trevarj.motd.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.ui.settings.AppearanceSettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomWallpaperStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = CustomWallpaperStore(context)
    private val directory get() = File(context.filesDir, CustomWallpaperStore.DIRECTORY)
    private lateinit var source: File

    @Before fun setUp() {
        directory.deleteRecursively()
        source = File(context.cacheDir, "wallpaper-source.png")
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun importsDecodedImagesWithUniqueNamesAndDeletesOnlyOwnedFiles() =
        runTest {
            val first = store.import(Uri.fromFile(source)).getOrThrow()
            val second = store.import(Uri.fromFile(source)).getOrThrow()
            assertNotEquals(first, second)
            assertArrayEquals(source.readBytes(), requireNotNull(CustomWallpaperStore.resolve(context, second)).readBytes())
            store.delete(first)
            assertNull(CustomWallpaperStore.resolve(context, first))
            assertTrue(requireNotNull(CustomWallpaperStore.resolve(context, second)).isFile)

            val outside = File(context.cacheDir, first).apply { writeText("keep") }
            Files.createSymbolicLink(File(directory, first).toPath(), outside.toPath())
            for (name in listOf(first, "../${outside.name}", outside.path, Uri.fromFile(outside).toString(), "https://example.com/photo.png")) {
                assertNull(CustomWallpaperStore.resolve(context, name))
                store.delete(name)
            }
            assertEquals("keep", outside.readText())
        }

    @Test fun invalidOversizedProviderFailureAndCancellationKeepExistingImageAndLeaveNoTemporaryFile() =
        runTest {
            val current = store.import(Uri.fromFile(source)).getOrThrow()
            val bytes = requireNotNull(CustomWallpaperStore.resolve(context, current)).readBytes()
            for (invalid in listOf("not an image".toByteArray(), source.readBytes().copyOf(33))) {
                val file = File(context.cacheDir, "invalid.png").apply { writeBytes(invalid) }
                assertTrue(store.import(Uri.fromFile(file)).isFailure)
            }
            val oversized = Uri.parse("content://wallpaper.test/oversized")
            var supplied = 0L
            shadowOf(context.contentResolver).registerInputStream(
                oversized,
                object : InputStream() {
                    override fun read(): Int = 0

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        supplied += length
                        return length
                    }
                },
            )
            assertTrue(store.import(oversized).isFailure)
            assertTrue(supplied <= CustomWallpaperStore.MAX_BYTES + DEFAULT_BUFFER_SIZE)
            for (failure in listOf(SecurityException("revoked grant"), IOException("provider failed"), CancellationException("cancelled"))) {
                val uri = Uri.parse("content://wallpaper.test/failure")
                shadowOf(context.contentResolver).registerInputStream(
                    uri,
                    object : InputStream() {
                        override fun read(): Int = throw failure
                    },
                )
                if (failure is CancellationException) {
                    try {
                        store.import(uri)
                        throw AssertionError("Cancellation must propagate")
                    } catch (_: CancellationException) {
                        // Cancellation is not an invalid-image result.
                    }
                } else {
                    assertTrue(store.import(uri).isFailure)
                }
            }
            assertArrayEquals(bytes, requireNotNull(CustomWallpaperStore.resolve(context, current)).readBytes())
            assertEquals(listOf(current), directory.listFiles().orEmpty().map { it.name })
        }

    @Test fun viewModelRollsBackFailedPersistenceAndSerializesImportBeforeBuiltinRemoval() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val initialName = store.import(Uri.fromFile(source)).getOrThrow()
            val initial = WallpaperSelection(ChatWallpaperPreset.RETRO_CHAT, 73, initialName)
            val configFlow = MutableStateFlow(AppearanceConfig(wallpaper = initial))
            var reject = true
            var persistenceStarted: CompletableDeferred<Unit>? = null
            var releasePersistence: CompletableDeferred<Unit>? = null
            val prefs =
                object : AppearancePrefs by AppearancePrefsImpl(context) {
                    override val config = configFlow

                    override suspend fun setWallpaper(selection: WallpaperSelection) {
                        configFlow.value.wallpaper.localImageName?.let { previousName ->
                            assertTrue("Old image must survive until preferences commit", File(directory, previousName).exists())
                        }
                        if (reject) throw IOException("DataStore write failed")
                        persistenceStarted?.complete(Unit)
                        releasePersistence?.await()
                        configFlow.value = configFlow.value.copy(wallpaper = selection)
                    }
                }
            val vm = AppearanceSettingsViewModel(DataStoreSettingsRepository(context), prefs, CustomFontStore(context), store)
            try {
                vm.importWallpaper(Uri.fromFile(source)).join()
                assertEquals(initial, configFlow.value.wallpaper)
                assertEquals(listOf(initialName), directory.listFiles().orEmpty().map { it.name })
                vm.setWallpaper(initial.copy(localImageName = null)).join()
                assertEquals(initial, configFlow.value.wallpaper)
                assertTrue(File(directory, initialName).exists())

                reject = false
                persistenceStarted = CompletableDeferred()
                releasePersistence = CompletableDeferred()
                val importing = vm.importWallpaper(Uri.fromFile(source))
                requireNotNull(persistenceStarted).await()
                assertTrue(vm.wallpaperImporting.value)
                val removing = vm.setWallpaper(WallpaperSelection(ChatWallpaperPreset.NONE, 73))
                requireNotNull(releasePersistence).complete(Unit)
                importing.join()
                assertFalse(File(directory, initialName).exists())
                // The second write must see the newly persisted image, not the stale preference snapshot.
                persistenceStarted = null
                releasePersistence = null
                removing.join()
                assertEquals(WallpaperSelection(ChatWallpaperPreset.NONE, 73), configFlow.value.wallpaper)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertFalse(vm.wallpaperImporting.value)

                persistenceStarted = CompletableDeferred()
                releasePersistence = CompletableDeferred()
                val cancelledImport = vm.importWallpaper(Uri.fromFile(source))
                requireNotNull(persistenceStarted).await()
                assertEquals(1, directory.listFiles().orEmpty().size)
                cancelledImport.cancel()
                requireNotNull(releasePersistence).complete(Unit)
                cancelledImport.join()
                val committed = configFlow.value.wallpaper
                assertEquals(ChatWallpaperPreset.NONE, committed.preset)
                assertEquals(73, committed.intensity)
                assertTrue(requireNotNull(CustomWallpaperStore.resolve(context, committed.localImageName)).exists())
                assertEquals(listOf(committed.localImageName), directory.listFiles().orEmpty().map { it.name })
                assertFalse(vm.wallpaperImporting.value)
            } finally {
                vm.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }
}
