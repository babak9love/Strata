package dev.cluvex.strata.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class HttpResult(
    val code: Int,
    val message: String,
    val protocol: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val truncated: Boolean,
    val elapsedMs: Long,
    val tls: String?,
)

object HttpTool {
    private const val MAX_BODY = 1 shl 20

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String = "application/json",
        timeoutMs: Long = 20_000,
    ): HttpResult = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val reqBody = body?.toRequestBody(contentType.toMediaTypeOrNull())
        val request = Request.Builder().url(url).method(method.uppercase(), reqBody)
            .headers(headers.toHeaders()).build()
        val started = System.nanoTime()
        client.newCall(request).execute().use { r ->
            val bytes = r.peekBody((MAX_BODY + 1).toLong()).bytes()
            val truncated = bytes.size > MAX_BODY
            val text = String(bytes, 0, minOf(bytes.size, MAX_BODY), Charsets.UTF_8)
            HttpResult(
                code = r.code,
                message = r.message,
                protocol = r.protocol.toString().uppercase(),
                headers = r.headers.map { it.first to it.second },
                body = text,
                truncated = truncated,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                tls = r.handshake?.let { "${it.tlsVersion} · ${it.cipherSuite}" },
            )
        }
    }
}
