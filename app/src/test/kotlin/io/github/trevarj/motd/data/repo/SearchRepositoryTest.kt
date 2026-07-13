package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
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
    fun setUp() = runTest {
        db = inMemoryDb()
        settings = FakeSettingsRepository()
        repo = SearchRepositoryImpl(db.messageDao(), settings)
        val nid = db.networkDao().insert(network())
        b1 = db.bufferDao().insert(buffer(nid, "#one"))
        b2 = db.bufferDao().insert(buffer(nid, "#two"))
        db.messageDao().insertAll(
            listOf(
                message(b1, "hello world", serverTime = 1, dedupKey = "a"),
                message(b2, "hello there", serverTime = 2, dedupKey = "b"),
                message(b1, "hello join", serverTime = 3, dedupKey = "c", kind = MessageKind.JOIN),
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun rawPrefixInput_matchesAcrossBuffers_excludesSystemKinds() = runTest {
        val hits = repo.search("hel", null).first()
        // JOIN row excluded → only the two chat messages match.
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.message.kind == MessageKind.PRIVMSG })
    }

    @Test
    fun bufferScopedSearch_restrictsResults() = runTest {
        val hits = repo.search("hello", b2).first()
        assertEquals(1, hits.size)
        assertEquals("hello there", hits.single().message.text)
    }

    @Test
    fun operatorOnlyInput_returnsEmptyWithoutTouchingDb() = runTest {
        assertEquals(0, repo.search("***", null).first().size)
    }

    @Test
    fun foolResultsRemainInCollapseAndDisappearInHide() = runTest {
        db.messageDao().insertAll(
            listOf(message(b1, "hello fool", sender = "alice", serverTime = 4, dedupKey = "fool")),
        )
        settings.state.value = Settings(fools = setOf("alice"), foolsMode = FoolsMode.COLLAPSE)
        assertTrue(repo.search("fool", null).first().isNotEmpty())

        settings.state.value = settings.state.value.copy(foolsMode = FoolsMode.HIDE)
        assertTrue(repo.search("fool", null).first().isEmpty())
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
        override suspend fun setNickColorOverride(nick: String, hue: Int?) = Unit
        override suspend fun setFriend(nick: String, isFriend: Boolean) = Unit
        override suspend fun setFool(nick: String, isFool: Boolean) = Unit
        override suspend fun setFoolsMode(m: FoolsMode) = Unit
        override suspend fun setShowJoinPartQuit(show: Boolean) = Unit
        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit
        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit
        override suspend fun setShowComposerEmoji(show: Boolean) = Unit
    }
}
