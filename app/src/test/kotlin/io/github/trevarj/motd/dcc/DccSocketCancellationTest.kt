package io.github.trevarj.motd.dcc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch

class DccSocketCancellationTest {
    @Test fun cancellationAfterAcceptStillClosesSocketBeforeDispatcherHandoff() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val acceptedSocket = CompletableDeferred<Socket>()
                val finishAccept = CountDownLatch(1)
                val job =
                    launch {
                        withDccSocket(listener) {
                            withContext(Dispatchers.IO) {
                                val accepted = it.accept()
                                acceptedSocket.complete(accepted)
                                finishAccept.await()
                                withDccSocket(accepted) { }
                            }
                        }
                    }
                Socket().use { peer ->
                    withContext(Dispatchers.IO) { peer.connect(listener.localSocketAddress) }
                    val accepted = acceptedSocket.await()
                    job.cancel()
                    finishAccept.countDown()
                    withTimeout(2_000) { job.join() }
                    assertTrue(accepted.isClosed)
                    assertTrue(listener.isClosed)
                }
            }
        }

    @Test fun cancellingBlockedAcceptClosesListenerAndReleasesOwner() =
        runBlocking {
            val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val enteringAccept = CompletableDeferred<Unit>()
            var released = false
            val job =
                launch(Dispatchers.IO) {
                    try {
                        withDccSocket(listener) {
                            enteringAccept.complete(Unit)
                            it.accept().close()
                        }
                    } finally {
                        released = true
                    }
                }
            enteringAccept.await()
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(listener.isClosed)
            assertTrue(released)
        }

    @Test fun cancellingBlockedWriteClosesActiveSocketAndReleasesOwner() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                Socket().use { writer ->
                    writer.sendBufferSize = 1024
                    withContext(Dispatchers.IO) { writer.connect(listener.localSocketAddress) }
                    listener.accept().use { peer ->
                        peer.receiveBufferSize = 1024
                        val writing = CompletableDeferred<Unit>()
                        var released = false
                        val job =
                            launch(Dispatchers.IO) {
                                try {
                                    withDccSocket(writer) {
                                        val bytes = ByteArray(1024 * 1024)
                                        writing.complete(Unit)
                                        while (true) it.getOutputStream().write(bytes)
                                    }
                                } finally {
                                    released = true
                                }
                            }
                        writing.await()
                        withTimeout(2_000) { job.cancelAndJoin() }
                        assertTrue(writer.isClosed)
                        assertTrue(released)
                    }
                }
            }
        }
}
