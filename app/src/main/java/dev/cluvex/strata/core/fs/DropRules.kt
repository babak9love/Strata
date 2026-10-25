package dev.cluvex.strata.core.fs

enum class DropVerdict { MOVE, SAME_FOLDER, INTO_ITSELF }

enum class DropKind { FOLDER, PARENT, PANE }

data class DropSpot(
    val key: String,
    val dir: String,
    val kind: DropKind,
    val pane: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    val area: Float get() = (right - left) * (bottom - top)
}

object DropRules {

    fun verdict(sources: Collection<String>, target: String): DropVerdict {
        val dest = normalize(target)
        for (s in sources) {
            val src = normalize(s)
            if (dest == src || dest.startsWith("$src/")) return DropVerdict.INTO_ITSELF
        }
        if (sources.isNotEmpty() && sources.all { parentOf(it) == dest }) return DropVerdict.SAME_FOLDER
        return DropVerdict.MOVE
    }

    fun parentOf(path: String): String {
        val p = normalize(path)
        val cut = p.lastIndexOf('/')
        return if (cut <= 0) "/" else p.substring(0, cut)
    }

    fun pick(spots: Collection<DropSpot>, x: Float, y: Float, sources: Collection<String>): DropSpot? {
        val panes = spots.filter { it.kind == DropKind.PANE }
        val hits = spots.filter { it.contains(x, y) }
        val row = hits
            .filter { it.kind != DropKind.PANE }
            .filter { spot -> panes.none { it.pane == spot.pane } || panes.any { it.pane == spot.pane && it.contains(x, y) } }
            .minByOrNull { it.area }
        val spot = row ?: hits.firstOrNull { it.kind == DropKind.PANE } ?: return null
        return spot.takeIf { verdict(sources, it.dir) == DropVerdict.MOVE }
    }

    private fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }
}
