package io.github.trevarj.motd.avatar

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import dagger.Lazy
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberDao
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.dickord.DICKORD_AVATAR_TAG
import io.github.trevarj.motd.dickord.isDickordChannel
import io.github.trevarj.motd.dickord.isDickordRelayNick
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConversationAvatarOutcome {
    data object LocalOnly : ConversationAvatarOutcome

    data object Shared : ConversationAvatarOutcome

    data object RequestSent : ConversationAvatarOutcome

    data object LocalReset : ConversationAvatarOutcome

    data object SharedCleared : ConversationAvatarOutcome

    data object Invalid : ConversationAvatarOutcome

    data object Failed : ConversationAvatarOutcome
}

interface AvatarController {
    suspend fun setShowSharedAvatars(show: Boolean)

    suspend fun ingestDickordAvatars(
        networkId: Long,
        events: List<IrcEvent>,
    ) = Unit

    suspend fun setSelfAvatar(
        networkId: Long,
        url: String?,
    ): Boolean

    suspend fun stopManagingSelfAvatar(networkId: Long)

    suspend fun clearNetworkState(networkId: Long)

    suspend fun importConversationAvatar(
        bufferId: Long,
        source: Uri,
    ): ConversationAvatarOutcome = ConversationAvatarOutcome.Failed

    suspend fun setConversationAvatar(
        bufferId: Long,
        url: String,
    ): ConversationAvatarOutcome = ConversationAvatarOutcome.Failed

    suspend fun resetConversationAvatar(bufferId: Long): ConversationAvatarOutcome = ConversationAvatarOutcome.Failed

    suspend fun shareConversationAvatar(bufferId: Long): ConversationAvatarOutcome = ConversationAvatarOutcome.Failed

    suspend fun clearSharedConversationAvatar(bufferId: Long): ConversationAvatarOutcome = ConversationAvatarOutcome.Failed

    fun publishingAvailable(networkId: Long): Boolean
}

object NoopAvatarController : AvatarController {
    override suspend fun setShowSharedAvatars(show: Boolean) = Unit

    override suspend fun setSelfAvatar(
        networkId: Long,
        url: String?,
    ) = false

    override suspend fun stopManagingSelfAvatar(networkId: Long) = Unit

    override suspend fun clearNetworkState(networkId: Long) = Unit

    override suspend fun importConversationAvatar(
        bufferId: Long,
        source: Uri,
    ) = ConversationAvatarOutcome.Failed

    override suspend fun setConversationAvatar(
        bufferId: Long,
        url: String,
    ) = ConversationAvatarOutcome.Failed

    override suspend fun resetConversationAvatar(bufferId: Long) = ConversationAvatarOutcome.Failed

    override suspend fun shareConversationAvatar(bufferId: Long) = ConversationAvatarOutcome.Failed

    override suspend fun clearSharedConversationAvatar(bufferId: Long) = ConversationAvatarOutcome.Failed

    override fun publishingAvailable(networkId: Long) = false
}

