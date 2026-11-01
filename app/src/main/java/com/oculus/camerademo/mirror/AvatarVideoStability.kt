package com.oculus.camerademo.mirror

import kotlin.math.abs

/** Filters small detection jitter only. A miss or a large jump resets immediately. */
class AvatarVideoStability {
    private var previous: AvatarVideoFace? = null
    private var timestamp = 0L
    fun update(face: AvatarVideoFace?,now: Long): AvatarVideoFace? {
        if (face == null || !face.usable) { previous = null; timestamp = now; return null }
        val old = previous
        val displacement = if (old == null) 1f else abs(face.left-old.left)+abs(face.top-old.top)+abs(face.right-old.right)+abs(face.bottom-old.bottom)
        val alpha = if (old == null || now <= timestamp || now-timestamp > 200L || displacement > .08f) 1f else .65f
        fun mix(a: Float,b: Float) = a+(b-a)*alpha
        val result = if (old == null || alpha == 1f) face else face.copy(left = mix(old.left,face.left),top = mix(old.top,face.top),
            right = mix(old.right,face.right),bottom = mix(old.bottom,face.bottom))
        previous = result; timestamp = now
        return result
    }
}
