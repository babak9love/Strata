package dev.cluvex.strata.core.remote

import androidx.annotation.StringRes
import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

class WebDavFileSystem(private val c: RemoteConnection) : RemoteFileSystem {

    private val https = c.protocol == RemoteProtocol.WEBDAVS
    private var http: OkHttpClient? = null
    private var trust: PinningTrust? = null

    override fun connect() {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        if (!c.anonymous) {
            val creds = Credentials(c.username, c.password)
            val cache = ConcurrentHashMap<String, CachingAuthenticator>()
            val auth = DispatchingAuthenticator.Builder()
                .with("digest", DigestAuthenticator(creds))
                .with("basic", BasicAuthenticator(creds))
                .build()
            builder.authenticator(CachingAuthenticatorDecorator(auth, cache)).addInterceptor(AuthenticationCacheInterceptor(cache))
        }
        if (https) {
            val t = PinningTrust(c.hostKey.takeIf { it.isNotBlank() })
            trust = t
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(t), null) }
            builder.sslSocketFactory(ssl.socketFactory, t)
            builder.hostnameVerifier { host, session -> !t.systemTrusted || OkHostnameVerifier.verify(host, session) }
        }
        http = builder.build()
        val probe = try {
            propfind(c.initialPath.ifBlank { "/" }, depth = 0)
        } catch (e: SSLHandshakeException) {
            val t = trust
            throw when {
                t?.changed == true -> RemoteHostKeyChangedException(tr(R.string.remote_certificate_changed), t.presented)
                else -> RemoteConnectException(tr(R.string.remote_tls_failed, e.message), e)
            }
        } catch (e: IOException) {
            throw if (e is RemoteAuthException) e else RemoteConnectException(tr(R.string.remote_cant_reach_reason, "${c.host}:${c.port}", e.message ?: e.javaClass.simpleName), e)
        }
        if (probe.isEmpty()) throw IOException(tr(R.string.remote_not_webdav_folder, c.initialPath))
    }

    override fun newlyTrustedHostKey(): String? = trust?.takeIf { it.pin == null && !it.systemTrusted }?.presented

    override val isConnected: Boolean get() = http != null

    private fun client() = http ?: throw IOException(tr(R.string.remote_not_connected))

    private fun url(path: String, folder: Boolean = false): HttpUrl {
        val b = HttpUrl.Builder().scheme(if (https) "https" else "http").host(c.host).port(c.port)
        val segments = RemotePaths.normalize(path).removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) b.addPathSegment("") else segments.forEach { b.addPathSegment(it) }
        if (folder && segments.isNotEmpty()) b.addPathSegment("")
        return b.build()
    }

    private fun call(request: Request): Response {
        val r = client().newCall(request).execute()
        if (r.code == 401) {
            r.close()
            throw RemoteAuthException(if (c.anonymous) tr(R.string.remote_needs_login) else tr(R.string.remote_wrong_login))
        }
        return r
    }

    private fun fail(r: Response, @StringRes what: Int): Nothing {
        val code = r.code
        r.close()
        throw when (code) {
            403 -> IOException(tr(R.string.remote_access_denied))
            404 -> IOException(tr(R.string.remote_not_found))
            405 -> IOException(tr(R.string.remote_not_allowed_here, tr(what)))
            409 -> IOException(tr(R.string.remote_parent_missing))
            412 -> IOException(tr(R.string.remote_already_exists))
            423 -> IOException(tr(R.string.remote_locked))
            507 -> IOException(tr(R.string.remote_server_full))
            else -> IOException(tr(R.string.common_failed, tr(what), "HTTP $code"))
        }
    }

    private fun propfind(path: String, depth: Int): List<RemoteEntry> {
        val body = PROPFIND_BODY.toRequestBody(XML)
        val r = call(Request.Builder().url(url(path, folder = depth == 1)).method("PROPFIND", body).header("Depth", depth.toString()).build())
        r.use {
            if (r.code == 404) return emptyList()
            if (r.code != 207) fail(r, R.string.remote_op_listing)
            return parseMultistatus(r.body.byteStream())
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val self = RemotePaths.normalize(path)
        return propfind(path, depth = 1).filter { it.path != self }
    }

    override fun stat(path: String): RemoteEntry? = propfind(path, depth = 0).firstOrNull()

    override fun openInput(path: String): InputStream {
        val r = call(Request.Builder().url(url(path)).get().build())
        if (!r.isSuccessful) fail(r, R.string.remote_op_download)
        return object : FilterInputStream(r.body.byteStream()) {
            override fun close() {
                try { super.close() } finally { r.close() }
            }
        }
    }

    override fun download(path: String, into: OutputStream, onBytes: (Long) -> Unit) {
        openInput(path).use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                into.write(buf, 0, n)
                total += n
                onBytes(total)
            }
        }
    }

    override fun upload(from: InputStream, path: String, onBytes: (Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    total += n
                    onBytes(total)
                }
            }
        }
        call(Request.Builder().url(url(path)).put(body).build()).use { r -> if (!r.isSuccessful) fail(r, R.string.remote_op_upload) }
    }

    override fun mkdir(path: String) {
        call(Request.Builder().url(url(path, folder = true)).method("MKCOL", null).build()).use { r -> if (r.code != 201) fail(r, R.string.remote_op_mkdir) }
    }

    override fun delete(path: String, recursive: Boolean) {
        val e = stat(path) ?: throw IOException(tr(R.string.remote_not_found))
        if (e.isDirectory && !recursive && list(path).isNotEmpty()) throw IOException(tr(R.string.remote_folder_not_empty))
        call(Request.Builder().url(url(path, e.isDirectory)).delete().build()).use { r -> if (!r.isSuccessful) fail(r, R.string.remote_op_delete) }
    }

    override fun rename(from: String, to: String) {
        val folder = stat(from)?.isDirectory == true
        val request = Request.Builder().url(url(from, folder)).method("MOVE", null)
            .header("Destination", url(to, folder).toString())
            .header("Overwrite", "F")
            .build()
        call(request).use { r -> if (r.code != 201 && r.code != 204) fail(r, R.string.remote_op_rename) }
    }

    override fun close() {
        http?.let {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        http = null
    }

    private class PinningTrust(val pin: String?) : X509TrustManager {
        private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        @Volatile var presented: String? = null
        @Volatile var systemTrusted = false
        @Volatile var changed = false

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("No client certificates")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            presented = fingerprint(chain[0])
            systemTrusted = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess
            if (systemTrusted) return
            when {
                pin == null -> return
                pin.equals(presented, ignoreCase = true) -> return
                else -> {
                    changed = true
                    throw CertificateException("Certificate changed: $presented")
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(Locale.ROOT, it) }

        internal fun parseMultistatus(xml: InputStream): List<RemoteEntry> {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml)
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val out = ArrayList<RemoteEntry>()
            for (i in 0 until responses.length) {
                val r = responses.item(i) as Element
                val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
                val path = RemotePaths.normalize(decodeHref(href))
                var isDir = false
                var size = 0L
                var modified = 0L
                val propstats = r.getElementsByTagNameNS("DAV:", "propstat")
                for (j in 0 until propstats.length) {
                    val ps = propstats.item(j) as Element
                    val status = ps.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent.orEmpty()
                    if (" 200 " !in status && !status.endsWith(" 200")) continue
                    if (ps.getElementsByTagNameNS("DAV:", "collection").length > 0) isDir = true
                    ps.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull()?.let { size = it }
                    ps.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.trim()?.let { modified = parseDate(it) }
                }
                out += RemoteEntry(RemotePaths.name(path).ifEmpty { "/" }, path, isDir, if (isDir) 0 else size, modified)
            }
            return out
        }

        internal fun decodeHref(href: String): String {
            val path = if ("://" in href) href.substringAfter("://").substringAfter('/', "").let { "/$it" } else href
            return URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        }

        private fun parseDate(text: String): Long = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(text)?.time
        }.getOrNull() ?: 0L
    }
}
