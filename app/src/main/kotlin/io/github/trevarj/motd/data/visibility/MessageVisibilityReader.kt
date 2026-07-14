package io.github.trevarj.motd.data.visibility

import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class VisibleMessageAnchor(
    val id: Long,
    val msgid: String?,
    val serverTime: Long,
)

/** Policy-backed one-shot reads kept outside the frozen Room DAO and repository contracts. */
@Singleton
class MessageVisibilityReader @Inject constructor(
    private val db: MotdDatabase,
) {
    private val chatListCache = LinkedHashMap<ChatListCacheKey, ChatListRow>()

    fun observeLatestRawTime(bufferId: Long): Flow<Long?> = callbackFlow {
        val observer = object : InvalidationTracker.Observer("messages") {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        db.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }.map { latestRawTime(bufferId) }.distinctUntilChanged()

    suspend fun latestRawTime(bufferId: Long): Long? = withContext(Dispatchers.IO) {
        db.query(
            SimpleSQLiteQuery(
                "SELECT MAX(serverTime) FROM messages WHERE bufferId = ?",
                arrayOf<Any>(bufferId),
            ),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    suspend fun countTimelineNewer(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        spec: MessageVisibilitySpec,
    ): Int = countMatchingRows(
        where = "bufferId = ? AND (serverTime > ? OR (serverTime = ? AND id > ?))",
        args = listOf(bufferId, serverTime, serverTime, id),
        order = "serverTime DESC, id DESC",
        predicate = MessageVisibilityPolicy(spec)::timeline,
    )

    suspend fun firstVisibleUnreadTime(
        bufferId: Long,
        after: Long,
        spec: MessageVisibilitySpec,
    ): Long? {
        val policy = MessageVisibilityPolicy(spec)
        return firstMatchingRow(
            where = "bufferId = ? AND serverTime > ?",
            args = listOf(bufferId, after),
            order = "serverTime ASC, id ASC",
            predicate = policy::visibleUnread,
        )?.serverTime
    }

    suspend fun resolveSavedAnchor(
        bufferId: Long,
        msgid: String?,
        serverTime: Long,
        id: Long,
        spec: MessageVisibilitySpec,
    ): VisibleMessageAnchor? {
        val policy = MessageVisibilityPolicy(spec)
        val exact = queryRows(
            where = if (msgid != null) "bufferId = ? AND msgid = ?" else
                "bufferId = ? AND serverTime = ? AND id = ?",
            args = if (msgid != null) listOf(bufferId, msgid) else listOf(bufferId, serverTime, id),
            order = "serverTime DESC, id DESC",
            limit = 1,
            offset = 0,
        ).firstOrNull()
        if (exact != null && policy.anchor(exact)) return exact.toAnchor()

        // Prefer the first meaningful row at or behind the old viewport, then the nearest newer
        // row. This avoids surprising forward jumps while history is being read.
        val older = firstMatchingRow(
            where = "bufferId = ? AND (serverTime < ? OR (serverTime = ? AND id < ?))",
            args = listOf(bufferId, serverTime, serverTime, id),
            order = "serverTime DESC, id DESC",
            predicate = policy::anchor,
        )
        if (older != null) return older.toAnchor()
        return firstMatchingRow(
            where = "bufferId = ? AND (serverTime > ? OR (serverTime = ? AND id > ?))",
            args = listOf(bufferId, serverTime, serverTime, id),
            order = "serverTime ASC, id ASC",
            predicate = policy::anchor,
        )?.toAnchor()
    }

    /** Replace fool-authored chat-list state, then re-sort by the resulting meaningful activity. */
    suspend fun resolveChatList(
        rows: List<ChatListRow>,
        spec: MessageVisibilitySpec,
    ): List<ChatListRow> {
        if (spec.fools.isEmpty()) return rows
        val resolved = rows.map { row ->
            val key = ChatListCacheKey(row, spec)
            synchronized(chatListCache) { chatListCache[key] } ?: resolveChatListRow(row, spec).also {
                synchronized(chatListCache) {
                    chatListCache[key] = it
                    while (chatListCache.size > CHAT_LIST_CACHE_SIZE) {
                        chatListCache.remove(chatListCache.keys.first())
                    }
                }
            }
        }
        return resolved.sortedWith(
            compareByDescending<ChatListRow> { it.pinned }
                .thenBy { it.lastMessageTime == null }
                .thenByDescending { it.lastMessageTime ?: Long.MIN_VALUE }
                .thenByDescending { it.bufferId },
        )
    }

    private suspend fun resolveChatListRow(
        row: ChatListRow,
        spec: MessageVisibilitySpec,
    ): ChatListRow {
        val policy = MessageVisibilityPolicy(spec)
        val preview = firstMatchingRow(
            where = "bufferId = ? AND kind NOT IN ('JOIN', 'PART', 'QUIT', 'NETSPLIT', 'NETJOIN')",
            args = listOf(row.bufferId),
            order = "serverTime DESC, id DESC",
            predicate = policy::preview,
        )
        var unreadCount = 0
        var mentionCount = 0
        forEachMatchingRow(
            where = "bufferId = ? AND isSelf = 0 " +
                "AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION') " +
                "AND serverTime > COALESCE((SELECT readMarkerTime FROM buffers WHERE id = ?), 0)",
            args = listOf(row.bufferId, row.bufferId),
            order = "serverTime DESC, id DESC",
            predicate = policy::visibleUnread,
        ) { message ->
            unreadCount++
            if (message.hasMention) mentionCount++
        }
        return row.copy(
            lastMessageText = preview?.text,
            lastMessageSender = preview?.sender,
            lastMessageTime = preview?.serverTime,
            unreadCount = unreadCount,
            mentionCount = mentionCount,
        )
    }

    private suspend fun firstMatchingRow(
        where: String,
        args: List<Any>,
        order: String,
        predicate: (MessageEntity) -> Boolean,
    ): MessageEntity? {
        var offset = 0
        while (true) {
            val page = queryRows(where, args, order, READ_CHUNK_SIZE, offset)
            page.firstOrNull(predicate)?.let { return it }
            if (page.size < READ_CHUNK_SIZE) return null
            offset += page.size
        }
    }

    private suspend fun countMatchingRows(
        where: String,
        args: List<Any>,
        order: String,
        predicate: (MessageEntity) -> Boolean,
    ): Int {
        var count = 0
        forEachMatchingRow(where, args, order, predicate) { count++ }
        return count
    }

    /** Sequence pages keep every SQL statement and allocation bounded on long-lived buffers. */
    private suspend fun forEachMatchingRow(
        where: String,
        args: List<Any>,
        order: String,
        predicate: (MessageEntity) -> Boolean,
        block: (MessageEntity) -> Unit,
    ) {
        var offset = 0
        while (true) {
            val page = queryRows(where, args, order, READ_CHUNK_SIZE, offset)
            page.filter(predicate).forEach(block)
            if (page.size < READ_CHUNK_SIZE) return
            offset += page.size
        }
    }

    private suspend fun queryRows(
        where: String,
        args: List<Any>,
        order: String,
        limit: Int,
        offset: Int,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT id, bufferId, msgid, serverTime, sender, kind, text, isSelf, hasMention
            FROM messages WHERE $where ORDER BY $order LIMIT $limit OFFSET $offset
        """.trimIndent()
        db.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MessageEntity(
                            id = cursor.getLong(0),
                            bufferId = cursor.getLong(1),
                            msgid = cursor.getString(2),
                            serverTime = cursor.getLong(3),
                            sender = cursor.getString(4),
                            kind = MessageKind.valueOf(cursor.getString(5)),
                            text = cursor.getString(6),
                            isSelf = cursor.getInt(7) != 0,
                            hasMention = cursor.getInt(8) != 0,
                            dedupKey = "visibility:${cursor.getLong(0)}",
                        ),
                    )
                }
            }
        }
    }

    private fun MessageEntity.toAnchor() = VisibleMessageAnchor(id, msgid, serverTime)

    private data class ChatListCacheKey(
        val row: ChatListRow,
        val spec: MessageVisibilitySpec,
    )

    private companion object {
        const val READ_CHUNK_SIZE = 128
        const val CHAT_LIST_CACHE_SIZE = 256
    }
}
