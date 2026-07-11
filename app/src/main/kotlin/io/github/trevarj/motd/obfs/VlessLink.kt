package io.github.trevarj.motd.obfs

import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The deliberately small VLESS share-link subset motd can hand to its embedded core.
 *
 * Supporting only TCP + REALITY without flow keeps the persisted configuration unambiguous: a pasted
 * link can never silently select an unsupported transport or security mode.
 */
data class VlessLink(
    val uuid: String,
    val host: String,
    val port: Int,
    val sni: String,
    val publicKey: String,
    val shortId: String,
    val fingerprint: String,
) {
    /** One sing-box VLESS outbound, ready to be placed in libbox's generated configuration. */
    fun toSingBoxOutboundJson(): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("type", "vless")
            put("tag", "motd-reality")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("tls", buildJsonObject {
                put("enabled", true)
                put("server_name", sni)
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                })
                put("reality", buildJsonObject {
                    put("enabled", true)
                    put("public_key", publicKey)
                    put("short_id", shortId)
                })
            })
        },
    )

    companion object {
        /** Parse only the TCP/REALITY/no-flow subset validated by the embedded libbox provider. */
        fun parse(value: String): Result<VlessLink> = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme.equals("vless", ignoreCase = true)) { "Link must use the vless scheme" }
            require(!uri.userInfo.isNullOrBlank()) { "VLESS UUID is required" }
            val uuid = try {
                UUID.fromString(uri.userInfo).toString()
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("VLESS UUID is invalid")
            }
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("VLESS server host is required")
            val port = uri.port
            require(port in 1..65535) { "VLESS server port is required" }

            val params = queryParameters(uri.rawQuery)
            require(params["type"]?.lowercase() == "tcp") { "Only TCP VLESS links are supported" }
            require(params["security"]?.lowercase() == "reality") { "Only REALITY VLESS links are supported" }
            require(params["flow"].isNullOrBlank()) { "VLESS flow is not supported" }

            VlessLink(
                uuid = uuid,
                host = host,
                port = port,
                sni = required(params, "sni"),
                publicKey = required(params, "pbk"),
                shortId = required(params, "sid"),
                fingerprint = params["fp"]?.takeIf { it.isNotBlank() }
                    ?: params["fingerprint"]?.takeIf { it.isNotBlank() }
                    ?: "chrome",
            )
        }

        private fun required(params: Map<String, String>, name: String): String =
            params[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("VLESS $name parameter is required")

        private fun queryParameters(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            return rawQuery.split('&').associate { part ->
                val index = part.indexOf('=')
                val rawKey = if (index >= 0) part.substring(0, index) else part
                val rawValue = if (index >= 0) part.substring(index + 1) else ""
                URLDecoder.decode(rawKey, "UTF-8") to URLDecoder.decode(rawValue, "UTF-8")
            }
        }
    }
}
