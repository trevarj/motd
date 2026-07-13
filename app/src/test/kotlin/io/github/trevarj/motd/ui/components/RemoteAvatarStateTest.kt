package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.avatar.AvatarRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAvatarStateTest {
    private val alice = AvatarRecord(
        networkId = 7,
        identity = "account:alice",
        nick = "alice",
        account = "Alice",
        url = "https://example.com/{size}.png",
        updatedAt = 1,
    )

    @Test fun resolves_account_then_nick_with_network_isolation() {
        val state = RemoteAvatarState(enabled = true, records = listOf(alice))
        assertEquals("https://example.com/40.png", state.url(7, "OtherNick", "ALICE", 40))
        assertEquals("https://example.com/40.png", state.url(7, "Alice", null, 40))
        assertNull(state.url(8, "Alice", "alice", 40))
    }

    @Test fun disabled_state_returns_no_model_so_coil_cannot_request() {
        val state = RemoteAvatarState(enabled = false, records = listOf(alice))
        assertNull(state.url(7, "Alice", "alice", 40))
    }
}
