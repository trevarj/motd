package io.github.trevarj.motd.audio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.service.LocalSocksEngine
import io.github.trevarj.motd.service.LocalSocksProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * URL-only avatars/icons have no per-network route tag, so direct fetching is denied on obfuscated
 * transports unless the user opts in. Unknown networks or physical bouncer endpoints always fail
 * closed, including routed media and preview requests.
 */
@RunWith(RobolectricTestRunner::class)
class DirectMediaPolicyTest {
    private lateinit var db: MotdDatabase
    private lateinit var prefs: FakeContentPreviewPrefs
    private lateinit var provider: NetworkMediaRouteProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        prefs = FakeContentPreviewPrefs()
        provider =
            NetworkMediaRouteProvider(
                db,
                LocalSocksProvider.forTest { FailingEngine },
                NoPinsTrustStore,
                prefs,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun direct_networks_allow_global_media_fetches() =
        runTest {
            assertTrue(provider.directMediaAllowed(insert(obfsMode = null)))
            assertTrue(provider.directMediaAllowed(insert(obfsMode = ObfsMode.NONE)))
        }

    @Test
    fun every_obfuscated_transport_denies_global_media_fetches() =
        runTest {
            assertFalse(provider.directMediaAllowed(insert(obfsMode = ObfsMode.SOCKS5)))
            assertFalse(provider.directMediaAllowed(insert(obfsMode = ObfsMode.TOR)))
            assertFalse(provider.directMediaAllowed(insert(obfsMode = ObfsMode.EMBEDDED_REALITY)))
        }

    @Test
    fun opt_in_allows_direct_media_on_obfuscated_transports() =
        runTest {
            prefs.state.value = ContentPreviewConfig(directMediaOnProxiedNetworks = true)
            assertTrue(provider.directMediaAllowed(insert(obfsMode = ObfsMode.SOCKS5)))
            assertTrue(provider.directMediaAllowed(insert(obfsMode = ObfsMode.TOR)))
            assertTrue(provider.directMediaAllowed(insert(obfsMode = ObfsMode.EMBEDDED_REALITY)))
        }

    @Test
    fun opt_in_lifts_the_denial_for_an_obfuscated_bouncer_child() =
        runTest {
            val realityParent = insert(obfsMode = ObfsMode.EMBEDDED_REALITY)
            val child = insert(obfsMode = null, role = NetworkRole.BOUNCER_CHILD, parentId = realityParent)
            assertFalse(provider.directMediaAllowed(child))

            prefs.state.value = ContentPreviewConfig(directMediaOnProxiedNetworks = true)
            assertTrue(provider.directMediaAllowed(child))
        }

    @Test
    fun previewRouteUsesDirectConnectionOnlyAfterOptIn() =
        runTest {
            val reality = insert(obfsMode = ObfsMode.EMBEDDED_REALITY)

            provider.routeForPreview(reality).use { routed ->
                assertNotNull(routed)
                assertNotNull(routed?.proxyError)
            }

            prefs.state.value = ContentPreviewConfig(directMediaOnProxiedNetworks = true)
            provider.routeForPreview(reality).use { direct ->
                assertNotNull(direct)
                assertNull(direct?.proxy)
                assertNull(direct?.proxyError)
            }

            // Uploads and other network-bound operations keep the owning transport despite preview opt-in.
            provider.routeForNetwork(reality).use { routed ->
                assertNotNull(routed)
                assertNotNull(routed?.proxyError)
            }
        }

    @Test
    fun optedInBouncerChildPreviewUsesDirectConnection() =
        runTest {
            val realityParent = insert(obfsMode = ObfsMode.EMBEDDED_REALITY)
            val child = insert(obfsMode = null, role = NetworkRole.BOUNCER_CHILD, parentId = realityParent)
            prefs.state.value = ContentPreviewConfig(directMediaOnProxiedNetworks = true)

            provider.routeForPreview(child).use { route ->
                assertNotNull(route)
                assertNull(route?.proxy)
                assertNull(route?.proxyError)
            }
        }

    @Test
    fun bouncer_children_inherit_the_parent_endpoint_transport() =
        runTest {
            val torParent = insert(obfsMode = ObfsMode.TOR)
            val child =
                insert(
                    obfsMode = null,
                    role = NetworkRole.BOUNCER_CHILD,
                    parentId = torParent,
                )
            assertFalse(provider.directMediaAllowed(child))

            val directParent = insert(obfsMode = null)
            val directChild =
                insert(
                    obfsMode = null,
                    role = NetworkRole.BOUNCER_CHILD,
                    parentId = directParent,
                )
            assertTrue(provider.directMediaAllowed(directChild))
        }

    @Test
    fun unknown_networks_fail_closed() =
        runTest {
            assertFalse(provider.directMediaAllowed(9_999L))
        }

    @Test
    fun unknown_networks_fail_closed_even_with_opt_in() =
        runTest {
            prefs.state.value = ContentPreviewConfig(directMediaOnProxiedNetworks = true)
            assertFalse(provider.directMediaAllowed(9_999L))
        }

    @Test
    fun bouncer_children_without_a_physical_endpoint_fail_closed_even_with_opt_in() =
        runTest {
            val parent = insert(obfsMode = ObfsMode.TOR, role = NetworkRole.BOUNCER_ROOT)
            val orphan = insert(obfsMode = null, role = NetworkRole.BOUNCER_CHILD, parentId = parent)
            val withoutParentId = insert(obfsMode = null, role = NetworkRole.BOUNCER_CHILD)
            db.networkDao().deleteNetworkRows(listOf(parent))

            for (optIn in listOf(false, true)) {
                prefs.state.value = prefs.state.value.copy(directMediaOnProxiedNetworks = optIn)
                for (child in listOf(orphan, withoutParentId)) {
                    assertNull(provider.routeForNetwork(child))
                    assertNull(provider.routeForPreview(child))
                    assertFalse(provider.directMediaAllowed(child))
                }
            }
        }

    private suspend fun insert(
        obfsMode: ObfsMode?,
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
    ): Long =
        db.networkDao().insert(
            NetworkEntity(
                name = "net",
                role = role,
                parentId = parentId,
                host = "irc.example.test",
                port = 6697,
                nick = "nick",
                username = "user",
                realname = "real",
                obfsMode = obfsMode,
                proxyHost = if (obfsMode == ObfsMode.SOCKS5) "127.0.0.1" else null,
                proxyPort = if (obfsMode == ObfsMode.SOCKS5) 1080 else null,
            ),
        )

    private class FakeContentPreviewPrefs : ContentPreviewPrefs {
        val state = MutableStateFlow(ContentPreviewConfig())
        override val config: Flow<ContentPreviewConfig> = state

        override suspend fun setShowImages(show: Boolean) {
            state.value = state.value.copy(showImages = show)
        }

        override suspend fun setShowLinkPreviews(show: Boolean) {
            state.value = state.value.copy(showLinkPreviews = show)
        }

        override suspend fun setAutoLoadOnUnmetered(enabled: Boolean) = Unit

        override suspend fun setAutoLoadOnMetered(enabled: Boolean) = Unit

        override suspend fun setDirectMediaOnProxiedNetworks(enabled: Boolean) {
            state.value = state.value.copy(directMediaOnProxiedNetworks = enabled)
        }
    }

    private object FailingEngine : LocalSocksEngine {
        override fun start(configJson: String): Result<Int> = Result.failure(IllegalStateException("no embedded core in unit tests"))

        override fun stop() = Unit
    }

    private object NoPinsTrustStore : CertTrustStore {
        override suspend fun pinnedFor(
            host: String,
            port: Int,
        ): String? = null

        override suspend fun isPinned(
            host: String,
            port: Int,
            sha256: String,
        ): Boolean = false

        override suspend fun pin(
            host: String,
            port: Int,
            sha256: String,
        ) = Unit

        override suspend fun unpin(
            host: String,
            port: Int,
        ) = Unit
    }
}
