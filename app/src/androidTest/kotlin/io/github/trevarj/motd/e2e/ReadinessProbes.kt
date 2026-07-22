package io.github.trevarj.motd.e2e

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class ConnectionProbe(private val connections: ConnectionManager, private val milestones: E2eMilestoneRecorder) {
    suspend fun awaitReady(id: Long, requiredCaps: Set<String>, timeoutMs: Long = 30_000): IrcClientState.Ready =
        withTimeout(timeoutMs) {
            connections.connectionStates.first { states ->
                when (val state = states[id]) {
                    is IrcClientState.Ready -> {
                        milestones.record("connection_ready", "network=$id caps=${state.caps.sorted().joinToString(",")}")
                        requiredCaps.all { cap -> state.caps.any { it == cap || it.startsWith("$cap=") } }
                    }
                    is IrcClientState.Failed -> {
                        milestones.record("connection_failed", "network=$id fatal=${state.fatal}")
                        if (state.fatal) error("fatal connection state")
                        false
                    }
                    null -> false
                    else -> {
                        milestones.record("connection_state", "network=$id state=${state::class.simpleName}")
                        false
                    }
                }
            }
            connections.connectionStates.value[id] as IrcClientState.Ready
        }
}

class BufferProbe(private val buffers: BufferRepository, private val milestones: E2eMilestoneRecorder) {
    suspend fun awaitJoinedChannel(networkId: Long, channel: String, timeoutMs: Long = 20_000): Long =
        withTimeout(timeoutMs) {
            buffers.observeChatList().first { rows ->
                rows.any { row -> row.networkId == networkId && row.type == BufferType.CHANNEL && row.displayName.equals(channel, true) }
            }.first { it.networkId == networkId && it.type == BufferType.CHANNEL && it.displayName.equals(channel, true) }
                .bufferId.also { milestones.record("buffer_joined", "network=$networkId buffer=$it") }
        }
}

/** Uses the public search repository to observe the canonical event written by EventProcessor. */
class MessageLifecycleProbe(
    private val search: SearchRepository,
    private val milestones: E2eMilestoneRecorder,
) {
    suspend fun awaitCanonical(token: String, bufferId: Long, timeoutMs: Long = 20_000): MessageEntity =
        try {
            withTimeout(timeoutMs) {
                search.search(token, bufferId).first { hits ->
                    hits.count { hit ->
                        hit.message.isSelf && hit.message.text == token && hit.message.msgid != null &&
                            hit.message.pendingLabel == null && !hit.message.failed
                    } == 1
                }.single { hit ->
                    hit.message.isSelf && hit.message.text == token && hit.message.msgid != null &&
                        hit.message.pendingLabel == null && !hit.message.failed
                }.message.also { milestones.record("canonical_message", "buffer=$bufferId event=${it.id}") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            milestones.record("canonical_timeout", "buffer=$bufferId")
            throw AssertionError("canonical message readiness timed out for buffer=$bufferId", failure)
        }
}
