package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.ui.theme.MotdTheme
import java.text.DateFormat
import java.util.Date

/**
 * TOFU cert-trust dialog (plans/12). Shows the presented leaf's host:port, colon-formatted SHA-256,
 * subject, issuer, and validity window. When [CertPrompt.changed] is true it leads with a red
 * "certificate changed" warning (possible MITM / rotation). Trust pins the leaf and reconnects;
 * Cancel dismisses and leaves the network disconnected.
 */
@Composable
fun CertTrustDialog(
    prompt: CertPrompt,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("cert_trust_dialog"),
        onDismissRequest = onCancel,
        title = {
            Text(
                text = if (prompt.changed) {
                    stringResource(R.string.cert_trust_changed_title)
                } else {
                    stringResource(R.string.cert_trust_title)
                },
                color = if (prompt.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (prompt.changed) {
                    Text(
                        text = stringResource(R.string.cert_trust_changed_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.cert_trust_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CertField(stringResource(R.string.cert_trust_host), "${prompt.host}:${prompt.port}")
                CertField(
                    stringResource(R.string.cert_trust_fingerprint),
                    formatFingerprint(prompt.sha256),
                    modifier = Modifier.testTag("cert_trust_fingerprint"),
                    monospace = true,
                )
                CertField(stringResource(R.string.cert_trust_subject), prompt.subject)
                CertField(stringResource(R.string.cert_trust_issuer), prompt.issuer)
                CertField(stringResource(R.string.cert_trust_valid_from), formatDate(prompt.notBefore))
                CertField(stringResource(R.string.cert_trust_valid_to), formatDate(prompt.notAfter))
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cert_trust_cancel)) }
        },
    )
}

@Composable
private fun CertField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            // Applied to the value Text so the harness can assert the specific fingerprint.
            modifier = modifier,
        )
    }
}

/** Group the lowercase-hex fingerprint into colon-separated byte pairs (AB:CD:...). */
private fun formatFingerprint(sha256: String): String =
    sha256.uppercase().chunked(2).joinToString(":")

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

@Preview
@Composable
private fun CertTrustDialogPreview() {
    MotdTheme {
        CertTrustDialog(
            prompt = CertPrompt(
                networkId = 1,
                host = "192.168.1.10",
                port = 6697,
                sha256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                subject = "CN=bouncer.local",
                issuer = "CN=bouncer.local",
                notBefore = 0L,
                notAfter = 4102444800000L,
                changed = false,
            ),
            onTrust = {},
            onCancel = {},
        )
    }
}
