package io.github.trevarj.motd.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Single app database. Destructive fallback is deliberately NOT configured so schema
// drift surfaces in review instead of wiping real user data/history; schema changes ship a proper
// Migration instead.
@Database(
    entities = [
        NetworkEntity::class,
        NetworkIdentityEntity::class,
        NetworkIgnoreEntity::class,
        ChatFolderEntity::class,
        IgnoredAutoGroupPatternEntity::class,
        PendingFolderAssignmentEntity::class,
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
        HistoryGapEntity::class,
        NetworkHistoryCursorEntity::class,
        HistoryBackfillCursorEntity::class,
        ConnectionGenerationEntity::class,
        AppStateEntity::class,
        ReactionEntity::class,
        UserEntity::class,
        MemberEntity::class,
        DccTransferEntity::class,
    ],
    version = 39,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MotdDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao

    abstract fun networkIdentityDao(): NetworkIdentityDao

    abstract fun networkIgnoreDao(): NetworkIgnoreDao

    abstract fun chatFolderDao(): ChatFolderDao

    abstract fun bufferDao(): BufferDao

    abstract fun messageDao(): MessageDao

    abstract fun composerDraftDao(): ComposerDraftDao

    abstract fun memberDao(): MemberDao

    abstract fun reactionDao(): ReactionDao

    abstract fun userDao(): UserDao

    abstract fun dccTransferDao(): DccTransferDao

    abstract fun canonicalTimelineDao(): CanonicalTimelineDao

    abstract fun roomAliasDao(): RoomAliasDao

    abstract fun historyCursorDao(): HistoryCursorDao

    abstract fun historyBackfillCursorDao(): HistoryBackfillCursorDao

    abstract fun historyGapDao(): HistoryGapDao

    abstract fun connectionGenerationDao(): ConnectionGenerationDao

    abstract fun appStateDao(): AppStateDao
}

/**
 * v1 -> v2: add the nullable `wsUrl` column on `networks` for the opt-in IRC-over-WebSocket
 * transport. Non-destructive additive change: TEXT, nullable, no default, so all
 * existing rows keep `wsUrl = NULL` (TCP/TLS) and every message/buffer/history row is preserved.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
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
val MIGRATION_2_3 =
    object : Migration(2, 3) {
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
val MIGRATION_3_4 =
    object : Migration(3, 4) {
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
val MIGRATION_4_5 =
    object : Migration(4, 5) {
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
val MIGRATION_5_6 =
    object : Migration(5, 6) {
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
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE networks ADD COLUMN serverPassword TEXT")
        }
    }

/**
 * v7 -> v8: add a local-only unread floor for muted buffers. It lets unmute discard the locally
 * accumulated backlog without advancing the IRC/bouncer read marker stored in readMarkerTime.
 */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buffers ADD COLUMN localUnreadFloorTime INTEGER")
        }
    }

/**
 * v8 -> v9: persist a local-only pending CHANNEL close. Nullable rows retain the existing
 * immediate-delete semantics for QUERY/SERVER buffers and channels that have already completed.
 */
val MIGRATION_8_9 =
    object : Migration(8, 9) {
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
val MIGRATION_9_10 =
    object : Migration(9, 10) {
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
val MIGRATION_10_11 =
    object : Migration(10, 11) {
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
val MIGRATION_11_12 =
    object : Migration(11, 12) {
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
val MIGRATION_12_13 =
    object : Migration(12, 13) {
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
val MIGRATION_13_14 =
    object : Migration(13, 14) {
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

/** v14 -> v15: nullable per-conversation message-layout override; null inherits global layout. */
val MIGRATION_14_15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buffers ADD COLUMN layoutDensityOverride TEXT")
        }
    }

/** v15 -> v16: archive is a non-destructive local chat-list visibility flag. */
val MIGRATION_15_16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buffers ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        }
    }

/** v16 -> v17: nullable per-network initial away message for IRCv3 pre-away or post-001 fallback. */
val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE networks ADD COLUMN initialAwayMessage TEXT")
        }
    }

