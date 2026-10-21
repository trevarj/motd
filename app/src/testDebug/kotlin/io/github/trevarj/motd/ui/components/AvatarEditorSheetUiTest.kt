package io.github.trevarj.motd.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AvatarEditorSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun importedImageIsStagedAndCancelOrTargetChangeCannotCommitIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File.createTempFile("avatar-proposal", ".png", context.cacheDir)
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val uri = Uri.fromFile(file)
        val registry =
            object : ActivityResultRegistry() {
                var code = -1

                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    code = requestCode
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        val identity = mutableStateOf("first-person")
        val open = mutableStateOf(true)
        val saved = mutableStateOf<String?>(null)
        val imports = mutableListOf<Uri>()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MotdTheme {
                        AvatarEditorSheet(
                            open = open.value,
                            currentModel = saved.value,
                            isChannel = false,
                            identity = identity.value,
                            name = "Alex",
                            networkId = 7,
                            onDismiss = { open.value = false },
                            onImport = {
                                imports += it
                                saved.value = it.toString()
                            },
                            onUpload = {},
                            onUrl = {},
                            onReset = {},
                        )
                    }
                }
            }

            fun chooseImage(result: Uri = uri) {
                compose.onNodeWithTag("avatar_editor_import").performScrollTo().performClick()
                compose.runOnIdle { registry.dispatchResult(registry.code, Activity.RESULT_OK, Intent().setData(result)) }
            }
            chooseImage()
            compose.onNodeWithTag("avatar_local_proposal").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(emptyList<Uri>(), imports)
                assertEquals(null, saved.value)
            }
            compose.onNodeWithTag("avatar_editor_cancel").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(null, saved.value)
                open.value = true
            }
            compose.onNodeWithTag("avatar_local_proposal").assertDoesNotExist()

            chooseImage()
            compose.runOnIdle { identity.value = "second-person" }
            compose.onNodeWithTag("avatar_local_proposal").assertDoesNotExist()
            compose.onNodeWithTag("avatar_editor_import").performScrollTo().performClick()
            compose.runOnIdle {
                identity.value = "third-person"
            }
            compose.runOnIdle { registry.dispatchResult(registry.code, Activity.RESULT_OK, Intent().setData(uri)) }
            compose.onNodeWithTag("avatar_local_proposal").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(emptyList<Uri>(), imports)
                assertEquals(null, saved.value)
            }

            chooseImage(Uri.parse("https://avatar.invalid/not-local.png"))
            compose.onNodeWithTag("avatar_editor_import_error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("avatar_local_proposal").assertDoesNotExist()
            compose.onNodeWithTag("avatar_editor_use_image").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(emptyList<Uri>(), imports)
                assertEquals(null, saved.value)
            }

            chooseImage()
            compose.onNodeWithTag("avatar_editor_use_image").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(listOf(uri), imports)
                assertEquals(uri.toString(), saved.value)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun validatesUrl_resetsLocal_andConfirmsSharedClear() {
        var resets = 0
        var clears = 0
        var savedUrl: String? = null
        compose.setContent {
            MotdTheme {
                AvatarEditorSheet(
                    open = true,
                    currentModel = null,
                    isChannel = true,
                    identity = "example-channel",
                    name = "#garden",
                    networkId = null,
                    onDismiss = {},
                    onImport = {},
                    onUpload = {},
                    onUrl = { savedUrl = it },
                    onReset = { resets += 1 },
                    onClearShared = { clears += 1 },
                )
            }
        }

        compose.onNodeWithTag("avatar_editor_url").performScrollTo().performTextInput("http://avatar.invalid/proposed.png")
        compose.onNodeWithTag("avatar_editor_url_save").performScrollTo().performClick()
        compose.onNodeWithText("Enter valid HTTPS URL.").assertIsDisplayed()
        compose.onNodeWithTag("avatar_editor_url").performTextReplacement("https://avatar.invalid/proposed.png")
        compose.onNodeWithTag("avatar_editor_url_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://avatar.invalid/proposed.png", savedUrl) }

        compose.onNodeWithTag("avatar_editor_reset").performScrollTo().performClick()
        compose.onNodeWithTag("avatar_editor_clear_shared").performScrollTo().performClick()
        compose.onNodeWithTag("avatar_editor_clear_dialog").assertIsDisplayed()
        compose.onNodeWithTag("avatar_editor_clear_confirm").performClick()

        compose.runOnIdle {
            assertEquals(1, resets)
            assertEquals(1, clears)
        }
    }
}
