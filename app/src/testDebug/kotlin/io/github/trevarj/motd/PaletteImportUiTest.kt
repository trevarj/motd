package io.github.trevarj.motd

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.drawToBitmap
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.JoinInviteV1
import io.github.trevarj.motd.ui.nav.ChatListRoute
import io.github.trevarj.motd.ui.nav.JoinInviteRoute
import io.github.trevarj.motd.ui.nav.PalettePreviewRoute
import io.github.trevarj.motd.ui.nav.SharePickerRoute
import io.github.trevarj.motd.ui.settings.PalettePreviewScreen
import io.github.trevarj.motd.ui.settings.PalettePreviewViewModel
import io.github.trevarj.motd.ui.share.*
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class PaletteImportUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = AppearancePrefsImpl(context)
    private val settings = DataStoreSettingsRepository(context)
    private val models = ViewModelStore()
    private val shares = PendingShareStore()
    private val provider = ImportProvider()
    private val consumed = mutableListOf<Intent>()
    private var serial = 0
    private var intake by mutableStateOf<PaletteIntentIntake?>(null)
    private lateinit var handle: SavedStateHandle
    private lateinit var nav: NavHostController
    private lateinit var originalAppearance: Preferences
    private lateinit var originalSettings: Preferences
    private lateinit var baseline: AppearanceConfig
    private val fixture: ByteArray get() =
        requireNotNull(
            javaClass.getResourceAsStream(
                "/palettes/duplicate-last-valid.motd-palette.json",
            ),
        ).use { it.readBytes() }

    @Before fun isolatePreferences() =
        runBlocking {
            originalAppearance = context.appearanceDataStore.data.first()
            originalSettings = context.settingsDataStore.data.first()
            context.appearanceDataStore.edit { it.clear() }
            context.settingsDataStore.edit { it.clear() }
            prefs.selectPalette(PaletteSelection(dark = PaletteChoice(ColorThemePreset.DARK), mode = PaletteMode.DARK))
            baseline = current()
            provider.attachInfo(context, ProviderInfo().apply { authority = "palette-import.test" })
            ShadowContentResolver.registerProviderInternal("palette-import.test", provider)
            handle = SavedStateHandle()
            intake = owner(handle)
        }

    @After fun restorePreferences(): Unit =
        runBlocking {
            provider.entries.values.forEach { it.release.countDown() }
            models.clear()
            context.appearanceDataStore.edit { target ->
                target.clear()
                originalAppearance.asMap().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    target[key as Preferences.Key<Any>] = value
                }
            }
            context.settingsDataStore.edit { target ->
                target.clear()
                originalSettings.asMap().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    target[key as Preferences.Key<Any>] = value
                }
            }
            provider.entries.values.forEach { it.file.delete() }
        }

    @Test fun coldAndWarmMarkedFilesPreviewWithoutInstalling_andCancelDoesNotApply() {
        val cold = source(fixture, "cold.json")
        receive(view(cold))
        mount()
        awaitPreview("Duplicate fixture")
        assertEquals(baseline, current())
        capture("import-cold-preview.png")
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }
        val warmDocument = decodePalette(fixture).copy(id = "warm-palette", name = "Warm import")
        val warm = source(encodePalette(warmDocument), "warm.motd-palette.json")
        receive(send(warm))
        awaitPreview("Warm import")
        assertEquals(
            warmDocument,
            decodePalette(
                nav.currentBackStackEntry!!
                    .toRoute<PalettePreviewRoute>()
                    .paletteJson
                    .toByteArray(),
            ),
        )
        assertEquals(2, consumed.size)
        assertEquals(baseline, current())
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }
        assertEquals(baseline, current())
        assertFalse(provider.openedOnMain)
    }

    @Test fun markedDocumentReplacesOpenPreviewSnapshot_andCancelReturnsToOriginalChat() {
        mount()
        val originalChat = nav.currentBackStackEntry
        val firstDocument = decodePalette(fixture)
        val first = view(source(fixture, "first.motd-palette.json"))
        receive(first)
        awaitPreview(firstDocument.name)
        compose.onNodeWithText("Palette preview").assertIsDisplayed()
        compose.onNodeWithText("Revision ${firstDocument.revision}").assertIsDisplayed()
        compose.onNodeWithText("Light and dark").assertIsDisplayed()
        assertEquals(baseline, current())
        val firstPreview = nav.currentBackStackEntry

        val newestDocument =
            firstDocument.copy(
                id = "replacement-palette",
                name = "Replacement import",
                revision = 2,
                dark = null,
            )
        val newest = send(source(encodePalette(newestDocument), "replacement.motd-palette.json"))
        receive(newest)
        awaitPreview(newestDocument.name)
        compose.onNodeWithText("Palette preview").assertIsDisplayed()
        compose.onNodeWithText("Revision ${newestDocument.revision}").assertIsDisplayed()
        compose.onNodeWithText("Light only").assertIsDisplayed()
        compose.onNodeWithText(firstDocument.name).assertDoesNotExist()
        compose.onNodeWithText("Revision ${firstDocument.revision}").assertDoesNotExist()
        assertNotSame(firstPreview, nav.currentBackStackEntry)
        assertEquals(
            newestDocument,
            decodePalette(
                nav.currentBackStackEntry!!
                    .toRoute<PalettePreviewRoute>()
                    .paletteJson
                    .toByteArray(),
            ),
        )
        assertEquals(listOf(first, newest), consumed)
        assertEquals(baseline, current())

        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }
        assertSame(originalChat, nav.currentBackStackEntry)
        compose.onNodeWithText("Channels").assertIsDisplayed()
        compose.onNodeWithText("Palette preview").assertDoesNotExist()
        assertEquals(baseline, current())
        assertFalse(provider.openedOnMain)
    }

    @Test fun genericJsonSendRemainsAnAttachment_butUnmarkedViewShowsAnError() {
        mount()
        val generic = source("{\"name\":\"ordinary JSON\"}".toByteArray(), "ordinary.json")
        receive(send(generic))
        await { nav.currentDestination?.hasRoute<SharePickerRoute>() == true }
        assertEquals(PendingShare.File(generic, "application/json"), shares.consume())
        compose.runOnIdle { nav.popBackStack() }
        receive(view(generic))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Not a MOTD palette").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Not a MOTD palette").assertIsDisplayed()
        assertTrue(nav.currentDestination!!.hasRoute<ChatListRoute>())
        compose.onNodeWithTag("palette_preview_apply").assertDoesNotExist()
        assertEquals(baseline, current())
        capture("import-unmarked-error.png", dialog = true)
        compose.onNodeWithText("OK").performClick()
    }

    @Test fun newerIntentWinsOverBlockedOldRead_andOldConsumeCannotClearNewPending() {
        val stale = source(fixture, "stale.json")
        receive(view(stale))
        await { intake!!.pending.value != null }
        val staleToken = intake!!.pending.value!!.token
        val slow = source(fixture, "slow.json", held = true)
        receive(view(slow))
        val blocked = provider.entry(slow)
        assertTrue("Old provider must enter its IO open", blocked.entered.await(10, TimeUnit.SECONDS))
        assertNull(blocked.descriptor)
        val newestDocument = decodePalette(fixture).copy(id = "newest", name = "Newest import")
        val newest = source(encodePalette(newestDocument), "newest.json")
        val newestIntent = view(newest)
        receive(newestIntent)
        await { intake!!.pending.value?.source === newestIntent }
        val newestToken = intake!!.pending.value!!.token
        compose.runOnUiThread {
            assertNull(intake!!.consume(staleToken))
            assertTrue(intake!!.isCurrent(newestToken))
        }
        mount()
        awaitPreview("Newest import")
        blocked.release.countDown()
        await { blocked.descriptor?.valid() == false }
        assertFalse(requireNotNull(provider.entry(newest).descriptor).valid())
        compose.runOnIdle {
            assertNull(intake!!.consume(staleToken))
            assertNull(intake!!.consume(newestToken))
        }
        assertEquals(
            newestDocument,
            decodePalette(
                nav.currentBackStackEntry!!
                    .toRoute<PalettePreviewRoute>()
                    .paletteJson
                    .toByteArray(),
            ),
        )
        assertEquals(listOf(newestIntent), consumed)
        assertEquals(baseline, current())
        assertFalse(provider.openedOnMain)
    }

    @Test fun revokedGrantActualOverCapFileAndUnsupportedVersionNeverOfferApply() {
        mount()
        val revoked = source(fixture, "revoked.motd-palette.json")
        provider.entry(revoked).revoked = true
        val over = source(ByteArray(PALETTE_MAX_BYTES + 64) { 32 }, "oversize.motd-palette.json")
        val unsupported =
            source(
                encodePalette(decodePalette(fixture))
                    .decodeToString()
                    .replace("\"version\":1", "\"version\":2")
                    .toByteArray(),
                "future.json",
            )
        for ((uri, message) in listOf(revoked to "Could not read palette", over to "Palette exceeds byte limit", unsupported to "Unsupported palette version")) {
            receive(view(uri))
            compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithTag("palette_preview_apply").assertDoesNotExist()
            assertEquals(baseline, current())
            compose.onNodeWithText("OK").performClick()
        }
        assertFalse(requireNotNull(provider.entry(over).descriptor).valid())
        assertFalse(requireNotNull(provider.entry(unsupported).descriptor).valid())
        assertFalse(provider.openedOnMain)
    }

    @Test fun rotationRestoresConsumedErrorWithoutReopeningGrant() {
        mount()
        val uri =
            source(
                encodePalette(decodePalette(fixture))
                    .decodeToString()
                    .replace("\"version\":1", "\"version\":2")
                    .toByteArray(),
                "rotation-error.json",
            )
        val original = view(uri)
        receive(original)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Unsupported palette version").fetchSemanticsNodes().isNotEmpty() && consumed.size == 1
        }
        val delivery = intake!!.delivery.value
        assertTrue(delivery > 0L)
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        provider.entry(uri).revoked = true
        compose.runOnIdle {
            intake = owner(restored)
            intake!!.receive(original, restoring = true)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Unsupported palette version").assertIsDisplayed()
        compose.onNodeWithTag("palette_preview_apply").assertDoesNotExist()
        assertEquals(delivery, intake!!.delivery.value)
        assertEquals(1, provider.entry(uri).opens.get())
        assertEquals(1, consumed.size)
        assertEquals(baseline, current())
    }

    @Test fun rotationRestoresPreviewWithoutReopeningConsumedGrant_andCancelLeavesPrefsUntouched() {
        val uri = source(fixture, "rotation.json")
        val original = view(uri)
        receive(original)
        mount()
        awaitPreview("Duplicate fixture")
        assertEquals(1, provider.entry(uri).opens.get())
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        provider.entry(uri).revoked = true
        compose.runOnIdle {
            intake = owner(restored)
            intake!!.receive(original, restoring = true)
        }
        restoration.emulateSavedInstanceStateRestore()
        awaitPreview("Duplicate fixture")
        assertEquals(1, provider.entry(uri).opens.get())
        assertEquals(1, consumed.size)
        assertEquals(baseline, current())
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }
        assertEquals(baseline, current())
    }

    @Test fun externalExtrasCannotPoisonRestoreMetadata() {
        mount()
        val uri = source(fixture, "external-metadata.json")
        val incoming =
            view(uri).apply {
                putExtra("deliveryCounter", "bad")
                putExtra("consumedSource", 5)
                putExtra("unrelated-large-extra", "x".repeat(64 * 1024))
            }
        val inheritedState =
            SavedStateHandle(
                mapOf(
                    "deliveryCounter" to incoming.getStringExtra("deliveryCounter"),
                    "consumedSource" to incoming.getIntExtra("consumedSource", 0),
                    "unrelated-large-extra" to incoming.getStringExtra("unrelated-large-extra"),
                ),
            )
        compose.runOnIdle {
            intake = owner(inheritedState)
            intake!!.receive(incoming, restoring = true)
        }
        awaitPreview("Duplicate fixture")
        assertEquals(1, provider.entry(uri).opens.get())
        assertSame(incoming, consumed.single())
        assertEquals(baseline, current())
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }
        assertEquals(baseline, current())
    }

    @Test fun restoredConsumedDelivery_doesNotSuppressDifferentIncomingIntent() {
        val originalUri = source(fixture, "handled-before-restoration.json")
        val original = view(originalUri)
        receive(original)
        mount()
        awaitPreview("Duplicate fixture")
        assertEquals(1, consumed.size)
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }

        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val differentDocument = decodePalette(fixture).copy(id = "restored-new-source", name = "New restored delivery")
        val differentUri = source(encodePalette(differentDocument), "different-restored.json")
        val different = view(differentUri)
        compose.runOnIdle {
            intake = owner(restored)
            intake!!.receive(original, restoring = true)
        }
        compose.waitForIdle()
        assertEquals(1, provider.entry(originalUri).opens.get())
        assertEquals(1, consumed.size)
        compose.runOnIdle { intake!!.receive(different, restoring = true) }
        awaitPreview(differentDocument.name)
        assertEquals(
            differentDocument,
            decodePalette(
                nav.currentBackStackEntry!!
                    .toRoute<PalettePreviewRoute>()
                    .paletteJson
                    .toByteArray(),
            ),
        )
        assertEquals(1, provider.entry(differentUri).opens.get())
        assertSame(different, consumed.last())
        assertEquals(baseline, current())
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }

        receive(Intent(original))
        awaitPreview("Duplicate fixture")
        assertEquals(2, provider.entry(originalUri).opens.get())
        assertEquals(3, consumed.size)
        assertEquals(baseline, current())
    }

    @Test fun inviteTextAndMultipleRetainTheirNativeClassification() {
        mount()
        val invite = JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")
        receive(Intent(Intent.ACTION_VIEW, Uri.parse(JoinInviteCodec.appUri(invite))))
        await { nav.currentDestination?.hasRoute<JoinInviteRoute>() == true }
        assertEquals(JoinInviteCodec.encode(invite), nav.currentBackStackEntry!!.toRoute<JoinInviteRoute>().payload)
        compose.runOnIdle { nav.popBackStack() }
        val attachment = source(fixture, "text.json")
        receive(
            send(attachment).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello channel")
            },
        )
        await { nav.currentDestination?.hasRoute<SharePickerRoute>() == true }
        assertEquals(PendingShare.Text("hello channel"), shares.consume())
        assertEquals(0, provider.entry(attachment).opens.get())
        compose.runOnIdle { nav.popBackStack() }
        val multiple =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/json"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(attachment))
            }
        assertNull(runBlocking { classifyIncomingContent(context, multiple) })
        receive(multiple)
        compose.waitForIdle()
        assertTrue(nav.currentDestination!!.hasRoute<ChatListRoute>())
        assertEquals(2, consumed.size)
        assertEquals(baseline, current())
    }

    @Test fun markerAndDecodeUseSameDuplicateLastWinsTree() {
        mount()
        val valid = source(fixture, "duplicate.json")
        receive(send(valid))
        awaitPreview("Duplicate fixture")
        val route = nav.currentBackStackEntry!!.toRoute<PalettePreviewRoute>()
        assertEquals(decodePalette(fixture), decodePalette(route.paletteJson.toByteArray()))
        assertEquals(1, provider.entry(valid).opens.get())
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { nav.currentDestination?.hasRoute<ChatListRoute>() == true }
        val unmarkedLast = source((fixture.decodeToString().trimEnd().dropLast(1) + ",\"format\":\"ordinary\"}").toByteArray(), "last-unmarked.json")
        receive(send(unmarkedLast))
        await { nav.currentDestination?.hasRoute<SharePickerRoute>() == true }
        assertEquals(PendingShare.File(unmarkedLast, "application/json"), shares.consume())
        compose.runOnIdle { nav.popBackStack() }
        val invalid =
            source(
                requireNotNull(
                    javaClass.getResourceAsStream(
                        "/palettes/duplicate-last-invalid.motd-palette.json",
                    ),
                ).use { it.readBytes() },
                "last-invalid.json",
            )
        receive(view(invalid))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Palette import").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Palette import").assertIsDisplayed()
        compose.onNodeWithTag("palette_preview_apply").assertDoesNotExist()
        assertEquals(1, provider.entry(invalid).opens.get())
        assertEquals(baseline, current())
    }

    private fun owner(state: SavedStateHandle) = PaletteIntentIntake(context, state).also { models.put("intake-${serial++}", it) }

    private fun receive(intent: Intent) = compose.runOnUiThread { intake!!.receive(intent) }

    private fun current() = runBlocking { prefs.config.first() }

    private fun await(condition: () -> Boolean) =
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            condition()
        }

    private fun awaitPreview(name: String) {
        await { nav.currentDestination?.hasRoute<PalettePreviewRoute>() == true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithTag("palette_preview_apply").assertIsEnabled()
    }

    private fun mount() {
        restoration.setContent {
            val appearance by prefs.config.collectAsState(AppearanceConfig())
            val selection = appearance.paletteSelection
            val dark = selection.mode == PaletteMode.DARK || (selection.mode == PaletteMode.SYSTEM && isSystemInDarkTheme())
            val choice = selection.choiceFor(dark)
            MotdTheme(
                themePreset = choice.builtin,
                paletteMode = if (dark) PaletteMode.DARK else PaletteMode.LIGHT,
                customPalette = appearance.customPalette(choice),
                dynamicColor = false,
                syncSystemBars = false,
            ) {
                nav = rememberNavController()
                Surface(Modifier.fillMaxSize()) {
                    NavHost(navController = nav, startDestination = ChatListRoute) {
                        composable<ChatListRoute> { Text("Channels") }
                        composable<PalettePreviewRoute> { entry ->
                            val route = entry.toRoute<PalettePreviewRoute>()
                            val model =
                                remember(entry) {
                                    PalettePreviewViewModel(
                                        SavedStateHandle(
                                            mapOf(
                                                "paletteJson" to route.paletteJson,
                                                "sourceLink" to route.sourceLink,
                                                "catalogId" to route.catalogId,
                                                "targetInstalledId" to route.targetInstalledId,
                                                "assignmentTarget" to route.assignmentTarget,
                                            ),
                                        ),
                                        prefs,
                                        settings,
                                    ).also { models.put("preview-${serial++}", it) }
                                }
                            PalettePreviewScreen(onBack = { nav.popBackStack() }, onInstalled = { nav.popBackStack() }, viewModel = model)
                        }
                        composable<JoinInviteRoute> { Text("Join invite") }
                        composable<SharePickerRoute> { Text("Share picker") }
                    }
                    IncomingContentNavigation(intake!!, nav, shares::set, { consumed.add(it) })
                }
            }
        }
    }

    private fun source(
        bytes: ByteArray,
        name: String,
        held: Boolean = false,
    ): Uri {
        val uri = Uri.parse("content://palette-import.test/${serial++}")
        val file = File.createTempFile("native-import-", ".json", context.cacheDir).apply { writeBytes(bytes) }
        provider.entries[uri.toString()] = ImportEntry(file, name, held)
        return uri
    }

    private fun send(uri: Uri) =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun view(uri: Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/json")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun capture(
        name: String,
        dialog: Boolean = false,
    ) {
        compose.waitForIdle()
        val bitmap =
            if (dialog) {
                compose.runOnIdle {
                    requireNotNull(requireNotNull(ShadowDialog.getLatestDialog()).window).decorView.drawToBitmap()
                }
            } else {
                compose.onRoot().captureToImage().asAndroidBitmap()
            }
        val module = if (File("app/src").isDirectory) File("app") else File(".")
        val file = File(module, "build/reports/palettes/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

private class ImportEntry(
    val file: File,
    val name: String,
    held: Boolean,
) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(if (held) 1 else 0)
    val opens = AtomicInteger()

    @Volatile var descriptor: FileDescriptor? = null

    @Volatile var revoked = false
}

private class ImportProvider : ContentProvider() {
    val entries = ConcurrentHashMap<String, ImportEntry>()

    @Volatile var openedOnMain = false

    fun entry(uri: Uri) = requireNotNull(entries[uri.toString()])

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/json"

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ) = AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val item = entry(uri)
        openedOnMain = openedOnMain || Looper.myLooper() == Looper.getMainLooper()
        item.opens.incrementAndGet()
        if (item.revoked) throw SecurityException("Grant revoked")
        item.entered.countDown()
        check(item.release.await(10, TimeUnit.SECONDS)) { "Held palette open was not released" }
        return ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.parseMode(mode)).also {
            item.descriptor = it.fileDescriptor
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(entry(uri).name, 1L)) }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}
