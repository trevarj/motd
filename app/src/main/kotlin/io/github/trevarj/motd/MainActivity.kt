package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.ConnectionManagerImpl
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.IrcForegroundService
import io.github.trevarj.motd.ui.components.CertPromptViewModel
import io.github.trevarj.motd.ui.components.CertTrustDialog
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

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch/splash theme for the app theme before drawing Compose content.
        setTheme(R.style.Theme_Motd)
        super.onCreate(savedInstanceState)

        requestPostNotificationsIfNeeded()
        maybeStartForegroundService()

        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = Settings())
            MotdTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                layoutDensity = settings.layoutDensity,
                nickColorsEnabled = settings.nickColorsEnabled,
                nickColorPalette = settings.nickColorPalette,
                nickColorOverrides = settings.nickColorOverrides,
            ) {
                // Root Surface paints the themed background under every screen (incl. non-Scaffold
                // ones like onboarding) so the window follows the color scheme instead of white.
                // testTagsAsResourceId is enabled once here so every Compose testTag in the tree
                // surfaces as a uiautomator resource-id for the e2e harness (plans/18 §4).
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MotdNavGraph()
                    // Global TOFU cert-trust dialog host: shows above the whole nav graph so it works
                    // for onboarding connect-tests, chat-list reconnects, etc. (plans/12).
                    CertTrustDialogHost()
                }
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

/** Collects the ConnectionManager's cert prompts and shows the dialog for the first pending one. */
@Composable
private fun CertTrustDialogHost(viewModel: CertPromptViewModel = hiltViewModel()) {
    val prompts by viewModel.certPrompts.collectAsState()
    val prompt = prompts.firstOrNull() ?: return
    CertTrustDialog(
        prompt = prompt,
        onTrust = { viewModel.trust(prompt) },
        onCancel = { viewModel.dismiss(prompt) },
    )
}
