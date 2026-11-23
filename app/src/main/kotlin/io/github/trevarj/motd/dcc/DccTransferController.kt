package io.github.trevarj.motd.dcc

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PreparedUpload
import io.github.trevarj.motd.attachment.UploadMetadataSanitizer
import io.github.trevarj.motd.attachment.isLocalMediaUri
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferDao
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.networkFingerprint
import io.github.trevarj.motd.service.resolveTransportProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal class DccSendUnavailableException(
    message: String,
) : IllegalStateException(message)

/** Owns the exact staged bytes shown in the review until confirmation or close. */
class PreparedDccSend internal constructor(
    internal val bufferId: Long,
    internal val networkId: Long,
    val filename: String,
    val sizeBytes: Long,
    val peerNick: String,
    val networkName: String,
    internal val normalizedPeer: String,
    private val owner: DccTransferController,
    upload: PreparedUpload,
    internal val requireCurrentTarget: suspend () -> IrcClient,
) : Closeable {
    private val pending = AtomicReference<PreparedUpload?>(upload)

    internal fun claim(controller: DccTransferController): PreparedUpload? {
        if (owner !== controller) throw DccSendUnavailableException("File review is no longer available")
        return pending.getAndSet(null)
    }

    override fun close() {
        pending.getAndSet(null)?.close()
    }
}

interface DccTransferController {
    fun observeAll(): Flow<List<DccTransferEntity>>

    fun observeForNetwork(networkId: Long): Flow<List<DccTransferEntity>>

    suspend fun acceptIncoming(
        transferId: Long,
        destinationUri: Uri,
        allowPrivateEndpoint: Boolean,
    )

    suspend fun reject(transferId: Long)

    suspend fun removeRecord(transferId: Long)

    suspend fun saveToDownloads(transferId: Long)

    suspend fun prepareSend(
        bufferId: Long,
        sourceUri: Uri,
        allowOriginalMetadata: Boolean = false,
    ): PreparedDccSend

    suspend fun sendPrepared(prepared: PreparedDccSend)
}

