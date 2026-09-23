package io.github.trevarj.motd.data.visibility

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.SMART_PRESENCE_WINDOW_MS
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

/**
 * Presence events attributable to one user. These carry that user's `normalizedActor`, which is what
 * makes the smart test ([PresenceMode.SMART]) possible: "did this actor speak here recently".
 */
val ACTOR_PRESENCE_KINDS: Set<MessageKind> =
    setOf(
        MessageKind.JOIN,
        MessageKind.PART,
        MessageKind.QUIT,
        MessageKind.NICK,
        MessageKind.AWAY,
        MessageKind.BACK,
    )

/**
 * Netsplit/netjoin rows aggregate many users into a single condensed row and carry no single actor
 * (`normalizedActor` is empty), so the smart test cannot apply to them. They are already low-noise
 * by construction and only [PresenceMode.HIDDEN] removes them.
 */
val AGGREGATE_PRESENCE_KINDS: Set<MessageKind> = setOf(MessageKind.NETSPLIT, MessageKind.NETJOIN)

/** Every non-conversation row governed by [MessageVisibilitySpec.presenceMode]. */
val PRESENCE_KINDS: Set<MessageKind> = ACTOR_PRESENCE_KINDS + AGGREGATE_PRESENCE_KINDS

val CONVERSATION_KINDS: Set<MessageKind> =
    setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)

data class MessageVisibilitySpec(
    val presenceMode: PresenceMode = PresenceMode.SMART,
    val showRedactedMessages: Boolean = true,
    /** Stored configured nicks; consumers normalize them with the room's IRC identity rules. */
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    /** Chat-local escape hatch: reveal HIDE-mode rows without making them meaningful activity. */
    val revealHiddenFools: Boolean = false,
) {
    companion object {
        /** [override] is the conversation's own presence choice; null inherits the global one. */
        fun from(
            settings: Settings,
            override: PresenceMode? = null,
        ): MessageVisibilitySpec =
            MessageVisibilitySpec(
                presenceMode = override ?: settings.presenceMode,
                showRedactedMessages = settings.showRedactedMessages,
                fools = settings.fools,
                foolsMode = settings.foolsMode,
            )
    }
}

/** One policy for every consumer that decides whether a stored message is meaningful. */
class MessageVisibilityPolicy(
    private val spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
) {
    private val foolActors = spec.fools.mapTo(hashSetOf<String>()) { identityRules.normalize(it.trim()) }
    private val foolAccounts = spec.fools.mapTo(hashSetOf<String>()) { it.trim() }

    fun matchesFoolIdentity(
        senderAccount: String?,
        normalizedActor: String,
    ): Boolean = senderAccount?.let { it in foolAccounts } == true || normalizedActor in foolActors

    fun isFool(message: MessageEntity): Boolean =
        message.kind in CONVERSATION_KINDS &&
            !message.isSelf &&
            matchesFoolIdentity(message.senderAccount, message.normalizedActor)

    /**
     * Rows physically presented by the timeline. Collapse retains its expandable placeholder.
     *
     * The smart test is deliberately NOT re-applied here. It depends on neighboring rows, so
     * [MessageVisibilitySql] owns it and every row an in-memory consumer can see has already passed
     * it — re-deciding from a lone entity would hide rows that are on screen and corrupt the anchor
     * and effective-bottom scans that run over loaded pages. In-memory, SMART therefore admits the
     * same rows as [PresenceMode.ALL]; only [PresenceMode.HIDDEN] removes presence rows outright.
     */
    fun timeline(message: MessageEntity): Boolean =
        (spec.presenceMode != PresenceMode.HIDDEN || message.kind !in PRESENCE_KINDS) &&
            (spec.showRedactedMessages || message.kind != MessageKind.REDACTED) &&
            !(spec.foolsMode == FoolsMode.HIDE && !spec.revealHiddenFools && isFool(message))

    /** Preview and activity use the same eligibility; fools never reorder the chat list. */
    fun preview(message: MessageEntity): Boolean =
        message.kind !in PRESENCE_KINDS &&
            (spec.showRedactedMessages || message.kind != MessageKind.REDACTED) &&
            !isFool(message)

    fun activity(message: MessageEntity): Boolean = preview(message)

    /**
     * Visible unread and mention counts include only other users' meaningful chat rows, plus
     * incoming file offers: the chat-list cue counts payload-bearing DCC_TRANSFER rows, so the
     * unread anchor must too or the badge promises a divider entry never resolves.
     */
    fun visibleUnread(message: MessageEntity): Boolean =
        (
            message.kind in CONVERSATION_KINDS ||
                (message.kind == MessageKind.DCC_TRANSFER && message.eventPayload != null)
        ) &&
            !message.isSelf && !isFool(message)

    /** Hide removes fool results; Collapse keeps them so the target can be expanded on open. */
    fun search(message: MessageEntity): Boolean =
        message.kind in CONVERSATION_KINDS &&
            !(spec.foolsMode == FoolsMode.HIDE && isFool(message))

    /** Anchors never attach to ignored activity, including a collapsed fool placeholder. */
    fun anchor(message: MessageEntity): Boolean = timeline(message) && !isFool(message)

    /** Ignored raw tails are already settled when the newest meaningful row is at the viewport. */
    fun effectiveBottom(message: MessageEntity): Boolean = anchor(message)
}

