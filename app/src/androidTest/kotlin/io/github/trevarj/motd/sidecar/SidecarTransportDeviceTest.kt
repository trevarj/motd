package io.github.trevarj.motd.sidecar

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class SidecarTransportDeviceTest {
    @Test
    fun reliableSocketCarriesFullDuplexIrcLines() =
        runBlocking {
            val pair = ParcelFileDescriptor.createReliableSocketPair()
            val transport = SidecarTransport { SidecarOpenedSession(pair[0]) }
            transport.connect()

            val remoteOutput =
                ParcelFileDescriptor.AutoCloseOutputStream(
                    ParcelFileDescriptor.dup(pair[1].fileDescriptor),
                )
            val received = CompletableDeferred<String>()
            val collector =
                launch(Dispatchers.IO) {
                    transport.incoming.collect { line -> received.complete(line) }
                }
            remoteOutput.write(":sidecar PING token\r\n".toByteArray())
            remoteOutput.flush()
            assertEquals(":sidecar PING token", received.await())

            val remoteReader =
                BufferedReader(
                    InputStreamReader(
                        ParcelFileDescriptor.AutoCloseInputStream(pair[1]),
                    ),
                )
            transport.send("PONG token")
            assertEquals("PONG token", remoteReader.readLine())

            remoteOutput.close()
            remoteReader.close()
            transport.close()
            collector.cancelAndJoin()
        }
}
