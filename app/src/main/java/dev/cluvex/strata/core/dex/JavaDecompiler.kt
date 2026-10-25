package dev.cluvex.strata.core.dex

import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildListener
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.core.dex.instructions.args.ArgType
import java.io.File
import java.io.IOException

data class DecompiledClass(val code: String, val members: List<ClassMember>)

object JavaDecompiler {

    private val qualifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*\\.")

    private fun decompiler(input: File, configure: JadxArgs.() -> Unit): JadxDecompiler {
        val args = JadxArgs().apply {
            setInputFile(input)
            threadsCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            isShowInconsistentCode = true
            isSkipResources = true
            isDebugInfo = false
            configure()
        }
        return JadxDecompiler(args)
    }

    fun decompileToDir(input: File, outDir: File, log: BuildListener = BuildListener.NONE): Int {
        outDir.mkdirs()
        return decompiler(input) {
            setOutDir(outDir)
            setOutDirSrc(outDir)
        }.use { jadx ->
            log.step(tr(R.string.dex_step_jadx_load))
            jadx.load()
            log.info(DexTools.classCount(jadx.classes.size))
            log.step(tr(R.string.dex_step_write_java))
            jadx.saveSources()
            val errors = jadx.errorsCount
            if (errors > 0) log.warn(trPlural(R.plurals.dex_jadx_errors, errors, BuildRun.number(errors)))
            log.info(outDir.name)
            jadx.classes.size
        }
    }

    fun decompileClass(input: File, fqcn: String): String = decompile(input, fqcn).code

    fun decompile(input: File, fqcn: String): DecompiledClass = decompiler(input) {
        val base = fqcn.substringBefore('$')
        classFilter = java.util.function.Predicate { name -> name == fqcn || name.substringBefore('$') == base }
    }.use { jadx ->
        jadx.load()
        val simple = fqcn.substringAfterLast('.')
        val match: JavaClass = jadx.classes.firstOrNull { it.fullName == fqcn }
            ?: jadx.classes.firstOrNull { it.fullName == "defpackage.$fqcn" }
            ?: jadx.classes.firstOrNull { it.fullName.substringAfterLast('.') == simple }
            ?: throw IOException(tr(R.string.dex_class_not_found, fqcn))
        val code = match.code
        DecompiledClass(code, members(match, code))
    }

    private fun members(cls: JavaClass, code: String): List<ClassMember> {
        val starts = lineStarts(code)
        fun lineOf(pos: Int): Int {
            if (pos <= 0) return 1
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= pos) lo = mid else hi = mid - 1
            }
            return lo + 1
        }
        val out = ArrayList<ClassMember>()
        for (f in cls.fields) {
            if (f.defPos <= 0) continue
            val type = simple(f.type)
            out += ClassMember(MemberKind.FIELD, f.name, type, "${f.name}: $type", lineOf(f.defPos), f.accessFlags.isStatic)
        }
        for (m in cls.methods) {
            if (m.defPos <= 0) continue
            val args = m.arguments.joinToString(", ") { simple(it) }
            val kind = if (m.isConstructor) MemberKind.CONSTRUCTOR else MemberKind.METHOD
            val label = if (m.isConstructor) "${cls.name}($args)" else "${m.name}($args): ${simple(m.returnType)}"
            out += ClassMember(kind, m.name, "($args)", label, lineOf(m.defPos), m.accessFlags.isStatic)
        }
        return out.sortedBy { it.line }
    }

    private fun simple(t: ArgType): String = t.toString().replace(qualifier, "")

    private fun lineStarts(code: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in code.indices) if (code[i] == '\n') starts += i + 1
        return starts.toIntArray()
    }
}
