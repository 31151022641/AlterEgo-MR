# Horizon avatar integration — October 3, 2026

AlterEgo MR uses Quest App ID **1331229656743286**, package
**com.misterslee.alterego.mr**, signed release **1.0.9 (10)**. The standalone
headset app includes Meta Platform SDK **69.0.0**, Meta Avatars SDK **40.0.1**,
the official core rig/animation archive, one official public sample avatar,
and their SDK license notice. A PC is not required for mirror rendering.

## What is implemented

The native bridge checks app entitlement before requesting the headset's Meta
identity and runtime token. IDs and tokens stay inside native code; they do not
enter Kotlin, preferences, diagnostic logs, or the LAN webcam protocol.
Approved accounts can query avatar availability and load an avatar entity from
the Oculus graph. Account access, model loading, and rendered-frame success are
separate states. A successful access query never counts as a rendered avatar.

A custom GLES3 renderer copies SDK mesh, material, texture, skinning, and morph
buffers into an offscreen 960 × 540 target. Its horizontally reflected camera
frames the wearer from the belly upward. The same rendered bitmap supplies the
movable 2D mirror and the existing optional LAN sender. The SDK uses its current
rig, behavior, and constraint flags and default animation graph. Only the
requested LOD is copied from the core assets, keeping morph data within the
128 MB budget. The renderer restores Spatial SDK's graphics context after each
frame and uses the existing sensor session.

The entity accepts valid head and wrist poses, optional physical face weights,
and optional eye gaze before each animation update. OpenXR face weights are
explicitly mapped to the SDK's different 72-expression layout; tongue channels
cannot accidentally animate brows or lips. SDK-only unsupported expressions
have zero confidence. The SDK infers torso/arm motion from head and wrist inputs.
Optical wrist poses take precedence over body wrist-twist bones. Articulated
hands use the runtime's `XR_FB_hand_tracking_mesh` bind skeleton and current
`XR_EXT_hand_tracking` joint poses. Bind-relative motion is converted into the
official SDK reference prefab's joint axes, preserving its bone lengths and
thumb trapezium. The SDK custom-hand skeleton/pose APIs receive canonical local
rotations; absolute XR rotations are not copied into OVR local bones. A separate
provider wrist-basis correction preserves finger and radial directions. Missing
or partial optical hands clear the custom override.
**Full body-joint retargeting is not implemented for this SDK entity.** The
existing procedural preset renderer uses body/hand joints directly. This custom shader is not the complete Meta
Unity Style 2 shader pipeline; advanced skin/hair/material effects and arbitrary
personal avatar appearance remain unverified.

The mirror offers **My Horizon avatar**, **Meta sample**, and the existing
presets. The bundled sample can load without account authorization and is always
identified as a sample rather than the user's personal avatar. Account refusal
does not block selecting the sample or a preset.

## Verified on Quest Pro

The signed release built and installed successfully. Its **81 release unit
tests** pass with zero failures, errors, or skipped tests. The native expression
mapping and hand retargeting regression checks pass, and the arm64 build checks SDK structure sizes
and offsets. Android's verifier confirms the stable v2 signing certificate.
Lint still reports the existing Spatial SDK Kotlin metadata compatibility
warnings; this is not a claim of warning-free static analysis.

A real 2D Android window successfully initialized the SDK, loaded the official
public sample, and rendered its face, eyes, clothing, and upper body. Separate
synthetic-input images verify wrist/arm movement and mouth/eye expression
response. These public-sample fixtures never record wearer sensors, camera
images, a personal avatar, or account credentials. Version 1.0.5 also verifies
upright open hands, opposing thumbs, and closed fists in controlled sample images.
Native checks cover both wrist/radial axes through combined pitch and yaw.
Long-running physical animation still requires headset validation.
Quest 3/3S hardware is not available for testing.

The October 3 physical test reproduced the head-only failure: opening the OS
window changed the XR session from FOCUSED to VISIBLE. Body tracking returned
runtime failure, face tracking returned `XR_SESSION_NOT_FOCUSED`, and hands
became inactive. Permissions remained granted. The user authorized a separate
**Animate mirror** mode, which retains a movable flat panel in the app's
passthrough session. The regular OS-managed window remains available; it does
not promise uninterrupted raw sensor access. The UI explains this distinction.

In the installed live-panel build, the connected Quest Pro returned fresh
physical head/body/face data, both optical hands, and both valid eyes. Both
runtime hand bind skeletons loaded successfully. The user confirmed working
live face and eye animation, then reported twisted wrist/finger directions.
Version 1.0.5 corrects the SDK wrist basis and canonical finger retargeting;
final physical hand-direction validation is pending. Tracker initialization
can retry after a transient failure instead of staying unavailable for the session.

## Flat-panel Reset view

