package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Thin pass-through over NetworkDao. Delete treats a bouncer root and its local child mirrors as
// one local tree; a missing row is a no-op. addNetwork additionally dedups against existing rows
// so re-running onboarding / "Add network" for a server the user already has does not create a
// duplicate NetworkEntity (which would spawn a second actor + socket for the same server).
class NetworkRepositoryImpl @Inject constructor(
    private val networkDao: NetworkDao,
    private val bouncerKindPrefs: BouncerKindPrefs = NoopBouncerKindPrefs,
) : NetworkRepository {
    private val addMutex = Mutex()

    override fun observeNetworks(): Flow<List<NetworkEntity>> = networkDao.observeAll()

    /**
     * Insert [n], or return the id of an existing equivalent network instead of creating a
     * duplicate. Two rows are "the same server" when [networkIdentityKey] matches (see there for
     * the per-role key). The dedup is at the data layer so every add path (onboarding, Add
     * network, soju child import) is covered transparently and callers keep the "returns the row
     * id" contract — they just get the pre-existing id on a duplicate.
     */
    override suspend fun addNetwork(n: NetworkEntity): Long = addMutex.withLock {
        val key = networkIdentityKey(n)
        networkDao.allNow().firstOrNull { networkIdentityKey(it) == key }?.let { return it.id }
        networkDao.insert(n)
    }

    override suspend fun updateNetwork(n: NetworkEntity) = networkDao.update(n)

    override suspend fun deleteNetwork(id: Long) {
        networkDao.deleteLocalTree(id).forEach { deletedId ->
            bouncerKindPrefs.clear(deletedId)
        }
    }

    override suspend fun networkById(id: Long): NetworkEntity? = networkDao.byId(id)

    override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = networkDao.childrenOf(rootId)
}

/** Normalize a host for identity comparison: trim, drop a trailing dot, lowercase (DNS is
 *  case-insensitive). Hostnames are ASCII so [lowercase] with the default locale is safe. */
internal fun normalizeHost(host: String): String =
    host.trim().trimEnd('.').lowercase()

/**
 * Stable identity key deciding whether two [NetworkEntity] rows are the same server, used by
 * [NetworkRepositoryImpl.addNetwork] to reject duplicates. Keyed per role:
 *
 * - **BOUNCER_CHILD**: `(parentId, bouncerNetId)` — a child is one bouncer-side network under one
 *   root, regardless of host (the mirror may not know the host yet). Guards both the onboarding
 *   import loop and the notify-mirror racing to insert the same child.
 * - **BOUNCER_ROOT**: `(host, port, saslUser)` — one soju account (login) per host:port. Adding
 *   the same bouncer account twice reuses the existing root.
 * - **DIRECT**: `(host, port, nick, bouncer selector?)` — ordinary direct rows use the first
 *   three; ZNC's slash-bearing SASL authcid or CLoak's PASS `user/network:password` adds the
 *   selected upstream network so one endpoint can hold more than one bouncer network.
 *
 * A `null` sub-key element is kept distinct (encoded as an empty segment) so under-specified rows
 * don't collapse onto each other.
 */
internal fun networkIdentityKey(n: NetworkEntity): String = when (n.role) {
    NetworkRole.BOUNCER_CHILD -> "child|${n.parentId}|${n.bouncerNetId.orEmpty()}"
    NetworkRole.BOUNCER_ROOT ->
        "root|${normalizeHost(n.host)}|${n.port}|${n.saslUser.orEmpty()}"
    NetworkRole.DIRECT -> {
        // ZNC selects an upstream in its SASL authcid; CLoak does so in the non-secret prefix of
        // PASS. Keep only that selector in the key so passwords never become identity material.
        val saslSelector = n.saslUser?.takeIf { '/' in it }
        val passSelector = n.serverPassword?.let(::serverPasswordSelector)
        val bouncerSelector = saslSelector ?: passSelector.orEmpty()
        "direct|${normalizeHost(n.host)}|${n.port}|${n.nick}|$bouncerSelector"
    }
}

/** Extract CLoak's `user/network` selector without retaining its trailing password. */
private fun serverPasswordSelector(password: String): String? {
    val slash = password.indexOf('/')
    val colon = password.lastIndexOf(':')
    return password.substring(0, colon).takeIf { slash >= 0 && colon > slash }
}
