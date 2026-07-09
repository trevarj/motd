package io.github.trevarj.motd.ui.settings

import android.security.KeyChain
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
 *
 * [showServer]/[showAuth] let onboarding render server-only (step 3) or auth-only (step 4);
 * NetworkSettings leaves both on to show the full combined form.
 *
 * [soju] collapses everything into the bouncer-login form: host/port/TLS/nick + a single
 * Username/Password pair (the soju SASL login, always PLAIN). No mechanism picker, no separate
 * USER-ident username / real name — those are derived internally by [buildNetworkEntity].
 */
@Composable
fun NetworkForm(
    server: ServerForm,
    auth: AuthForm,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    modifier: Modifier = Modifier,
    showServer: Boolean = true,
    showAuth: Boolean = true,
    soju: Boolean = false,
    // Full USER-ident identity (nick + username + realname): the direct-network path shows all
    // three. The soju root shows only [showNick] (its nick), since the bouncer SASL user/password
    // live on the AUTH step and username/realname default to the nick.
    showIdentity: Boolean = true,
    showNick: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // soju: single collapsed form (host/port/TLS/nick + username/password), no picker.
        if (soju) {
            SojuFields(
                server = server,
                auth = auth,
                onServerChange = onServerChange,
                onAuthChange = onAuthChange,
            )
            return@Column
        }
        if (showServer) {
            ServerFields(
                server = server,
                onServerChange = onServerChange,
                showIdentity = showIdentity,
                showNick = showNick,
                // Last server field is Done unless the auth section follows it.
                lastImeAction = if (showAuth) ImeAction.Next else ImeAction.Done,
            )
        }
        if (showAuth) {
            Text(
                stringResource(R.string.onboarding_auth_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            AuthSection(auth = auth, onAuthChange = onAuthChange)
        }
    }
}

/**
 * soju bouncer form: host/port/TLS/nick + one Username + one Password. The Username/Password map
 * to the SASL login ([AuthForm.saslUser]/[AuthForm.saslPassword]); mechanism is always PLAIN.
 */
@Composable
private fun SojuFields(
    server: ServerForm,
    auth: AuthForm,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
) {
    HostField(server, onServerChange)
    PortField(server, onServerChange)
    TlsRow(server, onServerChange)
    NickField(server, onServerChange, imeAction = ImeAction.Next)
    // Bouncer login username — case-sensitive, no auto-capitalise/correct.
    OutlinedTextField(
        value = auth.saslUser,
        onValueChange = { onAuthChange(auth.copy(saslUser = it)) },
        label = { Text(stringResource(R.string.onboarding_field_username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(
        value = auth.saslPassword,
        onValueChange = { onAuthChange(auth.copy(saslPassword = it)) },
        label = stringResource(R.string.onboarding_auth_sasl_password),
        imeAction = ImeAction.Done,
    )
}

/**
 * Server fields. [showIdentity] gates the full nick/username/realname block (direct networks);
 * [showNick] surfaces just the nick (soju root), whose username/realname default to the nick and
 * whose SASL login lives on the AUTH step.
 */
@Composable
private fun ServerFields(
    server: ServerForm,
    onServerChange: (ServerForm) -> Unit,
    showIdentity: Boolean = true,
    showNick: Boolean = false,
    lastImeAction: ImeAction = ImeAction.Done,
) {
    val hasIdentity = showIdentity || showNick
    HostField(server, onServerChange)
    PortField(server, onServerChange)
    TlsRow(server, onServerChange)
    // Nick is shown for the full-identity direct path and for the nick-only soju root.
    if (hasIdentity) {
        NickField(
            server,
            onServerChange,
            imeAction = if (showIdentity) ImeAction.Next else lastImeAction,
        )
    }
    if (showIdentity) {
        OutlinedTextField(
            value = server.username,
            onValueChange = { onServerChange(server.copy(username = it)) },
            label = { Text(stringResource(R.string.onboarding_field_username)) },
            placeholder = { Text(stringResource(R.string.onboarding_field_username_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = server.realname,
            onValueChange = { onServerChange(server.copy(realname = it)) },
            label = { Text(stringResource(R.string.onboarding_field_realname)) },
            placeholder = { Text(stringResource(R.string.onboarding_field_realname_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = lastImeAction),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- Shared field composables ----------------------------------------------------------------

@Composable
private fun HostField(server: ServerForm, onServerChange: (ServerForm) -> Unit) {
    OutlinedTextField(
        value = server.host,
        onValueChange = { onServerChange(server.copy(host = it)) },
        label = { Text(stringResource(R.string.onboarding_field_host)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PortField(server: ServerForm, onServerChange: (ServerForm) -> Unit) {
    OutlinedTextField(
        value = server.port,
        onValueChange = { onServerChange(server.copy(port = it.filter(Char::isDigit))) },
        label = { Text(stringResource(R.string.onboarding_field_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TlsRow(server: ServerForm, onServerChange: (ServerForm) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.onboarding_field_tls), modifier = Modifier.weight(1f))
        // withTls re-defaults the port (6697<->6667) unless the user typed a custom one.
        Switch(checked = server.tls, onCheckedChange = { onServerChange(server.withTls(it)) })
    }
}

@Composable
private fun NickField(
    server: ServerForm,
    onServerChange: (ServerForm) -> Unit,
    imeAction: ImeAction,
) {
    // IRC nicks are case-sensitive: no auto-capitalise/correct.
    OutlinedTextField(
        value = server.nick,
        onValueChange = { onServerChange(server.copy(nick = it)) },
        label = { Text(stringResource(R.string.onboarding_field_nick)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Password field with a trailing show/hide eye that toggles the visual transformation. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.password_hide else R.string.password_show,
                    ),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
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
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = auth.saslPassword,
                onValueChange = { onAuthChange(auth.copy(saslPassword = it)) },
                label = stringResource(R.string.onboarding_auth_sasl_password),
                imeAction = ImeAction.Done,
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
    // Opt-in IRC-over-WebSocket endpoint (plans/19 §3.3); null keeps the TCP/TLS transport.
    wsUrl: String? = null,
): NetworkEntity {
    val isSoju = role == NetworkRole.BOUNCER_ROOT || role == NetworkRole.BOUNCER_CHILD
    // Trim leading/trailing whitespace: paste artefacts break host resolution and NICK/USER.
    val host = server.host.trim()
    val nick = server.nick.trim()
    val saslUser = auth.saslUser.trim()
    // :irc sends NICK/USER on every socket (incl. the soju root). Prefer the collected nick; fall
    // back to the SASL login user, then a placeholder, only as a last resort so the registration
    // lines stay well-formed.
    val identitySeed = nick.ifBlank { saslUser.ifBlank { DEFAULT_IDENTITY } }
    // soju is always SASL PLAIN (the bouncer login); the direct path uses the picked mechanism.
    val mechanism = if (isSoju) SaslMechanism.PLAIN else auth.mode.toSaslMechanism()
    return NetworkEntity(
        id = id,
        name = name,
        role = role,
        parentId = parentId,
        bouncerNetId = bouncerNetId,
        host = host,
        port = server.port.toIntOrNull() ?: 6697,
        tls = server.tls,
        nick = identitySeed,
        // soju: USER ident = nick; there is no separate ident field. Direct: explicit ident or nick.
        username = if (isSoju) identitySeed else server.username.trim().ifBlank { identitySeed },
        // soju: assume the login username is the real name (fallback nick). Direct: explicit or nick.
        realname = if (isSoju) saslUser.ifBlank { identitySeed } else server.realname.trim().ifBlank { identitySeed },
        saslMechanism = mechanism.name,
        saslUser = auth.saslUser.trim().ifBlank { null },
        saslPassword = auth.saslPassword.ifBlank { null },
        clientCertAlias = if (isSoju) null else auth.certAlias,
        wsUrl = wsUrl?.trim()?.ifBlank { null },
    )
}

/** Placeholder identity for the soju root socket when no nick/SASL user is available. */
private const val DEFAULT_IDENTITY = "motd"

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
