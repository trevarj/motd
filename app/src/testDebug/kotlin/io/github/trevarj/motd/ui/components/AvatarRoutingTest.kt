package io.github.trevarj.motd.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.audio.NetworkMediaHttpTest
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.avatar.AvatarRecord
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.PinningTrustManager
import io.github.trevarj.motd.ui.chatlist.DrawerRow
import io.github.trevarj.motd.ui.chatlist.ServerDrawerContent
import io.github.trevarj.motd.ui.theme.MotdTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarRoutingTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun automaticLoadingUsesSourceAvatarAndDrawerIconOwnersThroughRealSocksTls() {
        Fixture().use { fixture ->
            val automatic = mutableStateOf(false)
            val avatar = fixture.avatar()
            fixture.server.enqueue(fixture.imageResponse())
            fixture.server.enqueue(fixture.imageResponse())
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.IRC_SPRITE) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides automatic.value,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(avatar)),
                    ) {
                        Column {
                            // Global views retain the record's owner rather than borrowing the icon's network.
                            Avatar("alice", modifier = Modifier.testTag("avatar"))
                            Drawer(fixture.url("icon"), 8)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.size == 2 } }
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
            fixture.errors.clear()

            compose.runOnIdle { automatic.value = true }
            // Coil completes on Android's main looper; pump it while awaiting background HTTP/decode.
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.size == 2 || fixture.errors.isNotEmpty() } }
            assertEquals(emptyList<String>(), fixture.errors.toList())
            compose.onNodeWithTag("avatar").assertIsDisplayed()
            compose.onNodeWithTag("drawer_network_icon_8", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(setOf(7L, 8L), fixture.selectedNetworks.toSet())
            assertEquals(listOf("media-test.invalid"), fixture.avatarProxy.destinations.toList())
            assertEquals(listOf("media-test.invalid"), fixture.iconProxy.destinations.toList())
            val requests = List(2) { fixture.server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertEquals(setOf("/avatar.png", "/icon.png"), requests.map { it.path }.toSet())
            requests.forEach { assertNull(it.getHeader("Authorization")) }
        }
    }

    @Test
    fun missingBrokenAndAmbiguousOwnersNeverFetchRemoteAvatarsOrIcons() {
        Fixture().use { fixture ->
            val avatar = fixture.avatar()
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.IRC_SPRITE) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides true,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(avatar, avatar.copy(networkId = 8))),
                    ) {
                        Column {
                            Avatar("alice") // Same URL across networks is not a unique owner.
                            Avatar("ownerless", conversationModel = fixture.url("ownerless"))
                            Avatar("missing", networkId = 404, conversationModel = fixture.url("missing"))
                            Drawer(fixture.url("broken"), 9)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.size == 3 } }
            assertEquals(setOf(404L, 9L), fixture.selectedNetworks.toSet())
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<String>(), fixture.avatarProxy.destinations.toList())
            assertEquals(emptyList<String>(), fixture.iconProxy.destinations.toList())
            assertEquals(emptyList<String>(), fixture.loaded.toList())
        }
    }

    @Test
    fun importedLocalAvatarOverridesSharedImageWithoutNetworkOrAutomaticLoading() {
        Fixture().use { fixture ->
            val local = Uri.fromFile(temporaryFolder.newFile("avatar.png").apply { writeBytes(PNG) }).toString()
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides false,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(fixture.avatar())),
                    ) {
                        Avatar("alice", conversationModel = local, modifier = Modifier.testTag("local_avatar"))
                    }
                }
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(local) } }
            compose.onNodeWithTag("local_avatar").assertIsDisplayed()
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
        }
    }

    @androidx.compose.runtime.Composable
    private fun Drawer(
        iconUrl: String,
        networkId: Long,
    ) {
        ServerDrawerContent(
            drawerRows =
                listOf(
                    DrawerRow(
                        networkId = networkId,
                        name = "Network",
                        role = NetworkRole.DIRECT,
                        depth = 0,
                        state = IrcClientState.Disconnected,
                        nick = null,
                        unread = 0,
                        mentions = 0,
                        iconUrl = iconUrl,
                    ),
                ),
            selectedNetworkId = networkId,
            allUnread = 0,
            allMentions = 0,
            scopedUnreadCount = 0,
            allOffline = false,
            onSelectNetwork = {},
            onConnect = {},
            onDisconnect = {},
            onServerMessages = {},
            onOpenNetworkSettings = {},
            onAddNetwork = {},
            onToggleOffline = {},
            onOpenSettings = {},
            onMarkAllRead = {},
        )
    }

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        private val certificate =
            KeyStore.getInstance("PKCS12").let { store ->
                val password = "media-test".toCharArray()
                AvatarRoutingTest::class.java.getResourceAsStream("/network-media.p12")!!.use { store.load(it, password) }
                val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
                server.useHttps(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.socketFactory, false)
                server.start()
                store.getCertificate("media") as X509Certificate
            }
        val avatarProxy = NetworkMediaHttpTest.SocksProxy(server.port)
        val iconProxy = NetworkMediaHttpTest.SocksProxy(server.port)
        val selectedNetworks = ConcurrentLinkedQueue<Long>()
        val loaded = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<String>()
        private val http =
            NetworkMediaHttp({ id ->
                selectedNetworks += id
                if (id !in 7L..9L) {
                    null
                } else {
                    NetworkMediaRoute(
                        networkId = id,
                        endpoint = NetworkEntity(name = "test", host = "media-test.invalid", port = 6697, role = NetworkRole.DIRECT, nick = "nick", username = "user", realname = "real"),
                        proxy = if (id == 7L) avatarProxy.proxy else iconProxy.proxy,
                        proxyError = if (id == 9L) "Broken route" else null,
                        authorizationHeader = "Basic must-not-leak",
                        endpointPinnedSha256 = PinningTrustManager.sha256Hex(certificate),
                    )
                }
            })
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val previousImageLoader = Coil.imageLoader(context)
        private val imageLoader =
            ImageLoader
                .Builder(context)
                .okHttpClient(http.client)
                .memoryCache(null)
                .diskCache(null)
                .eventListener(
                    object : EventListener {
                        override fun onSuccess(
                            request: ImageRequest,
                            result: SuccessResult,
                        ) {
                            loaded += request.data.toString()
                        }

                        override fun onError(
                            request: ImageRequest,
                            result: ErrorResult,
                        ) {
                            errors += "${request.data}: ${result.throwable}"
                        }
                    },
                ).build()

        init {
            Coil.setImageLoader(imageLoader)
        }

        fun url(name: String) = "https://media-test.invalid:${server.port}/$name.png"

        fun avatar() = AvatarRecord(7, "nick:alice", "alice", null, url("avatar"), 1)

        fun imageResponse() = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(PNG))

        override fun close() {
            Coil.setImageLoader(previousImageLoader)
            imageLoader.shutdown()
            avatarProxy.close()
            iconProxy.close()
            server.shutdown()
        }
    }

    private companion object {
        val PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}
