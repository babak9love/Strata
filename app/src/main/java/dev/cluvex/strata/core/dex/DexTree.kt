package dev.cluvex.strata.core.dex

sealed interface DexNode {
    data class Package(val path: String, val label: String, val classCount: Int) : DexNode
    data class Class(val cls: DexClass) : DexNode
}

data class DexCrumb(val path: String, val label: String)

class DexTree(classes: List<DexClass>) {

    private val direct = HashMap<String, MutableList<DexClass>>()
    private val subs = HashMap<String, MutableSet<String>>()
    private val counts = HashMap<String, Int>()

    val size: Int = classes.size

    init {
        for (c in classes) {
            val pkg = c.packageName
            direct.getOrPut(pkg) { ArrayList() } += c
            counts[""] = (counts[""] ?: 0) + 1
            var p = pkg
            while (p.isNotEmpty()) {
                counts[p] = (counts[p] ?: 0) + 1
                val parent = p.substringBeforeLast('.', "")
                subs.getOrPut(parent) { LinkedHashSet() } += p
                p = parent
            }
        }
    }

    fun count(pkg: String): Int = counts[pkg] ?: 0

    fun exists(pkg: String): Boolean = pkg.isEmpty() || counts.containsKey(pkg)

    fun children(pkg: String): List<DexNode> {
        val packages = subs[pkg].orEmpty().map { compact(it) }
            .map { DexNode.Package(it, label(pkg, it), count(it)) }
            .sortedWith(compareBy<DexNode.Package> { it.label.lowercase() }.thenBy { it.label })
        val classes = direct[pkg].orEmpty()
            .sortedWith(compareBy<DexClass> { it.simpleName.lowercase() }.thenBy { it.simpleName })
            .map { DexNode.Class(it) }
        return packages + classes
    }

    fun shown(pkg: String): Boolean = pkg.isEmpty() || !direct[pkg].isNullOrEmpty() || subs[pkg].orEmpty().size != 1

    fun landing(pkg: String): String {
        var p = pkg
        while (p.isNotEmpty() && !counts.containsKey(p)) p = p.substringBeforeLast('.', "")
        while (p.isNotEmpty() && !shown(p)) p = p.substringBeforeLast('.', "")
        return p
    }

    fun parent(pkg: String): String? {
        if (pkg.isEmpty()) return null
        var p = pkg.substringBeforeLast('.', "")
        while (!shown(p)) p = p.substringBeforeLast('.', "")
        return p
    }

    fun crumbs(pkg: String): List<DexCrumb> {
        val chain = ArrayList<String>()
        var p: String? = pkg
        while (p != null) {
            chain += p
            p = parent(p)
        }
        chain.reverse()
        return chain.mapIndexed { i, path -> DexCrumb(path, if (i == 0) "" else label(chain[i - 1], path)) }
    }

    private fun compact(pkg: String): String {
        var p = pkg
        while (direct[p].isNullOrEmpty() && subs[p].orEmpty().size == 1) p = subs[p]!!.first()
        return p
    }

    private fun label(parent: String, pkg: String): String = if (parent.isEmpty()) pkg else pkg.removePrefix("$parent.")
}
