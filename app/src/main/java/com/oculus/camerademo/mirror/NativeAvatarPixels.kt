package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Full-resolution conversion on the camera worker; buffers never outlive Image.close(). */
internal object NativeAvatarPixels {
    init { System.loadLibrary("alterego_eye_gaze") }
    external fun convert(destination: Bitmap,y: ByteBuffer,u: ByteBuffer,v: ByteBuffer,
        yRow: Int,yPixel: Int,uRow: Int,uPixel: Int,vRow: Int,vPixel: Int,left: Int,top: Int,colorMode: Int): Boolean
}
