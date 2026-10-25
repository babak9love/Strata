package dev.cluvex.strata.core.props

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import java.io.File

class PermissionApplier(
    private val api: PosixApi,
    private val access: FileAccess = LocalFileAccess,
) {

    data class Result(val changed: Int, val failed: List<String>)

    fun apply(targets: List<String>, mode: Int, applyToSubFiles: Boolean, applyToSubDirs: Boolean): Result =
        applyEach(targets, applyToSubFiles, applyToSubDirs) { api.chmod(it, mode) }

    fun applyOwner(targets: List<String>, uid: Int, gid: Int, applyToSubFiles: Boolean, applyToSubDirs: Boolean): Result =
        applyEach(targets, applyToSubFiles, applyToSubDirs) { api.chown(it, uid, gid) }

    private fun applyEach(
        targets: List<String>,
        subFiles: Boolean,
        subDirs: Boolean,
        action: (String) -> Unit,
    ): Result {
        val work = ArrayList<String>()
        for (target in targets) {
            work += target
            val f = access.file(target)
            if ((subFiles || subDirs) && f.isDirectory && !access.isSymlink(f)) collect(f, subFiles, subDirs, work)
        }
        var changed = 0
        val failed = ArrayList<String>()
        for (path in work.asReversed()) {
            try {
                action(path)
                changed++
            } catch (e: Exception) {
                failed += tr(R.string.files_item_error, File(path).name, e.message ?: e.javaClass.simpleName)
            }
        }
        return Result(changed, failed)
    }

    private fun collect(dir: File, files: Boolean, dirs: Boolean, out: MutableList<String>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (access.isSymlink(child)) continue
            if (child.isDirectory) {
                if (dirs) out += child.absolutePath
                collect(child, files, dirs, out)
            } else if (files) {
                out += child.absolutePath
            }
        }
    }
}
