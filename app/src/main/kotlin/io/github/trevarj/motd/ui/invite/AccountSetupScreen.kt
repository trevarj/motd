package io.github.trevarj.motd.ui.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AccountEnrollmentProvider
import io.github.trevarj.motd.ui.settings.PasswordField

@Composable
fun AccountSetupScreen(
    networkId: Long,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: AccountSetupViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.init(networkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.events.collect { onComplete() } }
    AccountSetupContent(
        state = state,
        onBack = onBack,
        onAccountChange = viewModel::editAccount,
        onEmailChange = viewModel::editEmail,
        onPasswordChange = viewModel::editPassword,
        onVerificationChange = viewModel::editVerification,
        onSubmit = viewModel::submit,
        onVerify = viewModel::verify,
        onRequestOftcVerification = viewModel::requestOftcVerification,
        onRetry = viewModel::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSetupContent(
    state: AccountSetupUiState,
    onBack: () -> Unit,
    onAccountChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onVerificationChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onVerify: () -> Unit,
    onRetry: () -> Unit,
    onRequestOftcVerification: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.account_setup_help))
            when (state.phase) {
                AccountSetupPhase.FORM, AccountSetupPhase.FAILED -> {
                    OutlinedTextField(
                        value = state.account,
                        onValueChange = onAccountChange,
                        label = { Text(stringResource(R.string.account_setup_account)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("account_setup_account"),
                    )
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = {
                            Text(
                                stringResource(
                                    if (state.emailRequired) R.string.account_setup_email_required else R.string.account_setup_email_optional,
                                ),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("account_setup_email"),
                    )
                    if (state.provider == AccountEnrollmentProvider.LIBERA) {
                        Text(
                            stringResource(R.string.account_setup_libera_email_help),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PasswordField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = stringResource(R.string.onboarding_auth_sasl_password),
                        modifier = Modifier.fillMaxWidth().testTag("account_setup_password"),
                    )
                    state.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                    Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().testTag("account_setup_submit")) {
                        Text(stringResource(R.string.account_setup_create))
                    }
                    if (state.phase == AccountSetupPhase.FAILED) {
                        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.onboarding_connect_retry))
                        }
                    }
                }

                AccountSetupPhase.VERIFY -> {
                    if (state.provider == AccountEnrollmentProvider.OFTC) {
                        Text(stringResource(R.string.account_setup_oftc_verify_help))
                        state.verificationUrl?.let { url ->
                            Button(
                                onClick = { uriHandler.openUri(url) },
                                modifier = Modifier.fillMaxWidth().testTag("account_setup_open_verification"),
                            ) {
                                Text(stringResource(R.string.account_setup_open_verification))
                            }
                        }
                        OutlinedButton(
                            onClick = onRequestOftcVerification,
                            modifier = Modifier.fillMaxWidth().testTag("account_setup_new_verification"),
                        ) {
                            Text(stringResource(R.string.account_setup_new_verification))
                        }
                        state.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        Button(onClick = onVerify, modifier = Modifier.fillMaxWidth().testTag("account_setup_verify")) {
                            Text(stringResource(R.string.account_setup_oftc_verified))
                        }
                    } else {
                        state.serverMessage?.let { Text(it) }
                        Text(stringResource(R.string.account_setup_verify_help))
                        OutlinedTextField(
                            value = state.verification,
                            onValueChange = onVerificationChange,
                            label = { Text(stringResource(R.string.account_setup_code)) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("account_setup_code"),
                        )
                        state.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        Button(onClick = onVerify, modifier = Modifier.fillMaxWidth().testTag("account_setup_verify")) {
                            Text(stringResource(R.string.account_setup_verify))
                        }
                    }
                }

                AccountSetupPhase.SUBMITTING, AccountSetupPhase.ACTIVATING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(
                        stringResource(
                            if (state.phase == AccountSetupPhase.ACTIVATING) {
                                R.string.account_setup_signing_in
                            } else {
                                R.string.account_setup_creating
                            },
                        ),
                    )
                }

                AccountSetupPhase.SUCCESS -> {
                    Text(stringResource(R.string.account_setup_success))
                }

                AccountSetupPhase.UNSUPPORTED -> {
                    Text(state.error.orEmpty(), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
