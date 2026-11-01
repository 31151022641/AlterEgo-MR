package com.oculus.camerademo.model3d

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class UpperBodyMeshTest {
    private val skin=0xffd7a184.toInt()
    private fun build(body: List<Pose?> = emptyList(),hands: List<List<Pose?>> = emptyList(),
        head: Pose = Pose(), detail: Int = 12)=UpperBodyMesh.build(head,body,hands,skin,detail=detail)
    private fun point(g: PortraitGeometry,index: Int)=Vector3(g.positions[index*3],g.positions[index*3+1],g.positions[index*3+2])
    private fun distance(a: Vector3,b: Vector3): Float {val d=a-b;return sqrt(d.x*d.x+d.y*d.y+d.z*d.z)}
    private fun closest(g: PortraitGeometry,p: Vector3)=g.colors.indices.minOf {distance(point(g,it),p)}

    @Test fun neutralFallbackHasAFullSolidTorsoAndHumanSizedArmsWithoutTracking() {
        val geometry=build()
        assertTrue(geometry.indices.size/3 in 1800..6000)
        assertTrue(geometry.positions.all {it.isFinite()})
        val torso=geometry.colors.indices.map {point(geometry,it)}.filter {it.y in -.5f..-.30f && abs(it.x)<.15f}
        assertTrue(torso.minOf {it.z}<-.10f)
        assertTrue(torso.maxOf {it.z}>.07f)
        assertTrue(geometry.colors.indices.map {point(geometry,it).x}.maxOrNull()!!>.28f)
        assertTrue(geometry.colors.indices.map {point(geometry,it).y}.minOrNull()!!<-.69f)
        assertTrue(geometry.surfaces.all {it==PortraitSurface.SKIN})
        assertTrue(geometry.colors.contains(skin))
        assertTrue(geometry.colors.contains(0xff244b6d.toInt()))
    }

    @Test fun everyTriangleHasAreaAndFacesItsSmoothedNormals() {
        val geometry=build()
        for(at in geometry.indices.indices step 3) {
            val a=geometry.indices[at];val b=geometry.indices[at+1];val c=geometry.indices[at+2]
            val normal=(point(geometry,b)-point(geometry,a)).cross(point(geometry,c)-point(geometry,a))
            assertTrue("Degenerate body triangle",normal.dot(normal)>1e-13f)
            fun n(i:Int)=Vector3(geometry.normals[i*3],geometry.normals[i*3+1],geometry.normals[i*3+2])
            assertTrue("Inward body winding",normal.dot(n(a)+n(b)+n(c))>=-1e-8f)
        }
        for(at in geometry.normals.indices step 3) {
            val length=sqrt(geometry.normals[at]*geometry.normals[at]+geometry.normals[at+1]*geometry.normals[at+1]+geometry.normals[at+2]*geometry.normals[at+2])
            assertEquals(1f,length,.0001f)
        }
    }

    @Test fun trackedHumerusElbowAndOpticalWristDriveVolumesAtTheirActualJoints() {
        val body=MutableList<Pose?>(18){null}
        body[10]=Pose(Vector3(.23f,-.21f,0f));body[11]=Pose(Vector3(.40f,-.28f,.04f))
        body[12]=Pose(Vector3(.43f,-.49f,.05f))
        val optical=MutableList<Pose?>(26){null}
        optical[1]=Pose(Vector3(.49f,-.38f,.10f))
        val geometry=build(body,listOf(optical,emptyList()))
        assertTrue(closest(geometry,body[10]!!.t)<.09f)
        assertTrue(closest(geometry,body[11]!!.t)<.055f)
        assertTrue(closest(geometry,optical[1]!!.t)<.04f)
        assertTrue("Optical wrist must take priority over old body wrist",closest(geometry,optical[1]!!.t)<closest(geometry,body[12]!!.t))
        assertTrue(geometry.colors.indices.any {point(geometry,it).x>.49f})
    }

    @Test fun aHandInFrontOfTheFaceHasSolidPalmAndIndividualFingerVolumes() {
        val hand=MutableList<Pose?>(26){null}
        hand[1]=Pose(Vector3(.04f,-.14f,.30f));hand[0]=Pose(Vector3(.04f,-.07f,.31f))
        val geometry=build(hands=listOf(hand,emptyList()))
        val front=geometry.colors.indices.map {point(geometry,it)}.filter {it.z>.25f}
        assertTrue(front.size>120)
        assertTrue(front.maxOf {it.z}-front.minOf {it.z}>.035f)
        assertTrue(front.maxOf {it.y}>-.04f)
        assertTrue(closest(geometry,hand[0]!!.t)<.035f)
    }

    @Test fun missingOrInvalidBodyObservationsStayFiniteAndBounded() {
        val body=MutableList<Pose?>(18){null}
        body[10]=Pose(Vector3(Float.NaN,0f,0f));body[15]=Pose(Vector3(2000f,-.2f,0f))
        body[1]=Pose(Vector3(0f,-10000f,0f));body[11]=Pose(Vector3(Float.POSITIVE_INFINITY,0f,0f))
        val hands=List(2){List<Pose?>(26){Pose(Vector3(100f,100f,100f))}}
        val geometry=build(body,hands)
        assertTrue(geometry.positions.all {it.isFinite() && abs(it)<1.2f})
        assertArrayEquals(build().positions,geometry.positions,.00001f)
    }

    @Test fun aPartiallyTrackedLeftHandKeepsItsFallbackThumbOnTheIndexSide() {
        val hand=MutableList<Pose?>(26){null}
        hand[1]=Pose(Vector3(.30f,-.60f,.30f));hand[0]=Pose(Vector3(.30f,-.65f,.31f))
        hand[7]=Pose(Vector3(.275f,-.68f,.31f));hand[22]=Pose(Vector3(.33f,-.68f,.31f))
        val geometry=build(hands=listOf(hand,emptyList()))
        assertTrue("Thumb should reach toward the observed index side, not outward across the little finger",
            geometry.colors.indices.map {point(geometry,it)}.any {it.z>.28f && it.y>-.73f && it.x<.25f})
    }

    @Test fun invalidHeadCannotGenerateAnArbitraryBody() {
        assertTrue(build(head=Pose(Vector3(Float.NaN,0f,0f))).indices.isEmpty())
        assertTrue(build(head=Pose(Vector3(0f),Quaternion(0f,0f,0f,0f))).indices.isEmpty())
    }

    @Test fun neutralBodyDoesNotTwistWhenOnlyTheHeadTurns() {
        val neutral=build()
        val turned=build(head=Pose(Vector3(0f),Quaternion(0f,45f,0f)))
        // The authored head is drawn separately; only its neck attachment may move.
        val neutralTorso=neutral.colors.indices.filter {neutral.colors[it]!=skin}.map {point(neutral,it)}
        val turnedTorso=turned.colors.indices.filter {turned.colors[it]!=skin}.map {point(turned,it)}
        assertEquals(neutralTorso.size,turnedTorso.size)
        for(i in neutralTorso.indices)assertTrue(distance(neutralTorso[i],turnedTorso[i])<.0001f)
    }

    @Test fun geometryBudgetStaysBoundedAcrossQualityLevels() {
        for(detail in listOf(1,8,12,16,100)) {
            val geometry=build(detail=detail)
            assertTrue(geometry.indices.size/3 in 1200..6000)
            assertTrue(geometry.colors.size<3500)
            assertTrue(geometry.indices.all {it in geometry.colors.indices})
        }
    }

    @Test fun neutralCrewNeckKeepsTheChinGapShortAndConnectsIntoSoftShoulders() {
        val geometry=build()
        val collar=geometry.colors.indices.map {point(geometry,it)}.filter {abs(it.x)<.09f && it.y in -.17f..-.13f}
        assertTrue(collar.isNotEmpty())
        // Human chin bounds are Rain -.086m / Snow -.102m; a crew-neck opening
        // near -.15m keeps the visible neck about4–6cm, rather than a long stalk.
        val visibleGap=-.086f-collar.maxOf {it.y}
        assertTrue(visibleGap in .035f.. .070f)
        assertTrue(closest(geometry,Vector3(.21f,-.18f,-.025f))<.07f)
    }
}
