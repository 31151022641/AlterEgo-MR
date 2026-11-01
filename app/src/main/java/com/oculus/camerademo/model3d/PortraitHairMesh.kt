package com.oculus.camerademo.model3d

import kotlin.math.*

/** Static sculpted hairstyles. Cached independently of face tracking deformation. */
internal object PortraitHairMesh {
    private data class Key(val color: Int, val kind: String, val detail: Int)
    private val cache = object : LinkedHashMap<Key, PortraitGeometry>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PortraitGeometry>?) = size > 24
    }

    @Synchronized fun build(color: Int, kind: String, detail: Int): PortraitGeometry {
        val key = Key(color, kind, detail.coerceIn(12, 24))
        return cache.getOrPut(key) { sculpt(key) }
    }

    private fun sculpt(key: Key): PortraitGeometry {
        val out = Builder()
        val bob = key.kind == "classic"
        val braid = key.kind == "holo"
        val short = key.kind == "bearded"
        val gold = key.kind == "gold"
        val robot = key.kind == "cyber"
        val color = if (robot) 0xffaec8d7.toInt() else key.color
        val highlight = if (gold) 0xffffdfa1.toInt() else if (braid) 0xff64bad1.toInt() else 0xff9a7e6c.toInt()
        fun finish(u: Float, v: Float): Int = mix(color, highlight,
            .02f + .10f * sin(v * PI_F).coerceAtLeast(0f) * (.5f + .5f * cos(u * TAU * 2f + v * 3f)))
        fun line(lon: Float): Float {
            val facing = max(0f, cos(lon))
            val back = max(0f, -cos(lon))
            val y = when {
                robot -> .030f + .058f * facing - .055f * back
                braid -> .020f + .070f * facing.pow(1.5f) - .045f * back - .014f * exp(-((lon + .5f) / .45f).pow(2))
                short -> .024f + .066f * facing.pow(1.5f) - .055f * back
                else -> .018f + .073f * facing.pow(1.5f) - .055f * back + .007f * sin(lon * 2f) * facing
            }
            return asin(((y - .010f) / .128f).coerceIn(-.99f, .99f))
        }
        fun cap(lon: Float, lat: Float, lift: Float = .007f): V {
            val y = .010f + .128f * sin(lat)
            val t = sin(lat)
            val cheeks = 1f + .070f * exp(-((t + .12f) / .34f).pow(2))
            val width = .119f * max(0f, 1f - t * t).pow(.46f) * cheeks
            val crown = max(0f, sin(lat))
            val thickness = lift + (if (robot) .003f else if (short) .010f else .017f) * crown
            return V((width + thickness * cos(lat)) * sin(lon), y + thickness * sin(lat),
                -.008f + (if (cos(lon) > 0f) .085f else .092f) * cos(lat) * cos(lon) + thickness * cos(lat) * cos(lon))
        }

        // A rear mass supports every curl and keeps the hairstyle full in profile.
        if (bob) out.patch(12, 32, color, ::finish) { u, v ->
            val lon = (u * 2f - 1f) * PI_F
            val lat = (v - .5f) * PI_F
            val wave = .006f * (.5f + .5f * cos(lon * 14f + lat * 13f)) * cos(lat)
            V((.136f + wave) * cos(lat) * sin(lon), .005f + (.145f + wave) * sin(lat),
                -.090f + (.055f + wave) * cos(lat) * cos(lon))
        }
        out.material = if (robot) PortraitSurface.HORN else PortraitSurface.HAIR
        out.patch(if (bob) 20 else 10 + key.detail / 6, if (bob) 51 else 32 + key.detail / 2, color, { u, v ->
            if (robot) mix(color, 0xffedf8ff.toInt(), v * .12f) else finish(u, v)
        }) { u, v ->
            val lon = (u * 2f - 1f) * PI_F
            val edge = line(lon)
            val lat = edge + (PI_F / 2f - edge) * v
            var ridges = if (robot) 0f else .003f * sin(v * PI_F) * (.5f + .5f * cos(lon * 18f + v * 6f))
            if (bob) {
                for (curl in 0..7) {
                    val curlLon = -1.10f + curl * .32f
                    val curlLat = 1.06f + (curl % 2) * .11f
                    ridges += .014f * exp(-((lon - curlLon) / .19f).pow(2) - ((lat - curlLat) / .24f).pow(2))
                }
                ridges += .004f * sin(v * PI_F) * (.5f + .5f * cos(lon * 9f + lat * 15f))
            }
            val quiff = if (short) .014f * exp(-((lon + .35f) / .85f).pow(2) - ((lat - 1.08f) / .42f).pow(2)) * sin(v * PI_F) else 0f
            val p = cap(lon, lat, .007f + ridges + quiff)
            V(p.x - quiff * .35f, p.y + quiff * .45f, p.z + quiff * .30f)
        }

        if (robot) return out.geometry()
        // Curls are continuous coiled locks with fine longitudinal channels.
        // Their roots overlap the cap; tapered ends finish below the jaw.
        if (bob) {
            for (side in listOf(-1f, 1f)) for (lock in 0..4) {
                val root = cap(side * (.60f + lock * .27f), 1.20f - lock * .10f, .014f)
                val length = .248f - lock * .008f
                val phase = lock * 1.73f + side * .4f
                out.lock(27, 8, color, ::finish, .019f, .017f, .055f) { t ->
                    val bend = smooth(t / .36f)
                    val coil = t * TAU * (2.50f + lock * .09f) + phase
                    val amplitude = .0065f * sin(t * PI_F).coerceAtLeast(0f).pow(.35f)
                    V(root.x * (1f - bend) + side * (.123f + lock * .006f) * bend + amplitude * cos(coil),
                        root.y - length * t,
                        root.z * (1f - bend) + (.021f - lock * .024f) * bend + amplitude * sin(coil))
                }
            }
        }
        if (gold || short || braid) {
            // Swept ridges follow the hair flow rather than separate hair balls.
            for (lock in 0..6) {
                val lon = -.90f + lock * .24f
                out.lock(18, 8, color, ::finish, if (gold) .014f else .017f, .009f, .055f) { t ->
                    cap(lon + .70f - 1.10f * t, 1.27f - .52f * t + .22f * sin(t * PI_F),
                        .001f + (if (short) .025f else .017f) * sin(t * PI_F))
                }
            }
        }
        if (braid) {
            for (strand in 0..2) {
                val phase = strand * TAU / 3f
                out.patch(48, 8, color, ::finish) { u, v ->
                    val a = -u * TAU
                    val wave = phase + v * TAU * 3.5f
                    val taper = (1f - v.pow(7)).coerceAtLeast(0f).pow(.35f)
                    val x = .114f + .030f * sin(v * PI_F) + .010f * cos(wave) * taper
                    val y = .103f - .247f * v
                    val z = -.035f + .023f * v + .010f * sin(wave) * taper
                    V(x + .011f * taper * cos(a), y, z + .011f * taper * sin(a))
                }
            }
            out.tube(cap(-.90f, 1.18f), cap(-1.1f, .88f, .035f),
                V(-.121f, .014f, -.010f), V(-.113f, -.041f, -.025f),
                .019f, .017f, 14, 10, color, ::finish, .04f)
        }
        return out.geometry()
    }

    private data class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(v: V) = V(x + v.x, y + v.y, z + v.z)
        operator fun minus(v: V) = V(x - v.x, y - v.y, z - v.z)
        operator fun times(s: Float) = V(x * s, y * s, z * s)
        fun cross(v: V) = V(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
        fun unit(fallback: V = V(0f, 0f, 1f)): V {
            val length = sqrt(x * x + y * y + z * z)
            return if (length.isFinite() && length > 1e-12f) this * (1f / length) else fallback
        }
    }

    private class Builder {
        var material = PortraitSurface.HAIR
        private val points = ArrayList<V>()
        private val normals = ArrayList<V>()
        private val colors = ArrayList<Int>()
        private val materials = ArrayList<Int>()
        private val indices = ArrayList<Int>()

        fun patch(rows: Int, columns: Int, color: Int, colorAt: ((Float, Float) -> Int)?, shape: (Float, Float) -> V) {
            val start = points.size
            for (r in 0..rows) for (c in 0..columns) {
                val u = c.toFloat() / columns; val v = r.toFloat() / rows
                val p = shape(u, v)
                val du = shape((u + .0003f).coerceAtMost(1f), v) - shape((u - .0003f).coerceAtLeast(0f), v)
                val dv = shape(u, (v + .0003f).coerceAtMost(1f)) - shape(u, (v - .0003f).coerceAtLeast(0f))
                points += p; normals += du.cross(dv).unit()
                colors += (colorAt?.invoke(u, v) ?: color) or (0xff shl 24); materials += material
            }
            for (r in 0 until rows) for (c in 0 until columns) {
                val a = start + r * (columns + 1) + c; val b = a + columns + 1
                indices.addAll(listOf(a, a + 1, b, a + 1, b + 1, b))
            }
        }

        fun tube(a: V, b: V, c: V, d: V, width: Float, depth: Float, rows: Int, columns: Int,
                 color: Int, colorAt: (Float, Float) -> Int, curl: Float) {
            fun curve(t: Float): V {
                val s = 1f - t
                return a * (s * s * s) + b * (3f * s * s * t) + c * (3f * s * t * t) + d * (t * t * t)
            }
            lock(rows, columns, color, colorAt, width, depth, curl, ::curve)
        }

        fun lock(rows: Int, columns: Int, color: Int, colorAt: (Float, Float) -> Int,
                 width: Float, depth: Float, fluting: Float, curve: (Float) -> V) {
            patch(rows, columns, color, colorAt) { u, v ->
                val p = curve(v)
                val tangent = (curve((v + .001f).coerceAtMost(1f)) - curve((v - .001f).coerceAtLeast(0f))).unit(V(0f, -1f, 0f))
                val side = V(tangent.y, -tangent.x, 0f).unit(V(1f, 0f, 0f))
                val back = side.cross(tangent).unit()
                val angle = -u * TAU
                val end = (min(v, 1f - v) / .10f).coerceIn(0f, 1f)
                val cap = sqrt(max(0f, 1f - (1f - end).pow(2)))
                val radius = cap * (.86f + .14f * sin(v * PI_F)) *
                    (1f + fluting * cos(v * TAU * 1.5f + angle * 5f))
                p + side * (width * radius * cos(angle)) + back * (depth * radius * sin(angle))
            }
        }

        fun geometry(): PortraitGeometry {
            fun packed(v: List<V>) = FloatArray(v.size * 3) { i -> when (i % 3) { 0 -> v[i / 3].x; 1 -> v[i / 3].y; else -> v[i / 3].z } }
            return PortraitGeometry(packed(points), packed(normals), colors.toIntArray(), indices.toIntArray(), materials.toIntArray())
        }
    }

    private const val PI_F = 3.1415927f
    private const val TAU = PI_F * 2f
    private fun smooth(value: Float): Float { val t = value.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun c(s: Int) = (((a ushr s) and 255) * (1f - t) + ((b ushr s) and 255) * t).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
}
