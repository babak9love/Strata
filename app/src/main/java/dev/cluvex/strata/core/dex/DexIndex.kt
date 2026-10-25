package dev.cluvex.strata.core.dex

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliFormatter
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import java.io.IOException
import java.io.StringWriter

data class DexTarget(val type: String, val member: ClassMember? = null)

class DexIndex private constructor(
    val file: File,
    val dexFiles: List<DexBackedDexFile>,
    private val defs: Map<String, ClassDef>,
) {

    val classes: List<DexClass> = defs.keys.map { DexClass(it) }
        .sortedWith(compareBy<DexClass> { it.fqcn.lowercase() }.thenBy { it.fqcn })

    val size: Int get() = defs.size

    operator fun contains(type: String): Boolean = type in defs

    fun def(type: String): ClassDef? = defs[type]

    fun smali(type: String, readable: Boolean = true): String {
        val def = defs[type] ?: throw IOException(tr(R.string.dex_class_not_in_dex, DexClass(type).fqcn))
        val raw = disassemble(def)
        return if (readable) SmaliText.readable(raw) else raw
    }

    fun resolveMethod(owner: String, name: String, proto: String): String? =
        lookup(owner) { def -> def.methods.any { it.name == name && protoOf(it) == proto } }

    fun resolveField(owner: String, name: String, type: String): String? =
        lookup(owner) { def -> def.fields.any { it.name == name && it.type == type } }

    fun target(ref: SmaliRef): DexTarget? = when (ref) {
        is SmaliRef.Type -> if (ref.type in defs) DexTarget(ref.type) else null
        is SmaliRef.Method -> resolveMethod(ref.owner, ref.name, ref.proto)?.let { t ->
            DexTarget(t, SmaliOutline.findMethod(smali(t), ref.name, ref.proto))
        }
        is SmaliRef.Field -> resolveField(ref.owner, ref.name, ref.type)?.let { t ->
            DexTarget(t, SmaliOutline.findField(smali(t), ref.name, ref.type))
        }
    }

    private fun lookup(owner: String, has: (ClassDef) -> Boolean): String? {
        val chain = ArrayList<ClassDef>()
        val seen = HashSet<String>()
        var t: String? = owner
        while (t != null && seen.add(t)) {
            val def = defs[t] ?: break
            if (has(def)) return t
            chain += def
            t = def.superclass
        }
        val queue = ArrayDeque<String>()
        chain.forEach { queue.addAll(it.interfaces) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (!seen.add(i)) continue
            val def = defs[i] ?: continue
            if (has(def)) return i
            queue.addAll(def.interfaces)
        }
        return null
    }

    companion object {
        const val API = 24

        fun load(file: File): DexIndex {
            val container = DexFileFactory.loadDexContainer(file, null)
            val dexes = ArrayList<DexBackedDexFile>()
            val defs = LinkedHashMap<String, ClassDef>()
            for (name in container.dexEntryNames) {
                val dex = container.getEntry(name)?.dexFile ?: continue
                dexes += dex
                for (c in dex.classes) defs.putIfAbsent(c.type, c)
            }
            if (defs.isEmpty()) throw IOException(tr(R.string.dex_no_classes_in, file.name))
            return DexIndex(file, dexes, defs)
        }

        fun protoOf(m: Method): String = m.parameterTypes.joinToString("", "(", ")") + m.returnType

        fun apiOf(dex: DexBackedDexFile): Int = maxOf(API, dex.opcodes.api)

        fun options(api: Int) = BaksmaliOptions().apply {
            apiLevel = api
            parameterRegisters = true
            localsDirective = true
            sequentialLabels = true
            debugInfo = true
        }

        fun disassemble(def: ClassDef): String {
            val options = options((def as? DexBackedClassDef)?.let { apiOf(it.dexFile) } ?: API)
            val writer = StringWriter()
            BaksmaliFormatter().getWriter(writer).use { ClassDefinition(options, def).writeTo(it) }
            return writer.toString()
        }
    }
}
