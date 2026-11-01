package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class WearerTrackingTest {
    private fun packet() = FloatArray(434).apply { this[0] = 1f; this[11] = 1f }
    @Test fun outwardDetectionsCannotAppearAsWearerSensorData() {
        assertNull(WearerPacketDecoder.decode(null, 10).head)
        assertNull(WearerPacketDecoder.decode(FloatArray(433), 10).head)
    }
    @Test fun invalidHeadOrientationDoesNotCreateLivePreview() {
        assertNull(WearerPacketDecoder.decode(packet().apply { this[11] = 0f }, 10).head)
    }
    @Test fun sensorCoordinatesUseSpatialForwardAxis() {
        val decoded = WearerPacketDecoder.decode(packet().apply { this[5] = .4f; this[6] = 1.5f; this[7] = -.2f }, 10)
        assertEquals(.4f, decoded.head!!.position.x, .00001f)
        assertEquals(1.5f, decoded.head!!.position.y, .00001f)
        assertEquals(.2f, decoded.head!!.position.z, .00001f)
        assertTrue(decoded.isFresh(160))
        assertFalse(decoded.isFresh(161))
        assertFalse(decoded.isFresh(9))
    }
    @Test fun missingPhysicalFaceKeepsNeutralWeights() {
        val decoded = WearerPacketDecoder.decode(packet().apply { this[364 + 24] = 1f }, 10)
        assertFalse(decoded.hasPhysicalFace)
        assertEquals(0f, decoded.expression.jawOpen, 0f)
    }
    @Test fun physicalFaceWeightsMapToExpressionWithClamping() {
        val decoded = WearerPacketDecoder.decode(packet().apply { this[2] = 1f; this[364 + 24] = 2f
            this[364 + 12] = .4f; this[364 + 33] = .8f }, 10)
        assertTrue(decoded.hasPhysicalFace)
        assertEquals(1f, decoded.expression.jawOpen, 0f)
        assertEquals(.4f, decoded.expression.eyeBlinkLeft, 0f)
        assertEquals(.8f, decoded.expression.mouthSmileRight, 0f)
    }
    @Test fun inactiveBodyAndHandsSuppressEvenPopulatedJoints() {
        val decoded = WearerPacketDecoder.decode(packet().apply { this[12] = 1f; this[19] = 1f; this[156] = 1f; this[157] = 1f }, 10)
        assertFalse(decoded.hasBody)
        assertTrue(decoded.body.all { it == null })
        assertTrue(decoded.hands.flatten().all { it == null })
    }
    @Test fun bodyAndHandFlagsAreIndependent() {
        val decoded = WearerPacketDecoder.decode(packet().apply {
            this[1] = 1f; this[12] = 1f; this[19] = 1f
            this[3] = 1f; this[156] = 1f; this[159] = -.3f
        }, 10)
        assertTrue(decoded.hasBody)
        assertNotNull(decoded.body[0])
        assertNotNull(decoded.hands[0][0])
        assertEquals(.3f, decoded.hands[0][0]!!.position.z, .00001f)
        assertTrue(decoded.hands[1].all { it == null })
    }
    @Test fun nonfinitePacketsAreRejected() {
        assertNull(WearerPacketDecoder.decode(packet().apply { this[120] = Float.NaN }, 10).head)
    }
    @Test fun extendedHandPacketPreservesNativeCoordinatesForTheAvatarSdk() {
        val p = packet().copyOf(642).apply { this[7] = -.2f; this[437] = 1f }
        val decoded = WearerPacketDecoder.decode(p, 10)
        assertNotNull(decoded.head)
        assertEquals(.2f, decoded.head!!.position.z, 0f)
        assertEquals(-.2f, decoded.nativePacket!![7], 0f)
        assertNull(WearerPacketDecoder.decode(p.copyOf().apply { this[440] = Float.NaN }, 10).head)
    }
    @Test fun bindSkeletonPacketKeepsIndependentFaceAndFingerInputs() {
        val p = packet().copyOf(1060).apply {
            this[2] = 1f; this[364 + 24] = .7f
            this[3] = 1f; this[160] = 1f; this[163] = -.3f
            this[438] = .5f; this[441] = .8660254f; this[642] = 1f
        }
        val decoded = WearerPacketDecoder.decode(p, 10)
        assertTrue(decoded.hasPhysicalFace)
        assertEquals(.7f, decoded.expression.jawOpen, 0f)
        assertNotNull(decoded.hands[0][1])
        assertEquals(.3f, decoded.hands[0][1]!!.position.z, 0f)
        assertEquals(.5f, kotlin.math.abs(decoded.hands[0][1]!!.orientation.x), .00001f)
        assertTrue(decoded.hands[1].all { it == null })
        assertEquals(1060, decoded.nativePacket!!.size)
    }
}
