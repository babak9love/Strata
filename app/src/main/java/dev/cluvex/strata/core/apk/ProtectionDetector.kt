package dev.cluvex.strata.core.apk

import java.io.File
import java.util.zip.ZipFile

object ProtectionDetector {

    private class Marker(val name: String, val matches: (String) -> Boolean)

    private fun lib(prefix: String): (String) -> Boolean = { entry ->
        entry.startsWith("lib/") && entry.substringAfterLast('/').startsWith(prefix)
    }

    private val markers = listOf(
        Marker("360 Jiagu", lib("libjiagu")),
        Marker("Bangcle (SecNeo)", lib("libDexHelper")),
        Marker("Bangcle", lib("libSecShell")),
        Marker("Ijiami") { it == "assets/ijiami.dat" || lib("libexecmain")(it) },
        Marker("Tencent Legu") { lib("libshella")(it) || lib("libshellx")(it) },
        Marker("Alibaba", lib("libmobisec")),
        Marker("Baidu", lib("libbaiduprotect")),
        Marker("NetEase Yidun", lib("libnesec")),
        Marker("NQ Shield", lib("libnqshield")),
        Marker("APKProtect", lib("libAPKProtect")),
        Marker("Kiwisec", lib("libkwscmm")),
    )

    fun detect(entryNames: Sequence<String>): List<String> {
        val found = LinkedHashSet<String>()
        for (entry in entryNames) {
            for (m in markers) if (m.name !in found && m.matches(entry)) found += m.name
        }
        return markers.map { it.name }.filter { it in found }
    }

    fun detect(apk: File): List<String> = ZipFile(apk).use { zip ->
        detect(zip.entries().asSequence().map { it.name })
    }
}
