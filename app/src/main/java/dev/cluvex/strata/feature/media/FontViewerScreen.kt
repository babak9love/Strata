package dev.cluvex.strata.feature.media

import dev.cluvex.strata.ui.isolated
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.FontInfo
import dev.cluvex.strata.core.media.FontInfoReader
import dev.cluvex.strata.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private class LoadedFont(val info: FontInfo, val family: FontFamily)

private val CHARSETS = listOf(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    "abcdefghijklmnopqrstuvwxyz",
    "0123456789 !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
    "ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی ۰۱۲۳۴۵۶۷۸۹",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FontViewerScreen(path: String, onBack: () -> Unit) {
    val file = remember(path) { File(path) }
    val loaded by produceState<Result<LoadedFont>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { LoadedFont(FontInfoReader.read(file), FontFamily(Font(file))) }
        }
    }
    val defaultSample = stringResource(R.string.media_font_sample)
    var sample by remember(defaultSample) { mutableStateOf(defaultSample) }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.testTag("font-viewer"),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                title = {
                    Column {
                        Text(loaded?.getOrNull()?.info?.fullName ?: file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(isolated(file.name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
            )
        },
    ) { padding ->
        val result = loaded
        when {
            result == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            result.isFailure -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.media_font_unreadable, result.exceptionOrNull()?.message.toString()), color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val font = result.getOrThrow()
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { FontDetails(font.info, file.length()) }
                    item {
                        OutlinedTextField(
                            value = sample,
                            onValueChange = { sample = it },
                            label = { Text(stringResource(R.string.media_sample_text)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(listOf(12, 16, 20, 28, 36, 48, 64)) { size ->
                        Column {
                            Text(stringResource(R.string.media_font_size_sp, size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(sample, fontFamily = font.family, fontSize = size.sp, lineHeight = (size * 1.3f).sp)
                        }
                    }
                    item { HorizontalDivider() }
                    items(CHARSETS) { chars ->
                        Text(chars, fontFamily = font.family, fontSize = 24.sp, lineHeight = 34.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FontDetails(info: FontInfo, size: Long) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            info.family?.let { InfoLine(stringResource(R.string.media_font_family), it) }
            info.subfamily?.let { InfoLine(stringResource(R.string.media_font_style), it) }
            val fonts = info.fontsInCollection
            InfoLine(
                stringResource(R.string.media_font_format),
                if (fonts > 1) pluralStringResource(R.plurals.media_font_collection, fonts, info.format, fonts) else info.format,
            )
            InfoLine(stringResource(R.string.media_font_glyphs), stringResource(R.string.media_number, info.glyphCount))
            info.version?.let { InfoLine(stringResource(R.string.media_font_version), it) }
            info.designer?.let { InfoLine(stringResource(R.string.media_font_designer), it) }
            info.copyright?.let { InfoLine(stringResource(R.string.media_font_copyright), it) }
            InfoLine(stringResource(R.string.media_file_size), Formatters.size(size))
        }
    }
}
