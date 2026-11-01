package com.oculus.camerademo.mirror

/** The mirror must never fall back to a passthrough or third-party camera. */
data class AvatarCameraCandidate(val id: String, val source: Int?, val frontFacing: Boolean)

object AvatarCameraPolicy {
    fun select(cameras: List<AvatarCameraCandidate>): String? =
        cameras.firstOrNull { it.source == 1 }?.id
            ?: cameras.firstOrNull { it.source == null && it.frontFacing }?.id
}

/** A single frame's face estimate. Coordinates are normalized camera-image coordinates. */
data class AvatarVideoFace(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val rotation: FloatArray,
    val expression: com.oculus.camerademo.ml.FaceEmotionState,
    val gazeX: Float = 0f, val gazeY: Float = 0f,
    val blendshapes: Map<String, Float> = emptyMap(),
) {
    val usable: Boolean get() = listOf(left, top, right, bottom).all { it.isFinite() } &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
        right - left in .025f.. .8f && bottom - top in .035f.. .9f &&
        rotation.size == 16 && rotation.all { it.isFinite() } && (0..2).all { column ->
            val at = column*4
            val norm = rotation[at]*rotation[at] + rotation[at+1]*rotation[at+1] + rotation[at+2]*rotation[at+2]
            norm in .01f..100f
        }
}

data class AvatarFrameCrop(val left: Float, val top: Float, val width: Float, val height: Float)

object AvatarFaceFit {
    fun adjust(face: AvatarVideoFace,scale: Float,x: Float,y: Float): AvatarVideoFace {
        if (!listOf(scale,x,y).all { it.isFinite() }) return face
        val width = face.right-face.left; val height = face.bottom-face.top
        val cx = (face.left+face.right)*.5f+x.coerceIn(-.12f,.12f)*width
        val cy = (face.top+face.bottom)*.5f+y.coerceIn(-.12f,.12f)*height
        val halfWidth = width*scale.coerceIn(.8f,1.25f)*.5f
        val halfHeight = height*scale.coerceIn(.8f,1.25f)*.5f
        return face.copy(left = (cx-halfWidth).coerceAtLeast(0f),right = (cx+halfWidth).coerceAtMost(1f),
            top = (cy-halfHeight).coerceAtLeast(0f),bottom = (cy+halfHeight).coerceAtMost(1f))
    }
}

object AvatarFrameFraming {
    fun crop(sourceWidth: Int, sourceHeight: Int, zoom: Float): AvatarFrameCrop {
        require(sourceWidth > 0 && sourceHeight > 0)
        val aspect = 16f/9f
        var width = sourceWidth.toFloat(); var height = width/aspect
        if (height > sourceHeight) { height = sourceHeight.toFloat(); width = height*aspect }
        val magnification = if (zoom.isFinite()) zoom.coerceIn(1f,2.2f) else 1f
        width /= magnification; height /= magnification
        return AvatarFrameCrop((sourceWidth-width)*.5f,(sourceHeight-height)*.35f,width,height)
    }
}
