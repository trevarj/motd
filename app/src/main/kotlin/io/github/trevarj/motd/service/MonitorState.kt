package io.github.trevarj.motd.service

internal data class MonitorReconciliation(
    val clear: Boolean,
    val remove: List<String>,
    val add: List<String>,
    val status: Boolean,
)

internal fun monitorReconciliation(
    previous: Map<String, String>,
    desired: Map<String, String>,
    fresh: Boolean,
): MonitorReconciliation {
    val baseline = if (fresh) emptyMap() else previous
    return MonitorReconciliation(
        clear = fresh,
        remove = baseline.filterKeys { it !in desired }.values.toList(),
        add = desired.filterKeys { it !in baseline }.values.toList(),
        status = fresh,
    )
}

internal fun presenceForDesired(
    current: Map<PresenceKey, PresenceState>,
    networkId: Long,
    desiredNormalized: Set<String>,
): Map<PresenceKey, PresenceState> {
    val keys = desiredNormalized.mapTo(HashSet()) { PresenceKey(networkId, it) }
    return current.filterKeys { it.networkId != networkId || it in keys } +
        keys.associateWith { current[it] ?: PresenceState.UNKNOWN }
}

internal fun presenceIfTracked(
    current: Map<PresenceKey, PresenceState>,
    key: PresenceKey,
    state: PresenceState,
): Map<PresenceKey, PresenceState> = if (key in current) current + (key to state) else current

internal fun rekeyPresenceState(
    current: Map<PresenceKey, PresenceState>,
    oldKey: PresenceKey,
    newKey: PresenceKey,
): Map<PresenceKey, PresenceState> {
    val state = current[oldKey] ?: return current
    return (current - oldKey) + (newKey to state)
}

internal fun invalidatePresenceState(
    current: Map<PresenceKey, PresenceState>,
    networkId: Long,
): Map<PresenceKey, PresenceState> = current.mapValues { (key, state) ->
    if (key.networkId == networkId) PresenceState.UNKNOWN else state
}
