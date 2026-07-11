package io.github.trevarj.motd.obfs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessLinkTest {
    private val valid = "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?" +
        "type=tcp&security=reality&sni=www.cloudflare.com&pbk=public-key&sid=a1b2&" +
        "fp=chrome"

    @Test
    fun `parses supported TCP REALITY no-flow link into sing-box outbound`() {
        val link = VlessLink.parse(valid).getOrThrow()

        assertEquals("edge.example", link.host)
        assertEquals(443, link.port)
        assertEquals("chrome", link.fingerprint)
        val outbound = Json.parseToJsonElement(link.toSingBoxOutboundJson()).jsonObject
        assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("edge.example", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("www.cloudflare.com", outbound["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        assertEquals("public-key", outbound["tls"]!!.jsonObject["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects missing REALITY requirements and unsupported transports`() {
        assertTrue(VlessLink.parse("vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?type=tcp&security=reality").isFailure)
        assertTrue(VlessLink.parse(valid.replace("type=tcp", "type=ws")).isFailure)
        assertTrue(VlessLink.parse(valid.replace("security=reality", "security=tls")).isFailure)
        assertTrue(VlessLink.parse("$valid&flow=xtls-rprx-vision").isFailure)
    }
}
