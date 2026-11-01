# Avatar-camera mirror

## Current layout — Quest 1.2.0 (26)

Quest 1.2.0 installs the selected Quick launcher layout with Faces, Avatar,
PC Link and Add face destinations, a visible Menu button, and a larger preview.
Back navigates to the launcher and then closes controls. Menu navigation itself
does not switch identity, stop the camera or disconnect sharing. Selection and
pairing actions remain explicit. The left ≡ binding is implemented for a focused
immersive session; the ordinary OS window also handles it if Horizon OS delivers the
corresponding Android key event. Use the visible Menu button in the OS window.

The file flow decodes and previews real PNG/JPG pixels and saves a local named
photo mask. The regular mirror can apply that static mask to its existing
head-pose estimate while retaining the Horizon body and hands. The focused live
mirror uses animated rigs and does not support these photo masks. Generic model
import is not implemented. Selection epochs still discard frames from a previous
identity, including asynchronous photo changes.

The final signed build passed 312 JVM tests. On Quest, the isolated synthetic
photo check passed 7/7 and generated all six native menu views for visual review.
It returned to normal My mirror without a recorded app crash. This is a layout
and image-pipeline check, not a new live tracking or webcam reliability pass.
The underlying 1.1.12 sample worker and animation changes are retained, with the
wearer comparison still pending. See [LAYOUT_DEPLOYMENT.md](LAYOUT_DEPLOYMENT.md).

## Regular 2D Meta sample — Quest 1.1.12 (25)

The final signed **1.1.12 (25)** build passed **278 JVM unit tests**, with zero
failures/errors, plus the standalone native exact-morph-cache check. It was
installed as a signed update, and normal My mirror → Meta sample was opened in
the regular 2D window. Loaded-camera timing comparisons and live wearer
confirmation remain **pending**; current live reliability is unresolved. The
build log still contains existing Spatial SDK Kotlin metadata analyzer
diagnostics, and the final build result was **BUILD SUCCESSFUL**.

Following restart and a clean original-Horizon comparison, the wearer reported
that only hands still need improvement. The earlier 1.1.11 release passed 255
unit tests and five native recovery scenarios. Face and eye estimates publish
before hand inference completes;
independent capture clocks and same-frame revisions preserve freshness. Partial
channel loss settles at bounded cadence instead of caching a partly raised hand.
Direct sensor startup also retries missing resources/bind meshes. These repairs
do not establish accurate hand recognition or change Horizon OS's own hand poses.
The combined public check passed 4/4, including independent face/eye animation
while hands were inactive. Four warm load samples published face controls an
average 116 ms before the completed hand packet; this is publication timing,
not verified wearer display latency. The subsequent normal Meta sample wearer
comparison rejected **1.1.11**: "I need it as smooth as the full screen, it is
really delayed." The public animation passes do not meet that live requirement.

Version **1.1.12 (25)** runs face and hand models on independent workers.
Each worker owns its model and input image, keeps a bounded latest-image queue,
and reports the observation's original capture time. A slow hand inference no
longer blocks the next face inference, or vice versa. The regular sample UI
receives controls without copying the hidden OS camera bitmap into a preview;
the model workers still own the separate images needed for safe processing.
The SDK may advance animation from still-fresh held controls between estimates.
Fast response, Balanced and Maximum detail use 33/50/66 ms scheduling intervals,
with slower intervals under thermal pressure and actual drawing time deducted
from the next wait. Per-mesh exact weight caching skips CPU morph rebuilding and
vertex uploads when weights are unchanged, while SDK animation, skin matrices
and drawing still run. All weights must be finite; a failed upload resets its
cache, and a fresh resource requires its first successful upload.

Loaded-camera timings and the live 1.1.12 wearer comparison are **pending**.
Scheduled animation rates are not measured inference or display rates.
Repeated animation draws preserve each channel's
real acquisition time and do not extend local freshness or the stricter LAN
deadline. This remains an estimate from rendered OS avatar video, so these
changes cannot promise raw-sensor accuracy or full-screen smoothness.

Selecting **Meta sample** now loads the public Meta SDK avatar inside the ordinary
mirror window. It keeps the OS avatar camera running as input and estimates head
rotation, expressions, eye movement, wrist poses and fingers from its rendered
video. The mirror and optional webcam show the resulting SDK sample. This does
not request the personal avatar through Meta account APIs or start Animate mirror.

