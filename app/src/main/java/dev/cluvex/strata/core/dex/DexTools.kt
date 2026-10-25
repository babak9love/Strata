package dev.cluvex.strata.core.dex

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.googlecode.d2j.dex.Dex2jar
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ZipEditor
import dev.cluvex.strata.core.build.BuildListener
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DexClass(val type: String) {
    val fqcn: String get() = type.removePrefix("L").removeSuffix(";").replace('/', '.')
    val simpleName: String get() = fqcn.substringAfterLast('.')
    val packageName: String get() = fqcn.substringBeforeLast('.', "")
    val smaliFileName: String get() = fqcn.replace('.', '/') + ".smali"
}

object DexTools {

    const val API = DexIndex.API

    val STEP_FIND: String get() = tr(R.string.dex_step_find)
    val STEP_EXTRACT: String get() = tr(R.string.dex_step_extract)
    val STEP_ASSEMBLE: String get() = tr(R.string.dex_step_assemble)
    val STEP_REBUILD: String get() = tr(R.string.dex_step_rebuild)
    val STEP_WRITE_APK: String get() = tr(R.string.dex_step_write_apk)
    val STEP_WRITE_JAR: String get() = tr(R.string.dex_step_write_jar)
    val STEP_WRITE_DEX: String get() = tr(R.string.dex_step_write_dex)

    fun dexToJar(input: File, output: File, log: BuildListener = BuildListener.NONE) {
        val classesDir = File(output.parentFile, output.nameWithoutExtension + ".d2j-${System.nanoTime()}")
        try {
            classesDir.mkdirs()
            log.step(tr(R.string.dex_step_dex2jar))
            log.info("${input.name} · ${BuildRun.size(input.length())}")
            Dex2jar.from(input).topoLogicalSort().skipDebug(false).doTranslate(classesDir.toPath())
            val files = classesDir.walkTopDown().filter { it.isFile }.toList()
            log.info(trPlural(R.plurals.dex_class_files, files.size, BuildRun.number(files.size)))
            log.step(tr(R.string.build_step_write, output.name))
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                files.forEachIndexed { i, f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(classesDir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (i % 50 == 0) log.progress(i + 1, files.size)
                }
            }
            log.info(BuildRun.size(output.length()))
        } catch (e: Exception) {
            output.delete()
            throw IOException(tr(R.string.dex_to_jar_failed, e.message ?: e.javaClass.simpleName), e)
        } finally {
            FsUtil.deleteRecursively(classesDir)
        }
    }

    fun disassemble(input: File, outDir: File, jobs: Int = concurrency(), log: BuildListener = BuildListener.NONE): Int {
        log.step(tr(R.string.dex_step_read, input.name))
        val container = DexFileFactory.loadDexContainer(input, null)
        log.info(trPlural(R.plurals.dex_dex_files, container.dexEntryNames.size, BuildRun.number(container.dexEntryNames.size)))
        var count = 0
        for (name in container.dexEntryNames) {
            val dex = container.getEntry(name)?.dexFile ?: continue
            val sub = if (container.dexEntryNames.size > 1) File(outDir, dexDirName(name)) else outDir
            sub.mkdirs()
            log.step(tr(R.string.dex_step_disassemble, name))
            log.info(classCount(dex.classes.size) + " · dex API ${dex.opcodes.api}")
            Baksmali.disassembleDexFile(dex, sub, jobs, DexIndex.options(DexIndex.apiOf(dex)))
            count += dex.classes.size
        }
        log.info(trPlural(R.plurals.dex_smali_files_in, count, BuildRun.number(count), outDir.name))
        return count
    }

    fun listClasses(dexOrApk: File): List<DexClass> = DexIndex.load(dexOrApk).classes

    fun classToSmali(dexOrApk: File, type: String): String = DexIndex.load(dexOrApk).smali(type, readable = false)