/**
 * v17 -> v18 records unresolved credentials after importing a credentials-excluded configuration.
 * The connection layer already observes `autoConnect`; imports set it false until the user repairs
 * the missing credential fields, then restore the saved desired value.
 */
val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE networks ADD COLUMN pendingCredentialRequirements TEXT")
            db.execSQL("ALTER TABLE networks ADD COLUMN restoreAutoConnect INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * v18 -> v19: add per-network ignore masks. Existing networks start with no ignores; deletes cascade
 * so removing a network also removes its local privacy rules.
 */
val MIGRATION_18_19 =
    object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `network_ignores` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `networkId` INTEGER NOT NULL,
                `pattern` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_network_ignores_networkId_pattern` " +
                    "ON `network_ignores` (`networkId`, `pattern`)",
            )
        }
    }

/**
 * v19 -> v20 adds durable DCC transfer and direct-chat session state. Timeline rows still own the
 * user-visible history; these tables track offer/session progress and retain history after a row is
 * deleted.
 */
val MIGRATION_19_20 =
    object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `dcc_transfers` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `networkId` INTEGER NOT NULL,
                `timelineEventId` INTEGER,
                `offerKey` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `protocol` TEXT NOT NULL,
                `peerNick` TEXT NOT NULL,
                `normalizedPeer` TEXT NOT NULL,
                `filename` TEXT NOT NULL,
                `displayFilename` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `addressKind` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `sizeBytes` INTEGER,
                `token` TEXT,
                `state` TEXT NOT NULL,
                `bytesTransferred` INTEGER NOT NULL,
                `destinationUri` TEXT,
                `partialUri` TEXT,
                `error` TEXT,
                `createdAt` INTEGER NOT NULL,
                `expiresAt` INTEGER,
                `acceptedAt` INTEGER,
                `completedAt` INTEGER,
                `updatedAt` INTEGER NOT NULL,
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`timelineEventId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )""",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_dcc_transfers_networkId_offerKey` " +
                    "ON `dcc_transfers` (`networkId`, `offerKey`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dcc_transfers_networkId_peerNick` ON `dcc_transfers` (`networkId`, `peerNick`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dcc_transfers_timelineEventId` ON `dcc_transfers` (`timelineEventId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dcc_transfers_state_updatedAt` ON `dcc_transfers` (`state`, `updatedAt`)")
        }
    }

/**
 * v20 -> v21 separates stable timeline ordering and temporal provenance from the local primary
 * key. Existing rows retain their exact visible order; completed playback can subsequently
 * reconcile provisional equal-time ordering without rewriting canonical event ids.
 */
val MIGRATION_20_21 =
    object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN timelineOrder INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN timelineOrderConfirmed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN timeProvenance TEXT NOT NULL DEFAULT 'LOCAL_CLOCK'")
            db.execSQL("UPDATE messages SET timelineOrder = id")
            db.execSQL(
                "UPDATE messages SET timeProvenance = CASE " +
                    "WHEN serverTimeAuthoritative = 1 THEN 'SERVER_TAG' ELSE 'LOCAL_CLOCK' END",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messages_bufferId_serverTime_timelineOrder " +
                    "ON messages(bufferId, serverTime, timelineOrder)",
            )
        }
    }

/**
 * v21 -> v22 records unresolved intervals between independently fetched history windows. Existing
 * v21 timelines were published as complete extents, so migration creates no speculative gaps.
 */
val MIGRATION_21_22 =
    object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `history_gaps` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `roomId` INTEGER NOT NULL,
                `olderMsgid` TEXT,
                `olderServerTime` INTEGER NOT NULL,
                `newerMsgid` TEXT,
                `newerServerTime` INTEGER NOT NULL,
                `recoverable` INTEGER NOT NULL DEFAULT 1,
                `olderEventId` INTEGER,
                `olderTimelineOrder` INTEGER,
                `newerEventId` INTEGER,
                `newerTimelineOrder` INTEGER,
                FOREIGN KEY(`roomId`) REFERENCES `buffers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_gaps_roomId_olderServerTime` " +
                    "ON `history_gaps` (`roomId`, `olderServerTime`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_gaps_roomId_newerServerTime` " +
                    "ON `history_gaps` (`roomId`, `newerServerTime`)",
            )
        }
    }

/**
 * v22 -> v23 repairs legacy `recoverable = 0` poison on `history_gaps`. Builds before commit
 * 7f27e550 misused the flag: a saturated timestamp-only CHATHISTORY page (no older msgid) wrongly
 * set recoverable = 0, permanently clamping the timeline so scrolling to the top of that buffer
 * fetched nothing. The new semantics reserve recoverable = 0 for server-proven-empty intervals
 * only, but a poisoned legacy row is indistinguishable from a genuinely empty one, so this
 * data-only migration lifts every unrecoverable gap back to recoverable = 1. This is safe and
 * self-healing: re-probing a truly empty interval fetches 0 rows, proves it empty, and re-marks it
 * unrecoverable under the new rule, while a wrongly-poisoned interval is finally allowed to page.
 */
val MIGRATION_22_23 =
    object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE history_gaps SET recoverable = 1 WHERE recoverable = 0")
        }
    }

/**
 * v23 -> v24 turns the drawer's network order into stored, user-owned state. `networks.ordering`
 * has existed since v1 and `NetworkDao` always sorted by it, but nothing ever assigned a value: every
 * row stayed at 0, so the visible order was SQLite's arbitrary resolution of an all-ties sort (in
 * practice rowid order). Manual reordering needs real values, so this data-only migration freezes the
 * order each existing database already displays — `(ordering, id)` ascending — into distinct
 * sequential positions. Nothing is deleted and no row moves: after the migration `ORDER BY ordering,
 * id` reproduces the pre-migration list exactly, and a user who never reorders sees no change.
 */
val MIGRATION_23_24 =
    object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Rank into a temp table first. A correlated UPDATE reading the table it rewrites would
            // count against its own partially applied writes and produce duplicate positions.
            db.execSQL(
                """CREATE TEMP TABLE migration_23_24_network_order(
                   id INTEGER PRIMARY KEY NOT NULL,
                   position INTEGER NOT NULL
               )""",
            )
            db.execSQL(
                """INSERT INTO migration_23_24_network_order(id, position)
               SELECT n.id, (
                   SELECT COUNT(*) FROM networks other
                   WHERE other.ordering < n.ordering
                      OR (other.ordering = n.ordering AND other.id < n.id)
               ) FROM networks n""",
            )
            // Every networks row has exactly one ranked entry, so no row can be left with a NULL.
            db.execSQL(
                """UPDATE networks SET ordering = (
                   SELECT position FROM migration_23_24_network_order WHERE id = networks.id
               )""",
            )
            db.execSQL("DROP TABLE migration_23_24_network_order")
        }
    }

/**
 * v24 -> v25: add the durable resume cursor for the paced background TARGETS backfill (initial
 * sync now enumerates only a recent window; the remainder trickles in behind this cursor).
 * Additive table; existing networks without a row simply have no backfill scheduled until their
 * first post-upgrade initial sync seeds one.
 */
val MIGRATION_24_25 =
    object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `history_backfill_cursors` (
                   `networkId` INTEGER NOT NULL,
                   `upperBound` INTEGER NOT NULL,
                   `complete` INTEGER NOT NULL DEFAULT 0,
                   PRIMARY KEY(`networkId`),
                   FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`)
                       ON UPDATE NO ACTION ON DELETE CASCADE
               )""",
            )
        }
    }