The new [avatar-camera mirror](AVATAR_CAMERA_MIRROR.md) uses the OS-rendered
personal avatar in an ordinary 2D window. It does not depend on this app's SDK
identity approval or keep an XR session behind the window. Camera permission is
required. Preset replacement in this mode estimates expressions from rendered
video and keeps Horizon's body/hands; it is experimental. The identity error
below still applies to the immersive SDK avatar renderer. The previous
cooperative-window approach has been replaced for normal app launches.

Version 1.0.8 uses one level, head-relative layout for all app flat panels and
the UI tabs within them. The main panel is 0.82 m ahead and 4 cm below eye height;
the dock is lower, and the side mirror faces the viewer. The scene explicitly
uses `ReferenceSpace.LOCAL_FLOOR`, following Meta's recentering guidance, instead
of the default room-fixed `STAGE`. Reset view requests
survive Quick Settings pauses. They wait for two consistent fresh, focused scene frames
after a short settling interval; there is no cached pre-reset pose fallback.
The main panel stays level during head pitch/roll, including a vertical look.
Opening a menu, changing modes, and the mirror's Centre view use the same reset.
The selected UI content is preserved and closed panels stay closed. Entering
Animate mirror hides the old layout and automatically places the panel ahead
after the live session gains focus, so the OS-window-era pose is not exposed.

Automatic distance-based panel relocation and unconditional reset on Android
resume were removed. A dragged panel keeps its location until it is explicitly
reset or reopened. Regular mirror startup preserves its original Android
activity/task instead of destroying it and asking Horizon to create a second
window. Its physical OS-window placement still belongs to Horizon OS; the
app does not claim an API for overriding arbitrary Home windows. Nine new
regression tests cover heading, level placement, origin changes, paused resets,
invalid tracking, and coalescing. The complete suite is now **81 tests**.
The initial version 1.0.6 physical check did not reset Animate mirror despite
the passing layout tests. Version 1.0.7 changes the tracking reference space and
adds isolated reset-event logging. The connected Quest Pro now reports a
user-initiated system reset followed by fresh-frame panel placement. The user
reported that the first Animate mirror entry still appeared in the wrong
position. Version 1.0.8 now requires focused frames and hides the old placement
during that transition. Its signed build passes all 81 tests and is installed
on Quest Pro. Physical visual confirmation of first entry and both regular
and live mirror modes is pending.

Fresh normal-app account check:

```
Meta Platform initialization: succeeded
Application entitlement: succeeded
Account identity request: rejected, error code 2
Account token request: not attempted
Personal avatar access/load: not attempted
```

The earlier **1322338430965742** registration was for Rift/Link PC VR. With the
owner's approval, the separate Quest registration above was created under
MistersLee. Its entitlement now passes. The signed-in dashboard previously
showed organization verification required and User ID, User Profile, and
Avatars not requested/approved. The owner confirmed verification is still
incomplete. This is consistent with the identity rejection, but the numeric
error alone does not prove its cause. The UI now distinguishes denied identity
access from an app entitlement failure.

## Meta setup still required

Select AlterEgo MR (**1331229656743286**) in the
[Meta Developer Dashboard](https://developers.meta.com/horizon/manage).

1. Complete MistersLee's organization verification directly with Meta. Answer
   its business/administrator questions accurately; verification documents
   should stay in Meta's verification flow.
2. Complete the app's age-group self-certification for its intended audience.
3. Under Requirements > Data Use Checkup, request **User ID**, **User Profile**,
   and **Avatars**, choosing **Use Avatars**, and complete the required accurate
   declarations. Wait for Meta approval.
4. Reopen My mirror and select **My Horizon avatar**. Validate its actual
   appearance and physical animation after identity/token access succeeds.

No password, app secret, or access token needs to be shared or embedded in the
APK. Uploading an APK is not the next fix for the developer's current identity
rejection: entitlement already passes. Distribution to other testers later
requires a private release and explicit tester entitlement.

Meta's [avatar app configuration guide](https://developers.meta.com/vr/documentation/unity/meta-avatars-app-config/)
describes the required Data Use access; its
[entitlement troubleshooting guide](https://developers.meta.com/vr/documentation/native/ps-entitlement-check/)
explains package/channel configuration.

## Build, signing, and diagnostics

Run `build_quest_release.ps1 -Offline` with the configured dependencies. The APK
is `app/build/outputs/apk/release/app-release.apk`. Preserve the original local
signing key and Windows-DPAPI password described in `SIGNING.md`; every update
must use that key. Neither file belongs in source control or the APK.

The explicit `avatar_render_diagnostic` extra on a cold-started MirrorActivity
runs only public sample fixtures in a regular 2D window. It saves
`horizon-sample-preview.png`, `horizon-sample-motion.png`,
`horizon-sample-expression.png`, `horizon-sample-open-hands.png`,
`horizon-sample-curled-hands.png`, and `horizon-sample-blink-gaze.png`
to the app's external files directory. It does
not start a sensor session or LAN sharing. Normal launches never save these
images. The diagnostic defers to the normal tracking window if a sensor owner
is already running, and only one Horizon SDK owner is active at a time.
