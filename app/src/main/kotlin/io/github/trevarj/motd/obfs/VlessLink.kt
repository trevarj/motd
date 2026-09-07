package io.github.trevarj.motd.obfs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.UUID

/**
 * The deliberately small VLESS share-link subset motd can hand to its embedded core.
 *
 * Only TCP + REALITY and WebSocket + TLS without flow are supported. A pasted link can never
 * silently select an unsupported transport or security mode.
 */
data class VlessLink(
    val uuid: String,
    val host: String,
    val port: Int,
    val sni: String,
    val publicKey: String?,
    val shortId: String?,
    val fingerprint: String,
    /** Non-null for WebSocket + TLS; null keeps the TCP + REALITY transport. */
    val wsPath: String? = null,
    val wsHost: String? = null,
) {
    /** One sing-box VLESS outbound, ready to be placed in libbox's generated configuration. */
    fun toSingBoxOutboundJson(): String =
        Json.encodeToString(
            kotlinx.serialization.json.JsonObject
                .serializer(),
            buildJsonObject {
                put("type", "vless")
                put("tag", "motd-vless")
                put("server", host)
                put("server_port", port)
                put("uuid", uuid)
                put(
                    "tls",
                    buildJsonObject {
                        put("enabled", true)
                        put("server_name", sni)
                        put("insecure", false)
                        if (wsPath == null) {
                            put(
                                "utls",
                                buildJsonObject {
                                    put("enabled", true)
                                    put("fingerprint", fingerprint)
                                },
                            )
                            put(
                                "reality",
                                buildJsonObject {
                                    put("enabled", true)
                                    put("public_key", publicKey)
                                    put("short_id", shortId)
                                },
                            )
                        }
                    },
                )
                if (wsPath != null) {
                    put(
                        "transport",
                        buildJsonObject {
                            put("type", "ws")
                            put("path", wsPath)
                            if (wsHost != null) {
                                put("headers", buildJsonObject { put("Host", wsHost) })
                            }
                        },
                    )
                }
            },
        )

    companion object {
        /** Parse only the TCP/REALITY or WebSocket/TLS no-flow subsets supported by libbox. */
        fun parse(value: String): Result<VlessLink> =
            runCatching {
                val uri =
                    try {
                        URI(value.trim())
                    } catch (_: URISyntaxException) {
                        throw IllegalArgumentException("VLESS URI is invalid")
                    }
                require(uri.scheme.equals("vless", ignoreCase = true)) { "Link must use the vless scheme" }
                require(!uri.userInfo.isNullOrBlank()) { "VLESS UUID is required" }
                val uuid =
                    try {
                        UUID.fromString(uri.userInfo).toString()
                    } catch (_: IllegalArgumentException) {
                        throw IllegalArgumentException("VLESS UUID is invalid")
                    }
                val host =
                    uri.host?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("VLESS server host is required")
                val port = uri.port
                require(port in 1..65535) { "VLESS server port is required" }

                val params = queryParameters(uri.rawQuery)
                val transport = params["type"]?.lowercase()
                val security = params["security"]?.lowercase()
                require(
                    (transport == "tcp" && security == "reality") ||
                        (transport == "ws" && security == "tls"),
                ) { "Only TCP + REALITY or WebSocket + TLS VLESS links are supported" }
                require(params["flow"].isNullOrEmpty()) { "VLESS flow is not supported" }
                val wsPath = if (transport == "ws") params["path"]?.ifEmpty { "/" } ?: "/" else null
                val wsHost = if (transport == "ws") params["host"]?.ifEmpty { null } else null
                require(wsPath == null || (wsPath.startsWith('/') && wsPath.none { it.isISOControl() })) {
                    "VLESS WebSocket path must start with / and contain no control characters"
                }
                require(wsHost == null || wsHost.none { it.isWhitespace() || it.isISOControl() }) {
                    "VLESS WebSocket Host must contain no whitespace or control characters"
                }

                VlessLink(
                    uuid = uuid,
                    host = host,
                    port = port,
                    sni = required(params, "sni"),
                    publicKey = if (transport == "tcp") required(params, "pbk") else null,
                    shortId = if (transport == "tcp") required(params, "sid") else null,
                    fingerprint =
                        params["fp"]?.takeIf { it.isNotBlank() }
                            ?: params["fingerprint"]?.takeIf { it.isNotBlank() }
                            ?: "chrome",
                    wsPath = wsPath,
                    wsHost = wsHost,
                )
            }

        private fun required(
            params: Map<String, String>,
            name: String,
        ): String =
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
