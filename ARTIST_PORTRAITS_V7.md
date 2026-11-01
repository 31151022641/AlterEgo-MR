# Artist-created human heads — 1.2.7 (33)

The five human presets now load adapted Blender Studio Rain and Snow geometry.
Rain Portrait and Holographic Matrix Human use Rain; Snow Portrait, Classical
Golden Bust and Cyber-Synth Human use Snow. Existing saved enum/preset IDs are
retained. The Meta/Horizon, Meta sample and animal renderer paths are preserved.

The artist's modeled head, eyes, brows/lashes, hair, teeth, gums and tongue replace
the procedural human forms. Cycles bakes the original shader base colors into
opaque vertex colors, with painted attributes and UDIM textures resolved before
export. Renderers then supply their own lighting. Materials are grouped into the
existing skin, hair, eye, mouth and accent finishes. Transparent cornea shells
and alternate viewport eyes are excluded to suit the app's opaque mesh format.

Each model contains 25 expression/gaze bases and seven nonlinear corrective
samples. Rig evaluation preserves bone-driven actions and shape-key correctives;
embedded source scripts are disabled. Pucker and funnel share a normalized
activation budget to avoid extrapolating the same lip narrowing twice. Optional
hair hides the hairstyle while retaining brows and lashes. Theme palettes retain
the same animated topology. A missing development asset can use the procedural
fallback; corrupt packaged data fails explicitly.

The production conversion uses Blender 4.2.3 LTS, one fixed face subdivision,
neutral-space head clipping with interpolated boundary vertices, an outer
underside cap, and a compact FK pose for Rain's ponytail. Mouth-interior rear
roots are cropped to fit a floating head. Snow's original file identifies Blender
4.4 as its writer; the extracted geometry, materials and facial rig are evaluated
and checked in the listed converter runtime. The original ZIPs remain untouched.

To reproduce from the project directory, use the portable Blender executable
under `scratch/tools/blender/blender-4.2.3-windows-x64/`:

```powershell
$blender = 'scratch/tools/blender/blender-4.2.3-windows-x64/blender.exe'
& $blender --background --factory-startup --disable-autoexec --python scripts/export-authored-portrait.py -- --input 'scratch/authored-avatars/rain/Rain v3.3/rain_v3.2.blend' --name rain --output app/src/main/assets/portraits/rain.aeph
& $blender --background --factory-startup --disable-autoexec --python scripts/export-authored-portrait.py -- --input 'scratch/authored-avatars/snow/Snow/snow_v4.2.blend' --name snow --output app/src/main/assets/portraits/snow.aeph
.\build_quest_release.ps1 -Offline
```

The converter writes deterministic gzip streams with positions, normals, opaque
colors, surface/hairstyle flags, triangle indices and sparse position/normal
deltas. The adjacent JSON records topology, morph names, bounds and conversion
details. The `.aeph` extension deliberately keeps Android's asset packager from
automatically unpacking and renaming `.gz` files. The release script verifies
both compressed payloads at their runtime paths inside the actual APK.
Both Activities install the Android asset loader. The JVM preview loads
the identical files and exercises `PortraitMesh.build`, `PortraitSurface.shade`
and `PortraitDepthBuffer`; source promotional artwork is not an app screenshot.

The five-preset preview is
[`assets/previews/artist-human-faces-v7.png`](assets/previews/artist-human-faces-v7.png).
Expression QA renders in `scratch/authored-v7-raster` cover 53 poses, including
profile, blink, wink, gaze, smile, speech and stress combinations. The actual
asset tests require both models, validate declared topology and material colors,
check independent expression/gaze motion and normalized normals, and measure
closed-lid occlusion in the shared rasterizer. Additional tests cover the binary
reader and the wearer batching optimization, including per-pixel body layering.

Final release verification: all **356 JVM tests** pass, with no failures, errors
or skipped tests. The signed APK reports version **1.2.7 (33)** and passes Android
APK Signature Scheme v2 verification. The package check confirms both compressed
portrait streams at the exact runtime paths and includes both attribution files.
Rain contains 36,981 vertices / 68,585 triangles; Snow contains 26,821 vertices /
48,097 triangles. Each includes 32 morph records. The final APK is 141,363,456
bytes, SHA-256
`16AE70CA643CB5FFFF0A221A0643043CDDCA6604BD32A165552120E4F02FC165`.

These offline checks do not establish native headset appearance, alignment,
expression fidelity from physical tracking, or sustained performance. The
characters preserve Rain/Snow's authored proportions and are not exact copies
of the user's Memoji sample. Source/license details and required credits are in
[`ARTIST_PORTRAITS_LICENSES.md`](ARTIST_PORTRAITS_LICENSES.md); the APK includes the
full CC BY 4.0 license and visible credits in Faces and Settings.
