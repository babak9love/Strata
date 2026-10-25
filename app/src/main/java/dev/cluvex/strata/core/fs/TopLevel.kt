package dev.cluvex.strata.core.fs

import dev.cluvex.strata.core.model.FileItem
import java.io.File

object TopLevel {

    val NAMES = listOf(
        "acct", "apex", "bin", "bugreports", "cache", "config", "d", "data", "data_mirror", "debug_ramdisk", "dev",
        "etc", "init", "init.environ.rc", "linkerconfig", "lost+found", "metadata", "mnt", "odm", "odm_dlkm", "oem",
        "postinstall", "proc", "product", "sdcard", "second_stage_resources", "storage", "sys", "system",
        "system_dlkm", "system_ext", "vendor", "vendor_dlkm",
    )

    fun probe(root: File = File("/")): List<FileItem> = NAMES.mapNotNull { name ->
        val f = File(root, name)
        if (!f.exists() && !FsUtil.isSymlink(f)) return@mapNotNull null
        val item = FileItem.of(f)
        if (item.isDirectory || f.isFile || '.' in name) item else item.copy(isDirectory = true, size = 0)
    }
}
