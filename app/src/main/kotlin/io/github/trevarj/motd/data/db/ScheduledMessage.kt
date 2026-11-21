package io.github.trevarj.motd.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IRC_HEX_COLOR
import io.github.trevarj.motd.irc.format.IRC_ITALIC
import io.github.trevarj.motd.irc.format.IRC_MONOSPACE
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IRC_REVERSE
import io.github.trevarj.motd.irc.format.IRC_STRIKETHROUGH
import io.github.trevarj.motd.irc.format.IRC_UNDERLINE
import io.github.trevarj.motd.irc.format.plainIrcText
import kotlinx.coroutines.flow.Flow

const val SCHEDULE_OVERDUE_MS = 86_400_000L

enum class ScheduledMessageState { QUEUED, SENDING, OVERDUE, UNAVAILABLE, UNCERTAIN }

@Entity(tableName = "scheduled_messages")
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val revision: Long = 0,
    val bufferId: Long,
    val networkId: Long,
    val networkName: String,
    val target: String,
    val membershipCycle: Long?,
    val text: String,
    val dueAt: Long?,
    val zoneId: String?,
    val state: ScheduledMessageState = ScheduledMessageState.QUEUED,
)

@Dao
interface ScheduledMessageDao {
    @Query("SELECT * FROM scheduled_messages ORDER BY id")
    fun observeAll(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages ORDER BY id")
    suspend fun all(): List<ScheduledMessageEntity>

    @Insert
    suspend fun insert(message: ScheduledMessageEntity): Long

    @Query("UPDATE scheduled_messages SET state = :to WHERE id = :id AND revision = :revision AND state = :from")
    suspend fun transition(
        id: Long,
        revision: Long,
        from: ScheduledMessageState,
        to: ScheduledMessageState,
    ): Int

    @Query("DELETE FROM scheduled_messages WHERE id = :id AND revision = :revision AND state = :state")
    suspend fun remove(
        id: Long,
        revision: Long,
        state: ScheduledMessageState,
    ): Int

    @Query(
        """
        UPDATE scheduled_messages SET text = :text, dueAt = :dueAt, zoneId = :zoneId, state = 'QUEUED', revision = revision + 1
        WHERE id = :id AND revision = :revision AND state = :state
        """,
    )
    suspend fun revise(
        id: Long,
        revision: Long,
        state: ScheduledMessageState,
        text: String,
        dueAt: Long?,
        zoneId: String?,
    ): Int

    @Query("UPDATE scheduled_messages SET state = 'UNCERTAIN' WHERE state = 'SENDING'")
    suspend fun recoverInterrupted(): Int
}

private const val IRC_FORMATTING_CONTROLS =
    "$IRC_BOLD$IRC_COLOR$IRC_HEX_COLOR$IRC_ITALIC$IRC_MONOSPACE$IRC_RESET" +
        "$IRC_REVERSE$IRC_STRIKETHROUGH$IRC_UNDERLINE"

fun isSchedulableMessage(text: String): Boolean {
    var formatted = false
    for (char in text) {
        if (!char.isISOControl() || char == '\t' || char == '\n' || char == '\r') continue
        if (char !in IRC_FORMATTING_CONTROLS) return false
        formatted = true
    }
    val plain = if (formatted) plainIrcText(text) else text
    val first = plain.firstOrNull { !it.isWhitespace() }
    return first != null && first != '/'
}
