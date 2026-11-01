package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import kotlin.math.*

/** Rounded, fully volumetric creatures. Metres; +Z faces the viewer and +X is anatomical left. */
object CartoonCreatureMesh {
    fun build(
        style: PortraitStyle,
        emotion: FaceEmotionState,
        gazeX: Float,
        gazeY: Float,
        hairEnabled: Boolean,
        detail: Int
    ): PortraitGeometry {
        val d = detail.coerceIn(12, 24)
        val lion = style.character == "lion"
        val pony = style.character == "unicorn"
        val out = Builder()
        val sourceDefault = if (lion) 0xffffb300.toInt() else if (pony) 0xffffffff.toInt() else 0xff4caf50.toInt()
        val selectedSkin = style.skin or (0xff shl 24)
        val customSkin = selectedSkin != sourceDefault
        val skin = if (customSkin) selectedSkin else if (lion) 0xffedb763.toInt() else if (pony) 0xfffff1f4.toInt() else 0xff52ad98.toInt()
        val muzzleColor = if (customSkin) mix(skin, 0xfffff4e4.toInt(), .63f)
            else if (lion) 0xffffedc9.toInt() else if (pony) 0xffedbfd8.toInt() else 0xffbce6bf.toInt()
        val browColor = if (customSkin) mix(skin, 0xff29343d.toInt(), .68f)
            else if (lion) 0xff774a30.toInt() else if (pony) 0xff8665a4.toInt() else 0xff327d67.toInt()
        val iris = if (lion) 0xffa07036.toInt() else if (pony) 0xff9279bf.toInt() else 0xffc3a34b.toInt()
        val jaw = w(emotion.jawOpen)
        val pucker = max(w(emotion.mouthPucker), w(emotion.mouthFunnel))
        val smileL = w(emotion.mouthSmileLeft)
        val smileR = w(emotion.mouthSmileRight)
        val frownL = w(emotion.mouthFrownLeft)
        val frownR = w(emotion.mouthFrownRight)
        val lookX = finite(gazeX).coerceIn(-1f, 1f)
        val lookY = finite(gazeY).coerceIn(-1f, 1f)
        val mouthY = if (pony) -.140f else if (lion) -.078f else -.092f
        // Species have their own silhouette and sagittal profile. In particular,
        // the horse's long nasal bridge and the dragon's projecting snout must
        // remain recognizable with the mane, horn and tracking disabled.
        val profile = when {
            pony -> arrayOf(
                Section(-.169f, 0f, .102f, .025f),
                Section(-.154f, .046f, .158f, -.010f),
                Section(-.123f, .067f, .190f, -.026f),
                Section(-.088f, .063f, .182f, -.046f),
                Section(-.040f, .064f, .138f, -.066f),
                Section(.016f, .083f, .091f, -.085f),
                Section(.065f, .098f, .052f, -.087f),
                Section(.111f, .080f, .019f, -.065f),
                Section(.145f, 0f, -.023f, -.023f))
            lion -> arrayOf(
                Section(-.127f, 0f, .024f, -.030f),
                Section(-.111f, .055f, .078f, -.065f),
                Section(-.084f, .084f, .125f, -.077f),
                Section(-.048f, .109f, .132f, -.085f),
                Section(-.005f, .128f, .093f, -.090f),
                Section(.038f, .118f, .072f, -.090f),
                Section(.080f, .102f, .043f, -.075f),
                Section(.113f, .068f, .010f, -.052f),
                Section(.132f, 0f, -.025f, -.025f))
            else -> arrayOf(
                Section(-.126f, 0f, .035f, -.030f),
                Section(-.115f, .066f, .110f, -.055f),
                Section(-.100f, .098f, .165f, -.068f),
                Section(-.068f, .114f, .188f, -.075f),
                Section(-.030f, .108f, .173f, -.085f),
                Section(.012f, .103f, .108f, -.090f),
                Section(.055f, .115f, .064f, -.090f),
                Section(.094f, .095f, .025f, -.070f),
                Section(.124f, 0f, -.020f, -.020f))
        }
        val centerY = (profile.first().y + profile.last().y) * .5f
        val height = (profile.last().y - profile.first().y) * .5f
        fun section(y: Float) = sample(profile, y)

        fun jawPoint(x: Float, y: Float, z: Float): V {
            val hinge = if (pony) -.119f else if (lion) -.057f else -.075f
            val lower = smooth((hinge - y) / (if (pony) .046f else .062f))
            return V(x, y - jaw * .026f * lower, z - jaw * .008f * lower)
        }
        fun radius(y: Float) = section(y).width
        fun frontExponent(y: Float) = if (lion) .82f else if (pony) .72f else .48f + .34f * smooth((y + .015f) / .10f)
        fun relief(x: Float, y: Float): Float {
            val muzzle = if (lion) (.019f + w(emotion.cheekPuff) * .004f) *
                (gauss((x - .032f) / .034f, (y + .055f) / .026f) + gauss((x + .032f) / .034f, (y + .055f) / .026f))
                else if (pony) .005f * gauss(x / .025f, (y + .038f) / .075f)
                else .008f * (gauss((x - .062f) / .028f, (y + .008f) / .040f) + gauss((x + .062f) / .028f, (y + .008f) / .040f))
            val socketX = if (pony) .068f else if (lion) .060f else .070f
            val socketY = if (pony) .060f else if (lion) .044f else .054f
            val sockets = .005f * (gauss((x - socketX) / .029f, (y - socketY) / .027f) + gauss((x + socketX) / .029f, (y - socketY) / .027f))
            val recess = (.001f + jaw * .016f + pucker * .003f) * gauss(x / (if (pony) .055f else .070f), (y - mouthY + jaw * .008f) / (.014f + jaw * .018f))
            return muzzle - sockets - recess
        }
        fun front(x: Float, y: Float): Float {
            val ring = section(y)
            val c = sqrt(max(0f, 1f - (x / ring.width.coerceAtLeast(.0001f)).coerceIn(-1f, 1f).pow(2)))
            return -.020f + (ring.front + .020f) * c.pow(frontExponent(y)) + relief(x, y) * c * c
        }
        fun point(x: Float, y: Float, offset: Float = 0f) = jawPoint(x, y, front(x, y) + offset)
        fun skull(lon: Float, lat: Float): V {
            val y = centerY + height * sin(lat)
            val ring = section(y)
            val x = ring.width * sin(lon)
            val c = cos(lon)
            val z = if (c >= 0f) -.020f + (ring.front + .020f) * c.pow(frontExponent(y)) + relief(x, y) * c * c
                else -.020f + (-.020f - ring.back) * c
            return jawPoint(x, y, z)
        }

        // One closed mane volume: swept ridges are part of the same surface,
        // eliminating intersections between a backing mass and separate locks.
        if (lion && hairEnabled) {
            out.surface = PortraitSurface.HAIR
            out.patch(d / 2 + 22, 96, 0xffb96935.toInt(), colorAt = { u, v ->
                val a = u * TAU
                val lat = (v - .5f) * PI_F
                val sweep = ((cos(lat) - .60f) / .40f).coerceIn(0f, 1f)
                val flow = .5f + .5f * cos(a * 12f + sweep * 3.5f)
                mix(0xff78362b.toInt(), 0xffe9aa55.toInt(), .32f + .30f * flow + .08f * sin(a))
            }) { u, v ->
                val a = u * TAU
                val lat = (v - .5f) * PI_F
                val r = cos(lat)
                val wave = .0065f * sin(a * 9f + .65f) + .0035f * sin(a * 5f - .4f)
                val sweep = ((r - .60f) / .40f).coerceIn(0f, 1f)
                val flow = .5f + .5f * cos(a * 12f + sweep * 3.5f)
                val ridge = .012f * flow * sin(sweep * PI_F).coerceAtLeast(0f) * smooth(sin(lat) / .25f)
                V((.160f + wave) * r * cos(a), -.004f + (.164f + wave) * r * sin(a), -.050f + .060f * sin(lat) + ridge)
            }
        }
        out.surface = PortraitSurface.SKIN

        // Ears are embedded in the head. The separate inner fold has volume and
        // stays behind the outer rim from oblique views.
        for (side in listOf(-1f, 1f)) {
            if (lion) {
                out.ellipsoid(V(side * .094f, .093f, -.010f), V(.031f, .033f, .024f), skin, 9, 14)
                out.ellipsoid(V(side * .095f, .095f, .010f), V(.018f, .021f, .007f),
                    if (customSkin) mix(skin, 0xff76544f.toInt(), .40f) else 0xffb67b57.toInt(), 7, 12)
                out.ellipsoid(V(side * .095f, .091f, .016f), V(.011f, .012f, .003f),
                    if (customSkin) mix(skin, muzzleColor, .62f) else 0xffe4b67e.toInt(), 5, 10)
            } else if (pony) {
                fun ear(u: Float, v: Float, inset: Boolean): V {
                    val lat = (v - .5f) * PI_F
                    val a = u * TAU
                    val length = if (inset) .036f else .050f
                    val cy = if (inset) .144f else .140f
                    val localY = length * sin(lat)
                    val width = (if (inset) .0115f else .022f) * cos(lat) * (1f - .22f * sin(lat))
                    return V(side * (.075f + localY * .24f) + width * sin(a), cy + localY,
                        (if (inset) .010f else -.003f) + (if (inset) .004f else .015f) * cos(lat) * cos(a))
                }
                out.patch(12, 16, skin) { u, v -> ear(u, v, false) }
                out.patch(9, 12, if (customSkin) mix(skin, 0xffb88ba1.toInt(), .50f) else 0xffd699b9.toInt()) { u, v -> ear(u, v, true) }
            } else {
                // A ribbed, pointed web distinguishes dragon frills from mammal ears.
                val outline = arrayOf(V(.103f, .060f, -.037f), V(.170f, .106f, -.060f),
                    V(.150f, .057f, -.048f), V(.174f, .030f, -.042f), V(.146f, .018f, -.038f),
                    V(.154f, -.012f, -.030f), V(.102f, -.024f, -.022f))
                val root = V(side * .113f, .026f, -.018f)
                out.patch(6, 42, skin, colorAt = { _, v -> mix(skin, 0xffbddb9a.toInt(), .15f + .40f * v) }) { u, v ->
                    val position = (if (side > 0f) u else 1f - u) * outline.size
                    val segment = position.toInt().coerceAtMost(outline.lastIndex)
                    val t = position - segment
                    val a = outline[segment]; val b = outline[(segment + 1) % outline.size]
                    val edge = a * (1f - t) + b * t
                    val p = root * (1f - v) + V(side * edge.x, edge.y, edge.z) * v
                    V(p.x, p.y, p.z + .004f * sin(v * PI_F))
                }
                for (tip in listOf(1, 3, 5)) {
                    val edge = outline[tip]
                    out.curvedTube(root, V(side * .130f, .026f, -.018f),
                        V(side * edge.x, edge.y, edge.z + .004f), V(side * edge.x, edge.y, edge.z),
                        .0028f, .0025f, mix(skin, 0xffd5e7ad.toInt(), .48f), 9, 8)
                }
                out.curvedTube(V(side * .067f, .084f, -.040f), V(side * .098f, .124f, -.065f),
                    V(side * .112f, .156f, -.110f), V(side * .105f, .161f, -.151f),
                    .018f, .015f, 0xffffdfae.toInt(), 20, 14, surfaceType = PortraitSurface.HORN)
                for (spike in 0..1) out.curvedTube(V(side * .100f, -.026f - spike * .023f, -.015f),
                    V(side * .124f, -.026f - spike * .026f, -.006f),
                    V(side * .142f, -.037f - spike * .026f, -.004f),
                    V(side * .145f, -.045f - spike * .026f, -.007f),
                    .010f, .008f, mix(skin, 0xffd5e7ad.toInt(), .48f), 9, 10, surfaceType = PortraitSurface.HORN)
            }
        }

        // Broad cheeks and the muzzle belong to the head surface. The palette
        // transition therefore cannot leave a separate floating muzzle seam.
        val headCols = d * 2 + 12
        fun longitude(u: Float): Float { val t = u * 2f - 1f; return sign(t) * abs(t).pow(1.22f) * PI_F }
        out.patch(d + 26, headCols, skin, colorAt = { u, v ->
            val lon = longitude(u)
            val lat = (v - .5f) * PI_F
            val y = centerY + height * sin(lat)
            val x = radius(y) * sin(lon)
            val facing = if (lion) smooth(max(0f, cos(lon)) / .65f) else max(0f, cos(lon)).pow(4)
            val patch = when {
                lion -> max(gauss((x - .030f) / .043f, (y + .057f) / .030f), gauss((x + .030f) / .043f, (y + .057f) / .030f))
                pony -> gauss(x / .084f, (y + .123f) / .038f)
                else -> gauss(x / .112f, (y + .092f) / .038f)
            }
            var color = mix(skin, muzzleColor, smooth((patch - .16f) / (if (lion) .43f else .55f)) * facing)
            // A cool coat edge and warm center give each sculpt a deliberate palette.
            val sideTone = smooth((abs(sin(lon)) - .45f) / .45f) * max(0f, cos(lon))
            color = mix(color, if (pony) 0xffb8b8da.toInt() else if (lion) 0xffbd783e.toInt() else 0xff277e86.toInt(), .24f * sideTone)
            val socketX = if (pony) .068f else if (lion) .060f else .070f
            val socketY = if (pony) .060f else if (lion) .044f else .054f
            val socketShade = gauss((abs(x) - socketX) / .034f, (y - socketY) / .030f) * facing
            color = mix(color, browColor, .14f * socketShade)
            val blush = (if (pony) .12f else .06f) * facing * gauss((abs(x) - .079f) / .025f, (y + .015f) / .025f)
            color = mix(color, if (pony) 0xffe693bc.toInt() else 0xffe6a18d.toInt(), blush)
            color
        }) { u, v -> skull(longitude(u), (v - .5f) * PI_F) }

        val eyeX = if (pony) .068f else if (lion) .058f else .070f
        val eyeY = if (pony) .060f else if (lion) .044f else .054f
        val eyeW = if (pony) .0245f else if (lion) .032f else .031f
        val eyeH = if (pony) .029f else if (lion) .0255f else .0265f
        val eyeSegments = d + 12
        for (side in listOf(-1f, 1f)) {
            val left = side > 0f
            val blink = w(if (left) emotion.eyeBlinkLeft else emotion.eyeBlinkRight)
            val squint = w(if (left) emotion.eyeSquintLeft else emotion.eyeSquintRight)
            val wide = w(if (left) emotion.eyeWideLeft else emotion.eyeWideRight)
            val cheek = w(if (left) emotion.cheekSquintLeft else emotion.cheekSquintRight)
            val cx = side * eyeX
            val cy = eyeY + cheek * .002f
            val aperture = (eyeH * (1f + wide * .18f) * (1f - blink) * (1f - squint * .35f - cheek * .12f)).coerceAtLeast(.00018f)
            val closed = blink > .97f
            fun lidCenter(nx: Float) = cy + side * nx * (if (pony) .003f else .0018f) + blink * .003f * (1f - nx * nx)
            fun lidSpan(nx: Float) = aperture * max(0f, 1f - nx * nx).pow(if (pony) .62f else .55f)
            val lowerLid = if (pony) .70f else .77f
            fun boundary(a: Float): Pair<Float, Float> {
                val s = sin(a)
                val nx = cos(a)
                return cx + eyeW * nx to lidCenter(nx) + sign(s) * lidSpan(nx) * (if (s > 0f) 1f else lowerLid)
            }
            fun eyeZ(x: Float, y: Float): Float {
                val r = ((x - cx) / eyeW).pow(2) + ((y - cy) / (aperture + .001f)).pow(2)
                return front(x, y) + .0027f + .007f * (1f - r.coerceIn(0f, 1f))
            }
            out.surface = PortraitSurface.EYE
            out.patch(4, eyeSegments, if (closed) skin else 0xfffffbf0.toInt()) { u, v ->
                val edge = boundary(-u * TAU)
                val x = cx + (edge.first - cx) * v
                val y = cy + (edge.second - cy) * v
                V(x, y, eyeZ(x, y))
            }
            val irisX = cx + lookX * .0065f
            val irisY = cy + (.002f + lookY * .005f) * (1f - blink)
            fun disc(radius: Float, color: Int, offset: Float, segments: Int, aspectX: Float = 1f, aspectY: Float = 1f) {
                out.patch(3, segments, if (closed) skin else color) { u, v ->
                    val a = -u * TAU
                    val x = (irisX + radius * cos(a) * v * aspectX).coerceIn(cx - eyeW * .98f, cx + eyeW * .98f)
                    val nx = ((x - cx) / eyeW).coerceIn(-1f, 1f)
                    val center = lidCenter(nx)
                    val span = lidSpan(nx) * .94f
                    val y = (irisY + radius * sin(a) * v * aspectY * (aperture / eyeH).coerceAtMost(1.2f)).coerceIn(center - span * lowerLid, center + span)
                    V(x, y, eyeZ(x, y) + offset)
                }
            }
            disc(.0225f, mix(iris, 0xff243e43.toInt(), .38f), .00035f, 32)
            disc(.0203f, iris, .00070f, 32)
            disc(.0162f, mix(iris, if (pony) 0xffcbb1ed.toInt() else 0xffe2c27b.toInt(), .27f), .00088f, 28)
            disc(.0135f, 0xff172b35.toInt(), .00105f, 28, if (!lion && !pony) .43f else 1f, if (pony) 1.23f else 1f)
            for ((dx, dy, size) in listOf(Triple(-.005f, .007f, .0031f), Triple(.0058f, -.0055f, .00125f))) {
                out.patch(2, 12, if (closed) skin else 0xffffffff.toInt()) { u, v ->
                    val a = -u * TAU
                    val x = irisX + (dx + size * cos(a) * v) * (1f - blink)
                    val y = irisY + (dy + size * sin(a) * v) * (1f - blink)
                    V(x, y, eyeZ(x, y) + .00145f)
                }
            }
            out.surface = PortraitSurface.SKIN
            // The lid rolls continuously down onto the sclera; a restrained upper
            // crease makes the eye readable without a black outlined sticker.
            out.patch(3, eyeSegments, skin, colorAt = { u, v ->
                mix(skin, browColor, (1f - v) * (if (sin(-u * TAU) > 0f) .20f else .07f))
            }) { u, v ->
                val a = -u * TAU
                val edge = boundary(a)
                val x = edge.first + .0033f * cos(a) * v
                val y = edge.second + .0044f * sin(a) * v
                V(x, y, front(x, y) + .0027f + .0018f * sin(v * PI_F))
            }
            out.patch(1, eyeSegments, mix(browColor, skin, .18f)) { u, v ->
                val a = (1f - u) * PI_F
                val edge = boundary(a)
                val thickness = (if (pony) .0026f else .0014f) * sin(u * PI_F).coerceAtLeast(0f).pow(.5f) + .0004f
                V(edge.first, edge.second + (v - .5f) * thickness,
                    front(edge.first, edge.second) + if (closed) .013f else .0035f)
            }
            if (pony) for (lash in 0..1) {
                val a = .18f + lash * .27f
                val edge = boundary(if (side > 0f) a else PI_F - a)
                val x = edge.first
                val y = edge.second
                val tipX = x + side * (.0055f + lash * .001f)
                val tipY = y + .005f + lash * .002f
                out.curvedTube(point(x, y, .0037f), point(x + side * .003f, y + .001f, .004f),
                    point(tipX, tipY - .001f, .0038f), point(tipX, tipY, .003f),
                    .0013f, .0011f, mix(browColor, 0xff3e344e.toInt(), .45f), 6, 8)
            }
            val down = w(if (left) emotion.browDownLeft else emotion.browDownRight)
            val outer = w(if (left) emotion.browOuterUpLeft else emotion.browOuterUpRight)
            val inner = w(emotion.browInnerUp)
            // Fur and scale ridges replace painted human eyebrows.
            out.patch(4, 20, mix(skin, browColor, if (pony) .10f else .18f)) { u, v ->
                val t = u * 2f - 1f
                val outside = (t * side + 1f) * .5f
                val x = cx + eyeW * t
                val y = eyeY + eyeH * .98f + .005f * (1f - t * t) + inner * .007f * (1f - outside) + outer * .007f * outside - down * .006f
                val thickness = (if (pony) .003f else if (lion) .008f else .011f) * sqrt(max(0f, 1f - t * t))
                point(x, y + (v - .5f) * thickness, .003f + (if (pony) .001f else .005f) * sin(v * PI_F))
            }
        }

        if (lion) {
            // A rounded triangular nose, with the broad upper edge nested in the muzzle.
            out.patch(8, 20, 0xff694336.toInt(), surfaceType = PortraitSurface.HORN, colorAt = { _, v -> mix(0xff55352f.toInt(), 0xff8f5e4a.toInt(), v * .45f) }) { u, v ->
                val a = -u * TAU
                val sx = cos(a)
                val sy = sin(a)
                val width = .026f * (.77f + .23f * sy)
                val x = width * sx * v
                val y = -.031f + .014f * sy * v
                point(x, y, .003f + .008f * sqrt(max(0f, 1f - v * v)))
            }
            out.patch(1, 12, 0xff9c7758.toInt()) { u, v ->
                val x = (u - .5f) * .0014f
                val y = -.043f - v * .027f
                point(x, y, .0018f)
            }
            for (side in listOf(-1f, 1f)) for (dot in 0..2) {
                val x = side * (.031f + (dot % 2) * .014f)
                val y = -.052f - (dot / 2) * .012f
                out.frontDisc(x, y, .0015f, .0011f, 0xff9f7a55.toInt(), { px, py -> point(px, py, .0017f) })
            }
            for (side in listOf(-1f, 1f)) for (whisker in 0..2) {
                val y = -.052f - whisker * .010f
                val endY = y + .012f - whisker * .012f
                out.curvedTube(point(side * .045f, y, .003f), point(side * .067f, y + .004f, .009f),
                    V(side * .094f, endY, .128f), V(side * .112f, endY, .105f),
                    .00065f, .00065f, 0xffffe6bd.toInt(), 8, 8)
            }
        } else {
            for (side in listOf(-1f, 1f)) {
                val nx = if (pony) .047f else .054f
                val ny = if (pony) -.111f else -.032f
                val nostril = if (customSkin) mix(skin, 0xff333643.toInt(), .75f) else if (pony) 0xff815c77.toInt() else 0xff2c6459.toInt()
                out.patch(4, 20, nostril) { u, v ->
                    val a = -u * TAU
                    val px = side * nx + (if (pony) .009f else .008f) * cos(a) * v
                    val py = ny + (if (pony) .013f else .0045f) * sin(a) * v + side * (if (pony) .36f else -.28f) * (px - side * nx)
                    point(px, py, .0022f + .0015f * (1f - v))
                }
            }
        }

        out.surface = PortraitSurface.MOUTH
        val mouthWidth = (if (pony) .052f else if (lion) .061f else .084f) * (1f - pucker * .23f)
        val mouthDark = if (customSkin) mix(skin, 0xff302e3b.toInt(), .74f)
            else if (pony) 0xff76516c.toInt() else if (lion) 0xff613d32.toInt() else 0xff325f55.toInt()
        val lip = mix(if (lion) skin else muzzleColor, mouthDark, .15f)
        fun mouth(t: Float, upper: Boolean): Pair<Float, Float> {
            val smile = if (t >= 0f) smileL else smileR
            val frown = if (t >= 0f) frownL else frownR
            val span = sqrt(max(0f, 1f - t * t))
            val felineSplit = if (lion) .008f * exp(-(t / .20f).pow(2)) else 0f
            val y = mouthY + felineSplit + ((if (pony) .003f else .009f) + smile * .010f - frown * .012f) * abs(t).pow(1.8f) - jaw * .0015f
            return t * mouthWidth to if (upper) y + (.0006f + jaw * .0035f + pucker * .004f) * span
                else y - (.0010f + jaw * .027f + pucker * .008f) * span
        }
        fun mouthPoint(x: Float, y: Float, offset: Float): V = point(x, y, offset + pucker * .008f)
        out.patch(5, d + 12, mouthDark) { u, v ->
            val t = u * 2f - 1f
            val top = mouth(t, true)
            val bottom = mouth(t, false)
            mouthPoint(top.first, bottom.second + (top.second - bottom.second) * v, .0020f)
        }
        for (upper in listOf(true, false)) out.patch(3, d + 12, lip) { u, rawV ->
            val v = if (upper) rawV else 1f - rawV
            val t = u * 2f - 1f
            val edge = mouth(t, upper)
            val thickness = (if (upper) .0012f else .0020f) * sqrt(max(0f, 1f - t * t))
            val y = edge.second + (if (upper) 1f else -1f) * thickness * v
            mouthPoint(edge.first, y, .0025f + .0015f * sin(v * PI_F))
        }
        out.patch(2, d + 4, if (jaw > .12f) 0xfffff4da.toInt() else mouthDark) { u, rawV ->
            val v = 1f - rawV
            val t = (u * 2f - 1f) * .73f
            val top = mouth(t, true)
            val bottom = mouth(t, false)
            val y = top.second - .00035f - min(.0055f, (top.second - bottom.second) * .27f) * v
            mouthPoint(top.first, y, .0029f)
        }
        out.patch(2, d, if (jaw > .30f) 0xffdc959c.toInt() else mouthDark) { u, v ->
            val t = (u * 2f - 1f) * .55f
            val edge = mouth(t, false)
            mouthPoint(edge.first, edge.second + (.0008f + jaw * .0035f) * v, .0028f)
        }
        if (!pony) for (side in listOf(-1f, 1f)) {
            val top = mouth(side * .64f, true)
            val visible = smooth(jaw / .18f)
            out.curvedTube(mouthPoint(top.first, top.second, .0036f), mouthPoint(top.first, top.second - .003f * visible, .005f),
                mouthPoint(top.first - side * .002f, top.second - .010f * visible, .005f),
                mouthPoint(top.first - side * .003f, top.second - .014f * visible, .003f),
                .0038f * visible, .003f * visible, 0xfffff4da.toInt(), 8, 10)
        }

        if (pony) {
            out.surface = PortraitSurface.HORN
            val pastels = intArrayOf(0xffc3a5df.toInt(), 0xffe6a9ce.toInt(), 0xff9bcfd5.toInt(), 0xffafc6e7.toInt())
            out.curvedTube(V(0f, .111f, .025f), V(0f, .159f, .039f), V(.002f, .207f, .035f), V(.005f, .242f, .025f),
                .014f, .014f, 0xffffd59b.toInt(), 44, 20,
                spiral = .10f, colorAt = { u, v -> mix(0xffffd59b.toInt(), 0xffffecc5.toInt(), .35f + .16f * sin(u * TAU - v * 31f)) })
            if (hairEnabled) {
                out.surface = PortraitSurface.HAIR
                // One continuous rear cap supports three long swept forelocks.
                out.patch(10, 44, pastels[0], colorAt = { u, _ ->
                    val position = u * (pastels.size - 1)
                    val band = position.toInt().coerceAtMost(pastels.lastIndex - 1)
                    mix(pastels[band], pastels[band + 1], position - band)
                }) { u, v ->
                    val lon = (u * 2f - 1f) * PI_F
                    val edge = .18f + .62f * max(0f, cos(lon))
                    val lat = edge + (PI_F / 2f - edge) * v
                    val p = skull(lon, lat)
                    V(p.x + .008f * cos(lat) * sin(lon), p.y + .008f * sin(lat), p.z + .010f * cos(lat) * cos(lon))
                }
                for (lock in 0..2) {
                    val offset = lock * .014f
                    out.curvedTube(V(.055f - offset, .094f, -.020f), V(.025f - offset, .157f, .036f),
                        V(-.052f - offset * .25f, .126f - offset * .45f, .078f), V(-.084f - offset * .10f, .078f - offset * .45f, .053f),
                        .021f, .015f, pastels[lock], 18, 14, soft = true, fluting = .035f,
                        colorAt = { u, v -> mix(pastels[lock], 0xffffe4f4.toInt(), .22f * sin(v * PI_F).coerceAtLeast(0f) * (.5f + .5f * cos(u * TAU * 2f + v * 3f))) })
                }
                for (lock in 0..1) {
                    val offset = lock * .014f
                    out.curvedTube(V(-.060f - offset * .35f, .083f, -.036f), V(-.146f - offset * .25f, .064f, -.031f),
                        V(-.139f - offset * .45f, -.084f, .005f), V(-.079f - offset * .35f, -.110f, .025f),
                        .030f, .023f, pastels[lock + 1], 24, 16, soft = true, fluting = .035f,
                        colorAt = { u, v -> mix(pastels[lock + 1], 0xffffe4f4.toInt(), .24f * sin(v * PI_F).coerceAtLeast(0f) * (.5f + .5f * cos(u * TAU * 3f + v * 3f))) })
                }
                out.curvedTube(V(.074f, .076f, -.037f), V(.125f, .042f, -.040f),
                    V(.123f, -.067f, -.024f), V(.086f, -.091f, -.005f),
                    .022f, .018f, pastels[3], 24, 16, soft = true, fluting = .035f,
                    colorAt = { u, v -> mix(pastels[3], 0xffffe4f4.toInt(), .22f * sin(v * PI_F).coerceAtLeast(0f) * (.5f + .5f * cos(u * TAU * 3f + v * 3f))) })
            }
        } else if (!lion) {
            out.surface = PortraitSurface.SKIN
            // Small pearl scales curve with the forehead, rather than sitting as
            // separate spheres. Their light centers give the dragon a soft crest.
            for (scale in 0..2) {
                val x = (scale - 1) * .013f
                val y = .084f - abs(scale - 1) * .004f
                out.patch(3, 16, mix(skin, 0xffd4efca.toInt(), .38f)) { u, v ->
                    val a = -u * TAU
                    val px = x + .0045f * cos(a) * v
                    val py = y + .006f * sin(a) * v
                    point(px, py, .001f + .0015f * (1f - v * v))
                }
            }
            // Small shield-shaped scales follow the broad snout and cheeks.
            for (side in listOf(-1f, 1f)) for (row in 0..1) for (column in 0..1) {
                val x = side * (.082f + column * .014f - row * .002f)
                val y = .012f - row * .021f + column * .005f
                out.patch(4, 18, mix(skin, 0xffb6d9b4.toInt(), .16f), colorAt = { _, v -> mix(skin, 0xffb6d9b4.toInt(), .06f + .14f * (1f - v)) }) { u, v ->
                    val a = -u * TAU
                    val px = x + .0075f * cos(a) * v * (.78f + .22f * sin(a))
                    val py = y + .010f * sin(a) * v
                    point(px, py, .0004f + .0017f * (1f - v * v))
                }
            }
            // Swept dorsal blades form a reptilian crest along the sagittal ridge.
            for (ridge in 0..4) out.curvedTube(V(0f, .104f - ridge * .012f, .018f - ridge * .032f),
                V(0f, .145f - ridge * .012f, .020f - ridge * .032f),
                V(0f, .177f - ridge * .014f, -.002f - ridge * .032f),
                V(0f, .175f - ridge * .016f, -.025f - ridge * .032f),
                .018f - ridge * .0018f, .013f, 0xffe1ca87.toInt(), 12, 12, surfaceType = PortraitSurface.HORN)
        }
        return out.geometry()
    }

