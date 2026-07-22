package io.github.trevarj.motd.e2e

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.trevarj.motd.MotdApplication
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.di.RequiredE2eEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

data class FixtureArgs(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val nick: String,
    val channel: String,
    val fingerprint: String,
    val runId: String,
) {
    companion object {
        fun read(): FixtureArgs {
            val args = InstrumentationRegistry.getArguments()
            fun required(name: String): String = args.getString(name)?.trim().orEmpty().also {
                require(it.isNotEmpty()) { "missing required instrumentation argument: $name" }
            }
            val port = required("sojuPort").toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: error("invalid sojuPort")
            val fingerprint = required("sojuTlsSha256").lowercase()
            require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "invalid sojuTlsSha256" }
            return FixtureArgs(
                host = required("sojuHost"), port = port, user = required("sojuUser"),
                password = required("sojuPassword"), nick = required("nick"),
                channel = required("channel"), fingerprint = fingerprint,
                runId = required("e2eRunId"),
            )
        }
    }
}

data class BootstrappedNetwork(val rootId: Long, val childId: Long)

class E2eBootstrap private constructor(
    val args: FixtureArgs,
    val seams: RequiredE2eEntryPoint,
) {
    companion object {
        fun fromApplication(context: Context): E2eBootstrap {
            val app = context.applicationContext as MotdApplication
            return E2eBootstrap(
                FixtureArgs.read(),
                EntryPointAccessors.fromApplication(app, RequiredE2eEntryPoint::class.java),
            )
        }
    }

    /**
     * Creates the fixture topology through the production repository, pins the fixture certificate,
     * and starts the real connection manager exactly once. The root and child identity keys make
     * repeated setup idempotent without reaching behind repository boundaries.
     */
    suspend fun connectedSojuNetwork(): BootstrappedNetwork {
        seams.certTrust().pin(args.host, args.port, args.fingerprint)
        val rootId = seams.networks().addNetwork(
            NetworkEntity(
                name = "required-e2e-root",
                role = NetworkRole.BOUNCER_ROOT,
                host = args.host,
                port = args.port,
                nick = args.nick,
                username = args.user,
                realname = "MOTD required E2E",
                saslMechanism = "PLAIN",
                saslUser = args.user,
                saslPassword = args.password,
            ),
        )
        val childId = seams.networks().addNetwork(
            NetworkEntity(
                name = "libera",
                role = NetworkRole.BOUNCER_CHILD,
                parentId = rootId,
                bouncerNetId = "libera",
                host = args.host,
                port = args.port,
                nick = args.nick,
                username = args.user,
                realname = "MOTD required E2E",
                saslMechanism = "PLAIN",
                saslUser = args.user,
                saslPassword = args.password,
            ),
        )
        withTimeout(10_000) {
            seams.networks().observeNetworks().first { rows ->
                rows.count { it.role == NetworkRole.BOUNCER_ROOT } == 1 &&
                    rows.count { it.role == NetworkRole.BOUNCER_CHILD && it.parentId == rootId && it.bouncerNetId == "libera" } == 1
            }
        }
        seams.connections().startAll()
        return BootstrappedNetwork(rootId, childId)
    }
}
