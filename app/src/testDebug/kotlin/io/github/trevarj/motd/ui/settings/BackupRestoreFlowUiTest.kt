package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.backup.BackupExportMode
import io.github.trevarj.motd.data.backup.BackupImportMode
import io.github.trevarj.motd.data.backup.ConfigurationImportPreview
import io.github.trevarj.motd.data.backup.ConfigurationImportResult
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class BackupRestoreFlowUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun export_modes_reveal_password_and_show_progress_and_result() {
        var mode by mutableStateOf(BackupExportMode.CREDENTIALS_EXCLUDED)
        var state by mutableStateOf(BackupRestoreUiState())
        render(state = { state }, mode = { mode }, onMode = { mode = it })

        compose.onNodeWithTag("backup_export_with_credentials").performClick()
        compose.onNodeWithTag("backup_export_password").assertIsDisplayed()
        compose.onNodeWithTag("backup_export").assertIsNotEnabled()

        compose.runOnIdle {
            state = state.copy(exportPhase = BackupExportPhase.EXPORTING)
        }
        compose.onNodeWithTag("backup_export_progress").assertIsDisplayed()

        compose.runOnIdle {
            state = state.copy(exportPhase = BackupExportPhase.IDLE, exportOutcome = BackupExportOutcome.Success)
        }
        compose.onNodeWithTag("backup_export_result").assertIsDisplayed()
    }

    @Test
    fun replace_preview_requires_confirmation_with_removal_count() {
        var confirmed = false
        val preview = preview(removed = 2)
        render(
            state = {
                BackupRestoreUiState(
                    importPhase = BackupImportPhase.PREVIEW,
                    importMode = BackupImportMode.REPLACE,
                    selectedFilename = "backup.motdconfig",
                    preview = preview,
                    confirmReplace = true,
                )
            },
            onConfirmReplace = { confirmed = true },
        )

        compose.onNodeWithTag("backup_replace_confirm").assertIsDisplayed()
        compose.onNodeWithText("This removes 2 local networks and their local message history. This cannot be undone.").assertIsDisplayed()
        compose.onNodeWithTag("backup_replace_confirm_action").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun import_success_replaces_preview_anchor_and_offers_review_networks_and_dismissal() {
        var reviewed = false
        var dismissed = false
        render(
            state = {
                BackupRestoreUiState(
                    importPhase = BackupImportPhase.COMPLETE,
                    importOutcome =
                        BackupImportOutcome.Success(
                            result = ConfigurationImportResult(1, 2, 0, 3),
                            sourceFilename = "backup.motdconfig",
                            encrypted = true,
                            mode = BackupImportMode.MERGE,
                        ),
                )
            },
            onReviewNetworks = { reviewed = true },
            onDismissImport = { dismissed = true },
        )

        compose.onNodeWithTag("backup_selected_document").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("backup_completed_mode").assertIsDisplayed()
        compose.onNodeWithTag("backup_import_result").assertIsDisplayed()
        compose.onNodeWithText("Review networks").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(reviewed) }
        compose.onNodeWithText("Dismiss").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun preview_localizes_known_and_unknown_setting_group_keys() {
        render(
            state = {
                BackupRestoreUiState(
                    importPhase = BackupImportPhase.PREVIEW,
                    selectedFilename = "backup.motdconfig",
                    preview = preview(removed = 0).copy(settingGroups = listOf("general", "future-key")),
                )
            },
        )

        compose.onNodeWithText("Settings: General, Other settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sensitive_export_password_is_retained_only_for_unfinished_encrypted_export() {
        assertEquals(
            "secret password",
            retainedExportPassword(BackupExportMode.ENCRYPTED_WITH_CREDENTIALS, null, "secret password"),
        )
        assertEquals(
            "",
            retainedExportPassword(BackupExportMode.ENCRYPTED_WITH_CREDENTIALS, BackupExportOutcome.Success, "secret password"),
        )
        assertEquals(
            "",
            retainedExportPassword(BackupExportMode.CREDENTIALS_EXCLUDED, null, "secret password"),
        )
    }

    private fun render(
        state: () -> BackupRestoreUiState,
        mode: () -> BackupExportMode = { BackupExportMode.CREDENTIALS_EXCLUDED },
        onMode: (BackupExportMode) -> Unit = {},
        onConfirmReplace: () -> Unit = {},
        onReviewNetworks: () -> Unit = {},
        onDismissImport: () -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                BackupRestoreContent(
                    state = state(),
                    exportMode = mode(),
                    exportPassword = "",
                    importPassword = "",
                    onBack = {},
                    onExportMode = onMode,
                    onExportPassword = {},
                    onChooseExport = {},
                    onDismissExport = {},
                    onChooseImport = {},
                    onImportPassword = {},
                    onPreview = {},
                    onImportMode = {},
                    onApply = {},
                    onConfirmReplace = onConfirmReplace,
                    onCancelReplace = {},
                    onDismissImport = onDismissImport,
                    onReviewNetworks = onReviewNetworks,
                )
            }
        }
    }

    private fun preview(removed: Int) =
        ConfigurationImportPreview(
            appVersion = "1.0",
            exportedAtEpochMillis = 1_000,
            containsSecrets = false,
            networkCount = 3,
            addedNetworks = 1,
            updatedNetworks = 0,
            removedNetworks = removed,
            retainedLocalCredentials = 0,
            missingCredentialNetworks = 0,
            settingGroups = listOf("general"),
        )
}
