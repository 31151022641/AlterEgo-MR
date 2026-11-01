# Animal head anatomy and portrait polish — Quest 1.2.3 (29)

The animal presets now use distinct head profiles instead of a rounded human
face with animal accessories. Their shape remains recognizable without a mane.

- **Unicorn:** a long horse nasal bridge, narrow cheeks, a projecting broad muzzle,
  large nostrils and lateral eyes, with a tall horn and swept pastel mane.
- **Dragon:** a wide projecting reptilian snout and jaw, amber slit pupils,
  pointed webbed frills with ribs, swept horns, a dorsal crest and small fangs.
- **Lion:** a broad feline muzzle with paired whisker pads, a larger triangular
  nose, split upper mouth, whiskers, rounded ears and a surrounding mane.

Species profiles interpolate continuously through forehead, bridge, muzzle and
jaw. Mouth tracking moves each animal's lower jaw; blink and gaze remain separate.
Human eyebrow strips and full pink lip rims have been replaced on creatures by
fur/scale ridges and restrained animal mouth edges. Creature color customization
and the mane visibility setting remain supported. Extreme gaze is clipped to
the smaller lateral eye apertures.

The five human-derived presets also receive tapered jaws, softer cheeks,
almond-shaped eyelids, larger layered irises, fuller shaped lips and stronger
swept hair volumes. The beard now has a faded upper edge and a separate tapered
moustache, replacing the dark ring around the mouth.

The shared meshes feed the native headset scene, regular mirror/video painter
and wearer view. Meta/Horizon and Meta sample rendering were not redesigned.

## Preview and validation

- [Animal heads: front, turn and profile](assets/previews/animal-heads-v3.png)
- [All eight presets](assets/previews/cartoon-faces-v3.png)
- Signed release assembly and all **333 JVM tests** passed: zero failures,
  errors or skipped tests. APK Signature Scheme v2 verification passed.
- Four added creature regression tests cover projecting muzzles, horse bridge
  length, dragon snout width/slit pupils/frills, jaw/eye independence and gaze
  containment. Existing finite geometry, normalized normals, color, topology,
  blink and depth-buffer tests also passed.
- **80 final expression/rotation cases** were rendered and visually inspected
  using the actual Kotlin depth buffer and the application's vertex lighting.
  This includes 27 creature cases and 26 Natural/Bearded cases.

These are offline renders of the real application geometry, not headset captures.
Physical headset fit, live tracking and frame time remain unverified. No device
installation was performed for this update.

APK: `app/build/outputs/apk/release/app-release.apk` — version **1.2.3 (29)**,
arm64, Android API 34 minimum. SHA-256:

```text
52F6987D4EBEB02FA0B72D95F528DF5262CAF9130FB905C8C614A3AB6908BB52
```

## Reproduce

```powershell
.\build_quest_release.ps1 -Offline
.\scripts\build-portrait-preview.ps1 -Detail 20 `
    -RasterDirectory scratch/faces-v3-final-raster `
    -Output scratch/faces-v3-final.json
node scripts/render-portrait-preview.cjs scratch/faces-v3-final.json `
    assets/previews/animal-heads-v3.png <path-to-sharp-module> `
    --animal-heads --cols=3 --use-app-raster
node scripts/render-portrait-preview.cjs scratch/faces-v3-final.json `
    assets/previews/cartoon-faces-v3.png <path-to-sharp-module> `
    --gallery --use-app-raster
```
