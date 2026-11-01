package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AvatarVideoMotionDiagnosticsTest {
    private fun sample(timestamp: Long, face: Boolean = true, eyes: Boolean = true,
        left: Boolean = true, right: Boolean = true, jaw: Float = 0f, gaze: Float = 0f,
        leftX: Float = 0f, rightX: Float = 0f, fingerAngle: Float = 0f): WearerSnapshot {
        val packet = FloatArray(1060).apply {
            this[0] = 1f; this[6] = 1.6f; this[11] = 1f
            this[2] = if (face) 1f else 0f; this[388] = jaw
            for (side in 0..1) {
                this[3+side] = if (if (side == 0) left else right) 1f else 0f
                for (joint in 0..25) {
                    val at = 156+side*104+joint*4
                    this[at] = 1f; this[at+1] = if (side == 0) leftX else rightX
                    this[at+2] = 1.3f
                    val q = 434+side*104+joint*4
                    this[q+3] = 1f
                    if (side == 0 && joint == 9) {
                        this[q] = sin(fingerAngle/2); this[q+3] = cos(fingerAngle/2)
                    }
                }
            }
        }
        return WearerPacketDecoder.decode(packet, timestamp).copy(hasVideoFace = face,
            hasPhysicalFace = false, hasEyes = eyes, gazeX = gaze)
    }

    private fun observe(helper: AvatarVideoMotionDiagnostics, snapshot: WearerSnapshot) {
        helper.update(snapshot, snapshot.sampledAtMs+20L)
    }

    @Test fun firstDetectionAndUnchangedFramesDoNotClaimMotion() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000)); observe(helper,sample(1100)); observe(helper,sample(1200))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun briefExpressionAndGazeChangesRemainLatchedUntilReport() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000)); observe(helper,sample(1100,jaw = .8f,gaze = .3f))
        observe(helper,sample(1200)); observe(helper,sample(1300))
        assertEquals(AvatarVideoMotionFlags(faceExpressionChanged = true,gazeChanged = true),helper.takeWindow())
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
        observe(helper,sample(1400))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun translationAndFingerRotationDetectEachHandIndependently() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000)); observe(helper,sample(1100,fingerAngle = .3f))
        assertEquals(AvatarVideoMotionFlags(leftHandChanged = true),helper.takeWindow())
        observe(helper,sample(1200,fingerAngle = .3f,rightX = .05f))
        assertEquals(AvatarVideoMotionFlags(rightHandChanged = true),helper.takeWindow())
    }

    @Test fun disappearanceAndReappearanceDoNotBecomeMovement() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        observe(helper,sample(1100,face = false,eyes = false,left = false,right = false))
        observe(helper,sample(1200,jaw = .8f,gaze = .5f,leftX = .2f,rightX = -.2f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun lossOfOneChannelLeavesOthersComparable() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        observe(helper,sample(1100,face = false,eyes = false,left = false,rightX = .05f))
        assertEquals(AvatarVideoMotionFlags(rightHandChanged = true),helper.takeWindow())
        observe(helper,sample(1200,jaw = .8f,gaze = .5f,leftX = .2f,rightX = .1f))
        assertEquals(AvatarVideoMotionFlags(rightHandChanged = true),helper.takeWindow())
    }

    @Test fun quaternionScaleAndSignCannotCreateFalseMotion() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        val other = sample(1100)
        for (at in 434 until 642) other.nativePacket!![at] *= -2f
        observe(helper,other)
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun invalidNumericInputClearsBaselineInsteadOfRevivingMotion() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        val invalid = sample(1100,jaw = .8f)
        invalid.nativePacket!![400] = Float.NaN
        observe(helper,invalid)
        observe(helper,sample(1200,jaw = .8f,gaze = .5f,leftX = .2f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun invalidHandRotationCannotBlockValidOtherChannelsOrCountOnRecovery() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        val invalidHand = sample(1100,jaw = .8f,gaze = .3f,rightX = .05f)
        invalidHand.nativePacket!!.fill(0f,434,438)
        observe(helper,invalidHand)
        assertEquals(AvatarVideoMotionFlags(faceExpressionChanged = true,gazeChanged = true,
            rightHandChanged = true),helper.takeWindow())
        observe(helper,sample(1200,jaw = .8f,gaze = .3f,leftX = .2f,rightX = .05f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun expiredInputAndLongAcquisitionGapClearComparisons() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        helper.update(sample(1100,jaw = .8f),1100+AvatarSampleFramePolicy.MAX_INPUT_AGE_MS+1)
        observe(helper,sample(1200,jaw = .8f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
        observe(helper,sample(1201+AvatarSampleFramePolicy.MAX_INPUT_AGE_MS,jaw = .1f,gaze = .5f,leftX = .2f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun runOrSelectionResetClearsBothBaselineAndWindowFlags() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000)); observe(helper,sample(1100,jaw = .8f,leftX = .2f))
        helper.reset()
        observe(helper,sample(1200,jaw = .1f,gaze = .5f,leftX = -.2f,rightX = .2f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun staleOrDuplicateAcquisitionCannotBeCountedAsANewMotion() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        observe(helper,sample(1000,jaw = .8f,gaze = .5f,leftX = .2f))
        observe(helper,sample(1100,jaw = .8f,gaze = .5f,leftX = .2f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun onlySmallEstimatorJitterDoesNotClaimMovement() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        observe(helper,sample(1100,jaw = .03f,gaze = .03f,leftX = .005f,rightX = .005f,fingerAngle = .03f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun gradualMovementsAccumulateAgainstAnchorRatherThanDisappearAsJitter() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        observe(helper,sample(1100,jaw = .03f,gaze = .03f,leftX = .008f))
        observe(helper,sample(1200,jaw = .06f,gaze = .06f,leftX = .016f))
        observe(helper,sample(1300,jaw = .09f,gaze = .09f,leftX = .024f))
        assertEquals(AvatarVideoMotionFlags(faceExpressionChanged = true,gazeChanged = true,
            leftHandChanged = true),helper.takeWindow())
    }

    @Test fun jitterThatReturnsToItsAnchorDoesNotAccumulateAsMovement() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        observe(helper,sample(1100,jaw = .03f,gaze = .03f,leftX = .008f))
        observe(helper,sample(1200))
        observe(helper,sample(1300,jaw = .03f,gaze = -.03f,leftX = -.008f))
        observe(helper,sample(1400))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
    }

    @Test fun reportingStartsANewAnchorAtLatestObservationEvenWithoutPreviousMotion() {
        val helper = AvatarVideoMotionDiagnostics()
        observe(helper,sample(1000))
        observe(helper,sample(1100,jaw = .06f,gaze = .06f,leftX = .016f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
        observe(helper,sample(1200,jaw = .1f,gaze = .1f,leftX = .03f))
        assertEquals(AvatarVideoMotionFlags(),helper.takeWindow())
        observe(helper,sample(1300,jaw = .19f,gaze = .19f,leftX = .055f))
        assertEquals(AvatarVideoMotionFlags(faceExpressionChanged = true,gazeChanged = true,
            leftHandChanged = true),helper.takeWindow())
    }
}
