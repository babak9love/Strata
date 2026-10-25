package dev.cluvex.strata.core.share

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LanHttpServer(
    private val root: SafeRoot,
    private val password: String,
    private val indexHtml: ByteArray,
    private val readOnly: Boolean = false,
) {
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val throttle = LoginThrottle()
    private val random = SecureRandom()
    private var server: EmbeddedServer<*, *>? = null

    fun start(host: String, port: Int): Int {
        require(password.isNotEmpty()) { tr(R.string.share_set_password) }
        val s = embeddedServer(CIO, host = host, port = port) { module() }.start(wait = false)
        server = s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        server = null
        sessions.clear()
    }

    val isRunning: Boolean get() = server != null

    private fun token(): String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun ApplicationCall.signedIn() = request.cookies["sid"]?.let { it in sessions } == true

    private fun Application.module() {
        intercept(ApplicationCallPipeline.Plugins) {
            val c = call
            val path = c.request.path()
            val write = c.request.httpMethod != HttpMethod.Get && c.request.httpMethod != HttpMethod.Head
            val peer = runCatching { InetAddress.getByName(c.request.local.remoteAddress) }.getOrNull()
            val host = c.request.headers[HttpHeaders.Host]
            val reject: HttpStatusCode? = when {
                peer == null || !isLanPeer(peer) -> HttpStatusCode.Forbidden
                !isIpLiteralHost(host) -> HttpStatusCode(421, "Misdirected Request")
                write && (c.request.headers["X-Strata"] != "1" || c.request.headers[HttpHeaders.Origin].let { it != null && it != "http://$host" }) ->
                    HttpStatusCode.Forbidden
                path.startsWith("/api/") && path != "/api/login" && !c.signedIn() -> HttpStatusCode.Unauthorized
                readOnly && write && path != "/api/login" -> HttpStatusCode.Forbidden
                else -> null
            }
            c.response.header("X-Content-Type-Options", "nosniff")
            c.response.header("X-Frame-Options", "DENY")
            c.response.header("Referrer-Policy", "no-referrer")
            c.response.header(HttpHeaders.CacheControl, "no-store")
            if (reject != null) {
                c.respond(reject)
                finish()
            }
        }
        routing {
            get("/") {
                call.response.header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:")
                call.respondBytes(indexHtml, ContentType.Text.Html.withParameter("charset", "utf-8"))
            }
            post("/api/login") {
                val ip = call.request.local.remoteAddress
                val wait = throttle.retryAfterMs(ip)
                if (wait > 0) {
                    call.response.header(HttpHeaders.RetryAfter, ((wait + 999) / 1000).toString())
                    return@post call.respond(HttpStatusCode.TooManyRequests)
                }
                val given = call.receiveParameters()["password"].orEmpty()
                if (!MessageDigest.isEqual(given.toByteArray(), password.toByteArray())) {
                    throttle.failed(ip)
                    return@post call.respond(HttpStatusCode.Unauthorized)
                }
                throttle.succeeded(ip)
                val sid = token().also(sessions::add)
                call.response.header(HttpHeaders.SetCookie, "sid=$sid; Path=/; HttpOnly; SameSite=Strict")
                call.respond(HttpStatusCode.NoContent)
            }
            get("/api/info") {
                call.respondText(buildJsonObject { put("readOnly", readOnly); put("name", root.root.name) }.toString(), ContentType.Application.Json)
            }
            get("/api/list") {
                val dir = root.resolve(call.request.queryParameters["path"])?.takeIf { it.isDirectory }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val json = buildJsonArray {
                    dir.listFiles().orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })).forEach { f ->
                        addJsonObject {
                            put("name", f.name)
                            put("dir", f.isDirectory)
                            put("size", if (f.isDirectory) 0 else f.length())
                            put("mtime", f.lastModified())
                        }
                    }
                }
                call.respondText(json.toString(), ContentType.Application.Json)
            }
            get("/api/download") {
                val f = root.resolve(call.request.queryParameters["path"])?.takeIf { it.isFile }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val ascii = f.name.map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }.joinToString("")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, ascii)
                        .withParameter(ContentDisposition.Parameters.FileNameAsterisk, f.name, encodeValue = true)
                        .toString(),
                )
                call.respondFile(f)
            }
            post("/api/upload") {
                val dir = root.resolve(call.request.queryParameters["dir"])?.takeIf { it.isDirectory }
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val saved = ArrayList<String>()
                var clash = false
                call.receiveMultipart(formFieldLimit = Long.MAX_VALUE).forEachPart { part ->
                    try {
                        if (part is PartData.FileItem) {
                            val name = uploadName(part.originalFileName) ?: return@forEachPart
                            val tmp = File(dir, ".$name.part")
                            try {
                                part.provider().copyAndClose(tmp.writeChannel())
                                val target = File(dir, name)
                                if (target.exists() || !tmp.renameTo(target)) clash = true else saved += name
                            } finally {
                                tmp.delete()
                            }
                        }
                    } finally {
                        part.dispose()
                    }
                }
                val body = buildJsonArray { saved.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }.toString()
                call.respondText(body, ContentType.Application.Json, if (clash) HttpStatusCode.Conflict else HttpStatusCode.OK)
            }
            post("/api/mkdir") {
                val parent = root.resolve(call.request.queryParameters["dir"])?.takeIf { it.isDirectory }
                val name = safeChildName(call.request.queryParameters["name"])
                if (parent == null || name == null) return@post call.respond(HttpStatusCode.BadRequest)
                call.respond(if (File(parent, name).mkdir()) HttpStatusCode.Created else HttpStatusCode.Conflict)
            }
            post("/api/rename") {
                val from = root.resolve(call.request.queryParameters["path"])?.takeIf { it.exists() && it.canonicalFile != root.root }
                val name = safeChildName(call.request.queryParameters["to"])
                if (from == null || name == null) return@post call.respond(HttpStatusCode.BadRequest)
                val to = File(from.parentFile, name)
                call.respond(if (!to.exists() && from.renameTo(to)) HttpStatusCode.NoContent else HttpStatusCode.Conflict)
            }
            post("/api/delete") {
                val f = root.resolve(call.request.queryParameters["path"])?.takeIf { it.exists() && it.canonicalFile != root.root }
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(if (FsUtil.deleteRecursively(f)) HttpStatusCode.NoContent else HttpStatusCode.InternalServerError)
            }
        }
    }

    companion object {
        fun page(): ByteArray = LanHttpServer::class.java.getResourceAsStream("/strata-web/index.html")!!.use { it.readBytes() }
    }
}
