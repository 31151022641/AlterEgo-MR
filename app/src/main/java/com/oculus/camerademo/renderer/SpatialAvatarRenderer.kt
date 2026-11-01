package com.oculus.camerademo.renderer

import android.graphics.Color
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.TriangleMesh
import com.oculus.camerademo.ml.TrackedHeadObject
import com.oculus.camerademo.ml.FaceEmotionState
import com.oculus.camerademo.model3d.PortraitAppearance
import com.oculus.camerademo.model3d.PortraitMesh
import com.oculus.camerademo.model3d.PortraitStyle
import com.oculus.camerademo.model3d.PortraitSurface
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.PhotoFace

/** Native stereo scene geometry. Each persistent track owns its portrait buffers. */
class SpatialAvatarRenderer(private val scene: Scene) : AutoCloseable {
    private class Avatar(val scene: Scene, val id: Int,val photo: PhotoFace?=null) {
        val portrait = PortraitMesh()
        val stability = FacePositionStability()
        val texture = photo?.let {SceneTexture(it.bitmap)} ?: SceneTexture(Color.valueOf(Color.WHITE))
        val materials = Array(PortraitSurface.COUNT) { SceneMaterial(texture, AlphaMode.TRANSLUCENT).apply {
            setSidedness(MaterialSidedness.DOUBLE_SIDED)
            setRoughness(0.65f)
        } }
        var triangle: TriangleMesh? = null
        var mesh: SceneMesh? = null
        var object3D: SceneObject? = null
        var lastGeometryTime = -1L
        var lastPersona: HumanPersona? = null
        var lastHairEnabled = false
        var lastColor = 0
        var lastDetail: AvatarDetail? = null

        fun upload(geometry: Geometry) {
            val current = triangle
            if (current == null || currentVertexCount != geometry.colors.size ||
                currentIndices.size != geometry.indices.size || !currentRanges.contentEquals(geometry.materialRanges) ||
                !currentMaterialIds.contentEquals(geometry.materialIds)) {
                // Detail, sculpted hair, and beard styling can all change topology.
                object3D?.destroy()
                mesh?.destroy()
                current?.destroy()
                triangle = TriangleMesh(geometry.colors.size, geometry.indices.size,
                    geometry.materialRanges, Array(geometry.materialIds.size) { materials[geometry.materialIds[it]] }).apply {
                    updateGeometry(0, geometry.positions, geometry.normals, geometry.uvs, geometry.colors)
                    updatePrimitives(0, geometry.indices)
                }
                mesh = SceneMesh.fromTriangleMesh(triangle!!, false)
                object3D = SceneObject(scene, mesh!!, "face-track-$id")
                currentVertexCount = geometry.colors.size
                currentIndices = geometry.indices.copyOf()
                currentRanges = geometry.materialRanges.copyOf()
                currentMaterialIds = geometry.materialIds.copyOf()
            } else {
                current.updateGeometry(0, geometry.positions, geometry.normals, geometry.uvs, geometry.colors)
                if (!currentIndices.contentEquals(geometry.indices)) {
                    current.updatePrimitives(0, geometry.indices)
                    currentIndices = geometry.indices.copyOf()
                }
                mesh!!.updateWithTriangleMesh(current, false)
            }
        }

        var currentVertexCount = 0
        var currentIndices = IntArray(0)
        var currentRanges = IntArray(0)
        var currentMaterialIds = IntArray(0)
        fun destroy() {
            object3D?.destroy()
            mesh?.destroy()
            triangle?.destroy()
            materials.forEach { it.destroy() }
            texture.destroy()
        }
    }

    private val avatars = mutableMapOf<Int, Avatar>()

