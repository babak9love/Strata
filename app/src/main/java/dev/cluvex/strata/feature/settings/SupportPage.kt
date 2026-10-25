package dev.cluvex.strata.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.donate.Donation
import dev.cluvex.strata.ui.asPath
import dev.cluvex.strata.ui.rememberTextClipboard
import kotlinx.coroutines.launch

@Composable
fun SupportCard(onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("settings-support-card"),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingIcon(Icons.Rounded.VolunteerActivism, Tint.PRIMARY, size = 48)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.settings_support_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_support_summary), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun SupportPage(onBack: () -> Unit) {
    val clipboard = rememberTextClipboard()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.settings_support_copied)
    SettingsPageScaffold(stringResource(R.string.settings_support_title), onBack, snackbar = snackbar) {
        Text(
            stringResource(R.string.settings_support_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp).testTag("support-body"),
        )
        SettingsGroup(stringResource(R.string.settings_support_wallets), footer = stringResource(R.string.settings_support_network_hint)) {
            Donation.wallets.forEach { wallet ->
                custom(title = wallet.network, icon = Icons.Rounded.AccountBalanceWallet, tint = Tint.PRIMARY) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                wallet.address,
                                style = MaterialTheme.typography.bodyMedium.asPath(),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("wallet-${wallet.id}"),
                            )
                        }
                        IconButton(
                            onClick = {
                                clipboard.setText(wallet.address)
                                scope.launch { snackbar.showSnackbar(copied) }
                            },
                            modifier = Modifier.testTag("copy-${wallet.id}"),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.settings_support_copy, wallet.network))
                        }
                    }
                }
            }
        }
    }
}
