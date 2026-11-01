package com.oculus.camerademo.eyetracking

import kotlin.math.sqrt

/** Values in Spatial SDK's scene coordinate system (metres, +Z forward). */
data class GazeVector(val x: Float, val y: Float, val z: Float) {
    operator fun plus(v: GazeVector) = GazeVector(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: GazeVector) = GazeVector(x - v.x, y - v.y, z - v.z)
    operator fun times(scale: Float) = GazeVector(x * scale, y * scale, z * scale)
    fun dot(v: GazeVector) = x * v.x + y * v.y + z * v.z
    fun normalized(): GazeVector? {
        val length = sqrt(dot(this))
        return if (length.isFinite() && length > 0.00001f) this * (1f / length) else null
    }
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
}

data class GazeRotation(val w: Float = 1f, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun inverseRotate(v: GazeVector): GazeVector {
        val norm = w * w + x * x + y * y + z * z
        if (!norm.isFinite() || norm < 0.00001f) return GazeVector(Float.NaN, Float.NaN, Float.NaN)
        val inv = 1f / sqrt(norm)
        val qw = w * inv
        val qx = -x * inv
        val qy = -y * inv
        val qz = -z * inv
        val tx = 2f * (qy * v.z - qz * v.y)
        val ty = 2f * (qz * v.x - qx * v.z)
        val tz = 2f * (qx * v.y - qy * v.x)
        return GazeVector(
            v.x + qw * tx + qy * tz - qz * ty,
            v.y + qw * ty + qz * tx - qx * tz,
            v.z + qw * tz + qx * ty - qy * tx,
        )
    }
}

data class GazeRay(val origin: GazeVector, val direction: GazeVector, val sampledAtMs: Long)

/** An oriented ellipsoid matching the rendered head's pose and physical dimensions. */
data class GazeHeadTarget(
    val id: Int,
    val center: GazeVector,
    val radii: GazeVector,
    val rotation: GazeRotation = GazeRotation(),
)

data class GazeSelection(val candidateId: Int?, val lockedId: Int?, val dwellTimeMs: Long)

/** Pure geometry and dwell logic; never invents a gaze sample or chooses an image-space centre. */
class GazeTargetSelector(private val dwellThresholdMs: Long = 120L, private val maxSampleAgeMs: Long = 150L) {
    private var candidateId: Int? = null
    private var fixationStartMs = 0L

    fun reset() {
        candidateId = null
        fixationStartMs = 0L
    }

    fun select(ray: GazeRay?, targets: List<GazeHeadTarget>, nowMs: Long): GazeSelection {
        if (ray == null || nowMs < ray.sampledAtMs || nowMs - ray.sampledAtMs > maxSampleAgeMs ||
            !ray.origin.isFinite() || ray.direction.normalized() == null) {
            reset()
            return GazeSelection(null, null, 0L)
        }
        val hit = targets.mapNotNull { target -> intersectionDistance(ray, target)?.let { target.id to it } }
            .minByOrNull { it.second }?.first
        if (hit != candidateId) {
            candidateId = hit
            fixationStartMs = nowMs
        }
        val dwell = if (hit == null) 0L else (nowMs - fixationStartMs).coerceAtLeast(0L)
        return GazeSelection(hit, hit?.takeIf { dwell >= dwellThresholdMs }, dwell)
    }

    private fun intersectionDistance(ray: GazeRay, target: GazeHeadTarget): Float? {
        val r = target.radii
        if (!r.isFinite() || r.x <= 0f || r.y <= 0f || r.z <= 0f || !target.center.isFinite()) return null
        val origin = target.rotation.inverseRotate(ray.origin - target.center)
        val direction = target.rotation.inverseRotate(ray.direction.normalized() ?: return null)
        val o = GazeVector(origin.x / r.x, origin.y / r.y, origin.z / r.z)
        val d = GazeVector(direction.x / r.x, direction.y / r.y, direction.z / r.z)
        val a = d.dot(d)
        val b = 2f * o.dot(d)
        val c = o.dot(o) - 1f
        val discriminant = b * b - 4f * a * c
        if (!discriminant.isFinite() || discriminant < 0f || a <= 0f) return null
        val root = sqrt(discriminant)
        val near = (-b - root) / (2f * a)
        val far = (-b + root) / (2f * a)
        return when {
            near >= 0f -> near
            far >= 0f -> far
            else -> null
        }
    }
}
