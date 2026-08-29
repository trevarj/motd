package io.github.trevarj.motd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.sidecar.SidecarSecurityState

enum class NetworkRole { DIRECT, BOUNCER_ROOT, BOUNCER_CHILD }

enum class ConnectionTransport { NETWORK, SIDECAR }

/**
 * Per-network obfuscation transport. `NONE` is the default
 * direct connection. `SOCKS5` dials through a user-supplied SOCKS5 proxy (host/port). `TOR` is a
 * `SOCKS5` shortcut pinned at Orbot's `127.0.0.1:9050`. `EMBEDDED_REALITY` is the in-app
 * sing-box VLESS+REALITY core configured by a pasted share link; the core exposes its own private
 * loopback SOCKS5 endpoint to the transport.
 */
enum class ObfsMode { NONE, SOCKS5, TOR, EMBEDDED_REALITY }

enum class BufferType { CHANNEL, QUERY, SERVER }

enum class FolderIconKind { GENERIC, DEVICON, MATERIAL }

enum class FolderIdentityKind { CHANNEL, ACCOUNT, NICK }

typealias RoomId = Long
typealias TimelineEventId = Long

data class TimelineAnchor(
    val serverTime: Long,
    val eventId: TimelineEventId,
    val timelineOrder: Long = eventId,
) : Comparable<TimelineAnchor> {
    override fun compareTo(other: TimelineAnchor): Int =
        compareValuesBy(
            this,
            other,
            TimelineAnchor::serverTime,
            TimelineAnchor::timelineOrder,
            TimelineAnchor::eventId,
        )
}

enum class RoomAliasNamespace { CHANNEL, ACCOUNT, VERIFIED_NICK, PROVISIONAL_NICK, LEGACY_NAME }

enum class EventAliasNamespace { MSGID, LABEL, EXACT_FINGERPRINT, BATCH_POSITION, TYPED_EVENT }

enum class ObservationOrigin { LIVE, PUSH, HISTORY, LOCAL_SEND }

enum class TimeProvenance { SERVER_TAG, LOCAL_CLOCK, UNKNOWN }

enum class MessageKind {
    PRIVMSG,
    NOTICE,
    ACTION,
    JOIN,
    PART,
    QUIT,
    KICK,
    NICK,
    AWAY,
    BACK,
    MODE,
    TOPIC,
    ERROR,
    SERVER_INFO,
    INVITE,
    NETSPLIT,
    NETJOIN,
    DCC_TRANSFER,
    DCC_UNSUPPORTED,
    REDACTED,
}

/** Durable state for an invitation timeline event. Null for every non-invitation message. */
enum class InviteState { PENDING, JOINING, JOINED, DISMISSED, FAILED, HISTORICAL }

enum class DccDirection { INCOMING, OUTGOING }

enum class DccTransferProtocol { SEND, SSEND }

enum class DccAddressKind { IPV4_INTEGER, IPV4_DOTTED, IPV6_LITERAL }

enum class DccTransferState {
    OFFERED,
    ACCEPTING,
    ACTIVE,
    PARTIAL,
    COMPLETED,
    FAILED,
    REJECTED,
    EXPIRED,
    REMOVED,
}

