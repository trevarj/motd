package io.github.trevarj.motd.sidecar

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.MAX_IRC_LINE_BYTES
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.sidecar.SidecarJson
import io.github.trevarj.motd.sidecar.SidecarSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.EOFException
import java.util.UUID

class SidecarTransportFactory(
    private val binder: SidecarBinder,
    private val component: ComponentName,
    private val accountId: String,
) : TransportFactory {
    override fun create(
        host: String,
        port: Int,
        tls: Boolean,
        wsUrl: String?,
        proxy: java.net.Proxy?,
    ): IrcTransport =
        SidecarTransport {
            val binding = binder.bindTrusted(component)
            try {
                val descriptor =
                    binding.provider.openSession(
                        accountId,
                        SidecarJson.encodeSessionOptions(
                            SidecarSessionOptions(clientInstanceId = UUID.randomUUID().toString()),
                        ),
                    ) ?: error("Companion provider returned no session")
                SidecarOpenedSession(descriptor, binding::close)
            } catch (failure: Throwable) {
                binding.close()
                throw failure
            }
        }
}

internal data class SidecarOpenedSession(
    val descriptor: ParcelFileDescriptor,
    val closeBinding: () -> Unit = {},
)

internal class SidecarTransport(
    private val openSession: suspend () -> SidecarOpenedSession,
) : IrcTransport {
    private val sendMutex = Mutex()
    private var closeBinding: (() -> Unit)? = null
    private var inputDescriptor: ParcelFileDescriptor? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var source: BufferedSource? = null
    private var sink: BufferedSink? = null

    override suspend fun connect() {
        val opened = openSession()
        val descriptor = opened.descriptor
        val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        closeBinding = opened.closeBinding
        inputDescriptor = descriptor
        outputDescriptor = duplicate
        source = ParcelFileDescriptor.AutoCloseInputStream(descriptor).source().buffer()
        sink = ParcelFileDescriptor.AutoCloseOutputStream(duplicate).sink().buffer()
    }

    override val incoming: Flow<String> =
        channelFlow {
            val input = source ?: error("connect() not called")
            while (true) {
                val line =
                    try {
                        input.readUtf8LineStrict(MAX_IRC_LINE_BYTES.toLong())
                    } catch (_: EOFException) {
                        inputDescriptor?.checkError()
                        break
                    }
                send(line)
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun send(line: String) {
        val output = sink ?: error("connect() not called")
        sendMutex.withLock {
            runInterruptible(Dispatchers.IO) {
                output.writeUtf8(line)
                output.writeUtf8("\r\n")
                output.flush()
            }
        }
    }

    override suspend fun sendAll(lines: List<String>) {
        if (lines.isEmpty()) return
        val output = sink ?: error("connect() not called")
        sendMutex.withLock {
            runInterruptible(Dispatchers.IO) {
                lines.forEach {
                    output.writeUtf8(it)
                    output.writeUtf8("\r\n")
                }
                output.flush()
            }
        }
    }

    override suspend fun close() {
        runInterruptible(Dispatchers.IO) {
            runCatching { source?.close() }
            runCatching { sink?.close() }
            runCatching { inputDescriptor?.close() }
            runCatching { outputDescriptor?.close() }
            closeBinding?.invoke()
        }
        source = null
        sink = null
        inputDescriptor = null
        outputDescriptor = null
        closeBinding = null
    }
}
