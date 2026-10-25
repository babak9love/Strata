package dev.cluvex.strata.core.archive

import androidx.annotation.StringRes
import dev.cluvex.strata.R

import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import net.lingala.zip4j.model.enums.CompressionLevel as Zip4jLevel

enum class ArchiveFormat(val label: String, val extension: String, val supportsPassword: Boolean, val supportsLevel: Boolean) {
    ZIP("ZIP", ".zip", supportsPassword = true, supportsLevel = true),
    SEVEN_Z("7z", ".7z", supportsPassword = true, supportsLevel = true),
    TAR("TAR", ".tar", supportsPassword = false, supportsLevel = false),
    TAR_GZ("TAR.GZ", ".tar.gz", supportsPassword = false, supportsLevel = true),
    TAR_BZ2("TAR.BZ2", ".tar.bz2", supportsPassword = false, supportsLevel = true),
    TAR_XZ("TAR.XZ", ".tar.xz", supportsPassword = false, supportsLevel = true),
    TAR_ZST("TAR.ZST", ".tar.zst", supportsPassword = false, supportsLevel = true),
    ;

    fun defaultName(item: FileItem): String {
        val dot = item.name.lastIndexOf('.')
        val base = if (!item.isDirectory && dot > 0) item.name.substring(0, dot) else item.name
        return base + extension
    }

    fun fileName(name: String): String {
        val trimmed = name.trim()
        val known = entries.map { it.extension }.sortedByDescending { it.length }
            .firstOrNull { trimmed.endsWith(it, ignoreCase = true) && trimmed.length > it.length }
        return (if (known != null) trimmed.dropLast(known.length) else trimmed) + extension
    }
}

enum class CompressionLevel(@StringRes val label: Int) {
    STORE(R.string.model_level_store),
    FASTEST(R.string.model_level_fastest),
    FAST(R.string.model_level_fast),
    NORMAL(R.string.model_level_normal),
    MAXIMUM(R.string.model_level_maximum),
    ULTRA(R.string.model_level_ultra),
}

data class ArchiveOptions(
    val format: ArchiveFormat = ArchiveFormat.ZIP,
    val level: CompressionLevel = CompressionLevel.NORMAL,
    val password: CharArray? = null,
    val encryptNames: Boolean = false,
) {
    val encrypted: Boolean get() = password != null && password.isNotEmpty() && format.supportsPassword
}

object ArchiveCreator {
    fun create(
        items: List<FileItem>,
        destPath: String,
        options: ArchiveOptions = ArchiveOptions(),
        from: FileAccess = LocalFileAccess,
        to: FileAccess = LocalFileAccess,
    ): Flow<OpProgress> = flow {
        val dest = to.file(destPath)
        if (FsUtil.exists(dest, to)) throw IOException(tr(R.string.common_already_exists, dest.name))
        val count = write(items, dest, options, from, to)
        emit(OpProgress.Finished(count, emptyList(), created = listOf(dest.absolutePath)))
    }.flowOn(Dispatchers.IO)