    private data class Section(val y: Float, val width: Float, val front: Float, val back: Float)

    /** Cubic interpolation keeps the forehead, bridge, muzzle and jaw continuous. */
    private fun sample(profile: Array<Section>, requestedY: Float): Section {
        val y = requestedY.coerceIn(profile.first().y, profile.last().y)
        val index = (0 until profile.lastIndex).firstOrNull { y <= profile[it + 1].y } ?: profile.lastIndex - 1
        val a = profile[index]; val b = profile[index + 1]
        val before = profile[max(0, index - 1)]; val after = profile[min(profile.lastIndex, index + 2)]
        val distance = b.y - a.y
        val t = (y - a.y) / distance
        val t2 = t * t; val t3 = t2 * t
        fun channel(value: (Section) -> Float): Float {
            val slopeA = (value(b) - value(before)) / (b.y - before.y)
            val slopeB = (value(after) - value(a)) / (after.y - a.y)
            return (2f * t3 - 3f * t2 + 1f) * value(a) + (t3 - 2f * t2 + t) * distance * slopeA +
                (-2f * t3 + 3f * t2) * value(b) + (t3 - t2) * distance * slopeB
        }
        return Section(y, channel { it.width }.coerceAtLeast(0f), channel { it.front }, channel { it.back })
    }

