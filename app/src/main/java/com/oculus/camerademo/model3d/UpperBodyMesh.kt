package com.oculus.camerademo.model3d

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.*

/** Solid, metre-scale clothing/body geometry for the custom portrait mirror.
 * Missing observations choose a neutral visual pose; no tracking flags change.
 * Anatomical left is +X, front is +Z, matching PortraitMesh. */
object UpperBodyMesh {
    @JvmStatic fun build(head: Pose, body: List<Pose?>, hands: List<List<Pose?>>,
        skin: Int, shirt: Int = 0xff244b6d.toInt(), detail: Int = 12): PortraitGeometry {
        if (!valid(head)) return empty()
        val h = V.of(head.t)
        val out = Builder()
        val d = detail.coerceIn(8, 16)
        val cloth = opaque(shirt); val flesh = opaque(skin)
        val trim = mix(cloth, 0xff0f2034.toInt(), .42f)
        fun observed(p: Pose?, radius: Float = 1.45f): V? =
            if (p != null && valid(p) && (V.of(p.t)-h).length() <= radius) V.of(p.t) else null
        fun joint(index: Int) = observed(body.getOrNull(index))
        fun shoulder(index: Int, clavicle: Int, side: Float): V {
            val candidate = joint(index) ?: joint(clavicle)
            return candidate?.takeIf { it.y in h.y-.42f..h.y-.06f && (it-h).length() in .10f.. .60f }
                ?: h+V(side*.21f,-.18f,-.025f)
        }
        var left = shoulder(10,8,1f); var right = shoulder(15,13,-1f)
        val span = left-right
        val horizontalSpan = sqrt(span.x*span.x+span.z*span.z)
        if (horizontalSpan !in .24f.. .65f || abs(left.y-right.y) > .22f) {
            left=h+V(.21f,-.18f,-.025f); right=h+V(-.21f,-.18f,-.025f)
        }
        val across = V(left.x-right.x,0f,left.z-right.z).unit(V.X)
        val front = across.cross(V.Y).unit(V.Z)
        val shoulderCenter=(left+right)*.5f
        val halfWidth=((left-right).length()*.5f).coerceIn(.15f,.28f)
        val hips = joint(1)?.takeIf { it.y in shoulderCenter.y-.65f..shoulderCenter.y-.28f }
        val torsoLength=(hips?.let { shoulderCenter.y-it.y } ?: .48f).coerceIn(.35f,.55f)
        val waist = if (hips != null) V(
            hips.x.coerceIn(shoulderCenter.x-.20f,shoulderCenter.x+.20f),
            shoulderCenter.y-torsoLength,
            hips.z.coerceIn(shoulderCenter.z-.20f,shoulderCenter.z+.20f))
            else shoulderCenter-V(0f,torsoLength,0f)
        val collarCenter=V(shoulderCenter.x,(shoulderCenter.y+.027f).coerceIn(h.y-.153f,h.y-.140f),shoulderCenter.z)
        val profiles = arrayOf(
            Ring(collarCenter-V(0f,.002f,0f),.079f,.058f,cloth),
            Ring(lerp(collarCenter,shoulderCenter,.55f),halfWidth*.73f,.081f,cloth),
            Ring(shoulderCenter-V(0f,.018f,0f),halfWidth*.98f,.097f,cloth),
            Ring(lerp(shoulderCenter,waist,.30f),halfWidth*.94f,.111f,cloth),
            Ring(lerp(shoulderCenter,waist,.57f),halfWidth*.86f,.106f,cloth),
            Ring(lerp(shoulderCenter,waist,.82f),halfWidth*.77f,.095f,cloth),
            Ring(lerp(shoulderCenter,waist,1.04f),halfWidth*.78f,.093f,cloth),
            Ring(lerp(shoulderCenter,waist,1.08f),halfWidth*.78f,.093f,trim))
        val torsoSegments=(d+8).coerceIn(20,24)
        out.loft(profiles,across,front,torsoSegments)

        val neckBase=collarCenter+V(0f,.012f,0f)
        val neckTop=V.of(head*Vector3(0f,-.083f,0f))
        out.tube(neckBase,neckTop,.048f,.043f,flesh,d,3)
        out.torus(collarCenter,across,front,.078f,.058f,.009f,trim,torsoSegments,6)

        for (side in 0..1) {
            val sign=if(side==0)1f else -1f
            val start=if(side==0)left else right
            val optical=hands.getOrNull(side) ?: emptyList()
            val wristCandidate=observed(optical.getOrNull(1)) ?: joint(if(side==0)12 else 17)
            val neutralWrist=start+across*(sign*.035f)+V(0f,-.37f,0f)+front*.09f
            val wrist=wristCandidate?.takeIf { (it-start).length() in .16f.. .78f } ?: neutralWrist
            val elbowCandidate=joint(if(side==0)11 else 16)
            val elbow=elbowCandidate?.takeIf {
                (it-start).length() in .10f.. .48f && (wrist-it).length() in .08f.. .48f
            } ?: bendElbow(start,wrist,across*sign,front)
            out.ellipsoid(start,across,V.Y,front,.062f,.057f,.064f,cloth,d,8)
            val sleeveEnd=lerp(start,elbow,.54f)
            val cuffEnd=lerp(start,elbow,.62f)
            out.tube(start,sleeveEnd,.061f,.055f,cloth,d,3)
            out.tube(sleeveEnd,cuffEnd,.058f,.057f,trim,d,2)
            out.tube(cuffEnd,elbow,.048f,.043f,flesh,d,3)
            out.ellipsoid(elbow,across,V.Y,front,.044f,.047f,.044f,flesh,d,5)
            out.tube(elbow,wrist,.046f,.029f,flesh,d,4)
            buildHand(out,h,optical,wrist,elbow,across,front,sign,flesh,d)
        }
        return out.finish()
    }

