package dev.cluvex.strata.core.dex

import com.android.tools.r8.ByteDataView
import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DexIndexedConsumer
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.origin.Origin
import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildListener
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

data class SmaliGroup(val dexName: String, val files: List<File>)

object DexConvert {

    const val JAR_MIN_API = 21

    private val dexDir = Regex("classes\\d*")
    private val dexEntry = Regex("classes\\d*\\.dex")

    fun containsSmali(folder: File, access: FileAccess = LocalFileAccess, budget: Int = 4000): Boolean {
        var seen = 0
        val stack = ArrayDeque<File>()
        stack += folder
        while (stack.isNotEmpty() && seen < budget) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                seen++
                if (c.isFile && c.name.endsWith(".smali", ignoreCase = true)) return true
                if (c.isDirectory && !access.isSymlink(c)) stack += c
            }
        }
        return false
    }

    fun smaliGroups(root: File, access: FileAccess = LocalFileAccess): List<SmaliGroup> {
        val all = root.walkTopDown()
            .onEnter { it == root || !access.isSymlink(it) }
            .filter { it.isFile && it.name.endsWith(".smali", ignoreCase = true) }
            .toList()
        if (all.isEmpty()) return emptyList()
        val dexDirs = root.listFiles().orEmpty().filter { it.isDirectory && dexDir.matches(it.name) }
        fun under(f: File, d: File) = f.path.startsWith(d.path + File.separator)
        val split = dexDirs.size >= 2 && all.all { f -> dexDirs.any { under(f, it) } }
        if (!split) return listOf(SmaliGroup("classes", all.sortedBy { it.path }))
        return dexDirs.sortedBy { it.name.removePrefix("classes").toIntOrNull() ?: 1 }
            .map { d -> SmaliGroup(d.name, all.filter { under(it, d) }.sortedBy { it.path }) }
            .filter { it.files.isNotEmpty() }
    }

    val STEP_COLLECT: String get() = tr(R.string.dex_step_collect)
    val STEP_READ_JAR: String get() = tr(R.string.dex_step_read_jar)
    val STEP_D8: String get() = tr(R.string.dex_step_d8)

    fun smaliToDex(folder: String, from: FileAccess, to: FileAccess, log: BuildListener = BuildListener.NONE, isCancelled: () -> Boolean = { false }): File {
        log.step(STEP_COLLECT)
        val root = from.file(folder)
        val groups = smaliGroups(root, from)
        val total = groups.sumOf { it.files.size }
        if (total == 0) throw IOException(tr(R.string.dex_no_smali, root.name))
        log.info(
            if (groups.size > 1) {
                trPlural(R.plurals.dex_smali_files_in_folders, total, BuildRun.number(total), BuildRun.number(groups.size))
            } else {
                trPlural(R.plurals.dex_smali_files, total, BuildRun.number(total))
            },
        )
        val built = ArrayList<Pair<String, ByteArray>>()
        for (group in groups) {
            var api = DexIndex.API
            var bytes: ByteArray? = null
            while (bytes == null) {
                log.step(tr(R.string.dex_step_assemble_file, "${group.dexName}.dex"))
                log.info("API $api · ${trPlural(R.plurals.dex_files, group.files.size, BuildRun.number(group.files.size))}")
                val asm = SmaliAssembler(api)
                var here = 0
                var raised = false
                for (f in group.files) {
                    if (isCancelled()) throw CancellationException(tr(R.string.build_stopped))
                    val text = from.openInput(f).use { it.readBytes().decodeToString() }
                    val needed = SmaliAssembler.apiFor(text, api)
                    if (needed > api) {
                        log.info(tr(R.string.dex_newer_opcodes, f.name, needed.toString()))
                        api = needed
                        raised = true
                        break
                    }
                    if (!asm.add(f.relativeTo(root).invariantSeparatorsPath, text)) log.warn(tr(R.string.dex_has_errors, f.relativeTo(root).invariantSeparatorsPath))
                    here++
                    if (here % 10 == 0 || here == group.files.size) log.progress(here, group.files.size)
                }
                if (raised) continue
                bytes = asm.build()
                log.info("${DexTools.classCount(asm.classTypes.size)} · ${BuildRun.size(bytes.size.toLong())}")
            }
            built += group.dexName to bytes
        }
        return write(built, root.parentFile ?: root, root.name, to, log)
    }

    fun jarToDex(jar: String, from: FileAccess, to: FileAccess, minApi: Int = JAR_MIN_API, log: BuildListener = BuildListener.NONE): File {
        log.step(STEP_READ_JAR)
        val src = from.file(jar)
        val classes = ArrayList<Pair<String, ByteArray>>()
        var hasDex = false
        var skipped = 0
        ZipInputStream(from.openInput(src).buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (e.isDirectory) continue
                when {
                    name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class") -> classes += name to zip.readBytes()
                    name.endsWith(".class") -> skipped++
                    dexEntry.matches(name) -> hasDex = true
                }
            }
        }
        if (classes.isEmpty()) {
            throw IOException(if (hasDex) tr(R.string.dex_jar_has_dex, src.name) else tr(R.string.dex_no_class_files, src.name))
        }
        log.info("${DexTools.classCount(classes.size)} · ${BuildRun.size(src.length())}" + if (skipped > 0) " · " + tr(R.string.dex_skipped_classes, BuildRun.number(skipped)) else "")
        log.step(STEP_D8)
        log.info(tr(R.string.dex_min_api, minApi.toString()))
        val dexes = d8(classes, minApi, log)
        log.info(trPlural(R.plurals.dex_dex_files, dexes.size, BuildRun.number(dexes.size)) + " · " + BuildRun.size(dexes.sumOf { it.size.toLong() }))
        val built = dexes.mapIndexed { i, b -> (if (i == 0) "classes" else "classes${i + 1}") to b }
        return write(built, src.parentFile ?: src, src.nameWithoutExtension, to, log)
    }

    fun d8(classes: List<Pair<String, ByteArray>>, minApi: Int = JAR_MIN_API, log: BuildListener = BuildListener.NONE): List<ByteArray> {
        val errors = ArrayList<String>()
        var warnings = 0
        val handler = object : DiagnosticsHandler {
            override fun error(diagnostic: Diagnostic) {
                errors += diagnostic.diagnosticMessage
            }

            override fun warning(diagnostic: Diagnostic) {
                warnings++
                if (warnings <= MAX_WARNINGS) log.warn(diagnostic.diagnosticMessage.lineSequence().first())
            }

            override fun info(diagnostic: Diagnostic) = Unit
        }
        val out = TreeMap<Int, ByteArray>()
        val builder = D8Command.builder(handler)
            .setMinApiLevel(minApi)
            .setMode(CompilationMode.DEBUG)
            .setProgramConsumer(object : DexIndexedConsumer.ForwardingConsumer(null) {
                override fun accept(fileIndex: Int, data: ByteDataView, descriptors: Set<String>?, handler: DiagnosticsHandler?) {
                    synchronized(out) { out[fileIndex] = data.copyByteData() }
                }
            })
        for ((name, bytes) in classes) builder.addClassProgramData(bytes, EntryOrigin(name))
        try {
            D8.run(builder.build())
        } catch (e: CompilationFailedException) {
            throw IOException(errors.firstOrNull() ?: e.message ?: tr(R.string.dex_d8_failed), e)
        } catch (e: LinkageError) {
            throw IOException(tr(R.string.dex_d8_unsupported, e.message ?: e.javaClass.simpleName), e)
        } catch (e: OutOfMemoryError) {
            throw IOException(tr(R.string.dex_d8_memory), e)
        }
        if (warnings > MAX_WARNINGS) log.warn(trPlural(R.plurals.dex_more_warnings, warnings - MAX_WARNINGS, BuildRun.number(warnings - MAX_WARNINGS)))
        if (out.isEmpty()) throw IOException(errors.firstOrNull() ?: tr(R.string.dex_d8_no_dex))
        return out.values.toList()
    }

    private const val MAX_WARNINGS = 40

    private fun write(built: List<Pair<String, ByteArray>>, parent: File, base: String, to: FileAccess, log: BuildListener): File {
        val dir = to.file(parent.path)
        if (built.size == 1) {
            val out = FsUtil.uniqueSibling(to.child(dir, "$base.dex"), false, to)
            log.step(tr(R.string.build_step_write, out.name))
            to.openOutput(out).use { it.write(built[0].second) }
            log.info("${out.name} · ${BuildRun.size(built[0].second.size.toLong())}")
            return out
        }
        val folder = FsUtil.uniqueSibling(to.child(dir, "$base-dex"), true, to)
        log.step(tr(R.string.build_step_write, folder.name))
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException(tr(R.string.dex_cant_create, folder.name))
        for ((name, bytes) in built) {
            to.openOutput(to.child(folder, "$name.dex")).use { it.write(bytes) }
            log.info("$name.dex · ${BuildRun.size(bytes.size.toLong())}")
        }
        return folder
    }

    private class EntryOrigin(private val name: String) : Origin(Origin.root()) {
        override fun part(): String = name
    }
}
