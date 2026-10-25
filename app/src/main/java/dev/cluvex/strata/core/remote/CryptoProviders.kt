package dev.cluvex.strata.core.remote

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

object CryptoProviders {

    @Synchronized
    fun ensureBouncyCastle() {
        val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (current is BouncyCastleProvider) return
        if (current != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
