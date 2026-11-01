# Cartoon face update — Quest 1.2.1 (27)

The user requested a cartoon/Memoji style for the faces other than Meta/Horizon
and the Meta sample. All eight built-in presets now use the same procedural mesh
in regular mirror/video, native stereo passthrough and the wearer fallback view.
Meta's avatar assets, tracking, sample renderer and native SDK integration were
not changed. Imported photos retain their existing static-mask behavior.

## Appearance

- Continuous rounded skull, fuller cheeks and a compact integrated nose.
- Larger almond eyes, clipped irises, pupils, catchlights and independent eyelids.
- Curved lips, recessed mouth, teeth and tongue driven by existing expressions.
- Conforming swept hair and a beard following a smooth cheek/jaw contour.
- Distinct lion muzzle/mane, dragon snout/crest/horns, and unicorn muzzle,
  pointed ears, pastel horn and rainbow mane.
- Readable steel-blue cyber palette and consistent colors across renderers.
- Stable neutral fitting bounds: speaking no longer scales or shifts the head.
- Correct front-facing lip, teeth and lash triangles when backface culling is used.
- 384 px gallery images with room for horns; hairstyle visibility remains optional.

## Validation and limits

The final signed **1.2.1 (27)** APK built successfully. All **319 JVM tests**
passed with zero failures, errors or skipped tests; APK v2 signature verification
passed. The final eight-face gallery and 24-pose preview were visually inspected.

`PortraitMeshTest` has seven regression tests covering finite normalized buffers,
valid indices, independent anatomical blinks, chin motion with stationary forehead,
invalid/extreme inputs, bounded detail, stable topology/gaze, and front-facing
mouth/lash surfaces. The signed release and full JVM suite are built using:

```powershell
.\build_quest_release.ps1 -Offline
```

The offline preview harness exports the actual Kotlin model, then renders its
indexed geometry with smooth lighting, depth testing and backface culling.
Twenty-four cases cover all eight styles, head turns, profile, speaking, smile,
blink, wink, pucker, frown and extreme expressions. The compact collection is
[assets/previews/cartoon-faces.png](assets/previews/cartoon-faces.png).
Sources are `scripts/PortraitMeshPreview.java` and
`scripts/render-portrait-preview.cjs`; they use the compiled model/Kotlin runtime
and Node.js/Sharp, respectively. These are geometry previews rather than headset
captures. Runtime lighting, Holo wire overlay and perspective can differ.

No authorized headset was connected during this update. Live expression fit,
mobile frame rate, camera occlusion and appearance in the headset remain physical
QA tasks. The APK was not installed on a device. No Windows receiver update is
required because the headset sends the resulting avatar video through the existing
protocol.