    private fun buildHand(out: Builder, head: V, joints: List<Pose?>, wrist: V, elbow: V,
        across: V, front: V, side: Float, skin: Int, detail: Int) {
        fun point(index: Int): V? {
            val pose=joints.getOrNull(index) ?: return null
            if(!valid(pose))return null
            return V.of(pose.t).takeIf { (it-wrist).length() <= .28f && (it-head).length() <= 1.6f }
        }
        val knuckles=listOf(7,12,17,22).mapNotNull { point(it) }
        val knuckleCenter=if(knuckles.size>=2)knuckles.reduce { a,b->a+b }*(1f/knuckles.size) else null
        val direction=(knuckleCenter?.minus(wrist) ?: (wrist-elbow)).unit(V(0f,-1f,0f))
        val observedAcross=point(7)?.let { index -> point(22)?.let { index-it } }
        val rawAcross=observedAcross ?: across
        val lateral=(rawAcross-direction*rawAcross.dot(direction)).unit(across)
        val thumbSide=if(observedAcross!=null)1f else -side
        val normal=lateral.cross(direction).unit(front)
        val palm=point(0)?.takeIf { (it-wrist).length() in .025f.. .14f } ?: wrist+direction*.055f
        out.ellipsoid(palm,lateral,direction,normal,.040f,.058f,.023f,skin,detail,6)
        out.tube(wrist,palm,.028f,.033f,skin,max(8,detail-2),2)
        val chains=arrayOf(intArrayOf(2,3,4,5),intArrayOf(7,8,9,10),intArrayOf(12,13,14,15),
            intArrayOf(17,18,19,20),intArrayOf(22,23,24,25))
        for(finger in chains.indices) {
            val observed=chains[finger].map { point(it) }
            val complete=observed.all { it!=null } && observed.zipWithNext().all { (a,b)->(a!!-b!!).length() in .004f.. .095f }
            val points=if(complete)observed.map { it!! } else {
                val thumb=finger==0
                val lateralOffset=if(thumb)thumbSide*.036f else thumbSide*floatArrayOf(.028f,.009f,-.011f,-.029f)[finger-1]
                val base=palm+lateral*lateralOffset+direction*(if(thumb)-.005f else .038f)
                val reach=if(thumb)(direction*.75f+lateral*thumbSide*.65f).unit(direction) else direction
                val length=when(finger){0->.071f;1->.091f;2->.101f;3->.095f;else->.078f}
                listOf(base,base+reach*(length*.38f),base+reach*(length*.72f)-normal*.006f,
                    base+reach*length-normal*.014f)
            }
            for(segment in 0..2) {
                val r=(if(finger==0).012f else .010f)*(1f-segment*.12f)
                out.tube(points[segment],points[segment+1],r,r*.86f,skin,max(8,detail/2),2)
                if(segment>0)out.ellipsoid(points[segment],lateral,direction,normal,r,r,r,skin,6,4)
            }
            out.ellipsoid(points.last(),lateral,direction,normal,.007f,.008f,.007f,skin,6,4)
        }
    }

