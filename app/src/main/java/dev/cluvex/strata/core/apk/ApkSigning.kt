package dev.cluvex.strata.core.apk

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildListener
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import java.io.IOException

data class SignOptions(
    val v1: Boolean = true,
    val v2: Boolean = true,
    val v3: Boolean = true,
    val v4: Boolean = false,
    val minSdkVersion: Int? = null,
)

object ApkSigning {
    val STEP_SIGN: String get() = tr(R.string.apk_step_sign)
    val STEP_VERIFY: String get() = tr(R.string.apk_step_verify)

    fun sign(
        input: File,
        output: File,
        key: SigningKey,
        options: SignOptions = SignOptions(),
        createdBy: String = "Android",
        log: BuildListener = BuildListener.NONE,
        verify: Boolean = log !== BuildListener.NONE,
    ) {
        require(input.isFile) { tr(R.string.apk_error_not_a_file, input.name) }
        require(input.canonicalFile != output.canonicalFile) { "The output must be a different file" }
        log.step(STEP_SIGN)
        log.info("${input.name} · ${BuildRun.size(input.length())}")
        key.certificates.firstOrNull()?.let { cert ->
            log.info("Key ${cert.subjectX500Principal.name}")
            log.info("SHA-256 " + Hashing.hex(cert.encoded, "SHA-256").take(23) + "…")
        }
        log.info("Schemes " + listOfNotNull("v1".takeIf { options.v1 }, "v2".takeIf { options.v2 }, "v3".takeIf { options.v3 }, "v4".takeIf { options.v4 }).joinToString(" + ") + " · 4-byte aligned")

        val signerConfig = ApkSigner.SignerConfig.Builder("CERT", KeyConfig.Jca(key.privateKey), key.certificates).build()
        val builder = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(options.v1)
            .setV2SigningEnabled(options.v2)
            .setV3SigningEnabled(options.v3)
            .setV4SigningEnabled(options.v4)
            .setAlignFileSize(true)
            .setCreatedBy(createdBy)
        options.minSdkVersion?.let { builder.setMinSdkVersion(it) }

        try {
            builder.build().sign()
        } catch (e: Throwable) {
            runCatching { FsUtil.deleteRecursively(output) }
            throw e
        }
        log.info("${output.name} · ${BuildRun.size(output.length())}")
        if (!verify) return
        log.step(STEP_VERIFY)
        val signature = ApkSignatureInspector.inspect(output)
        signature.warnings.take(10).forEach { log.warn(it) }
        if (!signature.verified) {
            throw IOException(tr(R.string.apk_error_sign_verify, signature.errors.firstOrNull() ?: signature.error ?: tr(R.string.apk_error_unknown_reason)))
        }
        log.info("Verified with " + signature.schemes.joinToString(" + "))
    }
}
