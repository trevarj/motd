package io.github.trevarj.motd.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class NetworkRole { DIRECT, BOUNCER_ROOT, BOUNCER_CHILD }

/**
 * Per-network obfuscation transport (plans/19 §3.4, plans/20 Phase 1). `NONE` is the default
 * direct connection. `SOCKS5` dials through a user-supplied SOCKS5 proxy (host/port). `TOR` is a
 * `SOCKS5` shortcut pinned at Orbot's `127.0.0.1:9050`. `EMBEDDED_REALITY` is the in-app
 * sing-box VLESS+REALITY core configured by a pasted share link; the core exposes its own private
 * loopback SOCKS5 endpoint to the transport.
 */
enum class ObfsMode { NONE, SOCKS5, TOR, EMBEDDED_REALITY }
enum class BufferType { CHANNEL, QUERY, SERVER }
enum class MessageKind {
    PRIVMSG, NOTICE, ACTION, JOIN, PART, QUIT, KICK, NICK, MODE, TOPIC, ERROR, SERVER_INFO,
    INVITE, NETSPLIT, NETJOIN,
}

/** Durable state for an invitation timeline event. Null for every non-invitation message. */
enum class InviteState { PENDING, JOINING, JOINED, DISMISSED, FAILED, HISTORICAL }

@Entity(tableName = "networks")
data class NetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: NetworkRole,
    val parentId: Long? = null,          // BOUNCER_CHILD -> its BOUNCER_ROOT row
    val bouncerNetId: String? = null,
    val host: String, val port: Int, val tls: Boolean = true,
    val nick: String, val username: String, val realname: String,
    val saslMechanism: String = "NONE",  // SaslMechanism.name
    val saslUser: String? = null, val saslPassword: String? = null,
    val clientCertAlias: String? = null,
    val autoConnect: Boolean = true,
    val ordering: Int = 0,
    // Opt-in IRC-over-WebSocket endpoint (plans/19 §3.3). When set (e.g. wss://bnc.example.com:443/)
    // the connection tunnels over a WebSocket to blend with HTTPS; null uses the TCP/TLS transport.
    val wsUrl: String? = null,
    // Opt-in obfuscation transport (plans/19 §3.4, plans/20 Phase 1). null/NONE = direct. SOCKS5/TOR
    // dial the connection through a SOCKS5 proxy at proxyHost:proxyPort with remote DNS; TOR pins
    // Orbot's 127.0.0.1:9050. EMBEDDED_REALITY is configured by an opaque VLESS+REALITY share link;
    // the embedded core owns its loopback SOCKS endpoint, so no proxy host/port is persisted for it.
    val obfsMode: ObfsMode? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    /** Pasted VLESS+REALITY link. It contains credentials, so [toString] must never expose it. */
    val obfsLink: String? = null,
) {
    // Redact secrets (saslPassword) from logs; proxyHost/port are non-sensitive so keep them out
    // too for brevity — the endpoint host:port is enough to identify the row.
    override fun toString() = "NetworkEntity(id=$id, name=$name, role=$role, host=$host:$port)"
}

@Entity(
    tableName = "buffers",
    indices = [Index(value = ["networkId", "name"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = NetworkEntity::class, parentColumns = ["id"],
        childColumns = ["networkId"], onDelete = ForeignKey.CASCADE
    )]
)
data class BufferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val name: String,                    // case-normalized
    val displayName: String,
    val type: BufferType,
    val topic: String? = null, val topicSetBy: String? = null,
    val joined: Boolean = false,
    val pinned: Boolean = false, val muted: Boolean = false,
    val ordering: Int = 0,
    val readMarkerTime: Long? = null,    // epoch ms, synced via draft/read-marker
    val oldestFetchedTime: Long? = null, // CHATHISTORY paging bookkeeping
    val historyComplete: Boolean = false,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["bufferId", "dedupKey"], unique = true),
        // msgid is the durable server identity. Once a row carries a msgid, no second row in the
        // same buffer may share it: any later echo OR CHATHISTORY replay bearing that msgid collapses
        // via INSERT IGNORE instead of duplicating (goguma keys dedup on draft/msgid the same way).
        // SQLite treats NULLs as distinct in a UNIQUE index, so the many still-pending / msgid-less
        // self rows coexist freely; only confirmed duplicates are rejected.
        Index(value = ["bufferId", "msgid"], unique = true),
        // Typed events need a stable identity independent of their rendered text. NULL keeps
        // ordinary chat rows outside this constraint; non-null keys deduplicate socket/push/history.
        Index(value = ["bufferId", "eventKey"], unique = true),
        Index(value = ["bufferId", "serverTime", "id"])],
    foreignKeys = [ForeignKey(
        entity = BufferEntity::class, parentColumns = ["id"],
        childColumns = ["bufferId"], onDelete = ForeignKey.CASCADE
    )]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long,
    val msgid: String? = null,
    val serverTime: Long,
    val sender: String,
    val senderAccount: String? = null,
    val kind: MessageKind,
    val text: String,
    val isSelf: Boolean = false,
    val hasMention: Boolean = false,     // computed at insert by EventProcessor
    val replyToMsgid: String? = null,
    val pendingLabel: String? = null,    // set while awaiting echo; null once confirmed
    val failed: Boolean = false,         // echo timeout -> retry UI
    val dedupKey: String,                // msgid ?: sha1(serverTime|sender|text); "pending:<label>" while pending
    val eventKey: String? = null,        // stable identity for INVITE/NETSPLIT/NETJOIN
    val eventPayload: String? = null,    // versioned, defensively decoded typed-event payload
    val inviteState: InviteState? = null,
)

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(val text: String, val sender: String)

@Entity(
    tableName = "reactions",
    indices = [Index(value = ["bufferId", "targetMsgid", "sender"], unique = true)]
)
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long, val targetMsgid: String,
    val sender: String, val emoji: String, val serverTime: Long,
)

@Entity(tableName = "users", primaryKeys = ["networkId", "nick"])
data class UserEntity(
    val networkId: Long, val nick: String,
    val account: String? = null, val away: Boolean = false,
    val hostmask: String? = null, val realname: String? = null,
)

@Entity(tableName = "members", primaryKeys = ["bufferId", "nick"])
data class MemberEntity(val bufferId: Long, val nick: String, val prefixes: String = "")
