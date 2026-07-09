package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResult
import io.github.trevarj.motd.irc.event.IrcEvent
import java.time.Instant

/**
 * Reconnect catch-up (plans/04), extracted from [ConnectionManagerImpl] so it is testable against
 * a fake history source without an Android context or a real socket.
 *
 * ```
 * if chathistory:
 *   since = max newestTime over the network's open buffers (null → LATEST each)
 *   targets = TARGETS timestamp=since timestamp=now 100
 *   for target in targets ∪ open buffers:
 *     loop AFTER target timestamp=<local newest> pageLimit; insert; break on short page
 *   fetch MARKREAD for every open buffer
 * ```
 */
class CatchUp(
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val history: HistorySource,
    private val normalize: (String) -> String,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val pageLimit: Int = 100,
) {
    /** Minimal seam over IrcClient for CHATHISTORY + MARKREAD, so tests can script responses. */
    interface HistorySource {
        fun hasCap(cap: String): Boolean
        suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult
        suspend fun fetchReadMarker(target: String)
    }

    suspend fun run(networkId: Long, openBuffers: List<Pair<Long, String>>) {
        if (!history.hasCap(CHATHISTORY_CAP)) return
        val since = openBuffers.mapNotNull { messageDao.newestTime(it.first) }.maxOrNull()

        val discovered = if (since != null) {
            runCatching {
                history.chathistory(
                    ChatHistoryRequest(
                        subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                        target = "*",
                        bound1 = "timestamp=${Instant.ofEpochMilli(since)}",
                        bound2 = "timestamp=${Instant.ofEpochMilli(now())}",
                        limit = 100,
                    ),
                ).targets.map { it.first }
            }.getOrDefault(emptyList())
        } else emptyList()

        val targets = (openBuffers.map { it.second } + discovered).distinct()
        for (target in targets) pageAfter(networkId, target)
        for ((_, name) in openBuffers) runCatching { history.fetchReadMarker(name) }
    }

    private suspend fun pageAfter(networkId: Long, target: String) {
        var localNewest = bufferDao.byName(networkId, normalize(target))?.let { messageDao.newestTime(it.id) }
        // Empty local store: there is no lower bound to page AFTER (an AFTER epoch walks forward from
        // the oldest retained message and can miss recent history on retention-limited bouncers).
        // Pull the most recent page via LATEST first so the buffer paints newest-first (plans/04:
        // "null → LATEST each joined buffer"). Subsequent AFTER pages then fill any gap forward.
        if (localNewest == null) {
            val latest = runCatching {
                history.chathistory(ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = pageLimit))
            }.getOrNull()
            if (latest != null && latest.events.isNotEmpty()) {
                processor.process(networkId, IrcEvent.HistoryBatch(target, latest.events))
                localNewest = bufferDao.byName(networkId, normalize(target))?.let { messageDao.newestTime(it.id) }
                if (latest.events.size < pageLimit) return // short page: nothing newer to page AFTER
            } else {
                return // no history for this target
            }
        }
        while (true) {
            val bound = "timestamp=${Instant.ofEpochMilli(localNewest!!)}"
            val result = runCatching {
                history.chathistory(ChatHistoryRequest(ChatHistoryRequest.Subcommand.AFTER, target, bound1 = bound, limit = pageLimit))
            }.getOrNull() ?: break
            if (result.events.isEmpty()) break
            processor.process(networkId, IrcEvent.HistoryBatch(target, result.events))
            val newNewest = bufferDao.byName(networkId, normalize(target))?.let { messageDao.newestTime(it.id) }
            if (newNewest == localNewest) break // no progress guard
            localNewest = newNewest
            if (result.events.size < pageLimit) break
        }
    }

    companion object {
        const val CHATHISTORY_CAP = "draft/chathistory"
    }
}
