package io.github.trevarj.motd.data.repo

import androidx.sqlite.db.SupportSQLiteDatabase

/** A registration nonce and revision belong to one retained slot on the primary writer. */
internal data class ChatPageVersion(
    val session: String,
    val revision: Long,
    val externalVersion: Long,
)

/**
 * TEMP counters cover only owned slots. Unrelated IRC traffic does not dirty a room's presentation.
 * The writer transaction pins data_version to one connection; another writer or lost TEMP state
 * still invalidates certification. UPDATE touches both rooms when a message is moved.
 */
internal fun readChatPageVersion(
    database: SupportSQLiteDatabase,
    slot: String,
    roomId: Long,
): ChatPageVersion {
    check(database.inTransaction()) { "chat page versions require the primary writer transaction" }
    val installed =
        database
            .query(
                "SELECT COUNT(*) FROM sqlite_temp_master WHERE " +
                    "(type = 'table' AND name = 'motd_chat_page_version') OR " +
                    "(type = 'trigger' AND name IN ('motd_chat_page_insert', 'motd_chat_page_update', 'motd_chat_page_delete'))",
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0) == 4
            }
    if (!installed) {
        for (operation in listOf("insert", "update", "delete")) {
            database.execSQL("DROP TRIGGER IF EXISTS temp.motd_chat_page_$operation")
        }
        database.execSQL("DROP TABLE IF EXISTS temp.motd_chat_page_version")
        database.execSQL(
            "CREATE TEMP TABLE motd_chat_page_version (" +
                "slot TEXT PRIMARY KEY NOT NULL, roomId INTEGER NOT NULL, session TEXT NOT NULL, revision INTEGER NOT NULL)",
        )
        for ((operation, predicate) in listOf(
            "insert" to "roomId = NEW.bufferId",
            "delete" to "roomId = OLD.bufferId",
            "update" to "roomId IN (OLD.bufferId, NEW.bufferId)",
        )) {
            database.execSQL(
                "CREATE TEMP TRIGGER motd_chat_page_$operation AFTER $operation ON main.messages BEGIN " +
                    "UPDATE motd_chat_page_version SET revision = revision + 1 WHERE $predicate; END",
            )
        }
    }
    database.execSQL(
        "INSERT OR IGNORE INTO temp.motd_chat_page_version VALUES (?, ?, hex(randomblob(16)), 0)",
        arrayOf<Any>(slot, roomId),
    )
    val externalVersion =
        database.query("PRAGMA main.data_version").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    return database.query("SELECT session, revision, roomId FROM temp.motd_chat_page_version WHERE slot = ?", arrayOf(slot)).use { cursor ->
        check(cursor.moveToFirst())
        check(cursor.getLong(2) == roomId) { "a chat slot cannot change canonical rooms" }
        ChatPageVersion(cursor.getString(0), cursor.getLong(1), externalVersion)
    }
}

internal fun releaseChatPageVersion(
    database: SupportSQLiteDatabase,
    slot: String,
) {
    val installed =
        database.query("SELECT name FROM sqlite_temp_master WHERE type = 'table' AND name = 'motd_chat_page_version'").use { it.moveToFirst() }
    if (installed) database.execSQL("DELETE FROM temp.motd_chat_page_version WHERE slot = ?", arrayOf(slot))
}
