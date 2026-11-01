# Windows webcam layout — 1.5.0

October 20, 2026. The Windows Forms receiver has a new native layout with a
larger preview, clearer grouping and matching background/pairing windows.

## Main window

- Large preview on the left, with source resolution and a short call-app hint.
- Receiver off, Starting, Waiting for Quest and Video live states shown in text;
  colour is an additional cue. These describe the receiver, not the remote call.
- Connection card on the right, with the network selector and Start/Stop receiver.
- Manual pairing expands only when requested. No empty key row is reserved.
- Background card shows the current choice and Change background action.
- Camera tools separates Check Windows camera from expandable installation,
  repair and Reset pairing actions.
- Clear empty/waiting instructions replace a featureless blank preview.
- The sidebar scrolls vertically at small sizes or with expanded controls.

The initial window is 1160 × 800, with a 920 × 640 minimum. The synthetic scaling
check enlarges both geometry and text by 150%; it is not a hardware test of
per-monitor DPI transitions on every display configuration.

The Background window has grouped style, colour/picture and edge controls plus a
Done button. The pairing window prominently displays the comparison code and
keeps the same Codes match / Cancel / close decisions. Its callback completion
and both-device approval requirements are preserved.

## Validation

The native layout check generates eight app-view captures using isolated state:
ready, waiting, synthetic live, expanded manual pairing/camera tools, compact,
150% geometry/text, background and pairing. It checks usable control/preview
sizes, expansion/collapse, no horizontal scrolling, and rendered waiting text.
All views were visually inspected. Pairing approval, cancellation and window
close are also exercised with an isolated completion result.

No wearer avatar, headset camera, user image, real network adapter list, saved
pairing or installed video channel is acquired by the layout check. The live
fixture is generated geometric shapes, and addresses are synthetic. Normal
receiver installation/start operations are disabled in this preview mode.

The 16 existing synthetic background checks passed with an average of 19.2 ms
for ten warmed 1080p composites on this PC. Native source and high-detail receiver
checks passed as well. Discovery/pairing checks preserved matching comparison
codes and both-device approval; the receipt test confirmed 113 images with
pause/recovery and disconnect clearing. Bundle hashes, compatibility EXE identity,
private frame ACLs, upgrade cleanup and single-file setup validation passed.
Streaming, image composition, camera formats, source clocks
and persistence schemas are not changed by this layout release.

The existing background colour/image choices and trusted headset pairing are
retained by the upgrade. The Quest application remains 1.2.0. Actual wearer
tracking smoothness and real-call edge quality are outside this layout check.

## Files and release

`windows/WebcamLayout.cs` contains the shared native theme, main layout, pairing
window and isolated layout checks. `windows/AvatarBackground.cs` uses the theme
for the background window. The existing receiver is wired through
`windows/AlterEgoWebcam.cs`.

The complete offline 1.5.0 setup and ZIP are in `windows/dist`; the compatibility
EXE under `pc_avatar_webcam/dist` remains the same receiver bytes.

Installation finished. The installed receiver reports **1.5.0.0**, the Windows
installation record reports **1.5.0**, and every installed payload matches the
verified bundle hash. The normal Windows application reopened successfully.

Installer SHA-256:
`CA5F8865CA1AACE489E5B81FC470D47C65A344D33BEBD612EB74909600847F39`

Synthetic native preview: `windows/dist/layout-check/ready.png`. This image uses
an empty preview and synthetic network data, rather than any personal avatar.
