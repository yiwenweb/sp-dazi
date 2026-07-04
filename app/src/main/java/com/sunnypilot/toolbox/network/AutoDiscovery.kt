package com.sunnypilot.toolbox.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object AutoDiscovery {

    data class DiscoveredHost(
        val host: String,
        val port: Int
    )

    suspend fun findSshHosts(
        port: Int = 22,
        timeoutMs: Int = 400,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val subnets = getLocalSubnets()
        if (subnets.isEmpty()) return@withContext emptyList()

        val candidates = subnets.flatMap { subnet ->
            (1..254).map { "${subnet}.$it" }
        }.distinct()

        val results = mutableListOf<DiscoveredHost>()
        val batchSize = 50

        candidates.chunked(batchSize).forEachIndexed { index, batch ->
            val deferred = batch.map { ip ->
                async {
                    if (isPortOpen(ip, port, timeoutMs)) {
                        DiscoveredHost(ip, port)
                    } else null
                }
            }
            results.addAll(deferred.awaitAll().filterNotNull())
            onProgress((index + 1) * batchSize, candidates.size)
        }

        results
    }

    private fun getLocalSubnets(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it.hostAddress.contains(".") }
                .map { address ->
                    val bytes = address.address
                    if (bytes.size == 4) {
                        "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}"
                    } else null
                }
                .filterNotNull()
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
