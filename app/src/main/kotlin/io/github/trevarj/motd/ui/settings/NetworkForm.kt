package io.github.trevarj.motd.ui.settings

import android.security.KeyChain
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm

/**
 * Reusable server + auth edit form, shared by onboarding steps 3-4 and NetworkSettings. Stateless:
 * the caller owns the [ServerForm]/[AuthForm] state and receives edits via callbacks.
 */
@Composable
fun NetworkForm(
    server: ServerForm,
    auth: AuthForm,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = server.host,
            onValueChange = { onServerChange(server.copy(host = it)) },
            label = { Text(stringResource(R.string.onboarding_field_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = server.port,
            onValueChange = { onServerChange(server.copy(port = it.filter(Char::isDigit))) },
            label = { Text(stringResource(R.string.onboarding_field_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.onboarding_field_tls), modifier = Modifier.weight(1f))
            Switch(checked = server.tls, onCheckedChange = { onServerChange(server.copy(tls = it)) })
        }
        OutlinedTextField(
            value = server.nick,
            onValueChange = { onServerChange(server.copy(nick = it)) },
            label = { Text(stringResource(R.string.onboarding_field_nick)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = server.username,
            onValueChange = { onServerChange(server.copy(username = it)) },
            label = { Text(stringResource(R.string.onboarding_field_username)) },
            placeholder = { Text(stringResource(R.string.onboarding_field_username_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = server.realname,
            onValueChange = { onServerChange(server.copy(realname = it)) },
            label = { Text(stringResource(R.string.onboarding_field_realname)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.onboarding_auth_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        AuthSection(auth = auth, onAuthChange = onAuthChange)
    }
}

@Composable
private fun AuthSection(auth: AuthForm, onAuthChange: (AuthForm) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.selectableGroup()) {
        AuthOption(AuthMode.NONE, auth.mode, stringResource(R.string.onboarding_auth_none)) {
            onAuthChange(auth.copy(mode = AuthMode.NONE))
        }
        AuthOption(AuthMode.PLAIN, auth.mode, stringResource(R.string.onboarding_auth_plain)) {
            onAuthChange(auth.copy(mode = AuthMode.PLAIN))
        }
        AuthOption(AuthMode.EXTERNAL, auth.mode, stringResource(R.string.onboarding_auth_external)) {
            onAuthChange(auth.copy(mode = AuthMode.EXTERNAL))
        }
    }

    when (auth.mode) {
        AuthMode.PLAIN -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = auth.saslUser,
                onValueChange = { onAuthChange(auth.copy(saslUser = it)) },
                label = { Text(stringResource(R.string.onboarding_auth_sasl_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = auth.saslPassword,
                onValueChange = { onAuthChange(auth.copy(saslPassword = it)) },
                label = { Text(stringResource(R.string.onboarding_auth_sasl_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AuthMode.EXTERNAL -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    // System client-cert picker; result folds back into the form.
                    KeyChain.choosePrivateKeyAlias(
                        context as android.app.Activity,
                        { alias -> onAuthChange(auth.copy(certAlias = alias)) },
                        null, null, null, -1, null,
                    )
                },
            ) { Text(stringResource(R.string.onboarding_auth_choose_cert)) }
            auth.certAlias?.let {
                Text(stringResource(R.string.onboarding_auth_cert_selected, it))
            }
        }

        AuthMode.NONE -> Unit
    }
}

@Composable
private fun AuthOption(mode: AuthMode, selected: AuthMode, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

// ---- Mapping helpers between form types and NetworkEntity ------------------------------------

fun AuthMode.toSaslMechanism(): SaslMechanism = when (this) {
    AuthMode.NONE -> SaslMechanism.NONE
    AuthMode.PLAIN -> SaslMechanism.PLAIN
    AuthMode.EXTERNAL -> SaslMechanism.EXTERNAL
}

fun saslToAuthMode(name: String): AuthMode = when (name) {
    SaslMechanism.PLAIN.name -> AuthMode.PLAIN
    SaslMechanism.EXTERNAL.name -> AuthMode.EXTERNAL
    else -> AuthMode.NONE
}

/** Build a NetworkEntity from the shared forms. [role] distinguishes soju root vs. direct. */
fun buildNetworkEntity(
    server: ServerForm,
    auth: AuthForm,
    role: NetworkRole,
    id: Long = 0,
    name: String = server.host,
    parentId: Long? = null,
    bouncerNetId: String? = null,
): NetworkEntity = NetworkEntity(
    id = id,
    name = name,
    role = role,
    parentId = parentId,
    bouncerNetId = bouncerNetId,
    host = server.host,
    port = server.port.toIntOrNull() ?: 6697,
    tls = server.tls,
    nick = server.nick,
    username = server.effectiveUsername,
    realname = server.realname.ifBlank { server.nick },
    saslMechanism = auth.mode.toSaslMechanism().name,
    saslUser = auth.saslUser.ifBlank { null },
    saslPassword = auth.saslPassword.ifBlank { null },
    clientCertAlias = auth.certAlias,
)

/** Inverse: seed the forms from an existing entity for editing. */
fun NetworkEntity.toServerForm(): ServerForm = ServerForm(
    host = host,
    port = port.toString(),
    tls = tls,
    nick = nick,
    username = username,
    realname = realname,
)

fun NetworkEntity.toAuthForm(): AuthForm = AuthForm(
    mode = saslToAuthMode(saslMechanism),
    saslUser = saslUser.orEmpty(),
    saslPassword = saslPassword.orEmpty(),
    certAlias = clientCertAlias,
)
