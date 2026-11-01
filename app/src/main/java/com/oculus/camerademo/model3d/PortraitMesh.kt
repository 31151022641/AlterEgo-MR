package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Renderer independent, metre-scale portrait. +Z is the face and +Y is up. */
data class PortraitGeometry(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: IntArray,
    val indices: IntArray,
    val surfaces: IntArray = IntArray(colors.size)
)

data class PortraitStyle(
    val skin: Int,
    val hair: Int = 0xff3f2b21.toInt(),
    val lips: Int = 0xffad6860.toInt(),
    val beard: Boolean = false,
    val metallic: Boolean = false,
    val character: String = "human",
    val variant: String = "classic"
)

/**
 * Rounded, expressive cartoon portrait with a continuous sculpted skin surface.
 * Eye and mouth surfaces follow that surface, and keep the same topology while
 * the sensor weights move. Anatomical left is +X. Creature rigs are independent.
 */
class PortraitMesh {
    fun build(
        style: PortraitStyle,
        emotion: FaceEmotionState = FaceEmotionState(),
        gazeX: Float = 0f,
        gazeY: Float = 0f,
        hairEnabled: Boolean = true,
        detail: Int = 20
    ): PortraitGeometry {
        if (style.character == "sunglasses") return PortraitSunglasses.standalone()
        if (style.character in setOf("lion", "dragon", "unicorn")) {
            return CartoonCreatureMesh.build(style, emotion, gazeX, gazeY, hairEnabled, detail)
        }
        AuthoredPortraitStore.build(style, emotion, gazeX, gazeY, hairEnabled)?.let { return it }
        val d = detail.coerceIn(12, 24)
        val out = SurfaceBuilder()
        val robot = style.variant == "cyber"
        val holo = style.variant == "holo"
        val beard = style.beard || style.variant == "bearded"
        val jaw = weight(emotion.jawOpen)
        val pucker = max(weight(emotion.mouthPucker), weight(emotion.mouthFunnel))
        val smileL = weight(emotion.mouthSmileLeft)
        val smileR = weight(emotion.mouthSmileRight)
        val frownL = weight(emotion.mouthFrownLeft)
        val frownR = weight(emotion.mouthFrownRight)
        val puff = weight(emotion.cheekPuff)
        val lookX = finite(gazeX).coerceIn(-1f, 1f)
        val lookY = finite(gazeY).coerceIn(-1f, 1f)
        val skin = if (holo) mix(opaque(style.skin), 0xffbdeee3.toInt(), .28f) else opaque(style.skin)
        val hair = if (style.metallic) mix(style.hair, skin, .58f) else if (holo) mix(style.hair, 0xff326c99.toInt(), .34f) else opaque(style.hair)
        val lipColor = when {
            robot -> mix(skin, 0xff113747.toInt(), .65f)
            style.metallic -> mix(skin, 0xffa86f21.toInt(), .30f)
            else -> mix(style.lips, skin, .23f)
        }
        val eyeWhite = if (style.metallic) mix(skin, 0xffffefd3.toInt(), .63f) else EYE_WHITE
        val irisColor = when { robot -> 0xff38c9d7.toInt(); holo -> 0xff14818c.toInt(); style.metallic -> 0xff9a6e27.toInt(); beard -> 0xff674733.toInt(); else -> 0xff795433.toInt() }
        val irisRim = when { robot || holo -> 0xff174e60.toInt(); style.metallic -> 0xff75501c.toInt(); else -> 0xff4b382e.toInt() }
        val pupilColor = if (style.metallic) 0xff483815.toInt() else if (robot || holo) 0xff17272e.toInt() else 0xff211a17.toInt()

        fun jawPoint(x: Float, y: Float, z: Float): V {
            val lower = smooth((-.026f - y) / .105f)
            return V(x, y - jaw * .029f * lower, z - jaw * .005f * lower)
        }
        fun ringWidth(y: Float): Float {
            val width = sculptProfile(y, HEAD_WIDTH)
            // A fuller jaw and flatter cheeks distinguish the masculine sculpt.
            return width * (1f + (if (beard) .045f else 0f) * gaussian(0f, (y + .065f) / .041f))
        }
        fun relief(x: Float, y: Float): Float {
            val nose = (if (robot) .014f else .018f) * gaussian(x / .020f, (y + .010f) / .014f)
            val bridge = .012f * gaussian(x / .011f, (y - .011f) / .025f)
            val wings = .007f * (gaussian((x - .016f) / .009f, (y + .017f) / .008f) + gaussian((x + .016f) / .009f, (y + .017f) / .008f))
            val smileLift = (smileL + smileR) * .5f
            val cheeks = (.014f + puff * .005f + smileLift * .004f) * (gaussian((x - .070f) / .032f, (y + .013f - smileLift * .008f) / .025f) + gaussian((x + .070f) / .032f, (y + .013f - smileLift * .008f) / .025f))
            val sockets = .007f * (gaussian((x - .049f) / .034f, (y - .025f) / .022f) + gaussian((x + .049f) / .034f, (y - .025f) / .022f))
            val chin = .007f * gaussian(x / .037f, (y + .087f) / .020f)
            val lipBed = .006f * gaussian(x / .040f, (y + .049f) / .019f)
            val philtrum = .002f * gaussian(x / .0055f, (y + .031f) / .010f)
            val dimples = .002f * smileLift * gaussian((abs(x) - .047f) / .009f, (y + .039f) / .012f)
            return nose + bridge + wings + cheeks + chin + lipBed - sockets - philtrum - dimples
        }
        fun front(x: Float, y: Float): Float {
            val radius = ringWidth(y).coerceAtLeast(.0001f)
            val cosine = sqrt(max(0f, 1f - (x / radius).coerceIn(-1f, 1f).pow(2)))
            return -.008f + sculptProfile(y, HEAD_DEPTH) * cosine.pow(.80f) + relief(x, y) * cosine * cosine
        }
        fun skinPoint(x: Float, y: Float, offset: Float = 0f) = jawPoint(x, y, front(x, y) + offset)
        fun skull(lon: Float, lat: Float): V {
            val y = .010f + .128f * sin(lat)
            val x = ringWidth(y) * sin(lon)
            val c = cos(lon)
            val z = -.008f + (if (c >= 0f) sculptProfile(y, HEAD_DEPTH) * c.pow(.80f) else .092f * cos(lat) * c) + relief(x, y) * max(0f, c).pow(2)
            return jawPoint(x, y, z)
        }
        fun longitude(u: Float): Float { val t = u * 2f - 1f; return sign(t) * abs(t).pow(1.22f) * PI_F }
        fun latitude(v: Float): Float { val t = v * 2f - 1f; return sign(t) * abs(t).pow(1.18f) * PI_F / 2f }
        out.patch(d + 22, d * 2 + 20, skin, colorAt = { u, v ->
            val lon = longitude(u)
            val y = .010f + .128f * sin(latitude(v))
            val x = ringWidth(y) * sin(lon)
            val blush = if (style.metallic || robot || holo) 0f else .22f * max(0f, cos(lon)).pow(5) * gaussian((abs(x) - .071f) / .030f, (y + .015f) / .027f)
            var color = mix(skin, 0xffd98183.toInt(), blush)
            val facing = max(0f, cos(lon))
            val socket = gaussian((abs(x) - .047f) / .035f, (y - .032f) / .025f) * facing.pow(3)
            color = mix(color, if (robot) 0xff243f57.toInt() else if (holo) 0xff258795.toInt() else if (style.metallic) 0xff927022.toInt() else mix(skin, 0xff69463b.toInt(), .45f), socket * .16f)
            if (holo) color = mix(color, 0xff4b9fbd.toInt(), .32f * abs(sin(lon)) * facing)
            color
        }) { u, v -> skull(longitude(u), latitude(v)) }

        // Rounded ear lobes merge into the cheek, with a recessed warm inner fold.
        for (side in listOf(-1f, 1f)) {
            out.ellipsoid(V(side * .119f, .004f, -.010f), V(if (robot) .019f else .013f, .026f, .018f), if (robot) mix(skin, hair, .30f) else skin, 8, 12)
            out.ellipsoid(V(side * .124f, .003f, .005f), V(.006f, .016f, .005f), if (robot) 0xff54dbe1.toInt() else mix(skin, 0xffb36c60.toInt(), .24f), 6, 10)
        }

        // A rounded nose tip has its own curved topology. The broad bridge and
        // alar volumes in the skin bury its rear half and soften the transition.
        val noseCenterZ = front(0f, -.011f) - .0015f
        out.ellipsoid(V(0f, -.011f, noseCenterZ), V(.017f, .012f, if (robot) .013f else .017f),
            skin, 11, 16)

        val eyeSegments = d + 12
        for (side in listOf(-1f, 1f)) {
            val left = side > 0f
            val blink = weight(if (left) emotion.eyeBlinkLeft else emotion.eyeBlinkRight)
            val squint = weight(if (left) emotion.eyeSquintLeft else emotion.eyeSquintRight)
            val wide = weight(if (left) emotion.eyeWideLeft else emotion.eyeWideRight)
            val cx = side * .049f
            val cy = .025f
            val halfWidth = if (robot) .030f else .0305f
            val baseHeight = if (beard || style.metallic) .0205f else .0215f
            val halfHeight = ((baseHeight + wide * .005f) * (1f - blink) * (1f - .40f * squint)).coerceAtLeast(.0002f)
            val closed = blink > .96f
            // Almond contour, lifted outer corners and a relaxed lower lid.
            // The same contour clips every iris layer during gaze and blinking.
            fun lidCenter(nx: Float) = cy + side * .0022f * nx + blink * .002f * (1f - nx * nx)
            fun lidSpan(nx: Float) = halfHeight * max(0f, 1f - nx * nx).pow(.60f)
            fun boundary(a: Float): Pair<Float, Float> {
                val nx = cos(a)
                return cx + halfWidth * nx to lidCenter(nx) +
                    sign(sin(a)) * lidSpan(nx) * (if (sin(a) >= 0f) .90f else .72f)
            }
            fun eyeZ(x: Float, y: Float): Float {
                val r = ((x - cx) / halfWidth).pow(2) + ((y - cy) / (halfHeight + .001f)).pow(2)
                return front(x, y) + .0018f + .005f * (1f - r.coerceIn(0f, 1f))
            }
            out.surface = PortraitSurface.EYE
            out.patch(5, eyeSegments, if (closed) skin else eyeWhite) { u, v ->
                val edge = boundary(-u * TAU)
                val x = cx + (edge.first - cx) * v
                val y = cy + (edge.second - cy) * v
                V(x, y, eyeZ(x, y))
            }
            val irisX = cx + lookX * .007f
            val irisY = cy + .002f * (1f - blink) + lookY * halfHeight * .22f
            fun eyeDisc(radius: Float, color: Int, depth: Float, segments: Int, offsetX: Float = 0f, offsetY: Float = 0f) {
                out.patch(3, segments, if (closed) skin else color) { u, v ->
                    val a = -u * TAU
                    val x = irisX + offsetX * (1f - blink) + radius * cos(a) * v * (1f - blink * .4f)
                    val rawY = irisY + offsetY * (1f - blink) + radius * sin(a) * v * (halfHeight / baseHeight).coerceAtMost(1.25f)
                    val nx = ((x - cx) / halfWidth).coerceIn(-1f, 1f)
                    val aperture = lidSpan(nx) * .96f
                    val center = lidCenter(nx)
                    val y = rawY.coerceIn(center - aperture * .72f, center + aperture * .90f)
                    V(x, y, eyeZ(x, y) + depth)
                }
            }
            eyeDisc(.0185f, irisRim, .00040f, 32)
            eyeDisc(.0172f, irisColor, .00075f, 32)
            eyeDisc(.0148f, mix(irisColor, 0xffd6ae66.toInt(), if (robot || holo) .05f else .10f), .00105f, 28)
            eyeDisc(.0115f, pupilColor, .00135f, 28)
            eyeDisc(.0039f, 0xfffffff6.toInt(), .0018f, 14, -.0045f, .006f)
            eyeDisc(.0013f, 0xfffffff6.toInt(), .0019f, 10, .005f, -.004f)
            out.surface = PortraitSurface.SKIN
            out.patch(5, eyeSegments, skin, colorAt = { u, v ->
                val upper = sin(-u * TAU) > 0f
                mix(skin, if (upper) mix(skin, hair, .45f) else mix(skin, lipColor, .45f), (1f - v) * if (upper) .30f else .18f)
            }) { u, v ->
                val a = -u * TAU
                val edge = boundary(a)
                val upper = sin(a) > 0f
                val x = edge.first + cos(a) * .0045f * v
                val y = edge.second + sin(a) * (if (upper) .008f else .006f) * v
                V(x, y, front(x, y) + .0018f * (1f - v) + sin(v * PI_F) * (if (upper) .0038f else .0023f))
            }
            // A soft lid crease is readable when open and becomes the blink line.
            out.patch(1, eyeSegments, mix(hair, skin, .18f)) { u, v ->
                val a = (1f - u) * PI_F
                val edge = boundary(a)
                val thickness = .0005f + (if (beard || robot) .0018f else .0026f) * sin(u * PI_F).coerceAtLeast(0f).pow(.5f)
                V(edge.first, edge.second + (v - .5f) * thickness, front(edge.first, edge.second) + if (closed) .012f else .0029f)
            }
            val browDown = weight(if (left) emotion.browDownLeft else emotion.browDownRight)
            val browOuter = weight(if (left) emotion.browOuterUpLeft else emotion.browOuterUpRight)
            val browInner = weight(emotion.browInnerUp)
            val browColor = if (style.metallic) mix(skin, hair, .52f) else hair
            out.patch(4, d + 4, browColor, surfaceType = PortraitSurface.HAIR) { u, v ->
                val t = u * 2f - 1f
                val x = cx + .030f * t
                val outer = (t * side + 1f) * .5f
                val centerY = .059f + .008f * (1f - t * t) - .003f * outer + browInner * .011f * (1f - outer) + browOuter * .011f * outer - browDown * .009f
                val thickness = (.0007f + (if (beard) .0085f else .0060f) * max(0f, 1f - t * t).pow(.40f)) * (1.15f - .68f * outer)
                val y = centerY + (v - .5f) * thickness
                V(x, y, front(x, y) + .0015f + sin(v * PI_F) * .0025f)
            }
        }

        // Tiny warm nostril crescents underline a local button nose.
        for (side in listOf(-1f, 1f)) out.patch(2, 12, mix(skin, if (style.metallic) 0xff77501c.toInt() else 0xff804c3c.toInt(), .38f)) { u, v ->
            val a = -u * TAU
            val x = side * .012f + .0037f * cos(a) * v
            val y = -.0195f + .0018f * sin(a) * v
            val sphere = 1f - (x / .017f).pow(2) - ((y + .011f) / .012f).pow(2)
            val z = max(front(x, y), noseCenterZ + (if (robot) .013f else .017f) * sqrt(max(0f, sphere)))
            jawPoint(x, y, z + .00055f)
        }

        out.surface = PortraitSurface.MOUTH
        val width = (if (robot) .033f else .037f) * (1f + (smileL + smileR) * .13f) * (1f - pucker * .47f)
        val mouthDark = if (style.metallic) 0xff6e491a.toInt() else 0xff573039.toInt()
        fun mouth(t: Float, upper: Boolean): Pair<Float, Float> {
            val smile = if (t >= 0f) smileL else smileR
            val frown = if (t >= 0f) frownL else frownR
            val arch = (.0035f + smile * .014f - frown * .014f) * abs(t).pow(1.8f)
            val span = sqrt(max(0f, 1f - t * t))
            val line = -.050f + arch
            val y = if (upper) line + (.00065f + jaw * .004f + pucker * .0045f + smile * .002f) * span
                else line - (.00065f + jaw * .026f + pucker * .009f + smile * .0055f) * span
            return t * width to y
        }
        fun mouthPoint(x: Float, y: Float, offset: Float) = skinPoint(x, y, offset + pucker * .012f)
        out.patch(5, d + 12, mouthDark) { u, v ->
            val t = u * 2f - 1f
            val top = mouth(t, true); val bottom = mouth(t, false)
            val y = bottom.second + (top.second - bottom.second) * v
            mouthPoint(top.first, y, .0011f)
        }
        for (upper in listOf(true, false)) out.patch(6, d + 12, lipColor, colorAt = { _, rawV ->
            val v = if (upper) rawV else 1f - rawV
            val base = if (upper) mix(lipColor, 0xff6f4242.toInt(), .10f) else mix(lipColor, skin, .12f)
            // The vermilion blends into the muzzle instead of a painted outline.
            mix(base, skin, .70f * smooth((v - .48f) / .52f))
        }) { u, rawV ->
            val v = if (upper) rawV else 1f - rawV
            val t = u * 2f - 1f
            val edge = mouth(t, upper)
            val span = sqrt(max(0f, 1f - t * t))
            val cupid = 1f + .28f * exp(-((abs(t) - .28f) / .20f).pow(2)) - .12f * exp(-(t / .13f).pow(2))
            val thickness = (if (upper) .0055f * cupid else .0080f) * span
            val y = edge.second + (if (upper) 1f else -1f) * thickness * v
            mouthPoint(edge.first * (1f + .025f * v), y, .0007f + .0060f * sin(v * PI_F) + .0024f * (1f - v))
        }
        val toothVisible = jaw > .10f || max(smileL, smileR) > .3f
        out.patch(2, d + 4, if (toothVisible) (if (style.metallic) mix(skin, 0xffffecc9.toInt(), .50f) else 0xfff2e8d6.toInt()) else mouthDark) { u, rawV ->
            val v = 1f - rawV
            val t = (u * 2f - 1f) * .82f
            val top = mouth(t, true); val bottom = mouth(t, false)
            val available = (top.second - bottom.second).coerceAtLeast(0f)
            mouthPoint(top.first, top.second - .00025f - min(.0080f, available * .52f) * v, .00145f)
        }
        out.patch(2, d, if (jaw > .3f && !style.metallic) 0xffb87881.toInt() else mouthDark) { u, v ->
            val t = (u * 2f - 1f) * .60f
            val bottom = mouth(t, false)
            mouthPoint(bottom.first, bottom.second + (.0005f + jaw * .003f) * v, .0014f)
        }

        if (beard && !robot) {
            out.surface = PortraitSurface.HAIR
            val beardColor = mix(hair, 0xffa87551.toInt(), .08f)
            // A short rounded jaw contour leaves the cheeks and mouth uncovered.
            out.patch(16, d * 2 + 8, beardColor, colorAt = { u, v ->
                val groomed = mix(beardColor, skin, .03f + .025f * cos(u * 18f))
                mix(skin, groomed, smooth((1f - v) / .13f))
            }) { u, v ->
                val lon = (u * 2f - 1f) * 1.65f
                var edgeY = -.066f
                repeat(4) {
                    val x = ringWidth(edgeY) * sin(lon)
                    edgeY = -.066f + .048f * (abs(x) / .116f).coerceIn(0f, 1f).pow(1.55f)
                }
                val edgeLat = asin(((edgeY - .010f) / .128f).coerceIn(-1f, 1f))
                val lat = -PI_F / 2f + (edgeLat + PI_F / 2f) * v
                val p = skull(lon, lat)
                val volume = .0005f + (.0015f + .0035f * sin(v * PI_F).coerceAtLeast(0f).pow(.7f)) * smooth((1f - v) / .13f)
                V(p.x + sin(lon) * volume, p.y - .002f * (1f - v), p.z + cos(lon) * volume)
            }
            // Two groomed moustache lobes follow the upper lip. Leaving skin at
            // the mouth corners avoids the old dark oval surrounding the lips.
            out.patch(4, d * 2 + 8, beardColor) { u, v ->
                val t = u * 2f - 1f
                val edge = mouth(t, true)
                val span = sqrt(max(0f, 1f - t * t))
                val lower = edge.second + .0055f * span
                val thickness = .0065f * span * (1f - .50f * exp(-(t / .16f).pow(2)))
                skinPoint(edge.first * 1.10f, lower + thickness * v, .0020f + .0022f * sin(v * PI_F) * span)
            }
        }

        if (!beard && !robot && !holo && !style.metallic) {
            // Rigid open frames keep the eyes visible; no opaque lens overlays.
            val frame = 0xffd9b77b.toInt()
            for (side in listOf(-1f, 1f)) {
                out.patch(5, 32, frame, surfaceType = PortraitSurface.HORN) { u, v ->
                    val a = u * TAU; val b = v * TAU
                    val radius = .0017f * cos(b)
                    val x = side * .049f + (.036f + radius) * cos(a)
                    val y = .025f + (.030f + radius) * sin(a)
                    val z = .110f - .30f * (abs(x) - .014f) + .0017f * sin(b)
                    V(x, y, z)
                }
                out.rod(V(side * .084f, .027f, .089f), V(side * .127f, .008f, -.014f), .0019f, frame)
            }
            out.ellipsoid(V(0f, .034f, .111f), V(.016f, .0024f, .0024f), frame, 5, 12, PortraitSurface.HORN)
        }
        if (hairEnabled) {
            val kind = when { robot -> "cyber"; holo -> "holo"; style.metallic -> "gold"; beard -> "bearded"; else -> "classic" }
            out.append(PortraitHairMesh.build(hair, kind, d))
        }
        if (robot || holo) {
            out.surface = PortraitSurface.SKIN
            val accent = if (robot) 0xff65e9ee.toInt() else 0xffaffff0.toInt()
            for (side in listOf(-1f, 1f)) {
                // Temple inlays and cheek accents belong to the curved surface.
                out.patch(3, 16, if (robot) mix(skin, hair, .38f) else mix(skin, 0xff056978.toInt(), .20f)) { u, v ->
                    val y = -.040f + .086f * u
                    val x = side * (.106f + .006f * sin(u * PI_F)) + .008f * (.5f - v)
                    skinPoint(x, y, .0028f + .002f * sin(v * PI_F))
                }
                out.patch(2, 12, accent, surfaceType = PortraitSurface.ACCENT) { u, v ->
                    val y = -.030f + .022f * u
                    val x = side * .095f + .002f * (.5f - v)
                    skinPoint(x, y, .0045f)
                }
                if (robot) for (dot in 0 until 3) out.ellipsoid(V(side * (.077f + dot * .005f), -.039f, front(side * (.077f + dot * .005f), -.039f) + .0025f), V(.0016f, .0016f, .001f), accent, 4, 8, PortraitSurface.ACCENT)
            }
            out.patch(2, 12, accent, surfaceType = PortraitSurface.ACCENT) { u, v ->
                val x = (.5f - v) * .006f
                val y = .097f + .022f * u
                skinPoint(x, y, .006f)
            }
        }
        return out.geometry()
    }
    private data class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(other: V) = V(x + other.x, y + other.y, z + other.z)
        operator fun times(scale: Float) = V(x * scale, y * scale, z * scale)
        operator fun minus(other: V) = V(x - other.x, y - other.y, z - other.z)
        fun cross(other: V) = V(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
        fun unit(fallback: V = V(0f, 0f, 1f)): V {
            val length = sqrt(x * x + y * y + z * z)
            return if (length > 1e-14f && length.isFinite()) V(x / length, y / length, z / length) else fallback
        }
    }

