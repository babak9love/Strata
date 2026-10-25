package dev.cluvex.strata.core.open

object IncomingPaths {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    const val DOWNLOADS = "com.android.providers.downloads.documents"
    const val FALLBACK_NAME = "file"

    private const val RAW = "raw:"
    private const val MAX_NAME_BYTES = 255
    private const val FAT_UNSAFE = "\"*:<>?\\|"

    private val emulated = listOf(
        Regex("/mnt/user/\\d+/emulated/(\\d+)(/.*)?"),
        Regex("/mnt/pass_through/\\d+/emulated/(\\d+)(/.*)?"),
        Regex("/mnt/runtime/[a-z]+/emulated/(\\d+)(/.*)?"),
        Regex("/data/media/(\\d+)(/.*)?"),
    )
    private val volumes = listOf(
        Regex("/mnt/media_rw/([^/]+)(/.*)?"),
        Regex("/mnt/user/\\d+/([^/]+)(/.*)?"),
        Regex("/mnt/pass_through/\\d+/([^/]+)(/.*)?"),
        Regex("/mnt/runtime/[a-z]+/([^/]+)(/.*)?"),
    )
    private val notVolumes = setOf("emulated", "self")
    private val pseudo = listOf("/dev/", "/proc/", "/sys/", "/memfd:")

    fun normalize(path: String): String? {
        if (!path.startsWith("/") || path.endsWith(" (deleted)")) return null
        if (pseudo.any { path.startsWith(it) }) return null
        val clean = path.replace(Regex("/+"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
        if (clean.split('/').any { it == "." || it == ".." }) return null
        for (r in emulated) r.matchEntire(clean)?.let { m -> return "/storage/emulated/${m.groupValues[1]}${m.groupValues[2]}" }
        for (r in volumes) r.matchEntire(clean)?.let { m ->
            if (m.groupValues[1] !in notVolumes) return "/storage/${m.groupValues[1]}${m.groupValues[2]}"
        }
        return clean
    }

    fun fromDocumentId(authority: String?, documentId: String, primary: String): String? = when (authority) {
        DOWNLOADS -> if (documentId.startsWith(RAW)) normalize(documentId.substring(RAW.length)) else null
        EXTERNAL_STORAGE -> {
            val colon = documentId.indexOf(':')
            val volume = if (colon > 0) documentId.substring(0, colon) else ""
            if (volume.isEmpty() || '/' in volume) {
                null
            } else {
                val root = when (volume) {
                    "primary" -> primary
                    "home" -> "$primary/Documents"
                    else -> "/storage/$volume"
                }
                val rest = documentId.substring(colon + 1).trim('/')
                normalize(if (rest.isEmpty()) root else "$root/$rest")
            }
        }
        else -> null
    }

    fun safeName(name: String?): String {
        val clean = name.orEmpty()
            .substringAfterLast('/')
            .filterNot { it.isISOControl() }
            .map { if (it in FAT_UNSAFE) '_' else it }
            .joinToString("")
            .trim()
        if (clean.isEmpty() || clean.all { it == '.' }) return FALLBACK_NAME
        return fitBytes(clean)
    }

    private fun fitBytes(name: String): String {
        if (name.toByteArray().size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        var base = name.substring(0, name.length - ext.length)
        while (base.isNotEmpty() && (base + ext).toByteArray().size > MAX_NAME_BYTES) {
            base = base.substring(0, base.offsetByCodePoints(base.length, -1))
        }
        return (base + ext).ifEmpty { FALLBACK_NAME }
    }
}
