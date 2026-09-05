package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class DeliverySettingsNotificationPermissionUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun persistent_socket_shows_notification_remediation_without_push_status() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                DeliverySettingsContent(
                    deliveryMode = DeliveryMode.PERSISTENT_SOCKET,
                    pushAvailability = PushAvailability(notificationsGranted = false),
                    onBack = {},
                    onDeliveryMode = {},
                )
            }
        }

        compose.onNodeWithTag("settings_notification_permission_warning").assertIsDisplayed()
        compose.onNodeWithTag("settings_fix_notifications").assertIsDisplayed()
        compose
            .onNodeWithText("Synchronization may continue while message and status alerts are blocked. Enable notifications in Android settings to receive alerts.")
            .assertIsDisplayed()
        compose.onAllNodesWithTag("settings_push_status_card").assertCountEquals(0)
    }
}
