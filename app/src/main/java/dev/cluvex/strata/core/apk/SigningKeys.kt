package dev.cluvex.strata.core.apk

import java.io.File
import java.util.concurrent.ConcurrentHashMap

object SigningKeys {
    fun default(filesDir: File): SigningKey {
        val ksDir = File(filesDir, "keystores").apply { mkdirs() }
        val ksFile = File(ksDir, "default.p12")
        val pw = "android".toCharArray()
        val spec = KeystoreSpec(ksFile, pw, "default", pw)
        return if (ksFile.isFile) cached(ksFile) { KeyStoreFactory.load(spec) }
        else KeyStoreFactory.generate(spec, KeyIdentity(commonName = "Android")).also { remember(ksFile, it) }
    }

    private val opened = ConcurrentHashMap<String, Pair<String, SigningKey>>()

    private fun stamp(file: File) = "${file.length()}:${file.lastModified()}"

    fun cached(file: File, load: () -> SigningKey): SigningKey {
        opened[file.absolutePath]?.let { (stamp, key) -> if (stamp == stamp(file)) return key }
        return load().also { remember(file, it) }
    }

    fun remember(file: File, key: SigningKey) {
        opened[file.absolutePath] = stamp(file) to key
    }

    fun forget(file: File) {
        opened.remove(file.absolutePath)
    }
}
