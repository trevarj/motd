package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.ConnectionManagerImpl
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.IrcForegroundService
import io.github.trevarj.motd.ui.nav.MotdNavGraph
import io.github.trevarj.motd.ui.theme.MotdTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var db: MotdDatabase

    // POST_NOTIFICATIONS runtime permission (API 33+); result is advisory, no action needed.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch/splash theme for the app theme before drawing Compose content.
        setTheme(R.style.Theme_Motd)
        super.onCreate(savedInstanceState)

        requestPostNotificationsIfNeeded()
        maybeStartForegroundService()

        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = Settings())
            MotdTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                MotdNavGraph()
            }
        }
    }

    /** Ask for POST_NOTIFICATIONS at first launch on API 33+ (needed for message/status notifs). */
    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Start the persistent-socket foreground service when delivery mode is PERSISTENT_SOCKET and
     * at least one auto-connect network exists (plans/05). Under UNIFIED_PUSH the socket stays
     * down and pushes drive delivery, so we skip it.
     */
    private fun maybeStartForegroundService() {
        lifecycleScope.launch {
            val persistent =
                settingsRepository.settings.first().deliveryMode == DeliveryMode.PERSISTENT_SOCKET
            val hasNetworks = db.networkDao().connectable().isNotEmpty()
            if (ConnectionManagerImpl.shouldRunService(persistent, hasNetworks)) {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, IrcForegroundService::class.java),
                )
            }
        }
    }
}