    private class SurfaceBuilder {
        var surface = PortraitSurface.SKIN
        private var positions = FloatArray(18000)
        private var normals = FloatArray(18000)
        private var colors = IntArray(6000)
        private var surfaces = IntArray(6000)
        private var indices = IntArray(42000)
        private var vertices = 0
        private var primitives = 0

        fun patch(rows: Int, columns: Int, color: Int, colorAt: ((Float, Float) -> Int)? = null, surfaceType: Int = this.surface,
            surface: (Float, Float) -> V) {
            val count = (rows + 1) * (columns + 1)
            reserve(vertices + count, primitives + rows * columns * 6)
            val start = vertices
            for (row in 0..rows) for (column in 0..columns) {
                val u = column.toFloat() / columns
                val v = row.toFloat() / rows
                val p = surface(u, v)
                val du = surface((u + .0003f).coerceAtMost(1f), v) - surface((u - .0003f).coerceAtLeast(0f), v)
                val dv = surface(u, (v + .0003f).coerceAtMost(1f)) - surface(u, (v - .0003f).coerceAtLeast(0f))
                val fallback = if (abs(p.y - .010f) > .126f) V(0f, sign(p.y - .010f), 0f) else V(0f, 0f, 1f)
                val n = du.cross(dv).unit(fallback)
                val i = vertices * 3
                positions[i] = p.x; positions[i + 1] = p.y; positions[i + 2] = p.z
                normals[i] = n.x; normals[i + 1] = n.y; normals[i + 2] = n.z
                surfaces[vertices] = surfaceType
                colors[vertices++] = opaque(colorAt?.invoke(u, v) ?: color)
            }
            val topology = grids.getOrPut((rows shl 16) or columns) {
                IntArray(rows * columns * 6).also { grid ->
                    var i = 0
                    for (row in 0 until rows) for (column in 0 until columns) {
                        val a = row * (columns + 1) + column
                        val b = a + columns + 1
                        grid[i++] = a; grid[i++] = a + 1; grid[i++] = b
                        grid[i++] = a + 1; grid[i++] = b + 1; grid[i++] = b
                    }
                }
            }
            for (index in topology) indices[primitives++] = start + index
        }

