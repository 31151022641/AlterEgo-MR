# AlterEgo MR upgrade — October 8, 2026

Quest **1.1.0 (13)** and Windows Webcam **1.2.0** have been built and installed
on the connected Quest Pro and this Windows 11 PC. The user confirmed that the
regular Horizon mirror displays and animates their avatar. The installed Windows
virtual camera passed MediaCapture acquisition and reopen checks.

## Upgrade coverage

| Area | Implemented behavior | Verification / remaining limits |
| --- | --- | --- |
| Windows camera compatibility | Accepts repeated Start, preserves outstanding requests, recreates a shut-down activation source, and advertises RGB32/NV12/YUY2 with matching layouts and color metadata. | Native format/orientation/lifecycle checks passed. Installed MediaCapture read 8 timestamped samples, then 8 more on reopen. Full Camera switching, Teams, Zoom and browser-call QA remains outstanding. |
| Easier pairing | Find PC discovers local receivers without publishing the PIN. Fresh nonces and the TLS certificate determine a matching comparison code. Both devices must approve before trust is saved. Manual pinned pairing remains available. | Production discovery, matching-code, no-PIN-before-approval and approval/decline checks passed. Deadline handling was reviewed in code; the full 90-second expiry was not exercised. Physical two-device flow is awaiting the user's check. |
| Accurate connection status | AEMR0004 returns sequence-bound accepted/paused/delayed receipts. Quest shows live video only after receiver publication. | Accepted, rejected and no-image receipts passed; 119 publications confirmed during the new-protocol regression. A receiver receipt does not prove that a call application selected the camera. |
| Faces and fit | Eight offline animated style previews, improved rounded/shaded heads, eye/brow/mouth expressions, beard/muzzle/horn details, size/offset controls, and conservative hand-overlap fallback. | Synthetic Android previews inspected. Models remain procedural and stylized, including the human portraits. No photorealistic asset pack or personal digital-twin reconstruction has been added. Video face/hand estimates can miss or misclassify an OS avatar. |
| Regular 2D mirror | Mirror/Faces/Webcam/Quality tabs retain the preview, Horizon default, retry/framing controls and saved settings. | The user confirmed their own Horizon avatar appears and moves after the final update. Raw sensor rendering still uses the immersive mode; the regular window uses OS-rendered avatar video. |
| Smoother performance | Automatic/Smooth/Save battery modes, processing/network/thermal feedback, lower compression cost under load, bounded inference inputs and shared rounded-mesh vertices/rotation axes. | Scheduling/hysteresis tests passed. No 30-new-picture-fps or long-session thermal guarantee; actual performance appears in Quality. Preset inference can be slower than the Horizon feed. |
| Quest 3/3S face alignment | Small world-position noise is damped with bounded lag. Ambiguous association during crossing hides uncertain overlays until observations separate. | Association and movement/jump/gap tests passed. Quest 3/3S outward-camera and real-person occlusion/crossing tests remain unavailable. Monocular distance is approximate. Quest Pro still has no supported outward-camera face replacement path. |
| Installation/signing | Complete offline single-file setup captures the normal user before UAC, validates/stages the payload, unloads the old FrameServer component, handles locked retired backups, and installs private-subnet TCP/UDP rules. | Package/private-ACL/transaction/bootstrap checks passed. Installed DLL hash matches the tested payload. No usable private-key Code Signing certificate was found in CurrentUser/My or LocalMachine/My; publisher signing remains unavailable. |

## Verification performed

- Final signed Quest release build and **125 release unit tests**, zero failures
  and errors. The existing Meta SDK/Kotlin metadata warnings remain in build
  diagnostics; Gradle completed successfully.
- Native Windows checks: source events, repeated Start, pause/stop/reopen,
  timestamps, RGB32/NV12/YUY2 sample sizes and orientation, private paths/channel
  token, seqlock/cache continuity and explicit privacy clearing.
- Encrypted previous-protocol regression: **552 confirmed publications over
  60 seconds**, variable delay/jitter/gaps, late/future rejection, pause/recovery,
  authentication throttling and disconnect clearing.
- New pairing/receipt regression: both approvals, refusal on either side,
  withholding the PIN before PC approval, discovery validation and **119 confirmed
  publications in 5 seconds**. Unit checks cover nonce/certificate code binding.
- Bundle hash/private-ACL/compatibility alias/installer validation and upgrade
  cleanup regression passed. The single-file bootstrap returned validation exit 0.
- Installed Windows MediaCapture reported all three formats. Two sequential
  acquisitions each returned **8 frames and 7 increasing timestamp intervals**,
  without the earlier `0xC00D3E82` failure. Live encrypted headset video was also
  observed in the upgraded receiver.
- Synthetic preset previews were exported independently of wearer pixels and
  visually checked. The headset was returned to normal My mirror afterward.

These are bounded tests. They do not certify every call app, a multi-hour session,
hardware not present, or photorealistic quality.

## Error 7 during the headset test

The SDK sample diagnostic screen returned **Avatar model could not load · Error 7**.
It had accidentally been left open for the user. Force-stopping that diagnostic
and launching normal My mirror restored the OS avatar camera; the user confirmed
that their Horizon avatar appears and moves. This does **not** establish that the
separate SDK sample model-loading problem is permanently repaired. A subsequent
bounded fresh-launch test loaded the sample successfully (`stage=21`, one render
primitive) and exercised synthetic face, eye and both-finger inputs with successful
hand retarget skeleton setup. These are synthetic inputs, not a new physical sensor
verification. Quest was returned to normal My mirror in the test's cleanup. The
earlier Error 7 therefore appears intermittent; its underlying cause has not been
established. Personal Meta SDK avatar entitlement/organization approvals remain
separate from the functioning regular Horizon mirror and its video presets.

## Artifacts and usage

- Quest: `app/build/outputs/apk/release/app-release.apk`.
- Offline Windows setup: `windows/dist/AlterEgoMR-Webcam-Setup-1.2.0.exe`.
- Complete ZIP: `windows/dist/AlterEgoMR-Webcam-1.2.0-Windows11-x64.zip`.
- Supported source and build: `windows/`; compatibility receiver:
  `pc_avatar_webcam/dist/pc_avatar_webcam.exe`.

Start the Windows receiver on the network shared with Quest. On Quest choose
**My mirror → Webcam → Find PC**; compare the exact code and approve on both
devices only if it matches. Select **AlterEgo MR Avatar (Windows Virtual Camera)**
in the video-call app and keep the headset mirror active. The stream is video
only; use the PC's normal microphone. Quest remains standalone for its own mirror
and supported mixed-reality features.

See [Windows instructions](windows/README.md) for installation, pairing, camera
checks, privacy clearing, update/removal and signing. Earlier deployment results
are retained in [the stability report](PC_WEBCAM_STABILITY.md).
