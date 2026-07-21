package io.github.trevarj.motd.ui.about

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdTheme

/** About screen: brand lockup, version, support diagnostics, and project links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit = {},
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val createDiagnosticDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let(viewModel::export) }
    AboutContent(
        state = state,
        onBack = onBack,
        onDiagnosticLoggingChanged = viewModel::setDiagnosticLoggingEnabled,
        onExportDiagnostics = {
            createDiagnosticDocument.launch("motd-diagnostics-${System.currentTimeMillis()}.txt")
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutContent(
    state: AboutDiagnosticsUiState,
    onBack: () -> Unit,
    onDiagnosticLoggingChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val licenseUrl = "https://github.com/trevarj/motd/blob/main/LICENSE"
    val githubUrl = stringResource(R.string.settings_github_url)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Image(
                    painter = painterResource(R.drawable.motd_logo_lockup),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(vertical = 24.dp),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = aboutBuildLabel(appVersion(context), BuildConfig.MOTD_SOURCE_COMMIT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.about_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
                Text(
                    text = stringResource(R.string.about_legal_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_diagnostic_logging)) },
                    supportingContent = { Text(stringResource(R.string.about_diagnostic_logging_summary)) },
                    trailingContent = {
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = onDiagnosticLoggingChanged,
                            modifier = Modifier.testTag("about_diagnostic_logging_switch"),
                        )
                    },
                    modifier = Modifier.clickable {
                        onDiagnosticLoggingChanged(!state.enabled)
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_export_diagnostics)) },
                    supportingContent = {
                        Text(
                            when (state.exportResult) {
                                ExportResult.SUCCESS -> stringResource(R.string.about_export_diagnostics_success)
                                ExportResult.FAILURE -> stringResource(R.string.about_export_diagnostics_failure)
                                null -> stringResource(R.string.about_export_diagnostics_summary)
                            },
                        )
                    },
                    modifier = Modifier
                        .clickable(enabled = !state.exporting, onClick = onExportDiagnostics)
                        .testTag("about_export_diagnostics"),
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_license)) },
                    supportingContent = { Text(stringResource(R.string.about_license_gpl)) },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, licenseUrl.toUri()))
                    }.testTag("about_license"),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_github)) },
                    supportingContent = { Text(githubUrl) },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, githubUrl.toUri()))
                    }.testTag("about_github"),
                )
            }
        }
    }
}

/** App versionName from the package manager; "?" if unavailable. Moved here from SettingsScreen. */
internal fun appVersion(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

internal fun aboutBuildLabel(version: String, sourceCommit: String): String =
    "$version ($sourceCommit)"

@Preview
@Composable
private fun AboutScreenPreview() {
    MotdTheme {
        AboutContent(
            state = AboutDiagnosticsUiState(),
            onBack = {},
            onDiagnosticLoggingChanged = {},
            onExportDiagnostics = {},
        )
    }
}
