package com.remote.dipgonc

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LanDiscovery {

    private const val CONNECT_TIMEOUT_MS = 120
    private const val SCAN_TIMEOUT_MS = 1800
    private const val WORKERS = 64

    fun findHttpHost(port: Int): String? {
        val candidates = buildCandidates()
        if (candidates.isEmpty()) return null

        val executor = Executors.newFixedThreadPool(WORKERS)
        val completion: CompletionService<String?> = ExecutorCompletionService(executor)
        try {
            candidates.forEach { host ->
                completion.submit(Callable<String?> {
                    if (isPortOpen(host, port)) host else null
                })
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SCAN_TIMEOUT_MS.toLong())
            repeat(candidates.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
                val host = future.get()
                if (host != null) return host
            }
            return null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun buildCandidates(): List<String> {
        val ownHosts = linkedSetOf<String>()
        val subnets = linkedSetOf<String>()

        Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            Collections.list(networkInterface.inetAddresses).forEach { address ->
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    val host = address.hostAddress ?: return@forEach
                    ownHosts.add(host)
                    subnetOf(host)?.let { subnets.add(it) }
                }
            }
        }

        subnets.add("192.168.43")
        subnets.add("192.168.49")
        subnets.add("192.168.137")
        subnets.add("172.20.10")

        val hosts = ArrayList<String>(subnets.size * 254)
        subnets.forEach { subnet ->
            for (i in 1..254) {
                val host = "$subnet.$i"
                if (!ownHosts.contains(host)) {
                    hosts.add(host)
                }
            }
        }
        return hosts
    }

    private fun subnetOf(host: String): String? {
        val lastDot = host.lastIndexOf('.')
        return if (lastDot > 0) host.substring(0, lastDot) else null
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
