# Human face refinement — Quest 1.2.6 (32)

The user selected the overall style across all human faces in the
[reference collage](assets/references/avatar-style-reference.png). This release
refines Natural, Bearded, Gold, Cyber and Holo. It remains a procedural
interpretation: it does not reconstruct the original character models or match
their sculpting, hair and rendering quality exactly.

The head now uses independently sculpted width and depth profiles for the
cranium, cheeks, jaw and chin. Interpolation of squared radii gives the chin a
rounded closure. Bearded has a slightly fuller jaw. A separate curved nose-tip
surface sits in the continuous bridge and nostril-wing volumes, giving the nose
a rounded profile instead of a shallow pointed bump.

Eyes have more depth in their sockets, smaller irises and pupils, rolled upper
and lower lids, softer skin transitions and thicker shaped brows. Bearded and
Gold have a slightly more relaxed eye aperture. Lip rolls have more volume and
blend into the surrounding skin; the smile has a wider curved tooth band.

Natural's dark hair uses continuous wavy locks, a sculpted crown and a smaller
rear mass. Bearded, Gold and Holo have swept strands following the cap surface.
Locks have rounded ends and subtle longitudinal channels. The existing bounded
hair cache keeps these static while expressions animate. Natural's gold frames
are repositioned for the new nose and eyes.

The same meshes feed the native scene, mirror, wearer view, gallery and webcam.
Animal meshes and Meta/Horizon and Meta sample rendering were not changed.
The Windows receiver does not need an update.

## Validation

- [Five human presets](assets/previews/reference-human-faces-v6.png): actual app
  geometry rendered with the Kotlin depth buffer and shared vertex lighting.
- Signed release build and **339 JVM tests** passed, with zero failures, errors
  or skipped tests. APK Signature Scheme v2 verification passed.
- A new regression checks nose projection and volume near the base of the chin.
  Existing topology, independent blink, gaze, jaw, normal, winding, material and
  bounded-workload checks pass. Natural uses **11,988 vertices** and 20,608
  triangles at maximum detail, below the existing 12,000-vertex limit.
- Visually inspected 53 final expression/rotation views across the five human
  presets and five smiling artwork poses, all at detail 24. This includes front,
  left/right turn, profile, smile, speech, blink, wink and gaze, plus pucker,
  surprise, frown and squint for Natural and Bearded.

These are offline software renders. Native PBR appearance, headset alignment,
live sensor motion and mobile frame time remain untested for this release.
No device installation was performed.

APK: `app/build/outputs/apk/release/app-release.apk`, version **1.2.6 (32)**,
arm64, API 34 minimum, 127,938,454 bytes. SHA-256:

```text
56BE04430AB74C11FC23853B1563407E280A2900A26F367E5F8289E360E6A6CC
```

## Reproduce

```powershell
.\build_quest_release.ps1 -Offline
.\scripts\build-portrait-preview.ps1 -Detail 24 `
    -Styles 'natural,beard,gold,cyber,holo' `
    -RasterDirectory scratch/reference-v6-final-raster -Output scratch/reference-v6-final.json
.\scripts\build-portrait-preview.ps1 -ArtPortraits -Detail 24 `
    -Styles 'natural,beard,gold,cyber,holo' `
    -RasterDirectory scratch/reference-v6-art-final-raster -Output scratch/reference-v6-art-final.json
node scripts/render-portrait-preview.cjs scratch/reference-v6-art-final.json `
    assets/previews/reference-human-faces-v6.png <path-to-sharp-module> `
    --human-gallery --art --cols=3 --use-app-raster
```
