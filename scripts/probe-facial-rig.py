"""Inspect an artist rig without running its embedded UI scripts or changing sources.

Run with Blender --background --factory-startup --disable-autoexec --python ... --
  --input model.blend --output scratch/authored-avatars/rain-rig.json
The output is metadata plus measured evaluated-mesh motion, not a new asset.
"""
import argparse
import collections
import json
import math
from pathlib import Path
import re
import sys

import bpy
from mathutils import Matrix, Vector


def args():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--name', default='')
    parser.add_argument('--skip-calibration', action='store_true')
    parser.add_argument('--preview-directory', default='')
    return parser.parse_args(argv)


def plain(value):
    if isinstance(value, (str, bool, int, float)) or value is None:
        return value
    if isinstance(value, bpy.types.ID):
        return {'id':value.name,'type':value.__class__.__name__}
    if hasattr(value, 'keys'):
        return {str(key):plain(value[key]) for key in value.keys()}
    try:
        return [plain(item) for item in value]
    except Exception:
        return str(value)


def custom_properties(item):
    return {key: plain(item[key]) for key in item.keys()
            if key != '_RNA_UI' and not isinstance(item[key], bpy.types.ID)}


def bounds(points):
    if not points:
        return None
    return {'min': [min(p[i] for p in points) for i in range(3)],
            'max': [max(p[i] for p in points) for i in range(3)]}


def freeze_demo_animation():
    """Remove active demo animation while preserving deformation drivers."""
    for item in list(bpy.data.objects) + list(bpy.data.shape_keys):
        anim = item.animation_data
        if anim:
            anim.action = None
            for track in anim.nla_tracks:
                track.mute = True
    for rig in [obj for obj in bpy.data.objects if obj.type == 'ARMATURE']:
        rig.data.pose_position = 'POSE'
        for bone in rig.pose.bones:
            bone.matrix_basis = Matrix.Identity(4)
    for key in bpy.data.shape_keys:
        driven = {curve.data_path for curve in key.animation_data.drivers} if key.animation_data else set()
        for block in key.key_blocks:
            if block.name == 'Basis':
                continue
            if block.path_from_id('value') not in driven:
                block.value = 0
    bpy.context.scene.frame_set(1)
    bpy.context.view_layer.update()


def mesh_snapshot(obj):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh()
    try:
        world = evaluated.matrix_world
        return [tuple(world @ vertex.co) for vertex in mesh.vertices]
    finally:
        evaluated.to_mesh_clear()


def delta_stats(before, after):
    if len(before) != len(after):
        return {'topology_changed': True, 'before': len(before), 'after': len(after)}
    moved = []
    for i, (a, b) in enumerate(zip(before, after)):
        delta = tuple(b[k] - a[k] for k in range(3))
        distance = math.sqrt(sum(v*v for v in delta))
        if distance > 1e-7:
            moved.append((distance, i, a, b, delta))
    moved.sort(reverse=True)
    count = len(moved)
    return {'vertex_count': len(before), 'moved_count': count,
            'max_distance': moved[0][0] if moved else 0,
            'rms_distance': math.sqrt(sum(p[0]**2 for p in moved)/max(1,len(before))),
            'mean_delta_moved': [sum(p[4][k] for p in moved)/max(1,count) for k in range(3)],
            'affected_neutral_bounds': bounds([p[2] for p in moved]),
            'largest_changes': [{'index': i, 'neutral': a, 'posed': b, 'delta': d}
                                for distance,i,a,b,d in moved[:6]]}


def control_constraint(c):
    fields = ['subtarget','transform_channel','target_space','owner_space',
              'frame_start','frame_end','min','max','use_eval_time',
              'eval_time','influence','mix_mode']
    return {p:plain(getattr(c,p,None)) for p in fields} | {
        'name':c.name, 'target':c.target.name if c.target else None,
        'action':c.action.name if c.action else None}


