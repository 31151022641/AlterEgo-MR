package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test
import com.oculus.camerademo.ml.FaceEmotionState
import java.io.*

class AvatarUpgradeTest {
    private val fingerprint = "00".repeat(32)
    private fun face(left: Float = .3f) = AvatarVideoFace(left,.2f,left+.2f,.6f,
        floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f),FaceEmotionState())
    @Test fun confirmationChangesWithEitherNonceOrIdentity() {
        val a = ByteArray(32); val b = ByteArray(32)
        val code = AvatarPairingConfirmation.code(fingerprint,a,b)
        assertTrue(code.matches(Regex("[0-9A-F]{4}(-[0-9A-F]{4}){2}")))
        a[0] = 1; assertNotEquals(code,AvatarPairingConfirmation.code(fingerprint,a,b))
        a[0] = 0; b[0] = 1; assertNotEquals(code,AvatarPairingConfirmation.code(fingerprint,a,b))
        b[0] = 0; assertNotEquals(code,AvatarPairingConfirmation.code("11".repeat(32),a,b))
    }
    @Test fun discoveryCannotRedirectToPublicAddressesOrExposePins() {
        val reply = "AEGO_REPLY1|nonce|192.168.1.12|38991|$fingerprint|My PC"
        val pc = AvatarDiscoveryProtocol.parse(reply,"nonce")!!
        assertEquals("My PC",pc.name)
        assertNull(AvatarDiscoveryProtocol.parse(reply,"old nonce"))
        assertNull(AvatarDiscoveryProtocol.parse(reply.replace("192.168.1.12","8.8.8.8"),"nonce"))
        assertNull(AvatarDiscoveryProtocol.parse(reply+"|123456","nonce"))
    }
    @Test fun thermalAndSlowNetworkReduceSchedulingAndJpegSize() {
        val budget = AvatarVideoBudget()
        assertEquals(33L,budget.intervalMs)
        repeat(15) { budget.network(180) }
        assertEquals(66L,budget.intervalMs); assertEquals(82,budget.jpegQuality)
        repeat(30) { budget.network(10); budget.processing(10) }
        assertEquals(33L,budget.intervalMs)
        budget.quality = AvatarVideoQuality.SMOOTH; budget.thermalStatus = 3
        assertEquals(66L,budget.intervalMs)
    }
    @Test fun maximumPreservesDetailWhileReducingCadenceUnderLoad() {
        val budget = AvatarVideoBudget(); budget.quality = AvatarVideoQuality.MAXIMUM
        assertEquals(97,budget.jpegQuality); assertEquals(33L,budget.intervalMs)
        budget.processing(150); budget.thermalStatus = 3
        assertEquals(97,budget.jpegQuality); assertEquals(100L,budget.intervalMs)
    }
    @Test fun usefulCropChoosesHighestRealDetailAndNeverEnlargesOnQuest() {
        val sizes = listOf(AvatarVideoSize(1024,576),AvatarVideoSize(1280,720),AvatarVideoSize(1600,1200))
        assertEquals(AvatarVideoSize(1600,1200),AvatarVideoResolution.choose(sizes,AvatarVideoQuality.MAXIMUM))
        assertEquals(AvatarVideoSize(1600,900),AvatarVideoResolution.output(1600,1200,AvatarVideoQuality.MAXIMUM))
        assertEquals(AvatarVideoSize(1280,720),AvatarVideoResolution.choose(sizes,AvatarVideoQuality.SMOOTH))
        assertEquals(AvatarVideoSize(1024,576),AvatarVideoResolution.output(1024,576,AvatarVideoQuality.MAXIMUM))
        assertEquals(AvatarVideoSize(1920,1080),AvatarVideoResolution.output(2560,1440,AvatarVideoQuality.MAXIMUM))
        assertNull(AvatarVideoResolution.choose(listOf(AvatarVideoSize(0,0),AvatarVideoSize(8192,8192)),AvatarVideoQuality.MAXIMUM))
    }
    @Test fun missNeverReusesFaceAndLargeMotionDoesNotLag() {
        val filter = AvatarVideoStability(); val original = face()
        assertEquals(original,filter.update(original,100))
        val jitter = filter.update(face(.301f),133)!!
        assertTrue(jitter.left in .3f.. .301f)
        assertNull(filter.update(null,150)); assertEquals(.5f,filter.update(face(.5f),166)!!.left,0f)
        assertEquals(.2f,filter.update(face(.2f),200)!!.left,0f)
    }
    @Test fun fitIsBoundedAndHandOverlapFallsBackOnlyNearFace() {
        val f = face(); assertEquals(f,AvatarFaceFit.adjust(f,Float.NaN,0f,0f))
        val fit = AvatarFaceFit.adjust(f,1.2f,.1f,0f)
        assertTrue(fit.usable); assertTrue(fit.right-fit.left>f.right-f.left)
        assertFalse(AvatarHandOverlap.coversFace(f,listOf(List(21) { 0f to .9f })))
        assertTrue(AvatarHandOverlap.coversFace(f,listOf(List(21) { if (it%2 == 0) .25f to .3f else .45f to .55f })))
    }
    @Test fun receiptMustMatchSequenceAndKnownOutcome() {
        fun reply(seq: Long, state: Int): DataInputStream {
            val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).apply { writeLong(seq); writeByte(state) }
            return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        }
        assertEquals(1,AvatarStreamProtocol.acceptFrameReply(reply(7,1),7))
        assertEquals(0,AvatarStreamProtocol.acceptFrameReply(reply(8,0),8))
        assertThrows(IllegalArgumentException::class.java) { AvatarStreamProtocol.acceptFrameReply(reply(6,1),7) }
        assertThrows(IllegalArgumentException::class.java) { AvatarStreamProtocol.acceptFrameReply(reply(7,9),7) }
    }
}
