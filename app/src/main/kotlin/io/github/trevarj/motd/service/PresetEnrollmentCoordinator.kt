package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefs
import io.github.trevarj.motd.data.repo.NetworkRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val LIBERA_HOST = "irc.libera.chat"
private const val LIBERA_TLS_PORT = 6697
internal const val MOTD_CHANNEL = "#motd"

/** Turns a persisted preset enrollment into one IRC JOIN on the network's first Ready state. */
@Singleton
class PresetEnrollmentCoordinator @Inject constructor(
    private val prefs: PresetEnrollmentPrefs,
    private val networks: NetworkRepository,
) {
    suspend fun onReady(
        networkId: Long,
        isCurrent: () -> Boolean,
        join: suspend (String) -> Unit,
    ): EnrollmentJoinResult {
        if (!isCurrent() || !prefs.claimLiberaMotdJoin(networkId)) return EnrollmentJoinResult.NOT_ELIGIBLE

        // Consume first, then validate the durable row. A changed/deleted row must not regain the
        // one-shot later, and a crash between the claim and JOIN must never duplicate the JOIN.
        val network = networks.networkById(networkId)
        if (!network.isDirectLiberaEndpoint() || !isCurrent()) return EnrollmentJoinResult.SKIPPED

        return try {
            join(MOTD_CHANNEL)
            EnrollmentJoinResult.SENT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable attempt stays consumed. Server-side JOIN failures continue through the
            // ordinary IRC event path; transport write failures are intentionally not retried.
            EnrollmentJoinResult.FAILED
        }
    }
}

enum class EnrollmentJoinResult { NOT_ELIGIBLE, SKIPPED, SENT, FAILED }

internal fun NetworkEntity?.isDirectLiberaEndpoint(): Boolean =
    this?.role == NetworkRole.DIRECT &&
        host.trim().trimEnd('.').equals(LIBERA_HOST, ignoreCase = true) &&
        port == LIBERA_TLS_PORT && tls

internal fun liberaEndpointChanged(before: NetworkEntity, after: NetworkEntity): Boolean =
    before.host.trim().trimEnd('.').lowercase() != after.host.trim().trimEnd('.').lowercase() ||
        before.port != after.port || before.tls != after.tls
