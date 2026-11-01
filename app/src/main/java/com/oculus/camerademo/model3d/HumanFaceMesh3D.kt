package com.oculus.camerademo.model3d

import android.graphics.Color
import com.oculus.camerademo.ml.FaceEmotionState
import com.oculus.camerademo.ml.FaceTrackingData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 3D Canonical Human Face Mesh with real-time expression and emotion deformation.
 * Supports independent left/right winking, open mouth with recessed oral cavity,
 * asymmetric smiles, frowns, pucker, and brow surprise/furrowing.
 */
class HumanFaceMesh3D {

    // Base canonical human face vertices: (X, Y, Z, NormalX, NormalY, NormalZ, U, V)
    // 34 anatomical vertices structured across forehead, eyebrows, independent eyelids,
    // nose, cheeks, mouth aperture, oral cavity, and jaw/chin.
    private val baseVertices = floatArrayOf(
        // 0: Forehead center
        0.0f,  0.15f, 0.05f,   0.0f, 0.2f, 0.9f,   0.5f, 0.9f,
        // 1: Forehead left
       -0.08f, 0.14f, 0.02f,  -0.3f, 0.2f, 0.8f,   0.2f, 0.85f,
        // 2: Forehead right
        0.08f, 0.14f, 0.02f,   0.3f, 0.2f, 0.8f,   0.8f, 0.85f,

        // 3: Temple left
       -0.12f, 0.10f,-0.05f,  -0.7f, 0.1f, 0.4f,   0.05f, 0.75f,
        // 4: Temple right
        0.12f, 0.10f,-0.05f,   0.7f, 0.1f, 0.4f,   0.95f, 0.75f,

        // 5: Glabella (between eyes)
        0.0f,  0.07f, 0.07f,   0.0f, 0.0f, 1.0f,   0.5f, 0.7f,

        // 6: Left eye inner canthus
       -0.028f, 0.05f, 0.052f,  -0.2f, 0.0f, 0.9f,   0.44f, 0.65f,
        // 7: Left eye outer canthus
       -0.072f, 0.05f, 0.025f,  -0.5f, 0.0f, 0.8f,   0.28f, 0.65f,
        // 8: Right eye inner canthus
        0.028f, 0.05f, 0.052f,   0.2f, 0.0f, 0.9f,   0.56f, 0.65f,
        // 9: Right eye outer canthus
        0.072f, 0.05f, 0.025f,   0.5f, 0.0f, 0.8f,   0.72f, 0.65f,

        // 10: Nose bridge
        0.0f,  0.01f, 0.09f,   0.0f, 0.1f, 1.0f,   0.5f, 0.55f,
        // 11: Nose tip
        0.0f, -0.03f, 0.11f,   0.0f, 0.0f, 1.0f,   0.5f, 0.45f,
        // 12: Nostril left
       -0.03f,-0.04f, 0.07f,  -0.6f,-0.1f, 0.7f,   0.4f, 0.42f,
        // 13: Nostril right
        0.03f,-0.04f, 0.07f,   0.6f,-0.1f, 0.7f,   0.6f, 0.42f,

        // 14: Cheek left
       -0.10f,-0.01f, 0.01f,  -0.6f, 0.0f, 0.6f,   0.15f, 0.5f,
        // 15: Cheek right
        0.10f,-0.01f, 0.01f,   0.6f, 0.0f, 0.6f,   0.85f, 0.5f,

        // 16: Upper lip center
        0.0f, -0.07f, 0.08f,   0.0f,-0.2f, 0.9f,   0.5f, 0.35f,
        // 17: Upper lip left corner
       -0.045f,-0.08f, 0.06f,  -0.4f,-0.2f, 0.8f,   0.38f, 0.32f,
        // 18: Upper lip right corner
        0.045f,-0.08f, 0.06f,   0.4f,-0.2f, 0.8f,   0.62f, 0.32f,

        // 19: Lower lip center
        0.0f, -0.095f, 0.075f,  0.0f,-0.4f, 0.8f,   0.5f, 0.28f,
        // 20: Lower lip left
       -0.03f,-0.095f, 0.065f, -0.4f,-0.4f, 0.7f,   0.4f, 0.28f,
        // 21: Lower lip right
        0.03f,-0.095f, 0.065f,  0.4f,-0.4f, 0.7f,   0.6f, 0.28f,

        // 22: Jaw corner left
       -0.10f,-0.12f,-0.02f,  -0.7f,-0.5f, 0.4f,   0.15f, 0.2f,
        // 23: Jaw corner right
        0.10f,-0.12f,-0.02f,   0.7f,-0.5f, 0.4f,   0.85f, 0.2f,

        // 24: Chin / Jaw Tip
        0.0f, -0.16f, 0.05f,   0.0f,-0.6f, 0.7f,   0.5f, 0.1f,
        // 25: Chin left
       -0.04f,-0.15f, 0.03f,  -0.4f,-0.6f, 0.6f,   0.35f, 0.12f,
        // 26: Chin right
        0.04f,-0.15f, 0.03f,   0.4f,-0.6f, 0.6f,   0.65f, 0.12f,

        // 27: Left Upper Eyelid (drops down on wink left)
       -0.050f, 0.062f, 0.045f, -0.3f, 0.2f, 0.9f,   0.36f, 0.68f,
        // 28: Left Lower Eyelid (lifts slightly on squint left)
       -0.050f, 0.038f, 0.040f, -0.3f,-0.2f, 0.9f,   0.36f, 0.58f,

        // 29: Right Upper Eyelid (drops down on wink right)
        0.050f, 0.062f, 0.045f,  0.3f, 0.2f, 0.9f,   0.64f, 0.68f,
        // 30: Right Lower Eyelid (lifts slightly on squint right)
        0.050f, 0.038f, 0.040f,  0.3f,-0.2f, 0.9f,   0.64f, 0.58f,

        // 31: Left Eyebrow Arch (raises on surprise/curious, lowers on frown/angry)
       -0.055f, 0.095f, 0.045f, -0.3f, 0.3f, 0.8f,   0.34f, 0.74f,
        // 32: Right Eyebrow Arch (raises on surprise/curious, lowers on frown/angry)
        0.055f, 0.095f, 0.045f,  0.3f, 0.3f, 0.8f,   0.66f, 0.74f,

        // 33: Oral Cavity Aperture Depth (opens 3D depth when mouth opens)
        0.0f,  -0.082f, 0.040f,  0.0f, 0.0f, 1.0f,   0.5f, 0.32f
    )

