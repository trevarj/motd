package io.github.trevarj.motd.data.repo

import androidx.sqlite.db.SupportSQLiteDatabase

/** One room's committed message revision. [session] changes if revision tracking is reinstalled. */
internal data class ChatPageVersion(
    val session: String,
    val revision: Long,
)

/**
 * Persistent per-room message revisions, maintained by triggers on every insert, update and delete
 * in `messages`, whichever connection makes the change. Living in the main database, they are
 * readable from any reader connection, so certifying a page never has to wait for the writer.
 * UPDATE touches both rooms when a message is moved.
 *
 * Created when the database opens rather than as a Room entity: there is no schema version to
 * bump, and an install that predates them simply starts every room at revision 0. Every read also
 * counts the tracking objects; if any are missing, [reinstall] rotates the session token, so neither
 * restarted counters nor writes made while a trigger was gone can match an earlier certification.
 * Triggers avoid UPSERT, which needs a newer SQLite than minSdk 26 ships.
 */
internal object ChatRoomRevisions {
    private val tables = listOf("motd_room_revision", "motd_room_revision_session")
    private val triggers = listOf("insert", "update", "delete").map { "motd_room_revision_$it" }

    /** Objects [install] creates; fewer than this means tracking is missing or incomplete. */
    val objectCount: Int get() = tables.size + triggers.size

    const val TRACKING_OBJECTS_SQL =
        "SELECT COUNT(*) FROM sqlite_master WHERE " +
            "(type = 'table' AND name IN ('motd_room_revision', 'motd_room_revision_session')) OR " +
            "(type = 'trigger' AND name IN ('motd_room_revision_insert', 'motd_room_revision_update', 'motd_room_revision_delete'))"

    private fun bump(rooms: String): String {
        val roomIds = rooms.split(",").map(String::trim)
        // Not INSERT OR IGNORE: a triggering statement's own conflict policy (Room's INSERT OR
        // ABORT) overrides one written inside the trigger body.
        return roomIds.joinToString("") {
            "INSERT INTO motd_room_revision (roomId, revision) SELECT $it, 0 " +
                "WHERE NOT EXISTS (SELECT 1 FROM motd_room_revision WHERE roomId = $it); "
        } +
            "UPDATE motd_room_revision SET revision = revision + 1 WHERE roomId IN ($rooms); "
    }

    /** Repair missing tracking. A new session means nothing written while it was missing can certify. */
    fun reinstall(database: SupportSQLiteDatabase) {
        install(database)
        database.execSQL("UPDATE motd_room_revision_session SET token = hex(randomblob(16)) WHERE id = 1")
    }

    fun install(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS motd_room_revision (roomId INTEGER PRIMARY KEY NOT NULL, revision INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS motd_room_revision_session (" +
                "id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1), token TEXT NOT NULL)",
        )
        database.execSQL("INSERT OR IGNORE INTO motd_room_revision_session (id, token) VALUES (1, hex(randomblob(16)))")
        for ((operation, rooms) in listOf(
            "insert" to "NEW.bufferId",
            "delete" to "OLD.bufferId",
            "update" to "OLD.bufferId, NEW.bufferId",
        )) {
            database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS motd_room_revision_$operation AFTER $operation ON messages BEGIN ${bump(rooms)}END",
            )
        }
    }
}
