package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.RoomAliasEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

// End-to-end: repository sanitizes raw input and drives the FTS DAO, honoring the kind filter
// and buffer scoping.
@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {
    private lateinit var db: MotdDatabase
    private lateinit var repo: SearchRepositoryImpl
    private lateinit var settings: FakeSettingsRepository
    private var b1: Long = 0
    private var b2: Long = 0

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            settings = FakeSettingsRepository()
            repo = SearchRepositoryImpl(db, settings)
            val nid = db.networkDao().insert(network())
            b1 = db.bufferDao().insert(buffer(nid, "#one"))
            b2 = db.bufferDao().insert(buffer(nid, "#two"))
            db.messageDao().insertAll(
                listOf(
                    message(b1, "hello world", serverTime = 1, dedupKey = "a"),
                    message(b2, "hello there", serverTime = 2, dedupKey = "b"),
                    message(b1, "hello join", serverTime = 3, dedupKey = "c", kind = MessageKind.JOIN),
                ),
            )
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun rawPrefixInput_matchesAcrossBuffers_excludesSystemKinds() =
        runTest {
            val hits = repo.search("hel", null).first().hits
            // JOIN row excluded → only the two chat messages match.
            assertEquals(2, hits.size)
            assertTrue(hits.all { it.message.kind == MessageKind.PRIVMSG })
        }

    @Test
    fun bufferScopedSearch_restrictsResults() =
        runTest {
            val hits = repo.search("hello", b2).first().hits
            assertEquals(1, hits.size)
            assertEquals("hello there", hits.single().message.text)
        }

    @Test
    fun bufferScopedSearch_resolvesLosingRoomRedirect() =
        runTest {
            BufferStore(db).mergeRooms(b1, b2)

            val hits = repo.search("there", b2).first().hits

            assertEquals(1, hits.size)
            assertEquals("hello there", hits.single().message.text)
            assertEquals(b1, hits.single().message.bufferId)
        }

    @Test
    fun operatorOnlyInput_returnsEmptyWithoutTouchingDb() =
        runTest {
            assertEquals(
                0,
                repo
                    .search("***", null)
                    .first()
                    .hits.size,
            )
        }

    @Test
    fun structuredFtsFiltersAreAppliedBeforeTheCap() =
        runTest {
            insertMatches(b1, "target", count = SearchRepositoryImpl.LOCAL_SEARCH_LIMIT + 1, sender = "not-alice")
            db.messageDao().insertAll(
                listOf(
                    message(b1, "target older bob", sender = "Bob", serverTime = 4, dedupKey = "bob"),
                    message(b1, "target older alice", sender = "Alice", serverTime = 5, dedupKey = "alice"),
                    message(b2, "target wrong room", sender = "Alice", serverTime = 6, dedupKey = "wrong-room"),
                    message(b1, "wrong text", sender = "Alice", serverTime = 7, dedupKey = "wrong-text"),
                ),
            )
            val senders = List(40_000) { if (it % 2 == 0) "ALICE" else "bOb" }
            val result = repo.search("target", null, LocalSearchFilters(room = "#ONE", network = "LIBERA", senders = senders)).first()

            assertEquals(listOf("target older alice", "target older bob"), result.hits.map { it.message.text })
            assertTrue(!result.truncated)
            val operatorOnly = repo.search("***", null, LocalSearchFilters(room = "#one")).first()
            assertTrue(operatorOnly.hits.isEmpty())
        }

    @Test
    fun filterOnlyUsesPersistedIrcCasemapsButUnicodeNetworkLabels() =
        runTest {
            val cases = listOf(null, "RFC1459-STRICT", "ascii", "unknown")
            for ((index, mapping) in cases.withIndex()) {
                val networkId = db.networkDao().insert(network(if (index % 2 == 0) "Équipe Chat" else "éQUIPE CHAT"))
                if (mapping != null) db.networkIdentityDao().upsert(NetworkIdentityEntity(networkId, caseMapping = mapping))
                val roomId = db.bufferDao().insert(buffer(networkId, "#[ÉCHO]"))
                db.messageDao().insertAll(
                    listOf(
                        message(roomId, "brackets-$index", sender = "[Nick]", serverTime = 10, dedupKey = "brackets"),
                        message(roomId, "tilde-$index", sender = "Nick~", serverTime = 10, dedupKey = "tilde"),
                        message(roomId, "unicode-$index", sender = "ÉNICK", serverTime = 10, dedupKey = "unicode"),
                    ),
                )
            }
            val network = LocalSearchFilters(network = "équipe chat")

            suspend fun texts(filters: LocalSearchFilters): List<String> {
                val result = repo.search("", null, filters).first()
                return result.hits.map { it.message.text }
            }

            assertEquals(12, texts(network).size)
            assertTrue(texts(network.copy(network = "équipe")).isEmpty())
            assertEquals(
                listOf("brackets-1", "brackets-0"),
                texts(network.copy(room = "#{Écho}", senders = listOf("{NICK}"))),
            )
            assertEquals(listOf("tilde-0"), texts(network.copy(room = "#[Écho]", senders = listOf("NICK^"))))
            assertEquals(listOf("brackets-3", "brackets-2", "brackets-1", "brackets-0"), texts(network.copy(senders = listOf("[NICK]"))))
            assertEquals(
                listOf("unicode-3", "unicode-2", "unicode-1", "unicode-0"),
                texts(network.copy(room = "#[Écho]", senders = listOf("Énick"))),
            )
            assertTrue(texts(network.copy(room = "#[écho]")).isEmpty())
            assertTrue(texts(network.copy(senders = listOf("énick"))).isEmpty())
        }

    @Test
    fun uppercaseStrictCasemapAndAsciiIdentifiersMatchInTurkishLocale() =
        runTest {
            val previousLocale = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"))
                val networkId = db.networkDao().insert(network("Turkish locale"))
                db.networkIdentityDao().upsert(NetworkIdentityEntity(networkId, caseMapping = "RFC1459-STRICT"))
                val roomId = db.bufferDao().insert(buffer(networkId, "#[INBOX]"))
                db.messageDao().insertAll(
                    listOf(message(roomId, "message", sender = "[NICK]", serverTime = 10, dedupKey = "turkish")),
                )
                val result = repo.search("", null, LocalSearchFilters(room = "#{INBOX}", senders = listOf("{NICK}"))).first()
                assertEquals(listOf(roomId), result.hits.map { it.message.bufferId })
            } finally {
                Locale.setDefault(previousLocale)
            }
        }

    @Test
    fun redirectedScopeRemainsCanonicalWhenAFilterConflicts() =
        runTest {
            val networkId = db.networkDao().insert(network("Other Network"))
            val other = db.bufferDao().insert(buffer(networkId, "#other"))
            db.messageDao().insertAll(listOf(message(other, "hello other", serverTime = 9, dedupKey = "other")))
            db.roomAliasDao().markRedirect(b2, b1)

            val byCanonicalName = repo.search("hello", b2, LocalSearchFilters(room = "#one")).first()
            val byRedirectedName = repo.search("", b2, LocalSearchFilters(room = "#two")).first()
            val wrongRoom = repo.search("hello", b2, LocalSearchFilters(room = "#other")).first()
            val wrongNetwork = repo.search("", b2, LocalSearchFilters(network = "other network")).first()

            assertEquals(listOf("hello world"), byCanonicalName.hits.map { it.message.text })
            assertEquals(listOf("hello world"), byRedirectedName.hits.map { it.message.text })
            assertTrue(wrongRoom.hits.isEmpty())
            assertTrue(wrongNetwork.hits.isEmpty())
        }

    @Test
    fun roomQualifierUsesActiveVerifiedNickInsteadOfAnOldAccountWithTheSameNick() =
        runTest {
            val networkId = checkNotNull(db.bufferDao().rawById(b1)).networkId
            val store = BufferStore(db)
            val provisional = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
            val old = store.bindQueryIdentity(provisional.id, networkId, "alice", "Alice", "acct-a")
            val active = store.bindQueryIdentity(old.id, networkId, "alice", "Alice", "acct-b")
            db.messageDao().insertAll(
                listOf(
                    message(old.id, "hello old account", serverTime = 11, dedupKey = "old-account"),
                    message(active.id, "hello active account", serverTime = 10, dedupKey = "active-account"),
                ),
            )
            val filters = LocalSearchFilters(room = "ALICE")
            val global = repo.search("hello", null, filters).first()
            val activeScope = repo.search("", active.id, filters).first()
            val oldScope = repo.search("", old.id, filters).first()
            assertEquals(listOf(active.id), global.hits.map { it.message.bufferId })
            assertEquals(listOf(active.id), activeScope.hits.map { it.message.bufferId })
            assertTrue(oldScope.hits.isEmpty())
        }

    @Test
    fun roomQualifierPrefersExactStoredAliasesAfterCasemapChange() =
        runTest {
            val networkId = checkNotNull(db.bufferDao().rawById(b1)).networkId
            val identities = db.networkIdentityDao()
            identities.upsert(NetworkIdentityEntity(networkId, caseMapping = "ascii"))
            val store = BufferStore(db)
            val provisional = store.getOrCreate(networkId, "[alice]", "[Alice]", BufferType.QUERY)
            val oldNick = store.bindQueryIdentity(provisional.id, networkId, "[alice]", "[Alice]", "acct")
            val activeNick = store.getOrCreate(networkId, "{alice}", "{Alice}", BufferType.QUERY)
            val oldChannel = store.getOrCreate(networkId, "#[room]", "#[Room]", BufferType.CHANNEL)
            val activeChannel = store.getOrCreate(networkId, "#{room}", "#{Room}", BufferType.CHANNEL)
            db.messageDao().insertAll(
                listOf(oldNick, activeNick, oldChannel, activeChannel).map { room ->
                    message(room.id, "message", serverTime = 10, dedupKey = room.name)
                },
            )

            suspend fun roomFor(qualifier: String): Long {
                val result = repo.search("", null, LocalSearchFilters(room = qualifier)).first()
                val hit = result.hits.single()
                return hit.message.bufferId
            }

            assertEquals(oldNick.id, roomFor("[ALICE]"))
            assertEquals(oldChannel.id, roomFor("#[ROOM]"))
            identities.upsert(NetworkIdentityEntity(networkId, caseMapping = "rfc1459"))
            assertEquals(activeNick.id, roomFor("[ALICE]"))
            assertEquals(activeChannel.id, roomFor("#[ROOM]"))
        }

    @Test
    fun timestampTiesUseTimelineOrderOnlyForAnExplicitBufferScope() =
        runTest {
            db.messageDao().insertAll(
                listOf(
                    message(b1, "tie earliest id", serverTime = 9, dedupKey = "tie-1").copy(timelineOrder = 10),
                    message(b1, "tie lower order", serverTime = 9, dedupKey = "tie-2").copy(timelineOrder = 9),
                    message(b1, "tie latest id", serverTime = 9, dedupKey = "tie-3").copy(timelineOrder = 10),
                    message(b2, "tie other room", serverTime = 9, dedupKey = "tie-4").copy(timelineOrder = 1),
                ),
            )
            val expected = listOf("tie latest id", "tie earliest id", "tie lower order")
            val scopedFts = repo.search("tie", b1).first()
            val scopedFilterOnly = repo.search("", b1, LocalSearchFilters(senders = listOf("alice"))).first()
            assertEquals(expected, scopedFts.hits.map { it.message.text })
            assertEquals(expected + "hello world", scopedFilterOnly.hits.map { it.message.text })
            val globalOrder = listOf("tie other room", "tie latest id", "tie lower order", "tie earliest id")
            val globalFts = repo.search("tie", null).first()
            val globalFilteredFts = repo.search("tie", null, LocalSearchFilters(senders = listOf("alice"))).first()
            assertEquals(globalOrder, globalFts.hits.map { it.message.text })
            assertEquals(globalOrder, globalFilteredFts.hits.map { it.message.text })
        }

    @Test
    fun filterOnlyAliasSearchObservesMessagesNamesAndCasemap() =
        runTest {
            val networkId = db.networkDao().insert(network("Équipe Chat"))
            val roomId = db.bufferDao().insert(buffer(networkId, "original", BufferType.QUERY))
            val filters = LocalSearchFilters(room = "{CURRENT}", network = "équipe chat")
            val updates = Channel<List<String>>(Channel.UNLIMITED)
            backgroundScope.launch {
                repo
                    .search("", null, filters)
                    .map { result -> result.hits.map { it.message.text } }
                    .distinctUntilChanged()
                    .collect { updates.send(it) }
            }
            assertTrue(updates.receive().isEmpty())

            val aliases = db.roomAliasDao()
            aliases.insertIgnore(RoomAliasEntity(networkId = networkId, namespace = RoomAliasNamespace.ACCOUNT, value = "[Current]", roomId = roomId))
            val messageId = db.messageDao().insertAll(listOf(message(roomId, "message", serverTime = 10, dedupKey = "message"))).single()
            val accountOnly = repo.search("", null, filters).first()
            assertTrue(accountOnly.hits.isEmpty())
            aliases.insertIgnore(
                RoomAliasEntity(networkId = networkId, namespace = RoomAliasNamespace.VERIFIED_NICK, value = "[Current]", roomId = roomId, verified = true),
            )
            assertEquals(listOf("message"), updates.receive())
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkId, caseMapping = "ascii"))
            assertTrue(updates.receive().isEmpty())
            val room = checkNotNull(db.bufferDao().rawById(roomId))
            db.bufferDao().update(room.copy(displayName = "{Current}"))
            assertEquals(listOf("message"), updates.receive())
            val network = checkNotNull(db.networkDao().byId(networkId))
            db.networkDao().update(network.copy(name = "Other Chat"))
            assertTrue(updates.receive().isEmpty())
            db.networkDao().update(network)
            assertEquals(listOf("message"), updates.receive())
            db.messageDao().deleteById(messageId)
            assertTrue(updates.receive().isEmpty())
        }

    @Test
    fun caseOnlyNetworkRenameKeepsTheMatchingPage() =
        runTest {
            val networkId = db.networkDao().insert(network("Équipe Chat"))
            val roomId = db.bufferDao().insert(buffer(networkId, "#hello"))
            db.messageDao().insertAll(listOf(message(roomId, "message", serverTime = 10, dedupKey = "message")))
            val updates = Channel<List<String>>(Channel.UNLIMITED)
            backgroundScope.launch {
                repo
                    .search("", null, LocalSearchFilters(network = "équipe chat"))
                    .map { result -> result.hits.map { it.networkName } }
                    .distinctUntilChanged()
                    .collect { updates.send(it) }
            }
            assertEquals(listOf("Équipe Chat"), updates.receive())

            val network = checkNotNull(db.networkDao().byId(networkId))
            db.networkDao().update(network.copy(name = "éQUIPE CHAT"))
            assertEquals(listOf("éQUIPE CHAT"), updates.receive())
        }

    @Test
    fun foolResultsRemainInCollapseAndDisappearInHide() =
        runTest {
            db.messageDao().insertAll(
                listOf(message(b1, "hello fool", sender = "alice", serverTime = 4, dedupKey = "fool")),
            )
            settings.state.value = Settings(fools = setOf("alice"), foolsMode = FoolsMode.COLLAPSE)
            assertTrue(
                repo
                    .search("fool", null)
                    .first()
                    .hits
                    .isNotEmpty(),
            )

            settings.state.value = settings.state.value.copy(foolsMode = FoolsMode.HIDE)
            assertTrue(
                repo
                    .search("fool", null)
                    .first()
                    .hits
                    .isEmpty(),
            )
        }

    @Test
    fun mixedDimmedCollapsedAndHiddenSearchUsesIndividualVisibilityAndCurrentPreferences() =
        runTest {
            db.messageDao().insertAll(
                listOf("a", "b", "c").mapIndexed { index, sender ->
                    message(b1, "mixedfool $sender", sender = sender, serverTime = 100L + index, dedupKey = sender)
                },
            )
            settings.state.value =
                Settings(
                    fools = setOf("a", "b", "c"),
                    foolPreferences =
                        mapOf(
                            "b" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true),
                            "c" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                        ),
                )
            assertEquals(
                listOf("b", "a"),
                repo
                    .search("mixedfool", null)
                    .first()
                    .hits
                    .map { it.message.sender },
            )
            settings.setFoolPreferences("b", FoolPreferences(FoolsMode.HIDE), null)
            assertEquals(
                listOf("a"),
                repo
                    .search("mixedfool", null)
                    .first()
                    .hits
                    .map { it.message.sender },
            )
        }

    @Test
    fun foolFilteringUsesEachHitsPersistedAsciiOrStrictRules() =
        runTest {
            val asciiNetwork = db.networkDao().insert(network("ascii"))
            val strictNetwork = db.networkDao().insert(network("strict"))
            db.networkIdentityDao().upsert(
                NetworkIdentityEntity(asciiNetwork, caseMapping = "ascii"),
            )
            db.networkIdentityDao().upsert(
                NetworkIdentityEntity(strictNetwork, caseMapping = "rfc1459-strict"),
            )
            val asciiBuffer = db.bufferDao().insert(buffer(asciiNetwork, "#ascii"))
            val strictBuffer = db.bufferDao().insert(buffer(strictNetwork, "#strict"))
            db.messageDao().insertAll(
                listOf(
                    message(
                        asciiBuffer,
                        "network-policy-hit",
                        sender = "[Alice",
                        serverTime = 10,
                        dedupKey = "ascii-policy",
                    ).copy(normalizedActor = "[alice"),
                    message(
                        strictBuffer,
                        "network-policy-hit",
                        sender = "listener^",
                        serverTime = 11,
                        dedupKey = "strict-policy",
                    ).copy(normalizedActor = "listener^"),
                ),
            )
            settings.state.value =
                Settings(
                    fools = setOf("[alice", "listener~"),
                    foolsMode = FoolsMode.HIDE,
                )

            val hits = repo.search("network", null).first().hits

            assertEquals(listOf("listener^"), hits.map { it.message.sender })
            assertEquals("rfc1459-strict", hits.single().caseMapping)
        }

    @Test
    fun searchFlagsTruncationAtTheRawDaoCap() =
        runTest {
            insertMatches(b1, "capped", count = SearchRepositoryImpl.LOCAL_SEARCH_LIMIT + 1)
            insertMatches(b1, "brimmed", count = SearchRepositoryImpl.LOCAL_SEARCH_LIMIT - 1)

            val capped = repo.search("capped", b1).first()
            assertTrue("a full page must disclose truncation", capped.truncated)
            assertEquals(SearchRepositoryImpl.LOCAL_SEARCH_LIMIT, capped.hits.size)

            val underCap = repo.search("brimmed", b1).first()
            assertEquals(SearchRepositoryImpl.LOCAL_SEARCH_LIMIT - 1, underCap.hits.size)
            assertTrue("an unfilled page is not truncated", !underCap.truncated)
        }

    @Test
    fun truncationIsMeasuredBeforeVisibilityFiltering() =
        runTest {
            insertMatches(b1, "capped", count = SearchRepositoryImpl.LOCAL_SEARCH_LIMIT, sender = "fool")
            settings.state.value = Settings(fools = setOf("fool"), foolsMode = FoolsMode.HIDE)

            val result = repo.search("capped", b1).first()

            assertTrue("every hit is hidden", result.hits.isEmpty())
            assertTrue("the SQL page still hit the cap", result.truncated)
        }

    @Test
    fun coverageIsDeviceOnlyForAllBuffers() =
        runTest {
            assertEquals(SearchCoverage.DeviceOnly, repo.coverage(null).first())
        }

    @Test
    fun coverageIsCompleteForGaplessCompleteBuffer() =
        runTest {
            db.bufferDao().markHistoryComplete(b1)

            assertEquals(SearchCoverage.BufferComplete, repo.coverage(b1).first())
        }

    @Test
    fun coverageReportsOpenGaps() =
        runTest {
            db.bufferDao().markHistoryComplete(b1)
            db.historyGapDao().insert(
                HistoryGapEntity(roomId = b1, olderMsgid = "o", olderServerTime = 1, newerMsgid = "n", newerServerTime = 2),
            )

            assertEquals(SearchCoverage.BufferPartial(openGaps = 1, historyComplete = true), repo.coverage(b1).first())
            // A buffer that never reached its oldest edge is partial even without a recorded gap.
            assertEquals(SearchCoverage.BufferPartial(openGaps = 0, historyComplete = false), repo.coverage(b2).first())
        }

    @Test
    fun coverageFollowsBufferRedirect() =
        runTest {
            db.historyGapDao().insert(
                HistoryGapEntity(roomId = b1, olderMsgid = "o", olderServerTime = 1, newerMsgid = "n", newerServerTime = 2),
            )
            BufferStore(db).mergeRooms(b1, b2)

            // Asking with the losing id must describe the winning room's corpus, like search() does.
            val viaLoser = repo.coverage(b2).first()
            assertEquals(repo.coverage(b1).first(), viaLoser)
            assertEquals(
                db.historyGapDao().forRoom(b1).size,
                (viaLoser as SearchCoverage.BufferPartial).openGaps,
            )
        }

    @Test
    fun coverageNeverOverclaimsForAnUnknownBuffer() =
        runTest {
            assertEquals(SearchCoverage.BufferPartial(openGaps = 0, historyComplete = false), repo.coverage(9_999L).first())
        }

    private suspend fun insertMatches(
        bufferId: Long,
        token: String,
        count: Int,
        sender: String = "alice",
    ) {
        db.messageDao().insertAll(
            (1..count).map { i ->
                message(bufferId, "$token $i", sender = sender, serverTime = 1_000L + i, dedupKey = "$token-$i")
            },
        )
    }

    private class FakeSettingsRepository : SettingsRepository {
        val state = MutableStateFlow(Settings())
        override val settings = state

        override suspend fun setThemeMode(m: ThemeMode) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

        override suspend fun setNotificationPreview(preview: io.github.trevarj.motd.service.NotificationPreview) = Unit

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

        override suspend fun setCompactNickBrackets(value: io.github.trevarj.motd.data.prefs.CompactNickBrackets) {
            state.value = state.value.copy(compactNickBrackets = value)
        }

        override suspend fun setShowChannelPrefixes(enabled: Boolean) {
            state.value = state.value.copy(showChannelPrefixes = enabled)
        }

        override suspend fun setNickMentionStyle(value: io.github.trevarj.motd.data.prefs.NickMentionStyle) {
            state.value = state.value.copy(nickMentionStyle = value)
        }

        override suspend fun setTypingIndicatorPlacement(placement: io.github.trevarj.motd.data.prefs.TypingIndicatorPlacement) {
            state.value = state.value.copy(typingIndicatorPlacement = placement)
        }

        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(
            nick: String,
            hue: Int?,
        ) = Unit

        override suspend fun setFriend(
            nick: String,
            isFriend: Boolean,
        ) = Unit

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
        ) = Unit

        override suspend fun setFoolPreferences(
            nick: String,
            preferences: io.github.trevarj.motd.data.prefs.FoolPreferences,
            identityRules: io.github.trevarj.motd.irc.proto.IrcIdentityRules?,
        ) {
            val current = state.value
            val keys =
                current.fools.filter {
                    identityRules?.normalize(it) == identityRules?.normalize(nick.trim()) && (
                        identityRules != null || it ==
                            io.github.trevarj.motd.data.prefs
                                .normalizeNick(nick)
                    )
                }
            state.value =
                current.copy(
                    foolPreferences =
                        current.foolPreferences.toMutableMap().apply {
                            keys.forEach {
                                if (preferences ==
                                    io.github.trevarj.motd.data.prefs
                                        .FoolPreferences()
                                ) {
                                    remove(it)
                                } else {
                                    put(it, preferences)
                                }
                            }
                        },
                )
        }

        override suspend fun setFoolsMode(m: FoolsMode) = Unit

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

        override suspend fun setShowVoiceMessageButton(show: Boolean) = Unit

        override suspend fun setNicknameTapAction(action: io.github.trevarj.motd.data.prefs.NicknameTapAction) = Unit

        override suspend fun setProfileDisplayNameFirst(enabled: Boolean) {
            state.value = state.value.copy(profileDisplayNameFirst = enabled)
        }

        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

        override suspend fun setHistorySyncDepth(d: io.github.trevarj.motd.data.prefs.HistorySyncDepth) = Unit

        override suspend fun setHistorySyncMode(mode: HistorySyncMode) {
            state.value = state.value.copy(historySyncMode = mode)
        }

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }
}
