package io.github.trevarj.motd.sidecar

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidecarProviderDeviceTest {
    @Test
    fun discoversPairsAndRunsIrcThroughSeparateProviderProcess() =
        runBlocking(Dispatchers.Default) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val discovery = SidecarDiscovery(context)
            val descriptor =
                withTimeout(5_000) {
                    var found: SidecarProviderDescriptor? = null
                    while (found == null) {
                        found = discovery.providers().firstOrNull { it.label == "motd E2E Companion" }
                        if (found == null) kotlinx.coroutines.delay(50)
                    }
                    requireNotNull(found)
                }
            val trust = SidecarTrustStoreImpl(context)
            trust.revoke(descriptor.component)
            val binder = SidecarBinder(context, discovery, trust)

            val pairIntent =
                withTimeout(10_000) { binder.bindForPairing(descriptor.component) }.use {
                    it.provider.createUiIntent(SidecarContract.ACTION_PAIR, "", "{}")
                }
            context.startActivity(pairIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            kotlinx.coroutines.delay(250)
            trust.trust(descriptor.component, descriptor.currentSignerSha256)

            val account =
                withTimeout(10_000) { binder.bindTrusted(descriptor.component) }.use {
                    SidecarJson.decodeAccount(it.provider.accountsJson.single())
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client =
                IrcClient(
                    IrcClientConfig(
                        host = "sidecar",
                        port = 0,
                        tls = false,
                        nick = "motd",
                        username = "motd",
                        realname = "motd",
                        extraCaps = setOf(SidecarContract.IRC_CAPABILITY),
                    ),
                    SidecarTransportFactory(binder, descriptor.component, account.accountId),
                    scope,
                )
            try {
                client.start()
                withTimeout(5_000) { client.state.first { it is IrcClientState.Ready } }
                val echo =
                    async {
                        withTimeout(5_000) {
                            client.broadcastEvents.filterIsInstance<IrcEvent.ChatMessage>().first()
                        }
                    }
                client.sendMessage("alice", "hello through sidecar", null, "device-sidecar")
                assertEquals("hello through sidecar", echo.await().text)
            } finally {
                client.stop()
                scope.cancel()
            }
        }
}
