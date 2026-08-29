package io.github.trevarj.motd.ui.settings.sidecar

import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.sidecar.SidecarAccount
import io.github.trevarj.motd.sidecar.SidecarBinder
import io.github.trevarj.motd.sidecar.SidecarContract
import io.github.trevarj.motd.sidecar.SidecarDiscovery
import io.github.trevarj.motd.sidecar.SidecarJson
import io.github.trevarj.motd.sidecar.SidecarProviderDescriptor
import io.github.trevarj.motd.sidecar.SidecarTrustStore
import io.github.trevarj.motd.sidecar.SidecarWakeRegistrar
import io.github.trevarj.motd.sidecar.requireSidecarProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SidecarProviderRow(
    val descriptor: SidecarProviderDescriptor,
    val paired: Boolean,
    val accounts: List<SidecarAccount> = emptyList(),
)

data class SidecarProvidersUiState(
    val providers: List<SidecarProviderRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class SidecarUiLaunch(
    val component: ComponentName,
    val intent: Intent,
)

@HiltViewModel
class SidecarProvidersViewModel
    @Inject
    constructor(
        private val discovery: SidecarDiscovery,
        private val binder: SidecarBinder,
        private val trust: SidecarTrustStore,
        private val networks: NetworkRepository,
        private val connections: ConnectionManager,
        private val wakeRegistrar: SidecarWakeRegistrar,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SidecarProvidersUiState())
        val state: StateFlow<SidecarProvidersUiState> = _state.asStateFlow()

        private val _launches = MutableSharedFlow<SidecarUiLaunch>(extraBufferCapacity = 1)
        val launches: SharedFlow<SidecarUiLaunch> = _launches.asSharedFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _state.value = _state.value.copy(loading = true, error = null)
                val rows =
                    discovery.providers().map { descriptor ->
                        val paired = trust.pinnedSigner(descriptor.component) in descriptor.signerHistorySha256
                        SidecarProviderRow(
                            descriptor = descriptor,
                            paired = paired,
                            accounts = if (paired) loadAccounts(descriptor.component) else emptyList(),
                        )
                    }
                _state.value = SidecarProvidersUiState(rows, loading = false)
            }
        }

        fun pair(component: ComponentName) {
            viewModelScope.launch {
                runCatching {
                    binder.bindForPairing(component).use { binding ->
                        binding.provider
                            .createUiIntent(SidecarContract.ACTION_PAIR, "", "{}")
                            .requireSidecarProvider(component)
                    }
                }.onSuccess { intent ->
                    _launches.emit(SidecarUiLaunch(component, intent))
                }.onFailure(::showError)
            }
        }

        fun pairingFinished(
            component: ComponentName,
            approved: Boolean,
        ) {
            if (!approved) return
            viewModelScope.launch {
                runCatching {
                    val descriptor = requireNotNull(discovery.descriptor(component))
                    trust.trust(component, descriptor.currentSignerSha256)
                    // Prove provider independently recorded caller approval before showing accounts.
                    loadAccounts(component)
                }.onSuccess { refresh() }.onFailure {
                    trust.revoke(component)
                    showError(it)
                }
            }
        }

        fun addAccount(
            component: ComponentName,
            account: SidecarAccount,
            onAdded: (Long) -> Unit,
        ) {
            viewModelScope.launch {
                runCatching {
                    val descriptor = requireNotNull(discovery.descriptor(component))
                    val entity =
                        NetworkEntity(
                            name = account.displayName,
                            role = NetworkRole.DIRECT,
                            host = "sidecar",
                            port = 0,
                            tls = false,
                            nick = "motd",
                            username = "motd",
                            realname = "motd",
                            autoConnect = true,
                            connectionTransport = ConnectionTransport.SIDECAR,
                            sidecarPackage = descriptor.component.packageName,
                            sidecarService = descriptor.component.className,
                            sidecarAccountId = account.accountId,
                        )
                    networks.addNetwork(entity)
                }.onSuccess { networkId ->
                    networks.networkById(networkId)?.let { existing ->
                        networks.updateNetwork(
                            existing.copy(
                                autoConnect = true,
                                pendingCredentialRequirements = null,
                                restoreAutoConnect = false,
                            ),
                        )
                    }
                    connections.connect(networkId)
                    onAdded(networkId)
                }.onFailure(::showError)
            }
        }

        fun unpair(component: ComponentName) {
            viewModelScope.launch {
                wakeRegistrar.clearProvider(component)
                networks
                    .observeNetworks()
                    .first()
                    .filter {
                        it.sidecarPackage == component.packageName &&
                            it.sidecarService == component.className
                    }.forEach { network ->
                        connections.disconnect(network.id)
                        networks.updateNetwork(
                            network.copy(
                                autoConnect = false,
                                pendingCredentialRequirements = "sidecarPairing",
                                restoreAutoConnect = true,
                            ),
                        )
                    }
                trust.revoke(component)
                refresh()
            }
        }

        private suspend fun loadAccounts(component: ComponentName): List<SidecarAccount> =
            binder.bindTrusted(component).use { binding ->
                binding.provider.accountsJson
                    .take(SidecarContract.MAX_ACCOUNTS)
                    .map(SidecarJson::decodeAccount)
            }

        private fun showError(failure: Throwable) {
            _state.value = _state.value.copy(loading = false, error = failure.message ?: "Companion provider failed")
        }
    }
