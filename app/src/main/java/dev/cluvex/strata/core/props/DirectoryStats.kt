package dev.cluvex.strata.core.props

import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.LocalFileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

data class DirStats(val bytes: Long = 0, val files: Int = 0, val folders: Int = 0)

object DirectoryStats {

    private const val EMIT_EVERY = 256

    suspend fun compute(paths: List<String>, access: FileAccess = LocalFileAccess, countRoots: Boolean = false): DirStats =
        withContext(Dispatchers.IO) { walk(paths, access, countRoots) { } }

    fun stream(paths: List<String>, access: FileAccess = LocalFileAccess, countRoots: Boolean = false): Flow<DirStats> = flow {
        val total = walk(paths, access, countRoots) { snapshot -> emit(snapshot) }
        emit(total)
    }.flowOn(Dispatchers.IO)

    private suspend fun walk(paths: List<String>, access: FileAccess, countRoots: Boolean, tick: suspend (DirStats) -> Unit): DirStats {
        var bytes = 0L
        var files = 0
        var folders = 0
        var seen = 0
        for (p in paths) {
            val root = access.file(p)
            when {
                access.isSymlink(root) -> files++
                !root.isDirectory -> {
                    files++
                    bytes += root.length()
                }
                else -> {
                    if (countRoots) folders++
                    val stack = ArrayDeque<File>()
                    stack.addLast(root)
                    while (stack.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val dir = stack.removeLast()
                        val children = dir.listFiles() ?: continue
                        for (child in children) {
                            when {
                                access.isSymlink(child) -> files++
                                child.isDirectory -> {
                                    folders++
                                    stack.addLast(child)
                                }
                                else -> {
                                    files++
                                    bytes += child.length()
                                }
                            }
                            if (++seen % EMIT_EVERY == 0) tick(DirStats(bytes, files, folders))
                        }
                    }
                }
            }
        }
        return DirStats(bytes, files, folders)
    }
}
