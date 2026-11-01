package com.oculus.camerademo.mirror

enum class AvatarVideoQuality(val label: String) {
    MAXIMUM("Maximum detail"), AUTOMATIC("Automatic"), SMOOTH("Smooth motion"), PERFORMANCE("Save battery")
}

data class AvatarVideoSize(val width: Int,val height: Int) {
    val pixels get() = width.toLong()*height
}

/** Pick genuine camera pixels by the useful 16:9 crop, not a stretched output label. */
object AvatarVideoResolution {
    fun output(width: Int,height: Int,quality: AvatarVideoQuality): AvatarVideoSize {
        require(width>0 && height>0)
        val cap = when(quality) { AvatarVideoQuality.MAXIMUM -> 1920; AvatarVideoQuality.PERFORMANCE -> 960; else -> 1280 }
        // Multiples of 16x9 are even for the source's 4:2:0 chroma and Windows YUV formats.
        val blocks = minOf(width/16,height/9,cap/16).coerceAtLeast(2)
        return AvatarVideoSize(blocks*16,blocks*9)
    }
    fun choose(sizes: List<AvatarVideoSize>,quality: AvatarVideoQuality): AvatarVideoSize? {
        val candidates = sizes.filter { it.width in 320..2560 && it.height in 240..1600 && it.pixels<=4096000 }
        return candidates.maxWithOrNull(compareBy<AvatarVideoSize> { output(it.width,it.height,quality).pixels }
            .thenBy { -it.pixels }) // Prefer less conversion work when both deliver the same useful detail.
    }

    /** Sample models consume at most 640 × 360, independently of SDK/video output
     * quality. Choose a real stream that supplies those useful pixels with the
     * least YUV conversion work; never request an unsupported synthetic size. */
    fun chooseInference(sizes: List<AvatarVideoSize>): AvatarVideoSize? {
        val candidates = sizes.filter { it.width in 320..2560 && it.height in 240..1600 && it.pixels<=4096000 }
        return candidates.maxWithOrNull(compareBy<AvatarVideoSize> {
            val blocks = minOf(it.width/16,it.height/9,640/16)
            blocks.toLong()*blocks*16*9
        }.thenBy { -it.pixels })
    }
}

/** Sample observations use the latest camera input rather than the LAN encoder's
 * quality/load cadence. The one-frame inference queue remains bounded; heat still
 * slows capture without making an old observation appear newly acquired. */
internal object AvatarSampleCapturePolicy {
    fun intervalMs(thermalStatus: Int): Long = when {
        thermalStatus >= 3 -> 100L
        thermalStatus >= 2 -> 66L
        else -> 33L
    }
}

/** Bounded scheduling with hysteresis; never invents/interpolates camera images. */
class AvatarVideoBudget {
    @Volatile var quality = AvatarVideoQuality.AUTOMATIC
    @Volatile private var processingMs = 0f
    @Volatile private var networkMs = 0f
    private var slow = false
    @Volatile var thermalStatus = 0
    fun processing(milliseconds: Long) { processingMs = if (processingMs == 0f) milliseconds.toFloat() else .85f*processingMs+.15f*milliseconds }
    fun network(milliseconds: Long) { networkMs = .8f*networkMs+.2f*milliseconds }
    val intervalMs: Long get() {
        // Maximum preserves resolution/JPEG detail. Reduce cadence under load instead.
        if (quality == AvatarVideoQuality.MAXIMUM) {
            val cost = maxOf(processingMs,networkMs)
            return when { thermalStatus>=3 || cost>100f -> 100L; thermalStatus>=2 || cost>32f -> 66L; else -> 33L }
        }
        if (thermalStatus >= 2 || quality == AvatarVideoQuality.PERFORMANCE) return 66L
        if (quality == AvatarVideoQuality.SMOOTH) return 33L
        if (!slow && (processingMs > 32f || networkMs > 100f)) slow = true
        if (slow && processingMs < 23f && networkMs < 65f) slow = false
        return if (slow) 66L else 33L
    }
    val jpegQuality get() = if (quality == AvatarVideoQuality.MAXIMUM) 97
        else if (thermalStatus >= 2 || networkMs > 100f || quality == AvatarVideoQuality.PERFORMANCE) 82 else 92
}
