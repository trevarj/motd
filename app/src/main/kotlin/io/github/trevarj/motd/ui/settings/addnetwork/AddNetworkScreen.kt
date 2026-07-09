package io.github.trevarj.motd.ui.settings.addnetwork

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.trevarj.motd.R

/**
 * Add-network flow (plans/16 §5.4). WP-V0 stub: shell only. WP-V2 fills the ViewModel-backed
 * three-phase (FORM/TESTING/FAILED) body. [onOpenBouncerNetworks] is invoked with the created
 * soju root network id after a successful test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkScreen(
    onBack: () -> Unit = {},
    onOpenBouncerNetworks: (Long) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_network_title)) },
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
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.add_network_title))
        }
    }
}