@Entity(tableName = "networks")
data class NetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: NetworkRole,
    val parentId: Long? = null, // BOUNCER_CHILD -> its BOUNCER_ROOT row
    val bouncerNetId: String? = null,
    val host: String,
    val port: Int,
    val tls: Boolean = true,
    val nick: String,
    val username: String,
    val realname: String,
    val saslMechanism: String = "NONE", // SaslMechanism.name
    val saslUser: String? = null,
    val saslPassword: String? = null,
    /** Optional IRC PASS value. Kept separate because servers may require PASS with or without SASL. */
    val serverPassword: String? = null,
    /** Optional NickServ fallback for direct connections without SASL. */
    val nickServPassword: String? = null,
    /** Null is the default nickname-password syntax. */
    val nickServIdentifySyntax: String? = null,
    @ColumnInfo(defaultValue = "0") val nickServRecoveryEnabled: Boolean = false,
    /** Null is the default one-command `REGAIN` sequence. */
    val nickServRecoverySequence: String? = null,
    val initialAwayMessage: String? = null,
    val clientCertAlias: String? = null,
    val autoConnect: Boolean = true,
    /**
     * Manual drawer position, owned by the user (drag or the row's move actions). Distinct and
     * gap-free after any reorder; `NetworkDao.insertLast` appends new rows. The default only applies
     * to rows built in tests/imports — every insert through the repository is ranked.
     */
    val ordering: Int = 0,
    // Opt-in IRC-over-WebSocket endpoint. When set (e.g. wss://bnc.example.com:443/)
    // the connection tunnels over a WebSocket to blend with HTTPS; null uses the TCP/TLS transport.
    val wsUrl: String? = null,
    // Opt-in obfuscation transport. null/NONE = direct. SOCKS5/TOR
    // dial the connection through a SOCKS5 proxy at proxyHost:proxyPort with remote DNS; TOR pins
    // Orbot's 127.0.0.1:9050. EMBEDDED_REALITY is configured by an opaque VLESS+REALITY share link;
    // the embedded core owns its loopback SOCKS endpoint, so no proxy host/port is persisted for it.
    val obfsMode: ObfsMode? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    /** Pasted VLESS+REALITY link. It contains credentials, so [toString] must never expose it. */
    val obfsLink: String? = null,
    /** Comma-separated portable credential requirements left unresolved by a credentials-excluded import. */
    val pendingCredentialRequirements: String? = null,
    /** Desired auto-connect value restored once [pendingCredentialRequirements] has been repaired. */
    val restoreAutoConnect: Boolean = false,
    /** Validated HTTPS icon advertised through IRCv3 draft/ICON; refreshed on registration. */
    val serverIconUrl: String? = null,
    /** Physical connection boundary. SIDECAR opens an approved Android provider instead of a socket. */
    val connectionTransport: ConnectionTransport = ConnectionTransport.NETWORK,
    /** Explicit provider service identity; set together for SIDECAR rows. */
    val sidecarPackage: String? = null,
    val sidecarService: String? = null,
    /** Provider-owned opaque account key. It is not a credential and is removed from backups. */
    val sidecarAccountId: String? = null,
) {
    // Redact secrets (saslPassword, serverPassword, nickServPassword, obfsLink) from logs; proxyHost/port are
    // non-sensitive so keep them out
    // too for brevity — the endpoint host:port is enough to identify the row.
    override fun toString() = "NetworkEntity(id=$id, name=$name, role=$role, host=$host:$port)"
}

