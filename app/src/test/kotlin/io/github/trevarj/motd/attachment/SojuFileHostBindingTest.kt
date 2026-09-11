package io.github.trevarj.motd.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A soju file-host upload authenticates with the network's SASL credential, so the advertised
 * `soju.im/FILEHOST` endpoint is bound to the host that credential belongs to before any request
 * is opened. Without that binding a server can name any third-party host and have the client
 * forward its Authorization header there — credential forwarding, with no pin and no consent.
 * A VLESS ingress explicitly configured for that network is also an authority, but no other host is.
 *
 * The probe socket below stands in for the advertised host so a leaked request is observable:
 * every connection attempt is counted, then dropped.
 */
@RunWith(RobolectricTestRunner::class)
class SojuFileHostBindingTest {
    private val probe = ConnectionProbe()
    private var released = false

    @After fun tearDown() = probe.close()

    @Test fun offHostFileHostIsRefusedBeforeAnythingIsOpened() {
        val error =
            assertThrows(UploadException::class.java) {
                uploadText(advertised = "https://127.0.0.1:${probe.port}/uploads", networkHost = "irc.example")
            }
        // The refusal names the advertised host, so a misconfigured bouncer reads differently from
        // a hostile one.
        assertTrue(error.message, error.message.orEmpty().contains("127.0.0.1"))
        assertTrue(error.message, error.message.orEmpty().contains("irc.example"))
        // The OPTIONS probe carries the credential too and runs before the POST, so the binding has
        // to precede both: not one byte may reach the advertised host.
        assertEquals(0, probe.connections())
        assertTrue("the media route was not released", released)
    }

    @Test fun advertisedHostIsComparedCaseInsensitivelyAndIgnoringPort() {
        // soju serves IRC and the file host from one process on two ports, so the file host is
        // regularly on a different port than the IRC endpoint. That must still upload.
        val error =
            assertThrows(IOException::class.java) {
                uploadText(advertised = "https://127.0.0.1:${probe.port}/uploads", networkHost = "127.0.0.1")
            }
        // The probe speaks no TLS, so the request dies in the handshake — after it was made.
        assertFalse(error.message, error.message.orEmpty().contains("Refusing"))
        assertTrue("the upload never reached the advertised host", probe.connections() > 0)
    }

    @Test fun vlessIngressHostMayServeTheFileHost() {
        assertThrows(IOException::class.java) {
            uploadText(
                advertised = "https://127.0.0.1:${probe.port}/uploads",
                networkHost = "soju",
                vlessHost = "127.0.0.1",
            )
        }
        assertTrue("the upload never reached the configured VLESS host", probe.connections() > 0)
    }

    @Test fun embeddedDirectNetworkMayUseItsAdvertisedFileHost() {
        assertThrows(IOException::class.java) {
            uploadText(
                advertised = "https://127.0.0.1:${probe.port}/uploads",
                networkHost = "soju",
                vlessHost = "192.0.2.1",
            )
        }
        assertTrue("the upload never reached the embedded peer's advertised file host", probe.connections() > 0)
    }

    @Test fun embeddedBouncerMayUseItsExternalFileHost() {
        assertThrows(IOException::class.java) {
            uploadText(
                advertised = "https://127.0.0.1:${probe.port}/uploads",
                networkHost = "soju",
                vlessHost = "192.0.2.1",
                role = NetworkRole.BOUNCER_ROOT,
            )
        }
        assertTrue("the upload never reached the bouncer's advertised file host", probe.connections() > 0)
    }

    @Test fun networkWithoutAFileHostIsRefusedWithoutNamingAHost() {
        val error =
            assertThrows(UploadException::class.java) {
                uploadText(advertised = null, networkHost = "irc.example")
            }
        assertEquals("This IRC network is not advertising a Soju file host.", error.message)
        assertEquals(0, probe.connections())
    }

    private fun uploadText(
        advertised: String?,
        networkHost: String,
        vlessHost: String? = null,
        role: NetworkRole = NetworkRole.DIRECT,
    ) = runBlocking {
        val isupport = advertised?.let { mapOf(SOJU_FILEHOST_TOKEN to it) }.orEmpty()
        val uploader =
            AttachmentUploaderImpl(
                ApplicationProvider.getApplicationContext<Context>(),
                FakeConnectionManager(IrcClientState.Ready("me", emptySet(), isupport)),
                MediaRouteResolver { id -> route(id, networkHost, vlessHost, role) },
            )
        uploader
            .upload(
                AttachmentSource.Text("hello"),
                PasteBackendConfig(backend = AttachmentBackend.SOJU_FILEHOST),
                AttachmentUploadContext(NETWORK_ID),
            ).collect { }
    }

    private fun route(
        networkId: Long,
        host: String,
        vlessHost: String?,
        role: NetworkRole,
    ) = NetworkMediaRoute(
        networkId = networkId,
        endpoint =
            NetworkEntity(
                id = networkId,
                name = "net",
                role = role,
                host = host,
                port = 6697,
                nick = "me",
                username = "me",
                realname = "me",
                saslMechanism = "PLAIN",
                saslUser = "me",
                saslPassword = "hunter2",
                obfsMode = vlessHost?.let { ObfsMode.EMBEDDED_REALITY },
                obfsLink =
                    vlessHost?.let {
                        "vless://123e4567-e89b-12d3-a456-426614174000@$it:443?" +
                            "type=tcp&security=reality&sni=example.com&pbk=key&sid=id"
                    },
            ),
        proxy = null,
        proxyError = null,
        authorizationHeader = "Basic bWU6aHVudGVyMg==",
        release = { released = true },
    )

    /** Counts every inbound connection, then drops it so a stalled handshake cannot hang the test. */
    private class ConnectionProbe : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val count = AtomicInteger()
        private val accepting =
            Thread {
                while (true) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    count.incrementAndGet()
                    runCatching { client.close() }
                }
            }.apply {
                isDaemon = true
                start()
            }

        val port: Int get() = socket.localPort

        fun connections(): Int = count.get()

        override fun close() {
            runCatching { socket.close() }
            accepting.join(1_000)
        }
    }

    private class FakeConnectionManager(
        state: IrcClientState,
    ) : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow(mapOf(NETWORK_ID to state))

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 0

        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
    }

    private companion object {
        const val NETWORK_ID = 7L
    }
}
