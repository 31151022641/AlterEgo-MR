package com.oculus.camerademo.ml

/**
 * Recognized high-level emotion categories detected in real-time from the human head object.
 */
enum class RecognizedEmotion(
    val emoji: String,
    val title: String,
    val description: String
) {
    NEUTRAL("😐", "Neutral", "Natural relaxed expression"),
    HAPPY_SMILE("😄", "Smiling", "Warm smile with upturned mouth corners and raised cheeks"),
    WINKING_LEFT("😜", "Winking Left", "Left eye playfully winked shut with right eye open"),
    WINKING_RIGHT("😉", "Winking Right", "Right eye playfully winked shut with left eye open"),
    SURPRISED("😲", "Surprised", "Wide open mouth with raised eyebrows and wide eyes"),
    OPEN_MOUTH("😮", "Open Mouth", "Jaw dropped open speaking, vocalizing, or laughing"),
    POUTING_PUCKER("😙", "Pucker", "Lips funneled / puckered forward in an 'O' shape"),
    FROWNING("🙁", "Frowning", "Downturned mouth corners and sad expression"),
    ANGRY_FOCUSED("😠", "Focused / Intense", "Furrowed brow lowered downward over eyes")
}

/**
 * Detailed facial expression and emotion state captured from the 3D tracked human head object.
 * Contains 20+ standardized blendshape weights and classified dominant emotion.
 */
data class FaceEmotionState(
    val dominantEmotion: RecognizedEmotion = RecognizedEmotion.NEUTRAL,
    val confidence: Float = 1.0f,

    // Eyes: Independent Left & Right for Winking and Squinting
    val eyeBlinkLeft: Float = 0f,
    val eyeBlinkRight: Float = 0f,
    val eyeSquintLeft: Float = 0f,
    val eyeSquintRight: Float = 0f,
    val eyeWideLeft: Float = 0f,
    val eyeWideRight: Float = 0f,

    // Jaw & Mouth: Open, Smile, Frown, Pucker, Funnel
    val jawOpen: Float = 0f,
    val mouthSmileLeft: Float = 0f,
    val mouthSmileRight: Float = 0f,
    val mouthFrownLeft: Float = 0f,
    val mouthFrownRight: Float = 0f,
    val mouthPucker: Float = 0f,
    val mouthFunnel: Float = 0f,

    // Eyebrows & Forehead: Surprise, Focus, Anger, Skepticism
    val browInnerUp: Float = 0f,
    val browDownLeft: Float = 0f,
    val browDownRight: Float = 0f,
    val browOuterUpLeft: Float = 0f,
    val browOuterUpRight: Float = 0f,

    // Cheeks: Smile Puff & Squint Mound
    val cheekPuff: Float = 0f,
    val cheekSquintLeft: Float = 0f,
    val cheekSquintRight: Float = 0f
) {
    val isWinkingLeft: Boolean get() = eyeBlinkLeft > 0.40f && eyeBlinkRight < 0.25f
    val isWinkingRight: Boolean get() = eyeBlinkRight > 0.40f && eyeBlinkLeft < 0.25f
    val areBothEyesClosed: Boolean get() = eyeBlinkLeft > 0.40f && eyeBlinkRight > 0.40f
    val isSmiling: Boolean get() = (mouthSmileLeft + mouthSmileRight) * 0.5f > 0.35f
    val isSurprised: Boolean get() = jawOpen > 0.40f && browInnerUp > 0.30f
    val isFrowning: Boolean get() = (mouthFrownLeft + mouthFrownRight) * 0.5f > 0.25f
    val isPouting: Boolean get() = mouthPucker > 0.35f || mouthFunnel > 0.35f
    val isOpenMouth: Boolean get() = jawOpen > 0.30f

    companion object {
        /**
         * Classifies high-level dominant emotion from detailed blendshape values.
         */
        fun classifyEmotion(
            eyeBlinkLeft: Float,
            eyeBlinkRight: Float,
            jawOpen: Float,
            mouthSmileLeft: Float,
            mouthSmileRight: Float,
            mouthFrownLeft: Float,
            mouthFrownRight: Float,
            mouthPucker: Float,
            mouthFunnel: Float,
            browInnerUp: Float,
            browDownLeft: Float,
            browDownRight: Float
        ): Pair<RecognizedEmotion, Float> {
            val avgSmile = (mouthSmileLeft + mouthSmileRight) * 0.5f
            val avgFrown = (mouthFrownLeft + mouthFrownRight) * 0.5f
            val avgBrowDown = (browDownLeft + browDownRight) * 0.5f

            // 1. Asymmetric Eye Winking Check
            if (eyeBlinkLeft > 0.40f && eyeBlinkRight < 0.25f) {
                return Pair(RecognizedEmotion.WINKING_LEFT, eyeBlinkLeft)
            }
            if (eyeBlinkRight > 0.40f && eyeBlinkLeft < 0.25f) {
                return Pair(RecognizedEmotion.WINKING_RIGHT, eyeBlinkRight)
            }

            // 2. Surprised: Wide Jaw + Raised Eyebrows
            if (jawOpen > 0.38f && browInnerUp > 0.30f) {
                return Pair(RecognizedEmotion.SURPRISED, (jawOpen + browInnerUp) * 0.5f)
            }

            // 3. Pouting / Pucker
            if (mouthPucker > 0.35f || mouthFunnel > 0.35f) {
                return Pair(RecognizedEmotion.POUTING_PUCKER, maxOf(mouthPucker, mouthFunnel))
            }

            // 4. Happy / Big Smile
            if (avgSmile > 0.35f) {
                return Pair(RecognizedEmotion.HAPPY_SMILE, avgSmile)
            }

            // 5. Open Mouth / Speaking
            if (jawOpen > 0.35f) {
                return Pair(RecognizedEmotion.OPEN_MOUTH, jawOpen)
            }

            // 6. Frowning / Sad
            if (avgFrown > 0.28f && avgSmile < 0.20f) {
                return Pair(RecognizedEmotion.FROWNING, avgFrown)
            }

            // 7. Angry / Intense Focus
            if (avgBrowDown > 0.35f && avgSmile < 0.20f) {
                return Pair(RecognizedEmotion.ANGRY_FOCUSED, avgBrowDown)
            }

            return Pair(RecognizedEmotion.NEUTRAL, 1.0f)
        }
    }
}
