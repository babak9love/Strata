package dev.cluvex.strata.core.vault

import dev.cluvex.strata.R
import dev.cluvex.strata.core.crypto.FileCipher
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.nativebridge.NativeBridge
import dev.cluvex.strata.core.remote.RemotePaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class VaultStore(
    val dir: File,
    private val kdf: Kdf = Kdf(),
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val chunkLog2: Int = VaultCipher.DEFAULT_CHUNK_LOG2,
) {

    data class Kdf(val memoryKib: Int = 64 * 1024, val passes: Int = 3, val lanes: Int = 1)

    enum class State { NONE, LOCKED, UNLOCKED }

    data class Summary(val files: Int, val folders: Int, val bytes: Long)

    private class Session(val key: ByteArray)

    private class Opened(val path: String, val stamp: Long, val length: Long)

    private val keyFile = File(dir, "vault.json")
    private val indexFile = File(dir, "index.bin")
    private val backupIndex = File(dir, "index.bin.bak")
    private val blobDir = File(dir, "blobs")
    private val json = Json { ignoreUnknownKeys = true }
    private val entries = TreeMap<String, VaultEntry>()
    private val lockListeners = CopyOnWriteArrayList<() -> Unit>()
    private val opened = ConcurrentHashMap<String, Opened>()

    @Volatile
    private var session: Session? = null

    private val _state = MutableStateFlow(if (keyFile.isFile) State.LOCKED else State.NONE)
    val state: StateFlow<State> = _state.asStateFlow()

    val exists: Boolean get() = keyFile.isFile

    val isUnlocked: Boolean get() = session != null

    val hasBiometric: Boolean get() = biometricWrap() != null

    fun onLock(listener: () -> Unit) {
        lockListeners += listener
    }

    fun create(password: CharArray) {
        require(password.isNotEmpty())
        synchronized(this) { if (exists) throw IOException(tr(R.string.vault_err_exists)) }
        val dataKey = ByteArray(KEY_SIZE).also(random::nextBytes)
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val wrapped = wrap(dataKey, password, salt, kdf)
        synchronized(this) {
            dir.mkdirs()
            blobDir.mkdirs()
            writeKeyFile(VaultKeyFile(memoryKib = kdf.memoryKib, passes = kdf.passes, lanes = kdf.lanes, salt = Hex.encode(salt), password = Hex.encode(wrapped)))
            entries.clear()
            session = Session(dataKey)
            saveIndex()
            _state.value = State.UNLOCKED
        }
    }

    fun unlock(password: CharArray) {
        val file = readKeyFile() ?: throw IOException(tr(R.string.vault_err_missing))
        val dataKey = unwrap(file, password) ?: throw WrongVaultPasswordException()
        open(dataKey)
    }

    fun unlock(dataKey: ByteArray) {
        open(dataKey.copyOf())
    }

    fun checkPassword(password: CharArray): Boolean {
        val file = readKeyFile() ?: return false
        val key = unwrap(file, password) ?: return false
        key.fill(0)
        return true
    }

    fun trackOpened(local: File, path: String) {
        opened[local.path] = Opened(RemotePaths.normalize(path), local.lastModified(), local.length())
    }

    fun saveOpened(): List<String> {
        val saved = ArrayList<String>()
        for ((local, o) in opened.entries.toList()) {
            val file = File(local)
            if (!file.isFile || (file.lastModified() == o.stamp && file.length() == o.length)) continue
            runCatching {
                file.inputStream().use { write(o.path, it) }
                trackOpened(file, o.path)
                saved += o.path
            }
        }
        return saved
    }

    fun lock() {
        if (session == null) return
        runCatching { saveOpened() }
        opened.clear()
        val s: Session
        synchronized(this) {
            s = session ?: return
            session = null
            entries.clear()
            _state.value = if (exists) State.LOCKED else State.NONE
        }
        s.key.fill(0)
        lockListeners.forEach { runCatching { it() } }
    }

    fun changePassword(old: CharArray, new: CharArray) {
        require(new.isNotEmpty())
        val file = readKeyFile() ?: throw IOException(tr(R.string.vault_err_missing))
        val dataKey = unwrap(file, old) ?: throw WrongVaultPasswordException()
        try {
            val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
            val wrapped = wrap(dataKey, new, salt, kdf)
            synchronized(this) {
                val now = readKeyFile() ?: throw IOException(tr(R.string.vault_err_missing))
                writeKeyFile(now.copy(memoryKib = kdf.memoryKib, passes = kdf.passes, lanes = kdf.lanes, salt = Hex.encode(salt), password = Hex.encode(wrapped)))
            }
        } finally {
            dataKey.fill(0)
        }
    }

    fun dataKey(): ByteArray = current().key.copyOf()

    fun biometricWrap(): Pair<ByteArray, ByteArray>? {
        val f = readKeyFile() ?: return null
        val iv = f.biometricIv ?: return null
        val key = f.biometricKey ?: return null
        return Hex.decode(iv) to Hex.decode(key)
    }

    @Synchronized
    fun setBiometric(iv: ByteArray, wrapped: ByteArray) {
        current()
        val f = readKeyFile() ?: throw IOException(tr(R.string.vault_err_missing))
        writeKeyFile(f.copy(biometricIv = Hex.encode(iv), biometricKey = Hex.encode(wrapped)))
    }

    @Synchronized
    fun clearBiometric() {
        val f = readKeyFile() ?: return
        if (f.biometricKey != null || f.biometricIv != null) writeKeyFile(f.copy(biometricIv = null, biometricKey = null))
    }

    fun destroy() {
        lock()
        synchronized(this) {
            FsUtil.deleteRecursively(dir)
            entries.clear()
            _state.value = State.NONE
        }
    }

    @Synchronized
    fun summary(): Summary {
        current()
        var files = 0
        var folders = 0
        var bytes = 0L
        for (e in entries.values) {
            if (e.dir) folders++ else {
                files++
                bytes += e.size
            }
        }
        return Summary(files, folders, bytes)
    }

    @Synchronized
    fun list(path: String): List<VaultEntry> {
        current()
        val p = RemotePaths.normalize(path)
        if (p != "/" && entries[p]?.dir != true) throw notFound(p)
        return children(p)
    }

    @Synchronized
    fun stat(path: String): VaultEntry? {
        current()
        val p = RemotePaths.normalize(path)
        return if (p == "/") VaultEntry("/", dir = true) else entries[p]
    }

    @Synchronized
    fun tree(path: String): List<VaultEntry> {
        current()
        val p = RemotePaths.normalize(path)
        if (p == "/") return entries.values.toList()
        val entry = entries[p] ?: throw notFound(p)
        return listOf(entry) + descendants(p)
    }

    fun openInput(path: String): InputStream {
        val p = RemotePaths.normalize(path)
        val (s, entry) = synchronized(this) { current() to (entries[p] ?: throw notFound(p)) }
        val blob = entry.blob
        if (entry.dir || blob == null) throw IOException(tr(R.string.vault_err_is_folder, RemotePaths.name(p)))
        val key = s.key.copyOf()
        val raw = try {
            File(blobDir, blob).inputStream()
        } catch (e: FileNotFoundException) {
            key.fill(0)
            throw VaultDamagedException()
        }
        val stream = try {
            VaultCipher.DecryptingStream(raw, { key }, Hex.decode(blob), { session === s })
        } catch (e: Throwable) {
            key.fill(0)
            raw.close()
            throw e
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    key.fill(0)
                }
            }
        }
    }

    fun read(path: String, into: OutputStream, onBytes: (Long) -> Unit = {}): Long {
        openInput(path).use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                into.write(buf, 0, n)
                total += n
                onBytes(total)
            }
            buf.fill(0)
            return total
        }
    }

    fun write(path: String, input: InputStream, origin: String? = null, modified: Long? = null, onBytes: (Long) -> Unit = {}) {
        val p = RemotePaths.normalize(path)
        if (p == "/") throw IOException(tr(R.string.vault_err_is_folder, "/"))
        val s = synchronized(this) {
            val s = current()
            checkParent(p)
            if (entries[p]?.dir == true) throw IOException(tr(R.string.vault_err_is_folder, RemotePaths.name(p)))
            s
        }
        val id = ByteArray(VaultCipher.ID_SIZE).also(random::nextBytes)
        val blob = Hex.encode(id)
        blobDir.mkdirs()
        val part = File(blobDir, "$blob.part")
        val key = s.key.copyOf()
        val size = try {
            FileOutputStream(part).use { fos ->
                val out = fos.buffered(64 * 1024)
                val n = VaultCipher.encrypt(input, out, key, id, random, chunkLog2, alive = { session === s }, onBytes = onBytes)
                out.flush()
                fos.fd.sync()
                n
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            key.fill(0)
        }
        val target = File(blobDir, blob)
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException(tr(R.string.vault_err_write, RemotePaths.name(p)))
        }
        val replaced = synchronized(this) {
            try {
                if (session !== s) throw VaultLockedException()
                checkParent(p)
                val old = entries[p]
                if (old?.dir == true) throw IOException(tr(R.string.vault_err_is_folder, RemotePaths.name(p)))
                val now = clock()
                entries[p] = VaultEntry(p, dir = false, blob = blob, size = size, modified = modified ?: now, added = old?.added ?: now, origin = origin ?: old?.origin)
                try {
                    saveIndex()
                } catch (e: Throwable) {
                    if (old != null) entries[p] = old else entries.remove(p)
                    throw e
                }
                old?.blob
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
        }
        replaced?.let { File(blobDir, it).delete() }
    }

    @Synchronized
    fun mkdir(path: String) {
        current()
        val p = RemotePaths.normalize(path)
        if (p == "/") throw IOException(tr(R.string.common_already_exists, "/"))
        checkParent(p)
        if (entries.containsKey(p)) throw IOException(tr(R.string.common_already_exists, RemotePaths.name(p)))
        val now = clock()
        entries[p] = VaultEntry(p, dir = true, modified = now, added = now)
        try {
            saveIndex()
        } catch (e: Throwable) {
            entries.remove(p)
            throw e
        }
    }

    fun delete(path: String, recursive: Boolean) {
        val removed = synchronized(this) {
            current()
            val p = RemotePaths.normalize(path)
            if (p == "/") throw IOException(tr(R.string.vault_err_root))
            val entry = entries[p] ?: throw notFound(p)
            val inside = descendants(p)
            if (entry.dir && inside.isNotEmpty() && !recursive) throw IOException(tr(R.string.vault_err_not_empty, RemotePaths.name(p)))
            val all = listOf(entry) + inside
            all.forEach { entries.remove(it.path) }
            try {
                saveIndex()
            } catch (e: Throwable) {
                all.forEach { entries[it.path] = it }
                throw e
            }
            all
        }
        removed.mapNotNull { it.blob }.forEach { File(blobDir, it).delete() }
    }

    @Synchronized
    fun rename(from: String, to: String) {
        current()
        val a = RemotePaths.normalize(from)
        val b = RemotePaths.normalize(to)
        if (a == "/" || b == "/") throw IOException(tr(R.string.vault_err_root))
        if (a == b) return
        val entry = entries[a] ?: throw notFound(a)
        if (entries.containsKey(b)) throw IOException(tr(R.string.common_already_exists, RemotePaths.name(b)))
        if (entry.dir && b.startsWith("$a/")) throw IOException(tr(R.string.vault_err_into_itself, RemotePaths.name(a)))
        checkParent(b)
        val moved = listOf(entry) + descendants(a)
        moved.forEach { entries.remove(it.path) }
        moved.forEach { e ->
            val np = b + e.path.substring(a.length)
            entries[np] = e.copy(path = np)
        }
        try {
            saveIndex()
        } catch (e: Throwable) {
            moved.forEach { m -> entries.remove(b + m.path.substring(a.length)) }
            moved.forEach { entries[it.path] = it }
            throw e
        }
    }

    private fun current(): Session = session ?: throw VaultLockedException()

    private fun notFound(path: String) = FileNotFoundException(tr(R.string.vault_err_not_found, RemotePaths.name(path).ifEmpty { "/" }))

    private fun checkParent(p: String) {
        val parent = RemotePaths.parent(p)
        if (parent != "/" && entries[parent]?.dir != true) throw IOException(tr(R.string.vault_err_no_folder, RemotePaths.name(parent)))
    }

    private fun children(p: String): List<VaultEntry> {
        val prefix = if (p == "/") "/" else "$p/"
        return entries.subMap(prefix, prefix + '\uFFFF').values.filter { it.path.indexOf('/', prefix.length) < 0 && it.path.length > prefix.length }
    }

    private fun descendants(p: String): List<VaultEntry> {
        val prefix = "$p/"
        return entries.subMap(prefix, prefix + '\uFFFF').values.toList()
    }

    private fun derive(password: CharArray, salt: ByteArray, k: Kdf): ByteArray {
        val bytes = FileCipher.passwordBytes(password)
        try {
            return NativeBridge.deriveKey(bytes, salt, k.memoryKib, k.passes, k.lanes) ?: throw IOException(tr(R.string.vault_err_unavailable))
        } finally {
            bytes.fill(0)
        }
    }

    private fun wrap(dataKey: ByteArray, password: CharArray, salt: ByteArray, k: Kdf): ByteArray {
        val wrapping = derive(password, salt, k)
        try {
            return VaultCipher.seal(wrapping, dataKey, KEY_AAD, random)
        } finally {
            wrapping.fill(0)
        }
    }

    private fun unwrap(file: VaultKeyFile, password: CharArray): ByteArray? {
        val sane = file.memoryKib in 8 * file.lanes..FileCipher.MAX_MEMORY_KIB && file.passes in 1..FileCipher.MAX_PASSES && file.lanes in 1..FileCipher.MAX_LANES
        if (!sane) throw VaultDamagedException()
        val wrapping = derive(password, Hex.decode(file.salt), Kdf(file.memoryKib, file.passes, file.lanes))
        try {
            return VaultCipher.open(wrapping, Hex.decode(file.password), KEY_AAD)
        } finally {
            wrapping.fill(0)
        }
    }

    private fun open(dataKey: ByteArray) {
        val fresh = synchronized(this) {
            val (loaded, fromFile) = try {
                loadIndex(dataKey)
            } catch (e: Throwable) {
                dataKey.fill(0)
                throw e
            }
            session?.key?.fill(0)
            entries.clear()
            loaded.forEach { entries[it.path] = it }
            session = Session(dataKey)
            _state.value = State.UNLOCKED
            fromFile
        }
        if (fresh) collectGarbage()
    }

    private fun loadIndex(key: ByteArray): Pair<List<VaultEntry>, Boolean> {
        if (!indexFile.isFile && !backupIndex.isFile) return emptyList<VaultEntry>() to false
        for (file in listOf(indexFile, backupIndex)) {
            if (!file.isFile) continue
            val bytes = file.readBytes()
            if (bytes.size < INDEX_HEADER.size || !INDEX_HEADER.indices.all { bytes[it] == INDEX_HEADER[it] }) continue
            val plain = VaultCipher.open(key, bytes.copyOfRange(INDEX_HEADER.size, bytes.size), INDEX_HEADER) ?: continue
            val parsed = runCatching { json.decodeFromString<VaultIndexFile>(String(plain, Charsets.UTF_8)) }.getOrNull()
            plain.fill(0)
            if (parsed != null) return parsed.entries to (file == indexFile)
        }
        throw VaultDamagedException()
    }

    private fun saveIndex() {
        val s = current()
        val plain = json.encodeToString(VaultIndexFile(entries = entries.values.toList())).toByteArray(Charsets.UTF_8)
        val sealed = try {
            VaultCipher.seal(s.key, plain, INDEX_HEADER, random)
        } finally {
            plain.fill(0)
        }
        dir.mkdirs()
        val tmp = File(dir, "index.bin.tmp")
        tmp.outputStream().use { out ->
            out.write(INDEX_HEADER)
            out.write(sealed)
            out.flush()
            out.fd.sync()
        }
        if (indexFile.exists()) {
            backupIndex.delete()
            indexFile.renameTo(backupIndex)
        }
        if (!tmp.renameTo(indexFile)) throw IOException(tr(R.string.vault_err_write, indexFile.name))
    }

    private fun collectGarbage() {
        val referenced = synchronized(this) { entries.values.mapNotNullTo(HashSet()) { it.blob } }
        blobDir.listFiles()?.forEach { f ->
            val live = Hex.isBlobName(f.name) && f.name in referenced
            if (!live && session != null) f.delete()
        }
    }

    private fun readKeyFile(): VaultKeyFile? =
        if (!keyFile.isFile) null else runCatching { json.decodeFromString<VaultKeyFile>(keyFile.readText()) }.getOrElse { throw VaultDamagedException() }

    private fun writeKeyFile(file: VaultKeyFile) {
        dir.mkdirs()
        val tmp = File(dir, "vault.json.tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(file).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(keyFile)) throw IOException(tr(R.string.vault_err_write, keyFile.name))
        if (_state.value == State.NONE) _state.value = if (session != null) State.UNLOCKED else State.LOCKED
    }

    private companion object {
        const val KEY_SIZE = 32
        const val SALT_SIZE = 16
        val KEY_AAD = "strata-vault-key-v1".toByteArray(Charsets.US_ASCII)
        val INDEX_HEADER = "STRAVIDX\u0001".toByteArray(Charsets.US_ASCII)
    }
}
