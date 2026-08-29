package io.github.trevarj.motd.sidecar

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(RobolectricTestRunner::class)
class SidecarTransportTest {
    @Ignore("Robolectric cannot dup AF_UNIX socket descriptors; covered by device instrumentation")
    @Test
    fun reliableSocketCarriesFullDuplexIrcLines() =
        runTest {
            val pair = ParcelFileDescriptor.createReliableSocketPair()
            val transport = SidecarTransport { SidecarOpenedSession(pair[0]) }
            transport.connect()

            val remoteOutput =
                ParcelFileDescriptor.AutoCloseOutputStream(
                    ParcelFileDescriptor.dup(pair[1].fileDescriptor),
                )
            val incoming = async { transport.incoming.first() }
            remoteOutput.write(":sidecar PING token\r\n".toByteArray())
            remoteOutput.flush()
            assertEquals(":sidecar PING token", incoming.await())

            val remoteReader =
                BufferedReader(
                    InputStreamReader(
                        ParcelFileDescriptor.AutoCloseInputStream(pair[1]),
                    ),
                )
            transport.send("PONG token")
            assertEquals("PONG token", remoteReader.readLine())

            transport.close()
            remoteOutput.close()
            remoteReader.close()
        }
}
