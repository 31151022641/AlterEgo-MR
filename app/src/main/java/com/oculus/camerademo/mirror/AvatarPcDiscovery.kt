package com.oculus.camerademo.mirror

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

data class DiscoveredPc(val name: String, val endpoint: AvatarEndpoint, val fingerprint: String)

object AvatarDiscoveryProtocol {
    fun parse(reply: String, nonce: String): DiscoveredPc? = runCatching {
        val fields = reply.split('|')
        require(fields.size == 6 && fields[0] == "AEGO_REPLY1" && fields[1] == nonce)
        require(fields[4].matches(Regex("[0-9A-Fa-f]{64}")))
        val endpoint = AvatarEndpoint.parse("${fields[2]}:${fields[3]}")
        require(fields[5].length in 1..60 && fields[5].all { it.code in 32..126 })
        DiscoveredPc(fields[5],endpoint,fields[4].uppercase())
    }.getOrNull()
}

/** Discovery never authorizes video. The returned identity must be confirmed. */
object AvatarPcDiscovery {
    fun scan(): List<DiscoveredPc> {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        val query = "AEGO_DISCOVER1|$nonce".toByteArray(Charsets.US_ASCII)
        val destinations = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
            network.interfaceAddresses.mapNotNull { it.broadcast }.forEach { destinations += it }
        }
        val results = linkedMapOf<String,DiscoveredPc>()
        DatagramSocket().use { socket ->
            socket.broadcast = true; socket.soTimeout = 350
            fun send() { destinations.forEach { address -> runCatching { socket.send(DatagramPacket(query,query.size,address,38992)) } } }
            send()
            val deadline = System.nanoTime()+3_000_000_000L
            var repeated = false
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                if (!repeated && deadline-System.nanoTime() < 2_000_000_000L) { repeated = true; send() }
                val packet = DatagramPacket(ByteArray(512),512)
                runCatching { socket.receive(packet) }.onSuccess {
                    AvatarDiscoveryProtocol.parse(String(packet.data,0,packet.length,Charsets.US_ASCII),nonce)?.let {
                        // The advertised address must be the responding PC, not a third party.
                        if (it.endpoint.address == packet.address.hostAddress)
                            results["${it.endpoint.key}:${it.fingerprint}"] = it
                    }
                }
            }
        }
        return results.values.take(12).toList()
    }
}
