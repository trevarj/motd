package io.github.trevarj.motd.ui.chat

import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.sidecar.SidecarBinder
import io.github.trevarj.motd.sidecar.SidecarContract
import io.github.trevarj.motd.sidecar.SidecarJson
import io.github.trevarj.motd.sidecar.SidecarProviderFeature
import io.github.trevarj.motd.sidecar.SidecarUiRequest
import io.github.trevarj.motd.sidecar.requireSidecarProvider
import javax.inject.Inject

@HiltViewModel
class SidecarAttachmentViewModel
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val binder: SidecarBinder,
    ) : ViewModel() {
        suspend fun createSecurityIntent(bufferId: Long): Intent? {
            val room = db.bufferDao().observeById(bufferId) ?: return null
            val network = db.networkDao().byId(room.networkId) ?: return null
            if (network.connectionTransport != ConnectionTransport.SIDECAR) return null
            val component = ComponentName(network.sidecarPackage ?: return null, network.sidecarService ?: return null)
            val accountId = network.sidecarAccountId ?: return null
            return binder.bindTrusted(component).use { binding ->
                val info = SidecarJson.decodeProvider(binding.provider.providerInfoJson)
                if (SidecarProviderFeature.MANAGE_SECURITY !in info.features) return@use null
                binding.provider
                    .createUiIntent(
                        SidecarContract.ACTION_MANAGE_SECURITY,
                        accountId,
                        SidecarJson.encodeUiRequest(
                            SidecarUiRequest(wireTarget = room.ircTarget, displayName = room.displayName),
                        ),
                    ).requireSidecarProvider(component)
            }
        }

        suspend fun createIntent(
            bufferId: Long,
            mimeType: String?,
            fileName: String,
            caption: String?,
        ): Intent? {
            val room = db.bufferDao().observeById(bufferId) ?: return null
            val network = db.networkDao().byId(room.networkId) ?: return null
            if (network.connectionTransport != ConnectionTransport.SIDECAR) return null
            val component = ComponentName(network.sidecarPackage ?: return null, network.sidecarService ?: return null)
            val accountId = network.sidecarAccountId ?: return null
            return binder.bindTrusted(component).use { binding ->
                val info = SidecarJson.decodeProvider(binding.provider.providerInfoJson)
                if (SidecarProviderFeature.ATTACHMENTS !in info.features) return@use null
                binding.provider
                    .createUiIntent(
                        SidecarContract.ACTION_SEND_ATTACHMENT,
                        accountId,
                        SidecarJson.encodeUiRequest(
                            SidecarUiRequest(
                                wireTarget = room.ircTarget,
                                displayName = room.displayName,
                                mimeType = mimeType,
                                fileName = fileName,
                                caption = caption,
                            ),
                        ),
                    ).requireSidecarProvider(component)
            }
        }
    }
