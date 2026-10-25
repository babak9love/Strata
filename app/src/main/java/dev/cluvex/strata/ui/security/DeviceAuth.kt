package dev.cluvex.strata.ui.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

enum class AuthAvailability { READY, NOT_ENROLLED, UNAVAILABLE }

class AuthRequest(
    val title: String,
    val subtitle: String? = null,
    val deviceCredential: Boolean,
    val cipher: Cipher? = null,
    val negative: String? = null,
)

sealed interface AuthResult {
    data class Success(val cipher: Cipher?) : AuthResult
    data object Canceled : AuthResult
    data object UsePassword : AuthResult
    data class Failed(val message: String, val lockedOut: Boolean = false) : AuthResult
}

interface Authenticator {
    fun lockAvailability(context: Context): AuthAvailability
    fun cryptoAvailability(context: Context): AuthAvailability
    fun authenticate(activity: FragmentActivity, request: AuthRequest, onResult: (AuthResult) -> Unit)
    fun cancel() {}
}

object DeviceAuth {

    @Volatile
    var override: Authenticator? = null

    val current: Authenticator get() = override ?: Biometrics

    fun attach(activity: FragmentActivity) = Biometrics.attach(activity)
}

object Biometrics : Authenticator {

    private var prompt: BiometricPrompt? = null
    private var pending: ((AuthResult) -> Unit)? = null

    private const val LOCK_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun attach(activity: FragmentActivity) {
        prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                finish(AuthResult.Success(result.cryptoObject?.cipher))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finish(
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> AuthResult.UsePassword
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> AuthResult.Canceled
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthResult.Failed(errString.toString(), lockedOut = true)
                        else -> AuthResult.Failed(errString.toString())
                    },
                )
            }
        })
    }

    private fun finish(result: AuthResult) {
        val callback = pending
        pending = null
        callback?.invoke(result)
    }

    private fun availability(context: Context, authenticators: Int): AuthAvailability =
        when (BiometricManager.from(context).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NOT_ENROLLED
            else -> AuthAvailability.UNAVAILABLE
        }

    override fun lockAvailability(context: Context): AuthAvailability = availability(context, LOCK_AUTHENTICATORS)

    override fun cryptoAvailability(context: Context): AuthAvailability =
        if (Build.VERSION.SDK_INT < 28) AuthAvailability.UNAVAILABLE else availability(context, BIOMETRIC_STRONG)

    override fun authenticate(activity: FragmentActivity, request: AuthRequest, onResult: (AuthResult) -> Unit) {
        val p = prompt ?: return onResult(AuthResult.Failed(""))
        pending = onResult
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(request.title)
            .apply { request.subtitle?.let { setSubtitle(it) } }
            .setConfirmationRequired(false)
            .apply {
                if (request.deviceCredential) {
                    setAllowedAuthenticators(LOCK_AUTHENTICATORS)
                } else {
                    setAllowedAuthenticators(BIOMETRIC_STRONG)
                    setNegativeButtonText(request.negative ?: activity.getString(android.R.string.cancel))
                }
            }
            .build()
        runCatching {
            val cipher = request.cipher
            if (cipher != null) p.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else p.authenticate(info)
        }.onFailure { finish(AuthResult.Failed(it.message ?: it.javaClass.simpleName)) }
    }

    override fun cancel() {
        runCatching { prompt?.cancelAuthentication() }
    }
}
