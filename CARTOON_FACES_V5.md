# Reference-inspired avatar faces — Quest 1.2.5 (31)

The [user-provided reference](assets/references/avatar-style-reference.png) guides
the five human presets toward a rounded, expressive avatar style. This is a new
procedural interpretation of the reference; its character models were not imported.

The human heads have wider soft cheeks, a shorter rounded silhouette, lower eyes
with larger dark irises/pupils, fuller rolled lips, a wider smile and a more defined
button nose with nostril wings. Denser sampling around the middle of the face
preserves the nose and lip contours. The tracking rig still controls independent
blinks, gaze, smile, jaw, frown and pucker.

The hairstyles now have distinct geometry:

- Natural: a rounded curly bob with crown/side curl clusters and open round gold
  glasses. The frames have a bridge and temple arms, with no opaque lens overlay.
- Bearded: a short swept quiff with a faded beard and separate moustache.
- Gold: a classical curled hairline and temple curls with the existing gold finish.
- Holo: a swept crown and three intertwined strands forming a side braid.
- Cyber: the pale ceramic cap remains, with the updated facial proportions below.

Hair meshes use a bounded cache of 24 style/color/detail combinations. Tracking
updates append copies of the static hair buffers, so speech cannot stretch the
hairstyle or mutate a cached sculpt. The hair visibility control remains available.
The Natural preset's frames stay visible when its hair is hidden.

Animal head anatomy, Meta/Horizon and Meta sample rendering remain unchanged.
The shared human meshes update the native scene, software mirror, wearer view
and webcam frames. No Windows receiver update is required.

## Preview and validation

- [Final reference-inspired collection](assets/previews/reference-avatars-v5.png)
- Signed release assembly and **338 JVM tests** passed: zero failures, errors or
  skipped tests. APK Signature Scheme v2 verification passed.
- Added tests cover rigid hairstyles during speech, isolation of cached buffers,
  and distinct finite/normalized/indexable hairstyle geometry at maximum detail.
- Existing blink, jaw, gaze, color, surface partition, winding and depth-buffer
  regressions passed. The expanded Natural mesh stays below 12,000 vertices at
  maximum detail; the workload test includes the glasses and curly hairstyle.
- Eighty final expression/rotation QA cases and eight smiling artwork poses were
  rendered and visually inspected using the actual Kotlin depth buffer and
  surface lighting. The Natural inspection includes 13 front/turned/profile,
  blink/wink, gaze, speech and expression cases with its glasses in place.

These are offline software renders of the real app geometry. Native PBR lighting,
headset fit, live movement and mobile frame time still need device testing.
No device installation was performed.

APK: `app/build/outputs/apk/release/app-release.apk`, version **1.2.5 (31)**,
arm64, Android API 34 minimum. SHA-256:

```text
1F1C9F06878AC834E3490F9D0EFBED9DADE56C3B24A2F0188205D967C24252C1
```

## Reproduce

```powershell
.\build_quest_release.ps1 -Offline
.\scripts\build-portrait-preview.ps1 -Detail 20 `
    -RasterDirectory scratch/reference-v5-final-raster -Output scratch/reference-v5-final.json
.\scripts\build-portrait-preview.ps1 -ArtPortraits -Detail 20 `
    -RasterDirectory scratch/reference-v5-art-raster -Output scratch/reference-v5-art.json
node scripts/render-portrait-preview.cjs scratch/reference-v5-art.json `
    assets/previews/reference-avatars-v5.png <path-to-sharp-module> --gallery --art --use-app-raster
```