    private data class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(other: V) = V(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: V) = V(x - other.x, y - other.y, z - other.z)
        operator fun times(s: Float) = V(x * s, y * s, z * s)
        fun cross(v: V) = V(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
        fun unit(fallback: V = V(0f, 0f, 1f)): V {
            val length = sqrt(x * x + y * y + z * z)
            return if (length > 1e-12f && length.isFinite()) this * (1f / length) else fallback
        }
    }

    private class Builder {
        var surface = PortraitSurface.SKIN
        private var positions = FloatArray(21000)
        private var normals = FloatArray(21000)
        private var colors = IntArray(7000)
        private var surfaces = IntArray(7000)
        private var indices = IntArray(42000)
        private var vertices = 0
        private var indexCount = 0

        fun patch(rows: Int, columns: Int, color: Int, colorAt: ((Float, Float) -> Int)? = null,
                  surfaceType: Int = this.surface, surface: (Float, Float) -> V) {
            val count = (rows + 1) * (columns + 1)
            if (vertices + count > colors.size) {
                val capacity = max(colors.size * 2, vertices + count)
                positions = positions.copyOf(capacity * 3)
                normals = normals.copyOf(capacity * 3)
                colors = colors.copyOf(capacity)
                surfaces = surfaces.copyOf(capacity)
            }
            val addedIndices = rows * columns * 6
            if (indexCount + addedIndices > indices.size) indices = indices.copyOf(max(indices.size * 2, indexCount + addedIndices))
            val start = vertices
            for (r in 0..rows) for (c in 0..columns) {
                val u = c.toFloat() / columns
                val v = r.toFloat() / rows
                val p = surface(u, v)
                val du = surface((u + .0003f).coerceAtMost(1f), v) - surface((u - .0003f).coerceAtLeast(0f), v)
                val dv = surface(u, (v + .0003f).coerceAtMost(1f)) - surface(u, (v - .0003f).coerceAtLeast(0f))
                val n = du.cross(dv).unit()
                val offset = vertices * 3
                positions[offset] = p.x; positions[offset + 1] = p.y; positions[offset + 2] = p.z
                normals[offset] = n.x; normals[offset + 1] = n.y; normals[offset + 2] = n.z
                surfaces[vertices] = surfaceType
                colors[vertices++] = (colorAt?.invoke(u, v) ?: color) or (0xff shl 24)
            }
            for (r in 0 until rows) for (c in 0 until columns) {
                val a = start + r * (columns + 1) + c
                val b = a + columns + 1
                indices[indexCount++] = a; indices[indexCount++] = a + 1; indices[indexCount++] = b
                indices[indexCount++] = a + 1; indices[indexCount++] = b + 1; indices[indexCount++] = b
            }
        }