/**
 * v25 -> v26: nullable per-conversation presence-event override (null inherits the global mode),
 * plus the actor-leading message index the smart presence filter seeks on.
 */
val MIGRATION_25_26 =
    object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buffers ADD COLUMN presenceModeOverride TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_bufferId_normalizedActor_serverTime` " +
                    "ON `messages` (`bufferId`, `normalizedActor`, `serverTime`)",
            )
        }
    }

/**
 * v26 -> v27 keeps the schema and clears start-of-history claims so they are proven again.
 *
 * Through v26 an unbounded CHATHISTORY LATEST that delivered nothing persisted completion, which is
 * a fact about what the target could serve at that instant rather than about where the room's
 * history begins — and completion is durable, so such a room could never page older again once the
 * backlog arrived. A wiped store hit it in every room at once, since every room seeds from empty.
 * The claim is cheap to re-earn: the next time a reader reaches the bottom of a genuinely complete
 * room, one BEFORE comes back empty and marks it again.
 */
val MIGRATION_26_27 =
    object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE buffers SET historyComplete = 0")
            db.execSQL("UPDATE history_cursors SET historyComplete = 0")
        }
    }

/**
 * v27 -> v28 adds `buffers.advertisedLatestTime`, the newest activity CHATHISTORY TARGETS has
 * advertised for a room.
 *
 * Purely additive and nullable, so every existing row keeps NULL and behaves exactly as it did:
 * NULL contributes nothing to the chat-list activity sort and raises no advertised-unread cue. The
 * first discovery pass after the upgrade fills in whatever the server currently advertises.
 */
val MIGRATION_27_28 =
    object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buffers ADD COLUMN advertisedLatestTime INTEGER")
        }
    }

/** v28 -> v29 adds optional NickServ fallback settings; existing networks remain disabled. */
val MIGRATION_28_29 =
    object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE networks ADD COLUMN nickServPassword TEXT")
            db.execSQL("ALTER TABLE networks ADD COLUMN nickServIdentifySyntax TEXT")
            db.execSQL(
                "ALTER TABLE networks ADD COLUMN nickServRecoveryEnabled INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE networks ADD COLUMN nickServRecoverySequence TEXT")
        }
    }

/** v29 -> v30 adds local per-conversation avatars; existing rooms inherit generated/shared avatars. */
val MIGRATION_29_30 =
    object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buffers ADD COLUMN avatarOverrideModel TEXT")
        }
    }

/** v30 -> v31 stores exact IRC formatting beside each row's searchable plain projection. */
val MIGRATION_30_31 =
    object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN ircFormattedText TEXT")
        }
    }

/** v31 -> v32 materializes QUERY activity for MONITOR and indexes capped mention scans. */
val MIGRATION_31_32 =
    object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buffers ADD COLUMN monitorActivityTime INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "index_messages_bufferId_hasMention_isSelf_kind_serverTime_timelineOrder_id " +
                    "ON messages(bufferId, hasMention, isSelf, kind, serverTime, timelineOrder, id)",
            )
            db.execSQL(
                """UPDATE buffers SET monitorActivityTime = (
                   SELECT m.serverTime FROM messages m
                   WHERE m.bufferId = buffers.id
                     AND m.kind NOT IN ('JOIN', 'PART', 'QUIT', 'NETSPLIT', 'NETJOIN')
                   ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1
               ) WHERE type = 'QUERY'""",
            )
        }
    }

/** v32 -> v33 persists IRCv3 bot status for messages, cached users, and channel members. */
val MIGRATION_32_33 =
    object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN isBot INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE users ADD COLUMN isBot INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE members ADD COLUMN isBot INTEGER NOT NULL DEFAULT 0")
        }
    }

/** v33 -> v34 retains outgoing channel-context tags across durable retries. */
val MIGRATION_33_34 =
    object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN channelContext TEXT")
        }
    }

/** v34 -> v35 stores the latest validated server-advertised network icon URL. */
val MIGRATION_34_35 =
    object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE networks ADD COLUMN serverIconUrl TEXT")
        }
    }

/**
 * v35 -> v36 indexes the cross-buffer scan the global feed reads and repairs soju console rows older
 * builds stored as queries. "bouncerserv" has no RFC1459-foldable character, so `lower()` suffices.
 * Bouncer roots only: on any other network that nick is an ordinary user whose DM must stay a query.
 */
val MIGRATION_35_36 =
    object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_serverTime_timelineOrder_id` " +
                    "ON `messages` (`serverTime`, `timelineOrder`, `id`)",
            )
            db.execSQL(
                "UPDATE buffers SET type = 'SERVER' " +
                    "WHERE lower(name) = 'bouncerserv' AND type = 'QUERY' " +
                    "AND networkId IN (SELECT id FROM networks WHERE role = 'BOUNCER_ROOT')",
            )
        }
    }