/** Latest identity-related ISUPPORT values advertised by one network. */
@Entity(
    tableName = "network_identity",
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NetworkIdentityEntity(
    @PrimaryKey val networkId: Long,
    /** Null means CASEMAPPING was absent and the protocol default applies. */
    val caseMapping: String? = null,
    /** Null means absent/default; empty is an explicit empty CHANTYPES advertisement. */
    val chanTypes: String? = null,
    /** Last registered/current session nick; null means fall back to the configured network nick. */
    val selfNick: String? = null,
)

val NetworkIdentityEntity.identityRules: IrcIdentityRules
    get() = IrcIdentityRules.from(caseMapping, chanTypes)

@Entity(
    tableName = "network_ignores",
    indices = [
        Index(value = ["networkId", "pattern"], unique = true),
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
data class NetworkIgnoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val pattern: String,
    val enabled: Boolean = true,
    val createdAt: Long,
)

@Entity(
    tableName = "chat_folders",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class ChatFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val normalizedName: String,
    val iconKind: FolderIconKind = FolderIconKind.GENERIC,
    val iconKey: String = "folder",
    val ordering: Int = 0,
    val expanded: Boolean = true,
)

@Entity(
    tableName = "ignored_auto_group_patterns",
    primaryKeys = ["networkId", "normalizedPrefix"],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IgnoredAutoGroupPatternEntity(
    val networkId: Long,
    val normalizedPrefix: String,
)

@Entity(
    tableName = "pending_folder_assignments",
    primaryKeys = ["networkId", "chatType", "identityKind", "identityValue"],
    indices = [Index(value = ["folderId"])],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingFolderAssignmentEntity(
    val networkId: Long,
    val chatType: BufferType,
    val identityKind: FolderIdentityKind,
    val identityValue: String,
    val folderId: Long,
)

@Entity(
    tableName = "buffers",
    indices = [
        Index(value = ["networkId", "name"], unique = true),
        Index(value = ["redirectToRoomId"]),
        Index(value = ["folderId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class RoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val name: String, // case-normalized
    val displayName: String,
    val type: BufferType,
    val topic: String? = null,
    val topicSetBy: String? = null,
    val joined: Boolean = false,
    /** Incremented only by explicit self PART/KICK, so reconnect JOIN replay stays in one cycle. */
    val membershipCycle: Long = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** Hidden from the active chat list without altering membership, history, or read state. */
    val archived: Boolean = false,
    val ordering: Int = 0,
    val readMarkerTime: Long? = null, // last known remote draft/read-marker timestamp
    val localReadAnchorTime: Long? = null,
    val localReadAnchorEventId: TimelineEventId? = null,
    val localUnreadFloorTime: Long? = null, // local-only mute backlog floor; never synced
    val oldestFetchedTime: Long? = null, // CHATHISTORY paging bookkeeping
    val historyComplete: Boolean = false,
    /** QUERY removed from normal UI while its identity and reconnect cursor remain durable. */
    val dismissed: Boolean = false,
    /** Permanent lower bound preventing discarded query history from being imported again. */
    val historyDiscardedThroughMsgid: String? = null,
    val historyDiscardedThroughTime: Long? = null,
    /** Non-null while a CHANNEL leave/delete is waiting for server acceptance. */
    val pendingCloseAt: Long? = null,
    /** Losing room ids remain durable redirects so stale navigation/deep links keep working. */
    val redirectToRoomId: RoomId? = null,
    /** Null inherits the global message-layout preference for this durable conversation. */
    val layoutDensityOverride: LayoutDensity? = null,
    /** Null inherits the global presence-event preference for this durable conversation. */
    val presenceModeOverride: PresenceMode? = null,
    /** User-selected HTTPS URL or app-owned file URI. SERVER rooms always leave this null. */
    val avatarOverrideModel: String? = null,
    /** Local-only flat folder assignment. Pinned rows temporarily escape presentation only. */
    val folderId: Long? = null,
    /** Latest locally retained non-presence event for QUERY-only MONITOR ranking. */
    val monitorActivityTime: Long? = null,
    /** Stable server/provider target when [displayName] is presentation-only. */
    val wireTarget: String? = null,
    /** Current provider-reported policy; null for ordinary IRC and unknown provider state. */
    val sidecarSecurity: SidecarSecurityState? = null,
    /**
     * Newest activity CHATHISTORY TARGETS advertised for this room, or null when discovery has
     * never mentioned it. Written forward-only, by [io.github.trevarj.motd.data.sync.EventProcessor]
     * alone, from a completed TARGETS page.
     *
     * It exists so a catch-up pass can re-sort and badge the chat list from DISCOVERY, before any
     * per-room LATEST page lands — that first response already names every room whose server-side
     * tail moved, and waiting for the fan-out to reach each one is what made a reconnect feel slow.
     *
     * It is a high-water mark of what the SERVER last said, not a piece of unread state: the
     * derived "advertised but not yet fetched" cue extinguishes itself the moment the room's own
     * newest row or read anchor catches up with it, so ordinary convergence needs no clearing write
     * at all.
     *
     * It moves DOWN in exactly one case, and only from the response that forces it: a pass that has
     * proven the room is as current as CHATHISTORY will make it clamps the value onto the newest
     * row the room can actually show
     * ([io.github.trevarj.motd.data.sync.EventProcessor.clampAdvertisedActivity]). TARGETS
     * timestamps the newest SERVER event, which is routinely a JOIN or an event ingestion filters
     * away, and soju can index an event replay never returns; without that clamp the residue is a
     * permanent unread dot for a message that will never arrive.
     */
    val advertisedLatestTime: Long? = null,
)

/** Compatibility name retained while callers migrate to the canonical room vocabulary. */
typealias BufferEntity = RoomEntity

/** Exact server identities retained for discarded messages tied at an ambiguous history boundary. */
@Entity(
    tableName = "discarded_message_ids",
    primaryKeys = ["roomId", "msgid"],
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DiscardedMessageIdEntity(
    val roomId: RoomId,
    val msgid: String,
)

/** Internal room keys may be disambiguated; wire targets always retain server spelling. */
val RoomEntity.ircTarget: String
    get() = if (type == BufferType.SERVER) name else wireTarget ?: displayName

@Entity(
    tableName = "room_aliases",
    indices = [
        Index(value = ["networkId", "namespace", "value"], unique = true),
        Index(value = ["roomId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoomAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val namespace: RoomAliasNamespace,
    val value: String,
    val roomId: RoomId,
    val verified: Boolean = false,
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["bufferId", "serverTime", "id"]),
        Index(value = ["bufferId", "serverTime", "timelineOrder"]),
        Index(value = ["bufferId", "hasMention", "isSelf", "kind", "serverTime", "timelineOrder", "id"]),
        Index(value = ["replyToEventId"]),
        Index(value = ["bufferId", "msgid"]),
        Index(value = ["bufferId", "replyToMsgid", "replyToEventId"]),
        Index(value = ["bufferId", "pendingLabel"]),
        // Smart presence filtering asks "did this actor speak in this room just before the event";
        // without an actor-leading index that lookup degrades to a serverTime range scan per row.
        Index(value = ["bufferId", "normalizedActor", "serverTime"]),
        // The global feed orders every room's rows together. Every other index here is
        // bufferId-prefixed and cannot serve that scan, leaving it a full sort of the table.
        // (serverTime, id) only: timelineOrder is per-buffer and not comparable across buffers.
        Index(value = ["serverTime", "id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = BufferEntity::class,
            parentColumns = ["id"],
            childColumns = ["bufferId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TimelineEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long,
    val msgid: String? = null,
    val serverTime: Long,
    val sender: String,
    val normalizedActor: String = sender,
    val senderAccount: String? = null,
    val kind: MessageKind,
    /** Plain projection used by FTS, previews, notifications, accessibility, copy, and share. */
    val text: String,
    /** Exact IRC-formatted payload used for rendering, retries, and protocol identity. */
    val ircFormattedText: String? = null,
    val isSelf: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isBot: Boolean = false,
    val hasMention: Boolean = false, // computed at insert by EventProcessor
    val replyToMsgid: String? = null,
    val replyToEventId: TimelineEventId? = null,
    val channelContext: String? = null,
    val pendingLabel: String? = null, // set while awaiting echo; null once confirmed
    val failed: Boolean = false, // echo timeout -> retry UI
    /** Diagnostic compatibility value. Identity is enforced only by event_aliases. */
    val dedupKey: String,
    val eventKey: String? = null, // stable identity for INVITE/NETSPLIT/NETJOIN
    val eventPayload: String? = null, // versioned, defensively decoded typed-event payload
    val inviteState: InviteState? = null,
    val serverTimeAuthoritative: Boolean = true,
    /** Stable presentation tie-break independent of first local insertion id. */
    val timelineOrder: Long = id,
    /** Once a completed playback establishes relative order, later conflicts cannot oscillate it. */
    val timelineOrderConfirmed: Boolean = false,
    val timeProvenance: TimeProvenance =
        if (serverTimeAuthoritative) {
            TimeProvenance.SERVER_TAG
        } else {
            TimeProvenance.LOCAL_CLOCK
        },
    val notificationHandled: Boolean = false,
    /** Provider-reported protection for this exact row; null on ordinary IRC. */
    val sidecarSecurity: SidecarSecurityState? = null,
    /** Durable two-phase notification claim; reset on startup before database-backed recovery. */
    val notificationClaimed: Boolean = false,
    /** Process-session owner prevents startup recovery from releasing an active presentation. */
    val notificationClaimOwner: String? = null,
    val soundHandled: Boolean = false,
)

/** Compatibility name retained while presentation callers migrate to canonical event ids. */
typealias MessageEntity = TimelineEventEntity

@Entity(
    tableName = "dcc_transfers",
    indices = [
        Index(value = ["networkId", "offerKey"], unique = true),
        Index(value = ["networkId", "peerNick"]),
        Index(value = ["timelineEventId"]),
        Index(value = ["state", "updatedAt"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["timelineEventId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class DccTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val timelineEventId: TimelineEventId?,
    val offerKey: String,
    val direction: DccDirection,
    val protocol: DccTransferProtocol,
    val peerNick: String,
    val normalizedPeer: String,
    val filename: String,
    val displayFilename: String,
    val address: String,
    val addressKind: DccAddressKind,
    val port: Int,
    val sizeBytes: Long?,
    val token: String?,
    val state: DccTransferState,
    val bytesTransferred: Long = 0,
    val destinationUri: String? = null,
    val partialUri: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val expiresAt: Long?,
    val acceptedAt: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long,
)

@Fts4(contentEntity = TimelineEventEntity::class)
@Entity(tableName = "messages_fts")
data class TimelineEventFtsEntity(
    val text: String,
    val sender: String,
)

typealias MessageFtsEntity = TimelineEventFtsEntity

@Entity(
    tableName = "composer_drafts",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ComposerDraftEntity(
    @PrimaryKey val roomId: RoomId,
    val text: String,
    /** Canonical local id only. Deliberately not an FK so deleting history cannot erase a draft. */
    val replyToEventId: TimelineEventId? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "event_aliases",
    indices = [
        Index(value = ["networkId", "namespace", "value"], unique = true),
        Index(value = ["timelineEventId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["timelineEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val namespace: EventAliasNamespace,
    /** Exact binary identity. msgids remain case-sensitive and are never IRC-casefolded. */
    val value: ByteArray,
    val timelineEventId: TimelineEventId,
)

/** Durable replacement for a canonical id that lost a later coalescence race. */
@Entity(
    tableName = "event_redirects",
    indices = [Index(value = ["canonicalEventId"])],
    foreignKeys = [
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonicalEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventRedirectEntity(
    @PrimaryKey val losingEventId: TimelineEventId,
    val canonicalEventId: TimelineEventId,
)

@Entity(
    tableName = "event_observations",
    indices = [
        Index(value = ["timelineEventId"]),
        Index(value = ["networkId", "receiveOrder"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["timelineEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val timelineEventId: TimelineEventId,
    val origin: ObservationOrigin,
    val connectionGeneration: Long?,
    val receiveOrder: Long,
    val batchId: String?,
    val timeProvenance: TimeProvenance,
    val semanticFingerprint: ByteArray,
    val batchExactOrdinal: Int?,
    val observedAt: Long,
)

@Entity(
    tableName = "history_cursors",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HistoryCursorEntity(
    @PrimaryKey val roomId: RoomId,
    val newestMsgid: String? = null,
    val newestServerTime: Long? = null,
    val oldestMsgid: String? = null,
    val oldestServerTime: Long? = null,
    val historyComplete: Boolean = false,
)

/**
 * An interval of retained server history that is not present locally. Both boundaries are known
 * messages surrounding the missing interval; msgid is preferred for protocol paging while time is
 * always retained for local ordering and timestamp-only servers.
 */
@Entity(
    tableName = "history_gaps",
    indices = [
        Index(value = ["roomId", "olderServerTime"]),
        Index(value = ["roomId", "newerServerTime"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HistoryGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: RoomId,
    val olderMsgid: String?,
    val olderServerTime: Long,
    val newerMsgid: String?,
    val newerServerTime: Long,
    /** False when the server proved this missing interval is no longer page-recoverable. */
    @ColumnInfo(defaultValue = "1") val recoverable: Boolean = true,
    /** Exact local tuple for msgid-less/equal-time boundaries, when that row is still retained. */
    val olderEventId: Long? = null,
    val olderTimelineOrder: Long? = null,
    val newerEventId: Long? = null,
    val newerTimelineOrder: Long? = null,
)

/** Whole-network discovery watermark, colocated with the canonical room/history graph. */
@Entity(
    tableName = "network_history_cursors",
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NetworkHistoryCursorEntity(
    @PrimaryKey val networkId: Long,
    val lastSuccessfulSync: Long,
    /** False for quarantined v10 device-clock watermarks; true only for proven server boundaries. */
    @ColumnInfo(defaultValue = "0") val serverDerived: Boolean = false,
)

/**
 * Resume cursor for the paced background TARGETS backfill: the interval [epoch, upperBound) has
 * not been enumerated yet. Independent of [NetworkHistoryCursorEntity] so the reconnect watermark's
 * INSERT OR REPLACE can never clobber backfill progress.
 */
@Entity(
    tableName = "history_backfill_cursors",
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HistoryBackfillCursorEntity(
    @PrimaryKey val networkId: Long,
    /** Exclusive newest bound of the not-yet-enumerated interval; only ever walks toward epoch. */
    val upperBound: Long,
    @ColumnInfo(defaultValue = "0") val complete: Boolean = false,
)

/** Monotonic process-independent connection identity used to scope outgoing label aliases. */
@Entity(
    tableName = "connection_generations",
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConnectionGenerationEntity(
    @PrimaryKey val networkId: Long,
    val generation: Long,
)

/** One-shot app transition markers; fresh databases intentionally start with no rows. */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val key: String,
)

@Entity(
    tableName = "reactions",
    indices = [
        Index(value = ["bufferId", "targetMsgid", "actorKey", "emoji"], unique = true),
        Index(value = ["bufferId", "targetMsgid", "targetEventId"]),
    ],
)
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long,
    val targetMsgid: String,
    /** Account identity when advertised, otherwise an IRC-casemapped nick identity. */
    val actorKey: String,
    /** Display spelling retained independently from [actorKey]. */
    val sender: String,
    val emoji: String,
    val serverTime: Long,
    val targetEventId: TimelineEventId? = null,
)

@Entity(tableName = "users", primaryKeys = ["networkId", "nick"])
data class UserEntity(
    val networkId: Long,
    val nick: String,
    val username: String? = null,
    val account: String? = null,
    val away: Boolean = false,
    val hostmask: String? = null,
    val realname: String? = null,
    @ColumnInfo(defaultValue = "0") val isBot: Boolean = false,
)

@Entity(tableName = "members", primaryKeys = ["bufferId", "nick"])
data class MemberEntity(
    val bufferId: Long,
    val nick: String,
    val prefixes: String = "",
    @ColumnInfo(defaultValue = "0") val isBot: Boolean = false,
)
