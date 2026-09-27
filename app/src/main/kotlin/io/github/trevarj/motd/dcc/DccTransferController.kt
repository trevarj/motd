package io.github.trevarj.motd.dcc

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferType
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
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.resolveTransportProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

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

    suspend fun sendFile(
        bufferId: Long,
        sourceUri: Uri,
        secure: Boolean = false,
    )
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
    ) : DccTransferController {
        private val transfers: DccTransferDao get() = db.dccTransferDao()
        private val resultCache = EbooksResultCache(context)
        private val stateLock = Mutex()
        private val jobs = LinkedHashMap<Long, Job>()
        private val jobLock = Mutex()

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
                    transfer.destinationUri?.let { resultCache.discard(Uri.parse(it), transferId) }
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

        override suspend fun sendFile(
            bufferId: Long,
            sourceUri: Uri,
            secure: Boolean,
        ) {
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            if (buffer.type != BufferType.QUERY) return
            val endpointNetwork = effectiveNetworkFor(buffer.networkId) ?: return
            val meta = queryMeta(sourceUri)
            val filename = sanitizeDccDisplayFilename(meta.name)
            val now = System.currentTimeMillis()
            val token =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(16)
            val id =
                transfers.insertIgnore(
                    DccTransferEntity(
                        networkId = buffer.networkId,
                        timelineEventId = null,
                        offerKey = "dcc:outgoing:$token",
                        direction = DccDirection.OUTGOING,
                        protocol = DccTransferProtocol.SEND,
                        peerNick = buffer.displayName,
                        normalizedPeer = buffer.name,
                        filename = filename,
                        displayFilename = filename,
                        address = "0",
                        addressKind = io.github.trevarj.motd.data.db.DccAddressKind.IPV4_INTEGER,
                        port = 0,
                        sizeBytes = meta.sizeBytes,
                        token = token,
                        state = DccTransferState.ACCEPTING,
                        createdAt = now,
                        expiresAt = now + OUTGOING_OFFER_TIMEOUT_MS,
                        acceptedAt = now,
                        updatedAt = now,
                    ),
                )
            if (id <= 0) return
            launchTransfer(id) {
                // Outgoing SSEND needs a user-manageable TLS server identity. Until that UX exists,
                // outgoing offers are plaintext SEND; incoming SSEND remains supported as a client.
                sendOutgoing(
                    transferId = id,
                    endpointNetwork = endpointNetwork,
                    clientNetworkId = buffer.networkId,
                    peerTarget = buffer.ircTarget,
                    sourceUri = sourceUri,
                )
            }
        }

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
                inputSocket.use { active ->
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
            endpointNetwork: NetworkEntity,
            clientNetworkId: Long,
            peerTarget: String,
            sourceUri: Uri,
        ) {
            runCatching {
                if (usesProxy(endpointNetwork)) {
                    error("Direct file sending is unavailable while this network uses a proxy")
                }
                val server = ServerSocket(0)
                server.use { listening ->
                    listening.soTimeout = ACCEPT_TIMEOUT_MS
                    val advertised = chooseAdvertisedAddress()
                    if (dccEndpointRisk(advertised) != DccEndpointRisk.PUBLIC) {
                        error("No public local address is available for direct file sending")
                    }
                    val (wireAddress, addressKind) = advertiseDccAddress(advertised)
                    val current = transfers.byId(transferId) ?: error("Transfer no longer exists")
                    transitions(transferId) { transfer, now ->
                        transfer.copy(
                            address = wireAddress,
                            addressKind = addressKind,
                            port = listening.localPort,
                            state = DccTransferState.OFFERED,
                            updatedAt = now,
                        )
                    }
                    val offer =
                        DccOutgoingOffer(
                            protocol = DccTransferProtocol.SEND,
                            filename = current.filename,
                            address = wireAddress,
                            port = listening.localPort,
                            sizeBytes = current.sizeBytes,
                            token = current.token,
                        )
                    sendCtcp(clientNetworkId, peerTarget, dccSendCtcp(offer))
                    val accepted = withContext(Dispatchers.IO) { listening.accept() }
                    accepted.use { active ->
                        transitions(transferId) { transfer, now ->
                            transfer.copy(state = DccTransferState.ACTIVE, error = null, updatedAt = now)
                        }
                        context.contentResolver.openInputStream(sourceUri).use { input ->
                            requireNotNull(input) { "Unable to open source" }
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
                failTransfer(transferId, error.message ?: "DCC send failed")
            }
        }

        private suspend fun sendCtcp(
            networkId: Long,
            target: String,
            ctcp: String,
        ): Boolean {
            val client = connectionManager.clientFor(networkId) ?: return false
            return client.sendMessage(target, ctcp, replyToMsgid = null, label = "motd-${UUID.randomUUID()}")
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
            block: suspend () -> Unit,
        ) {
            cancelJob(transferId)
            val job = applicationScope.launch { block() }
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
