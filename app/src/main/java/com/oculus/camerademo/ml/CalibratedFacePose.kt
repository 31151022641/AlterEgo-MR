package com.oculus.camerademo.ml

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class HeadQuaternion(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f)

data class CalibratedHeadPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val orientation: HeadQuaternion,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val reprojectionErrorPixels: Float
)

/**
 * Fits a rigid canonical face to undistorted calibrated image rays. Metric scale is a canonical-face
 * assumption, so this remains a MONOCULAR depth estimate, not stereo/range-sensor measurement.
 * Graphics coordinates: +X right, +Y up, visible points have -Z. Identity faces the camera (+Z).
 */
object CalibratedFacePose {
    data class Correspondence(val landmarkIndex: Int, val rayX: Float, val rayY: Float)
    data class CanonicalPoint(val landmarkIndex: Int, val x: Double, val y: Double, val z: Double)

    // MediaPipe canonical_face_model.obj vertices (centimetres converted to metres), Apache 2.0:
    // https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/modules/face_geometry/data/canonical_face_model.obj
    // Mouth corners are excluded so smiling does not rotate the entire head.
    val canonicalPoints = listOf(
        CanonicalPoint(1, 0.0, -0.01126865, 0.07475604),
        CanonicalPoint(6, 0.0, 0.02473255, 0.05788627),
        CanonicalPoint(10, 0.0, 0.08261778, 0.04481535),
        CanonicalPoint(33, -0.04445859, 0.02663991, 0.03173422),
        CanonicalPoint(263, 0.04445859, 0.02663991, 0.03173422),
        CanonicalPoint(152, 0.0, -0.09403378, 0.04264492)
    )

    fun solve(
        observations: List<Correspondence>,
        focalLengthPixels: Float,
        initialMatrixColumnMajor: FloatArray?
    ): CalibratedHeadPose? {
        if (focalLengthPixels <= 0f || !focalLengthPixels.isFinite()) return null
        val observedByIndex = observations.associateBy { it.landmarkIndex }
        val pairs = canonicalPoints.mapNotNull { point -> observedByIndex[point.landmarkIndex]?.let { point to it } }
        if (pairs.size < 6 || pairs.any { !it.second.rayX.isFinite() || !it.second.rayY.isFinite() }) return null
        val left = observedByIndex[33] ?: return null
        val right = observedByIndex[263] ?: return null
        val eyeSpan = sqrt((right.rayX - left.rayX).toDouble().let { it * it } +
            (right.rayY - left.rayY).toDouble().let { it * it })
        if (eyeSpan < 0.004) return null
        val depth = (0.08891718 / eyeSpan + 0.03173422).coerceIn(0.25, 8.0)
        val matrixRotation = initialMatrixColumnMajor?.let { rotationFromColumnMajor(it) }
        val initialRotation = matrixRotation ?: doubleArrayOf(0.0, 0.0, atan2(
            (right.rayY - left.rayY).toDouble(), (right.rayX - left.rayX).toDouble()))
        val centerX = (left.rayX + right.rayX) * 0.5
        val centerY = (left.rayY + right.rayY) * 0.5
        var parameters = doubleArrayOf(initialRotation[0], initialRotation[1], initialRotation[2],
            centerX * (depth - 0.03173422), centerY * (depth - 0.03173422) - 0.02663991, -depth)

        fun residuals(p: DoubleArray): DoubleArray? {
            val rotation = rotationMatrix(p[0], p[1], p[2])
            val residuals = DoubleArray(pairs.size * 2)
            pairs.forEachIndexed { index, (point, observed) ->
                val x = rotation[0] * point.x + rotation[1] * point.y + rotation[2] * point.z + p[3]
                val y = rotation[3] * point.x + rotation[4] * point.y + rotation[5] * point.z + p[4]
                val z = rotation[6] * point.x + rotation[7] * point.y + rotation[8] * point.z + p[5]
                if (z >= -0.10 || !z.isFinite()) return null
                residuals[index * 2] = (x / -z - observed.rayX) * focalLengthPixels
                residuals[index * 2 + 1] = (y / -z - observed.rayY) * focalLengthPixels
            }
            return residuals
        }

        var residual = residuals(parameters) ?: return null
        var error = residual.sumOf { it * it }
        var damping = 0.01
        repeat(25) {
            val jacobian = Array(residual.size) { DoubleArray(6) }
            for (component in 0..5) {
                val perturbed = parameters.copyOf()
                val step = 0.00001
                perturbed[component] += step
                val delta = residuals(perturbed) ?: return null
                for (row in residual.indices) jacobian[row][component] = (delta[row] - residual[row]) / step
            }
            val normal = Array(6) { DoubleArray(6) }
            val rhs = DoubleArray(6)
            for (row in residual.indices) {
                // Limit contribution from an expressive chin or a partially occluded landmark.
                val weight = if (abs(residual[row]) <= 5.0) 1.0 else 5.0 / abs(residual[row])
                for (a in 0..5) {
                    rhs[a] -= jacobian[row][a] * residual[row] * weight
                    for (b in 0..5) normal[a][b] += jacobian[row][a] * jacobian[row][b] * weight
                }
            }
            for (component in 0..5) normal[component][component] += damping * max(normal[component][component], 1.0)
            val delta = solveLinear(normal, rhs) ?: return null
            val candidate = DoubleArray(6) { parameters[it] + delta[it] }
            val candidateResidual = residuals(candidate)
            val candidateError = candidateResidual?.sumOf { it * it } ?: Double.POSITIVE_INFINITY
            if (candidateError < error) {
                parameters = candidate
                residual = candidateResidual!!
                error = candidateError
                damping = (damping * 0.4).coerceAtLeast(0.000001)
            } else {
                damping = (damping * 6.0).coerceAtMost(10000.0)
            }
        }
        val reprojectionRms = sqrt(error / pairs.size)
        val rotation = rotationMatrix(parameters[0], parameters[1], parameters[2])
        if (parameters.any { !it.isFinite() } || -parameters[5] !in 0.25..8.0 ||
            reprojectionRms > max(5.0, focalLengthPixels * 0.02) || rotation[8] <= 0.0) return null
        // Euler fields remain convenience values; render the quaternion to preserve all axes.
        val pitch = asin((-rotation[5]).coerceIn(-1.0, 1.0))
        val yaw = atan2(rotation[2], rotation[8])
        val roll = atan2(rotation[3], rotation[4])
        return CalibratedHeadPose(parameters[3].toFloat(), parameters[4].toFloat(), parameters[5].toFloat(),
            quaternion(rotation), Math.toDegrees(yaw).toFloat(), Math.toDegrees(pitch).toFloat(),
            Math.toDegrees(roll).toFloat(), reprojectionRms.toFloat())
    }

