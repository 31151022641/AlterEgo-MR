package com.oculus.camerademo.renderer

import kotlin.math.sqrt

/** World-position jitter filter, bounded to 4 mm so moving faces cannot trail far behind. */
class FacePositionStability {
    data class Position(val x: Float,val y: Float,val z: Float)
    private var position: Position? = null
    private var timestamp = -1L
    fun update(value: Position,capturedAt: Long): Position {
        val old = position
        if (capturedAt == timestamp && old != null) return old
        val gap = capturedAt-timestamp
        val dx = if (old == null) 0f else value.x-old.x
        val dy = if (old == null) 0f else value.y-old.y
        val dz = if (old == null) 0f else value.z-old.z
        val distance = sqrt(dx*dx+dy*dy+dz*dz)
        val alpha = if (old == null || gap !in 1..150 || distance > .06f) 1f
            else maxOf(.65f,1f-.004f/distance.coerceAtLeast(.00001f))
        val result = if (old == null) value else Position(old.x+dx*alpha,old.y+dy*alpha,old.z+dz*alpha)
        position = result; timestamp = capturedAt; return result
    }
}
