package com.oculus.camerademo.camera

import kotlin.math.abs
import kotlin.math.sqrt

/** Graphics coordinates: +X right, +Y up, -Z forward. */
data class CameraPoint(val x: Float, val y: Float, val z: Float)

data class SensorCrop(val left: Float, val top: Float, val width: Float, val height: Float)

/** Intrinsics are in delivered-image pixels, including any center crop and downsampling. */
data class CameraIntrinsics(
    val focalLengthX: Float,
    val focalLengthY: Float,
    val principalPointX: Float,
    val principalPointY: Float,
    val skew: Float = 0f,
    val distortion: List<Float> = emptyList()
) {
    val isValid: Boolean
        get() = focalLengthX.isFinite() && focalLengthY.isFinite() &&
            focalLengthX > 0f && focalLengthY > 0f && principalPointX.isFinite() &&
            principalPointY.isFinite() && skew.isFinite() &&
            (distortion.isEmpty() || distortion.size >= 5) && distortion.all { it.isFinite() }

    /** Inverts K and, for an uncorrected Camera2 stream, its Brown-Conrady distortion. */
    fun pixelRay(pixelX: Float, pixelY: Float): CameraPoint? {
        if (!isValid || !pixelX.isFinite() || !pixelY.isFinite()) return null
        val distortedY = (pixelY - principalPointY) / focalLengthY
        val distortedX = (pixelX - principalPointX - skew * distortedY) / focalLengthX
        var x = distortedX
        var y = distortedY
        if (distortion.size >= 5) {
            repeat(8) {
                val r2 = x * x + y * y
                val radial = 1f + distortion[0] * r2 + distortion[1] * r2 * r2 +
                    distortion[2] * r2 * r2 * r2
                if (!radial.isFinite() || abs(radial) < 0.00001f) return null
                val deltaX = 2f * distortion[3] * x * y + distortion[4] * (r2 + 2f * x * x)
                val deltaY = 2f * distortion[4] * x * y + distortion[3] * (r2 + 2f * y * y)
                x = (distortedX - deltaX) / radial
                y = (distortedY - deltaY) / radial
            }
        }
        val length = sqrt(x * x + y * y + 1f)
        if (!length.isFinite()) return null
        return CameraPoint(x / length, -y / length, -1f / length)
    }

    companion object {
        /** Calibration's origin is the pre-correction active-array's top-left, per Camera2. */
        fun fromSensorCalibration(
            calibration: FloatArray,
            preCorrectionLeft: Int,
            preCorrectionTop: Int,
            crop: SensorCrop,
            outputWidth: Int,
            outputHeight: Int,
            distortion: List<Float> = emptyList()
        ): CameraIntrinsics? {
            if (calibration.size < 5 || crop.width <= 0f || crop.height <= 0f ||
                outputWidth <= 0 || outputHeight <= 0) return null
            val scaleX = outputWidth / crop.width
            val scaleY = outputHeight / crop.height
            return CameraIntrinsics(
                calibration[0] * scaleX,
                calibration[1] * scaleY,
                (calibration[2] + preCorrectionLeft - crop.left) * scaleX,
                (calibration[3] + preCorrectionTop - crop.top) * scaleY,
                calibration[4] * scaleX,
                distortion
            ).takeIf { it.isValid }
        }
    }
}

/** Camera graphics -> HMD graphics transform, in meters, quaternion order (x, y, z, w). */
data class CameraExtrinsics(
    val translationX: Float,
    val translationY: Float,
    val translationZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationW: Float
) {
    fun toHeadset(point: CameraPoint): CameraPoint {
        val tx = 2f * (rotationY * point.z - rotationZ * point.y)
        val ty = 2f * (rotationZ * point.x - rotationX * point.z)
        val tz = 2f * (rotationX * point.y - rotationY * point.x)
        return CameraPoint(
            point.x + rotationW * tx + rotationY * tz - rotationZ * ty + translationX,
            point.y + rotationW * ty + rotationZ * tx - rotationX * tz + translationY,
            point.z + rotationW * tz + rotationX * ty - rotationY * tx + translationZ
        )
    }

    companion object {
        /**
         * Camera2 rotation maps device sensor axes to camera (+X right,+Y down,+Z forward).
         * Invert it, then rotate the camera graphics axes 180 degrees about X. Meta's
         * passthrough metadata supplies translation relative to the center of the HMD.
         */
        fun fromAndroidPose(translation: FloatArray?, rotation: FloatArray?): CameraExtrinsics? {
            if (translation == null || rotation == null || translation.size < 3 ||
                rotation.size < 4 || translation.any { !it.isFinite() } ||
                rotation.any { !it.isFinite() }) return null
            val length = sqrt(rotation.take(4).sumOf { (it * it).toDouble() }).toFloat()
            if (length < 0.00001f) return null
            val x = rotation[0] / length
            val y = rotation[1] / length
            val z = rotation[2] / length
            val w = rotation[3] / length
            // conjugate(q) * quaternion(180 degrees about X)
            return CameraExtrinsics(translation[0], translation[1], translation[2], w, -z, y, x)
        }
    }
}
