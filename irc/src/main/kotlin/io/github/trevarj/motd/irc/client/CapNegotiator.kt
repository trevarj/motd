package io.github.trevarj.motd.irc.client

/**
 * IRCv3 capability tiers. Policy: request every advertised cap from all tiers plus
 * any config extras; the tier only governs degradation when a cap is absent, which the client
 * handles at runtime.
 */
internal object CapTiers {
    val TIER1 =
        setOf(
            "sasl",
            "cap-notify",
            "message-tags",
            "server-time",
            "znc.in/server-time-iso",
            "batch",
            "labeled-response",
            "echo-message",
        )

    val TIER2 =
        setOf(
            "multi-prefix",
            "away-notify",
            "account-notify",
            "account-tag",
            "extended-join",
            "chghost",
            "setname",
            "userhost-in-names",
            "invite-notify",
            "no-implicit-names",
            "draft/no-implicit-names",
            "soju.im/no-implicit-names",
            "extended-monitor",
            "draft/extended-monitor",
            "sts",
        )

    val TIER3 =
        setOf(
            "draft/chathistory",
            "draft/event-playback",
            "draft/read-marker",
            "soju.im/read",
            "draft/metadata-2",
            "standard-replies",
            "draft/relaymsg",
            "draft/pre-away",
            "draft/channel-rename",
            MESSAGE_REDACTION_CAP,
            ACCOUNT_REGISTRATION_CAP,
            "soju.im/bouncer-networks",
            "soju.im/bouncer-networks-notify",
            "soju.im/webpush",
            "soju.im/search",
            MULTILINE_CAP,
        )

    val ALL: Set<String> = TIER1 + TIER2 + TIER3
}

/**
 * Computes the CAP REQ set and splits it into <=400-byte REQ payloads.
 *
 * `draft/event-playback` is only requested when `draft/chathistory` is also advertised
 *.
 */
internal object CapNegotiator {
    fun requestSet(
        advertised: Set<String>,
        extraCaps: Set<String>,
    ): Set<String> {
        val wanted = CapTiers.ALL + extraCaps
        var req = wanted.filter { it in advertised }.toMutableSet()
        // event-playback only makes sense alongside chathistory.
        if ("draft/event-playback" in req && "draft/chathistory" !in advertised) {
            req.remove("draft/event-playback")
        }
        // metadata-2 uses metadata batches for snapshots and therefore requires batch.
        if ("draft/metadata-2" in req && "batch" !in advertised) {
            req.remove("draft/metadata-2")
        }
        if (MULTILINE_CAP in req && "batch" !in advertised) {
            req.remove(MULTILINE_CAP)
        }
        if (MESSAGE_REDACTION_CAP in req && "message-tags" !in advertised) {
            req.remove(MESSAGE_REDACTION_CAP)
        }
        val selectedNames = preferredNoImplicitNames(advertised)
        req.removeAll(NO_IMPLICIT_NAMES_ALIASES)
        if (selectedNames != null) req.add(selectedNames)
        val selectedMonitor = preferredExtendedMonitor(advertised)
        req.removeAll(EXTENDED_MONITOR_ALIASES)
        if (selectedMonitor != null) req.add(selectedMonitor)
        // draft/read-marker is the IRCv3 standard; soju.im/read is the pre-standard fallback with
        // an identical on-wire shape. Request only the standard when both are advertised so soju
        // broadcasts MARKREAD (not READ) to this client.
        val selectedReadMarker = preferredReadMarker(advertised)
        req.removeAll(READ_MARKER_ALIASES)
        if (selectedReadMarker != null) req.add(selectedReadMarker)
        val selectedServerTime = preferredServerTime(advertised)
        req.removeAll(SERVER_TIME_ALIASES)
        if (selectedServerTime != null) req.add(selectedServerTime)
        return req
    }

    /** Preserve an already-selected no-implicit-names alias for this connection generation. */
    fun runtimeRequestSet(
        newCaps: Set<String>,
        ackedCaps: Set<String>,
        extraCaps: Set<String>,
    ): Set<String> {
        val ackedNames = ackedCaps.mapTo(HashSet()) { it.substringBefore('=') }
        val heldAliases =
            buildSet {
                if (preferredNoImplicitNames(ackedNames) != null) addAll(NO_IMPLICIT_NAMES_ALIASES)
                if (preferredExtendedMonitor(ackedNames) != null) addAll(EXTENDED_MONITOR_ALIASES)
                if (preferredReadMarker(ackedNames) != null) addAll(READ_MARKER_ALIASES)
                if (preferredServerTime(ackedNames) != null) addAll(SERVER_TIME_ALIASES)
            }
        val advertised = (newCaps - heldAliases) + ackedNames
        return requestSet(advertised, extraCaps) - ackedNames
    }

    /** Split caps into space-joined batches whose payload stays within [limit] bytes. */
    fun batches(
        caps: Collection<String>,
        limit: Int = 400,
    ): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (cap in caps) {
            val addLen = cap.length + if (sb.isEmpty()) 0 else 1
            if (sb.isNotEmpty() && sb.length + addLen > limit) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(cap)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

const val MESSAGE_REDACTION_CAP: String = "draft/message-redaction"

fun hasMessageRedactionCap(caps: Set<String>): Boolean {
    val names = caps.mapTo(HashSet()) { it.substringBefore('=') }
    return MESSAGE_REDACTION_CAP in names && "message-tags" in names
}

val NO_IMPLICIT_NAMES_ALIASES: List<String> =
    listOf(
        "no-implicit-names",
        "draft/no-implicit-names",
        "soju.im/no-implicit-names",
    )

fun preferredNoImplicitNames(caps: Set<String>): String? {
    val names = caps.mapTo(HashSet()) { it.substringBefore('=') }
    return NO_IMPLICIT_NAMES_ALIASES.firstOrNull { it in names }
}

val EXTENDED_MONITOR_ALIASES: List<String> = listOf("extended-monitor", "draft/extended-monitor")

fun preferredExtendedMonitor(caps: Set<String>): String? {
    val names = caps.mapTo(HashSet()) { it.substringBefore('=') }
    return EXTENDED_MONITOR_ALIASES.firstOrNull { it in names }
}

// draft/read-marker (IRCv3) supersedes soju's older soju.im/read; both share the timestamp= param.
val READ_MARKER_ALIASES: List<String> = listOf("draft/read-marker", "soju.im/read")

fun preferredReadMarker(caps: Set<String>): String? {
    val names = caps.mapTo(HashSet()) { it.substringBefore('=') }
    return READ_MARKER_ALIASES.firstOrNull { it in names }
}

// IRCv3 server-time supersedes ZNC's legacy ISO timestamp cap; both produce the `time` tag.
val SERVER_TIME_ALIASES: List<String> = listOf("server-time", "znc.in/server-time-iso")

fun preferredServerTime(caps: Set<String>): String? {
    val names = caps.mapTo(HashSet()) { it.substringBefore('=') }
    return SERVER_TIME_ALIASES.firstOrNull { it in names }
}
