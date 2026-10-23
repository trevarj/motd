package io.github.trevarj.motd.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.FutureTask

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NetworkCertificatePickerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val auth = mutableStateOf(AuthForm(mode = AuthMode.EXTERNAL, certAlias = "saved-cert"))
    private val shown = mutableStateOf(true)
    private val launches = mutableListOf<Intent>()
    private lateinit var activity: Activity
    private lateinit var registry: ActivityResultRegistry
    private var requestCode = 0
    private var launchFailure: RuntimeException? = null
    private var aliasPublishedOnMain = false

    @Test
    fun importCancelKeepsSelectedIdentityWithoutKeyAuthorization() {
        setContent()
        importFile()
        assertEquals("android.credentials.INSTALL", launches.single().action)
        assertTrue(launches.single().extras?.isEmpty != false)
        compose.onNodeWithTag("auth_import_certificate").assertIsNotEnabled()
        compose.onNodeWithTag("auth_use_installed_certificate").assertIsNotEnabled()

        completeInstall(Activity.RESULT_CANCELED)

        compose.runOnIdle {
            assertEquals("saved-cert", auth.value.certAlias)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        compose.onNodeWithTag("auth_import_certificate").assertIsEnabled()
    }

    @Test
    fun successfulImportAuthorizesIdentityAndPreservesLatestFields() {
        setContent()
        compose.onNodeWithTag("server_password_options").performScrollTo().performClick()
        importFile()
        compose.onNodeWithTag("server_password_field").performScrollTo().performTextReplacement("updated while importing")
        completeInstall(Activity.RESULT_OK)
        val canceledChooser = nextAliasChooser()
        completeAlias(canceledChooser, null)
        compose.runOnIdle {
            assertEquals("saved-cert", auth.value.certAlias)
            assertEquals("updated while importing", auth.value.serverPassword)
        }

        useInstalledCertificate()
        val chooser = nextAliasChooser()
        compose.onNodeWithTag("server_password_field").performScrollTo().performTextReplacement("updated while authorizing")
        compose.runOnIdle { assertEquals("updated while authorizing", auth.value.serverPassword) }
        completeAlias(chooser, "selected-cert")

        compose.runOnIdle {
            assertEquals("selected-cert", auth.value.certAlias)
            assertEquals("updated while authorizing", auth.value.serverPassword)
            assertTrue(aliasPublishedOnMain)
        }
    }

    @Test
    fun leavingModeOrEntryDiscardsLateCertificateResults() {
        setContent()
        importFile()
        compose.runOnIdle { auth.value = auth.value.copy(mode = AuthMode.NONE) }
        compose.runOnIdle { auth.value = auth.value.copy(mode = AuthMode.EXTERNAL) }
        completeInstall(Activity.RESULT_OK)
        compose.runOnIdle { assertNull(shadowOf(activity).nextStartedActivity) }

        useInstalledCertificate()
        val oldModeChooser = nextAliasChooser()
        compose.runOnIdle { auth.value = auth.value.copy(mode = AuthMode.NONE) }
        compose.runOnIdle { auth.value = auth.value.copy(mode = AuthMode.EXTERNAL) }
        completeAlias(oldModeChooser, "stale-mode-cert")
        compose.runOnIdle { assertEquals("saved-cert", auth.value.certAlias) }

        useInstalledCertificate()
        val oldEntryChooser = nextAliasChooser()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        completeAlias(oldEntryChooser, "stale-entry-cert")
        compose.runOnIdle { assertEquals("saved-cert", auth.value.certAlias) }
    }

    @Test
    fun missingOrDeniedInstallerShowsSafeActionableError() {
        setContent()
        for (failure in listOf(ActivityNotFoundException("untrusted provider detail"), SecurityException("untrusted provider detail"))) {
            launchFailure = failure
            importFile()
            compose.onNodeWithTag("auth_certificate_error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("untrusted provider detail", substring = true).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("saved-cert", auth.value.certAlias)
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            compose.onNodeWithTag("auth_use_installed_certificate").assertIsEnabled()
        }
    }

    private fun setContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    launchFailure?.let { throw it }
                    this@NetworkCertificatePickerUiTest.requestCode = requestCode
                    launches += contract.createIntent(context, input)
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        compose.setContent {
            activity = checkNotNull(LocalActivity.current)
            val wrappedContext = ContextWrapper(LocalContext.current)
            CompositionLocalProvider(
                LocalContext provides wrappedContext,
                LocalActivityResultRegistryOwner provides owner,
            ) {
                MotdTheme(dynamicColor = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (shown.value) {
                            NetworkForm(
                                server = ServerForm(host = "irc.example", nick = "me"),
                                auth = auth.value,
                                onServerChange = {},
                                onAuthChange = {
                                    if (it.certAlias != auth.value.certAlias) aliasPublishedOnMain = Looper.myLooper() == Looper.getMainLooper()
                                    auth.value = it
                                },
                                showServer = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun importFile() {
        compose.onNodeWithTag("auth_import_certificate").performScrollTo().performClick()
    }

    private fun useInstalledCertificate() {
        compose.onNodeWithTag("auth_use_installed_certificate").performScrollTo().performClick()
    }

    private fun completeInstall(result: Int) {
        compose.runOnIdle { registry.dispatchResult(requestCode, result, null) }
    }

    private fun nextAliasChooser(): Intent {
        var intent: Intent? = null
        compose.runOnIdle { intent = shadowOf(activity).nextStartedActivity }
        return requireNotNull(intent).also { assertEquals("com.android.keychain.CHOOSER", it.action) }
    }

    private fun completeAlias(
        intent: Intent,
        alias: String?,
    ) {
        // Exercise Android's actual Binder callback, without a production injection seam.
        val response = requireNotNull(intent.extras?.getBinder("response"))
        val callback = response.javaClass.getDeclaredMethod("alias", String::class.java).apply { isAccessible = true }
        val result = FutureTask { callback.invoke(response, alias) }
        Thread(result).start()
        result.get()
        compose.waitForIdle()
    }
}