/** SQL equivalent of [MessageVisibilityPolicy], shared by paging and positional reads. */
internal class MessageVisibilitySql(
    private val spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
) {
    private val foolIdentities =
        spec.fools
            .asSequence()
            .map { configured ->
                val account = configured.trim()
                identityRules.normalize(account) to account
            }.distinct()
            .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            .joinToString(",") { (actor, account) ->
                val accountLiteral = if (account == actor) "NULL" else sqlBlobLiteral(account)
                "(${sqlBlobLiteral(actor)},$accountLiteral)"
            }
    private val defaultNotFoolPredicate = buildNotFoolPredicate()

    fun timeline(alias: String = "m"): String =
        allOf(
            when (spec.presenceMode) {
                PresenceMode.ALL -> TRUE
                PresenceMode.HIDDEN -> notPresence(alias)
                PresenceMode.SMART -> smartPresence(alias)
            },
            redactionVisibility(alias),
            if (spec.foolsMode == FoolsMode.HIDE && !spec.revealHiddenFools) notFool(alias) else TRUE,
        )

    fun anchor(alias: String = "m"): String = allOf(timeline(alias), notFool(alias))

    fun preview(alias: String = "m"): String = allOf(notPresence(alias), redactionVisibility(alias), notFool(alias))

    // The DCC disjunct mirrors the chat-list unreadCount SQL in Daos.kt: a payload-bearing
    // incoming offer is counted by the cue, so the unread anchor must see the same row.
    fun visibleUnread(alias: String = "m"): String =
        allOf(
            "(${column(alias, "kind")} IN ($CONVERSATION_KIND_SQL) " +
                "OR (${column(alias, "kind")} = '${MessageKind.DCC_TRANSFER.name}' " +
                "AND ${column(alias, "eventPayload")} IS NOT NULL))",
            "${column(alias, "isSelf")} = 0",
            notFool(alias),
        )

    private fun notPresence(alias: String): String = "${column(alias, "kind")} NOT IN ($PRESENCE_KIND_SQL)"

    private fun redactionVisibility(alias: String): String = if (spec.showRedactedMessages) TRUE else "${column(alias, "kind")} != '${MessageKind.REDACTED.name}'"

    /**
     * Keep an actor-attributable presence row only when that actor took part in the conversation:
     * they sent a message in the same room within [SMART_PRESENCE_WINDOW_MS] before the event.
     * Backward-looking only, matching Halloy, so a row's visibility never changes as later messages
     * arrive — a forward-looking window would make already-rendered rows appear and disappear.
     *
     * Our own presence rows are always kept: "you joined" anchors a freshly opened buffer even
     * before anything has been said. Aggregate netsplit/netjoin rows have no single actor and are
     * left alone here; only HIDDEN drops them.
     *
     * The correlated lookup is a covering seek on (bufferId, normalizedActor, serverTime).
     */
    private fun smartPresence(alias: String): String {
        val kind = column(alias, "kind")
        val serverTime = column(alias, "serverTime")
        return "($kind NOT IN ($ACTOR_PRESENCE_KIND_SQL) " +
            "OR ${column(alias, "isSelf")} = 1 " +
            "OR EXISTS (SELECT 1 FROM messages spoke " +
            "WHERE spoke.bufferId = ${column(alias, "bufferId")} " +
            "AND spoke.normalizedActor = ${column(alias, "normalizedActor")} " +
            "AND spoke.kind IN ($CONVERSATION_KIND_SQL) " +
            "AND spoke.serverTime <= $serverTime " +
            "AND spoke.serverTime >= $serverTime - $SMART_PRESENCE_WINDOW_MS))"
    }

    private fun notFool(alias: String): String = if (alias == "m") defaultNotFoolPredicate else buildNotFoolPredicate(alias)

    private fun buildNotFoolPredicate(alias: String = "m"): String {
        if (foolIdentities.isEmpty()) return TRUE
        return "NOT (${column(alias, "kind")} IN ($CONVERSATION_KIND_SQL) " +
            "AND ${column(alias, "isSelf")} = 0 " +
            "AND EXISTS (SELECT 1 FROM (VALUES $foolIdentities) AS fool " +
            "WHERE fool.column1 = CAST(${column(alias, "normalizedActor")} AS BLOB) " +
            "OR (${column(alias, "senderAccount")} IS NOT NULL " +
            "AND COALESCE(fool.column2, fool.column1) = " +
            "CAST(${column(alias, "senderAccount")} AS BLOB))))"
    }

    companion object {
        /**
         * Fool exclusion across networks. There are exactly three foldings, so three fixed terms
         * keyed on [caseMappingExpr] cover every network and the SQL stays constant as the network
         * list changes. A missing identity row means CASEMAPPING was never advertised: RFC1459 by
         * protocol default.
         */
        fun notFoolAnyCasemap(
            spec: MessageVisibilitySpec,
            caseMappingExpr: String,
            alias: String = "m",
        ): String {
            if (spec.fools.isEmpty()) return TRUE
            val rfc1459 = IrcCaseMapping.Rfc1459.rawName
            val strict = IrcCaseMapping.Rfc1459Strict.rawName
            val casemap = "LOWER(COALESCE($caseMappingExpr,'$rfc1459'))"
            return listOf(
                "$casemap = '$rfc1459'" to IrcCaseMapping.Rfc1459,
                "$casemap = '$strict'" to IrcCaseMapping.Rfc1459Strict,
                // ASCII, plus every unrecognized advertisement.
                "$casemap NOT IN ('$rfc1459','$strict')" to IrcCaseMapping.Ascii,
            ).joinToString(separator = " OR ", prefix = "(", postfix = ")") { (test, mapping) ->
                "($test AND ${MessageVisibilitySql(spec, IrcIdentityRules(mapping)).notFool(alias)})"
            }
        }
    }
}

