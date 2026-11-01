package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Artist-authored neutral geometry and sampled facial-rig poses, shared by every renderer.
 * AEPH0001 uses big-endian arrays inside gzip. Each vertex's material byte stores
 * surface 0..5 in the lower seven bits; bit 0x80 marks removable hairstyle geometry.
 * Brows, lashes and beards retain their material finish without that removal flag.
 */
object AuthoredPortraitStore {
    private const val MAGIC = "AEPH0001"
    private const val MAX_VERTICES = 200_000
    private const val MAX_INDICES = 1_200_000
    private const val MAX_MORPHS = 96
    private const val MAX_MORPH_ENTRIES = 2_000_000
    private const val MAX_BYTES = 128L * 1024 * 1024
    private val names = listOf(
        "eyeBlinkLeft", "eyeBlinkRight", "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight",
        "jawOpen", "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
        "mouthPucker", "mouthFunnel", "browInnerUp", "browDownLeft", "browDownRight",
        "browOuterUpLeft", "browOuterUpRight", "cheekPuff", "cheekSquintLeft", "cheekSquintRight",
        "gazeXPositive", "gazeXNegative", "gazeYPositive", "gazeYNegative")

    private data class Morph(val channels: IntArray, val vertices: IntArray, val deltas: FloatArray)
    private data class Model(val positions: FloatArray, val normals: FloatArray, val colors: IntArray,
        val surfaces: IntArray, val indices: IntArray, val bareIndices: IntArray, val morphs: List<Morph>)
    private class Source(val open: (String) -> InputStream) {
        private val models = mutableMapOf<String, Model?>()
        @Synchronized fun get(path: String): Model? {
            if (!models.containsKey(path)) {
                models[path] = try { open(path).use { decode(it) } }
                    catch (_: FileNotFoundException) { null }
            }
            return models[path]
        }
    }
    @Volatile private var source: Source? = null

    /** The opener must outlive activities; Android callers provide applicationContext.assets. */
    @JvmStatic fun installLoader(open: (String) -> InputStream) { source = Source(open) }

    /** JVM preview tools load the identical packaged assets without Android classes. */
    @JvmStatic fun installDirectory(directory: String) {
        val root = File(directory)
        installLoader { path -> File(root, path).inputStream() }
    }

    /** Isolates codec fixtures from the procedural-only unit tests. */
    @JvmStatic fun clear() { source = null }

    internal fun build(style: PortraitStyle, emotion: FaceEmotionState, gazeX: Float, gazeY: Float,
        hairEnabled: Boolean): PortraitGeometry? {
        val current = source ?: return null
        val snow = style.beard || style.variant in setOf("bearded", "gold", "cyber")
        val model = current.get(if (snow) "portraits/snow.aeph" else "portraits/rain.aeph") ?: return null
        val positions = model.positions.copyOf()
        val normals = model.normals.copyOf()
        val coefficients = coefficients(emotion, gazeX, gazeY)
        for (morph in model.morphs) {
            var weight = 1f
            for (channel in morph.channels) weight *= coefficients[channel]
            if (weight == 0f) continue
            for (entry in morph.vertices.indices) {
                val at = morph.vertices[entry] * 3
                val delta = entry * 6
                for (axis in 0..2) {
                    positions[at + axis] += morph.deltas[delta + axis] * weight
                    normals[at + axis] += morph.deltas[delta + 3 + axis] * weight
                }
            }
        }
        for (at in normals.indices step 3) {
            val length = sqrt(normals[at] * normals[at] + normals[at + 1] * normals[at + 1] + normals[at + 2] * normals[at + 2])
            if (length.isFinite() && length > 1e-8f) for (axis in 0..2) normals[at + axis] /= length
            else for (axis in 0..2) normals[at + axis] = model.normals[at + axis]
        }
        val colors = model.colors.copyOf()
        val reference = if (snow) 0xffdaa884.toInt() else 0xffe1af91.toInt()
        for (vertex in colors.indices) {
            val surface = model.surfaces[vertex]
            colors[vertex] = when (style.variant) {
                "gold" -> tint(colors[vertex], when (surface) {
                    PortraitSurface.HAIR -> mix(style.hair, style.skin, .60f)
                    PortraitSurface.MOUTH -> style.lips
                    PortraitSurface.EYE -> mix(style.skin, 0xffffefcf.toInt(), .40f)
                    else -> style.skin
                })
                "cyber", "holo" -> tint(colors[vertex], when (surface) {
                    PortraitSurface.HAIR -> style.hair
                    PortraitSurface.MOUTH -> style.lips
                    PortraitSurface.EYE, PortraitSurface.ACCENT -> if (style.variant == "cyber") 0xff65e9ee.toInt() else 0xffaffff0.toInt()
                    PortraitSurface.HORN -> if (style.variant == "cyber") 0xffb8d0db.toInt() else style.skin
                    else -> style.skin
                })
                else -> if (style.skin != reference && (surface == PortraitSurface.SKIN || surface == PortraitSurface.MOUTH))
                    ratioTint(colors[vertex], style.skin, reference) else colors[vertex]
            }
        }
        return PortraitGeometry(positions, normals, colors,
            (if (hairEnabled) model.indices else model.bareIndices).copyOf(), model.surfaces.copyOf())
    }