    private val stride = 8
    private val vertexCount = baseVertices.size / stride

    // Dynamic deformed vertices buffer
    private val deformedVertices = baseVertices.clone()

    // Triangles for rendering human face surface with eyelid winking and mouth opening
    val indices = shortArrayOf(
        // Forehead & Eyebrows
        0, 1, 31,   0, 31, 5,   0, 5, 32,   0, 32, 2,
        1, 3, 31,   3, 7, 31,   2, 32, 4,   4, 32, 9,

        // Left Eye Orbit & Eyelids (Winking Left geometry)
        31, 7, 27,   31, 27, 5,   5, 27, 6,
        27, 7, 28,   27, 28, 6,
        28, 7, 14,   28, 14, 12,  28, 12, 6,

        // Right Eye Orbit & Eyelids (Winking Right geometry)
        32, 29, 9,   32, 5, 29,   5, 29, 8,
        29, 30, 9,   29, 8, 30,
        30, 15, 9,   30, 13, 15,  30, 8, 13,

        // Nose Bridge & Tip
        5, 6, 10,   5, 10, 8,
        6, 12, 10,  8, 10, 13,
        10, 12, 11, 10, 11, 13,

        // Cheeks & Mouth Transition
        14, 17, 12, 15, 13, 18,
        12, 17, 16, 13, 16, 18,
        11, 12, 16, 11, 16, 13,

        // Oral Cavity Opening & Lips (Open Mouth & Pucker)
        16, 17, 33, 16, 33, 18,
        33, 17, 20, 33, 20, 19,
        33, 19, 21, 33, 21, 18,

        // Chin & Jaw Lower
        17, 22, 20, 20, 22, 25, 19, 20, 25, 19, 25, 24,
        18, 21, 23, 21, 26, 23, 19, 26, 21, 19, 24, 26,
        22, 24, 25, 23, 26, 24
    )

