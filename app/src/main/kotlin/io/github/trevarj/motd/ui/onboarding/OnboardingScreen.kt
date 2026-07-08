package io.github.trevarj.motd.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.settings.NetworkForm
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel; the wizard is a single route with an internal pager. */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    OnboardingContent(
        state = state,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onChoose = viewModel::chooseConnection,
        onLibera = viewModel::applyLiberaPreset,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onRetry = viewModel::retryConnect,
        onToggleBouncer = viewModel::toggleBouncerNetwork,
        onAddBouncer = viewModel::addBouncerNetwork,
        onFinish = { viewModel.finish(onDone) },
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onChoose: (ConnectionChoice) -> Unit,
    onLibera: () -> Unit,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onRetry: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onAddBouncer: (String, String) -> Unit,
    onFinish: () -> Unit,
) {
    val steps = OnboardingStep.entries
    val pagerState = rememberPagerState(pageCount = { steps.size })

    // Keep the pager synced to the reducer-driven step.
    LaunchedEffect(state.step) {
        pagerState.animateScrollToPage(steps.indexOf(state.step))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
        ) { page ->
            when (steps[page]) {
                OnboardingStep.WELCOME -> WelcomePage()
                OnboardingStep.CHOICE -> ChoicePage(state, onChoose, onLibera)
                OnboardingStep.SERVER -> ServerPage(state, onServerChange, onAuthChange, authOnly = false)
                OnboardingStep.AUTH -> ServerPage(state, onServerChange, onAuthChange, authOnly = true)
                OnboardingStep.CONNECT -> ConnectPage(state, onRetry, onToggleBouncer, onAddBouncer)
                OnboardingStep.FINISH -> FinishPage()
            }
        }
        WizardBar(
            state = state,
            onNext = onNext,
            onBack = onBack,
            onFinish = onFinish,
        )
    }
}

@Composable
private fun WizardBar(
    state: OnboardingState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.step != OnboardingStep.WELCOME) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
        } else {
            Spacer(Modifier.size(1.dp))
        }
        when (state.step) {
            OnboardingStep.WELCOME -> Button(onClick = onNext) {
                Text(stringResource(R.string.onboarding_get_started))
            }
            OnboardingStep.FINISH -> Button(onClick = onFinish) {
                Text(stringResource(R.string.onboarding_finish))
            }
            else -> Button(onClick = onNext, enabled = state.canAdvance) {
                Text(stringResource(R.string.onboarding_next))
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.onboarding_welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ChoicePage(
    state: OnboardingState,
    onChoose: (ConnectionChoice) -> Unit,
    onLibera: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_choice_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        ChoiceCard(
            title = stringResource(R.string.onboarding_choice_soju_title),
            desc = stringResource(R.string.onboarding_choice_soju_desc),
            selected = state.choice == ConnectionChoice.SOJU,
            onClick = { onChoose(ConnectionChoice.SOJU) },
        )
        ChoiceCard(
            title = stringResource(R.string.onboarding_choice_network_title),
            desc = stringResource(R.string.onboarding_choice_network_desc),
            selected = state.choice == ConnectionChoice.NETWORK,
            onClick = { onChoose(ConnectionChoice.NETWORK) },
        )
        OutlinedButton(onClick = onLibera, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.onboarding_libera_preset))
                Text(
                    stringResource(R.string.onboarding_libera_preset_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp),
                ) else Modifier,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Steps 3 (server) and 4 (auth) share the [NetworkForm]. [authOnly] scrolls emphasis to auth;
 * both render the full form so a user can revise either without leaving the wizard.
 */
@Composable
private fun ServerPage(
    state: OnboardingState,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    authOnly: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
    ) {
        Text(
            stringResource(if (authOnly) R.string.onboarding_auth_title else R.string.onboarding_server_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        NetworkForm(
            server = state.server,
            auth = state.auth,
            onServerChange = onServerChange,
            onAuthChange = onAuthChange,
        )
    }
}

@Composable
private fun ConnectPage(
    state: OnboardingState,
    onRetry: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onAddBouncer: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_connect_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        StateIndicator(state.connState)
        state.stateLog.forEach { s ->
            Text("• ${s::class.simpleName}", style = MaterialTheme.typography.bodySmall)
        }
        val failed = state.connState as? IrcClientState.Failed
        if (failed != null) {
            failed.reason.let { Text(it, color = Color.Red) }
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.onboarding_connect_retry)) }
        }

        if (state.isReady && state.isSoju && state.bouncerListLoaded) {
            BouncerNetworksSection(state, onToggleBouncer, onAddBouncer)
        }
    }
}