The preview identifies these as **video estimates**. Hands must be visible in
the camera image. Hidden, ambiguous or malformed observations clear immediately;
missing faces and model-loading failures keep Meta sample selected and render
neutral controls. The Horizon camera pixels are never published in sample mode.
Face and hand estimates have separate failure/retry paths; visible hands can
continue when the face is absent. A latest-frame queue, camera-run and selection
checks, 1000 ms local input limit and automatic inference retry prevent old observations
from keeping the sample animated. Rendering runs separately from inference, and
the quality tab reports both rates. Camera recovery keeps the local sample visible
while clearing unavailable webcam images. Pause closes the camera and sample renderer; reopening
loads a fresh sample session. The sample uses wide framing so cropping cannot
change reconstructed hand positions. Centre view keeps the normal OS window.

The SDK receives explicit neutral face and forward-looking eye controls during
video gaps so it cannot retain prior expressions. The observation flags still
say unavailable. Quest 1.1.3 passed 173 unit checks and a 180-render public sample
gap/recovery test, including fresh hands while face and eyes were unavailable.
Those public diagnostics do not establish live recognition accuracy: the user
reported unreliable face, eye and hand animation in both 1.1.3 and 1.1.4. The
original Horizon camera has since been confirmed to animate all three channels.

Version 1.1.5 uses a persistent GPU inference worker with a single latest queued
image and CPU fallback on delegate failures. Sample-only capture selects
Performance camera size and builds inference images directly at at most 640 × 360;
the SDK sample still renders 1920 × 1080. Rendering reuses pixels for unchanged
observations, advances animation by elapsed time and renders neutral poses for a
bounded settling period so hands cannot freeze mid-recovery. Processing errors
stop the matching run once, and stale run errors cannot interrupt its replacement.
Camera acquisition during model warmup prevents unnecessary capture restarts.
The 1.1.5 physical Meta sample check still failed. Live logs contained intermittent
hand observations and repeated face/eye neutral transitions when inputs expired.
Version 1.1.6 additionally waits for native rig asset loading before enabling
animation, matching the pinned SDK's loading sequence. This addresses a separate
startup readiness race, not the image detector's recognition limitations. Regular
Meta sample is labeled experimental in the app. My Horizon avatar was confirmed
by the wearer to track face, eyes and hands in the ordinary window. The later
1.1.6 sample wearer check also failed; public rendering tests do not establish
live sample tracking reliability.

The combined public sample check did animate face, eyes and both hands with the
real camera and GPU inference still running. Live 1.1.6 estimates often expired
after the SDK draw or before the next inference completed. Version 1.1.7 fixes
that artificial neutral oscillation by allowing up to 1000 ms from real capture
for local video estimates. Missing detections still clear immediately; LAN
images retain the 750 ms transport deadline. This is a timing repair and does
not certify smooth live recognition. Coarse change flags help verify movement
without exporting wearer images or numerical pose/expression values.
The signed 1.1.7 release passed 207 unit checks and the public gap/recovery check
and was installed on Quest Pro. The wearer confirmed following movement with
noticeable delay. Version 1.1.8 adds Fast response (540p), Balanced (720p) and
Maximum detail (1080p) for the regular sample. Fast response starts by default;
selecting another profile preserves the loaded avatar and animation. Face and
hand estimators share one owned input image, with independent failure/retry.
207 unit checks, all six public GPU fixtures and the three render profiles
passed. Stage timings confirm lower drawing cost in Fast response; face and
hand inference remain the main delay. The 1.1.8 wearer result reported no
improvement. Version 1.1.9 selects a supported native camera stream sufficient
for 640 × 360 inference, with a sample-specific 33 ms acquisition interval
(66/100 ms under thermal limits). Completed estimates request a draw immediately
instead of waiting for the polling timer. Real acquisition timestamps, bounded
latest-input queuing and generation/run/selection guards remain intact. The
signed release passed 215 unit checks and is installed. The combined public
camera/SDK check passed 3/3 captures, but the normal 1.1.9 wearer check failed:
"Nothing follows." The retained normal log shows changing face/eye estimates
and no sampled hand candidates, with only about 3.7 rendered frames/sec. This
route remains experimental and does not meet the requested live reliability.
Version 1.1.10 restores the previous sample source-size policy while preserving
the 640 × 360 model input and faster capture/publication. This isolates a possible
source-detail/framing regression rather than assuming the smaller native stream
is equivalent. A public face-only diagnostic checks SDK expression/eye controls
with no body, wrist or finger input. The signed release passed 217 unit checks
and was installed. Subsequent My Horizon avatar checks reported frozen or
delayed face/eye and hand motion; the later clean original-avatar comparison
reported that only hands still need improvement. These source checks do not
establish a 1.1.10 Meta sample pass. See the tracking report for current results.

