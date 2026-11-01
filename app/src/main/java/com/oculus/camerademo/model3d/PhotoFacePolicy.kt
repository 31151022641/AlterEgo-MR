package com.oculus.camerademo.model3d

import java.io.InputStream
import java.io.ByteArrayOutputStream

/** Bound compressed and decoded inputs before allocating or trusting metadata. */
object PhotoFacePolicy {
    const val MAX_BYTES = 16*1024*1024
    const val MAX_PIXELS = 32_000_000L
    const val SIZE = 512
    fun supported(mime: String?) = mime == "image/png" || mime == "image/jpeg"
    fun dimensions(width: Int,height: Int) = width > 0 && height > 0 &&
        width <= 16384 && height <= 16384 && width.toLong()*height <= MAX_PIXELS
    fun validId(id: String) = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)
    fun name(value: String) = value.filter { !it.isISOControl() }.trim().take(40).ifEmpty { "My photo face" }
    fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            if (count == 0) {
                val one=input.read();if(one<0)break
                require(out.size()<MAX_BYTES){"Choose a PNG/JPG image smaller than 16 MB."}
                out.write(one);continue
            }
            require(out.size().toLong()+count <= MAX_BYTES) { "Choose a PNG/JPG image smaller than 16 MB." }
            out.write(buffer,0,count)
        }
        require(out.size() > 0) { "The selected file is empty." }
        return out.toByteArray()
    }
}
