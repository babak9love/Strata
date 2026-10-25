package dev.cluvex.strata.feature.settings

import android.content.res.Resources
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.core.data.StrataSettings
import dev.cluvex.strata.core.i18n.AppLanguage
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.model.DateStyle
import dev.cluvex.strata.core.model.LockDelay
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.core.vault.Vault
import dev.cluvex.strata.core.vault.VaultStore
import dev.cluvex.strata.feature.vault.VaultAccess
import dev.cluvex.strata.feature.vault.VaultDeleteDialog
import dev.cluvex.strata.feature.vault.VaultPasswordDialog
import dev.cluvex.strata.feature.vault.VaultSetupDialog
import dev.cluvex.strata.feature.vault.VaultUnlockDialog
import dev.cluvex.strata.ui.i18n.AppLocales
import dev.cluvex.strata.ui.security.AppLock
import dev.cluvex.strata.ui.security.AuthAvailability
import dev.cluvex.strata.ui.security.AuthRequest
import dev.cluvex.strata.ui.security.AuthResult
import dev.cluvex.strata.ui.security.DeviceAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class VaultDialog { SETUP, UNLOCK_THEN_BIOMETRIC, PASSWORD, DELETE }

@Composable
fun SecurityPage(settings: StrataSettings, act: SettingsActions, onOpenVault: () -> Unit, onBack: () -> Unit) {
    val s = settings.security
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val ignored = remember { SecureScreen.ignoredBySystem(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val vaultState by Vault.store.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<VaultDialog?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val biometricReady = remember { VaultAccess.biometricReady(context) }
    val vaultBiometric = remember(vaultState, refresh) { runCatching { Vault.store.hasBiometric }.getOrDefault(false) }
    val summary by produceState<VaultStore.Summary?>(null, vaultState, refresh) {
        value = if (vaultState == VaultStore.State.UNLOCKED) withContext(Dispatchers.IO) { runCatching { Vault.store.summary() }.getOrNull() } else null
    }

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun setAppLock(on: Boolean) {
        if (!on) {
            act.security { it.copy(appLock = false) }
            return
        }
        when (DeviceAuth.current.lockAvailability(context)) {
            AuthAvailability.READY -> {
                val a = activity ?: return
                AppLock.authStarted()
                DeviceAuth.current.authenticate(a, AuthRequest(title = tr(R.string.lock_enable_title), subtitle = tr(R.string.lock_prompt_subtitle), deviceCredential = true)) { r ->
                    AppLock.authFinished(r is AuthResult.Success)
                    when (r) {
                        is AuthResult.Success -> {
                            AppLock.turnedOn()
                            act.security { it.copy(appLock = true) }
                            UiSounds.play(UiSound.LOCK)
                        }
                        is AuthResult.Failed -> say(r.message.ifBlank { tr(R.string.lock_failed) })
                        else -> Unit
                    }
                }
            }
            AuthAvailability.NOT_ENROLLED -> say(tr(R.string.lock_need_screen_lock))
            AuthAvailability.UNAVAILABLE -> say(tr(R.string.lock_unavailable_device))
        }
    }

    fun enableVaultBiometric() {
        val a = activity ?: return
        VaultAccess.enableBiometric(a) { outcome ->
            refresh++
            when (outcome) {
                VaultAccess.Outcome.Done -> say(tr(R.string.vault_biometric_on))
                is VaultAccess.Outcome.Error -> say(outcome.message)
                else -> Unit
            }
        }
    }

    fun setVaultBiometric(on: Boolean) {
        if (!on) {
            VaultAccess.disableBiometric()
            refresh++
            return
        }
        if (Vault.store.isUnlocked) enableVaultBiometric() else dialog = VaultDialog.UNLOCK_THEN_BIOMETRIC
    }

    SettingsPageScaffold(stringResource(R.string.security_title), onBack, subtitle = stringResource(R.string.security_subtitle), snackbar = snackbar) {
        SettingsGroup(
            tr(R.string.lock_group),
            footer = if (Build.VERSION.SDK_INT >= 33) tr(R.string.lock_footer) else null,
        ) {
            switch(
                tr(R.string.lock_switch),
                s.appLock,
                ::setAppLock,
                summary = tr(R.string.lock_switch_summary),
                icon = Icons.Rounded.Fingerprint,
                tint = Tint.PRIMARY,
            )
            pick(
                tr(R.string.lock_after),
                LockDelay.entries,
                s.lockDelay,
                { tr(it.label) },
                { d -> act.security { it.copy(lockDelay = d) } },
                icon = Icons.Rounded.Timer,
                enabled = s.appLock,
            )
            link(tr(R.string.lock_now), icon = Icons.Rounded.Lock, enabled = s.appLock, chevron = false) { AppLock.lockNow() }
        }
        SettingsGroup(tr(R.string.vault_group), footer = tr(R.string.vault_footer)) {
            if (vaultState == VaultStore.State.NONE) {
                link(tr(R.string.vault_setup_link), summary = tr(R.string.vault_setup_summary), icon = Icons.Rounded.FolderSpecial, tint = Tint.TERTIARY) {
                    dialog = VaultDialog.SETUP
                }
            } else {
                val status = when {
                    vaultState == VaultStore.State.LOCKED -> tr(R.string.vault_status_locked)
                    summary != null -> tr(
                        R.string.vault_status_unlocked,
                        trPlural(R.plurals.common_files, summary!!.files, summary!!.files),
                        Formatters.size(summary!!.bytes),
                    )
                    else -> tr(R.string.vault_status_open)
                }
                link(tr(R.string.vault_open), summary = status, icon = Icons.Rounded.FolderSpecial, tint = Tint.TERTIARY, onClick = onOpenVault)
                switch(
                    tr(R.string.vault_use_biometric),
                    vaultBiometric,
                    ::setVaultBiometric,
                    summary = tr(if (biometricReady) R.string.vault_biometric_summary else R.string.vault_biometric_unavailable),
                    icon = Icons.Rounded.Fingerprint,
                    enabled = biometricReady || vaultBiometric,
                )
                pick(
                    tr(R.string.vault_lock_after),
                    LockDelay.entries,
                    s.vaultLockDelay,
                    { tr(it.label) },
                    { d -> act.security { it.copy(vaultLockDelay = d) } },
                    icon = Icons.Rounded.LockClock,
                )
                link(tr(R.string.vault_lock_now), icon = Icons.Rounded.LockOpen, enabled = vaultState == VaultStore.State.UNLOCKED, chevron = false) {
                    scope.launch {
                        VaultAccess.lock()
                        UiSounds.play(UiSound.LOCK)
                    }
                }
                link(tr(R.string.vault_change_password), icon = Icons.Rounded.Key) { dialog = VaultDialog.PASSWORD }
                link(tr(R.string.vault_delete), icon = Icons.Rounded.DeleteForever, tint = Tint.ERROR) { dialog = VaultDialog.DELETE }
            }
        }
        SettingsGroup(
            tr(R.string.security_screen_group),
            footer = if (ignored) tr(R.string.security_secure_ignored) else null,
        ) {
            switch(
                tr(R.string.security_secure_screen),
                s.secureScreen,
                { v -> act.security { it.copy(secureScreen = v) } },
                summary = tr(R.string.security_secure_screen_summary),
                icon = Icons.Rounded.ScreenLockPortrait,
            )
        }
    }

    when (dialog) {
        VaultDialog.SETUP -> VaultSetupDialog(onDismiss = { dialog = null }) {
            dialog = null
            refresh++
            say(tr(R.string.vault_ready))
        }
        VaultDialog.UNLOCK_THEN_BIOMETRIC -> VaultUnlockDialog(onDismiss = { dialog = null }) {
            dialog = null
            enableVaultBiometric()
        }
        VaultDialog.PASSWORD -> VaultPasswordDialog(onDismiss = { dialog = null }) {
            dialog = null
            say(tr(R.string.vault_password_changed))
        }
        VaultDialog.DELETE -> VaultDeleteDialog(onDismiss = { dialog = null }) {
            dialog = null
            refresh++
            say(tr(R.string.vault_deleted))
        }
        null -> Unit
    }
}

object SecureScreen {
    fun ignoredBySystem(context: android.content.Context): Boolean =
        runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "window_ignore_secure", 0) == 1 }.getOrDefault(false)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LanguagePage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val current = remember { AppLocales.current(context) }
    val system = remember { Resources.getSystem().configuration.locales[0] }
    val sample = remember {
        Formatters.listDate(System.currentTimeMillis(), DateStyle.LONG, true, locale = Locale.forLanguageTag("fa"), solarHijri = true)
    }
    val options = listOf(AppLanguage.SYSTEM) + AppLanguage.choices
    SettingsPageScaffold(stringResource(R.string.language_title), onBack, subtitle = stringResource(R.string.language_subtitle)) {
        SettingsGroup(tr(R.string.language_group), footer = if (Build.VERSION.SDK_INT >= 33) tr(R.string.language_system_note) else null) {
            options.forEach { language ->
                item { shapes ->
                    val title = stringResource(language.nativeName)
                    val detail = when (language) {
                        AppLanguage.SYSTEM -> system.getDisplayName(system).replaceFirstChar { it.titlecase(system) }
                        AppLanguage.ENGLISH -> stringResource(R.string.language_english)
                        AppLanguage.PERSIAN -> stringResource(R.string.language_persian)
                        AppLanguage.RUSSIAN -> stringResource(R.string.language_russian)
                        AppLanguage.CHINESE -> stringResource(R.string.language_chinese)
                    }
                    SegmentedListItem(
                        onClick = { if (language != current) activity?.let { AppLocales.set(it, language) } },
                        shapes = shapes,
                        colors = settingColors(),
                        modifier = Modifier.testTag("language-${language.name.lowercase()}"),
                        leadingContent = { RadioButton(selected = language == current, onClick = null) },
                        supportingContent = { Text(detail) },
                    ) { Text(title) }
                }
            }
        }
        SettingsGroup(tr(R.string.language_calendar_group)) {
            switch(
                tr(R.string.language_persian_calendar),
                settings.list.persianCalendar,
                { v -> act.list { it.copy(persianCalendar = v) } },
                summary = tr(R.string.language_persian_calendar_summary, sample),
                icon = Icons.Rounded.CalendarMonth,
            )
        }
        Column { SettingNote(stringResource(R.string.language_help)) }
    }
}
