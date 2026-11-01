package com.oculus.camerademo.mirror

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class AvatarPairingTest {
    private val fingerprint = "AB".repeat(32)
    private fun rejects(operation: () -> Unit) {
        try { operation(); fail("Invalid pairing or frame was accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun oldSixDigitCodeCannotDowngradeToPlaintext() {
        rejects { AvatarPairing.parse("123456") }
        rejects { AvatarPairing.parse("123456-${fingerprint.dropLast(1)}") }
        rejects { AvatarPairing.parse("123456-${fingerprint}00") }
        rejects { AvatarPairing.parse("123456-${"ZZ".repeat(32)}") }
    }
    @Test fun pastedCodeKeepsLeadingZeroPinAndFullCertificate() {
        val pairing = AvatarPairing.parse("  000123-${fingerprint.lowercase()}  ")
        assertEquals("000123",pairing.pin)
        assertEquals(fingerprint,pairing.certificateSha256)
        assertEquals("000123-$fingerprint",AvatarPairing.filterInput("000123-\n${fingerprint.lowercase()}"))
    }
    @Test fun endpointCanonicalizationCannotBypassRememberedIdentity() {
        assertEquals("192.168.1.2:5555",AvatarEndpoint.parse("192.168.001.002:5555").key)
        assertEquals("127.0.0.1:5555",AvatarEndpoint.parse("127.0.0.1:5555").key)
    }
    @Test fun barePcIpv4UsesTheCompanionsDefaultPortAndTheSameTrustedIdentityKey() {
        val bare = AvatarEndpoint.parse(" 192.168.001.130 ")
        assertEquals(38991,bare.port)
        assertEquals(AvatarEndpoint.parse("192.168.1.130:38991"),bare)
        assertEquals("192.168.1.130:38991",bare.key)
        assertEquals(38991,AvatarEndpoint.parse("10.0.0.2").port)
    }
    @Test fun explicitInvalidOrEmptyPortCannotSilentlyFallBackToDefault() {
        listOf("192.168.1.130:","192.168.1.130:abc","192.168.1.130:0","192.168.1.130:65536",
            "192.168.1.130:38991:1").forEach { rejects { AvatarEndpoint.parse(it) } }
        assertEquals(5555,AvatarEndpoint.parse("192.168.1.130:5555").port)
    }
    @Test fun unsafeOrMalformedEndpointsAreRejectedBeforeOpeningSocket() {
        listOf("8.8.8.8:5555","localhost:5555","172.32.0.1:5555","192.168.1.300:5555",
            "192.168.1.2:443","192.168.1.2:65536","192.168.1.2:5555:1","192.168.1")
            .forEach { rejects { AvatarEndpoint.parse(it) } }
    }
    @Test fun savedIdentityRequiresExplicitForgetBeforeCertificateChange() {
        AvatarStreamProtocol.requireTrustedIdentity(null,fingerprint)
        AvatarStreamProtocol.requireTrustedIdentity(fingerprint,fingerprint)
        rejects { AvatarStreamProtocol.requireTrustedIdentity(fingerprint,"CD".repeat(32)) }
    }
    @Test fun pairingWireContainsNewMagicAndPinInsideEncryptedTransport() {
        val bytes = ByteArrayOutputStream()
        AvatarStreamProtocol.writePairing(DataOutputStream(bytes),AvatarPairing.parse("000123-$fingerprint"))
        assertArrayEquals("AEMR0005000123".toByteArray(Charsets.US_ASCII),bytes.toByteArray())
    }
    @Test fun sequenceAndCaptureUptimeUseBigEndianBeforeJpeg() {
        val bytes = ByteArrayOutputStream()
        val jpeg = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
        AvatarStreamProtocol.writeFrame(DataOutputStream(bytes),0x0102030405060708L,123456789L,jpeg)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8),bytes.toByteArray().take(8).toByteArray())
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(0x0102030405060708L,input.readLong())
        assertEquals(123456789L,input.readLong())
        assertEquals(jpeg.size,input.readInt())
        assertArrayEquals(jpeg,ByteArray(jpeg.size).also { input.readFully(it) })
        assertEquals(-1,input.read())
    }
    @Test fun noImageRecordHasSameHeaderAndZeroPayloadWithoutAvatarPixels() {
        val bytes = ByteArrayOutputStream()
        AvatarStreamProtocol.writeNoImage(DataOutputStream(bytes),7,123456)
        assertEquals(20,bytes.size())
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(7L,input.readLong()); assertEquals(123456L,input.readLong())
        assertEquals(0,input.readInt()); assertEquals(-1,input.read())
        rejects { AvatarStreamProtocol.writeNoImage(DataOutputStream(bytes),-1,123456) }
        rejects { AvatarStreamProtocol.writeNoImage(DataOutputStream(bytes),7,0) }
    }
    @Test fun staleMissingAndFutureFrameTimesCannotBeStreamed() {
        assertTrue(AvatarStreamProtocol.fresh(1000,1750))
        assertFalse(AvatarStreamProtocol.fresh(1000,1751))
        assertFalse(AvatarStreamProtocol.fresh(1001,1000))
        assertFalse(AvatarStreamProtocol.fresh(0,1000))
        assertFalse(AvatarStreamProtocol.fresh(Long.MIN_VALUE,Long.MAX_VALUE))
    }
    @Test fun malformedFrameHeadersAndOversizedFramesCannotBeWritten() {
        val output = DataOutputStream(ByteArrayOutputStream())
        rejects { AvatarStreamProtocol.writeFrame(output,-1,1,byteArrayOf(1)) }
        rejects { AvatarStreamProtocol.writeFrame(output,0,0,byteArrayOf(1)) }
        rejects { AvatarStreamProtocol.writeFrame(output,0,1,byteArrayOf()) }
        rejects { AvatarStreamProtocol.writeFrame(output,0,1,ByteArray(AvatarStreamProtocol.MAX_JPEG_BYTES+1)) }
    }
    @Test fun busyReplyAndPairingDisconnectAreTransientRatherThanIncorrectPin() {
        AvatarStreamProtocol.acceptPairingReply(1)
        for (reply in listOf(2,-1)) {
            try { AvatarStreamProtocol.acceptPairingReply(reply); fail("Expected retryable pairing error") }
            catch (error: IOException) { assertFalse(AvatarStreamProtocol.permanentFailure(error)) }
        }
    }
    @Test fun incorrectPinAndUnknownPairingReplyNeverRetryOrSendFrames() {
        try { AvatarStreamProtocol.acceptPairingReply(0); fail("Incorrect PIN accepted") }
        catch (error: SecurityException) { assertTrue(AvatarStreamProtocol.permanentFailure(error)) }
        try { AvatarStreamProtocol.acceptPairingReply(9); fail("Unknown protocol reply accepted") }
        catch (error: IllegalArgumentException) { assertTrue(AvatarStreamProtocol.permanentFailure(error)) }
    }
    @Test fun tlsHandshakeInterruptedByPcRestartAndSocketDisconnectCanRetry() {
        val interrupted = SSLHandshakeException("Remote host terminated the handshake").apply { initCause(EOFException()) }
        assertFalse(AvatarStreamProtocol.permanentFailure(interrupted))
        assertFalse(AvatarStreamProtocol.permanentFailure(SocketException("Connection reset")))
        assertFalse(AvatarStreamProtocol.permanentFailure(EOFException("PC disconnected")))
    }
    @Test fun certificateMismatchAndExpirationRemainFatalThroughTlsWrapper() {
        for (certificateError in listOf(CertificateException("PC fingerprint mismatch"),CertificateExpiredException())) {
            val handshake = SSLHandshakeException("Certificate rejected").apply { initCause(certificateError) }
            assertTrue(AvatarStreamProtocol.permanentFailure(handshake))
            assertTrue(AvatarStreamProtocol.permanentFailure(IOException("Wrapped by provider",handshake)))
        }
    }
}
