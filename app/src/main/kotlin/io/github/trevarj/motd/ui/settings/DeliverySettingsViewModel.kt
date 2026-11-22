package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.push.PushDistributorController
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.MotdNotifications
import io.github.trevarj.motd.service.TestAlertCategory
import io.github.trevarj.motd.service.TestAlertResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeliverySettingsUiState(
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
    val pushAvailability: PushAvailability = PushAvailability(),
)

@HiltViewModel
class DeliverySettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val pushAvailability: PushAvailabilityProvider,
        private val pushDistributorController: PushDistributorController,
        private val notifications: MotdNotifications,
    ) : ViewModel() {
        val state =
            combine(settingsRepository.settings, pushAvailability.availability()) { settings, availability ->
                DeliverySettingsUiState(settings.deliveryMode, availability)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeliverySettingsUiState())

        fun setDeliveryMode(value: DeliveryMode) = viewModelScope.launch { settingsRepository.setDeliveryMode(value) }

        fun selectPushDistributor(packageName: String) = viewModelScope.launch { runCatching { pushDistributorController.select(packageName) } }

        fun retryPushSetup() = viewModelScope.launch { runCatching { pushDistributorController.retry() } }

        fun refreshNotificationPermission() = pushAvailability.refreshNotificationPermission()

        internal fun postTestAlert(category: TestAlertCategory): TestAlertResult = notifications.postTestAlert(category)
    }