@Singleton
class DccTransferControllerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val db: MotdDatabase,
        private val connectionManager: ConnectionManager,
        private val localSocksProvider: LocalSocksProvider,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
        private val metadataSanitizer: UploadMetadataSanitizer,
        private val attachmentPrefs: AttachmentPrefs,
    ) : DccTransferController {
        private val transfers: DccTransferDao get() = db.dccTransferDao()
        private val resultCache = EbooksResultCache(context)
        private val stateLock = Mutex()
        private val jobs = LinkedHashMap<Long, Job>()
        private val jobLock = Mutex()

        // The app has one process and one controller. Reap before staging, never while a review or transfer owns a file.
        private val sendStagingDirectory =
            lazy {
                File(context.cacheDir, "dcc-send-staging").also { directory ->
                    if (directory.exists()) {
                        val staleFiles = directory.listFiles() ?: throw IOException("Unable to read DCC staging directory")
                        for (file in staleFiles) {
                            if (!file.delete() && file.exists()) throw IOException("Unable to clear DCC staging directory")
                        }
                    }
                }
            }

        override fun observeAll(): Flow<List<DccTransferEntity>> = transfers.observeAll()

        override fun observeForNetwork(networkId: Long): Flow<List<DccTransferEntity>> = transfers.observeForNetwork(networkId)

        override suspend fun acceptIncoming(
            transferId: Long,
            destinationUri: Uri,
            allowPrivateEndpoint: Boolean,
        ) {
            stateLock.withLock {
                val transfer = transfers.byId(transferId) ?: return
                if (transfer.direction != DccDirection.INCOMING ||
                    transfer.state !in setOf(DccTransferState.OFFERED, DccTransferState.PARTIAL, DccTransferState.FAILED)
                ) {
                    return
                }
                val privateCache = resultCache.isOwned(destinationUri, transferId)
                if (privateCache && !transfer.displayFilename.endsWith(".zip", ignoreCase = true)) return
                if (destinationUri.scheme == "file" && resultCache.isOwned(destinationUri) && !privateCache) return
                if (privateCache) {
                    resultCache.discard(destinationUri, transferId)
                } else {
                    transfer.destinationUri?.let { resultCache.discard(it.toUri(), transferId) }
                }
                val now = System.currentTimeMillis()
                transfers.update(
                    transfer.copy(
                        state = DccTransferState.ACCEPTING,
                        destinationUri = destinationUri.toString(),
                        bytesTransferred = 0,
                        acceptedAt = transfer.acceptedAt ?: now,
                        error = null,
                        updatedAt = now,
                    ),
                )
                launchTransfer(transfer.id) {
                    receiveIncoming(transfer.id, destinationUri, allowPrivateEndpoint)
                }
            }
        }

        override suspend fun reject(transferId: Long) {
            stateLock.withLock {
                val transfer = transfers.byId(transferId) ?: return
                if (transfer.direction != DccDirection.INCOMING ||
                    transfer.state !in setOf(DccTransferState.OFFERED, DccTransferState.PARTIAL, DccTransferState.FAILED)
                ) {
                    return
                }
                cancelJob(transferId)
                val cachedUri = transfer.destinationUri?.let(Uri::parse)?.takeIf { resultCache.isOwned(it, transferId) }
                cachedUri?.let { resultCache.discard(it, transferId) }
                transitions(transferId) { current, now ->
                    current.copy(
                        state = DccTransferState.REJECTED,
                        destinationUri = if (cachedUri != null) null else current.destinationUri,
                        error = null,
                        updatedAt = now,
                    )
                }
            }
        }

        override suspend fun removeRecord(transferId: Long) {
            stateLock.withLock {
                val transfer = transfers.byId(transferId) ?: return
                cancelJob(transferId)
                val cachedUri = transfer.destinationUri?.let(Uri::parse)?.takeIf { resultCache.isOwned(it, transferId) }
                cachedUri?.let { resultCache.discard(it, transferId) }
                transitions(transferId) { current, now ->
                    current.copy(
                        state = DccTransferState.REMOVED,
                        destinationUri = if (cachedUri != null) null else current.destinationUri,
                        error = null,
                        updatedAt = now,
                    )
                }
            }
        }

        override suspend fun saveToDownloads(transferId: Long) {
            stateLock.withLock {
                val transfer = transfers.byId(transferId) ?: error("Transfer record is unavailable")
                saveDccToDownloads(context, transfer)
            }
        }

        override suspend fun prepareSend(
            bufferId: Long,
            sourceUri: Uri,
            allowOriginalMetadata: Boolean,
        ): PreparedDccSend {
            if (!sourceUri.isLocalMediaUri()) throw DccSendUnavailableException("Direct file sending requires a local file")
            val target = currentSendTarget(bufferId)
            val stripMetadata = attachmentPrefs.config.first().stripMetadata
            val document =
                withContext(Dispatchers.IO) {
                    sendStagingDirectory.value // Run the one-time reaping on IO.
                    val meta = queryMeta(sourceUri)
                    AttachmentSource.Document(sourceUri, meta.name, context.contentResolver.getType(sourceUri), meta.sizeBytes)
                }
            val upload =
                metadataSanitizer.prepare(
                    document,
                    MAX_DCC_FILE_SIZE_BYTES,
                    allowOriginal = allowOriginalMetadata,
                    stripMetadata = stripMetadata,
                    stagingDirectory = sendStagingDirectory.value,
                )
            try {
                val source = upload.source as AttachmentSource.LocalFile
                requireSameSendTarget(bufferId, target)
                currentCoroutineContext().ensureActive()
                return PreparedDccSend(
                    bufferId = bufferId,
                    networkId = target.networkId,
                    filename = sanitizeDccDisplayFilename(source.name),
                    sizeBytes = source.file.length(),
                    peerNick = target.peerNick,
                    networkName = target.networkName,
                    normalizedPeer = target.normalizedPeer,
                    owner = this,
                    upload = upload,
                    requireCurrentTarget = { requireSameSendTarget(bufferId, target) },
                )
            } catch (failure: Throwable) {
                upload.close()
                throw failure
            }
        }

        override suspend fun sendPrepared(prepared: PreparedDccSend) {
            val upload = prepared.claim(this) ?: return
            val confirming = currentCoroutineContext()
            var handedOff = false
            try {
                val source = upload.source as AttachmentSource.LocalFile
                stateLock.withLock {
                    // Once recorded, hand the staged file to the application job even if the UI leaves.
                    withContext(NonCancellable) {
                        val id =
                            db.withTransaction {
                                prepared.requireCurrentTarget()
                                confirming.ensureActive()
                                val now = System.currentTimeMillis()
                                val token =
                                    UUID
                                        .randomUUID()
                                        .toString()
                                        .replace("-", "")
                                        .take(16)
                                transfers.insertIgnore(
                                    DccTransferEntity(
                                        networkId = prepared.networkId,
                                        timelineEventId = null,
                                        offerKey = "dcc:outgoing:$token",
                                        direction = DccDirection.OUTGOING,
                                        protocol = DccTransferProtocol.SEND,
                                        peerNick = prepared.peerNick,
                                        normalizedPeer = prepared.normalizedPeer,
                                        filename = prepared.filename,
                                        displayFilename = prepared.filename,
                                        address = "0",
                                        addressKind = DccAddressKind.IPV4_INTEGER,
                                        port = 0,
                                        sizeBytes = prepared.sizeBytes,
                                        token = token,
                                        state = DccTransferState.ACCEPTING,
                                        createdAt = now,
                                        expiresAt = now + OUTGOING_OFFER_TIMEOUT_MS,
                                        acceptedAt = now,
                                        updatedAt = now,
                                    ),
                                )
                            }
                        if (id <= 0) throw DccSendUnavailableException("Unable to record the direct file transfer")
                        launchTransfer(id, onComplete = upload::close) {
                            withContext(Dispatchers.IO) { sendOutgoing(id, prepared, source.file) }
                        }
                        handedOff = true
                    }
                }
            } finally {
                if (!handedOff) upload.close()
            }
        }

        private suspend fun requireSameSendTarget(
            bufferId: Long,
            expected: SendTarget,
        ): IrcClient {
            val current = currentSendTarget(bufferId)
            if (current != expected) throw DccSendUnavailableException("The recipient or network changed. Choose the file again.")
            return current.client
        }

        private suspend fun currentSendTarget(bufferId: Long): SendTarget {
            val query = db.bufferDao().observeById(bufferId)
            if (query == null || query.id != bufferId || query.redirectToRoomId != null ||
                query.type != BufferType.QUERY || query.dismissed || query.pendingCloseAt != null
            ) {
                throw DccSendUnavailableException("Direct file sending requires an open private chat")
            }
            val network =
                db.networkDao().byId(query.networkId)
                    ?: throw DccSendUnavailableException("The IRC network is no longer available")
            val root =
                if (network.role == NetworkRole.BOUNCER_CHILD) {
                    network.parentId
                        ?.let { db.networkDao().byId(it) }
                        ?.takeIf { it.role == NetworkRole.BOUNCER_ROOT }
                        ?: throw DccSendUnavailableException("The bouncer connection is no longer available")
                } else {
                    null
                }
            if (usesProxy(root ?: network)) {
                throw DccSendUnavailableException("Direct file sending is unavailable while this network uses a proxy")
            }
            val fingerprint = networkFingerprint(network, root)
            val client =
                connectionManager.readyClientForRoute(network.id, fingerprint)
                    ?: throw DccSendUnavailableException("The IRC network is not ready")
            val peer = query.ircTarget
            val isupport = client.isupport
            val identityRules = isupport.identityRules
            if (!client.targetClassificationReady.value) throw DccSendUnavailableException("The IRC network is not ready")
            if (peer.isEmpty() || peer.first() in ":$" || peer.any { it.isISOControl() || it.isWhitespace() || it in ",@!%*?" } ||
                identityRules.isChannel(peer) || identityRules.isChannel(peer.trimStart { it in isupport["STATUSMSG"].orEmpty() })
            ) {
                throw DccSendUnavailableException("Direct file sending requires a private nickname")
            }
            return SendTarget(network.id, network.name, query.name, peer, network.role, root?.id, fingerprint, identityRules, client)
        }

        private data class SendTarget(
            val networkId: Long,
            val networkName: String,
            val normalizedPeer: String,
            val peerNick: String,
            val role: NetworkRole,
            val rootId: Long?,
            val fingerprint: String,
            val identityRules: IrcIdentityRules,
            val client: IrcClient,
        )

        private suspend fun receiveIncoming(
            transferId: Long,
            destinationUri: Uri,
            allowPrivateEndpoint: Boolean,
        ) {
            val snapshot = transfers.byId(transferId) ?: return
            val privateCache = resultCache.isOwned(destinationUri, transferId)
            var completed = false
            try {
                val network =
                    effectiveNetworkFor(snapshot.networkId)
                        ?: error("Network no longer exists")
                val address = resolveDccAddress(snapshot.address, snapshot.addressKind)
                val risk = dccEndpointRisk(address)
                if (risk != DccEndpointRisk.PUBLIC && !allowPrivateEndpoint) {
                    error("Endpoint is ${risk.name.lowercase().replace('_', ' ')}; allow once to receive")
                }
                val maxBytes = if (privateCache) EbooksResultCache.MAX_COMPRESSED_BYTES else MAX_DCC_FILE_SIZE_BYTES
                if ((snapshot.sizeBytes ?: 0L) > maxBytes) {
                    error(
                        if (privateCache) "Results ZIP is larger than the 16 MiB cache limit" else "File is larger than the 4 GiB safety limit",
                    )
                }
                val proxy = proxyFor(network, transferId)
                val socket = withContext(Dispatchers.IO) { openConnectedSocket(address, snapshot.port, proxy) }
                val inputSocket =
                    if (snapshot.protocol == DccTransferProtocol.SSEND) {
                        (trustAllSslFactory().createSocket(socket, address.hostAddress, snapshot.port, true) as SSLSocket)
                            .apply {
                                useClientMode = true
                                startHandshake()
                            }
                    } else {
                        socket
                    }
                withDccSocket(inputSocket) { active ->
                    active.soTimeout = READ_TIMEOUT_MS
                    context.contentResolver.openOutputStream(destinationUri, "wt").use { output ->
                        requireNotNull(output) { "Unable to open destination" }
                        receiveBytes(
                            transferId = transferId,
                            input = active.getInputStream(),
                            output = output,
                            ack = active.getOutputStream(),
                            expectedBytes = snapshot.sizeBytes,
                            maxBytes = maxBytes,
                        )
                    }
                }
                transitions(transferId) { transfer, now ->
                    transfer.copy(
                        state = DccTransferState.COMPLETED,
                        bytesTransferred = transfer.sizeBytes ?: transfer.bytesTransferred,
                        completedAt = now,
                        error = null,
                        updatedAt = now,
                    )
                }
                completed = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failTransfer(transferId, error.message ?: "DCC receive failed")
            } finally {
                if (privateCache && !completed) {
                    resultCache.discard(destinationUri, transferId)
                    transitions(transferId) { transfer, now ->
                        transfer.copy(
                            state = if (transfer.state == DccTransferState.PARTIAL) DccTransferState.FAILED else transfer.state,
                            bytesTransferred = 0,
                            destinationUri = null,
                            updatedAt = now,
                        )
                    }
                }
            }
        }

        private suspend fun sendOutgoing(
            transferId: Long,
            prepared: PreparedDccSend,
            source: File,
        ) {
            runCatching {
                val current = transfers.byId(transferId) ?: throw DccSendUnavailableException("Transfer no longer exists")
                prepared.requireCurrentTarget()
                val advertised = chooseAdvertisedAddress()
                if (dccEndpointRisk(advertised) != DccEndpointRisk.PUBLIC) {
                    throw DccSendUnavailableException("No public local address is available for direct file sending")
                }
                val (wireAddress, addressKind) = advertiseDccAddress(advertised)
                val client = prepared.requireCurrentTarget()
                val server = ServerSocket(0)
                withDccSocket(server) { listening ->
                    listening.soTimeout = ACCEPT_TIMEOUT_MS
                    val offer =
                        DccOutgoingOffer(
                            protocol = DccTransferProtocol.SEND,
                            filename = current.filename,
                            address = wireAddress,
                            port = listening.localPort,
                            sizeBytes = current.sizeBytes,
                            token = current.token,
                        )
                    val sent =
                        client.sendGuardedMessage(prepared.peerNick, dccSendCtcp(offer), label = "motd-${UUID.randomUUID()}") {
                            prepared.requireCurrentTarget() === client
                        }
                    if (!sent) throw DccSendUnavailableException("IRC rejected the file offer")
                    transitions(transferId) { transfer, now ->
                        transfer.copy(
                            address = wireAddress,
                            addressKind = addressKind,
                            port = listening.localPort,
                            state = DccTransferState.OFFERED,
                            updatedAt = now,
                        )
                    }
                    val accepted = listening.accept()
                    withDccSocket(accepted) { active ->
                        transitions(transferId) { transfer, now ->
                            transfer.copy(state = DccTransferState.ACTIVE, error = null, updatedAt = now)
                        }
                        source.inputStream().use { input ->
                            sendBytes(transferId, input, active.getOutputStream(), current.sizeBytes)
                        }
                    }
                    transitions(transferId) { transfer, now ->
                        transfer.copy(
                            state = DccTransferState.COMPLETED,
                            bytesTransferred = transfer.sizeBytes ?: transfer.bytesTransferred,
                            completedAt = now,
                            error = null,
                            updatedAt = now,
                        )
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                val message =
                    when (error) {
                        is DccSendUnavailableException -> error.message ?: "DCC send failed"
                        is java.net.SocketTimeoutException -> "The direct file transfer timed out"
                        else -> "DCC send failed"
                    }
                failTransfer(transferId, message)
            }
        }

        private suspend fun receiveBytes(
            transferId: Long,
            input: InputStream,
            output: OutputStream,
            ack: OutputStream,
            expectedBytes: Long?,
            maxBytes: Long,
        ): Long {
            transitions(transferId) { transfer, now ->
                transfer.copy(state = DccTransferState.ACTIVE, error = null, updatedAt = now)
            }
            return receiveDccBytes(
                input = input,
                output = output,
                ack = ack,
                expectedBytes = expectedBytes,
                maxBytes = maxBytes,
                progressStepBytes = PROGRESS_STEP_BYTES,
            ) { total -> persistProgress(transferId, total) }
        }

        private suspend fun sendBytes(
            transferId: Long,
            input: InputStream,
            output: OutputStream,
            expectedBytes: Long?,
        ) = withContext(Dispatchers.IO) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            var lastPersisted = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                if (total - lastPersisted >= PROGRESS_STEP_BYTES) {
                    lastPersisted = total
                    persistProgress(transferId, total)
                }
                if (expectedBytes != null && total >= expectedBytes) break
            }
            output.flush()
            persistProgress(transferId, total)
        }

        private suspend fun persistProgress(
            transferId: Long,
            total: Long,
        ) {
            transitions(transferId) { transfer, now ->
                transfer.copy(bytesTransferred = total, updatedAt = now)
            }
        }

        private suspend fun failTransfer(
            transferId: Long,
            message: String,
        ) {
            transitions(transferId) { transfer, now ->
                val partial = transfer.direction == DccDirection.INCOMING && transfer.bytesTransferred > 0L
                transfer.copy(
                    state = if (partial) DccTransferState.PARTIAL else DccTransferState.FAILED,
                    error = message.take(180),
                    updatedAt = now,
                )
            }
        }

        private suspend fun launchTransfer(
            transferId: Long,
            onComplete: (() -> Unit)? = null,
            block: suspend () -> Unit,
        ) {
            cancelJob(transferId)
            val job = applicationScope.launch { block() }
            onComplete?.let { cleanup -> job.invokeOnCompletion { cleanup() } }
            jobLock.withLock { jobs[transferId] = job }
        }

        private suspend fun cancelJob(transferId: Long) {
            val job = jobLock.withLock { jobs.remove(transferId) }
            job?.cancelAndJoin()
        }

        private suspend fun transitions(
            transferId: Long,
            transform: (DccTransferEntity, Long) -> DccTransferEntity,
        ) = withContext(NonCancellable) {
            val current = transfers.byId(transferId) ?: return@withContext
            transfers.update(transform(current, System.currentTimeMillis()))
        }

        private fun proxyFor(
            network: NetworkEntity,
            transferId: Long,
        ): Proxy? {
            val resolution = resolveTransportProxy(network, localSocksProvider, ownerKey = "dcc-$transferId")
            resolution.error?.let { message -> error(message) }
            return resolution.proxy
        }

        private suspend fun effectiveNetworkFor(networkId: Long): NetworkEntity? {
            val row = db.networkDao().byId(networkId) ?: return null
            return if (row.role == NetworkRole.BOUNCER_CHILD && row.parentId != null) {
                db.networkDao().byId(row.parentId) ?: row
            } else {
                row
            }
        }

        private fun usesProxy(network: NetworkEntity): Boolean = network.obfsMode != null && network.obfsMode != ObfsMode.NONE

        private fun openConnectedSocket(
            address: InetAddress,
            port: Int,
            proxy: Proxy?,
        ): Socket {
            require(port in 1..65535) { "Passive DCC offers need reverse mode, which is not available for receive yet" }
            val socket = if (proxy != null) Socket(proxy) else Socket()
            return socket.apply {
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            }
        }

        private fun queryMeta(uri: Uri): FileMeta {
            val resolver = context.contentResolver
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME) ?: "file"
                        val size = cursor.longOrNull(OpenableColumns.SIZE)
                        return FileMeta(name, size)
                    }
                }
            return FileMeta(uri.lastPathSegment ?: "file", null)
        }

        private fun chooseAdvertisedAddress(): InetAddress =
            NetworkInterface
                .getNetworkInterfaces()
                .iterator()
                .asSequence()
                .flatMap { it.inetAddresses.iterator().asSequence() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        !address.isAnyLocalAddress &&
                        address is java.net.Inet4Address
                }
                ?: InetAddress.getByName("127.0.0.1")

        private data class FileMeta(
            val name: String,
            val sizeBytes: Long?,
        )

        private fun android.database.Cursor.stringOrNull(column: String): String? {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getString(index) else null
        }

        private fun android.database.Cursor.longOrNull(column: String): Long? {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getLong(index) else null
        }

        private fun trustAllSslFactory(): SSLSocketFactory = TRUST_ALL_SSL_FACTORY

        companion object {
            private const val CONNECT_TIMEOUT_MS = 15_000
            private const val ACCEPT_TIMEOUT_MS = 5 * 60_000
            private const val READ_TIMEOUT_MS = 30_000
            private const val OUTGOING_OFFER_TIMEOUT_MS = 5 * 60_000L
            private const val PROGRESS_STEP_BYTES = 256L * 1024L
            const val MAX_DCC_FILE_SIZE_BYTES = 0xffff_ffffL

            // DCC SSEND has no CA-backed peer identity model; the UI labels it as encrypted only.
            private val TRUST_ALL_SSL_FACTORY: SSLSocketFactory by lazy {
                val trustAll =
                    @SuppressLint("CustomX509TrustManager")
                    object : X509TrustManager {
                        override fun checkClientTrusted(
                            chain: Array<java.security.cert.X509Certificate>,
                            authType: String,
                        ) = Unit

                        override fun checkServerTrusted(
                            chain: Array<java.security.cert.X509Certificate>,
                            authType: String,
                        ) = Unit

                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                    }
                SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }.socketFactory
            }
        }
    }

internal suspend fun <T : Closeable, R> withDccSocket(
    socket: T,
    block: suspend (T) -> R,
): R =
    coroutineScope {
        val closeOnCancel =
            launch(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Nothing> { continuation ->
                    continuation.invokeOnCancellation { runCatching { socket.close() } }
                }
            }
        try {
            block(socket)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            closeOnCancel.cancel()
            runCatching { socket.close() }
        }
    }