@Composable
private fun StateIndicator(connState: IrcClientState?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedContent(targetState = connState, label = "connState") { cs ->
            when (cs) {
                is IrcClientState.Ready -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF2E7D32))
                is IrcClientState.Failed -> Icon(Icons.Filled.Close, contentDescription = null, tint = Color.Red)
                null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            when (connState) {
                is IrcClientState.Ready -> "Connected as ${connState.nick}"
                is IrcClientState.Failed -> "Failed"
                IrcClientState.Registering -> "Registering…"
                IrcClientState.Connecting -> "Connecting…"
                else -> "Starting…"
            },
        )
    }
}

@Composable
private fun BouncerNetworksSection(
    state: OnboardingState,
    onToggleBouncer: (String) -> Unit,
    onAddBouncer: (String, String) -> Unit,
) {
    Text(
        stringResource(R.string.onboarding_connect_networks_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        stringResource(R.string.onboarding_connect_import_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.selectableGroup()) {
        state.bouncerNetworks.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleBouncer(row.netId) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.name, modifier = Modifier.weight(1f))
                Switch(checked = row.selected, onCheckedChange = { onToggleBouncer(row.netId) })
            }
        }
    }

    var addName by remember { mutableStateOf("") }
    var addHost by remember { mutableStateOf("") }
    Text(
        stringResource(R.string.onboarding_connect_add_network),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    OutlinedTextField(
        value = addName,
        onValueChange = { addName = it },
        label = { Text(stringResource(R.string.onboarding_connect_add_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = addHost,
        onValueChange = { addHost = it },
        label = { Text(stringResource(R.string.onboarding_connect_add_host)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { onAddBouncer(addName, addHost); addName = ""; addHost = "" },
        enabled = addName.isNotBlank() && addHost.isNotBlank(),
    ) { Text(stringResource(R.string.onboarding_connect_add_network)) }
}

@Composable
private fun FinishPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            stringResource(R.string.onboarding_finish),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Preview
@Composable
private fun OnboardingChoicePreview() {
    MotdTheme {
        OnboardingContent(
            state = OnboardingState(step = OnboardingStep.CHOICE, choice = ConnectionChoice.NETWORK),
            onNext = {}, onBack = {}, onChoose = {}, onLibera = {},
            onServerChange = {}, onAuthChange = {}, onRetry = {},
            onToggleBouncer = {}, onAddBouncer = { _, _ -> }, onFinish = {},
        )
    }
}

@Preview
@Composable
private fun OnboardingConnectPreview() {
    MotdTheme {
        OnboardingContent(
            state = OnboardingState(
                step = OnboardingStep.CONNECT,
                choice = ConnectionChoice.SOJU,
                connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                stateLog = listOf(IrcClientState.Connecting, IrcClientState.Registering),
                bouncerListLoaded = true,
                bouncerNetworks = listOf(
                    BouncerNetworkRow("1", "Libera", selected = true),
                    BouncerNetworkRow("2", "OFTC", selected = false),
                ),
            ),
            onNext = {}, onBack = {}, onChoose = {}, onLibera = {},
            onServerChange = {}, onAuthChange = {}, onRetry = {},
            onToggleBouncer = {}, onAddBouncer = { _, _ -> }, onFinish = {},
        )
    }
}
