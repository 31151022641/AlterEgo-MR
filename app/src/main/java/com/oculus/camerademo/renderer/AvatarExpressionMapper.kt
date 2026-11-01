package com.oculus.camerademo.renderer

import com.oculus.camerademo.ml.FaceEmotionState

/** The old procedural rig named image-left as left; detector weights use anatomical left. */
object AvatarExpressionMapper {
    fun forLegacyMesh(expression: FaceEmotionState) = expression.copy(
        eyeBlinkLeft = expression.eyeBlinkRight, eyeBlinkRight = expression.eyeBlinkLeft,
        eyeSquintLeft = expression.eyeSquintRight, eyeSquintRight = expression.eyeSquintLeft,
        eyeWideLeft = expression.eyeWideRight, eyeWideRight = expression.eyeWideLeft,
        mouthSmileLeft = expression.mouthSmileRight, mouthSmileRight = expression.mouthSmileLeft,
        mouthFrownLeft = expression.mouthFrownRight, mouthFrownRight = expression.mouthFrownLeft,
        browDownLeft = expression.browDownRight, browDownRight = expression.browDownLeft,
        browOuterUpLeft = expression.browOuterUpRight, browOuterUpRight = expression.browOuterUpLeft,
        cheekSquintLeft = expression.cheekSquintRight, cheekSquintRight = expression.cheekSquintLeft,
    )
}
