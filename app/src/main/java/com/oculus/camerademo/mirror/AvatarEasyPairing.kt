package com.oculus.camerademo.mirror

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object AvatarPairingConfirmation {
    fun code(fingerprint: String, clientNonce: ByteArray, serverNonce: ByteArray): String {
        require(fingerprint.matches(Regex("[0-9A-Fa-f]{64}")) && clientNonce.size == 32 && serverNonce.size == 32)
        val certificate = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(certificate+clientNonce+serverNonce)
            .take(6).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    }
}

/** No avatar or account data is sent here. A PIN is released only after both approvals. */
class AvatarEasyPairing : AutoCloseable {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var accepted = false
    private val confirmation = CountDownLatch(1)
    fun confirm(matches: Boolean) { accepted = matches; confirmation.countDown() }
    fun pair(pc: DiscoveredPc, showCode: (String) -> Unit): AvatarPairing {
        val tls = SSLContext.getInstance("TLS").apply {
            init(null,arrayOf(AvatarPinnedTrustManager(pc.fingerprint)),SecureRandom())
        }
        val connection = tls.socketFactory.createSocket() as SSLSocket
        socket = connection
        connection.use {
            connection.enabledProtocols = arrayOf("TLSv1.2")
            connection.connect(InetSocketAddress(pc.endpoint.address,pc.endpoint.port),3000)
            connection.soTimeout = 3000; connection.startHandshake()
            val output = DataOutputStream(connection.outputStream)
            val input = DataInputStream(connection.inputStream)
            val clientNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            output.writeBytes("AEPAIR01"); output.write(clientNonce); output.flush()
            require(input.read() == 1) { "PC cannot accept a new pairing now. Stop and restart its receiver." }
            val serverNonce = ByteArray(32).also { input.readFully(it) }
            showCode(AvatarPairingConfirmation.code(pc.fingerprint,clientNonce,serverNonce))
            check(confirmation.await(75,TimeUnit.SECONDS) && accepted) { "Pairing canceled or not confirmed" }
            output.writeByte(1); output.flush(); connection.soTimeout = 90000
            check(input.read() == 1) { "Pairing was declined on the PC" }
            val pin = ByteArray(6).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            return AvatarPairing.parse("$pin-${pc.fingerprint}")
        }
    }
    override fun close() { confirm(false); runCatching { socket?.close() }; socket = null }
}
