package com.oculus.camerademo.mirror

import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLPeerUnverifiedException

data class AvatarPairing(val pin: String, val certificateSha256: String) {
    val code get() = "$pin-$certificateSha256"
    companion object {
        fun parse(value: String): AvatarPairing {
            val clean = value.trim().uppercase()
            require(clean.matches(Regex("[0-9]{6}-[0-9A-F]{64}"))) {
                "Paste the PC's full secure pairing code: six digits, a hyphen and its certificate fingerprint"
            }
            return AvatarPairing(clean.substring(0,6),clean.substring(7))
        }
        fun filterInput(value: String) = value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' || it == '-' }.take(71)
    }
}

data class AvatarEndpoint(val address: String, val port: Int) {
    val key get() = "$address:$port"
    companion object {
        const val DEFAULT_PORT = 38991
        fun parse(value: String): AvatarEndpoint {
            val parts = value.trim().split(':')
            val octets = parts.firstOrNull()?.split('.')?.map { it.toIntOrNull() }
            val port = if (parts.size == 1) DEFAULT_PORT else parts.getOrNull(1)?.toIntOrNull()
            require(parts.size in 1..2 && octets?.size == 4 && octets.all { it != null && it in 0..255 } &&
                port in 1024..65535) { "Enter the PC's local IPv4 address, optionally followed by :port" }
            val bytes = octets!!.map { it!! }
            require(bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168) || bytes[0] == 127) { "Use the PC's private LAN address" }
            return AvatarEndpoint(bytes.joinToString("."),port!!)
        }
    }
}

object AvatarStreamProtocol {
    const val MAGIC = "AEMR0005"
    const val MAX_FRAME_AGE_MS = 750L
    const val MAX_JPEG_BYTES = 8 * 1024 * 1024
    fun fresh(capturedAt: Long, now: Long) = capturedAt > 0 && now >= capturedAt && now - capturedAt <= MAX_FRAME_AGE_MS
    fun writePairing(output: DataOutputStream, pairing: AvatarPairing) {
        output.writeBytes(MAGIC); output.writeBytes(pairing.pin); output.flush()
    }
    fun writeFrame(output: DataOutputStream, sequence: Long, capturedAt: Long, jpeg: ByteArray) {
        require(sequence >= 0 && capturedAt > 0 && jpeg.isNotEmpty() && jpeg.size <= MAX_JPEG_BYTES)
        output.writeLong(sequence); output.writeLong(capturedAt); output.writeInt(jpeg.size)
        output.write(jpeg); output.flush()
    }
    fun writeNoImage(output: DataOutputStream, sequence: Long, senderUptime: Long) {
        require(sequence >= 0 && senderUptime > 0)
        output.writeLong(sequence); output.writeLong(senderUptime); output.writeInt(0); output.flush()
    }
    fun acceptFrameReply(input: java.io.DataInputStream, sequence: Long): Int {
        val received = input.readLong(); val result = input.readUnsignedByte()
        require(received == sequence && result in 0..2) { "PC returned an invalid video receipt" }
        return result
    }
    fun requireTrustedIdentity(savedPin: String?, presentedPin: String) {
        require(savedPin == null || savedPin == presentedPin) {
            "This PC's identity changed. Choose Forget pairing, then paste the new code displayed on that PC."
        }
    }
    fun acceptPairingReply(reply: Int) {
        when (reply) {
            1 -> return
            0 -> throw SecurityException("Pairing code rejected. Paste the current code from the PC.")
            2 -> throw IOException("The PC webcam is busy with another headset")
            -1 -> throw EOFException("PC disconnected during pairing")
            else -> throw IllegalArgumentException("The PC returned an unsupported pairing reply")
        }
    }
    /** A TLS connection can close during a PC restart. Only identity/PIN validation failures prohibit retries. */
    fun permanentFailure(error: Throwable): Boolean {
        if (error is SecurityException || error is IllegalArgumentException) return true
        var cause: Throwable? = error
        repeat(16) {
            if (cause is CertificateException || cause is SSLPeerUnverifiedException) return true
            val previous = cause
            cause = cause?.cause
            if (cause == null || cause === previous) return false
        }
        return false
    }
}

/** The full code physically displayed by the PC is the trust anchor, never arbitrary LAN certificates. */
class AvatarPinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not accepted")
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("The PC supplied no certificate")
        certificate.checkValidity()
        val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        if (!MessageDigest.isEqual(expected,actual)) throw CertificateException("The PC certificate does not match its pairing code")
    }
}
