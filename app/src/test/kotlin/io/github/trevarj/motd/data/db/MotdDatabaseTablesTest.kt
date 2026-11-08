package io.github.trevarj.motd.data.db

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MotdDatabaseTablesTest {
    @Test
    fun observedTablesStayTrackedSoScreensNeverChangeTheTriggerSet() {
        val db = inMemoryDb()
        try {
            val schemaTables =
                names(db, "SELECT name FROM sqlite_master WHERE type = 'table'")
                    .filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it.startsWith("motd_") }
                    .filterNot { it == "android_metadata" || it.startsWith("messages_fts_") }
                    .toSet()
            assertEquals(emptySet<String>(), OBSERVED_TABLES.toSet() intersect UNOBSERVED_TABLES.toSet())
            assertEquals(
                "a new table must join OBSERVED_TABLES or UNOBSERVED_TABLES",
                schemaTables,
                OBSERVED_TABLES.toSet() + UNOBSERVED_TABLES,
            )

            // With every observed table already tracked, no screen's Flow can be a table's first
            // observer, so opening or closing one never installs or drops triggers (a writer transaction).
            db.keepObservedTablesTracked()
            val triggered =
                names(db, "SELECT name FROM sqlite_temp_master WHERE type = 'trigger'")
                    .mapNotNull { Regex("room_table_modification_trigger_(.+)_(INSERT|UPDATE|DELETE)").matchEntire(it)?.groupValues?.get(1) }
                    .groupingBy { it }
                    .eachCount()
            // External-content FTS is tracked through its content table, messages.
            assertEquals((OBSERVED_TABLES.toSet() - "messages_fts").associateWith { 3 }, triggered)
        } finally {
            db.close()
        }
    }

    private fun names(
        db: MotdDatabase,
        sql: String,
    ): List<String> =
        db.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
}