        fun ellipsoid(center: V, radius: V, color: Int, rows: Int, columns: Int) {
            patch(rows, columns, color) { u, v ->
                val lat = (v - .5f) * PI_F
                val lon = (u * 2f - 1f) * PI_F
                V(center.x + radius.x * cos(lat) * sin(lon), center.y + radius.y * sin(lat), center.z + radius.z * cos(lat) * cos(lon))
            }
        }

        fun frontDisc(x: Float, y: Float, rx: Float, ry: Float, color: Int, surface: (Float, Float) -> V) {
            patch(2, 12, color) { u, v ->
                val a = -u * TAU
                surface(x + rx * cos(a) * v, y + ry * sin(a) * v)
            }
        }

        /** Rounded swept volume with a closed base and gently tapered soft tip. */
        fun curvedTube(a: V, b: V, c: V, d: V, width: Float, depth: Float, color: Int,
                       rows: Int, columns: Int, colorAt: ((Float, Float) -> Int)? = null, soft: Boolean = false,
                       surfaceType: Int = surface, fluting: Float = 0f, spiral: Float = 0f) {
            fun curve(t: Float): V {
                val s = 1f - t
                return a * (s * s * s) + b * (3f * s * s * t) + c * (3f * s * t * t) + d * (t * t * t)
            }
            patch(rows, columns, color, colorAt, surfaceType) { u, v ->
                val center = curve(v)
                val tangent = (curve((v + .001f).coerceAtMost(1f)) - curve((v - .001f).coerceAtLeast(0f))).unit(V(0f, 1f, 0f))
                val side = V(tangent.y, -tangent.x, 0f).unit(V(1f, 0f, 0f))
                val back = side.cross(tangent).unit()
                val radius = if (soft) sin(PI_F * v).coerceAtLeast(0f).pow(.35f) * (1f - .20f * v)
                    else sin(PI_F * (.055f + .945f * v)).coerceAtLeast(0f).pow(.58f) * (1f - .48f * v)
                val angle = -u * TAU
                val sculpt = 1f + fluting * cos(angle * 5f + v * 4f) + spiral * cos(angle - v * TAU * 5f)
                center + side * (width * radius * sculpt * cos(angle)) + back * (depth * radius * sculpt * sin(angle))
            }
        }

        fun geometry() = PortraitGeometry(positions.copyOf(vertices * 3), normals.copyOf(vertices * 3), colors.copyOf(vertices), indices.copyOf(indexCount), surfaces.copyOf(vertices))
    }

    private const val PI_F = 3.1415927f
    private const val TAU = PI_F * 2f
    private fun finite(v: Float) = if (v.isFinite()) v else 0f
    private fun w(v: Float) = finite(v).coerceIn(0f, 1f)
    private fun gauss(x: Float, y: Float) = exp(-x * x - y * y)
    private fun smooth(v: Float): Float { val t = v.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun mix(a: Int, b: Int, value: Float): Int {
        val t = value.coerceIn(0f, 1f)
        fun channel(shift: Int) = (((a ushr shift) and 255) * (1f - t) + ((b ushr shift) and 255) * t).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