    fun replaceClassSmali(dexFile: File, type: String, editedSmali: String, output: File, log: BuildListener = BuildListener.NONE) {
        log.step(STEP_ASSEMBLE)
        val original = DexFileFactory.loadDexFile(dexFile, null)
        val api = SmaliAssembler.apiFor(editedSmali, DexIndex.apiOf(original))
        val lineCount = editedSmali.lines().size
        log.info("${DexClass(type).simpleName} · ${trPlural(R.plurals.dex_lines, lineCount, BuildRun.number(lineCount))} · API $api")
        val assembled = assembleSmali(editedSmali, api)
        val replacements = assembled.classes.associateBy { it.type }
        require(type in replacements) { tr(R.string.dex_must_define, DexClass(type).fqcn) }
        log.info(trPlural(R.plurals.dex_classes_assembled, assembled.classes.size, BuildRun.number(assembled.classes.size)))
        log.step(STEP_REBUILD)
        val originalTypes = original.classes.mapTo(HashSet()) { it.type }
        val classes = LinkedHashSet<ClassDef>()
        for (c in original.classes) classes += replacements[c.type] ?: c
        for (c in assembled.classes) if (c.type !in originalTypes) {
            classes += c
            log.info(tr(R.string.dex_new_class, DexClass(c.type).fqcn))
        }
        writeDex(classes, output, api)
        log.info(classCount(classes.size) + " · " + tr(R.string.dex_size_was, BuildRun.size(output.length()), BuildRun.size(dexFile.length())))
    }

    fun replaceClassInApk(apk: File, type: String, editedSmali: String, workDir: File, log: BuildListener = BuildListener.NONE) {
        workDir.mkdirs()
        log.step(STEP_FIND)
        val entryName = dexEntryContaining(apk, type) ?: throw IOException(tr(R.string.dex_class_not_in, DexClass(type).fqcn, apk.name))
        log.info(tr(R.string.dex_class_is_in, DexClass(type).fqcn, entryName))
        log.step(STEP_EXTRACT)
        val extracted = File(workDir, "orig.dex")
        extractDexEntry(apk, entryName, extracted)
        log.info("$entryName · ${BuildRun.size(extracted.length())}")
        val rebuilt = File(workDir, "rebuilt.dex")
        replaceClassSmali(extracted, type, editedSmali, rebuilt, log)
        log.step(if (apk.name.endsWith(".apk", ignoreCase = true)) STEP_WRITE_APK else STEP_WRITE_JAR)
        ZipEditor.put(apk, entryName, rebuilt)
        log.info("${apk.name} · ${BuildRun.size(apk.length())}")
    }

    fun dexEntryContaining(file: File, type: String): String? {
        val container = DexFileFactory.loadDexContainer(file, null)
        return container.dexEntryNames.firstOrNull { name ->
            container.getEntry(name)?.dexFile?.classes?.any { it.type == type } == true
        }
    }

    private fun extractDexEntry(file: File, entryName: String, out: File) {
        val dex = DexFileFactory.loadDexEntry(file, entryName, true, null).dexFile
        DexFileFactory.writeDexFile(out.absolutePath, dex)
    }

    fun assembleSmali(smali: String, api: Int = SmaliAssembler.apiFor(smali)): DexBackedDexFile =
        DexBackedDexFile(Opcodes.forApi(api), SmaliAssembler.assemble(smali, api))

    fun extractDexContaining(file: File, type: String, outDir: File): File {
        outDir.mkdirs()
        val container = DexFileFactory.loadDexContainer(file, null)
        for (name in container.dexEntryNames) {
            val dex = container.getEntry(name)?.dexFile ?: continue
            if (dex.classes.any { it.type == type }) {
                val out = File(outDir, "extracted.dex")
                DexFileFactory.writeDexFile(out.absolutePath, dex)
                return out
            }
        }
        throw IOException(tr(R.string.dex_class_not_in, DexClass(type).fqcn, file.name))
    }

    private fun writeDex(classes: Collection<ClassDef>, output: File, api: Int) {
        output.parentFile?.mkdirs()
        DexFileFactory.writeDexFile(output.absolutePath, ImmutableDexFile(Opcodes.forApi(api), classes))
    }

    private fun dexDirName(entryName: String): String = entryName.substringAfterLast('/').removeSuffix(".dex")

    internal fun classCount(n: Int): String = trPlural(R.plurals.dex_classes, n, BuildRun.number(n))

    private fun concurrency(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
}