    /** Neutral two-bone bend when elbows are unavailable, driven by a valid wrist. */
    private fun bendElbow(shoulder: V,wrist: V,outward: V,front: V): V {
        val displacement=wrist-shoulder
        val distance=displacement.length().coerceAtLeast(.05f)
        val direction=displacement.unit(V(0f,-1f,0f))
        val upper=max(.25f,distance*.53f+.012f);val lower=max(.23f,distance*.47f+.012f)
        val along=((upper*upper-lower*lower+distance*distance)/(2f*distance)).coerceIn(0f,upper)
        val raw=V(0f,-1f,0f)+outward*.35f+front*.25f
        val bend=(raw-direction*raw.dot(direction)).unit(front)
        return shoulder+direction*along+bend*sqrt(max(0f,upper*upper-along*along))
    }

    private data class Ring(val center: V,val width: Float,val depth: Float,val color: Int)
    private class Builder {
        val points=ArrayList<V>();val normals=ArrayList<V>();val colors=ArrayList<Int>();val indices=ArrayList<Int>()
        fun vertex(point: V,normal: V,color: Int): Int {
            points+=point; normals+=normal.unit(V.Z);colors+=opaque(color);return points.lastIndex
        }
        fun triangle(a: Int,b: Int,c: Int) {
            val cross=(points[b]-points[a]).cross(points[c]-points[a])
            if(cross.dot(cross)<1e-13f)return
            indices+=a
            if(cross.dot(normals[a]+normals[b]+normals[c])<0f){indices+=c;indices+=b}
            else {indices+=b;indices+=c}
        }
        fun loft(rings: Array<Ring>,u: V,v: V,segments: Int) {
            val start=points.size
            fun location(ring: Ring,angle: Float)=ring.center+u*(ring.width*cos(angle))+v*(ring.depth*sin(angle))
            for(i in rings.indices)for(j in 0 until segments) {
                val angle=j*TAU/segments
                val around=u*(-rings[i].width*sin(angle))+v*(rings[i].depth*cos(angle))
                val along=location(rings[min(i+1,rings.lastIndex)],angle)-location(rings[max(0,i-1)],angle)
                vertex(location(rings[i],angle),around.cross(along),rings[i].color)
            }
            for(i in 0 until rings.lastIndex)for(j in 0 until segments) {
                val a=start+i*segments+j;val b=start+i*segments+(j+1)%segments
                val c=a+segments;val d=b+segments
                triangle(a,b,c);triangle(b,d,c)
            }
            val axis=(rings.last().center-rings.first().center).unit(V(0f,-1f,0f))
            cap(start,segments,rings.first().center,-axis,rings.first().color)
            cap(start+rings.lastIndex*segments,segments,rings.last().center,axis,rings.last().color)
        }
        fun tube(a: V,b: V,r0: Float,r1: Float,color: Int,segments: Int,rings: Int) {
            val length=(b-a).length();if(length<.003f)return
            val axis=(b-a)*(1f/length)
            val u=axis.cross(if(abs(axis.y)<.9f)V.Y else V.X).unit(V.X)
            val v=axis.cross(u).unit(V.Z)
            val start=points.size
            for(i in 0 until rings) {
                val t=i.toFloat()/(rings-1);val radius=r0*(1f-t)+r1*t
                for(j in 0 until segments) {
                    val angle=j*TAU/segments;val radial=u*cos(angle)+v*sin(angle)
                    vertex(lerp(a,b,t)+radial*radius,radial-axis*((r1-r0)/length),color)
                }
            }
            for(i in 0 until rings-1)for(j in 0 until segments) {
                val x=start+i*segments+j;val next=start+i*segments+(j+1)%segments
                triangle(x,next,x+segments);triangle(next,next+segments,x+segments)
            }
            cap(start,segments,a,-axis,color);cap(start+(rings-1)*segments,segments,b,axis,color)
        }
        private fun cap(start: Int,segments: Int,center: V,normal: V,color: Int) {
            val middle=vertex(center,normal,color)
            for(j in 0 until segments)triangle(start+j,middle,start+(j+1)%segments)
        }
        fun ellipsoid(center: V,u: V,v: V,w: V,rx: Float,ry: Float,rz: Float,color: Int,segments: Int,bands: Int) {
            val bottom=vertex(center-v*ry,-v,color)
            val start=points.size
            for(i in 1 until bands) {
                val latitude=-PI_F*.5f+PI_F*i/bands
                for(j in 0 until segments) {
                    val theta=j*TAU/segments
                    val x=cos(latitude)*cos(theta);val y=sin(latitude);val z=cos(latitude)*sin(theta)
                    vertex(center+u*(rx*x)+v*(ry*y)+w*(rz*z),u*(x/rx)+v*(y/ry)+w*(z/rz),color)
                }
            }
            val top=vertex(center+v*ry,v,color)
            for(j in 0 until segments) {
                triangle(bottom,start+j,start+(j+1)%segments)
                val last=start+(bands-2)*segments
                triangle(top,last+j,last+(j+1)%segments)
            }
            for(i in 0 until bands-2)for(j in 0 until segments) {
                val a=start+i*segments+j;val b=start+i*segments+(j+1)%segments
                triangle(a,b,a+segments);triangle(b,b+segments,a+segments)
            }
        }
        fun torus(center: V,u: V,v: V,rx: Float,rz: Float,radius: Float,color: Int,segments: Int,bands: Int) {
            val start=points.size
            for(i in 0 until segments) {
                val angle=i*TAU/segments;val radial=(u*cos(angle)+v*sin(angle)).unit(u)
                val ring=center+u*(rx*cos(angle))+v*(rz*sin(angle))
                for(j in 0 until bands) {
                    val cross=j*TAU/bands;val normal=radial*cos(cross)+V.Y*sin(cross)
                    vertex(ring+normal*radius,normal,color)
                }
            }
            for(i in 0 until segments)for(j in 0 until bands) {
                val a=start+i*bands+j;val b=start+((i+1)%segments)*bands+j
                val c=start+i*bands+(j+1)%bands;val d=start+((i+1)%segments)*bands+(j+1)%bands
                triangle(a,b,c);triangle(b,d,c)
            }
        }
        fun finish()=PortraitGeometry(FloatArray(points.size*3){i->points[i/3].axis(i%3)},
            FloatArray(normals.size*3){i->normals[i/3].axis(i%3)},colors.toIntArray(),indices.toIntArray(),
            IntArray(points.size){PortraitSurface.SKIN})
    }
    private data class V(val x: Float,val y: Float,val z: Float) {
        operator fun plus(v: V)=V(x+v.x,y+v.y,z+v.z)
        operator fun minus(v: V)=V(x-v.x,y-v.y,z-v.z)
        operator fun unaryMinus()=V(-x,-y,-z)
        operator fun times(s: Float)=V(x*s,y*s,z*s)
        fun dot(v: V)=x*v.x+y*v.y+z*v.z
        fun cross(v: V)=V(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
        fun length()=sqrt(dot(this))
        fun unit(fallback: V): V { val length=length();return if(length.isFinite() && length>1e-6f)this*(1f/length) else fallback }
        fun axis(index: Int)=when(index){0->x;1->y;else->z}
        companion object {
            val X=V(1f,0f,0f);val Y=V(0f,1f,0f);val Z=V(0f,0f,1f)
            fun of(v: Vector3)=V(v.x,v.y,v.z)
        }
    }
    private fun valid(p: Pose): Boolean {
        if(!listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z).all { it.isFinite() })return false
        val norm=p.q.w*p.q.w+p.q.x*p.q.x+p.q.y*p.q.y+p.q.z*p.q.z
        return norm in .5f..1.5f
    }
    private fun lerp(a: V,b: V,t: Float)=a*(1f-t)+b*t
    private fun opaque(color: Int)=color or (0xff shl 24)
    private fun mix(a: Int,b: Int,t: Float): Int {
        fun channel(shift: Int)=(((a ushr shift) and 255)*(1f-t)+((b ushr shift) and 255)*t).roundToInt().coerceIn(0,255)
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    private fun empty()=PortraitGeometry(FloatArray(0),FloatArray(0),IntArray(0),IntArray(0))
    private const val PI_F=3.14159265358979323846f
    private const val TAU=PI_F*2f
}
