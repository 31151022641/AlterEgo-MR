package com.oculus.camerademo.renderer

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.oculus.camerademo.eyetracking.EyeGazeData
import com.oculus.camerademo.ml.FaceTrackingData
import com.oculus.camerademo.ml.MultiFaceTrackingData
import com.oculus.camerademo.ml.TrackedHeadObject
import com.oculus.camerademo.model3d.DynamicHairMesh3D
import com.oculus.camerademo.model3d.HumanFaceMesh3D
import com.oculus.camerademo.model3d.HumanPersona
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.hypot

class FaceMeshRenderer {

    private val mesh3D = HumanFaceMesh3D()
    private val hairMesh = DynamicHairMesh3D()

    // Model, View, Projection matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    private var programId = 0
    private var uMVPMatrixLoc = 0
    private var uNormalMatrixLoc = 0
    private var uColorLoc = 0
    private var uAccentColorLoc = 0
    private var uAlphaLoc = 0
    private var uLightDirLoc = 0
    private var uFaceTextureLoc = 0
    private var uUseFaceTextureLoc = 0
    private var aPositionLoc = 0
    private var aNormalLoc = 0
    private var aFaceTexCoordLoc = 0

    // Background video texture quad with Foveated Gaze Shader
    private var videoProgramId = 0
    private var videoTextureId = 0
    private var uGazeCenterLoc = 0
    private var uFoveaRadiusLoc = 0
    private var uFoveatedEnabledLoc = 0
    private var uShowFoveaRingLoc = 0
    private var uPeripheralGridLoc = 0

    // 3D Lionel Messi / Avatar Texture
    private var faceTextureId = 0

    // Default to Lionel Messi 3D for debug
    var currentPersona: HumanPersona = HumanPersona.MESSI_3D
    var morphAlpha: Float = 0.90f // 0.0 = Real person, 1.0 = Full 3D Lionel Messi Face
    var headScale: Float = 1.0f // 1.0 = Default natural size matching real person's head/object
    var resolutionQuality: ResolutionQuality = ResolutionQuality.BALANCED
    var isFoveatedRenderingEnabled: Boolean = true
    var showGazeIndicatorRing: Boolean = true
    var isHairPhysicsEnabled: Boolean = true // Real-time spring-mass hair inertia physics
    var isPartyModeEnabled: Boolean = false // Party Mode: morph ALL heads simultaneously
    var isPartyDiversePersonas: Boolean = false // Party Mode: diverse multi-persona crowd

    // Per-head dynamic hair physics engines so each person has independent hair inertia
    private val hairMeshes = mutableMapOf<Int, DynamicHairMesh3D>()

    // Smooth transition when eye gaze locks onto or leaves a physical head object
    private val headTransitionAlphas = mutableMapOf<Int, Float>()
    private var currentGazeTransitionAlpha: Float = 0.0f

