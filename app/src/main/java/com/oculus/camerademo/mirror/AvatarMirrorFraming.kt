package com.oculus.camerademo.mirror

/** Metre-scale belly-up framing. Head/shoulders through waist fit in view;
 * the head remains life-sized rather than being squashed to make room. */
object AvatarMirrorFraming {
    const val HALF_HEIGHT = .55f
    const val CENTER_Y = -.27f
    const val HEAD_SCALE = 1f

    @JvmStatic fun projectX(x: Float, width: Int, height: Int): Float =
        width * .5f + x * height / (2f * HALF_HEIGHT)

    @JvmStatic fun projectY(y: Float, width: Int, height: Int): Float =
        height * .5f - (y - CENTER_Y) * height / (2f * HALF_HEIGHT)
}
