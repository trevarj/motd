package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
            repo = SearchRepositoryImpl(db.bufferDao(), db.messageDao(), db.historyGapDao(), settings)
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

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

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

        override suspend fun setFoolsMode(m: FoolsMode) = Unit

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

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