/**
 * v36 -> v37 re-keys the cross-buffer ordering index on `(serverTime, id)`.
 *
 * `timelineOrder` is only meaningful within one buffer: playback settle rewrites dense 0,1,2…
 * indexes per (bufferId, serverTime) while every other writer stores the rowid, so across buffers
 * the two scales are incomparable and same-second ties sorted settled history below live rows.
 * The global feed now orders by `(serverTime, id)` and this is the index that walk needs.
 */
val MIGRATION_36_37 =
    object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_messages_serverTime_timelineOrder_id`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_serverTime_id` " +
                    "ON `messages` (`serverTime`, `id`)",
            )
        }
    }

/** v37 -> v38 adds local flat chat folders, ignored Auto-group prefixes, and deferred restore intent. */
val MIGRATION_37_38 =
    object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `chat_folders` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `displayName` TEXT NOT NULL,
                `normalizedName` TEXT NOT NULL,
                `iconKind` TEXT NOT NULL,
                `iconKey` TEXT NOT NULL,
                `ordering` INTEGER NOT NULL,
                `expanded` INTEGER NOT NULL
            )""",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_folders_normalizedName` ON `chat_folders` (`normalizedName`)")
            db.execSQL("ALTER TABLE buffers ADD COLUMN folderId INTEGER REFERENCES chat_folders(id) ON DELETE SET NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_buffers_folderId` ON `buffers` (`folderId`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `ignored_auto_group_patterns` (
                `networkId` INTEGER NOT NULL,
                `normalizedPrefix` TEXT NOT NULL,
                PRIMARY KEY(`networkId`, `normalizedPrefix`),
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `pending_folder_assignments` (
                `networkId` INTEGER NOT NULL,
                `chatType` TEXT NOT NULL,
                `identityKind` TEXT NOT NULL,
                `identityValue` TEXT NOT NULL,
                `folderId` INTEGER NOT NULL,
                PRIMARY KEY(`networkId`, `chatType`, `identityKind`, `identityValue`),
                FOREIGN KEY(`networkId`) REFERENCES `networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`folderId`) REFERENCES `chat_folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_folder_assignments_folderId` ON `pending_folder_assignments` (`folderId`)")
        }
    }

/** v38 -> v39 adds an additive Android companion transport and presentation-safe target metadata. */
val MIGRATION_38_39 =
    object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE networks ADD COLUMN connectionTransport TEXT NOT NULL DEFAULT 'NETWORK'")
            db.execSQL("ALTER TABLE networks ADD COLUMN sidecarPackage TEXT")
            db.execSQL("ALTER TABLE networks ADD COLUMN sidecarService TEXT")
            db.execSQL("ALTER TABLE networks ADD COLUMN sidecarAccountId TEXT")
            db.execSQL("ALTER TABLE buffers ADD COLUMN wireTarget TEXT")
            db.execSQL("ALTER TABLE buffers ADD COLUMN sidecarSecurity TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN sidecarSecurity TEXT")
        }
    }

/**
 * The complete registered upgrade path, single-sourced so the runtime builder (DbModule) and the
 * migration tests cannot drift apart.
 *
 * The dangerous drift runs one way: a migration added here and covered by a test but left out of the
 * builder ships a crash-on-upgrade behind a fully green suite, because the tests supply their own
 * array. Both sides now read this one.
 *
 * Declared after every `MIGRATION_*` val on purpose — top-level properties initialize in file order,
 * so an earlier declaration would capture nulls.
 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
    )

private fun legacyReactionNormalizedSender(column: String): String = "replace(replace(replace(replace(lower($column), '[', '{'), ']', '}'), '\\', '|'), '~', '^')"

private fun SupportSQLiteDatabase.addNetworkColumnsIfMissing(vararg columns: Pair<String, String>) {
    val existing =
        buildSet {
            query("PRAGMA table_info(`networks`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }
    columns.forEach { (name, type) ->
        if (name !in existing) execSQL("ALTER TABLE networks ADD COLUMN $name $type")
    }
}