This route is limited by what the OS avatar video shows and what the image models
recognize. Eye movement is not raw measured gaze; wrist depth and covered fingers
cannot be recovered precisely. **Animate mirror** remains the direct sensor path.
Meta documents the standard 2D
[avatar camera](https://developers.meta.com/vr/documentation/android-apps/features-overview/).
See [sample implementation and verification](SAMPLE_2D_TRACKING.md).

## Earlier deployment history

Version **1.0.10 (11)** retains the regular Horizon OS 2D mirror introduced in
**1.0.9 (10)** and adds secure pairing with the **1.1.0 Windows companion**.
The mirror uses the system Avatar Selfie Camera. It does not start an OpenXR activity, load an avatar through
the app's Avatars SDK, or require the configured App ID's avatar account access.
Horizon OS renders the signed-in user's avatar with the device's available
tracking. Quest 3/3S cannot supply Quest Pro's physical eye/face tracking.

## User controls

- **My Horizon avatar** shows the system avatar video unchanged.
- Selecting a face preset enables experimental video-based face replacement.
  The detector estimates expressions and head rotation from that same video
  frame; the shaded procedural 3D head follows its detected face. Presets keep
  Horizon's rendered clothing, body and hands. Unicorn, lion and dragon heads
  include their distinguishing horns, ears or mane.
- **Framing** crops closer to the upper body. **Centre view** restores wide
  framing; Horizon OS controls Reset view and placement of this normal window.
- **Retry camera** restarts capture after permission or camera availability
  problems. Allow Avatar Camera access if prompted; CAMERA is used on older OS
  builds that do not expose the separate AVATAR_CAMERA permission.
- **Connect webcam** sends the rendered mirror through **AEMR0002 over TLS 1.2**.
  Enter the PC receiver's address and full pairing key: its six-digit code plus
  complete 64-hex certificate fingerprint. The receiver identity is checked before
  any pairing code or video is sent; the previous short-code/plaintext protocol is
  rejected. The 2D self-preview is horizontally mirrored; outgoing camera video
  keeps the original camera orientation, as video-call applications commonly
  mirror their own self-preview.
- **Animate mirror** opens the focused XR mode for native tracking.
  Before 1.1.2, **Meta sample · Live mode** also opened that mode.

## Windows companion

Use the complete **Windows 1.1.0** bundle. The EXE in `pc_avatar_webcam/dist` is
the same native receiver as `windows/dist/AlterEgoWebcam.exe`; the incompatible
UDP prototype is archived and no external webcam DLL is downloaded. Install webcam
places the whole product in Program Files, creates a Start menu shortcut and
limits its firewall rule to the local subnet on a Private Windows network. Setup
captures the intended user's SID before administrator elevation.

Windows frames use `ProgramData/AlterEgoMR/<Windows-user-SID>/frames.dat`, with
Modify access for that user, read access for LocalService/FrameServer, and no
ordinary cross-user read/write access. Administrators and SYSTEM retain their
administrative access. The former global frame file is removed during upgrade.
A random per-channel token also prevents another camera activation from obtaining
video merely by naming that private path. The TLS identity is protected with
per-user Windows DPAPI. Reset pairing rotates the receiver certificate and code.

The receiver must stay open and the call app must select **AlterEgo MR Avatar
Windows Virtual Camera**. The stream supplies video only; choose a separate
microphone. See [companion setup](windows/README.md) and
[repairs and end-to-end verification](PC_WEBCAM_REPAIRS.md).

## Limits and lifecycle

This is a video workaround, not access to raw tracking or a custom hand skeleton.
The MediaPipe model targets human faces. Detection on different stylized avatars
still needs broader hardware validation. Missing or invalid detections show the original camera
frame with a clear status, never a cached face pose. Rotations are taken from the
video model, not treated as metric headset poses. Estimated eye movement is not
a measured gaze direction. Hands overlapping the replacement face may not have
correct occlusion, because the camera feed provides no depth or hand skeleton.
Photorealistic celebrity likenesses and imported textures are not supplied by
this procedural preset renderer.

Only the avatar camera (vendor source 1, or a front camera without that tag on
older firmware) can be selected. Passthrough, spatial, and third-party camera
sources are rejected. The system determines the avatar feed's background; the
same content is visible in the preview before webcam sharing is enabled.
Production capture stays in memory. Pause, mode exit, activity destruction,
camera failure, and a missing-frame watchdog clear the preview and stop sharing.
Frame generations reject results from an earlier camera run. Switching back to
the OS mirror closes the app's immersive task.

A developer-only `avatar_camera_diagnostic` intent saves one rendered preview
to the app's external files directory for visual QA. It is off during ordinary
launches. The separate `avatar_render_diagnostic` + `avatar_video_test` check
renders the SDK's public sample with synthetic inputs and saves six composited
images. It never records the wearer or camera. Inference owns a copy of each
bitmap, so closing its MediaPipe image cannot recycle the displayed frame.

## Validation

The **1.0.9** signed build passed **89 unit tests**, including camera-source selection,
crop bounds, invalid face rejection, column-major rotation and scale removal.
The connected Quest Pro exposes a front-facing camera with
`com.meta.extra_metadata.camera_source = 1` and sensor orientation 0. Camera
permission was granted. Version 1.0.9 was installed and launched MirrorActivity
without a MainActivity task. Logs confirm the system camera opened and delivered
a frame. The user reported that their avatar and the preset face both move in
the ordinary mirror. A subsequent public-sample test exposed and fixed a
MediaPipe bitmap-ownership bug; all six sample poses then produced valid face
detections and saved overlays, including open hands, curled hands, expression
and blink/gaze poses. Inspected images retain the sample's body and hands.
That corrected 1.0.9 build was installed. Its follow-up live-camera test found
the headset asleep. Expression accuracy, occlusion, sustained performance and
physical alignment after this last correction still need longer testing.
Unit tests and synthetic fixtures alone do not establish those qualities.
Version **1.0.10** adds the secure webcam sender without changing this evidence
about the avatar-camera renderer. The final Android build and deployment result
is recorded in [the webcam repair report](PC_WEBCAM_REPAIRS.md).
The updated Windows secure loopback tests pass full certificate/PIN checks,
protocol and malformed-frame rejection, active-owner protection, delayed-frame
dropping, recovery, sustained synthetic frame delivery and disconnect clearing.
The production Java sender delivered five encrypted frames to the Windows
receiver. These local tests do not establish a real Quest-to-call-app session;
Windows registration and calling-app results are recorded separately in that report.

For physical QA, wear the headset, open My mirror, and compare My Horizon avatar
with Golden Bust while turning the head, blinking, opening the mouth, and moving
the hands. Test loss of face visibility, wide/close framing, pause/resume,
camera-in-use recovery, transitions to/from Animate mirror, and webcam sharing.

The final **1.0.10 (11)** secure-webcam release passes **108 unit checks** and is
installed. Its bare-address and scrollable pairing feedback correction restored
the live PC preview. Windows service checks consumed changing real avatar frames
in RGB32 and converted NV12. Named call apps and long sessions remain unverified;
see [PC_WEBCAM_REPAIRS.md](PC_WEBCAM_REPAIRS.md).

The subsequent **1.0.11 (12)** webcam stability update passes **116 release unit
checks**. It separates temporary camera pauses from pairing loss, bounds the UI
preview queue, and requires the matching Windows **1.1.1** companion. Current
verification is recorded in [PC_WEBCAM_STABILITY.md](PC_WEBCAM_STABILITY.md).

## References

- [Meta's avatar camera permissions and Android camera selection](https://developers.meta.com/vr/documentation/android-apps/features-overview/)
- [Meta's virtual camera source types](https://developers.meta.com/vr/reference/metavr-os-nsdk/v207/enum_virtual_camera_8h_mvr_virtual_camera_source/)
- [MediaPipe face landmarks and expression estimates](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker)
