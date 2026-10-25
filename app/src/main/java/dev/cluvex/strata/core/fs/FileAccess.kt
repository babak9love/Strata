package dev.cluvex.strata.core.fs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel

interface FileAccess {
    val isRoot: Boolean
    fun file(path: String): File
    fun child(parent: File, name: String): File
    fun isSymlink(file: File): Boolean
    fun openInput(file: File): InputStream
    fun openOutput(file: File, append: Boolean = false): OutputStream

    fun openReadChannel(file: File): SeekableByteChannel

    fun openWriteChannel(file: File): SeekableByteChannel

    fun createSymlink(link: File, target: String)
}

object LocalFileAccess : FileAccess {
    override val isRoot: Boolean = false
    override fun file(path: String): File = File(path)
    override fun child(parent: File, name: String): File = File(parent, name)
    override fun isSymlink(file: File): Boolean = FsUtil.isSymlink(file)
    override fun openInput(file: File): InputStream = FileInputStream(file)
    override fun openOutput(file: File, append: Boolean): OutputStream = FileOutputStream(file, append)
    override fun openReadChannel(file: File): SeekableByteChannel = RandomAccessFile(file, "r").channel
    override fun openWriteChannel(file: File): SeekableByteChannel =
        RandomAccessFile(file, "rw").also { it.setLength(0) }.channel
    override fun createSymlink(link: File, target: String) = FsUtil.createSymlink(link, target)
}

fun FileAccess.sameProcessAs(other: FileAccess): Boolean = isRoot == other.isRoot
