package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.KeywordHighlightRule
import io.github.trevarj.motd.data.prefs.KeywordMatch
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.ChatRoomRevisions
import io.github.trevarj.motd.data.repo.NetworkIgnoreCache
import io.github.trevarj.motd.data.repo.SUPPRESS_HIGHLIGHT
import io.github.trevarj.motd.data.repo.SUPPRESS_UNREAD
import io.github.trevarj.motd.data.saved.SavedMessage
import io.github.trevarj.motd.data.saved.SavedMessageSource
import io.github.trevarj.motd.data.saved.SavedMessagesRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.dickord.DICKORD_CHANNEL_TAG
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.EventMapper
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.push.PushEventHandler
import io.github.trevarj.motd.push.WebPushCrypto
import io.github.trevarj.motd.push.launchUnifiedPushBroadcast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class EventProcessorTest {
    private class RecordingDiagnostics : DiagnosticLogger {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(true)
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun setEnabled(enabled: Boolean) = Unit

        override fun record(
            component: String,
            event: String,
            fields: () -> Map<String, Any?>,
        ) {
            events += event to fields()
        }

        override fun fingerprint(value: String?): String? = value

        override suspend fun exportTo(output: OutputStream) = Unit
    }

    private enum class InviteDeliveryOrder {
        HISTORY_LIVE,
        LIVE_HISTORY,
        HISTORY_PUSH,
        PUSH_HISTORY,
    }

    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId: Long = 0

    private fun ctx(
        msgid: String? = null,
        time: Long = 1000,
        label: String? = null,
        account: String? = null,
    ) = MessageContext(msgid = msgid, serverTime = time, account = account, batchId = null, label = label)

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun pagingList(bufferId: Long) =
        db
            .messageDao()
            .pagingSource(bufferId)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 100, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    /** The fixture network is DIRECT; soju's console only exists on a bouncer root. */
    private suspend fun makeBouncerRoot() {
        val row = db.networkDao().byId(networkId)!!
        db.networkDao().update(row.copy(role = NetworkRole.BOUNCER_ROOT))
    }

    @Test
    fun chatMessage_channel_insertsRow_andAutoCreatesBuffer() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "hello world",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")
            assertNotNull(buffer)
            assertEquals(BufferType.CHANNEL, buffer!!.type)
            val rows = pagingList(buffer.id)
            assertEquals(1, rows.size)
            assertEquals("hello world", rows.single().text)
            assertNull(rows.single().ircFormattedText)
            assertFalse(rows.single().hasMention)
        }

    @Test
    fun bot_join_survives_roster_refresh_and_nick_change_but_not_a_new_session() =
        runTest {
            processor.process(networkId, IrcEvent.NamesStarted("#chan"))
            processor.process(networkId, IrcEvent.Joined(ctx(msgid = "bot-join"), "Helper", "#chan", "acct", null, false, isBot = true))
            processor.process(networkId, IrcEvent.Names("#chan", emptyList()))
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertTrue(
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .single()
                    .isBot,
            )
            assertTrue(db.messageDao().byMsgid(buffer.id, "bot-join")!!.isBot)

            processor.process(networkId, IrcEvent.NickChanged(ctx(), "Helper", "Renamed", false))
            processor.process(
                networkId,
                IrcEvent.Registered("me", emptySet(), mapOf("BOT" to "b"), startsSession = false),
            )
            assertFalse(db.userDao().isBot(networkId, "helper"))
            assertTrue(db.userDao().isBot(networkId, "renamed"))
            assertTrue(
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .single()
                    .isBot,
            )

            processor.process(networkId, IrcEvent.Registered("me", emptySet(), emptyMap()))
            assertFalse(db.userDao().isBot(networkId, "renamed"))
            assertFalse(
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .single()
                    .isBot,
            )
            assertEquals("acct", db.userDao().byNick(networkId, "renamed")?.account)
        }

    @Test
    fun ordinary_who_marks_rfc1459_equivalent_nicks_and_preserves_account() =
        runTest {
            val mapper = EventMapper({ "me" }, { Isupport() })
            val bot = mapper.map(IrcMessage.parse(":srv 352 me #chan username host srv HELPER{ Gb* :0 Name")) as IrcEvent.WhoRow
            processor.process(networkId, IrcEvent.Joined(ctx(), "Helper[", "#chan", "account", null, false))
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            processor.process(networkId, bot)
            assertFalse(
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .single()
                    .isBot,
            )

            processor.process(networkId, IrcEvent.Registered("me", emptySet(), mapOf("BOT" to "b"), startsSession = false))
            processor.process(networkId, bot)
            val user = db.userDao().byNick(networkId, "helper{")!!
            assertEquals("account", user.account)
            assertEquals("username@host", user.hostmask)
            assertEquals("Name", user.realname)
            assertTrue(user.away)
            assertTrue(user.isBot)
            assertTrue(
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .single()
                    .isBot,
            )

            processor.process(networkId, bot.copy(flags = "HB"))
            assertFalse(db.userDao().isBot(networkId, "helper{"))
            assertFalse(
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .single()
                    .isBot,
            )
        }

    @Test
    fun repeated_whox_restores_bot_flag_after_channel_rename_merges_into_older_room() =
        runTest {
            processor.process(networkId, IrcEvent.Registered("me", emptySet(), mapOf("BOT" to "b"), startsSession = false))
            processor.process(networkId, IrcEvent.Names("#new", emptyList()))
            processor.process(networkId, IrcEvent.Names("#old", listOf(IrcEvent.Names.Member("Helper[", "@", null, null))))
            val destination = db.bufferDao().byName(networkId, "#new")!!
            val source = db.bufferDao().byName(networkId, "#old")!!
            val whox = IrcEvent.WhoxRow(12, "helper", "host", "HELPER{", null, "Hb", "Helper")
            processor.process(networkId, whox)
            assertTrue(
                db
                    .memberDao()
                    .allNow(source.id)
                    .single()
                    .isBot,
            )

            processor.process(networkId, IrcEvent.ChannelRenamed(ctx(), "oper", "#old", "#new", null))
            assertEquals(destination.id, db.bufferDao().canonicalId(source.id))
            assertTrue(db.userDao().isBot(networkId, "helper{"))

            processor.process(networkId, whox)
            val member = db.memberDao().allNow(destination.id).single()
            assertEquals("Helper[", member.nick)
            assertEquals("@", member.prefixes)
            assertTrue(member.isBot)
        }

    @Test
    fun whois_bot_numeric_stays_in_command_output_and_only_message_tag_marks_chat() =
        runTest {
            processor.process(networkId, IrcEvent.Joined(ctx(), "Helper", "#chan", null, null, false, isBot = true))
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            processor.beginCommandResponse(networkId, buffer.id, "WHOIS", label = null)
            processor.process(networkId, IrcEvent.Raw(IrcMessage.parse(":srv 335 me Other :is a robot")))
            processor.process(networkId, IrcEvent.Raw(IrcMessage.parse(":srv 318 me Other :End of WHOIS")))
            assertNull(db.userDao().byNick(networkId, "other"))
            assertTrue(pagingList(buffer.id).any { it.kind == MessageKind.SERVER_INFO && it.text.contains("is a robot") })

            val chat = IrcEvent.ChatMessage(ctx("untagged", 2000), IrcEvent.ChatKind.PRIVMSG, Prefix("Helper"), "#chan", "hello", false, null)
            processor.process(networkId, chat)
            processor.process(networkId, chat.copy(ctx = ctx("tagged", 3000), isBot = true))
            assertTrue(db.userDao().isBot(networkId, "helper"))
            assertFalse(db.messageDao().byMsgid(buffer.id, "untagged")!!.isBot)
            assertTrue(db.messageDao().byMsgid(buffer.id, "tagged")!!.isBot)
        }

    @Test
    fun bot_status_expires_after_last_irc_folded_part_without_leaking_to_rejoin() =
        runTest {
            val join = IrcEvent.Joined(ctx(), "Helper[", "#first", null, null, false, isBot = true)
            processor.process(networkId, join)
            processor.process(networkId, join.copy(channel = "#second", isBot = false))
            val first = db.bufferDao().byName(networkId, "#first")!!
            val second = db.bufferDao().byName(networkId, "#second")!!
            processor.process(networkId, IrcEvent.Parted(ctx(), "HELPER{", "#first", null, false))
            assertTrue(db.memberDao().allNow(first.id).isEmpty())
            assertTrue(db.userDao().isBot(networkId, "helper{"))
            assertTrue(
                db
                    .memberDao()
                    .allNow(second.id)
                    .single()
                    .isBot,
            )

            processor.process(networkId, IrcEvent.Parted(ctx(), "HELPER{", "#second", null, false))
            assertTrue(db.memberDao().allNow(second.id).isEmpty())
            assertFalse(db.userDao().isBot(networkId, "helper{"))

            processor.process(networkId, join.copy(ctx = ctx(msgid = "human-join", time = 2000), isBot = false))
            assertFalse(db.userDao().isBot(networkId, "helper{"))
            assertFalse(
                db
                    .memberDao()
                    .allNow(first.id)
                    .single()
                    .isBot,
            )
            assertFalse(db.messageDao().byMsgid(first.id, "human-join")!!.isBot)
        }

    @Test
    fun irc_folded_kick_removes_bot_and_expires_cache_before_untagged_rejoin() =
        runTest {
            val join = IrcEvent.Joined(ctx(), "Helper[", "#chan", null, null, false, isBot = true)
            processor.process(networkId, join)
            val room = db.bufferDao().byName(networkId, "#chan")!!
            assertTrue(
                db
                    .memberDao()
                    .allNow(room.id)
                    .single()
                    .isBot,
            )

            processor.process(networkId, IrcEvent.Kicked(ctx(), "HELPER{", "#chan", "op", null, false))
            assertTrue(db.memberDao().allNow(room.id).isEmpty())
            assertFalse(db.userDao().isBot(networkId, "helper{"))

            processor.process(networkId, join.copy(ctx = ctx(msgid = "human-join", time = 2000), isBot = false))
            assertFalse(db.userDao().isBot(networkId, "helper{"))
            assertFalse(
                db
                    .memberDao()
                    .allNow(room.id)
                    .single()
                    .isBot,
            )
            assertFalse(db.messageDao().byMsgid(room.id, "human-join")!!.isBot)
        }

    @Test
    fun formattedChatRetainsRawPayloadBesidePlainProjection() =
        runTest {
            val raw = "\u0002hello\u0002"
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "formatted"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = raw,
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val room = requireNotNull(db.bufferDao().byName(networkId, "#chan"))
            val row = pagingList(room.id).single()
            assertEquals("hello", row.text)
            assertEquals(raw, row.ircFormattedText)
        }

    @Test
    fun ignoredNetworkSender_isNotPersisted() =
        runTest {
            db.networkIgnoreDao().upsert(
                NetworkIgnoreEntity(networkId = networkId, pattern = "alice!*@*", createdAt = 1),
            )

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "ignored"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice", "user", "host"),
                    target = "#chan",
                    text = "hidden",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            assertNull(db.bufferDao().byName(networkId, "#chan"))

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "shown", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("bob", "user", "host"),
                    target = "#chan",
                    text = "shown",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")
            assertNotNull(buffer)
            val rows = pagingList(buffer!!.id)
            assertEquals(listOf("shown"), rows.map { it.text })
        }

    @Test
    fun ignoredNetworkSender_preservesHistoryAndReplayAttribution() =
        runTest {
            db.networkIgnoreDao().upsert(
                NetworkIgnoreEntity(networkId = networkId, pattern = "alice!*@*", createdAt = 1),
            )

            for (batch in listOf("history", "replay")) {
                val target = "#$batch"
                val messages =
                    listOf("alice", "bob", "alice", "carol", "me").mapIndexed { index, nick ->
                        IrcEvent.ChatMessage(
                            ctx =
                                ctx(
                                    msgid = "$batch-$index",
                                    time = 1000L + index,
                                    account = "$nick-account",
                                ).copy(batchId = batch),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix(nick, "user", "host"),
                            target = target,
                            text = "$batch-body-$index",
                            isSelf = nick == "me",
                            replyToMsgid = null,
                        )
                    }
                processor.process(
                    networkId,
                    if (batch == "history") {
                        IrcEvent.HistoryBatch(target, messages)
                    } else {
                        IrcEvent.ReplayBatch(target, messages)
                    },
                )

                val buffer = db.bufferDao().byName(networkId, target)!!
                val rows = pagingList(buffer.id)
                val accepted = messages.filterNot { it.source.nick == "alice" }
                assertEquals(accepted.map { it.ctx.msgid }.toSet(), rows.map { it.msgid }.toSet())
                assertEquals(accepted.size, rows.size)
                for (event in accepted) {
                    val row = rows.single { it.msgid == event.ctx.msgid }
                    assertEquals(event.source.nick, row.sender)
                    assertEquals(event.ctx.account, row.senderAccount)
                    assertEquals(event.text, row.text)
                    assertEquals(event.ctx.serverTime, row.serverTime)
                    assertEquals(event.isSelf, row.isSelf)
                }
                val ignoredBodies = messages.filter { it.source.nick == "alice" }.map { it.text }
                assertTrue(rows.none { it.text in ignoredBodies })
            }
        }

    @Test
    fun hardIgnoredHistoryDoesNotCreateOrBindQuery() =
        runTest {
            val hard = NetworkIgnoreEntity(networkId = networkId, pattern = "alice!*@*", createdAt = 1)
            db.networkIgnoreDao().upsert(hard)
            db.networkIgnoreDao().upsert(hard.copy(dropMessages = false, suppressedSignals = SUPPRESS_HIGHLIGHT))
            val ignored =
                IrcEvent.ChatMessage(
                    ctx(msgid = "ignored", account = "alice-account").copy(batchId = "history"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice", "user", "host"),
                    "me",
                    "hidden",
                    true,
                    null,
                )
            processor.process(networkId, IrcEvent.HistoryBatch("alice", listOf(ignored)))

            assertNull(db.bufferDao().byName(networkId, "alice"))
            assertNull(BufferStore(db).resolveQueryRoom(networkId, "alice", "alice-account"))
        }

    @Test
    fun hardIgnoredHistoryPreservesReadOnlyPeerEvidenceForRedaction() =
        runTest {
            val ignoreDao = db.networkIgnoreDao()
            val cache = NetworkIgnoreCache(ignoreDao)
            val processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop, networkIgnoreCache = cache)
            processor.onRegistered(networkId, "me", emptyMap())
            val original =
                IrcEvent.ChatMessage(
                    ctx(msgid = "secret", account = "peer-account"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("old-peer"),
                    "me",
                    "secret",
                    false,
                    null,
                )
            processor.process(networkId, original)
            val room = checkNotNull(db.bufferDao().byName(networkId, "old-peer"))
            val row = checkNotNull(db.messageDao().byMsgid(room.id, "secret"))
            val saved = SavedMessagesRepository(db, AppClock { 5_000L })
            saved.setSaved(row.id, true)
            processor.process(
                networkId,
                original.copy(ctx = ctx(msgid = "other", account = "other-account"), source = Prefix("new-peer"), text = "different owner"),
            )
            val rooms = BufferStore(db)
            val currentPeer = checkNotNull(rooms.resolveQueryRoom(networkId, "new-peer", account = null))
            ignoreDao.upsert(NetworkIgnoreEntity(networkId = networkId, pattern = "new-peer!*@*", createdAt = 1))
            cache.invalidate(networkId)
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "batch-peer",
                    listOf(
                        original.copy(
                            ctx = ctx(msgid = "ignored", time = 2_000, account = "peer-account"),
                            source = Prefix("new-peer"),
                            target = "previous-me",
                        ),
                        IrcEvent.MessageRedacted(ctx(), Prefix("new-peer"), "previous-me", "secret", null),
                        IrcEvent.MessageRedacted(ctx(), Prefix("new-peer"), "previous-me", "other", null),
                    ),
                ),
            )

            assertEquals(MessageKind.REDACTED, pagingList(room.id).single().kind)
            val snapshot = saved.observe().first().single()
            assertNull(snapshot.text)
            assertEquals(currentPeer.id, rooms.resolveQueryRoom(networkId, "new-peer", account = null)?.id)
            assertEquals("different owner", pagingList(currentPeer.id).single().text)
            assertNull(db.bufferDao().byName(networkId, "batch-peer"))
        }

    @Test
    fun selectivelyUnreadSuppressedChatStoresRowsWithoutRevivingArchiveOrWritingMarkers() =
        runTest {
            val dao = db.networkIgnoreDao()
            val cache = NetworkIgnoreCache(dao)
            val processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop, networkIgnoreCache = cache)
            processor.onRegistered(networkId, "me", emptyMap())
            val ruleId =
                dao.upsert(
                    NetworkIgnoreEntity(
                        networkId = networkId,
                        pattern = "alice!*@*",
                        createdAt = 1,
                        dropMessages = false,
                        suppressedSignals = SUPPRESS_UNREAD,
                    ),
                )
            for (origin in listOf("live", "history", "push", "replay")) {
                dao.setEnabled(ruleId, true)
                cache.invalidate(networkId)
                val channel = "#$origin"
                val roomId =
                    db.bufferDao().insert(
                        BufferEntity(
                            networkId = networkId,
                            name = channel,
                            displayName = channel,
                            type = BufferType.CHANNEL,
                            archived = true,
                            readMarkerTime = 100,
                            localReadAnchorTime = 200,
                            localUnreadFloorTime = 300,
                        ),
                    )
                val msg =
                    IrcEvent.ChatMessage(
                        ctx(msgid = origin, time = 1_000),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("alice"),
                        channel,
                        "me: still visible",
                        false,
                        null,
                    )
                when (origin) {
                    "history" -> processor.process(networkId, IrcEvent.HistoryBatch(channel, listOf(msg)))
                    "push" -> processor.processPush(networkId, msg)
                    "replay" -> processor.process(networkId, IrcEvent.ReplayBatch(channel, listOf(msg)))
                    else -> processor.process(networkId, msg)
                }

                dao.setEnabled(ruleId, false)
                cache.invalidate(networkId)
                processor.process(networkId, msg)
                val row = pagingList(roomId).single()
                assertEquals(msg.text, row.text)
                assertEquals(SUPPRESS_UNREAD, row.suppressedSignals)
                assertTrue(row.hasMention)
                val archived = requireNotNull(db.bufferDao().observeById(roomId))
                assertTrue(archived.archived)
                assertEquals(100L, archived.readMarkerTime)
                assertEquals(200L, archived.localReadAnchorTime)
                assertNull(archived.localReadAnchorEventId)
                assertEquals(300L, archived.localUnreadFloorTime)

                processor.processPush(networkId, msg.copy(ctx = ctx(msgid = "$origin-new", time = 800), text = "new"))
                assertFalse(requireNotNull(db.bufferDao().observeById(roomId)).archived)
            }
        }

    @Test
    fun `only live peer chat revives an unmuted archive`() =
        runTest {
            val room =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#archive", displayName = "#archive", type = BufferType.CHANNEL, archived = true),
                )

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "live-peer"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#archive",
                    text = "live",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            assertFalse(db.bufferDao().observeById(room)!!.archived)
        }

    @Test
    fun `new history catch-up peer chat revives an unmuted archive`() =
        runTest {
            val room =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#archive", displayName = "#archive", type = BufferType.CHANNEL, archived = true),
                )

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#archive",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx = ctx(msgid = "caught-up", time = 2000).copy(batchId = "history"),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("alice"),
                            target = "#archive",
                            text = "new while away",
                            isSelf = false,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )

            assertFalse(db.bufferDao().observeById(room)!!.archived)
        }

    @Test
    fun `push-delivered new peer chat revives an unmuted archive`() =
        runTest {
            val room =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#archive", displayName = "#archive", type = BufferType.CHANNEL, archived = true),
                )

            processor.processPush(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "pushed", time = 2000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#archive",
                    text = "pushed while away",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            assertFalse(db.bufferDao().observeById(room)!!.archived)
        }

    @Test
    fun `backfill self and muted chat retain archive`() =
        runTest {
            // Seed a newest already-read message, then archive: replayed older or duplicate
            // history must not resurrect the chat.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "seen", time = 5000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#archive",
                    text = "seen",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val room = db.bufferDao().byName(networkId, "#archive")!!.id
            val seen = db.messageDao().byMsgid(room, "seen")!!
            db.bufferDao().advanceLocalReadAnchor(room, seen.serverTime, seen.id)
            db.bufferDao().setArchived(room, true)

            suspend fun archived() = db.bufferDao().observeById(room)!!.archived

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#archive",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx = ctx(msgid = "backfill", time = 1000).copy(batchId = "history"),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("alice"),
                            target = "#archive",
                            text = "old",
                            isSelf = false,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )
            assertTrue(archived())

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "self", time = 6000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#archive",
                    text = "mine",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            assertTrue(archived())

            db.bufferDao().setMuted(room, true)
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "muted-live", time = 7000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#archive",
                    text = "still hidden",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            assertTrue(archived())

            db.bufferDao().setMuted(room, false)
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#archive",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx = ctx(msgid = "read-elsewhere", time = 5000).copy(batchId = "history2"),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("alice"),
                            target = "#archive",
                            text = "duplicate-time replay",
                            isSelf = false,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )
            assertTrue(archived())
        }

    @Test
    fun advertisedCustomChantypesAreAuthoritativeForRoomRouting() =
        runTest {
            processor.onRegistered(
                networkId,
                "me",
                mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to "+!"),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "custom-channel"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "+room",
                    text = "channel",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "hash-query"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("bob"),
                    target = "#room",
                    text = "query",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            assertEquals(BufferType.CHANNEL, db.bufferDao().byName(networkId, "+room")?.type)
            assertEquals(BufferType.QUERY, db.bufferDao().byName(networkId, "bob")?.type)
            assertNull(db.bufferDao().byName(networkId, "#room"))
        }

    @Test
    fun explicitlyEmptyChantypesRoutesHashTargetAsQueryWithoutSplittingOldRooms() =
        runTest {
            val legacyId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#legacy",
                        displayName = "#legacy",
                        type = BufferType.CHANNEL,
                    ),
                )
            processor.onRegistered(
                networkId,
                "me",
                mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to ""),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "empty-chantypes"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#legacy",
                    text = "query now",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            assertEquals(BufferType.CHANNEL, db.bufferDao().observeById(legacyId)?.type)
            assertEquals(BufferType.QUERY, db.bufferDao().byName(networkId, "alice")?.type)
        }

    @Test
    fun registrationStoresOnlyValidHttpsNetworkIcons() =
        runTest {
            processor.onRegistered(
                networkId,
                "me",
                mapOf("draft/ICON" to "https://example.org/icon-{size}.png"),
            )
            assertEquals("https://example.org/icon-{size}.png", db.networkDao().byId(networkId)?.serverIconUrl)

            processor.onRegistered(networkId, "me", mapOf("draft/ICON" to "http://example.org/icon.png"))
            assertNull(db.networkDao().byId(networkId)?.serverIconUrl)
        }

    @Test
    fun registrationBeforeIsupportKeepsLastKnownIdentityRules() =
        runTest {
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "ascii", "CHANTYPES" to "#"))

            // 001 completes registration before any 005 has arrived.
            processor.onRegistered(networkId, "renamed", emptyMap())
            val welcomed = db.networkIdentityDao().byNetwork(networkId)!!
            assertEquals("ascii", welcomed.caseMapping)
            assertEquals("#", welcomed.chanTypes)
            assertEquals("renamed", welcomed.selfNick)

            // 005 can span lines: each one replaces only the keys it advertises.
            processor.onRegistered(networkId, "renamed", mapOf("CASEMAPPING" to "rfc1459"))
            val partial = db.networkIdentityDao().byNetwork(networkId)!!
            assertEquals("rfc1459", partial.caseMapping)
            assertEquals("#", partial.chanTypes)

            // An advertised empty value is authoritative, unlike an absent key.
            processor.onRegistered(networkId, "renamed", mapOf("CHANTYPES" to ""))
            assertEquals("", db.networkIdentityDao().byNetwork(networkId)!!.chanTypes)
        }

    @Test
    fun explicitIdentityRemovalsClearPersistedFallbacksAndRestoreDefaultRouting() =
        runTest {
            val advertised =
                IrcEvent.Registered(
                    "me",
                    emptySet(),
                    mapOf("CASEMAPPING" to "ascii", "CHANTYPES" to "+!"),
                )
            processor.process(networkId, advertised)
            processor.process(
                networkId,
                advertised.copy(
                    isupport = mapOf("CASEMAPPING" to "ascii"),
                    removedIsupport = setOf("CHANTYPES"),
                ),
            )
            val partial = db.networkIdentityDao().byNetwork(networkId)!!
            assertNull(partial.chanTypes)
            assertEquals("ascii", partial.caseMapping)
            val message =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "removed-chantypes"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("Alice["),
                    target = "#room",
                    text = "default channel",
                    isSelf = false,
                    replyToMsgid = null,
                )
            processor.process(networkId, message)
            val channel = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(BufferType.CHANNEL, channel.type)
            assertEquals("alice[", pagingList(channel.id).single().normalizedActor)
            assertNull(db.bufferDao().byName(networkId, "alice["))

            processor.process(
                networkId,
                advertised.copy(isupport = emptyMap(), removedIsupport = setOf("CASEMAPPING", "CHANTYPES")),
            )
            val reset = db.networkIdentityDao().byNetwork(networkId)!!
            assertNull(reset.caseMapping)
            assertNull(reset.chanTypes)
            assertEquals("nick{|}^", reset.identityRules.normalize("Nick[\\]~"))
            processor.process(
                networkId,
                message.copy(ctx = ctx(msgid = "removed-casemapping"), target = "#after["),
            )
            val foldedChannel = db.bufferDao().byName(networkId, "#after{")!!
            assertEquals(BufferType.CHANNEL, foldedChannel.type)
            assertEquals("alice{", pagingList(foldedChannel.id).single().normalizedActor)

            // A later partial 005 and process restart must not resurrect either old fallback.
            processor.process(networkId, advertised.copy(isupport = mapOf("NETWORK" to "Test")))
            val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            restarted.processPush(
                networkId,
                message.copy(ctx = ctx(msgid = "removed-after-restart"), target = "#restored["),
            )
            val restored = db.bufferDao().byName(networkId, "#restored{")!!
            assertEquals(BufferType.CHANNEL, restored.type)
            assertEquals("alice{", pagingList(restored.id).single().normalizedActor)
        }

    @Test
    fun firstRegistrationBeforeIsupportUsesProtocolDefaults() =
        runTest {
            val fresh =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "fresh",
                        role = NetworkRole.DIRECT,
                        host = "irc.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(fresh, "me", emptyMap())
            val identity = db.networkIdentityDao().byNetwork(fresh)!!
            assertNull(identity.caseMapping)
            assertNull(identity.chanTypes)
        }

    @Test
    fun customChantypesRestoreAfterProcessDeathForPushRouting() =
        runTest {
            processor.onRegistered(
                networkId,
                "me",
                mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to "+!"),
            )
            val persisted = db.networkIdentityDao().byNetwork(networkId)!!
            assertEquals("rfc1459", persisted.caseMapping)
            assertEquals("+!", persisted.chanTypes)
            assertEquals("me", persisted.selfNick)

            val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            restarted.processPush(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "custom-after-restart"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "+room",
                    text = "channel after restart",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            assertEquals(BufferType.CHANNEL, db.bufferDao().byName(networkId, "+room")?.type)
        }

    @Test
    fun mapperLimitedPushedOutgoingPmUsesPersistedCurrentSelfNick() =
        runTest {
            processor.onRegistered(networkId, "OldSelf", mapOf("CASEMAPPING" to "rfc1459"))
            assertEquals("me", db.networkDao().byId(networkId)?.nick)
            assertEquals("OldSelf", db.networkIdentityDao().byNetwork(networkId)?.selfNick)
            processor.process(
                networkId,
                IrcEvent.NickChanged(
                    ctx = ctx(msgid = "self-nick"),
                    from = "OldSelf",
                    to = "Me[",
                    isSelf = false,
                ),
            )
            assertEquals("me", db.networkDao().byId(networkId)?.nick)
            assertEquals("Me[", db.networkIdentityDao().byNetwork(networkId)?.selfNick)

            var notifications = 0
            val restarted =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notifications++
                        }
                    },
                )
            val pushed =
                PushEventHandler.mapToEvent(
                    IrcMessage.parse(":ME{!user@host PRIVMSG Bob :pushed outgoing"),
                ) as IrcEvent.ChatMessage
            assertFalse(pushed.isSelf)

            restarted.processPush(networkId, pushed)

            val peer = db.bufferDao().byName(networkId, "bob")!!
            val row = pagingList(peer.id).single()
            assertTrue(row.isSelf)
            assertEquals("pushed outgoing", row.text)
            assertNull(db.bufferDao().byName(networkId, "me{"))
            assertEquals(0, notifications)
        }

    @Test
    fun missingPersistedSelfNickFallsBackToConfiguredNickForPush() =
        runTest {
            db.networkIdentityDao().upsert(
                io.github.trevarj.motd.data.db.NetworkIdentityEntity(
                    networkId = networkId,
                    caseMapping = "rfc1459",
                    chanTypes = "",
                    selfNick = null,
                ),
            )
            val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            val pushed =
                PushEventHandler.mapToEvent(
                    IrcMessage.parse(":ME!user@host PRIVMSG Bob :configured fallback"),
                ) as IrcEvent.ChatMessage

            restarted.processPush(networkId, pushed)

            val peer =
                db.roomAliasDao().byValue(
                    networkId,
                    RoomAliasNamespace.PROVISIONAL_NICK,
                    "bob",
                )!!
            assertTrue(pagingList(peer.roomId).single().isSelf)
            assertEquals("me", db.networkDao().byId(networkId)?.nick)
            assertNull(db.networkIdentityDao().byNetwork(networkId)?.selfNick)
        }

    @Test
    fun emptyChantypesRestoreForPushAndPreserveLegacyChannelRoom() =
        runTest {
            val legacyId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#peer",
                        displayName = "#peer",
                        type = BufferType.CHANNEL,
                    ),
                )
            processor.onRegistered(
                networkId,
                "me",
                mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to ""),
            )

            val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            restarted.processPush(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "empty-after-restart"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#peer",
                    text = "query after restart",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )

            assertEquals("", db.networkIdentityDao().byNetwork(networkId)?.chanTypes)
            assertEquals(BufferType.CHANNEL, db.bufferDao().rawById(legacyId)?.type)
            val queryAlias =
                db.roomAliasDao().byValue(
                    networkId,
                    RoomAliasNamespace.PROVISIONAL_NICK,
                    "#peer",
                )!!
            val query = db.bufferDao().observeById(queryAlias.roomId)!!
            assertEquals(BufferType.QUERY, query.type)
            assertNotEquals(legacyId, query.id)
            assertEquals("empty-after-restart", pagingList(query.id).single().msgid)
        }

    @Test
    fun restoredUnknownCasemappingUsesAsciiAndEmitsDiagnostic() =
        runTest {
            processor.onRegistered(
                networkId,
                "me",
                mapOf("CASEMAPPING" to "vendor-unicode", "CHANTYPES" to "+"),
            )
            val diagnostics = RecordingDiagnostics()
            val restarted =
                EventProcessor(
                    db = db,
                    typing = TypingTrackerImpl(),
                    notifier = MessageNotifier.Noop,
                    diagnostics = diagnostics,
                )
            listOf("+[room]", "+{room}").forEachIndexed { index, target ->
                restarted.processPush(
                    networkId,
                    IrcEvent.ChatMessage(
                        ctx = ctx(msgid = "unknown-$index", time = 1000L + index),
                        kind = IrcEvent.ChatKind.PRIVMSG,
                        source = Prefix("alice"),
                        target = target,
                        text = target,
                        isSelf = false,
                        replyToMsgid = null,
                    ),
                )
            }

            val persisted = db.networkIdentityDao().byNetwork(networkId)!!
            assertEquals("vendor-unicode", persisted.caseMapping)
            assertNotEquals(
                db.bufferDao().byName(networkId, "+[room]")?.id,
                db.bufferDao().byName(networkId, "+{room}")?.id,
            )
            assertTrue(diagnostics.events.any { (event, _) -> event == "unsupported_casemapping" })
        }

    @Test
    fun strictRfc1459KeepsTildeAndCaretRoomsDistinctForFutureEvents() =
        runTest {
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459-strict"))
            listOf("#room~", "#room^").forEachIndexed { index, target ->
                processor.process(
                    networkId,
                    IrcEvent.ChatMessage(
                        ctx = ctx(msgid = "strict-room-$index", time = 1000L + index),
                        kind = IrcEvent.ChatKind.PRIVMSG,
                        source = Prefix("alice"),
                        target = target,
                        text = target,
                        isSelf = false,
                        replyToMsgid = null,
                    ),
                )
            }

            val tilde = db.bufferDao().byName(networkId, "#room~")
            val caret = db.bufferDao().byName(networkId, "#room^")
            assertNotNull(tilde)
            assertNotNull(caret)
            assertNotEquals(tilde?.id, caret?.id)
        }

    @Test
    fun strictReactionActorsRemainDistinctAfterRestart() =
        runTest {
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459-strict"))
            val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            restarted.processPush(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "Opaque-Target"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            listOf("bot~", "bot^").forEachIndexed { index, actor ->
                restarted.processPush(
                    networkId,
                    IrcEvent.TagMessage(
                        ctx = ctx(time = 2000L + index),
                        source = Prefix(actor),
                        target = "#chan",
                        typing = null,
                        reactEmoji = "+1",
                        reactTargetMsgid = "Opaque-Target",
                    ),
                )
            }
            restarted.processPush(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 2002),
                    source = Prefix("BOT~"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "+1",
                    reactTargetMsgid = "Opaque-Target",
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val reactions = db.reactionDao().observeFor(buffer.id, listOf("Opaque-Target")).first()
            assertEquals(setOf("nick:bot~", "nick:bot^"), reactions.map { it.actorKey }.toSet())
            assertTrue(reactions.all { it.targetMsgid == "Opaque-Target" })
            assertEquals(
                "rfc1459-strict",
                db
                    .networkIdentityDao()
                    .byNetwork(networkId)
                    ?.identityRules
                    ?.caseMapping
                    ?.rawName,
            )
        }

    @Test
    fun nickChangeRekeysCachedAccountForOwnReactionIdentity() =
        runTest {
            processor.process(networkId, IrcEvent.AccountChanged("me", "stable-self-account"))
            processor.process(
                networkId,
                IrcEvent.NickChanged(ctx(msgid = "nick-rekey"), "me", "NewMe", isSelf = true),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "reaction-parent", time = 2_000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 2_001),
                    source = Prefix("NewMe"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "+1",
                    reactTargetMsgid = "reaction-parent",
                ),
            )

            assertNull(db.userDao().byNick(networkId, "me"))
            assertEquals("stable-self-account", db.userDao().byNick(networkId, "newme")?.account)
            val room = db.bufferDao().byName(networkId, "#chan")!!
            assertEquals(
                setOf("account:stable-self-account"),
                db
                    .reactionDao()
                    .observeFor(room.id, listOf("reaction-parent"))
                    .first()
                    .mapTo(mutableSetOf()) { it.actorKey },
            )
        }

    @Test
    fun topicSnapshot_updatesChannelTopic_withoutAddingTimelineEntry() =
        runTest {
            processor.process(networkId, IrcEvent.TopicSnapshot("#Room", "Welcome to the room"))

            val buffer = db.bufferDao().byName(networkId, "#room")!!
            assertEquals("Welcome to the room", buffer.topic)
            assertNull(buffer.topicSetBy)
            assertTrue(pagingList(buffer.id).isEmpty())

            processor.process(networkId, IrcEvent.TopicSnapshot("#Room", ""))

            assertEquals("", db.bufferDao().byName(networkId, "#room")!!.topic)
            assertTrue(pagingList(buffer.id).isEmpty())
        }

    @Test
    fun chatMessage_dm_autoCreatesQueryBuffer_keyedBySender() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m2"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("bob"),
                    target = "me",
                    text = "hi",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "bob")
            assertNotNull(buffer)
            assertEquals(BufferType.QUERY, buffer!!.type)
        }

    @Test
    fun privateMessageWithChannelContextRoutesIntoAnExistingChannelAcrossDeliveries() =
        runTest {
            processor.onRegistered(networkId, "7me", emptyMap())
            processor.process(networkId, IrcEvent.TopicSnapshot("#[Help]", "Help"))
            val notice =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "context-live").copy(clientTags = mapOf("+channel-context" to "#{HELP}")),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("7héłper"),
                    target = "7me",
                    text = "private help",
                    isSelf = false,
                    replyToMsgid = null,
                )
            processor.process(networkId, notice)
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "7héłper",
                    listOf(
                        notice.copy(
                            ctx = ctx(msgid = "context-history", time = 900).copy(clientTags = mapOf("+draft/channel-context" to "#{help}")),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("OldMe"),
                            target = "7héłper",
                        ),
                    ),
                ),
            )
            processor.processPush(
                networkId,
                checkNotNull(
                    PushEventHandler.mapToEvent(
                        IrcMessage.parse("@msgid=context-push;+channel-context=#{help} :7héłper!bot@libera.chat NOTICE 7me :push help"),
                    ),
                ),
            )

            val channel = db.bufferDao().byName(networkId, "#{help}")!!
            val rows = pagingList(channel.id).associateBy { it.msgid }
            assertEquals(mapOf("context-live" to false, "context-history" to true, "context-push" to false), rows.mapValues { it.value.isSelf })
            assertNull(db.bufferDao().byName(networkId, "7héłper"))
        }

    @Test
    fun channelContextDoesNotOverrideNonPrivateMessagesOrCreateChannels() =
        runTest {
            processor.onRegistered(networkId, "me", mapOf("PREFIX" to "(ov)@+", "STATUSMSG" to "@+"))
            processor.process(networkId, IrcEvent.TopicSnapshot("#help", "Help"))
            val notice =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "private-fallback").copy(clientTags = mapOf("+channel-context" to "#missing")),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("helperbot"),
                    target = "me",
                    text = "private help",
                    isSelf = false,
                    replyToMsgid = null,
                )
            processor.process(networkId, notice)
            val query = db.bufferDao().byName(networkId, "helperbot")!!
            assertEquals("private-fallback", pagingList(query.id).single().msgid)
            assertNull(db.bufferDao().byName(networkId, "#missing"))

            for (target in listOf("#public", "@+#public", "me,someone", "me@libera.chat", "\$*.example", "")) {
                processor.process(
                    networkId,
                    notice.copy(
                        ctx = ctx(msgid = "target-$target").copy(clientTags = mapOf("+channel-context" to "#help")),
                        target = target,
                        text = target,
                    ),
                )
            }
            processor.process(
                networkId,
                notice.copy(
                    ctx = ctx(msgid = "server-notice").copy(clientTags = mapOf("+channel-context" to "#help")),
                    source = Prefix("irc.libera.chat"),
                ),
            )

            val channel = db.bufferDao().byName(networkId, "#help")!!
            assertTrue(pagingList(channel.id).isEmpty())
            val publicChannel = db.bufferDao().byName(networkId, "#public")!!
            assertEquals(setOf("target-#public", "target-@+#public"), pagingList(publicChannel.id).map { it.msgid }.toSet())
            assertTrue(pagingList(query.id).none { it.msgid == "target-@+#public" })
            assertEquals("server-notice", pagingList(db.bufferDao().byName(networkId, "*")!!.id).single().msgid)
        }

    @Test
    fun outgoingPrivateChannelContextEchoRetainsPendingLabelAlias() =
        runTest {
            processor.process(networkId, IrcEvent.TopicSnapshot("#help", "Help"))
            val queryId = selfBuffer("helperbot")
            processor.insertPending(queryId, "context-label", "me", "request", null, MessageKind.PRIVMSG)
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "context-echo", label = "context-label").copy(clientTags = mapOf("+channel-context" to "#help")),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "helperbot",
                    text = "request",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val row = pagingList(queryId).single { it.text == "request" }
            assertEquals("context-echo", row.msgid)
            assertNull(row.pendingLabel)
            assertTrue(pagingList(db.bufferDao().byName(networkId, "#help")!!.id).isEmpty())
        }

    @Test
    fun dismissedQueryDropsOldHistoryAndRevivesForNewDm() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("m-old", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "discard me",
                    false,
                    null,
                ),
            )
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "bob",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx("m-older", 90).copy(batchId = "history"),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("bob"),
                            "me",
                            "older",
                            false,
                            null,
                        ),
                        IrcEvent.ChatMessage(
                            ctx("m-old", 100).copy(batchId = "history"),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("bob"),
                            "me",
                            "discard me",
                            false,
                            null,
                        ),
                        IrcEvent.ChatMessage(
                            ctx("m-new", 101).copy(batchId = "history"),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("bob"),
                            "me",
                            "new dm",
                            false,
                            null,
                        ),
                    ),
                ),
            )

            assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals(listOf("m-new"), pagingList(query.id).mapNotNull { it.msgid })
        }

    @Test
    fun dismissedQueryDropsReconnectReplayAtDiscardBoundaryAndRevivesForNewerDm() =
        runTest {
            val old =
                IrcEvent.ChatMessage(
                    ctx(msgid = null, time = 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "discard me",
                    false,
                    null,
                )
            processor.process(networkId, old)
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.process(
                networkId,
                IrcEvent.ReplayBatch(
                    "bob",
                    listOf(old.copy(ctx = old.ctx.copy(batchId = "reconnect-playback"))),
                ),
            )

            assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
            assertTrue(pagingList(query.id).isEmpty())

            processor.process(
                networkId,
                IrcEvent.ReplayBatch(
                    "bob",
                    listOf(
                        old.copy(
                            ctx = ctx(msgid = null, time = 101).copy(batchId = "reconnect-playback"),
                            text = "new dm",
                        ),
                    ),
                ),
            )

            assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals(listOf("new dm"), pagingList(query.id).map { it.text })
        }

    @Test
    fun dismissedQueryConservativelyRevivesForEqualTimeDifferentMsgid() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("m-old", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "old",
                    false,
                    null,
                ),
            )
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "bob",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx("m-same-time", 100).copy(batchId = "history"),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("bob"),
                            "me",
                            "possibly new",
                            false,
                            null,
                        ),
                    ),
                ),
            )

            assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals("m-same-time", pagingList(query.id).single().msgid)
        }

    @Test
    fun dismissedQueryRejectsEveryExactMsgidAtTimestampTie() =
        runTest {
            listOf("m-tie-a", "m-tie-b").forEach { msgid ->
                processor.process(
                    networkId,
                    IrcEvent.ChatMessage(
                        ctx(msgid, 100),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("bob"),
                        "me",
                        msgid,
                        false,
                        null,
                    ),
                )
            }
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "bob",
                    listOf("m-tie-a", "m-tie-b").map { msgid ->
                        IrcEvent.ChatMessage(
                            ctx(msgid, 100).copy(batchId = "history"),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("bob"),
                            "me",
                            msgid,
                            false,
                            null,
                        )
                    },
                ),
            )

            assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
            assertTrue(pagingList(query.id).isEmpty())
        }

    @Test
    fun revivedQueryRejectsDiscardBoundaryReplayThatLostItsMsgid() =
        runTest {
            val old =
                IrcEvent.ChatMessage(
                    ctx("m-old", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "old",
                    false,
                    null,
                )
            processor.process(networkId, old)
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.process(
                networkId,
                old.copy(ctx = ctx("m-new", 200), text = "new"),
            )
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "bob",
                    listOf(old.copy(ctx = ctx(null, 100).copy(batchId = "history"))),
                ),
            )

            assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals(listOf("new"), pagingList(query.id).map { it.text })
        }

    @Test
    fun dismissedQueryAlwaysRevivesForPushDespiteServerClockSkew() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("m-old", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "old",
                    false,
                    null,
                ),
            )
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.processPush(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("m-push", 50),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "clock-skewed new dm",
                    false,
                    null,
                ),
            )

            assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals("m-push", pagingList(query.id).single().msgid)
            assertEquals(
                1,
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single {
                        it.bufferId == query.id
                    }.unreadCount,
            )
        }

    @Test
    fun dismissedQueryIgnoresExactDelayedPushDuplicate() =
        runTest {
            val message =
                IrcEvent.ChatMessage(
                    ctx("m-old", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "old",
                    false,
                    null,
                )
            processor.process(networkId, message)
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.processPush(networkId, message.copy(ctx = ctx("m-old", 150)))

            assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
            assertTrue(pagingList(query.id).isEmpty())
        }

    @Test
    fun outgoingMessageRevivesDismissedQueryBeforePersisting() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("old", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "old",
                    false,
                    null,
                ),
            )
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            processor.insertPending(
                query.id,
                "send-after-delete",
                "me",
                "new outgoing",
                replyToMsgid = null,
                kind = MessageKind.PRIVMSG,
            )

            assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals("new outgoing", pagingList(query.id).single().text)
        }

    @Test
    fun dismissedAccountQueryAppliesFloorAfterHistoricalNickRerouting() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("account-old", 1_000, account = "acct-a"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "me",
                    "old",
                    false,
                    null,
                ),
            )
            val query = db.bufferDao().byName(networkId, "alice")!!
            db.bufferDao().deleteBuffer(query.id)

            fun history(
                msgid: String,
                time: Long,
            ) = IrcEvent.ChatMessage(
                ctx(msgid, time, account = "acct-a").copy(batchId = "history"),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                msgid,
                false,
                null,
            )

            processor.process(networkId, IrcEvent.HistoryBatch("bob", listOf(history("older", 900))))

            assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
            assertTrue(pagingList(query.id).isEmpty())
            assertNull(db.bufferDao().byName(networkId, "bob"))

            processor.process(networkId, IrcEvent.HistoryBatch("bob", listOf(history("newer", 1_100))))

            assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals(listOf("newer"), pagingList(query.id).mapNotNull { it.msgid })
        }

    @Test
    fun liveTypingTracksPeersButIgnoresSelfUsingServerCaseMapping() =
        runTest {
            val typing = TypingTrackerImpl()
            val recording = EventProcessor(db, typing, MessageNotifier.Noop)
            recording.onRegistered(networkId, "Me[", mapOf("CASEMAPPING" to "rfc1459"))

            recording.process(
                networkId,
                IrcEvent.TagMessage(ctx(), Prefix("Alice"), "#chan", "active", null, null),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertEquals(listOf("Alice"), typing.typingNicks(buffer.id).value)

            recording.process(
                networkId,
                IrcEvent.TagMessage(ctx(), Prefix("ME{"), "#chan", "active", null, null),
            )

            assertEquals(listOf("Alice"), typing.typingNicks(buffer.id).value)
        }

    @Test
    fun liveChatClearsOnlyItsCasemappedTypistAndHistoryPushAndSelfDoNot() =
        runTest {
            // Room uses real executors; waiting for it can advance runTest's background timers.
            val typingScheduler = TestCoroutineScheduler()
            val typingScope = CoroutineScope(backgroundScope.coroutineContext + typingScheduler + StandardTestDispatcher(typingScheduler))
            val typing = TypingTrackerImpl(typingScope, AppClock { typingScheduler.currentTime })
            val recording = EventProcessor(db, typing, MessageNotifier.Noop)
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            for (kind in listOf(IrcEvent.ChatKind.PRIVMSG, IrcEvent.ChatKind.ACTION, IrcEvent.ChatKind.NOTICE)) {
                val target = "#${kind.name}"
                for (nick in listOf("Alice[", "Bob")) {
                    recording.process(networkId, IrcEvent.TagMessage(ctx(), Prefix(nick), target, "active", null, null))
                }
                val room = db.bufferDao().byName(networkId, target.lowercase())!!
                val peers = listOf("Alice[", "Bob")
                assertEquals("$kind TAGMSG", peers, typing.typingNicks(room.id).value)
                val message = IrcEvent.ChatMessage(ctx("live-${kind.name}"), kind, Prefix("ALICE{"), target, "sent", false, null)
                recording.process(networkId, IrcEvent.HistoryBatch(target, listOf(message.copy(ctx = ctx("history-${kind.name}")))))
                assertEquals("$kind history", peers, typing.typingNicks(room.id).value)
                recording.processPush(networkId, message.copy(ctx = ctx("push-${kind.name}")))
                assertEquals("$kind push", peers, typing.typingNicks(room.id).value)
                recording.process(networkId, message.copy(ctx = ctx("self-${kind.name}"), source = Prefix("me"), isSelf = true))
                assertEquals("$kind self", peers, typing.typingNicks(room.id).value)
                recording.process(networkId, message)
                assertEquals(listOf("Bob"), typing.typingNicks(room.id).value)
                recording.process(networkId, IrcEvent.TagMessage(ctx(), Prefix("ALICE{"), target, "paused", null, null))
                recording.process(networkId, message.copy(ctx = ctx("second-${kind.name}")))
                assertEquals(listOf("Bob"), typing.typingNicks(room.id).value)
            }
        }

    @Test
    fun typingMessageRemovalHonorsStrictCasemapAndKeepsOtherTypistsOriginalExpiry() =
        runTest {
            val typing =
                TypingTrackerImpl(
                    backgroundScope,
                    io.github.trevarj.motd.di
                        .AppClock { testScheduler.currentTime },
                )
            val strict =
                io.github.trevarj.motd.irc.proto.IrcIdentityRules
                    .from("rfc1459-strict", null)
            typing.onTyping(1, "Alice~", "active", identityRules = strict)
            typing.onTyping(1, "Bob", "active", identityRules = strict)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(3_000)
            typing.onMessage(1, "ALICE^", strict)
            assertEquals(listOf("Alice~", "Bob"), typing.typingNicks(1).value)
            typing.onMessage(1, "ALICE~", strict)
            assertEquals(listOf("Bob"), typing.typingNicks(1).value)
            typing.onTyping(1, "BOB", "paused", identityRules = strict)
            assertEquals(listOf("BOB"), typing.typingNicks(1).value)
            typing.onTyping(2, "Carol", "active", identityRules = strict)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(3_000)
            typing.onMessage(2, "CAROL", strict)
            assertEquals(listOf("BOB"), typing.typingNicks(1).value)
            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()
            assertEquals(emptyList<String>(), typing.typingNicks(1).value)
        }

    @Test
    fun highlightProvenanceSurvivesNickCasemapChangesAndReplayButHistoryIsUnknown() =
        runTest {
            processor.onRegistered(networkId, "Me[", mapOf("CASEMAPPING" to "rfc1459"))
            val message = IrcEvent.ChatMessage(ctx("highlight"), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#chan", "\u0002Me{\u0002!", false, null)
            processor.process(networkId, message)
            val room = db.bufferDao().byName(networkId, "#chan")!!
            val initial = db.messageDao().byMsgid(room.id, "highlight")!!
            assertEquals("Me[", initial.highlightNick)
            assertEquals("rfc1459", initial.highlightCaseMapping)
            assertEquals("Me{!", initial.text)
            processor.onRegistered(networkId, "newnick", mapOf("CASEMAPPING" to "ascii"))
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(message)))
            val replayed = db.messageDao().byMsgid(room.id, "highlight")!!
            assertEquals(initial.highlightNick, replayed.highlightNick)
            assertEquals(initial.highlightCaseMapping, replayed.highlightCaseMapping)
            val old = message.copy(ctx = ctx("unknown-history"), text = "newnick!")
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(old)))
            val historical = db.messageDao().byMsgid(room.id, "unknown-history")!!
            assertTrue(historical.hasMention)
            assertNull(historical.highlightNick)
            assertNull(historical.highlightCaseMapping)
            processor.process(networkId, IrcEvent.MessageRedacted(ctx(), Prefix("oper"), "#chan", "highlight", null))
            processor.process(networkId, message)
            val redacted = db.messageDao().byMsgid(room.id, "highlight")!!
            assertNull(redacted.highlightNick)
            assertNull(redacted.highlightCaseMapping)
        }

    @Test
    fun selfPmEchoAccountTagDoesNotIdentifyDifferentRecipientsAsOneRoom() =
        runTest {
            suspend fun echo(
                target: String,
                msgid: String,
            ) {
                processor.process(
                    networkId,
                    IrcEvent.ChatMessage(
                        ctx(msgid = msgid).copy(account = "my-account"),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("me"),
                        target,
                        "hello $target",
                        true,
                        null,
                    ),
                )
            }

            echo("Alice", "self-alice")
            echo("Bob", "self-bob")

            val alice = db.bufferDao().byName(networkId, "alice")!!
            val bob = db.bufferDao().byName(networkId, "bob")!!
            assertNotEquals(alice.id, bob.id)
            assertEquals("my-account", pagingList(alice.id).single().senderAccount)
            assertEquals("my-account", pagingList(bob.id).single().senderAccount)
        }

    @Test
    fun historicalOutgoingPmFromOldSelfNickUsesBatchPeerRoom() =
        runTest {
            processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
            val historical =
                IrcEvent.ChatMessage(
                    ctx(msgid = "old-self-history", time = 4_000).copy(
                        account = "my-account",
                        batchId = "bob-history",
                    ),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("oldMe"),
                    "Bob",
                    "sent before nick change",
                    false,
                    null,
                )

            processor.process(networkId, IrcEvent.HistoryBatch("Bob", listOf(historical)))

            val bob = db.bufferDao().byName(networkId, "bob")!!
            val row = pagingList(bob.id).single()
            assertTrue(row.isSelf)
            assertEquals("my-account", row.senderAccount)
            assertNull(db.bufferDao().byName(networkId, "oldme"))
        }

    @Test
    fun historicalIncomingPmFromPeersOldNickRemainsIncomingInBatchPeerRoom() =
        runTest {
            processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
            val historical =
                IrcEvent.ChatMessage(
                    ctx(msgid = "old-peer-history", time = 4_100).copy(
                        account = "bob-account",
                        batchId = "bob-history",
                    ),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("OldBob"),
                    "oldMe",
                    "sent to my previous nick",
                    false,
                    null,
                )

            processor.process(networkId, IrcEvent.HistoryBatch("Bob", listOf(historical)))

            val bob = db.bufferDao().byName(networkId, "bob")!!
            val row = pagingList(bob.id).single()
            assertFalse(row.isSelf)
            assertEquals("bob-account", row.senderAccount)
            assertEquals(
                bob.id,
                BufferStore(db).resolveQueryRoom(networkId, "bob", "bob-account")?.id,
            )
            assertNull(db.bufferDao().byName(networkId, "oldbob"))
        }

    @Test
    fun historicalIncomingPmIgnoresCurrentNickCollisionInMapperSelfFlag() =
        runTest {
            processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
            val historical =
                IrcEvent.ChatMessage(
                    ctx(msgid = "peer-used-current-self-nick", time = 4_200).copy(
                        account = "bob-account",
                        batchId = "bob-history",
                    ),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("newMe"),
                    "oldMe",
                    "the peer used my current nick in the past",
                    true,
                    null,
                )

            processor.process(networkId, IrcEvent.HistoryBatch("Bob", listOf(historical)))

            val bob = db.bufferDao().byName(networkId, "bob")!!
            val row = pagingList(bob.id).single()
            assertFalse(row.isSelf)
            assertEquals("newMe", row.sender)
            assertEquals("bob-account", row.senderAccount)
            assertNull(db.bufferDao().byName(networkId, "oldme"))
        }

    @Test
    fun historicalQueryReplayRepairsIncorrectSelfAttribution() =
        runTest {
            val persistedSettings = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext())
            val rules =
                object : SettingsRepository by persistedSettings {
                    override val keywordHighlightRules = flowOf(listOf(KeywordHighlightRule("repair", alerts = true)))
                }
            val processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop, settingsRepository = rules)
            db.networkIgnoreDao().upsert(
                NetworkIgnoreEntity(
                    networkId = networkId,
                    pattern = "newMe!*@*",
                    createdAt = 1,
                    dropMessages = false,
                    suppressedSignals = SUPPRESS_HIGHLIGHT,
                ),
            )
            processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
            val incorrectlyAttributed =
                IrcEvent.ChatMessage(
                    ctx(msgid = "repair-query-direction", time = 4_300),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("newMe"),
                    "Bob",
                    "repair my direction",
                    true,
                    null,
                )
            processor.processPush(networkId, incorrectlyAttributed)
            val bob = db.bufferDao().byName(networkId, "bob")!!
            val self = pagingList(bob.id).single()
            assertTrue(self.isSelf)
            assertNull(self.keywordMatch)
            assertNull(self.suppressedSignals)

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "Bob",
                    listOf(
                        incorrectlyAttributed.copy(
                            ctx = incorrectlyAttributed.ctx.copy(batchId = "bob-history"),
                            target = "oldMe",
                        ),
                    ),
                ),
            )

            val repaired = pagingList(bob.id).single()
            assertFalse(repaired.isSelf)
            assertEquals(self.id, repaired.id)
            assertEquals(KeywordMatch.ALERT, repaired.keywordMatch)
            assertEquals(SUPPRESS_HIGHLIGHT, repaired.suppressedSignals)
            assertFalse(repaired.hasMention)
            assertEquals("repair-query-direction", repaired.msgid)
        }

    @Test
    fun historicalReplayFromQueryPeerCannotFlipIncomingLiveMessageToSelf() =
        runTest {
            processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
            val incoming =
                IrcEvent.ChatMessage(
                    ctx(msgid = "incoming-then-history", time = 4_400),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("Bob"),
                    "newMe",
                    "correct before opening the chat",
                    false,
                    null,
                )
            processor.process(networkId, incoming)
            val bob = db.bufferDao().byName(networkId, "bob")!!
            assertFalse(pagingList(bob.id).single().isSelf)

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "Bob",
                    listOf(
                        incoming.copy(
                            ctx = incoming.ctx.copy(batchId = "bob-history"),
                            // Some bouncers replay a query against its conversation target rather than
                            // retaining the self nick that appeared on the original wire message.
                            target = "Bob",
                        ),
                    ),
                ),
            )

            val replayed = pagingList(bob.id).single()
            assertFalse(replayed.isSelf)
            assertEquals("incoming-then-history", replayed.msgid)
        }

    @Test
    fun accountTaggedDmMergesExistingProvisionalNickRoomIntoKnownAccount() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx(msgid = "alice-account", time = 1_000).copy(account = "acct-a"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "me",
                    "from alice",
                    false,
                    null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx(msgid = "bob-provisional", time = 2_000),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "before identification",
                    false,
                    null,
                ),
            )
            val bobBefore = db.bufferDao().byName(networkId, "bob")!!

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx(msgid = "bob-account", time = 3_000).copy(account = "acct-a"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "after identification",
                    false,
                    null,
                ),
            )

            val accountRoom =
                checkNotNull(
                    BufferStore(db).resolveQueryRoom(networkId, "bob", "acct-a"),
                )
            assertEquals(accountRoom.id, db.bufferDao().observeById(bobBefore.id)?.id)
            assertEquals(3, pagingList(accountRoom.id).size)
            assertEquals(
                1,
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .size,
            )
        }

    @Test
    fun rfc1459EquivalentChannelTargets_shareOneBuffer() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "case-channel-1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#Room",
                    text = "first",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "case-channel-2", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#room",
                    text = "second",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val buffers =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .filter { it.displayName.contains("Room", ignoreCase = true) }
            assertEquals(1, buffers.size)
            assertEquals(2, pagingList(buffers.single().bufferId).size)
        }

    @Test
    fun liveAndHistoryRepresentations_withNickCaseChange_reconcileToOneRow() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "durable", time = 2000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("ALICE"),
                    target = "#chan",
                    text = "same line",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(time = 2001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "same line",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val rows = pagingList(buffer.id)
            assertEquals(1, rows.size)
            assertEquals("durable", rows.single().msgid)
        }

    @Test
    fun msgidPromotion_withNickCaseChange_reusesTheMsgidlessRow() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(time = 3000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("ALICE"),
                    target = "#chan",
                    text = "same line",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "promoted", time = 3000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "same line",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val rows = pagingList(buffer.id)
            assertEquals(1, rows.size)
            assertEquals("promoted", rows.single().msgid)
        }

    @Test
    fun mentionFlag_setWhenOwnNickAppears_wordBoundary() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m3"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "hey me, look here",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            // Substring-only should NOT match (e.g. "meeting").
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m4", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "meeting soon",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val rows = pagingList(buffer.id).sortedBy { it.serverTime }
            assertTrue(rows[0].hasMention)
            assertEquals("me", rows[0].highlightNick)
            assertEquals("rfc1459", rows[0].highlightCaseMapping)
            assertNull(rows[1].highlightNick)
            assertNull(rows[1].highlightCaseMapping)
            assertFalse(rows[1].hasMention)
        }

    @Test
    fun mentionRegex_rebuiltOnNickChange() =
        runTest {
            // Self renames me -> newnick.
            processor.process(networkId, IrcEvent.NickChanged(ctx(), from = "me", to = "newnick", isSelf = true))
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m5"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "ping newnick please",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertTrue(pagingList(buffer.id).single().hasMention)
        }

    @Test
    fun mentionMatchingUsesStrictRfc1459WithoutFoldingTilde() =
        runTest {
            processor.onRegistered(networkId, "me~", mapOf("CASEMAPPING" to "rfc1459-strict"))
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "strict-no"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "ping me^",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "strict-yes", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "ping ME~",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val rows = pagingList(db.bufferDao().byName(networkId, "#chan")!!.id)
            assertFalse(rows.first { it.msgid == "strict-no" }.hasMention)
            assertTrue(rows.first { it.msgid == "strict-yes" }.hasMention)
        }

    @Test
    fun replyToOwnKnownParent_setsMention_withoutTextMention() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "parent"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "parent text",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "child", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "plain child",
                    isSelf = false,
                    replyToMsgid = "parent",
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertTrue(pagingList(buffer.id).first { it.msgid == "child" }.hasMention)
            assertNull(pagingList(buffer.id).first { it.msgid == "child" }.highlightNick)
            assertNull(pagingList(buffer.id).first { it.msgid == "child" }.highlightCaseMapping)
        }

    @Test
    fun replyArrivingBeforeParentResolvesByIndexedRoomAndMsgidLookup() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "child"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "child",
                    isSelf = false,
                    replyToMsgid = "late-parent",
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertNull(db.messageDao().byMsgid(buffer.id, "child")?.replyToEventId)

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "late-parent", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("bob"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val parent = db.messageDao().byMsgid(buffer.id, "late-parent")!!
            assertEquals(parent.id, db.messageDao().byMsgid(buffer.id, "child")?.replyToEventId)
        }

    @Test
    fun replyToOwnKnownParent_notifiesExactlyOnce() =
        runTest {
            var notifications = 0
            val notifying =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notifications++
                            assertTrue(hasMention)
                        }
                    },
                )
            notifying.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            notifying.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "parent"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "parent",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            notifying.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "child", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "plain child",
                    isSelf = false,
                    replyToMsgid = "parent",
                ),
            )

            assertEquals(1, notifications)
        }

    @Test
    fun readerAdmitsOnlyCommittedNewLivePeerChatNeverHistoryPushDuplicateOrSelf() =
        runTest {
            db.networkIgnoreDao().upsert(NetworkIgnoreEntity(networkId = networkId, pattern = "ignored!*@*", createdAt = 1))
            val admitted = mutableListOf<MessageEntity>()
            val reading =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    MessageNotifier.Noop,
                    incomingMessageReader =
                        object : IncomingMessageReader {
                            override fun onIncoming(message: MessageEntity) {
                                admitted += message
                            }
                        },
                )
            reading.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

            fun chat(
                id: String,
                text: String = id,
                kind: IrcEvent.ChatKind = IrcEvent.ChatKind.PRIVMSG,
            ) = IrcEvent.ChatMessage(ctx(id), kind, Prefix("trev"), "#chan", text, false, null)
            val old = chat("old")
            reading.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(old)))
            reading.process(networkId, old)
            val pushed = chat("push")
            reading.processPush(networkId, pushed)
            reading.process(networkId, pushed)
            reading.process(networkId, IrcEvent.ReplayBatch("#chan", listOf(chat("replayed"))))
            reading.process(networkId, chat("self").copy(source = Prefix("me"), isSelf = true))
            reading.process(networkId, chat("notice", kind = IrcEvent.ChatKind.NOTICE))
            val live = chat("live", "\u0002hello\u0002 how are you")
            reading.process(networkId, live)
            reading.process(networkId, live)
            reading.process(networkId, live.copy(ctx = live.ctx.copy(account = "trev-account")))
            reading.process(networkId, chat("action", "waves", IrcEvent.ChatKind.ACTION))
            val dm = chat("dm", "hello").copy(target = "me")
            reading.process(networkId, dm)
            reading.process(networkId, chat("ignored").copy(source = Prefix("ignored")))

            assertEquals(listOf("live", "action", "dm"), admitted.map { it.msgid })
            assertEquals(listOf("hello how are you", "waves", "hello"), admitted.map { it.text })
            assertEquals(listOf(MessageKind.PRIVMSG, MessageKind.ACTION, MessageKind.PRIVMSG), admitted.map { it.kind })
            admitted.forEach { assertEquals(it.id, db.messageDao().byCanonicalId(it.id)?.id) }
        }

    @Test
    fun readerFailureCannotInterruptCommittedMessageOrNotification() =
        runTest {
            var notified = 0
            val reading =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notified++
                        }
                    },
                    incomingMessageReader =
                        object : IncomingMessageReader {
                            override fun onIncoming(message: MessageEntity) {
                                error("Installed engine unavailable")
                            }
                        },
                )
            reading.onRegistered(networkId, "me", emptyMap())
            reading.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("reader-failure"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("trev"),
                    "#chan",
                    "me: hello",
                    false,
                    null,
                ),
            )
            val room = checkNotNull(db.bufferDao().byName(networkId, "#chan"))
            assertEquals("me: hello", pagingList(room.id).single().text)
            assertEquals(1, notified)
        }

    @Test
    fun chatSoundFailure_doesNotInterruptPersistenceOrNotification() =
        runTest {
            var notifications = 0
            val recording =
                EventProcessor(
                    db = db,
                    typing = TypingTrackerImpl(),
                    notifier =
                        object : MessageNotifier {
                            override suspend fun onIncoming(
                                networkId: Long,
                                bufferId: Long,
                                type: BufferType,
                                hasMention: Boolean,
                                message: IrcEvent.ChatMessage,
                            ) {
                                notifications++
                            }
                        },
                    chatSoundPlayer =
                        object : ChatSoundPlayer {
                            override suspend fun onIncoming(
                                bufferId: Long,
                                type: BufferType,
                                message: IrcEvent.ChatMessage,
                            ) {
                                error("audio service unavailable")
                            }

                            override suspend fun onOutgoingAccepted(bufferId: Long) = Unit
                        },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

            recording.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "sound-failure"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "me: still persist and notify",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertEquals(1, pagingList(buffer.id).size)
            assertEquals(1, notifications)
        }

    @Test
    fun canonicalEventDrivesOneNotificationAndAtMostOneSoundAcrossHistoryAndLive() =
        runTest {
            var notifications = 0
            var sounds = 0
            val recording =
                EventProcessor(
                    db = db,
                    typing = TypingTrackerImpl(),
                    notifier =
                        object : MessageNotifier {
                            override suspend fun onIncoming(
                                networkId: Long,
                                bufferId: Long,
                                type: BufferType,
                                hasMention: Boolean,
                                message: IrcEvent.ChatMessage,
                            ) {
                                notifications++
                            }
                        },
                    chatSoundPlayer =
                        object : ChatSoundPlayer {
                            override suspend fun onIncoming(
                                bufferId: Long,
                                type: BufferType,
                                message: IrcEvent.ChatMessage,
                            ) {
                                sounds++
                            }

                            override suspend fun onOutgoingAccepted(bufferId: Long) = Unit
                        },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            val durable =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "presentation-once", time = 5_000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "me: one presentation decision",
                    isSelf = false,
                    replyToMsgid = null,
                )

            recording.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(durable)))
            recording.process(networkId, durable)
            recording.process(networkId, durable)

            assertEquals(1, notifications)
            assertEquals(1, sounds)
            val buffer = requireNotNull(db.bufferDao().byName(networkId, "#chan"))
            assertEquals(1, pagingList(buffer.id).size)
        }

    @Test
    fun pendingChannelClose_deletesOnlyAfterSelfPartAcknowledgement() =
        runTest {
            val bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#closing",
                        displayName = "#closing",
                        type = BufferType.CHANNEL,
                        pendingCloseAt = 123,
                    ),
                )

            processor.process(
                networkId,
                IrcEvent.Parted(
                    ctx = ctx(),
                    nick = "me",
                    channel = "#closing",
                    reason = null,
                    isSelf = true,
                ),
            )

            assertNull(db.bufferDao().observeById(bufferId))
        }

    @Test
    fun pendingChannelClose_notOnChannelError_isTerminalAcknowledgement() =
        runTest {
            val bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#already-gone",
                        displayName = "#already-gone",
                        type = BufferType.CHANNEL,
                        pendingCloseAt = 123,
                    ),
                )
            processor.process(networkId, IrcEvent.Joined(ctx(), "Helper", "#already-gone", null, null, false, isBot = true))
            assertTrue(db.userDao().isBot(networkId, "helper"))

            processor.process(
                networkId,
                IrcEvent.ServerError(
                    code = "403",
                    params = listOf("me", "#already-gone", "No such channel"),
                    text = "No such channel",
                ),
            )

            assertNull(db.bufferDao().observeById(bufferId))
            assertFalse(db.userDao().isBot(networkId, "helper"))
        }

    @Test
    fun notOnChannelError_routesInlineAndFlipsJoined() =
        runTest {
            val bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#gone",
                        displayName = "#gone",
                        type = BufferType.CHANNEL,
                        joined = true,
                    ),
                )
            processor.process(networkId, IrcEvent.Joined(ctx(), "Helper", "#gone", null, null, false, isBot = true))
            assertTrue(db.userDao().isBot(networkId, "helper"))
            // A self-send is sitting un-echoed in the buffer; the server never accepted it.
            val pendingId =
                db.canonicalTimelineDao().insertEvent(
                    TimelineEventEntity(
                        bufferId = bufferId,
                        serverTime = 1000,
                        sender = "me",
                        kind = MessageKind.PRIVMSG,
                        text = "hello?",
                        isSelf = true,
                        pendingLabel = "L1",
                        dedupKey = "dk-pending",
                    ),
                )

            processor.process(
                networkId,
                IrcEvent.ServerError(
                    code = "442",
                    params = listOf("me", "#gone", "You're not on that channel"),
                    text = "You're not on that channel",
                ),
            )

            val buffer = db.bufferDao().observeById(bufferId)!!
            assertFalse(buffer.joined)
            assertTrue(db.memberDao().allNow(bufferId).isEmpty())
            assertFalse(db.userDao().isBot(networkId, "helper"))
            // The error surfaced inline in the channel buffer, not the SERVER buffer.
            val channelRows = pagingList(bufferId)
            assertTrue(channelRows.any { it.kind == MessageKind.ERROR && it.text.contains("442") })
            val server =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "*", displayName = "*", type = BufferType.SERVER),
                )
            assertFalse(pagingList(server).any { it.kind == MessageKind.ERROR })
            // The orphaned send is marked failed so the user sees it never delivered.
            assertTrue(db.canonicalTimelineDao().eventById(pendingId)!!.failed)
        }

    @Test
    fun cannotSendError_routesInlineWithoutFlippingJoined() =
        runTest {
            val bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#muted",
                        displayName = "#muted",
                        type = BufferType.CHANNEL,
                        joined = true,
                    ),
                )
            val pendingId =
                db.canonicalTimelineDao().insertEvent(
                    TimelineEventEntity(
                        bufferId = bufferId,
                        serverTime = 1000,
                        sender = "me",
                        kind = MessageKind.PRIVMSG,
                        text = "hello?",
                        isSelf = true,
                        pendingLabel = "L1",
                        dedupKey = "dk-pending",
                    ),
                )

            processor.process(
                networkId,
                IrcEvent.ServerError(
                    code = "404",
                    params = listOf("me", "#muted", "Cannot send to channel"),
                    text = "Cannot send to channel",
                ),
            )

            // 404 may mean muted/banned while still joined; don't flip joined.
            assertTrue(db.bufferDao().observeById(bufferId)!!.joined)
            assertTrue(pagingList(bufferId).any { it.kind == MessageKind.ERROR && it.text.contains("404") })
            assertTrue(db.canonicalTimelineDao().eventById(pendingId)!!.failed)
        }

    @Test
    fun notOnChannelError_withoutChannelBuffer_fallsBackToServerBuffer() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ServerError(
                    code = "442",
                    params = listOf("me", "#never-joined", "You're not on that channel"),
                    text = "You're not on that channel",
                ),
            )

            val server = db.bufferDao().byName(networkId, "*")!!
            assertTrue(pagingList(server.id).any { it.kind == MessageKind.ERROR && it.text.contains("442") })
            assertNull(db.bufferDao().byName(networkId, "#never-joined"))
        }

    @Test
    fun replyToOtherOrMissingParent_doesNotInventMention() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "other-parent"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("bob"),
                    target = "#chan",
                    text = "parent text",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "other-child", time = 1001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "plain child",
                    isSelf = false,
                    replyToMsgid = "other-parent",
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "missing-child", time = 1002),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "another plain child",
                    isSelf = false,
                    replyToMsgid = "missing",
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val rows = pagingList(buffer.id)
            assertFalse(rows.first { it.msgid == "other-child" }.hasMention)
            assertFalse(rows.first { it.msgid == "missing-child" }.hasMention)
        }

    @Test
    fun quit_fansOutToEveryMemberBuffer() =
        runTest {
            // alice joins two channels.
            processor.process(networkId, IrcEvent.Joined(ctx(), "alice", "#a", null, null, false))
            processor.process(networkId, IrcEvent.Joined(ctx(), "alice", "#b", null, null, false))
            val a = db.bufferDao().byName(networkId, "#a")!!.id
            val b = db.bufferDao().byName(networkId, "#b")!!.id
            assertEquals(
                1,
                db
                    .memberDao()
                    .observe(a)
                    .first()
                    .count { it.nick == "alice" },
            )
            assertEquals(
                1,
                db
                    .memberDao()
                    .observe(b)
                    .first()
                    .count { it.nick == "alice" },
            )

            processor.process(networkId, IrcEvent.Quit(ctx(time = 2000), "alice", "bye"))

            assertTrue(
                db
                    .memberDao()
                    .observe(a)
                    .first()
                    .none { it.nick == "alice" },
            )
            assertTrue(
                db
                    .memberDao()
                    .observe(b)
                    .first()
                    .none { it.nick == "alice" },
            )
            // A QUIT system message landed in both buffers.
            assertTrue(pagingList(a).any { it.kind == MessageKind.QUIT })
            assertTrue(pagingList(b).any { it.kind == MessageKind.QUIT })
        }

    /**
     * Room releases PagingSource invalidations per commit. A single JOIN still commits once;
     * consecutive live peer JOIN/PART rows now share one outer commit. The DB is rebuilt with
     * a direct executor so InvalidationTracker refreshes inline rather than racing this assertion.
     */
    @Test
    fun `a quit fanning across channels commits one timeline invalidation`() =
        runTest {
            val direct = directCommitDatabase()
            try {
                direct.processor.process(direct.networkId, IrcEvent.Joined(ctx(), "alice", "#a", null, null, false))
                direct.processor.process(direct.networkId, IrcEvent.Joined(ctx(), "alice", "#b", null, null, false))
                direct.processor.process(direct.networkId, IrcEvent.Joined(ctx(), "alice", "#c", null, null, false))

                val invalidations =
                    direct.countInvalidations("messages") {
                        direct.processor.process(direct.networkId, IrcEvent.Quit(ctx(time = 2000), "alice", "bye"))
                    }

                assertEquals(1, invalidations)
                listOf("#a", "#b", "#c").forEach { name ->
                    val buffer = direct.db.bufferDao().byName(direct.networkId, name)!!
                    assertTrue(
                        direct.db
                            .messageDao()
                            .pagingSource(buffer.id)
                            .load(
                                androidx.paging.PagingSource.LoadParams
                                    .Refresh(null, 100, false),
                            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }
                            .any { it.kind == MessageKind.QUIT },
                    )
                }
            } finally {
                direct.db.close()
            }
        }

    @Test
    fun `a peer join commits one invalidation across roster and timeline`() =
        runTest {
            val direct = directCommitDatabase()
            try {
                direct.processor.process(direct.networkId, IrcEvent.Joined(ctx(), "me", "#a", null, null, isSelf = true))

                // The roster tables are observed by the member list, so a JOIN that committed the member
                // row, the user row and the timeline row separately redrew three surfaces per line.
                val invalidations =
                    direct.countInvalidations("messages", "members", "users") {
                        direct.processor.process(
                            direct.networkId,
                            IrcEvent.Joined(ctx(time = 2000), "alice", "#a", null, null, false),
                        )
                    }

                assertEquals(1, invalidations)
            } finally {
                direct.db.close()
            }
        }

    @Test
    fun `a nick change renaming across channels commits one timeline invalidation`() =
        runTest {
            val direct = directCommitDatabase()
            try {
                listOf("#a", "#b", "#c").forEach { channel ->
                    direct.processor.process(direct.networkId, IrcEvent.Joined(ctx(), "alice", channel, null, null, false))
                }

                val invalidations =
                    direct.countInvalidations("messages", "members") {
                        direct.processor.process(
                            direct.networkId,
                            IrcEvent.NickChanged(ctx(time = 2000), "alice", "alice2", isSelf = false),
                        )
                    }

                assertEquals(1, invalidations)
                listOf("#a", "#b", "#c").forEach { name ->
                    val buffer = direct.db.bufferDao().byName(direct.networkId, name)!!
                    assertTrue(
                        direct.db
                            .memberDao()
                            .observe(buffer.id)
                            .first()
                            .any { it.nick == "alice2" },
                    )
                }
            } finally {
                direct.db.close()
            }
        }

    @Test
    fun `peer join part batch persists ordered distinct rows with one invalidation and unchanged visibility`() =
        runTest {
            val direct = directCommitDatabase()
            try {
                val individual = "#individual"
                val batched = "#batched"
                listOf(individual, batched).forEach { channel ->
                    direct.processor.process(
                        direct.networkId,
                        IrcEvent.Joined(ctx(msgid = "self-$channel"), "me", channel, null, null, true),
                    )
                }

                fun events(channel: String): List<IrcEvent> =
                    (0 until 4).flatMap { cycle ->
                        listOf(
                            IrcEvent.Joined(
                                ctx(msgid = "join-$channel-$cycle", time = 2_000L + cycle * 2),
                                "alice",
                                channel,
                                null,
                                null,
                                false,
                            ),
                            IrcEvent.Parted(
                                ctx(msgid = "part-$channel-$cycle", time = 2_001L + cycle * 2),
                                "alice",
                                channel,
                                null,
                                false,
                            ),
                        )
                    } +
                        IrcEvent.Joined(
                            ctx(msgid = "join-$channel-bob", time = 2_008),
                            "bob",
                            channel,
                            null,
                            null,
                            false,
                        )
                val individualEvents = events(individual)
                val batchEvents = events(batched)
                val individualCommits =
                    direct.countInvalidations("messages", "members", "users") {
                        individualEvents.forEach { direct.processor.process(direct.networkId, it) }
                    }
                val batchCommits =
                    direct.countInvalidations("messages", "members", "users") {
                        direct.processor.processPeerPresenceBatch(direct.networkId, batchEvents)
                    }
                assertEquals(individualEvents.size, individualCommits)
                assertEquals(1, batchCommits)
                for (channel in listOf(individual, batched)) {
                    val roomId =
                        direct.db
                            .bufferDao()
                            .byName(direct.networkId, channel)!!
                            .id
                    val rows =
                        direct.db
                            .messageDao()
                            .pagingSource(roomId)
                            .load(
                                androidx.paging.PagingSource.LoadParams
                                    .Refresh(null, 100, false),
                            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }
                            .filterNot { it.isSelf }
                            .asReversed()
                    assertEquals((2_000L..2_008L).toList(), rows.map { it.serverTime })
                    assertEquals(
                        (0 until 9).map { if (it % 2 == 0) MessageKind.JOIN else MessageKind.PART },
                        rows.map { it.kind },
                    )
                    assertEquals((0 until 8).map { "alice" } + "bob", rows.map { it.sender })
                    assertEquals(9, rows.map { it.id }.toSet().size)
                    assertEquals(
                        setOf("me", "bob"),
                        direct.db
                            .memberDao()
                            .observe(roomId)
                            .first()
                            .map { it.nick }
                            .toSet(),
                    )
                    val allRows =
                        direct.db
                            .messageDao()
                            .pagingSource(messagePagingQuery(roomId, MessageVisibilitySpec(presenceMode = PresenceMode.ALL)))
                            .load(
                                androidx.paging.PagingSource.LoadParams
                                    .Refresh(null, 100, false),
                            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }
                            .filterNot { it.isSelf }
                    val smartRows =
                        direct.db
                            .messageDao()
                            .pagingSource(messagePagingQuery(roomId, MessageVisibilitySpec(presenceMode = PresenceMode.SMART)))
                            .load(
                                androidx.paging.PagingSource.LoadParams
                                    .Refresh(null, 100, false),
                            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }
                            .filterNot { it.isSelf }
                    assertEquals(rows.map { it.id }.toSet(), allRows.map { it.id }.toSet())
                    assertTrue(smartRows.isEmpty())
                }
            } finally {
                direct.db.close()
            }
        }

    private class DirectCommitFixture(
        val db: MotdDatabase,
        val processor: EventProcessor,
        val networkId: Long,
    ) {
        /** Invalidations of [tables] released while [block] runs. One per committed transaction. */
        suspend fun countInvalidations(
            vararg tables: String,
            block: suspend () -> Unit,
        ): Int {
            var count = 0
            val observer =
                object : InvalidationTracker.Observer(arrayOf(*tables)) {
                    override fun onInvalidated(tables: Set<String>) {
                        count++
                    }
                }
            db.invalidationTracker.addObserver(observer)
            try {
                block()
            } finally {
                db.invalidationTracker.removeObserver(observer)
            }
            return count
        }
    }

    private suspend fun directCommitDatabase(): DirectCommitFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directDb =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(Runnable::run)
                .build()
        // Close on a failed seed: the caller's `finally` only exists once this returns.
        return runCatching {
            val processor = EventProcessor(directDb, TypingTrackerImpl(), MessageNotifier.Noop)
            val id =
                directDb.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(id, "me", mapOf("CASEMAPPING" to "rfc1459"))
            DirectCommitFixture(directDb, processor, id)
        }.onFailure { directDb.close() }.getOrThrow()
    }

    @Test
    fun join_insertsSystemMessage_andMarksSelfJoined() =
        runTest {
            processor.process(networkId, IrcEvent.Joined(ctx(msgid = "j1"), "me", "#chan", null, null, isSelf = true))
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertTrue(buffer.joined)
            assertTrue(pagingList(buffer.id).any { it.kind == MessageKind.JOIN })
        }

    @Test
    fun reaction_upserted() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(),
                    source = Prefix("alice"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "m1",
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val reactions = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
            assertEquals(1, reactions.size)
            assertEquals("👍", reactions.single().emoji)
            assertNull(reactions.single().activityAt)
        }

    @Test
    fun reactionActivityUsesCanonicalTargetAndSurvivesAccountPromotionAndHistoryRefresh() =
        runTest {
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "ascii"))
            val parent =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "Opaque-Parent"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#actual",
                    text = "my message",
                    isSelf = true,
                    replyToMsgid = null,
                )
            processor.process(networkId, parent)
            val room = checkNotNull(db.bufferDao().byName(networkId, "#actual"))
            val parentId = checkNotNull(db.messageDao().byMsgid(room.id, "Opaque-Parent")).id
            val reactions = db.reactionDao()
            val add =
                IrcEvent.TagMessage(
                    ctx = ctx(msgid = "add", time = 2_000),
                    source = Prefix("B[ob]"),
                    target = "me",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "Opaque-Parent",
                )
            processor.process(networkId, add)
            val first = reactions.forRoom(room.id).single()
            assertEquals(parentId, first.targetEventId)
            assertEquals(2_000L, first.activityAt)
            assertEquals("B[ob]", first.activitySender)
            assertEquals("add", first.activityEventMsgid)
            assertNull(first.activityAccount)
            assertNull(db.bufferDao().byName(networkId, "b[ob]"))

            val accountEcho = add.copy(ctx = ctx(msgid = "add", time = 2_100, account = "bob-account"), source = Prefix("B[OB]"))
            processor.process(networkId, accountEcho)
            processor.process(networkId, IrcEvent.HistoryBatch("#actual", listOf(accountEcho.copy(target = "#actual"))))
            val refreshed = reactions.forRoom(room.id).single()
            assertEquals(first.id, refreshed.id)
            assertEquals("account:bob-account", refreshed.actorKey)
            assertEquals(first.activityAt, refreshed.activityAt)
            assertEquals(first.activitySender, refreshed.activitySender)
            assertEquals(first.activityEventMsgid, refreshed.activityEventMsgid)
            assertNull(refreshed.activityAccount)

            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage.parse("@account=bob-account;+draft/unreact=👍;+reply=Opaque-Parent :b[ob]!u@h TAGMSG me")),
            )
            assertTrue(reactions.forRoom(room.id).isEmpty())
            processor.process(networkId, IrcEvent.HistoryBatch("#actual", listOf(accountEcho.copy(target = "#actual"))))
            assertNull(reactions.forRoom(room.id).single().activityAt)
            processor.process(networkId, accountEcho.copy(ctx = ctx(msgid = "new-add", time = 3_000, account = "bob-account")))
            val readded = reactions.forRoom(room.id).single()
            assertNotEquals(first.id, readded.id)
            assertEquals(3_000L, readded.activityAt)

            // This add is a new occurrence even though the client missed the intervening unreact.
            val latest = accountEcho.copy(ctx = ctx(msgid = "missed-unreact-readd", time = 5_000, account = "bob-account"))
            processor.process(networkId, latest)
            val latestRow = reactions.forRoom(room.id).single()
            assertEquals(readded.id, latestRow.id)
            assertEquals(5_000L, latestRow.activityAt)
            assertEquals("missed-unreact-readd", latestRow.activityEventMsgid)

            processor.process(networkId, IrcEvent.HistoryBatch("#actual", listOf(accountEcho.copy(target = "#actual"))))
            val oldRemoval =
                IrcEvent.Raw(
                    IrcMessage.parse(
                        "@time=1970-01-01T00:00:04.000Z;account=bob-account;+draft/unreact=👍;" +
                            "+reply=Opaque-Parent :b[ob]!u@h TAGMSG #actual",
                    ),
                )
            val undated = IrcEvent.Raw(oldRemoval.message.copy(tags = oldRemoval.message.tags - "time"))
            processor.process(networkId, IrcEvent.HistoryBatch("#actual", listOf(oldRemoval, undated)))
            assertEquals(5_000L, reactions.forRoom(room.id).single().activityAt)

            val newer = IrcEvent.Raw(oldRemoval.message.copy(tags = oldRemoval.message.tags + ("time" to "1970-01-01T00:00:06.000Z")))
            processor.process(networkId, IrcEvent.HistoryBatch("#actual", listOf(newer)))
            assertTrue(reactions.forRoom(room.id).isEmpty())

            val receipt = accountEcho.copy(ctx = ctx(time = 3_000, account = "bob-account").copy(serverTimeSource = ServerTimeSource.LOCAL))
            processor.process(networkId, receipt)
            processor.process(networkId, IrcEvent.ReplayBatch("#actual", listOf(newer)))
            assertEquals(3_000L, reactions.forRoom(room.id).single().activityAt)
            processor.process(networkId, newer)
            assertTrue(reactions.forRoom(room.id).isEmpty())

            processor.process(networkId, accountEcho.copy(ctx = ctx(msgid = "account-first", time = 7_000, account = "bob-account")))
            val firstByAccount = reactions.forRoom(room.id).single()
            val duplicateWithoutTime = ctx(msgid = "account-first", time = 7_100).copy(serverTimeSource = ServerTimeSource.LOCAL)
            processor.process(networkId, add.copy(ctx = duplicateWithoutTime))
            val nickEcho = reactions.forRoom(room.id).single()
            assertEquals(firstByAccount.id, nickEcho.id)
            assertEquals("account:bob-account", nickEcho.actorKey)
            assertEquals("bob-account", nickEcho.activityAccount)
            assertEquals(7_000L, nickEcho.activityAt)

            val untimed = duplicateWithoutTime.copy(msgid = "distinct-untimed", account = "bob-account", serverTime = 8_000)
            processor.process(networkId, accountEcho.copy(ctx = untimed))
            val untimedRow = reactions.forRoom(room.id).single()
            assertEquals(firstByAccount.id, untimedRow.id)
            assertEquals(8_000L, untimedRow.activityAt)
            assertEquals("distinct-untimed", untimedRow.activityEventMsgid)
            assertFalse(untimedRow.activityHasWireTime)
            val missedRemoval = IrcEvent.Raw(oldRemoval.message.copy(tags = oldRemoval.message.tags + ("time" to "1970-01-01T00:00:07.500Z")))
            processor.process(networkId, IrcEvent.HistoryBatch("#actual", listOf(missedRemoval)))
            assertEquals(untimedRow, reactions.forRoom(room.id).single())
        }

    @Test
    fun historyReplayPushSelfAndSuppressedActorsDoNotCreateReactionActivity() =
        runTest {
            val own =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "own"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "parent",
                    isSelf = true,
                    replyToMsgid = null,
                )
            db.networkIgnoreDao().upsert(NetworkIgnoreEntity(networkId = networkId, pattern = "blocked!*@*", createdAt = 1))
            db.networkIgnoreDao().upsert(
                NetworkIgnoreEntity(
                    networkId = networkId,
                    pattern = "quiet!*@*",
                    createdAt = 1,
                    dropMessages = false,
                    suppressedSignals = SUPPRESS_HIGHLIGHT,
                ),
            )
            processor.process(networkId, own)

            fun add(nick: String) = IrcEvent.TagMessage(ctx(time = 2_000), Prefix(nick, "u", "h"), "#chan", null, "👍", "own")

            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(add("historic"))))
            processor.process(networkId, IrcEvent.ReplayBatch("#chan", listOf(add("replay"))))
            processor.processPush(networkId, add("push"))
            listOf("ME", "blocked", "quiet").forEach { processor.process(networkId, add(it)) }
            val account = add("Bob").copy(ctx = ctx(time = 2_000, account = "bob-account"))
            processor.process(networkId, account)

            val room = checkNotNull(db.bufferDao().byName(networkId, "#chan"))
            val rows = db.reactionDao().forRoom(room.id)
            assertEquals(setOf("historic", "replay", "push", "ME", "blocked", "quiet", "Bob"), rows.map { it.sender }.toSet())
            assertEquals("bob-account", rows.filter { it.activityAt != null }.single().activityAccount)

            val otherNetwork =
                db.networkDao().insert(checkNotNull(db.networkDao().byId(networkId)).copy(id = 0, name = "Other network"))
            processor.onRegistered(otherNetwork, "me", emptyMap())
            val otherRoom = BufferStore(db).getOrCreate(otherNetwork, "#chan", "#chan", BufferType.CHANNEL)
            processor.process(otherNetwork, add("Pat"))
            val unresolved = db.reactionDao().forRoom(otherRoom.id).single()
            assertNull(unresolved.targetEventId)
            processor.process(otherNetwork, own.copy(source = Prefix("other"), isSelf = false))
            val otherReaction = db.reactionDao().forRoom(otherRoom.id).single()
            assertEquals(db.messageDao().byMsgid(otherRoom.id, "own")?.id, otherReaction.targetEventId)
            assertNull(otherReaction.activityAt)
            assertEquals(rows, db.reactionDao().forRoom(room.id))

            processor.process(networkId, IrcEvent.MessageRedacted(ctx(), Prefix("oper"), "#chan", "own", null))
            processor.process(networkId, account)
            assertTrue(db.reactionDao().forRoom(room.id).isEmpty())
        }

    @Test
    fun redaction_tombstonesKnownMessageInTargetHistory_andRemovesItsReactions() =
        runTest {
            ChatRoomRevisions.install(db.openHelper.writableDatabase)
            val notified = mutableListOf<Pair<Long, Long>>()
            val wrongRoom = IrcEvent.MessageRedacted(ctx(), Prefix("oper"), "#other", "m1", "wrong room")
            BufferStore(db).getOrCreate(networkId, "#other", "#other", BufferType.CHANNEL)
            processor =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier by MessageNotifier.Noop {
                        override suspend fun onMessageRedacted(
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            assertFalse(db.inTransaction())
                            assertEquals(MessageKind.REDACTED, db.messageDao().byId(messageId)?.kind)
                            processor.process(networkId, wrongRoom)
                            notified += bufferId to messageId
                            if (notified.size == 1) error("interrupted retirement")
                        }
                    },
                )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1").copy(serverTimeSource = ServerTimeSource.LOCAL),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "\u0002secret\u000f",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(),
                    source = Prefix("bob"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "m1",
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!

            // A known msgid outside the command target must not be touched.
            processor.process(networkId, wrongRoom)
            assertEquals(MessageKind.PRIVMSG, db.messageDao().byMsgid(buffer.id, "m1")!!.kind)
            assertTrue(notified.isEmpty())

            val redaction = IrcEvent.MessageRedacted(ctx(time = 2_000), Prefix("oper"), "#chan", "m1", "spam")
            val page =
                HistoryPageWrite(
                    ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 2),
                    ChatHistoryResponse.Messages(
                        events = listOf(redaction),
                        oldest = null,
                        newest = null,
                        endOfHistory = true,
                        primaryMessageCount = 0,
                    ),
                )
            val invalid = page.copy(request = page.request.copy(subcommand = ChatHistoryRequest.Subcommand.TARGETS))
            val rollback = runCatching { processor.persistHistoryPagesResult(networkId, listOf(page, invalid), buffer.id) }
            assertTrue(rollback.exceptionOrNull() is IllegalArgumentException)
            assertEquals(MessageKind.PRIVMSG, db.messageDao().byMsgid(buffer.id, "m1")!!.kind)
            assertTrue(notified.isEmpty())

            processor.persistHistoryPage(networkId, page.request, page.response, expectedRoomId = buffer.id)

            val redacted = db.messageDao().byMsgid(buffer.id, "m1")!!
            assertEquals(MessageKind.REDACTED, redacted.kind)
            assertEquals("Message deleted by oper (spam)", redacted.text)
            assertNull(redacted.ircFormattedText)
            assertFalse(redacted.hasMention)
            assertEquals(listOf(buffer.id to redacted.id), notified)
            val roomRevision = checkNotNull(db.messageDao().roomRevision(buffer.id))
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(redaction.copy(reason = "duplicate"))))
            assertEquals(listOf(buffer.id to redacted.id, buffer.id to redacted.id), notified)
            assertEquals(redacted, db.messageDao().byId(redacted.id))
            assertEquals(roomRevision, db.messageDao().roomRevision(buffer.id))
            assertTrue(
                db
                    .reactionDao()
                    .observeFor(buffer.id, listOf("m1"))
                    .first()
                    .isEmpty(),
            )

            // Later authoritative history may enrich identity and time, but cannot restore content.
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx = ctx(msgid = "m1", time = 1_000),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("alice"),
                            target = "#chan",
                            text = "secret",
                            isSelf = false,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )
            val replayed = db.messageDao().byMsgid(buffer.id, "m1")!!
            assertEquals(MessageKind.REDACTED, replayed.kind)
            assertEquals("Message deleted by oper (spam)", replayed.text)
        }

    @Test
    fun savedSnapshotSurvivesPruningAndOnlyExactRoutedRedactionErasesIt() =
        runTest {
            val saved = SavedMessagesRepository(db, AppClock { 5_000L })
            val chat =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "SecretId"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "\u0002secret\u000f",
                    isSelf = false,
                    replyToMsgid = null,
                )
            processor.process(networkId, chat)
            val room = checkNotNull(db.bufferDao().byName(networkId, "#chan"))
            BufferStore(db).getOrCreate(networkId, "#other", "#other", BufferType.CHANNEL)
            val original = checkNotNull(db.messageDao().byMsgid(room.id, "SecretId"))
            saved.setSaved(original.id, true)
            saved.setSaved(original.id, true)
            val snapshot = saved.observe().first().single()
            assertEquals(SavedMessage(snapshot.id, "alice", "secret", 5_000L, 1_000L, "libera", "#chan"), snapshot)
            assertEquals(SavedMessageSource(room.id, original.id, "SecretId", 1_000L), saved.resolveSource(snapshot.id))

            assertEquals(1, db.historyPruneDao().deleteOldest(room.id, 1_001L, presence = false, chunk = 1))
            assertNull(db.messageDao().byId(original.id))
            assertEquals(snapshot, saved.observe().first().single())
            assertEquals(SavedMessageSource(room.id, null, "SecretId", 1_000L), saved.resolveSource(snapshot.id))

            val redaction = IrcEvent.MessageRedacted(ctx(), Prefix("oper"), "#chan", "SecretId", null)
            val wrongRoom = redaction.copy(target = "#other")
            processor.process(networkId, wrongRoom)
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(wrongRoom)))
            processor.process(networkId, redaction.copy(targetMsgid = "secretid"))
            assertEquals(snapshot, saved.observe().first().single())

            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(redaction)))
            assertEquals(snapshot.copy(text = null), saved.observe().first().single())
            processor.process(networkId, chat)
            val replayed = checkNotNull(db.messageDao().byMsgid(room.id, "SecretId"))
            assertEquals(MessageKind.REDACTED, replayed.kind)
            assertNull(replayed.ircFormattedText)
            assertFalse(replayed.notificationEligible)
            assertEquals(replayed.id, saved.resolveSource(snapshot.id)?.eventId)
            saved.remove(snapshot.id)
            db.messageDao().deleteById(replayed.id)
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(chat)))
            assertEquals(MessageKind.REDACTED, db.messageDao().byMsgid(room.id, "SecretId")?.kind)
            assertTrue(saved.observe().first().isEmpty())
            db.networkDao().deleteLocalTree(networkId)
            assertTrue(saved.observe().first().isEmpty())
            saved.setSaved(original.id, true)
            assertTrue(saved.observe().first().isEmpty())
        }

    @Test
    fun savedChannelIdentitySurvivesCloseAndRejoin() =
        runTest {
            val saved = SavedMessagesRepository(db, AppClock { 5_000L })
            val chat =
                IrcEvent.ChatMessage(
                    ctx(msgid = "channel-msg"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#chan",
                    "secret",
                    false,
                    null,
                )
            processor.process(networkId, chat)
            val room = checkNotNull(db.bufferDao().byName(networkId, "#chan"))
            val original = checkNotNull(db.messageDao().byMsgid(room.id, "channel-msg"))
            saved.setSaved(original.id, true)
            val snapshot = saved.observe().first().single()
            db.bufferDao().deleteBuffer(room.id)
            assertNull(saved.resolveSource(snapshot.id))

            val rejoined = BufferStore(db).getOrCreate(networkId, "#chan", "#CHAN", BufferType.CHANNEL)
            assertNotEquals(room.id, rejoined.id)
            assertEquals(SavedMessageSource(rejoined.id, null, "channel-msg", 1_000L), saved.resolveSource(snapshot.id))
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(chat)))
            val replayed = checkNotNull(db.messageDao().byMsgid(rejoined.id, "channel-msg"))
            assertEquals(replayed.id, saved.resolveSource(snapshot.id)?.eventId)
            assertTrue(saved.observeSaved(replayed.id).first())
            saved.setSaved(replayed.id, true)
            assertEquals(snapshot, saved.observe().first().single())

            db.bufferDao().deleteBuffer(rejoined.id)
            processor.process(networkId, IrcEvent.MessageRedacted(ctx(), Prefix("oper"), "#CHAN", "channel-msg", null))
            assertEquals(snapshot.copy(text = null), saved.observe().first().single())
            processor.process(networkId, chat)
            val nextRoom = checkNotNull(db.bufferDao().byName(networkId, "#chan"))
            assertEquals(MessageKind.REDACTED, db.messageDao().byMsgid(nextRoom.id, "channel-msg")?.kind)
            assertEquals(nextRoom.id, saved.resolveSource(snapshot.id)?.bufferId)
        }

    @Test
    fun historicalDmRedactionRejectsKnownWrongPeerAndAcceptsOldSelfTarget() =
        runTest {
            val saved = SavedMessagesRepository(db, AppClock { 5_000L })
            processor.onRegistered(networkId, "oldme", mapOf("CASEMAPPING" to "rfc1459"))
            processor.process(networkId, IrcEvent.Registered("me", emptySet(), emptyMap(), startsSession = false))
            BufferStore(db).getOrCreate(networkId, "oldme", "oldme", BufferType.QUERY)
            for (peer in listOf("alice", "bob")) {
                processor.process(
                    networkId,
                    IrcEvent.ChatMessage(
                        ctx(msgid = "$peer-msg", account = "$peer-account"),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix(peer),
                        "me",
                        "secret",
                        false,
                        null,
                    ),
                )
            }
            val alice = checkNotNull(db.bufferDao().byName(networkId, "alice"))
            val original = checkNotNull(db.messageDao().byMsgid(alice.id, "alice-msg"))
            saved.setSaved(original.id, true)
            assertEquals(1, db.historyPruneDao().deleteOldest(alice.id, 1_001, presence = false, chunk = 1))

            val wrongPeer = IrcEvent.MessageRedacted(ctx(account = "bob-account"), Prefix("bob"), "me", "alice-msg", null)
            processor.process(networkId, IrcEvent.HistoryBatch("alice", listOf(wrongPeer)))
            processor.process(networkId, IrcEvent.HistoryBatch("alice", listOf(wrongPeer.copy(ctx = ctx()))))
            val thirdParty = wrongPeer.copy(ctx = ctx(), target = "alice")
            processor.process(networkId, IrcEvent.HistoryBatch("alice", listOf(thirdParty)))
            val before = saved.observe().first().single()
            assertEquals("secret", before.text)
            val correctPeer = wrongPeer.copy(ctx = ctx(account = "alice-account"), source = Prefix("alice"), target = "oldme")
            processor.process(networkId, IrcEvent.HistoryBatch("alice", listOf(correctPeer)))
            val after = saved.observe().first().single()
            assertNull(after.text)

            val bob = checkNotNull(db.bufferDao().byName(networkId, "bob"))
            val bobMessage = checkNotNull(db.messageDao().byMsgid(bob.id, "bob-msg"))
            saved.setSaved(bobMessage.id, true)
            val outgoing = IrcEvent.MessageRedacted(ctx(account = "own-account"), Prefix("oldme"), "bob", "bob-msg", null)
            processor.process(networkId, IrcEvent.HistoryBatch("bob", listOf(outgoing)))
            assertEquals(MessageKind.REDACTED, db.messageDao().byId(bobMessage.id)?.kind)
            val bobSave = saved.observe().first().single { it.conversationName == "bob" }
            assertNull(bobSave.text)

            val untaggedSource =
                IrcEvent.ChatMessage(ctx(msgid = "old-self-no-account"), IrcEvent.ChatKind.PRIVMSG, Prefix("me"), "bob", "secret", true, null)
            processor.process(networkId, untaggedSource)
            val untaggedMessage = checkNotNull(db.messageDao().byMsgid(bob.id, "old-self-no-account"))
            saved.setSaved(untaggedMessage.id, true)
            val untagged = outgoing.copy(ctx = ctx(), targetMsgid = "old-self-no-account")
            processor.process(networkId, IrcEvent.HistoryBatch("bob", listOf(untagged)))
            assertEquals(MessageKind.REDACTED, db.messageDao().byId(untaggedMessage.id)?.kind)
            val bobSaves = saved.observe().first().filter { it.conversationName == "bob" }
            assertEquals(listOf(null, null), bobSaves.map { it.text })
        }

    @Test
    fun historyRedactionKeepsBoundRoomWhenRequestedChannelNameIsReused() =
        runTest {
            val saved = SavedMessagesRepository(db, AppClock { 5_000L })
            val store = BufferStore(db)
            processor.process(
                networkId,
                IrcEvent.ChatMessage(ctx(msgid = "old-msg"), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#old", "secret", false, null),
            )
            val old = checkNotNull(store.resolveChannelRoom(networkId, "#old"))
            val original = checkNotNull(db.messageDao().byMsgid(old.id, "old-msg"))
            saved.setSaved(original.id, true)
            val renamed = checkNotNull(store.renameChannel(networkId, "#old", "#renamed", "#renamed"))
            val reused = store.getOrCreate(networkId, "#old", "#old", BufferType.CHANNEL)
            assertNotEquals(renamed.id, reused.id)
            val redaction = IrcEvent.MessageRedacted(ctx(), Prefix("oper"), "#old", "old-msg", null)
            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#old", limit = 2),
                ChatHistoryResponse.Messages(
                    events = listOf(redaction),
                    oldest = null,
                    newest = null,
                    endOfHistory = true,
                    primaryMessageCount = 0,
                ),
                expectedRoomId = renamed.id,
            )
            assertEquals(MessageKind.REDACTED, db.messageDao().byId(original.id)?.kind)
            val snapshot = saved.observe().first().single()
            assertNull(snapshot.text)
            assertEquals(renamed.id, saved.resolveSource(snapshot.id)?.bufferId)
        }

    @Test
    fun historyIdentityMergeKeepsTheRedactedLosersTombstoneAndInvalidatesTheWinner() =
        runTest {
            val notified = mutableListOf<Pair<Long, Long>>()
            processor =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            error("interrupted presentation")
                        }

                        override suspend fun onMessageRedacted(
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            assertFalse(db.inTransaction())
                            notified += bufferId to messageId
                        }
                    },
                )
            val early =
                IrcEvent.ChatMessage(
                    ctx(time = 1_000).copy(serverTimeSource = ServerTimeSource.TAG),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#chan",
                    "\u0002secret me\u000f",
                    false,
                    null,
                )
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(early)))
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val winnerId = pagingList(buffer.id).single().id
            processor.process(networkId, early.copy(ctx = early.ctx.copy(msgid = "m1", serverTime = 2_000)))
            val loser = db.messageDao().byMsgid(buffer.id, "m1")!!
            assertNotEquals(winnerId, loser.id)
            var saveTime = 10_000L
            val saved = SavedMessagesRepository(db, AppClock { saveTime })
            saved.setSaved(loser.id, true)
            val firstSave = saved.observe().first().single()
            saveTime += 1_000L
            saved.setSaved(winnerId, true)
            assertEquals(2, saved.observe().first().size)
            val timeline = CanonicalTimelineStore(db)
            val dao = db.canonicalTimelineDao()
            assertEquals(listOf(loser.id), dao.pendingNotifications(10, 60_000, 100).map { it.id })
            assertTrue(timeline.claimNotification(loser.id))
            processor.process(networkId, IrcEvent.MessageRedacted(ctx(), Prefix("oper"), "#chan", "m1", null))

            val bridge = early.copy(ctx = early.ctx.copy(msgid = "m1"))
            val playback = IrcEvent.HistoryBatch("#chan", listOf(bridge))
            processor.process(networkId, playback)
            processor.process(networkId, playback)

            val stored = pagingList(buffer.id).single()
            assertEquals(winnerId, stored.id)
            assertEquals(winnerId, db.messageDao().byCanonicalId(loser.id)?.id)
            assertEquals(MessageKind.REDACTED, stored.kind)
            assertEquals(firstSave.copy(text = null), saved.observe().first().single())
            assertEquals(winnerId, saved.resolveSource(firstSave.id)?.eventId)
            assertTrue(saved.observeSaved(winnerId).first())
            assertTrue(saved.observeSaved(loser.id).first())
            saved.setSaved(loser.id, false)
            saved.setSaved(loser.id, false)
            assertTrue(saved.observe().first().isEmpty())
            saved.setSaved(winnerId, true)
            assertTrue(saved.observe().first().isEmpty())
            assertEquals("Message deleted by oper", stored.text)
            assertNull(stored.ircFormattedText)
            assertNull(stored.highlightNick)
            assertFalse(stored.hasMention)
            assertTrue(stored.notificationEligible)
            assertTrue(stored.notificationEligibilityResolved)
            assertTrue(stored.notificationClaimed)
            assertEquals(listOf(buffer.id to loser.id, buffer.id to winnerId), notified)
            timeline.releaseNotification(loser.id)
            assertFalse(db.messageDao().byId(winnerId)!!.notificationClaimed)
            assertTrue(dao.pendingNotifications(10, 60_000, 100).isEmpty())
        }

    @Test
    fun unreact_removesMatchingReaction_andReplayIsIdempotent() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(),
                    source = Prefix("bob"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "m1",
                ),
            )
            val unreact =
                IrcEvent.Raw(
                    IrcMessage.parse("@+draft/unreact=👍;+reply=m1 :BOB!u@h TAGMSG #chan"),
                )

            processor.process(networkId, unreact)
            processor.process(networkId, unreact)

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertTrue(
                db
                    .reactionDao()
                    .observeFor(buffer.id, listOf("m1"))
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun actorCanReactWithMultipleEmojis_andUnreactRemovesOnlySelectedEmoji() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            listOf("👍", "🎉").forEachIndexed { index, emoji ->
                processor.process(
                    networkId,
                    IrcEvent.TagMessage(
                        ctx = ctx(time = 2_000L + index),
                        source = Prefix("bob"),
                        target = "#chan",
                        typing = null,
                        reactEmoji = emoji,
                        reactTargetMsgid = "m1",
                    ),
                )
            }
            processor.process(
                networkId,
                IrcEvent.Raw(
                    IrcMessage.parse("@+draft/unreact=👍;+reply=m1 :BOB!u@h TAGMSG #chan"),
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val rows = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
            assertEquals(listOf("🎉"), rows.map { it.emoji })
        }

    @Test
    fun accountReactionEchoReconcilesOptimisticNickKeyWithTargetedMutation() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            db.reactionDao().upsert(
                io.github.trevarj.motd.data.db.ReactionEntity(
                    bufferId = buffer.id,
                    targetMsgid = "m1",
                    actorKey = "nick:bob",
                    sender = "bob",
                    emoji = "👍",
                    serverTime = 1,
                ),
            )

            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 2, account = "BobAccount"),
                    source = Prefix("BOB"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "m1",
                ),
            )

            val echoed =
                db
                    .reactionDao()
                    .observeFor(buffer.id, listOf("m1"))
                    .first()
                    .single()
            assertEquals("account:BobAccount", echoed.actorKey)
            assertEquals("BOB", echoed.sender)

            processor.process(networkId, IrcEvent.AccountChanged("bob", "BobAccount"))
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 3),
                    source = Prefix("bob"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "m1",
                ),
            )
            assertEquals(
                "account:BobAccount",
                db
                    .reactionDao()
                    .observeFor(buffer.id, listOf("m1"))
                    .first()
                    .single()
                    .actorKey,
            )

            processor.process(
                networkId,
                IrcEvent.Raw(
                    IrcMessage.parse(
                        "@+draft/unreact=👍;+reply=m1 :bob!u@h TAGMSG #chan",
                    ),
                ),
            )
            assertTrue(
                db
                    .reactionDao()
                    .observeFor(buffer.id, listOf("m1"))
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun migratedLegacyReactionAliasesAreCleanedPerEmojiWithoutScanningOtherReactions() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val legacyActor = "nick:bob\u0000legacy:7"
            db.reactionDao().upsert(
                ReactionEntity(
                    bufferId = buffer.id,
                    targetMsgid = "m1",
                    actorKey = legacyActor,
                    sender = "BOB",
                    emoji = "one",
                    serverTime = 1,
                ),
            )
            db.reactionDao().upsert(
                ReactionEntity(
                    bufferId = buffer.id,
                    targetMsgid = "m1",
                    actorKey = legacyActor,
                    sender = "BOB",
                    emoji = "two",
                    serverTime = 2,
                ),
            )

            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 3),
                    source = Prefix("Bob"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "one",
                    reactTargetMsgid = "m1",
                ),
            )

            var rows = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
            assertEquals("nick:bob", rows.single { it.emoji == "one" }.actorKey)
            assertEquals(legacyActor, rows.single { it.emoji == "two" }.actorKey)

            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage.parse("@+draft/unreact=one;+reply=m1 :BOB!u@h TAGMSG #chan")),
            )

            rows = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
            assertEquals(listOf("two"), rows.map { it.emoji })
            assertEquals(legacyActor, rows.single().actorKey)
        }

    @Test
    fun queryHistoryReactionsAndUnreactionsFollowBatchPeerAcrossNickChanges() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "query-parent"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("Bob"),
                    target = "me",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "bob")!!

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "Bob",
                    listOf(
                        IrcEvent.TagMessage(
                            ctx = ctx(time = 2_000),
                            source = Prefix("oldMe"),
                            target = "Bob",
                            typing = null,
                            reactEmoji = "👍",
                            reactTargetMsgid = "query-parent",
                        ),
                        IrcEvent.TagMessage(
                            ctx = ctx(time = 2_001),
                            source = Prefix("OldBob"),
                            target = "oldMe",
                            typing = null,
                            reactEmoji = "🔥",
                            reactTargetMsgid = "query-parent",
                        ),
                    ),
                ),
            )

            assertEquals(
                setOf("oldMe" to "👍", "OldBob" to "🔥"),
                db
                    .reactionDao()
                    .observeFor(buffer.id, listOf("query-parent"))
                    .first()
                    .map { it.sender to it.emoji }
                    .toSet(),
            )

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "Bob",
                    listOf(
                        IrcEvent.Raw(
                            IrcMessage.parse(
                                "@+draft/unreact=👍;+reply=query-parent :oldMe!u@h TAGMSG Bob",
                            ),
                        ),
                        IrcEvent.Raw(
                            IrcMessage.parse(
                                "@+draft/unreact=🔥;+reply=query-parent :OldBob!u@h TAGMSG oldMe",
                            ),
                        ),
                    ),
                ),
            )

            assertTrue(
                db
                    .reactionDao()
                    .observeFor(buffer.id, listOf("query-parent"))
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun reactionForMissingParent_isIgnoredWithoutCreatingBuffer() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(),
                    source = Prefix("alice"),
                    target = "#missing",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "absent",
                ),
            )

            assertNull(db.bufferDao().byName(networkId, "#missing"))
        }

    @Test
    fun reactionArrivingBeforeParent_isRetainedUntilParentBecomesVisible() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "seed"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "existing buffer",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!

            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 5),
                    source = Prefix("bob"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "late-parent",
                ),
            )
            val before = db.reactionDao().forRoom(buffer.id).single()
            assertNull(before.targetEventId)
            assertEquals(5L, before.activityAt)

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "late-parent", time = 6),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "parent",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )

            val reactions = db.reactionDao().observeFor(buffer.id, listOf("late-parent")).first()
            assertEquals("👍", reactions.single().emoji)
            assertEquals("bob", reactions.single().sender)
            assertEquals(db.messageDao().byMsgid(buffer.id, "late-parent")?.id, reactions.single().targetEventId)
            assertEquals(before.id, reactions.single().id)
            assertEquals(5L, reactions.single().activityAt)
            assertEquals(1, db.historyPruneDao().deleteOldest(buffer.id, 7L, presence = false, chunk = 1))
            assertTrue(db.reactionDao().forRoom(buffer.id).isEmpty())
        }

    @Test
    fun lateQueryReactionTargetRepairsAmbiguousNickRouteAfterAccountReuse() =
        runTest {
            suspend fun incoming(
                msgid: String,
                account: String,
                text: String,
            ) {
                processor.process(
                    networkId,
                    IrcEvent.ChatMessage(
                        ctx = ctx(msgid = msgid, account = account),
                        kind = IrcEvent.ChatKind.PRIVMSG,
                        source = Prefix("Bob"),
                        target = "me",
                        text = text,
                        isSelf = false,
                        replyToMsgid = null,
                    ),
                )
            }

            incoming("account-a-seed", "account-a", "first Bob")
            incoming("account-b-seed", "account-b", "reused Bob")
            val accountARoom = checkNotNull(BufferStore(db).resolveQueryRoom(networkId, "bob", "account-a"))
            val accountBRoom = checkNotNull(BufferStore(db).resolveQueryRoom(networkId, "bob", "account-b"))
            assertNotEquals(accountARoom.id, accountBRoom.id)

            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 2_000),
                    source = Prefix("Bob"),
                    target = "me",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "account-a-parent",
                ),
            )
            assertEquals(
                "nick:bob",
                db
                    .reactionDao()
                    .observeFor(accountBRoom.id, listOf("account-a-parent"))
                    .first()
                    .single()
                    .actorKey,
            )

            incoming("account-a-parent", "account-a", "late parent")

            val parent = checkNotNull(db.messageDao().byMsgid(accountARoom.id, "account-a-parent"))
            val reaction =
                db
                    .reactionDao()
                    .observeFor(accountARoom.id, listOf("account-a-parent"))
                    .first()
                    .single()
            assertEquals(parent.id, reaction.targetEventId)
            assertNull(reaction.activityAt)
            assertTrue(
                db
                    .reactionDao()
                    .observeFor(accountBRoom.id, listOf("account-a-parent"))
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun ownReactionEcho_reconcilesOntoOptimisticRow_withoutDuplicating() =
        runTest {
            // Auto-create the buffer and seed the optimistic own-reaction row the way sendReact does
            // (upsert keyed by bufferId+targetMsgid+actorKey+emoji) before the server echo arrives.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "m1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "hi",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            db.reactionDao().upsert(
                io.github.trevarj.motd.data.db.ReactionEntity(
                    bufferId = buffer.id,
                    targetMsgid = "m1",
                    actorKey = "nick:me",
                    sender = "me",
                    emoji = "👍",
                    serverTime = 5,
                ),
            )
            // Server echoes our own react back with different nick casing; IRC casefolding must still
            // reconcile it with the optimistic row rather than creating a duplicate.
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx(time = 9),
                    source = Prefix("Me"),
                    target = "#chan",
                    typing = null,
                    reactEmoji = "👍",
                    reactTargetMsgid = "m1",
                ),
            )

            val reactions = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
            assertEquals(1, reactions.size)
            assertEquals("Me", reactions.single().sender)
            assertEquals(9, reactions.single().serverTime)
            assertNull(reactions.single().activityAt)
        }

    @Test
    fun readMarker_advancesInclusiveLocalAnchorBeforeHistoryAndMaxOnly() =
        runTest {
            val bufferId =
                db.bufferDao().insert(
                    io.github.trevarj.motd.data.db.BufferEntity(
                        networkId = networkId,
                        name = "#chan",
                        displayName = "#chan",
                        type = BufferType.CHANNEL,
                    ),
                )
            processor.process(networkId, IrcEvent.ReadMarker("#chan", 5000))
            processor.process(networkId, IrcEvent.ReadMarker("#chan", 3000)) // lower → ignored
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            assertEquals(5000L, buffer.readMarkerTime)
            assertEquals(5000L, buffer.localReadAnchorTime)
            assertEquals(Long.MAX_VALUE, buffer.localReadAnchorEventId)

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "before-marker", time = 4999),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "before",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "same-ms", time = 5000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "same millisecond",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.bufferId == bufferId }
            assertEquals(0, row.unreadCount)

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "after-marker", time = 5001),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "after",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            assertEquals(
                1,
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.bufferId == bufferId }
                    .unreadCount,
            )
        }

    @Test
    fun readMarker_notifiesOnlyForKnownTimestampedTarget() =
        runTest {
            val observed = mutableListOf<Pair<Long, io.github.trevarj.motd.data.db.TimelineAnchor>>()
            val notifying =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) = Unit

                        override suspend fun onRead(
                            bufferId: Long,
                            anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
                        ) {
                            observed += bufferId to anchor
                        }
                    },
                )
            notifying.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            val bufferId =
                db.bufferDao().insert(
                    io.github.trevarj.motd.data.db.BufferEntity(
                        networkId = networkId,
                        name = "#chan",
                        displayName = "#chan",
                        type = BufferType.CHANNEL,
                    ),
                )

            notifying.process(networkId, IrcEvent.ReadMarker("#chan", 5000))
            notifying.process(networkId, IrcEvent.ReadMarker("#missing", 6000))
            notifying.process(networkId, IrcEvent.ReadMarker("#chan", null))

            assertEquals(
                listOf(
                    bufferId to
                        io.github.trevarj.motd.data.db
                            .TimelineAnchor(5000, Long.MAX_VALUE),
                ),
                observed,
            )
        }

    @Test
    fun bouncerNetworkState_updatesImportedChild_thenDeletes() =
        runTest {
            val rootId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "soju",
                        role = NetworkRole.BOUNCER_ROOT,
                        host = "bnc.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.process(rootId, IrcEvent.BouncerNetworkState("42", mapOf("name" to "OFTC", "host" to "irc.oftc.net")))
            var children = db.networkDao().childrenOf(rootId)
            assertTrue(children.isEmpty())
            val childId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "OFTC",
                        role = NetworkRole.BOUNCER_CHILD,
                        parentId = rootId,
                        bouncerNetId = "42",
                        host = "irc.oftc.net",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            val child = db.networkDao().byId(childId)!!
            val originalRoom =
                BufferEntity(
                    networkId = child.id,
                    name = "#layout",
                    displayName = "#layout",
                    type = BufferType.CHANNEL,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            db.bufferDao().setLayoutDensityOverride(originalRoom.id, LayoutDensity.COMPACT)

            // A repeated state is an update of this stable bouncer child, not a new local network.
            processor.process(rootId, IrcEvent.BouncerNetworkState("42", mapOf("name" to "OFTC renamed")))
            val updatedChild = db.networkDao().childrenOf(rootId).single()
            val retainedRoom = db.bufferDao().byName(updatedChild.id, "#layout")
            assertEquals(child.id, updatedChild.id)
            assertEquals(originalRoom.id, retainedRoom?.id)
            assertEquals(LayoutDensity.COMPACT, retainedRoom?.layoutDensityOverride)

            processor.process(rootId, IrcEvent.BouncerNetworkState("42", emptyMap())) // "*" → delete
            children = db.networkDao().childrenOf(rootId)
            assertTrue(children.isEmpty())

            processor.process(rootId, IrcEvent.BouncerNetworkState("42", mapOf("name" to "OFTC recreated")))
            assertTrue(db.networkDao().childrenOf(rootId).isEmpty())
        }

    @Test
    fun bouncerNetworkState_partialUpdate_preservesExistingConnectionFields() =
        runTest {
            val rootId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "soju",
                        role = NetworkRole.BOUNCER_ROOT,
                        host = "bnc.example",
                        port = 6697,
                        nick = "rootNick",
                        username = "me",
                        realname = "Me",
                    ),
                )
            db.networkDao().insert(
                NetworkEntity(
                    name = "OFTC",
                    role = NetworkRole.BOUNCER_CHILD,
                    parentId = rootId,
                    bouncerNetId = "42",
                    host = "irc.oftc.net",
                    port = 6698,
                    nick = "childNick",
                    username = "me",
                    realname = "Me",
                ),
            )

            // Soju's later NETWORK notifications may omit unchanged attrs. They must not replace the
            // child endpoint/nick with root defaults, which would churn the child's fingerprint.
            processor.process(rootId, IrcEvent.BouncerNetworkState("42", mapOf("name" to "OFTC")))

            val child = db.networkDao().childrenOf(rootId).single()
            assertEquals("irc.oftc.net", child.host)
            assertEquals(6698, child.port)
            assertEquals("childNick", child.nick)
        }

    // --- Round 5: server buffer routing ---

    private suspend fun serverBuffer() = db.bufferDao().byName(networkId, "*")

    private suspend fun setPrivateNoticeRouting(enabled: Boolean) {
        val network = db.networkDao().byId(networkId)!!
        db.networkDao().update(network.copy(routePrivateNoticesToServer = enabled))
    }

    private fun privateNotice(
        msgid: String,
        sender: String = "Helper",
        account: String? = null,
    ) = IrcEvent.ChatMessage(ctx(msgid, account = account), IrcEvent.ChatKind.NOTICE, Prefix(sender), "ME", "me: $msgid", false, null)

    @Test
    fun privateNoticeRoutingIsNetworkScopedAndHonorsIgnoreRules() =
        runTest {
            val other = db.networkDao().insert(db.networkDao().byId(networkId)!!.copy(id = 0, name = "other"))
            setPrivateNoticeRouting(true)
            db.networkIgnoreDao().upsert(NetworkIgnoreEntity(networkId = networkId, pattern = "blocked!*@*", createdAt = 1))
            db.networkIgnoreDao().upsert(
                NetworkIgnoreEntity(
                    networkId = networkId,
                    pattern = "helper!*@*",
                    createdAt = 1,
                    dropMessages = false,
                    suppressedSignals = SUPPRESS_UNREAD,
                ),
            )
            processor.process(networkId, privateNotice("hidden", "Blocked"))
            assertNull(serverBuffer())
            assertNull(db.bufferDao().byName(networkId, "blocked"))

            val notice = privateNotice("same-id", account = "acct")
            processor.processPush(networkId, notice)
            processor.process(other, notice)
            val row = pagingList(serverBuffer()!!.id).single()
            assertEquals(MessageKind.NOTICE, row.kind)
            assertEquals("Helper", row.sender)
            assertEquals("acct", row.senderAccount)
            assertFalse(row.hasMention)
            assertEquals(SUPPRESS_UNREAD, row.suppressedSignals)
            assertNull(db.bufferDao().byName(networkId, "helper"))
            assertNull(db.roomAliasDao().byValue(networkId, RoomAliasNamespace.ACCOUNT, "acct"))
            assertEquals("same-id", pagingList(db.bufferDao().byName(other, "helper")!!.id).single().msgid)
            assertNull(db.bufferDao().byName(other, "*"))

            processor.process(networkId, privateNotice("untagged", "Fresh").copy(ctx = ctx()))
            assertTrue(pagingList(serverBuffer()!!.id).any { it.text == "me: untagged" })
            assertNull(db.bufferDao().byName(networkId, "fresh"))
        }

    @Test
    fun privateNoticeRoutingRetainsNickFirstMergeAndAccountAliases() =
        runTest {
            setPrivateNoticeRouting(true)
            val provisional = privateNotice("first", "Peer[").copy(kind = IrcEvent.ChatKind.PRIVMSG)
            val identified = privateNotice("second", "OldNick", "acct").copy(kind = IrcEvent.ChatKind.PRIVMSG)
            processor.process(networkId, provisional)
            processor.process(networkId, identified)
            val first = db.bufferDao().byName(networkId, "peer{")!!.id
            val second = db.bufferDao().byName(networkId, "oldnick")!!.id
            processor.process(networkId, privateNotice("merged", "PEER{", "acct"))
            processor.process(networkId, privateNotice("alias", "NewNick", "acct"))
            processor.process(networkId, privateNotice("nick-only", "newnick"))
            assertEquals(first, db.bufferDao().canonicalId(second))
            assertEquals(setOf("first", "second", "merged", "alias", "nick-only"), pagingList(first).map { it.msgid }.toSet())
            assertNull(serverBuffer())

            processor.process(networkId, privateNotice("recycled", "NewNick", "other-owner"))
            assertEquals("recycled", pagingList(serverBuffer()!!.id).single().msgid)
            val replay = privateNotice("recycled-replay", "NewNick", "other-owner")
            processor.process(networkId, IrcEvent.ReplayBatch("NewNick", listOf(replay)))
            assertEquals(setOf("recycled", "recycled-replay"), pagingList(serverBuffer()!!.id).map { it.msgid }.toSet())
            assertNull(db.roomAliasDao().byValue(networkId, RoomAliasNamespace.ACCOUNT, "other-owner"))
        }

    @Test
    fun privateNoticeRoutingLeavesChannelContextSelfEchoAndNonPrivateTargetsAlone() =
        runTest {
            setPrivateNoticeRouting(true)
            processor.onRegistered(networkId, "me", mapOf("PREFIX" to "(ov)@+", "STATUSMSG" to "@+"))
            processor.process(networkId, IrcEvent.TopicSnapshot("#chan", "Help"))
            for (target in listOf("#chan", "@+#chan")) {
                processor.process(networkId, privateNotice(target).copy(target = target))
            }
            processor.process(
                networkId,
                privateNotice("context").copy(ctx = ctx("context").copy(clientTags = mapOf("+channel-context" to "#chan"))),
            )
            processor.processPush(networkId, privateNotice("context"))
            processor.process(networkId, privateNotice("echo", "me").copy(target = "Alice"))
            for (target in listOf("\$*.example", "someone", "me,someone")) {
                processor.process(networkId, privateNotice(target, "Oper").copy(target = target))
            }
            assertEquals(setOf("#chan", "@+#chan", "context"), pagingList(db.bufferDao().byName(networkId, "#chan")!!.id).map { it.msgid }.toSet())
            assertTrue(pagingList(db.bufferDao().byName(networkId, "alice")!!.id).single().isSelf)
            assertEquals(3, pagingList(db.bufferDao().byName(networkId, "oper")!!.id).size)
            assertNull(db.bufferDao().byName(networkId, "helper"))
            assertNull(serverBuffer())
            processor.process(networkId, privateNotice("system", "irc.example"))
            assertEquals("system", pagingList(serverBuffer()!!.id).single().msgid)
            assertNull(db.bufferDao().byName(networkId, "irc.example"))
        }

    @Test
    fun privateNoticeRoutingPreservesExactReplayWithoutBindingOrRevivingAQuery() =
        runTest {
            setPrivateNoticeRouting(true)
            val notice = privateNotice("server-copy")
            processor.process(networkId, IrcEvent.ReplayBatch("Helper", listOf(notice)))
            val server = serverBuffer()!!.id
            val original = pagingList(server).single()
            setPrivateNoticeRouting(false)
            processor.onRegistered(networkId, "renamed", emptyMap())
            processor.processPush(networkId, notice)
            assertNull(db.bufferDao().byName(networkId, "helper"))

            val later = privateNotice("later").copy(target = "renamed")
            processor.process(networkId, later)
            val query = db.bufferDao().byName(networkId, "helper")!!.id
            db.bufferDao().deleteBuffer(query)
            setPrivateNoticeRouting(true)
            val replay = privateNotice("server-copy", account = "new-account")
            processor.process(networkId, IrcEvent.HistoryBatch("Helper", listOf(replay, later)))
            assertTrue(db.bufferDao().rawById(query)!!.dismissed)
            assertTrue(pagingList(query).isEmpty())
            assertNull(db.roomAliasDao().byValue(networkId, RoomAliasNamespace.ACCOUNT, "new-account"))
            assertEquals(original.id, pagingList(server).single().id)
        }

    @Test
    fun privateNoticeRoutingKeepsQueryReactionsInMixedReplay() =
        runTest {
            setPrivateNoticeRouting(true)
            val dm = privateNotice("dm").copy(kind = IrcEvent.ChatKind.PRIVMSG)
            val react = IrcEvent.TagMessage(ctx(), Prefix("Helper"), "me", null, "+1", "dm")
            processor.process(networkId, IrcEvent.ReplayBatch("Helper", listOf(privateNotice("notice"), dm, react)))
            val query = db.bufferDao().byName(networkId, "helper")!!
            assertEquals("dm", pagingList(query.id).single().msgid)
            assertEquals(
                "+1",
                db
                    .reactionDao()
                    .forRoom(query.id)
                    .single()
                    .emoji,
            )
            assertEquals("notice", pagingList(serverBuffer()!!.id).single().msgid)
        }

    @Test
    fun privateNoticeRoutingKeepsExactQueryAboveAnotherRoomsDiscardFloor() =
        runTest {
            val old = privateNotice("old", "OldPeer")
            processor.process(networkId, old)
            val room = db.bufferDao().byName(networkId, "oldpeer")!!
            setPrivateNoticeRouting(true)
            val dm = privateNotice("dm", "NewPeer", "acct").copy(kind = IrcEvent.ChatKind.PRIVMSG, ctx = ctx("dm", 2_000, account = "acct"))
            processor.process(networkId, dm)
            db.bufferDao().deleteBuffer(db.bufferDao().byName(networkId, "newpeer")!!.id)
            processor.process(
                networkId,
                IrcEvent.HistoryBatch("unknown", listOf(old.copy(isBot = true), dm.copy(ctx = ctx("newer", 3_000, account = "acct")))),
            )
            assertTrue(pagingList(room.id).single().isBot)
            assertNull(db.bufferDao().byName(networkId, "unknown"))
        }

    @Test
    fun privateNoticeRoutingAllowsOnlyMatchingPeerRedaction() =
        runTest {
            setPrivateNoticeRouting(true)
            processor.process(networkId, privateNotice("notice", "Peer[", "acct"))
            processor.process(networkId, privateNotice("system", "irc.example"))
            val server = serverBuffer()!!.id
            val redact = IrcEvent.MessageRedacted(ctx(account = "acct"), Prefix("peer{"), "ME", "notice", null)
            processor.process(networkId, redact.copy(source = Prefix("other")))
            processor.process(networkId, redact.copy(target = "other"))
            processor.process(networkId, redact.copy(ctx = ctx(account = "other-account")))
            processor.process(networkId, IrcEvent.HistoryBatch("other", listOf(redact)))
            processor.process(networkId, redact.copy(targetMsgid = "system", source = Prefix("irc.example")))
            assertTrue(pagingList(server).all { it.kind == MessageKind.NOTICE })
            processor.process(networkId, IrcEvent.HistoryBatch("PEER[", listOf(redact)))
            assertEquals(MessageKind.REDACTED, db.messageDao().byMsgid(server, "notice")!!.kind)
            assertEquals(MessageKind.NOTICE, db.messageDao().byMsgid(server, "system")!!.kind)
            assertNull(db.bufferDao().byName(networkId, "peer{"))
        }

    @Test
    fun serverNotice_routesToServerBuffer_notQuery() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("irc.libera.chat"),
                    target = "me",
                    text = "*** Looking up your hostname",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val server = serverBuffer()
            assertNotNull(server)
            assertEquals(BufferType.SERVER, server!!.type)
            assertEquals("libera", server.displayName)
            assertEquals("*** Looking up your hostname", pagingList(server.id).single().text)
            // No junk query buffer for the host source.
            assertNull(db.bufferDao().byName(networkId, "irc.libera.chat"))
        }

    @Test
    fun repeatedHistoryServerNoticesUseServerRoutingAndPreserveMultiplicity() =
        runTest {
            val notice =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = null, time = 2_000).copy(batchId = "notice-history"),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("irc.libera.chat"),
                    target = "me",
                    text = "same historical notice",
                    isSelf = false,
                    replyToMsgid = null,
                )
            val batch = IrcEvent.HistoryBatch("me", listOf(notice, notice))

            processor.process(networkId, batch)
            processor.process(networkId, batch)

            val server = checkNotNull(serverBuffer())
            assertEquals(2, pagingList(server.id).count { it.kind == MessageKind.NOTICE })
            assertNull(db.bufferDao().byName(networkId, "irc.libera.chat"))
        }

    @Test
    fun historyPreflightRoomCreationRollsBackWithFailedBatch() =
        runTest {
            db.openHelper.writableDatabase.execSQL(
                """CREATE TRIGGER reject_history_message BEFORE INSERT ON messages
               BEGIN SELECT RAISE(ABORT, 'forced history failure'); END""",
            )
            val message =
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "rollback-history", time = 2_100).copy(batchId = "rollback"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#rollback",
                    text = "must roll back with its room",
                    isSelf = false,
                    replyToMsgid = null,
                )

            val result =
                runCatching {
                    processor.process(networkId, IrcEvent.HistoryBatch("#rollback", listOf(message)))
                }

            assertTrue(result.isFailure)
            assertNull(db.bufferDao().byName(networkId, "#rollback"))
        }

    @Test
    fun repeatedAccountBackedPmHistoryUsesCanonicalAccountRoom() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx(msgid = "known-account", time = 1_000).copy(account = "acct-a"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "me",
                    "establish account room",
                    false,
                    null,
                ),
            )
            val knownRoom = db.bufferDao().byName(networkId, "alice")!!
            val repeated =
                IrcEvent.ChatMessage(
                    ctx(msgid = null, time = 2_200).copy(account = "acct-a", batchId = "account-history"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "same account history",
                    false,
                    null,
                )
            val batch = IrcEvent.HistoryBatch("bob", listOf(repeated, repeated))

            processor.process(networkId, batch)
            processor.process(networkId, batch)

            assertEquals(3, pagingList(knownRoom.id).size)
            assertEquals(2, pagingList(knownRoom.id).count { it.text == "same account history" })
            assertNull(db.bufferDao().byName(networkId, "bob"))
        }

    @Test
    fun msgidlessPushServerNotice_persistsCanonicalServerEvent() =
        runTest {
            processor.processPush(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("irc.libera.chat"),
                    target = "me",
                    text = "transient server notice",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val server = serverBuffer()
            assertNotNull(server)
            assertEquals(BufferType.SERVER, server!!.type)
            assertEquals(1, pagingList(server.id).size)
            assertNull(db.bufferDao().byName(networkId, "irc.libera.chat"))
        }

    @Test
    fun nickServNotice_stillCreatesQueryBuffer() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("NickServ"),
                    target = "me",
                    text = "This nick is registered.",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val query = db.bufferDao().byName(networkId, "nickserv")
            assertNotNull(query)
            assertEquals(BufferType.QUERY, query!!.type)
            assertNull(serverBuffer())
        }

    @Test
    fun equalTimeNickServPushesKeepBroadcastOrderWithoutSocketCopies() =
        runTest {
            val lines = (1..7).map { "Notice $it" }
            val firstDecrypting = CompletableDeferred<Unit>()
            val releaseFirst = CountDownLatch(1)
            val handler =
                PushEventHandler(
                    crypto = { body, _ ->
                        if (body.decodeToString().endsWith(lines.first())) {
                            firstDecrypting.complete(Unit)
                            check(releaseFirst.await(15, TimeUnit.SECONDS))
                        }
                        body
                    },
                    eventSink = processor,
                )
            val keys = WebPushCrypto.generateKeyMaterial()

            fun receive(line: String) =
                launchUnifiedPushBroadcast("org.unifiedpush.android.connector.MESSAGE") {
                    val payload = "@time=2026-11-25T06:32:00.000Z :NickServ!service@fixture NOTICE me :$line"
                    runBlocking { handler.handle(networkId, payload.encodeToByteArray(), keys) }
                }

            withContext(Dispatchers.Default) {
                val first = receive(lines.first())
                val jobs = mutableListOf(first)
                try {
                    withTimeout(5_000) { firstDecrypting.await() }
                    val later = lines.drop(1).map(::receive)
                    jobs += later
                    val lifecycle = CompletableDeferred<Unit>()
                    jobs += launchUnifiedPushBroadcast("org.unifiedpush.android.connector.UNREGISTERED") { lifecycle.complete(Unit) }
                    withTimeout(5_000) { lifecycle.await() }
                    withTimeoutOrNull(1_000) { later.joinAll() }
                } finally {
                    releaseFirst.countDown()
                    withTimeout(5_000) { jobs.joinAll() }
                }
            }

            val room = requireNotNull(db.bufferDao().byName(networkId, "nickserv"))
            assertEquals(lines, pagingList(room.id).asReversed().map { it.text })
        }

    @Test
    fun pushFirstNickServNoticesFollowSocketOrderAtSameTimestamp() =
        runTest {
            val first =
                IrcEvent.ChatMessage(
                    ctx = ctx(time = 5_000),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("NickServ"),
                    target = "me",
                    text = "This nickname is registered and protected.",
                    isSelf = false,
                    replyToMsgid = null,
                )
            val second = first.copy(text = "Authenticate yourself to services with the IDENTIFY command.")
            val third = first.copy(text = "You have been automatically identified.")
            val pushOnly = first.copy(text = "This notice did not reach the socket.")
            processor.processPush(networkId, third)
            processor.processPush(networkId, first)
            processor.processPush(networkId, pushOnly)

            val room = requireNotNull(db.bufferDao().byName(networkId, "nickserv"))
            val pushedIds = pagingList(room.id).associate { it.text to it.id }
            processor.process(networkId, first)
            assertEquals(listOf(pushOnly.text, first.text, third.text), pagingList(room.id).map { it.text })

            processor.process(networkId, second)
            val laterPush = first.copy(text = "This notice is still awaiting a socket copy.")
            processor.processPush(networkId, laterPush)

            val last = first.copy(text = "Welcome back.")
            for (notice in listOf(third, last, first)) {
                processor.process(networkId, notice)
            }

            val rows = pagingList(room.id)
            assertEquals(listOf(last.text, laterPush.text, third.text, second.text, pushOnly.text, first.text), rows.map { it.text })
            assertEquals(pushedIds, rows.filter { it.text in pushedIds }.associate { it.text to it.id })
        }

    @Test
    fun serverError_insertsErrorRow() =
        runTest {
            processor.process(networkId, IrcEvent.ServerError("482", listOf("me", "#chan"), "You're not a channel operator"))
            val server = serverBuffer()!!
            val row = pagingList(server.id).single()
            assertEquals(MessageKind.ERROR, row.kind)
            assertTrue(row.text.startsWith("482"))
        }

    @Test
    fun whitelistedNumeric_insertsServerInfo_withNickDropped() =
        runTest {
            // 375 RPL_MOTDSTART: params = [me, "- server Message of the Day -"].
            processor.process(
                networkId,
                IrcEvent.Raw(
                    io.github.trevarj.motd.irc.proto
                        .IrcMessage(command = "375", params = listOf("me", "- Message of the Day -")),
                ),
            )
            val server = serverBuffer()!!
            val row = pagingList(server.id).single()
            assertEquals(MessageKind.SERVER_INFO, row.kind)
            assertEquals("- Message of the Day -", row.text) // our nick dropped
        }

    @Test
    fun nonWhitelistedRaw_isDropped() =
        runTest {
            // 322 (RPL_LIST) is deliberately excluded so LIST never floods the server buffer.
            processor.process(
                networkId,
                IrcEvent.Raw(
                    io.github.trevarj.motd.irc.proto
                        .IrcMessage(command = "322", params = listOf("me", "#chan", "42", "topic")),
                ),
            )
            assertNull(serverBuffer())
        }

    @Test
    fun dickordDescriptorSeedTagCreatesRawChannelWithoutVisibleMessage() =
        runTest {
            val json =
                """{"v":1,"guild_id":"123","guild_name":"Guild","channel_id":"456","channel_type":0,"parent_id":null}"""

            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx().copy(clientTags = mapOf(DICKORD_CHANNEL_TAG to json)),
                    source = Prefix("seed-service"),
                    target = "#Discord.Guild.General",
                    typing = null,
                    reactEmoji = null,
                    reactTargetMsgid = null,
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#discord.guild.general")!!
            assertEquals("#discord.guild.general", buffer.name)
            assertEquals("#Discord.Guild.General", buffer.displayName)
            assertEquals(json, buffer.dickordChannelJson)
            assertTrue(pagingList(buffer.id).isEmpty())
        }

    @Test
    fun dickordDescriptorTagsOnHistoricalAndLiveChatUpdateRawChannelWithoutChangingMessages() =
        runTest {
            val historicalJson =
                """{"v":1,"guild_id":"123","guild_name":"Guild","channel_id":"456","channel_type":0,"parent_id":null}"""
            val liveJson =
                """{"v":1,"guild_id":"123","guild_name":"Guild","channel_id":"789","channel_type":0,"parent_id":null}"""
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#Discord.Guild.General",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx =
                                ctx(msgid = "history", time = 1_000).copy(
                                    batchId = "history",
                                    clientTags = mapOf(DICKORD_CHANNEL_TAG to historicalJson),
                                ),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("Alice/discord"),
                            target = "#Discord.Guild.General",
                            text = "historical raw text",
                            isSelf = false,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#discord.guild.general")!!
            assertEquals(historicalJson, buffer.dickordChannelJson)

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx =
                        ctx(msgid = "live", time = 2_000).copy(
                            clientTags = mapOf(DICKORD_CHANNEL_TAG to liveJson),
                        ),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("Bob/discord"),
                    target = "#DISCORD.GUILD.GENERAL",
                    text = "live raw text",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            assertEquals(liveJson, db.bufferDao().observeById(buffer.id)?.dickordChannelJson)
            assertEquals("#Discord.Guild.General", db.bufferDao().observeById(buffer.id)?.displayName)
            val messages = pagingList(buffer.id).associateBy { it.msgid }
            assertEquals("Alice/discord", messages.getValue("history").sender)
            assertEquals("historical raw text", messages.getValue("history").text)
            assertEquals("Bob/discord", messages.getValue("live").sender)
            assertEquals("live raw text", messages.getValue("live").text)
        }

    @Test
    fun invalidOrEmptyDickordDescriptorTagsPreserveExistingValue() =
        runTest {
            val currentJson =
                """{"v":1,"guild_id":"123","guild_name":"Guild","channel_id":"456","channel_type":0,"parent_id":null}"""
            val bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#discord.guild.general",
                        displayName = "#Discord.Guild.General",
                        type = BufferType.CHANNEL,
                        joined = false,
                        dickordChannelJson = currentJson,
                    ),
                )
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx =
                        ctx().copy(
                            clientTags =
                                mapOf(
                                    DICKORD_CHANNEL_TAG to
                                        """{"v":2,"guild_id":"123","guild_name":"Bad","channel_id":"789","channel_type":0,"parent_id":null}""",
                                ),
                        ),
                    source = Prefix("seed-service"),
                    target = "#Discord.Guild.General",
                    typing = null,
                    reactEmoji = null,
                    reactTargetMsgid = null,
                ),
            )
            assertEquals(currentJson, db.bufferDao().observeById(bufferId)?.dickordChannelJson)

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "empty").copy(clientTags = mapOf(DICKORD_CHANNEL_TAG to "")),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("Alice/discord"),
                    target = "#Discord.Guild.General",
                    text = "empty descriptor",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#Discord.Guild.General",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx =
                                ctx(msgid = "malformed", time = 2_000).copy(
                                    batchId = "history",
                                    clientTags = mapOf(DICKORD_CHANNEL_TAG to "{"),
                                ),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("Bob/discord"),
                            target = "#Discord.Guild.General",
                            text = "malformed descriptor",
                            isSelf = false,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )

            assertEquals(currentJson, db.bufferDao().observeById(bufferId)?.dickordChannelJson)
        }

    @Test
    fun dickordDescriptorTagsRejectNonDickordTargets() =
        runTest {
            val json =
                """{"v":1,"guild_id":"123","guild_name":"Guild","channel_id":"456","channel_type":0,"parent_id":null}"""
            val target = "#discorded.guild.general"
            processor.process(
                networkId,
                IrcEvent.TagMessage(
                    ctx = ctx().copy(clientTags = mapOf(DICKORD_CHANNEL_TAG to json)),
                    source = Prefix("seed-service"),
                    target = target,
                    typing = null,
                    reactEmoji = null,
                    reactTargetMsgid = null,
                ),
            )
            assertNull(db.bufferDao().byName(networkId, target))

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "ordinary").copy(clientTags = mapOf(DICKORD_CHANNEL_TAG to json)),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("Alice/discord"),
                    target = target,
                    text = "ordinary raw text",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val buffer = db.bufferDao().byName(networkId, target)!!
            assertNull(buffer.dickordChannelJson)
            assertEquals("ordinary raw text", pagingList(buffer.id).single().text)
        }

    @Test
    fun labeledCommandRepliesRouteToTheirOriginatingChats() =
        runTest {
            val first =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#one", displayName = "#one", type = BufferType.CHANNEL),
                )
            val second =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#two", displayName = "#two", type = BufferType.CHANNEL),
                )
            processor.beginCommandResponse(networkId, first, "MOTD", "first")
            processor.beginCommandResponse(networkId, second, "LINKS", "second")

            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage(tags = mapOf("label" to "second"), command = "364", params = listOf("me", "link"))),
            )
            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage(tags = mapOf("label" to "first"), command = "372", params = listOf("me", "motd"))),
            )

            assertEquals("link", pagingList(second).single().text)
            assertEquals("motd", pagingList(first).single().text)
            assertTrue(pagingList(first).single().eventPayload!!.startsWith(COMMAND_RESPONSE_PAYLOAD_PREFIX))
            assertNull(serverBuffer())
        }

    @Test
    fun labeledCommandAckCompletesWithoutCreatingAnEmptyPill() =
        runTest {
            val origin =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#origin", displayName = "#origin", type = BufferType.CHANNEL),
                )
            processor.beginCommandResponse(networkId, origin, "NICK", "nick-label")

            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage(tags = mapOf("label" to "nick-label"), command = "ACK")),
            )

            assertTrue(pagingList(origin).isEmpty())
            assertNull(serverBuffer())
        }

    @Test
    fun unlabeledCommandUsesLatestOriginUntilTerminalOrTimeout() =
        runTest {
            val old =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#old", displayName = "#old", type = BufferType.CHANNEL),
                )
            val latest =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#latest", displayName = "#latest", type = BufferType.CHANNEL),
                )
            processor.beginCommandResponse(networkId, old, "MOTD", null)
            processor.beginCommandResponse(networkId, latest, "MOTD", null)
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "372", params = listOf("me", "latest"))))
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "376", params = listOf("me", "done"))))
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "372", params = listOf("me", "server"))))

            assertTrue(pagingList(old).isEmpty())
            assertEquals(listOf("done", "latest"), pagingList(latest).map { it.text }.sorted())
            assertEquals("server", pagingList(serverBuffer()!!.id).single().text)

            processor.beginCommandResponse(networkId, old, "MOTD", null, now = 0)
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "372", params = listOf("me", "expired"))))
            assertTrue(pagingList(old).isEmpty())
        }

    @Test
    fun unlabeledMetadataQueriesConsumeOnlyTheirOwnSnapshotAndClose() =
        runTest {
            val origin =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#origin", displayName = "#origin", type = BufferType.CHANNEL),
                )
            val automatic = EventProcessor.MetadataSnapshotRouting.AUTOMATIC
            val positiveOnly = EventProcessor.MetadataSnapshotRouting.POSITIVE_ONLY
            val now = System.currentTimeMillis()
            processor.beginCommandResponse(networkId, origin, "METADATA", null, now, listOf("#chat", "get", "status"))
            val status = IrcMessage.parse(":srv 761 me #CHAT status * :busy")
            val avatar = IrcMessage.parse(":srv 761 me #chat avatar * :https://example.com/avatar.png")

            suspend fun route(
                target: String,
                vararg messages: IrcMessage,
                at: Long = now,
            ) = processor.routeMetadataSnapshot(networkId, IrcEvent.MetadataSnapshot(target, messages.toList()), at)
            assertEquals(automatic, route("#elsewhere", status))
            assertEquals(positiveOnly, route("#chat", avatar))
            assertEquals(positiveOnly, route("#chat"))
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "372", params = listOf("me", "server"))))
            assertEquals(positiveOnly, route("#CHAT", status))
            assertEquals(positiveOnly, route("#chat", status))
            assertEquals(positiveOnly, route("#chat"))
            assertEquals(automatic, route("#chat", status, at = now + 30_001))
            assertEquals("#CHAT status * busy", pagingList(origin).single().text)
            assertEquals("server", pagingList(serverBuffer()!!.id).single().text)

            processor.beginCommandResponse(networkId, origin, "METADATA", null, now, listOf("#chat", "LIST"))
            assertEquals(positiveOnly, route("#chat"))
            assertEquals(positiveOnly, route("#chat", status))
            assertEquals(1, pagingList(origin).size)
        }

    @Test
    fun commandErrorRoutesToOriginInsteadOfProtocolTarget() =
        runTest {
            val origin =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#origin", displayName = "#origin", type = BufferType.CHANNEL),
                )
            db.bufferDao().insert(
                BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
            )
            processor.beginCommandResponse(networkId, origin, "JOIN", "join-label")

            processor.process(
                networkId,
                IrcEvent.ServerError(
                    "473",
                    listOf("me", "#other"),
                    "Cannot join channel (+i)",
                    ctx(label = "join-label"),
                ),
            )

            val row = pagingList(origin).single()
            assertEquals(MessageKind.ERROR, row.kind)
            assertTrue(row.text.startsWith("473"))
            assertNull(serverBuffer())
        }

    @Test
    fun whoisIsSheetOnlyUnlessSentAsTrackedRawCommand() =
        runTest {
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "311", params = listOf("me", "alice", "u", "h"))))
            assertNull(serverBuffer())

            val origin =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#origin", displayName = "#origin", type = BufferType.CHANNEL),
                )
            processor.beginCommandResponse(networkId, origin, "WHOIS", null)
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "311", params = listOf("me", "alice", "u", "h"))))
            processor.process(networkId, IrcEvent.Raw(IrcMessage(command = "318", params = listOf("me", "alice", "done"))))

            assertEquals(2, pagingList(origin).size)
            assertNull(serverBuffer())
        }

    @Test
    fun disconnected_insertsServerInfoMarker() =
        runTest {
            processor.process(networkId, IrcEvent.Disconnected("connection reset"))
            val server = serverBuffer()!!
            val row = pagingList(server.id).single()
            assertEquals(MessageKind.SERVER_INFO, row.kind)
            assertEquals("disconnected: connection reset", row.text)
        }

    @Test
    fun serverBufferMention_doesNotNotify() =
        runTest {
            // A recording notifier; a motd line containing our nick must not fire.
            var fired = false
            val recProcessor =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            fired = true
                        }
                    },
                )
            recProcessor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            recProcessor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("irc.libera.chat"),
                    target = "me",
                    text = "welcome me to the server",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            assertFalse(fired)
            // The line still landed in the server buffer.
            assertNotNull(serverBuffer())
        }

    @Test
    fun bouncerServ_isServerConsole_notChat_butItsNoticesStillNotify() =
        runTest {
            makeBouncerRoot()
            setPrivateNoticeRouting(true)
            val notifiedKinds = mutableListOf<IrcEvent.ChatKind>()
            val recording =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notifiedKinds += message.kind
                        }
                    },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

            recording.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "service-reply", time = 2_000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("BouncerServ"),
                    target = "me",
                    text = "network status for me",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            recording.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "service-notice", time = 3_000),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("BouncerServ"),
                    target = "me",
                    text = "detached relay",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "bouncerserv")!!
            assertEquals(BufferType.SERVER, buffer.type)
            // The console is hidden from the chat list, so a swallowed NOTICE would leave soju's
            // "network X disconnected" warning with no signal at all. Its PRIVMSG replies stay quiet.
            assertEquals(listOf(IrcEvent.ChatKind.NOTICE), notifiedKinds)
            assertTrue(
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .isEmpty(),
            )
        }

    /** Off a bouncer root that nick is an ordinary user: the DM must stay a query and notify. */
    @Test
    fun bouncerServ_onAPlainNetwork_staysAQueryAndNotifies() =
        runTest {
            val notifiedKinds = mutableListOf<IrcEvent.ChatKind>()
            val recording =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notifiedKinds += message.kind
                        }
                    },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

            recording.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "peer-dm", time = 2_000),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("BouncerServ"),
                    target = "me",
                    text = "hey, nice nick",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "bouncerserv")!!
            assertEquals(BufferType.QUERY, buffer.type)
            assertEquals(listOf(IrcEvent.ChatKind.PRIVMSG), notifiedKinds)
            assertEquals(
                listOf(buffer.id),
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .map { it.bufferId },
            )
            // A real user's words are stored as sent: redaction is console-only.
            assertEquals("hey, nice nick", pagingList(buffer.id).single().text)
        }

    /** The history path asks for a QUERY; `BufferStore` still returns the console. */
    @Test
    fun bouncerServ_historyQueryPath_stillYieldsTheServerConsole() =
        runTest {
            makeBouncerRoot()
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

            val roomId = processor.ensureHistoryQuery(networkId, "BouncerServ", "bouncerserv")

            val buffer = db.bufferDao().byName(networkId, "bouncerserv")!!
            assertEquals(roomId, buffer.id)
            assertEquals(BufferType.SERVER, buffer.type)
            assertTrue(
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun bouncerServ_livePrivmsg_reusesTheSingleConsoleRoom() =
        runTest {
            makeBouncerRoot()
            val existing = processor.ensureHistoryQuery(networkId, "BouncerServ", "bouncerserv")
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "promote"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("BouncerServ"),
                    target = "me",
                    text = "help",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "bouncerserv")!!
            assertEquals(existing, buffer.id)
            assertEquals(BufferType.SERVER, buffer.type)
            assertTrue(
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun bouncerServ_self_echo_is_redacted_before_room_and_dedup() =
        runTest {
            makeBouncerRoot()
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "secret-command"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "BouncerServ",
                    text = "sasl set-plain -network libera alice hunter2",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "bouncerserv")!!
            assertEquals(BufferType.SERVER, buffer.type)
            val row = pagingList(buffer.id).single()
            assertFalse(row.text.contains("hunter2"))
            assertTrue(row.text.contains("<redacted>"))
        }

    /**
     * Targeted replay resolves the console SERVER-typed, so a redaction gated on "this is a DM"
     * never fired and stored the credential verbatim — readable from search.
     */
    @Test
    fun bouncerServ_replayedEcho_isRedactedOnTheTargetedHistoryRoute() =
        runTest {
            makeBouncerRoot()
            val console = processor.ensureHistoryQuery(networkId, "BouncerServ", "bouncerserv")
            val echo =
                IrcEvent.ChatMessage(
                    ctx("replayed-command", 100).copy(batchId = "history"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("me"),
                    "BouncerServ",
                    "sasl set-plain -network libera alice hunter2",
                    true,
                    null,
                )

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "BouncerServ", limit = 50),
                ChatHistoryResponse.Messages(
                    events = listOf(echo),
                    oldest = ChatHistoryReference("replayed-command", 100),
                    newest = ChatHistoryReference("replayed-command", 100),
                    endOfHistory = false,
                    primaryMessageCount = 1,
                ),
                expectedRoomId = console,
            )

            val row = pagingList(console).single()
            assertFalse(row.text.contains("hunter2"))
            assertTrue(row.text.contains("<redacted>"))
        }

    // --- own-message single-row across the three echo scenarios ---

    /** Buffer id for a self-send target, creating the query/channel buffer as onChat would. */
    private suspend fun selfBuffer(target: String): Long {
        // A channel target starts with '#'; DMs key by the other party but self-sends key by target.
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx = ctx(msgid = null, time = 500),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("me"),
                target = target,
                text = "__seed__",
                isSelf = true,
                replyToMsgid = null,
            ),
        )
        val name = if (target.startsWith("#")) target else target
        return db.bufferDao().byName(networkId, name)!!.id
    }

    @Test
    fun ownMessage_echoWithLabeledResponse_singleRow() =
        runTest {
            val bufferId = selfBuffer("#chan")
            // Pending insert (labeled send).
            processor.insertPending(bufferId, "lbl1", "me", "hey there", null, MessageKind.PRIVMSG)
            // Labeled echo confirms in place.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "srv1", time = 2000, label = "lbl1"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "hey there",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val rows = pagingList(bufferId).filter { it.text == "hey there" }
            assertEquals(1, rows.size)
            assertEquals("srv1", rows.single().msgid)
            assertNull(rows.single().pendingLabel)
        }

    @Test
    fun ownMessage_echoOnly_noLabel_collapsesToSingleRow() =
        runTest {
            val bufferId = selfBuffer("#chan")
            // (c/b) Confirmed-local insert as the no-labeled-response send path does: self ChatMessage,
            // no msgid, no label, local clock.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = null, time = 1000, label = null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "echo test",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            // Server echo-message arrives with a real msgid and a slightly different (server) time.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "srv2", time = 1200, label = null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "echo test",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val rows = pagingList(bufferId).filter { it.text == "echo test" }
            assertEquals(1, rows.size)
            assertEquals("srv2", rows.single().msgid)
            // A later CHATHISTORY replay of the same msgid is ignored.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "srv2", time = 1200, label = null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "echo test",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            assertEquals(1, pagingList(bufferId).count { it.text == "echo test" })
        }

    @Test
    fun ownMessage_pendingThenUnlabeledEcho_collapsesAndConfirms() =
        runTest {
            val bufferId = selfBuffer("bob") // DM query buffer
            // Labeled send inserts a pending row (serverTime = wall-clock now, like production)...
            processor.insertPending(bufferId, "lblx", "me", "dm body", null, MessageKind.PRIVMSG)
            // ...but the echo comes back WITHOUT the label (server dropped labeled-response mid-flight).
            // Its serverTime is the server clock, ~now (within the 30s echo window of the pending row).
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "srv3", time = System.currentTimeMillis(), label = null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "bob",
                    text = "dm body",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val rows = pagingList(bufferId).filter { it.text == "dm body" }
            assertEquals(1, rows.size)
            assertEquals("srv3", rows.single().msgid)
            assertNull(rows.single().pendingLabel)
        }

    @Test
    fun ownMessage_localInsertOnly_neitherEchoNorLabel_singleRow() =
        runTest {
            val bufferId = selfBuffer("#chan")
            // (c) No echo-message, no labeled-response: only the confirmed-local insert exists.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = null, time = 1000, label = null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "lonely",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val rows = pagingList(bufferId).filter { it.text == "lonely" }
            assertEquals(1, rows.size)
            assertNull(rows.single().pendingLabel)
        }

    @Test
    fun successfulLegacyWriteConfirmsPendingAndCannotLaterTimeoutAsFailed() =
        runTest {
            val bufferId = selfBuffer("#chan")
            val eventId =
                processor.insertPending(
                    bufferId,
                    "local-attempt",
                    "me",
                    "legacy delivered",
                    null,
                    MessageKind.PRIVMSG,
                )

            processor.confirmIfStillPending(bufferId, "local-attempt")
            processor.failIfStillPending(bufferId, "local-attempt")

            val row = db.messageDao().byId(eventId)
            assertNull(row?.pendingLabel)
            assertFalse(row?.failed ?: true)
        }

    @Test
    fun ownMessage_bareEchoThenLaterHistoryWithMsgid_collapsesByMsgid() =
        runTest {
            // The remaining double-send: a self message confirmed by a BARE echo (no msgid) keeps a
            // msgid-less local row. A reconnect CHATHISTORY replay later delivers the SAME message with
            // a real msgid. Its arrival is later, but tagged server-time remains the original event
            // time, allowing conservative bounded reconciliation without matching a later repeat.
            val bufferId = selfBuffer("#chan")
            // 1. optimistic pending send.
            processor.insertPending(bufferId, "lblz", "me", "double me", null, MessageKind.PRIVMSG)
            // 2. bare echo (echo-message on, but this echo carries no draft/msgid) confirms in place.
            val originalServerTime = System.currentTimeMillis()
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = null, time = originalServerTime, label = "lblz"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "double me",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            assertEquals(1, pagingList(bufferId).count { it.text == "double me" })
            // 3. A much-later CHATHISTORY replay carries the original tagged server time and msgid.
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx = ctx(msgid = "hist-msgid", time = originalServerTime, label = null),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("me"),
                            target = "#chan",
                            text = "double me",
                            isSelf = true,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )
            val rows = pagingList(bufferId).filter { it.text == "double me" }
            assertEquals(1, rows.size)
            assertEquals("hist-msgid", rows.single().msgid)
        }

    @Test
    fun ownMessage_distinctSelfSendsSameText_notMerged_outsideWindow() =
        runTest {
            val bufferId = selfBuffer("#chan")
            // Two genuinely separate self-sends of the same text far apart in time must stay separate.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "a1", time = 1000, label = null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "same",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = ctx(msgid = "a2", time = 1000 + 60_000, label = null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "same",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            assertEquals(2, pagingList(bufferId).count { it.text == "same" })
        }

    // --- live self-join idempotency ---

    @Test
    fun selfJoin_idempotent_acrossRepeatedLiveJoins() =
        runTest {
            // Live self-join (no msgid).
            processor.process(networkId, IrcEvent.Joined(ctx(msgid = null, time = 1000), "me", "#chan", null, null, isSelf = true))
            val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
            // A repeated live self-join with a msgid still belongs to the same membership cycle.
            processor.process(networkId, IrcEvent.Joined(ctx(msgid = "j-play", time = 1000), "me", "#chan", null, null, isSelf = true))
            // Reconnect → another live self-join, no msgid, different time.
            processor.process(networkId, IrcEvent.Joined(ctx(msgid = null, time = 9999), "me", "#chan", null, null, isSelf = true))
            val joins = pagingList(bufferId).filter { it.kind == MessageKind.JOIN }
            assertEquals(1, joins.size)
        }

    @Test
    fun otherJoin_notCollapsedBySelfJoinKey() =
        runTest {
            // A self-join and someone else's join to the same buffer must both appear.
            processor.process(networkId, IrcEvent.Joined(ctx(msgid = null, time = 1000), "me", "#chan", null, null, isSelf = true))
            processor.process(networkId, IrcEvent.Joined(ctx(msgid = "oj", time = 1001), "alice", "#chan", null, null, isSelf = false))
            val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
            assertEquals(2, pagingList(bufferId).count { it.kind == MessageKind.JOIN })
        }

    @Test
    fun selfJoinAfterExplicitPartStartsNewMembershipCycle() =
        runTest {
            val firstJoin =
                IrcEvent.Joined(
                    ctx(msgid = null, time = 1_000),
                    "me",
                    "#chan",
                    null,
                    null,
                    true,
                )
            processor.process(
                networkId,
                firstJoin,
            )
            processor.process(
                networkId,
                IrcEvent.Parted(ctx(msgid = null, time = 2_000), "me", "#chan", "leaving", true),
            )
            processor.process(
                networkId,
                IrcEvent.Joined(ctx(msgid = null, time = 3_000), "me", "#chan", null, null, true),
            )
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(firstJoin)))

            val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
            assertEquals(2, pagingList(bufferId).count { it.kind == MessageKind.JOIN })
            assertEquals(1, pagingList(bufferId).count { it.kind == MessageKind.PART })
        }

    @Test
    fun selfJoinAfterKickStartsNewCycleWithoutImportingHistoricalJoin() =
        runTest {
            val firstJoin =
                IrcEvent.Joined(
                    ctx(msgid = null, time = 1_000),
                    "me",
                    "#chan",
                    null,
                    null,
                    true,
                )
            processor.process(networkId, firstJoin)
            processor.process(
                networkId,
                IrcEvent.Kicked(ctx(msgid = null, time = 2_000), "me", "#chan", "op", null, true),
            )
            processor.process(
                networkId,
                IrcEvent.Joined(ctx(msgid = null, time = 3_000), "me", "#chan", null, null, true),
            )
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(firstJoin)))

            val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
            assertEquals(2, pagingList(bufferId).count { it.kind == MessageKind.JOIN })
            assertEquals(1, pagingList(bufferId).count { it.kind == MessageKind.KICK })
        }

    @Test
    fun selfPartAndMembershipCycleAdvanceRollbackTogether() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Joined(ctx(msgid = null, time = 1_000), "me", "#chan", null, null, true),
            )
            val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
            db.openHelper.writableDatabase.execSQL(
                """CREATE TRIGGER fail_membership_cycle BEFORE UPDATE OF membershipCycle ON buffers
               BEGIN SELECT RAISE(ABORT, 'cycle failure'); END""",
            )

            val failure =
                runCatching {
                    processor.process(
                        networkId,
                        IrcEvent.Parted(ctx(msgid = null, time = 2_000), "me", "#chan", null, true),
                    )
                }

            assertTrue(failure.isFailure)
            assertEquals(0L, db.bufferDao().observeById(bufferId)?.membershipCycle)
            assertEquals(true, db.bufferDao().observeById(bufferId)?.joined)
            assertEquals(0, pagingList(bufferId).count { it.kind == MessageKind.PART })
        }

    @Test
    fun namesUserhost_convergesIdentityWithoutErasingOnPlainSnapshot() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Names(
                    "#Room",
                    listOf(IrcEvent.Names.Member("Nick", "@+", "~user", "host.example")),
                ),
            )
            val buffer = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(
                "@+",
                db
                    .memberDao()
                    .observe(buffer.id)
                    .first()
                    .single()
                    .prefixes,
            )
            val enriched = db.userDao().byNick(networkId, "nick")!!
            assertEquals("~user", enriched.username)
            assertEquals("~user@host.example", enriched.hostmask)

            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("NICK", "@", null, null))),
            )
            assertEquals("~user@host.example", db.userDao().byNick(networkId, "nick")?.hostmask)

            processor.process(networkId, IrcEvent.HostChanged("Nick", "new", "cloak.example"))
            val changed = db.userDao().byNick(networkId, "nick")!!
            assertEquals("new@cloak.example", changed.hostmask)
        }

    @Test
    fun whoxRow_enrichesUserWithoutChangingMembershipPrefixes() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Nick", "@+", null, null))),
            )
            processor.process(
                networkId,
                IrcEvent.WhoxRow(
                    token = 12,
                    username = "~user",
                    host = "cloak.example",
                    nick = "NICK",
                    account = "account",
                    flags = "G@",
                    realname = "Real Name",
                ),
            )

            val user = db.userDao().byNick(networkId, "nick")!!
            assertEquals("~user", user.username)
            assertEquals("~user@cloak.example", user.hostmask)
            assertEquals("account", user.account)
            assertTrue(user.away)
            assertEquals("Real Name", user.realname)
            val buffer = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(
                "@+",
                db
                    .memberDao()
                    .observe(buffer.id)
                    .first()
                    .single()
                    .prefixes,
            )
        }

    @Test
    fun partialWhoxRow_updatesSafeIdentity_withoutInventingAwayOrErasingRealname() =
        runTest {
            processor.process(networkId, IrcEvent.AccountChanged("Nick", "old-account"))
            processor.process(networkId, IrcEvent.AwayChanged("Nick", "gone"))
            processor.process(networkId, IrcEvent.RealnameChanged("Nick", "Known Name"))

            processor.process(
                networkId,
                IrcEvent.WhoxRow(
                    token = 13,
                    username = "~new",
                    host = "new.example",
                    nick = "NICK",
                    account = null,
                    flags = null,
                    realname = null,
                ),
            )

            val user = db.userDao().byNick(networkId, "nick")!!
            assertEquals("~new", user.username)
            assertEquals("~new@new.example", user.hostmask)
            assertEquals(null, user.account)
            assertTrue(user.away)
            assertEquals("Known Name", user.realname)
        }

    @Test
    fun namesSnapshot_replaysJoinThatArrivesBeforeEndOfNames() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Joined(ctx(), "me", "#room", null, null, isSelf = true),
            )
            processor.process(networkId, IrcEvent.NamesStarted("#room"))
            processor.process(
                networkId,
                IrcEvent.Joined(ctx(time = 1001), "Alice", "#room", null, null, isSelf = false),
            )
            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Bob", "", null, null))),
            )

            val buffer = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(
                setOf("Alice", "Bob"),
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .map { it.nick }
                    .toSet(),
            )
        }

    @Test
    fun namesSnapshot_replaysPrefixModeThatArrivesBeforeEndOfNames() =
        runTest {
            processor.onRegistered(
                networkId,
                "me",
                mapOf(
                    "CASEMAPPING" to "rfc1459",
                    "PREFIX" to "(qaohv)~&@%+",
                    "CHANMODES" to "beI,k,l,imnst",
                ),
            )
            processor.process(networkId, IrcEvent.NamesStarted("#room"))
            processor.process(
                networkId,
                IrcEvent.ModeChanged(ctx(), "#room", "+o", listOf("Nick")),
            )
            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Nick", "", null, null))),
            )

            val buffer = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(
                "@",
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .single()
                    .prefixes,
            )
        }

    @Test
    fun namesSnapshot_replaysOrderedRemovalAndRenameRaces_withoutDuplicateTimelineRows() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Joined(ctx(), "me", "#room", null, null, isSelf = true),
            )
            processor.process(networkId, IrcEvent.NamesStarted("#room"))
            processor.process(networkId, IrcEvent.Kicked(ctx("kick"), "Bob", "#room", "op", null, false))
            processor.process(networkId, IrcEvent.NickChanged(ctx("nick"), "Alice", "Alicia", false))
            processor.process(networkId, IrcEvent.Quit(ctx("quit"), "Carol", "gone"))
            processor.process(networkId, IrcEvent.Parted(ctx("part"), "Dave", "#room", null, false))
            processor.process(
                networkId,
                IrcEvent.Names(
                    "#room",
                    listOf("Alice", "Bob", "Carol", "Dave", "Eve").map {
                        IrcEvent.Names.Member(it, "", null, null)
                    },
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(
                setOf("Alicia", "Eve"),
                db
                    .memberDao()
                    .allNow(buffer.id)
                    .map { it.nick }
                    .toSet(),
            )
            val rows = pagingList(buffer.id)
            assertEquals(1, rows.count { it.kind == MessageKind.KICK })
            assertEquals(1, rows.count { it.kind == MessageKind.NICK })
            assertEquals(1, rows.count { it.kind == MessageKind.QUIT })
            assertEquals(1, rows.count { it.kind == MessageKind.PART })
        }

    @Test
    fun selfPart_clearsDurableRoster() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Nick", "@", null, null))),
            )
            processor.process(
                networkId,
                IrcEvent.Parted(ctx(), "me", "#room", null, isSelf = true),
            )

            val buffer = db.bufferDao().byName(networkId, "#room")!!
            assertTrue(db.memberDao().allNow(buffer.id).isEmpty())
            assertFalse(buffer.joined)
        }

    @Test
    fun monitorOnline_enrichesIdentityWithoutTimelineActivity() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.MonitorOnline(listOf(Prefix("Nick", "~user", "cloak.example"))),
            )

            val user = db.userDao().byNick(networkId, "nick")!!
            assertEquals("~user", user.username)
            assertEquals("~user@cloak.example", user.hostmask)
            assertNull(db.bufferDao().byName(networkId, "nick"))
        }

    @Test
    fun monitorLimitExceeded_insertsOneDiagnosticForAllRejectedTargets() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.MonitorLimitExceeded(1, listOf("Alice", "Bob"), "list is full"),
            )

            val server = db.bufferDao().byName(networkId, "*")!!
            val rows = pagingList(server.id)
            assertEquals(1, rows.size)
            assertEquals(MessageKind.ERROR, rows.single().kind)
            assertTrue(rows.single().text.contains("Alice,Bob"))
        }

    @Test
    fun networkBatches_fanOutMembershipAndOneOrderedPillPerChannelIdempotently() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Names(
                    "#one",
                    listOf(
                        IrcEvent.Names.Member("Alice", "@", null, null),
                        IrcEvent.Names.Member("Bob", "", null, null),
                    ),
                ),
            )
            processor.process(
                networkId,
                IrcEvent.Names(
                    "#two",
                    listOf(IrcEvent.Names.Member("Alice", "", null, null)),
                ),
            )
            val split =
                IrcEvent.NetworkBatch(
                    IrcEvent.NetworkBatchKind.NETSPLIT,
                    "a.example",
                    "b.example",
                    listOf(
                        IrcEvent.Quit(ctx("q1", 10_000).copy(batchId = "split"), "Alice", "split"),
                        IrcEvent.Quit(ctx("q2", 10_001).copy(batchId = "split"), "Bob", "split"),
                    ),
                )
            processor.process(networkId, split)
            processor.process(networkId, split)

            val one = db.bufferDao().byName(networkId, "#one")!!
            val two = db.bufferDao().byName(networkId, "#two")!!
            assertTrue(db.memberDao().allNow(one.id).isEmpty())
            assertTrue(db.memberDao().allNow(two.id).isEmpty())
            val oneRows = pagingList(one.id)
            val twoRows = pagingList(two.id)
            assertEquals(1, oneRows.count { it.kind == MessageKind.NETSPLIT })
            assertEquals(1, twoRows.count { it.kind == MessageKind.NETSPLIT })
            assertEquals(0, oneRows.count { it.kind == MessageKind.QUIT })
            assertEquals(
                listOf("Alice", "Bob"),
                NetworkBatchPayloadV1.decode(oneRows.single { it.kind == MessageKind.NETSPLIT }.eventPayload)?.nicks,
            )

            val join =
                IrcEvent.NetworkBatch(
                    IrcEvent.NetworkBatchKind.NETJOIN,
                    "a.example",
                    "b.example",
                    listOf(
                        IrcEvent.Joined(
                            ctx("j1", 20_000).copy(batchId = "join"),
                            "Alice",
                            "#one",
                            null,
                            null,
                            false,
                        ),
                        IrcEvent.Joined(
                            ctx("j2", 20_001).copy(batchId = "join"),
                            "Bob",
                            "#one",
                            null,
                            null,
                            false,
                        ),
                    ),
                )
            processor.process(networkId, join)
            assertEquals(
                setOf("Alice", "Bob"),
                db
                    .memberDao()
                    .allNow(one.id)
                    .map { it.nick }
                    .toSet(),
            )
            assertEquals(1, pagingList(one.id).count { it.kind == MessageKind.NETJOIN })
            assertEquals(0, pagingList(one.id).count { it.kind == MessageKind.JOIN })
            processor.process(networkId, IrcEvent.Joined(ctx("live-join", 21_000), "Carol", "#one", null, null, false))
            processor.process(networkId, IrcEvent.NickChanged(ctx("live-nick", 22_000), "Carol", "Caroline", false))
            processor.process(networkId, IrcEvent.Parted(ctx("live-part", 23_000), "Bob", "#one", null, false))
            processor.process(networkId, IrcEvent.Quit(ctx("live-quit", 24_000), "Caroline", "bye"))
            assertEquals(
                setOf(MessageKind.JOIN, MessageKind.NICK, MessageKind.PART, MessageKind.QUIT),
                pagingList(one.id).filter { it.msgid?.startsWith("live-") == true }.map { it.kind }.toSet(),
            )
        }

    @Test
    fun malformedNetworkBatch_isIgnoredWithoutPartialMembershipMutation() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Alice", "", null, null))),
            )
            processor.process(
                networkId,
                IrcEvent.NetworkBatch(
                    IrcEvent.NetworkBatchKind.NETSPLIT,
                    "a",
                    "b",
                    listOf(
                        IrcEvent.Quit(ctx(), "Alice", "split"),
                        IrcEvent.Joined(ctx(), "Bob", "#room", null, null, false),
                    ),
                ),
            )

            val room = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(listOf("Alice"), db.memberDao().allNow(room.id).map { it.nick })
            assertTrue(pagingList(room.id).none { it.kind == MessageKind.NETSPLIT })
        }

    @Test
    fun networkBatch_insertFailureRollsBackEveryMembershipMutation() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Alice", "", null, null))),
            )
            db.openHelper.writableDatabase.execSQL(
                """CREATE TRIGGER reject_network_batch BEFORE INSERT ON messages
               WHEN NEW.kind = 'NETSPLIT'
               BEGIN SELECT RAISE(ABORT, 'forced batch insert failure'); END""",
            )

            val result =
                runCatching {
                    processor.process(
                        networkId,
                        IrcEvent.NetworkBatch(
                            IrcEvent.NetworkBatchKind.NETSPLIT,
                            "a",
                            "b",
                            listOf(IrcEvent.Quit(ctx("rollback"), "Alice", "split")),
                        ),
                    )
                }

            assertTrue(result.isFailure)
            val room = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(listOf("Alice"), db.memberDao().allNow(room.id).map { it.nick })
            assertTrue(pagingList(room.id).none { it.kind == MessageKind.NETSPLIT })
        }

    @Test
    fun networkBatch_withoutMsgidsPreservesDistinctTaggedOccurrences() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Alice", "", null, null))),
            )

            fun split(time: Long) =
                IrcEvent.NetworkBatch(
                    IrcEvent.NetworkBatchKind.NETSPLIT,
                    "a.example",
                    "b.example",
                    listOf(IrcEvent.Quit(ctx(msgid = null, time = time).copy(batchId = "split"), "Alice", "split")),
                    target = "#room",
                )

            processor.process(networkId, split(31_000))
            processor.process(networkId, split(32_000))
            processor.process(networkId, split(61_000))

            val room = db.bufferDao().byName(networkId, "#room")!!
            assertEquals(3, pagingList(room.id).count { it.kind == MessageKind.NETSPLIT })
        }

    @Test
    fun hundredUserSplit_createsOnePillAndNoPerNickQuitRows() =
        runTest {
            val nicks = (1..100).map { "Nick$it" }
            processor.process(
                networkId,
                IrcEvent.Names("#large", nicks.map { IrcEvent.Names.Member(it, "", null, null) }),
            )
            processor.process(
                networkId,
                IrcEvent.NetworkBatch(
                    IrcEvent.NetworkBatchKind.NETSPLIT,
                    "a",
                    "b",
                    nicks.mapIndexed { index, nick ->
                        IrcEvent.Quit(
                            ctx("q$index", 30_000L + index).copy(batchId = "split"),
                            nick,
                            "split",
                        )
                    },
                ),
            )

            val large = db.bufferDao().byName(networkId, "#large")!!
            val rows = pagingList(large.id)
            assertTrue(db.memberDao().allNow(large.id).isEmpty())
            assertEquals(1, rows.count { it.kind == MessageKind.NETSPLIT })
            assertEquals(0, rows.count { it.kind == MessageKind.QUIT })
            assertEquals(
                nicks,
                NetworkBatchPayloadV1.decode(rows.single { it.kind == MessageKind.NETSPLIT }.eventPayload)?.nicks,
            )
        }

    // --- invitations ------------------------------------------------------

    @Test
    fun selfInvite_createsUnjoinedChannelRow_andNotifiesOnce() =
        runTest {
            val notified = mutableListOf<Triple<Long, Long, Long>>()
            val resolved = mutableListOf<Long>()
            val recording =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) = Unit

                        override suspend fun onInvitation(
                            networkId: Long,
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            notified += Triple(networkId, bufferId, messageId)
                        }

                        override suspend fun onInvitationResolved(messageId: Long) {
                            resolved += messageId
                        }
                    },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            val invite = IrcEvent.Invited(ctx("invite-1"), "alice", "ME", "#Secret")

            recording.process(networkId, invite)
            recording.process(networkId, invite)

            val buffer = db.bufferDao().byName(networkId, "#secret")!!
            assertFalse(buffer.joined)
            assertEquals(BufferType.CHANNEL, buffer.type)
            val row = pagingList(buffer.id).single()
            assertEquals(MessageKind.INVITE, row.kind)
            assertEquals(InviteState.PENDING, row.inviteState)
            assertEquals(InvitePayloadV1("alice", "ME", "#Secret"), InvitePayloadV1.decode(row.eventPayload))
            assertEquals(1, notified.size)
            assertEquals(row.id, notified.single().third)

            recording.process(
                networkId,
                IrcEvent.Joined(ctx("join-echo", 2_000), "me", "#Secret", null, null, isSelf = true),
            )
            assertEquals(InviteState.JOINED, db.messageDao().byId(row.id)?.inviteState)
            assertEquals(listOf(row.id), resolved)

            // A later socket replay or push delivery must not revive the resolved card.
            recording.process(networkId, invite)
            assertEquals(InviteState.JOINED, db.messageDao().byId(row.id)?.inviteState)
            assertEquals(1, notified.size)
        }

    // --- DCC direct connections -----------------------------------------

    @Test
    fun dccFileOffer_createsQueryTimelineRow_transferState_andNotification() =
        runTest {
            val presentations = mutableListOf<Pair<Long, Boolean>>()
            val recording =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier by MessageNotifier.Noop {
                        override suspend fun onDccTransferOffer(
                            networkId: Long,
                            bufferId: Long,
                            messageId: Long,
                            silent: Boolean,
                        ) {
                            presentations += messageId to silent
                            if (presentations.size == 1) error("interrupted after posting")
                        }
                    },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            val offer =
                IrcEvent.DccSend(
                    ctx = ctx("dcc-file-1", 10_000, account = "alice-account"),
                    source = Prefix("Alice"),
                    target = "me",
                    offer =
                        IrcEvent.DccSendOffer(
                            protocol = IrcEvent.DccFileProtocol.SEND,
                            filename = "../photo set.jpg",
                            endpoint =
                                IrcEvent.DccEndpoint(
                                    address = "192.0.2.10",
                                    port = 49_152,
                                    addressKind = IrcEvent.DccAddressKind.IPV4_DOTTED,
                                ),
                            sizeBytes = 1_024,
                            token = null,
                        ),
                )

            recording.process(networkId, offer)
            val buffer = db.bufferDao().byName(networkId, "alice")!!
            val interrupted = pagingList(buffer.id).single()
            assertFalse(interrupted.notificationClaimed)
            assertFalse(interrupted.notificationHandled)

            recording.process(networkId, offer)
            recording.process(networkId, offer)

            assertEquals(BufferType.QUERY, buffer.type)
            val row = pagingList(buffer.id).single()
            assertEquals(MessageKind.DCC_TRANSFER, row.kind)
            assertEquals("DCC file offer: photo set.jpg (1 KiB)", row.text)
            val payload = DccFileOfferPayloadV1.decode(row.eventPayload)!!
            assertEquals("dcc:file:msgid:dcc-file-1", payload.offerKey)
            assertEquals("SEND", payload.protocol)
            assertEquals("../photo set.jpg", payload.filename)
            val transfer = db.dccTransferDao().byOfferKey(networkId, payload.offerKey)!!
            assertEquals(row.id, transfer.timelineEventId)
            assertEquals(DccTransferState.OFFERED, transfer.state)
            assertEquals("photo set.jpg", transfer.displayFilename)
            assertEquals(310_000L, transfer.expiresAt)
            assertEquals(listOf(row.id to false, row.id to true), presentations)
            assertFalse(row.notificationClaimed)
            assertTrue(row.notificationHandled)
        }

    @Test
    fun unsupportedDcc_createsInertQueryTimelineRow_withoutTransferState() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.UnsupportedDcc(
                    ctx = ctx("dcc-unsupported-1", 30_000),
                    source = Prefix("Alice"),
                    target = "me",
                    command = "VOICE",
                    reason = IrcEvent.DccUnsupportedReason.UNKNOWN_COMMAND,
                    rawPayload = "DCC VOICE chat 192.0.2.10 49152",
                ),
            )

            val buffer = db.bufferDao().byName(networkId, "alice")!!
            val row = pagingList(buffer.id).single()
            assertEquals(MessageKind.DCC_UNSUPPORTED, row.kind)
            assertEquals("Unsupported DCC VOICE request", row.text)
            assertEquals("VOICE", UnsupportedDccPayloadV1.decode(row.eventPayload)?.command)
            assertNull(db.dccTransferDao().byOfferKey(networkId, "dcc:file:msgid:dcc-unsupported-1"))
        }

    @Test
    fun msgidlessInvite_preservesDistinctTaggedOccurrences() =
        runTest {
            val first = IrcEvent.Invited(ctx(msgid = null, time = 31_000), "alice", "me", "#fallback")
            val duplicate = first.copy(ctx = first.ctx.copy(serverTime = 32_000))
            val later = first.copy(ctx = first.ctx.copy(serverTime = 61_000))

            processor.process(networkId, first)
            processor.process(networkId, duplicate)
            processor.process(networkId, later)

            val buffer = db.bufferDao().byName(networkId, "#fallback")!!
            assertEquals(3, pagingList(buffer.id).count { it.kind == MessageKind.INVITE })
        }

    @Test
    fun historyLiveAndPushInvitePermutationsConvergeToOneActionableEvent() =
        runTest {
            val notifications = mutableListOf<Long>()
            val recovered = mutableListOf<Long>()
            val recording =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) = Unit

                        override suspend fun onInvitation(
                            networkId: Long,
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            notifications += messageId
                        }

                        override suspend fun onRecoveredInvitation(
                            networkId: Long,
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            recovered += messageId
                        }
                    },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

            enumValues<InviteDeliveryOrder>().forEachIndexed { index, order ->
                val channel = "#invite-order-$index"
                val msgid = "invite-order-$index"
                val live = IrcEvent.Invited(ctx(msgid, 20_000L + index), "alice", "me", channel)
                val history = live.copy(ctx = live.ctx.copy(batchId = "invite-history-$index"))
                when (order) {
                    InviteDeliveryOrder.HISTORY_LIVE -> {
                        recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                        recording.process(networkId, live)
                    }

                    InviteDeliveryOrder.LIVE_HISTORY -> {
                        recording.process(networkId, live)
                        recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                    }

                    InviteDeliveryOrder.HISTORY_PUSH -> {
                        recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                        recording.processPush(networkId, live)
                    }

                    InviteDeliveryOrder.PUSH_HISTORY -> {
                        recording.processPush(networkId, live)
                        recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                    }
                }

                val room = db.bufferDao().byName(networkId, channel)!!
                val row = pagingList(room.id).single { it.msgid == msgid }
                assertEquals(InviteState.PENDING, row.inviteState)
                assertEquals(1, notifications.count { it == row.id })
            }
            assertTrue(recovered.isEmpty())
        }

    @Test
    fun interruptedInvitePresentationRetriesSilentlyOnAnotherLiveDelivery() =
        runTest {
            val firstPresentations = mutableListOf<Long>()
            val recoveredPresentations = mutableListOf<Long>()
            processor =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier by MessageNotifier.Noop {
                        override suspend fun onInvitation(
                            networkId: Long,
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            firstPresentations += messageId
                            error("interrupted after posting")
                        }

                        override suspend fun onRecoveredInvitation(
                            networkId: Long,
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            recoveredPresentations += messageId
                        }
                    },
                )
            val invite = IrcEvent.Invited(ctx("retry-invite"), "alice", "me", "#retry-invite")
            processor.process(networkId, invite)
            val buffer = db.bufferDao().byName(networkId, "#retry-invite")!!
            val row = pagingList(buffer.id).single()
            assertFalse(row.notificationClaimed)
            assertFalse(row.notificationHandled)

            processor.process(networkId, invite)
            processor.processPush(networkId, invite)

            assertEquals(listOf(row.id), firstPresentations)
            assertEquals(listOf(row.id), recoveredPresentations)
            assertTrue(db.messageDao().byId(row.id)!!.notificationHandled)
        }

    @Test
    fun invalidSelfInvite_isInformationalInServerBuffer_withoutActions() =
        runTest {
            processor.process(networkId, IrcEvent.Invited(ctx("invalid-invite"), "alice", "me", "not a channel"))

            val server = db.bufferDao().byName(networkId, "*")!!
            val row = pagingList(server.id).single { it.kind == MessageKind.INVITE }
            assertEquals(InviteState.HISTORICAL, row.inviteState)
            assertTrue(row.text.contains("invalid invitation"))
        }

    @Test
    fun inviteContributesPreviewAndActivity_butNeverUnread() =
        runTest {
            processor.process(networkId, IrcEvent.Invited(ctx("preview-invite", 9_000), "alice", "me", "#preview"))

            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.displayName == "#preview" }
            assertEquals("alice invited you to #preview", row.lastMessageText)
            assertEquals(9_000L, row.lastMessageTime)
            assertEquals(0, row.unreadCount)
            assertEquals(0, row.mentionCount)
        }

    @Test
    fun thirdPartyInvite_routesToExistingChannel_withoutNotification() =
        runTest {
            processor.process(networkId, IrcEvent.Joined(ctx(), "me", "#ops", null, null, isSelf = true))
            processor.process(networkId, IrcEvent.Invited(ctx("invite-ops", 2_000), "oper", "bob", "#ops"))

            val buffer = db.bufferDao().byName(networkId, "#ops")!!
            val row = pagingList(buffer.id).first { it.kind == MessageKind.INVITE }
            assertEquals(InviteState.HISTORICAL, row.inviteState)
            assertEquals("oper invited bob to #ops", row.text)
        }

    @Test
    fun historyInvite_isCompactAndNeverNotifies() =
        runTest {
            val batch =
                IrcEvent.HistoryBatch(
                    "#old",
                    listOf(IrcEvent.Invited(ctx("history-invite"), "alice", "me", "#old")),
                )
            processor.process(networkId, batch)

            val buffer = db.bufferDao().byName(networkId, "#old")!!
            assertEquals(InviteState.HISTORICAL, pagingList(buffer.id).single().inviteState)
        }

    @Test
    fun inviteState_claimAndDismiss_areAtomicAndJoinDoesNotReviveDismissed() =
        runTest {
            processor.process(networkId, IrcEvent.Invited(ctx("invite-state"), "alice", "me", "#state"))
            val buffer = db.bufferDao().byName(networkId, "#state")!!
            val row = pagingList(buffer.id).single()

            assertEquals(
                1,
                db.messageDao().compareAndSetInviteState(row.id, InviteState.PENDING, InviteState.JOINING),
            )
            assertEquals(
                0,
                db.messageDao().compareAndSetInviteState(row.id, InviteState.PENDING, InviteState.JOINING),
            )
            assertEquals(1, db.messageDao().dismissInvite(row.id))
            processor.process(
                networkId,
                IrcEvent.Joined(ctx("late-join"), "me", "#state", null, null, isSelf = true),
            )
            assertEquals(InviteState.DISMISSED, db.messageDao().byId(row.id)?.inviteState)
        }

    @Test
    fun joinRejection_marksOnlyMatchingJoiningInviteFailed_withReason() =
        runTest {
            processor.process(networkId, IrcEvent.Invited(ctx("reject-a"), "alice", "me", "#locked"))
            processor.process(networkId, IrcEvent.Invited(ctx("reject-b"), "bob", "me", "#other"))
            val locked = db.bufferDao().byName(networkId, "#locked")!!
            val other = db.bufferDao().byName(networkId, "#other")!!
            val lockedRow = pagingList(locked.id).single()
            val otherRow = pagingList(other.id).single()
            db.messageDao().compareAndSetInviteState(lockedRow.id, InviteState.PENDING, InviteState.JOINING)
            db.messageDao().compareAndSetInviteState(otherRow.id, InviteState.PENDING, InviteState.JOINING)

            processor.process(
                networkId,
                IrcEvent.ServerError("473", listOf("me", "#LOCKED"), "Cannot join channel (+i)"),
            )

            val failed = db.messageDao().byId(lockedRow.id)!!
            assertEquals(InviteState.FAILED, failed.inviteState)
            assertTrue(failed.text.contains("Cannot join channel (+i)"))
            assertEquals(InviteState.JOINING, db.messageDao().byId(otherRow.id)?.inviteState)
        }

    @Test
    fun disconnect_marksAllJoiningInvitesOnNetworkFailed() =
        runTest {
            processor.process(networkId, IrcEvent.Invited(ctx("disconnect-a"), "alice", "me", "#one"))
            processor.process(networkId, IrcEvent.Invited(ctx("disconnect-b"), "bob", "me", "#two"))
            val rows =
                listOf("#one", "#two").map { channel ->
                    pagingList(db.bufferDao().byName(networkId, channel)!!.id).single()
                }
            rows.forEach {
                db.messageDao().compareAndSetInviteState(it.id, InviteState.PENDING, InviteState.JOINING)
            }

            processor.process(networkId, IrcEvent.Disconnected("network lost"))

            rows.forEach {
                val failed = db.messageDao().byId(it.id)!!
                assertEquals(InviteState.FAILED, failed.inviteState)
                assertTrue(failed.text.contains("network lost"))
            }
        }

    @Test
    fun history_pushedThroughInOneBatch_isIdempotent() =
        runTest {
            val batch =
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(
                        IrcEvent.ChatMessage(ctx(msgid = "h1"), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#chan", "one", false, null),
                        IrcEvent.ChatMessage(ctx(msgid = "h2"), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#chan", "two", false, null),
                    ),
                )
            processor.process(networkId, batch)
            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val pagingSource = db.messageDao().pagingSource(buffer.id)
            pagingSource.load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 100, true),
            )
            processor.process(networkId, batch) // replay overlap → IGNORE
            assertEquals(2, pagingList(buffer.id).size)
            // A no-op replay must not replace the visible Paging generation with placeholders.
            assertFalse(pagingSource.invalid)
        }

    @Test
    fun historyBatch_identicalMsgidlessTextAtDifferentTaggedTimes_replaysIdempotently() =
        runTest {
            val batch =
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx(msgid = null, time = 10_000),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("alice"),
                            "#chan",
                            "same text",
                            false,
                            null,
                        ),
                        IrcEvent.ChatMessage(
                            ctx(msgid = null, time = 11_000),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("alice"),
                            "#chan",
                            "same text",
                            false,
                            null,
                        ),
                    ),
                )

            processor.process(networkId, batch)
            processor.process(networkId, batch)

            val buffer = db.bufferDao().byName(networkId, "#chan")!!
            val rows = pagingList(buffer.id)
            assertEquals(2, rows.size)
            assertEquals(setOf(10_000L, 11_000L), rows.map { it.serverTime }.toSet())
        }

    @Test
    fun historyBatch_identicalMsgidlessSystemEventsPreserveMultiplicityAndReplay() =
        runTest {
            val mode =
                IrcEvent.ModeChanged(
                    ctx(msgid = null, time = 12_000).copy(batchId = "history-mode"),
                    "#chan",
                    "+m",
                    emptyList(),
                )
            val batch = IrcEvent.HistoryBatch("#chan", listOf(mode, mode))

            processor.process(networkId, batch)
            processor.process(networkId, batch)

            val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
            val modes = pagingList(bufferId).filter { it.kind == MessageKind.MODE }
            assertEquals(2, modes.size)
        }

    @Test
    fun nativePlaybackPreservesRepeatedUnknownTimeMessagesWithoutDuplicatingOnReplay() =
        runTest {
            val repeated =
                IrcEvent.ChatMessage(
                    ctx =
                        ctx(msgid = null, time = 0).copy(
                            batchId = "znc-playback",
                            serverTimeSource = ServerTimeSource.UNKNOWN,
                        ),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "same replayed body",
                    isSelf = false,
                    replyToMsgid = null,
                )
            val playback =
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.ZNC_PLAYBACK,
                    target = "#chan",
                    items =
                        listOf(repeated, repeated).mapIndexed { ordinal, event ->
                            IrcEvent.PlaybackItem(event, ordinal)
                        },
                )

            processor.process(networkId, playback)
            processor.process(networkId, playback)

            val rows = pagingList(db.bufferDao().byName(networkId, "#chan")!!.id)
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.serverTime == 0L })
            assertTrue(rows.all { it.timeProvenance == TimeProvenance.UNKNOWN })
        }

    @Test
    fun nativePlaybackKeepsSubstantiveEventsAndOmitsHistoricalPresenceWithoutChangingSession() =
        runTest {
            processor.process(networkId, IrcEvent.Joined(ctx("self-join"), "me", "#chan", null, null, true))
            processor.process(networkId, IrcEvent.TopicChanged(ctx("current-topic", 2_000), "#chan", "current", "alice"))
            val room = db.bufferDao().byName(networkId, "#chan")!!

            processor.process(
                networkId,
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.ZNC_PLAYBACK,
                    target = "#chan",
                    items =
                        listOf(
                            IrcEvent.TopicChanged(ctx("old-topic", 1_000).copy(batchId = "znc"), "#chan", "old", "bob"),
                            IrcEvent.Joined(ctx("old-join", 1_100).copy(batchId = "znc"), "historical-user", "#chan", null, null, false),
                            IrcEvent.Parted(ctx("old-part", 1_101), "historical-user", "#chan", null, false),
                            IrcEvent.Quit(ctx("old-quit", 1_102), "historical-user", null),
                            IrcEvent.NickChanged(ctx("old-nick", 1_103), "historical-user", "old-user", false),
                            IrcEvent.ChatMessage(ctx("old-action", 1_104), IrcEvent.ChatKind.ACTION, Prefix("bob"), "#chan", "waves", false, null),
                            IrcEvent.Kicked(ctx("old-kick", 1_105), "bob", "#chan", "op", null, false),
                            IrcEvent.NetworkBatch(
                                IrcEvent.NetworkBatchKind.NETJOIN,
                                "a.example",
                                "b.example",
                                listOf(IrcEvent.Joined(ctx("old-netjoin", 1_106), "historical-user", "#chan", null, null, false)),
                                target = "#chan",
                            ),
                        ).mapIndexed { ordinal, event -> IrcEvent.PlaybackItem(event, ordinal) },
                ),
            )
            processor.process(
                networkId,
                IrcEvent.ReplayBatch(
                    "#chan",
                    listOf(
                        IrcEvent.Quit(ctx("replay-quit", 1_107), "alice", null),
                        IrcEvent.NetworkBatch(
                            IrcEvent.NetworkBatchKind.NETSPLIT,
                            "a.example",
                            "b.example",
                            listOf(IrcEvent.Quit(ctx("replay-split", 1_108), "alice", "split")),
                            target = "#chan",
                        ),
                        IrcEvent.ChatMessage(ctx("replay-chat", 1_109), IrcEvent.ChatKind.PRIVMSG, Prefix("bob"), "#chan", "still here", false, null),
                    ),
                ),
            )

            assertEquals("current", db.bufferDao().observeById(room.id)!!.topic)
            assertTrue(db.memberDao().allNow(room.id).none { it.nick == "historical-user" })
            val rows = pagingList(room.id)
            assertEquals(1, rows.count { it.kind == MessageKind.TOPIC && it.text == "topic: old" })
            assertEquals(1, rows.count { it.msgid == "old-action" && it.kind == MessageKind.ACTION })
            assertEquals(1, rows.count { it.msgid == "old-kick" && it.kind == MessageKind.KICK })
            assertEquals(1, rows.count { it.msgid == "replay-chat" && it.kind == MessageKind.PRIVMSG })
            assertTrue(
                rows.none {
                    it.msgid in setOf("old-join", "old-part", "old-quit", "old-nick", "replay-quit") ||
                        it.kind == MessageKind.NETJOIN || it.kind == MessageKind.NETSPLIT
                },
            )
        }

    @Test
    fun completedPlaybackReconcilesEqualTimestampRowsToServerOrder() =
        runTest {
            val first =
                IrcEvent.ChatMessage(
                    ctx("first", 5_000),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#chan",
                    "first",
                    false,
                    null,
                )
            val second = first.copy(ctx = ctx("second", 5_000), text = "second")
            processor.process(networkId, second)
            processor.process(networkId, first)

            processor.process(
                networkId,
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.CHATHISTORY,
                    target = "#chan",
                    items =
                        listOf(first, second).mapIndexed { ordinal, event ->
                            IrcEvent.PlaybackItem(event, ordinal)
                        },
                    placement = IrcEvent.PlaybackPlacement.LATEST,
                ),
            )

            val rows = pagingList(db.bufferDao().byName(networkId, "#chan")!!.id)
            assertEquals(listOf("second", "first"), rows.map { it.text })
            assertEquals(listOf(1L, 0L), rows.map { it.timelineOrder })
            assertTrue(rows.all { it.timelineOrderConfirmed })
            val secondRow = rows.first()
            val firstRow = rows.last()
            db.bufferDao().advanceLocalReadAnchor(secondRow.bufferId, secondRow.serverTime, secondRow.id)
            db.bufferDao().advanceLocalReadAnchor(firstRow.bufferId, firstRow.serverTime, firstRow.id)
            assertEquals(
                secondRow.id,
                db.bufferDao().observeById(secondRow.bufferId)!!.localReadAnchorEventId,
            )
        }

    @Test
    fun beforePlaybackPlacesNewEqualTimestampRowsAheadOfExistingTimelineRows() =
        runTest {
            fun message(
                msgid: String,
                text: String,
            ) = IrcEvent.ChatMessage(
                ctx(msgid, 6_000),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("alice"),
                "#chan",
                text,
                false,
                null,
            )
            processor.process(networkId, message("existing", "existing"))

            processor.process(
                networkId,
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.CHATHISTORY,
                    target = "#chan",
                    items =
                        listOf(
                            message("first", "first"),
                            message("second", "second"),
                        ).mapIndexed { ordinal, event -> IrcEvent.PlaybackItem(event, ordinal) },
                    placement = IrcEvent.PlaybackPlacement.BEFORE,
                ),
            )

            val rows = pagingList(db.bufferDao().byName(networkId, "#chan")!!.id)
            assertEquals(listOf("existing", "second", "first"), rows.map { it.text })
            assertEquals(listOf(2L, 1L, 0L), rows.map { it.timelineOrder })
        }

    @Test
    fun historyCursorNeverPairsANewerTimestampWithAnOlderMsgid() =
        runTest {
            val tagged =
                IrcEvent.ChatMessage(
                    ctx("identified", 100).copy(batchId = "history"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#chan",
                    "identified",
                    false,
                    null,
                )
            val msgidless =
                tagged.copy(
                    ctx = ctx(null, 200).copy(batchId = "history"),
                    text = "newer without msgid",
                )

            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(tagged, msgidless)))

            val room = db.bufferDao().byName(networkId, "#chan")!!
            val cursor = db.historyCursorDao().byRoom(room.id)!!
            assertEquals(200L, cursor.newestServerTime)
            assertNull(cursor.newestMsgid)
            assertEquals(100L, cursor.oldestServerTime)
            assertEquals("identified", cursor.oldestMsgid)
        }

    @Test
    fun historyBatch_persistsMentionsAndDms_withoutNotifications() =
        runTest {
            val notifications = mutableListOf<String>()
            val recording =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notifications += message.text
                        }
                    },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            recording.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx("history-mention", 1_000),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("alice"),
                            "#chan",
                            "hello me",
                            false,
                            null,
                        ),
                        IrcEvent.ChatMessage(
                            ctx("history-dm", 1_001),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("bob"),
                            "me",
                            "old direct message",
                            false,
                            null,
                        ),
                    ),
                ),
            )

            assertTrue(pagingList(db.bufferDao().byName(networkId, "#chan")!!.id).isNotEmpty())
            assertTrue(pagingList(db.bufferDao().byName(networkId, "bob")!!.id).isNotEmpty())
            assertTrue(notifications.isEmpty())

            recording.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("live-dm", 1_002),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    "live direct message",
                    false,
                    null,
                ),
            )
            assertEquals(listOf("live direct message"), notifications)
        }

    @Test
    fun historyBatch_persistsTimeline_withoutMutatingCurrentSessionState() =
        runTest {
            val notifications = mutableListOf<String>()
            val reads = mutableListOf<Pair<Long, Long>>()
            val inviteNotifications = mutableListOf<Long>()
            val resolvedInvites = mutableListOf<Long>()
            val typing = TypingTrackerImpl()
            val recording =
                EventProcessor(
                    db,
                    typing,
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notifications += message.text
                        }

                        override suspend fun onRead(
                            bufferId: Long,
                            anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
                        ) {
                            reads += bufferId to anchor.serverTime
                        }

                        override suspend fun onInvitation(
                            networkId: Long,
                            bufferId: Long,
                            messageId: Long,
                        ) {
                            inviteNotifications += messageId
                        }

                        override suspend fun onInvitationResolved(messageId: Long) {
                            resolvedInvites += messageId
                        }
                    },
                )
            recording.onRegistered(
                networkId,
                "me",
                mapOf(
                    "CASEMAPPING" to "rfc1459",
                    "PREFIX" to "(ov)@+",
                    "CHANMODES" to "beI,k,l,imnst",
                ),
            )
            recording.process(networkId, IrcEvent.Joined(ctx("self-live", 10_000), "me", "#room", null, null, true))
            recording.process(
                networkId,
                IrcEvent.Names(
                    "#room",
                    listOf(
                        IrcEvent.Names.Member("me", "@", "self", "current.example"),
                        IrcEvent.Names.Member("Alice", "+", "alice", "current.example"),
                        IrcEvent.Names.Member("Bob", "", "bob", "current.example"),
                    ),
                ),
            )
            recording.process(networkId, IrcEvent.TopicChanged(ctx("topic-live", 10_001), "#room", "current topic", "op"))
            recording.process(networkId, IrcEvent.ReadMarker("#room", 10_002))
            recording.process(networkId, IrcEvent.Invited(ctx("invite-live", 10_003), "oper", "me", "#room"))
            recording.process(
                networkId,
                IrcEvent.ChatMessage(ctx("relation-parent", 10_004), IrcEvent.ChatKind.PRIVMSG, Prefix("me"), "#room", "parent", true, null),
            )

            val bufferBefore = db.bufferDao().byName(networkId, "#room")!!
            val membersBefore = db.memberDao().allNow(bufferBefore.id).sortedBy { it.nick }
            val aliceBefore = db.userDao().byNick(networkId, "alice")
            val inviteBefore = pagingList(bufferBefore.id).single { it.msgid == "invite-live" }
            assertEquals(
                1,
                db.messageDao().compareAndSetInviteState(inviteBefore.id, InviteState.PENDING, InviteState.JOINING),
            )
            db.networkDao().update(db.networkDao().byId(networkId)!!.copy(role = NetworkRole.BOUNCER_ROOT))
            val networksBefore = db.networkDao().allNow()
            reads.clear()
            inviteNotifications.clear()

            val history =
                IrcEvent.HistoryBatch(
                    "#room",
                    listOf(
                        IrcEvent.ChatMessage(ctx("history-chat", 1_000), IrcEvent.ChatKind.PRIVMSG, Prefix("Alice"), "#room", "old me mention", false, null),
                        IrcEvent.ChatMessage(ctx("history-action", 1_000), IrcEvent.ChatKind.ACTION, Prefix("Alice"), "#room", "waves", false, null),
                        IrcEvent.Joined(ctx("history-join", 1_001), "Eve", "#room", "old-account", "Old Eve", false),
                        IrcEvent.Joined(ctx("history-other-join", 1_001), "Eve", "#ghost", null, null, false),
                        IrcEvent.Parted(ctx("history-part", 1_002), "Alice", "#room", "old part", false),
                        IrcEvent.Kicked(ctx("history-kick", 1_003), "Bob", "#room", "old-op", "old kick", false),
                        IrcEvent.Quit(ctx("history-quit", 1_004), "Alice", "old quit"),
                        IrcEvent.NickChanged(ctx("history-nick", 1_005), "me", "old-me", true),
                        IrcEvent.ModeChanged(ctx("history-mode", 1_006), "#room", "-o", listOf("me")),
                        IrcEvent.TopicChanged(ctx("history-topic", 1_007), "#room", "old topic", "old-op"),
                        IrcEvent.AwayChanged("Alice", "old away"),
                        IrcEvent.AccountChanged("Alice", "old-account"),
                        IrcEvent.HostChanged("Alice", "old-user", "old.example"),
                        IrcEvent.RealnameChanged("Alice", "Old Alice"),
                        IrcEvent.TagMessage(ctx("history-typing", 1_007), Prefix("Mallory"), "#room", "active", null, null),
                        IrcEvent.TagMessage(ctx("history-react-bob", 1_007), Prefix("Bob"), "#room", null, "👍", "relation-parent"),
                        IrcEvent.TagMessage(ctx("history-react-alice", 1_007), Prefix("Alice"), "#room", null, "👍", "relation-parent"),
                        IrcEvent.Raw(IrcMessage.parse("@+draft/unreact=👍;+reply=relation-parent :Alice!u@h TAGMSG #room")),
                        IrcEvent.NamesStarted("#room"),
                        IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Mallory", "@", "mallory", "old.example"))),
                        IrcEvent.ReadMarker("#room", 20_000),
                        IrcEvent.BouncerNetworkState("old-net", mapOf("name" to "Old network")),
                        IrcEvent.Invited(ctx("history-invite", 1_008), "old-oper", "me", "#room"),
                        IrcEvent.Joined(ctx("history-self-join", 1_009), "me", "#room", null, null, true),
                        IrcEvent.Disconnected("old disconnect"),
                        IrcEvent.NetworkBatch(
                            IrcEvent.NetworkBatchKind.NETSPLIT,
                            "old-a.example",
                            "old-b.example",
                            listOf(IrcEvent.Quit(ctx("history-split", 1_010), "Bob", "split")),
                            target = "#room",
                        ),
                        IrcEvent.NetworkBatch(
                            IrcEvent.NetworkBatchKind.NETJOIN,
                            "old-a.example",
                            "old-b.example",
                            listOf(IrcEvent.Joined(ctx("history-netjoin", 1_011), "Eve", "#room", null, null, false)),
                            target = "#room",
                        ),
                    ),
                )
            recording.process(networkId, history)
            recording.process(networkId, history)

            assertEquals(bufferBefore, db.bufferDao().byName(networkId, "#room"))
            assertEquals(membersBefore, db.memberDao().allNow(bufferBefore.id).sortedBy { it.nick })
            assertEquals(aliceBefore, db.userDao().byNick(networkId, "alice"))
            assertNull(db.userDao().byNick(networkId, "eve"))
            assertNull(db.userDao().byNick(networkId, "mallory"))
            assertNull(db.bufferDao().byName(networkId, "#ghost"))
            assertEquals(networksBefore, db.networkDao().allNow())
            assertEquals(InviteState.JOINING, db.messageDao().byId(inviteBefore.id)?.inviteState)
            assertTrue(notifications.isEmpty())
            assertTrue(reads.isEmpty())
            assertTrue(inviteNotifications.isEmpty())
            assertTrue(resolvedInvites.isEmpty())
            assertTrue(typing.typingNicks(bufferBefore.id).value.isEmpty())
            assertEquals(
                listOf("Bob"),
                db
                    .reactionDao()
                    .observeFor(bufferBefore.id, listOf("relation-parent"))
                    .first()
                    .map { it.sender },
            )

            val rows = pagingList(bufferBefore.id)
            listOf(
                MessageKind.PRIVMSG,
                MessageKind.ACTION,
                MessageKind.KICK,
                MessageKind.MODE,
                MessageKind.TOPIC,
                MessageKind.INVITE,
            ).forEach { kind -> assertTrue("missing historical $kind row", rows.any { it.kind == kind }) }
            assertTrue(
                rows.none {
                    it.serverTime < 10_000L &&
                        it.kind in setOf(MessageKind.JOIN, MessageKind.PART, MessageKind.QUIT, MessageKind.NICK, MessageKind.NETSPLIT, MessageKind.NETJOIN)
                },
            )
            assertEquals(1, rows.count { it.msgid == "history-chat" })
            assertEquals(1, rows.count { it.msgid == "history-action" })
            assertEquals(1, rows.count { it.msgid == "history-kick" })
            assertEquals(InviteState.HISTORICAL, rows.single { it.msgid == "history-invite" }.inviteState)

            recording.process(
                networkId,
                IrcEvent.ChatMessage(ctx("post-history", 30_000), IrcEvent.ChatKind.PRIVMSG, Prefix("Alice"), "#room", "hello me", false, null),
            )
            assertEquals(listOf("hello me"), notifications)
        }

    @Test
    fun protocolPageUsesPrimaryMetadataForCursorAndRestoresGenericHistoryWrites() =
        runTest {
            val context =
                IrcEvent.ChatMessage(
                    ctx("context", 50),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#page",
                    "context",
                    false,
                    null,
                )
            val primary =
                IrcEvent.ChatMessage(
                    ctx("PrimaryCase", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#page",
                    "primary",
                    false,
                    null,
                )
            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#page", limit = 50),
                ChatHistoryResponse.Messages(
                    events = listOf(context, primary),
                    oldest = ChatHistoryReference("PrimaryCase", 100),
                    newest = ChatHistoryReference("PrimaryCase", 100),
                    endOfHistory = false,
                    primaryMessageCount = 1,
                ),
            )

            val page = db.bufferDao().byName(networkId, "#page")!!
            assertEquals(
                HistoryCursorEntity(page.id, "PrimaryCase", 100, "PrimaryCase", 100, false),
                db.historyCursorDao().byRoom(page.id),
            )

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#other",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx("other", 25),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("bob"),
                            "#other",
                            "other",
                            false,
                            null,
                        ),
                    ),
                ),
            )
            val other = db.bufferDao().byName(networkId, "#other")!!
            assertEquals("other", db.historyCursorDao().byRoom(other.id)?.oldestMsgid)
            assertEquals("PrimaryCase", db.historyCursorDao().byRoom(page.id)?.oldestMsgid)
        }

    @Test
    fun protocolPageDoesNotRecreateDeletedExpectedRoom() =
        runTest {
            val room = BufferStore(db).getOrCreate(networkId, "#gone", "#gone", BufferType.CHANNEL)
            db.bufferDao().deleteBuffer(room.id)

            val result =
                runCatching {
                    processor.persistHistoryPage(
                        networkId,
                        ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#gone", limit = 50),
                        ChatHistoryResponse.Messages(
                            events =
                                listOf(
                                    IrcEvent.ChatMessage(
                                        ctx("late", 100),
                                        IrcEvent.ChatKind.PRIVMSG,
                                        Prefix("alice"),
                                        "#gone",
                                        "too late",
                                        false,
                                        null,
                                    ),
                                ),
                            oldest = ChatHistoryReference("late", 100),
                            newest = ChatHistoryReference("late", 100),
                            endOfHistory = true,
                            primaryMessageCount = 1,
                        ),
                        expectedRoomId = room.id,
                    )
                }

            assertTrue(result.isFailure)
            assertEquals(null, db.bufferDao().byName(networkId, "#gone"))
        }

    @Test
    fun protocolPageCompletionIsDirectional() =
        runTest {
            val empty =
                ChatHistoryResponse.Messages(
                    events = emptyList(),
                    oldest = null,
                    newest = null,
                    endOfHistory = true,
                    primaryMessageCount = 0,
                )
            val incompleteRequests =
                listOf(
                    ChatHistoryRequest(ChatHistoryRequest.Subcommand.AFTER, "#after", bound1 = "msgid=x", limit = 50),
                    ChatHistoryRequest(ChatHistoryRequest.Subcommand.AROUND, "#around", bound1 = "msgid=x", limit = 50),
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.BETWEEN,
                        "#between",
                        bound1 = "msgid=x",
                        bound2 = "msgid=y",
                        limit = 50,
                    ),
                    ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#bounded", bound1 = "msgid=x", limit = 50),
                    // An unbounded LATEST that DELIVERED nothing is the same non-proof whether or not the
                    // server tagged the empty batch terminal: it names no boundary and leaves no row, so it
                    // describes what the target could serve at that instant. Completion is durable and
                    // nothing clears it, so this answer must not brand a room that has yet to receive its
                    // backlog — a channel restored moments ago, a bouncer still to archive anything for it.
                    ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#latest", limit = 50),
                )

            incompleteRequests.forEach { request ->
                val roomId = processor.persistHistoryPage(networkId, request, empty)
                assertFalse(db.bufferDao().observeById(roomId)!!.historyComplete)
                assertFalse(db.historyCursorDao().byRoom(roomId)!!.historyComplete)
            }

            // BEFORE named the row it had to be older than, so an empty answer to it IS start-of-history.
            listOf(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, "#before", bound1 = "msgid=x", limit = 50),
            ).forEach { request ->
                val roomId = processor.persistHistoryPage(networkId, request, empty)
                assertTrue(db.bufferDao().observeById(roomId)!!.historyComplete)
                assertTrue(db.historyCursorDao().byRoom(roomId)!!.historyComplete)
            }
        }

    @Test
    fun terminalDirectionalPageMarksUnreachedRemainderUnrecoverable() =
        runTest {
            val room = BufferStore(db).getOrCreate(networkId, "#gap", "#gap", BufferType.CHANNEL)
            db.historyGapDao().insert(
                HistoryGapEntity(0, room.id, "m100", 100, "m900", 900),
            )
            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AFTER,
                    "#gap",
                    bound1 = "msgid=m100",
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events = emptyList(),
                    oldest = null,
                    newest = null,
                    endOfHistory = true,
                    primaryMessageCount = 0,
                ),
                expectedRoomId = room.id,
            )

            assertFalse(
                db
                    .historyGapDao()
                    .forRoom(room.id)
                    .single()
                    .recoverable,
            )

            db.historyGapDao().deleteForRoom(room.id)
            db.historyGapDao().insert(
                HistoryGapEntity(0, room.id, "m100", 100, "m900", 900),
            )
            val terminal =
                IrcEvent.ChatMessage(
                    ctx("m150", 150),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#gap",
                    "terminal",
                    false,
                    null,
                )
            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AFTER,
                    "#gap",
                    bound1 = "msgid=m100",
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events = listOf(terminal),
                    oldest = ChatHistoryReference("m150", 150),
                    newest = ChatHistoryReference("m150", 150),
                    endOfHistory = true,
                    primaryMessageCount = 1,
                ),
                expectedRoomId = room.id,
            )

            val remainder = db.historyGapDao().forRoom(room.id).single()
            assertEquals("m150", remainder.olderMsgid)
            assertEquals(150L, remainder.olderServerTime)
            assertFalse(remainder.recoverable)
        }

    @Test
    fun saturatedTimestampOnlyLatestRecordsARecoverableCatchUpGap() =
        runTest {
            // Timestamp-only wire (soju advertises MSGREFTYPES=timestamp): boundary references carry no
            // msgids and the reconnect catch-up LATEST page is saturated (primary == limit). The gap
            // between the previous newest row and the page's oldest row must still be born RECOVERABLE:
            // recoverable=false is reserved for server-proven-empty intervals, and per-fetch
            // equal-timestamp ambiguity is the loader's per-page concern, not a reason to permanently
            // block gap fill. (Regression pin: the old saturation rule made every soju catch-up gap
            // unrecoverable, so opening an unread channel could never page older history.)
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx(null, 10),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#ts-only",
                    "marker",
                    false,
                    null,
                ),
            )
            val room = checkNotNull(db.bufferDao().byName(networkId, "#ts-only"))
            val page =
                (212..261).map { time ->
                    IrcEvent.ChatMessage(
                        ctx(null, time.toLong()),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("alice"),
                        "#ts-only",
                        "row$time",
                        false,
                        null,
                    )
                }

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#ts-only", limit = 50),
                ChatHistoryResponse.Messages(
                    events = page,
                    oldest = ChatHistoryReference(null, 212),
                    newest = ChatHistoryReference(null, 261),
                    endOfHistory = false,
                    primaryMessageCount = 50,
                ),
                expectedRoomId = room.id,
            )

            val gap = db.historyGapDao().forRoom(room.id).single()
            assertTrue(gap.recoverable)
            assertEquals(10L, gap.olderServerTime)
            assertEquals(212L, gap.newerServerTime)
        }

    @Test
    fun aroundPageBelowAllRetainedRowsRecordsARecoverableIslandGap() =
        runTest {
            // A deep-link AROUND page can land strictly older than every retained row. Nothing then
            // records the interval between that new island's NEWEST edge and the previously-oldest
            // retained boundary, so the timeline renders false adjacency across it AND the interval is
            // unfillable: the APPEND ladder pages BEFORE the island's oldest row, never above it.
            val room = seedIslandHost("#island")
            val around = (500..502).map { time -> islandRow("#island", time) }

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AROUND,
                    "#island",
                    bound1 = "msgid=m501",
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events = around,
                    oldest = ChatHistoryReference("m500", 500),
                    newest = ChatHistoryReference("m502", 502),
                    endOfHistory = false,
                    primaryMessageCount = 3,
                ),
                expectedRoomId = room.id,
            )

            val gap = db.historyGapDao().forRoom(room.id).single()
            assertTrue("an island gap is born recoverable", gap.recoverable)
            val islandNewest = checkNotNull(db.messageDao().byMsgid(room.id, "m502"))
            assertEquals("m502", gap.olderMsgid)
            assertEquals(502L, gap.olderServerTime)
            assertEquals(islandNewest.id, gap.olderEventId)
            assertEquals(islandNewest.timelineOrder, gap.olderTimelineOrder)
            val retainedOldest = checkNotNull(db.messageDao().byMsgid(room.id, "m2000"))
            assertEquals("m2000", gap.newerMsgid)
            assertEquals(2000L, gap.newerServerTime)
            assertEquals(retainedOldest.id, gap.newerEventId)
            assertEquals(retainedOldest.timelineOrder, gap.newerTimelineOrder)
        }

    @Test
    fun beforePageFromTheOldestBoundaryRecordsNoIslandGap() =
        runTest {
            // The ordinary APPEND ladder pages BEFORE the previously-oldest boundary, which makes the
            // returned page adjacent to it BY PROTOCOL. Recording a gap there would draw a seam on
            // every appended page of healthy history. Both advertised selector forms must be honored.
            val byMsgid = seedIslandHost("#ladder-msgid", prefix = "lm")
            appendLadderPage(byMsgid, "#ladder-msgid", prefix = "lm", bound1 = ChatHistorySelectors.msgid("lm2000"))
            assertEquals(
                db.historyGapDao().forRoom(byMsgid.id).toString(),
                0,
                db.historyGapDao().forRoom(byMsgid.id).size,
            )

            val byTimestamp = seedIslandHost("#ladder-ts", prefix = "lt")
            appendLadderPage(byTimestamp, "#ladder-ts", prefix = "lt", bound1 = ChatHistorySelectors.timestamp(2000))
            assertEquals(
                db.historyGapDao().forRoom(byTimestamp.id).toString(),
                0,
                db.historyGapDao().forRoom(byTimestamp.id).size,
            )
        }

    @Test
    fun aroundPageOverlappingTheOldestRetainedRowRecordsNoIslandGap() =
        runTest {
            // The page straddles the previously-oldest retained row, so it is not a separate island.
            val room = seedIslandHost("#overlap", prefix = "ov")

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AROUND,
                    "#overlap",
                    bound1 = "msgid=ov2000",
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events = listOf(1900, 1950, 2050).map { islandRow("#overlap", it, prefix = "ov") },
                    oldest = ChatHistoryReference("ov1900", 1900),
                    newest = ChatHistoryReference("ov2050", 2050),
                    endOfHistory = false,
                    primaryMessageCount = 3,
                ),
                expectedRoomId = room.id,
            )

            val gaps = db.historyGapDao().forRoom(room.id)
            assertEquals(gaps.toString(), 0, gaps.size)
        }

    @Test
    fun aroundPageInsideAnExistingGapSplitsWithoutAnIslandGap() =
        runTest {
            // Landing inside a recorded interval splits it. The split branch already accounts for the
            // page, so the island rule must not add a third row on top of the two halves.
            val room = seedIslandHost("#split", prefix = "sp")
            aroundPage(room, "#split", prefix = "sp", times = 500..502, bound1 = "msgid=sp501")
            assertEquals(1, db.historyGapDao().forRoom(room.id).size)

            aroundPage(room, "#split", prefix = "sp", times = 800..802, bound1 = "msgid=sp801")

            val gaps = db.historyGapDao().forRoom(room.id).sortedBy { it.olderServerTime }
            assertEquals(gaps.toString(), 2, gaps.size)
            assertEquals(502L, gaps.first().olderServerTime)
            assertEquals(800L, gaps.first().newerServerTime)
            assertEquals(802L, gaps.last().olderServerTime)
            assertEquals(2000L, gaps.last().newerServerTime)
        }

    @Test
    fun islandGapIsFillableByBeforePagingFromItsNewerEdge() =
        runTest {
            // End-to-end recoverability: the interval the island rule records must be reachable by a
            // gap-directed BEFORE from its newer edge, and close when the fill reaches the island.
            val room = seedIslandHost("#fillable", prefix = "fi")
            aroundPage(room, "#fillable", prefix = "fi", times = 500..502, bound1 = "msgid=fi501")
            val gapId =
                db
                    .historyGapDao()
                    .forRoom(room.id)
                    .single()
                    .id

            processor.persistHistoryPageResult(
                networkId = networkId,
                request =
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.BEFORE,
                        "#fillable",
                        bound1 = "msgid=fi2000",
                        limit = 50,
                    ),
                response =
                    ChatHistoryResponse.Messages(
                        // BEFORE is exclusive only of its bound, so the fill reaches back onto the island's
                        // newest row (fi502) and continues upward from there.
                        events = listOf(502, 600, 700).map { islandRow("#fillable", it, prefix = "fi") },
                        oldest = ChatHistoryReference("fi502", 502),
                        newest = ChatHistoryReference("fi700", 700),
                        endOfHistory = false,
                        primaryMessageCount = 3,
                    ),
                expectedRoomId = room.id,
                historyGapId = gapId,
            )

            val gaps = db.historyGapDao().forRoom(room.id)
            assertEquals(gaps.toString(), 0, gaps.size)
            assertNotNull(db.messageDao().byMsgid(room.id, "fi600"))
        }

    @Test
    fun msgidlessEqualTimestampIslandBoundaryWithMatchingAnchorRecordsNoGap() =
        runTest {
            // A msgid-less page boundary at the previously-oldest timestamp that resolves to the SAME
            // stored event is not below it. Only the exact tuple proves that, never the timestamp.
            val room = seedIslandHost("#anchor", prefix = "an")

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AROUND,
                    "#anchor",
                    bound1 = ChatHistorySelectors.timestamp(2000),
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    // The 2000 row replays without its msgid and dedups onto the retained row.
                    events = listOf(1900, 2000).map { islandRow("#anchor", it, prefix = null) },
                    oldest = ChatHistoryReference(null, 1900),
                    newest = ChatHistoryReference(null, 2000),
                    endOfHistory = false,
                    primaryMessageCount = 2,
                ),
                expectedRoomId = room.id,
            )

            val gaps = db.historyGapDao().forRoom(room.id)
            assertEquals(gaps.toString(), 0, gaps.size)
        }

    @Test
    fun undecidableEqualTimestampIslandBoundaryRecordsNoGap() =
        runTest {
            // Fully msgid-less on both sides: the stored oldest boundary has no resolvable identity, so
            // the page's equal-time newest row MAY BE that same row. A gap here would be zero-width and
            // assert a missing message that cannot exist between an event and itself.
            processor.process(networkId, islandRow("#undecidable", 2000, prefix = null))
            val room = checkNotNull(db.bufferDao().byName(networkId, "#undecidable"))

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AROUND,
                    "#undecidable",
                    bound1 = ChatHistorySelectors.timestamp(2000),
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events =
                        listOf(
                            islandRow("#undecidable", 1900, prefix = null),
                            IrcEvent.ChatMessage(
                                ctx(null, 2000),
                                IrcEvent.ChatKind.PRIVMSG,
                                Prefix("alice"),
                                "#undecidable",
                                "a different line at the same instant",
                                false,
                                null,
                            ),
                        ),
                    oldest = ChatHistoryReference(null, 1900),
                    newest = ChatHistoryReference(null, 2000),
                    endOfHistory = false,
                    primaryMessageCount = 2,
                ),
                expectedRoomId = room.id,
            )

            val gaps = db.historyGapDao().forRoom(room.id)
            assertEquals(gaps.toString(), 0, gaps.size)
        }

    @Test
    fun timestampOnlyAroundIslandGapStoresTheExactIslandEdgeTuple() =
        runTest {
            // Timestamp-only wire (soju MSGREFTYPES=timestamp): the island's newest edge still carries
            // the exact local tuple so an equal-timestamp neighbor can never be mistaken for it. The
            // newer edge has no resolvable identity on this wire and falls back to its timestamp.
            processor.process(networkId, islandRow("#ts-island", 2000, prefix = null))
            processor.process(networkId, islandRow("#ts-island", 2100, prefix = null))
            val room = checkNotNull(db.bufferDao().byName(networkId, "#ts-island"))

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AROUND,
                    "#ts-island",
                    bound1 = ChatHistorySelectors.timestamp(501),
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events = (500..502).map { islandRow("#ts-island", it, prefix = null) },
                    oldest = ChatHistoryReference(null, 500),
                    newest = ChatHistoryReference(null, 502),
                    endOfHistory = false,
                    primaryMessageCount = 3,
                ),
                expectedRoomId = room.id,
            )

            val gap = db.historyGapDao().forRoom(room.id).single()
            val islandNewest = pagingList(room.id).single { it.text == "row502" }
            assertTrue(gap.recoverable)
            assertNull(gap.olderMsgid)
            assertEquals(502L, gap.olderServerTime)
            assertEquals(islandNewest.id, gap.olderEventId)
            assertEquals(islandNewest.timelineOrder, gap.olderTimelineOrder)
            assertNull(gap.newerMsgid)
            assertEquals(2000L, gap.newerServerTime)
            assertNull(gap.newerEventId)
        }

    /** Two retained rows at T=2000/2100 so a page below them forms a distinct older island. */
    private suspend fun seedIslandHost(
        target: String,
        prefix: String? = "m",
    ): BufferEntity {
        processor.process(networkId, islandRow(target, 2000, prefix))
        processor.process(networkId, islandRow(target, 2100, prefix))
        return checkNotNull(db.bufferDao().byName(networkId, target))
    }

    private fun islandRow(
        target: String,
        time: Int,
        prefix: String? = "m",
    ) = IrcEvent.ChatMessage(
        ctx(prefix?.let { "$it$time" }, time.toLong()),
        IrcEvent.ChatKind.PRIVMSG,
        Prefix("alice"),
        target,
        "row$time",
        false,
        null,
    )

    private suspend fun aroundPage(
        room: BufferEntity,
        target: String,
        prefix: String,
        times: IntRange,
        bound1: String,
    ) {
        processor.persistHistoryPage(
            networkId,
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.AROUND, target, bound1 = bound1, limit = 50),
            ChatHistoryResponse.Messages(
                events = times.map { islandRow(target, it, prefix) },
                oldest = ChatHistoryReference("$prefix${times.first}", times.first.toLong()),
                newest = ChatHistoryReference("$prefix${times.last}", times.last.toLong()),
                endOfHistory = false,
                primaryMessageCount = times.count(),
            ),
            expectedRoomId = room.id,
        )
    }

    private suspend fun appendLadderPage(
        room: BufferEntity,
        target: String,
        prefix: String,
        bound1: String,
    ) {
        processor.persistHistoryPage(
            networkId,
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, target, bound1 = bound1, limit = 50),
            ChatHistoryResponse.Messages(
                events = (500..502).map { islandRow(target, it, prefix) },
                oldest = ChatHistoryReference("${prefix}500", 500),
                newest = ChatHistoryReference("${prefix}502", 502),
                endOfHistory = false,
                primaryMessageCount = 3,
            ),
            expectedRoomId = room.id,
        )
    }

    @Test
    fun saturatedTimestampOnlyGapFillKeepsTheRecededRemainderRecoverable() =
        runTest {
            // Filling a recoverable gap with a saturated msgid-less BEFORE page recedes its newer edge
            // and must keep the remainder RECOVERABLE so the next APPEND can continue toward the marker.
            val room = BufferStore(db).getOrCreate(networkId, "#ts-fill", "#ts-fill", BufferType.CHANNEL)
            val gapId =
                db.historyGapDao().insert(
                    HistoryGapEntity(0, room.id, null, 10, null, 212),
                )
            val page =
                (162..211).map { time ->
                    IrcEvent.ChatMessage(
                        ctx(null, time.toLong()),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("alice"),
                        "#ts-fill",
                        "row$time",
                        false,
                        null,
                    )
                }

            processor.persistHistoryPageResult(
                networkId = networkId,
                request =
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.BEFORE,
                        "#ts-fill",
                        bound1 = "timestamp=1970-01-01T00:00:00.212Z",
                        limit = 50,
                    ),
                response =
                    ChatHistoryResponse.Messages(
                        events = page,
                        oldest = ChatHistoryReference(null, 162),
                        newest = ChatHistoryReference(null, 211),
                        endOfHistory = false,
                        primaryMessageCount = 50,
                    ),
                expectedRoomId = room.id,
                historyGapId = gapId,
            )

            val remainder = db.historyGapDao().forRoom(room.id).single()
            assertTrue(remainder.recoverable)
            assertEquals(10L, remainder.olderServerTime)
            assertEquals(162L, remainder.newerServerTime)
        }

    @Test
    fun saturatedTimestampOnlySeedRecordsNoDegenerateGap() =
        runTest {
            // Source-level invariant for the fresh-buffer seed on a timestamp-only wire: the page is
            // saturated (primary == limit) and msgid-less with no prior newest boundary and no focused
            // gap, which used to write a ZERO-WIDTH `recoverable = false` gap on the page's oldest row.
            // Both claims were false — an interval whose edges name the same row cannot hold a message,
            // and recoverable=false is reserved for server-proven-empty intervals — and the row poisoned
            // paging (an unrecoverable focused gap is terminal for APPEND) and the Recent window (it
            // clamps at the edge row). Nothing missing was observed, so nothing is recorded; the cursor
            // and historyComplete=false carry "there may be more" on their own.
            val room = BufferStore(db).getOrCreate(networkId, "#ts-seed", "#ts-seed", BufferType.CHANNEL)
            val page =
                (212..261).map { time ->
                    IrcEvent.ChatMessage(
                        ctx(null, time.toLong()),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("alice"),
                        "#ts-seed",
                        "row$time",
                        false,
                        null,
                    )
                }

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#ts-seed", limit = 50),
                ChatHistoryResponse.Messages(
                    events = page,
                    oldest = ChatHistoryReference(null, 212),
                    newest = ChatHistoryReference(null, 261),
                    endOfHistory = false,
                    primaryMessageCount = 50,
                ),
                expectedRoomId = room.id,
            )

            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())
            // The page is still not proof of completeness, so older paging stays open.
            assertFalse(checkNotNull(db.bufferDao().observeById(room.id)).historyComplete)
            assertEquals(212L, checkNotNull(db.historyCursorDao().byRoom(room.id)).oldestServerTime)
        }

    @Test
    fun saturatedTimestampOnlyBeforePageRecordsNoDegenerateGap() =
        runTest {
            // Same invariant for the cursor-driven BEFORE page (no focused gap). Every page of a soju
            // backfill ladder is saturated and msgid-less, so the old rule would have written one
            // unrecoverable zero-width gap per page — each of which permanently ends older paging.
            val room = BufferStore(db).getOrCreate(networkId, "#ts-before", "#ts-before", BufferType.CHANNEL)
            val page =
                (162..211).map { time ->
                    IrcEvent.ChatMessage(
                        ctx(null, time.toLong()),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("alice"),
                        "#ts-before",
                        "row$time",
                        false,
                        null,
                    )
                }

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.BEFORE,
                    "#ts-before",
                    bound1 = "timestamp=1970-01-01T00:00:00.212Z",
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events = page,
                    oldest = ChatHistoryReference(null, 162),
                    newest = ChatHistoryReference(null, 211),
                    endOfHistory = false,
                    primaryMessageCount = 50,
                ),
                expectedRoomId = room.id,
            )

            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())
            assertFalse(checkNotNull(db.bufferDao().observeById(room.id)).historyComplete)
        }

    @Test
    fun emptyGapFillResponseStillMarksTheGapUnrecoverable() =
        runTest {
            // The one legitimate route to recoverable=false: the server PROVED the interval empty by
            // returning zero primary messages for the gap-directed request.
            val room = BufferStore(db).getOrCreate(networkId, "#ts-empty", "#ts-empty", BufferType.CHANNEL)
            val gapId =
                db.historyGapDao().insert(
                    HistoryGapEntity(0, room.id, null, 10, null, 212),
                )

            processor.persistHistoryPageResult(
                networkId = networkId,
                request =
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.BEFORE,
                        "#ts-empty",
                        bound1 = "timestamp=1970-01-01T00:00:00.212Z",
                        limit = 50,
                    ),
                response = ChatHistoryResponse.Messages(emptyList(), null, null, false, 0),
                expectedRoomId = room.id,
                historyGapId = gapId,
            )

            assertFalse(
                db
                    .historyGapDao()
                    .forRoom(room.id)
                    .single()
                    .recoverable,
            )
        }

    @Test
    fun exactGapIdentityDisambiguatesEqualTimeTimestampFallback() =
        runTest {
            val room = BufferStore(db).getOrCreate(networkId, "#ambiguous-gap", "#ambiguous-gap", BufferType.CHANNEL)
            val firstId =
                db.historyGapDao().insert(
                    HistoryGapEntity(0, room.id, "a", 100, "b", 500),
                )
            val secondId =
                db.historyGapDao().insert(
                    HistoryGapEntity(0, room.id, "c", 100, "d", 900),
                )

            processor.persistHistoryPageResult(
                networkId = networkId,
                request =
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.AFTER,
                        "#ambiguous-gap",
                        bound1 = "timestamp=1970-01-01T00:00:00.100Z",
                        limit = 50,
                    ),
                response = ChatHistoryResponse.Messages(emptyList(), null, null, true, 0),
                expectedRoomId = room.id,
                historyGapId = secondId,
            )

            val gaps = db.historyGapDao().forRoom(room.id).associateBy { it.id }
            assertTrue(checkNotNull(gaps[firstId]).recoverable)
            assertFalse(checkNotNull(gaps[secondId]).recoverable)
        }

    @Test
    fun terminalDirectionalPageClosesGapAfterReachingOppositeBoundary() =
        runTest {
            val room = BufferStore(db).getOrCreate(networkId, "#closed-gap", "#closed-gap", BufferType.CHANNEL)
            db.historyGapDao().insert(
                HistoryGapEntity(0, room.id, "m100", 100, "m900", 900),
            )
            val terminal =
                IrcEvent.ChatMessage(
                    ctx("m900", 900),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#closed-gap",
                    "terminal",
                    false,
                    null,
                )

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AFTER,
                    "#closed-gap",
                    bound1 = "msgid=m100",
                    limit = 50,
                ),
                ChatHistoryResponse.Messages(
                    events = listOf(terminal),
                    oldest = ChatHistoryReference("m900", 900),
                    newest = ChatHistoryReference("m900", 900),
                    endOfHistory = true,
                    primaryMessageCount = 1,
                ),
                expectedRoomId = room.id,
            )

            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())
        }

    @Test
    fun duplicateLatestPrimaryPageCreatesNoEqualTimeGap() =
        runTest {
            val target = "#duplicate-latest"
            val room = BufferStore(db).getOrCreate(networkId, target, target, BufferType.CHANNEL)
            val request = ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = 2)
            val response =
                ChatHistoryResponse.Messages(
                    events =
                        listOf(
                            islandRow(target, 100, prefix = "a").copy(text = "a"),
                            islandRow(target, 100, prefix = "b").copy(text = "b"),
                        ),
                    oldest = ChatHistoryReference("a100", 100),
                    newest = ChatHistoryReference("b100", 100),
                    endOfHistory = false,
                    primaryMessageCount = 2,
                )
            processor.persistHistoryPage(networkId, request, response, expectedRoomId = room.id)
            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())

            val duplicate = processor.persistHistoryPageResult(networkId, request, response, expectedRoomId = room.id)

            assertEquals(0, duplicate.inserted)
            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())
        }

    @Test
    fun latestPrimaryPageDoesNotRecreateAClosedEqualTimeGap() =
        runTest {
            val target = "#closed-latest"
            val room = BufferStore(db).getOrCreate(networkId, target, target, BufferType.CHANNEL)
            val request = ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = 2)
            val response =
                ChatHistoryResponse.Messages(
                    events =
                        listOf(
                            islandRow(target, 100, prefix = "a").copy(text = "a"),
                            islandRow(target, 100, prefix = "b").copy(text = "b"),
                        ),
                    oldest = ChatHistoryReference("a100", 100),
                    newest = ChatHistoryReference("b100", 100),
                    endOfHistory = false,
                    primaryMessageCount = 2,
                )
            processor.persistHistoryPage(networkId, request, response, expectedRoomId = room.id)
            // A gap left by an earlier version is closed by the directed fill.
            val gapId = db.historyGapDao().insert(HistoryGapEntity(0, room.id, "b100", 100, "a100", 100))
            processor.persistHistoryPageResult(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, target, bound1 = "msgid=a100", limit = 2),
                ChatHistoryResponse.Messages(
                    events = listOf(islandRow(target, 50, prefix = "c")),
                    oldest = ChatHistoryReference("c50", 50),
                    newest = ChatHistoryReference("c50", 50),
                    endOfHistory = true,
                    primaryMessageCount = 1,
                ),
                expectedRoomId = room.id,
                historyGapId = gapId,
            )
            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())

            val duplicate = processor.persistHistoryPageResult(networkId, request, response, expectedRoomId = room.id)

            assertEquals(0, duplicate.inserted)
            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())
        }

    @Test
    fun beforePrimaryPageClosesGapWithInteriorOppositeBoundary() =
        runTest {
            val target = "#before-interior"
            val room = BufferStore(db).getOrCreate(networkId, target, target, BufferType.CHANNEL)
            val gapId = db.historyGapDao().insert(HistoryGapEntity(0, room.id, "m100", 100, "m900", 900))

            processor.persistHistoryPageResult(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, target, bound1 = "msgid=m900", limit = 3),
                ChatHistoryResponse.Messages(
                    events = listOf("a", "m", "c").map { islandRow(target, 100, prefix = it).copy(text = it) },
                    oldest = ChatHistoryReference("a100", 100),
                    newest = ChatHistoryReference("c100", 100),
                    endOfHistory = false,
                    primaryMessageCount = 3,
                ),
                expectedRoomId = room.id,
                historyGapId = gapId,
            )

            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())
        }

    @Test
    fun afterPrimaryPageClosesGapWithInteriorOppositeBoundary() =
        runTest {
            val target = "#after-interior"
            val room = BufferStore(db).getOrCreate(networkId, target, target, BufferType.CHANNEL)
            val gapId = db.historyGapDao().insert(HistoryGapEntity(0, room.id, "m100", 100, "m900", 900))

            processor.persistHistoryPageResult(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.AFTER, target, bound1 = "msgid=m100", limit = 3),
                ChatHistoryResponse.Messages(
                    events = listOf("a", "m", "c").map { islandRow(target, 900, prefix = it).copy(text = it) },
                    oldest = ChatHistoryReference("a900", 900),
                    newest = ChatHistoryReference("c900", 900),
                    endOfHistory = false,
                    primaryMessageCount = 3,
                ),
                expectedRoomId = room.id,
                historyGapId = gapId,
            )

            assertTrue(db.historyGapDao().forRoom(room.id).isEmpty())
        }

    @Test
    fun directionalContextBoundaryDoesNotCloseEqualTimeRemainder() =
        runTest {
            listOf(ChatHistoryRequest.Subcommand.BEFORE, ChatHistoryRequest.Subcommand.AFTER).forEach { direction ->
                val target = "#context-${direction.name.lowercase()}"
                val room = BufferStore(db).getOrCreate(networkId, target, target, BufferType.CHANNEL)
                val gapId = db.historyGapDao().insert(HistoryGapEntity(0, room.id, "m100", 100, "m900", 900))
                val before = direction == ChatHistoryRequest.Subcommand.BEFORE
                val oppositeTime = if (before) 100 else 900
                val context = islandRow(target, oppositeTime)

                processor.persistHistoryPageResult(
                    networkId,
                    ChatHistoryRequest(direction, target, bound1 = if (before) "msgid=m900" else "msgid=m100", limit = 2),
                    ChatHistoryResponse.Messages(
                        events =
                            listOf(
                                islandRow(target, oppositeTime, prefix = "a").copy(text = "a"),
                                context.copy(ctx = context.ctx.copy(isHistoryContext = true)),
                                islandRow(target, oppositeTime, prefix = "c").copy(text = "c"),
                            ),
                        oldest = ChatHistoryReference("a$oppositeTime", oppositeTime.toLong()),
                        newest = ChatHistoryReference("c$oppositeTime", oppositeTime.toLong()),
                        endOfHistory = false,
                        primaryMessageCount = 2,
                    ),
                    expectedRoomId = room.id,
                    historyGapId = gapId,
                )

                val remainder = db.historyGapDao().forRoom(room.id).single()
                assertEquals(gapId, remainder.id)
                assertEquals(if (before) "m100" else "c900", remainder.olderMsgid)
                assertEquals(if (before) "a100" else "m900", remainder.newerMsgid)
                assertTrue(remainder.recoverable)
            }
        }

    @Test
    fun latestContextBoundaryDoesNotSuppressEqualTimeGap() =
        runTest {
            val target = "#latest-context"
            val context = islandRow(target, 100)
            processor.process(networkId, context)
            val room = checkNotNull(db.bufferDao().byName(networkId, target))

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = 2),
                ChatHistoryResponse.Messages(
                    events =
                        listOf(
                            islandRow(target, 100, prefix = "a").copy(text = "a"),
                            context.copy(ctx = context.ctx.copy(isHistoryContext = true)),
                            islandRow(target, 100, prefix = "c").copy(text = "c"),
                        ),
                    oldest = ChatHistoryReference("a100", 100),
                    newest = ChatHistoryReference("c100", 100),
                    endOfHistory = false,
                    primaryMessageCount = 2,
                ),
                expectedRoomId = room.id,
            )

            val gap = db.historyGapDao().forRoom(room.id).single()
            assertEquals("m100", gap.olderMsgid)
            assertEquals("a100", gap.newerMsgid)
            assertTrue(gap.recoverable)
        }

    @Test
    fun msgidlessLatestBoundaryProvesCoverageOnlyWhenPrimary() =
        runTest {
            listOf(true, false).forEach { isContext ->
                val target = "#msgidless-context-$isContext"
                val boundary = islandRow(target, 100, prefix = null).copy(text = "boundary")
                processor.process(networkId, boundary)
                val room = checkNotNull(db.bufferDao().byName(networkId, target))
                val boundaryId = db.messageDao().newestMessage(room.id)!!.id

                processor.persistHistoryPage(
                    networkId,
                    ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = 3),
                    ChatHistoryResponse.Messages(
                        events =
                            listOf(
                                islandRow(target, 100, prefix = null).copy(text = "earlier"),
                                boundary.copy(ctx = boundary.ctx.copy(isHistoryContext = isContext)),
                                islandRow(target, 100, prefix = null).copy(text = "later"),
                            ),
                        oldest = ChatHistoryReference(null, 100),
                        newest = ChatHistoryReference(null, 100),
                        endOfHistory = false,
                        primaryMessageCount = if (isContext) 2 else 3,
                    ),
                    expectedRoomId = room.id,
                )

                val gaps = db.historyGapDao().forRoom(room.id)
                if (isContext) {
                    val gap = gaps.single()
                    assertEquals(boundaryId, gap.olderEventId)
                    assertEquals(pagingList(room.id).single { it.text == "earlier" }.id, gap.newerEventId)
                    assertTrue(gap.recoverable)
                } else {
                    assertTrue(gaps.isEmpty())
                }
            }
        }

    @Test
    fun latestRetainsGapWhenOpaqueBoundariesShareATimestamp() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("old-boundary", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#same-time",
                    "old",
                    false,
                    null,
                ),
            )
            val room = checkNotNull(db.bufferDao().byName(networkId, "#same-time"))
            val newest =
                IrcEvent.ChatMessage(
                    ctx("new-boundary", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#same-time",
                    "new",
                    false,
                    null,
                )

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#same-time", limit = 50),
                ChatHistoryResponse.Messages(
                    events = listOf(newest),
                    oldest = ChatHistoryReference("new-boundary", 100),
                    newest = ChatHistoryReference("new-boundary", 100),
                    endOfHistory = false,
                    primaryMessageCount = 1,
                ),
                expectedRoomId = room.id,
            )

            val gap = db.historyGapDao().forRoom(room.id).single()
            assertEquals("old-boundary", gap.olderMsgid)
            assertEquals("new-boundary", gap.newerMsgid)
            assertEquals(100L, gap.olderServerTime)
            assertEquals(100L, gap.newerServerTime)
        }

    @Test
    fun latestKeepsDistinctSameTimestampGapsSeparate() =
        runTest {
            val room = BufferStore(db).getOrCreate(networkId, "#same-time-gaps", "#same-time-gaps", BufferType.CHANNEL)
            val ids =
                db.messageDao().insertAll(
                    listOf("a", "b", "c").map { msgid ->
                        MessageEntity(
                            bufferId = room.id,
                            msgid = msgid,
                            serverTime = 100,
                            sender = "alice",
                            kind = MessageKind.PRIVMSG,
                            text = msgid,
                            dedupKey = msgid,
                        )
                    },
                )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = room.id,
                    olderMsgid = "a",
                    olderServerTime = 100,
                    newerMsgid = "b",
                    newerServerTime = 100,
                    olderEventId = ids[0],
                    olderTimelineOrder = ids[0],
                    newerEventId = ids[1],
                    newerTimelineOrder = ids[1],
                ),
            )
            db.historyCursorDao().upsert(
                HistoryCursorEntity(room.id, "c", 100, "a", 100, false),
            )
            val newest =
                IrcEvent.ChatMessage(
                    ctx("d", 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#same-time-gaps",
                    "d",
                    false,
                    null,
                )

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#same-time-gaps", limit = 50),
                ChatHistoryResponse.Messages(
                    listOf(newest),
                    ChatHistoryReference("d", 100),
                    ChatHistoryReference("d", 100),
                    false,
                    1,
                ),
                expectedRoomId = room.id,
            )

            assertEquals(
                setOf("a" to "b", "c" to "d"),
                db
                    .historyGapDao()
                    .forRoom(room.id)
                    .map { it.olderMsgid to it.newerMsgid }
                    .toSet(),
            )
        }

    @Test
    fun overlappingPageAtEqualOlderTimestampRetainsTheUncoveredPrefix() =
        runTest {
            val room = BufferStore(db).getOrCreate(networkId, "#equal-edge", "#equal-edge", BufferType.CHANNEL)
            val boundaries =
                db.messageDao().insertAll(
                    listOf(
                        MessageEntity(bufferId = room.id, msgid = "a", serverTime = 100, sender = "alice", kind = MessageKind.PRIVMSG, text = "a", dedupKey = "a"),
                        MessageEntity(bufferId = room.id, msgid = "z", serverTime = 200, sender = "alice", kind = MessageKind.PRIVMSG, text = "z", dedupKey = "z"),
                    ),
                )
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = room.id,
                    olderMsgid = "a",
                    olderServerTime = 100,
                    newerMsgid = "z",
                    newerServerTime = 200,
                    olderEventId = boundaries[0],
                    olderTimelineOrder = boundaries[0],
                    newerEventId = boundaries[1],
                    newerTimelineOrder = boundaries[1],
                ),
            )
            val page =
                listOf(
                    IrcEvent.ChatMessage(ctx("b", 100), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#equal-edge", "b", false, null),
                    IrcEvent.ChatMessage(ctx("z", 200), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#equal-edge", "z", false, null),
                )

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.AROUND, "#equal-edge", bound1 = "msgid=b", limit = 50),
                ChatHistoryResponse.Messages(
                    page,
                    ChatHistoryReference("b", 100),
                    ChatHistoryReference("z", 200),
                    false,
                    2,
                ),
                expectedRoomId = room.id,
            )

            val gaps = db.historyGapDao().forRoom(room.id)
            assertEquals(gaps.toString(), 1, gaps.size)
            val remaining = gaps.single()
            assertEquals("a", remaining.olderMsgid)
            assertEquals("b", remaining.newerMsgid)
        }

    @Test
    fun msgidlessSameTimestampLatestGapStoresExactTupleBoundary() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(ctx("old", 100), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#tuple", "old", false, null),
            )
            val room = checkNotNull(db.bufferDao().byName(networkId, "#tuple"))
            val newest =
                IrcEvent.ChatMessage(
                    ctx(null, 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#tuple",
                    "new",
                    false,
                    null,
                )

            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#tuple", limit = 50),
                ChatHistoryResponse.Messages(
                    listOf(newest),
                    ChatHistoryReference(null, 100),
                    ChatHistoryReference(null, 100),
                    false,
                    1,
                ),
                expectedRoomId = room.id,
            )

            val gap = db.historyGapDao().forRoom(room.id).single()
            val newRow = db.messageDao().newestMessage(room.id)!!
            assertEquals(newRow.id, gap.newerEventId)
            assertEquals(newRow.timelineOrder, gap.newerTimelineOrder)
        }

    @Test
    fun protocolPagePreservesPostMergeWinnerCursorExtents() =
        runTest {
            val store = BufferStore(db)
            val winner = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
            store.bindQueryIdentity(winner.id, networkId, "alice", "Alice", "shared-account")
            val loser = store.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)
            db.historyCursorDao().upsert(
                HistoryCursorEntity(
                    roomId = winner.id,
                    newestMsgid = "winner-newest",
                    newestServerTime = 1_000,
                    oldestMsgid = "winner-oldest",
                    oldestServerTime = 100,
                ),
            )
            db.historyCursorDao().upsert(
                HistoryCursorEntity(
                    roomId = loser.id,
                    newestMsgid = "loser-newest",
                    newestServerTime = 800,
                    oldestMsgid = "loser-oldest",
                    oldestServerTime = 300,
                ),
            )
            val historyMessage =
                IrcEvent.ChatMessage(
                    ctx(msgid = "page", time = 200, account = "shared-account"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("Bob"),
                    "me",
                    "merged history",
                    false,
                    null,
                )
            val historyNick =
                IrcEvent.NickChanged(
                    ctx(msgid = "page-context", time = 201),
                    from = "Bob",
                    to = "Robert",
                    isSelf = false,
                )

            val roomId =
                processor.persistHistoryPage(
                    networkId,
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.BEFORE,
                        "bob",
                        bound1 = "msgid=loser-oldest",
                        limit = 50,
                    ),
                    ChatHistoryResponse.Messages(
                        events = listOf(historyMessage, historyNick),
                        oldest = ChatHistoryReference("page", 200),
                        newest = ChatHistoryReference("page", 200),
                        endOfHistory = false,
                        primaryMessageCount = 1,
                    ),
                )

            assertEquals(winner.id, roomId)
            val cursor = db.historyCursorDao().byRoom(winner.id)
            assertEquals("winner-oldest", cursor?.oldestMsgid)
            assertEquals(100L, cursor?.oldestServerTime)
            assertEquals("winner-newest", cursor?.newestMsgid)
            assertEquals(1_000L, cursor?.newestServerTime)
            assertNull(db.messageDao().byMsgid(winner.id, "page-context"))
            assertNull(db.messageDao().byMsgid(loser.id, "page-context"))
            assertNull(db.historyCursorDao().byRoom(loser.id))
        }

    @Test
    fun push_persistsSupportedMessage_withoutMutatingSessionState() =
        runTest {
            val notifications = mutableListOf<String>()
            val recording =
                EventProcessor(
                    db,
                    TypingTrackerImpl(),
                    object : MessageNotifier {
                        override suspend fun onIncoming(
                            networkId: Long,
                            bufferId: Long,
                            type: BufferType,
                            hasMention: Boolean,
                            message: IrcEvent.ChatMessage,
                        ) {
                            notifications += message.text
                        }
                    },
                )
            recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
            recording.process(networkId, IrcEvent.Joined(ctx("push-seed", 5_000), "me", "#push", null, null, true))
            recording.process(networkId, IrcEvent.TopicChanged(ctx("push-topic-live", 5_001), "#push", "current", "op"))
            recording.process(networkId, IrcEvent.ReadMarker("#push", 5_002))
            val before = db.bufferDao().byName(networkId, "#push")!!
            val membersBefore = db.memberDao().allNow(before.id)

            recording.processPush(networkId, IrcEvent.Joined(ctx("push-join", 6_000), "Mallory", "#push", null, null, false))
            recording.processPush(networkId, IrcEvent.TopicChanged(ctx("push-topic", 6_001), "#push", "pushed", "mallory"))
            recording.processPush(networkId, IrcEvent.ReadMarker("#push", 6_002))
            recording.processPush(
                networkId,
                IrcEvent.ChatMessage(
                    ctx("push-chat", 6_003),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("Alice"),
                    "#push",
                    "hello me",
                    false,
                    null,
                ),
            )

            assertEquals(before, db.bufferDao().byName(networkId, "#push"))
            assertEquals(membersBefore, db.memberDao().allNow(before.id))
            assertEquals(listOf("hello me"), notifications)
            assertEquals(1, pagingList(before.id).count { it.msgid == "push-chat" })
            assertTrue(pagingList(before.id).none { it.msgid == "push-join" || it.msgid == "push-topic" })
        }

    @Test
    fun concurrentLiveHistoryAndPushFirstContact_convergesOnOneLogicalMessage() =
        runTest {
            val start = CompletableDeferred<Unit>()
            val message =
                IrcEvent.ChatMessage(
                    ctx("concurrent-message", 50_000),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("Alice"),
                    "#concurrent",
                    "one logical message",
                    false,
                    null,
                )
            listOf(
                async {
                    start.await()
                    processor.process(networkId, message)
                },
                async {
                    start.await()
                    processor.process(networkId, IrcEvent.HistoryBatch("#concurrent", listOf(message)))
                },
                async {
                    start.await()
                    processor.processPush(networkId, message)
                },
            ).also { jobs ->
                start.complete(Unit)
                jobs.awaitAll()
            }

            val buffer = db.bufferDao().byName(networkId, "#concurrent")!!
            assertEquals(1, pagingList(buffer.id).count { it.msgid == "concurrent-message" })
            assertEquals(
                1,
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .count { it.bufferId == buffer.id },
            )
        }

    @Test
    fun networkEvictionAndShutdown_releaseProcessorSequencers() =
        runTest {
            assertEquals(1, processor.sequencerSize())
            processor.evictNetwork(networkId)
            assertEquals(0, processor.sequencerSize())

            processor.process(
                networkId,
                IrcEvent.ChatMessage(ctx("after-evict"), IrcEvent.ChatKind.PRIVMSG, Prefix("Alice"), "#room", "hi", false, null),
            )
            assertEquals(1, processor.sequencerSize())
            processor.shutdown()
            assertEquals(0, processor.sequencerSize())
        }
}
