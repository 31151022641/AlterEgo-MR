package com.oculus.camerademo.model3d

import kotlin.math.*
import java.util.concurrent.ConcurrentHashMap

/** Rigid, metre-scale eyewear in portrait space (+Z forward). The fitting is
 * deliberately independent of expression/gaze, so blinking cannot move a rim. */
object PortraitSunglasses {
    private val accessories = ConcurrentHashMap<Fit,PortraitGeometry>()
    /** A face effect made only of glasses; passthrough shows the real face. */
    @JvmStatic fun standalone(): PortraitGeometry {
        val fit=Fit(.049f,.025f,.129f,.038f,.030f,.124f)
        val accessory=accessories.getOrPut(fit) { build(fit) }
        return PortraitGeometry(accessory.positions.copyOf(),accessory.normals.copyOf(),
            accessory.colors.copyOf(),accessory.indices.copyOf(),accessory.surfaces.copyOf())
    }

    @JvmStatic fun attach(source: PortraitGeometry, style: PortraitStyle): PortraitGeometry {
        if (source.colors.isEmpty() || source.indices.isEmpty()) return source
        val fit = when (style.character) {
            "unicorn" -> Fit(.068f,.060f,.069f,.036f,.034f,.117f,.024f)
            "lion" -> Fit(.058f,.044f,.086f,.042f,.032f,.131f,.024f)
            "dragon" -> Fit(.070f,.054f,.084f,.041f,.033f,.131f,.024f,.030f)
            else -> if (source.colors.size > 20_000) {
                if (style.beard || style.variant in setOf("bearded","gold","cyber"))
                    Fit(.035f,.028f,.099f,.031f,.029f,.089f)
                else Fit(.043f,.030f,.101f,.037f,.032f,.105f)
            } else Fit(.049f,.025f,.129f,.038f,.030f,.124f)
        }
        val accessory=accessories.getOrPut(fit) { build(fit) }
        // Every attachment owns its returned buffers; private cached rigid
        // geometry cannot be changed by scene coordinate conversion.
        return PortraitGeometry(source.positions+accessory.positions,source.normals+accessory.normals,
            source.colors+accessory.colors,
            source.indices+IntArray(accessory.indices.size){source.colors.size+accessory.indices[it]},
            source.surfaces+accessory.surfaces)
    }

    private fun build(fit:Fit):PortraitGeometry {
        val out = Builder()
        val frame = 0xff111b25.toInt()
        fun outline(side: Float, angle: Float): V {
            val co = cos(angle); val si = sin(angle)
            val sx = sign(co)*abs(co).pow(.64f)
            val sy = sign(si)*abs(si).pow(.64f)
            val x = side*fit.eyeX + fit.width*sx*(.95f+.05f*sy)
            val y = fit.eyeY + fit.height*sy + side*sx*.0015f
            // The dragon's upper muzzle rises beneath its eyes; curve the
            // lower edge forward to clear that surface without floating the
            // whole pair away from the side-facing eyes.
            return V(x,y,fit.depth-fit.wrap*(abs(x)/(fit.eyeX+fit.width)).pow(2)+
                fit.lowerLift*max(0f,-sy).pow(2))
        }
        for (side in listOf(-1f,1f)) {
            val rim = List(32) { outline(side,it*TAU/32) }
            // Real lens thickness, bowed face and closed rear remain visible
            // as the tracked head turns. The eye never shows through the tint.
            out.lens(rim,V(side*fit.eyeX,fit.eyeY,fit.depth+.003f),fit)
            out.loop(rim,.0038f,frame,PortraitSurface.HORN)
            val outer = outline(side,if(side>0f)0f else PI.toFloat())
            val hinge = outer+V(side*.004f,.002f,-.004f)
            out.path(listOf(outer,hinge,V(side*fit.temple,fit.eyeY+.001f,.010f),
                V(side*(fit.temple-.002f),fit.eyeY-.011f,-.040f)),.0034f,frame,PortraitSurface.HORN)
            // A small cool sweep reads as polished glass without hiding the tint.
            val a=V(side*fit.eyeX-fit.width*.57f,fit.eyeY+fit.height*.50f,0f)
            val b=V(side*fit.eyeX+fit.width*.34f,fit.eyeY+fit.height*.29f,0f)
            fun reflection(p: V): V = V(p.x,p.y,
                fit.depth-fit.wrap*(abs(p.x)/(fit.eyeX+fit.width)).pow(2)+.0038f)
            out.path(listOf(reflection(a),reflection(b)),.0013f,0xff527589.toInt(),PortraitSurface.ACCENT)
        }
        val bridgeY=fit.eyeY+fit.height*.36f
        val inner=fit.eyeX-fit.width*.91f
        val bridgeZ=fit.depth+.008f
        out.path(listOf(V(-inner,bridgeY,bridgeZ-.004f),V(-inner*.5f,bridgeY+.004f,bridgeZ),
            V(0f,bridgeY+.005f,bridgeZ+.001f),V(inner*.5f,bridgeY+.004f,bridgeZ),
            V(inner,bridgeY,bridgeZ-.004f)),.0037f,frame,PortraitSurface.HORN)
        return out.finish()
    }