        fun ellipsoid(center: V, radius: V, color: Int, rows: Int, columns: Int, surfaceType: Int = surface) {
            patch(rows, columns, color, surfaceType = surfaceType) { u, v ->
                val lat = (v - .5f) * PI_F
                val lon = (u * 2f - 1f) * PI_F
                V(center.x + radius.x * cos(lat) * sin(lon),
                    center.y + radius.y * sin(lat), center.z + radius.z * cos(lat) * cos(lon))
            }
        }

        fun append(mesh: PortraitGeometry) {
            reserve(vertices + mesh.colors.size, primitives + mesh.indices.size)
            mesh.positions.copyInto(positions, vertices * 3)
            mesh.normals.copyInto(normals, vertices * 3)
            mesh.colors.copyInto(colors, vertices)
            mesh.surfaces.copyInto(surfaces, vertices)
            for (index in mesh.indices) indices[primitives++] = index + vertices
            vertices += mesh.colors.size
        }

        fun rod(a: V, b: V, radius: Float, color: Int) {
            val axis = (b - a).unit()
            val side = V(axis.y, -axis.x, 0f).unit(V(1f, 0f, 0f))
            val back = side.cross(axis).unit()
            patch(5, 10, color, surfaceType = PortraitSurface.HORN) { u, v ->
                val angle = -u * TAU
                val r = radius * sin(v * PI_F).coerceAtLeast(0f).pow(.25f)
                a * (1f - v) + b * v + side * (r * cos(angle)) + back * (r * sin(angle))
            }
        }

