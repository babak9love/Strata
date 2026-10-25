package dev.cluvex.strata.core.dex

sealed interface SmaliRef {
    data class Type(val type: String) : SmaliRef
    data class Method(val owner: String, val name: String, val proto: String) : SmaliRef
    data class Field(val owner: String, val name: String, val type: String) : SmaliRef
}

data class RefSpan(val start: Int, val end: Int, val ref: SmaliRef)

object SmaliRefs {

    private const val DELIMITERS = " \t,{}()=:@"
    private const val NOT_IN_NAME = " \t;(),{}\":<>@="
    private const val PRIMITIVES = "ZBSCIJFDV"

    fun at(line: String, column: Int): SmaliRef? {
        val spans = spans(line)
        spans.firstOrNull { column >= it.start && column < it.end }?.let { return it.ref }
        return spans.lastOrNull { column == it.end && column > it.start }?.ref
    }

    fun spans(line: String): List<RefSpan> {
        val limit = SmaliText.commentStart(line).takeIf { it >= 0 } ?: line.length
        val literals = SmaliText.literals(line)
        fun inLiteral(i: Int) = literals.any { i in it }
        val out = ArrayList<RefSpan>()
        val covered = ArrayList<IntRange>()

        var arrow = line.indexOf("->")
        while (arrow in 0 until limit) {
            if (!inLiteral(arrow)) memberAt(line, arrow, limit)?.let { (spans, range) ->
                out += spans
                covered += range
            }
            arrow = line.indexOf("->", arrow + 2)
        }

        val trimmed = line.trimStart()
        if (trimmed.startsWith(".method")) {
            val open = line.indexOf('(')
            if (open in 0 until limit) protoTypes(line, open, limit)?.let { (spans, end) ->
                out += spans
                covered += open until end
            }
        }

        var i = 0
        while (i < limit) {
            val c = line[i]
            if ((c == 'L' || c == '[') && (i == 0 || line[i - 1] in DELIMITERS) && covered.none { i in it } && !inLiteral(i)) {
                val end = descriptorEnd(line, i, limit)
                if (end != null) {
                    classType(line.substring(i, end))?.let { out += RefSpan(i, end, SmaliRef.Type(it)) }
                    i = end
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun memberAt(line: String, arrow: Int, limit: Int): Pair<List<RefSpan>, IntRange>? {
        var start = arrow
        while (start > 0 && line[start - 1] !in DELIMITERS) start--
        if (start == arrow || descriptorEnd(line, start, arrow) != arrow) return null
        val owner = line.substring(start, arrow)
        val nameStart = arrow + 2
        var nameEnd = nameStart
        while (nameEnd < limit && line[nameEnd] != '(' && line[nameEnd] != ':' && line[nameEnd] != ' ' && line[nameEnd] != '\t') nameEnd++
        if (nameEnd == nameStart || nameEnd >= limit) return null
        val name = line.substring(nameStart, nameEnd)
        val spans = ArrayList<RefSpan>()
        classType(owner)?.let { spans += RefSpan(start, arrow, SmaliRef.Type(it)) }
        val ownerType = classType(owner) ?: owner
        return when (line[nameEnd]) {
            '(' -> {
                val (types, protoEnd) = protoTypes(line, nameEnd, limit) ?: return null
                val proto = line.substring(nameEnd, protoEnd)
                spans += types
                spans += RefSpan(arrow, protoEnd, SmaliRef.Method(ownerType, name, proto))
                spans to (start until protoEnd)
            }
            ':' -> {
                val typeEnd = descriptorEnd(line, nameEnd + 1, limit) ?: return null
                val type = line.substring(nameEnd + 1, typeEnd)
                classType(type)?.let { spans += RefSpan(nameEnd + 1, typeEnd, SmaliRef.Type(it)) }
                spans += RefSpan(arrow, typeEnd, SmaliRef.Field(ownerType, name, type))
                spans to (start until typeEnd)
            }
            else -> null
        }
    }

    private fun protoTypes(line: String, open: Int, limit: Int): Pair<List<RefSpan>, Int>? {
        if (line[open] != '(') return null
        val spans = ArrayList<RefSpan>()
        var i = open + 1
        while (i < limit && line[i] != ')') {
            val end = descriptorEnd(line, i, limit) ?: return null
            classType(line.substring(i, end))?.let { spans += RefSpan(i, end, SmaliRef.Type(it)) }
            i = end
        }
        if (i >= limit) return null
        val retStart = i + 1
        val retEnd = descriptorEnd(line, retStart, limit) ?: return null
        classType(line.substring(retStart, retEnd))?.let { spans += RefSpan(retStart, retEnd, SmaliRef.Type(it)) }
        return spans to retEnd
    }

    fun descriptorEnd(line: String, from: Int, limit: Int): Int? {
        var i = from
        while (i < limit && line[i] == '[') i++
        if (i >= limit) return null
        val c = line[i]
        if (c in PRIMITIVES) return if (c == 'V' && i > from) null else i + 1
        if (c != 'L') return null
        var j = i + 1
        while (j < limit && line[j] != ';') {
            if (line[j] in NOT_IN_NAME) return null
            j++
        }
        if (j >= limit || j == i + 1) return null
        return j + 1
    }

    fun classType(descriptor: String): String? {
        val bare = descriptor.trimStart('[')
        return if (bare.length > 2 && bare[0] == 'L' && bare.last() == ';') bare else null
    }
}
