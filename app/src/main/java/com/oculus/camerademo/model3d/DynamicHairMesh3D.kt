package com.oculus.camerademo.model3d

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Single dynamic hair lock/strand governed by spring-mass secondary inertia physics.
 */
class HairStrandLock(
    val id: Int,
    val rootCenter: FloatArray,    // [x, y, z] anchor on skull
    val restTipCenter: FloatArray, // [x, y, z] rest position of tip
    val width: Float = 0.024f,     // Width of the hair ribbon
    val stiffness: Float = 130.0f, // Spring restoring constant k
    val damping: Float = 14.5f,    // Damping resistance c
    val mass: Float = 0.045f       // Mass m
) {
    // Current simulated displacement from rest tip in head local space
    val currentTipDisplacement = floatArrayOf(0f, 0f, 0f)
    val tipVelocity = floatArrayOf(0f, 0f, 0f)

    fun updatePhysics(
        dt: Float,
        headVelX: Float,
        headVelY: Float,
        headVelZ: Float,
        headYawVel: Float,
        headPitchVel: Float,
        headRollVel: Float,
        timeSec: Float
    ) {
        // 1. Inertial forces opposing head motion (Newton's 1st law)
        // Turning head (yaw) whips hair in the opposite direction
        val yawInertia = -headYawVel * 0.0010f
        val pitchInertia = -headPitchVel * 0.0009f
        val rollInertia = -headRollVel * 0.0007f

        // Natural idle breathing micro-sway when still (subtle living motion)
        val idleSwayX = sin(timeSec * 2.2f + id * 0.7f) * 0.002f
        val idleSwayY = sin(timeSec * 3.0f + id * 1.1f) * 0.0015f

        // Total forces: Spring restoring force + Damping + Head Inertia + Subtle gravity
        val forceX = (-headVelX * 2.8f) + yawInertia + rollInertia + idleSwayX -
                (stiffness * currentTipDisplacement[0]) - (damping * tipVelocity[0])

        val forceY = (-headVelY * 2.8f) + pitchInertia + idleSwayY -
                (stiffness * currentTipDisplacement[1]) - (damping * tipVelocity[1]) - 0.20f // Gravity pull

        val forceZ = (-headVelZ * 2.8f) -
                (stiffness * currentTipDisplacement[2]) - (damping * tipVelocity[2])

        // 2. Numerical integration
        val ax = forceX / mass
        val ay = forceY / mass
        val az = forceZ / mass

        tipVelocity[0] += ax * dt
        tipVelocity[1] += ay * dt
        tipVelocity[2] += az * dt

        currentTipDisplacement[0] += tipVelocity[0] * dt
        currentTipDisplacement[1] += tipVelocity[1] * dt
        currentTipDisplacement[2] += tipVelocity[2] * dt

        // 3. Elastic constraint: prevent unnatural stretching beyond hair length
        val maxDisplacement = 0.045f // 4.5 cm maximum sway
        val dispLen = hypot(
            currentTipDisplacement[0].toDouble(),
            hypot(currentTipDisplacement[1].toDouble(), currentTipDisplacement[2].toDouble())
        ).toFloat()

        if (dispLen > maxDisplacement) {
            val scale = maxDisplacement / dispLen
            currentTipDisplacement[0] *= scale
            currentTipDisplacement[1] *= scale
            currentTipDisplacement[2] *= scale
            tipVelocity[0] *= 0.5f
            tipVelocity[1] *= 0.5f
            tipVelocity[2] *= 0.5f
        }
    }
}

/**
 * 3D Dynamic Hair Mesh for the augmented human head (e.g. Lionel Messi 3D).
 * Features multi-strand physics-based secondary motion (Verlet/spring-mass),
 * swaying, bouncing, and reacting in real-time as the physical head object moves.
 */
class DynamicHairMesh3D {

    // 10 Anatomical Hair Locks distributed across the skull:
    // Fringe (front bangs), Forelock, Left & Right Crests, and Crown
    val hairStrands = listOf(
        // 1. Center-Left Fringe (Messi's characteristic front parting)
        HairStrandLock(0, floatArrayOf(-0.025f, 0.155f, 0.065f), floatArrayOf(-0.035f, 0.118f, 0.088f), width = 0.022f),
        // 2. Center-Right Fringe
        HairStrandLock(1, floatArrayOf(0.025f, 0.155f, 0.065f), floatArrayOf(0.035f, 0.118f, 0.088f), width = 0.022f),
        // 3. Outer Left Temple
        HairStrandLock(2, floatArrayOf(-0.075f, 0.145f, 0.035f), floatArrayOf(-0.095f, 0.110f, 0.045f), width = 0.020f),
        // 4. Outer Right Temple
        HairStrandLock(3, floatArrayOf(0.075f, 0.145f, 0.035f), floatArrayOf(0.095f, 0.110f, 0.045f), width = 0.020f),
        // 5. Front Forelock Volume
        HairStrandLock(4, floatArrayOf(0.000f, 0.190f, 0.035f), floatArrayOf(0.000f, 0.225f, 0.048f), width = 0.026f),
        // 6. Left Top Crest
        HairStrandLock(5, floatArrayOf(-0.055f, 0.200f, 0.000f), floatArrayOf(-0.070f, 0.235f, 0.005f), width = 0.025f),
        // 7. Right Top Crest
        HairStrandLock(6, floatArrayOf(0.055f, 0.200f, 0.000f), floatArrayOf(0.070f, 0.235f, 0.005f), width = 0.025f),
        // 8. Skull Apex Crown
        HairStrandLock(7, floatArrayOf(0.000f, 0.210f, -0.045f), floatArrayOf(0.000f, 0.245f, -0.055f), width = 0.028f),
        // 9. Back Left Crown
        HairStrandLock(8, floatArrayOf(-0.065f, 0.190f, -0.060f), floatArrayOf(-0.080f, 0.220f, -0.075f), width = 0.024f),
        // 10. Back Right Crown
        HairStrandLock(9, floatArrayOf(0.065f, 0.190f, -0.060f), floatArrayOf(0.080f, 0.220f, -0.075f), width = 0.024f)
    )

