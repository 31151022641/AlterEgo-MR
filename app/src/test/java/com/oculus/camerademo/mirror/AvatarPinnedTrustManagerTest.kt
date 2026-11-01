package com.oculus.camerademo.mirror

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.*
import org.junit.Test

class AvatarPinnedTrustManagerTest {
    private fun certificate(name: String) = javaClass.getResourceAsStream("/avatar-test-$name.der")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private fun pin(certificate: X509Certificate) = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun rejects(operation: () -> Unit) {
        try { operation(); fail("Untrusted PC certificate was accepted") } catch (_: CertificateException) { }
    }
    @Test fun exactSelfSignedPcCertificateIsAcceptedAsTheExplicitTrustAnchor() {
        val certificate = certificate("valid")
        AvatarPinnedTrustManager(pin(certificate)).checkServerTrusted(arrayOf(certificate),"RSA")
    }
    @Test fun differentOrTamperedFingerprintIsRejectedEvenForValidCertificate() {
        val certificate = certificate("valid")
        val original = pin(certificate)
        val wrong = (if (original[0] == 'A') "B" else "A") + original.drop(1)
        rejects { AvatarPinnedTrustManager(wrong).checkServerTrusted(arrayOf(certificate),"RSA") }
    }
    @Test fun expiredCertificateIsRejectedEvenWhenItsPinMatches() {
        val certificate = certificate("expired")
        rejects { AvatarPinnedTrustManager(pin(certificate)).checkServerTrusted(arrayOf(certificate),"RSA") }
    }
    @Test fun missingCertificateAndClientCertificateCannotBeTrusted() {
        val manager = AvatarPinnedTrustManager(pin(certificate("valid")))
        rejects { manager.checkServerTrusted(null,"RSA") }
        rejects { manager.checkServerTrusted(emptyArray(),"RSA") }
        rejects { manager.checkClientTrusted(arrayOf(certificate("valid")),"RSA") }
        assertEquals(0,manager.acceptedIssuers.size)
    }
}
