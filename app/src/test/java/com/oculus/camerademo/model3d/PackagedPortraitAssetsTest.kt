package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import com.oculus.camerademo.renderer.PortraitDepthBuffer
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Checks the actual distributable artist assets, not miniature codec fixtures. */
class PackagedPortraitAssetsTest {
    private lateinit var assets: File
    private val rain = PortraitStyle(0xffe1af91.toInt())
    private val snow = PortraitStyle(0xffdaa884.toInt(), variant = "bearded")
    private val mesh = PortraitMesh()

    @Before fun loadPackagedAssets() {
        assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { File(it, "portraits/rain.aeph").isFile }
            ?: error("Packaged Rain head missing")
        assertTrue("Packaged Snow head missing", File(assets, "portraits/snow.aeph").isFile)
        AuthoredPortraitStore.installDirectory(assets.path)
    }

    @After fun clearSource() = AuthoredPortraitStore.clear()

    @Test fun bothAuthoredModelsLoadTheirDeclaredTopologyAndPaintedColors() {
        for ((name, style) in listOf("rain" to rain, "snow" to snow)) {
            DataInputStream(GZIPInputStream(File(assets, "portraits/$name.aeph").inputStream())).use { input ->
                val magic = ByteArray(8); input.readFully(magic)
                assertEquals("AEPH0001", String(magic, Charsets.US_ASCII))
                val vertices = input.readInt(); val indices = input.readInt(); val morphs = input.readInt()
                assertTrue("$name optimized artist mesh", vertices in 10_000..100_000)
                assertTrue("$name has a facial rig", morphs >= 25)
                val geometry = mesh.build(style)
                assertEquals("$name must not silently use procedural fallback", vertices, geometry.colors.size)
                assertEquals(indices, geometry.indices.size)
                assertTrue("$name preserves painted material variation", geometry.colors.toSet().size > 128)
                assertTrue(geometry.indices.all { it in geometry.colors.indices })
                assertTrue(geometry.surfaces.all { it in 0 until PortraitSurface.COUNT })
                assertTrue(geometry.positions.all { it.isFinite() && abs(it) < .35f })
            }
        }
        assertFalse("The two source characters retain distinct geometry",
            mesh.build(rain).positions.contentEquals(mesh.build(snow).positions))
    }

