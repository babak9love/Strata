package dev.cluvex.strata.core.archive

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.nativebridge.NativeBridge
import dev.cluvex.strata.core.nativebridge.RarSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.StrataAesDecoder
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jInputStream

data class ArchiveSelection(val paths: Set<String>, val base: String = "") {
    private val clean = paths.map(ArchiveTree::normalize).filter { it.isNotEmpty() }
    private val cleanBase = ArchiveTree.normalize(base)

    fun target(entryName: String): String? {
        val name = ArchiveTree.normalize(entryName)
        if (name.isEmpty()) return null
        val selected = clean.isEmpty() || clean.any { name == it || name.startsWith("$it/") }
        if (!selected) return null
        if (cleanBase.isEmpty()) return name
        return if (name.startsWith("$cleanBase/")) name.removePrefix("$cleanBase/") else null
    }
}

class PasswordRequiredException(val archiveName: String) : IOException(tr(R.string.archive_password_required, archiveName))

class WrongPasswordException(val archiveName: String) : IOException(tr(R.string.archive_wrong_password))

object ArchiveManager {

    init {
        StrataAesDecoder.install()
    }

    private val tarballSuffixes = listOf(".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".tar.zst", ".tar.zstd", ".tar.lz4", ".tar")
    private val zipSuffixes = listOf(".zip", ".jar", ".apk", ".apks", ".xapk", ".apkm", ".war")
    private val singleSuffixes = listOf(".gz", ".bz2", ".xz", ".zst", ".zstd", ".lz4")

    fun isExtractable(name: String): Boolean {
        val n = name.lowercase()
        return zipSuffixes.any { n.endsWith(it) } ||
            n.endsWith(".7z") ||
            RarArchive.isRar(n) ||
            tarballSuffixes.any { n.endsWith(it) } ||
            singleSuffixes.any { n.endsWith(it) }
    }

    fun extractionFolderName(archiveName: String): String {
        val n = archiveName
        if (RarArchive.isRar(n)) return RarArchive.baseName(n).ifEmpty { "archive" }
        val suffix = (tarballSuffixes + zipSuffixes + singleSuffixes + ".7z")
            .firstOrNull { n.lowercase().endsWith(it) }
        return if (suffix != null) n.dropLast(suffix.length).ifEmpty { "archive" } else "${n}_extracted"
    }

    fun extract(
        archivePath: String,
        destDir: String,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = LocalFileAccess,
        password: CharArray? = null,
        selection: ArchiveSelection? = null,
    ): Flow<OpProgress> = flow {
        val file = from.file(archivePath)
        val name = file.name.lowercase()
        val dest = to.file(destDir)
        val createdDest = !FsUtil.exists(dest, to)
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException(tr(R.string.archive_cannot_create, dest.name))
        val out = Output(dest, to, selection)
        val pw = password?.takeIf { it.isNotEmpty() }
        val count = try {
            when {
                zipSuffixes.any { name.endsWith(it) } -> extractZip(file, from, out, pw)
                name.endsWith(".7z") -> extractSevenZ(file, from, out, pw)
                RarArchive.isRar(name) -> extractRar(file, from, out, pw)
                tarballSuffixes.any { name.endsWith(it) } -> extractTar(file, name, from, out)
                singleSuffixes.any { name.endsWith(it) } -> extractSingle(file, name, from, out)
                else -> throw IllegalArgumentException(tr(R.string.archive_unsupported, file.name))
            }
        } catch (e: Throwable) {
            val retryable = e is PasswordRequiredException || e is WrongPasswordException || e is CancellationException
            if (createdDest && retryable) runCatching { FsUtil.deleteRecursively(dest, to) }
            throw if (e is DestinationException) IOException(e.message, e.cause) else e
        }
        emit(OpProgress.Finished(count, emptyList(), created = listOf(dest.absolutePath)))
    }.flowOn(Dispatchers.IO)

    fun createZip(
        items: List<FileItem>,
        destZipPath: String,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = LocalFileAccess,
    ): Flow<OpProgress> = ArchiveCreator.create(items, destZipPath, ArchiveOptions(ArchiveFormat.ZIP), from, to)

    private class Output(dest: File, private val access: FileAccess, private val selection: ArchiveSelection? = null) {
        private val base: File = dest.canonicalFile
        private val basePath: String = base.path

        fun map(entryName: String): String? = if (selection == null) entryName else selection.target(entryName)

        fun child(entryName: String): File {
            val out = access.child(base, entryName)
            val canonical = out.canonicalPath
            if (canonical != basePath && !canonical.startsWith(basePath.trimEnd(File.separatorChar) + File.separator)) {
                throw SecurityException(tr(R.string.archive_path_traversal, entryName))
            }
            return out
        }

        fun dir(entryName: String) {
            val name = map(entryName) ?: return
            child(name).mkdirs()
        }

        inner class Opened(private val file: File, private val stream: OutputStream) {
            fun write(bytes: ByteArray, length: Int) {
                try {
                    stream.write(bytes, 0, length)
                } catch (e: IOException) {
                    throw DestinationException(e)
                }
            }

            fun close() = runCatching { stream.close() }

            fun discard() {
                close()
                runCatching { FsUtil.deleteRecursively(file, access) }
            }
        }

        fun openEntry(entryName: String): Opened? {
            val name = map(entryName) ?: return null
            val out = child(name)
            out.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val stream = try {
                access.openOutput(out)
            } catch (e: IOException) {
                throw DestinationException(e)
            }
            return Opened(out, stream)
        }

        fun write(entryName: String, input: InputStream) {
            val name = map(entryName) ?: return
            val out = child(name)
            out.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val stream = try {
                access.openOutput(out)
            } catch (e: IOException) {
                throw DestinationException(e)
            }
            stream.use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    try {
                        o.write(buf, 0, n)
                    } catch (e: IOException) {
                        throw DestinationException(e)
                    }
                }
            }
        }
    }

    private class DestinationException(cause: IOException) : IOException(cause.message, cause)

    private suspend fun FlowCollector<OpProgress>.extractZip(file: File, from: FileAccess, out: Output, password: CharArray?): Int {
        val encrypted: Boolean
        val total: Int
        ZipFile.builder().setSeekableByteChannel(from.openReadChannel(file)).get().use { zip ->
            val entries = Collections.list(zip.entries)
            total = entries.size
            encrypted = entries.any { it.generalPurposeBit.usesEncryption() }
            if (!encrypted) {
                entries.forEachIndexed { i, entry ->
                    currentCoroutineContext().ensureActive()
                    if (out.map(entry.name) == null) return@forEachIndexed
                    emit(OpProgress.Running(entry.name, i.toLong(), total.toLong(), i, total))
                    if (entry.isDirectory) out.dir(entry.name) else zip.getInputStream(entry).use { out.write(entry.name, it) }
                }
            }
        }
        if (!encrypted) return total
        if (password == null) throw PasswordRequiredException(file.name)
        return try {
            Zip4jInputStream(from.openInput(file).buffered(), password).use { zin ->
                var count = 0
                while (true) {
                    val entry = zin.nextEntry ?: break
                    currentCoroutineContext().ensureActive()
                    emit(OpProgress.Running(entry.fileName, count.toLong(), total.toLong(), count, total))
                    if (entry.isDirectory) out.dir(entry.fileName) else out.write(entry.fileName, zin)
                    count++
                }
                count
            }
        } catch (e: IOException) {
            if (e is DestinationException) throw e
            throw WrongPasswordException(file.name)
        }
    }

    private suspend fun FlowCollector<OpProgress>.extractRar(file: File, from: FileAccess, out: Output, password: CharArray?): Int {
        val readable = RarArchive.readable(file, from)
        try {
            val headers = RarArchive.list(readable.path, password, file.name)
            val pw = password?.let(::String)
            val encrypted = headers.any { it.encrypted }
            if (pw == null && encrypted) throw PasswordRequiredException(file.name)
            headers.filter { it.isDirectory }.forEach { out.dir(RarArchive.normalize(it.name)) }
            val total = headers.count { !it.isDirectory }
            return coroutineScope {
                val events = Channel<OpProgress>(Channel.CONFLATED)
                val worker = async(Dispatchers.IO) {
                    val job = coroutineContext[Job]
                    var done = 0
                    var current: Output.Opened? = null
                    val sink = object : RarSink {
                        override fun begin(name: String, size: Long, flags: Int): Boolean {
                            job?.ensureActive()
                            val entry = RarArchive.normalize(name)
                            current = out.openEntry(entry) ?: return false
                            events.trySend(OpProgress.Running(entry, done.toLong(), total.toLong(), done, total))
                            return true
                        }

                        override fun write(buffer: ByteArray, length: Int): Boolean {
                            job?.ensureActive()
                            current?.write(buffer, length)
                            return true
                        }

                        override fun end(ok: Boolean) {
                            val c = current ?: return
                            current = null
                            if (ok) {
                                c.close()
                                done++
                            } else {
                                c.discard()
                            }
                        }
                    }
                    try {
                        val code = NativeBridge.rarStream(readable.path, pw, sink)
                        if (code != 0) throw RarArchive.failure(code, file.name, pw != null, encrypted)
                        done
                    } finally {
                        current?.discard()
                        events.close()
                    }
                }
                for (e in events) emit(e)
                worker.await()
            }
        } finally {
            readable.cleanup()
        }
    }

    private suspend fun FlowCollector<OpProgress>.extractSevenZ(file: File, from: FileAccess, out: Output, password: CharArray?): Int =
        try {
            readSevenZ(file, from, out, password)
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw PasswordRequiredException(file.name)
        } catch (e: IOException) {
            if (password != null && e !is DestinationException) throw WrongPasswordException(file.name)
            throw e
        } finally {
            SevenZKeys.clear()
        }

    private suspend fun FlowCollector<OpProgress>.readSevenZ(file: File, from: FileAccess, out: Output, password: CharArray?): Int =
        SevenZFile.builder().setSeekableByteChannel(from.openReadChannel(file)).apply { if (password != null) setPassword(password) }.get().use { sevenZ ->
            val current = object : InputStream() {
                override fun read(): Int = sevenZ.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = sevenZ.read(b, off, len)
            }
            var count = 0
            var entry = sevenZ.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                emit(OpProgress.Running(entry.name, count.toLong(), -1, count, -1))
                if (entry.isDirectory) out.dir(entry.name) else out.write(entry.name, current)
                count++
                entry = sevenZ.nextEntry
            }
            count
        }

    private suspend fun FlowCollector<OpProgress>.extractTar(file: File, name: String, from: FileAccess, out: Output): Int =
        from.openInput(file).buffered().use { raw ->
            compressorFor(name, raw).use { decompressed ->
                TarArchiveInputStream(decompressed).use { tar ->
                    var count = 0
                    var entry = tar.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        emit(OpProgress.Running(entry.name, count.toLong(), -1, count, -1))
                        if (entry.isDirectory) out.dir(entry.name) else out.write(entry.name, tar)
                        count++
                        entry = tar.nextEntry
                    }
                    count
                }
            }
        }

    private suspend fun FlowCollector<OpProgress>.extractSingle(file: File, name: String, from: FileAccess, out: Output): Int {
        val suffix = singleSuffixes.first { name.endsWith(it) }
        val outName = file.name.dropLast(suffix.length).ifEmpty { "${file.name}.out" }
        emit(OpProgress.Running(outName, 0, 1, 0, 1))
        from.openInput(file).buffered().use { raw ->
            compressorFor(name, raw).use { input -> out.write(outName, input) }
        }
        return 1
    }

    private fun compressorFor(name: String, input: InputStream): InputStream = when {
        name.endsWith(".gz") || name.endsWith(".tgz") -> GzipCompressorInputStream(input)
        name.endsWith(".bz2") || name.endsWith(".tbz2") -> BZip2CompressorInputStream(input)
        name.endsWith(".xz") || name.endsWith(".txz") -> XZCompressorInputStream(input)
        name.endsWith(".zst") || name.endsWith(".zstd") -> ZstdCompressorInputStream(input)
        name.endsWith(".lz4") -> FramedLZ4CompressorInputStream(input)
        else -> input
    }
}
