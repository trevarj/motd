package io.github.trevarj.motd.data.notes

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import javax.inject.Inject
import javax.inject.Singleton

internal const val MAX_NICK_NOTE_LENGTH = 4096

internal data class NickNoteKey(
    val networkId: Long,
    val kind: NickNoteKind,
    val value: String,
)

internal enum class NickNoteKind { ACCOUNT, NICKNAME }

@Entity(
    tableName = "nick_notes",
    primaryKeys = ["networkId", "kind", "value"],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal class NickNoteEntity(
    val networkId: Long,
    val kind: NickNoteKind,
    val value: String,
    val text: String,
)

@Dao
internal interface NickNotesDao {
    @Query("SELECT text FROM nick_notes WHERE networkId = :networkId AND kind = :kind AND `value` = :value")
    suspend fun read(
        networkId: Long,
        kind: NickNoteKind,
        value: String,
    ): String?

    @Upsert
    suspend fun upsert(note: NickNoteEntity)

    @Query("DELETE FROM nick_notes WHERE networkId = :networkId AND kind = :kind AND `value` = :value")
    suspend fun delete(
        networkId: Long,
        kind: NickNoteKind,
        value: String,
    )
}

@Singleton
internal class NickNotesRepository
    @Inject
    constructor(
        db: MotdDatabase,
    ) {
        private val dao = db.nickNotesDao()

        suspend fun read(key: NickNoteKey): String? = dao.read(key.networkId, key.kind, key.value)

        suspend fun save(
            key: NickNoteKey,
            text: String,
        ) {
            if (text.isBlank()) {
                dao.delete(key.networkId, key.kind, key.value)
            } else {
                require(text.length <= MAX_NICK_NOTE_LENGTH) { "Notes must be at most $MAX_NICK_NOTE_LENGTH characters" }
                dao.upsert(NickNoteEntity(key.networkId, key.kind, key.value, text))
            }
        }
    }
