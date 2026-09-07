package io.github.trevarj.motd.obfs

import io.github.trevarj.motd.service.toSingBoxConfigJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessLinkTest {
    private val valid =
        "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?" +
            "type=tcp&security=reality&sni=www.cloudflare.com&pbk=public-key&sid=a1b2&" +
            "fp=chrome"
    private val websocket =
        "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?" +
            "type=ws&security=tls&sni=tls.example&host=http.example&path=%2Firc-vless%3Ftoken%3Da%2Bb%26mode%3Dchat"

    @Test
    fun `parses supported TCP REALITY no-flow link into sing-box outbound`() {
        val link = VlessLink.parse(valid).getOrThrow()

        val outbound = Json.parseToJsonElement(link.toSingBoxOutboundJson()).jsonObject
        val tls = outbound["tls"]!!.jsonObject
        assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("edge.example", outbound["server"]!!.jsonPrimitive.content)
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(tls["insecure"]!!.jsonPrimitive.boolean)
        assertEquals("www.cloudflare.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("public-key", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
        assertEquals("a1b2", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
        assertNull(outbound["transport"])
    }

    @Test
    fun `parses WebSocket TLS with independent ingress SNI Host and decoded path`() {
        val outbound = Json.parseToJsonElement(VlessLink.parse(websocket).getOrThrow().toSingBoxOutboundJson()).jsonObject
        val tls = outbound["tls"]!!.jsonObject
        val transport = outbound["transport"]!!.jsonObject

        assertEquals("edge.example", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("tls.example", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/irc-vless?token=a+b&mode=chat", transport["path"]!!.jsonPrimitive.content)
        assertEquals("http.example", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(tls["insecure"]!!.jsonPrimitive.boolean)
        assertNull(tls["reality"])
    }

    @Test
    fun `WebSocket without optional Host and path still uses TLS transport`() {
        val uri = websocket.substringBefore("&host=")
        val outbound = Json.parseToJsonElement(VlessLink.parse(uri).getOrThrow().toSingBoxOutboundJson()).jsonObject
        val transport = outbound["transport"]!!.jsonObject

        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/", transport["path"]!!.jsonPrimitive.content)
        assertNull(transport["headers"])
        assertNull(outbound["tls"]!!.jsonObject["reality"])
    }

    @Test
    fun `WebSocket URI cannot disable certificate verification`() {
        val uri = "$websocket&allowInsecure=1&insecure=true&skip-cert-verify=true"
        val outbound = Json.parseToJsonElement(VlessLink.parse(uri).getOrThrow().toSingBoxOutboundJson()).jsonObject

        assertTrue(outbound["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(outbound["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `sing-box config keeps both supported transports on the private VLESS route`() {
        for (uri in listOf(valid, websocket)) {
            val config = Json.parseToJsonElement(VlessLink.parse(uri).getOrThrow().toSingBoxConfigJson()).jsonObject
            val final = config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content
            val outbound = config["outbounds"]!!.jsonArray.single { it.jsonObject["tag"]!!.jsonPrimitive.content == final }.jsonObject
            val inbound = config["inbounds"]!!.jsonArray.single().jsonObject

            assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
            assertEquals("socks", inbound["type"]!!.jsonPrimitive.content)
            assertEquals("127.0.0.1", inbound["listen"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `rejects missing REALITY requirements and unsupported security combinations`() {
        for (required in listOf("&sni=www.cloudflare.com", "&pbk=public-key", "&sid=a1b2")) {
            assertTrue(VlessLink.parse(valid.replace(required, "")).isFailure)
        }
        assertTrue(VlessLink.parse(valid.replace("type=tcp", "type=ws")).isFailure)
        assertTrue(VlessLink.parse(valid.replace("security=reality", "security=tls")).isFailure)
        assertTrue(VlessLink.parse(websocket.replace("security=tls", "security=none")).isFailure)
        assertTrue(VlessLink.parse(websocket.replace("type=ws", "type=grpc")).isFailure)
        assertTrue(VlessLink.parse(websocket.replace("type=ws&", "")).isFailure)
        assertTrue(VlessLink.parse(websocket.replace("&sni=tls.example", "")).isFailure)
    }

    @Test
    fun `rejects nonempty flow on both transports`() {
        for (uri in listOf(valid, websocket)) {
            assertTrue(VlessLink.parse("$uri&flow=xtls-rprx-vision").isFailure)
            assertTrue(VlessLink.parse("$uri&flow=%20").isFailure)
        }
    }

    @Test
    fun `rejects WebSocket header injection and invalid paths`() {
        assertTrue(VlessLink.parse(websocket.replace("host=http.example", "host=http.example%0D%0AX-Test%3Aevil")).isFailure)
        assertTrue(VlessLink.parse("$websocket&path=relative").isFailure)
        assertTrue(VlessLink.parse("$websocket&path=%2Firc%0A").isFailure)
    }

    @Test
    fun `malformed URI errors do not expose credentials`() {
        val error = VlessLink.parse("$websocket&path=%").exceptionOrNull()!!

        assertFalse(error.message.orEmpty().contains("123e4567-e89b-12d3-a456-426614174000"))
    }
}
