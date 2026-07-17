package io.github.trevarj.motd.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
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
        val state = ChatState(
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
        val state = ChatState(
            connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
            typingNicks = listOf("alice"),
        )

        assertEquals("alice is typing…", chatSubtitle(state, context))
    }
}
