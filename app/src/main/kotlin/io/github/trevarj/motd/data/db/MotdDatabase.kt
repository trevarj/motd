package io.github.trevarj.motd.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Single app database. Destructive fallback is deliberately NOT configured (see plans/04) so schema
// drift surfaces in review instead of wiping real user data/history; schema changes ship a proper
// Migration instead.
@Database(
    entities = [
        NetworkEntity::class,
        NetworkIdentityEntity::class,
        RoomEntity::class,
        DiscardedMessageIdEntity::class,
        RoomAliasEntity::class,
        TimelineEventEntity::class,
        TimelineEventFtsEntity::class,
        ComposerDraftEntity::class,
        EventAliasEntity::class,
        EventRedirectEntity::class,
        EventObservationEntity::class,
        HistoryCursorEntity::class,
        NetworkHistoryCursorEntity::class,
        ConnectionGenerationEntity::class,
        AppStateEntity::class,
        ReactionEntity::class,
        UserEntity::class,
        MemberEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MotdDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao
    abstract fun networkIdentityDao(): NetworkIdentityDao
    abstract fun bufferDao(): BufferDao
    abstract fun messageDao(): MessageDao
    abstract fun composerDraftDao(): ComposerDraftDao
    abstract fun memberDao(): MemberDao
    abstract fun reactionDao(): ReactionDao
    abstract fun userDao(): UserDao
    abstract fun canonicalTimelineDao(): CanonicalTimelineDao
    abstract fun roomAliasDao(): RoomAliasDao
    abstract fun historyCursorDao(): HistoryCursorDao
    abstract fun connectionGenerationDao(): ConnectionGenerationDao
    abstract fun appStateDao(): AppStateDao
}

/**
 * v1 -> v2: add the nullable `wsUrl` column on `networks` for the opt-in IRC-over-WebSocket
 * transport (plans/19 §3.3). Non-destructive additive change: TEXT, nullable, no default, so all
 * existing rows keep `wsUrl = NULL` (TCP/TLS) and every message/buffer/history row is preserved.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE networks ADD COLUMN wsUrl TEXT")
    }
}

/**
 * v2 -> v3: add the UNIQUE(bufferId, msgid) index on `messages` so a self message can never surface
 * twice (the durable msgid becomes the dedup identity, goguma-style). Non-destructive: first collapse
 * any pre-existing duplicate-msgid rows a buggy build may have written — keep the lowest row id per
 * (bufferId, msgid) and delete the rest (their content is identical) — then create the unique index.
 * NULL msgids are untouched: SQLite treats them as distinct, so still-pending / msgid-less rows keep
 * coexisting. Room's FTS sync triggers cascade the dropped rows out of messages_fts automatically.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """DELETE FROM messages WHERE msgid IS NOT NULL AND id NOT IN (
                 SELECT MIN(id) FROM messages WHERE msgid IS NOT NULL GROUP BY bufferId, msgid
               )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_bufferId_msgid ON messages(bufferId, msgid)",
        )
    }
}

/**
 * v3 -> v4: add the nullable SOCKS5/Tor transport settings on `networks`. Existing rows remain
 * direct connections until the user explicitly enables an obfuscation mode.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addNetworkColumnsIfMissing(
            "obfsMode" to "TEXT",
            "proxyHost" to "TEXT",
            "proxyPort" to "INTEGER",
        )
    }
}

/**
 * v4 -> v5: add the nullable VLESS+REALITY share link. The embedded core owns its loopback SOCKS
 * endpoint, so this deliberately remains separate from the user-editable proxy host and port.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // An unreleased development build used schema version 3 for this column. MIGRATION_3_4
        // leaves it intact when upgrading that database, so this must be safe in both paths.
        db.addNetworkColumnsIfMissing("obfsLink" to "TEXT")
    }
}

/**
 * v5 -> v6: add versioned typed-event persistence for invitations and collapsed network batches.
 * All columns are nullable so existing chat/history/FTS rows retain their exact semantics. The
 * partial-in-practice UNIQUE index relies on SQLite's distinct NULL handling: ordinary messages
 * remain unrestricted while a typed event key can occur only once per buffer.
 *
 * Downgrading a development database still follows DbModule's explicitly destructive dev-only
 * policy; released databases only move forward through this additive migration.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN eventKey TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN eventPayload TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN inviteState TEXT")
        db.execSQL("ALTER TABLE users ADD COLUMN username TEXT")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_bufferId_eventKey " +
                "ON messages(bufferId, eventKey)",
        )
    }
}

/**
 * v6 -> v7: add a nullable IRC server password. Existing connections continue without PASS;
 * the value is deliberately separate from SASL credentials because IRC servers may require both.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE networks ADD COLUMN serverPassword TEXT")
    }
}

/**
 * v7 -> v8: add a local-only unread floor for muted buffers. It lets unmute discard the locally
 * accumulated backlog without advancing the IRC/bouncer read marker stored in readMarkerTime.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE buffers ADD COLUMN localUnreadFloorTime INTEGER")
    }
}

/**
 * v8 -> v9: persist a local-only pending CHANNEL close. Nullable rows retain the existing
 * immediate-delete semantics for QUERY/SERVER buffers and channels that have already completed.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE buffers ADD COLUMN pendingCloseAt INTEGER")
    }
}

/**
 * v9 -> v10 intentionally resets all IRC-derived state while preserving the complete networks
 * table, including credentials and transport configuration. The old buffer/message identity model
 * cannot be migrated without carrying its ambiguities into the canonical graph, so rooms, events,
 * aliases, observations, cursors, reactions, members, and cached users start clean.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS messages_fts")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS reactions")
        db.execSQL("DROP TABLE IF EXISTS members")
        db.execSQL("DROP TABLE IF EXISTS users")
        db.execSQL("DROP TABLE IF EXISTS network_history_cursors")
        db.execSQL("DROP TABLE IF EXISTS connection_generations")
        db.execSQL("DROP TABLE IF EXISTS buffers")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `buffers` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `networkId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `topic` TEXT,
                `topicSetBy` TEXT,
                `joined` INTEGER NOT NULL,
                `membershipCycle` INTEGER NOT NULL,
                `pinned` INTEGER NOT NULL,
                `muted` INTEGER NOT NULL,
                `ordering` INTEGER NOT NULL,
                `readMarkerTime` INTEGER,
                `localUnreadFloorTime` INTEGER,
                `oldestFetchedTime` INTEGER,
                `historyComplete` INTEGER NOT NULL,
                `pendingCloseAt` INTEGER,
                `redirectToRoomId` INTEGER,
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_buffers_networkId_name` ON `buffers` (`networkId`, `name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_buffers_redirectToRoomId` ON `buffers` (`redirectToRoomId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `room_aliases` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `networkId` INTEGER NOT NULL,
                `namespace` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `roomId` INTEGER NOT NULL,
                `verified` INTEGER NOT NULL,
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`roomId`) REFERENCES `buffers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_room_aliases_networkId_namespace_value` ON `room_aliases` (`networkId`, `namespace`, `value`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_room_aliases_roomId` ON `room_aliases` (`roomId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bufferId` INTEGER NOT NULL,
                `msgid` TEXT,
                `serverTime` INTEGER NOT NULL,
                `sender` TEXT NOT NULL,
                `normalizedActor` TEXT NOT NULL,
                `senderAccount` TEXT,
                `kind` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `isSelf` INTEGER NOT NULL,
                `hasMention` INTEGER NOT NULL,
                `replyToMsgid` TEXT,
                `replyToEventId` INTEGER,
                `pendingLabel` TEXT,
                `failed` INTEGER NOT NULL,
                `dedupKey` TEXT NOT NULL,
                `eventKey` TEXT,
                `eventPayload` TEXT,
                `inviteState` TEXT,
                `serverTimeAuthoritative` INTEGER NOT NULL,
                `notificationHandled` INTEGER NOT NULL,
                `notificationClaimed` INTEGER NOT NULL,
                `notificationClaimOwner` TEXT,
                `soundHandled` INTEGER NOT NULL,
                FOREIGN KEY(`bufferId`) REFERENCES `buffers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_bufferId_serverTime_id` ON `messages` (`bufferId`, `serverTime`, `id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_replyToEventId` ON `messages` (`replyToEventId`)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`text` TEXT NOT NULL, `sender` TEXT NOT NULL, content=`messages`)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`id`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`id`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `text`, `sender`) VALUES (NEW.`id`, NEW.`text`, NEW.`sender`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `text`, `sender`) VALUES (NEW.`id`, NEW.`text`, NEW.`sender`); END")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `event_aliases` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `networkId` INTEGER NOT NULL,
                `namespace` TEXT NOT NULL,
                `value` BLOB NOT NULL,
                `timelineEventId` INTEGER NOT NULL,
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`timelineEventId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_event_aliases_networkId_namespace_value` ON `event_aliases` (`networkId`, `namespace`, `value`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_aliases_timelineEventId` ON `event_aliases` (`timelineEventId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `event_redirects` (
                `losingEventId` INTEGER NOT NULL,
                `canonicalEventId` INTEGER NOT NULL,
                PRIMARY KEY(`losingEventId`),
                FOREIGN KEY(`canonicalEventId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_redirects_canonicalEventId` ON `event_redirects` (`canonicalEventId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `event_observations` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `networkId` INTEGER NOT NULL,
                `timelineEventId` INTEGER NOT NULL,
                `origin` TEXT NOT NULL,
                `connectionGeneration` INTEGER,
                `receiveOrder` INTEGER NOT NULL,
                `batchId` TEXT,
                `timeProvenance` TEXT NOT NULL,
                `semanticFingerprint` BLOB NOT NULL,
                `batchExactOrdinal` INTEGER,
                `observedAt` INTEGER NOT NULL,
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`timelineEventId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_observations_timelineEventId` ON `event_observations` (`timelineEventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_observations_networkId_receiveOrder` ON `event_observations` (`networkId`, `receiveOrder`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `history_cursors` (
                `roomId` INTEGER NOT NULL,
                `newestMsgid` TEXT,
                `newestServerTime` INTEGER,
                `oldestMsgid` TEXT,
                `oldestServerTime` INTEGER,
                `historyComplete` INTEGER NOT NULL,
                PRIMARY KEY(`roomId`),
                FOREIGN KEY(`roomId`) REFERENCES `buffers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `network_history_cursors` (
                `networkId` INTEGER NOT NULL,
                `lastSuccessfulSync` INTEGER NOT NULL,
                PRIMARY KEY(`networkId`),
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `connection_generations` (
                `networkId` INTEGER NOT NULL,
                `generation` INTEGER NOT NULL,
                PRIMARY KEY(`networkId`),
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_state` (`key` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
        db.execSQL("INSERT OR REPLACE INTO `app_state`(`key`) VALUES ('v10_notification_reset')")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `reactions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bufferId` INTEGER NOT NULL,
                `targetMsgid` TEXT NOT NULL,
                `sender` TEXT NOT NULL,
                `emoji` TEXT NOT NULL,
                `serverTime` INTEGER NOT NULL,
                `targetEventId` INTEGER
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reactions_bufferId_targetMsgid_sender` ON `reactions` (`bufferId`, `targetMsgid`, `sender`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `users` (`networkId` INTEGER NOT NULL, `nick` TEXT NOT NULL, `username` TEXT, `account` TEXT, `away` INTEGER NOT NULL, `hostmask` TEXT, `realname` TEXT, PRIMARY KEY(`networkId`, `nick`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `members` (`bufferId` INTEGER NOT NULL, `nick` TEXT NOT NULL, `prefixes` TEXT NOT NULL, PRIMARY KEY(`bufferId`, `nick`))")
    }
}

/**
 * v10 -> v11 preserves timeline/history rows, quarantines device-clock network cursors, and resets
 * completion claims for protocol revalidation. Reaction storage is rebuilt because legacy actors
 * use the v10 RFC1459-folded nick and did not retain account tags. If folding causes a unique-key
 * collision, the lowest id keeps the canonical key and later rows receive deterministic suffixes.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE network_history_cursors ADD COLUMN " +
                "serverDerived INTEGER NOT NULL DEFAULT 0",
        )
        // v10 completion was inferred from response shape and must be proven again under v11.
        db.execSQL("UPDATE buffers SET historyComplete = 0")
        db.execSQL("UPDATE history_cursors SET historyComplete = 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_bufferId_msgid` " +
                "ON `messages` (`bufferId`, `msgid`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_bufferId_replyToMsgid_replyToEventId` " +
                "ON `messages` (`bufferId`, `replyToMsgid`, `replyToEventId`)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `reactions_v11` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bufferId` INTEGER NOT NULL,
                `targetMsgid` TEXT NOT NULL,
                `actorKey` TEXT NOT NULL,
                `sender` TEXT NOT NULL,
                `emoji` TEXT NOT NULL,
                `serverTime` INTEGER NOT NULL,
                `targetEventId` INTEGER
            )""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS
               `index_reactions_bufferId_targetMsgid_actorKey_emoji`
               ON `reactions_v11` (`bufferId`, `targetMsgid`, `actorKey`, `emoji`)""",
        )
        db.execSQL(
            """WITH migrated AS (
                   SELECT r.*,
                       'nick:' || ${legacyReactionNormalizedSender("r.sender")} AS migratedActorKey
                   FROM reactions r
               )
               INSERT OR IGNORE INTO reactions_v11(
                   id, bufferId, targetMsgid, actorKey, sender, emoji, serverTime, targetEventId
               )
               SELECT id, bufferId, targetMsgid, migratedActorKey, sender, emoji, serverTime,
                      targetEventId
               FROM migrated ORDER BY id""",
        )
        db.execSQL(
            """WITH migrated AS (
                   SELECT r.*,
                       'nick:' || ${legacyReactionNormalizedSender("r.sender")} AS migratedActorKey
                   FROM reactions r
               )
               INSERT INTO reactions_v11(
                   id, bufferId, targetMsgid, actorKey, sender, emoji, serverTime, targetEventId
               )
               SELECT id, bufferId, targetMsgid,
                      migratedActorKey || char(0) || 'legacy:' || id,
                      sender, emoji, serverTime, targetEventId
               FROM migrated
               WHERE NOT EXISTS (SELECT 1 FROM reactions_v11 n WHERE n.id = migrated.id)
               ORDER BY id""",
        )
        db.execSQL("DROP TABLE reactions")
        db.execSQL("ALTER TABLE reactions_v11 RENAME TO reactions")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reactions_bufferId_targetMsgid_targetEventId` " +
                "ON `reactions` (`bufferId`, `targetMsgid`, `targetEventId`)",
        )
    }
}

/**
 * v11 -> v12 adds durable composer state and separates exact local read position from the remote
 * IRC read marker. Existing marker values seed the nearest retained timeline tuple, preserving the
 * old unread floor without treating future local pending timestamps as valid MARKREAD values.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE buffers ADD COLUMN localReadAnchorTime INTEGER")
        db.execSQL("ALTER TABLE buffers ADD COLUMN localReadAnchorEventId INTEGER")
        db.execSQL(
            """CREATE TEMP TABLE migration_11_12_read_anchors(
                   bufferId INTEGER PRIMARY KEY NOT NULL,
                   serverTime INTEGER NOT NULL,
                   eventId INTEGER NOT NULL
               )""",
        )
        db.execSQL(
            """INSERT INTO migration_11_12_read_anchors(bufferId, serverTime, eventId)
               SELECT b.id, m.serverTime, m.id
               FROM buffers b
               JOIN messages m ON m.id = (
                   SELECT candidate.id FROM messages candidate
                   WHERE candidate.bufferId = b.id
                     AND candidate.serverTimeAuthoritative = 1
                     AND candidate.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
                     AND candidate.serverTime <= b.readMarkerTime
                   ORDER BY candidate.serverTime DESC, candidate.id DESC LIMIT 1
               )
               WHERE b.readMarkerTime IS NOT NULL""",
        )
        db.execSQL(
            """UPDATE buffers SET
                   readMarkerTime = (
                       SELECT serverTime FROM migration_11_12_read_anchors
                       WHERE bufferId = buffers.id
                   ),
                   localReadAnchorTime = (
                       SELECT serverTime FROM migration_11_12_read_anchors
                       WHERE bufferId = buffers.id
                   ),
                   localReadAnchorEventId = (
                       SELECT eventId FROM migration_11_12_read_anchors
                       WHERE bufferId = buffers.id
                   )
               WHERE readMarkerTime IS NOT NULL""",
        )
        db.execSQL("DROP TABLE migration_11_12_read_anchors")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `composer_drafts` (
                `roomId` INTEGER NOT NULL,
                `text` TEXT NOT NULL,
                `replyToEventId` INTEGER,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`roomId`),
                FOREIGN KEY(`roomId`) REFERENCES `buffers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_bufferId_pendingLabel` " +
                "ON `messages` (`bufferId`, `pendingLabel`)",
        )
        // v11 scoped LABEL aliases as "generation<NUL>label". Preserve raw aliases for rows that
        // can still receive an echo after upgrade; v12 labels are globally unique opaque values.
        db.execSQL(
            """INSERT OR IGNORE INTO event_aliases(networkId, namespace, value, timelineEventId)
               SELECT b.networkId, 'LABEL', CAST(m.pendingLabel AS BLOB), m.id
               FROM messages m JOIN buffers b ON b.id = m.bufferId
               WHERE m.pendingLabel IS NOT NULL AND m.msgid IS NULL AND m.failed = 0""",
        )
    }
}

/** v12 -> v13 persists identity-related ISUPPORT and the current session nick. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `network_identity` (
                `networkId` INTEGER NOT NULL,
                `caseMapping` TEXT,
                `chanTypes` TEXT,
                `selfNick` TEXT,
                PRIMARY KEY(`networkId`),
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
    }
}

/**
 * v13 -> v14 adds a local dismissed-query shell, an immutable discarded-history boundary, and
 * exact msgid tombstones for ambiguous timestamp ties. Existing rooms and timeline rows survive.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE buffers ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE buffers ADD COLUMN historyDiscardedThroughMsgid TEXT")
        db.execSQL("ALTER TABLE buffers ADD COLUMN historyDiscardedThroughTime INTEGER")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `discarded_message_ids` (
                `roomId` INTEGER NOT NULL,
                `msgid` TEXT NOT NULL,
                PRIMARY KEY(`roomId`, `msgid`),
                FOREIGN KEY(`roomId`) REFERENCES `buffers`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
    }
}

private fun legacyReactionNormalizedSender(column: String): String =
    "replace(replace(replace(replace(lower($column), '[', '{'), ']', '}'), '\\', '|'), '~', '^')"

private fun SupportSQLiteDatabase.addNetworkColumnsIfMissing(vararg columns: Pair<String, String>) {
    val existing = buildSet {
        query("PRAGMA table_info(`networks`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameColumn))
        }
    }
    columns.forEach { (name, type) ->
        if (name !in existing) execSQL("ALTER TABLE networks ADD COLUMN $name $type")
    }
}
