package io.github.trevarj.motd.avatar

import android.net.Uri
import dagger.Lazy
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.dickord.DICKORD_AVATAR_TAG
import io.github.trevarj.motd.dickord.isDickordChannel
import io.github.trevarj.motd.dickord.isDickordRelayNick
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        private val localAvatars: LocalAvatarStore,
        private val connections: Lazy<ConnectionManager>,
        @ApplicationScope private val scope: CoroutineScope,
    ) : AvatarController {
        private val delayedSyncs = java.util.concurrent.ConcurrentHashMap<String, Job>()

        suspend fun onReady(
            networkId: Long,
            client: IrcClient,
        ) {
            if (!client.hasCap(METADATA_CAP)) return
            client.send(
                if (prefs.config.first().showSharedAvatars && supportsMetadataSubscription(client.caps)) {
                    subscribeMetadataMessage(AVATAR_KEY)
                } else {
                    unsubscribeMetadataMessage(AVATAR_KEY)
                },
            )
            when (val self = prefs.selfSetting(networkId).first()) {
                SelfAvatarSetting.Unmanaged -> {}

                SelfAvatarSetting.ExplicitlyCleared -> {
                    if (supportsAvatarMutation(client.caps)) {
                        client.send(publishAvatarMessage(null))
                    }
                }

                is SelfAvatarSetting.Set -> {
                    if (supportsAvatarPublishing(client.caps, self.url)) {
                        client.send(publishAvatarMessage(self.url))
                    }
                }
            }
        }

        suspend fun onEvent(
            networkId: Long,
            event: IrcEvent,
        ) {
            if (event.hasDickordAvatarObservation() && prefs.config.first().showSharedAvatars) {
                ingestDickordAvatarsEnabled(networkId, event)
            }
            when (event) {
                is IrcEvent.Raw -> {
                    handleRaw(networkId, event)
                }

                is IrcEvent.NickChanged -> {
                    store.rename(networkId, event.from, event.to, account = null)
                }

                is IrcEvent.AccountChanged -> {
                    store.rename(networkId, event.nick, event.nick, event.account)
                }

                is IrcEvent.Joined -> {
                    if (event.isSelf && prefs.config.first().showSharedAvatars) {
                        connections
                            .get()
                            .clientFor(networkId)
                            ?.takeIf { supportsMetadataSubscription(it.caps) }
                            ?.send(syncMetadataMessage(event.channel))
                    }
                }

                is IrcEvent.CapsChanged -> {
                    if (event.added.any { it == METADATA_CAP || it.startsWith("$METADATA_CAP=") }) {
                        connections.get().clientFor(networkId)?.let { onReady(networkId, it) }
                    }
                }

                else -> {}
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
            if (!prefs.config.first().showSharedAvatars) return
            when (val metadata = parseAvatarMetadata(event.message)) {
                is AvatarMetadataEvent.Changed -> {
                    val user = userDao.byNick(networkId, metadata.target.lowercase())
                    store.upsert(networkId, metadata.target, user?.account, metadata.url)
                }

                is AvatarMetadataEvent.Removed -> {
                    val user = userDao.byNick(networkId, metadata.target.lowercase())
                    store.remove(networkId, metadata.target, user?.account)
                }

                is AvatarMetadataEvent.SyncLater -> {
                    scheduleDelayedSync(networkId, metadata)
                }

                null -> {}
            }
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
                            ?.takeIf { supportsMetadataSubscription(it.caps) }
                            ?.send(syncMetadataMessage(metadata.target))
                    } finally {
                        delayedSyncs.remove(key)
                    }
                }
        }

        override suspend fun setShowSharedAvatars(show: Boolean) {
            prefs.setShowSharedAvatars(show)
            if (!show) store.clearAll()
            for (networkId in connections
                .get()
                .connectionStates.value.keys) {
                connections.get().clientFor(networkId)?.let { client ->
                    if (!client.hasCap(METADATA_CAP)) return@let
                    client.send(
                        if (show && supportsMetadataSubscription(client.caps)) {
                            subscribeMetadataMessage(AVATAR_KEY)
                        } else {
                            unsubscribeMetadataMessage(AVATAR_KEY)
                        },
                    )
                }
            }
        }

        override suspend fun setSelfAvatar(
            networkId: Long,
            url: String?,
        ): Boolean {
            val validated = url?.let(::validateAvatarUrl)
            if (url != null && validated == null) return false
            val client = connections.get().clientFor(networkId)
            if (validated != null && client != null && !supportsAvatarPublishing(client.caps, validated)) {
                return false
            }
            val setting = validated?.let(SelfAvatarSetting::Set) ?: SelfAvatarSetting.ExplicitlyCleared
            prefs.setSelfSetting(networkId, setting)
            client
                ?.takeIf {
                    if (validated == null) {
                        supportsAvatarMutation(it.caps)
                    } else {
                        supportsAvatarPublishing(it.caps, validated)
                    }
                }?.send(publishAvatarMessage(validated))
            val selfNick = (client?.state?.value as? io.github.trevarj.motd.irc.event.IrcClientState.Ready)?.nick
            if (selfNick != null && prefs.config.first().showSharedAvatars) {
                if (validated != null) {
                    store.upsert(networkId, selfNick, account = null, url = validated)
                } else {
                    store.remove(networkId, selfNick)
                }
            }
            return true
        }

        override suspend fun stopManagingSelfAvatar(networkId: Long) {
            prefs.setSelfSetting(networkId, SelfAvatarSetting.Unmanaged)
        }

        override suspend fun clearNetworkState(networkId: Long) {
            store.clearNetwork(networkId)
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
            val supported =
                if (url == null) {
                    supportsAvatarMutation(client.caps)
                } else {
                    supportsAvatarPublishing(client.caps, url)
                }
            if (!supported) return ConversationAvatarOutcome.LocalOnly
            val message = channelAvatarMessage(target, url)
            if (!client.hasCap("labeled-response")) {
                return if (runCatching { client.send(message) }.isSuccess) {
                    ConversationAvatarOutcome.RequestSent
                } else {
                    ConversationAvatarOutcome.LocalOnly
                }
            }
            val response =
                runCatching { client.sendLabeled(message) }.getOrElse {
                    return ConversationAvatarOutcome.LocalOnly
                }
            if (avatarMetadataRejected(response)) return ConversationAvatarOutcome.LocalOnly
            val returned =
                response.mapNotNull(::parseAvatarMetadata).firstOrNull { event ->
                    when (event) {
                        is AvatarMetadataEvent.Changed -> event.target.equals(target, ignoreCase = true)
                        is AvatarMetadataEvent.Removed -> event.target.equals(target, ignoreCase = true)
                        is AvatarMetadataEvent.SyncLater -> false
                    }
                }
            return when {
                url == null && returned is AvatarMetadataEvent.Removed -> {
                    ConversationAvatarOutcome.SharedCleared
                }

                returned is AvatarMetadataEvent.Changed -> {
                    bufferDao.setAvatarOverride(roomId, returned.url)
                    ConversationAvatarOutcome.Shared
                }

                else -> {
                    ConversationAvatarOutcome.LocalOnly
                }
            }
        }

        override fun publishingAvailable(networkId: Long): Boolean = connections.get().clientFor(networkId)?.let { supportsAvatarPublishing(it.caps) } == true

        private companion object {
            const val MAX_SYNC_DELAY_SECONDS = 60L * 60L
        }
    }