    fun createEach(
        items: List<FileItem>,
        destDir: String,
        options: ArchiveOptions = ArchiveOptions(),
        from: FileAccess = LocalFileAccess,
        to: FileAccess = LocalFileAccess,
    ): Flow<OpProgress> = flow {
        val dir = to.file(destDir)
        val created = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val dest = FsUtil.uniqueSibling(to.child(dir, options.format.defaultName(item)), isDirectory = false, access = to)
            try {
                write(listOf(item), dest, options, from, to)
                created += dest.absolutePath
            } catch (e: IOException) {
                failed += "${item.name}: ${e.message}"
            }
        }
        emit(OpProgress.Finished(created.size, failed, created = created))
    }.flowOn(Dispatchers.IO)

    internal data class Work(val file: File?, val entryName: String, val isDir: Boolean)

    private suspend fun FlowCollector<OpProgress>.write(
        items: List<FileItem>,
        dest: File,
        options: ArchiveOptions,
        from: FileAccess,
        to: FileAccess,
    ): Int {
        val work = mutableListOf<Work>()
        for (item in items) {
            val f = from.file(item.path)
            collectWork(f, f.name, from, work)
        }
        try {
            when (options.format) {
                ArchiveFormat.ZIP -> if (options.encrypted) zipAes(work, dest, options, from, to) else zip(work, dest, options, from, to)
                ArchiveFormat.SEVEN_Z -> sevenZ(work, dest, options, from, to)
                else -> tar(work, dest, options, from, to)
            }
        } catch (e: Throwable) {
            runCatching { FsUtil.deleteRecursively(dest, to) }
            throw e
        }
        return work.size
    }

    private suspend fun FlowCollector<OpProgress>.progress(i: Int, total: Int, w: Work) {
        currentCoroutineContext().ensureActive()
        emit(OpProgress.Running(w.entryName, i.toLong(), total.toLong(), i, total))
    }

    private suspend fun FlowCollector<OpProgress>.zip(work: List<Work>, dest: File, o: ArchiveOptions, from: FileAccess, to: FileAccess) {
        ZipArchiveOutputStream(to.openOutput(dest).buffered()).use { zos ->
            zos.setLevel(deflateLevel(o.level))
            work.forEachIndexed { i, w ->
                progress(i, work.size, w)
                if (w.isDir) {
                    zos.putArchiveEntry(ZipArchiveEntry(w.entryName.trimEnd('/') + "/"))
                    zos.closeArchiveEntry()
                } else if (w.file != null) {
                    zos.putArchiveEntry(ZipArchiveEntry(w.file, w.entryName))
                    from.openInput(w.file).use { it.copyTo(zos, BUFFER) }
                    zos.closeArchiveEntry()
                }
            }
        }
    }

    private suspend fun FlowCollector<OpProgress>.zipAes(work: List<Work>, dest: File, o: ArchiveOptions, from: FileAccess, to: FileAccess) {
        ZipOutputStream(to.openOutput(dest).buffered(), o.password).use { zos ->
            work.forEachIndexed { i, w ->
                progress(i, work.size, w)
                val params = ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    compressionLevel = zip4jLevel(o.level)
                    fileNameInZip = if (w.isDir) w.entryName.trimEnd('/') + "/" else w.entryName
                    if (!w.isDir) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    w.file?.let { lastModifiedFileTime = it.lastModified() }
                }
                zos.putNextEntry(params)
                if (!w.isDir && w.file != null) from.openInput(w.file).use { it.copyTo(zos, BUFFER) }
                zos.closeEntry()
            }
        }
    }

    private suspend fun FlowCollector<OpProgress>.sevenZ(work: List<Work>, dest: File, o: ArchiveOptions, from: FileAccess, to: FileAccess) {
        val channel = to.openWriteChannel(dest)
        channel.use { raw ->
            val shield = KeepOpen(raw)
            val password = if (o.encrypted) o.password else null
            SevenZOutputFile(shield, password).use { out ->
                out.setContentMethods(listOf(sevenZMethod(o.level)))
                work.forEachIndexed { i, w ->
                    progress(i, work.size, w)
                    if (w.isDir) {
                        out.putArchiveEntry(out.createArchiveEntry(w.file ?: File(w.entryName), w.entryName.trimEnd('/')).apply { isDirectory = true })
                    } else if (w.file != null) {
                        out.putArchiveEntry(out.createArchiveEntry(w.file, w.entryName))
                        from.openInput(w.file).use { out.write(it) }
                    }
                    out.closeArchiveEntry()
                }
                out.finish()
            }
            if (password != null && o.encryptNames) SevenZHeaderCrypto.encryptHeader(raw, password)
        }
    }

    private suspend fun FlowCollector<OpProgress>.tar(work: List<Work>, dest: File, o: ArchiveOptions, from: FileAccess, to: FileAccess) {
        val raw = to.openOutput(dest).buffered()
        val compressed: OutputStream = try {
            when (o.format) {
                ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(raw, GzipParameters().apply { compressionLevel = deflateLevel(o.level) })
                ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw, bzip2BlockSize(o.level))
                ArchiveFormat.TAR_XZ -> XZOutputStream(raw, lzma2Options(o.level))
                ArchiveFormat.TAR_ZST -> ZstdCompressorOutputStream.builder().setOutputStream(raw).setLevel(zstdLevel(o.level)).get()
                else -> raw
            }
        } catch (e: Throwable) {
            raw.close()
            throw e
        }
        TarArchiveOutputStream(compressed).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            work.forEachIndexed { i, w ->
                progress(i, work.size, w)
                if (w.isDir) {
                    tar.putArchiveEntry(TarArchiveEntry(w.entryName.trimEnd('/') + "/"))
                } else if (w.file != null) {
                    tar.putArchiveEntry(TarArchiveEntry(w.file, w.entryName))
                    from.openInput(w.file).use { it.copyTo(tar, BUFFER) }
                }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }

    internal fun collectWork(file: File, entryBase: String, access: FileAccess, out: MutableList<Work>) {
        if (file.isDirectory && !access.isSymlink(file)) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                out += Work(file, "$entryBase/", isDir = true)
            } else {
                for (child in children.sortedBy { it.name }) collectWork(child, "$entryBase/${child.name}", access, out)
            }
        } else if (!access.isSymlink(file) || file.isFile) {
            out += Work(file, entryBase, isDir = false)
        }
    }

    internal fun deflateLevel(level: CompressionLevel): Int = when (level) {
        CompressionLevel.STORE -> 0
        CompressionLevel.FASTEST -> 1
        CompressionLevel.FAST -> 3
        CompressionLevel.NORMAL -> 6
        CompressionLevel.MAXIMUM -> 8
        CompressionLevel.ULTRA -> 9
    }

    private fun zip4jLevel(level: CompressionLevel): Zip4jLevel = when (level) {
        CompressionLevel.STORE -> Zip4jLevel.NO_COMPRESSION
        CompressionLevel.FASTEST -> Zip4jLevel.FASTEST
        CompressionLevel.FAST -> Zip4jLevel.FAST
        CompressionLevel.NORMAL -> Zip4jLevel.HIGHER
        CompressionLevel.MAXIMUM -> Zip4jLevel.PRE_ULTRA
        CompressionLevel.ULTRA -> Zip4jLevel.ULTRA
    }

    internal fun lzma2Options(level: CompressionLevel): LZMA2Options = when (level) {
        CompressionLevel.STORE, CompressionLevel.FASTEST -> LZMA2Options(0)
        CompressionLevel.FAST -> LZMA2Options(3)
        CompressionLevel.NORMAL -> LZMA2Options(5)
        CompressionLevel.MAXIMUM -> LZMA2Options(6)
        CompressionLevel.ULTRA -> LZMA2Options(6).apply {
            niceLen = LZMA2Options.NICE_LEN_MAX
            depthLimit = 512
        }
    }

    private fun sevenZMethod(level: CompressionLevel): SevenZMethodConfiguration =
        if (level == CompressionLevel.STORE) SevenZMethodConfiguration(SevenZMethod.COPY)
        else SevenZMethodConfiguration(SevenZMethod.LZMA2, lzma2Options(level))

    private fun bzip2BlockSize(level: CompressionLevel): Int = when (level) {
        CompressionLevel.STORE, CompressionLevel.FASTEST -> 1
        CompressionLevel.FAST -> 3
        CompressionLevel.NORMAL -> 6
        CompressionLevel.MAXIMUM, CompressionLevel.ULTRA -> 9
    }

    private fun zstdLevel(level: CompressionLevel): Int = when (level) {
        CompressionLevel.STORE, CompressionLevel.FASTEST -> 1
        CompressionLevel.FAST -> 2
        CompressionLevel.NORMAL -> 3
        CompressionLevel.MAXIMUM -> 12
        CompressionLevel.ULTRA -> 19
    }

    private const val BUFFER = 64 * 1024

    private class KeepOpen(private val inner: SeekableByteChannel) : SeekableByteChannel {
        override fun read(dst: ByteBuffer): Int = inner.read(dst)
        override fun write(src: ByteBuffer): Int = inner.write(src)
        override fun position(): Long = inner.position()
        override fun position(newPosition: Long): SeekableByteChannel = apply { inner.position(newPosition) }
        override fun size(): Long = inner.size()
        override fun truncate(size: Long): SeekableByteChannel = apply { inner.truncate(size) }
        override fun isOpen(): Boolean = inner.isOpen
        override fun close() = Unit
    }
}
