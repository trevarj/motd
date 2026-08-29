package io.github.trevarj.motd.sidecar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.sidecar.IMotdSidecarProvider
import io.github.trevarj.motd.sidecar.SidecarContract
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun Intent.requireSidecarProvider(component: ComponentName): Intent {
    require(this.component?.packageName == component.packageName) {
        "Companion provider returned an activity outside its package"
    }
    return this
}

class SidecarBinding internal constructor(
    val provider: IMotdSidecarProvider,
    private val context: Context,
    private val connection: ServiceConnection,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { context.unbindService(connection) }
    }
}

@Singleton
class SidecarBinder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val discovery: SidecarDiscovery,
        private val trust: SidecarTrustStore,
    ) {
        suspend fun bindTrusted(component: ComponentName): SidecarBinding {
            val descriptor = discovery.descriptor(component) ?: error("Companion provider is unavailable")
            val pinned = trust.pinnedSigner(component) ?: error("Companion provider is not paired")
            require(pinned in descriptor.signerHistorySha256) { "Companion provider signing identity changed" }
            return bind(component).also { binding ->
                try {
                    require(binding.provider.apiVersion == SidecarContract.API_VERSION) { "Unsupported companion API" }
                    if (pinned != descriptor.currentSignerSha256) trust.trust(component, descriptor.currentSignerSha256)
                } catch (failure: Throwable) {
                    binding.close()
                    throw failure
                }
            }
        }

        suspend fun bindForPairing(component: ComponentName): SidecarBinding {
            val descriptor = discovery.descriptor(component) ?: error("Companion provider is unavailable")
            require(descriptor.apiVersion == SidecarContract.API_VERSION) { "Unsupported companion API" }
            return bind(component)
        }

        private suspend fun bind(component: ComponentName): SidecarBinding =
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                lateinit var connection: ServiceConnection
                connection =
                    object : ServiceConnection {
                        override fun onServiceConnected(
                            name: ComponentName,
                            binder: IBinder,
                        ) {
                            val provider = IMotdSidecarProvider.Stub.asInterface(binder)
                            if (completed.compareAndSet(false, true)) {
                                continuation.resume(SidecarBinding(provider, context, connection))
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName) = Unit

                        override fun onNullBinding(name: ComponentName) {
                            fail("Companion provider returned no Binder")
                        }

                        override fun onBindingDied(name: ComponentName) {
                            fail("Companion provider binding died")
                        }

                        private fun fail(message: String) {
                            if (completed.compareAndSet(false, true)) continuation.resumeWithException(IllegalStateException(message))
                        }
                    }
                val bound =
                    context.bindService(
                        Intent(SidecarContract.PROVIDER_ACTION).setComponent(component),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                if (!bound && completed.compareAndSet(false, true)) {
                    continuation.resumeWithException(IllegalStateException("Unable to bind companion provider"))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    if (completed.compareAndSet(false, true)) runCatching { context.unbindService(connection) }
                }
            }
    }