    @Test fun bothCharactersHaveActiveIndependentExpressionsAndGaze() {
        val expressions = listOf(
            "left blink" to FaceEmotionState(eyeBlinkLeft = 1f),
            "right blink" to FaceEmotionState(eyeBlinkRight = 1f),
            "jaw" to FaceEmotionState(jawOpen = 1f),
            "left smile" to FaceEmotionState(mouthSmileLeft = 1f),
            "right smile" to FaceEmotionState(mouthSmileRight = 1f),
            "pucker" to FaceEmotionState(mouthPucker = 1f),
            "brow" to FaceEmotionState(browInnerUp = 1f))
        for (style in listOf(rain, snow)) {
            val neutral = mesh.build(style)
            for ((name, expression) in expressions) {
                val posed = mesh.build(style, expression)
                assertArrayEquals(neutral.indices, posed.indices)
                assertArrayEquals(neutral.surfaces, posed.surfaces)
                assertTrue("${style.variant} $name must move the sampled rig", maxDelta(neutral, posed) > .0001f)
            }
            for ((x, y) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f))
                assertTrue("${style.variant} gaze $x,$y must move the authored eyes",
                    maxDelta(neutral, mesh.build(style, gazeX = x, gazeY = y)) > .0001f)
            val left = mesh.build(style, FaceEmotionState(eyeBlinkLeft = 1f))
            val right = mesh.build(style, FaceEmotionState(eyeBlinkRight = 1f))
            assertFalse("Lids must remain independently controlled", left.positions.contentEquals(right.positions))
        }
    }

    @Test fun closedLidsOccludeTheActualEyeSurfacesInTheSharedRasterizer() {
        for (style in listOf(rain, snow)) {
            val neutral = visibleEyes(mesh.build(style, hairEnabled = false))
            val closed = visibleEyes(mesh.build(style,
                FaceEmotionState(eyeBlinkLeft = 1f, eyeBlinkRight = 1f), hairEnabled = false))
            assertTrue("${style.variant} eye surfaces visible at neutral ($neutral)", neutral > 50)
            assertTrue("${style.variant} closed lids must hide eyes ($closed / $neutral)", closed < neutral * .65f)
        }
    }

    @Test fun extremeCombinedTrackingProducesFiniteNormalizedStableGeometry() {
        val stress = FaceEmotionState(eyeBlinkLeft = 1f, eyeBlinkRight = .8f,
            eyeSquintLeft = 1f, eyeSquintRight = 1f, jawOpen = 1f,
            mouthSmileLeft = 1f, mouthSmileRight = 1f, mouthPucker = 1f, mouthFunnel = 1f,
            browInnerUp = 1f, browDownLeft = 1f, browDownRight = 1f, cheekPuff = 1f)
        for (style in listOf(rain, snow)) {
            val neutral = mesh.build(style)
            val posed = mesh.build(style, stress, gazeX = -1f, gazeY = 1f)
            assertArrayEquals(neutral.indices, posed.indices)
            assertArrayEquals(neutral.colors, posed.colors)
            assertTrue(posed.positions.all { it.isFinite() && abs(it) < .4f })
            for (at in posed.normals.indices step 3) {
                val length = sqrt(posed.normals[at] * posed.normals[at] + posed.normals[at + 1] * posed.normals[at + 1] + posed.normals[at + 2] * posed.normals[at + 2])
                assertEquals(1f, length, .0001f)
            }
        }
    }

    @Test fun themedPresetsReuseTheAuthoredModelsAndHairSwitchRetainsBrowsAndLashes() {
        val rainBase = mesh.build(rain); val snowBase = mesh.build(snow)
        for ((style, source) in listOf(
            rain.copy(variant = "holo") to rainBase,
            snow.copy(variant = "gold", metallic = true) to snowBase,
            snow.copy(variant = "cyber") to snowBase)) {
            val themed = mesh.build(style)
            assertArrayEquals(source.positions, themed.positions, 0f)
            assertArrayEquals(source.indices, themed.indices)
            assertFalse(source.colors.contentEquals(themed.colors))
        }
        for (style in listOf(rain, snow)) {
            val full = mesh.build(style); val bare = mesh.build(style, hairEnabled = false)
            assertTrue(bare.indices.size < full.indices.size)
            assertArrayEquals(full.positions, bare.positions, 0f)
            assertTrue("Authored brows and lashes survive the hair switch",
                bare.indices.any { bare.surfaces[it] == PortraitSurface.HAIR })
        }
    }

    private fun maxDelta(a: PortraitGeometry, b: PortraitGeometry) =
        a.positions.indices.maxOf { abs(a.positions[it] - b.positions[it]) }

    private fun visibleEyes(face: PortraitGeometry): Int {
        val buffer = PortraitDepthBuffer().apply { begin(400, 400) }
        val coordinates = FloatArray(face.colors.size * 2)
        val depths = FloatArray(face.colors.size)
        val colors = IntArray(face.colors.size)
        for (vertex in face.colors.indices) {
            coordinates[vertex * 2] = 200f + face.positions[vertex * 3] * 1000f
            coordinates[vertex * 2 + 1] = 220f - face.positions[vertex * 3 + 1] * 1000f
            depths[vertex] = face.positions[vertex * 3 + 2]
            colors[vertex] = if (face.surfaces[vertex] == PortraitSurface.EYE) -1 else 0xff000000.toInt()
        }
        buffer.draw(coordinates, depths, colors, face.indices)
        var visible = 0
        // Omit the lower face: the teeth use the same glossy material category.
        for (y in 0 until 200) for (x in 0 until 400) if (buffer.pixels[y * 400 + x] == -1) visible++
        return visible
    }
}
