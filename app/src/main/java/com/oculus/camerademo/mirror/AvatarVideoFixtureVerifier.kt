package com.oculus.camerademo.mirror

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.oculus.camerademo.model3d.HumanPersona
import java.io.File
import java.util.concurrent.Executors

/** Developer-only test of public SDK sample pixels, never camera or wearer data. */
class AvatarVideoFixtureVerifier(private val context: Context) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private var tracker: AvatarVideoFaceTracker? = null
    @Volatile private var closed = false
    fun offer(bitmap: Bitmap, name: String) {
        if (closed) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888,true)
        worker.execute {
            try {
                if (tracker == null) tracker = AvatarVideoFaceTracker(context)
                val face = tracker!!.estimate(copy,SystemClock.uptimeMillis())
                if (face != null) AvatarVideoFacePainter().draw(copy,face,HumanPersona.GOLDEN_SCULPTURE)
                val output = File(context.getExternalFilesDir(null),"video-$name")
                output.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG,100,it) }
                Log.i("AlterEgoAvatarVideo","Public sample $name: faceDetected=${face != null}")
            } catch (error: Exception) { Log.e("AlterEgoAvatarVideo","Public sample video test failed: ${error.message}") }
            finally { copy.recycle() }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        worker.execute { tracker?.close(); tracker = null }
        worker.shutdown()
    }
}
