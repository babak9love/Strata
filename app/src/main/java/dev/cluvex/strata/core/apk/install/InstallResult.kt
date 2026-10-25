package dev.cluvex.strata.core.apk.install

import androidx.annotation.StringRes
import dev.cluvex.strata.R

enum class InstallFailure(@StringRes val message: Int) {
    DOWNGRADE(R.string.apk_fail_downgrade),
    SIGNATURE_CONFLICT(R.string.apk_fail_signature),
    CONFLICT(R.string.apk_fail_conflict),
    INCOMPATIBLE_SDK(R.string.apk_fail_sdk),
    OLD_TARGET_SDK(R.string.apk_fail_old_target),
    INCOMPATIBLE_ABI(R.string.apk_fail_abi),
    NOT_SIGNED(R.string.apk_fail_not_signed),
    TEST_ONLY(R.string.apk_fail_test_only),
    INVALID(R.string.apk_fail_invalid),
    STORAGE(R.string.apk_fail_storage),
    BLOCKED(R.string.apk_fail_blocked),
    CANCELLED(R.string.apk_fail_cancelled),
    TIMEOUT(R.string.apk_fail_timeout),
    OTHER(R.string.apk_fail_other),
}

sealed interface InstallResult {
    data class Success(val packageName: String?) : InstallResult
    data class Failure(val reason: InstallFailure, val detail: String = "") : InstallResult
}

object InstallStatus {
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8

    fun fromSession(status: Int, message: String?, packageName: String?): InstallResult {
        if (status == STATUS_SUCCESS) return InstallResult.Success(packageName)
        val detail = message.orEmpty()
        classify(detail)?.let { return InstallResult.Failure(it, detail) }
        val reason = when (status) {
            STATUS_FAILURE_ABORTED -> InstallFailure.CANCELLED
            STATUS_FAILURE_BLOCKED -> InstallFailure.BLOCKED
            STATUS_FAILURE_INVALID -> InstallFailure.INVALID
            STATUS_FAILURE_CONFLICT -> InstallFailure.CONFLICT
            STATUS_FAILURE_STORAGE -> InstallFailure.STORAGE
            STATUS_FAILURE_INCOMPATIBLE -> InstallFailure.INCOMPATIBLE_SDK
            STATUS_FAILURE_TIMEOUT -> InstallFailure.TIMEOUT
            else -> InstallFailure.OTHER
        }
        return InstallResult.Failure(reason, detail)
    }

    fun fromPm(output: String, packageName: String?): InstallResult {
        if (output.lineSequence().any { it.trim().startsWith("Success") }) return InstallResult.Success(packageName)
        val detail = output.trim()
        return InstallResult.Failure(classify(detail) ?: InstallFailure.OTHER, detail)
    }

    fun classify(text: String): InstallFailure? {
        val code = CODE.find(text)?.value ?: return null
        return when (code) {
            "INSTALL_FAILED_VERSION_DOWNGRADE" -> InstallFailure.DOWNGRADE
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES",
            "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
            -> InstallFailure.SIGNATURE_CONFLICT
            "INSTALL_FAILED_OLDER_SDK", "INSTALL_FAILED_NEWER_SDK" -> InstallFailure.INCOMPATIBLE_SDK
            "INSTALL_FAILED_DEPRECATED_SDK_VERSION" -> InstallFailure.OLD_TARGET_SDK
            "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> InstallFailure.INCOMPATIBLE_ABI
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> InstallFailure.NOT_SIGNED
            "INSTALL_FAILED_TEST_ONLY" -> InstallFailure.TEST_ONLY
            "INSTALL_FAILED_INSUFFICIENT_STORAGE", "INSTALL_FAILED_MEDIA_UNAVAILABLE" -> InstallFailure.STORAGE
            "INSTALL_FAILED_ALREADY_EXISTS", "INSTALL_FAILED_DUPLICATE_PERMISSION",
            "INSTALL_FAILED_CONFLICTING_PROVIDER", "INSTALL_FAILED_DUPLICATE_PACKAGE",
            -> InstallFailure.CONFLICT
            "INSTALL_FAILED_ABORTED" -> InstallFailure.CANCELLED
            "INSTALL_FAILED_USER_RESTRICTED", "INSTALL_FAILED_VERIFICATION_FAILURE" -> InstallFailure.BLOCKED
            "INSTALL_FAILED_VERIFICATION_TIMEOUT" -> InstallFailure.TIMEOUT
            else -> if (code.startsWith("INSTALL_PARSE_FAILED") || code == "INSTALL_FAILED_INVALID_APK") {
                InstallFailure.INVALID
            } else {
                null
            }
        }
    }

    private val CODE = Regex("INSTALL_(?:PARSE_)?FAILED_[A-Z_]+")
}
