package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer

/** Synthetic-only check: native full-resolution conversion, odd crop and plane padding. */
object AvatarPixelDiagnostics {
    fun run() {
        val width=1600; val height=1200; val yRow=width+16; val uvRow=width+16
        val y=ByteBuffer.allocateDirect(yRow*height)
        val u=ByteBuffer.allocateDirect(uvRow*height/2); val v=ByteBuffer.allocateDirect(uvRow*height/2)
        for(row in 0 until height) for(col in 0 until width) y.put(row*yRow+col,(if(col%2==0) 16 else 235).toByte())
        for(row in 0 until height/2) for(col in 0 until width/2) {
            u.put(row*uvRow+col*2,128.toByte()); v.put(row*uvRow+col*2,128.toByte())
        }
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val start=SystemClock.elapsedRealtime()
        check(NativeAvatarPixels.convert(output,y,u,v,yRow,1,uvRow,2,uvRow,2,0,0,0))
        val elapsed=SystemClock.elapsedRealtime()-start
        for(col in 0 until width) check((output.getPixel(col,0) and 255)==if(col%2==0) 0 else 255)
        val crop=Bitmap.createBitmap(31,17,Bitmap.Config.ARGB_8888)
        check(NativeAvatarPixels.convert(crop,y,u,v,yRow,1,uvRow,2,uvRow,2,1,1,0))
        check((crop.getPixel(0,0) and 255)==255 && (crop.getPixel(1,0) and 255)==0)
        y.put(0,8.toByte()); y.put(1,240.toByte())
        for(mode in 1..3 step 2) {
            check(NativeAvatarPixels.convert(crop,y,u,v,yRow,1,uvRow,2,uvRow,2,0,0,mode))
            check((crop.getPixel(0,0) and 255)==8 && (crop.getPixel(1,0) and 255)==240)
        }
        check(!NativeAvatarPixels.convert(crop,ByteBuffer.allocateDirect(1),u,v,yRow,1,uvRow,2,uvRow,2,0,0,0))
        output.recycle(); crop.recycle()
        Log.i("AlterEgoVideoQA","PASS: native 1600x1200 retains every alternating column; odd crop/interleaved padding/full-range/short-plane guards; conversionMs=$elapsed")
    }
}
