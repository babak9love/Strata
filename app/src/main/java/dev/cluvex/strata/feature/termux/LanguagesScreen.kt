package dev.cluvex.strata.feature.termux

import dev.cluvex.strata.core.i18n.separator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.termux.TermuxLanguages

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LanguagesScreen(onBack: () -> Unit, vm: TermuxViewModel = viewModel()) {
    val session = vm.session
    val state by session.state.collectAsStateWithLifecycle()
    TrackTermuxLaunchDisplay()
    val snackbar = remember { SnackbarHostState() }
    LifecycleResumeEffect(Unit) {
        session.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); session.consumeMessage() } }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.termux_languages_title))
                        val installedCount = state.installed?.size
                        Text(
                            when {
                                !state.termuxInstalled -> stringResource(R.string.termux_sub_not_installed)
                                !state.permission -> stringResource(R.string.termux_sub_permission)
                                installedCount != null -> pluralStringResource(R.plurals.termux_sub_installed, installedCount, installedCount)
                                else -> "Termux"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    if (state.checking) {
                        LoadingIndicator(Modifier.size(36.dp).padding(end = 8.dp))
                    } else {
                        IconButton(onClick = session::refresh, enabled = state.reachable) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.termux_check_again)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("languages")) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermuxSetupStep(state, session)
                    Text(
                        stringResource(R.string.termux_languages_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(TermuxLanguages.all, key = { it.id }) { language ->
                val installed = state.isInstalled(language)
                ListItem(
                    content = { Text(language.displayName) },
                    supportingContent = {
                        val version = state.installed?.get(language.id)?.takeIf { it.isNotBlank() }
                        val notInstalled = stringResource(R.string.termux_not_installed_short)
                        Text(
                            buildString {
                                append(language.extensions.joinToString(" ") { ".$it" })
                                when {
                                    installed == true && version != null -> append(separator()).append(version)
                                    installed == false -> append(separator()).append(notInstalled)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        when {
                            installed == true -> Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.termux_installed), tint = MaterialTheme.colorScheme.primary)
                            installed == false && state.reachable -> FilledTonalButton(onClick = { session.install(language) }) { Text(stringResource(R.string.common_install)) }
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }
}
