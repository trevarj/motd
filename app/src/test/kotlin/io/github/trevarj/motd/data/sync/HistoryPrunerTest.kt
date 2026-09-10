package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.HistoryRetention
import io.github.trevarj.motd.data.prefs.HistorySyncDepth
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.prefs.channelRetentionRows
import io.github.trevarj.motd.data.prefs.queryRetentionRows
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Retention keeps a room at its newest N rows and leaves it fetchable: the completion flags reopen,
 * the protocol cursor is forgotten, and gaps below the floor go with the rows.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryPrunerTest {
    private lateinit var db: MotdDatabase
    private lateinit var pruner: HistoryPrunerImpl
    private var networkId: Long = 0

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            networkId = db.networkDao().insert(network())
            db.historyCursorDao().setNetworkLastSuccessfulSync(networkId, 1_000_000)
            pruner = HistoryPrunerImpl(db, StubSettings, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        }

    @After
    fun tearDown() = db.close()

    private suspend fun room(
        name: String,
        type: BufferType = BufferType.CHANNEL,
        rows: Int,
        spacingMs: Long = 1_000,
    ): Long {
        val id = db.bufferDao().insert(buffer(networkId, name, type = type))
        db.messageDao().insertAll((1..rows).map { message(id, "$name-$it", serverTime = it * spacingMs, dedupKey = "$name-$it") })
        return id
    }

    private suspend fun texts(roomId: Long) = db.messageDao().historyRowsForMerge(roomId).map { it.text }

    @Test
    fun rowCapKeepsTheNewestRowsAndReopensHistory() =
        runTest {
            val id = room("#chan", rows = 12)
            db.bufferDao().markHistoryComplete(id)
            db.historyCursorDao().upsert(HistoryCursorEntity(roomId = id, oldestMsgid = "old", oldestServerTime = 1, historyComplete = true))
            db.historyGapDao().insert(HistoryGapEntity(roomId = id, olderMsgid = null, olderServerTime = 2_000, newerMsgid = null, newerServerTime = 3_000))
            val gapAbove = db.historyGapDao().insert(HistoryGapEntity(roomId = id, olderMsgid = null, olderServerTime = 10_000, newerMsgid = null, newerServerTime = 11_000))

            val deleted = pruner.prune(null, channelRows = 5, queryRows = null)

            assertEquals(7, deleted)
            assertEquals((8..12).map { "#chan-$it" }, texts(id))
            val buffer = db.bufferDao().rawById(id)!!
            assertFalse(buffer.historyComplete)
            assertEquals(8_000L, buffer.oldestFetchedTime)
            val cursor = db.historyCursorDao().byRoom(id)!!
            assertFalse(cursor.historyComplete)
            assertNull(cursor.oldestServerTime)
            assertEquals(listOf(gapAbove), db.historyGapDao().forRoom(id).map { it.id })
        }

    @Test
    fun profileMeasuresLiveBytesAndPrunableRooms() =
        runTest {
            room("#chan", rows = 30)
            room("alice", type = BufferType.QUERY, rows = 5)
            val direct = db.networkDao().insert(network("direct"))
            val unsynced = db.bufferDao().insert(buffer(direct, "#local"))
            db.messageDao().insertAll((1..7).map { message(unsynced, "local-$it", serverTime = it * 1_000L, dedupKey = "local-$it") })

            val profile = pruner.profile()

            assertEquals(42L, profile.totalRows)
            assertEquals(listOf(BufferType.CHANNEL to 30, BufferType.QUERY to 5), profile.rooms.map { it.type to it.rowCount })
            assertTrue(profile.liveBytes > 0)
            assertTrue(profile.bytesPerRow > 0)
        }

    @Test
    fun aPruneThatFreesEnoughRewritesTheFileAutomatically() =
        runTest {
            fun autoVacuum(): Long =
                db.openHelper.readableDatabase
                    .query("PRAGMA auto_vacuum")
                    .use { cursor ->
                        cursor.moveToFirst()
                        cursor.getLong(0)
                    }

            room("#big", rows = 3_000)
            assertEquals(0L, autoVacuum())

            // Far above anything a 3k-row prune frees: only the incremental no-op runs.
            pruner.prune(null, channelRows = 2_000, queryRows = null, autoCompactBytes = Long.MAX_VALUE / 2)
            assertEquals(0L, autoVacuum())

            // One byte: any freed page qualifies, and the VACUUM leaves the file on incremental auto-vacuum.
            pruner.prune(null, channelRows = 50, queryRows = null, autoCompactBytes = 1L)
            assertEquals(2L, autoVacuum())
            assertEquals(50, texts(db.bufferDao().byName(networkId, "#big")!!.id).size)
        }

    @Test
    fun defaultSettingsNeverPrune() {
        // Matches the behaviour before retention existed: nothing is deleted until the user opts in.
        assertNull(Settings().channelRetentionRows)
        assertNull(Settings().queryRetentionRows)
    }

    @Test
    fun roomAtOrUnderTheCapIsUntouched() =
        runTest {
            val exact = room("#exact", rows = 5)
            val under = room("#under", rows = 3)

            assertEquals(0, pruner.prune(null, channelRows = 5, queryRows = null))

            assertEquals(5, texts(exact).size)
            assertEquals(3, texts(under).size)
        }

    @Test
    fun queriesUseTheirOwnTierAndProtectedRowsSurvive() =
        runTest {
            val channel = room("#chan", rows = 10)
            val query = room("alice", type = BufferType.QUERY, rows = 10)
            db.messageDao().insertAll(listOf(message(channel, "unsent", serverTime = 1, dedupKey = "unsent", pendingLabel = "lbl")))

            pruner.prune(null, channelRows = 2, queryRows = 8)

            assertEquals(listOf("unsent", "#chan-9", "#chan-10"), texts(channel))
            assertEquals(8, texts(query).size)
        }

    @Test
    fun roomsWithoutServerHistoryAndOffRetentionAreLeftAlone() =
        runTest {
            val synced = room("#chan", rows = 10)
            val direct = db.networkDao().insert(network("direct"))
            val unsynced = db.bufferDao().insert(buffer(direct, "#local"))
            db.messageDao().insertAll((1..10).map { message(unsynced, "local-$it", serverTime = it * 1_000L, dedupKey = "local-$it") })

            assertEquals(0, pruner.prune(null, HistoryRetention.OFF.channelRows, HistoryRetention.OFF.queryRows))
            pruner.prune(null, channelRows = 2, queryRows = null)

            assertEquals(2, texts(synced).size)
            assertEquals(10, texts(unsynced).size)
        }

    private object StubSettings : SettingsRepository {
        override val settings: Flow<Settings> = flowOf(Settings())

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

        override suspend fun setHistorySyncDepth(d: HistorySyncDepth) = Unit

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }
}
