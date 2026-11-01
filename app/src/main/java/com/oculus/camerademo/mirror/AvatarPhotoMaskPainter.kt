package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.oculus.camerademo.model3d.PhotoFace

/** A photo is explicitly a static mask. Head pose comes from the existing face
 * estimate; no photo pixels are relabelled as face/eye sensor observations. */
class AvatarPhotoMaskPainter {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun draw(bitmap: Bitmap,face: AvatarVideoFace,photo: PhotoFace) {
        if(!face.usable)return
        val centerX=(face.left+face.right)*.5f*bitmap.width
        val centerY=(face.top+face.bottom)*.5f*bitmap.height
        val width=(face.right-face.left)*bitmap.width
        val height=(face.bottom-face.top)*bitmap.height
        val corners=listOf(-1f to 1f,1f to 1f,-1f to -1f,1f to -1f)
        val points=FloatArray(8)
        corners.forEachIndexed {index,(x,y)->
            val rotated=AvatarVideoRotation.rotate(face.rotation,x,y,0f)
            points[index*2]=centerX+rotated[0]*width*.5f
            points[index*2+1]=centerY-rotated[1]*height*.5f
        }
        if(points.any { !it.isFinite() })return
        Canvas(bitmap).drawBitmapMesh(photo.bitmap,1,1,points,0,null,0,paint)
    }
}
