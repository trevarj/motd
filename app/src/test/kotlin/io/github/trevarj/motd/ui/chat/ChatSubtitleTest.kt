package io.github.trevarj.motd.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.sidecar.SidecarSecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSubtitleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun initialSnapshotDoesNotClaimTheChatIsDisconnected() {
        assertNull(chatSubtitle(ChatState(), context))
    }

    @Test
    fun transientFailureShowsCurrentReconnectStateWithoutStaleProxyDetail() {
        val state =
            ChatState(
                connState = IrcClientState.Failed("SOCKS5 proxy not connected", fatal = false),
            )

        assertEquals(context.getString(R.string.drawer_state_connecting), chatSubtitle(state, context))
    }

    @Test
    fun fatalFailureRemainsActionable() {
        val state = ChatState(connState = IrcClientState.Failed("SASL authentication failed", fatal = true))

        assertEquals("SASL authentication failed", chatSubtitle(state, context))
    }

    @Test
    fun readyConnectionReturnsToConversationSubtitle() {
        val state =
            ChatState(
                connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                typingNicks = listOf("alice"),
            )

        assertEquals("alice is typing…", chatSubtitle(state, context))
    }

    @Test
    fun historyActivityDoesNotReplaceMemberCount() {
        val state = channelState(memberCount = 42)

        assertEquals(
            ChatSubtitleModel.Text("42 members"),
            chatSubtitleModel(state, context),
        )
    }

    @Test
    fun typingStillReplacesMemberCount() {
        val state = channelState(memberCount = 42, typingNicks = listOf("alice"))

        assertEquals(
            ChatSubtitleModel.Text("alice is typing…"),
            chatSubtitleModel(state, context),
        )
    }

    @Test
    fun sidecarSecurityReplacesOrdinaryReadySubtitle() {
        val state =
            channelState(memberCount = 42).copy(
                buffer =
                    channelState(42).buffer?.copy(
                        sidecarSecurity = SidecarSecurityState.E2EE_VERIFIED,
                    ),
            )

        assertEquals(
            context.getString(R.string.sidecar_security_verified),
            chatSubtitle(state, context),
        )
    }

    @Test
    fun memberCountRemainsTheDefaultReadyChannelSubtitle() {
        val state = channelState(memberCount = 42)

        assertEquals("42 members", chatSubtitle(state, context))
    }

    private fun channelState(
        memberCount: Int,
        typingNicks: List<String> = emptyList(),
    ) = ChatState(
        buffer =
            BufferEntity(
                networkId = 1,
                name = "#motd",
                displayName = "#motd",
                type = BufferType.CHANNEL,
            ),
        connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
        memberCount = memberCount,
        typingNicks = typingNicks,
    )
}