    val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(deformedVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer().apply {
            put(indices)
            position(0)
        }

    init {
        // Initial clean buffer upload
        updateBlendshapes(FaceEmotionState())
    }

    /**
     * Updates the 3D human face geometry with comprehensive real-time emotion and blendshape deformation:
     * - Independent Left & Right Eyelid Winking and Squinting
     * - Open Mouth with 3D Oral Cavity Aperture
     * - Asymmetric Smiles and Frowns
     * - Pouting / Pucker "O" Shape
     * - Brow Raise (Surprise/Curious) & Brow Furrow (Anger/Concentration)
     * - Cheek Smile Lifts and Puff
     */
    fun updateBlendshapes(emotion: FaceEmotionState) {
        val jawDrop = emotion.jawOpen.coerceIn(0f, 1f)
        val blinkLeft = emotion.eyeBlinkLeft.coerceIn(0f, 1f)
        val blinkRight = emotion.eyeBlinkRight.coerceIn(0f, 1f)
        val squintLeft = emotion.eyeSquintLeft.coerceIn(0f, 1f)
        val squintRight = emotion.eyeSquintRight.coerceIn(0f, 1f)
        val wideLeft = emotion.eyeWideLeft.coerceIn(0f, 1f)
        val wideRight = emotion.eyeWideRight.coerceIn(0f, 1f)

        val smileLeft = emotion.mouthSmileLeft.coerceIn(0f, 1f)
        val smileRight = emotion.mouthSmileRight.coerceIn(0f, 1f)
        val frownLeft = emotion.mouthFrownLeft.coerceIn(0f, 1f)
        val frownRight = emotion.mouthFrownRight.coerceIn(0f, 1f)
        val pucker = maxOf(emotion.mouthPucker, emotion.mouthFunnel).coerceIn(0f, 1f)

        val browUp = emotion.browInnerUp.coerceIn(0f, 1f)
        val browDownL = emotion.browDownLeft.coerceIn(0f, 1f)
        val browDownR = emotion.browDownRight.coerceIn(0f, 1f)
        val browOuterUpL = emotion.browOuterUpLeft.coerceIn(0f, 1f)
        val browOuterUpR = emotion.browOuterUpRight.coerceIn(0f, 1f)

        val puff = emotion.cheekPuff.coerceIn(0f, 1f)

        for (i in 0 until vertexCount) {
            val offset = i * stride
            var x = baseVertices[offset]
            var y = baseVertices[offset + 1]
            var z = baseVertices[offset + 2]

            when (i) {
                // ----------------------------------------------------
                // 1. WINKING & BLINKING (Independent Left & Right Eyes)
                // ----------------------------------------------------
                27 -> { // Left Upper Eyelid (Closes downward on wink left)
                    y -= (blinkLeft * 0.024f) - (wideLeft * 0.007f)
                    z -= blinkLeft * 0.005f
                }
                28 -> { // Left Lower Eyelid (Raises on squint left)
                    y += (blinkLeft * 0.004f) + (squintLeft * 0.008f)
                    z += squintLeft * 0.003f
                }
                29 -> { // Right Upper Eyelid (Closes downward on wink right)
                    y -= (blinkRight * 0.024f) - (wideRight * 0.007f)
                    z -= blinkRight * 0.005f
                }
                30 -> { // Right Lower Eyelid (Raises on squint right)
                    y += (blinkRight * 0.004f) + (squintRight * 0.008f)
                    z += squintRight * 0.003f
                }

                // ----------------------------------------------------
                // 2. EYEBROWS & FOREHEAD (Surprise, Frown, Concentration)
                // ----------------------------------------------------
                5 -> { // Glabella (Center Brow)
                    y += (browUp * 0.016f) - ((browDownL + browDownR) * 0.5f * 0.010f)
                    z += browUp * 0.006f
                }
                31 -> { // Left Brow Arch
                    y += (browUp * 0.012f) + (browOuterUpL * 0.014f) - (browDownL * 0.016f)
                    z += browUp * 0.005f
                }
                32 -> { // Right Brow Arch
                    y += (browUp * 0.012f) + (browOuterUpR * 0.014f) - (browDownR * 0.016f)
                    z += browUp * 0.005f
                }

                // ----------------------------------------------------
                // 3. JAW & OPEN MOUTH (Speaking, Laughing, Vocalizing)
                // ----------------------------------------------------
                16 -> { // Upper Lip Center
                    y += jawDrop * 0.008f
                    z += pucker * 0.018f
                }
                19 -> { // Lower Lip Center
                    y -= jawDrop * 0.042f
                    z -= (jawDrop * 0.012f) - (pucker * 0.018f)
                }
                20 -> { // Lower Lip Left
                    y -= jawDrop * 0.038f
                    z -= (jawDrop * 0.010f) - (pucker * 0.015f)
                }
                21 -> { // Lower Lip Right
                    y -= jawDrop * 0.038f
                    z -= (jawDrop * 0.010f) - (pucker * 0.015f)
                }
                24, 25, 26 -> { // Chin (Center, Left, Right)
                    y -= jawDrop * 0.052f
                    z -= jawDrop * 0.015f
                }
                22, 23 -> { // Jaw Corners
                    y -= jawDrop * 0.026f
                }
                33 -> { // Oral Cavity Depth (Recedes inside mouth aperture)
                    y -= jawDrop * 0.022f
                    z -= (jawDrop * 0.012f)
                }

                // ----------------------------------------------------
                // 4. MOUTH CORNERS (Asymmetric Smile, Frown, Pucker)
                // ----------------------------------------------------
                17 -> { // Mouth Corner Left
                    x -= (smileLeft * 0.016f) - (pucker * 0.012f)
                    y += (smileLeft * 0.014f) - (frownLeft * 0.012f) - (jawDrop * 0.020f)
                    z += (smileLeft * 0.006f) + (pucker * 0.015f)
                }
                18 -> { // Mouth Corner Right
                    x += (smileRight * 0.016f) - (pucker * 0.012f)
                    y += (smileRight * 0.014f) - (frownRight * 0.012f) - (jawDrop * 0.020f)
                    z += (smileRight * 0.006f) + (pucker * 0.015f)
                }

                // ----------------------------------------------------
                // 5. CHEEKS (Smile Lift, Puff, Squint)
                // ----------------------------------------------------
                14 -> { // Left Cheek
                    x -= puff * 0.016f
                    y += (smileLeft * 0.010f) + (squintLeft * 0.008f)
                    z += (smileLeft * 0.008f) + (puff * 0.014f)
                }
                15 -> { // Right Cheek
                    x += puff * 0.016f
                    y += (smileRight * 0.010f) + (squintRight * 0.008f)
                    z += (smileRight * 0.008f) + (puff * 0.014f)
                }

                // ----------------------------------------------------
                // 6. NOSTRILS & FOREHEAD (Organic Reaction Folds)
                // ----------------------------------------------------
                12 -> { // Left Nostril
                    x -= smileLeft * 0.003f
                    y += smileLeft * 0.004f
                }
                13 -> { // Right Nostril
                    x += smileRight * 0.003f
                    y += smileRight * 0.004f
                }
                0, 1, 2 -> { // Forehead (Lifts with inner brow raise)
                    y += browUp * 0.006f
                }
            }

            // Subtle organic breathing micro-motion so face stays alive even when silent
            val timeMs = android.os.SystemClock.uptimeMillis()
            val breathingPulse = kotlin.math.sin(timeMs * 0.0025f) * 0.0006f
            if (i in 14..15 || i in 19..21) {
                y += breathingPulse
            }

            deformedVertices[offset] = x
            deformedVertices[offset + 1] = y
            deformedVertices[offset + 2] = z

            // Dynamic Normal Vector Calculation for reactive skin lighting & specular sheen
            var nx = baseVertices[offset + 3]
            var ny = baseVertices[offset + 4]
            var nz = baseVertices[offset + 5]

            if (i == 14) { // Left Cheek Mound highlights
                nx -= (smileLeft * 0.25f) + (puff * 0.30f)
                ny += (smileLeft * 0.18f)
            } else if (i == 15) { // Right Cheek Mound highlights
                nx += (smileRight * 0.25f) + (puff * 0.30f)
                ny += (smileRight * 0.18f)
            } else if (i in 19..21) { // Lower Lip angles downward
                ny -= jawDrop * 0.28f
            } else if (i in 27..30) { // Eyelid normal tilts on wink
                nz += ((blinkLeft + blinkRight) * 0.5f) * 0.15f
            }

            val invLen = 1.0f / kotlin.math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(0.001f)
            deformedVertices[offset + 3] = nx * invLen
            deformedVertices[offset + 4] = ny * invLen
            deformedVertices[offset + 5] = nz * invLen
        }

        vertexBuffer.position(0)
        vertexBuffer.put(deformedVertices)
        vertexBuffer.position(0)
    }

    /**
     * Backward-compatible blendshape update method.
     */
    fun updateBlendshapes(jawOpen: Float, smileScore: Float) {
        updateBlendshapes(
            FaceEmotionState(
                jawOpen = jawOpen,
                mouthSmileLeft = smileScore,
                mouthSmileRight = smileScore
            )
        )
    }

    /**
     * Legacy tracking data blendshape update method.
     */
    fun updateBlendshapes(data: FaceTrackingData) {
        updateBlendshapes(data.emotion)
    }
}
