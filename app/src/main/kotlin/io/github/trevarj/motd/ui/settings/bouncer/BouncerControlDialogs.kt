package io.github.trevarj.motd.ui.settings.bouncer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.bouncer.NetworkCommandFields
import io.github.trevarj.motd.bouncer.UserCommandFields

@Composable
fun NetworkEditorDialog(
    existingName: String?,
    onDismiss: () -> Unit,
    onSubmit: (NetworkCommandFields) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var realName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var connectCommands by remember { mutableStateOf("") }
    var autoAway by remember { mutableStateOf<Boolean?>(null) }
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    val creating = existingName == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (creating) stringResource(R.string.bouncer_network_create_title)
                else stringResource(R.string.bouncer_network_update_title, existingName),
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (creating) stringResource(R.string.bouncer_network_create_help)
                    else stringResource(R.string.bouncer_network_update_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormField(address, { address = it }, R.string.bouncer_field_address)
                FormField(name, { name = it }, R.string.bouncer_field_name)
                FormField(nick, { nick = it }, R.string.bouncer_field_nick)
                FormField(username, { username = it }, R.string.onboarding_field_username)
                FormField(realName, { realName = it }, R.string.onboarding_field_realname)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.onboarding_auth_soju_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriStateField(
                    label = stringResource(R.string.bouncer_field_auto_away),
                    value = autoAway,
                    onValueChange = { autoAway = it },
                )
                TriStateField(
                    label = stringResource(R.string.bouncer_field_enabled),
                    value = enabled,
                    onValueChange = { enabled = it },
                )
                OutlinedTextField(
                    value = connectCommands,
                    onValueChange = { connectCommands = it },
                    label = { Text(stringResource(R.string.bouncer_field_connect_commands)) },
                    supportingText = { Text(stringResource(R.string.bouncer_field_connect_commands_help)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating || address.isNotBlank(),
                onClick = {
                    onSubmit(
                        NetworkCommandFields(
                            address = address.trim().takeIf(String::isNotBlank),
                            name = name.trim().takeIf(String::isNotBlank),
                            nick = nick.trim().takeIf(String::isNotBlank),
                            username = username.trim().takeIf(String::isNotBlank),
                            realName = realName.trim().takeIf(String::isNotBlank),
                            autoAway = autoAway,
                            enabled = enabled,
                            password = password.takeIf(String::isNotEmpty),
                            connectCommands = connectCommands.lineSequence()
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .toList()
                                .takeIf(List<String>::isNotEmpty),
                        ),
                    )
                },
            ) { Text(stringResource(if (creating) R.string.bouncer_add else R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun SaslPlainDialog(
    network: String,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bouncer_sasl_set_title, network)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField(username, { username = it }, R.string.onboarding_auth_sasl_user)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.onboarding_auth_sasl_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotEmpty(),
                onClick = { onSubmit(username.trim(), password) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun UserCreateDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, Boolean, Boolean) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var administrator by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bouncer_user_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField(username, { username = it }, R.string.onboarding_field_username)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.onboarding_auth_soju_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CheckboxRow(stringResource(R.string.bouncer_user_admin), administrator) { administrator = it }
                CheckboxRow(stringResource(R.string.bouncer_field_enabled), enabled) { enabled = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotEmpty(),
                onClick = { onSubmit(username.trim(), password, administrator, enabled) },
            ) { Text(stringResource(R.string.bouncer_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun UserUpdateDialog(
    currentUsername: String,
    administrator: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String?, UserCommandFields) -> Unit,
) {
    var target by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var disablePassword by remember { mutableStateOf(false) }
    var nick by remember { mutableStateOf("") }
    var realName by remember { mutableStateOf("") }
    var adminValue by remember { mutableStateOf<Boolean?>(null) }
    var enabledValue by remember { mutableStateOf<Boolean?>(null) }
    var maxNetworks by remember { mutableStateOf("") }
    val otherUser = target.isNotBlank() && target != currentUsername
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bouncer_user_update_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (administrator) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text(stringResource(R.string.bouncer_user_target)) },
                        supportingText = { Text(stringResource(R.string.bouncer_user_target_help, currentUsername)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; if (it.isNotEmpty()) disablePassword = false },
                    label = { Text(stringResource(R.string.onboarding_auth_soju_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !disablePassword,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CheckboxRow(
                    stringResource(R.string.bouncer_user_disable_password),
                    disablePassword,
                ) {
                    disablePassword = it
                    if (it) password = ""
                }
                if (!otherUser) {
                    FormField(nick, { nick = it }, R.string.bouncer_field_nick)
                    FormField(realName, { realName = it }, R.string.onboarding_field_realname)
                }
                if (administrator && otherUser) {
                    TriStateField(stringResource(R.string.bouncer_user_admin), adminValue) { adminValue = it }
                    TriStateField(stringResource(R.string.bouncer_field_enabled), enabledValue) { enabledValue = it }
                }
                if (administrator) {
                    OutlinedTextField(
                        value = maxNetworks,
                        onValueChange = { maxNetworks = it.filter { char -> char.isDigit() || char == '-' } },
                        label = { Text(stringResource(R.string.bouncer_user_max_networks)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(
                    target.trim().takeIf(String::isNotBlank),
                    UserCommandFields(
                        password = password.takeIf(String::isNotEmpty),
                        disablePassword = disablePassword,
                        administrator = adminValue,
                        nick = nick.trim().takeIf(String::isNotBlank),
                        realName = realName.trim().takeIf(String::isNotBlank),
                        enabled = enabledValue,
                        maxNetworks = maxNetworks.toIntOrNull(),
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun TypedUserDeleteDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bouncer_user_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.bouncer_user_delete_help))
                FormField(username, { username = it }, R.string.onboarding_field_username)
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(R.string.bouncer_user_delete_type_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && confirmation == username,
                onClick = { onSubmit(username.trim()) },
            ) { Text(stringResource(R.string.action_continue), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun TextCommandDialog(
    title: String,
    firstLabel: String,
    secondLabel: String? = null,
    secretSecond: Boolean = false,
    warning: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(firstLabel) },
                    minLines = if (secondLabel == null) 2 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                secondLabel?.let {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text(it) },
                        visualTransformation = if (secretSecond) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = first.isNotBlank() && (secondLabel == null || second.isNotBlank()),
                onClick = { onSubmit(first.trim(), second.trim()) },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriStateField(label: String, value: Boolean?, onValueChange: (Boolean?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val valueLabel = when (value) {
        null -> stringResource(R.string.bouncer_keep_unchanged)
        true -> stringResource(R.string.action_enable)
        false -> stringResource(R.string.action_disable)
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = valueLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf<Boolean?>(null, true, false).forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (option) {
                                null -> stringResource(R.string.bouncer_keep_unchanged)
                                true -> stringResource(R.string.action_enable)
                                false -> stringResource(R.string.action_disable)
                            },
                        )
                    },
                    onClick = { expanded = false; onValueChange(option) },
                )
            }
        }
    }
}

@Composable
private fun FormField(value: String, onValueChange: (String) -> Unit, label: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}
