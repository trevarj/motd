package io.github.trevarj.motd.ui.settings.bouncer

import io.github.trevarj.motd.bouncer.BouncerServResult
import io.github.trevarj.motd.bouncer.redactBouncerServReply

enum class BouncerControlTab { NETWORKS, CHANNELS, ACCOUNT, ADMIN, CONSOLE }

data class BouncerTranscriptEntry(
    val sender: String,
    val text: String,
    val serverTime: Long,
    val isSelf: Boolean,
)

data class BouncerChannelRow(
    val name: String,
    val status: String,
    val detached: Boolean,
)

data class PendingUserDeletion(
    val username: String,
    internal val token: String,
)

fun bouncerCommandSuggestions(commandPaths: Set<String>, input: String): List<String> {
    val prefix = input.trimStart().lowercase()
    if (prefix.isBlank()) return commandPaths.sorted().take(8)
    return commandPaths.asSequence()
        .filter { it.startsWith(prefix) && it != prefix }
        .sorted()
        .take(8)
        .toList()
}

fun BouncerServResult.safeSummary(): String = when (this) {
    is BouncerServResult.Success -> replies.lastOrNull()?.let(::redactBouncerServReply)
        ?: "Command completed"
    is BouncerServResult.Timeout -> if (replies.isEmpty()) {
        "BouncerServ did not reply in time"
    } else {
        "BouncerServ reply timed out after partial output"
    }
    is BouncerServResult.Disconnected -> "Connect the soju account to run commands"
    is BouncerServResult.Failed -> "BouncerServ command failed"
}

fun extractUserDeletionToken(username: String, replies: List<String>): String? {
    val expected = Regex(
        "(?i)To confirm user deletion, send \\\"user delete ${Regex.escape(username)} (\\S+)\\\"",
    )
    return replies.firstNotNullOfOrNull { expected.find(it)?.groupValues?.get(1) }
}

fun parseChannelStatus(replies: List<String>): List<BouncerChannelRow> = replies.mapNotNull { line ->
    val match = Regex("^(.+) \\[(.+)]$").matchEntire(line.trim()) ?: return@mapNotNull null
    val statuses = match.groupValues[2].split(',').map(String::trim)
    BouncerChannelRow(
        name = match.groupValues[1],
        status = statuses.firstOrNull().orEmpty(),
        detached = "detached" in statuses,
    )
}