    private val quadVertices = floatArrayOf(
        // Pos(X, Y), UV(U, V)
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f
    )

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(quadVertices)
            position(0)
        }

    fun initGL() {
        // Compile Shaders for 3D Human Face
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMVPMatrixLoc = GLES30.glGetUniformLocation(programId, "uMVPMatrix")
        uNormalMatrixLoc = GLES30.glGetUniformLocation(programId, "uNormalMatrix")
        uColorLoc = GLES30.glGetUniformLocation(programId, "uColor")
        uAccentColorLoc = GLES30.glGetUniformLocation(programId, "uAccentColor")
        uAlphaLoc = GLES30.glGetUniformLocation(programId, "uAlpha")
        uLightDirLoc = GLES30.glGetUniformLocation(programId, "uLightDir")
        uFaceTextureLoc = GLES30.glGetUniformLocation(programId, "uFaceTexture")
        uUseFaceTextureLoc = GLES30.glGetUniformLocation(programId, "uUseFaceTexture")
        aPositionLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        aNormalLoc = GLES30.glGetAttribLocation(programId, "aNormal")
        aFaceTexCoordLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")

        // Compile Shader for Foveated camera background video quad
        videoProgramId = createProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        uGazeCenterLoc = GLES30.glGetUniformLocation(videoProgramId, "uGazeCenter")
        uFoveaRadiusLoc = GLES30.glGetUniformLocation(videoProgramId, "uFoveaRadius")
        uFoveatedEnabledLoc = GLES30.glGetUniformLocation(videoProgramId, "uFoveatedEnabled")
        uShowFoveaRingLoc = GLES30.glGetUniformLocation(videoProgramId, "uShowFoveaRing")
        uPeripheralGridLoc = GLES30.glGetUniformLocation(videoProgramId, "uPeripheralGrid")

        val texIds = IntArray(2)
        GLES30.glGenTextures(2, texIds, 0)
        videoTextureId = texIds[0]
        faceTextureId = texIds[1]

        // Setup Video Texture
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Setup Face Texture
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, faceTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
    }

    fun setFaceTexture(bitmap: Bitmap) {
        if (faceTextureId != 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, faceTextureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        }
    }

    fun updateCameraTexture(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 60.0f, aspect, 0.1f, 100.0f)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
    }

    /**
     * Renders the scene contingent on eye tracking gaze:
     * - Background is sharp at gaze point and low-resolution in peripheral vision.
     * - The 3D Head model (e.g. Lionel Messi 3D) anchors and sticks directly onto the specific
     *   physical human head object that the user's eye tracking gaze is focused on.
     * - Heads outside of the eye tracking gaze area retain their natural appearance without 3D overlay.
     */
    fun renderMulti(multiTrackingData: MultiFaceTrackingData, gazeData: EyeGazeData) {
        // Enforce 100% transparent clear color for hardware passthrough compositing (No VR void)
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // 1. Render camera background with Foveated eye-tracking resolution pass
        renderFoveatedBackground(gazeData)

        // 2. Iterate through all tracked human head objects detected by the front passthrough camera
        val allPersonas = HumanPersona.available
        for ((index, head) in multiTrackingData.trackedHeads.withIndex()) {
            val distToGaze = hypot(
                (head.faceCenterX - gazeData.gazeX).toDouble(),
                (head.faceCenterY - gazeData.gazeY).toDouble()
            ).toFloat()

            // Eye tracking focus check: does the gaze fall on this specific human head?
            val isLookedAt = distToGaze <= (gazeData.foveaRadius * 1.35f)

            // In Party Mode: all detected heads in the room are morphed simultaneously!
            // In Focus Mode: only the specific human head that the user's eye tracking gaze is focused on is morphed.
            val targetAlpha = if (isPartyModeEnabled) {
                1.0f
            } else {
                if (isLookedAt) 1.0f else 0.0f
            }

            // Smooth transition for sticking/un-sticking the 3D head to this object
            val prevAlpha = headTransitionAlphas.getOrPut(head.id) { 0.0f }
            val currentAlpha = prevAlpha + (targetAlpha - prevAlpha) * 0.22f
            headTransitionAlphas[head.id] = currentAlpha

            val effectiveAlpha = morphAlpha * currentAlpha

            // 3. Stick and render the 3D Head model
            if (effectiveAlpha > 0.02f) {
                // Determine persona:
                // - If Party Diverse Mode is on: cycle through the available presets.
                // - Otherwise: all heads wear the currently selected persona
                val headPersona = if (isPartyModeEnabled && isPartyDiversePersonas) {
                    allPersonas[kotlin.math.abs(head.id + index) % allPersonas.size]
                } else {
                    currentPersona
                }

                render3DHeadOnObject(head, effectiveAlpha, headPersona, isLookedAt)
            }
        }
    }

    /**
     * Legacy single-face render entry point.
     */
    fun render(trackingData: FaceTrackingData, gazeData: EyeGazeData) {
        if (!trackingData.hasFace) {
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            renderFoveatedBackground(gazeData)
            return
        }

        val distance = trackingData.headDistanceMeters.coerceIn(0.5f, 3.5f)
        val singleHead = TrackedHeadObject(
            id = 0,
            faceCenterX = trackingData.faceCenterX,
            faceCenterY = trackingData.faceCenterY,
            headDistanceMeters = distance,
            rotationYaw = trackingData.rotationYaw,
            rotationPitch = trackingData.rotationPitch,
            rotationRoll = trackingData.rotationRoll,
            anchorX = (trackingData.faceCenterX - 0.5f) * distance * 1.15f,
            anchorY = (0.5f - trackingData.faceCenterY) * distance * 0.9f,
            anchorZ = -distance,
            jawOpen = trackingData.jawOpen,
            smileScore = trackingData.smileScore,
            eyeBlinkScore = trackingData.eyeBlinkScore
        )
        renderMulti(MultiFaceTrackingData(listOf(singleHead), totalTrackedHeads = 1), gazeData)
    }

    private fun renderFoveatedBackground(gazeData: EyeGazeData) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(videoProgramId)

        val uTexLoc = GLES30.glGetUniformLocation(videoProgramId, "uTexture")
        val aPosLoc = GLES30.glGetAttribLocation(videoProgramId, "aPosition")
        val aUvLoc = GLES30.glGetAttribLocation(videoProgramId, "aTexCoord")

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextureId)
        GLES30.glUniform1i(uTexLoc, 0)

        // Set eye gaze uniforms for dynamic foveated resolution
        GLES30.glUniform2f(uGazeCenterLoc, gazeData.gazeX, gazeData.gazeY)
        GLES30.glUniform1f(uFoveaRadiusLoc, gazeData.foveaRadius)
        GLES30.glUniform1i(uFoveatedEnabledLoc, if (isFoveatedRenderingEnabled) 1 else 0)
        GLES30.glUniform1i(uShowFoveaRingLoc, if (showGazeIndicatorRing) 1 else 0)
        GLES30.glUniform1f(uPeripheralGridLoc, resolutionQuality.gridFactor)

        quadBuffer.position(0)
        GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, quadBuffer)
        GLES30.glEnableVertexAttribArray(aPosLoc)

        quadBuffer.position(2)
        GLES30.glVertexAttribPointer(aUvLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, quadBuffer)
        GLES30.glEnableVertexAttribArray(aUvLoc)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPosLoc)
        GLES30.glDisableVertexAttribArray(aUvLoc)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /**
     * Anchors and sticks the 3D human head model directly onto the tracked head object in 3D space.
     */
    private fun render3DHeadOnObject(
        head: TrackedHeadObject,
        alpha: Float,
        persona: HumanPersona = currentPersona,
        isLookedAt: Boolean = false
    ) {
        mesh3D.updateBlendshapes(head.emotion)

        // Per-head dynamic hair physics engine instance
        val headHairMesh = hairMeshes.getOrPut(head.id) { DynamicHairMesh3D() }

        // Update Physics-Driven Secondary Hair Motion (reacts dynamically to head turns, tilts, and accelerations)
        if (isHairPhysicsEnabled) {
            headHairMesh.updatePhysics(
                head.anchorX, head.anchorY, head.anchorZ,
                head.rotationYaw, head.rotationPitch, head.rotationRoll
            )
        }

        // 3D Metric Anchor: Sticking directly to the human head object in front of headset camera
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, head.anchorX, head.anchorY, head.anchorZ)

        // Apply 6-DoF head rotation (yaw, pitch, roll) to match the person's head orientation exactly
        Matrix.rotateM(modelMatrix, 0, head.rotationYaw, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, -head.rotationPitch, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, -head.rotationRoll, 0f, 0f, 1f)

        // Apply head scaling: 1.0f = default natural size matching real person's head/object
        val safeScale = headScale.coerceIn(0.4f, 3.0f)
        Matrix.scaleM(modelMatrix, 0, safeScale, safeScale, safeScale)

        // Multiply MVP
        val mvMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvMatrix, 0)
        GLES30.glUniformMatrix4fv(uNormalMatrixLoc, 1, false, modelMatrix, 0)

        // Set Light Direction (with subtle specular focus if looked at in Party Mode)
        if (isLookedAt && isPartyModeEnabled) {
            GLES30.glUniform3f(uLightDirLoc, 0.45f, 0.85f, 0.60f)
        } else {
            GLES30.glUniform3f(uLightDirLoc, 0.4f, 0.8f, 0.5f)
        }

        // Configure Texture (for Lionel Messi 3D face)
        if (persona.isTextured && faceTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, faceTextureId)
            GLES30.glUniform1i(uFaceTextureLoc, 1)
            GLES30.glUniform1i(uUseFaceTextureLoc, 1)
        } else {
            GLES30.glUniform1i(uUseFaceTextureLoc, 0)
        }

        // Set Colors
        val primary = persona.primaryColor
        val accent = if (isLookedAt && isPartyModeEnabled) 0xFF00E5FF.toInt() else persona.accentColor
        GLES30.glUniform3f(
            uColorLoc,
            android.graphics.Color.red(primary) / 255f,
            android.graphics.Color.green(primary) / 255f,
            android.graphics.Color.blue(primary) / 255f
        )
        GLES30.glUniform3f(
            uAccentColorLoc,
            android.graphics.Color.red(accent) / 255f,
            android.graphics.Color.green(accent) / 255f,
            android.graphics.Color.blue(accent) / 255f
        )
        GLES30.glUniform1f(uAlphaLoc, alpha)

        // Stride: 8 floats (Pos 3, Normal 3, UV 2) = 32 bytes
        val stride = 8 * 4

        // 1. Position (offset 0)
        mesh3D.vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(aPositionLoc, 3, GLES30.GL_FLOAT, false, stride, mesh3D.vertexBuffer)
        GLES30.glEnableVertexAttribArray(aPositionLoc)

        // 2. Normal (offset 3)
        mesh3D.vertexBuffer.position(3)
        GLES30.glVertexAttribPointer(aNormalLoc, 3, GLES30.GL_FLOAT, false, stride, mesh3D.vertexBuffer)
        GLES30.glEnableVertexAttribArray(aNormalLoc)

        // 3. UV TexCoord (offset 6)
        mesh3D.vertexBuffer.position(6)
        GLES30.glVertexAttribPointer(aFaceTexCoordLoc, 2, GLES30.GL_FLOAT, false, stride, mesh3D.vertexBuffer)
        GLES30.glEnableVertexAttribArray(aFaceTexCoordLoc)

        if (persona.wireframe) {
            GLES30.glLineWidth(3.0f)
            GLES30.glDrawElements(GLES30.GL_LINES, mesh3D.indices.size, GLES30.GL_UNSIGNED_SHORT, mesh3D.indexBuffer)
        } else {
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh3D.indices.size, GLES30.GL_UNSIGNED_SHORT, mesh3D.indexBuffer)
        }

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aNormalLoc)
        GLES30.glDisableVertexAttribArray(aFaceTexCoordLoc)

        // 4. Render Dynamic Physics-Driven Hair (reacts to head turns, nods, and tilts like real hair)
        if (isHairPhysicsEnabled) {
            headHairMesh.vertexBuffer.position(0)
            GLES30.glVertexAttribPointer(aPositionLoc, 3, GLES30.GL_FLOAT, false, stride, headHairMesh.vertexBuffer)
            GLES30.glEnableVertexAttribArray(aPositionLoc)

            headHairMesh.vertexBuffer.position(3)
            GLES30.glVertexAttribPointer(aNormalLoc, 3, GLES30.GL_FLOAT, false, stride, headHairMesh.vertexBuffer)
            GLES30.glEnableVertexAttribArray(aNormalLoc)

            headHairMesh.vertexBuffer.position(6)
            GLES30.glVertexAttribPointer(aFaceTexCoordLoc, 2, GLES30.GL_FLOAT, false, stride, headHairMesh.vertexBuffer)
            GLES30.glEnableVertexAttribArray(aFaceTexCoordLoc)

            if (persona.wireframe) {
                GLES30.glLineWidth(2.5f)
                GLES30.glDrawElements(GLES30.GL_LINES, headHairMesh.totalIndices, GLES30.GL_UNSIGNED_SHORT, headHairMesh.indexBuffer)
            } else {
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, headHairMesh.totalIndices, GLES30.GL_UNSIGNED_SHORT, headHairMesh.indexBuffer)
            }

            GLES30.glDisableVertexAttribArray(aPositionLoc)
            GLES30.glDisableVertexAttribArray(aNormalLoc)
            GLES30.glDisableVertexAttribArray(aFaceTexCoordLoc)
        }
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentCode)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uNormalMatrix;
            out vec3 vNormal;
            out vec3 vPosition;
            out vec2 vFaceTexCoord;
            void main() {
                vPosition = aPosition;
                vNormal = normalize((uNormalMatrix * vec4(aNormal, 0.0)).xyz);
                vFaceTexCoord = aTexCoord;
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec3 vNormal;
            in vec3 vPosition;
            in vec2 vFaceTexCoord;
            uniform vec3 uColor;
            uniform vec3 uAccentColor;
            uniform vec3 uLightDir;
            uniform float uAlpha;
            uniform sampler2D uFaceTexture;
            uniform int uUseFaceTexture;
            out vec4 fragColor;
            void main() {
                vec3 norm = normalize(vNormal);
                vec3 light = normalize(uLightDir);
                float diff = max(dot(norm, light), 0.0);
                
                // Specular highlight
                vec3 viewDir = vec3(0.0, 0.0, 1.0);
                vec3 reflectDir = reflect(-light, norm);
                float spec = pow(max(dot(viewDir, reflectDir), 0.0), 16.0);
                
                // Edge rim lighting effect
                float rim = 1.0 - max(dot(viewDir, norm), 0.0);
                rim = pow(rim, 2.5);

                vec3 baseColor;
                if (uUseFaceTexture == 1) {
                    baseColor = texture(uFaceTexture, vFaceTexCoord).rgb;
                } else {
                    baseColor = uColor;
                }

                vec3 finalColor = baseColor * (0.45 + 0.55 * diff) + (uAccentColor * (spec * 0.35 + rim * 0.35));
                fragColor = vec4(finalColor, uAlpha);
            }
        """

        private const val VIDEO_VERTEX_SHADER = """#version 300 es
            in vec2 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.999, 1.0);
            }
        """

        private const val VIDEO_FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec2 uGazeCenter;
            uniform float uFoveaRadius;
            uniform int uFoveatedEnabled;
            uniform int uShowFoveaRing;
            uniform float uPeripheralGrid;
            out vec4 fragColor;
            void main() {
                float dist = distance(vTexCoord, uGazeCenter);
                
                // Dynamic Foveated Resolution:
                // High-resolution at eye gaze focal center, low-resolution outside in peripheral vision
                if (uFoveatedEnabled == 1 && dist > uFoveaRadius) {
                    float blurFactor = clamp((dist - uFoveaRadius) / 0.16, 0.0, 1.0);
                    float targetGrid = (uPeripheralGrid > 0.0) ? uPeripheralGrid : 32.0;
                    float pixelGrid = mix(1.0 / 640.0, 1.0 / targetGrid, blurFactor);
                    vec2 lowResUV = floor(vTexCoord / pixelGrid) * pixelGrid;
                    vec4 color = texture(uTexture, lowResUV);
                    
                    fragColor = vec4(color.rgb * (1.0 - blurFactor * 0.22), 1.0);
                } else {
                    fragColor = texture(uTexture, vTexCoord);
                }

                // Subtle holographic eye gaze fovea indicator ring
                if (uShowFoveaRing == 1) {
                    float ringDist = abs(dist - uFoveaRadius);
                    if (ringDist < 0.0035) {
                        float ringAlpha = (1.0 - ringDist / 0.0035) * 0.65;
                        fragColor = mix(fragColor, vec4(0.0, 0.9, 1.0, 1.0), ringAlpha);
                    }
                }
            }
        """
    }
}