    private data class Fit(val eyeX:Float,val eyeY:Float,val depth:Float,
        val width:Float,val height:Float,val temple:Float,val wrap:Float=.016f,val lowerLift:Float=0f)
    private data class V(val x:Float,val y:Float,val z:Float) {
        operator fun plus(v:V)=V(x+v.x,y+v.y,z+v.z)
        operator fun minus(v:V)=V(x-v.x,y-v.y,z-v.z)
        operator fun times(s:Float)=V(x*s,y*s,z*s)
        operator fun unaryMinus()=this*-1f
        fun dot(v:V)=x*v.x+y*v.y+z*v.z
        fun cross(v:V)=V(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
        fun unit():V { val len=sqrt(dot(this));return if(len>1e-8f)this*(1f/len) else V(0f,0f,1f) }
    }
    private class Builder {
        val positions=ArrayList<V>();val normals=ArrayList<V>()
        val colors=ArrayList<Int>();val surfaces=ArrayList<Int>();val indices=ArrayList<Int>()
        fun vertex(v:V,n:V,color:Int,surface:Int):Int {
            positions+=v;normals+=n.unit();colors+=color;surfaces+=surface;return colors.lastIndex
        }
        fun tri(a:Int,b:Int,c:Int) {
            val normal=(positions[b]-positions[a]).cross(positions[c]-positions[a])
            if(normal.dot(normal)<1e-14f)return
            indices+=a
            if(normal.dot(normals[a]+normals[b]+normals[c])<0f){indices+=c;indices+=b}
            else {indices+=b;indices+=c}
        }
        fun lens(edge:List<V>,center:V,fit:Fit) {
            val count=edge.size
            for (back in listOf(false,true)) {
                val start=positions.size
                val normal=V(0f,0f,if(back)-1f else 1f)
                val middle=vertex(center+V(0f,0f,if(back)-.006f else 0f),normal,
                    0xff132331.toInt(),PortraitSurface.EYE)
                for(ring in 1..3) {
                    val t=ring/3f
                    for(p in edge) {
                        val v=center*(1f-t)+p*t+V(0f,0f,if(back)-.006f else 0f)
                        val gradient=((v.y-fit.eyeY)/fit.height*.5f+.5f).coerceIn(0f,1f)
                        val color=mix(0xff080f19.toInt(),0xff243d4e.toInt(),gradient)
                        vertex(v,normal,color,PortraitSurface.EYE)
                    }
                }
                for(j in edge.indices)tri(middle,start+1+j,start+1+(j+1)%count)
                for(ring in 0 until 2)for(j in edge.indices) {
                    val a=start+1+ring*count+j;val b=start+1+ring*count+(j+1)%count
                    tri(a,b,a+count);tri(b,b+count,a+count)
                }
            }
            val start=positions.size
            for(p in edge) {
                val normal=V(p.x-center.x,p.y-center.y,0f).unit()
                vertex(p,normal,0xff0c1620.toInt(),PortraitSurface.EYE)
                vertex(p+V(0f,0f,-.006f),normal,0xff0c1620.toInt(),PortraitSurface.EYE)
            }
            for(j in edge.indices) {
                val a=start+j*2;val b=start+((j+1)%count)*2
                tri(a,b,a+1);tri(b,b+1,a+1)
            }
        }
        fun loop(points:List<V>,radius:Float,color:Int,surface:Int) =
            tube(points,radius,color,surface,true)
        fun path(points:List<V>,radius:Float,color:Int,surface:Int) =
            tube(points,radius,color,surface,false)
        private fun tube(points:List<V>,radius:Float,color:Int,surface:Int,closed:Boolean) {
            val start=positions.size;val sides=6
            for(i in points.indices) {
                val previous=points[if(i==0){if(closed)points.lastIndex else 0}else i-1]
                val next=points[if(i==points.lastIndex){if(closed)0 else i}else i+1]
                val tangent=(next-previous).unit()
                val u=tangent.cross(if(abs(tangent.z)<.9f)V(0f,0f,1f) else V(0f,1f,0f)).unit()
                val v=tangent.cross(u).unit()
                for(j in 0 until sides) {
                    val angle=j*TAU/sides;val normal=u*cos(angle)+v*sin(angle)
                    vertex(points[i]+normal*radius,normal,color,surface)
                }
            }
            val links=if(closed)points.size else points.size-1
            for(i in 0 until links)for(j in 0 until sides) {
                val a=start+i*sides+j;val b=start+i*sides+(j+1)%sides
                val c=start+((i+1)%points.size)*sides+j
                val d=start+((i+1)%points.size)*sides+(j+1)%sides
                tri(a,b,c);tri(b,d,c)
            }
            if(!closed)for(end in listOf(0,points.lastIndex)) {
                val normal=(if(end==0)points[0]-points[1] else points.last()-points[points.lastIndex-1]).unit()
                val middle=vertex(points[end],normal,color,surface)
                for(j in 0 until sides)tri(middle,start+end*sides+j,start+end*sides+(j+1)%sides)
            }
        }
        fun finish():PortraitGeometry {
            fun V.axis(axis:Int)=when(axis){0->x;1->y;else->z}
            return PortraitGeometry(
                FloatArray(positions.size*3){positions[it/3].axis(it%3)},
                FloatArray(normals.size*3){normals[it/3].axis(it%3)},
                colors.toIntArray(),indices.toIntArray(),surfaces.toIntArray())
        }
    }
    private fun mix(a:Int,b:Int,t:Float):Int {
        fun channel(shift:Int)=(((a ushr shift) and 255)*(1f-t)+((b ushr shift) and 255)*t).roundToInt()
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    private const val TAU=(PI*2).toFloat()
}