@Singleton
class AvatarCoordinator
    @Inject
    constructor(
        private val prefs: AvatarPrefs,
        private val store: AvatarStore,
        private val userDao: UserDao,
        private val bufferDao: BufferDao,
        private val memberDao: MemberDao,
        private val localAvatars: LocalAvatarStore,
        private val connections: Lazy<ConnectionManager>,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AvatarController {
        private val delayedSyncs = ConcurrentHashMap<String, Job>()
        private val cacheLock = Mutex()
        private val avatarCaches = mutableMapOf<Long, AvatarCache>()
        private var sharedAvatarsEnabledAtNanos = 0L
        private val metadataLock = Mutex()
        private val pendingMetadata = mutableMapOf<MetadataRequestKey, MetadataRequest>()
        private val expectedMetadataSnapshots = mutableMapOf<MetadataRequestKey, Long>()
        private val avatarSubscriptions = mutableMapOf<Long, IrcClient>()
        private val onlineMonitors = ConcurrentHashMap<Long, MutableSet<String>>()
        private val selfWrites = ConcurrentHashMap<Long, IrcClient>()

        private data class MetadataRequestKey(
            val networkId: Long,
            val client: IrcClient,
            val operation: String,
            val target: String,
        )

        private class MetadataRequest(
            val message: IrcMessage,
            val roomId: Long? = null,
            val refresh: Boolean = false,
        ) {
            var labeled = roomId != null
            var writtenAt: Long? = System.nanoTime()
            var retry: Job? = null
            var retrySeconds = 0L
        }

        private class AvatarCache(
            val client: IrcClient,
        ) {
            val latest = mutableMapOf<String, Long>()
            val awaitingNames = mutableMapOf<String, AvatarOmission?>()
            val renames = mutableMapOf<String, MutableList<AvatarRename>>()

            fun currentNick(
                nick: String,
                sequence: Long,
            ): String {
                if (sequence == 0L) return nick
                var current = nick
                var after = sequence
                while (true) {
                    val rename = renames[client.isupport.normalize(current)]?.firstOrNull { it.sequence > after } ?: return current
                    current = rename.nick
                    after = rename.sequence
                }
            }
        }

        private data class AvatarRename(
            val nick: String,
            val sequence: Long,
        )

        private data class AvatarOmission(
            val seen: Set<String>,
            val openingSequence: Long,
        )

        private fun avatarCache(
            networkId: Long,
            client: IrcClient,
        ): AvatarCache =
            avatarCaches[networkId]?.takeIf { it.client === client }
                ?: AvatarCache(client).also { avatarCaches[networkId] = it }

        private suspend fun withSharedAvatars(block: suspend () -> Unit) {
            cacheLock.withLock {
                if (prefs.config.first().showSharedAvatars) block()
            }
        }

        private val IrcClient.ready get() = state.value as? IrcClientState.Ready

        suspend fun onReady(
            networkId: Long,
            client: IrcClient,
        ) {
            activateMetadata(networkId, client, replaySelf = true)
        }

        private suspend fun activateMetadata(
            networkId: Long,
            client: IrcClient,
            replaySelf: Boolean,
        ) {
            val caps = client.ready?.caps ?: return
            val version = metadataVersion(caps) ?: return
            val show = prefs.config.first().showSharedAvatars
            if (version == METADATA_CAP || (show && supportsMetadataSubscription(caps))) {
                updateSubscription(networkId, client, show)
            }
            if (!replaySelf) return
            val url =
                when (val self = prefs.selfSetting(networkId).first()) {
                    SelfAvatarSetting.Unmanaged -> return
                    SelfAvatarSetting.ExplicitlyCleared -> null
                    is SelfAvatarSetting.Set -> self.url
                }
            if (connections.get().clientFor(networkId) === client) {
                client.publishSelfAvatarIfReady(networkId, url)
            }
        }

        suspend fun onEvent(
            networkId: Long,
            event: IrcEvent,
            positiveMetadataOnly: Boolean = false,
        ) {
            if (event.hasDickordAvatarObservation() && prefs.config.first().showSharedAvatars) {
                ingestDickordAvatarsEnabled(networkId, event)
            }
            when (event) {
                is IrcEvent.Raw -> {
                    handleRaw(networkId, event)
                }

                is IrcEvent.MetadataSnapshot -> {
                    handleSnapshot(networkId, event, positiveMetadataOnly)
                }

                is IrcEvent.Registered -> {
                    if (event.startsSession) {
                        cacheLock.withLock { avatarCaches.remove(networkId) }
                        expectMetadataSnapshots(networkId, listOf("*"), registration = true)
                    }
                }

                is IrcEvent.StandardReply -> {
                    finishFailedMetadata(networkId, event)
                }

                is IrcEvent.Disconnected -> {
                    forgetMetadataRequests(networkId)
                    cacheLock.withLock { avatarCaches.remove(networkId) }
                    onlineMonitors.remove(networkId)
                    selfWrites.remove(networkId)
                }

                is IrcEvent.MonitorOnline -> {
                    val online = onlineMonitors.getOrPut(networkId) { ConcurrentHashMap.newKeySet() }
                    event.identities.forEach { online += it.nick }
                    expectMetadataSnapshots(networkId, event.identities.map { it.nick })
                }

                is IrcEvent.MonitorOffline -> {
                    val client = connections.get().clientFor(networkId) ?: return
                    val offline = event.nicks.mapTo(hashSetOf(), client.isupport::normalize)
                    onlineMonitors[networkId]?.removeAll { client.isupport.normalize(it) in offline }
                }

                is IrcEvent.NickChanged -> {
                    withSharedAvatars {
                        val client = connections.get().clientFor(networkId)
                        if (client != null && event.wireSequence > 0L) {
                            val cache = avatarCache(networkId, client)
                            val from = client.isupport.normalize(event.from)
                            val to = client.isupport.normalize(event.to)
                            cache.renames.getOrPut(from, ::mutableListOf).add(AvatarRename(event.to, event.wireSequence))
                            if (from != to) {
                                val prior = cache.latest.remove(from)
                                cache.latest.remove(to)
                                if (prior != null) cache.latest[to] = prior
                            }
                        }
                        store.rename(networkId, event.from, event.to, account = null)
                    }
                }

                is IrcEvent.AccountChanged -> {
                    store.rename(networkId, event.nick, event.nick, event.account)
                }

                is IrcEvent.Joined -> {
                    if (event.isSelf && prefs.config.first().showSharedAvatars) {
                        val client = connections.get().clientFor(networkId) ?: return
                        if (client.supportsV2Subscriptions()) {
                            client.send(syncMetadataMessage(event.channel))
                        } else if (metadataVersion(client.ready?.caps.orEmpty()) == METADATA_V3_CAP) {
                            withSharedAvatars {
                                avatarCache(networkId, client).awaitingNames[client.isupport.normalize(event.channel)] = null
                            }
                            expectMetadataSnapshots(networkId, listOf(event.channel))
                        }
                    }
                }

                is IrcEvent.Names -> {
                    val client = connections.get().clientFor(networkId) ?: return
                    withSharedAvatars {
                        val cache = avatarCaches[networkId]?.takeIf { it.client === client } ?: return@withSharedAvatars
                        val omitted = cache.awaitingNames.remove(client.isupport.normalize(event.channel)) ?: return@withSharedAvatars
                        val scope = event.members.mapTo(hashSetOf()) { client.isupport.normalize(it.nick) }
                        removeOmittedAvatars(networkId, cache, scope, omitted)
                    }
                }

                is IrcEvent.CapsChanged -> {
                    val client = connections.get().clientFor(networkId) ?: return
                    if (event.removed.any { it.substringBefore('=') == "batch" || it.isAvatarMetadataCap() }) {
                        forgetMetadataRequests(networkId, replayUnconfirmedSelf = true)
                    }
                    if (event.added.any { it.isAvatarMetadataCap() } ||
                        ("batch" in event.added && metadataVersion(client.ready?.caps.orEmpty()) == METADATA_V3_CAP)
                    ) {
                        activateMetadata(networkId, client, replaySelf = selfWrites[networkId] !== client)
                    }
                }

                else -> {}
            }
        }

        private fun String.isAvatarMetadataCap(): Boolean = substringBefore('=').let { it == METADATA_CAP || it == METADATA_V3_CAP }

        private fun IrcClient.supportsV2Subscriptions(): Boolean = ready?.caps?.let { metadataVersion(it) == METADATA_CAP && supportsMetadataSubscription(it) } == true

        private suspend fun expectMetadataSnapshots(
            networkId: Long,
            targets: List<String>,
            registration: Boolean = false,
        ) {
            val client = connections.get().clientFor(networkId) ?: return
            if (metadataVersion(client.ready?.caps.orEmpty()) != METADATA_V3_CAP) return
            metadataLock.withLock {
                if (!registration && avatarSubscriptions[networkId] !== client) return@withLock
                val now = System.nanoTime()
                targets.forEach { target ->
                    expectedMetadataSnapshots[client.metadataKey(networkId, "SYNC", target)] = now
                }
            }
        }

        override suspend fun ingestDickordAvatars(
            networkId: Long,
            events: List<IrcEvent>,
        ) {
            if (events.none { it.hasDickordAvatarObservation() }) return
            if (!prefs.config.first().showSharedAvatars) return
            events.forEach { ingestDickordAvatarsEnabled(networkId, it) }
        }

        private suspend fun ingestDickordAvatarsEnabled(
            networkId: Long,
            event: IrcEvent,
        ) {
            if (!event.hasDickordAvatarObservation()) return
            when (event) {
                is IrcEvent.ChatMessage -> {
                    val avatar = event.ctx.clientTags[DICKORD_AVATAR_TAG] ?: return
                    if (avatar.isEmpty()) {
                        store.remove(networkId, event.source.nick, account = null)
                    } else {
                        validateAvatarUrl(avatar)?.let {
                            store.upsert(networkId, event.source.nick, account = null, url = it)
                        }
                    }
                }

                is IrcEvent.HistoryBatch -> {
                    event.events.forEach { ingestDickordAvatarsEnabled(networkId, it) }
                }

                is IrcEvent.PlaybackBatch -> {
                    event.items.forEach { ingestDickordAvatarsEnabled(networkId, it.event) }
                }

                is IrcEvent.ReplayBatch -> {
                    event.events.forEach { ingestDickordAvatarsEnabled(networkId, it) }
                }

                else -> {}
            }
        }

        private fun IrcEvent.hasDickordAvatarObservation(): Boolean =
            when (this) {
                is IrcEvent.ChatMessage -> {
                    DICKORD_AVATAR_TAG in ctx.clientTags &&
                        isDickordChannel(target) &&
                        isDickordRelayNick(source.nick)
                }

                is IrcEvent.HistoryBatch -> {
                    events.any { it.hasDickordAvatarObservation() }
                }

                is IrcEvent.PlaybackBatch -> {
                    items.any { it.event.hasDickordAvatarObservation() }
                }

                is IrcEvent.ReplayBatch -> {
                    events.any { it.hasDickordAvatarObservation() }
                }

                else -> {
                    false
                }
            }

        private suspend fun handleRaw(
            networkId: Long,
            event: IrcEvent.Raw,
        ) {
            val message = event.message
            if (message.command !in avatarRawCommands) return
            val client = connections.get().clientFor(networkId) ?: return
            val ready = client.ready ?: return
            val version = metadataVersion(ready.caps) ?: return
            val v3 = version == METADATA_V3_CAP
            if (v3 && (!client.isMetadataReply(message) || "batch" in message.tags || "label" in message.tags)) return
            if (v3 && message.command == "775") {
                handleRateLimit(networkId, client, message)
                return
            }
            if (v3 && (message.command == "770" || message.command == "771")) {
                if ((1 until message.params.size).any { message.params[it] == AVATAR_KEY }) {
                    metadataLock.withLock {
                        val key = client.metadataKey(networkId, "SUB", "*")
                        val expected = if (message.command == "770") "SUB" else "UNSUB"
                        val request = pendingMetadata[key]
                        if (request != null && request.message.params[1] == expected) {
                            removeMetadataRequest(key)
                            if (expected == "SUB") {
                                avatarSubscriptions[networkId] = client
                                val sync = client.metadataKey(networkId, "SYNC", "*ALL")
                                expectedMetadataSnapshots[sync] = System.nanoTime()
                                if (request.refresh && sync !in pendingMetadata) {
                                    val refresh = MetadataRequest(syncMetadataMessage("*ALL"), refresh = true)
                                    refresh.writtenAt = null
                                    pendingMetadata[sync] = refresh
                                    scheduleMetadataRetry(sync, refresh, 0L)
                                }
                            } else {
                                avatarSubscriptions.remove(networkId)
                            }
                        }
                    }
                }
                return
            }
            val metadata = parseAvatarMetadata(message, v3, selfNick = ready.nick) ?: return
            if (v3 && metadata !is AvatarMetadataEvent.SyncLater) {
                val target = message.params[1]
                metadataLock.withLock {
                    val key = client.metadataKey(networkId, "SET", client.resolveMetadataTarget(target))
                    val pending = pendingMetadata[key]
                    if (pending != null && !pending.labeled && pending.retry == null &&
                        (key.target == "*" || metadata.matchesRequestedValue(pending.message.params.getOrNull(3)))
                    ) {
                        removeMetadataRequest(key)
                    }
                }
            }
            if (metadata is AvatarMetadataEvent.SyncLater) {
                if (!prefs.config.first().showSharedAvatars) return
                if (v3) {
                    if (!supportsMetadataSubscription(ready.caps)) return
                    val request = syncMetadataMessage(metadata.target)
                    metadataLock.withLock {
                        if (avatarSubscriptions[networkId] !== client) return@withLock
                        val key = client.metadataKey(networkId, "SYNC", metadata.target)
                        expectedMetadataSnapshots.remove(key)
                        val pending = pendingMetadata.getOrPut(key) { MetadataRequest(request) }
                        pending.writtenAt = null
                        scheduleMetadataRetry(key, pending, metadata.retryAfterSeconds)
                    }
                } else {
                    scheduleDelayedSync(networkId, metadata)
                }
            } else {
                withSharedAvatars {
                    if (connections.get().clientFor(networkId) !== client) return@withSharedAvatars
                    storeAvatar(networkId, metadata, avatarCache(networkId, client), message.wireSequence)
                }
            }
        }

        private fun AvatarMetadataEvent.matchesRequestedValue(url: String?): Boolean =
            when (this) {
                is AvatarMetadataEvent.Changed -> this.url == url
                is AvatarMetadataEvent.Removed -> url == null
                is AvatarMetadataEvent.SyncLater -> false
            }

        private suspend fun storeAvatar(
            networkId: Long,
            metadata: AvatarMetadataEvent,
            cache: AvatarCache,
            sequence: Long,
        ) {
            val original =
                when (metadata) {
                    is AvatarMetadataEvent.Changed -> metadata.target
                    is AvatarMetadataEvent.Removed -> metadata.target
                    is AvatarMetadataEvent.SyncLater -> return
                }
            val nick = cache.currentNick(original, sequence)
            val normalized = cache.client.isupport.normalize(nick)
            if (sequence > 0L) {
                if ((cache.latest[normalized] ?: 0L) > sequence) return
                cache.latest[normalized] = sequence
            }
            when (metadata) {
                is AvatarMetadataEvent.Changed -> {
                    val user = userDao.byNick(networkId, nick.lowercase())
                    store.upsert(networkId, nick, user?.account, metadata.url)
                }

                is AvatarMetadataEvent.Removed -> {
                    val user = userDao.byNick(networkId, nick.lowercase())
                    store.remove(networkId, nick, user?.account)
                }

                is AvatarMetadataEvent.SyncLater -> {
                    return
                }
            }
        }

        private suspend fun handleSnapshot(
            networkId: Long,
            event: IrcEvent.MetadataSnapshot,
            positiveOnly: Boolean,
        ) {
            val client = connections.get().clientFor(networkId) ?: return
            val ready = client.ready ?: return
            if (metadataVersion(ready.caps) != METADATA_V3_CAP || !isMetadataTarget(event.target)) return
            withSharedAvatars {
                if (connections.get().clientFor(networkId) !== client ||
                    (
                        event.openedAtNanos != 0L && sharedAvatarsEnabledAtNanos != 0L &&
                            event.openedAtNanos - sharedAvatarsEnabledAtNanos < 0L
                    )
                ) {
                    return@withSharedAvatars
                }
                val cache = avatarCache(networkId, client)
                val target = if (event.target == "*") ready.nick else event.target
                val authoritative =
                    metadataLock.withLock {
                        val currentTarget = cache.currentNick(target, event.openingSequence)
                        val key = client.metadataKey(networkId, "SYNC", currentTarget)
                        val expectedAt = expectedMetadataSnapshots[key]
                        val sync = pendingMetadata[key]
                        val now = System.nanoTime()
                        val synced = sync?.writtenAt?.let { now - it <= METADATA_SNAPSHOT_WINDOW_NS } == true
                        val automatic = expectedAt != null && now - expectedAt <= METADATA_SNAPSHOT_WINDOW_NS
                        if (!positiveOnly) {
                            expectedMetadataSnapshots.remove(key)
                            if (synced || (automatic && sync?.refresh == true)) removeMetadataRequest(key)
                        }
                        synced || automatic
                    }
                if (authoritative) applyMetadataSnapshot(networkId, cache, ready.nick, target, event, positiveOnly)
            }
        }

        private suspend fun applyMetadataSnapshot(
            networkId: Long,
            cache: AvatarCache,
            selfNick: String,
            target: String,
            event: IrcEvent.MetadataSnapshot,
            positiveOnly: Boolean,
        ) {
            val client = cache.client
            val normalize = client.isupport::normalize
            val seen = hashSetOf<String>()
            for (message in event.messages) {
                if (positiveOnly && message.command != "761") continue
                val metadata =
                    if (message.command == "WARN" && message.params.size == 5 && message.params[0] == "METADATA" &&
                        message.params[1] == "KEY_NO_PERMISSION" && message.params[3] == AVATAR_KEY &&
                        isMetadataTarget(message.params[2])
                    ) {
                        AvatarMetadataEvent.Removed(if (message.params[2] == "*") selfNick else message.params[2])
                    } else {
                        val recipient = message.params.firstOrNull()?.let { cache.currentNick(it, message.wireSequence) }
                        if (!client.isMetadataReply(message, recipient)) continue
                        parseAvatarMetadata(message, v3 = true, selfNick = selfNick) ?: continue
                    }
                if (positiveOnly && metadata !is AvatarMetadataEvent.Changed) continue
                when (metadata) {
                    is AvatarMetadataEvent.Changed -> seen += normalize(cache.currentNick(metadata.target, message.wireSequence))
                    is AvatarMetadataEvent.Removed -> seen += normalize(cache.currentNick(metadata.target, message.wireSequence))
                    is AvatarMetadataEvent.SyncLater -> continue
                }
                storeAvatar(networkId, metadata, cache, message.wireSequence)
            }
            if (positiveOnly || event.messages.any { it.command == "FAIL" }) return

            val omitted = AvatarOmission(seen, event.openingSequence)
            val scope = hashSetOf(normalize(cache.currentNick(target, event.openingSequence)))
            val channels =
                when {
                    target == "*ALL" -> {
                        scope += normalize(selfNick)
                        onlineMonitors[networkId]?.mapTo(scope, normalize)
                        client.joinedChannels.value
                    }

                    client.isupport.isChannel(target) -> {
                        setOf(target)
                    }

                    else -> {
                        emptySet()
                    }
                }
            for (channel in channels) {
                val normalized = normalize(channel)
                scope += normalized
                if (normalized in cache.awaitingNames) {
                    val earlier = cache.awaitingNames[normalized]
                    if (earlier == null || earlier.openingSequence <= omitted.openingSequence) cache.awaitingNames[normalized] = omitted
                    continue
                }
                val buffer = bufferDao.byName(networkId, normalized) ?: continue
                memberDao.allNow(buffer.id).mapTo(scope) { normalize(it.nick) }
            }
            removeOmittedAvatars(networkId, cache, scope, omitted)
        }

        private suspend fun removeOmittedAvatars(
            networkId: Long,
            cache: AvatarCache,
            scope: Set<String>,
            omitted: AvatarOmission,
        ) {
            for (record in store.records.first()) {
                if (record.networkId != networkId) continue
                val nick = cache.client.isupport.normalize(record.nick)
                if (nick !in scope || nick in omitted.seen || (cache.latest[nick] ?: 0L) > omitted.openingSequence) continue
                store.remove(networkId, record.nick, record.account)
            }
            if (omitted.openingSequence > 0L) {
                for (nick in scope) {
                    if (nick !in omitted.seen && (cache.latest[nick] ?: 0L) < omitted.openingSequence) {
                        cache.latest[nick] = omitted.openingSequence
                    }
                }
            }
        }

        private fun IrcClient.isMetadataReply(
            message: IrcMessage,
            recipient: String? = message.params.firstOrNull(),
        ): Boolean =
            message.source?.user == null && message.source?.host == null &&
                recipient?.let { isupport.normalize(it) == isupport.normalize(ready?.nick.orEmpty()) } == true

        private fun IrcClient.resolveMetadataTarget(target: String): String = if (target == "*" || isupport.normalize(target) == isupport.normalize(ready?.nick.orEmpty())) "*" else target

        private fun IrcClient.metadataKey(
            networkId: Long,
            operation: String,
            target: String,
        ): MetadataRequestKey =
            MetadataRequestKey(
                networkId,
                this,
                if (operation == "UNSUB") "SUB" else operation,
                isupport.normalize(if (operation == "SYNC") resolveMetadataTarget(target) else target),
            )

        private fun IrcClient.metadataKey(
            networkId: Long,
            message: IrcMessage,
        ): MetadataRequestKey = metadataKey(networkId, message.params[1], message.params[0])

        private fun removeMetadataRequest(key: MetadataRequestKey) {
            pendingMetadata.remove(key)?.retry?.cancel()
        }

        private suspend fun forgetMetadataRequests(
            networkId: Long,
            operation: String? = null,
            target: String? = null,
            replayUnconfirmedSelf: Boolean = false,
        ) = metadataLock.withLock {
            if (operation == null) {
                expectedMetadataSnapshots.keys.removeAll { it.networkId == networkId }
                avatarSubscriptions.remove(networkId)
            }
            val requests = pendingMetadata.iterator()
            while (requests.hasNext()) {
                val (key, value) = requests.next()
                if (key.networkId == networkId && (operation == null || key.operation == operation) &&
                    (target == null || key.target == target)
                ) {
                    if (replayUnconfirmedSelf && key.operation == "SET" && key.target == "*") selfWrites.remove(networkId, key.client)
                    value.retry?.cancel()
                    requests.remove()
                }
            }
        }

        private suspend fun handleRateLimit(
            networkId: Long,
            client: IrcClient,
            message: IrcMessage,
            expected: MetadataRequest? = null,
        ): Boolean {
            if (message.command != "775" || !client.isMetadataReply(message) || message.params.size !in 4..6) return false
            val seconds = metadataRetrySeconds(message.params[1]) ?: return false
            val key = client.metadataKey(networkId, message.params[3], message.params[2])
            return metadataLock.withLock {
                val request = pendingMetadata[key] ?: return@withLock false
                val writtenAt = request.writtenAt ?: return@withLock false
                if (System.nanoTime() - writtenAt > METADATA_SNAPSHOT_WINDOW_NS || request.labeled != (expected != null) ||
                    (expected != null && request !== expected) || request.message.params != message.params.subList(2, message.params.size)
                ) {
                    return@withLock false
                }
                scheduleMetadataRetry(key, request, seconds)
                true
            }
        }

        private fun scheduleMetadataRetry(
            key: MetadataRequestKey,
            request: MetadataRequest,
            seconds: Long,
        ) {
            val retrySeconds = maxOf(request.retrySeconds, seconds)
            val delayMs = if (retrySeconds == 0L) MIN_METADATA_RETRY_MS else retrySeconds.coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000
            request.retry?.cancel()
            request.retrySeconds = retrySeconds
            request.retry =
                scope.launch {
                    delay(delayMs)
                    val labeled =
                        metadataLock.withLock {
                            if (pendingMetadata[key] !== request) return@withLock false
                            request.retry = null
                            request.retrySeconds = 0L
                            val client = key.client
                            val caps = client.ready?.caps.orEmpty()
                            val allowed =
                                when (request.message.params[1]) {
                                    "SUB", "SYNC" -> {
                                        prefs.config.first().showSharedAvatars && supportsMetadataSubscription(caps)
                                    }

                                    "UNSUB" -> {
                                        !prefs.config.first().showSharedAvatars
                                    }

                                    "SET" -> {
                                        val url = request.message.params.getOrNull(3)
                                        val self = key.target == "*"
                                        avatarWriteAllowed(caps, url, self) &&
                                            (!self || prefs.selfSetting(key.networkId).first() == selfSettingFor(url))
                                    }

                                    else -> {
                                        false
                                    }
                                }
                            val active = connections.get().clientFor(key.networkId) === client && metadataVersion(caps) == METADATA_V3_CAP
                            when {
                                !active || !allowed -> {
                                    removeMetadataRequest(key)
                                }

                                request.labeled && client.hasCap("labeled-response") -> {
                                    request.writtenAt = System.nanoTime()
                                    return@withLock true
                                }

                                else -> {
                                    request.labeled = false
                                    if (client.sendOptionalMetadata(request.message)) {
                                        request.writtenAt = System.nanoTime()
                                    } else {
                                        removeMetadataRequest(key)
                                    }
                                }
                            }
                            false
                        }
                    if (labeled) publishLabeledAvatar(key, request, v3 = true)
                }
        }

        private suspend fun finishFailedMetadata(
            networkId: Long,
            event: IrcEvent.StandardReply,
        ) {
            if (event.commandName != "METADATA" || event.ctx.batchId != null || event.ctx.label != null ||
                (
                    event.severity != IrcEvent.StandardReplySeverity.FAIL &&
                        !(event.severity == IrcEvent.StandardReplySeverity.WARN && event.code == "INVALID_KEY")
                )
            ) {
                return
            }
            val client = connections.get().clientFor(networkId) ?: return
            metadataLock.withLock {
                val requests = pendingMetadata.iterator()
                while (requests.hasNext()) {
                    val (key, request) = requests.next()
                    if (key.networkId != networkId || key.client !== client || request.labeled) continue
                    val refused =
                        when (event.code) {
                            "INVALID_TARGET", "KEY_NO_PERMISSION" -> {
                                val target = event.context.firstOrNull() ?: continue
                                if (event.code == "KEY_NO_PERMISSION" && (key.operation != "SET" || event.context.getOrNull(1) != AVATAR_KEY)) {
                                    continue
                                }
                                key.target == client.isupport.normalize(client.resolveMetadataTarget(target))
                            }

                            "INVALID_PARAMS" -> {
                                request.message.params[1] == event.context.firstOrNull()
                            }

                            "LIMIT_REACHED" -> {
                                request.message.params[1] == event.context.firstOrNull() &&
                                    event.context.getOrNull(1)?.let { item ->
                                        if (key.operation == "SUB") item == AVATAR_KEY else client.metadataKey(networkId, key.operation, item) == key
                                    } == true
                            }

                            "INVALID_KEY", "INVALID_VALUE" -> {
                                event.context.firstOrNull() == AVATAR_KEY &&
                                    (event.severity == IrcEvent.StandardReplySeverity.FAIL) == (key.operation == "SET")
                            }

                            else -> {
                                false
                            }
                        }
                    if (refused) {
                        if (key.operation == "SET" && key.target == "*") selfWrites.remove(networkId, client)
                        request.retry?.cancel()
                        requests.remove()
                    }
                }
            }
        }

        private suspend fun IrcClient.sendAvatarOperation(
            networkId: Long,
            message: IrcMessage,
            refresh: Boolean = false,
        ): Boolean {
            when (metadataVersion(ready?.caps.orEmpty())) {
                null -> return false
                METADATA_CAP -> return sendOptionalMetadata(message)
            }
            val key = metadataKey(networkId, message)
            return metadataLock.withLock {
                removeMetadataRequest(key)
                pendingMetadata[key] = MetadataRequest(message, refresh = refresh)
                var sent = false
                try {
                    sent = sendOptionalMetadata(message)
                    if (sent && key.operation == "SET" && key.target == "*") selfWrites[networkId] = this
                    sent
                } finally {
                    if (!sent) removeMetadataRequest(key)
                }
            }
        }

        private suspend fun updateSubscription(
            networkId: Long,
            client: IrcClient,
            show: Boolean,
            refresh: Boolean = false,
        ) {
            val caps = client.ready?.caps ?: return
            if (metadataVersion(caps) == null) return
            if (show && metadataVersion(caps) == METADATA_V3_CAP && !supportsMetadataSubscription(caps)) return
            val message =
                if (show && supportsMetadataSubscription(caps)) {
                    subscribeMetadataMessage(AVATAR_KEY)
                } else {
                    unsubscribeMetadataMessage(AVATAR_KEY)
                }
            client.sendAvatarOperation(networkId, message, refresh)
        }

        private fun scheduleDelayedSync(
            networkId: Long,
            metadata: AvatarMetadataEvent.SyncLater,
        ) {
            val key = "$networkId:${metadata.target}"
            if (delayedSyncs[key]?.isActive == true) return
            delayedSyncs[key] =
                scope.launch {
                    try {
                        delay(metadata.retryAfterSeconds.coerceAtMost(MAX_SYNC_DELAY_SECONDS) * 1_000)
                        if (!prefs.config.first().showSharedAvatars) return@launch
                        connections
                            .get()
                            .clientFor(networkId)
                            ?.takeIf { it.supportsV2Subscriptions() }
                            ?.send(syncMetadataMessage(metadata.target))
                    } finally {
                        delayedSyncs.remove(key)
                    }
                }
        }

        override suspend fun setShowSharedAvatars(show: Boolean) {
            cacheLock.withLock {
                if (show && !prefs.config.first().showSharedAvatars) sharedAvatarsEnabledAtNanos = System.nanoTime()
                prefs.setShowSharedAvatars(show)
                if (!show) {
                    avatarCaches.clear()
                    store.clearAll()
                    metadataLock.withLock {
                        expectedMetadataSnapshots.clear()
                        avatarSubscriptions.clear()
                        val requests = pendingMetadata.keys.filter { it.operation == "SYNC" || it.operation == "SUB" }
                        requests.forEach(::removeMetadataRequest)
                    }
                }
            }
            for (networkId in connections
                .get()
                .connectionStates.value.keys) {
                val client = connections.get().clientFor(networkId) ?: continue
                updateSubscription(networkId, client, show, refresh = show)
            }
        }

        override suspend fun setSelfAvatar(
            networkId: Long,
            url: String?,
        ): Boolean {
            val validated = url?.let(::validateAvatarUrl)
            if (url != null && validated == null) return false
            prefs.setSelfSetting(networkId, selfSettingFor(validated))
            val client = connections.get().clientFor(networkId) ?: return true
            val selfNick = client.publishSelfAvatarIfReady(networkId, validated) ?: return true
            if (metadataVersion(client.ready?.caps.orEmpty()) == METADATA_V3_CAP) return true
            try {
                withSharedAvatars {
                    if (validated != null) {
                        store.upsert(networkId, selfNick, account = null, url = validated)
                    } else {
                        store.remove(networkId, selfNick)
                    }
                }
            } catch (failure: Exception) {
                // The preference is already saved; temporary storage failures only affect the shared cache.
                when (failure) {
                    is IOException, is SQLiteDiskIOException, is SQLiteFullException, is SQLiteCantOpenDatabaseException -> Unit
                    else -> throw failure
                }
            }
            return true
        }

        private fun selfSettingFor(url: String?): SelfAvatarSetting = url?.let(SelfAvatarSetting::Set) ?: SelfAvatarSetting.ExplicitlyCleared

        private fun avatarWriteAllowed(
            caps: Set<String>,
            url: String?,
            self: Boolean = true,
        ): Boolean = if (url == null) supportsAvatarMutation(caps) else supportsAvatarPublishing(caps, url, self)

        private suspend fun IrcClient.publishSelfAvatarIfReady(
            networkId: Long,
            url: String?,
        ): String? {
            val ready = ready ?: return null
            if (!avatarWriteAllowed(ready.caps, url)) {
                forgetMetadataRequests(networkId, "SET", "*")
                return null
            }
            if (!sendAvatarOperation(networkId, publishAvatarMessage(url))) return null
            if (metadataVersion(ready.caps) == METADATA_CAP) selfWrites[networkId] = this
            return ready.nick
        }

        private suspend fun IrcClient.sendOptionalMetadata(message: IrcMessage): Boolean =
            try {
                sendIfConnected(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }

        override suspend fun stopManagingSelfAvatar(networkId: Long) {
            prefs.setSelfSetting(networkId, SelfAvatarSetting.Unmanaged)
            forgetMetadataRequests(networkId, "SET", "*")
        }

        override suspend fun clearNetworkState(networkId: Long) {
            forgetMetadataRequests(networkId)
            cacheLock.withLock {
                avatarCaches.remove(networkId)
                store.clearNetwork(networkId)
            }
            onlineMonitors.remove(networkId)
            selfWrites.remove(networkId)
            prefs.setSelfSetting(networkId, SelfAvatarSetting.Unmanaged)
        }

        override suspend fun importConversationAvatar(
            bufferId: Long,
            source: Uri,
        ): ConversationAvatarOutcome {
            val room =
                bufferDao.observeById(bufferId)?.takeIf { it.type != BufferType.SERVER }
                    ?: return ConversationAvatarOutcome.Failed
            val imported =
                withContext(Dispatchers.IO) { localAvatars.import(source) }
                    .getOrElse { return ConversationAvatarOutcome.Invalid }
            if (bufferDao.setAvatarOverride(room.id, imported) != 1) {
                localAvatars.delete(imported)
                return ConversationAvatarOutcome.Failed
            }
            localAvatars.delete(room.avatarOverrideModel)
            return ConversationAvatarOutcome.LocalOnly
        }

        override suspend fun setConversationAvatar(
            bufferId: Long,
            url: String,
        ): ConversationAvatarOutcome {
            val validated = validateAvatarUrl(url) ?: return ConversationAvatarOutcome.Invalid
            val room =
                bufferDao.observeById(bufferId)?.takeIf { it.type != BufferType.SERVER }
                    ?: return ConversationAvatarOutcome.Failed
            if (bufferDao.setAvatarOverride(room.id, validated) != 1) return ConversationAvatarOutcome.Failed
            localAvatars.delete(room.avatarOverrideModel)
            return if (room.type == BufferType.CHANNEL) {
                publishChannelAvatar(room.id, room.networkId, room.displayName, validated)
            } else {
                ConversationAvatarOutcome.LocalOnly
            }
        }

        override suspend fun resetConversationAvatar(bufferId: Long): ConversationAvatarOutcome {
            val room =
                bufferDao.observeById(bufferId)?.takeIf { it.type != BufferType.SERVER }
                    ?: return ConversationAvatarOutcome.Failed
            if (bufferDao.setAvatarOverride(room.id, null) != 1) return ConversationAvatarOutcome.Failed
            localAvatars.delete(room.avatarOverrideModel)
            return ConversationAvatarOutcome.LocalReset
        }

        override suspend fun shareConversationAvatar(bufferId: Long): ConversationAvatarOutcome {
            val room =
                bufferDao.observeById(bufferId)?.takeIf { it.type == BufferType.CHANNEL }
                    ?: return ConversationAvatarOutcome.Invalid
            val url =
                room.avatarOverrideModel?.let(::validateAvatarUrl)
                    ?: return ConversationAvatarOutcome.Invalid
            return publishChannelAvatar(room.id, room.networkId, room.displayName, url)
        }

        override suspend fun clearSharedConversationAvatar(bufferId: Long): ConversationAvatarOutcome {
            val room =
                bufferDao.observeById(bufferId)?.takeIf { it.type == BufferType.CHANNEL }
                    ?: return ConversationAvatarOutcome.Invalid
            return publishChannelAvatar(room.id, room.networkId, room.displayName, null)
        }

        private suspend fun publishChannelAvatar(
            roomId: Long,
            networkId: Long,
            target: String,
            url: String?,
        ): ConversationAvatarOutcome {
            val client = connections.get().clientFor(networkId) ?: return ConversationAvatarOutcome.LocalOnly
            val caps = client.ready?.caps ?: return ConversationAvatarOutcome.LocalOnly
            val message = channelAvatarMessage(target, url)
            val key = client.metadataKey(networkId, message)
            if (!avatarWriteAllowed(caps, url, self = false)) {
                metadataLock.withLock { removeMetadataRequest(key) }
                return ConversationAvatarOutcome.LocalOnly
            }
            if (!client.hasCap("labeled-response")) {
                return if (client.sendAvatarOperation(networkId, message)) {
                    ConversationAvatarOutcome.RequestSent
                } else {
                    ConversationAvatarOutcome.LocalOnly
                }
            }
            val v3 = metadataVersion(caps) == METADATA_V3_CAP
            val request = MetadataRequest(message, roomId)
            if (v3) {
                metadataLock.withLock {
                    removeMetadataRequest(key)
                    pendingMetadata[key] = request
                }
            }
            return publishLabeledAvatar(key, request, v3)
        }

        private suspend fun publishLabeledAvatar(
            key: MetadataRequestKey,
            request: MetadataRequest,
            v3: Boolean,
        ): ConversationAvatarOutcome {
            val client = key.client
            val networkId = key.networkId
            val url = request.message.params.getOrNull(3)
            if (v3 && metadataLock.withLock { pendingMetadata[key] !== request }) return ConversationAvatarOutcome.LocalOnly
            val response =
                try {
                    client.sendLabeled(request.message)
                } catch (cancelled: CancellationException) {
                    if (v3) {
                        withContext(NonCancellable) {
                            metadataLock.withLock {
                                if (pendingMetadata[key] === request) removeMetadataRequest(key)
                            }
                        }
                    }
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            if (v3) {
                for (reply in response) {
                    if (handleRateLimit(networkId, client, reply, expected = request)) return ConversationAvatarOutcome.RequestSent
                }
                val current =
                    metadataLock.withLock {
                        (pendingMetadata[key] === request).also { if (it) removeMetadataRequest(key) }
                    }
                if (!current) return ConversationAvatarOutcome.LocalOnly
            }
            if (avatarMetadataRejected(response)) return ConversationAvatarOutcome.LocalOnly
            val (returned, sequence) =
                response.firstNotNullOfOrNull { reply ->
                    if (v3 && !client.isMetadataReply(reply)) return@firstNotNullOfOrNull null
                    val event = parseAvatarMetadata(reply, v3, selfNick = client.ready?.nick) ?: return@firstNotNullOfOrNull null
                    val target =
                        when (event) {
                            is AvatarMetadataEvent.Changed -> event.target
                            is AvatarMetadataEvent.Removed -> event.target
                            is AvatarMetadataEvent.SyncLater -> return@firstNotNullOfOrNull null
                        }
                    if (client.isupport.normalize(target) == key.target) event to reply.wireSequence else null
                } ?: return ConversationAvatarOutcome.LocalOnly
            if (v3) {
                withSharedAvatars {
                    if (connections.get().clientFor(networkId) !== client) return@withSharedAvatars
                    storeAvatar(networkId, returned, avatarCache(networkId, client), sequence)
                }
            }
            return when {
                url == null && returned is AvatarMetadataEvent.Removed -> {
                    ConversationAvatarOutcome.SharedCleared
                }

                url != null && returned is AvatarMetadataEvent.Changed -> {
                    val roomId = checkNotNull(request.roomId)
                    if (v3) {
                        bufferDao.replaceChannelAvatarOverride(roomId, url, returned.url)
                    } else {
                        bufferDao.setAvatarOverride(roomId, returned.url)
                    }
                    ConversationAvatarOutcome.Shared
                }

                else -> {
                    ConversationAvatarOutcome.LocalOnly
                }
            }
        }

        override fun publishingAvailable(networkId: Long): Boolean {
            val client = connections.get().clientFor(networkId) ?: return false
            val ready = client.state.value as? IrcClientState.Ready ?: return false
            return supportsAvatarPublishing(ready.caps)
        }

        private companion object {
            const val MAX_SYNC_DELAY_SECONDS = 60L * 60L
            const val MIN_METADATA_RETRY_MS = 250L
            const val METADATA_SNAPSHOT_WINDOW_NS = 30_000_000_000L
            val avatarRawCommands = setOf("METADATA", "761", "766", "770", "771", "774", "775")
        }
    }
