package dev.cluvex.strata.feature.vault

import android.content.Context
import androidx.fragment.app.FragmentActivity
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.vault.Vault
import dev.cluvex.strata.core.vault.VaultDamagedException
import dev.cluvex.strata.ui.security.AuthAvailability
import dev.cluvex.strata.ui.security.AuthRequest
import dev.cluvex.strata.ui.security.AuthResult
import dev.cluvex.strata.ui.security.DeviceAuth
import dev.cluvex.strata.ui.security.VaultKeystore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object VaultAccess {

    sealed interface Outcome {
        data object Done : Outcome
        data object UsePassword : Outcome
        data object Canceled : Outcome
        data class Error(val message: String) : Outcome
    }

    const val MIN_PASSWORD = 6

    fun biometricReady(context: Context): Boolean = DeviceAuth.current.cryptoAvailability(context) == AuthAvailability.READY

    suspend fun create(password: CharArray) = withContext(Dispatchers.IO) {
        try {
            Vault.store.create(password)
        } finally {
            password.fill(' ')
        }
    }

    suspend fun unlock(password: CharArray) = withContext(Dispatchers.IO) {
        try {
            Vault.store.unlock(password)
        } finally {
            password.fill(' ')
        }
    }

    suspend fun lock() = withContext(Dispatchers.IO) { Vault.store.lock() }

    fun unlockWithBiometric(activity: FragmentActivity, scope: CoroutineScope, onOutcome: (Outcome) -> Unit) {
        val store = Vault.store
        val wrap = store.biometricWrap() ?: return onOutcome(Outcome.UsePassword)
        val cipher = runCatching { VaultKeystore.current.decrypter(wrap.first) }.getOrNull()
        if (cipher == null) {
            store.clearBiometric()
            return onOutcome(Outcome.Error(tr(R.string.vault_biometric_changed)))
        }
        val request = AuthRequest(
            title = tr(R.string.vault_unlock_title),
            subtitle = tr(R.string.vault_unlock_biometric),
            deviceCredential = false,
            cipher = cipher,
            negative = tr(R.string.vault_use_password),
        )
        DeviceAuth.current.authenticate(activity, request) { result ->
            when (result) {
                is AuthResult.Success -> scope.launch {
                    val outcome = runCatching {
                        val key = (result.cipher ?: cipher).doFinal(wrap.second)
                        try {
                            withContext(Dispatchers.IO) { store.unlock(key) }
                        } finally {
                            key.fill(0)
                        }
                    }.fold(
                        { Outcome.Done },
                        { e ->
                            if (e is VaultDamagedException || e is javax.crypto.AEADBadTagException) {
                                store.clearBiometric()
                                VaultKeystore.current.delete()
                                Outcome.Error(tr(R.string.vault_biometric_changed))
                            } else {
                                Outcome.Error(e.message ?: e.javaClass.simpleName)
                            }
                        },
                    )
                    onOutcome(outcome)
                }
                AuthResult.UsePassword -> onOutcome(Outcome.UsePassword)
                AuthResult.Canceled -> onOutcome(Outcome.Canceled)
                is AuthResult.Failed -> onOutcome(Outcome.Error(result.message.ifBlank { tr(R.string.vault_biometric_failed) }))
            }
        }
    }

    fun enableBiometric(activity: FragmentActivity, onOutcome: (Outcome) -> Unit) {
        val store = Vault.store
        val cipher = runCatching { VaultKeystore.current.encrypter() }.getOrElse {
            return onOutcome(Outcome.Error(it.message ?: tr(R.string.vault_biometric_failed)))
        }
        val request = AuthRequest(
            title = tr(R.string.vault_biometric_enable_title),
            subtitle = tr(R.string.vault_biometric_enable_subtitle),
            deviceCredential = false,
            cipher = cipher,
            negative = tr(R.string.common_cancel),
        )
        DeviceAuth.current.authenticate(activity, request) { result ->
            when (result) {
                is AuthResult.Success -> {
                    val outcome = runCatching {
                        val c = result.cipher ?: cipher
                        val key = store.dataKey()
                        try {
                            val wrapped = c.doFinal(key)
                            store.setBiometric(c.iv, wrapped)
                        } finally {
                            key.fill(0)
                        }
                    }.fold({ Outcome.Done }, { Outcome.Error(it.message ?: it.javaClass.simpleName) })
                    if (outcome !is Outcome.Done) VaultKeystore.current.delete()
                    onOutcome(outcome)
                }
                AuthResult.UsePassword, AuthResult.Canceled -> {
                    VaultKeystore.current.delete()
                    onOutcome(Outcome.Canceled)
                }
                is AuthResult.Failed -> {
                    VaultKeystore.current.delete()
                    onOutcome(Outcome.Error(result.message.ifBlank { tr(R.string.vault_biometric_failed) }))
                }
            }
        }
    }

    fun disableBiometric() {
        Vault.store.clearBiometric()
        VaultKeystore.current.delete()
    }

    suspend fun destroy() = withContext(Dispatchers.IO) {
        Vault.store.destroy()
        VaultKeystore.current.delete()
    }
}
