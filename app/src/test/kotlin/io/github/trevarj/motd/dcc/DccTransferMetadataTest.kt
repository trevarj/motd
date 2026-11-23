package io.github.trevarj.motd.dcc

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.MetadataNotSupportedException
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadMetadataSanitizer
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.buildChildConfig
import io.github.trevarj.motd.service.networkFingerprint
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DccTransferMetadataTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun disabledPolicyStagesOriginalButClosePreventsAnySend() =
        runBlocking {
            withController { fixture ->
                fixture.prefs.setConfig(PasteBackendConfig(stripMetadata = false, sizeLimitBytes = 1))
                val original = temporary.newFile("original.pdf").apply { writeText("%PDF-1.7 /Author(test-author)") }
                val review = fixture.controller.prepareSend(fixture.bufferId, original.toUri())
                assertEquals(original.name, review.filename)
                assertEquals(original.length(), review.sizeBytes)
                assertEquals("Peer", review.peerNick)
                assertEquals("Direct network", review.networkName)
                assertArrayEquals(original.readBytes(), fixture.stagedFiles().single().readBytes())
                fixture.assertNoOffer()
                review.close()
                review.close()
                fixture.controller.sendPrepared(review)
                fixture.assertNoOffer()
                assertTrue(original.exists())
                assertTrue(fixture.stagedFiles().isEmpty())
            }
        }

    @Test
    fun sanitizedReviewStagesOnlyOnceAndCreatesOneRowOnlyAfterConfirmation() =
        runBlocking {
            withController { fixture ->
                fixture.prefs.setConfig(PasteBackendConfig(stripMetadata = true))
                val original = metadataPng()
                val review = fixture.controller.prepareSend(fixture.bufferId, original.toUri())
                val staged = fixture.stagedFiles().single()
                assertTrue(review.filename.matches(Regex("upload-[0-9a-f-]+\\.png")))
                assertEquals(staged.length(), review.sizeBytes)
                assertTrue(review.sizeBytes < original.length())
                assertFalse(staged.readText(Charsets.ISO_8859_1).contains("test-author"))
                fixture.assertNoOffer()

                // Confirm uses the reviewed copy rather than reopening or sanitizing the picker URI.
                assertTrue(original.delete())
                fixture.connections.remainingReadyLookups.set(1)
                fixture.controller.sendPrepared(review)
                fixture.controller.sendPrepared(review)
                review.close()
                val transfer =
                    withTimeout(5_000) {
                        fixture.controller.observeAll().first { rows -> rows.any { it.state == DccTransferState.FAILED } }
                    }.single()
                assertEquals(review.filename, transfer.filename)
                assertEquals(review.filename, transfer.displayFilename)
                assertEquals(review.sizeBytes, transfer.sizeBytes)
                assertEquals(review.peerNick, transfer.peerNick)
                assertEquals("The IRC network is not ready", transfer.error)
                assertEquals(0, transfer.port)
                assertFalse(fixture.transport.sent.any { "\u0001DCC " in it })
                withTimeout(5_000) {
                    while (fixture.stagedFiles().isNotEmpty()) {
                        delay(1)
                    }
                }
                assertTrue(fixture.stagedFiles().isEmpty())
            }
        }

    @Test
    fun confirmationRejectsChangedPeerAndRedirectWithoutRetargeting() =
        runBlocking {
            withController { fixture ->
                fixture.prefs.setConfig(PasteBackendConfig(stripMetadata = false))
                val original = temporary.newFile("test.txt").apply { writeText("untouched") }
                val dao = fixture.db.bufferDao()
                val query = checkNotNull(dao.rawById(fixture.bufferId))
                val review = fixture.controller.prepareSend(query.id, original.toUri())
                dao.update(query.copy(name = "someone", displayName = "Someone"))
                expectUnavailable("The recipient or network changed. Choose the file again.") { fixture.controller.sendPrepared(review) }
                assertTrue(fixture.stagedFiles().isEmpty())
                dao.update(query)
                val redirected = dao.insert(query.copy(id = 0, name = "newpeer", displayName = "NewPeer"))
                val reviewBeforeMerge = fixture.controller.prepareSend(query.id, original.toUri())
                dao.update(query.copy(redirectToRoomId = redirected))
                expectUnavailable("Direct file sending requires an open private chat") { fixture.controller.sendPrepared(reviewBeforeMerge) }
                expectUnavailable("Direct file sending requires an open private chat") {
                    fixture.controller.prepareSend(query.id, original.toUri()).close()
                }
                fixture.assertNoOffer()
                assertTrue(fixture.stagedFiles().isEmpty())
            }
        }

    @Test
    fun boundNetworkShowsChildNameButRechecksParentProxyBeforeSending() =
        runBlocking {
            withController(bound = true) { fixture ->
                fixture.prefs.setConfig(PasteBackendConfig(stripMetadata = false))
                val original = temporary.newFile("test.txt").apply { writeText("untouched") }
                val review = fixture.controller.prepareSend(fixture.bufferId, original.toUri())
                assertEquals("Upstream network", review.networkName)
                val parent = checkNotNull(fixture.root)
                fixture.db.networkDao().update(parent.copy(host = "new-bouncer.example"))
                expectUnavailable("The IRC network is not ready") { fixture.controller.sendPrepared(review) }
                fixture.db.networkDao().update(parent)
                val reviewBeforeProxy = fixture.controller.prepareSend(fixture.bufferId, original.toUri())
                fixture.db.networkDao().update(parent.copy(obfsMode = ObfsMode.TOR))
                expectUnavailable("Direct file sending is unavailable while this network uses a proxy") {
                    fixture.controller.sendPrepared(reviewBeforeProxy)
                }
                fixture.assertNoOffer()
                assertTrue(fixture.stagedFiles().isEmpty())
            }
        }

    @Test
    fun rejectsNonlocalUrisAndCleansWhenCancelledDuringPreparation() =
        runBlocking {
            withController { fixture ->
                expectUnavailable("Direct file sending requires a local file") {
                    fixture.controller.prepareSend(fixture.bufferId, Uri.parse("https://invalid.test/private.png")).close()
                }
                fixture.prefs.setConfig(PasteBackendConfig(stripMetadata = false))
                val original = temporary.newFile("test.txt").apply { writeText("untouched") }
                val checks = AtomicInteger()
                val preparing =
                    launch {
                        val caller = coroutineContext.job
                        fixture.connections.onReadyRouteCheck = {
                            if (checks.incrementAndGet() == 2) caller.cancel()
                        }
                        fixture.controller.prepareSend(fixture.bufferId, original.toUri()).close()
                    }
                preparing.join()
                assertTrue(preparing.isCancelled)
                assertTrue(fixture.stagedFiles().isEmpty())
                fixture.assertNoOffer()
            }
        }

    @Test
    fun enabledPolicyRequiresOriginalConsentAndStillRejectsMalformedMedia() =
        runBlocking {
            withController { fixture ->
                fixture.prefs.setConfig(PasteBackendConfig(stripMetadata = true))
                val unsupported = temporary.newFile("unsupported.pdf").apply { writeText("%PDF-1.7 /Author(test-author)") }
                val controller = fixture.controller
                val bufferId = fixture.bufferId
                val unsupportedFailure = runCatching { controller.prepareSend(bufferId, unsupported.toUri()).close() }.exceptionOrNull()
                assertTrue(unsupportedFailure is MetadataNotSupportedException)
                assertTrue(fixture.stagedFiles().isEmpty())
                controller.prepareSend(bufferId, unsupported.toUri(), allowOriginalMetadata = true).use { review ->
                    assertEquals(unsupported.name, review.filename)
                    assertEquals(unsupported.length(), review.sizeBytes)
                    assertArrayEquals(unsupported.readBytes(), fixture.stagedFiles().single().readBytes())
                    fixture.assertNoOffer()
                }
                val malformed =
                    temporary.newFile("broken.png").apply {
                        writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                    }
                val malformedFailure =
                    runCatching {
                        controller.prepareSend(bufferId, malformed.toUri(), allowOriginalMetadata = true).close()
                    }.exceptionOrNull()
                assertTrue(malformedFailure is IOException)
                fixture.assertNoOffer()
                assertTrue(fixture.stagedFiles().isEmpty())
            }
        }

    private fun metadataPng(): File {
        val file = temporary.newFile("original.txt")
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
        val encoded = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val kind = "tEXt".toByteArray()
        val text = "Author\u0000test-author".toByteArray()
        val crc =
            CRC32().apply {
                update(kind)
                update(text)
            }
        DataOutputStream(file.outputStream()).use { stream ->
            stream.write(encoded, 0, encoded.size - 12)
            stream.writeInt(text.size)
            stream.write(kind)
            stream.write(text)
            stream.writeInt(crc.value.toInt())
            stream.write(encoded, encoded.size - 12, 12)
        }
        return file
    }

    private suspend fun expectUnavailable(
        message: String,
        action: suspend () -> Unit,
    ) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue(failure is DccSendUnavailableException)
        assertEquals(message, failure?.message)
    }

    private suspend fun withController(
        bound: Boolean = false,
        body: suspend (Fixture) -> Unit,
    ) {
        val cache = temporary.newFolder()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getCacheDir(): File = cache
            }
        val db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val irc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = AttachmentPrefsImpl(context)
        val previous = prefs.config.first()
        try {
            val direct = NetworkEntity(name = "Direct network", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me")
            val root =
                if (bound) {
                    val bouncer = direct.copy(name = "Bouncer", role = NetworkRole.BOUNCER_ROOT)
                    bouncer.copy(id = db.networkDao().insert(bouncer))
                } else {
                    null
                }
            val row =
                if (root != null) {
                    direct.copy(name = "Upstream network", role = NetworkRole.BOUNCER_CHILD, parentId = root.id, bouncerNetId = "1")
                } else {
                    direct
                }
            val network = row.copy(id = db.networkDao().insert(row))
            val bufferId = db.bufferDao().insert(BufferEntity(networkId = network.id, name = "peer", displayName = "Peer", type = BufferType.QUERY))
            val socks = LocalSocksProvider.forTest { error("DCC fixture must not open proxy sockets") }
            val transport = RecordingTransport()
            val client = IrcClient(buildChildConfig(network, root), TransportFactory { _, _, _, _, _ -> transport }, irc)
            client.start()
            transport.register()
            withTimeout(5_000) {
                client.state.first { it is IrcClientState.Ready }
                client.targetClassificationReady.first { it }
            }
            val connections = ReadyConnection(network.id, networkFingerprint(network, root), client)
            val controller = DccTransferControllerImpl(context, db, connections, socks, transfers, UploadMetadataSanitizer(context), prefs)
            body(Fixture(controller, prefs, db, bufferId, cache, root, connections, transport))
        } finally {
            transfers.coroutineContext.job.cancelAndJoin()
            irc.coroutineContext.job.cancelAndJoin()
            prefs.setConfig(previous)
            db.close()
        }
    }

    private class Fixture(
        val controller: DccTransferControllerImpl,
        val prefs: AttachmentPrefsImpl,
        val db: MotdDatabase,
        val bufferId: Long,
        val cache: File,
        val root: NetworkEntity?,
        val connections: ReadyConnection,
        val transport: RecordingTransport,
    ) {
        fun stagedFiles(): List<File> = cache.listFiles().orEmpty().filter { it.name.startsWith("upload-") }

        suspend fun assertNoOffer() {
            assertTrue(controller.observeAll().first().isEmpty())
            assertFalse(transport.sent.any { "\u0001DCC " in it })
        }
    }

    private class ReadyConnection(
        val networkId: Long,
        val fingerprint: String,
        val client: IrcClient,
    ) : NoopConnectionManager() {
        val remainingReadyLookups = AtomicInteger(Int.MAX_VALUE)
        var onReadyRouteCheck: () -> Unit = {}

        override fun readyClientForRoute(
            networkId: Long,
            expectedFingerprint: String,
        ): IrcClient? {
            onReadyRouteCheck()
            return client.takeIf {
                networkId == this.networkId && expectedFingerprint == fingerprint && it.state.value is IrcClientState.Ready &&
                    remainingReadyLookups.getAndUpdate { remaining -> (remaining - 1).coerceAtLeast(0) } > 0
            }
        }
    }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = ConcurrentLinkedQueue<String>()
        override val incoming = inbound.consumeAsFlow()

        override suspend fun connect() = Unit

        override suspend fun send(line: String) {
            sent += line
        }

        override suspend fun close() {
            inbound.close()
        }

        suspend fun register() {
            val caps = "batch message-tags server-time"
            inbound.send(":srv CAP * LS :$caps")
            inbound.send(":srv CAP me ACK :$caps")
            inbound.send(":srv 005 me CHANTYPES=# CASEMAPPING=rfc1459 :supported")
            inbound.send(":srv 001 me :Welcome")
        }
    }
}
