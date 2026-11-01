package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AuthoredPortraitStoreTest {
    private val rainStyle = PortraitStyle(0xffe1af91.toInt())
    private val snowStyle = PortraitStyle(0xffdaa884.toInt(), variant = "bearded")
    private val mesh = PortraitMesh()
    private data class Delta(val vertex: Int, val values: FloatArray)
    private data class Pose(val name: String, val entries: List<Delta>)

    @After fun clearSource() = AuthoredPortraitStore.clear()

    private fun fixture(poses: List<Pose> = emptyList(), indices: IntArray = intArrayOf(0, 1, 2, 3, 4, 5),
        badPosition: Float? = null, surface: Int = PortraitSurface.SKIN, removableHair: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeBytes("AEPH0001"); out.writeInt(6); out.writeInt(indices.size); out.writeInt(poses.size)
            floatArrayOf(-.02f, 0f, .05f, .02f, 0f, .05f, 0f, .03f, .05f,
                -.02f, .04f, .04f, .02f, .04f, .04f, 0f, .06f, .04f)
                .forEachIndexed { i, value -> out.writeFloat(if (i == 0) badPosition ?: value else value) }
            repeat(6) { out.writeFloat(0f); out.writeFloat(0f); out.writeFloat(1f) }
            repeat(6) { out.writeInt(if (it < 3) 0xffb27c68.toInt() else 0xff30251d.toInt()) }
            repeat(6) { out.writeByte(if (it < 3) surface else PortraitSurface.HAIR or (if (removableHair) 0x80 else 0)) }
            indices.forEach(out::writeInt)
            poses.forEach { pose ->
                val name = pose.name.toByteArray(Charsets.UTF_8)
                out.writeShort(name.size); out.write(name); out.writeInt(pose.entries.size)
                pose.entries.forEach { entry -> out.writeInt(entry.vertex); entry.values.forEach(out::writeFloat) }
            }
        }
        return bytes.toByteArray()
    }

    private fun delta(vertex: Int, dx: Float = 0f, dy: Float = 0f, dz: Float = 0f,
        nx: Float = 0f, ny: Float = 0f, nz: Float = 0f) = Delta(vertex, floatArrayOf(dx, dy, dz, nx, ny, nz))

    private fun install(bytes: ByteArray) = AuthoredPortraitStore.installLoader { ByteArrayInputStream(bytes) }

    @Test fun sparseExpressionsIndependentGazeAndProductCorrectivesUseActualRigDeltas() {
        install(fixture(listOf(
            Pose("eyeBlinkLeft", listOf(delta(0, dy = -.01f))),
            Pose("jawOpen", listOf(delta(1, dy = -.03f, nx = .5f))),
            Pose("gazeXNegative", listOf(delta(2, dx = -.004f))),
            Pose("jawOpen*mouthSmileLeft", listOf(delta(1, dz = .008f))))))
        val neutral = mesh.build(rainStyle)
        val face = mesh.build(rainStyle, FaceEmotionState(eyeBlinkLeft = 1f, jawOpen = .5f, mouthSmileLeft = .4f), gazeX = -.75f)
        assertEquals(neutral.positions[1] - .01f, face.positions[1], 1e-7f)
        assertEquals(neutral.positions[4] - .015f, face.positions[4], 1e-7f)
        assertEquals(neutral.positions[5] + .0016f, face.positions[5], 1e-7f)
        assertEquals(neutral.positions[6] - .003f, face.positions[6], 1e-7f)
        assertArrayEquals(neutral.indices, face.indices)
        for (at in face.normals.indices step 3) {
            val length = sqrt(face.normals[at] * face.normals[at] + face.normals[at + 1] * face.normals[at + 1] + face.normals[at + 2] * face.normals[at + 2])
            assertEquals(1f, length, 1e-6f)
        }
        // Anatomical right eye and all unrelated vertices remain untouched.
        assertArrayEquals(neutral.positions.copyOfRange(9, 18), face.positions.copyOfRange(9, 18), 0f)
    }

    @Test fun cacheIsSharedAcrossRenderersButReturnedArraysAndHairSelectionAreIndependent() {
        val paths = mutableListOf<String>()
        val bytes = fixture()
        AuthoredPortraitStore.installLoader { path -> paths.add(path); ByteArrayInputStream(bytes) }
        val rain = mesh.build(rainStyle)
        val expected = rain.positions.copyOf()
        rain.positions[0] = 100f; rain.normals[0] = 100f; rain.colors[0] = 0; rain.indices[0] = 100; rain.surfaces[0] = 100
        val fresh = PortraitMesh().build(rainStyle, hairEnabled = false)
        assertArrayEquals(expected, fresh.positions, 0f)
        assertArrayEquals(intArrayOf(0, 1, 2), fresh.indices)
        assertEquals(6, fresh.colors.size)
        assertEquals(0xffb27c68.toInt(), fresh.colors[0])
        assertEquals(PortraitSurface.SKIN, fresh.surfaces[0])
        assertEquals("Removal bit never reaches material routing", PortraitSurface.HAIR, fresh.surfaces[3])
        assertEquals(0f, fresh.normals[0], 0f)
        mesh.build(snowStyle); mesh.build(rainStyle.copy(variant = "holo")); mesh.build(snowStyle.copy(variant = "gold"))
        assertEquals(listOf("portraits/rain.aeph", "portraits/snow.aeph"), paths)
    }

    @Test fun eyebrowsLashesAndBeardsKeepTheirHairFinishWhenHairstyleIsHidden() {
        install(fixture(removableHair = false))
        val withHair = mesh.build(rainStyle)
        val withoutHair = mesh.build(rainStyle, hairEnabled = false)
        assertArrayEquals(withHair.indices, withoutHair.indices)
        assertEquals(PortraitSurface.HAIR, withoutHair.surfaces[3])
        assertEquals(0xff30251d.toInt(), withoutHair.colors[3])
        assertArrayEquals(withHair.positions, withoutHair.positions, 0f)
    }

    @Test fun originalArtistColorsSurviveDefaultPalettesAndCustomSkinTintPreservesHair() {
        install(fixture())
        val rain = mesh.build(rainStyle); val snow = mesh.build(snowStyle)
        assertArrayEquals(rain.colors, snow.colors)
        assertEquals(0xffb27c68.toInt(), rain.colors[0])
        val custom = mesh.build(rainStyle.copy(skin = 0xff704030.toInt()))
        assertNotEquals(rain.colors[0], custom.colors[0])
        assertEquals(rain.colors[3], custom.colors[3])
        val gold = mesh.build(snowStyle.copy(variant = "gold", skin = 0xffdcb432.toInt(), metallic = true))
        assertNotEquals(snow.colors[0], gold.colors[0])
        assertNotEquals(snow.colors[3], gold.colors[3])
    }

    @Test fun invalidSensorWeightsClampWithoutContaminatingGeometry() {
        install(fixture(listOf(Pose("jawOpen", listOf(delta(0, dy = -.03f))),
            Pose("eyeBlinkLeft", listOf(delta(1, dy = -.01f))),
            Pose("gazeYPositive", listOf(delta(2, dy = .004f))))))
        val neutral = mesh.build(rainStyle)
        val bad = mesh.build(rainStyle, FaceEmotionState(jawOpen = Float.NaN, eyeBlinkLeft = -100f),
            gazeX = Float.POSITIVE_INFINITY, gazeY = Float.NaN)
        assertArrayEquals(neutral.positions, bad.positions, 0f)
        val clamped = mesh.build(rainStyle, FaceEmotionState(jawOpen = 1000f), gazeY = 1000f)
        val expected = mesh.build(rainStyle, FaceEmotionState(jawOpen = 1f), gazeY = 1f)
        assertArrayEquals(expected.positions, clamped.positions, 0f)
        assertTrue(clamped.positions.all(Float::isFinite))
    }

    @Test fun puckerAndFunnelShareOneLipShapeBudgetWithoutChangingIndividualExtremes() {
        install(fixture(listOf(
            Pose("mouthPucker", listOf(delta(0, dx = .010f))),
            Pose("mouthFunnel", listOf(delta(0, dy = -.020f))),
            Pose("jawOpen*mouthPucker", listOf(delta(1, dz = .004f))),
            Pose("jawOpen*mouthFunnel", listOf(delta(1, dz = -.002f))))))
        val neutral = mesh.build(rainStyle)
        val combined = mesh.build(rainStyle,
            FaceEmotionState(mouthPucker = 1f, mouthFunnel = 2f / 3f, jawOpen = .5f))
        val normalized = mesh.build(rainStyle,
            FaceEmotionState(mouthPucker = .6f, mouthFunnel = .4f, jawOpen = .5f))
        assertArrayEquals(normalized.positions, combined.positions, 1e-7f)
        assertEquals(neutral.positions[0] + .006f, combined.positions[0], 1e-7f)
        assertEquals(neutral.positions[1] - .008f, combined.positions[1], 1e-7f)
        assertEquals("Correctives receive the same normalized coefficients",
            neutral.positions[5] + .0008f, combined.positions[5], 1e-7f)
        val pucker = mesh.build(rainStyle, FaceEmotionState(mouthPucker = 1f))
        val funnel = mesh.build(rainStyle, FaceEmotionState(mouthFunnel = 1f))
        assertEquals(neutral.positions[0] + .010f, pucker.positions[0], 1e-7f)
        assertEquals(neutral.positions[1] - .020f, funnel.positions[1], 1e-7f)
        val stress = mesh.build(rainStyle, FaceEmotionState(mouthPucker = 100f, mouthFunnel = 100f))
        val equalBlend = mesh.build(rainStyle, FaceEmotionState(mouthPucker = .5f, mouthFunnel = .5f))
        assertArrayEquals(equalBlend.positions, stress.positions, 0f)
    }

    @Test fun malformedAssetIndicesCountsMaterialsAndMorphsAreRejected() {
        val badAssets = listOf(
            fixture(indices = intArrayOf(0, 1, 999)),
            fixture(badPosition = Float.NaN),
            fixture(surface = 255),
            fixture(listOf(Pose("unrecognized", listOf(delta(0))))),
            fixture(listOf(Pose("jawOpen", listOf(delta(0), delta(0))))),
            fixture(listOf(Pose("jawOpen", listOf(delta(0, dz = Float.POSITIVE_INFINITY))))),
            fixture().copyOf(15))
        for (bytes in badAssets) {
            install(bytes)
            try { mesh.build(rainStyle); fail("Corrupt authored assets must not silently use procedural faces") }
            catch (_: IllegalArgumentException) { }
            catch (_: java.io.IOException) { }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { it.writeBytes("AEPH0001"); it.writeInt(Int.MAX_VALUE); it.writeInt(3); it.writeInt(0) }
        install(bytes.toByteArray())
        try { mesh.build(rainStyle); fail("Invalid counts must be rejected before allocating") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun missingOptionalAssetFallsBackAndCreaturesNeverLoadHumanAssets() {
        var opened = 0
        AuthoredPortraitStore.installLoader { opened++; throw FileNotFoundException() }
        val creature = mesh.build(rainStyle.copy(character = "unicorn"))
        assertTrue(creature.positions.isNotEmpty()); assertEquals(0, opened)
        val fallback = mesh.build(rainStyle)
        assertTrue(fallback.colors.size > 6); assertEquals(1, opened)
        mesh.build(rainStyle)
        assertEquals("Missing models are cached too", 1, opened)
    }
}
