package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.meta.spatial.core.Pose
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.PortraitAppearance
import com.oculus.camerademo.model3d.PortraitSurface
import com.oculus.camerademo.renderer.PortraitRasterizer

/** Solid 3D upper-body avatar shared by the immersive preview and LAN video.
 * Head, torso, arms and fingers participate in one pixel depth buffer. */
class WearerAvatarPainter {
    private val mesh = WearerAvatarMesh()
    private val rasterizer = PortraitRasterizer()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var reference: Pose? = null
    @Synchronized fun recenter() { reference = null }

    @Synchronized fun draw(snapshot: WearerSnapshot, persona: HumanPersona, hairEnabled: Boolean = true,
                           quality: AvatarVideoQuality = MirrorState.videoQuality): Bitmap {
        val size = AvatarVideoResolution.output(1920,1080,quality)
        val bitmap = Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(15,23,35))
        if (!snapshot.isFresh(SystemClock.uptimeMillis())) {
            val canvas = Canvas(bitmap)
            paint.color = Color.LTGRAY; paint.textSize = size.height / 18f
            canvas.drawText("Wearer tracking paused", size.width * .30f, size.height * .51f, paint)
            return bitmap
        }
        val head = snapshot.head!!
        if (reference == null) reference = Pose(head.position, head.orientation).removePitchAndRoll()
        val inverse = reference!!.inverse()
        fun local(joint: WearerJoint?): Pose? = joint?.let { inverse * Pose(it.position,it.orientation) }
        val appearance = PortraitAppearance.forPersona(persona)
        val detail = when (quality) {
            AvatarVideoQuality.MAXIMUM -> 16
            AvatarVideoQuality.PERFORMANCE -> 12
            else -> 14
        }
        val headPose = local(head)!!
        val geometry = mesh.build(headPose,snapshot.body.map(::local),snapshot.hands.map { it.map(::local) },
            appearance,snapshot.expression,
            gazeX = if(snapshot.hasEyes)snapshot.gazeX else 0f,
            gazeY = if(snapshot.hasEyes)snapshot.gazeY else 0f,
            hairEnabled=hairEnabled,detail=detail)
        val projected = FloatArray(geometry.colors.size*2)
        val depths = FloatArray(geometry.colors.size)
        val shaded = IntArray(geometry.colors.size)
        for (vertex in geometry.colors.indices) {
            val at = vertex*3
            projected[vertex*2] = AvatarMirrorFraming.projectX(geometry.positions[at],size.width,size.height)
            projected[vertex*2+1] = AvatarMirrorFraming.projectY(geometry.positions[at+1]-headPose.t.y,size.width,size.height)
            depths[vertex] = geometry.positions[at+2]
            shaded[vertex] = PortraitSurface.shade(geometry.colors[vertex],geometry.normals[at],geometry.normals[at+1],
                geometry.normals[at+2],geometry.surfaces[vertex],appearance.metallic)
        }
        rasterizer.begin(size.width,size.height)
        rasterizer.draw(bitmap,projected,depths,shaded,geometry.indices)
        return bitmap
    }
}