def pose_metadata(rig):
    bones = []
    for bone in rig.pose.bones:
        # Include face controls; omit thousands of mechanical/deform bones.
        if not re.search(r'eye|jaw|lip|mouth|brow|cheek|nose|head|neck|Properties',bone.name,re.I):
            continue
        if bone.name.startswith(('DEF-','BB-','P-BB-','TAN-','AIM-TAN-','N-','P-GRP-','GRP-','ROT-')):
            continue
        constraints = []
        for c in bone.constraints:
            if c.type == 'ACTION':
                constraints.append({'type':c.type,**control_constraint(c)})
            elif c.type in {'LIMIT_LOCATION','LIMIT_ROTATION','LIMIT_SCALE'}:
                constraints.append({'name':c.name,'type':c.type,**{
                    p:plain(getattr(c,p,None)) for p in [
                        'owner_space','use_min_x','use_min_y','use_min_z',
                        'use_max_x','use_max_y','use_max_z','min_x','min_y','min_z',
                        'max_x','max_y','max_z','use_limit_x','use_limit_y','use_limit_z']}})
        bones.append({'name':bone.name,'parent':bone.parent.name if bone.parent else None,
                      'location':list(bone.location),'rotation_mode':bone.rotation_mode,
                      'rotation_euler':list(bone.rotation_euler),'scale':list(bone.scale),
                      'lock_location':list(bone.lock_location),
                      'lock_rotation':list(bone.lock_rotation),'lock_scale':list(bone.lock_scale),
                      'head':list(bone.head),'tail':list(bone.tail),
                      'rest_matrix': [list(row) for row in bone.bone.matrix_local],
                      'properties':custom_properties(bone),'constraints':constraints})
    return bones


def all_action_constraints(rig):
    found = []
    seen = set()
    for bone in rig.pose.bones:
        for c in bone.constraints:
            if c.type != 'ACTION':
                continue
            data = control_constraint(c)
            key = json.dumps(data,sort_keys=True)
            if key not in seen:
                found.append(data); seen.add(key)
    return found


def set_channel(bone, channel, value):
    axis = 'XYZ'.index(channel[-1])
    if channel.startswith('LOCATION'):
        bone.location[axis] = value
    elif channel.startswith('ROTATION'):
        bone.rotation_mode = 'XYZ'
        bone.rotation_euler[axis] = math.radians(value)
    elif channel.startswith('SCALE'):
        bone.scale[axis] = value
    else:
        raise ValueError(channel)


