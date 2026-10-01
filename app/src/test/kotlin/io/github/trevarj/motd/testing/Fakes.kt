package io.github.trevarj.motd.testing

import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.ext.SearchRequest
import io.github.trevarj.motd.irc.ext.SearchResultMessage
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ConnectionActivitySnapshot
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Inert [ConnectionManager] every test double builds on.
 *
 * The seam is wide and each suite needed only a few members of it, so every test used to carry its
 * own copy of the same do-nothing overrides. Holding the inert half here is what lets
 * [ConnectionManager] declare its members abstract: the interface no longer ships default bodies
 * purely so lightweight fakes still compile.
 *
 * State-bearing members are backed by mutable flows this class owns, so a subclass drives them
 * through [states] / [activity] / [prompts] instead of re-declaring the property.
 */
internal open class NoopConnectionManager(
    initialStates: Map<Long, IrcClientState> = emptyMap(),
    initialActivity: ConnectionActivitySnapshot = ConnectionActivitySnapshot(),
) : ConnectionManager {
    val states = MutableStateFlow(initialStates)
    val activity = MutableStateFlow(initialActivity)
    val prompts = MutableStateFlow<List<CertPrompt>>(emptyList())

    override val connectionStates: StateFlow<Map<Long, IrcClientState>> get() = states
    override val connectionActivity: StateFlow<ConnectionActivitySnapshot> get() = activity
    override val certPrompts: StateFlow<List<CertPrompt>> get() = prompts

    override val rosterStates: StateFlow<Map<Long, RosterLoadState>> = MutableStateFlow(emptyMap())
    override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> = MutableStateFlow(emptyMap())
    override val lagStates: StateFlow<Map<Long, Long?>> = MutableStateFlow(emptyMap())
    override val selfAwayStates: StateFlow<Map<Long, String?>> = MutableStateFlow(emptyMap())
    override val channelJoinOutcomes: Flow<ChannelJoinOutcome> = emptyFlow()

    override fun clientFor(networkId: Long): IrcClient? = null

    override suspend fun startAll() = Unit

    override suspend fun stopAll() = Unit

    override suspend fun connect(networkId: Long) = Unit

    override suspend fun disconnect(networkId: Long) = Unit

    override suspend fun reconnectStale() = Unit

    override suspend fun checkpointHistory(focusBufferId: Long?) = Unit

    override suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId?,
        channelContext: String?,
    ): SendAcceptance = SendAcceptance.Accepted(emptyList())

    override suspend fun sendNotificationReply(
        bufferId: Long,
        text: String,
    ): SendAcceptance = SendAcceptance.Accepted(emptyList())

    override suspend fun retryMessage(eventId: TimelineEventId): SendAcceptance = SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)

    override suspend fun sendTyping(
        bufferId: Long,
        state: String,
    ) = Unit

    override suspend fun sendReact(
        bufferId: Long,
        msgid: String,
        emoji: String,
    ) = Unit

    override suspend fun joinChannel(
        networkId: Long,
        channel: String,
        key: String?,
    ) = true

    override suspend fun acceptInvite(messageId: Long) = Unit

    override suspend fun dismissInvite(messageId: Long) = Unit

    override suspend fun requestMembers(
        bufferId: Long,
        force: Boolean,
    ) = Unit

    override suspend fun partChannel(
        bufferId: Long,
        reason: String?,
    ) = Unit

    /** Mirrors the real strict check: an inert fake has no live transport to accept the write. */
    override suspend fun partChannelForClose(
        bufferId: Long,
        reason: String?,
    ): Boolean = false

    override suspend fun setChannelTopic(
        bufferId: Long,
        topic: String,
    ): Boolean = false

    override suspend fun setAway(
        networkId: Long,
        message: String?,
    ) = Unit

    override fun serverSearchAvailable(networkId: Long): Boolean = clientFor(networkId)?.searchAvailable == true

    override fun historyAvailabilityFor(networkId: Long): HistoryAvailability = clientFor(networkId)?.historyAvailability ?: HistoryAvailability.NegotiatingOrOffline

    override suspend fun searchMessages(
        networkId: Long,
        request: SearchRequest,
    ): List<SearchResultMessage>? = clientFor(networkId)?.search(request)

    override suspend fun ensureQueryBuffer(
        networkId: Long,
        nick: String,
    ): Long = 0L

    override suspend fun ensureServerBuffer(networkId: Long): Long = 0L

    override suspend fun markRead(
        bufferId: Long,
        anchor: TimelineAnchor,
    ) = Unit

    override suspend fun evaluatePushMode() = Unit

    override suspend fun trustCert(prompt: CertPrompt) = Unit

    override fun dismissCertPrompt(prompt: CertPrompt) = Unit
}