    private fun coefficients(e: FaceEmotionState, gazeX: Float, gazeY: Float): FloatArray {
        fun weight(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        return floatArrayOf(e.eyeBlinkLeft, e.eyeBlinkRight, e.eyeSquintLeft, e.eyeSquintRight,
            e.eyeWideLeft, e.eyeWideRight, e.jawOpen, e.mouthSmileLeft, e.mouthSmileRight,
            e.mouthFrownLeft, e.mouthFrownRight, e.mouthPucker, e.mouthFunnel, e.browInnerUp,
            e.browDownLeft, e.browDownRight, e.browOuterUpLeft, e.browOuterUpRight,
            e.cheekPuff, e.cheekSquintLeft, e.cheekSquintRight,
            max(0f, gazeX), max(0f, -gazeX), max(0f, gazeY), max(0f, -gazeY))
            .also { values ->
                for (i in values.indices) values[i] = weight(values[i])
                // Pucker and funnel are alternative lip silhouettes. Tracking may
                // report both; adding two full poses over-deforms the mouth.
                val lipShapeWeight = values[11] + values[12]
                if (lipShapeWeight > 1f) {
                    values[11] /= lipShapeWeight
                    values[12] /= lipShapeWeight
                }
            }
    }

    private fun decode(compressed: InputStream): Model {
        DataInputStream(object : FilterInputStream(GZIPInputStream(compressed)) {
            private var bytes = 0L
            private fun count(amount: Int) { if (amount > 0) { bytes += amount; require(bytes <= MAX_BYTES) { "Portrait exceeds byte budget" } } }
            override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { count(it) }
        }).use { input ->
            val magic = ByteArray(8); input.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == MAGIC) { "Unsupported portrait format" }
            val vertexCount = input.readInt(); val indexCount = input.readInt(); val morphCount = input.readInt()
            require(vertexCount in 3..MAX_VERTICES && indexCount in 3..MAX_INDICES && indexCount % 3 == 0 && morphCount in 0..MAX_MORPHS) { "Invalid portrait counts" }
            fun floats(count: Int, bound: Float) = FloatArray(count) { input.readFloat().also {
                require(it.isFinite() && abs(it) <= bound) { "Invalid portrait float" }
            } }
            val positions = floats(vertexCount * 3, 2f)
            val normals = floats(vertexCount * 3, 2f)
            for (at in normals.indices step 3) {
                val length = sqrt(normals[at] * normals[at] + normals[at + 1] * normals[at + 1] + normals[at + 2] * normals[at + 2])
                require(length > 1e-8f) { "Zero portrait normal" }
                for (axis in 0..2) normals[at + axis] /= length
            }
            val colors = IntArray(vertexCount) { input.readInt().also { require(it ushr 24 == 255) { "Portrait colors must be opaque" } } }
            val removableHair = BooleanArray(vertexCount)
            val surfaces = IntArray(vertexCount) { vertex ->
                val encoded = input.readUnsignedByte()
                removableHair[vertex] = encoded and 0x80 != 0
                (encoded and 0x7f).also { require(it < PortraitSurface.COUNT) { "Invalid portrait material" } }
            }
            val indices = IntArray(indexCount) { input.readInt().also { require(it in 0 until vertexCount) { "Portrait index outside vertices" } } }
            var totalEntries = 0
            val usedNames = mutableSetOf<String>()
            val morphs = List(morphCount) {
                val nameSize = input.readUnsignedShort()
                require(nameSize in 1..512) { "Invalid morph name length" }
                val nameBytes = ByteArray(nameSize); input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                require(usedNames.add(name)) { "Duplicate portrait morph" }
                val channels = name.split('*').map { token -> names.indexOf(token).also { require(it >= 0) { "Unknown portrait morph: $name" } } }.toIntArray()
                require(channels.size <= 4) { "Too many corrective channels" }
                val count = input.readInt()
                require(count in 0..vertexCount && totalEntries.toLong() + count <= MAX_MORPH_ENTRIES) { "Invalid morph vertex count" }
                totalEntries += count
                val seen = BooleanArray(vertexCount)
                val vertices = IntArray(count)
                val deltas = FloatArray(count * 6)
                for (entry in 0 until count) {
                    val vertex = input.readInt()
                    require(vertex in 0 until vertexCount && !seen[vertex]) { "Invalid or repeated morph vertex" }
                    seen[vertex] = true; vertices[entry] = vertex
                    for (axis in 0 until 6) deltas[entry * 6 + axis] = input.readFloat().also {
                        require(it.isFinite() && abs(it) <= if (axis < 3) 1f else 4f) { "Invalid morph delta" }
                    }
                }
                Morph(channels, vertices, deltas)
            }
            require(input.read() == -1) { "Trailing portrait data" }
            val bare = ArrayList<Int>()
            for (at in indices.indices step 3) if ((0..2).none { removableHair[indices[at + it]] }) {
                bare.add(indices[at]); bare.add(indices[at + 1]); bare.add(indices[at + 2])
            }
            return Model(positions, normals, colors, surfaces, indices, bare.toIntArray(), morphs)
        }
    }

    private fun ratioTint(color: Int, target: Int, reference: Int): Int {
        fun channel(shift: Int) = (((color ushr shift) and 255) * ((target ushr shift) and 255).toFloat() /
            ((reference ushr shift) and 255).coerceAtLeast(1)).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun tint(color: Int, palette: Int): Int {
        val luminance = (((color ushr 16) and 255) * .2126f + ((color ushr 8) and 255) * .7152f + (color and 255) * .0722f) / 255f
        val value = .18f + .90f * sqrt(luminance)
        fun channel(shift: Int) = (((palette ushr shift) and 255) * value).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        fun channel(shift: Int) = (((a ushr shift) and 255) * (1f - amount) + ((b ushr shift) and 255) * amount).roundToInt()
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
