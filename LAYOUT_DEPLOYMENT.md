# Quick launcher deployment — Quest 1.2.0 (26)

October 13, 2026. The selected second layout is implemented and installed on
Quest Pro as an update to `com.misterslee.alterego.mr`, retaining its signing
identity and app data. The app was returned to normal **My mirror** after checks.

## User flow

- Open **Menu** for the large Faces, Avatar, PC Link and Add face choices.
- Use **Avatar** to choose My Horizon avatar or Meta sample explicitly.
- Use **Faces** to choose a built-in face or saved photo mask.
- Use **Add face** to choose a PNG/JPG, check its actual square preview, name it
  and save it. Select the saved mask from Faces to apply it.
- Use **PC Link** in My mirror for discovery/manual pairing, code confirmation
  and webcam sharing. Manual secure codes remain masked in the field.
- Use **Settings** for quality, face fit and expandable tracking details.
- **Back** returns to the launcher, then closes controls. **Close** dismisses
  controls directly. Neither action changes the selected avatar or stops sharing.

The ordinary mirror keeps a larger preview with a short source/status label and
a visible Menu control. The focused immersive left ≡ shortcut opens controls.
The OS window handles the shortcut if the system delivers an Android menu key;
the visible Menu button is always the window route. The right Meta button remains
the system button. Physical shortcut delivery has not been wearer-tested here.

Opening the passthrough menu requests a fresh viewer pose and places only its
own panel ahead. Opening controls in the live mirror overlays the existing
mirror panel. Menu open/close does not continuously follow the head or move
other panels. Existing system Reset view placement remains available.

## Real image import

The reachable importer now reads image bytes and validates the decoded format,
size and dimensions, with a 16 MiB input limit and 32-million-pixel limit.
PNG/JPG images are decoded off the UI thread, converted to sRGB, centre-cropped
and normalized to 512 × 512. The exact normalized preview is saved as a private
PNG with a named library entry. The library is shared by the two supported
views, bounded to 128 entries, and uses validated generated IDs for storage.
Bad files and cancelled/failed loads preserve the current face.

The regular camera mirror draws the photo through its existing head-pose
estimate. The passthrough renderer creates a real image texture on a head-following
quad. These are **static photo masks**. They do not blink, speak, extract a face
from its background or create an animated rig. The focused live mirror panel
and arbitrary 3D model import do not support these masks.

Mirror and passthrough maintain separate saved selections. Switching to a
built-in face or account/sample avatar cancels an outstanding photo selection;
selection epochs also reject stale camera frames.

## Verification

| Check | Result |
| --- | --- |
| Signed release build | BUILD SUCCESSFUL; existing SDK Kotlin metadata analyzer diagnostics remain. |
| JVM tests | 312 tests, 0 failures, 0 errors. Includes 18 menu/input-gate, 10 photo-policy and 6 visible-target checks. |
| Synthetic photo checks on Quest | 7/7: decode size/colour, saved index, reload, mask rendering/placement, outside transparency, invalid-file rejection. |
| Synthetic native UI capture | 6/6: Home, Faces, Avatar, PC Link, Add face and Settings; visually inspected at 1250 × 950 app-view pixels. |
| Installation | Version name 1.2.0, version code 26 confirmed on Quest Pro. |
| Final state | Normal MirrorActivity opened, process remained running, no AndroidRuntime crash recorded in the final check. |

The UI check uses generated colour blocks, bundled synthetic preset previews
and an isolated photo-library namespace. It returns before opening cameras,
account avatars or saved pairing. The saved views contain no wearer images or
tracking values. Only the app's own synthetic decor view was captured.

Build artifact: `app/build/outputs/apk/release/app-release.apk`

SHA-256: `B0D59550E43F802FB44C4FA3286D921F051ECDA0EB036A01C4E8EE47DC6D67F1`

## Remaining device checks

Wearer confirmation is still needed for actual controller menu delivery,
ray/touch/pinch selection, scrolling, and menu placement from different viewing
directions. File-picker selection of a user image, live regular-mirror mask fit,
Quest 3/3S passthrough texture placement and another real webcam sharing session
were not checked in this installation pass. Synthetic photo checks do not prove
live face-fit accuracy or GPU texture orientation on the headset.

The underlying regular 2D sample tracking pipeline is inherited from 1.1.12.
Its loaded-camera timing and wearer comparison remain pending; this release
does not establish full-screen tracking smoothness. See `SAMPLE_2D_TRACKING.md`.
