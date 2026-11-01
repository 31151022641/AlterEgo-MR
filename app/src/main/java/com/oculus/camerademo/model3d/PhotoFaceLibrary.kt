package com.oculus.camerademo.model3d

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors

data class PhotoFaceEntry(val id: String,val name: String)
/** Shared immutable software pixels; readers must never recycle the bitmap. */
data class PhotoFace(val entry: PhotoFaceEntry,val bitmap: Bitmap)

class PhotoFaceLibrary(context: Context,namespace: String="photo_faces") {
    init {require(namespace=="photo_faces" || namespace=="photo_faces_qa")}
    private val app = context.applicationContext
    private val directory = File(app.filesDir,namespace)
    private val prefs = app.getSharedPreferences(namespace,Context.MODE_PRIVATE)
    private fun file(id: String): File { require(PhotoFacePolicy.validId(id)); return File(directory,"$id.png") }

    fun entries(): List<PhotoFaceEntry> = synchronized(LOCK) {
        val array = runCatching { JSONArray(prefs.getString("library","[]")) }.getOrDefault(JSONArray())
        (0 until minOf(array.length(),128)).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val id = value.optString("id")
            if (!PhotoFacePolicy.validId(id) || !file(id).isFile) null
            else PhotoFaceEntry(id,PhotoFacePolicy.name(value.optString("name")))
        }.distinctBy { it.id }
    }
    fun prepare(uri: Uri): Bitmap = app.contentResolver.openInputStream(uri)?.use {
        decode(PhotoFacePolicy.read(it))
    } ?: error("The selected image could not be opened.")

    fun decode(bytes: ByteArray): Bitmap {
        require(bytes.isNotEmpty() && bytes.size <= PhotoFacePolicy.MAX_BYTES) { "Choose a PNG/JPG smaller than 16 MB." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(PhotoFacePolicy.supported(bounds.outMimeType)) { "Choose a PNG or JPG image." }
        require(PhotoFacePolicy.dimensions(bounds.outWidth,bounds.outHeight)) { "This image is too large or invalid." }
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(PhotoFacePolicy.dimensions(info.size.width,info.size.height)) { "This image is too large or invalid." }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val ratio = minOf(1f,1024f/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),
                (info.size.height*ratio).toInt().coerceAtLeast(1))
            decoder.setOnPartialImageListener { false }
        }
        val side = minOf(decoded.width,decoded.height)
        val crop = Bitmap.createBitmap(decoded,(decoded.width-side)/2,(decoded.height-side)/2,side,side)
        val normalized = Bitmap.createScaledBitmap(crop,PhotoFacePolicy.SIZE,PhotoFacePolicy.SIZE,true)
        // This decoder owns these temporaries. Published normalized pixels stay immutable.
        if (crop !== normalized && crop !== decoded) crop.recycle()
        if (decoded !== normalized) decoded.recycle()
        return normalized
    }
    fun load(entry: PhotoFaceEntry): PhotoFace {
        val bytes = file(entry.id).inputStream().use { PhotoFacePolicy.read(it) }
        return PhotoFace(entry,decode(bytes))
    }
    fun thumbnail(entry: PhotoFaceEntry): Bitmap? = BitmapFactory.decodeFile(file(entry.id).absolutePath,
        BitmapFactory.Options().apply { inSampleSize=4; inPreferredConfig=Bitmap.Config.ARGB_8888 })
    fun save(bitmap: Bitmap,name: String): PhotoFaceEntry = synchronized(LOCK) {
        val current = entries()
        require(current.size < 128) { "The face library is full." }
        val entry = PhotoFaceEntry(UUID.randomUUID().toString(),PhotoFacePolicy.name(name))
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the face library." }
        val destination = file(entry.id)
        try {
            destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val index = JSONArray().apply { (current+entry).forEach {
                put(JSONObject().put("id",it.id).put("name",it.name))
            } }
            check(prefs.edit().putString("library",index.toString()).commit()) { "Could not save the face library." }
            entry
        } catch (error: Exception) { destination.delete(); throw error }
    }
    fun remove(entry: PhotoFaceEntry) = synchronized(LOCK) {
        val index = JSONArray().apply { entries().filter { it.id != entry.id }.forEach {
            put(JSONObject().put("id",it.id).put("name",it.name))
        } }
        check(prefs.edit().putString("library",index.toString()).commit())
        file(entry.id).delete(); Unit
    }
    companion object { private val LOCK = Any() }
}

/** I/O stays off the UI thread. A failed/cancelled import never changes selection. */
class PhotoFaceImport(private val activity: Activity,val library: PhotoFaceLibrary=PhotoFaceLibrary(activity)) : AutoCloseable {
    var entries by mutableStateOf<List<PhotoFaceEntry>>(emptyList()); private set
    var draft by mutableStateOf<Bitmap?>(null); private set
    var draftName by mutableStateOf("My photo face")
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var request = 0L

    fun refresh() { if (closed) return; worker.execute { val result=runCatching { library.entries() }
        main.post { if (!closed) entries=result.getOrDefault(emptyList()) } } }
    internal fun syntheticPreview(bitmap: Bitmap) {draft=bitmap;draftName="Automatic test mask";message="Synthetic image preview · Not wearer tracking"}
    fun picked(uri: Uri?) {
        if (uri == null || closed) return
        val token=++request; busy=true; message="Opening image…"
        worker.execute {
            val result=runCatching { library.prepare(uri) }
            main.post { if (!closed && token==request) {
                busy=false; result.onSuccess { draft=it; draftName="My photo face"; message="Preview the square mask before saving." }
                    .onFailure { message=it.message ?: "The image could not be opened." }
            } }
        }
    }
    fun unavailable() { message="The file picker could not open. Try again from the regular My mirror window." }
    fun save(onSaved: (PhotoFaceEntry)->Unit) {
        val pixels=draft ?: return; if (busy || closed) return
        val token=++request; val name=draftName; busy=true
        worker.execute { val result=runCatching { library.save(pixels,name) }
            main.post { if (!closed && token==request) {
                busy=false; result.onSuccess { entry -> draft=null; message="Saved to your face library."; refresh(); onSaved(entry) }
                    .onFailure { message=it.message ?: "The face could not be saved." }
            } }
        }
    }
    fun select(entry: PhotoFaceEntry,onLoaded: (PhotoFace)->Unit) {
        if (busy || closed) return; val token=++request; busy=true
        worker.execute { val result=runCatching { library.load(entry) }
            main.post { if (!closed && token==request) {
                busy=false; result.onSuccess { message=""; onLoaded(it) }
                    .onFailure { message="The saved photo face could not be loaded. Choose another face." }
            } }
        }
    }
    fun discard() { request++; busy=false; draft=null; message="" }
    fun cancelSelection() { request++; busy=false }
    fun restore(id: String,onLoaded: (PhotoFace)->Unit) {
        if(id.isBlank() || closed)return
        val token=++request;busy=true
        worker.execute {val result=runCatching {library.entries().firstOrNull {it.id==id}?.let {library.load(it)}}
            main.post {if(!closed && token==request){busy=false;result.getOrNull()?.let(onLoaded)}}}
    }
    override fun close() { closed=true; request++; worker.shutdownNow() }
}