    private val stride = 8 // Pos(3), Normal(3), UV(2)
    private val verticesPerStrand = 4
    val totalVertices = hairStrands.size * verticesPerStrand
    private val vertexData = FloatArray(totalVertices * stride)

    // 2 triangles per quad strand = 6 indices per strand
    val totalIndices = hairStrands.size * 6
    val indices = ShortArray(totalIndices)

    val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()

    // Previous frame state for velocity derivation
    private var lastAnchorX = 0f
    private var lastAnchorY = 0f
    private var lastAnchorZ = 0f
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastRoll = 0f
    private var lastUpdateMs = 0L

    init {
        // Build static index buffer for all hair ribbon quads
        var idx = 0
        for (s in hairStrands.indices) {
            val base = (s * 4).toShort()
            // Triangle 1
            indices[idx++] = base
            indices[idx++] = (base + 1).toShort()
            indices[idx++] = (base + 2).toShort()
            // Triangle 2
            indices[idx++] = (base + 1).toShort()
            indices[idx++] = (base + 3).toShort()
            indices[idx++] = (base + 2).toShort()
        }

        indexBuffer.position(0)
        indexBuffer.put(indices)
        indexBuffer.position(0)

        // Initial geometry build
        updateGeometry()
    }

    /**
     * Updates physics simulation based on the head object's 6-DoF velocity and acceleration.
     */
    fun updatePhysics(
        anchorX: Float,
        anchorY: Float,
        anchorZ: Float,
        yaw: Float,
        pitch: Float,
        roll: Float
    ) {
        val now = SystemClock.uptimeMillis()
        if (lastUpdateMs == 0L) {
            lastAnchorX = anchorX
            lastAnchorY = anchorY
            lastAnchorZ = anchorZ
            lastYaw = yaw
            lastPitch = pitch
            lastRoll = roll
            lastUpdateMs = now
            return
        }

        val dt = ((now - lastUpdateMs) / 1000f).coerceIn(0.008f, 0.050f)
        val timeSec = now / 1000f

        // Compute 6-DoF head velocities
        val velX = (anchorX - lastAnchorX) / dt
        val velY = (anchorY - lastAnchorY) / dt
        val velZ = (anchorZ - lastAnchorZ) / dt
        val yawVel = (yaw - lastYaw) / dt
        val pitchVel = (pitch - lastPitch) / dt
        val rollVel = (roll - lastRoll) / dt

        lastAnchorX = anchorX
        lastAnchorY = anchorY
        lastAnchorZ = anchorZ
        lastYaw = yaw
        lastPitch = pitch
        lastRoll = roll
        lastUpdateMs = now

        // Step simulation for all hair strands
        for (strand in hairStrands) {
            strand.updatePhysics(dt, velX, velY, velZ, yawVel, pitchVel, rollVel, timeSec)
        }

        updateGeometry()
    }

    /**
     * Rebuilds hair ribbon quad vertices with updated physics sway offsets.
     */
    private fun updateGeometry() {
        var vIdx = 0

        for (strand in hairStrands) {
            val rx = strand.rootCenter[0]
            val ry = strand.rootCenter[1]
            val rz = strand.rootCenter[2]

            // Dynamic tip position with physics displacement
            val tx = strand.restTipCenter[0] + strand.currentTipDisplacement[0]
            val ty = strand.restTipCenter[1] + strand.currentTipDisplacement[1]
            val tz = strand.restTipCenter[2] + strand.currentTipDisplacement[2]

            val halfW = strand.width * 0.5f

            // Vertex 0: Root Left
            vertexData[vIdx++] = rx - halfW
            vertexData[vIdx++] = ry
            vertexData[vIdx++] = rz
            vertexData[vIdx++] = 0.0f; vertexData[vIdx++] = 0.3f; vertexData[vIdx++] = 0.9f // Normal
            vertexData[vIdx++] = 0.3f; vertexData[vIdx++] = 0.15f // UV in hair texture region

            // Vertex 1: Root Right
            vertexData[vIdx++] = rx + halfW
            vertexData[vIdx++] = ry
            vertexData[vIdx++] = rz
            vertexData[vIdx++] = 0.0f; vertexData[vIdx++] = 0.3f; vertexData[vIdx++] = 0.9f
            vertexData[vIdx++] = 0.7f; vertexData[vIdx++] = 0.15f

            // Vertex 2: Tip Left (Dynamic Sway)
            vertexData[vIdx++] = tx - halfW * 0.8f
            vertexData[vIdx++] = ty
            vertexData[vIdx++] = tz
            vertexData[vIdx++] = 0.0f; vertexData[vIdx++] = 0.2f; vertexData[vIdx++] = 0.95f
            vertexData[vIdx++] = 0.35f; vertexData[vIdx++] = 0.02f

            // Vertex 3: Tip Right (Dynamic Sway)
            vertexData[vIdx++] = tx + halfW * 0.8f
            vertexData[vIdx++] = ty
            vertexData[vIdx++] = tz
            vertexData[vIdx++] = 0.0f; vertexData[vIdx++] = 0.2f; vertexData[vIdx++] = 0.95f
            vertexData[vIdx++] = 0.65f; vertexData[vIdx++] = 0.02f
        }

        vertexBuffer.position(0)
        vertexBuffer.put(vertexData)
        vertexBuffer.position(0)
    }
}
