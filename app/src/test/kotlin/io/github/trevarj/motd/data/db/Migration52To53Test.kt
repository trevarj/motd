package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.WallpaperChatChoice
import io.github.trevarj.motd.data.prefs.WallpaperChatChoiceKind
import io.github.trevarj.motd.data.prefs.WallpaperLibrary
import io.github.trevarj.motd.data.prefs.WallpaperPatternRef
import io.github.trevarj.motd.data.prefs.WallpaperPatternRefKind
import io.github.trevarj.motd.data.prefs.decodeWallpaperChatChoice
import io.github.trevarj.motd.data.repo.PortableChatWallpaper
import io.github.trevarj.motd.data.sync.BufferStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration52To53Test {
    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun legacyRoomsInheritAndRedirectWritesReachCanonicalRoom() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(52) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 52)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                ).use { helper ->
                    helper.writableDatabase.execSQL(
                        """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname, saslMechanism, autoConnect, ordering, restoreAutoConnect)
                        VALUES (1, 'test', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 0, 0, 0)""",
                    )
                    helper.writableDatabase.execSQL(
                        """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted, archived, ordering, historyComplete, dismissed)
                        VALUES (1, 1, 'alice', 'Alice', 'QUERY', 0, 0, 1, 0, 0, 0, 0, 0),
                               (2, 1, 'oldnick', 'OldNick', 'QUERY', 0, 0, 0, 0, 0, 0, 0, 0)""",
                    )
                }
            val db = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS).build()
            try {
                val buffers = db.bufferDao()
                val store = BufferStore(db)
                val library = WallpaperLibrary()
                val none = WallpaperChatChoice(WallpaperChatChoiceKind.NONE)
                val ref = WallpaperPatternRef(WallpaperPatternRefKind.STARTER, "motd")
                val pattern = WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, ref)
                assertNull(buffers.observeById(1)?.wallpaperOverrideEncoded)
                assertNull(buffers.observeById(2)?.wallpaperOverrideEncoded)
                assertEquals(true, buffers.observeById(1)?.pinned)
                assertTrue(store.setWallpaperOverride(1, none))
                assertTrue(store.setWallpaperOverride(2, pattern))
                assertEquals(none, decodeWallpaperChatChoice(store.mergeRooms(1, 2).wallpaperOverrideEncoded))
                assertEquals(1L, store.observeWallpaperTarget(2).first()?.id)
                assertTrue(store.setWallpaperOverride(2, pattern))
                assertEquals(pattern, decodeWallpaperChatChoice(buffers.observeById(1)?.wallpaperOverrideEncoded))

                val deferred = PortableChatWallpaper(1, BufferType.QUERY, FolderIdentityKind.ACCOUNT, "alice-account", none)
                store.restoreChatWallpaperAssignments(listOf(deferred), replace = false, library = library)
                assertEquals(listOf(FolderIdentityKind.NICK, FolderIdentityKind.ACCOUNT), store.chatWallpaperSnapshot(library).map { it.identityKind })
                assertTrue(store.setWallpaperOverride(2, null))
                store.bindQueryIdentity(1, 1, "alice", "Alice", "alice-account")
                assertNull(buffers.observeById(1)?.wallpaperOverrideEncoded)
                assertTrue(store.chatWallpaperSnapshot(library).isEmpty())
                assertEquals(true, buffers.observeById(1)?.pinned)

                // A verified account beats the nick assignment when both match the same room.
                val byNick = deferred.copy(identityKind = FolderIdentityKind.NICK, identityValue = "oldnick", choice = pattern)
                store.restoreChatWallpaperAssignments(listOf(byNick, deferred), replace = false, library = library)
                assertEquals(none, decodeWallpaperChatChoice(buffers.observeById(2)?.wallpaperOverrideEncoded))
                assertEquals(listOf(deferred), store.chatWallpaperSnapshot(library))

                val channel = store.getOrCreate(1, "#gone", "#gone", BufferType.CHANNEL)
                assertTrue(store.setWallpaperOverride(channel.id, pattern))
                val pendingPattern = deferred.copy(identityValue = "missing-pattern-account", choice = pattern)
                store.restoreChatWallpaperAssignments(listOf(pendingPattern), replace = false, library = library)
                assertEquals(2, store.observeWallpaperUseCount(ref).first())
                store.clearWallpaperReferences(ref)
                assertEquals(0, store.observeWallpaperUseCount(ref).first())
                assertNull(buffers.observeById(channel.id)?.wallpaperOverrideEncoded)
                assertEquals(none, decodeWallpaperChatChoice(buffers.observeById(1)?.wallpaperOverrideEncoded))
                assertTrue(store.setWallpaperOverride(channel.id, pattern))
                assertEquals(1, store.observeWallpaperUseCount(ref).first())
                buffers.deleteBuffer(channel.id)
                assertFalse(store.checkWallpaperAssignment(channel.id, explicit = true))
                assertFalse(store.setWallpaperOverride(channel.id, none))
                assertNull(store.observeWallpaperTarget(channel.id).first())
                val server = store.getOrCreate(1, "server", "server", BufferType.SERVER)
                assertEquals(0, buffers.setWallpaperOverride(server.id, "unused"))
                assertFalse(store.setWallpaperOverride(server.id, none))

                store.restoreChatWallpaperAssignments(listOf(deferred.copy(identityValue = "missing-account")), replace = false, library = library)
                assertEquals(1, db.appStateDao().keysGlob("$PENDING_CHAT_WALLPAPER_PREFIX*").size)
                db.networkDao().deleteLocalTree(1)
                assertTrue(db.appStateDao().keysGlob("$PENDING_CHAT_WALLPAPER_PREFIX*").isEmpty())
                assertTrue(db.appStateDao().keysGlob("$CHAT_WALLPAPER_EDIT_PREFIX:1:*").isEmpty())
                assertFalse(store.setWallpaperOverride(2, none))
                assertTrue(store.chatWallpaperSnapshot(library).isEmpty())
            } finally {
                db.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-52-53-test.db"
    }
}
