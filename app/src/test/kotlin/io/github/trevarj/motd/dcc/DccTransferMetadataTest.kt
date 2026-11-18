package io.github.trevarj.motd.dcc

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.MetadataNotSupportedException
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadMetadataSanitizer
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DccTransferMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun disabledPolicyStagesOriginalAndCancelledOwnerCleansItWithoutOpeningSocket() =
        runBlocking {
            withController { controller, prefs, db, bufferId, cache ->
                prefs.setConfig(PasteBackendConfig(stripMetadata = false, sizeLimitBytes = 1))
                val original = temporary.newFile("private.pdf").apply { writeText("%PDF-1.7 /Author(private-location)") }
                controller.sendFile(bufferId, original.toUri())
                val transfer =
                    db
                        .dccTransferDao()
                        .observeAll()
                        .first()
                        .single()
                assertEquals(original.name, transfer.displayFilename)
                assertEquals(original.length(), transfer.sizeBytes)
                assertTrue(original.exists())
                assertTrue(
                    cache
                        .walkTopDown()
                        .filter { it.isFile }
                        .toList()
                        .isEmpty(),
                )
            }
        }

    @Test fun enabledPolicyRejectsUnsupportedAndMalformedMediaEvenWithOriginalConsent() =
        runBlocking {
            withController { controller, prefs, db, bufferId, _ ->
                prefs.setConfig(PasteBackendConfig(stripMetadata = true))
                val unsupported = temporary.newFile("private.pdf").apply { writeText("%PDF-1.7 /Author(private)") }
                try {
                    controller.sendFile(bufferId, unsupported.toUri())
                    fail("Unsupported original was accepted")
                } catch (_: MetadataNotSupportedException) {
                    assertTrue(
                        db
                            .dccTransferDao()
                            .observeAll()
                            .first()
                            .isEmpty(),
                    )
                }
                val malformed =
                    temporary.newFile("broken.png").apply {
                        writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                    }
                try {
                    controller.sendFile(bufferId, malformed.toUri(), allowOriginalMetadata = true)
                    fail("Malformed image fell back to original")
                } catch (_: java.io.IOException) {
                    assertTrue(
                        db
                            .dccTransferDao()
                            .observeAll()
                            .first()
                            .isEmpty(),
                    )
                }
            }
        }

    private suspend fun withController(body: suspend (DccTransferControllerImpl, AttachmentPrefsImpl, MotdDatabase, Long, File) -> Unit) {
        val cache = temporary.newFolder()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getCacheDir(): File = cache
            }
        val db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).apply { cancel() }
        val prefs = AttachmentPrefsImpl(context)
        val previous = prefs.config.first()
        try {
            val networkId = db.networkDao().insert(NetworkEntity(name = "test", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            val bufferId = db.bufferDao().insert(BufferEntity(networkId = networkId, name = "peer", displayName = "peer", type = BufferType.QUERY))
            val socks = LocalSocksProvider.forTest { error("DCC fixture must not open proxy sockets") }
            body(DccTransferControllerImpl(context, db, NoopConnectionManager(), socks, scope, UploadMetadataSanitizer(context), prefs), prefs, db, bufferId, cache)
        } finally {
            prefs.setConfig(previous)
            db.close()
        }
    }
}
