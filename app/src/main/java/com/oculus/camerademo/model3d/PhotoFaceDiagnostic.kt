package com.oculus.camerademo.model3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.oculus.camerademo.mirror.AvatarPhotoMaskPainter
import com.oculus.camerademo.mirror.AvatarVideoFace
import com.oculus.camerademo.ml.FaceEmotionState
import java.io.ByteArrayOutputStream

/** Explicit developer check using generated colour blocks only. No camera,
 * account avatar, wearer pose or existing face-library pixels are acquired. */
object PhotoFaceDiagnostic {
    fun pattern(): Bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).also {bitmap ->
        val canvas=Canvas(bitmap);val paint=Paint()
        listOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE).forEachIndexed {index,color ->
            paint.color=color;val x=(index%2)*256f;val y=(index/2)*256f
            canvas.drawRect(x,y,x+256f,y+256f,paint)
        }
    }
    fun run(context: Context): Int {
        val source=pattern();val bytes=ByteArrayOutputStream().also {source.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val library=PhotoFaceLibrary(context,"photo_faces_qa");var saved:PhotoFaceEntry?=null;var checks=0
        try {
            val decoded=library.decode(bytes)
            check(decoded.width==512 && decoded.height==512);checks++
            check(Color.red(decoded.getPixel(96,96))>240 && Color.blue(decoded.getPixel(96,416))>240);checks++
            saved=library.save(decoded,"Automatic synthetic photo check")
            check(library.entries().any {it.id==saved!!.id});checks++
            val loaded=PhotoFaceLibrary(context,"photo_faces_qa").load(saved!!)
            check(loaded.bitmap.getPixel(96,96)==decoded.getPixel(96,96));checks++
            val output=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            val identity=FloatArray(16).apply {this[0]=1f;this[5]=1f;this[10]=1f;this[15]=1f}
            val face=AvatarVideoFace(.25f,.25f,.75f,.75f,identity,FaceEmotionState(),0f,0f)
            AvatarPhotoMaskPainter().draw(output,face,loaded)
            check(Color.red(output.getPixel(88,88))>230 && Color.blue(output.getPixel(88,168))>230);checks++
            check(Color.alpha(output.getPixel(12,12))==0);checks++
            check(runCatching {library.decode(byteArrayOf(1,2,3))}.isFailure);checks++
            return checks
        } finally {
            saved?.let {library.remove(it)}
        }
    }
}
