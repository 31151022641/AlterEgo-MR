# Rebuilt cartoon characters — Quest 1.2.2 (28)

This update replaces the previous long oval faces with compact rounded character
heads, larger layered eyes, short button noses and lips that follow the curved
face. Hair uses continuous swept volumes, and the short beard joins its moustache.
Cyber and Holo have distinct inlays; Gold uses metallic material settings in the
native scene. Lion cub, baby dragon and pony unicorn use independent creature
geometry with integrated muzzles, manes, ears and horns. User-selected creature
colors remain supported.

## Rendering repair

The previous software renderer sorted triangles by average depth. That cannot
resolve interleaving skin, iris, hair and beard surfaces: it produced speckled eyes
and a checkerboard beard even when a conventional depth-rendered preview looked
correct. `PortraitDepthBuffer` now tests and interpolates each covered pixel.
`PortraitRasterizer` composites only the portrait region onto the existing frame.
Both the regular mirror/video face painter and wearer fallback use it; the native
scene already uses GPU depth testing. Colors and lighting remain interpolated
from actual mesh vertices.

Meta/Horizon, Meta sample assets and tracking, transport and imported static photo
masks were not redesigned. No Windows receiver update is needed for this video.

## Validation

- Signed release build and all **329 JVM tests** passed with no failures, errors
  or skipped tests.
- Mesh tests cover bounded finite geometry, normalized normals, anatomical blink
  isolation, stable expression topology, gaze, hair/beard volume and front winding.
- Creature tests cover extreme tracking inputs, color customization and blinks.
- Depth regression tests cover intersecting surfaces in either submission order,
  batched drawing, per-frame reset, clipping/culling and interpolated colors.
- Eight final face previews were produced by the **actual Kotlin depth buffer**,
  using the application lighting. Eighty expression/rotation cases were inspected
  during the redesign. After the final human hair/beard refinement, 26 human and
  bearded expression/rotation cases were regenerated and visually checked.

The [final gallery](assets/previews/cartoon-faces-v2.png) is an offline render of
actual geometry, not a headset capture. Headset fit, live movement and mobile
frame time still require physical QA. No device installation was performed.

## Reproduce

```powershell
.\build_quest_release.ps1 -Offline
.\scripts\build-portrait-preview.ps1 -Quick -Detail 20 `
    -RasterDirectory scratch/portrait-final-raster `
    -Output scratch/portrait-final-mesh.json
node scripts/render-portrait-preview.cjs scratch/portrait-final-mesh.json `
    assets/previews/cartoon-faces-v2.png <path-to-sharp-module> --gallery --use-app-raster
```

The preview compiler uses cached local Kotlin dependencies and Java 17; it does
not download models or render wearer/camera images.