    /** Call on the scene thread, with positions already transformed at the image capture time. */
    fun render(
        heads: List<Pair<TrackedHeadObject, Pose>>,
        focusedId: Int?,
        partyMode: Boolean,
        diversePersonas: Boolean,
        persona: HumanPersona,
        alpha: Float,
        scale: Float,
        hairEnabled: Boolean,
        captureTimeMs: Long,
        primaryColor: Int? = null,
        visibleIds: Set<Int>,
        quality: ResolutionQuality = ResolutionQuality.BALANCED,
        prioritizeGaze: Boolean = true,
        validEyeGaze: Boolean = false,
        photo: PhotoFace?=null
    ) {
        val liveIds = heads.map { it.first.id }.toSet()
        avatars.keys.filter { it !in liveIds }.forEach { avatars.remove(it)?.destroy() }
        for ((head, pose) in heads.take(4)) {
            val visible = head.id in visibleIds && alpha > 0f && (partyMode || head.id == focusedId)
            // Do not create invisible geometry just because there is a detection.
            if (!visible) {
                avatars[head.id]?.object3D?.setIsVisible(false)
                continue
            }
            // A new immutable photo owns a new texture; destroy the old GPU resources.
            val effectivePhoto=photo?.takeIf { !partyMode || !diversePersonas || head.id==focusedId }
            if(avatars[head.id]?.photo?.entry?.id!=effectivePhoto?.entry?.id)avatars.remove(head.id)?.destroy()
            val avatar = avatars.getOrPut(head.id) { Avatar(scene, head.id,effectivePhoto) }
            val effectivePersona = if (partyMode && diversePersonas) {
                HumanPersona.available[head.id.mod(HumanPersona.available.size)]
            } else persona
            val skinColor = if (effectivePersona == persona) primaryColor ?: persona.primaryColor
                else effectivePersona.primaryColor
            val detail = AvatarDetailPolicy.detail(quality, prioritizeGaze, validEyeGaze, head.id, focusedId)
            val expressionDue = captureTimeMs != avatar.lastGeometryTime &&
                captureTimeMs - avatar.lastGeometryTime >= detail.expressionIntervalMs
            if (avatar.triangle == null || expressionDue || avatar.lastDetail != detail ||
                avatar.lastPersona != effectivePersona || avatar.lastHairEnabled != hairEnabled || avatar.lastColor != skinColor) {
                val appearance = PortraitAppearance.forPersona(effectivePersona, skinColor)
                avatar.materials.forEachIndexed { surface, material ->
                    val roughness = when {
                        effectivePhoto != null -> .65f
                        appearance.metallic -> if (surface == PortraitSurface.EYE) .17f else .24f
                        else -> when (surface) {
                            PortraitSurface.EYE -> .12f
                            PortraitSurface.HAIR -> .36f
                            PortraitSurface.HORN -> .25f
                            PortraitSurface.MOUTH -> .42f
                            else -> .46f
                        }
                    }
                    val metal = when {
                        effectivePhoto != null -> 0f
                        appearance.metallic -> .78f
                        appearance.variant == "cyber" && surface == PortraitSurface.HORN -> .32f
                        else -> 0f
                    }
                    material.setRoughnessMetallicness(roughness, metal)
                    material.setUnlit(effectivePhoto == null && surface == PortraitSurface.ACCENT)
                }
                avatar.upload(if (effectivePhoto != null) photoGeometry()
                    else buildGeometry(avatar.portrait, appearance, head.emotion, hairEnabled, detail))
                avatar.lastGeometryTime = captureTimeMs
                avatar.lastPersona = effectivePersona
                avatar.lastHairEnabled = hairEnabled
                avatar.lastColor = skinColor
                avatar.lastDetail = detail
            }
            avatar.materials.forEach { it.setAlbedoColor(Color.valueOf(1f, 1f, 1f, alpha.coerceIn(0f, 1f))) }
            avatar.object3D?.apply {
                val stable = avatar.stability.update(FacePositionStability.Position(pose.t.x,pose.t.y,pose.t.z),captureTimeMs)
                setPosition(Vector3(stable.x,stable.y,stable.z))
                setRotationQuat(pose.q)
                setScale(Vector3(scale.coerceIn(0.6f, 2.5f)))
                setIsVisible(true)
            }
        }
    }

    fun hideAll() = avatars.values.forEach { it.object3D?.setIsVisible(false) }
    override fun close() {
        avatars.values.forEach { it.destroy() }
        avatars.clear()
    }

    private data class Geometry(val positions: FloatArray, val normals: FloatArray,
        val uvs: FloatArray, val colors: IntArray, val indices: IntArray,
        val materialRanges: IntArray = intArrayOf(0, indices.size), val materialIds: IntArray = intArrayOf(PortraitSurface.SKIN))

    private fun photoGeometry()=Geometry(
        floatArrayOf(-.074f,.087f,-.034f, .074f,.087f,-.034f,
            -.074f,-.090f,-.034f, .074f,-.090f,-.034f),
        floatArrayOf(0f,0f,-1f,0f,0f,-1f,0f,0f,-1f,0f,0f,-1f),
        floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f),IntArray(4){Color.WHITE},
        intArrayOf(0,2,1,1,2,3))

    private fun buildGeometry(portrait: PortraitMesh, style: PortraitStyle,
        emotion: FaceEmotionState, hairEnabled: Boolean, detail: AvatarDetail): Geometry {
        // Sunglasses is a standalone portrait; all other presets keep their
        // own original geometry without an automatically attached accessory.
        val source = portrait.build(style, emotion, hairEnabled = hairEnabled, detail = detail.skullColumns)
        val positions = source.positions.copyOf()
        val normals = source.normals.copyOf()
        val sections = PortraitSurface.sections(source.indices, source.surfaces)
        val indices = sections.indices
        // Reflect the camera-facing portrait into the Spatial SDK's face orientation,
        // while retaining the existing calibrated scene placement and scale.
        for (i in source.colors.indices) {
            val at = i * 3
            positions[at] *= .61748f
            positions[at + 1] = positions[at + 1] * .56984f - .00286f
            positions[at + 2] *= -.68f
            val nx = normals[at] / .61748f
            val ny = normals[at + 1] / .56984f
            val nz = -normals[at + 2] / .68f
            val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.0001f)
            normals[at] = nx / length
            normals[at + 1] = ny / length
            normals[at + 2] = nz / length
        }
        // A reflection changes handedness, so reverse each triangle's winding.
        for (i in indices.indices step 3) {
            val b = indices[i + 1]
            indices[i + 1] = indices[i + 2]
            indices[i + 2] = b
        }
        return Geometry(positions, normals, FloatArray(source.colors.size * 2), source.colors, indices, sections.ranges, sections.materialIds)
    }
}
