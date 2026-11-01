# Upper-body mirror — Quest 1.2.18 (44)

Custom portraits now use a solid 3D upper body in the passthrough mirror and its
shared video output. The torso, shirt collar, shoulders, upper arms, forearms,
palms and fingers have volume and smooth normals. The head and body share one
depth buffer, so hands can correctly pass in front of the face.

## Head size and framing

Rain and Snow retain their authored proportions at uniform metre scale. Their
facial skin bounds are approximately 24 cm high; hair and creature horns do not
control face size. This replaces the previous reduced, uneven head scale.
The regular camera overlay also fits each portrait using its neutral skin mesh,
instead of measuring a different procedural face.

The custom mirror and directly tracked Horizon mirror now share a 1.10 m-high
view, centred 27 cm below the head reference. It includes the head through the
waist. Native Horizon artwork is unchanged. The separate video-driven Meta
sample retains its existing camera calibration and inference regions.

## Tracking and responsiveness

Body and optical-hand joints drive the mesh when available. Missing joints use
a neutral visual pose; this does not create tracking observations or change
tracking availability flags. The UI identifies the neutral-body fallback.

Custom software rendering runs on one background worker. It accepts one job at
a time, drops obsolete output, preserves observation timestamps, and invalidates
pending work on reset, pause, focus loss, source changes and shutdown. Reset
does not wait for rendering on the interaction thread. Render timing feeds the
existing adaptive cadence budget even when an image is discarded as stale.

## Preview and verification

[Preview image](assets/previews/upper-body-avatar-hero.png) and
[all eight poses](assets/previews/upper-body-avatar-v1.png) use the production
mesh, shading, framing and depth buffer with synthetic poses. They are not Quest
screenshots. The preview includes all five available custom presets, raised
hands, hand/face overlap, and a turned head. No preview vertices are clipped.

Regenerate with `scripts/build-wearer-preview.ps1`.

Verified on 28 October 2026:

- Release build and native compilation succeeded.
- All 434 JVM tests passed, with no failures, errors or skipped tests. New checks
  cover solid-body geometry, genuine joint mapping, neutral fallback, head
  dimensions and transforms, framing, skin-based face fitting and worker gating.
- Both compressed authored portraits and their required credits are present in
  the APK.
- APK Signature Scheme v2 verified. Package version is 1.2.18, code 44.
- APK size: 141,429,008 bytes.
- SHA-256: `F29B208B046C82AAE146353D28D80495EA139B7D661D9EBC00796119A2FB4361`.

APK: `app/build/outputs/apk/release/app-release.apk`.

Headset appearance and performance still require an awake Quest test. A render
exceeding the 150 ms observation freshness limit is discarded; desktop rendering
measurements do not establish Quest latency.

## Later headset check

1. Open My mirror and select Rain or Snow in passthrough.
2. Use Reset view. Confirm the head, shirt and waist are visible in front of you.
3. Turn your head and raise each hand. Check neck alignment, arm movement and
   hands passing in front of the face.
4. Switch between a custom portrait and Horizon. Compare head size and framing.
5. Test Menu and Exit passthrough while the mirror is animating.

The Quest was disconnected during the final desktop checks, so this release
has not been installed or physically tested as part of this change.
