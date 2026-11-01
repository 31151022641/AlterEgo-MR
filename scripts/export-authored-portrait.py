"""Bake Blender Studio facial rigs into the app's shared, animated portrait format.

Run in Blender 4.2 LTS with --background --factory-startup --disable-autoexec
--python scripts/export-authored-portrait.py -- --input rig.blend --name rain
--output app/src/main/assets/portraits/rain.aeph
Source files are read-only; embedded scripts are never enabled. Materials are
baked through Cycles' EMIT pass, so texture/UDIM and painted color attributes
survive the app's vertex-color-only renderer. No light is baked into the asset.
"""
import argparse
import gzip
import importlib.util
import json
import math
from pathlib import Path
import struct
import sys

import bpy
from mathutils import Vector
import numpy as np

spec = importlib.util.spec_from_file_location('rig_probe', Path(__file__).with_name('probe-facial-rig.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def arguments():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--name', choices=['rain', 'snow'], required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--neutral-only', action='store_true')
    return parser.parse_args(sys.argv[sys.argv.index('--') + 1:])


def selected_objects(name):
    parts = ['head','eyebrows','eyelashes','eyes','eye_dots','gums_lower',
             'gums_upper','teeth_lower','teeth_upper','tongue']
    if name == 'rain':
        parts += ['hair_main','hair_ponytail','hair_strand','hairband']
    else:
        parts += ['hair_base']
    names = ['GEO-' + name + '-' + part for part in parts]
    objects = [bpy.data.objects[n] for n in names if n in bpy.data.objects]
    assert any(o.name.endswith('-head') for o in objects)
    return objects


def apply_pose(rig, channels, name):
    probe.freeze_demo_animation()
    snow=name=='snow'
    if not snow:
        # The production rig's rest tail points horizontally backward. Give its
        # existing FK chain a compact, gravity-following avatar hairstyle.
        for i,angle in enumerate([-85,0,15,15],1):
            bone=rig.pose.bones['FK-Hair_Ponytail'+str(i)]
            bone.rotation_mode='XYZ';bone.rotation_euler.x=math.radians(angle)
        rig.pose.bones['FK-Hair_Ponytail1'].scale.y=.48
    def loc(bone, axis, amount):
        if bone not in rig.pose.bones:
            raise ValueError('Missing facial control ' + bone)
        rig.pose.bones[bone].location['XYZ'.index(axis)] += amount
    for channel in channels:
        side = 'L' if channel.endswith('Left') else 'R'
        eyelid='MSTR-' if snow else 'ACT-'
        if channel.startswith('eyeBlink'):
            loc(eyelid+'Eyelid_Upper.'+side, 'Y', -.025 if snow else -.030)
            loc(eyelid+'Eyelid_Lower.'+side, 'Y', .003 if snow else .005)
        elif channel.startswith(('eyeSquint','cheekSquint')):
            loc(eyelid+'Eyelid_Lower.'+side,'Y',.008 if snow else .012)
            loc(eyelid+'Eyelid_Upper.'+side,'Y',-.005 if snow else -.006)
            if snow and channel.startswith('cheekSquint'):loc('CheekRaiser.'+side,'Y',.020)
        elif channel.startswith('eyeWide'):
            loc(eyelid+'Eyelid_Upper.'+side,'Y',.010 if snow else .020)
            loc(eyelid+'Eyelid_Lower.'+side,'Y',-.008 if snow else -.010)
        elif channel == 'jawOpen':
            bone = rig.pose.bones['Jaw' if snow else 'MSTR-Jaw']
            bone.rotation_mode='XYZ'; bone.rotation_euler.x = math.radians(25)
        elif channel.startswith('mouthSmile'):
            loc('ACT-Lips_Corner.'+side,'Y',.020 if snow else .025)
            loc('ACT-Lips_Corner.'+side,'X' if snow else 'Z',-.012 if snow and side=='L' else .012)
        elif channel.startswith('mouthFrown'):
            loc('ACT-Lips_Corner.'+side,'Y',-.020)
        elif channel in ('mouthPucker','mouthFunnel'):
            for s in ['L','R']:
                if snow:
                    amount=.025 if channel=='mouthPucker' else .016
                    loc('ACT-Lips_Corner.'+s,'X',amount if s=='L' else -amount)
                    loc('ACT-Lips_Corner_Depth.'+s,'Z',-.010)
                else:loc('ACT-Lips_Corner.'+s,'Z',-.030 if channel=='mouthPucker' else -.020)
            if channel=='mouthFunnel':
                jaw=rig.pose.bones['Jaw' if snow else 'MSTR-Jaw']
                jaw.rotation_mode='XYZ';jaw.rotation_euler.x += math.radians(9)
        elif channel=='browInnerUp':
            for s in ['L','R']: loc('MSTR-Eyebrow.'+s,'Y',.022)
        elif channel.startswith('browDown'):
            loc('MSTR-Eyebrow.'+side,'Y',-.020)
        elif channel.startswith('browOuterUp'):
            loc('MSTR-Eyebrow.'+side,'Y',.020)
        elif channel=='cheekPuff':
            for s in ['L','R']:loc(('ACT-CheekPuff.' if snow else 'SK-Cheek_Puff.')+s,'Z' if snow else 'Y',-.020 if snow else .020)
        elif channel.startswith('gaze'):
            positive = channel.endswith('Positive')
            horizontal=channel.startswith('gazeX')
            amount=.23 if positive else -.23
            loc('TGT-Eyes',('X' if horizontal else 'Z') if snow else ('Z' if horizontal else 'Y'),-amount if snow and horizontal else amount)
        else:
            raise ValueError(channel)
    rig.update_tag(refresh={'OBJECT','DATA','TIME'})
    bpy.context.view_layer.update()


def snapshot(objects):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    result=[]
    for obj in objects:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = bpy.data.meshes.new_from_object(evaluated, preserve_all_data_layers=True, depsgraph=depsgraph)
        for i,mat in enumerate(list(mesh.materials)):
            # Snow assigns skin palettes per object rather than on its mesh.
            if i<len(obj.material_slots) and obj.material_slots[i].material:mesh.materials[i]=obj.material_slots[i].material
        mesh.transform(evaluated.matrix_world)
        mesh.update(); mesh.calc_loop_triangles()
        result.append(mesh)
    return result


def bake_material_colors(objects, meshes):
    """Sample the real authored shader base color into per-corner linear RGB."""
    original_scene = bpy.context.scene
    scene = bpy.data.scenes.new('Portrait material bake')
    bpy.context.window.scene=scene
    scene.render.engine='CYCLES'; scene.cycles.samples=1
    scene.render.bake.target='VERTEX_COLORS'
    scene.render.bake.use_clear=True
    material_copies={}
    bake_objects=[]
    for source,mesh in zip(objects,meshes):
        obj=bpy.data.objects.new('Bake-'+source.name,mesh)
        scene.collection.objects.link(obj)
        for i,mat in enumerate(list(mesh.materials)):
            if mat.name not in material_copies:
                copied=mat.copy(); copied.name='Export base color '+mat.name
                if not copied.use_nodes: copied.use_nodes=True
                tree=copied.node_tree
                # A blank Vertex Color node uses the active layer. Pin it before
                # the bake target becomes active, avoiding a self-read of black.
                original_color=mesh.color_attributes.active_color
                for node in tree.nodes:
                    if node.type=='VERTEX_COLOR' and not node.layer_name and original_color:
                        node.layer_name=original_color.name
                outputs=[n for n in tree.nodes if n.type=='OUTPUT_MATERIAL']
                output=next((n for n in outputs if n.is_active_output),outputs[0])
                # Follow the active surface to the artist's Principled shader.
                link=output.inputs['Surface'].links
                shader=link[0].from_node if link else None
                base=shader.inputs.get('Base Color') if shader else None
                emission=tree.nodes.new('ShaderNodeEmission')
                if base and base.links:
                    incoming=base.links[0]
                    tree.links.new(incoming.from_socket,emission.inputs['Color'])
                else:
                    emission.inputs['Color'].default_value=base.default_value if base else mat.diffuse_color
                tree.links.new(emission.outputs[0],output.inputs['Surface'])
                material_copies[mat.name]=copied
            mesh.materials[i]=material_copies[mat.name]
        color=mesh.color_attributes.new(name='PortraitBaked',type='FLOAT_COLOR',domain='CORNER')
        mesh.color_attributes.active_color=color
        obj.select_set(True); bake_objects.append(obj)
    bpy.context.view_layer.objects.active=bake_objects[0]
    bpy.ops.object.bake(type='EMIT')
    values=[]
    for mesh in meshes:
        color=mesh.color_attributes['PortraitBaked']
        data=np.empty(len(color.data)*4,dtype=np.float32)
        color.data.foreach_get('color',data)
        values.append(data.reshape((-1,4))[:,:3])
    bpy.context.window.scene=original_scene
    # Keep snapshots alive, but do not leave objects in the rig's scene.
    for obj in bake_objects: bpy.data.objects.remove(obj,do_unlink=True)
    bpy.data.scenes.remove(scene)
    return values


def surface(obj, material_name):
    if 'hair' in obj.name:
        return (4 if 'band' in obj.name else 1) | 0x80
    if any(part in obj.name for part in ['eyebrow','eyelash']):return 1
    if 'eye' in obj.name:return 2
    if 'teeth' in material_name:return 2
    if 'lip' in material_name:return 3
    if any(part in obj.name for part in ['gums','teeth','tongue']):return 3
    return 0


def srgb(linear):
    linear=np.clip(linear,0,1)
    return np.rint(np.where(linear<=.0031308,12.92*linear,1.055*linear**(1/2.4)-.055)*255).astype(np.uint32)


def main():
    options=arguments()
    bpy.ops.wm.open_mainfile(filepath=str(Path(options.input).resolve()),load_ui=False,use_scripts=False)
    objects=selected_objects(options.name)
    # One deterministic subdivision of the face silhouette; mouth interiors and
    # modeled hair retain their existing topology. No pose-dependent decimation.
    for obj in [o for o in bpy.data.objects if o.type=='MESH']:
        for mod in obj.modifiers:
            if mod.type=='SUBSURF':
                mod.driver_remove('levels');mod.driver_remove('render_levels')
                mod.levels=1 if obj.name.endswith('-head') else 0;mod.render_levels=mod.levels
    for obj in objects:
        obj.hide_viewport=False;obj.hide_set(False)
    rig=bpy.data.objects['RIG-rain' if options.name=='rain' else 'RIG-Snow']
    apply_pose(rig,[],options.name)
    neutral=snapshot(objects)
    colors=bake_material_colors(objects,neutral)
    # Blender front -Y/up +Z -> app front +Z/up +Y, a proper rotation.
    center_z=1.444 if options.name=='rain' else 1.588
    center_y=-.035
    scale=.86 if options.name=='rain' else .78
    rotation=np.array([[1,0,0],[0,0,1],[0,-1,0]],dtype=np.float64)
    source_indices=[]; secondary_indices=[]; blend_weights=[];object_indices=[]; encoded=[]; vertex_colors=[]; triangles=[]
    caps=[]
    for oi,(obj,mesh,paint) in enumerate(zip(objects,neutral,colors)):
        remap={}
        is_head=obj.name.endswith('-head')
        boundary={};representatives={}
        signed_weights=None
        if is_head:
            if options.name=='rain':
                mask_group=obj.vertex_groups['Lattice_Head'].index
                signed_weights=[sum(g.weight for g in v.groups if g.group==mask_group)-.5 for v in mesh.vertices]
            else:
                # Keep Snow's complete chin. Its facial-weight mask cuts through
                # the jaw, so use a shallow sloping plane below the chin instead.
                signed_weights=[v.co.z-(1.483+.40*v.co.y) for v in mesh.vertices]
        elif obj.name.endswith('-tongue'):
            # Rear tongue roots belong inside a full neck. Crop those hidden
            # roots so they cannot peek behind the new floating-head silhouette.
            signed_weights=[-.075-v.co.y for v in mesh.vertices]
        for triangle in mesh.loop_triangles:
            polygon=[]
            for vi,li in zip(triangle.vertices,triangle.loops):
                uv=np.array(mesh.uv_layers.active.data[li].uv) if mesh.uv_layers.active else np.zeros(2)
                polygon.append((vi,vi,0.,uv,paint[li],False))
            if signed_weights is not None:
                clipped=[]
                for previous,current in zip(polygon[-1:]+polygon[:-1],polygon):
                    a,b=previous[0],current[0]
                    wa,wb=signed_weights[a],signed_weights[b]
                    if (wa>=0)!=(wb>=0):
                        fraction=wa/(wa-wb)
                        uv=previous[3]*(1-fraction)+current[3]*fraction
                        color=previous[4]*(1-fraction)+current[4]*fraction
                        if a>b:a,b=b,a;fraction=1-fraction
                        clipped.append((a,b,round(fraction,9),uv,color,True))
                    if wb>=0:clipped.append(current)
                polygon=clipped
            if len(polygon)<3:continue
            result=[];tokens=[]
            material=mesh.materials[triangle.material_index]
            for a,b,fraction,uv,paint_color,cut in polygon:
                rgb=srgb(paint_color)
                color=int(0xff000000 | (rgb[0]<<16) | (rgb[1]<<8) | rgb[2])
                # Keep authored UV/color seams and material boundaries explicit.
                token=(a,b,fraction)
                key=(token,triangle.material_index,round(uv[0],5),round(uv[1],5))
                if key not in remap:
                    remap[key]=len(source_indices)
                    source_indices.append(a);secondary_indices.append(b);blend_weights.append(fraction);object_indices.append(oi)
                    vertex_colors.append(color);encoded.append(surface(obj,material.name))
                result.append(remap[key])
                representatives[token]=remap[key];tokens.append(token)
            for i in range(1,len(result)-1):triangles.extend([result[0],result[i],result[i+1]])
            if is_head:
                for i in range(len(polygon)):
                    j=(i+1)%len(polygon)
                    if polygon[i][5] and polygon[j][5]:boundary[tokens[i]]=tokens[j]
        if is_head:
            # Cap only NEW crop boundaries; the original eye/mouth openings are
            # deliberately preserved. Boundary fans follow the jaw in each pose.
            crop_loops=[]
            while boundary:
                first=next(iter(boundary));loop=[];v=first
                while v in boundary:
                    loop.append(representatives[v]);v=boundary.pop(v)
                assert v==first and len(loop)>=3,'Head crop is not a closed boundary'
                crop_loops.append(loop)
            def loop_area(loop):
                points=np.array([mesh.vertices[source_indices[i]].co*(1-blend_weights[i])+
                    mesh.vertices[secondary_indices[i]].co*blend_weights[i] for i in loop])
                return np.linalg.norm(np.sum(np.cross(points,np.roll(points,-1,axis=0)),axis=0))
            # One solid underside closes the outer skin. A second dark throat
            # cap would overlap it and could create a visible mouth-colored speck.
            for loop in ([max(crop_loops,key=loop_area)] if crop_loops else []):
                center=len(source_indices);source_indices.append(-1);secondary_indices.append(-1);blend_weights.append(0.);object_indices.append(-1)
                rgb=np.mean([[((vertex_colors[i]>>s)&255) for s in (16,8,0)] for i in loop],axis=0).astype(int)
                vertex_colors.append(int(0xff000000|(rgb[0]<<16)|(rgb[1]<<8)|rgb[2]));encoded.append(encoded[loop[0]])
                caps.append((center,loop))
                for a,b in zip(loop,loop[1:]+[loop[0]]):triangles.extend([b,a,center])
        print('EXPORTED_PART',obj.name,len(remap),flush=True)
    source_indices=np.asarray(source_indices);secondary_indices=np.asarray(secondary_indices)
    blend_weights=np.asarray(blend_weights)[:,None];object_indices=np.asarray(object_indices)
    def geometry(meshes):
        positions=np.zeros((len(source_indices),3),dtype=np.float64)
        normals=np.zeros_like(positions)
        for oi,mesh in enumerate(meshes):
            assert len(mesh.vertices)==len(neutral[oi].vertices),'Rig pose changed topology'
            vertices=np.empty(len(mesh.vertices)*3,dtype=np.float64)
            ns=np.empty_like(vertices)
            mesh.vertices.foreach_get('co',vertices);mesh.vertices.foreach_get('normal',ns)
            mask=object_indices==oi
            blend=blend_weights[mask]
            vs=vertices.reshape((-1,3));ns=ns.reshape((-1,3))
            positions[mask]=vs[source_indices[mask]]*(1-blend)+vs[secondary_indices[mask]]*blend
            normals[mask]=ns[source_indices[mask]]*(1-blend)+ns[secondary_indices[mask]]*blend
            normals[mask]/=np.maximum(1e-10,np.linalg.norm(normals[mask],axis=1)[:,None])
        for center,loop in caps:
            positions[center]=np.mean(positions[loop],axis=0)
            edges=positions[loop]-positions[center]
            normal=-np.sum(np.cross(edges,np.roll(edges,-1,axis=0)),axis=0)
            normals[center]=normal/max(1e-10,np.linalg.norm(normal))
        positions[:,1]-=center_y;positions[:,2]-=center_z
        return (positions @ rotation.T)*scale,normals @ rotation.T
    p0,n0=geometry(neutral)
    channels=['eyeBlinkLeft','eyeBlinkRight','eyeSquintLeft','eyeSquintRight','eyeWideLeft','eyeWideRight',
        'jawOpen','mouthSmileLeft','mouthSmileRight','mouthFrownLeft','mouthFrownRight','mouthPucker','mouthFunnel',
        'browInnerUp','browDownLeft','browDownRight','browOuterUpLeft','browOuterUpRight','cheekPuff',
        'cheekSquintLeft','cheekSquintRight','gazeXPositive','gazeXNegative','gazeYPositive','gazeYNegative']
    poses={} if options.neutral_only else dict.fromkeys(channels)
    for channel in list(poses):
        apply_pose(rig,[channel],options.name)
        meshes=snapshot(objects);p,n=geometry(meshes)
        poses[channel]=(p-p0,n-n0)
        for mesh in meshes:bpy.data.meshes.remove(mesh)
        print('EXPORTED_POSE',channel,float(np.max(np.abs(p-p0))),flush=True)
    if not options.neutral_only:
        # Restore nonlinear rig correctives when mouth controls are combined.
        combinations=[('jawOpen','mouthSmileLeft'),('jawOpen','mouthSmileRight'),
            ('mouthSmileLeft','mouthSmileRight'),('jawOpen','mouthPucker'),('jawOpen','mouthFunnel'),
            ('eyeBlinkLeft','eyeSquintLeft'),('eyeBlinkRight','eyeSquintRight')]
        for combo in combinations:
            apply_pose(rig,combo,options.name)
            meshes=snapshot(objects);p,n=geometry(meshes)
            dp=p-p0-sum((poses[c][0] for c in combo));dn=n-n0-sum((poses[c][1] for c in combo))
            poses['*'.join(combo)]=(dp,dn)
            for mesh in meshes:bpy.data.meshes.remove(mesh)
    output=Path(options.output);output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('wb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0,compresslevel=9) as stream:
        stream.write(b'AEPH0001')
        stream.write(struct.pack('>iii',len(p0),len(triangles),len(poses)))
        for array in [p0,n0]:stream.write(array.astype('>f4').tobytes())
        stream.write(np.array(vertex_colors,dtype='>u4').tobytes());stream.write(bytes(encoded))
        stream.write(np.array(triangles,dtype='>i4').tobytes())
        for name,(dp,dn) in poses.items():
            affected=np.nonzero(np.maximum(np.max(np.abs(dp),axis=1),np.max(np.abs(dn),axis=1))>1e-6)[0]
            utf8=name.encode('utf-8');stream.write(struct.pack('>H',len(utf8)));stream.write(utf8)
            stream.write(struct.pack('>i',len(affected)))
            for v in affected:stream.write(struct.pack('>i6f',v,*dp[v],*dn[v]))
    metadata={'source':str(Path(options.input).name),'character':options.name,'vertices':len(p0),'triangles':len(triangles)//3,
        'morphs':list(poses),'bounds_min':p0.min(axis=0).tolist(),'bounds_max':p0.max(axis=0).tolist(),
        'colors':'Cycles EMIT bake of original material base color; linear RGB converted to sRGB',
        'changes':'Head-only crop, one face subdivision, coordinate normalization, facial rig samples and sparse corrective deltas.',
        'embedded_scripts_executed':False,'blender':bpy.app.version_string}
    output.with_name(output.name+'.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    print('PORTRAIT_ASSET',output.resolve(),output.stat().st_size,flush=True)


if __name__=='__main__':main()
