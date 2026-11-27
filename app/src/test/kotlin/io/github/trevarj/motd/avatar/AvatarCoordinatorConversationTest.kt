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
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.profile.IrcProfileRepository
import io.github.trevarj.motd.profile.ProfileKey
import io.github.trevarj.motd.profile.ProfileTarget
import io.github.trevarj.motd.profile.ProfileWriteResult
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
            assertEquals("https://example.com/channel.png", db.bufferDao().observeById(channelId)?.avatarOverrideModel)
            assertEquals(ConversationAvatarOutcome.LocalReset, coordinator.resetConversationAvatar(channelId))
            assertNull(db.bufferDao().observeById(channelId)?.avatarOverrideModel)
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

    @Test fun metadata_v3_snapshots_clear_only_missing_avatars_in_scope() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP")
            connections.clients[networkId] = client
            val previous = "https://example.com/old.png"
            val current = "https://example.com/new.png"
            store.records.value =
                listOf("#chat", "Alice", "Bob", "Dave", "Elsewhere", "Late", "me").map {
                    AvatarRecord(networkId, avatarIdentity(it, null), it, null, previous, 0)
                }

            val ready = client.state.value as IrcClientState.Ready
            transport.feed(":me!u@host NICK :newme")
            runCurrent()
            coordinator.onEvent(networkId, IrcEvent.Registered(ready.nick, ready.caps, ready.isupport))
            coordinator.onReady(networkId, client)
            coordinator.onEvent(networkId, raw(":srv 770 newme avatar"))
            coordinator.onEvent(
                networkId,
                IrcEvent.Joined(dickordMessage(null).ctx, "me", "#chat", null, null, isSelf = true),
            )
            coordinator.onEvent(networkId, raw(":srv METADATA Alice avatar * :$previous"))
            coordinator.onEvent(networkId, raw(":srv 761 newme alice avatar * :$current", sequence = 20))
            coordinator.onEvent(networkId, raw(":srv 761 newme dave avatar * :$current", sequence = 21))
            coordinator.onEvent(
                networkId,
                IrcEvent.Joined(dickordMessage(null).ctx, "Late", "#chat", null, null, isSelf = false, wireSequence = 22),
            )
            coordinator.onEvent(
                networkId,
                IrcEvent.NickChanged(dickordMessage(null).ctx, "Eve", "Frank", isSelf = false, wireSequence = 23),
            )
            coordinator.onEvent(
                networkId,
                IrcEvent.NickChanged(dickordMessage(null).ctx, "me", "newme", isSelf = true, wireSequence = 24),
            )
            coordinator.onEvent(networkId, raw(":srv 761 newme eve avatar * :$current", sequence = 25))
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot(
                    "me",
                    listOf(IrcMessage.parse(":srv 761 me me avatar * :$current").copy(wireSequence = 17)),
                    openingSequence = 11,
                    selfNickAtOpen = "me",
                ),
            )
            coordinator.onEvent(networkId, raw(":srv 766 newme newme avatar :Key not set", sequence = 26))
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot(
                    "#chat",
                    listOf(
                        IrcMessage.parse(":srv 761 newme fresh avatar * :$current").copy(wireSequence = 27),
                        IrcMessage.parse(":srv 766 newme #chat avatar :Key not set").copy(wireSequence = 28),
                    ),
                    openingSequence = 26,
                ),
                positiveMetadataOnly = true,
            )
            assertEquals(listOf("newme"), store.removals.map { it.nick })
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot("Elsewhere", listOf(IrcMessage.parse(":srv 761 me Elsewhere avatar * :$current"))),
            )
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot(
                    "#CHAT",
                    listOf(
                        IrcMessage.parse(":srv 761 me alice avatar * :$previous").copy(wireSequence = 15),
                        IrcMessage.parse(":srv 761 me Bob status * :busy"),
                        IrcMessage.parse(":srv 761 me Eve avatar * :$current").copy(wireSequence = 16),
                    ),
                    openingSequence = 10,
                    selfNickAtOpen = "me",
                ),
            )
            assertEquals(listOf("alice", "dave", "eve", "newme", "fresh", "Frank"), store.upserts.map { it.nick })
            assertEquals("newme", (client.state.value as IrcClientState.Ready).nick)
            assertTrue(store.upserts.all { it.url == current })
            assertEquals(listOf("newme", "#chat"), store.removals.map { it.nick })
            coordinator.onEvent(
                networkId,
                IrcEvent.Names("#chat", listOf("Alice", "Bob", "Dave", "Late").map { IrcEvent.Names.Member(it, "", null, null) }),
            )
            coordinator.onEvent(networkId, raw(":srv 766 newme alice avatar :Key not set"))
            assertEquals(listOf("newme", "#chat", "Bob", "alice"), store.removals.map { it.nick })
            assertEquals(listOf("METADATA * SUB avatar"), transport.sent.filter { it == "METADATA * SUB avatar" })
        }

    @Test fun metadata_replies_use_the_self_nick_at_read_even_when_the_consumer_lags() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP")
            connections.clients[networkId] = client
            coordinator.onReady(networkId, client)
            val url = "https://example.com/avatar.png"
            coordinator.setSelfAvatar(networkId, url)
            val paused = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            collectAvatarEvents(networkId, coordinator, client) { event ->
                if (event is IrcEvent.Raw && event.message.command == "770") {
                    paused.complete(Unit)
                    release.await()
                }
            }

            transport.feed(":srv 770 me avatar")
            paused.await()
            transport.feed(":srv 761 me Alice avatar * :$url")
            transport.feed(":srv 775 me 1 * SET avatar :$url")
            transport.feed(":me!u@host NICK :newme")
            transport.feed(":srv BATCH +sync metadata *ALL")
            transport.feed("@batch=sync :srv 761 newme Bob avatar * :$url")
            transport.feed(":srv BATCH -sync")
            runCurrent()
            assertEquals("newme", (client.state.value as IrcClientState.Ready).nick)
            assertTrue(store.upserts.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("Alice", "Bob"), store.upserts.map { it.nick })
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, transport.avatarWrites.size)
        }

    @Test fun an_old_open_metadata_batch_cannot_claim_a_fresh_monitor_snapshot() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val old = "https://example.com/old.png"
            val store = FakeStore()
            store.records.value = listOf(AvatarRecord(networkId, avatarIdentity("Alice", null), "Alice", null, old, 0))
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP")
            connections.clients[networkId] = client
            coordinator.onReady(networkId, client)
            collectAvatarEvents(networkId, coordinator, client)

            transport.feed(":srv 770 me avatar")
            transport.feed(":srv BATCH +old metadata Alice")
            transport.feed("@batch=old :srv 761 me Alice avatar * :https://example.com/stale.png")
            transport.feed(":srv 730 me :Alice!u@host")
            transport.feed(":srv BATCH -old")
            runCurrent()
            assertTrue(store.upserts.isEmpty())
            assertTrue(store.removals.isEmpty())

            transport.feed(":srv BATCH +fresh metadata Alice")
            transport.feed(":srv BATCH -fresh")
            runCurrent()
            assertEquals(listOf(RemovedAvatar(networkId, "Alice", null)), store.removals)
        }

    @Test fun labeled_join_metadata_does_not_consume_a_pending_sync_response() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch labeled-response $METADATA_V3_CAP")
            connections.clients[networkId] = client
            coordinator.onReady(networkId, client)
            coordinator.onEvent(networkId, raw(":srv 770 me avatar", sequence = 1L))
            coordinator.onEvent(networkId, raw(":srv 774 me #chat 0 :Please retry", sequence = 2L))
            testScheduler.advanceTimeBy(250)
            runCurrent()
            assertTrue("METADATA #chat SYNC" in transport.sent)

            coordinator.onEvent(
                networkId,
                IrcEvent.Joined(dickordMessage(null).ctx, "me", "#chat", null, null, isSelf = true, wireSequence = 10L),
            )
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot("#chat", emptyList(), openingSequence = 11L, joinResponseLabel = "join"),
            )
            val url = "https://example.com/avatar.png"
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot("#chat", listOf(IrcMessage.parse(":srv 761 me #chat avatar * :$url")), openingSequence = 13L),
            )
            assertEquals(listOf(StoredAvatar(networkId, "#chat", null, url)), store.upserts)
        }

    @Test fun channel_snapshot_does_not_defer_omissions_to_a_lazy_roster() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val url = "https://example.com/avatar.png"
            val store = FakeStore()
            store.records.value = listOf(AvatarRecord(networkId, avatarIdentity("Alice", null), "Alice", null, url, 0))
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP no-implicit-names")
            connections.clients[networkId] = client
            coordinator.onReady(networkId, client)
            collectAvatarEvents(networkId, coordinator, client)

            transport.feed(":srv 770 me avatar")
            transport.feed(":me!u@host JOIN #chat")
            transport.feed(":srv BATCH +joined metadata #chat")
            transport.feed(":srv BATCH -joined")
            transport.feed(":Alice!u@host JOIN #chat")
            runCurrent()
            coordinator.onEvent(networkId, IrcEvent.Names("#chat", listOf(IrcEvent.Names.Member("Alice", "", null, null))))
            assertTrue(store.removals.isEmpty())
        }

    @Test fun dialect_loss_discards_omissions_waiting_for_implicit_names() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val url = "https://example.com/avatar.png"
            val store = FakeStore()
            store.records.value = listOf(AvatarRecord(networkId, avatarIdentity("Alice", null), "Alice", null, url, 0))
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP $METADATA_CAP")
            connections.clients[networkId] = client
            coordinator.onReady(networkId, client)
            collectAvatarEvents(networkId, coordinator, client)

            transport.feed(":srv 770 me avatar")
            transport.feed(":me!u@host JOIN #chat")
            transport.feed(":srv BATCH +joined metadata #chat")
            transport.feed(":srv BATCH -joined")
            runCurrent()
            transport.feed(":srv CAP me DEL :$METADATA_V3_CAP")
            runCurrent()
            transport.feed(":srv CAP me ACK :$METADATA_CAP")
            runCurrent()
            coordinator.onEvent(networkId, raw(":srv METADATA Alice avatar * :$url"))
            coordinator.onEvent(networkId, IrcEvent.Names("#chat", listOf(IrcEvent.Names.Member("Alice", "", null, null))))
            assertEquals(listOf(StoredAvatar(networkId, "Alice", null, url)), store.upserts)
            assertTrue(store.removals.isEmpty())
        }

    @Test fun terminal_metadata_limits_retire_requests_and_allow_self_replay_after_new_limits() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val connections = FakeConnections()
            val coordinator = coordinator(this, FakeStore(), connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP=max-keys=1,max-subs=1")
            connections.clients[networkId] = client
            coordinator.onReady(networkId, client)
            val url = "https://example.com/avatar.png"
            coordinator.setSelfAvatar(networkId, url)
            collectAvatarEvents(networkId, coordinator, client)
            transport.feed(":srv FAIL METADATA TOO_MANY_SUBS avatar 1 :Subscription limit reached")
            transport.feed(":srv 775 me 1 * SUB avatar")
            transport.feed(":srv FAIL METADATA LIMIT_REACHED * 1 :Metadata limit reached")
            runCurrent()
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, transport.sent.count { it == "METADATA * SUB avatar" })
            assertEquals(1, transport.avatarWrites.size)

            transport.feed(":srv CAP me NEW :$METADATA_V3_CAP=max-keys=2,max-subs=2")
            runCurrent()
            assertEquals(2, transport.sent.count { it == "METADATA * SUB avatar" })
            assertEquals(2, transport.avatarWrites.size)
        }

    @Test fun metadata_v3_retries_only_current_avatar_requests_and_waits_for_server_state() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = 5L
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP")
            connections.clients[networkId] = client
            val url = "https://example.com/explicit.png"
            val returned = "https://example.com/rewritten.png"
            val rateLimited = raw(":srv 775 me 2 * SET avatar :$url")
            coordinator.onReady(networkId, client)
            coordinator.onEvent(networkId, rateLimited)
            coordinator.onEvent(networkId, raw(":srv 775 me 0 * CLEAR"))
            coordinator.onEvent(networkId, raw(":srv 774 me #chat nope :Try later"))
            coordinator.onEvent(networkId, raw(":srv 775 me 1 * SUB avatar"))
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                listOf("METADATA * SUB avatar", "METADATA * SUB avatar"),
                transport.sent.filter { it == "METADATA * SUB avatar" },
            )
            coordinator.onEvent(networkId, raw(":srv 770 me avatar"))

            assertTrue(coordinator.setSelfAvatar(networkId, url))
            assertTrue(store.upserts.isEmpty())
            coordinator.onEvent(networkId, raw(":srv 775 me 0 * SET avatar :$returned"))
            coordinator.onEvent(networkId, raw("@label=manual :srv 775 me 0 * SET avatar :$url"))
            coordinator.onEvent(networkId, raw(":srv 775 someone 0 * SET avatar :$url"))
            coordinator.onEvent(networkId, raw(":someone!user@host 775 me 0 * SET avatar :$url"))
            coordinator.onEvent(networkId, rateLimited)
            coordinator.onEvent(
                networkId,
                IrcEvent.StandardReply(
                    dickordMessage(null).ctx,
                    IrcEvent.StandardReplySeverity.FAIL,
                    "METADATA",
                    "KEY_NO_PERMISSION",
                    listOf("*", "status"),
                    "Permission denied",
                ),
            )
            testScheduler.advanceTimeBy(1_999)
            runCurrent()
            assertEquals(1, transport.avatarWrites.size)
            testScheduler.advanceTimeBy(1)
            runCurrent()
            assertEquals(2, transport.avatarWrites.size)
            coordinator.onEvent(networkId, raw(":srv 761 me me avatar * :$returned"))
            assertEquals(listOf(StoredAvatar(networkId, "me", null, returned)), store.upserts)
            coordinator.onEvent(networkId, rateLimited)

            val later = raw(":srv 774 me *ALL 2 :Try later")
            coordinator.onEvent(networkId, later)
            testScheduler.advanceTimeBy(1_000)
            coordinator.onEvent(networkId, later)
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
            assertFalse("METADATA *ALL SYNC" in transport.sent)
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, transport.sent.count { it == "METADATA *ALL SYNC" })
            assertEquals(2, transport.avatarWrites.size)
            coordinator.onEvent(networkId, raw(":srv 775 me 1 *ALL SYNC"))
            coordinator.onEvent(networkId, IrcEvent.MetadataSnapshot("*ALL", emptyList()))

            assertTrue(coordinator.setSelfAvatar(networkId, url))
            coordinator.onEvent(networkId, rateLimited)
            coordinator.stopManagingSelfAvatar(networkId)
            testScheduler.advanceTimeBy(2_000)
            runCurrent()
            assertEquals(3, transport.avatarWrites.size)
            assertEquals(1, transport.sent.count { it == "METADATA *ALL SYNC" })
        }

    @Test fun profile_channel_set_is_ranked_labeled_and_never_edits_the_device_override() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val roomId = db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chat", displayName = "#chat", type = BufferType.CHANNEL))
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val profile = IrcProfileRepository(db, Lazy { connections }, coordinator)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch labeled-response $METADATA_V3_CAP")
            connections.clients[networkId] = client
            profile.onReady(networkId, client)
            collectAvatarEvents(networkId, coordinator, client, profile = profile)
            transport.feed(":srv 005 me PREFIX=(qov)!%+ :supported")
            transport.feed(":me!u@h JOIN #chat")
            transport.feed(":srv 353 me = #chat :+me")
            transport.feed(":srv 366 me #chat :End of NAMES")
            runCurrent()
            val target = ProfileTarget.Channel(roomId)
            val local = "https://example.com/local.png"
            val submitted = "https://example.com/submitted.png"
            val rewritten = "https://example.com/rewritten.png"
            assertEquals(ConversationAvatarOutcome.LocalOnly, coordinator.setConversationAvatar(roomId, local))
            assertFalse(transport.sent.any { " SET avatar" in it })
            assertEquals(ProfileWriteResult.READ_ONLY, profile.write(target, ProfileKey.AVATAR, submitted))

            transport.feed(":srv MODE #chat +q me")
            runCurrent()
            val writes = Channel<String>(Channel.UNLIMITED)
            transport.onMetadataWrite = { if (" SET " in it) writes.trySend(it).getOrThrow() }
            val publish = async { profile.write(target, ProfileKey.AVATAR, submitted) }
            val set = writes.receive()
            assertTrue(set.startsWith("@label=") && set.endsWith("METADATA #chat SET avatar $submitted"))
            transport.feed("${set.substringBefore(' ')} :srv 761 * #chat avatar * :$rewritten")
            assertEquals(ProfileWriteResult.CONFIRMED, publish.await())
            assertEquals(rewritten, profile.observe(networkId, "#chat").first()[ProfileKey.AVATAR])
            assertEquals(local, db.bufferDao().observeById(roomId)?.avatarOverrideModel)
            assertEquals(listOf(StoredAvatar(networkId, "#chat", null, rewritten)), store.upserts)

            val remove = async { profile.write(target, ProfileKey.AVATAR, null) }
            val clear = writes.receive()
            transport.feed("${clear.substringBefore(' ')} :srv 766 me #chat avatar :Key not set")
            assertEquals(ProfileWriteResult.CONFIRMED, remove.await())
            assertEquals(emptyMap<ProfileKey, String>(), profile.observe(networkId, "#chat").first())
            assertEquals(local, db.bufferDao().observeById(roomId)?.avatarOverrideModel)
            assertEquals(listOf(RemovedAvatar(networkId, "#chat", null)), store.removals)

            val refused = async { profile.write(target, ProfileKey.DISPLAY_NAME, "Not allowed by server") }
            val refusedSet = writes.receive()
            transport.feed("${refusedSet.substringBefore(' ')} :srv FAIL METADATA KEY_NO_PERMISSION #chat display-name :Permission denied")
            assertEquals(ProfileWriteResult.REJECTED, refused.await())

            val reads = Channel<String>(Channel.UNLIMITED)
            transport.onMetadataWrite = {
                if (" SET " in it) writes.trySend(it).getOrThrow()
                if (" GET " in it) reads.trySend(it).getOrThrow()
            }
            val hidden = async { profile.write(target, ProfileKey.DISPLAY_NAME, null) }
            transport.feed("${writes.receive().substringBefore(' ')} :srv ACK")
            val getLabel = reads.receive().substringBefore(' ')
            transport.feed("$getLabel :srv BATCH +hidden metadata #chat")
            transport.feed("@batch=hidden :srv 766 me #chat avatar :Key not set")
            transport.feed("@batch=hidden :srv 766 me #chat display-name :Key not set")
            transport.feed(":srv BATCH -hidden")
            assertEquals(ProfileWriteResult.UNCONFIRMED, hidden.await())

            transport.feed(":srv MODE #chat -q me")
            runCurrent()
            assertEquals(ProfileWriteResult.READ_ONLY, profile.write(target, ProfileKey.DISPLAY_NAME, "Not an operator"))
        }

    @Test fun v2_unlabeled_write_requires_exact_target_scoped_readback() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val connections = FakeConnections()
            val coordinator = coordinator(this, FakeStore(), connections = connections)
            val profile = IrcProfileRepository(db, Lazy { connections }, coordinator)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_CAP")
            connections.clients[networkId] = client
            profile.onReady(networkId, client)
            collectAvatarEvents(networkId, coordinator, client, profile = profile)
            val reads = Channel<IrcMessage>(Channel.UNLIMITED)
            transport.onMetadataWrite = { if (" GET " in it) reads.trySend(IrcMessage.parse(it)).getOrThrow() }
            val value = "  she/her (妳/她)  "
            val write = async { profile.write(ProfileTarget.Self(networkId), ProfileKey.PRONOUNS, value) }
            val read = reads.receive()
            assertTrue(transport.sent.contains("METADATA * SET pronouns :$value"))
            transport.feed(":srv 761 me me pronouns * :$value")
            runCurrent()
            assertFalse(write.isCompleted)

            fun reply(
                id: String,
                recipient: String,
                pronouns: String? = value,
            ) {
                transport.feed(":srv BATCH +$id metadata me")
                read.params.drop(2).forEach { key ->
                    val row = if (key == "pronouns" && pronouns != null) "761 $recipient me $key * :$pronouns" else "766 me me $key :Key not set"
                    transport.feed("@batch=$id :srv $row")
                }
                transport.feed(":srv BATCH -$id")
            }
            reply("wrong", "alice")
            runCurrent()
            assertFalse(write.isCompleted)
            reply("readback", "me")
            assertEquals(ProfileWriteResult.CONFIRMED, write.await())
            assertEquals(value, profile.observe(networkId, "me").first()[ProfileKey.PRONOUNS])

            val clear = async { profile.write(ProfileTarget.Self(networkId), ProfileKey.PRONOUNS, null) }
            reads.receive()
            reply("hidden", "me", pronouns = null)
            assertEquals(ProfileWriteResult.UNCONFIRMED, clear.await())
            assertNull(profile.observe(networkId, "me").first()[ProfileKey.PRONOUNS])

            val cancelled = async { profile.refresh(ProfileTarget.Self(networkId)) }
            reads.receive()
            cancelled.cancel()
            val after = async { profile.write(ProfileTarget.Self(networkId), ProfileKey.PRONOUNS, value) }
            runCurrent()
            assertTrue(reads.tryReceive().isFailure)
            reply("drained", "me")
            reads.receive()
            assertFalse(after.isCompleted)
            reply("new-read", "me")
            assertEquals(ProfileWriteResult.CONFIRMED, after.await())
            cancelled.join()
        }

    @Test fun labeled_profile_get_cannot_overwrite_a_newer_live_value_in_either_cache() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val connections = FakeConnections()
            val store = FakeStore()
            val coordinator = coordinator(this, store, connections = connections)
            val profile = IrcProfileRepository(db, Lazy { connections }, coordinator)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch labeled-response $METADATA_V3_CAP")
            connections.clients[networkId] = client
            profile.onReady(networkId, client)
            collectAvatarEvents(networkId, coordinator, client, profile = profile)

            val reads = Channel<String>(Channel.UNLIMITED)
            transport.onMetadataWrite = { if (" GET " in it) reads.trySend(it).getOrThrow() }
            val refresh = async { profile.refresh(ProfileTarget.Self(networkId)) }
            val label = reads.receive().substringBefore(' ')
            val live = "https://example.com/live.png"
            transport.feed("$label :srv BATCH +outer labeled-response")
            transport.feed("@batch=outer :srv BATCH +snapshot metadata me")
            transport.feed(":srv 761 me me avatar * :$live")
            runCurrent()
            transport.feed("@batch=snapshot :srv 761 me me avatar * :https://example.com/stale.png")
            transport.feed("@batch=outer :srv BATCH -snapshot")
            transport.feed(":srv BATCH -outer")
            refresh.await()
            runCurrent()

            assertEquals(live, profile.observe(networkId, "me").first()[ProfileKey.AVATAR])
            assertEquals(listOf(StoredAvatar(networkId, "me", null, live)), store.upserts)
        }

    @Test fun only_authoritative_profile_snapshots_clear_previously_subscribed_stale_values() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = insertNetwork()
            val connections = FakeConnections()
            val store = FakeStore()
            val coordinator = coordinator(this, store, FakePrefs(showSharedAvatars = false), connections)
            val profile = IrcProfileRepository(db, Lazy { connections }, coordinator)
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP")
            connections.clients[networkId] = client
            profile.onReady(networkId, client)
            coordinator.onReady(networkId, client)
            collectAvatarEvents(networkId, coordinator, client, profile = profile)

            transport.feed(":srv 770 me display-name status avatar")
            transport.feed(":srv 761 me Alice display-name * :Old Name")
            transport.feed(":srv 761 me Alice status * :Old status")
            transport.feed(":srv 761 me Alice homepage * :https://example.com")
            transport.feed(":srv 761 me Alice avatar * :https://example.com/avatar.png")
            transport.feed(":srv 730 me :Alice!u@host")
            runCurrent()

            val list = IrcEvent.MetadataSnapshot("Alice", emptyList(), Long.MAX_VALUE)
            profile.onEvent(networkId, client, list, positiveMetadataOnly = true)
            assertEquals("Old Name", profile.observe(networkId, "Alice").first()[ProfileKey.DISPLAY_NAME])
            transport.feed(":srv BATCH +sub metadata *ALL")
            transport.feed(":srv BATCH -sub")
            transport.feed(":srv BATCH +sync metadata Alice")
            transport.feed(":srv 761 me Alice status * :New status")
            transport.feed(":srv BATCH -sync")
            runCurrent()

            assertEquals(
                mapOf(ProfileKey.STATUS to "New status", ProfileKey.HOMEPAGE to "https://example.com"),
                profile.observe(networkId, "Alice").first(),
            )
            assertTrue(store.upserts.isEmpty())
            assertTrue(store.removals.isEmpty())
        }

    @Test fun cancelling_profile_avatar_restores_ownership_only_before_a_transport_attempt() =
        runTest {
            val prefs = FakePrefs()
            val coordinator = coordinator(this, FakeStore(), prefs)
            val networkId = 5L
            for (attempted in listOf(false, true)) {
                val waiting = CompletableDeferred<Unit>()
                val write =
                    launch {
                        coordinator.explicitSelfAvatar(networkId) { onAttempt ->
                            if (attempted) onAttempt()
                            waiting.complete(Unit)
                            CompletableDeferred<ProfileWriteResult>().await()
                        }
                    }
                waiting.await()
                assertTrue(prefs.profileOwnsSelfAvatar(networkId))
                write.cancelAndJoin()
                assertEquals(attempted, prefs.profileOwnsSelfAvatar(networkId))
            }
        }

    @Test fun metadata_fallback_restores_unconfirmed_self_avatar_without_retrying_old_session_request() =
        runTest {
            val networkId = 5L
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP $METADATA_CAP")
            val connections = FakeConnections()
            connections.clients[networkId] = client
            val coordinator = coordinator(this, FakeStore(), connections = connections)
            val url = "https://example.com/avatar.png"
            assertTrue(coordinator.setSelfAvatar(networkId, url))
            coordinator.onEvent(networkId, raw(":srv 775 me 2 * SET avatar :$url"))
            coordinator.onEvent(
                networkId,
                IrcEvent.StandardReply(
                    dickordMessage(null).ctx,
                    IrcEvent.StandardReplySeverity.FAIL,
                    "METADATA",
                    "KEY_NO_PERMISSION",
                    listOf("*", AVATAR_KEY),
                    "Permission denied",
                ),
            )

            transport.feed(":srv CAP me DEL :$METADATA_V3_CAP")
            runCurrent()
            coordinator.onEvent(networkId, IrcEvent.CapsChanged(emptySet(), setOf(METADATA_V3_CAP)))
            assertTrue("CAP REQ :$METADATA_CAP" in transport.sent)
            transport.feed(":srv CAP me ACK :$METADATA_CAP")
            runCurrent()
            coordinator.onEvent(networkId, IrcEvent.CapsChanged(setOf(METADATA_CAP), emptySet()))
            testScheduler.advanceTimeBy(2_000)
            runCurrent()
            assertEquals(List(2) { "METADATA * SET avatar $url" }, transport.avatarWrites)
        }

    @Test fun metadata_resyncs_after_rate_limited_unsubscribe_is_replaced() =
        runTest {
            val networkId = 5L
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch $METADATA_V3_CAP")
            val connections = FakeConnections()
            connections.clients[networkId] = client
            connections.states.value = mapOf(networkId to client.state.value)
            val store = FakeStore()
            val coordinator = coordinator(this, store, connections = connections)
            coordinator.onReady(networkId, client)
            coordinator.onEvent(networkId, raw(":srv 770 me avatar"))
            val oldOpening = System.nanoTime() - 1
            coordinator.setShowSharedAvatars(false)
            coordinator.onEvent(networkId, raw(":srv 775 me 2 * UNSUB avatar"))
            coordinator.setShowSharedAvatars(true)
            coordinator.onEvent(networkId, raw(":srv 770 me avatar"))
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot(
                    "*ALL",
                    listOf(IrcMessage.parse(":srv 761 me alice avatar * :https://example.com/stale.png")),
                    openedAtNanos = oldOpening,
                ),
            )
            assertTrue(store.upserts.isEmpty())
            testScheduler.advanceTimeBy(2_000)
            runCurrent()
            assertEquals(
                listOf("METADATA * SUB avatar", "METADATA * UNSUB avatar", "METADATA * SUB avatar", "METADATA *ALL SYNC"),
                transport.sent.filter { it == "METADATA *ALL SYNC" || it in listOf("METADATA * SUB avatar", "METADATA * UNSUB avatar") },
            )
            val current = "https://example.com/current.png"
            coordinator.onEvent(
                networkId,
                IrcEvent.MetadataSnapshot(
                    "*ALL",
                    listOf(IrcMessage.parse(":srv 761 me alice avatar * :$current")),
                    openedAtNanos = System.nanoTime(),
                ),
            )
            assertEquals(listOf(StoredAvatar(networkId, "alice", null, current)), store.upserts)
        }

    @Test fun metadata_snapshot_cannot_refill_shared_cache_after_opt_out() =
        runTest {
            useTestSchedulerForRoom()
            val networkId = 5L
            val store = FakeStore()
            val connections = FakeConnections()
            val coordinator = coordinator(this, store, connections = connections)
            val client = readyClient(FakeTransport(), caps = "batch $METADATA_V3_CAP")
            connections.clients[networkId] = client
            coordinator.onReady(networkId, client)
            coordinator.onEvent(networkId, raw(":srv 770 me avatar"))
            val writing = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            store.beforeUpsert = {
                writing.complete(Unit)
                resume.await()
            }
            val snapshot =
                async {
                    coordinator.onEvent(
                        networkId,
                        IrcEvent.MetadataSnapshot(
                            "*ALL",
                            listOf("alice", "bob").map { IrcMessage.parse(":srv 761 me $it avatar * :https://example.com/$it.png") },
                        ),
                    )
                }
            writing.await()
            val optOut = async { coordinator.setShowSharedAvatars(false) }
            runCurrent()
            assertFalse(optOut.isCompleted)
            resume.complete(Unit)
            snapshot.await()
            optOut.await()
            assertTrue(store.upserts.isEmpty())
        }

    private fun TestScope.collectAvatarEvents(
        networkId: Long,
        coordinator: AvatarCoordinator,
        client: IrcClient,
        profile: IrcProfileRepository? = null,
        beforeEvent: suspend (IrcEvent) -> Unit = {},
    ) {
        backgroundScope.launch {
            for (event in client.criticalEvents) {
                beforeEvent(event)
                profile?.onEvent(networkId, client, event)
                if (profile == null || event !is IrcEvent.MetadataSnapshot) coordinator.onEvent(networkId, event)
            }
        }
    }

    private fun raw(
        line: String,
        sequence: Long = 0L,
    ) = IrcEvent.Raw(IrcMessage.parse(line).copy(wireSequence = sequence))

    private fun TestScope.useTestSchedulerForRoom() {
        db.close()
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
                .build()
    }

    private suspend fun insertNetwork() =
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
        if ("labeled-response" in caps.split(' ') && !client.hasCap("labeled-response")) {
            transport.feed(":srv CAP me NEW :labeled-response")
            runCurrent()
            check("CAP REQ :labeled-response" in transport.sent)
            transport.feed(":srv CAP me ACK :labeled-response")
            runCurrent()
        }
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
        var onMetadataWrite: ((String) -> Unit)? = null

        override val incoming = inbound.consumeAsFlow()

        override suspend fun connect() = Unit

        override suspend fun send(line: String) {
            writeFailure?.invoke(line)?.let { throw it }
            sent += line
            if ("METADATA " in line) onMetadataWrite?.invoke(line)
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
        memberDao = db.memberDao(),
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
        private val profileOwners = mutableSetOf<Long>()

        override fun selfSetting(networkId: Long) = selfSettings.getOrPut(networkId) { MutableStateFlow(SelfAvatarSetting.Unmanaged) }

        override suspend fun setShowSharedAvatars(show: Boolean) {
            config.value = AvatarConfig(showSharedAvatars = show)
        }

        override suspend fun setSelfSetting(
            networkId: Long,
            setting: SelfAvatarSetting,
        ) {
            selfSetting(networkId).value = setting
        }

        override suspend fun profileOwnsSelfAvatar(networkId: Long): Boolean = networkId in profileOwners

        override suspend fun setProfileOwnsSelfAvatar(
            networkId: Long,
            owned: Boolean,
        ) {
            currentCoroutineContext().ensureActive()
            if (owned) profileOwners.add(networkId) else profileOwners.remove(networkId)
        }
    }

    private class FakeStore : AvatarStore {
        override val records = MutableStateFlow(emptyList<AvatarRecord>())
        val upserts = mutableListOf<StoredAvatar>()
        val removals = mutableListOf<RemovedAvatar>()
        var upsertFailure: Exception? = null
        var beforeUpsert: suspend () -> Unit = {}

        override suspend fun upsert(
            networkId: Long,
            nick: String,
            account: String?,
            url: String,
        ) {
            upsertFailure?.let { throw it }
            beforeUpsert()
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

        override suspend fun clearAll() {
            upserts.clear()
        }
    }
}
