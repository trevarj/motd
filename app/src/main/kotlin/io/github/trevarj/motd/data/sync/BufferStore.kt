package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.bouncer.isBouncerConsole
import io.github.trevarj.motd.data.db.AppStateEntity
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.CHAT_WALLPAPER_EDIT_PREFIX
import io.github.trevarj.motd.data.db.FolderIdentityKind
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.PENDING_CHAT_PLACEMENT_PREFIX
import io.github.trevarj.motd.data.db.PENDING_CHAT_WALLPAPER_PREFIX
import io.github.trevarj.motd.data.db.RoomAliasEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.RoomEntity
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.WallpaperChatChoice
import io.github.trevarj.motd.data.prefs.WallpaperLibrary
import io.github.trevarj.motd.data.prefs.WallpaperPatternRef
import io.github.trevarj.motd.data.prefs.WallpaperPatternRefKind
import io.github.trevarj.motd.data.prefs.decodeWallpaperChatChoice
import io.github.trevarj.motd.data.prefs.decodeWallpaperValue
import io.github.trevarj.motd.data.prefs.isCanonicalWallpaperUuid
import io.github.trevarj.motd.data.prefs.isValidIn
import io.github.trevarj.motd.data.prefs.wallpaperJson
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.repo.MAX_CHAT_WALLPAPER_ASSIGNMENTS
import io.github.trevarj.motd.data.repo.PortableChatPlacement
import io.github.trevarj.motd.data.repo.PortableChatWallpaper
import io.github.trevarj.motd.data.repo.isPortableChatIdentity
import io.github.trevarj.motd.data.repo.validatePortableChatIdentity
import io.github.trevarj.motd.service.NotificationRoomMergeListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Atomic buffer creation shared by event persistence and user-driven buffer creation. */
@Singleton
class BufferStore
    @Inject
    internal constructor(
        private val db: MotdDatabase,
        private val notifier: MessageNotifier = MessageNotifier.Noop,
        private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
        private val chatFolders: ChatFolderRepository = ChatFolderRepository(db),
        private val notificationRoomMergeListener: NotificationRoomMergeListener = NotificationRoomMergeListener.Noop,
    ) {
        private val identityEncoder = Base64.getUrlEncoder().withoutPadding()

        private fun pendingIdentityPrefix(
            networkId: Long,
            chatType: BufferType,
            identityKind: FolderIdentityKind,
            identityValue: String,
            keyPrefix: String = PENDING_CHAT_PLACEMENT_PREFIX,
        ): String {
            val encodedValue = identityEncoder.encodeToString(identityValue.encodeToByteArray())
            return "$keyPrefix$networkId:${chatType.name}:${identityKind.name}:$encodedValue:"
        }

        private fun pendingKey(placement: PortableChatPlacement): String =
            pendingIdentityPrefix(placement.networkId, placement.chatType, placement.identityKind, placement.identityValue) +
                Json.encodeToString(placement)

        private fun pendingPayload(key: String): String = key.substring(key.indexOf('{', PENDING_CHAT_PLACEMENT_PREFIX.length))

        private fun decodePending(key: String): PortableChatPlacement = Json.decodeFromString(pendingPayload(key))

        private suspend fun pending() = db.appStateDao().keysGlob("$PENDING_CHAT_PLACEMENT_PREFIX*").map { key -> key to decodePending(key) }

        private suspend fun nextRevision(keyPrefix: String = "chat-placement-revision"): Long {
            val keys = db.appStateDao().keysLike("$keyPrefix:%")
            val revision = (keys.maxOfOrNull { it.substringAfterLast(':').toLong() } ?: 0L) + 1L
            keys.forEach { db.appStateDao().delete(it) }
            db.appStateDao().insert(AppStateEntity("$keyPrefix:$revision"))
            return revision
        }

        private suspend fun recordEdit(
            roomId: Long,
            revision: Long,
            keyPrefix: String = "chat-placement-edit",
        ) {
            db.appStateDao().keysLike("$keyPrefix:$roomId:%").forEach { db.appStateDao().delete(it) }
            db.appStateDao().insert(AppStateEntity("$keyPrefix:$roomId:$revision"))
        }

        private suspend fun editRevision(
            roomId: Long,
            keyPrefix: String = "chat-placement-edit",
        ): Long =
            db
                .appStateDao()
                .keysLike("$keyPrefix:$roomId:%")
                .maxOfOrNull { it.substringAfterLast(':').toLong() } ?: 0L

        suspend fun setPinned(
            roomId: Long,
            pinned: Boolean,
        ) = db.withTransaction {
            val room = db.bufferDao().observeById(roomId) ?: return@withTransaction
            recordEdit(room.id, nextRevision(), "chat-placement-pin-edit")
            db.bufferDao().setPinned(room.id, pinned)
        }

        suspend fun setAnchored(
            roomId: Long,
            anchored: Boolean,
        ) = db.withTransaction {
            val room = db.bufferDao().observeById(roomId) ?: return@withTransaction
            if (room.type == BufferType.SERVER) return@withTransaction
            recordEdit(room.id, nextRevision())
            if (!anchored) {
                db.bufferDao().writeAnchorOrder(room.id, null)
            } else if (room.anchorOrder == null) {
                val liveMax = db.bufferDao().anchoredRooms().maxOfOrNull { it.anchorOrder ?: -1 } ?: -1
                val pendingMax = pending().maxOfOrNull { it.second.anchorOrder ?: -1 } ?: -1
                db.bufferDao().writeAnchorOrder(room.id, maxOf(liveMax, pendingMax) + 1)
            }
        }

        suspend fun reorderAnchored(orderedIds: List<Long>) =
            db.withTransaction {
                val anchored =
                    db
                        .bufferDao()
                        .anchoredRooms()
                        .map { it.id }
                        .toSet()
                val selected = orderedIds.distinct().filter { it in anchored }
                val revision = nextRevision()
                selected.forEach { recordEdit(it, revision) }
                // Live ranks already reserve pending slots; only selected live slots are exchanged.
                db.bufferDao().reorderAnchored(selected)
            }

        private fun PortableChatPlacement.identity() = listOf(networkId, chatType, identityKind, identityValue)

        private fun RoomEntity.portableIdentity(aliases: List<RoomAliasEntity>): Pair<FolderIdentityKind, String>? =
            when (type) {
                BufferType.CHANNEL -> {
                    FolderIdentityKind.CHANNEL to
                        (aliases.firstOrNull { it.namespace == RoomAliasNamespace.CHANNEL }?.value ?: name.substringBefore('\u0000'))
                }

                BufferType.QUERY -> {
                    aliases
                        .firstOrNull { it.namespace == RoomAliasNamespace.ACCOUNT && it.verified }
                        ?.let { FolderIdentityKind.ACCOUNT to it.value }
                        ?: aliases
                            .firstOrNull { it.namespace == RoomAliasNamespace.VERIFIED_NICK }
                            ?.let { FolderIdentityKind.NICK to it.value }
                        ?: aliases
                            .firstOrNull { it.namespace == RoomAliasNamespace.PROVISIONAL_NICK }
                            ?.let { FolderIdentityKind.NICK to it.value }
                        ?: (FolderIdentityKind.NICK to name.substringBefore('\u0000'))
                }

                BufferType.SERVER -> {
                    null
                }
            }

        suspend fun chatPlacementSnapshot(): List<PortableChatPlacement> =
            db.withTransaction {
                val aliases = db.roomAliasDao().allNow().groupBy { it.roomId }
                val rooms =
                    db.bufferDao().chatPlacementRooms().mapNotNull { room ->
                        val identity = room.portableIdentity(aliases[room.id].orEmpty()) ?: return@mapNotNull null
                        PortableChatPlacement(room.networkId, room.type, identity.first, identity.second, room.pinned, room.anchorOrder)
                    }
                val networks =
                    db
                        .networkDao()
                        .allNow()
                        .map { it.id }
                        .toSet()
                val placements =
                    (rooms + pending().map { it.second }.filter { it.networkId in networks })
                        .distinctBy { it.identity() }
                val orders =
                    placements
                        .filter { it.anchorOrder != null }
                        .sortedBy { it.anchorOrder }
                        .mapIndexed { index, placement -> placement.identity() to index }
                        .toMap()
                placements.map { it.copy(anchorOrder = orders[it.identity()]) }
            }

        suspend fun restoreChatPlacements(
            placements: List<PortableChatPlacement>,
            replace: Boolean,
        ) = db.withTransaction {
            val local = chatPlacementSnapshot()
            val importedIdentities = placements.map { it.identity() }.toSet()
            val importedRooms = placements.mapNotNull { resolve(it)?.id }.toSet()
            val retained =
                if (replace) {
                    emptyList()
                } else {
                    local.filter {
                        it.identity() !in importedIdentities && resolve(it)?.id !in importedRooms
                    }
                }
            val revision = nextRevision()
            val combined = placements.map { it.copy(restoreRevision = revision) } + retained
            val orders =
                (
                    placements.filter { it.anchorOrder != null }.sortedBy { it.anchorOrder } +
                        retained.filter { it.anchorOrder != null }.sortedBy { it.anchorOrder }
                ).mapIndexed { index, placement -> placement.identity() to index }
                    .toMap()
            db.bufferDao().chatPlacementRooms().forEach { room ->
                db.bufferDao().setPinned(room.id, false)
                db.bufferDao().writeAnchorOrder(room.id, null)
            }
            pending().forEach { db.appStateDao().delete(it.first) }
            combined.forEach { placement ->
                val normalized = placement.copy(anchorOrder = orders[placement.identity()])
                val room = resolve(normalized)
                if (room == null) {
                    db.appStateDao().insert(AppStateEntity(pendingKey(normalized)))
                } else {
                    apply(room.id, normalized)
                }
            }
        }

        private suspend fun resolve(placement: PortableChatPlacement): RoomEntity? = resolvePortableRoom(placement.networkId, placement.chatType, placement.identityKind, placement.identityValue)

        private suspend fun resolvePortableRoom(
            networkId: Long,
            chatType: BufferType,
            identityKind: FolderIdentityKind,
            identityValue: String,
            requireVerifiedAccount: Boolean = false,
        ): RoomEntity? {
            val namespace =
                when (identityKind) {
                    FolderIdentityKind.CHANNEL -> RoomAliasNamespace.CHANNEL
                    FolderIdentityKind.ACCOUNT -> RoomAliasNamespace.ACCOUNT
                    FolderIdentityKind.NICK -> RoomAliasNamespace.VERIFIED_NICK
                }
            val alias =
                db.roomAliasDao().byValue(networkId, namespace, identityValue)
                    ?: if (identityKind == FolderIdentityKind.NICK) {
                        db.roomAliasDao().byValue(networkId, RoomAliasNamespace.PROVISIONAL_NICK, identityValue)
                    } else {
                        null
                    }
            return alias
                ?.takeIf { !requireVerifiedAccount || identityKind != FolderIdentityKind.ACCOUNT || it.verified }
                ?.let { db.bufferDao().observeById(it.roomId) }
                ?.takeIf { it.type == chatType }
                ?: if (identityKind != FolderIdentityKind.ACCOUNT) {
                    db.bufferDao().byName(networkId, identityValue)?.takeIf { it.type == chatType }
                } else {
                    null
                }
        }

        private suspend fun pendingIdentities(room: RoomEntity): Set<Pair<FolderIdentityKind, String>> {
            val aliases = db.roomAliasDao().forRoom(room.id)
            return buildSet {
                val channel = room.type == BufferType.CHANNEL
                add((if (channel) FolderIdentityKind.CHANNEL else FolderIdentityKind.NICK) to room.name)
                for (alias in aliases) {
                    if (alias.networkId != room.networkId) continue
                    val kind =
                        when (alias.namespace) {
                            RoomAliasNamespace.CHANNEL -> if (channel) FolderIdentityKind.CHANNEL else null

                            RoomAliasNamespace.ACCOUNT -> if (channel) null else FolderIdentityKind.ACCOUNT

                            RoomAliasNamespace.VERIFIED_NICK,
                            RoomAliasNamespace.PROVISIONAL_NICK,
                            -> if (channel) null else FolderIdentityKind.NICK

                            RoomAliasNamespace.LEGACY_NAME -> null
                        }
                    if (kind != null) add(kind to alias.value)
                }
            }
        }

        private suspend inline fun forEachPendingKey(
            room: RoomEntity,
            identities: Set<Pair<FolderIdentityKind, String>>,
            keyPrefix: String,
            action: (String) -> Unit,
        ) {
            for ((kind, value) in identities) {
                val prefix = pendingIdentityPrefix(room.networkId, room.type, kind, value, keyPrefix)
                for (key in db.appStateDao().keysGlob("$prefix*")) action(key)
            }
        }

        private suspend fun claimPendingChatSettings(room: RoomEntity) {
            if (room.type == BufferType.SERVER) return
            val identities = pendingIdentities(room)
            claimPendingPlacement(room, identities)
            claimPendingWallpaper(room, identities)
        }

        private suspend fun claimPendingPlacement(
            room: RoomEntity,
            identities: Set<Pair<FolderIdentityKind, String>>,
        ) {
            val state = db.appStateDao()
            state.keysGlob("$PENDING_CHAT_PLACEMENT_PREFIX{*").forEach { key ->
                state.insert(AppStateEntity(pendingKey(decodePending(key))))
                state.delete(key)
            }

            val matches = mutableListOf<Pair<String, PortableChatPlacement>>()
            forEachPendingKey(room, identities, PENDING_CHAT_PLACEMENT_PREFIX) { key ->
                val placement = decodePending(key)
                if (resolve(placement)?.id == room.id) matches.add(key to placement)
            }
            if (matches.isEmpty()) return

            // Account identity is authoritative if a nick and account both become known together.
            matches.sortWith(compareBy({ it.second.identityKind == FolderIdentityKind.ACCOUNT }, { pendingPayload(it.first) }))
            val editedRevision = editRevision(room.id)
            val pinEditedRevision = editRevision(room.id, "chat-placement-pin-edit")
            for ((key, placement) in matches) {
                if (pinEditedRevision <= placement.restoreRevision) {
                    db.bufferDao().setPinned(room.id, placement.pinned)
                }
                if (editedRevision <= placement.restoreRevision) {
                    db.bufferDao().writeAnchorOrder(room.id, placement.anchorOrder)
                }
                state.delete(key)
            }
        }

        private suspend fun apply(
            roomId: Long,
            placement: PortableChatPlacement,
        ) {
            db.bufferDao().setPinned(roomId, placement.pinned)
            db.bufferDao().writeAnchorOrder(roomId, placement.anchorOrder)
        }

        fun observeWallpaperTarget(roomId: Long): Flow<RoomEntity?> =
            db
                .bufferDao()
                .observe(roomId)
                .map { it?.takeIf { room -> room.acceptsWallpaperEdits() } }
                .distinctUntilChanged()

        fun observeWallpaperUseCount(ref: WallpaperPatternRef): Flow<Int> =
            db
                .bufferDao()
                .observeChatWallpaperEntries("$PENDING_CHAT_WALLPAPER_PREFIX*")
                .map { entries ->
                    entries.count { entry ->
                        val choice =
                            if (entry.startsWith(PENDING_CHAT_WALLPAPER_PREFIX)) {
                                decodePendingWallpaper(entry)?.choice
                            } else {
                                decodeWallpaperChatChoice(entry)
                            }
                        choice?.pattern == ref
                    }
                }.distinctUntilChanged()

        private fun RoomEntity.acceptsWallpaperEdits(): Boolean = type != BufferType.SERVER && redirectToRoomId == null && pendingCloseAt == null

        /** Rechecked inside the write transaction; a dirty draft can check before saving its design. */
        suspend fun checkWallpaperAssignment(
            roomId: Long,
            explicit: Boolean,
        ): Boolean = db.withTransaction { wallpaperEditTarget(roomId, explicit) != null }

        private suspend fun wallpaperEditTarget(
            roomId: Long,
            explicit: Boolean,
        ): RoomEntity? {
            val room = db.bufferDao().observeById(roomId)?.takeIf { it.acceptsWallpaperEdits() } ?: return null
            if (explicit && decodeWallpaperChatChoice(room.wallpaperOverrideEncoded) == null) {
                val liveCount =
                    db.bufferDao().chatWallpaperRooms().count {
                        decodeWallpaperChatChoice(it.wallpaperOverrideEncoded) != null
                    }
                requireWallpaperQuota(liveCount + pendingWallpapers().size + 1)
            }
            return room
        }

        // Workshop validates saved refs against its library while holding its mutation lock.
        // Room never acquires that lock; it owns canonical identity, quota and edit ordering.
        suspend fun setWallpaperOverride(
            roomId: Long,
            choice: WallpaperChatChoice?,
        ): Boolean =
            db.withTransaction {
                val room = wallpaperEditTarget(roomId, choice != null) ?: return@withTransaction false
                choice?.pattern?.let { ref ->
                    require(ref.isValidIn(null) || ref.kind == WallpaperPatternRefKind.SAVED && isCanonicalWallpaperUuid(ref.id))
                }
                val encoded = choice?.let { wallpaperJson.encodeToString(it) }
                if (db.bufferDao().setWallpaperOverride(roomId, encoded) != 1) return@withTransaction false
                recordEdit(room.id, nextRevision("chat-wallpaper-revision"), room.wallpaperEditPrefix())
                true
            }

        private fun RoomEntity.wallpaperEditPrefix(): String = "$CHAT_WALLPAPER_EDIT_PREFIX:$networkId"

        private suspend fun wallpaperEditRevision(room: RoomEntity): Long = editRevision(room.id, room.wallpaperEditPrefix())

        suspend fun clearWallpaperReferences(ref: WallpaperPatternRef) {
            db.withTransaction {
                for (room in db.bufferDao().chatWallpaperRooms()) {
                    if (decodeWallpaperChatChoice(room.wallpaperOverrideEncoded)?.pattern == ref) {
                        db.bufferDao().setWallpaperOverride(room.id, null)
                    }
                }
                for ((key, assignment) in pendingWallpapers()) {
                    if (assignment.choice.pattern == ref) db.appStateDao().delete(key)
                }
            }
        }

        private fun PortableChatWallpaper.identityPrefix(): String = pendingIdentityPrefix(networkId, chatType, identityKind, identityValue, PENDING_CHAT_WALLPAPER_PREFIX)

        private fun PortableChatWallpaper.pendingWallpaperKey(): String = identityPrefix() + wallpaperJson.encodeToString(this)

        private fun decodePendingWallpaper(key: String): PortableChatWallpaper? {
            val payloadStart = key.indexOf('{', PENDING_CHAT_WALLPAPER_PREFIX.length)
            if (payloadStart < 0) return null
            return runCatching {
                decodeWallpaperValue<PortableChatWallpaper>(wallpaperJson.parseToJsonElement(key.substring(payloadStart)))
            }.getOrNull()?.takeIf {
                it.networkId > 0 && it.restoreRevision > 0 &&
                    isPortableChatIdentity(it.chatType, it.identityKind, it.identityValue) &&
                    key.regionMatches(0, it.identityPrefix(), 0, payloadStart)
            }
        }

        private suspend fun pendingWallpapers(): List<Pair<String, PortableChatWallpaper>> =
            db.appStateDao().keysGlob("$PENDING_CHAT_WALLPAPER_PREFIX*").mapNotNull { key ->
                decodePendingWallpaper(key)?.let { key to it }
            }

        private suspend fun resolve(assignment: PortableChatWallpaper): RoomEntity? = resolvePortableRoom(assignment.networkId, assignment.chatType, assignment.identityKind, assignment.identityValue, requireVerifiedAccount = true)

        private val wallpaperIdentityOrder =
            compareBy<PortableChatWallpaper>({ it.identityKind != FolderIdentityKind.ACCOUNT }, { it.identityValue })

        private fun WallpaperChatChoice.isValidIn(library: WallpaperLibrary): Boolean = pattern?.isValidIn(library) != false

        private suspend fun claimPendingWallpaper(
            room: RoomEntity,
            identities: Set<Pair<FolderIdentityKind, String>>,
        ) {
            var editedRevision: Long? = null
            var winner: PortableChatWallpaper? = null
            forEachPendingKey(room, identities, PENDING_CHAT_WALLPAPER_PREFIX) { key ->
                val assignment = decodePendingWallpaper(key) ?: return@forEachPendingKey
                if (resolve(assignment)?.id != room.id) return@forEachPendingKey
                val edited = editedRevision ?: wallpaperEditRevision(room).also { editedRevision = it }
                val best = winner
                if (assignment.restoreRevision >= edited && (best == null || wallpaperIdentityOrder.compare(assignment, best) < 0)) {
                    winner = assignment
                }
                db.appStateDao().delete(key)
            }
            winner?.let { db.bufferDao().setWallpaperOverride(room.id, wallpaperJson.encodeToString(it.choice)) }
        }

        suspend fun chatWallpaperSnapshot(library: WallpaperLibrary): List<PortableChatWallpaper> =
            db.withTransaction {
                val aliases = db.roomAliasDao().allNow().groupBy { it.roomId }
                val live =
                    db.bufferDao().chatWallpaperRooms().mapNotNull { room ->
                        val (kind, value) = room.portableIdentity(aliases[room.id].orEmpty()) ?: return@mapNotNull null
                        val choice = decodeWallpaperChatChoice(room.wallpaperOverrideEncoded) ?: return@mapNotNull null
                        if (!choice.isValidIn(library) || !isPortableChatIdentity(room.type, kind, value)) return@mapNotNull null
                        PortableChatWallpaper(room.networkId, room.type, kind, value, choice)
                    }
                val networkIds = db.networkDao().allNow().mapTo(mutableSetOf()) { it.id }
                val pending =
                    pendingWallpapers().mapNotNull { (_, assignment) ->
                        if (assignment.networkId !in networkIds || !assignment.choice.isValidIn(library)) return@mapNotNull null
                        val room = resolve(assignment)
                        if (room != null && wallpaperEditRevision(room) > assignment.restoreRevision) return@mapNotNull null
                        assignment
                    }
                (live + pending).distinctBy { it.identityPrefix() }
            }

        suspend fun preflightChatWallpaperAssignments(
            assignments: List<PortableChatWallpaper>,
            replace: Boolean,
            library: WallpaperLibrary,
        ) {
            db.withTransaction {
                planWallpaperRestore(assignments, replace, library, allowProvisionalNetworks = true)
            }
        }

        suspend fun restoreChatWallpaperAssignments(
            assignments: List<PortableChatWallpaper>,
            replace: Boolean,
            library: WallpaperLibrary,
        ) {
            db.withTransaction {
                val restore = planWallpaperRestore(assignments, replace, library, allowProvisionalNetworks = false)
                val revision = nextRevision("chat-wallpaper-revision")
                val buffers = db.bufferDao()
                for (room in restore.currentRooms) {
                    if (room.id !in restore.rooms) buffers.setWallpaperOverride(room.id, null)
                }
                val currentChoices = restore.currentRooms.associate { it.id to it.wallpaperOverrideEncoded }
                for ((id, choice) in restore.rooms) {
                    val encoded = wallpaperJson.encodeToString(choice)
                    if (currentChoices[id] != encoded) buffers.setWallpaperOverride(id, encoded)
                }
                val state = db.appStateDao()
                val pendingKeys =
                    restore.pending.values.mapTo(hashSetOf()) {
                        val assignment = if (it.restoreRevision == 0L) it.copy(restoreRevision = revision) else it
                        assignment.pendingWallpaperKey()
                    }
                val existingKeys = state.keysGlob("$PENDING_CHAT_WALLPAPER_PREFIX*").toHashSet()
                for (key in existingKeys - pendingKeys) state.delete(key)
                for (key in pendingKeys - existingKeys) state.insert(AppStateEntity(key))
            }
        }

        private data class WallpaperRestore(
            val currentRooms: List<RoomEntity>,
            val rooms: Map<Long, WallpaperChatChoice>,
            val pending: Map<String, PortableChatWallpaper>,
        )

        private suspend fun planWallpaperRestore(
            assignments: List<PortableChatWallpaper>,
            replace: Boolean,
            library: WallpaperLibrary,
            allowProvisionalNetworks: Boolean,
        ): WallpaperRestore {
            requireWallpaperQuota(assignments.size)
            val networkIds = db.networkDao().allNow().mapTo(mutableSetOf()) { it.id }
            val currentRooms = db.bufferDao().chatWallpaperRooms()
            val rooms = mutableMapOf<Long, WallpaperChatChoice>()
            val pending = mutableMapOf<String, PortableChatWallpaper>()
            if (!replace) {
                val localPending = pendingWallpapers().map { it.second }.sortedWith(wallpaperIdentityOrder)
                for (assignment in localPending) {
                    if (assignment.networkId !in networkIds || !assignment.choice.isValidIn(library)) continue
                    val room = resolve(assignment)
                    if (room == null) {
                        pending.putIfAbsent(assignment.identityPrefix(), assignment)
                    } else if (wallpaperEditRevision(room) <= assignment.restoreRevision) {
                        rooms.putIfAbsent(room.id, assignment.choice)
                    }
                }
                for (room in currentRooms) {
                    val choice = decodeWallpaperChatChoice(room.wallpaperOverrideEncoded)
                    if (choice != null && choice.isValidIn(library)) rooms[room.id] = choice
                }
            }
            val incomingIdentities = hashSetOf<String>()
            val incomingRooms = hashSetOf<Long>()
            for (assignment in assignments.sortedWith(wallpaperIdentityOrder)) {
                validatePortableChatIdentity(assignment.chatType, assignment.identityKind, assignment.identityValue)
                require(assignment.networkId in networkIds || allowProvisionalNetworks && assignment.networkId < 0) {
                    "Invalid chat wallpaper assignment network."
                }
                require(assignment.choice.isValidIn(library)) { "Invalid chat wallpaper assignment reference." }
                val identity = assignment.identityPrefix()
                require(incomingIdentities.add(identity)) { "Duplicate chat wallpaper assignment." }
                val room = if (assignment.networkId > 0) resolve(assignment) else null
                if (room == null) {
                    pending[identity] = assignment.copy(restoreRevision = 0)
                } else if (incomingRooms.add(room.id)) {
                    rooms[room.id] = assignment.choice
                }
            }
            requireWallpaperQuota(rooms.size + pending.size)
            return WallpaperRestore(currentRooms, rooms, pending)
        }

        private fun requireWallpaperQuota(count: Int) {
            require(count <= MAX_CHAT_WALLPAPER_ASSIGNMENTS) { "Too many chat wallpaper assignments." }
        }

        suspend fun getOrCreate(
            networkId: Long,
            normalizedName: String,
            displayName: String,
            type: BufferType,
            initiallyDismissed: Boolean = false,
        ): BufferEntity =
            db.withTransaction {
                val dao = db.bufferDao()
                val aliasDao = db.roomAliasDao()
                // soju's console is not a conversation: a caller may ask for a QUERY and still get
                // the console. Role-scoped, or a real user holding that nick elsewhere would lose
                // their DM room.
                val effectiveType =
                    if (db.networkDao().isBouncerConsole(networkId, normalizedName)) {
                        BufferType.SERVER
                    } else {
                        type
                    }
                val namespace =
                    when (effectiveType) {
                        BufferType.CHANNEL -> RoomAliasNamespace.CHANNEL
                        BufferType.QUERY -> RoomAliasNamespace.PROVISIONAL_NICK
                        BufferType.SERVER -> RoomAliasNamespace.LEGACY_NAME
                    }
                if (effectiveType == BufferType.QUERY) {
                    resolveQueryRoom(networkId, normalizedName, account = null)?.let {
                        return@withTransaction it
                    }
                } else {
                    aliasDao.byValue(networkId, namespace, normalizedName)?.let { alias ->
                        dao.observeById(alias.roomId)?.takeIf { it.type == effectiveType }?.let {
                            return@withTransaction it
                        }
                    }
                }
                val nameCollision = dao.byName(networkId, normalizedName)
                if (nameCollision?.type == effectiveType) return@withTransaction nameCollision
                if (effectiveType == BufferType.CHANNEL && nameCollision?.type == BufferType.QUERY) {
                    val hasAccountIdentity =
                        aliasDao
                            .forRoom(nameCollision.id)
                            .any { it.namespace == RoomAliasNamespace.ACCOUNT }
                    if (!hasAccountIdentity) {
                        val promoted =
                            nameCollision.copy(
                                displayName = displayName,
                                type = BufferType.CHANNEL,
                                readMarkerTime = if (nameCollision.dismissed) null else nameCollision.readMarkerTime,
                                localReadAnchorTime = if (nameCollision.dismissed) null else nameCollision.localReadAnchorTime,
                                localReadAnchorEventId = if (nameCollision.dismissed) null else nameCollision.localReadAnchorEventId,
                                localUnreadFloorTime = if (nameCollision.dismissed) null else nameCollision.localUnreadFloorTime,
                                oldestFetchedTime = if (nameCollision.dismissed) null else nameCollision.oldestFetchedTime,
                                historyComplete = if (nameCollision.dismissed) false else nameCollision.historyComplete,
                                dismissed = false,
                                historyDiscardedThroughMsgid = null,
                                historyDiscardedThroughTime = null,
                            )
                        dao.update(promoted)
                        dao.deleteDiscardedMessageIds(promoted.id)
                        if (nameCollision.dismissed) {
                            db.historyCursorDao().delete(promoted.id)
                            db.historyGapDao().deleteForRoom(promoted.id)
                        }
                        aliasDao.deleteQueryAliases(promoted.id)
                        aliasDao.insertIgnore(
                            RoomAliasEntity(
                                networkId = networkId,
                                namespace = RoomAliasNamespace.CHANNEL,
                                value = normalizedName,
                                roomId = promoted.id,
                                verified = true,
                            ),
                        )
                        chatFolders.claimPending(promoted.id, normalizedChannel = normalizedName)
                        claimPendingChatSettings(promoted)
                        return@withTransaction checkNotNull(dao.observeById(promoted.id))
                    }
                }
                val storedName =
                    if (nameCollision == null) {
                        normalizedName
                    } else {
                        "$normalizedName\u0000${effectiveType.name.lowercase()}"
                    }
                val candidate =
                    BufferEntity(
                        networkId = networkId,
                        name = storedName,
                        displayName = displayName,
                        type = effectiveType,
                        dismissed = initiallyDismissed,
                    )
                val insertedId = dao.insertIgnore(candidate)
                val room =
                    if (insertedId > 0L) {
                        candidate.copy(id = insertedId)
                    } else {
                        checkNotNull(dao.byName(networkId, storedName)) {
                            "buffer insert conflict did not leave a row for $networkId/$storedName"
                        }
                    }
                aliasDao.insertIgnore(
                    RoomAliasEntity(
                        networkId = networkId,
                        namespace = namespace,
                        value = normalizedName,
                        roomId = room.id,
                        verified = effectiveType == BufferType.CHANNEL,
                    ),
                )
                if (effectiveType == BufferType.CHANNEL) {
                    chatFolders.claimPending(room.id, normalizedChannel = normalizedName)
                }
                claimPendingChatSettings(room)
                checkNotNull(dao.observeById(room.id))
            }

        /**
         * Bind verified query identity. An account collision proves that two provisional nick rooms are
         * the same conversation; the oldest local id wins and the losing id remains a redirect.
         */
        suspend fun bindQueryIdentity(
            roomId: RoomId,
            networkId: Long,
            normalizedNick: String,
            displayNick: String,
            account: String?,
        ): BufferEntity =
            db.withTransaction {
                val requestedRoom = checkNotNull(db.bufferDao().observeById(roomId))
                var room = requestedRoom
                val aliasDao = db.roomAliasDao()
                if (account == null) {
                    aliasDao
                        .byValue(
                            networkId,
                            RoomAliasNamespace.VERIFIED_NICK,
                            normalizedNick,
                        )?.let { active ->
                            room = checkNotNull(db.bufferDao().observeById(active.roomId))
                        }
                }
                var roomAccounts =
                    aliasDao
                        .forRoom(room.id)
                        .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
                        .map { it.value }
                        .toSet()
                if (account != null) {
                    val known = aliasDao.byValue(networkId, RoomAliasNamespace.ACCOUNT, account)
                    room =
                        when {
                            known != null && known.roomId != requestedRoom.id && roomAccounts.isEmpty() -> {
                                mergeRooms(known.roomId, requestedRoom.id)
                            }

                            known != null -> {
                                checkNotNull(db.bufferDao().observeById(known.roomId))
                            }

                            roomAccounts.any { it != account } -> {
                                val disambiguated =
                                    BufferEntity(
                                        networkId = networkId,
                                        name = "$normalizedNick\u0000account:$account",
                                        displayName = displayNick,
                                        type = BufferType.QUERY,
                                    )
                                disambiguated.copy(id = db.bufferDao().insert(disambiguated))
                            }

                            else -> {
                                room
                            }
                        }
                    roomAccounts =
                        aliasDao
                            .forRoom(room.id)
                            .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
                            .map { it.value }
                            .toSet()
                }
                val namespace =
                    if (account != null) {
                        RoomAliasNamespace.ACCOUNT
                    } else {
                        RoomAliasNamespace.PROVISIONAL_NICK
                    }
                val value = account ?: normalizedNick
                val existing = aliasDao.byValue(networkId, namespace, value)
                if (existing != null && existing.roomId != room.id && account != null) {
                    room =
                        if (roomAccounts.isEmpty()) {
                            mergeRooms(existing.roomId, room.id)
                        } else {
                            checkNotNull(db.bufferDao().observeById(existing.roomId))
                        }
                }
                aliasDao.insertIgnore(
                    RoomAliasEntity(
                        networkId = networkId,
                        namespace = namespace,
                        value = value,
                        roomId = room.id,
                        verified = account != null,
                    ),
                )
                if (account != null) {
                    aliasDao.insertIgnore(
                        RoomAliasEntity(
                            networkId = networkId,
                            namespace = RoomAliasNamespace.VERIFIED_NICK,
                            value = normalizedNick,
                            roomId = room.id,
                            verified = true,
                        ),
                    )
                    aliasDao.moveVerifiedNick(networkId, normalizedNick, room.id)
                    if (room.displayName != displayNick) {
                        room = room.copy(displayName = displayNick)
                        db.bufferDao().update(room)
                    }
                }
                chatFolders.claimPending(room.id, account = account, normalizedNick = normalizedNick)
                claimPendingChatSettings(room)
                checkNotNull(db.bufferDao().observeById(room.id))
            }

        /** Resolve a PM by strong account, then the active verified nick, then provisional nick. */
        suspend fun resolveQueryRoom(
            networkId: Long,
            normalizedNick: String,
            account: String?,
        ): BufferEntity? {
            val aliases = db.roomAliasDao()
            val alias =
                account?.let {
                    aliases.byValue(networkId, RoomAliasNamespace.ACCOUNT, it)
                } ?: aliases.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedNick)
                    ?: aliases.byValue(networkId, RoomAliasNamespace.PROVISIONAL_NICK, normalizedNick)
            return alias
                ?.let { db.bufferDao().observeById(it.roomId) }
                ?.takeIf { it.type == BufferType.QUERY }
        }

        /** Resolve a channel through its durable alias, then a compatible legacy room key. */
        suspend fun resolveChannelRoom(
            networkId: Long,
            normalizedName: String,
        ): BufferEntity? {
            val aliased =
                db
                    .roomAliasDao()
                    .byValue(networkId, RoomAliasNamespace.CHANNEL, normalizedName)
                    ?.let { db.bufferDao().observeById(it.roomId) }
                    ?.takeIf { it.type == BufferType.CHANNEL }
            return aliased ?: db
                .bufferDao()
                .byName(networkId, normalizedName)
                ?.takeIf { it.type == BufferType.CHANNEL }
        }

        suspend fun mergeRooms(
            firstId: RoomId,
            secondId: RoomId,
        ): BufferEntity {
            val nestedTransaction = db.inTransaction()
            val merged =
                db.withTransaction {
                    val bufferDao = db.bufferDao()
                    val aliasDao = db.roomAliasDao()
                    val first = checkNotNull(bufferDao.observeById(firstId))
                    val second = checkNotNull(bufferDao.observeById(secondId))
                    if (first.id == second.id) return@withTransaction first
                    val winner = if (first.id < second.id) first else second
                    val loser = if (winner.id == first.id) second else first
                    val editedRevision = maxOf(editRevision(winner.id), editRevision(loser.id))
                    if (editedRevision > 0L) recordEdit(winner.id, editedRevision)
                    db.appStateDao().keysLike("chat-placement-edit:${loser.id}:%").forEach { db.appStateDao().delete(it) }
                    val pinEditedRevision = maxOf(editRevision(winner.id, "chat-placement-pin-edit"), editRevision(loser.id, "chat-placement-pin-edit"))
                    if (pinEditedRevision > 0L) recordEdit(winner.id, pinEditedRevision, "chat-placement-pin-edit")
                    db.appStateDao().keysLike("chat-placement-pin-edit:${loser.id}:%").forEach { db.appStateDao().delete(it) }
                    val wallpaperRevision = maxOf(wallpaperEditRevision(winner), wallpaperEditRevision(loser))
                    if (wallpaperRevision > 0L) recordEdit(winner.id, wallpaperRevision, winner.wallpaperEditPrefix())
                    bufferDao.deleteChatWallpaperEdits(loser.networkId, loser.id)
                    val rooms = listOf(winner, loser)
                    val visibleRooms = rooms.filterNot { it.dismissed }
                    val readStateRooms = visibleRooms.ifEmpty { rooms }
                    val localReadAnchor =
                        readStateRooms
                            .mapNotNull { room ->
                                room.localReadAnchorTime?.let {
                                    val eventId = room.localReadAnchorEventId ?: 0L
                                    val order = db.messageDao().byCanonicalId(eventId)?.timelineOrder ?: eventId
                                    TimelineAnchor(it, eventId, order)
                                }
                            }.minOrNull()
                    val discardedBoundary =
                        rooms.maxWithOrNull(
                            compareBy<BufferEntity> { it.historyDiscardedThroughTime ?: Long.MIN_VALUE }
                                .thenBy { it.historyDiscardedThroughMsgid != null },
                        )
                    val result =
                        winner.copy(
                            displayName = if (second.displayName.isNotBlank()) second.displayName else winner.displayName,
                            topic = winner.topic ?: loser.topic,
                            topicSetBy = winner.topicSetBy ?: loser.topicSetBy,
                            joined = winner.joined || loser.joined,
                            membershipCycle = maxOf(winner.membershipCycle, loser.membershipCycle),
                            pinned = winner.pinned || loser.pinned,
                            anchorOrder = rooms.mapNotNull { it.anchorOrder }.minOrNull(),
                            muted = winner.muted || loser.muted,
                            // The canonical winner owns the current archive decision; an older redirect must
                            // never unexpectedly hide a conversation the user has already revived.
                            archived = winner.archived,
                            readMarkerTime = readStateRooms.mapNotNull { it.readMarkerTime }.maxOrNull(),
                            localReadAnchorTime = localReadAnchor?.serverTime,
                            localReadAnchorEventId = localReadAnchor?.eventId,
                            localUnreadFloorTime = readStateRooms.mapNotNull { it.localUnreadFloorTime }.maxOrNull(),
                            oldestFetchedTime = minNullable(winner.oldestFetchedTime, loser.oldestFetchedTime),
                            historyComplete = winner.historyComplete && loser.historyComplete,
                            dismissed = winner.dismissed && loser.dismissed,
                            historyDiscardedThroughMsgid = discardedBoundary?.historyDiscardedThroughMsgid,
                            historyDiscardedThroughTime = discardedBoundary?.historyDiscardedThroughTime,
                            layoutDensityOverride = winner.layoutDensityOverride ?: loser.layoutDensityOverride,
                            compactNickBracketsOverride = winner.compactNickBracketsOverride ?: loser.compactNickBracketsOverride,
                            messageBackgroundOpacityOverride = winner.messageBackgroundOpacityOverride ?: loser.messageBackgroundOpacityOverride,
                            messageBackgroundBlurOverride = winner.messageBackgroundBlurOverride ?: loser.messageBackgroundBlurOverride,
                            presenceModeOverride = winner.presenceModeOverride ?: loser.presenceModeOverride,
                            presenceCustomKindsOverride = winner.presenceCustomKindsOverride ?: loser.presenceCustomKindsOverride,
                            historySyncModeOverride = winner.historySyncModeOverride ?: loser.historySyncModeOverride,
                            avatarOverrideModel = winner.avatarOverrideModel ?: loser.avatarOverrideModel,
                            folderId = winner.folderId ?: loser.folderId,
                            wallpaperOverrideEncoded =
                                if (winner.type == BufferType.SERVER) null else winner.wallpaperOverrideEncoded ?: loser.wallpaperOverrideEncoded,
                        )
                    val mergedHistoryGaps = historyGapsForRoomMerge(winner.id, loser.id)
                    bufferDao.update(result)
                    aliasDao.repoint(loser.id, winner.id)
                    canonicalTimeline.moveEventsToRoom(winner.networkId, loser.id, winner.id)
                    bufferDao.refreshMonitorActivity(winner.id)
                    val canonicalMergedHistoryGaps =
                        canonicalizeMergedHistoryGaps(
                            winner.id,
                            mergedHistoryGaps,
                        )
                    aliasDao.copyMembers(loser.id, winner.id)
                    aliasDao.deleteMembers(loser.id)
                    aliasDao.copyReactions(loser.id, winner.id)
                    aliasDao.deleteReactions(loser.id)
                    mergeComposerDrafts(winner.id, loser.id)
                    mergeHistoryCursors(winner.id, loser.id, result.historyComplete)
                    replaceMergedHistoryGaps(winner.id, loser.id, canonicalMergedHistoryGaps)
                    bufferDao.copyDiscardedMessageIds(loser.id, winner.id)
                    bufferDao.deleteDiscardedMessageIds(loser.id)
                    aliasDao.repointRedirects(loser.id, winner.id)
                    aliasDao.markRedirect(loser.id, winner.id)
                    db.appStateDao().insert(
                        AppStateEntity(roomMergePresentationKey(winner.id, loser.id)),
                    )
                    result
                }
            if (!nestedTransaction) drainCommittedRoomMerges()
            return merged
        }

        /** Preserve local islands supplied by the opposite room instead of hiding them in a stale gap. */
        private suspend fun historyGapsForRoomMerge(
            winnerId: RoomId,
            loserId: RoomId,
        ): List<HistoryGapEntity> {
            val dao = db.historyGapDao()
            val winnerRows = db.messageDao().historyRowsForMerge(winnerId)
            val loserRows = db.messageDao().historyRowsForMerge(loserId)
            val winnerGaps = dao.forRoom(winnerId)
            val loserGaps = dao.forRoom(loserId)
            return winnerGaps.flatMap { splitHistoryGap(it, loserRows, loserGaps) } +
                loserGaps.flatMap { splitHistoryGap(it, winnerRows, winnerGaps) }
        }

        private fun splitHistoryGap(
            gap: HistoryGapEntity,
            oppositeRows: List<MessageEntity>,
            oppositeGaps: List<HistoryGapEntity>,
        ): List<HistoryGapEntity> {
            val older = gapBoundary(gap, older = true)
            val newer = gapBoundary(gap, older = false)
            val inside =
                oppositeRows.filter { row ->
                    val anchor = TimelineAnchor(row.serverTime, row.id, row.timelineOrder)
                    anchor > older && anchor < newer
                }
            if (inside.isEmpty()) return listOf(gap)
            val islands = mutableListOf<MutableList<MessageEntity>>()
            inside.forEach { row ->
                val current = islands.lastOrNull()
                val separated =
                    current?.lastOrNull()?.let { previous ->
                        val previousAnchor = TimelineAnchor(previous.serverTime, previous.id, previous.timelineOrder)
                        val rowAnchor = TimelineAnchor(row.serverTime, row.id, row.timelineOrder)
                        oppositeGaps.any { opposite ->
                            gapBoundary(opposite, older = true) < rowAnchor &&
                                gapBoundary(opposite, older = false) > previousAnchor
                        }
                    } ?: true
                if (separated) islands += mutableListOf(row) else current += row
            }
            val segments = mutableListOf<Pair<HistoryMergeBoundary, HistoryMergeBoundary>>()
            var cursor =
                HistoryMergeBoundary(
                    gap.olderMsgid,
                    gap.olderServerTime,
                    gap.olderEventId,
                    gap.olderTimelineOrder,
                )
            islands.forEach { island ->
                val first = island.first().toHistoryMergeBoundary()
                val last = island.last().toHistoryMergeBoundary()
                segments += cursor to first
                cursor = last
            }
            segments += cursor to
                HistoryMergeBoundary(
                    gap.newerMsgid,
                    gap.newerServerTime,
                    gap.newerEventId,
                    gap.newerTimelineOrder,
                )
            return segments.map { (left, right) ->
                gap.copy(
                    id = 0,
                    olderMsgid = left.msgid,
                    olderServerTime = left.serverTime,
                    olderEventId = left.eventId,
                    olderTimelineOrder = left.timelineOrder,
                    newerMsgid = right.msgid,
                    newerServerTime = right.serverTime,
                    newerEventId = right.eventId,
                    newerTimelineOrder = right.timelineOrder,
                )
            }
        }

        private fun MessageEntity.toHistoryMergeBoundary() =
            HistoryMergeBoundary(
                msgid,
                serverTime,
                id,
                timelineOrder,
            )

        private data class HistoryMergeBoundary(
            val msgid: String?,
            val serverTime: Long,
            val eventId: Long?,
            val timelineOrder: Long?,
        )

        /** Resolve pre-move gap snapshots through any event coalescence performed during the move. */
        private suspend fun canonicalizeMergedHistoryGaps(
            winnerId: RoomId,
            gaps: List<HistoryGapEntity>,
        ): List<HistoryGapEntity> =
            gaps.mapNotNull { gap ->
                val older =
                    canonicalHistoryMergeBoundary(
                        winnerId,
                        gap.olderMsgid,
                        gap.olderServerTime,
                        gap.olderEventId,
                        gap.olderTimelineOrder,
                    )
                val newer =
                    canonicalHistoryMergeBoundary(
                        winnerId,
                        gap.newerMsgid,
                        gap.newerServerTime,
                        gap.newerEventId,
                        gap.newerTimelineOrder,
                    )
                val olderAnchor =
                    TimelineAnchor(
                        older.serverTime,
                        older.eventId ?: Long.MIN_VALUE,
                        older.timelineOrder ?: older.eventId ?: Long.MIN_VALUE,
                    )
                val newerAnchor =
                    TimelineAnchor(
                        newer.serverTime,
                        newer.eventId ?: Long.MAX_VALUE,
                        newer.timelineOrder ?: newer.eventId ?: Long.MAX_VALUE,
                    )
                if (olderAnchor > newerAnchor || (olderAnchor == newerAnchor && gap.recoverable)) {
                    null
                } else {
                    gap.copy(
                        olderMsgid = older.msgid,
                        olderServerTime = older.serverTime,
                        olderEventId = older.eventId,
                        olderTimelineOrder = older.timelineOrder,
                        newerMsgid = newer.msgid,
                        newerServerTime = newer.serverTime,
                        newerEventId = newer.eventId,
                        newerTimelineOrder = newer.timelineOrder,
                    )
                }
            }

        private suspend fun canonicalHistoryMergeBoundary(
            winnerId: RoomId,
            msgid: String?,
            serverTime: Long,
            eventId: Long?,
            timelineOrder: Long?,
        ): HistoryMergeBoundary {
            val canonical =
                msgid?.let { db.messageDao().byMsgid(winnerId, it) }
                    ?: eventId?.let { db.messageDao().byCanonicalId(it) }?.takeIf { it.bufferId == winnerId }
            return canonical?.let { row ->
                HistoryMergeBoundary(row.msgid ?: msgid, row.serverTime, row.id, row.timelineOrder)
            } ?: HistoryMergeBoundary(msgid, serverTime, eventId, timelineOrder)
        }

        private fun gapBoundary(
            gap: HistoryGapEntity,
            older: Boolean,
        ): TimelineAnchor {
            val time = if (older) gap.olderServerTime else gap.newerServerTime
            val eventId = if (older) gap.olderEventId else gap.newerEventId
            val order = if (older) gap.olderTimelineOrder else gap.newerTimelineOrder
            val fallback = if (older) Long.MIN_VALUE else Long.MAX_VALUE
            return TimelineAnchor(time, eventId ?: fallback, order ?: eventId ?: fallback)
        }

        /** Coalesce only provably overlapping intervals after both rooms' local islands are retained. */
        private suspend fun replaceMergedHistoryGaps(
            winnerId: RoomId,
            loserId: RoomId,
            gaps: List<HistoryGapEntity>,
        ) {
            val dao = db.historyGapDao()
            val merged = mutableListOf<HistoryGapEntity>()
            gaps
                .sortedWith(compareBy<HistoryGapEntity> { it.olderServerTime }.thenBy { it.newerServerTime })
                .forEach { gap ->
                    val previous = merged.lastOrNull()
                    // Opaque msgids have no sortable order. Merely touching at one millisecond cannot
                    // prove two intervals overlap, especially when an entire server page shares time.
                    if (
                        previous == null ||
                        gap.recoverable != previous.recoverable ||
                        gap.olderServerTime >= previous.newerServerTime
                    ) {
                        merged += gap.copy(id = 0, roomId = winnerId)
                    } else if (
                        gap.olderServerTime == previous.olderServerTime &&
                        !sameOlderHistoryBoundary(previous, gap)
                    ) {
                        merged += gap.copy(id = 0, roomId = winnerId)
                    } else if (gap.newerServerTime > previous.newerServerTime) {
                        merged[merged.lastIndex] =
                            previous.copy(
                                newerMsgid = gap.newerMsgid,
                                newerServerTime = gap.newerServerTime,
                                newerEventId = gap.newerEventId,
                                newerTimelineOrder = gap.newerTimelineOrder,
                            )
                    } else if (
                        gap.newerServerTime == previous.newerServerTime &&
                        !sameNewerHistoryBoundary(previous, gap)
                    ) {
                        merged += gap.copy(id = 0, roomId = winnerId)
                    }
                }
            dao.deleteForRoom(winnerId)
            dao.deleteForRoom(loserId)
            merged.forEach { dao.insert(it.copy(id = 0)) }
        }

        private fun sameNewerHistoryBoundary(
            left: HistoryGapEntity,
            right: HistoryGapEntity,
        ): Boolean =
            when {
                left.newerMsgid != null && right.newerMsgid != null -> {
                    left.newerMsgid == right.newerMsgid
                }

                left.newerEventId != null && right.newerEventId != null -> {
                    left.newerEventId == right.newerEventId &&
                        left.newerTimelineOrder == right.newerTimelineOrder
                }

                else -> {
                    false
                }
            }

        private fun sameOlderHistoryBoundary(
            left: HistoryGapEntity,
            right: HistoryGapEntity,
        ): Boolean =
            when {
                left.olderMsgid != null && right.olderMsgid != null -> {
                    left.olderMsgid == right.olderMsgid
                }

                left.olderEventId != null && right.olderEventId != null -> {
                    left.olderEventId == right.olderEventId &&
                        left.olderTimelineOrder == right.olderTimelineOrder
                }

                else -> {
                    false
                }
            }

        /** Apply an IRCv3 channel rename while retiring the old channel alias. */
        suspend fun renameChannel(
            networkId: Long,
            oldNormalizedName: String,
            newNormalizedName: String,
            newDisplayName: String,
        ): BufferEntity? {
            val nestedTransaction = db.inTransaction()
            val renamed =
                db.withTransaction {
                    val old = resolveChannelRoom(networkId, oldNormalizedName) ?: return@withTransaction null
                    val oldMembers = db.memberDao().allNow(old.id)
                    val destination = resolveChannelRoom(networkId, newNormalizedName)
                    if (destination != null && destination.id != old.id && old.id < destination.id) {
                        db.bufferDao().renameRoomKey(
                            destination.id,
                            "$newNormalizedName\u0000redirect:${destination.id}",
                        )
                    }
                    val canonical =
                        if (destination != null && destination.id != old.id) {
                            mergeRooms(old.id, destination.id)
                        } else {
                            old
                        }
                    val canonicalId = db.bufferDao().canonicalId(canonical.id) ?: canonical.id
                    if (canonicalId != old.id) {
                        db.memberDao().replaceAll(
                            canonicalId,
                            oldMembers.map { MemberEntity(canonicalId, it.nick, it.prefixes) },
                        )
                    }
                    val current = checkNotNull(db.bufferDao().observeById(canonicalId))
                    val updated =
                        current.copy(
                            name =
                                if (current.name == oldNormalizedName || destination?.id == null) {
                                    newNormalizedName
                                } else {
                                    current.name
                                },
                            displayName = newDisplayName,
                            topic = old.topic ?: current.topic,
                            topicSetBy = old.topicSetBy ?: current.topicSetBy,
                            joined = old.joined || current.joined,
                            membershipCycle = maxOf(old.membershipCycle, current.membershipCycle),
                        )
                    if (updated.name != current.name || updated.displayName != current.displayName ||
                        updated.topic != current.topic || updated.joined != current.joined ||
                        updated.membershipCycle != current.membershipCycle
                    ) {
                        db.bufferDao().update(updated)
                    }
                    val aliases = db.roomAliasDao()
                    aliases.deleteAlias(networkId, RoomAliasNamespace.CHANNEL, oldNormalizedName)
                    aliases.insertIgnore(
                        RoomAliasEntity(
                            networkId = networkId,
                            namespace = RoomAliasNamespace.CHANNEL,
                            value = newNormalizedName,
                            roomId = updated.id,
                            verified = true,
                        ),
                    )
                    updated
                }
            if (!nestedTransaction) drainCommittedRoomMerges()
            return renamed
        }

        /** Newest draft version wins a room merge; reply ids are opaque local references, not FKs. */
        private suspend fun mergeComposerDrafts(
            winnerId: RoomId,
            loserId: RoomId,
        ) {
            val drafts = db.composerDraftDao()
            val winner = drafts.byRoom(winnerId)
            val loser = drafts.byRoom(loserId)
            val selected =
                listOfNotNull(winner, loser).maxWithOrNull(
                    compareBy<io.github.trevarj.motd.data.db.ComposerDraftEntity> { it.updatedAt }
                        .thenBy { it.roomId },
                )
            if (selected != null && selected.roomId != winnerId) {
                drafts.upsert(selected.copy(roomId = winnerId))
            }
            drafts.delete(loserId)
        }

        /** Present only durable room merges; nested callers drain after their outer transaction commits. */
        suspend fun drainCommittedRoomMerges() {
            val state = db.appStateDao()
            state.keysLike("$ROOM_MERGE_PRESENTATION_PREFIX%").forEach { key ->
                val (winnerId, loserId) =
                    parseRoomMergePresentationKey(key) ?: run {
                        state.delete(key)
                        return@forEach
                    }
                notificationRoomMergeListener.onRoomsMerged(winnerId, loserId)
                notifier.onRoomsMerged(winnerId, loserId)
                state.delete(key)
            }
        }

        /** A server NICK event is strong evidence that the old and new provisional aliases are one PM. */
        suspend fun bindNickChange(
            networkId: Long,
            normalizedOldNick: String,
            normalizedNewNick: String,
            displayNewNick: String,
        ): BufferEntity? =
            db.withTransaction {
                val aliasDao = db.roomAliasDao()
                val oldAlias =
                    aliasDao.byValue(
                        networkId,
                        RoomAliasNamespace.PROVISIONAL_NICK,
                        normalizedOldNick,
                    ) ?: aliasDao.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedOldNick)
                        ?: return@withTransaction null
                val newAlias =
                    aliasDao.byValue(
                        networkId,
                        RoomAliasNamespace.PROVISIONAL_NICK,
                        normalizedNewNick,
                    ) ?: aliasDao.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedNewNick)
                val oldAccounts = accountAliases(oldAlias.roomId)
                val newAccounts = newAlias?.let { accountAliases(it.roomId) }.orEmpty()
                val conflictingAccounts =
                    oldAccounts.isNotEmpty() &&
                        newAccounts.isNotEmpty() && oldAccounts.intersect(newAccounts).isEmpty()
                var room =
                    if (
                        newAlias != null && newAlias.roomId != oldAlias.roomId && !conflictingAccounts
                    ) {
                        mergeRooms(oldAlias.roomId, newAlias.roomId)
                    } else {
                        checkNotNull(db.bufferDao().observeById(oldAlias.roomId))
                    }
                aliasDao.insertIgnore(
                    RoomAliasEntity(
                        networkId = networkId,
                        namespace = RoomAliasNamespace.VERIFIED_NICK,
                        value = normalizedNewNick,
                        roomId = room.id,
                        verified = true,
                    ),
                )
                aliasDao.moveVerifiedNick(networkId, normalizedNewNick, room.id)
                if (room.displayName != displayNewNick) {
                    room = room.copy(displayName = displayNewNick)
                    db.bufferDao().update(room)
                }
                room
            }

        private suspend fun accountAliases(roomId: RoomId): Set<String> =
            db
                .roomAliasDao()
                .forRoom(roomId)
                .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
                .mapTo(mutableSetOf()) { it.value }

        private suspend fun mergeHistoryCursors(
            winnerId: RoomId,
            loserId: RoomId,
            historyComplete: Boolean,
        ) {
            val cursors = db.historyCursorDao()
            val winner = cursors.byRoom(winnerId)
            val loser = cursors.byRoom(loserId)
            if (winner != null || loser != null) {
                val newest =
                    listOfNotNull(winner, loser).maxByOrNull {
                        it.newestServerTime ?: Long.MIN_VALUE
                    }
                val oldest =
                    listOfNotNull(winner, loser).minByOrNull {
                        it.oldestServerTime ?: Long.MAX_VALUE
                    }
                cursors.upsert(
                    HistoryCursorEntity(
                        roomId = winnerId,
                        newestMsgid = newest?.newestMsgid,
                        newestServerTime = newest?.newestServerTime,
                        oldestMsgid = oldest?.oldestMsgid,
                        oldestServerTime = oldest?.oldestServerTime,
                        historyComplete = historyComplete,
                    ),
                )
            }
            cursors.deleteRoom(loserId)
        }

        private fun maxNullable(
            first: Long?,
            second: Long?,
        ): Long? = listOfNotNull(first, second).maxOrNull()

        private fun minNullable(
            first: Long?,
            second: Long?,
        ): Long? = listOfNotNull(first, second).minOrNull()
    }

internal const val ROOM_MERGE_PRESENTATION_PREFIX = "room_merge_notification:"

internal fun roomMergePresentationKey(
    winnerId: RoomId,
    loserId: RoomId,
): String = "$ROOM_MERGE_PRESENTATION_PREFIX$winnerId:$loserId"

internal fun parseRoomMergePresentationKey(key: String): Pair<RoomId, RoomId>? {
    val ids = key.removePrefix(ROOM_MERGE_PRESENTATION_PREFIX).split(':')
    if (!key.startsWith(ROOM_MERGE_PRESENTATION_PREFIX) || ids.size != 2) return null
    return (ids[0].toLongOrNull() ?: return null) to (ids[1].toLongOrNull() ?: return null)
}
