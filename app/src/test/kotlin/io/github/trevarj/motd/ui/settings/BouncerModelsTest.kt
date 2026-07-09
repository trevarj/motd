package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.client.BouncerNetwork
import io.github.trevarj.motd.ui.settings.bouncer.mergeBouncerRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BouncerModelsTest {

    private fun child(id: Long, netId: String) = NetworkEntity(
        id = id, name = "child", role = NetworkRole.BOUNCER_CHILD, parentId = 1L,
        bouncerNetId = netId, host = "h", port = 6697,
        nick = "me", username = "me", realname = "me",
    )

    @Test
    fun imported_row_carries_child_network_id() {
        val listing = listOf(BouncerNetwork("42", mapOf("name" to "Libera", "host" to "irc.libera.chat", "state" to "connected")))
        val rows = mergeBouncerRows(listing, listOf(child(7, "42")))
        assertEquals(1, rows.size)
        assertEquals("Libera", rows[0].name)
        assertEquals("irc.libera.chat", rows[0].host)
        assertEquals("connected", rows[0].bouncerState)
        assertEquals(7L, rows[0].childNetworkId)
    }

    @Test
    fun unimported_row_has_null_child_id() {
        val listing = listOf(BouncerNetwork("42", mapOf("name" to "Libera")))
        val rows = mergeBouncerRows(listing, emptyList())
        assertNull(rows[0].childNetworkId)
    }

    @Test
    fun child_with_different_netid_does_not_match() {
        val listing = listOf(BouncerNetwork("42", mapOf("name" to "Libera")))
        val rows = mergeBouncerRows(listing, listOf(child(7, "99")))
        assertNull(rows[0].childNetworkId)
    }

    @Test
    fun name_falls_back_to_host_then_netid() {
        val rows = mergeBouncerRows(
            listOf(
                BouncerNetwork("1", mapOf("host" to "irc.oftc.net")),   // no name -> host
                BouncerNetwork("2", emptyMap()),                        // no name/host -> netId
            ),
            emptyList(),
        )
        assertEquals("irc.oftc.net", rows[0].name)
        assertEquals("2", rows[1].name)
    }

    @Test
    fun listing_order_preserved() {
        val rows = mergeBouncerRows(
            listOf(
                BouncerNetwork("a", mapOf("name" to "A")),
                BouncerNetwork("b", mapOf("name" to "B")),
            ),
            emptyList(),
        )
        assertEquals(listOf("a", "b"), rows.map { it.netId })
    }
}
