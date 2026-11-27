package io.github.trevarj.motd.profile

import dagger.Lazy
import io.github.trevarj.motd.attachment.advertisedSojuFileHostUrl
import io.github.trevarj.motd.avatar.AvatarCoordinator
import io.github.trevarj.motd.avatar.METADATA_CAP
import io.github.trevarj.motd.avatar.METADATA_V3_CAP
import io.github.trevarj.motd.avatar.isMetadataTarget
import io.github.trevarj.motd.avatar.metadataCapabilityLimits
import io.github.trevarj.motd.avatar.metadataVersion
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcTimeoutException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.messageContextOrNull
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class IrcProfileRepository
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val connections: Lazy<ConnectionManager>,
        private val avatars: AvatarCoordinator,
    ) {
        private val lock = Any()
        private val sessions = MutableStateFlow<Map<Long, Session>>(emptyMap())

        private data class Route(
            val networkId: Long,
            val channel: String? = null,
        )

        private class Entry {
            val values = MutableStateFlow<Map<ProfileKey, String>>(emptyMap())
            val sequences = mutableMapOf<ProfileKey, Long>()
            var invalidatedAt = 0L
            var invalidatedSequence = 0L
        }

        private class Read(
            val target: String,
            val keys: List<ProfileKey>,
        ) {
            val result = CompletableDeferred<Map<ProfileKey, ProfileValue>>()
            var sentAt = 0L
        }

        private class Session(
            val client: IrcClient,
            val version: String,
        ) {
            val requests = Mutex()
            val entries = mutableMapOf<String, Entry>()
            val rosters = mutableMapOf<String, MutableSet<String>>()
            val monitored = mutableSetOf<String>()
            val subscribedAt = mutableMapOf<ProfileKey, Long>()
            val ambiguousReads = mutableSetOf<String>()
            var read: Read? = null
            val caseMapping = client.isupport.caseMapping

            fun entry(target: String) = entries.getOrPut(client.isupport.normalize(target), ::Entry)

            fun forget(
                target: String,
                sequence: Long,
            ) {
                val entry = entry(target)
                entry.values.value = emptyMap()
                entry.sequences.clear()
                entry.invalidatedAt = System.nanoTime()
                entry.invalidatedSequence = maxOf(entry.invalidatedSequence, sequence)
            }

            fun visible(nick: String): Boolean = nick in monitored || rosters.values.any { nick in it }

            fun leave(
                channel: String,
                sequence: Long,
            ) {
                forget(channel, sequence)
                rosters.remove(client.isupport.normalize(channel))?.forEach { forgetUnlessVisible(it, sequence) }
            }

            fun forgetUnlessVisible(
                nick: String,
                sequence: Long,
            ) {
                if (!visible(nick) && client.isupport.normalize(client.readyNick()) != nick) forget(nick, sequence)
            }
        }

        fun editor(target: ProfileTarget): Flow<ProfileEditorState> {
            val routes =
                when (target) {
                    is ProfileTarget.Self -> {
                        flowOf(Route(target.networkId))
                    }

                    is ProfileTarget.Channel -> {
                        db.bufferDao().observe(target.bufferId).map { room ->
                            room?.takeIf { it.type == BufferType.CHANNEL }?.let { Route(it.networkId, it.displayName) }
                        }
                    }
                }
            return routes
                .distinctUntilChanged()
                .flatMapLatest { route ->
                    if (route == null) return@flatMapLatest flowOf(ProfileEditorState("", null, ProfileAccess.OFFLINE, emptyMap()))
                    val network = db.networkDao().observeById(route.networkId)
                    val persisted = db.networkIdentityDao().observe(route.networkId)
                    val live =
                        connections
                            .get()
                            .connectionStates
                            .map { it[route.networkId] }
                            .distinctUntilChanged()
                    combine(network, persisted, live, liveSessionFlow(route.networkId)) { row, identityRow, state, session ->
                        val ready = state as? IrcClientState.Ready
                        val identity = route.channel ?: ready?.nick ?: identityRow?.selfNick ?: row?.nick.orEmpty()
                        val caseMapping = if (ready == null) identityRow?.caseMapping else ready.isupport["CASEMAPPING"]
                        val initial =
                            ProfileEditorState(
                                identity,
                                route.networkId,
                                if (ready == null) ProfileAccess.OFFLINE else ProfileAccess.UNSUPPORTED,
                                emptyMap(),
                                row?.name,
                                ready?.isupport?.let(::advertisedSojuFileHostUrl),
                                normalizedIdentity = IrcCaseMapping.from(caseMapping).normalize(identity),
                            )
                        if (ready == null || session == null) return@combine flowOf(initial)
                        val values = synchronized(lock) { session.entry(identity).values }
                        combine(values, session.client.joinedChannels, session.client.selfChannelModes) { data, _, _ ->
                            val access = access(route, session.client)
                            initial.copy(
                                access = access,
                                values = data,
                                readable =
                                    (access == ProfileAccess.READ_ONLY || access == ProfileAccess.WRITABLE) &&
                                        (route.channel == null || session.client.isJoined(route.channel)),
                            )
                        }
                    }.flatMapLatest { it }
                }.distinctUntilChanged()
        }

        fun observe(
            networkId: Long,
            target: String,
        ): Flow<Map<ProfileKey, String>> =
            sessionFlow(networkId)
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(emptyMap())
                    } else {
                        synchronized(lock) { session.entry(target).values }
                    }
                }.distinctUntilChanged()

        internal fun supportsMetadata(networkId: Long): Flow<Boolean> = liveSessionFlow(networkId).map { it != null }.distinctUntilChanged()

        internal fun observeUserProfile(
            networkId: Long,
            nick: String,
        ): Flow<Map<ProfileKey, String>?> {
            if (!isMetadataTarget(nick) || nick.startsWith('*')) return flowOf(null)
            val route = Route(networkId)
            return liveSessionFlow(networkId)
                .flatMapLatest { session ->
                    if (session == null || session.client.isupport.isChannel(nick)) return@flatMapLatest flowOf(null)
                    val values = synchronized(lock) { session.entry(nick).values }
                    flow<Map<ProfileKey, String>?> {
                        coroutineScope {
                            launch { session.requests.withLock { read(route, session, nick, ProfileKey.entries) } }
                            this@flow.emitAll(values.map { data -> data.takeIf { isCurrent(route, session) } })
                        }
                    }
                }.buffer(0)
                .distinctUntilChanged()
        }

        private fun sessionFlow(networkId: Long) = sessions.map { it[networkId] }.distinctUntilChanged()

        private fun liveSessionFlow(networkId: Long): Flow<Session?> {
            val route = Route(networkId)
            return sessionFlow(networkId)
                .flatMapLatest { session ->
                    if (session == null) return@flatMapLatest flowOf(null)
                    session.client.state.map { session.takeIf { isCurrent(route, session) } }
                }.buffer(0)
                .distinctUntilChanged()
        }

        private suspend fun route(target: ProfileTarget): Route? =
            when (target) {
                is ProfileTarget.Self -> {
                    Route(target.networkId)
                }

                is ProfileTarget.Channel -> {
                    db
                        .bufferDao()
                        .observeById(target.bufferId)
                        ?.takeIf { it.type == BufferType.CHANNEL }
                        ?.let { Route(it.networkId, it.displayName) }
                }
            }

        suspend fun refresh(target: ProfileTarget) {
            val route = route(target) ?: return
            val session = sessions.value[route.networkId] ?: return
            val client = session.client
            val channel = route.channel
            if (channel != null && !client.isJoined(channel)) return
            if (channel != null &&
                client.selfChannelModes.value.keys
                    .none { client.same(it, channel) }
            ) {
                // Suppressed implicit NAMES also suppresses the initial prefix snapshot.
                val names = IrcMessage(command = "NAMES", params = listOf(channel))
                trySend { client.sendIfConnected(names) { requireCurrent(route, session) } }
            }
            session.requests.withLock {
                read(route, session, channel ?: "*", if (channel == null) ProfileKey.entries else channelProfileKeys)
            }
        }

        internal suspend fun canWrite(
            target: ProfileTarget,
            expectedNetworkId: Long,
            expectedIdentity: String,
        ): Boolean {
            val route = route(target) ?: return false
            val session = sessions.value[route.networkId] ?: return false
            return route.matches(session.client, expectedNetworkId, expectedIdentity) &&
                isCurrent(route, session) && access(route, session.client) == ProfileAccess.WRITABLE
        }

        suspend fun write(
            target: ProfileTarget,
            expectedNetworkId: Long,
            expectedIdentity: String,
            key: ProfileKey,
            value: String?,
        ): ProfileWriteResult {
            val route = route(target) ?: return ProfileWriteResult.OFFLINE
            val client = connections.get().clientFor(route.networkId) ?: return ProfileWriteResult.OFFLINE
            val session =
                sessions.value[route.networkId]?.takeIf { it.client === client }
                    ?: return if (client.ready == null) ProfileWriteResult.OFFLINE else ProfileWriteResult.UNSUPPORTED
            if (!route.matches(client, expectedNetworkId, expectedIdentity)) return ProfileWriteResult.READ_ONLY
            if (route.channel != null && key !in channelProfileKeys) return ProfileWriteResult.INVALID
            if (route.channel != null && !isMetadataTarget(route.channel)) return ProfileWriteResult.INVALID
            val valid = value?.let { validProfileValue(key, it) ?: return ProfileWriteResult.INVALID }
            if (valid == "" && session.version == METADATA_V3_CAP) return ProfileWriteResult.INVALID
            val operation: suspend (() -> Unit) -> ProfileWriteResult = { onAttempt ->
                session.requests.withLock {
                    val current = route(target)
                    if (current == null || !current.matches(client, expectedNetworkId, expectedIdentity)) {
                        return@withLock ProfileWriteResult.READ_ONLY
                    }
                    val command = metadataSet(current.channel ?: "*", key, valid)
                    if (!profileWireFits(command)) return@withLock ProfileWriteResult.INVALID
                    write(current, session, expectedIdentity, key, valid, command, onAttempt)
                }
            }
            return if (route.channel == null && key == ProfileKey.AVATAR) avatars.explicitSelfAvatar(route.networkId, operation) else operation {}
        }

        private fun Route.matches(
            client: IrcClient,
            expectedNetworkId: Long,
            expectedIdentity: String,
        ): Boolean = networkId == expectedNetworkId && client.same(channel ?: actualSelfNick(client), expectedIdentity)

        private suspend fun write(
            route: Route,
            session: Session,
            expectedIdentity: String,
            key: ProfileKey,
            value: String?,
            command: IrcMessage,
            onAttempt: () -> Unit,
        ): ProfileWriteResult {
            val client = session.client
            var sentAt = 0L
            var nick = ""
            var wireTarget = ""
            val caller = currentCoroutineContext()
            val guard = {
                caller.ensureActive()
                val ready = requireCurrent(route, session)
                if (route.channel == null && !client.same(ready.nick, expectedIdentity)) throw Aborted(ProfileWriteResult.READ_ONLY)
                access(route, client).let {
                    when (it) {
                        ProfileAccess.WRITABLE -> Unit
                        ProfileAccess.OFFLINE -> throw Aborted(ProfileWriteResult.OFFLINE)
                        ProfileAccess.UNSUPPORTED -> throw Aborted(ProfileWriteResult.UNSUPPORTED)
                        ProfileAccess.READ_ONLY -> throw Aborted(ProfileWriteResult.READ_ONLY)
                    }
                }
                val limits = metadataCapabilityLimits(ready.caps) ?: throw Aborted(ProfileWriteResult.UNSUPPORTED)
                if (limits.maxKeyBytes?.let { key.wireName.length > it } == true ||
                    (route.channel == null && value != null && limits.maxKeys == 0)
                ) {
                    throw Aborted(ProfileWriteResult.UNSUPPORTED)
                }
                if (value != null && limits.maxValueBytes?.let { value.encodeToByteArray().size > it } == true) {
                    throw Aborted(ProfileWriteResult.INVALID)
                }
                nick = ready.nick
                wireTarget = route.channel ?: nick
                sentAt = System.nanoTime()
                onAttempt()
            }

            fun current(): Boolean =
                stillCurrent(route, session, wireTarget, sentAt) &&
                    (route.channel?.let { client.isJoined(it) } ?: client.same(actualSelfNick(client), wireTarget))
            return try {
                if (client.hasCap("labeled-response")) {
                    val response =
                        try {
                            client.sendLabeled(command) {
                                if (!client.hasCap("labeled-response")) throw Aborted(ProfileWriteResult.UNSUPPORTED)
                                guard()
                            }
                        } catch (_: IrcTimeoutException) {
                            emptyList()
                        } catch (failure: IrcCommandException) {
                            if (failure.ircCommand == "METADATA" && current()) return ProfileWriteResult.REJECTED
                            throw failure
                        }
                    if (!current()) return ProfileWriteResult.UNCONFIRMED
                    if (response.any { isProfileSetFailure(it, key, wireTarget, route.channel == null, client.isupport::normalize) }) {
                        return ProfileWriteResult.REJECTED
                    }
                    val exact =
                        response
                            .mapNotNull { message ->
                                profileValue(message, session.version, nick, client.isupport::normalize)
                                    ?.takeIf { message.command != "METADATA" && it.key == key && client.same(it.target, wireTarget) }
                                    ?.let { it to message.wireSequence }
                            }.singleOrNull()
                    if (exact != null) {
                        apply(route.networkId, session, exact.first, exact.second, sentAt)
                        if ((exact.first.value == null) == (value == null)) return ProfileWriteResult.CONFIRMED
                    }
                } else if (!client.sendIfConnected(command, onWrite = guard)) {
                    return ProfileWriteResult.OFFLINE
                }
                // An unlabeled 761/766 is also a notification, so it cannot acknowledge this SET.
                // Readback can confirm a visible value, but 766 can also mean hidden, not cleared.
                if (!current()) return ProfileWriteResult.UNCONFIRMED
                val keys = if (route.channel == null) ProfileKey.entries else channelProfileKeys
                val readback = read(route, session, route.channel ?: "*", keys)[key]
                if (value != null && readback?.value == value && current()) {
                    ProfileWriteResult.CONFIRMED
                } else {
                    ProfileWriteResult.UNCONFIRMED
                }
            } catch (abort: Aborted) {
                abort.result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sentAt == 0L) ProfileWriteResult.OFFLINE else ProfileWriteResult.UNCONFIRMED
            }
        }

        private suspend fun read(
            route: Route,
            session: Session,
            target: String,
            keys: List<ProfileKey>,
        ): Map<ProfileKey, ProfileValue> {
            val client = session.client
            if (!(target == "*" || isMetadataTarget(target)) || target == "*ALL") return emptyMap()
            val actual = if (target == "*") client.ready?.nick ?: return emptyMap() else target
            val outgoing = IrcMessage(command = "METADATA", params = listOf(target, "GET") + keys.map(ProfileKey::wireName))
            if (!profileWireFits(outgoing)) return emptyMap()
            val request = Read(actual, keys)
            val caller = currentCoroutineContext()
            return trySend {
                if (client.hasCap("labeled-response")) {
                    val (opening, reply) =
                        client.sendLabeledMetadataGet(outgoing) {
                            caller.ensureActive()
                            if (!client.hasCap("labeled-response")) throw Aborted(ProfileWriteResult.UNSUPPORTED)
                            val ready = requireCurrent(route, session)
                            if (target == "*" && !client.same(ready.nick, actual)) throw Aborted(ProfileWriteResult.OFFLINE)
                            request.sentAt = System.nanoTime()
                        }
                    if (!stillCurrent(route, session, actual, request.sentAt)) return@trySend emptyMap()
                    val result = mutableMapOf<ProfileKey, ProfileValue>()
                    for (line in reply) {
                        val datum = profileValue(line, session.version, actualSelfNick(client), client.isupport::normalize) ?: continue
                        if (!client.same(datum.target, actual) || datum.key !in keys) continue
                        apply(route.networkId, session, datum, opening.takeIf { it > 0L } ?: line.wireSequence, request.sentAt)
                        result[datum.key] = datum
                    }
                    result
                } else {
                    synchronized(lock) {
                        if (client.isupport.normalize(actual) in session.ambiguousReads) return@trySend emptyMap()
                        session.read = request
                    }
                    try {
                        if (!client.sendIfConnected(outgoing, onWrite = {
                                caller.ensureActive()
                                val ready = requireCurrent(route, session)
                                if (target == "*" && !client.same(ready.nick, actual)) throw Aborted(ProfileWriteResult.OFFLINE)
                                synchronized(lock) { request.sentAt = System.nanoTime() }
                            })
                        ) {
                            return@trySend emptyMap()
                        }
                        withTimeoutOrNull(READBACK_TIMEOUT_MS) { request.result.await() }.orEmpty()
                    } finally {
                        // Keep the single flight while the response can still drain, even if its caller left.
                        if (request.sentAt != 0L && !request.result.isCompleted) {
                            withContext(NonCancellable) {
                                withTimeoutOrNull(READBACK_TIMEOUT_MS) { request.result.await() }
                            }
                        }
                        synchronized(lock) {
                            if (session.read === request) session.read = null
                            // A delayed response to an earlier identical GET cannot prove a later SET.
                            if (request.sentAt != 0L && !request.result.isCompleted) session.ambiguousReads += client.isupport.normalize(actual)
                        }
                    }
                }
            }.orEmpty()
        }

        internal fun onReady(
            networkId: Long,
            client: IrcClient,
        ) {
            configure(networkId, client)
        }

        internal fun onClientChanged(
            networkId: Long,
            client: IrcClient?,
        ) = synchronized(lock) {
            val current = sessions.value[networkId]
            if (current != null && current.client !== client) clear(networkId, current)
        }

        private fun configure(
            networkId: Long,
            client: IrcClient,
        ): Session? =
            synchronized(lock) {
                if (connections.get().clientFor(networkId) !== client) return@synchronized null
                val version =
                    client.ready
                        ?.caps
                        ?.takeIf { client.hasCap("batch") }
                        ?.let(::metadataVersion)
                val current = sessions.value[networkId]
                if (version == null) {
                    clear(networkId, current)
                    return@synchronized null
                }
                if (current?.client === client && current.version == version && current.caseMapping == client.isupport.caseMapping) return@synchronized current
                clear(networkId, current)
                Session(client, version).also { sessions.value += networkId to it }
            }

        private fun clear(
            networkId: Long,
            session: Session?,
        ) {
            if (session != null) {
                session.read?.result?.complete(emptyMap())
                sessions.value -= networkId
            }
        }

        internal suspend fun onEvent(
            networkId: Long,
            client: IrcClient,
            event: IrcEvent,
            positiveMetadataOnly: Boolean = false,
            explicitMetadataValues: Boolean = false,
        ) {
            if (connections.get().clientFor(networkId) !== client) return
            if (event is IrcEvent.Disconnected) {
                synchronized(lock) { clear(networkId, sessions.value[networkId]?.takeIf { it.client === client }) }
                return
            }
            if (event is IrcEvent.Registered && event.startsSession) synchronized(lock) { clear(networkId, sessions.value[networkId]) }
            if (event is IrcEvent.CapsChanged && event.removed.any { it.substringBefore('=') in PROFILE_CAPS }) {
                synchronized(lock) { clear(networkId, sessions.value[networkId]) }
            }
            val session = configure(networkId, client) ?: return
            when (event) {
                is IrcEvent.Raw -> {
                    val line = event.message
                    if ("label" in line.tags || "batch" in line.tags) return
                    if (session.version == METADATA_V3_CAP && (line.command == "770" || line.command == "771")) {
                        val self = line.selfNickAtRead ?: actualSelfNick(client)
                        val recipient = line.params.firstOrNull() ?: return
                        if (line.source?.user != null || line.source?.host != null || (recipient != "*" && !client.same(recipient, self))) return
                        synchronized(lock) {
                            for (index in 1 until line.params.size) {
                                val key = profileKeysByName[line.params[index]] ?: continue
                                if (line.command == "770") session.subscribedAt.putIfAbsent(key, line.wireSequence) else session.subscribedAt.remove(key)
                            }
                        }
                        return
                    }
                    val datum = profileValue(line, session.version, actualSelfNick(client), client.isupport::normalize) ?: return
                    // V2 numerics are replies, not subscriptions. An unlabeled SET reply is not proof.
                    if (session.version == METADATA_CAP && line.command != "METADATA") return
                    apply(networkId, session, datum, line.wireSequence)
                }

                is IrcEvent.MetadataSnapshot -> {
                    snapshot(networkId, session, event, positiveMetadataOnly, explicitMetadataValues)
                }

                else -> {
                    synchronized(lock) { lifecycle(session, event) }
                }
            }
        }

        private suspend fun snapshot(
            networkId: Long,
            session: Session,
            event: IrcEvent.MetadataSnapshot,
            positiveOnly: Boolean,
            explicitValues: Boolean,
        ) {
            val client = session.client
            val self = event.selfNickAtOpen ?: actualSelfNick(client)
            val scopeTarget = if (event.target == "*") self else event.target
            if (!isMetadataTarget(event.target)) return
            val rawConsoleBatch = positiveOnly || explicitValues
            val normalizedScope = client.isupport.normalize(scopeTarget)
            val request =
                synchronized(lock) {
                    session.read?.takeIf {
                        !rawConsoleBatch && it.sentAt != 0L && event.openedAtNanos >= it.sentAt &&
                            event.joinResponseLabel == null && client.same(it.target, scopeTarget)
                    }
                }
            val uncertainRead =
                request != null || event.joinResponseLabel == null &&
                    synchronized(lock) { normalizedScope in session.ambiguousReads }
            val authoritative =
                !uncertainRead && avatars.handleSnapshot(networkId, event, positiveOnly, explicitValues) &&
                    event.openingSequence > 0L && event.messages.none { it.command == "FAIL" }
            val received = if (request == null) null else mutableMapOf<ProfileKey, ProfileValue>()
            val seen = if (authoritative) mutableSetOf<ProfileKey>() else null
            var exactRead = request != null && request.keys.size == event.messages.size
            for ((index, line) in event.messages.withIndex()) {
                val wire = profileWireValue(line, session.version, self, client.isupport::normalize)
                val key = request?.keys?.getOrNull(index)
                if (request != null &&
                    !(
                        wire != null && wire.key == key && client.same(wire.target, request.target) ||
                            wire == null && key != null && isGetWarning(line, client, self, request.target, key)
                    )
                ) {
                    exactRead = false
                }
                if (wire == null) continue
                val sameTarget = client.same(wire.target, scopeTarget)
                if (sameTarget) seen?.add(wire.key)
                val datum = wire.validated(session.version) ?: continue
                if (sameTarget) received?.set(datum.key, datum)
                if ((uncertainRead || rawConsoleBatch) && !sameTarget) continue
                if (positiveOnly && datum.value == null) continue
                if (!sameTarget && event.target != "*ALL" && !client.isJoined(scopeTarget)) continue
                val known =
                    synchronized(lock) {
                        val normalized = client.isupport.normalize(datum.target)
                        normalized in session.entries || session.visible(normalized)
                    }
                if (!known && !(sameTarget && request != null) &&
                    !client.same(datum.target, self) && !client.isJoined(datum.target)
                ) {
                    continue
                }
                val baseline = event.openingSequence.takeIf { it > 0L } ?: line.wireSequence
                apply(networkId, session, datum, baseline, request?.sentAt ?: 0L)
            }
            if (seen != null) {
                val omitted =
                    synchronized(lock) {
                        if (normalizedScope !in session.entries) {
                            emptyList()
                        } else {
                            ProfileKey.entries.filter { key ->
                                val ack = session.subscribedAt[key]
                                key !in seen && ack != null && ack > 0L && ack < event.openingSequence
                            }
                        }
                    }
                for (key in omitted) apply(networkId, session, ProfileValue(scopeTarget, key, null), event.openingSequence)
            }
            if (exactRead && request != null) {
                request.result.complete(
                    if (stillCurrent(Route(networkId), session, request.target, request.sentAt)) received.orEmpty() else emptyMap(),
                )
            }
        }

        private fun isGetWarning(
            message: IrcMessage,
            client: IrcClient,
            self: String,
            target: String,
            key: ProfileKey,
        ): Boolean {
            val params = message.params
            if ((message.command != "WARN" && message.command != "FAIL") || params.firstOrNull() != "METADATA" ||
                message.source?.user != null || message.source?.host != null
            ) {
                return false
            }
            return when (params.getOrNull(1)) {
                "INVALID_KEY", "KEY_INVALID" -> {
                    params.size == 4 && params[2] == key.wireName
                }

                "KEY_NO_PERMISSION" -> {
                    params.size == 5 && params[3] == key.wireName &&
                        client.same(if (params[2] == "*") self else params[2], target)
                }

                else -> {
                    false
                }
            }
        }

        private fun lifecycle(
            session: Session,
            event: IrcEvent,
        ) {
            val normalize = session.client.isupport::normalize
            val context = event.messageContextOrNull()
            if (context?.isHistoryContext == true) return
            val sequence =
                context?.wireSequence ?: when (event) {
                    is IrcEvent.Names -> event.wireSequence
                    is IrcEvent.MonitorOffline -> event.wireSequence
                    else -> 0L
                }
            when (event) {
                is IrcEvent.Joined -> {
                    if (event.isSelf) session.leave(event.channel, sequence)
                    session.rosters.getOrPut(normalize(event.channel), ::mutableSetOf).add(normalize(event.nick))
                }

                is IrcEvent.Parted -> {
                    if (event.isSelf) session.leave(event.channel, sequence) else removeMember(session, event.channel, event.nick, sequence)
                }

                is IrcEvent.Kicked -> {
                    if (event.isSelf) session.leave(event.channel, sequence) else removeMember(session, event.channel, event.nick, sequence)
                }

                is IrcEvent.Names -> {
                    val names = event.members.mapTo(mutableSetOf()) { normalize(it.nick) }
                    val previous = session.rosters.put(normalize(event.channel), names)
                    previous?.forEach { if (it !in names) session.forgetUnlessVisible(it, sequence) }
                }

                is IrcEvent.ChannelRenamed -> {
                    session.leave(event.oldName, sequence)
                    session.leave(event.newName, sequence)
                }

                is IrcEvent.Quit -> {
                    val nick = normalize(event.nick)
                    session.rosters.values.forEach { it.remove(nick) }
                    session.monitored.remove(nick)
                    session.forget(nick, sequence)
                }

                is IrcEvent.NickChanged -> {
                    val from = normalize(event.from)
                    val to = normalize(event.to)
                    if (from == to) return
                    session.rosters.values.forEach { if (it.remove(from)) it.add(to) }
                    if (session.monitored.remove(from)) session.monitored.add(to)
                    session.forget(from, sequence)
                    session.forget(to, sequence)
                }

                is IrcEvent.MonitorOnline -> {
                    event.identities.forEach { session.monitored.add(normalize(it.nick)) }
                }

                is IrcEvent.MonitorOffline -> {
                    event.nicks.forEach {
                        session.monitored.remove(normalize(it))
                        session.forgetUnlessVisible(normalize(it), sequence)
                    }
                }

                is IrcEvent.NetworkBatch -> {
                    if (event.historyMetadata?.isContext != true) event.events.forEach { lifecycle(session, it) }
                }

                else -> {}
            }
        }

        private fun removeMember(
            session: Session,
            channel: String,
            nick: String,
            sequence: Long,
        ) {
            val normalize = session.client.isupport::normalize
            session.rosters[normalize(channel)]?.remove(normalize(nick))
            session.forgetUnlessVisible(normalize(nick), sequence)
        }

        private suspend fun apply(
            networkId: Long,
            session: Session,
            datum: ProfileValue,
            sequence: Long,
            requestedAt: Long = 0L,
        ) {
            val client = session.client
            val channel = client.isupport.isChannel(datum.target)
            if (channel && (datum.key !in channelProfileKeys || !client.isJoined(datum.target))) return
            synchronized(lock) {
                if (!stillCurrent(Route(networkId), session, datum.target, requestedAt)) return
                val entry = session.entry(datum.target)
                if (!channel && requestedAt == 0L && entry.invalidatedSequence > 0L &&
                    !client.same(datum.target, actualSelfNick(client)) && !session.visible(client.isupport.normalize(datum.target))
                ) {
                    return
                }
                if (sequence > 0L && entry.invalidatedSequence > sequence) return
                if (sequence > 0L && (entry.sequences[datum.key] ?: 0L) > sequence) return
                entry.sequences[datum.key] = sequence
                val values = entry.values.value
                if (values[datum.key] != datum.value) {
                    entry.values.value = if (datum.value == null) values - datum.key else values + (datum.key to datum.value)
                }
            }
            // Profile data alone cannot opt in to media, bypass a local override, or fetch a URL.
            if (datum.key == ProfileKey.AVATAR && requestedAt != 0L) {
                avatars.ingestProfileAvatar(networkId, client, datum.target, datum.value, sequence)
            }
        }

        private fun stillCurrent(
            route: Route,
            session: Session,
            target: String,
            since: Long,
        ): Boolean =
            synchronized(lock) {
                isCurrent(route, session) && (since == 0L || session.entry(target).invalidatedAt < since)
            }

        private fun isCurrent(
            route: Route,
            session: Session,
        ): Boolean {
            val client = session.client
            return sessions.value[route.networkId] === session && connections.get().clientFor(route.networkId) === client &&
                client.hasCap("batch") && metadataVersion(client.ready?.caps.orEmpty()) == session.version
        }

        private fun requireCurrent(
            route: Route,
            session: Session,
        ): IrcClientState.Ready {
            if (!isCurrent(route, session)) throw Aborted(ProfileWriteResult.OFFLINE)
            if (route.channel != null && !session.client.isJoined(route.channel)) throw Aborted(ProfileWriteResult.READ_ONLY)
            return session.client.ready ?: throw Aborted(ProfileWriteResult.OFFLINE)
        }

        private fun access(
            route: Route,
            client: IrcClient,
        ): ProfileAccess {
            val ready = client.ready ?: return ProfileAccess.OFFLINE
            if (!client.hasCap("batch") || metadataVersion(ready.caps) == null) return ProfileAccess.UNSUPPORTED
            val channel = route.channel ?: return ProfileAccess.WRITABLE
            if (!client.isJoined(channel)) return ProfileAccess.READ_ONLY
            val prefix = client.isupport.prefixModes
            val modes =
                client.selfChannelModes.value.entries
                    .find { client.same(it.key, channel) }
                    ?.value
                    .orEmpty()
            return if (ready.isupport.containsKey("PREFIX") && hasOperatorRank(prefix, modes)) ProfileAccess.WRITABLE else ProfileAccess.READ_ONLY
        }

        private val IrcClient.ready get() = state.value as? IrcClientState.Ready

        private fun IrcClient.same(
            a: String,
            b: String,
        ) = isupport.normalize(a) == isupport.normalize(b)

        private fun IrcClient.isJoined(channel: String) = isupport.isChannel(channel) && joinedChannels.value.any { same(it, channel) }

        private fun actualSelfNick(client: IrcClient) = client.ready?.nick.orEmpty()

        private suspend fun <T> trySend(block: suspend () -> T): T? =
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

        private class Aborted(
            val result: ProfileWriteResult,
        ) : RuntimeException()

        private companion object {
            const val READBACK_TIMEOUT_MS = 2_500L
            val PROFILE_CAPS = setOf("batch", METADATA_CAP, METADATA_V3_CAP)
        }
    }

private fun IrcClient.readyNick() = (state.value as? IrcClientState.Ready)?.nick.orEmpty()

internal fun hasOperatorRank(
    prefix: List<Pair<Char, Char>>,
    currentModes: Set<Char>,
): Boolean {
    val op = prefix.indexOfFirst { it.first == 'o' }
    return op >= 0 && (0..op).any { prefix[it].first in currentModes }
}
