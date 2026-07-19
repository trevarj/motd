package io.github.trevarj.motd.data.visibility

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

val JOIN_PART_QUIT_KINDS: Set<MessageKind> =
    setOf(
        MessageKind.JOIN,
        MessageKind.PART,
        MessageKind.QUIT,
        MessageKind.NETSPLIT,
        MessageKind.NETJOIN,
    )

val CONVERSATION_KINDS: Set<MessageKind> =
    setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)

data class MessageVisibilitySpec(
    val showJoinPartQuit: Boolean = true,
    /** Stored configured nicks; consumers normalize them with the room's IRC identity rules. */
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    /** Chat-local escape hatch: reveal HIDE-mode rows without making them meaningful activity. */
    val revealHiddenFools: Boolean = false,
) {
    companion object {
        fun from(settings: Settings): MessageVisibilitySpec = MessageVisibilitySpec(
            showJoinPartQuit = settings.showJoinPartQuit,
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

    fun matchesFoolIdentity(senderAccount: String?, normalizedActor: String): Boolean =
        senderAccount?.let { it in foolAccounts } == true || normalizedActor in foolActors

    fun isFool(message: MessageEntity): Boolean =
        message.kind in CONVERSATION_KINDS &&
            !message.isSelf &&
            matchesFoolIdentity(message.senderAccount, message.normalizedActor)

    /** Rows physically presented by the timeline. Collapse retains its expandable placeholder. */
    fun timeline(message: MessageEntity): Boolean =
        (spec.showJoinPartQuit || message.kind !in JOIN_PART_QUIT_KINDS) &&
            !(spec.foolsMode == FoolsMode.HIDE && !spec.revealHiddenFools && isFool(message))

    /** Preview and activity use the same eligibility; fools never reorder the chat list. */
    fun preview(message: MessageEntity): Boolean =
        message.kind !in JOIN_PART_QUIT_KINDS && !isFool(message)

    fun activity(message: MessageEntity): Boolean = preview(message)

    /** Visible unread and mention counts include only other users' meaningful chat rows. */
    fun visibleUnread(message: MessageEntity): Boolean =
        message.kind in CONVERSATION_KINDS && !message.isSelf && !isFool(message)

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
    private val foolIdentities = spec.fools.asSequence()
        .map { configured ->
            val account = configured.trim()
            identityRules.normalize(account) to account
        }
        .distinct()
        .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
        .joinToString(",") { (actor, account) ->
            val accountLiteral = if (account == actor) "NULL" else sqlBlobLiteral(account)
            "(${sqlBlobLiteral(actor)},$accountLiteral)"
        }
    private val defaultNotFoolPredicate = buildNotFoolPredicate()

    fun timeline(alias: String = "m"): String = allOf(
        if (spec.showJoinPartQuit) TRUE else notJoinPartQuit(alias),
        if (spec.foolsMode == FoolsMode.HIDE && !spec.revealHiddenFools) notFool(alias) else TRUE,
    )

    fun anchor(alias: String = "m"): String = allOf(timeline(alias), notFool(alias))

    fun preview(alias: String = "m"): String = allOf(notJoinPartQuit(alias), notFool(alias))

    fun visibleUnread(alias: String = "m"): String = allOf(
        "${column(alias, "kind")} IN ($CONVERSATION_KIND_SQL)",
        "${column(alias, "isSelf")} = 0",
        notFool(alias),
    )

    private fun notJoinPartQuit(alias: String): String =
        "${column(alias, "kind")} NOT IN ($JOIN_PART_QUIT_KIND_SQL)"

    private fun notFool(alias: String): String =
        if (alias == "m") defaultNotFoolPredicate else buildNotFoolPredicate(alias)

    private fun buildNotFoolPredicate(alias: String = "m"): String {
        if (foolIdentities.isEmpty()) return TRUE
        return "NOT (${column(alias, "kind")} IN ($CONVERSATION_KIND_SQL) " +
            "AND ${column(alias, "isSelf")} = 0 " +
            "AND EXISTS (SELECT 1 FROM (VALUES $foolIdentities) AS fool " +
            "WHERE fool.column1 = CAST(${column(alias, "normalizedActor")} AS BLOB) " +
            "OR (${column(alias, "senderAccount")} IS NOT NULL " +
            "AND COALESCE(fool.column2, fool.column1) = " +
            "CAST(${column(alias, "senderAccount")} AS BLOB)))"
    }
}

/** The PagingSource and its positional count deliberately share this exact timeline predicate. */
internal fun messagePagingQuery(
    bufferId: Long,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
        "AND ${MessageVisibilitySql(spec, identityRules).timeline()} " +
        "ORDER BY m.serverTime DESC, m.id DESC",
    arrayOf(bufferId),
)

internal fun countTimelineNewerQuery(
    bufferId: Long,
    serverTime: Long,
    id: Long,
    spec: MessageVisibilitySpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    "SELECT COUNT(*) FROM messages m WHERE m.bufferId = ? " +
        "AND (m.serverTime > ? OR (m.serverTime = ? AND m.id > ?)) " +
        "AND ${MessageVisibilitySql(spec, identityRules).timeline()}",
    arrayOf(bufferId, serverTime, serverTime, id),
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
        "SELECT COUNT(*) FROM (SELECT 1 FROM (" +
            "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
            "AND ${visibility.timeline()} ORDER BY m.serverTime DESC, m.id DESC LIMIT ?" +
            ") viewport WHERE (viewport.serverTime > ? OR " +
            "(viewport.serverTime = ? AND viewport.id > ?)) " +
            "AND ${visibility.visibleUnread("viewport")} LIMIT ?)",
        arrayOf(
            bufferId,
            beforeIndex.coerceAtLeast(0),
            after.serverTime,
            after.serverTime,
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
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    "SELECT m.* FROM messages m WHERE m.bufferId = ? " +
        "AND (m.serverTime > ? OR (m.serverTime = ? AND m.id > ?)) " +
        "AND ${MessageVisibilitySql(spec, identityRules).visibleUnread()} " +
        "ORDER BY m.serverTime ASC, m.id ASC LIMIT 1",
    arrayOf(bufferId, after.serverTime, after.serverTime, after.eventId),
)

private fun allOf(vararg clauses: String): String = clauses
    .filterNot { it == TRUE }
    .distinct()
    .joinToString(" AND ")
    .ifEmpty { TRUE }

private fun column(alias: String, name: String): String = if (alias.isEmpty()) name else "$alias.$name"

/**
 * Fool sets can exceed SQLite's bind-variable limit. Every value is represented losslessly as a
 * UTF-8 blob literal instead of dropping values or allocating one bind slot per nick.
 */
private fun sqlBlobLiteral(value: String): String = buildString(value.length * 2 + 3) {
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
private val JOIN_PART_QUIT_KIND_SQL = JOIN_PART_QUIT_KINDS.joinToString(",") { "'${it.name}'" }
private val CONVERSATION_KIND_SQL = CONVERSATION_KINDS.joinToString(",") { "'${it.name}'" }
