package io.github.trevarj.motd.ui.settings

import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.TestAlertCategory
import io.github.trevarj.motd.service.TestAlertResult
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
        setContent(notificationsGranted = false) { error("Unexpected test alert") }

        compose.onNodeWithTag("settings_notification_permission_warning").assertIsDisplayed()
        compose.onNodeWithTag("settings_fix_notifications").assertIsDisplayed()
        compose
            .onNodeWithText("Synchronization may continue while message and status alerts are blocked. Enable notifications in Android settings to receive alerts.")
            .assertIsDisplayed()
        compose.onAllNodesWithTag("settings_push_status_card").assertCountEquals(0)
        compose.onNodeWithTag("settings_test_alert").performScrollTo().assertIsDisplayed()
    }

    @Test fun test_alert_waits_for_category_then_opens_blocked_mention_category_settings() {
        val posted = mutableListOf<TestAlertCategory>()
        setContent { category ->
            posted += category
            TestAlertResult.CATEGORY_BLOCKED
        }

        openTestAlert()
        compose
            .onNodeWithText("Post a local Android notification on this device. This does not check IRC, push delivery, or sound.")
            .assertIsDisplayed()
        compose.onNodeWithTag("settings_test_alert_messages").assertTextEquals("Messages").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<TestAlertCategory>(), posted) }
        compose.onNodeWithTag("settings_test_alert_mentions").assertTextEquals("Mentions").performClick()

        compose
            .onNodeWithTag("settings_test_alert_result")
            .assertTextEquals("Test alert not sent. The Mentions notification category is blocked in Android settings.")
        compose.onNodeWithTag("settings_test_alert_open_settings").performClick()
        compose.runOnIdle {
            assertEquals(listOf(TestAlertCategory.MENTIONS), posted)
            val app = RuntimeEnvironment.getApplication()
            val intent = requireNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(TestAlertCategory.MENTIONS.channelId, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        }
    }

    @Test fun test_alert_app_block_opens_app_notification_settings_even_when_snapshot_granted() {
        setContent { TestAlertResult.APP_BLOCKED }

        openTestAlert()
        compose.onNodeWithTag("settings_test_alert_messages").performClick()
        compose
            .onNodeWithTag("settings_test_alert_result")
            .assertTextEquals("Test alert not sent. Notifications for this app are blocked in Android settings.")
        compose.onNodeWithTag("settings_test_alert_open_settings").performClick()
        compose.runOnIdle {
            val app = RuntimeEnvironment.getApplication()
            val intent = requireNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertNull(intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        }
    }

    @Test fun test_alert_reports_unavailable_and_does_not_promise_display_after_submission() {
        setContent { category ->
            when (category) {
                TestAlertCategory.MESSAGES -> TestAlertResult.UNAVAILABLE
                TestAlertCategory.MENTIONS -> TestAlertResult.SENT
            }
        }

        openTestAlert()
        compose.onNodeWithTag("settings_test_alert_messages").performClick()
        compose
            .onNodeWithTag("settings_test_alert_result")
            .assertTextEquals("Test alert unavailable. Android could not post it, or notification access changed. Try again.")
        compose.onNodeWithTag("settings_test_alert_open_settings").assertDoesNotExist()
        compose.onNodeWithTag("settings_test_alert_dismiss").performClick()

        openTestAlert()
        compose.onNodeWithTag("settings_test_alert_mentions").performClick()
        compose
            .onNodeWithTag("settings_test_alert_result")
            .assertTextEquals("Test alert sent to Android. Check the notification shade; Android may not display it.")
        compose.onNodeWithTag("settings_test_alert_open_settings").assertDoesNotExist()
    }

    private fun openTestAlert() {
        compose.onNodeWithTag("settings_test_alert").performScrollTo().performClick()
    }

    private fun setContent(
        notificationsGranted: Boolean = true,
        onPostTestAlert: (TestAlertCategory) -> TestAlertResult,
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                DeliverySettingsContent(
                    deliveryMode = DeliveryMode.PERSISTENT_SOCKET,
                    pushAvailability = PushAvailability(notificationsGranted = notificationsGranted),
                    onBack = {},
                    onDeliveryMode = {},
                    onPostTestAlert = onPostTestAlert,
                )
            }
        }
    }
}
