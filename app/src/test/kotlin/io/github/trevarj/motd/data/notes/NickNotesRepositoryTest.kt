package io.github.trevarj.motd.data.notes

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.ALL_MIGRATIONS
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.createExportedVersion
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NickNotesRepositoryTest {
    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun `migrated notes are isolated by network and identity and cascade on network removal`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            createLegacyDatabase(context)
            val builder = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS)
            val account = NickNoteKey(networkId = 1, kind = NickNoteKind.ACCOUNT, value = "alice")
            val nick = account.copy(kind = NickNoteKind.NICKNAME)
            val migrated = builder.build()
            val otherNetwork =
                try {
                    val repository = NickNotesRepository(migrated)
                    assertEquals("existing", migrated.networkDao().byId(account.networkId)?.name)
                    assertNull(repository.read(account))
                    val other = account.copy(networkId = migrated.networkDao().insert(network("other")))
                    repository.save(account, "account")
                    assertNull(repository.read(nick))
                    repository.save(nick, "nickname")
                    repository.save(other, "other network")
                    other
                } finally {
                    migrated.close()
                }

            val reopened = builder.build()
            try {
                val repository = NickNotesRepository(reopened)
                assertEquals("account", repository.read(account))
                assertNull(repository.read(account.copy(value = "Alice")))
                assertEquals("nickname", repository.read(nick))
                assertEquals("other network", repository.read(otherNetwork))
                assertEquals(listOf(account.networkId), reopened.networkDao().deleteLocalTree(account.networkId))
                assertNull(repository.read(account))
                assertNull(repository.read(nick))
                assertEquals("other network", repository.read(otherNetwork))
            } finally {
                reopened.close()
            }
        }

    @Test
    fun `saving updates exactly and blank deletes without truncating oversized edits`() =
        runTest {
            val db = inMemoryDb()
            try {
                val repository = NickNotesRepository(db)
                val key = NickNoteKey(db.networkDao().insert(network()), NickNoteKind.ACCOUNT, "Alice")
                val updated = " ${"x".repeat(MAX_NICK_NOTE_LENGTH - 2)}\n"
                repository.save(key, "original")
                repository.save(key, updated)
                assertEquals(updated, repository.read(key))
                val failure = runCatching { repository.save(key, "x$updated") }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals(updated, repository.read(key))
                repository.save(key, " \t\n")
                assertNull(repository.read(key))
            } finally {
                db.close()
            }
        }

    private fun createLegacyDatabase(context: Context) {
        FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(55) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 55)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = error("Unexpected upgrade from $oldVersion to $newVersion")
                        },
                    ).build(),
            ).use { helper ->
                helper.writableDatabase.execSQL(
                    """
                    INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname, saslMechanism, autoConnect, ordering, restoreAutoConnect)
                    VALUES (1, 'existing', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 0, 0, 0)
                    """.trimIndent(),
                )
            }
    }

    private companion object {
        const val DB_NAME = "migration-55-56-test.db"
    }
}
