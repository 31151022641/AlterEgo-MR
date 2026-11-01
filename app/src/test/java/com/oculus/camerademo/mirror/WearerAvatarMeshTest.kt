package com.oculus.camerademo.mirror

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.oculus.camerademo.ml.FaceEmotionState
import com.oculus.camerademo.model3d.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class WearerAvatarMeshTest {
    private val bodies = List<Pose?>(18) { null }
    private val hands = List(2) { List<Pose?>(26) { null } }
    @Before fun assets() {
        val root = listOf(File("src/main/assets"),File("app/src/main/assets"))
            .first { File(it,"portraits/rain.aeph").isFile }
        AuthoredPortraitStore.installDirectory(root.path)
    }
    @After fun clear() { AuthoredPortraitStore.clear() }

    @Test fun authoredFacesKeepTheirQuarterMetreSizeAndUniformProportionsOnTheBody() {
        for(style in listOf(PortraitStyle(0xffe1af91.toInt()),PortraitStyle(0xffdaa884.toInt(),variant="bearded"))) {
            val source=PortraitMesh().build(style,detail=14)
            val full=WearerAvatarMesh().build(Pose(),bodies,hands,style)
            val start=full.colors.size-source.colors.size
            assertTrue(start>500)
            val skin=source.colors.indices.filter {source.surfaces[it]==PortraitSurface.SKIN}
            val min=skin.minOf {full.positions[(start+it)*3+1]}
            val max=skin.maxOf {full.positions[(start+it)*3+1]}
            assertTrue("Face height ${max-min}",max-min in .23f.. .25f)
            for(axis in 0..2) {
                val original=skin.maxOf {source.positions[it*3+axis]}-skin.minOf {source.positions[it*3+axis]}
                val combined=skin.maxOf {full.positions[(start+it)*3+axis]}-skin.minOf {full.positions[(start+it)*3+axis]}
                assertEquals("Head must not be squeezed on axis $axis",original,combined,.0001f)
            }
        }
    }

    @Test fun headTurnsFollowSdkPoseWithoutChangingTheBodyOrFacialDimensions() {
        val style=PortraitStyle(0xffe1af91.toInt())
        val head=Pose(Vector3(.03f,.05f,.02f),Quaternion(15f,35f,-10f))
        val source=PortraitMesh().build(style)
        val full=WearerAvatarMesh().build(head,bodies,hands,style)
        val start=full.colors.size-source.colors.size
        for(i in source.colors.indices step 197) {
            val expected=head*Vector3(source.positions[i*3],source.positions[i*3+1],source.positions[i*3+2])
            assertEquals(expected.x,full.positions[(start+i)*3],.0001f)
            assertEquals(expected.y,full.positions[(start+i)*3+1],.0001f)
            assertEquals(expected.z,full.positions[(start+i)*3+2],.0001f)
        }
    }

    @Test fun headAndBodyUseOneValidDepthTestableMeshWithWaistInFrame() {
        val full=WearerAvatarMesh().build(Pose(),bodies,hands,PortraitStyle(0xffe1af91.toInt()))
        assertEquals(full.positions.size,full.normals.size)
        assertEquals(full.positions.size/3,full.colors.size)
        assertTrue(full.indices.all {it in full.colors.indices})
        assertTrue(full.positions.all {it.isFinite()})
        val minY=(1 until full.positions.size step 3).minOf {full.positions[it]}
        val maxY=(1 until full.positions.size step 3).maxOf {full.positions[it]}
        assertTrue("Whole upper body must fit below head",minY<-.60f)
        assertTrue(AvatarMirrorFraming.projectY(minY,960,540)<535f)
        assertTrue(AvatarMirrorFraming.projectY(maxY,960,540)>5f)
        assertTrue(full.positions.indices.step(3).any {full.positions[it+2]<-.08f && full.positions[it+1]<-.20f})
    }

    @Test fun matchingFrameAspectRatiosKeepTheSameAnatomicalScaleAtEveryVideoQuality() {
        for(size in listOf(1920 to 1080,1280 to 720,960 to 540)) {
            val x=AvatarMirrorFraming.projectX(.2f,size.first,size.second)/size.first
            val y=AvatarMirrorFraming.projectY(-.65f,size.first,size.second)/size.second
            assertEquals(AvatarMirrorFraming.projectX(.2f,960,540)/960,x,.0001f)
            assertEquals(AvatarMirrorFraming.projectY(-.65f,960,540)/540,y,.0001f)
            assertTrue(y<.95f)
        }
    }

    @Test fun invalidHeadPoseNeverProducesCorruptedTriangles() {
        val full=WearerAvatarMesh().build(Pose(Vector3(Float.NaN,0f,0f)),bodies,hands,PortraitStyle(0xffe1af91.toInt()))
        assertTrue(full.indices.isEmpty());assertTrue(full.positions.isEmpty())
    }
}
