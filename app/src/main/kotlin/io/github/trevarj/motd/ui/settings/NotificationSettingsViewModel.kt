package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NotificationChannelRow
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.service.NotificationSettings
import io.github.trevarj.motd.service.NotificationSettingsState
import io.github.trevarj.motd.ui.components.ChannelNotificationPresentation
import io.github.trevarj.motd.ui.components.deriveChannelNotificationPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationSettingsUiState(
    val loading: Boolean = true,
    /** Show "Notification settings could not be loaded. Message alerts are off until retry succeeds." with Retry. */
    val unavailable: Boolean = false,
    val global: NotificationMode = NotificationMode.MENTIONS,
    val networks: List<NetworkNotificationUi> = emptyList(),
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
    /** The screen presents "Could not save notification settings." until dismissed. */
    val saveError: Boolean = false,
)

data class NetworkNotificationUi(
    val networkId: Long,
    val name: String,
    val serverOverride: NotificationMode?,
    val effectiveMode: NotificationMode,
    val channelOverrideCount: Int,
    val activeWatchCount: Int,
    val channels: List<ChannelNotificationUi>,
)

data class ChannelNotificationUi(
    val bufferId: Long,
    val networkId: Long,
    val name: String,
    val joined: Boolean,
    val archived: Boolean,
    val notification: ChannelNotificationPresentation,
)

@HiltViewModel
class NotificationSettingsViewModel
    @Inject
    constructor(
        private val notificationSettings: NotificationSettings,
        networkRepository: NetworkRepository,
        bufferRepository: BufferRepository,
        settingsRepository: SettingsRepository,
        private val clock: AppClock,
    ) : ViewModel() {
        private val saveError = MutableStateFlow(false)
        private val ticks =
            flow {
                while (true) {
                    emit(Unit)
                    delay(30_000)
                }
            }

        val state: StateFlow<NotificationSettingsUiState> =
            combine(
                combine(
                    notificationSettings.state,
                    networkRepository.observeNetworks(),
                    bufferRepository.observeNotificationChannels(),
                    settingsRepository.settings.map { it.deliveryMode }.distinctUntilChanged(),
                    ticks,
                ) { settings, networks, channels, deliveryMode, _ ->
                    val config = (settings as? NotificationSettingsState.Ready)?.config
                    if (config == null) {
                        NotificationSettingsUiState(
                            loading = settings == NotificationSettingsState.Loading,
                            unavailable = settings == NotificationSettingsState.Unavailable,
                            global = NotificationMode.OFF,
                            deliveryMode = deliveryMode,
                        )
                    } else {
                        val now = clock.nowMillis()
                        val channelsByNetwork = channels.groupBy(NotificationChannelRow::networkId)
                        NotificationSettingsUiState(
                            loading = false,
                            global = config.global,
                            networks =
                                networks.map { network ->
                                    val serverOverride = config.servers[network.id]
                                    val parentMode = serverOverride ?: config.global
                                    val channelRows =
                                        channelsByNetwork[network.id].orEmpty().map { channel ->
                                            ChannelNotificationUi(
                                                bufferId = channel.bufferId,
                                                networkId = channel.networkId,
                                                name = channel.displayName,
                                                joined = channel.joined,
                                                archived = channel.archived,
                                                notification =
                                                    deriveChannelNotificationPresentation(
                                                        settingsState = settings,
                                                        networkId = channel.networkId,
                                                        bufferId = channel.bufferId,
                                                        muted = channel.muted,
                                                        nowMillis = now,
                                                    ),
                                            )
                                        }
                                    NetworkNotificationUi(
                                        networkId = network.id,
                                        name = network.name,
                                        serverOverride = serverOverride,
                                        effectiveMode = parentMode,
                                        channelOverrideCount = channelRows.count { it.notification.channelOverride != null },
                                        activeWatchCount = channelRows.count { it.notification.watch != null },
                                        channels = channelRows,
                                    )
                                },
                            deliveryMode = deliveryMode,
                        )
                    }
                },
                saveError,
            ) { base, error -> base.copy(saveError = error) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsUiState())

        fun setGlobal(mode: NotificationMode) = save { notificationSettings.setGlobal(mode) }

        fun setServer(
            networkId: Long,
            mode: NotificationMode?,
        ) = save { notificationSettings.setServer(networkId, mode) }

        fun setChannel(
            bufferId: Long,
            mode: NotificationMode?,
        ) = save { notificationSettings.setChannel(bufferId, mode) }

        fun startWatch(
            bufferId: Long,
            durationMs: Long?,
        ) = save { notificationSettings.startWatch(bufferId, durationMs) }

        fun stopWatch(bufferId: Long) = save { notificationSettings.stopWatch(bufferId) }

        fun retryLoad() = save { notificationSettings.retryLoad() }

        fun dismissError() {
            saveError.value = false
        }

        private fun save(operation: suspend () -> Boolean) =
            viewModelScope.launch {
                val saved =
                    try {
                        operation()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                if (!saved) saveError.value = true
            }
    }
