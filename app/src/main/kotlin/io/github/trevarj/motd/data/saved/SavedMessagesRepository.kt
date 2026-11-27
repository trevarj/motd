package io.github.trevarj.motd.data.saved

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.di.AppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

internal data class SavedMessage(
    val id: Long,
    val sender: String,
    val text: String?,
    val savedAt: Long,
    val serverTime: Long,
    val networkName: String,
    val conversationName: String,
)

internal data class SavedMessageSource(
    val bufferId: Long,
    val eventId: Long?,
    val msgid: String?,
    val serverTime: Long,
)

@Entity(
    tableName = "saved_messages",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["networkId", "bufferId", "msgid"]),
        Index(value = ["networkId", "channelName", "msgid"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SavedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val eventId: Long,
    val bufferId: Long,
    val networkId: Long,
    val channelName: String?,
    val msgid: String?,
    val sender: String,
    val text: String?,
    val savedAt: Long,
    val serverTime: Long,
    val networkName: String,
    val conversationName: String,
)

@Entity(
    tableName = "saved_message_redactions",
    primaryKeys = ["networkId", "bufferId", "msgid"],
    indices = [Index(value = ["networkId", "channelName", "msgid"])],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SavedMessageRedactionEntity(
    val networkId: Long,
    val bufferId: Long,
    val msgid: String,
    val channelName: String?,
)

internal data class SavedMessageIdentity(
    val id: Long,
    val eventId: Long,
    val redacted: Boolean,
    val hasLocalEvent: Boolean,
)

@Singleton
internal class SavedMessagesRepository
    @Inject
    constructor(
        db: MotdDatabase,
        private val clock: AppClock,
    ) {
        private val dao = db.savedMessagesDao()

        fun observe(): Flow<List<SavedMessage>> = dao.observe().distinctUntilChanged()

        fun observeSaved(eventId: Long): Flow<Boolean> = dao.observeSaved(eventId).distinctUntilChanged()

        suspend fun setSaved(
            eventId: Long,
            saved: Boolean,
        ) {
            if (saved) dao.save(eventId, clock.nowMillis()) else dao.deleteForEvent(eventId)
        }

        suspend fun remove(id: Long) = dao.delete(id)

        suspend fun resolveSource(id: Long): SavedMessageSource? = dao.resolveSource(id)
    }

private const val CANONICAL_EVENT_ID =
    "COALESCE((SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :eventId), :eventId)"

private const val CHANNEL_KEY =
    """CASE WHEN b.type = 'CHANNEL' THEN COALESCE(
           (SELECT a.value FROM room_aliases a WHERE a.roomId = b.id AND a.namespace = 'CHANNEL'
                   AND a.verified = 1 ORDER BY a.id DESC LIMIT 1),
           CASE WHEN instr(b.name, char(0)) = 0 THEN b.name END
       ) END"""

private const val REDACTION_MATCH =
    """networkId = :networkId AND
       (eventId = :eventId OR (msgid = :msgid AND (bufferId = :bufferId OR (channelName = :channelName
        AND NOT EXISTS (SELECT 1 FROM buffers WHERE id = saved_messages.bufferId)))))"""

private const val SAVED_IDS_FOR_EVENT =
    """SELECT id FROM saved_messages WHERE eventId = $CANONICAL_EVENT_ID
       UNION ALL
       SELECT s.id FROM messages m JOIN buffers b ON b.id = m.bufferId
       JOIN saved_messages s ON s.networkId = b.networkId AND s.msgid = m.msgid AND
            (s.bufferId = m.bufferId OR s.channelName = $CHANNEL_KEY)
       WHERE m.id = $CANONICAL_EVENT_ID
       AND (s.bufferId = m.bufferId OR NOT EXISTS (SELECT 1 FROM buffers WHERE id = s.bufferId))"""

@Dao
internal interface SavedMessagesDao {
    @Query(
        """SELECT id, sender, text, savedAt, serverTime, networkName, conversationName
           FROM saved_messages ORDER BY savedAt DESC, id DESC""",
    )
    fun observe(): Flow<List<SavedMessage>>

    @Query("SELECT EXISTS($SAVED_IDS_FOR_EVENT)")
    fun observeSaved(eventId: Long): Flow<Boolean>

    @Query("DELETE FROM saved_messages WHERE id IN ($SAVED_IDS_FOR_EVENT)")
    suspend fun deleteForEvent(eventId: Long): Int

    @Query(
        """INSERT INTO saved_messages (eventId, bufferId, networkId, channelName, msgid, sender, text,
                                         savedAt, serverTime, networkName, conversationName)
           SELECT m.id, b.id, n.id, $CHANNEL_KEY, NULLIF(m.msgid, ''), m.sender, m.text,
                  :savedAt, m.serverTime, n.name, b.displayName
           FROM messages m JOIN buffers b ON b.id = m.bufferId JOIN networks n ON n.id = b.networkId
           WHERE m.id = $CANONICAL_EVENT_ID AND b.type IN ('CHANNEL', 'QUERY')
             AND b.redirectToRoomId IS NULL AND b.dismissed = 0
             AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION') AND m.pendingLabel IS NULL AND m.failed = 0
             AND NOT EXISTS ($SAVED_IDS_FOR_EVENT)""",
    )
    suspend fun save(
        eventId: Long,
        savedAt: Long,
    )

    @Query("DELETE FROM saved_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """SELECT b.id AS bufferId, m.id AS eventId, COALESCE(NULLIF(m.msgid, ''), s.msgid) AS msgid,
                  COALESCE(m.serverTime, s.serverTime) AS serverTime
           FROM saved_messages s
           JOIN buffers b ON b.id = COALESCE(
               (SELECT COALESCE(redirectToRoomId, id) FROM buffers WHERE id = s.bufferId),
               (SELECT COALESCE(channel.redirectToRoomId, channel.id)
                FROM room_aliases a JOIN buffers channel ON channel.id = a.roomId
                WHERE a.networkId = s.networkId AND a.namespace = 'CHANNEL'
                  AND a.value = s.channelName AND a.verified = 1 AND channel.type = 'CHANNEL'),
               (SELECT id FROM buffers WHERE networkId = s.networkId AND name = s.channelName
                AND type = 'CHANNEL' AND redirectToRoomId IS NULL)
           )
           LEFT JOIN messages m ON m.bufferId = b.id AND m.id = COALESCE(
               (SELECT id FROM messages WHERE id = s.eventId AND bufferId = b.id),
               (SELECT target.id FROM event_aliases a JOIN messages target ON target.id = a.timelineEventId
                WHERE a.networkId = s.networkId AND a.namespace = 'MSGID' AND a.value = CAST(s.msgid AS BLOB)
                  AND target.bufferId = b.id)
           )
           WHERE s.id = :id AND b.networkId = s.networkId AND b.type IN ('CHANNEL', 'QUERY')
             AND b.dismissed = 0 AND (m.id IS NOT NULL OR s.msgid IS NOT NULL)""",
    )
    suspend fun resolveSource(id: Long): SavedMessageSource?

    @Query("SELECT $CHANNEL_KEY FROM buffers b WHERE b.id = :bufferId")
    suspend fun channelKey(bufferId: Long): String?

    @Query(
        """INSERT OR IGNORE INTO saved_message_redactions (networkId, bufferId, msgid, channelName)
           SELECT :networkId, COALESCE(:bufferId, bufferId), :msgid, :channelName
           FROM saved_messages WHERE $REDACTION_MATCH
           UNION ALL
           SELECT :networkId, :bufferId, :msgid, :channelName
           WHERE :eventId IS NOT NULL AND :bufferId IS NOT NULL""",
    )
    suspend fun rememberRedaction(
        networkId: Long,
        bufferId: Long?,
        channelName: String?,
        eventId: Long?,
        msgid: String,
    )

    @Query(
        """SELECT EXISTS(
           SELECT 1 FROM saved_message_redactions WHERE networkId = :networkId AND bufferId = :bufferId AND msgid = :msgid
           UNION ALL
           SELECT 1 FROM saved_message_redactions r JOIN buffers b ON b.id = :bufferId
           WHERE r.networkId = :networkId AND r.channelName = $CHANNEL_KEY AND r.msgid = :msgid
             AND b.networkId = :networkId AND NOT EXISTS (SELECT 1 FROM buffers WHERE id = r.bufferId))""",
    )
    suspend fun wasRedacted(
        networkId: Long,
        bufferId: Long,
        msgid: String,
    ): Boolean

    @Query("UPDATE saved_messages SET text = NULL WHERE text IS NOT NULL AND ($REDACTION_MATCH)")
    suspend fun redact(
        networkId: Long,
        bufferId: Long?,
        channelName: String?,
        eventId: Long?,
        msgid: String,
    )

    @Query(
        """SELECT m.* FROM saved_message_redactions r
           JOIN messages m ON m.bufferId = r.bufferId AND m.msgid = r.msgid
           WHERE r.networkId = :networkId AND r.bufferId = :bufferId
             AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')""",
    )
    suspend fun roomMessagesToRedact(
        networkId: Long,
        bufferId: Long,
    ): List<TimelineEventEntity>

    @Query(
        """SELECT s.id, s.eventId, s.text IS NULL AS redacted, m.id IS NOT NULL AS hasLocalEvent
           FROM saved_messages s LEFT JOIN messages m ON m.id = s.eventId AND m.bufferId = :bufferId
           WHERE s.eventId IN (:eventId, :losingId)
              OR (s.networkId = :networkId AND s.msgid = :msgid AND
                   (s.bufferId = :bufferId OR (s.channelName = (SELECT $CHANNEL_KEY FROM buffers b WHERE b.id = :bufferId)
                    AND NOT EXISTS (SELECT 1 FROM buffers WHERE id = s.bufferId))))
           ORDER BY s.savedAt, s.id""",
    )
    suspend fun matching(
        networkId: Long,
        bufferId: Long,
        msgid: String?,
        eventId: Long?,
        losingId: Long?,
    ): List<SavedMessageIdentity>

    @Query(
        """UPDATE saved_messages SET eventId = :eventId, bufferId = :bufferId, msgid = COALESCE(:msgid, msgid),
                  channelName = (SELECT $CHANNEL_KEY FROM buffers b WHERE b.id = :bufferId),
                  text = CASE WHEN :redacted THEN NULL ELSE text END WHERE id = :id""",
    )
    suspend fun repoint(
        id: Long,
        eventId: Long,
        bufferId: Long,
        msgid: String?,
        redacted: Boolean,
    )

    @Query(
        """UPDATE saved_messages SET bufferId = :toRoomId,
                  channelName = (SELECT $CHANNEL_KEY FROM buffers b WHERE b.id = :toRoomId)
           WHERE networkId = :networkId AND bufferId = :fromRoomId""",
    )
    suspend fun repointRoom(
        networkId: Long,
        fromRoomId: Long,
        toRoomId: Long,
    ): Int

    @Query(
        """UPDATE OR IGNORE saved_message_redactions SET bufferId = :toRoomId,
                  channelName = (SELECT $CHANNEL_KEY FROM buffers b WHERE b.id = :toRoomId)
           WHERE networkId = :networkId AND (bufferId = :fromRoomId OR (
               channelName IN (:fromChannelName, :toChannelName)
               AND NOT EXISTS (SELECT 1 FROM buffers WHERE id = saved_message_redactions.bufferId)))""",
    )
    suspend fun repointRedactions(
        networkId: Long,
        fromRoomId: Long,
        toRoomId: Long,
        fromChannelName: String?,
        toChannelName: String?,
    )

    @Query("DELETE FROM saved_message_redactions WHERE networkId = :networkId AND bufferId = :bufferId")
    suspend fun dropDuplicateRedactions(
        networkId: Long,
        bufferId: Long,
    )

    @Query(
        """UPDATE saved_messages SET channelName = :newName
           WHERE networkId = :networkId AND (channelName = :oldName OR bufferId = :bufferId)""",
    )
    suspend fun renameSavedChannel(
        networkId: Long,
        bufferId: Long,
        oldName: String,
        newName: String,
    )

    @Query(
        """UPDATE saved_message_redactions SET channelName = :newName
           WHERE networkId = :networkId AND (channelName = :oldName OR bufferId = :bufferId)""",
    )
    suspend fun renameRedactedChannel(
        networkId: Long,
        bufferId: Long,
        oldName: String,
        newName: String,
    )

    @Query(
        """UPDATE saved_messages SET text = NULL
           WHERE networkId = :networkId AND bufferId = :bufferId AND text IS NOT NULL
             AND EXISTS (
               SELECT 1 FROM saved_message_redactions r
               WHERE r.networkId = :networkId AND r.bufferId = :bufferId AND r.msgid = saved_messages.msgid
               UNION ALL
               SELECT 1 FROM messages m
               WHERE m.bufferId = :bufferId AND m.msgid = saved_messages.msgid AND m.kind = 'REDACTED'
             )""",
    )
    suspend fun redactForRoom(
        networkId: Long,
        bufferId: Long,
    )

    @Query(
        """SELECT msgid FROM saved_messages WHERE networkId = :networkId AND bufferId = :bufferId AND msgid IS NOT NULL
           GROUP BY msgid HAVING COUNT(*) > 1""",
    )
    suspend fun duplicateMsgids(
        networkId: Long,
        bufferId: Long,
    ): List<String>

    suspend fun reconcileEvent(
        networkId: Long,
        event: TimelineEventEntity,
        losingId: Long? = null,
    ) {
        val msgid = event.msgid?.takeIf(String::isNotEmpty)
        val matches = matching(networkId, event.bufferId, msgid, event.id, losingId)
        reconcile(matches, event.bufferId, event.id, msgid, event.kind == MessageKind.REDACTED)
    }

    suspend fun mergeRooms(
        networkId: Long,
        fromRoomId: Long,
        toRoomId: Long,
    ) {
        val moved = repointRoom(networkId, fromRoomId, toRoomId)
        redactForRoom(networkId, toRoomId)
        if (moved == 0) return
        for (msgid in duplicateMsgids(networkId, toRoomId)) {
            val matches = matching(networkId, toRoomId, msgid, null, null)
            val event = matches.firstOrNull { it.hasLocalEvent } ?: matches.first()
            reconcile(matches, toRoomId, event.eventId, msgid, false)
        }
    }

    private suspend fun reconcile(
        matches: List<SavedMessageIdentity>,
        bufferId: Long,
        eventId: Long,
        msgid: String?,
        redacted: Boolean,
    ) {
        if (matches.isEmpty()) return
        for (index in 1 until matches.size) delete(matches[index].id)
        repoint(matches.first().id, eventId, bufferId, msgid, redacted || matches.any { it.redacted })
    }
}
