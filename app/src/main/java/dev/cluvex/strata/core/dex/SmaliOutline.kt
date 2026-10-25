package dev.cluvex.strata.core.dex

enum class MemberKind { FIELD, CONSTRUCTOR, METHOD }

data class ClassMember(
    val kind: MemberKind,
    val name: String,
    val descriptor: String,
    val label: String,
    val line: Int,
    val isStatic: Boolean = false,
    val nameColumn: Int = 0,
)

object SmaliOutline {

    fun parse(smali: String): List<ClassMember> {
        val out = ArrayList<ClassMember>()
        smali.lineSequence().forEachIndexed { index, line -> member(line, index + 1)?.let { out += it } }
        return out
    }

    fun member(line: String, number: Int = 0): ClassMember? {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        return when {
            trimmed.startsWith(".method ") -> method(trimmed, number, indent)
            trimmed.startsWith(".field ") -> field(trimmed, number, indent)
            else -> null
        }
    }

    private fun method(text: String, number: Int, indent: Int): ClassMember? {
        val open = text.indexOf('(')
        if (open < 0) return null
        val nameStart = text.lastIndexOf(' ', open) + 1
        val name = text.substring(nameStart, open)
        if (name.isEmpty()) return null
        val protoEnd = SmaliRefs.descriptorEnd(text, text.indexOf(')', open) + 1, text.length) ?: return null
        val proto = text.substring(open, protoEnd)
        val flags = text.substring(".method ".length, nameStart).trim().split(' ').filter { it.isNotEmpty() }
        val kind = if (name == "<init>" || name == "<clinit>") MemberKind.CONSTRUCTOR else MemberKind.METHOD
        return ClassMember(kind, name, proto, "$name(${params(proto).joinToString(", ") { readable(it) }}): ${readable(proto.substringAfter(')'))}", number, "static" in flags, indent + nameStart)
    }

    private fun field(text: String, number: Int, indent: Int): ClassMember? {
        val body = text.substring(".field ".length).substringBefore(" = ")
        val colon = body.lastIndexOf(':')
        if (colon < 0) return null
        val nameStart = body.lastIndexOf(' ', colon) + 1
        val name = body.substring(nameStart, colon)
        val type = body.substring(colon + 1).trim()
        if (name.isEmpty() || type.isEmpty()) return null
        val flags = body.substring(0, nameStart).trim().split(' ').filter { it.isNotEmpty() }
        return ClassMember(MemberKind.FIELD, name, type, "$name: ${readable(type)}", number, "static" in flags, indent + ".field ".length + nameStart)
    }

    fun params(proto: String): List<String> {
        val out = ArrayList<String>()
        val close = proto.indexOf(')')
        var i = 1
        while (i in 1 until close) {
            val end = SmaliRefs.descriptorEnd(proto, i, close) ?: break
            out += proto.substring(i, end)
            i = end
        }
        return out
    }

    fun readable(descriptor: String): String {
        val dims = descriptor.takeWhile { it == '[' }.length
        val base = descriptor.substring(dims)
        val name = when (base) {
            "Z" -> "boolean"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            "V" -> "void"
            else -> base.removePrefix("L").removeSuffix(";").substringAfterLast('/')
        }
        return name + "[]".repeat(dims)
    }

    fun findMethod(smali: String, name: String, proto: String): ClassMember? =
        parse(smali).firstOrNull { it.kind != MemberKind.FIELD && it.name == name && it.descriptor == proto }

    fun findField(smali: String, name: String, type: String? = null): ClassMember? =
        parse(smali).firstOrNull { it.kind == MemberKind.FIELD && it.name == name && (type == null || it.descriptor == type) }
}
