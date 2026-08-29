package io.github.trevarj.motd.ui.chatlist

import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.sidecar.SidecarBinder
import io.github.trevarj.motd.sidecar.SidecarContract
import io.github.trevarj.motd.sidecar.SidecarJson
import io.github.trevarj.motd.sidecar.SidecarProviderFeature
import io.github.trevarj.motd.sidecar.SidecarUiRequest
import io.github.trevarj.motd.sidecar.requireSidecarProvider
import javax.inject.Inject

@HiltViewModel
class SidecarTargetViewModel
    @Inject
    constructor(
        private val networks: NetworkRepository,
        private val binder: SidecarBinder,
        private val db: MotdDatabase,
        private val connections: ConnectionManager,
    ) : ViewModel() {
        suspend fun openPerson(
            networkId: Long,
            wireTarget: String,
            displayName: String,
        ): Long {
            val roomId = connections.ensureQueryBuffer(networkId, wireTarget)
            db.bufferDao().updateSidecarDisplayName(roomId, wireTarget, displayName)
            return roomId
        }

        suspend fun createIntent(networkId: Long): Intent? {
            val network = networks.networkById(networkId) ?: return null
            if (network.connectionTransport != ConnectionTransport.SIDECAR) return null
            val component = ComponentName(network.sidecarPackage ?: return null, network.sidecarService ?: return null)
            val accountId = network.sidecarAccountId ?: return null
            return binder.bindTrusted(component).use { binding ->
                val info = SidecarJson.decodeProvider(binding.provider.providerInfoJson)
                if (SidecarProviderFeature.TARGET_PICKER !in info.features) return@use null
                binding.provider
                    .createUiIntent(
                        SidecarContract.ACTION_PICK_TARGET,
                        accountId,
                        SidecarJson.encodeUiRequest(SidecarUiRequest()),
                    ).requireSidecarProvider(component)
            }
        }
    }
