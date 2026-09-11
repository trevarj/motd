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
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    private fun coordinator(
        scope: CoroutineScope,
        store: AvatarStore,
        prefs: AvatarPrefs = FakePrefs(),
    ) = AvatarCoordinator(
        prefs = prefs,
        store = store,
        userDao = db.userDao(),
        bufferDao = db.bufferDao(),
        localAvatars = local,
        connections = Lazy { NoopConnectionManager() },
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

        override fun selfSetting(networkId: Long) = MutableStateFlow<SelfAvatarSetting>(SelfAvatarSetting.Unmanaged)

        override suspend fun setShowSharedAvatars(show: Boolean) = Unit

        override suspend fun setSelfSetting(
            networkId: Long,
            setting: SelfAvatarSetting,
        ) = Unit
    }

    private class FakeStore : AvatarStore {
        override val records = MutableStateFlow(emptyList<AvatarRecord>())
        val upserts = mutableListOf<StoredAvatar>()
        val removals = mutableListOf<RemovedAvatar>()

        override suspend fun upsert(
            networkId: Long,
            nick: String,
            account: String?,
            url: String,
        ) {
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
