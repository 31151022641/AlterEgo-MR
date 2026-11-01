package com.oculus.camerademo.mirror

import com.oculus.camerademo.ml.FaceEmotionState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AvatarVideoTrackingTest {
    private fun face(rotation: FloatArray = matrix(), expression: FaceEmotionState = FaceEmotionState(),
        gazeX: Float = .3f, gazeY: Float = -.2f, weights: Map<String, Float> = emptyMap()) =
        AvatarVideoFace(.4f, .15f, .6f, .45f, rotation, expression, gazeX, gazeY, weights)

    private fun matrix(axis: Int = 0, degrees: Float = 0f): FloatArray {
        val a = degrees * PI.toFloat() / 180f; val c = cos(a); val s = sin(a)
        val m = FloatArray(16); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        when (axis) {
            0 -> { m[5] = c; m[6] = s; m[9] = -s; m[10] = c }
            1 -> { m[0] = c; m[2] = -s; m[8] = s; m[10] = c }
            2 -> { m[0] = c; m[1] = s; m[4] = -s; m[5] = c }
        }
        return m
    }

    private fun hand(side: AvatarVideoHandSide, wristX: Float, timestamp: Long = 100L,
        curl: Boolean = false, rotation: Float = 0f): AvatarVideoHand {
        val sign = if (side == AvatarVideoHandSide.LEFT) 1f else -1f
        val points = mutableListOf(AvatarVideoLandmark(0f, 0f, 0f))
        points += listOf(AvatarVideoLandmark(.035f * sign, -.025f, 0f), AvatarVideoLandmark(.058f * sign, -.05f, 0f),
            AvatarVideoLandmark(.074f * sign, -.069f, 0f), AvatarVideoLandmark(.09f * sign, -.084f, 0f))
        val xs = floatArrayOf(.032f, .01f, -.015f, -.039f)
        val lengths = floatArrayOf(.038f, .043f, .04f, .031f)
        for (f in 0..3) {
            val x = xs[f] * sign; val length = lengths[f]
            points += AvatarVideoLandmark(x, -.07f, 0f)
            points += AvatarVideoLandmark(x, -.07f - length, 0f)
            points += AvatarVideoLandmark(x, -.07f - length - if (curl) 0f else .6f * length,
                if (curl) -.6f * length else 0f)
            points += AvatarVideoLandmark(x, -.07f - if (curl) .6f * length else 2f * length,
                if (curl) -.6f * length else 0f)
        }
        val rotated = points.map { p -> AvatarVideoLandmark(p.x * cos(rotation) - p.y * sin(rotation),
            p.x * sin(rotation) + p.y * cos(rotation), p.z) }
        val normalized = rotated.map { p -> AvatarVideoLandmark(wristX + p.x, .6f + p.y, p.z) }
        return AvatarVideoHand(side, .95f, normalized, rotated, timestamp)
    }

    @Test fun videoFaceDoesNotPretendToHavePhysicalSensors() {
        val result = AvatarVideoTracking().update(face(expression = FaceEmotionState(jawOpen = .8f)), emptyList(), 100)
        assertNotNull(result.head)
        assertFalse(result.hasPhysicalFace)
        assertTrue(result.hasVideoFace)
        assertTrue(result.hasEyes)
        assertEquals(.3f, result.gazeX, 0f)
        assertEquals(-.2f, result.gazeY, 0f)
        assertEquals(.8f, result.nativePacket!![364 + 24], 0f)
        assertTrue(result.message.startsWith("Video estimates:"))
        assertFalse(result.hasBody)
    }

    @Test fun eyesBlinkMouthAndBrowControlsKeepBothSidesIndependent() {
        val weights = mapOf("eyeBlinkLeft" to .9f, "eyeBlinkRight" to .1f,
            "eyeLookInLeft" to .8f, "eyeLookOutRight" to .7f, "mouthSmileRight" to .6f,
            "mouthFunnel" to .4f, "browInnerUp" to .5f, "mouthLowerDownLeft" to .3f,
            "jawLeft" to .2f, "eyeWideRight" to .75f)
        val p = AvatarVideoTracking().update(face(weights = weights), emptyList(), 100).nativePacket!!
        assertEquals(.9f, p[376], 0f); assertEquals(.1f, p[377], 0f)
        assertEquals(.8f, p[382], 0f); assertEquals(.7f, p[383], 0f)
        assertEquals(.6f, p[397], 0f); assertEquals(.4f, p[399], 0f)
        assertEquals(.5f, p[386], 0f); assertEquals(.3f, p[415], 0f)
        assertEquals(.2f, p[389], 0f); assertEquals(.75f, p[424], 0f)
        assertTrue((427 until 434).all { p[it] == 0f })
    }

    @Test fun invalidExpressionWeightsAreNeutralAndFinite() {
        val p = AvatarVideoTracking().update(face(weights = mapOf("jawOpen" to Float.NaN,
            "mouthSmileLeft" to 2f, "eyeBlinkRight" to -1f)), emptyList(), 100).nativePacket!!
        assertEquals(0f, p[388], 0f); assertEquals(1f, p[396], 0f); assertEquals(0f, p[377], 0f)
        assertTrue(p.all { it.isFinite() })
    }

    @Test fun frontalRotationPreservesRollAndReflectsDepthForYawPitch() {
        for (axis in 0..2) {
            val p = AvatarVideoTracking().update(face(matrix(axis, 40f)), emptyList(), 100).nativePacket!!
            val expected = sin(20f * PI.toFloat() / 180f) * if (axis == 2) 1f else -1f
            assertEquals(expected, p[8 + axis], .0001f)
            assertEquals(cos(20f * PI.toFloat() / 180f), p[11], .0001f)
        }
    }

    @Test fun scaledFaceMatrixIgnoresTranslationAndRejectsReflectionOrSkew() {
        val scaled = matrix(1, 30f).apply { for (column in 0..2) for (row in 0..2) this[column * 4 + row] *= 2f; this[12] = 200f; this[13] = -200f }
        val normal = AvatarVideoTracking().update(face(matrix(1, 30f)), emptyList(), 100).nativePacket!!
        val actual = AvatarVideoTracking().update(face(scaled), emptyList(), 100).nativePacket!!
        assertArrayEquals(normal.sliceArray(5..11), actual.sliceArray(5..11), .00001f)
        for (invalid in listOf(matrix().apply { this[0] = -1f }, matrix().apply { this[4] = .6f })) {
            val rejected = AvatarVideoTracking().update(face(invalid), emptyList(), 100)
            assertFalse(rejected.hasVideoFace)
            assertEquals(0f, rejected.nativePacket!![2], 0f)
            assertArrayEquals(floatArrayOf(0f, 1.6f, 0f, 0f, 0f, 0f, 1f), rejected.nativePacket!!.sliceArray(5..11), 0f)
        }
    }

    @Test fun bothHandsAndAllFingerJointsRemainSeparateWhenCrossed() {
        val result = AvatarVideoTracking().update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .25f), hand(AvatarVideoHandSide.RIGHT, .75f)), 100)
        val p = result.nativePacket!!
        assertTrue(result.hasBody)
        assertTrue(result.hands.all { h -> h.all { it != null } })
        assertTrue(p[161] < 0f); assertTrue(p[265] > 0f)
        assertEquals(1f, p[3], 0f); assertEquals(1f, p[4], 0f)
        assertEquals(1f, p[642], 0f); assertEquals(1f, p[643], 0f)
        for (side in 0..1) for (joint in 0..25) {
            val at = 434 + side * 104 + joint * 4
            assertEquals(1f, (0..3).sumOf { p[at + it].toDouble().pow(2) }.toFloat(), .0001f)
        }
    }

    @Test fun unmirroredHandLabelsAreSwappedWithoutUsingScreenPosition() {
        assertEquals(AvatarVideoHandSide.LEFT, AvatarVideoHandTracker.wearerSide("Right"))
        assertEquals(AvatarVideoHandSide.RIGHT, AvatarVideoHandTracker.wearerSide("Left"))
        assertNull(AvatarVideoHandTracker.wearerSide("Unknown"))
    }

    @Test fun wristsFollowImageMovementAndPalmRotationChanges() {
        val left = AvatarVideoHandSide.LEFT
        val a = AvatarVideoTracking().update(face(), listOf(hand(left, .35f)), 100).nativePacket!!
        val b = AvatarVideoTracking().update(face(), listOf(hand(left, .65f, rotation = PI.toFloat() / 2)), 100).nativePacket!!
        assertTrue(b[161] > a[161])
        assertFalse(a.sliceArray(438..441).contentEquals(b.sliceArray(438..441)))
        assertEquals(b[161], b[13 + 12 * 8], 0f)
        assertEquals(b[162], b[14 + 12 * 8], 0f)
    }

    @Test fun fingerCurlChangesFingerRotationsWhileNeutralBindStaysFixed() {
        val side = AvatarVideoHandSide.LEFT
        val a = AvatarVideoTracking().update(face(), listOf(hand(side, .65f)), 100).nativePacket!!
        val b = AvatarVideoTracking().update(face(), listOf(hand(side, .65f, curl = true)), 100).nativePacket!!
        assertArrayEquals(a.sliceArray(644..851), b.sliceArray(644..851), 0f)
        assertArrayEquals(a.sliceArray(438..441), b.sliceArray(438..441), .0001f)
        assertFalse(a.sliceArray(466..473).contentEquals(b.sliceArray(466..473)))
        // A curled first frame must differ from the open reference orientation.
        val currentAt = 434 + 8 * 4
        val bindAt = 644 + 8 * 8 + 4
        assertFalse(b.sliceArray(currentAt..currentAt + 3).contentEquals(b.sliceArray(bindAt..bindAt + 3)))
    }

    @Test fun deepFingerCurlDoesNotFlipTwistWhenItPasses90Degrees() {
        val base = hand(AvatarVideoHandSide.LEFT, .65f)
        fun bent(angle: Float): AvatarVideoHand {
            val a = angle * PI.toFloat() / 180f; val points = base.worldLandmarks.toMutableList()
            val pivot = points[6]
            points[7] = pivot.copy(y = pivot.y - .025f * cos(a), z = pivot.z - .025f * sin(a))
            points[8] = pivot.copy(y = pivot.y - .04f * cos(a), z = pivot.z - .04f * sin(a))
            return base.copy(worldLandmarks = points)
        }
        val before = AvatarVideoTracking().update(face(), listOf(bent(89f)), 100).nativePacket!!
        val after = AvatarVideoTracking().update(face(), listOf(bent(91f)), 100).nativePacket!!
        val at = 434 + 8 * 4
        val similarity = (0..3).sumOf { (before[at + it] * after[at + it]).toDouble() }
        assertTrue(abs(similarity) > .999)
    }

    @Test fun ambiguousLowConfidenceStaleOrDegenerateHandsAreNotRendered() {
        val left = hand(AvatarVideoHandSide.LEFT, .65f)
        val cases = listOf(listOf(left.copy(confidence = .65f)), listOf(left.copy(sampledAtMs = 301)),
            listOf(left.copy(sampledAtMs = 0)), listOf(left.copy(sampledAtMs = Long.MIN_VALUE)), listOf(left, left.copy()),
            listOf(left.copy(worldLandmarks = List(21) { AvatarVideoLandmark(0f, 0f, 0f) })),
            listOf(left.copy(worldLandmarks = left.worldLandmarks.mapIndexed { i, p -> if (i == 5) p.copy(x = Float.NaN) else p })))
        for (hands in cases) {
            val result = AvatarVideoTracking().update(face(), hands, 300)
            assertTrue(result.hands.flatten().all { it == null })
            assertFalse(result.hasBody)
            assertEquals(0f, result.nativePacket!![3], 0f)
        }
    }

    @Test fun narrowObservedPalmsMapBothHandsWithoutChangingMeasuredFingerOffsets() {
        for (side in AvatarVideoHandSide.values()) {
            val observed = hand(side, .65f).let { base ->
                base.copy(worldLandmarks = base.worldLandmarks.map { it.copy(x = it.x * .2f) },
                    landmarks = base.landmarks.map { it.copy(x = .65f + (it.x - .65f) * .2f) })
            }
            // Similar to the public sample's side-view estimate: roughly 15 mm
            // MCP width despite an otherwise plausible 70 mm palm length.
            val width = abs(observed.worldLandmarks[5].x - observed.worldLandmarks[17].x)
            assertTrue(width in .014f.. .015f)
            val result = AvatarVideoTracking().update(face(), listOf(observed), 100)
            val joints = result.hands[side.packetSide]
            assertTrue(joints.all { it != null })
            assertEquals(1f, result.nativePacket!![3 + side.packetSide], 0f)
            assertEquals(1f, result.nativePacket!![642 + side.packetSide], 0f)
            // OpenXR index tip 10 corresponds to observed MediaPipe index tip 8.
            // Retargeting must preserve those measured positions, not widen the
            // palm or substitute a fabricated open hand to pass validation.
            val wrist = joints[1]!!.position; val tip = joints[10]!!.position
            val observedWrist = observed.worldLandmarks[0]; val observedTip = observed.worldLandmarks[8]
            assertEquals(observedTip.x - observedWrist.x, tip.x - wrist.x, .000001f)
            assertEquals(-(observedTip.y - observedWrist.y), tip.y - wrist.y, .000001f)
            assertEquals(observedTip.z - observedWrist.z, tip.z - wrist.z, .000001f)
            assertTrue(result.nativePacket!!.all { it.isFinite() })
            for (joint in 0..25) {
                val at = 434 + side.packetSide * 104 + joint * 4
                assertEquals(1f, (0..3).sumOf { result.nativePacket!![at + it].toDouble().pow(2) }.toFloat(), .0001f)
            }
        }
    }

    @Test fun collapsedCollinearAndNearlyCollinearPalmsCannotAnimateWristTwist() {
        val base = hand(AvatarVideoHandSide.LEFT, .65f)
        val cases = listOf(
            base.copy(worldLandmarks = base.worldLandmarks.map { it.copy(x = 0f) }),
            base.copy(worldLandmarks = base.worldLandmarks.map { it.copy(x = 0f, y = it.y + it.x * .5f, z = 0f) }),
            base.copy(worldLandmarks = base.worldLandmarks.map { it.copy(x = it.x * .005f, y = it.y + it.x * .5f, z = 0f) }),
            base.copy(worldLandmarks = base.worldLandmarks.map { it.copy(x = it.x * .02f) })
        )
        for (observed in cases) {
            val result = AvatarVideoTracking().update(face(), listOf(observed), 100)
            assertTrue(result.hands.flatten().all { it == null })
            assertEquals(0f, result.nativePacket!![3], 0f)
            assertEquals(0f, result.nativePacket!![642], 0f)
            assertFalse(result.hasBody)
            assertTrue(result.hasVideoFace)
        }
    }

    @Test fun narrowerPalmAcceptanceDoesNotRelaxHandIdentityFreshnessOrFingerGeometry() {
        val narrow = hand(AvatarVideoHandSide.LEFT, .65f).let { base ->
            base.copy(worldLandmarks = base.worldLandmarks.map { it.copy(x = it.x * .2f) })
        }
        assertEquals(1f, AvatarVideoTracking().update(face(), listOf(narrow.copy(confidence = .7f)), 100).nativePacket!![3], 0f)
        val badCandidates = listOf(narrow.copy(confidence = .699f), narrow.copy(sampledAtMs = 251),
            narrow.copy(sampledAtMs = 0), narrow.copy(worldLandmarks = narrow.worldLandmarks.toMutableList().apply {
                this[7] = this[6]
            }), narrow.copy(worldLandmarks = narrow.worldLandmarks.map { it.copy(y = it.y * 3f) }))
        for (observed in badCandidates) {
            val result = AvatarVideoTracking().update(face(), listOf(observed), 250)
            assertEquals(0f, result.nativePacket!![3], 0f)
            assertTrue(result.hands.flatten().all { it == null })
        }
        val ambiguous = AvatarVideoTracking().update(face(), listOf(narrow, narrow.copy()), 100)
        assertEquals(0f, ambiguous.nativePacket!![3], 0f)
    }

    @Test fun handDropoutClearsImmediatelyAndRecoveryStartsAtObservedPose() {
        val tracker = AvatarVideoTracking()
        tracker.update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .3f)), 100)
        val missing = tracker.update(face(), emptyList(), 150)
        assertEquals(0f, missing.nativePacket!![3], 0f)
        assertEquals(0f, missing.nativePacket!![642], 0f)
        val restored = tracker.update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .8f, 200)), 200)
        val expected = AvatarVideoTracking().update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .8f, 200)), 200)
        assertEquals(expected.nativePacket!![161], restored.nativePacket!![161], 0f)
    }

    @Test fun noObservationsClearChannelsAndKeepOnlyNeutralSdkFraming() {
        val tracker = AvatarVideoTracking()
        tracker.update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .7f)), 100)
        val missing = tracker.update(null, emptyList(), 150)
        assertNotNull(missing.head)
        assertFalse(missing.hasVideoFace); assertFalse(missing.hasEyes); assertFalse(missing.hasPhysicalFace)
        assertFalse(missing.hasBody); assertTrue(missing.hands.flatten().all { it == null })
        val p = missing.nativePacket!!
        assertArrayEquals(floatArrayOf(0f, 1.6f, 0f, 0f, 0f, 0f, 1f), p.sliceArray(5..11), 0f)
        assertEquals(1f, p[0], 0f); assertTrue((1..4).all { p[it] == 0f })
        assertTrue((364 until 434).all { p[it] == 0f })
        assertEquals(0f, missing.gazeX, 0f); assertEquals(0f, missing.gazeY, 0f)
        assertEquals(FaceEmotionState(), missing.expression)
        assertEquals("Video estimates: no face or hands visible", missing.message)
        val duplicate = tracker.update(face(), emptyList(), 150)
        assertNull(duplicate.head); assertNull(duplicate.nativePacket)
        val late = tracker.update(face(), emptyList(), 100)
        assertNull(late.head); assertNull(late.nativePacket)
        assertNotNull(tracker.update(face(), emptyList(), 200).head)
    }

    @Test fun faceOcclusionLeavesFreshHandsActiveAndClearsOnlyMissingHand() {
        val tracker = AvatarVideoTracking()
        tracker.update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .3f), hand(AvatarVideoHandSide.RIGHT, .8f)), 100)
        val missingFace = tracker.update(null, listOf(hand(AvatarVideoHandSide.LEFT, .65f, 150, curl = true)), 150)
        val p = missingFace.nativePacket!!
        assertFalse(missingFace.hasVideoFace); assertFalse(missingFace.hasEyes)
        assertEquals(0f, p[2], 0f); assertTrue((364 until 434).all { p[it] == 0f })
        assertEquals(1f, p[3], 0f); assertEquals(0f, p[4], 0f)
        assertTrue(missingFace.hands[0].all { it != null }); assertTrue(missingFace.hands[1].all { it == null })
        assertTrue(missingFace.hasBody)
        // A hand observed before and during the face miss retains its short movement filter.
        val instantlyAtNewWrist = AvatarVideoTracking().update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .65f, 150, curl = true)), 150)
        assertTrue(p[161] < instantlyAtNewWrist.nativePacket!![161])
        assertEquals("Video estimates: 1 hand; face not visible", missingFace.message)
        val restoredRight = tracker.update(null, listOf(hand(AvatarVideoHandSide.RIGHT, .2f, 200)), 200)
        val unfilteredRight = AvatarVideoTracking().update(face(), listOf(hand(AvatarVideoHandSide.RIGHT, .2f, 200)), 200)
        assertEquals(unfilteredRight.nativePacket!![265], restoredRight.nativePacket!![265], 0f)
        assertEquals(0f, restoredRight.nativePacket!![3], 0f)
    }

    @Test fun firstFrameWithOnlyHandsUsesDefaultProjectionWithoutInventingFaceTracking() {
        val observed = hand(AvatarVideoHandSide.LEFT, .7f)
        val result = AvatarVideoTracking().update(null, listOf(observed), 100)
        val p = result.nativePacket!!
        assertFalse(result.hasVideoFace); assertFalse(result.hasEyes); assertFalse(result.hasPhysicalFace)
        assertEquals(1f, p[3], 0f); assertEquals(0f, p[2], 0f)
        assertEquals((.7f - .5f) * .86f * 16f / 9f, p[161], .00001f)
        assertEquals(1.6f + (.29f - .6f) * .86f, p[162], .00001f)
        assertTrue(p.all { it.isFinite() })
    }

    @Test fun invalidFaceRotationOrBoundsCannotDisableValidHandsOrLeakFaceControls() {
        val badFaces = listOf(face(matrix().apply { this[4] = .6f }),
            face(matrix().apply { this[0] = -1f }), face().copy(top = Float.NaN),
            face(expression = FaceEmotionState(jawOpen = 1f)).copy(right = 1.5f))
        for (badFace in badFaces) {
            val result = AvatarVideoTracking().update(badFace, listOf(hand(AvatarVideoHandSide.RIGHT, .6f)), 100)
            assertFalse(result.hasVideoFace); assertFalse(result.hasEyes)
            assertEquals(1f, result.nativePacket!![4], 0f)
            assertEquals(0f, result.nativePacket!![2], 0f)
            assertTrue((364 until 434).all { result.nativePacket!![it] == 0f })
            assertTrue(result.hands[1].all { it != null })
            assertTrue(result.nativePacket!!.all { it.isFinite() })
        }
    }

    @Test fun faceRecoveryStartsAtObservedHeadExpressionAndGazeWithoutOldFilters() {
        val tracker = AvatarVideoTracking()
        tracker.update(face(matrix(1, 60f), FaceEmotionState(jawOpen = 1f), gazeX = .7f, gazeY = .6f), emptyList(), 100)
        tracker.update(null, emptyList(), 150)
        val freshFace = face(matrix(2, -30f), FaceEmotionState(eyeBlinkLeft = .4f, mouthSmileRight = .6f),
            gazeX = -.3f, gazeY = -.2f).copy(left = .5f, right = .7f)
        val recovered = tracker.update(freshFace, emptyList(), 200)
        val expected = AvatarVideoTracking().update(freshFace, emptyList(), 200)
        assertArrayEquals(expected.nativePacket, recovered.nativePacket, 0f)
        assertEquals(expected.expression, recovered.expression)
        assertEquals(expected.gazeX, recovered.gazeX, 0f); assertEquals(expected.gazeY, recovered.gazeY, 0f)
        assertTrue(recovered.hasVideoFace)
    }

    @Test fun missingFaceRetainsOnlyBriefFramingReferenceThenExpiresToDefault() {
        val tracker = AvatarVideoTracking()
        val lowFace = face(matrix(1, 50f), FaceEmotionState(jawOpen = 1f)).copy(top = .4f, bottom = .8f)
        tracker.update(lowFace, emptyList(), 100)
        val retained = tracker.update(null, listOf(hand(AvatarVideoHandSide.LEFT, .7f, 200)), 200)
        val projectedWithFace = AvatarVideoTracking().update(lowFace, listOf(hand(AvatarVideoHandSide.LEFT, .7f, 200)), 200)
        assertArrayEquals(projectedWithFace.nativePacket!!.sliceArray(160..163), retained.nativePacket!!.sliceArray(160..163), 0f)
        assertFalse(retained.hasVideoFace); assertEquals(0f, retained.nativePacket!![388], 0f)
        val expired = tracker.update(null, listOf(hand(AvatarVideoHandSide.LEFT, .7f, 5200)), 5200)
        val fallback = AvatarVideoTracking().update(null, listOf(hand(AvatarVideoHandSide.LEFT, .7f, 5200)), 5200)
        assertArrayEquals(fallback.nativePacket, expired.nativePacket, 0f)
        tracker.reset()
        val reset = tracker.update(null, listOf(hand(AvatarVideoHandSide.LEFT, .7f)), 100)
        val newSession = AvatarVideoTracking().update(null, listOf(hand(AvatarVideoHandSide.LEFT, .7f)), 100)
        assertArrayEquals(newSession.nativePacket, reset.nativePacket, 0f)
    }

    @Test fun missingFaceStillRejectsStaleOrAmbiguousHandsIndependently() {
        val left = hand(AvatarVideoHandSide.LEFT, .6f, 100)
        val right = hand(AvatarVideoHandSide.RIGHT, .3f, 300)
        val result = AvatarVideoTracking().update(null, listOf(left, right), 300)
        assertEquals(0f, result.nativePacket!![3], 0f); assertEquals(1f, result.nativePacket!![4], 0f)
        val ambiguous = AvatarVideoTracking().update(null, listOf(right, right.copy()), 300)
        assertTrue(ambiguous.hands.flatten().all { it == null })
        assertFalse(ambiguous.hasBody); assertFalse(ambiguous.hasVideoFace)
    }

    @Test fun smoothingTakesShortestQuaternionPathAcross180Degrees() {
        val tracker = AvatarVideoTracking()
        tracker.update(face(matrix(2, 179f)), emptyList(), 100)
        val p = tracker.update(face(matrix(2, -179f)), emptyList(), 150).nativePacket!!
        assertTrue(abs(p[10]) > .99f)
        assertTrue(abs(p[11]) < .02f)
    }

    @Test fun gapAndResetDoNotBlendWithAnOldWristOrHeadPose() {
        val tracker = AvatarVideoTracking()
        tracker.update(face(matrix(1, 60f)), listOf(hand(AvatarVideoHandSide.LEFT, .2f)), 100)
        val next = tracker.update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .8f, 600)), 600)
        val expected = AvatarVideoTracking().update(face(), listOf(hand(AvatarVideoHandSide.LEFT, .8f, 600)), 600)
        assertArrayEquals(expected.nativePacket, next.nativePacket, 0f)
        tracker.reset()
        assertNotNull(tracker.update(face(), emptyList(), 1).head)
    }

    @Test fun invalidGazeCannotPoisonPacketOrBeAdvertisedAsEyeTracking() {
        val result = AvatarVideoTracking().update(face(gazeX = Float.NaN), emptyList(), 100)
        assertFalse(result.hasEyes); assertEquals(0f, result.gazeX, 0f)
        assertTrue(result.nativePacket!!.all { it.isFinite() })
    }

    @Test fun bothClosedEyesDisableUnreliableGazeAndReopeningStartsFresh() {
        val tracker = AvatarVideoTracking()
        tracker.update(face(gazeX = .7f), emptyList(), 100)
        val blink = tracker.update(face(expression = FaceEmotionState(eyeBlinkLeft = .9f, eyeBlinkRight = .8f),
            gazeX = -.8f, gazeY = -.8f), emptyList(), 150)
        assertFalse(blink.hasEyes); assertEquals(0f, blink.gazeX, 0f); assertEquals(0f, blink.gazeY, 0f)
        assertTrue(blink.nativePacket!![376] > .65f)
        val opened = tracker.update(face(gazeX = -.2f, gazeY = .1f), emptyList(), 200)
        assertTrue(opened.hasEyes); assertEquals(-.2f, opened.gazeX, 0f); assertEquals(.1f, opened.gazeY, 0f)
    }
}
