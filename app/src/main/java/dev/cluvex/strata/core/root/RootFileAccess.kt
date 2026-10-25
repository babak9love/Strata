package dev.cluvex.strata.core.root

import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FileOperations
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel

class RootFileAccess(private val fs: FileSystemManager) : FileAccess {

    override val isRoot: Boolean = true

    override fun file(path: String): File = fs.getFile(path)

    override fun child(parent: File, name: String): File = fs.getFile(parent.path, name)

    override fun isSymlink(file: File): Boolean = (file as? ExtendedFile)?.isSymlink ?: FsUtil.isSymlink(file)

    override fun openInput(file: File): InputStream = ext(file).newInputStream()

    override fun openOutput(file: File, append: Boolean): OutputStream = ext(file).newOutputStream(append)

    override fun openReadChannel(file: File): SeekableByteChannel =
        fs.openChannel(file.path, FileSystemManager.MODE_READ_ONLY)

    override fun openWriteChannel(file: File): SeekableByteChannel = fs.openChannel(
        file.path,
        FileSystemManager.MODE_READ_WRITE or FileSystemManager.MODE_CREATE or FileSystemManager.MODE_TRUNCATE,
    )

    override fun createSymlink(link: File, target: String) {
        if (FsUtil.exists(link, this)) throw IOException(tr(R.string.files_name_taken))
        if (!ext(link).createNewSymlink(target)) throw IOException(tr(R.string.files_link_failed))
    }

    fun list(path: String): List<FileItem>? =
        fs.getFile(path).listFiles()?.map { FileOperations.itemOf(it, this) }

    fun openChannel(path: String, writable: Boolean): FileChannel =
        fs.openChannel(path, if (writable) FileSystemManager.MODE_READ_WRITE else FileSystemManager.MODE_READ_ONLY)

    private fun ext(file: File): ExtendedFile = file as? ExtendedFile ?: fs.getFile(file.path)
}
