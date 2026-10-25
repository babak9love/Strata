package dev.cluvex.strata.feature.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("plugins-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plugin_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        ExpressiveEmptyState(
            title = stringResource(R.string.plugin_coming_soon),
            body = stringResource(R.string.plugin_coming_soon_body),
            icon = Icons.Rounded.Extension,
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).testTag("plugins-coming-soon"),
        )
    }
}
