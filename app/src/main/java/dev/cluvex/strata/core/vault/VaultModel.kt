package dev.cluvex.strata.core.vault

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
data class VaultEntry(
    val path: String,
    val dir: Boolean = false,
    val blob: String? = null,
    val size: Long = 0,
    val modified: Long = 0,
    val added: Long = 0,
    val origin: String? = null,
)

@Serializable
internal data class VaultIndexFile(val version: Int = 1, val entries: List<VaultEntry> = emptyList())

@Serializable
internal data class VaultKeyFile(
    val version: Int = 1,
    val memoryKib: Int,
    val passes: Int,
    val lanes: Int,
    val salt: String,
    val password: String,
    val biometricIv: String? = null,
    val biometricKey: String? = null,
)

class VaultLockedException : IOException(tr(R.string.vault_err_locked))

class WrongVaultPasswordException : IOException(tr(R.string.vault_err_wrong_password))

class VaultDamagedException : IOException(tr(R.string.vault_err_damaged))

internal object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(DIGITS[v ushr 4]).append(DIGITS[v and 0xF])
        }
    }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "Odd hex length" }
        return ByteArray(text.length / 2) { i ->
            ((Character.digit(text[2 * i], 16) shl 4) or Character.digit(text[2 * i + 1], 16)).toByte()
        }
    }

    fun isBlobName(name: String): Boolean = name.length == VaultCipher.ID_SIZE * 2 && name.all { it in '0'..'9' || it in 'a'..'f' }
}
