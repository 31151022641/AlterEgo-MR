# Sculpted character artwork — Quest 1.2.4 (30)

This pass refines the eight built-in heads as stylized character sculptures while
retaining the distinct horse, dragon and feline anatomy introduced in 1.2.3.

- Lion: one closed, higher-resolution mane volume with swept fur ridges, a warm
  copper/amber palette, larger eyes and glossy nose. The mane has no overlapping
  backing/front shells.
- Unicorn: gently fluted pastel locks, lavender coat shading and a horn with
  actual spiral relief. More samples along the horn keep the spiral smooth.
- Dragon: a richer emerald/teal coat, restrained cheek scales, amber slit eyes
  and polished horns. The broad snout, frills, crest and fangs remain intact.
- Human portraits: more defined button noses, warmer eye sockets and flowing
  hair highlights. Cyber has a pale ceramic cap; Holo has a teal/blue hair palette.

## Surface rendering

The geometry carries independent surface assignments for skin, hair/fur, eyes,
mouth, luminous accents and horns. The software gallery, mirror and webcam views
use the same pure Kotlin `PortraitSurface.shade` function: soft warm key lighting,
cool fill, an edge reflection and finish-specific highlights.

The native scene partitions triangle indices into material sections and assigns
separate PBR finishes. Eyes use lower roughness than skin; fur/hair use a satin
finish; horns have a polished finish; Gold uses metallic material settings;
Cyber's cap has a partial metallic finish. Accent sections stay luminous using
an unlit material. The mesh remains one scene object per tracked face, with
material ranges rebuilt when the style/detail changes. Material range semantics
follow the [Meta SDK mesh API](https://developers.meta.com/vr/reference/spatial-sdk/v0.13.2/com_meta_spatial_runtime_scenemesh/).

Meta/Horizon, Meta sample rendering, tracking and imported photo masks were not
redesigned. No Windows receiver update is required for these transmitted frames.

## Preview and validation

- [Character artwork: soft smiles and a 25-degree turn](assets/previews/character-art-v4.png)
- [Animal heads: front, turn and profile](assets/previews/animal-heads-v4.png)
- Signed release assembly and **336 JVM tests** passed with zero failures,
  errors or skipped tests. APK Signature Scheme v2 verification passed.
- New material tests verify that partitioning preserves every triangle and its
  winding, material ranges cover all indices, and expressions/gaze do not reassign
  surfaces. Lighting tests cover scaled/invalid normals and luminous accents.
- Existing geometry, blink, jaw, gaze, color customization and depth-buffer
  regression tests passed. Creature meshes remain below 12,000 vertices at
  maximum detail.
- Eighty final expression/rotation QA cases and eight artwork poses were rendered
  with the actual Kotlin depth buffer and surface lighting, then visually checked.
  Preview exports also carry the actual shaded vertex colors for reference tools.

These previews are offline software renders of the application meshes. Native
PBR reflections depend on the headset scene. Live headset fit, tracking appearance
and frame time remain unverified; no device installation was performed.

APK: `app/build/outputs/apk/release/app-release.apk`, version **1.2.4 (30)**,
arm64, Android API 34 minimum. SHA-256:

```text
6BDAA70AD2ACE63903307C6CF9A7EE0AAF856BA69133A322CFE0787D6DCAB202
```

## Reproduce

```powershell
.\build_quest_release.ps1 -Offline
.\scripts\build-portrait-preview.ps1 -Detail 20 `
    -RasterDirectory scratch/art-v4-final-raster -Output scratch/art-v4-final.json
.\scripts\build-portrait-preview.ps1 -ArtPortraits -Detail 20 `
    -RasterDirectory scratch/art-v4-portraits-raster -Output scratch/art-v4-portraits.json
node scripts/render-portrait-preview.cjs scratch/art-v4-portraits.json `
    assets/previews/character-art-v4.png <path-to-sharp-module> --gallery --art --use-app-raster
node scripts/render-portrait-preview.cjs scratch/art-v4-final.json `
    assets/previews/animal-heads-v4.png <path-to-sharp-module> --animal-heads --cols=3 --use-app-raster
```
