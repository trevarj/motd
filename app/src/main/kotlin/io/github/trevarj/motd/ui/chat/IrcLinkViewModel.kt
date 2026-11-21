package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.networkIdentityKey
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.channellist.exactChannelTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

internal enum class IrcLinkAction { OPEN, JOIN, CONNECT_AND_JOIN }

internal data class IrcLinkAccount(
    val id: Long,
    val identity: String,
    val name: String,
    val nick: String,
    val username: String,
    val saslUser: String?,
    val action: IrcLinkAction,
)

internal data class IrcLinkReview(
    val link: IrcChannelLink? = null,
    val accounts: List<IrcLinkAccount> = emptyList(),
    val selectedId: Long? = null,
    val loading: Boolean = false,
    val working: IrcLinkAction? = null,
    val error: String? = null,
    val joinedId: Long? = null,
)

@HiltViewModel
internal class IrcLinkViewModel
    @Inject
    constructor(
        private val networks: NetworkRepository,
        private val buffers: BufferRepository,
        private val connections: ConnectionManager,
        private val bouncerKinds: BouncerKindPrefs,
    ) : ViewModel() {
        private val _review = MutableStateFlow(IrcLinkReview())
        val review = _review.asStateFlow()
        private var work: Job? = null
        private var joinedClient: IrcClient? = null

        fun review(
            link: IrcChannelLink,
            sourceBufferId: Long,
        ) {
            if (_review.value.link != null) return
            _review.value = IrcLinkReview(link = link, loading = true)
            work =
                viewModelScope.launch {
                    try {
                        val accounts = matchingAccounts(link).map { account(it, link.channel) }
                        val sourceId = if (accounts.size > 1) buffers.buffer(sourceBufferId)?.networkId else null
                        val selectedId = accounts.firstOrNull { it.id == sourceId }?.id ?: accounts.singleOrNull()?.id
                        _review.value = IrcLinkReview(link = link, accounts = accounts, selectedId = selectedId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _review.value = _review.value.copy(error = error.message ?: "Could not read saved connections")
                    }
                }
        }

        fun dismiss() {
            work?.cancel()
            work = null
            joinedClient = null
            _review.value = IrcLinkReview()
        }

        fun consumeJoined(): Long? {
            val current = _review.value
            val bufferId = current.joinedId ?: return null
            val channel = current.link?.channel ?: return null
            val id = current.selectedId ?: return null
            val client = joinedClient
            if (client == null || readyClient(id) !== client || !client.hasMembership(channel)) {
                joinedClient = null
                _review.value = current.copy(joinedId = null, error = "Connection or channel membership changed before it could be opened")
                return null
            }
            dismiss()
            return bufferId
        }

        fun selectAccount(id: Long) {
            val current = _review.value
            if (current.working == null && current.accounts.any { it.id == id }) {
                _review.value = current.copy(selectedId = id, error = null)
            }
        }

        fun confirm() {
            val current = _review.value
            val link = current.link ?: return
            if (current.loading || current.working != null || current.joinedId != null) return
            val selected = current.accounts.firstOrNull { it.id == current.selectedId } ?: return
            _review.value = current.copy(working = selected.action, error = null)
            work =
                viewModelScope.launch {
                    try {
                        val latest = approvedAccount(link, selected) ?: return@launch
                        val id = latest.id
                        val client =
                            if (latest.action == IrcLinkAction.CONNECT_AND_JOIN) {
                                val ready = awaitReady(id)
                                approvedAccount(link, selected) ?: return@launch
                                ready
                            } else {
                                checkNotNull(readyClient(id)) { "Connection closed before channel join" }
                            }
                        check(readyClient(id) === client) { "Connection changed. Review the action again." }
                        check(exactChannelTarget(link.channel, client.isupport.identityRules) != null) { "This server does not support that channel name" }
                        _review.value = _review.value.copy(working = IrcLinkAction.JOIN)
                        val bufferId = awaitMembership(id, link.channel, client, selected.action != IrcLinkAction.OPEN)
                        joinedClient = client
                        _review.value = _review.value.copy(working = null, joinedId = bufferId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _review.value = _review.value.copy(working = null, error = error.message ?: "Channel join failed")
                    }
                }
        }

        private suspend fun approvedAccount(
            link: IrcChannelLink,
            selected: IrcLinkAccount,
        ): IrcLinkAccount? {
            val accounts = matchingAccounts(link).map { account(it, link.channel) }
            val latest = accounts.firstOrNull { it.id == selected.id }
            if (latest != null && latest.copy(action = selected.action) == selected && latest.action <= selected.action) return latest
            _review.value =
                IrcLinkReview(
                    link = link,
                    accounts = accounts,
                    selectedId = latest?.id ?: accounts.singleOrNull()?.id,
                    error = "The saved connection or channel membership changed. Review the action again.",
                )
            return null
        }

        private suspend fun matchingAccounts(link: IrcChannelLink): List<NetworkEntity> =
            matchingIrcLinkAccounts(link, networks.observeNetworks().first(), bouncerKinds.zncNetworkIds.first()) { rootId, netId ->
                connections
                    .clientFor(rootId)
                    ?.bouncerNetworks
                    ?.value
                    ?.get(netId)
            }

        private fun connectionState(id: Long): IrcClientState? = connections.clientFor(id)?.state?.value ?: connections.connectionStates.value[id]

        private fun readyClient(id: Long): IrcClient? = connections.clientFor(id)?.takeIf { it.state.value is IrcClientState.Ready }

        private fun IrcClient.hasMembership(channel: String): Boolean {
            val rules = isupport.identityRules
            val normalized = rules.normalize(channel)
            return joinedChannels.value.any { rules.normalize(it) == normalized }
        }

        private fun account(
            network: NetworkEntity,
            channel: String,
        ): IrcLinkAccount {
            val id = network.id
            val client = readyClient(id)
            val action =
                when {
                    client == null -> IrcLinkAction.CONNECT_AND_JOIN
                    client.hasMembership(channel) -> IrcLinkAction.OPEN
                    else -> IrcLinkAction.JOIN
                }
            return IrcLinkAccount(id, networkIdentityKey(network), network.name, network.nick, network.username, network.saslUser, action)
        }

        private suspend fun awaitReady(id: Long): IrcClient =
            checkNotNull(
                withTimeoutOrNull(30_000) {
                    readyClient(id)?.let { return@withTimeoutOrNull it }
                    when (connectionState(id)) {
                        is IrcClientState.Ready, is IrcClientState.Connecting, is IrcClientState.Registering -> Unit
                        else -> connections.connect(id)
                    }
                    connections.connectionStates
                        .mapNotNull { states ->
                            val state = states[id]
                            readyClient(id) ?: if (state is IrcClientState.Failed) error(state.reason) else null
                        }.first()
                },
            ) { "Connection timed out" }

        private suspend fun awaitMembership(
            id: Long,
            channel: String,
            client: IrcClient,
            allowJoin: Boolean,
        ): Long {
            val joinedId =
                withTimeoutOrNull(20_000) {
                    coroutineScope {
                        val disconnected =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                client.state.first { it !is IrcClientState.Ready }
                            }
                        val rejected =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                connections.channelJoinOutcomes.filterIsInstance<ChannelJoinOutcome.Rejected>().first {
                                    val rules = client.isupport.identityRules
                                    it.networkId == id && rules.normalize(it.channel) == rules.normalize(channel)
                                }
                            }
                        val joined =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                combine(client.joinedChannels, buffers.observeJoinedChannels(id)) { names, rows ->
                                    val rules = client.isupport.identityRules
                                    val normalized = rules.normalize(channel)
                                    if (readyClient(id) !== client || names.none { rules.normalize(it) == normalized }) return@combine null
                                    rows
                                        .firstOrNull { rules.normalize(it.displayName) == normalized }
                                        ?.bufferId
                                }.first { it != null }
                            }
                        try {
                            check(readyClient(id) === client) { "Connection closed before channel join" }
                            val needsJoin = !client.hasMembership(channel)
                            if (needsJoin) {
                                check(allowJoin) { "You are no longer in this channel. Review the action again." }
                                check(connections.joinChannel(id, channel)) { "Connection closed before channel join" }
                            }
                            select<Long?> {
                                joined.onAwait { it }
                                disconnected.onAwait { error("Connection closed before the channel join was confirmed") }
                                if (needsJoin) rejected.onAwait { error(it.reason) }
                            }
                        } finally {
                            disconnected.cancel()
                            joined.cancel()
                            rejected.cancel()
                        }
                    }
                }
            check(readyClient(id) === client && (joinedId == null || client.hasMembership(channel))) {
                "Connection or channel membership changed before it could be opened"
            }
            return checkNotNull(joinedId) { "Channel join timed out" }
        }
    }
