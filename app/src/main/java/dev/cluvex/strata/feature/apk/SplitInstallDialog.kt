package dev.cluvex.strata.feature.apk

import dev.cluvex.strata.core.i18n.separator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.AppIntents
import dev.cluvex.strata.core.apk.SplitApk
import dev.cluvex.strata.core.apk.SplitKind
import dev.cluvex.strata.core.apk.install.InstallFailure
import dev.cluvex.strata.core.apk.install.InstallResult
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SplitInstallDialog(
    path: String,
    policy: InstallPolicy,
    onView: () -> Unit,
    onDismiss: () -> Unit,
    vm: SplitInstallViewModel = viewModel(key = "split-install"),
) {
    LaunchedEffect(path) { vm.load(path) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.takeIf { it.path == path } ?: SplitInstallState(path = path)
    val context = LocalContext.current
    val busy = s.install is InstallPhase.Installing

    BasicAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.testTag("split-install")) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
                    Text(stringResource(R.string.apk_split_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        s.packageName ?: s.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    when {
                        s.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                        s.error != null -> Text(s.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                        else -> {
                            val info = s.info
                            if (info != null) {
                                val installed = s.installed
                                Text(
                                    if (installed != null) {
                                        stringResource(R.string.apk_split_version_installed, info.versionCode.toString(), installed.versionName, installed.versionCode.toString())
                                    } else {
                                        stringResource(R.string.apk_split_version, info.versionCode.toString())
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                stringResource(R.string.apk_split_explain),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
                            )
                            s.bundle?.apks?.forEach { split ->
                                SplitRow(split, checked = split.entry in s.selected, enabled = !busy && split.kind != SplitKind.Base) {
                                    vm.toggle(split.entry)
                                }
                            }
                            val obbs = s.bundle?.obbEntries?.size ?: 0
                            if (obbs > 0) {
                                Text(
                                    pluralStringResource(R.plurals.apk_split_obb_note, obbs, obbs),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                    }
                }
                if (busy) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(if ((s.install as InstallPhase.Installing).viaRoot) R.string.apk_installing_root else R.string.apk_waiting_installer),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (s.bundle != null) stringResource(R.string.apk_split_count, s.selected.size, s.bundle.apks.size) + separator() + Formatters.size(s.selectedSize) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onDismiss(); onView() }, shapes = ButtonDefaults.shapes(), enabled = !busy) { Text(stringResource(R.string.common_view)) }
                    Button(
                        onClick = { vm.install(policy) },
                        shapes = ButtonDefaults.shapes(),
                        enabled = !busy && s.bundle?.base != null && s.error == null,
                    ) { Text(stringResource(R.string.common_install)) }
                }
            }
        }
    }

    val phase = s.install
    if (phase is InstallPhase.Done) {
        val pkg = s.packageName
        when (val r = phase.result) {
            is InstallResult.Success -> ExpressiveDialog(
                onDismissRequest = vm::resetInstall,
                title = stringResource(R.string.apk_install_success_title),
                icon = Icons.Rounded.Check,
                shapes = HeroShapes.happy,
                sound = UiSound.COMPLETE,
                text = {
                    Text(
                        if (s.obbCopied > 0) {
                            stringResource(R.string.apk_split_installed_apks_obb, s.selected.size, s.obbCopied)
                        } else {
                            stringResource(R.string.apk_split_installed_apks, s.selected.size)
                        },
                    )
                },
                confirmButton = {
                    if (pkg != null) TextButton(onClick = { vm.resetInstall(); onDismiss(); AppIntents.launch(context, pkg) }) { Text(stringResource(R.string.apk_launch)) }
                },
                dismissButton = { TextButton(onClick = { vm.resetInstall(); onDismiss() }) { Text(stringResource(R.string.common_close)) } },
            )
            is InstallResult.Failure -> ExpressiveDialog(
                onDismissRequest = vm::resetInstall,
                title = stringResource(if (r.reason == InstallFailure.CANCELLED) R.string.apk_install_canceled_title else R.string.apk_install_failed_title),
                icon = if (r.reason == InstallFailure.CANCELLED) Icons.Rounded.Block else Icons.Rounded.ErrorOutline,
                tone = if (r.reason == InstallFailure.CANCELLED) ArtTone.SECONDARY else ArtTone.ERROR,
                shapes = HeroShapes.alert,
                sound = if (r.reason == InstallFailure.CANCELLED) null else UiSound.ERROR,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(r.reason.message))
                        if (r.detail.isNotBlank()) {
                            Text(r.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = vm::resetInstall) { Text(stringResource(R.string.common_close)) } },
                dismissButton = {
                    val conflict = r.reason == InstallFailure.SIGNATURE_CONFLICT || r.reason == InstallFailure.DOWNGRADE
                    if (conflict && s.installed != null && pkg != null) {
                        TextButton(onClick = { vm.resetInstall(); AppIntents.uninstall(context, pkg) }) { Text(stringResource(R.string.common_uninstall)) }
                    }
                },
            )
        }
    }
}

@Composable
private fun SplitRow(split: SplitApk, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val kind = when (val k = split.kind) {
        SplitKind.Base -> stringResource(R.string.apk_split_base)
        is SplitKind.Abi -> stringResource(R.string.apk_split_cpu, k.abi.replace('_', '-'))
        is SplitKind.Density -> stringResource(R.string.apk_split_screen, k.density)
        is SplitKind.Language -> stringResource(R.string.apk_split_language, k.language)
        is SplitKind.Feature -> stringResource(R.string.apk_split_feature)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(split.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$kind · ${Formatters.size(split.size.coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
