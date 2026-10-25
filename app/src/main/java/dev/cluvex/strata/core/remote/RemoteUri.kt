package dev.cluvex.strata.core.remote

object RemoteUri {
    const val SCHEME = "remote://"

    fun isRemote(path: String?): Boolean = path != null && path.startsWith(SCHEME)

    fun of(connectionId: String, path: String = "/"): String {
        require(connectionId.isNotEmpty() && '/' !in connectionId) { "Invalid storage id" }
        return SCHEME + connectionId + RemotePaths.normalize(path)
    }

    fun parse(uri: String): Pair<String, String>? {
        if (!isRemote(uri)) return null
        val rest = uri.removePrefix(SCHEME)
        val slash = rest.indexOf('/')
        val id = if (slash < 0) rest else rest.substring(0, slash)
        if (id.isEmpty()) return null
        return id to RemotePaths.normalize(if (slash < 0) "/" else rest.substring(slash))
    }

    fun id(uri: String): String = requireNotNull(parse(uri)) { "Not a remote location: $uri" }.first

    fun path(uri: String): String = requireNotNull(parse(uri)) { "Not a remote location: $uri" }.second

    fun parent(uri: String): String? {
        val (id, path) = parse(uri) ?: return null
        return if (path == "/") null else of(id, RemotePaths.parent(path))
    }

    fun child(uri: String, name: String): String {
        val (id, path) = requireNotNull(parse(uri)) { "Not a remote location: $uri" }
        return of(id, RemotePaths.join(path, name))
    }

    fun name(uri: String): String = RemotePaths.name(path(uri))

    fun display(uri: String, storageName: String?): String {
        val (id, path) = parse(uri) ?: return uri
        return "${storageName ?: id.take(8)}:$path"
    }
}
