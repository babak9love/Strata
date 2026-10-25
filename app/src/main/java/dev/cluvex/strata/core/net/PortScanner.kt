package dev.cluvex.strata.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class PortResult(val port: Int, val open: Boolean, val service: String? = ServiceNames.of(port))

object PortScanner {
    suspend fun scan(
        host: String,
        ports: List<Int>,
        timeoutMs: Int = 600,
        concurrency: Int = 256,
    ): List<PortResult> = coroutineScope {
        val gate = Semaphore(concurrency.coerceIn(1, 1024))
        ports.map { port ->
            async(Dispatchers.IO) { gate.withPermit { PortResult(port, isOpen(host, port, timeoutMs)) } }
        }.awaitAll()
    }

    suspend fun openPorts(host: String, ports: List<Int> = ServiceNames.COMMON_PORTS, timeoutMs: Int = 600, concurrency: Int = 256): List<Int> =
        scan(host, ports, timeoutMs, concurrency).filter { it.open }.map { it.port }.sorted()

    private suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true } }.getOrDefault(false)
    }
}