        fun horn(base: V, radius: Float, height: Float, lean: V, color: Int,
            colorAt: ((Float, Float) -> Int)? = null) {
            patch(10, 14, color, colorAt) { u, v ->
                val a = u * TAU
                val r = radius * (1f - v).pow(.78f)
                V(base.x + lean.x * v + r * sin(a), base.y + height * v,
                    base.z + lean.z * v + r * cos(a))
            }
        }

        private fun reserve(vertexCount: Int, indexCount: Int) {
            if (vertexCount > colors.size) {
                val capacity = max(vertexCount, colors.size * 2)
                colors = colors.copyOf(capacity)
                surfaces = surfaces.copyOf(capacity)
                positions = positions.copyOf(capacity * 3)
                normals = normals.copyOf(capacity * 3)
            }
            if (indexCount > indices.size) indices = indices.copyOf(max(indexCount, indices.size * 2))
        }
        fun geometry() = PortraitGeometry(positions.copyOf(vertices * 3), normals.copyOf(vertices * 3),
            colors.copyOf(vertices), indices.copyOf(primitives), surfaces.copyOf(vertices))
    }

    companion object {
        private const val PI_F = 3.1415927f
        private const val TAU = PI_F * 2f
        private const val EYE_WHITE = 0xfff7f0e5.toInt()
        private val grids = ConcurrentHashMap<Int, IntArray>()
        // Sculpted cross sections: round cranium, full cheek, tapered jaw and a
        // rounded chin. Width and depth are authored independently of a sphere.
        private val HEAD_Y = floatArrayOf(-.118f, -.110f, -.100f, -.080f, -.060f, -.035f, -.010f, .015f, .045f, .075f, .100f, .125f, .138f)
        private val HEAD_WIDTH = floatArrayOf(0f, .031f, .053f, .078f, .094f, .108f, .117f, .118f, .115f, .110f, .096f, .060f, 0f)
        private val HEAD_DEPTH = floatArrayOf(0f, .025f, .039f, .057f, .070f, .078f, .080f, .079f, .077f, .070f, .055f, .030f, 0f)
        private fun sculptProfile(y: Float, values: FloatArray): Float {
            if (y <= HEAD_Y.first()) return values.first()
            if (y >= HEAD_Y.last()) return values.last()
            var i = 0
            while (HEAD_Y[i + 1] < y) i++
            // Interpolate squared radii so both ends close as rounded caps,
            // rather than producing a cone at the chin and crown.
            fun squared(j: Int) = values[j] * values[j]
            fun slope(j: Int) = (squared(j + 1) - squared(j)) / (HEAD_Y[j + 1] - HEAD_Y[j])
            fun tangent(j: Int): Float {
                if (j == 0) return slope(0)
                if (j == values.lastIndex) return slope(j - 1)
                val a = slope(j - 1); val b = slope(j)
                return if (a * b <= 0f) 0f else 2f * a * b / (a + b)
            }
            val h = HEAD_Y[i + 1] - HEAD_Y[i]
            val t = (y - HEAD_Y[i]) / h
            val t2 = t * t; val t3 = t2 * t
            return sqrt(((2f * t3 - 3f * t2 + 1f) * squared(i) + (t3 - 2f * t2 + t) * h * tangent(i) +
                (-2f * t3 + 3f * t2) * squared(i + 1) + (t3 - t2) * h * tangent(i + 1)).coerceAtLeast(0f))
        }
        private fun finite(value: Float) = if (value.isFinite()) value else 0f
        private fun weight(value: Float) = finite(value).coerceIn(0f, 1f)
        private fun smooth(value: Float): Float { val t = value.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        private fun gaussian(x: Float, y: Float) = exp(-x * x - y * y)
        private fun opaque(color: Int) = color or (0xff shl 24)
        private fun mix(a: Int, b: Int, fraction: Float): Int {
            val t = fraction.coerceIn(0f, 1f)
            fun channel(shift: Int) = (((a ushr shift) and 255) * (1f - t) + ((b ushr shift) and 255) * t).roundToInt().coerceIn(0, 255)
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}



