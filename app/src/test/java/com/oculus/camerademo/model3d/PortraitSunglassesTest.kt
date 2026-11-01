package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import java.io.File
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PortraitSunglassesTest {
    private val styles = listOf(
        PortraitStyle(0xffe1af91.toInt()),
        PortraitStyle(0xffdaa884.toInt(), beard = true, variant = "bearded"),
        PortraitStyle(0xffffffff.toInt(), character = "unicorn"),
        PortraitStyle(0xffffb300.toInt(), character = "lion"),
        PortraitStyle(0xff4caf50.toInt(), character = "dragon"),
    )

    @Before fun installAuthoredFaces() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { File(it, "portraits/rain.aeph").isFile }
        AuthoredPortraitStore.installDirectory(root.path)
    }

    @After fun clearAuthoredFaces() { AuthoredPortraitStore.clear() }

    @Test fun attachmentPreservesEveryOriginalFaceAttributeAndDoesNotMutateItsInput() {
        for (style in styles) {
            val source = PortraitMesh().build(style, detail = 14)
            val positions = source.positions.copyOf()
            val normals = source.normals.copyOf()
            val colors = source.colors.copyOf()
            val indices = source.indices.copyOf()
            val surfaces = source.surfaces.copyOf()
            val dressed = PortraitSunglasses.attach(source, style)
            assertTrue(dressed.colors.size > source.colors.size)
            assertArrayEquals(positions, source.positions, 0f)
            assertArrayEquals(normals, source.normals, 0f)
            assertArrayEquals(colors, source.colors)
            assertArrayEquals(indices, source.indices)
            assertArrayEquals(surfaces, source.surfaces)
            assertArrayEquals(positions, dressed.positions.copyOfRange(0, positions.size), 0f)
            assertArrayEquals(normals, dressed.normals.copyOfRange(0, normals.size), 0f)
            assertArrayEquals(colors, dressed.colors.copyOfRange(0, colors.size))
            assertArrayEquals(indices, dressed.indices.copyOfRange(0, indices.size))
            assertArrayEquals(surfaces, dressed.surfaces.copyOfRange(0, surfaces.size))
        }
    }

    @Test fun accessoryHasFiniteSolidGeometryAndUnitNormalsWithinItsTriangleBudget() {
        for (style in styles) {
            val source = PortraitMesh().build(style, detail = 12)
            val dressed = PortraitSunglasses.attach(source, style)
            val start = source.colors.size
            val extraTriangles = (dressed.indices.size - source.indices.size) / 3
            assertTrue("A solid frame and temple arms need volume", extraTriangles > 100)
            assertTrue("Accessory triangle budget: $extraTriangles", extraTriangles < 3000)
            assertEquals(dressed.colors.size * 3, dressed.positions.size)
            assertEquals(dressed.positions.size, dressed.normals.size)
            assertEquals(dressed.colors.size, dressed.surfaces.size)
            for (vertex in start until dressed.colors.size) {
                val at = vertex * 3
                assertTrue(dressed.positions.sliceArray(at..at + 2).all { it.isFinite() })
                val nx = dressed.normals[at]; val ny = dressed.normals[at + 1]; val nz = dressed.normals[at + 2]
                assertEquals(1f, sqrt(nx * nx + ny * ny + nz * nz), .002f)
                assertEquals(255, dressed.colors[vertex] ushr 24)
                assertTrue(dressed.surfaces[vertex] in 0 until PortraitSurface.COUNT)
            }
            for (triangle in source.indices.size until dressed.indices.size step 3) {
                val vertices = (0..2).map { dressed.indices[triangle + it] }
                assertTrue("Accessory must not rewrite the face topology", vertices.all { it in start until dressed.colors.size })
                fun difference(a: Int, b: Int, axis: Int) = dressed.positions[a * 3 + axis] - dressed.positions[b * 3 + axis]
                val ab = FloatArray(3) { difference(vertices[1], vertices[0], it) }
                val ac = FloatArray(3) { difference(vertices[2], vertices[0], it) }
                val cross = floatArrayOf(ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0])
                assertTrue("No degenerate sunglasses triangles", cross.sumOf { (it * it).toDouble() } > 1e-16)
            }
        }
    }

    @Test fun bothLensesCoverTheEyesAndSitInFrontOfThemForEverySpecies() {
        for (style in styles) {
            val source = PortraitMesh().build(style, detail = 16)
            val dressed = PortraitSunglasses.attach(source, style)
            for (sign in listOf(-1f, 1f)) {
                val eyes = source.colors.indices.filter {
                    source.surfaces[it] == PortraitSurface.EYE && source.positions[it * 3] * sign > .005f
                }
                val lens = (source.colors.size until dressed.colors.size).filter {
                    dressed.surfaces[it] == PortraitSurface.EYE && dressed.positions[it * 3] * sign > .005f
                }
                assertTrue("Eye reference must exist", eyes.isNotEmpty())
                assertTrue("Both lenses must exist", lens.isNotEmpty())
                fun bounds(vertices: List<Int>, positions: FloatArray, axis: Int) =
                    vertices.minOf { positions[it * 3 + axis] }..vertices.maxOf { positions[it * 3 + axis] }
                val eyeX = bounds(eyes, source.positions, 0)
                val eyeY = bounds(eyes, source.positions, 1)
                val lensX = bounds(lens, dressed.positions, 0)
                val lensY = bounds(lens, dressed.positions, 1)
                assertTrue("Lens must cover eye centre horizontally: ${style.character}/${style.variant}",
                    (eyeX.start + eyeX.endInclusive) * .5f in lensX)
                assertTrue("Lens must cover eye centre vertically: ${style.character}/${style.variant}",
                    (eyeY.start + eyeY.endInclusive) * .5f in lensY)
                assertTrue("Lenses must clear the eyes instead of hiding inside the head",
                    lens.maxOf { dressed.positions[it * 3 + 2] } > eyes.maxOf { source.positions[it * 3 + 2] } + .001f)
            }
        }
    }

    @Test fun blinkJawAndGazeNeverMoveOrResizeTheRigidSunglasses() {
        val expression = FaceEmotionState(jawOpen = 1f, eyeBlinkLeft = 1f, eyeBlinkRight = .7f,
            mouthSmileLeft = 1f, mouthSmileRight = .6f, cheekPuff = 1f)
        for (style in styles) {
            val portrait = PortraitMesh()
            val neutral = portrait.build(style, detail = 14)
            val animated = portrait.build(style, expression, gazeX = 1f, gazeY = -1f, detail = 14)
            val neutralDressed = PortraitSunglasses.attach(neutral, style)
            val animatedDressed = PortraitSunglasses.attach(animated, style)
            assertArrayEquals(neutralDressed.positions.copyOfRange(neutral.positions.size, neutralDressed.positions.size),
                animatedDressed.positions.copyOfRange(animated.positions.size, animatedDressed.positions.size), 0f)
            assertArrayEquals(neutralDressed.normals.copyOfRange(neutral.normals.size, neutralDressed.normals.size),
                animatedDressed.normals.copyOfRange(animated.normals.size, animatedDressed.normals.size), 0f)
        }
    }

    @Test fun returnedBuffersCannotCorruptTheAccessoryOnOtherFaces() {
        val style = styles.first()
        val source = PortraitMesh().build(style, detail = 14)
        val first = PortraitSunglasses.attach(source, style)
        val expected = first.positions.copyOfRange(source.positions.size, first.positions.size)
        java.util.Arrays.fill(first.positions, source.positions.size, first.positions.size, 999f)
        val second = PortraitSunglasses.attach(source, style)
        assertArrayEquals(expected, second.positions.copyOfRange(source.positions.size, second.positions.size), 0f)
    }
}
