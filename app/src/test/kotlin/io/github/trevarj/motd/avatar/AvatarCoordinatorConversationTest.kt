package io.github.trevarj.motd.avatar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.dickord.DICKORD_AVATAR_TAG
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarCoordinatorConversationTest {
    private lateinit var db: MotdDatabase
    private lateinit var local: LocalAvatarStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        local = LocalAvatarStore(context)
    }

    @After fun tearDown() = db.close()

    @Test fun query_changes_are_local_only_and_reset_does_not_clear_channel_metadata() =
        runTest {
            val networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "test",
                        role = NetworkRole.DIRECT,
                        host = "irc.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            val queryId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "alice", displayName = "Alice", type = BufferType.QUERY),
                )
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#chat", displayName = "#chat", type = BufferType.CHANNEL),
                )
            val coordinator = coordinator(this, FakeStore())

            assertEquals(
                ConversationAvatarOutcome.LocalOnly,
                coordinator.setConversationAvatar(queryId, "https://example.com/query.png"),
            )
            assertEquals("https://example.com/query.png", db.bufferDao().observeById(queryId)?.avatarOverrideModel)
            assertEquals(ConversationAvatarOutcome.LocalReset, coordinator.resetConversationAvatar(queryId))
            assertNull(db.bufferDao().observeById(queryId)?.avatarOverrideModel)

            assertEquals(
                ConversationAvatarOutcome.LocalOnly,
                coordinator.setConversationAvatar(channelId, "https://example.com/channel.png"),
            )
            assertEquals(
                ConversationAvatarOutcome.LocalOnly,
                coordinator.clearSharedConversationAvatar(channelId),
            )
            assertEquals("https://example.com/channel.png", db.bufferDao().observeById(channelId)?.avatarOverrideModel)
            assertEquals(
                ConversationAvatarOutcome.Invalid,
                coordinator.setConversationAvatar(queryId, "http://example.com/no.png"),
            )
            assertNull(db.bufferDao().observeById(queryId)?.avatarOverrideModel)
        }

    @Test fun dickord_avatar_tags_are_ingested_from_live_and_batch_events() =
        runTest {
            val store = FakeStore()
            val coordinator = coordinator(this, store)
            val urls =
                (1..5).map {
                    "https://cdn.discordapp.com/avatars/$it/hash.png?size=256"
                }

            coordinator.onEvent(7, dickordMessage(urls[0]))
            coordinator.onEvent(
                7,
                IrcEvent.HistoryBatch("#discord.guild.general", listOf(dickordMessage(urls[1]))),
            )
            coordinator.onEvent(
                7,
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.CHATHISTORY,
                    target = "#discord.guild.general",
                    items = listOf(IrcEvent.PlaybackItem.from(dickordMessage(urls[2]), 0)),
                ),
            )
            coordinator.onEvent(
                7,
                IrcEvent.ReplayBatch("#discord.guild.general", listOf(dickordMessage(urls[3]))),
            )
            coordinator.ingestDickordAvatars(7, listOf(dickordMessage(urls[4])))

            assertEquals(
                urls.map { StoredAvatar(7, "Alice/discord", account = null, url = it) },
                store.upserts,
            )
        }

    @Test fun dickord_avatar_tags_remove_on_empty_and_ignore_untrusted_or_near_miss_messages() =
        runTest {
            val prefs = FakePrefs()
            val store = FakeStore()
            val coordinator = coordinator(this, store, prefs)

            coordinator.onEvent(9, dickordMessage(""))
            coordinator.onEvent(9, dickordMessage("http://cdn.discordapp.com/avatar.png"))
            coordinator.onEvent(9, dickordMessage("not a URL"))
            coordinator.onEvent(9, dickordMessage("https://cdn.discordapp.com/avatar.png", target = "#discord"))
            coordinator.onEvent(9, dickordMessage("https://cdn.discordapp.com/avatar.png", target = "#discordish.chat"))
            coordinator.onEvent(9, dickordMessage("https://cdn.discordapp.com/avatar.png", source = "Alice/Discord"))
            coordinator.onEvent(9, dickordMessage("https://cdn.discordapp.com/avatar.png", source = "Alice/discord2"))
            coordinator.onEvent(9, dickordMessage(null))
            prefs.config.value = AvatarConfig(showSharedAvatars = false)
            coordinator.onEvent(9, dickordMessage("https://cdn.discordapp.com/avatar.png"))

            assertEquals(listOf(RemovedAvatar(9, "Alice/discord", account = null)), store.removals)
            assertEquals(emptyList<StoredAvatar>(), store.upserts)
        }

    @Test fun self_avatar_saved_without_metadata_is_replayed_only_on_its_capable_network() =
        runTest {
            val prefs = FakePrefs()
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, prefs, connections)
            val networkId = 1L
            val otherNetworkId = 2L
            val url = "https://example.com/avatar.png"
            val unsupported = FakeTransport()
            val client = readyClient(unsupported, caps = "batch")
            connections.clients[networkId] = client

            assertFalse(coordinator.publishingAvailable(networkId))
            assertTrue(coordinator.setSelfAvatar(networkId, " $url "))
            assertFalse(coordinator.setSelfAvatar(networkId, "http://example.com/invalid.png"))
            assertEquals(SelfAvatarSetting.Set(url), prefs.selfSetting(networkId).first())
            assertEquals(SelfAvatarSetting.Unmanaged, prefs.selfSetting(otherNetworkId).first())
            assertTrue(unsupported.avatarWrites.isEmpty())
            assertTrue(store.upserts.isEmpty())
            client.stop()

            val other = FakeTransport()
            val otherClient = readyClient(other, caps = "batch $METADATA_CAP")
            connections.clients[otherNetworkId] = otherClient
            coordinator.onReady(otherNetworkId, otherClient)
            assertTrue(other.avatarWrites.isEmpty())

            val reconnected = FakeTransport()
            val replacement = readyClient(reconnected, caps = "batch $METADATA_CAP=max-keys=1,max-value-bytes=128")
            connections.clients[networkId] = replacement
            assertTrue(coordinator.publishingAvailable(networkId))
            coordinator.onReady(networkId, replacement)
            assertEquals(listOf("METADATA * SET avatar $url"), reconnected.avatarWrites)
            assertTrue(store.upserts.isEmpty())

            assertTrue(coordinator.setSelfAvatar(networkId, url))
            assertEquals(listOf(StoredAvatar(networkId, "me", null, url)), store.upserts)
            assertTrue(coordinator.setSelfAvatar(networkId, null))
            assertEquals(SelfAvatarSetting.ExplicitlyCleared, prefs.selfSetting(networkId).first())
            assertEquals("METADATA * SET avatar", reconnected.avatarWrites.last())
            assertEquals(listOf(RemovedAvatar(networkId, "me", null)), store.removals)
            coordinator.stopManagingSelfAvatar(networkId)
            assertEquals(SelfAvatarSetting.Unmanaged, prefs.selfSetting(networkId).first())
            val writes = reconnected.avatarWrites
            coordinator.onReady(networkId, replacement)
            assertEquals(writes, reconnected.avatarWrites)
        }

    @Test fun self_avatar_write_limits_and_optional_failures_do_not_undo_local_saves() =
        runTest {
            val prefs = FakePrefs()
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, prefs, connections)
            val networkId = 1L
            val url = "https://example.com/avatar.png"
            val limited = FakeTransport()
            val limitedClient = readyClient(limited, caps = "batch $METADATA_CAP=max-value-bytes=16")
            connections.clients[networkId] = limitedClient

            assertTrue(coordinator.publishingAvailable(networkId))
            assertTrue(coordinator.setSelfAvatar(networkId, url))
            assertEquals(SelfAvatarSetting.Set(url), prefs.selfSetting(networkId).first())
            coordinator.onReady(networkId, limitedClient)
            assertTrue(limited.avatarWrites.isEmpty())
            assertTrue(store.upserts.isEmpty())
            limitedClient.stop()

            val capable = FakeTransport()
            val capableClient = readyClient(capable, caps = "batch $METADATA_CAP=max-value-bytes=2048")
            connections.clients[networkId] = capableClient
            capable.writeFailure = { line -> if (line.startsWith("METADATA ")) IOException("socket closed") else null }
            assertTrue(coordinator.setSelfAvatar(networkId, url))
            coordinator.onReady(networkId, capableClient)
            assertTrue(capable.avatarWrites.isEmpty())
            assertTrue(store.upserts.isEmpty())

            capable.writeFailure = { line -> if (line.startsWith("METADATA * SUB ")) IOException("socket closed") else null }
            coordinator.onReady(networkId, capableClient)
            assertEquals(listOf("METADATA * SET avatar $url"), capable.avatarWrites)

            val oversizedUrl = "https://example.com/${"a".repeat(512)}"
            assertTrue(coordinator.setSelfAvatar(networkId, oversizedUrl))
            assertEquals(SelfAvatarSetting.Set(oversizedUrl), prefs.selfSetting(networkId).first())
            coordinator.onReady(networkId, capableClient)
            assertEquals(listOf("METADATA * SET avatar $url"), capable.avatarWrites)
            assertTrue(store.upserts.isEmpty())
        }

    @Test fun self_avatar_optional_cache_io_is_nonfatal_but_cancellation_and_unexpected_errors_propagate() =
        runTest {
            val prefs = FakePrefs()
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, prefs, connections)
            val networkId = 1L
            val url = "https://example.com/avatar.png"
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_CAP")
            connections.clients[networkId] = client
            store.upsertFailure = IOException("disk unavailable")
            assertTrue(coordinator.setSelfAvatar(networkId, url))
            assertEquals(SelfAvatarSetting.Set(url), prefs.selfSetting(networkId).first())
            assertEquals(listOf("METADATA * SET avatar $url"), transport.avatarWrites)

            val unexpected = IllegalStateException("broken cache")
            store.upsertFailure = unexpected
            assertSame(unexpected, runCatching { coordinator.setSelfAvatar(networkId, url) }.exceptionOrNull())
            val cancelled = CancellationException("disconnected")
            transport.writeFailure = { cancelled }
            assertSame(cancelled, runCatching { coordinator.setSelfAvatar(networkId, url) }.exceptionOrNull())
            assertSame(cancelled, runCatching { coordinator.onReady(networkId, client) }.exceptionOrNull())
        }

    private fun TestScope.readyClient(
        transport: FakeTransport,
        caps: String,
    ): IrcClient {
        val client =
            IrcClient(
                IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
                TransportFactory { _, _, _, _, _ -> transport },
                backgroundScope,
            )
        client.start()
        runCurrent()
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        val requested = transport.sent.single { it.startsWith("CAP REQ :") }.substringAfter("CAP REQ :")
        transport.feed(":srv CAP me ACK :$requested")
        transport.feed(":srv 005 me CHANTYPES=# :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready)
        return client
    }

    private class FakeConnections : NoopConnectionManager() {
        val clients = mutableMapOf<Long, IrcClient>()

        override fun clientFor(networkId: Long): IrcClient? = clients[networkId]
    }

    private class FakeTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        val avatarWrites get() = sent.filter { it.startsWith("METADATA * SET avatar") }
        var writeFailure: ((String) -> Exception?)? = null

        override val incoming = inbound.consumeAsFlow()

        override suspend fun connect() = Unit

        override suspend fun send(line: String) {
            writeFailure?.invoke(line)?.let { throw it }
            sent += line
        }

        override suspend fun close() {
            inbound.close()
        }

        fun feed(line: String) {
            inbound.trySend(line).getOrThrow()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        store: AvatarStore,
        prefs: AvatarPrefs = FakePrefs(),
        connections: ConnectionManager = NoopConnectionManager(),
    ) = AvatarCoordinator(
        prefs = prefs,
        store = store,
        userDao = db.userDao(),
        bufferDao = db.bufferDao(),
        localAvatars = local,
        connections = Lazy { connections },
        scope = scope,
    )

    private fun dickordMessage(
        avatar: String?,
        target: String = "#discord.guild.general",
        source: String = "Alice/discord",
    ) = IrcEvent.ChatMessage(
        ctx =
            MessageContext(
                msgid = "message",
                serverTime = 1,
                account = "ignored-account",
                batchId = null,
                label = null,
                clientTags = avatar?.let { mapOf(DICKORD_AVATAR_TAG to it) } ?: emptyMap(),
            ),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix(source),
        target = target,
        text = "hello",
        isSelf = false,
        replyToMsgid = null,
    )

    private data class StoredAvatar(
        val networkId: Long,
        val nick: String,
        val account: String?,
        val url: String,
    )

    private data class RemovedAvatar(
        val networkId: Long,
        val nick: String,
        val account: String?,
    )

    private class FakePrefs(
        showSharedAvatars: Boolean = true,
    ) : AvatarPrefs {
        override val config = MutableStateFlow(AvatarConfig(showSharedAvatars))

        private val selfSettings = mutableMapOf<Long, MutableStateFlow<SelfAvatarSetting>>()

        override fun selfSetting(networkId: Long) = selfSettings.getOrPut(networkId) { MutableStateFlow(SelfAvatarSetting.Unmanaged) }

        override suspend fun setShowSharedAvatars(show: Boolean) = Unit

        override suspend fun setSelfSetting(
            networkId: Long,
            setting: SelfAvatarSetting,
        ) {
            selfSetting(networkId).value = setting
        }
    }

    private class FakeStore : AvatarStore {
        override val records = MutableStateFlow(emptyList<AvatarRecord>())
        val upserts = mutableListOf<StoredAvatar>()
        val removals = mutableListOf<RemovedAvatar>()
        var upsertFailure: Exception? = null

        override suspend fun upsert(
            networkId: Long,
            nick: String,
            account: String?,
            url: String,
        ) {
            upsertFailure?.let { throw it }
            upserts += StoredAvatar(networkId, nick, account, url)
        }

        override suspend fun remove(
            networkId: Long,
            nick: String,
            account: String?,
        ) {
            removals += RemovedAvatar(networkId, nick, account)
        }

        override suspend fun rename(
            networkId: Long,
            oldNick: String,
            newNick: String,
            account: String?,
        ) = Unit

        override suspend fun clearNetwork(networkId: Long) = Unit

        override suspend fun clearAll() = Unit
    }
}
