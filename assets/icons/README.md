# AlterEgo app icons

Matching APK and EXE icons based on the supplied Webcam Active headset/camera image.

- `alterego.png`: 1024 × 1024 PNG with a pale rounded tile and transparent corners.
- `alterego-source.png`: original generated transparent artwork, retained for rebuilding.
- `../../windows/assets/alterego.ico`: Windows icon containing 16, 20, 24, 32, 40, 48, 64, 128 and 256 px entries. Embedded in the receiver and setup EXEs by `windows/build.ps1`.
- `../../app/src/main/res/drawable-nodpi/alterego_launcher_foreground.png`: padded Android adaptive foreground. The symbol occupies 62 dp of a 108 dp canvas. Both launcher variants use it, including themed monochrome icons.
- `../../app/src/main/res/mipmap-*/ic_launcher*.webp`: lossless square and round legacy icons in all five launcher densities.

Package the source again with Node.js and Sharp installed:

```powershell
node scripts/build-icons.cjs
# A Sharp module path can also be supplied as the first argument.
```

Build the app packages using the project's existing commands:

```powershell
.\build_quest_release.ps1 -Offline
.\windows\build.ps1
.\windows\verify_packaging.ps1
```

## Artwork provenance

Created with the built-in image generation tool in edit mode, using the supplied `1-1000023251.png` as reference. Asset packaging uses Sharp for cropping transparent margins, scaling, backgrounds and file conversion.

Exact generation prompt:

> Use case: precise-object-edit. Asset type: production app icon foreground shared by an Android APK and a Windows EXE. Input image 1 is the supplied design reference and edit target. Recreate just its teal VR-headset outline with central webcam lens and green partial circular ring, clean and crisp at 1024x1024 with a truly transparent background. Preserve the recognizable geometry: rounded wide headset body, side strap tabs, two short top posts, shallow upward nose notch on lower edge, large central camera lens, pale cyan broadcast arcs to left and right. Same deep cyan teal and green palette, flat smooth vector-like thick rounded strokes, balanced and symmetrical. Center the complete symbol with comfortable even transparent margins and keep every part fully inside the image. The headset is the sole object. Remove all caption text including 'Webcam Active' and remove the pale rounded tile, screenshot background and neighboring tile fragment. No letters, no words, no watermarks, no mockup, no drop shadows, no 3D effects. Keep the lens highlight. Output one square isolated icon, not a grid of icons.
