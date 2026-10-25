package dev.cluvex.strata.core.dex

import androidx.annotation.StringRes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

enum class DexSearchMode(@StringRes val label: Int) {
    STRINGS(R.string.dex_mode_strings),
    CLASSES(R.string.dex_mode_classes),
    METHODS(R.string.dex_mode_methods),
    FIELDS(R.string.dex_mode_fields),
    CODE(R.string.dex_mode_code),
}

data class DexSearchQuery(
    val text: String,
    val mode: DexSearchMode = DexSearchMode.STRINGS,
    val matchCase: Boolean = false,
    val regex: Boolean = false,
    val wholeWord: Boolean = false,
    val inPackage: String? = null,
)

data class DexHit(val type: String, val line: Int, val text: String, val start: Int, val end: Int) {
    val cls: DexClass get() = DexClass(type)
}

data class DexSearchResult(val hits: List<DexHit>, val truncated: Boolean, val searched: Int, val total: Int)

class TextMatcher(private val query: DexSearchQuery) {

    private val pattern: Regex? = if (query.regex) {
        Regex(query.text, if (query.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
    } else {
        null
    }

    fun find(text: String, from: Int = 0): IntRange? {
        var at = from
        while (at <= text.length) {
            val range = raw(text, at) ?: return null
            if (!query.wholeWord || whole(text, range)) return range
            at = range.first + 1
        }
        return null
    }

    fun matches(text: String): Boolean = find(text) != null

    private fun raw(text: String, from: Int): IntRange? {
        if (pattern != null) {
            var m = pattern.find(text, from) ?: return null
            while (m.range.isEmpty()) {
                if (m.range.first + 1 > text.length) return null
                m = pattern.find(text, m.range.first + 1) ?: return null
            }
            return m.range
        }
        val i = text.indexOf(query.text, from, ignoreCase = !query.matchCase)
        return if (i < 0) null else i until i + query.text.length
    }

    private fun whole(text: String, r: IntRange): Boolean {
        val before = r.first - 1
        val after = r.last + 1
        return (before < 0 || !word(text[before])) && (after >= text.length || !word(text[after]))
    }

    private fun word(c: Char) = c.isLetterOrDigit() || c == '_'

    companion object {
        fun check(query: DexSearchQuery): String? {
            if (query.text.isEmpty()) return tr(R.string.dex_type_to_search)
            if (query.regex) runCatching { Regex(query.text) }.exceptionOrNull()?.let { return it.message ?: tr(R.string.dex_invalid_regex) }
            return null
        }
    }
}

object DexSearch {

    const val MAX_HITS = 2000

    fun run(
        index: DexIndex,
        query: DexSearchQuery,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        isCancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onHits: (List<DexHit>) -> Unit = {},
    ): DexSearchResult {
        TextMatcher.check(query)?.let { throw IllegalArgumentException(it) }
        val matcher = TextMatcher(query)
        val scope = index.classes.filter { inScope(it, query.inPackage) }
        if (query.mode == DexSearchMode.CLASSES) {
            val hits = scope.mapNotNull { c -> matcher.find(c.fqcn)?.let { DexHit(c.type, 1, c.fqcn, it.first, it.last + 1) } }
            val kept = hits.take(MAX_HITS)
            onHits(kept)
            onProgress(scope.size, scope.size)
            return DexSearchResult(kept, hits.size > MAX_HITS, scope.size, scope.size)
        }
        val strings = if (query.mode == DexSearchMode.STRINGS) poolMatches(index, matcher) else emptyMap()
        if (query.mode == DexSearchMode.STRINGS && strings.isEmpty()) {
            onProgress(scope.size, scope.size)
            return DexSearchResult(emptyList(), false, scope.size, scope.size)
        }
        val candidates = scope.filter { c ->
            val def = index.def(c.type) ?: return@filter false
            when (query.mode) {
                DexSearchMode.METHODS -> def.methods.any { matcher.matches(it.name) }
                DexSearchMode.FIELDS -> def.fields.any { matcher.matches(it.name) }
                DexSearchMode.STRINGS -> {
                    val pool = if (def is DexBackedClassDef) strings[def.dexFile].orEmpty() else strings.values.flatten().toSet()
                    pool.isNotEmpty() && usesAny(def, pool)
                }
                else -> true
            }
        }
        val n = candidates.size
        val results = arrayOfNulls<List<DexHit>>(n)
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val found = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val lock = Any()
        var published = 0
        fun publish() {
            synchronized(lock) {
                val fresh = ArrayList<DexHit>()
                while (published < n && results[published] != null) {
                    fresh += results[published]!!
                    published++
                }
                if (fresh.isNotEmpty()) onHits(fresh)
                onProgress(done.get(), n)
            }
        }
        val workers = (0 until threads.coerceAtLeast(1)).map {
            thread(name = "dex-search-$it", isDaemon = true) {
                while (!stop.get() && !isCancelled()) {
                    val i = next.getAndIncrement()
                    if (i >= n) break
                    val hits = runCatching { classHits(index, candidates[i].type, query.mode, matcher) }.getOrDefault(emptyList())
                    results[i] = hits
                    done.incrementAndGet()
                    if (found.addAndGet(hits.size) >= MAX_HITS) stop.set(true)
                    publish()
                }
            }
        }
        workers.forEach { it.join() }
        publish()
        val all = results.filterNotNull().flatten()
        val kept = all.take(MAX_HITS)
        val truncated = all.size > MAX_HITS || (stop.get() && done.get() < n)
        return DexSearchResult(kept, truncated, done.get(), n)
    }

    fun classHits(index: DexIndex, type: String, mode: DexSearchMode, matcher: TextMatcher): List<DexHit> {
        val smali = index.smali(type)
        val out = ArrayList<DexHit>()
        smali.lineSequence().forEachIndexed { i, line ->
            val number = i + 1
            when (mode) {
                DexSearchMode.CODE -> matcher.find(line)?.let { out += hit(type, number, line, it.first, it.last + 1) }
                DexSearchMode.STRINGS -> if (!debugLine(line)) for (lit in SmaliText.literals(line)) {
                    val raw = line.substring(lit.first + 1, lit.last)
                    val value = SmaliText.unescape(raw)
                    val r = matcher.find(value) ?: continue
                    if (raw == value) {
                        out += hit(type, number, line, lit.first + 1 + r.first, lit.first + 2 + r.last)
                    } else {
                        out += hit(type, number, line, lit.first + 1, lit.last)
                    }
                    break
                }
                DexSearchMode.METHODS, DexSearchMode.FIELDS -> {
                    val m = SmaliOutline.member(line, number)
                    val wanted = if (mode == DexSearchMode.FIELDS) m?.kind == MemberKind.FIELD else m != null && m.kind != MemberKind.FIELD
                    if (m != null && wanted) {
                        matcher.find(m.name)?.let { r -> out += hit(type, number, line, m.nameColumn + r.first, m.nameColumn + r.last + 1) }
                    }
                }
                DexSearchMode.CLASSES -> Unit
            }
        }
        return out
    }

    private val debugPrefixes = listOf(".param", ".local", ".end local", ".restart local", ".source", ".parameter")

    private fun debugLine(line: String): Boolean {
        val t = line.trimStart()
        return debugPrefixes.any { t.startsWith(it) }
    }

    private const val SNIPPET = 240

    private fun hit(type: String, line: Int, text: String, start: Int, end: Int): DexHit {
        val lead = text.length - text.trimStart().length
        val shown = text.trim()
        var s = (start - lead).coerceIn(0, shown.length)
        var e = (end - lead).coerceIn(s, shown.length)
        if (shown.length <= SNIPPET) return DexHit(type, line, shown, s, e)
        val from = (s - 60).coerceAtLeast(0)
        val to = (maxOf(e, from) + SNIPPET - 60).coerceAtMost(shown.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < shown.length) "…" else ""
        val cut = prefix + shown.substring(from, to) + suffix
        s = (s - from + prefix.length).coerceIn(0, cut.length)
        e = (e - from + prefix.length).coerceIn(s, cut.length)
        return DexHit(type, line, cut, s, e)
    }

    private fun inScope(c: DexClass, pkg: String?): Boolean =
        pkg.isNullOrEmpty() || c.packageName == pkg || c.packageName.startsWith("$pkg.")

    private fun poolMatches(index: DexIndex, matcher: TextMatcher): Map<DexBackedDexFile, Set<String>> {
        val out = HashMap<DexBackedDexFile, Set<String>>()
        for (dex in index.dexFiles) {
            val found = HashSet<String>()
            val pool = dex.stringSection
            for (i in 0 until pool.size) {
                val s = runCatching { pool[i] }.getOrNull() ?: continue
                if (matcher.matches(s)) found += s
            }
            if (found.isNotEmpty()) out[dex] = found
        }
        return out
    }

    private fun usesAny(def: ClassDef, strings: Set<String>): Boolean {
        if (annotationsUse(def.annotations, strings)) return true
        for (f in def.fields) {
            if (valueUses(f.initialValue, strings) || annotationsUse(f.annotations, strings)) return true
        }
        for (m in def.methods) {
            if (annotationsUse(m.annotations, strings)) return true
            if (m.parameters.any { annotationsUse(it.annotations, strings) }) return true
            val impl = m.implementation ?: continue
            for (ins in impl.instructions) {
                val ref = (ins as? ReferenceInstruction)?.reference as? StringReference ?: continue
                if (ref.string in strings) return true
            }
        }
        return false
    }

    private fun annotationsUse(annotations: Set<Annotation>, strings: Set<String>): Boolean =
        annotations.any { a -> a.elements.any { valueUses(it.value, strings) } }

    private fun valueUses(v: EncodedValue?, strings: Set<String>): Boolean = when (v) {
        is StringEncodedValue -> v.value in strings
        is ArrayEncodedValue -> v.value.any { valueUses(it, strings) }
        is AnnotationEncodedValue -> v.elements.any { valueUses(it.value, strings) }
        else -> false
    }
}
