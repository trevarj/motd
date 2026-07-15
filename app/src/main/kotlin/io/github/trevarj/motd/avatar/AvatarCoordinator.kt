package io.github.trevarj.motd.avatar

import dagger.Lazy
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

interface AvatarController {
    suspend fun setShowSharedAvatars(show: Boolean)
    suspend fun setSelfAvatar(networkId: Long, url: String?): Boolean
    suspend fun stopManagingSelfAvatar(networkId: Long)
    suspend fun clearNetworkState(networkId: Long)
    fun publishingAvailable(networkId: Long): Boolean
}

@Singleton
class AvatarCoordinator @Inject constructor(
    private val prefs: AvatarPrefs,
    private val store: AvatarStore,
    private val userDao: UserDao,
    private val connections: Lazy<ConnectionManager>,
    @ApplicationScope private val scope: CoroutineScope,
) : AvatarController {
    private val delayedSyncs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    suspend fun onReady(networkId: Long, client: IrcClient) {
        if (!client.hasCap(AVATAR_CAP)) return
        if (prefs.config.first().showSharedAvatars && supportsAvatarSubscription(client.caps)) {
            client.send(subscribeAvatarMessage())
        } else {
            client.send(unsubscribeAvatarMessage())
        }
        when (val self = prefs.selfSetting(networkId).first()) {
            SelfAvatarSetting.Unmanaged -> Unit
            SelfAvatarSetting.ExplicitlyCleared -> if (supportsAvatarMutation(client.caps)) {
                client.send(publishAvatarMessage(null))
            }
            is SelfAvatarSetting.Set -> if (supportsAvatarPublishing(client.caps, self.url)) {
                client.send(publishAvatarMessage(self.url))
            }
        }
    }

    suspend fun onEvent(networkId: Long, event: IrcEvent) {
        when (event) {
            is IrcEvent.Raw -> handleRaw(networkId, event)
            is IrcEvent.NickChanged -> store.rename(networkId, event.from, event.to, account = null)
            is IrcEvent.AccountChanged -> {
                store.rename(networkId, event.nick, event.nick, event.account)
            }
            is IrcEvent.Joined -> if (event.isSelf && prefs.config.first().showSharedAvatars) {
                connections.get().clientFor(networkId)?.takeIf { supportsAvatarSubscription(it.caps) }
                    ?.send(syncAvatarMessage(event.channel))
            }
            is IrcEvent.CapsChanged -> if (event.added.any { it == AVATAR_CAP || it.startsWith("$AVATAR_CAP=") }) {
                connections.get().clientFor(networkId)?.let { onReady(networkId, it) }
            }
            else -> Unit
        }
    }

    private suspend fun handleRaw(networkId: Long, event: IrcEvent.Raw) {
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
            is AvatarMetadataEvent.SyncLater -> scheduleDelayedSync(networkId, metadata)
            null -> Unit
        }
    }

    private fun scheduleDelayedSync(networkId: Long, metadata: AvatarMetadataEvent.SyncLater) {
        val key = "$networkId:${metadata.target}"
        if (delayedSyncs[key]?.isActive == true) return
        delayedSyncs[key] = scope.launch {
            try {
                delay(metadata.retryAfterSeconds.coerceAtMost(MAX_SYNC_DELAY_SECONDS) * 1_000)
                if (!prefs.config.first().showSharedAvatars) return@launch
                connections.get().clientFor(networkId)?.takeIf { supportsAvatarSubscription(it.caps) }
                    ?.send(syncAvatarMessage(metadata.target))
            } finally {
                delayedSyncs.remove(key)
            }
        }
    }

    override suspend fun setShowSharedAvatars(show: Boolean) {
        prefs.setShowSharedAvatars(show)
        if (!show) store.clearAll()
        for (networkId in connections.get().connectionStates.value.keys) {
            connections.get().clientFor(networkId)?.let { client ->
                if (!client.hasCap(AVATAR_CAP)) return@let
                client.send(
                    if (show && supportsAvatarSubscription(client.caps)) subscribeAvatarMessage()
                    else unsubscribeAvatarMessage(),
                )
            }
        }
    }

    override suspend fun setSelfAvatar(networkId: Long, url: String?): Boolean {
        val validated = url?.let(::validateAvatarUrl)
        if (url != null && validated == null) return false
        val client = connections.get().clientFor(networkId)
        if (validated != null && client != null && !supportsAvatarPublishing(client.caps, validated)) {
            return false
        }
        val setting = validated?.let(SelfAvatarSetting::Set) ?: SelfAvatarSetting.ExplicitlyCleared
        prefs.setSelfSetting(networkId, setting)
        client?.takeIf {
            if (validated == null) supportsAvatarMutation(it.caps)
            else supportsAvatarPublishing(it.caps, validated)
        }?.send(publishAvatarMessage(validated))
        val selfNick = (client?.state?.value as? io.github.trevarj.motd.irc.event.IrcClientState.Ready)?.nick
        if (selfNick != null && prefs.config.first().showSharedAvatars) {
            if (validated != null) store.upsert(networkId, selfNick, account = null, url = validated)
            else store.remove(networkId, selfNick)
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

    override fun publishingAvailable(networkId: Long): Boolean =
        connections.get().clientFor(networkId)?.let { supportsAvatarPublishing(it.caps) } == true

    private companion object {
        const val MAX_SYNC_DELAY_SECONDS = 60L * 60L
    }
}
