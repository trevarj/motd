package io.github.trevarj.motd.ui.about

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.settings.PersistentStatusNotice
import io.github.trevarj.motd.ui.settings.SettingsActionRow
import io.github.trevarj.motd.ui.settings.SettingsDivider
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import io.github.trevarj.motd.ui.settings.SwitchRow
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ceramicLogoColorMatrix
import io.github.trevarj.motd.ui.settings.SettingsTarget as SettingsTargetAnchor

@Composable
fun AboutScreen(
    onBack: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val createDiagnosticDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let(viewModel::export)
        }
    AboutContent(
        state = state,
        target = target,
        onBack = onBack,
        onDiagnosticLoggingChanged = viewModel::setDiagnosticLoggingEnabled,
        onExportDiagnostics = { createDiagnosticDocument.launch("motd-diagnostics-${System.currentTimeMillis()}.txt") },
    )
}

@Composable
private fun AboutContent(
    state: AboutDiagnosticsUiState,
    target: SettingsTarget? = null,
    onBack: () -> Unit,
    onDiagnosticLoggingChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val licenseUrl = stringResource(R.string.about_license_url)
    val githubUrl = stringResource(R.string.settings_github_url)
    SettingsScaffold(
        title = stringResource(R.string.about_title),
        onBack = onBack,
        modifier = Modifier.testTag("screen_about"),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.motd_logo_mark),
                contentDescription = null,
                colorFilter =
                    ColorFilter.colorMatrix(
                        ColorMatrix(ceramicLogoColorMatrix(MaterialTheme.colorScheme.onSurface.toArgb())),
                    ),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(34.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            aboutBuildLabel(appVersion(context), BuildConfig.MOTD_SOURCE_COMMIT),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.about_blurb),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            textAlign = TextAlign.Center,
        )
        SettingsTargetAnchor(
            if (target == SettingsTarget.ABOUT) SettingsTarget.DIAGNOSTICS.name else target?.name,
            SettingsTarget.DIAGNOSTICS.name,
        ) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.about_support_section), modifier = targetModifier) {
                SwitchRow(
                    title = stringResource(R.string.about_diagnostic_logging),
                    subtitle = stringResource(R.string.about_diagnostic_logging_summary),
                    checked = state.enabled,
                    onCheckedChange = onDiagnosticLoggingChanged,
                    switchTag = "about_diagnostic_logging_switch",
                )
                SettingsDivider()
                SettingsActionRow(
                    title = stringResource(R.string.about_export_diagnostics),
                    summary = stringResource(R.string.about_export_diagnostics_summary),
                    enabled = !state.exporting,
                    modifier = Modifier.testTag("about_export_diagnostics"),
                    onClick = onExportDiagnostics,
                )
                state.exportResult?.let { result ->
                    PersistentStatusNotice(
                        text = stringResource(if (result == ExportResult.SUCCESS) R.string.about_export_diagnostics_success else R.string.about_export_diagnostics_failure),
                        error = result == ExportResult.FAILURE,
                    )
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.about_project_section)) {
            SettingsTargetAnchor(target?.name, SettingsTarget.LICENSE.name) { targetModifier ->
                SettingsActionRow(
                    title = stringResource(R.string.about_license),
                    summary = stringResource(R.string.about_license_gpl),
                    modifier = targetModifier.testTag("about_license"),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, licenseUrl.toUri())) },
                )
            }
            SettingsDivider()
            SettingsTargetAnchor(target?.name, SettingsTarget.PROJECT.name) { targetModifier ->
                SettingsActionRow(
                    title = stringResource(R.string.settings_github),
                    summary = githubUrl,
                    modifier = targetModifier.testTag("about_github"),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, githubUrl.toUri())) },
                )
            }
        }
        Text(
            stringResource(R.string.about_legal_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

internal fun appVersion(context: android.content.Context): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }.getOrDefault("?")

internal fun aboutBuildLabel(
    version: String,
    sourceCommit: String,
): String = "$version ($sourceCommit)"

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AboutScreenPreview() {
    MotdTheme {
        AboutContent(AboutDiagnosticsUiState(), onBack = {}, onDiagnosticLoggingChanged = {}, onExportDiagnostics = {})
    }
}
