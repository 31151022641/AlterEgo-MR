package com.oculus.camerademo.mirror

import com.meta.spatial.core.Pose
import com.oculus.camerademo.ml.FaceEmotionState
import com.oculus.camerademo.model3d.PortraitGeometry
import com.oculus.camerademo.model3d.PortraitMesh
import com.oculus.camerademo.model3d.PortraitStyle
import com.oculus.camerademo.model3d.PortraitSurface
import com.oculus.camerademo.model3d.UpperBodyMesh

/** Production mesh shared by the headset mirror, video output and JVM previews.
 * Inputs are genuine joints in the current mirror reference. Missing channels
 * create neutral visual anatomy in UpperBodyMesh, not fabricated tracking data. */
class WearerAvatarMesh {
    private val portrait = PortraitMesh()
    private val skinTones = mutableMapOf<Pair<PortraitStyle,Int>,Int>()

    fun build(head: Pose, body: List<Pose?>, hands: List<List<Pose?>>, style: PortraitStyle,
              emotion: FaceEmotionState = FaceEmotionState(), gazeX: Float = 0f, gazeY: Float = 0f,
              hairEnabled: Boolean = true, detail: Int = 14): PortraitGeometry {
        val q = head.q
        val norm = q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w
        if (!head.t.x.isFinite() || !head.t.y.isFinite() || !head.t.z.isFinite() || !norm.isFinite() ||
            norm !in .5f..1.5f) return PortraitGeometry(FloatArray(0),FloatArray(0),IntArray(0),IntArray(0))
        // Rotate the large authored mesh with primitive coefficients, without
        // allocating two Vector3 objects for every one of its ~30–40k vertices.
        val s = 2f/norm
        val xx=q.x*q.x*s; val yy=q.y*q.y*s; val zz=q.z*q.z*s
        val xy=q.x*q.y*s; val xz=q.x*q.z*s; val yz=q.y*q.z*s
        val wx=q.w*q.x*s; val wy=q.w*q.y*s; val wz=q.w*q.z*s
        val m00=1f-yy-zz; val m01=xy-wz; val m02=xz+wy
        val m10=xy+wz; val m11=1f-xx-zz; val m12=yz-wx
        val m20=xz-wy; val m21=yz+wx; val m22=1f-xx-yy
        val headMesh = portrait.build(style, emotion, gazeX, gazeY, hairEnabled, detail)
        val skin = if (style.character == "human") skinTones.getOrPut(style to headMesh.colors.size) {
            var r=0L; var g=0L; var b=0L; var count=0
            for (i in headMesh.colors.indices) if (headMesh.surfaces[i]==PortraitSurface.SKIN) {
                val color=headMesh.colors[i]
                r+=(color ushr 16) and 255; g+=(color ushr 8) and 255; b+=color and 255; count++
            }
            if(count==0) style.skin else (0xff shl 24) or ((r/count).toInt() shl 16) or
                ((g/count).toInt() shl 8) or (b/count).toInt()
        } else style.skin
        val bodyMesh = UpperBodyMesh.build(head, body, hands, skin, detail = (detail/2+4).coerceIn(8, 14))
        val bodyVertices = bodyMesh.colors.size
        val headVertices = headMesh.colors.size
        val positions = FloatArray((bodyVertices + headVertices) * 3)
        val normals = FloatArray(positions.size)
        bodyMesh.positions.copyInto(positions); bodyMesh.normals.copyInto(normals)
        for (vertex in 0 until headVertices) {
            val at = vertex * 3
            // The human assets already have ~24cm facial height. Keep their
            // uniform metre-scale form; hair/horns must not resize the face.
            val x=headMesh.positions[at]*AvatarMirrorFraming.HEAD_SCALE
            val y=headMesh.positions[at+1]*AvatarMirrorFraming.HEAD_SCALE
            val z=headMesh.positions[at+2]*AvatarMirrorFraming.HEAD_SCALE
            val nx=headMesh.normals[at]; val ny=headMesh.normals[at+1]; val nz=headMesh.normals[at+2]
            val output = (bodyVertices + vertex) * 3
            positions[output]=m00*x+m01*y+m02*z+head.t.x
            positions[output+1]=m10*x+m11*y+m12*z+head.t.y
            positions[output+2]=m20*x+m21*y+m22*z+head.t.z
            normals[output]=m00*nx+m01*ny+m02*nz
            normals[output+1]=m10*nx+m11*ny+m12*nz
            normals[output+2]=m20*nx+m21*ny+m22*nz
        }
        val indices = IntArray(bodyMesh.indices.size + headMesh.indices.size)
        bodyMesh.indices.copyInto(indices)
        for (i in headMesh.indices.indices) indices[bodyMesh.indices.size+i] = bodyVertices + headMesh.indices[i]
        return PortraitGeometry(positions, normals, bodyMesh.colors + headMesh.colors, indices,
            bodyMesh.surfaces + headMesh.surfaces)
    }
}