/** The PagingSource and its positional count deliberately share this exact timeline predicate. */
internal fun messagePagingQuery(
    bufferId: Long,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery =
    SimpleSQLiteQuery(
        "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
            "AND ${MessageVisibilitySql(spec, identityRules).timeline()} " +
            "ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
        arrayOf(bufferId),
    )

/**
 * The single newest row [messagePagingQuery] would present, or none when the filter admits none.
 *
 * Deliberately the same predicate and the same ordering with `LIMIT 1`, because this IS the
 * presented list's ceiling: timeline seams clamp against it so a gap edge resolved in raw
 * message-store coordinates cannot come to rest above every row the reader can actually see.
 */
internal fun newestPresentedMessageQuery(
    bufferId: Long,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery =
    SimpleSQLiteQuery(
        "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
            "AND ${MessageVisibilitySql(spec, identityRules).timeline()} " +
            "ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1",
        arrayOf(bufferId),
    )

/** Oldest row the same visibility-filtered PagingSource would present. */
internal fun oldestPresentedMessageQuery(
    bufferId: Long,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery =
    SimpleSQLiteQuery(
        "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
            "AND ${MessageVisibilitySql(spec, identityRules).timeline()} " +
            "ORDER BY m.serverTime ASC, m.timelineOrder ASC, m.id ASC LIMIT 1",
        arrayOf(bufferId),
    )

internal fun countTimelineNewerQuery(
    bufferId: Long,
    serverTime: Long,
    id: Long,
    timelineOrder: Long,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery =
    SimpleSQLiteQuery(
        "SELECT COUNT(*) FROM messages m WHERE m.bufferId = ? " +
            "AND (m.serverTime > ? OR (m.serverTime = ? AND (m.timelineOrder > ? OR " +
            "(m.timelineOrder = ? AND m.id > ?)))) " +
            "AND ${MessageVisibilitySql(spec, identityRules).timeline()}",
        arrayOf(bufferId, serverTime, serverTime, timelineOrder, timelineOrder, id),
    )

/**
 * Count visible unread rows in the newest [beforeIndex] timeline positions. Paging placeholders and
 * max-size page drops do not participate: the same SQL predicate and ordering as the PagingSource
 * determine the viewport prefix. The outer limit bounds work once the UI's 99+ cap is reached.
 */
internal fun countVisibleUnreadInTimelinePrefixQuery(
    bufferId: Long,
    beforeIndex: Int,
    after: TimelineAnchor,
    maxCount: Int,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery {
    val visibility = MessageVisibilitySql(spec, identityRules)
    return SimpleSQLiteQuery(
        "SELECT COUNT(*) FROM (" +
            "SELECT 1 FROM (" +
            "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
            "AND ${visibility.timeline()} ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT ?" +
            ") AS viewport WHERE (viewport.serverTime > ? OR " +
            "(viewport.serverTime = ? AND (viewport.timelineOrder > ? OR " +
            "(viewport.timelineOrder = ? AND viewport.id > ?)))) " +
            "AND ${visibility.visibleUnread("viewport")} LIMIT ?" +
            ") AS capped",
        arrayOf<Any>(
            bufferId,
            beforeIndex.coerceAtLeast(0),
            after.serverTime,
            after.serverTime,
            after.timelineOrder,
            after.timelineOrder,
            after.eventId,
            maxCount.coerceAtLeast(0),
        ),
    )
}

internal fun firstVisibleUnreadQuery(
    bufferId: Long,
    after: TimelineAnchor,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery =
    SimpleSQLiteQuery(
        "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
            "AND (m.serverTime > ? OR (m.serverTime = ? AND (m.timelineOrder > ? OR " +
            "(m.timelineOrder = ? AND m.id > ?)))) " +
            "AND ${MessageVisibilitySql(spec, identityRules).visibleUnread()} " +
            "ORDER BY m.serverTime ASC, m.timelineOrder ASC, m.id ASC LIMIT 1",
        arrayOf<Any>(
            bufferId,
            after.serverTime,
            after.serverTime,
            after.timelineOrder,
            after.timelineOrder,
            after.eventId,
        ),
    )

/**
 * Oldest unread nick mention among the newest [beforeIndex] visible-timeline rows: the nearest
 * such mention sitting strictly below the viewport (reversed list, index < firstVisibleItemIndex).
 * `visibleUnread` already excludes self/fools and non-chat kinds (its DCC-offer disjunct never
 * carries a mention), so `hasMention = 1` narrows it to mentions of our nick. Ordered ascending so
 * the oldest (closest to the viewport edge) wins.
 */
internal fun nearestUnreadMentionInPrefixQuery(
    bufferId: Long,
    beforeIndex: Int,
    after: TimelineAnchor,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery {
    val visibility = MessageVisibilitySql(spec, identityRules)
    return SimpleSQLiteQuery(
        "SELECT viewport.* FROM (" +
            "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
            "AND ${visibility.timeline()} ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT ?" +
            ") AS viewport WHERE (viewport.serverTime > ? OR " +
            "(viewport.serverTime = ? AND (viewport.timelineOrder > ? OR " +
            "(viewport.timelineOrder = ? AND viewport.id > ?)))) " +
            "AND ${visibility.visibleUnread("viewport")} " +
            "AND viewport.hasMention = 1 " +
            "ORDER BY viewport.serverTime ASC, viewport.timelineOrder ASC, viewport.id ASC LIMIT 1",
        arrayOf<Any>(
            bufferId,
            beforeIndex.coerceAtLeast(0),
            after.serverTime,
            after.serverTime,
            after.timelineOrder,
            after.timelineOrder,
            after.eventId,
        ),
    )
}

/**
 * Keyset cursor into the cross-buffer stream.
 *
 * `(serverTime, id)` is the only globally comparable ordering key. `timelineOrder` carries two
 * incomparable scales — `CanonicalTimelineStore`'s playback settle rewrites dense per-(buffer,
 * serverTime) indexes 0,1,2…, while every other writer stores the rowid — so it orders rows only
 * WITHIN one buffer. Cross-buffer it would sink settled history below live rows on every
 * same-second tie and flip a buffer's position whenever its history replays.
 */
data class GlobalFeedKey(
    val serverTime: Long,
    val id: Long,
)

/** Which side of a [GlobalFeedKey] a page reads, and whether the key row itself is included. */
internal enum class GlobalFeedSeek { OLDER, OLDER_OR_AT, NEWER }

/** The shared cross-buffer pager supports the ordinary stream and its mention-only view. */
internal enum class GlobalFeedMode { ALL, MENTIONS }

/**
 * Cross-buffer reverse-chronological conversation stream, newest first.
 *
 * Lifecycle exclusions match [io.github.trevarj.motd.data.db.MessageDao.observeInvitations]; the
 * `event_redirects` anti-join is defensive only — `CanonicalTimelineStore.coalesce` already deletes
 * the losing row.
 *
 * Fools are excluded in both modes (preview semantics: a collapsed placeholder means nothing here),
 * keyed on each row's own network casemap so the SQL never depends on which networks exist.
 *
 * [key] seeks one page without OFFSET; a null key reads the newest page. [GlobalFeedSeek.NEWER] is the
 * prepend direction and returns rows oldest-first, so the caller reverses them.
 */
internal fun globalFeedPagingQuery(
    spec: MessageVisibilitySpec,
    key: GlobalFeedKey? = null,
    seek: GlobalFeedSeek = GlobalFeedSeek.OLDER,
    limit: Int? = null,
    mode: GlobalFeedMode = GlobalFeedMode.ALL,
): SimpleSQLiteQuery {
    val direction = if (key != null && seek == GlobalFeedSeek.NEWER) "ASC" else "DESC"
    val keyset = if (key == null) "" else "${keysetPredicate(seek)} "
    val limitClause = if (limit == null) "" else " LIMIT ?"
    return SimpleSQLiteQuery(
        "SELECT m.*, b.displayName AS bufferDisplayName, n.name AS networkName, " +
            "b.type AS bufferType, b.networkId AS networkId, " +
            "b.avatarOverrideModel AS avatarOverrideModel, " +
            "ni.caseMapping AS caseMapping, ni.chanTypes AS chanTypes " +
            "FROM messages m " +
            // CROSS JOIN pins join order, not semantics: driven from buffers the ORDER BY becomes
            // a temp B-tree sort per page instead of a walk of index_messages_serverTime_id.
            "CROSS JOIN buffers b ON b.id = m.bufferId " +
            "JOIN networks n ON n.id = b.networkId " +
            "LEFT JOIN network_identity ni ON ni.networkId = b.networkId " +
            "LEFT JOIN event_redirects redirect ON redirect.losingEventId = m.id " +
            "WHERE b.type IN ('CHANNEL','QUERY') AND b.dismissed = 0 " +
            (if (mode == GlobalFeedMode.ALL) "AND b.archived = 0 " else "") +
            "AND b.pendingCloseAt IS NULL AND b.redirectToRoomId IS NULL " +
            "AND redirect.losingEventId IS NULL " +
            "AND m.kind IN ($CONVERSATION_KIND_SQL) " +
            (if (mode == GlobalFeedMode.MENTIONS) "AND m.hasMention = 1 AND m.isSelf = 0 " else "") +
            "AND ${MessageVisibilitySql.notFoolAnyCasemap(spec, "ni.caseMapping")} " +
            keyset +
            "ORDER BY m.serverTime $direction, m.id $direction" +
            limitClause,
        buildList<Any> {
            key?.let { addAll(listOf(it.serverTime, it.serverTime, it.id)) }
            limit?.let { add(it) }
        }.toTypedArray(),
    )
}

/**
 * Written as a leading-column range plus a tie-break rather than the row-value form
 * `(serverTime, id) < (?, ?)`: SQLite gives no index guarantee for row values, while
 * `serverTime <= ?` is a plain range constraint the `(serverTime, id)` index can seek on.
 */
private fun keysetPredicate(seek: GlobalFeedSeek): String =
    when (seek) {
        GlobalFeedSeek.OLDER -> "AND m.serverTime <= ? AND (m.serverTime < ? OR m.id < ?)"
        GlobalFeedSeek.OLDER_OR_AT -> "AND m.serverTime <= ? AND (m.serverTime < ? OR m.id <= ?)"
        GlobalFeedSeek.NEWER -> "AND m.serverTime >= ? AND (m.serverTime > ? OR m.id > ?)"
    }

private fun allOf(vararg clauses: String): String =
    clauses
        .filterNot { it == TRUE }
        .distinct()
        .joinToString(" AND ")
        .ifEmpty { TRUE }

private fun column(
    alias: String,
    name: String,
): String = if (alias.isEmpty()) name else "$alias.$name"

/**
 * Fool sets can exceed SQLite's bind-variable limit. Every value is represented losslessly as a
 * UTF-8 blob literal instead of dropping values or allocating one bind slot per nick.
 */
private fun sqlBlobLiteral(value: String): String =
    buildString(value.length * 2 + 3) {
        append("X'")
        for (byte in value.encodeToByteArray()) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
        append('\'')
    }

private const val TRUE = "1"
private const val HEX = "0123456789abcdef"
private val PRESENCE_KIND_SQL = PRESENCE_KINDS.joinToString(",") { "'${it.name}'" }
private val ACTOR_PRESENCE_KIND_SQL = ACTOR_PRESENCE_KINDS.joinToString(",") { "'${it.name}'" }
private val CONVERSATION_KIND_SQL = CONVERSATION_KINDS.joinToString(",") { "'${it.name}'" }
