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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A small centre-view aiming dot for head-direction selection outside the menu. */
class HeadDirectionReticle(private val scene: Scene) : AutoCloseable {
    private var texture: SceneTexture? = null
    private var material: SceneMaterial? = null
    private var mesh: SceneMesh? = null
    private var triangle: TriangleMesh? = null
    private var object3D: SceneObject? = null

    fun render(viewer: Pose?, visible: Boolean, locked: Boolean) {
        if (!visible || viewer == null) { object3D?.setIsVisible(false); return }
        if (object3D == null) {
            texture = SceneTexture(Color.valueOf(Color.WHITE))
            material = SceneMaterial(texture!!, AlphaMode.TRANSLUCENT).apply {
                setSidedness(MaterialSidedness.DOUBLE_SIDED)
            }
            val positions = FloatArray(13 * 3)
            for (i in 0 until 12) {
                positions[(i + 1) * 3] = cos(2.0 * PI * i / 12).toFloat() * 0.003f
                positions[(i + 1) * 3 + 1] = sin(2.0 * PI * i / 12).toFloat() * 0.003f
            }
            val indices = IntArray(36) { index -> when (index % 3) {
                0 -> 0; 1 -> index / 3 + 1; else -> (index / 3 + 1) % 12 + 1
            } }
            triangle = TriangleMesh(13, 36, intArrayOf(0, 36), arrayOf(material!!)).apply {
                updateGeometry(0, positions, FloatArray(39) { if (it % 3 == 2) -1f else 0f },
                    FloatArray(26), IntArray(13) { Color.WHITE })
                updatePrimitives(0, indices)
            }
            mesh = SceneMesh.fromTriangleMesh(triangle!!, false)
            object3D = SceneObject(scene, mesh!!, "head-direction-cursor")
        }
        material!!.setAlbedoColor(Color.valueOf(if (locked) Color.rgb(0, 230, 118) else Color.WHITE))
        object3D!!.apply {
            setPosition(viewer * Vector3(0f, 0f, 0.65f))
            setRotationQuat(viewer.q)
            setIsVisible(true)
        }
    }

    override fun close() {
        object3D?.destroy()
        mesh?.destroy()
        triangle?.destroy()
        material?.destroy()
        texture?.destroy()
        object3D = null
        mesh = null
        triangle = null
        material = null
        texture = null
    }
}