def render_calibration_previews(directory, rigs):
    """Workbench images inspect lid/lip contact, without claiming final material quality."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.render.resolution_x = 640
    scene.render.resolution_y = 640
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.display.shading.light = 'STUDIO'
    scene.display.shading.color_type = 'MATERIAL'
    scene.display.shading.show_shadows = True
    scene.display.shading.show_cavity = False
    scene.display.shading.show_specular_highlight = True
    for obj in bpy.data.objects:
        if obj.type == 'MESH':
            keep = bool(re.search(r'^GEO-.*(?:head$|eyes$|eyelash|eyebrow|gums|teeth|tongue)',obj.name,re.I)) and not re.search(r'helper|deformer|eye_anim',obj.name,re.I)
            obj.hide_render = not keep
            if obj.name in bpy.context.view_layer.objects:
                obj.hide_set(not keep)
            obj.hide_viewport = not keep
    camera_data = bpy.data.cameras.new('Rig calibration camera')
    camera = bpy.data.objects.new('Rig calibration camera', camera_data)
    scene.collection.objects.link(camera)
    camera_data.type = 'ORTHO'
    snow = any(rig.name == 'RIG-Snow' for rig in rigs)
    center_z = 1.60 if snow else 1.46
    camera_data.ortho_scale = .40 if snow else .36
    camera.location = (0,-3,center_z)
    camera.rotation_euler = (Vector((0,0,center_z))-camera.location).to_track_quat('-Z','Y').to_euler()
    scene.camera = camera
    poses = [
        ('neutral', []),
        ('blink-upper-025', [('ACT-Eyelid_Upper.L','LOCATION_Y',-.025),('ACT-Eyelid_Upper.R','LOCATION_Y',-.025)]),
        ('blink-upper-030-lower-005', [('ACT-Eyelid_Upper.L','LOCATION_Y',-.030),('ACT-Eyelid_Upper.R','LOCATION_Y',-.030),('ACT-Eyelid_Lower.L','LOCATION_Y',.005),('ACT-Eyelid_Lower.R','LOCATION_Y',.005)]),
        ('blink-upper-040', [('ACT-Eyelid_Upper.L','LOCATION_Y',-.04),('ACT-Eyelid_Upper.R','LOCATION_Y',-.04)]),
        ('smile-025-wide-012', [('ACT-Lips_Corner.L','LOCATION_Y',.025),('ACT-Lips_Corner.R','LOCATION_Y',.025),('ACT-Lips_Corner.L','LOCATION_Z',.012),('ACT-Lips_Corner.R','LOCATION_Z',.012)]),
        ('pucker-030', [('ACT-Lips_Corner.L','LOCATION_Z',-.03),('ACT-Lips_Corner.R','LOCATION_Z',-.03)]),
        ('jaw-25', [('MSTR-Jaw','ROTATION_X',25)]),
    ]
    if snow:
        poses = [
            ('neutral', []),
            ('blink-upper-020', [('MSTR-Eyelid_Upper.L','LOCATION_Y',-.020),('MSTR-Eyelid_Upper.R','LOCATION_Y',-.020)]),
            ('blink-upper-025-lower-003', [('MSTR-Eyelid_Upper.L','LOCATION_Y',-.025),('MSTR-Eyelid_Upper.R','LOCATION_Y',-.025),('MSTR-Eyelid_Lower.L','LOCATION_Y',.003),('MSTR-Eyelid_Lower.R','LOCATION_Y',.003)]),
            ('blink-upper-030', [('MSTR-Eyelid_Upper.L','LOCATION_Y',-.030),('MSTR-Eyelid_Upper.R','LOCATION_Y',-.030)]),
            ('smile-020-wide-012', [('ACT-Lips_Corner.L','LOCATION_Y',.020),('ACT-Lips_Corner.R','LOCATION_Y',.020),('ACT-Lips_Corner.L','LOCATION_X',-.012),('ACT-Lips_Corner.R','LOCATION_X',.012)]),
            ('pucker-025-forward-010', [('ACT-Lips_Corner.L','LOCATION_X',.025),('ACT-Lips_Corner.R','LOCATION_X',-.025),('ACT-Lips_Corner_Depth.L','LOCATION_Z',-.010),('ACT-Lips_Corner_Depth.R','LOCATION_Z',-.010)]),
            ('jaw-25', [('Jaw','ROTATION_X',25)]),
        ]
    for name, transforms in poses:
        freeze_demo_animation()
        for rig in rigs:
            for bone, channel, value in transforms:
                if bone in rig.pose.bones:
                    set_channel(rig.pose.bones[bone],channel,value)
            rig.update_tag(refresh={'OBJECT','DATA','TIME'})
        bpy.context.view_layer.update()
        scene.render.filepath = str((directory/(name+'.png')).resolve())
        bpy.ops.render.render(write_still=True)


def main():
    options = args()
    bpy.ops.wm.open_mainfile(filepath=str(Path(options.input).resolve()),
                             load_ui=False,use_scripts=False)
    original_rigs = [obj for obj in bpy.data.objects if obj.type == 'ARMATURE']
    report = {'source':str(Path(options.input).resolve()),'name':options.name,
              'blender':bpy.app.version_string,'embedded_scripts_executed':False,
              'text_blocks':[{'name':t.name,'characters':len(t.as_string())} for t in bpy.data.texts],
              'original_pose':{rig.name:pose_metadata(rig) for rig in original_rigs},
              'actions':[{'name':act.name,'frame_range':list(act.frame_range),
                          'asset':bool(act.asset_data),'curve_count':len(act.fcurves),
                          'pose_markers':[{'name':marker.name,'frame':marker.frame}
                                          for marker in act.pose_markers],
                          'paths':sorted({curve.data_path for curve in act.fcurves})}
                         for act in bpy.data.actions]}
    # Low-resolution topology is adequate for calibrating controller signs and is quick.
    for obj in bpy.data.objects:
        for modifier in obj.modifiers:
            if modifier.type == 'SUBSURF':
                modifier.levels = 0
                modifier.render_levels = 0
    freeze_demo_animation()
    rigs = [obj for obj in bpy.data.objects if obj.type == 'ARMATURE']
    report['rigs'] = {}
    for rig in rigs:
        report['rigs'][rig.name] = {'properties':custom_properties(rig),
                                  'controls':pose_metadata(rig),
                                  'action_constraints':all_action_constraints(rig)}
    meshes = [obj for obj in bpy.data.objects if obj.type == 'MESH' and obj.name.startswith('GEO-')]
    report['meshes'] = []
    for obj in meshes:
        points = mesh_snapshot(obj)
        report['meshes'].append({'name':obj.name,'source_vertices':len(obj.data.vertices),
            'evaluated_vertices':len(points),'world_bounds':bounds(points),
            'hide_render':obj.hide_render,'hide_viewport':obj.hide_viewport,
            'materials':[slot.material.name if slot.material else None for slot in obj.material_slots],
            'modifiers':[{'name':m.name,'type':m.type,'viewport':m.show_viewport,'render':m.show_render}
                         for m in obj.modifiers],
            'shape_keys':[key.name for key in obj.data.shape_keys.key_blocks] if obj.data.shape_keys else [],
            'vertex_groups':[group.name for group in obj.vertex_groups]})
    report['images'] = [{'name':image.name,'source':image.source,'filepath':image.filepath,
                         'size':list(image.size),'tiles':[tile.number for tile in image.tiles]
                         if image.source=='TILED' else []} for image in bpy.data.images]
    report['drivers'] = []
    for item in list(bpy.data.objects)+list(bpy.data.shape_keys):
        if not item.animation_data:
            continue
        for curve in item.animation_data.drivers:
            report['drivers'].append({'owner':item.name,'path':curve.data_path,'index':curve.array_index,
                'valid':curve.is_valid,'driver_type':curve.driver.type,'expression':curve.driver.expression,
                'variables':[{'name':v.name,'type':v.type,'targets':[{
                    'id':t.id.name if t.id else None,'data_path':t.data_path,
                    'bone':t.bone_target,'channel':t.transform_type,'space':t.transform_space}
                    for t in v.targets]} for v in curve.driver.variables]})
    report['calibration'] = []
    if not options.skip_calibration:
        # Head / eyes / teeth are enough to measure facial motion. Hair is irrelevant here.
        face_meshes = [obj for obj in meshes if re.search(r'head|eyes(?:$|_)|eyebrow|eyelash|gum|teeth|tongue',obj.name,re.I) and not re.search(r'helper|deformer',obj.name,re.I)]
        for obj in face_meshes:
            obj.hide_viewport = False
            obj.hide_set(False)
        bpy.context.view_layer.update()
        report['calibration_unhides_face_meshes'] = True
        neutral = {obj.name:mesh_snapshot(obj) for obj in face_meshes}
        candidates = []
        seen = set()
        for rig in rigs:
            for c in all_action_constraints(rig):
                bone_name = c['subtarget']; channel = c['transform_channel']
                if not bone_name or c['target'] != rig.name or bone_name not in rig.pose.bones:
                    continue
                for value in [c['min'],c['max']]:
                    identity = (rig.name,bone_name,channel,round(value,7))
                    if identity in seen:
                        continue
                    seen.add(identity)
                    candidates.append((rig,bone_name,channel,value,c['action']))
            # Jaw and gaze are usually directly transformed rather than Action-driven.
            for bone_name in ['MSTR-Jaw','TGT-Eyes','TGT-Eye.L','TGT-Eye.R']:
                if bone_name not in rig.pose.bones:
                    continue
                choices = [('ROTATION_X',v) for v in [-15,15,30]] if 'Jaw' in bone_name else [
                    (channel,v) for channel in ['LOCATION_X','LOCATION_Y','LOCATION_Z'] for v in [-.1,.1]]
                for channel,value in choices:
                    candidates.append((rig,bone_name,channel,value,'direct'))
            if rig.name == 'RIG-Snow':
                # Snow v4 drives Action.eval_time through secondary properties.
                # Inspect physical controls directly; keep the metarig untouched.
                controls = {
                    'Jaw': [('ROTATION_X',v) for v in [-15,15,25]],
                    'MSTR-Eyelid_Upper.L': [('LOCATION_Y',v) for v in [-.03,-.025,.01]],
                    'MSTR-Eyelid_Upper.R': [('LOCATION_Y',v) for v in [-.03,-.025,.01]],
                    'MSTR-Eyelid_Lower.L': [('LOCATION_Y',v) for v in [-.01,.01]],
                    'MSTR-Eyelid_Lower.R': [('LOCATION_Y',v) for v in [-.01,.01]],
                    'ACT-Lips_Corner.L': [('LOCATION_Y',v) for v in [-.03,.025]]+ [('LOCATION_X',v) for v in [-.04,.025]],
                    'ACT-Lips_Corner.R': [('LOCATION_Y',v) for v in [-.03,.025]]+ [('LOCATION_X',v) for v in [.04,-.025]],
                    'ACT-Lips_Corner_Depth.L': [('LOCATION_Z',v) for v in [-.02,.02]],
                    'ACT-Lips_Corner_Depth.R': [('LOCATION_Z',v) for v in [-.02,.02]],
                    'MSTR-Eyebrow.L': [('LOCATION_Y',v) for v in [-.025,.02]]+[('LOCATION_X',-.014)],
                    'MSTR-Eyebrow.R': [('LOCATION_Y',v) for v in [-.025,.02]]+[('LOCATION_X',.014)],
                    'ACT-CheekPuff.L': [('LOCATION_Z',-.02)],
                    'ACT-CheekPuff.R': [('LOCATION_Z',-.02)],
                    'CheekRaiser.L': [('LOCATION_Y',.02)],
                    'CheekRaiser.R': [('LOCATION_Y',.02)],
                }
                for bone_name, poses in controls.items():
                    if bone_name in rig.pose.bones:
                        for channel,value in poses:
                            candidates.append((rig,bone_name,channel,value,'snow-direct'))
        for rig,bone_name,channel,value,action in candidates:
            freeze_demo_animation()
            set_channel(rig.pose.bones[bone_name],channel,value)
            rig.update_tag(refresh={'OBJECT','DATA','TIME'})
            bpy.context.view_layer.update()
            sample={'rig':rig.name,'bone':bone_name,'channel':channel,'value':value,
                    'action':action,'meshes':{}}
            for obj in face_meshes:
                delta=delta_stats(neutral[obj.name],mesh_snapshot(obj))
                if delta.get('max_distance',0)>1e-6 or delta.get('topology_changed'):
                    sample['meshes'][obj.name]=delta
            report['calibration'].append(sample)
            print('CALIBRATED',bone_name,channel,value,flush=True)
    output = Path(options.output)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('RIG_REPORT',output.resolve(),flush=True)
    if options.preview_directory:
        render_calibration_previews(options.preview_directory,rigs)


if __name__ == '__main__':
    main()
