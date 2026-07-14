package io.github.trevarj.motd.irc.client

/**
 * IRCv3 capability tiers (plans/03). Policy: request every advertised cap from all tiers plus
 * any config extras; the tier only governs degradation when a cap is absent, which the client
 * handles at runtime.
 */
internal object CapTiers {
    val TIER1 = setOf(
        "sasl",
        "cap-notify",
        "message-tags",
        "server-time",
        "batch",
        "labeled-response",
        "echo-message",
    )

    val TIER2 = setOf(
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
        "sts",
    )

    val TIER3 = setOf(
        "draft/chathistory",
        "draft/event-playback",
        "draft/read-marker",
        "draft/metadata-2",
        "soju.im/bouncer-networks",
        "soju.im/bouncer-networks-notify",
        "soju.im/webpush",
    )

    val ALL: Set<String> = TIER1 + TIER2 + TIER3
}

/**
 * Computes the CAP REQ set and splits it into <=400-byte REQ payloads.
 *
 * `draft/event-playback` is only requested when `draft/chathistory` is also advertised
 * (plans/03).
 */
internal object CapNegotiator {
    fun requestSet(advertised: Set<String>, extraCaps: Set<String>): Set<String> {
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
        val selectedNames = preferredNoImplicitNames(advertised)
        req.removeAll(NO_IMPLICIT_NAMES_ALIASES)
        if (selectedNames != null) req.add(selectedNames)
        return req
    }

    /** Split caps into space-joined batches whose payload stays within [limit] bytes. */
    fun batches(caps: Collection<String>, limit: Int = 400): List<String> {
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

val NO_IMPLICIT_NAMES_ALIASES: List<String> = listOf(
    "no-implicit-names",
    "draft/no-implicit-names",
    "soju.im/no-implicit-names",
)

fun preferredNoImplicitNames(caps: Set<String>): String? {
    val names = caps.mapTo(HashSet()) { it.substringBefore('=') }
    return NO_IMPLICIT_NAMES_ALIASES.firstOrNull { it in names }
}
