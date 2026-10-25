package dev.cluvex.strata.core.apk

import com.android.apksig.ApkVerifier
import java.io.File

object ApkSignatureInspector {
    fun inspect(apk: File): ApkSignature {
        return try {
            val result = ApkVerifier.Builder(apk).build().verify()
            val certs = result.signerCertificates.map { it.toApkCertificate() }
            ApkSignature(
                verified = result.isVerified,
                v1 = result.isVerifiedUsingV1Scheme,
                v2 = result.isVerifiedUsingV2Scheme,
                v3 = result.isVerifiedUsingV3Scheme,
                v31 = result.isVerifiedUsingV31Scheme,
                v4 = result.isVerifiedUsingV4Scheme,
                certificates = certs,
                errors = result.errors.map { it.toString() },
                warnings = result.warnings.map { it.toString() },
            )
        } catch (e: Exception) {
            ApkSignature.failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
