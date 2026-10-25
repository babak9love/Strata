package dev.cluvex.strata.core.data

import dev.cluvex.strata.core.model.BehaviorPrefs
import dev.cluvex.strata.core.model.StartFolder

object StartFolders {

    fun resolve(b: BehaviorPrefs, last: LastFolders, home: String): Pair<String, String> = when (b.startFolder) {
        StartFolder.HOME -> home to home
        StartFolder.LAST -> usable(last.left, home) to usable(last.right, home)
        StartFolder.CUSTOM -> usable(b.startPath, home).let { it to it }
    }

    private fun usable(path: String?, home: String): String =
        path?.trim()?.takeIf { it.startsWith("/") && !it.contains('\u0000') }?.trimEnd('/')?.ifEmpty { "/" } ?: home
}
