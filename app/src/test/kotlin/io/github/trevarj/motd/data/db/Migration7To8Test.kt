package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migration_adds_nullable_local_unread_floor_and_preserves_buffer_state() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_BUFFERS_V7)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(INSERT_BUFFER)

        MIGRATION_7_8.migrate(db)

        db.query(
            "SELECT displayName, muted, readMarkerTime, localUnreadFloorTime FROM buffers WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("#motd", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(123L, cursor.getLong(2))
            assertNull(cursor.getString(3))
        }
    }

    private companion object {
        const val DB_NAME = "migration-7-8-test.db"
        const val CREATE_BUFFERS_V7 = """
            CREATE TABLE buffers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                networkId INTEGER NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                type TEXT NOT NULL,
                topic TEXT,
                topicSetBy TEXT,
                joined INTEGER NOT NULL,
                pinned INTEGER NOT NULL,
                muted INTEGER NOT NULL,
                ordering INTEGER NOT NULL,
                readMarkerTime INTEGER,
                oldestFetchedTime INTEGER,
                historyComplete INTEGER NOT NULL
            )
        """
        const val INSERT_BUFFER = """
            INSERT INTO buffers (
                id, networkId, name, displayName, type, joined, pinned, muted,
                ordering, readMarkerTime, historyComplete
            ) VALUES (
                1, 1, '#motd', '#motd', 'CHANNEL', 1, 0, 1, 0, 123, 0
            )
        """
    }
}
