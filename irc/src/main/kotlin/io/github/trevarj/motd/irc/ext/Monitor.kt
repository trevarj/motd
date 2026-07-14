package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage

sealed interface MonitorSupport {
    data object Unsupported : MonitorSupport
    data object Unlimited : MonitorSupport
    data class Limited(val limit: Int) : MonitorSupport
    data object Malformed : MonitorSupport
}

fun monitorSupport(isupport: Map<String, String>): MonitorSupport {
    val raw = isupport["MONITOR"] ?: return MonitorSupport.Unsupported
    if (raw.isBlank()) return MonitorSupport.Unlimited
    val limit = raw.toIntOrNull() ?: return MonitorSupport.Malformed
    return if (limit > 0) MonitorSupport.Limited(limit) else MonitorSupport.Malformed
}

object MonitorCommands {
    fun clear() = IrcMessage(command = "MONITOR", params = listOf("C"))
    fun status() = IrcMessage(command = "MONITOR", params = listOf("S"))
    fun remove(targets: Collection<String>, maxBytes: Int = 400): List<IrcMessage> =
        chunk("-", targets, maxBytes)
    fun add(targets: Collection<String>, maxBytes: Int = 400): List<IrcMessage> =
        chunk("+", targets, maxBytes)

    private fun chunk(operation: String, targets: Collection<String>, maxBytes: Int): List<IrcMessage> {
        val result = mutableListOf<IrcMessage>()
        var current = mutableListOf<String>()
        targets.forEach { target ->
            if (target.isBlank() || ',' in target || ' ' in target) return@forEach
            val candidate = current + target
            val message = message(operation, candidate)
            if (current.isNotEmpty() && message.serialize().toByteArray(Charsets.UTF_8).size > maxBytes) {
                result += message(operation, current)
                current = mutableListOf(target)
            } else {
                current += target
            }
        }
        if (current.isNotEmpty()) result += message(operation, current)
        return result
    }

    private fun message(operation: String, targets: List<String>) =
        IrcMessage(command = "MONITOR", params = listOf(operation, targets.joinToString(",")))
}