    private fun rotationFromColumnMajor(matrix: FloatArray): DoubleArray? {
        if (matrix.size != 16 || matrix.any { !it.isFinite() }) return null
        val rotation = doubleArrayOf(matrix[0].toDouble(), matrix[4].toDouble(), matrix[8].toDouble(),
            matrix[1].toDouble(), matrix[5].toDouble(), matrix[9].toDouble(),
            matrix[2].toDouble(), matrix[6].toDouble(), matrix[10].toDouble())
        // Remove possible scale from MediaPipe's similarity transform.
        for (column in 0..2) {
            val norm = sqrt((0..2).sumOf { row -> rotation[row * 3 + column].let { it * it } })
            if (norm < 0.00001) return null
            for (row in 0..2) rotation[row * 3 + column] /= norm
        }
        val angle = kotlin.math.acos(((rotation[0] + rotation[4] + rotation[8] - 1.0) / 2.0).coerceIn(-1.0, 1.0))
        if (angle < 0.00001) return doubleArrayOf(0.0, 0.0, 0.0)
        val factor = angle / (2.0 * sin(angle))
        if (!factor.isFinite() || angle > 3.0) return null
        return doubleArrayOf((rotation[7] - rotation[5]) * factor,
            (rotation[2] - rotation[6]) * factor, (rotation[3] - rotation[1]) * factor)
    }

    /** Row-major Rodrigues rotation, exposed for calibrated synthetic verification. */
    internal fun rotationMatrix(x: Double, y: Double, z: Double): DoubleArray {
        val theta = sqrt(x * x + y * y + z * z)
        if (theta < 1e-10) return doubleArrayOf(1.0, -z, y, z, 1.0, -x, -y, x, 1.0)
        val a = sin(theta) / theta
        val b = (1.0 - cos(theta)) / (theta * theta)
        return doubleArrayOf(1.0 - b * (y * y + z * z), b * x * y - a * z, b * x * z + a * y,
            b * x * y + a * z, 1.0 - b * (x * x + z * z), b * y * z - a * x,
            b * x * z - a * y, b * y * z + a * x, 1.0 - b * (x * x + y * y))
    }

    private fun quaternion(r: DoubleArray): HeadQuaternion {
        val trace = r[0] + r[4] + r[8]
        val q = if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            doubleArrayOf((r[7] - r[5]) / s, (r[2] - r[6]) / s, (r[3] - r[1]) / s, s * 0.25)
        } else {
            val largest = (0..2).maxByOrNull { r[it * 3 + it] }!!
            val next = (largest + 1) % 3
            val last = (largest + 2) % 3
            val s = sqrt(1.0 + r[largest * 3 + largest] - r[next * 3 + next] - r[last * 3 + last]) * 2.0
            DoubleArray(4).apply {
                this[largest] = s * 0.25
                this[next] = (r[next * 3 + largest] + r[largest * 3 + next]) / s
                this[last] = (r[last * 3 + largest] + r[largest * 3 + last]) / s
                this[3] = (r[last * 3 + next] - r[next * 3 + last]) / s
            }
        }
        val norm = sqrt(q.sumOf { it * it })
        return HeadQuaternion((q[0] / norm).toFloat(), (q[1] / norm).toFloat(),
            (q[2] / norm).toFloat(), (q[3] / norm).toFloat())
    }

    private fun solveLinear(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val augmented = Array(6) { row -> DoubleArray(7) { col -> if (col == 6) rhs[row] else matrix[row][col] } }
        for (pivot in 0..5) {
            val best = (pivot..5).maxByOrNull { abs(augmented[it][pivot]) }!!
            if (abs(augmented[best][pivot]) < 1e-12) return null
            val swapped = augmented[pivot]
            augmented[pivot] = augmented[best]
            augmented[best] = swapped
            val divisor = augmented[pivot][pivot]
            for (col in pivot..6) augmented[pivot][col] /= divisor
            for (row in 0..5) if (row != pivot) {
                val factor = augmented[row][pivot]
                for (col in pivot..6) augmented[row][col] -= factor * augmented[pivot][col]
            }
        }
        return DoubleArray(6) { augmented[it][6] }
    }
}
