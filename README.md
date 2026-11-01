# AlterEgo MR

Quest mixed-reality face effects and an avatar mirror, with Windows and
Apple-silicon Mac webcam companion sources. This project builds on Meta's
[Passthrough Camera API sample](https://github.com/meta-quest/Meta-Passthrough-Camera-API-Samples).

## Build from source

- **Quest app:** Open this folder in Android Studio with JDK 17. Install Android
  SDK 34, NDK 27.2.12479018 and CMake 3.22.1. Run
  `./gradlew :app:assembleDebug :app:testDebugUnitTest` (use `gradlew.bat` on
  Windows). Release signing keys are kept locally; see [SIGNING.md](SIGNING.md).
- **Windows webcam:** See [windows/README.md](windows/README.md) and
  `windows/build.ps1` for the native receiver and installer.
- **Mac webcam:** See [macos/START HERE.md](macos/START%20HERE.md). The Mac target
  requires Xcode and signing on a Mac; native Mac verification is still pending.

The repository includes the runtime portrait models and required asset notices.
Generated installers/APKs, private signing material, local configuration,
reference attachments and downloaded Blender source ZIPs are excluded. Original
character download links are in [the portrait credits](ARTIST_PORTRAITS_LICENSES.md).
See [third-party notices](THIRD_PARTY_NOTICES.md) for the component licenses.

## Meta upload packaging — Quest 1.2.23 (49)

The manifest now declares the OpenGL ES 3.0 graphics requirement used by the
renderer. This fixes Meta's missing-graphics-version validation error. The signed
release build passes all 444 JVM tests; headset testing remains pending.

## Combined Faces & Avatars — Quest 1.2.22 (48)

One **Faces & Avatars** menu now contains Horizon, Meta sample, preset faces,
Sunglasses and photo-face choices where supported. The separate Avatar menu
has been removed. The same combined picker is used in passthrough and My
mirror, with view controls below the choices. Version 1.2.22 passes 444 JVM
tests and is installed on the Quest Pro; awake presentation checks are pending. See
[the menu report](COMBINED_LOOKS_MENU.md) for verification.

## Face detection check — Quest 1.2.21 (47)

The connected Quest Pro exposes a virtual avatar camera, but no supported
outward camera images for detecting people. Both camera permissions are already
granted. The app now makes camera/detector failures visible across the
passthrough menu and separates camera retry from detector recovery. Version
1.2.21 passes 444 JVM tests and is installed on the Quest Pro. This update does
not enable outward detection on Quest Pro. See
[the detection report](PASSTHROUGH_FACE_DETECTION_CHECK.md) for device evidence,
review findings and verification status.

## Separate Sunglasses face — Quest 1.2.20 (46)

Choose **Faces & Avatars → Sunglasses** in passthrough to place only 3D sunglasses over
the real face. It is a separate selectable item; the existing human and animal
presets retain their original faces. The sunglasses move and scale with the
tracked head. The item is limited to passthrough, keeping mirror avatar choices
separate. Version 1.2.20 passes 444 JVM tests and is installed on the Quest Pro;
awake appearance checks remain pending. See
[the sunglasses report](PASSTHROUGH_SUNGLASSES.md) and
[production-geometry preview](assets/previews/standalone-sunglasses.png).

## Full upper-body mirror — Quest 1.2.18 (44)

Custom portraits now have a rounded 3D torso, shirt, arms, palms and fingers
with shared head/body depth. Rain and Snow retain their authored head proportions
at a scale closer to Horizon. The passthrough mirror includes the upper body
through the waist, and custom rendering runs off the interaction thread.
The signed APK builds successfully and passes 434 JVM tests. Installation and
headset appearance/performance checks are pending because the Quest is
disconnected. See [the change report](UPPER_BODY_AVATAR.md) and
[production-geometry preview](assets/previews/upper-body-avatar-hero.png).

## Mac application — Apple silicon

A native SwiftUI Mac target with a built-in CoreMediaIO camera extension is in
[macos](macos/START%20HERE.md), based on the Windows receiver's connection,
pairing, preview and background features. It uses the existing Quest protocol
and requires no OBS. Source/project validation passes on Windows; native Mac
compilation, signing and camera/call verification remain pending. The Mac source
folder includes the Xcode project, native tests and build/signing instructions.
The included [packaging helper](macos/scripts/package_build_kit.py) can create a
local Apple-silicon build kit ZIP. It is a source/build package, not an already
compiled or signed application.

## Window recovery — Quest 1.2.17 (43)

Reset view places the active window on the wearer's current sightline, restores
its size, and leaves the live mirror uncovered. A clearer window frame and a
separate lower control row improve visibility. Windows recover when returning
to the app/headset, with a bounded placement refinement after a system reset.
The controller mirror shortcut now opens the immersive mirror panel. See
[the recovery report](PASSTHROUGH_VIEW_RECOVERY.md) for checks and test status.

## Exit label — Quest 1.2.16 (42)

The launcher and lower dock now label the passthrough exit **Exit passthrough**.
Selecting it returns to the regular My mirror window.

## Passthrough configuration and later testing — Quest 1.2.15 (41)

The wearer reports that hand lag occurs only inside AlterEgo and can test later.
Hand-version and passthrough metadata now live inside the application element,
and the app declares Meta's focus-aware setting. This corrects configuration;
its effect on the reported lag is not yet confirmed.

Small local summaries now retain scene timing and existing SDK input availability
across a later test. They use a background writer, two bounded files and no
images or tracked poses. Sleep/Home gaps are excluded. The signed release passes
393 JVM tests and is installed on the USB Quest Pro; launch and stored-log
retrieval pass. Awake smoothness remains unconfirmed. See
[the performance report](PASSTHROUGH_HAND_PERFORMANCE.md)
for the later test and collection steps.

## Passthrough hand responsiveness — Quest 1.2.14 (40)

Control hands use the SDK's existing tracking/prediction stream again. The extra
per-frame native hand trackers from 1.2.13 are removed; Menu is read inside the
SDK input stage, including a hand-menu press with its pointing ray suppressed.
Eye permission checks and unused camera/gaze work no longer run on every
interaction frame. Hand-microgesture virtual locomotion is disabled in this
stationary passthrough view. The signed update passes 387 JVM tests and is
installed on the connected Quest Pro; wearer confirmation is pending. See
[the performance report](PASSTHROUGH_HAND_PERFORMANCE.md) for verification.

## Tracked-hand ☰ Menu — Quest 1.2.13 (39)

The wearer clarified that Menu means the ☰ attached to the tracked hand.
The app now reads the runtime's non-dominant-hand Menu gesture directly, without
requiring a pointing ray, panel hit or Android key. A fresh gesture opens the
launcher and recentres the layout. Held gestures, focus loss, missing hand data
and duplicate SDK/Android delivery are guarded. The separate dock also keeps
its buttons ready while hidden, has larger targets and native hover/press
feedback, and cannot be accidentally grabbed while selecting a button. The
signed build passes 386 JVM tests and 11 native signal assertions on the Quest.
Version 1.2.13 is installed; the actual hand-gesture check is pending.
See [the hand Menu report](PASSTHROUGH_MENU_FIX.md) for validation and the status
of the physical hand check.

## Visible hand controls and reset — Quest 1.2.12 (38)

The wearer confirmed that they use hand tracking and that system Reset View
still showed nothing in 1.2.11. Live logs recorded the reset and placement, which
isolated a panel orientation error: the menu, mirror and dock were rotated toward
their back faces. Their visible/touchable local -Z faces now point toward the
wearer, following Meta's UI panel positioning sample. The lower dock tilts up
for hand interaction. Placement tests now check the actual panel face and
screen-right direction at four viewer headings. The signed release passes all
379 JVM tests and is installed on the connected Quest Pro. The wearer confirmed
that Reset View shows the menu; the hand button still failed in this version.
See [the recovery report](PASSTHROUGH_VIEW_RECOVERY.md) for validation and the
status of the awake-headset check.

## Menu and reset recovery — Quest 1.2.11 (37)

Menu and Reset view recover the whole immersive layout: menu, mirror and dock.
User-initiated system Reset View also reopens the launcher. A **Reset view**
button is available beside Menu, in the launcher footer and on the live mirror.
Recovery uses two fresh head poses and has a bounded settling interval, so
looking around does not indefinitely block placement. Pause/focus loss discards
old pose samples while retaining the recovery request. Controller Menu also
reads the local player's left-hand press edge, using Meta's documented input
path rather than depending only on globally active controller rays.
The signed build passed 378 JVM tests and was installed on the connected Quest
Pro. The subsequent wearer check still showed no panels; see
[the recovery report](PASSTHROUGH_VIEW_RECOVERY.md).

## Passthrough hand Menu — Quest 1.2.10 (36)

Hand-tracking permission is requested in passthrough as well as the live mirror.
The floating Menu button, left controller Menu and Android Menu delivery now
open/bring the launcher ahead instead of toggling it closed. The existing menu
stays visible and selectable while fresh placement is pending. Inactive
controllers cannot hold Menu down, and a missing Android key-up no longer blocks
the next fresh press. Close and Back keep their existing navigation behavior.
The signed build passes 371 JVM tests and is installed on the connected Quest
Pro. Live hand-control Menu verification is pending; see
[the Menu repair notes](PASSTHROUGH_MENU_FIX.md).

## Passthrough exit — Quest 1.2.9 (35)

The passthrough launcher and dock now show **Back to mirror**. Controller B and
Android Back return from a submenu to the launcher, then leave passthrough for
the regular My mirror window. With the menu closed, Back leaves directly.
An explicit exit no longer waits for camera permission, scene initialization or
XR tracking readiness. The handoff stops tracking/sharing, opens the mirror and
finishes the immersive task; duplicate input cannot launch multiple windows.
All 368 JVM tests pass and the release signature/package checks pass. Version
1.2.9 is installed on the connected Quest Pro. The wearer confirmed that Back
returns from passthrough to My mirror.

## Face selection — Quest 1.2.8 (34)

Gold, Cyber and Holo are removed from the selectable presets, face cycling and
Party mode. The remaining built-in faces are Rain, Snow, Unicorn, Lion and Dragon.
Saved selections of the removed faces migrate to Rain in both mirror and
passthrough views. Legacy enum identifiers are retained internally for migration.
The signed APK passes all 360 JVM tests and the packaged portrait checks.

## Artist-created human heads — Quest 1.2.7 (33)

Rain Portrait and Snow Portrait now use Blender Studio's sculpted 3D characters,
including their modeled hair, painted materials, eyes, teeth and facial rigs.
Gold and Cyber use Snow's geometry; Holo uses Rain's geometry. Expressions and
gaze use sampled rig deformations in the native scene, gallery and mirror views.
The signed APK **1.2.7 (33)** passes all **356 JVM tests** and the packaged
portrait/credit checks.
The shared software renderer retains depth-tested geometry, and wearer rendering
avoids per-triangle temporary objects and sorting.

See the [actual app render preview](assets/previews/artist-human-faces-v7.png),
[conversion and validation notes](ARTIST_PORTRAITS_V7.md), and
[source attribution](ARTIST_PORTRAITS_LICENSES.md). These are adapted free
CC BY 4.0 characters. Their sculpt and proportions differ from the supplied
Memoji reference. Live headset appearance, fit and sustained performance need
device testing.

## Human face refinement — Quest 1.2.6 (32)

All five human presets have revised cheek/jaw profiles, rounded nose tips,
deeper eye sockets and eyelids, fuller blended lips, and flowing hair geometry.
Natural has darker wavy locks; Bearded, Gold and Holo have swept strands.
The result remains an approximation of the supplied reference, and does not
match the original models' finish exactly. Meta/Horizon, Meta sample and animal
head geometry are unchanged.

See the [five-face preview](assets/previews/reference-human-faces-v6.png) and
[validation notes](CARTOON_FACES_V6.md). Signed APK **1.2.6 (32)** passes all
339 JVM tests. The previews show actual app meshes in the software renderer;
native headset appearance and performance still need device testing.

## Reference-inspired avatars — Quest 1.2.5 (31)

The human faces now follow the supplied avatar reference with rounder cheeks,
larger dark eyes, sculpted button noses, fuller lips and wider smiles. Natural
has a curly bob and round gold frames, Bearded has a swept quiff, Gold has a
curled hairline, and Holo has an intertwined side braid. Static hairstyles are
cached and remain rigid while the face talks. Animal head anatomy and the
Meta/Horizon and Meta sample renderers are unchanged.

See the [new avatar collection](assets/previews/reference-avatars-v5.png) and
[validation notes](CARTOON_FACES_V5.md). Signed APK **1.2.5 (31)** passes all
338 JVM tests. The previews are offline renders of actual application meshes;
live headset fit, appearance and performance still require device testing.

## Sculpted character artwork — Quest 1.2.4 (30)

The eight built-in heads now have separate surface finishes for glossy eyes,
satin skin/fur, polished horns and luminous accents. Software views use shared
studio lighting; the native scene uses separate material sections. The lion has
a continuous swept mane, the unicorn has softly fluted pastel hair and a spiral
horn, and the dragon has an emerald coat and subtle cheek scales. Their animal
head shapes and expression tracking remain intact.

See the [character artwork](assets/previews/character-art-v4.png),
[animal heads from three angles](assets/previews/animal-heads-v4.png), and
[validation notes](CARTOON_FACES_V4.md). Signed APK **1.2.4 (30)** passes all
336 JVM tests. Previews use the actual application meshes and software renderer;
native headset appearance and frame time still require device testing.

## Animal head anatomy — Quest 1.2.3 (29)

Unicorn, Dragon and Lion now have their own animal head shapes: a long horse
bridge and muzzle, a broad reptilian snout with webbed frills and slit pupils,
and a feline muzzle with whisker pads and whiskers. Speech moves the animal jaws;
blink, gaze and custom colors remain supported. Human presets also receive
softer tapered proportions, almond eyes, fuller lips, swept hair and a fitted
beard without the dark mouth ring.

See the [animal heads from three angles](assets/previews/animal-heads-v3.png),
[all eight presets](assets/previews/cartoon-faces-v3.png) and
[validation notes](CARTOON_FACES_V3.md). The signed APK is **1.2.3 (29)**;
all 333 JVM tests passed. Previews use the actual application geometry and
software renderer. Live headset appearance and performance still need testing.

## Rebuilt character faces — Quest 1.2.2 (28)

All eight built-in faces have been redesigned with compact cartoon proportions,
larger warm eyes, surface-following mouths and sculpted hairstyles. The creatures
now have separate lion-cub, baby-dragon and pony-unicorn rigs. The software mirror
and video renderer now depth-tests each pixel, fixing the speckled eyes and patchy
beard caused by drawing overlapping surfaces in triangle order. Meta/Horizon and
Meta sample rendering are unchanged.

See the [new face collection](assets/previews/cartoon-faces-v2.png) and
[validation notes](CARTOON_FACES_V2.md). The signed APK is 1.2.2 (28).

## Cartoon faces — Quest 1.2.1 (27)

The eight built-in faces now share sculpted cartoon geometry across the regular
mirror, webcam video, and native passthrough view. Larger eyes, rounded cheeks,
blended noses, curved lips, swept hair and a fitted beard replace the primitive
face parts. Lion, dragon and unicorn have distinct muzzles, ears, horns and manes.
Talking moves the jaw without resizing the whole head. The gallery uses higher
resolution previews, and the hairstyle setting controls visibility.

Meta/Horizon avatar and Meta sample rendering are unchanged. Imported images
remain static photo masks. See the [face preview](assets/previews/cartoon-faces.png)
and [validation notes](CARTOON_FACES.md). The APK is built for testing; visual
appearance and performance on a physical headset still need confirmation.

## Windows layout — 1.5.0

The Windows webcam now centres the experience on a large preview, with connection,
background and camera tools in a separate sidebar. Manual pairing and repair/reset
options expand when needed. Ready/waiting guidance, a receiver status badge and
source resolution are visible without technical logs. The background and pairing
windows use the same theme. The layout was checked with synthetic ready, waiting,
live and expanded states, compact sizing and 150% simulated layout/text scaling.
See [the Windows layout report](WINDOWS_LAYOUT.md).

## Windows webcam backgrounds — 1.4.0

The Windows receiver now has a **Background…** menu: Original, dark/light studio,
custom colour, PNG/JPG picture and green screen. It removes a plain avatar
backdrop locally before both receiver preview and virtual-camera publication.
Enclosed clothing details remain protected; a covered corner is repaired from
the agreeing corners. Edge strength is adjustable. Settings and the chosen
picture are saved on the PC. This feature does not change the Quest mirror or
require a headset update. Detailed-scene segmentation and transparent RGB/YUV
webcam output are not claimed. See [the background report](WEBCAM_BACKGROUNDS.md)
and [the Windows instructions](windows/README.md).

## Quick launcher — Quest 1.2.0 (26)

The user's selected second layout, **Quick launcher**, is implemented and
installed on Quest Pro. Its large **Faces / Avatar / PC Link / Add face** tiles
replace the crowded mirror tabs and immersive controls. The regular window keeps
a larger mirror preview and a visible **Menu** button. The immersive left ≡
shortcut opens the menu ahead of the viewer; Back returns to the launcher, then
closes it. Closing controls preserves the current selection and sharing session.
Android/SDK duplicate delivery, held buttons and focus loss are guarded.

PNG/JPG imports now decode the actual file, show a square preview, save a named
photo mask to the headset library, and use its pixels in the regular camera
mirror and passthrough renderer. These are static head-following photo masks;
they do not create a blinking/talking 3D rig. Live mirror photo masks and arbitrary
3D model import are not supported. Mirror and passthrough keep separate choices.

The signed release passed **312 JVM tests**, **7/7 synthetic photo-import/render
checks**, and **6/6 synthetic native menu captures**, which were visually inspected.
Installation and return to normal My mirror were confirmed. Physical controller
delivery, wearer placement, user-file selection, live passthrough photo rendering
and another real webcam pairing session remain to be tested. The regular 2D
sample tracking comparison from 1.1.12 also remains pending; this layout release
does not establish smoother or more reliable sample tracking.
See [the layout deployment report](LAYOUT_DEPLOYMENT.md).

## October 13 regular 2D sample update

**Previous tracking release:** The final signed Quest **1.1.12 (25)** build passed
**278 JVM unit tests**, with zero failures/errors, and the standalone native
exact-morph-cache check. It was installed as a signed update and normal
**My mirror → Meta sample** was opened in the regular 2D window. Loaded-camera
timing comparisons and wearer confirmation remain **pending**. The requested
live smoothness and reliability remain unresolved. Existing Spatial SDK Kotlin
metadata analyzer diagnostics remain in the build log; the final build result
was **BUILD SUCCESSFUL**.
See [the tracking report](SAMPLE_2D_TRACKING.md).

The earlier **1.1.11 (24)** passed 255 unit tests, five standalone native recovery
scenarios and the combined public animation check (4/4), including independent
face/eye animation with inactive hands. After the clean original-Horizon
comparison, the wearer reported that only hands still need improvement. The
subsequent normal Meta sample comparison rejected 1.1.11: "I need it as smooth as
the full screen, it is really delayed." Those earlier checks are not a live
sample tracking pass.

**Implemented in Quest 1.1.12 (25):** Face and hand models run on separate
workers, each with bounded latest-image queuing, its own model and real capture
clock. Sample observations reach the UI as tracking controls without copying the
hidden camera image into the preview. Fresh controls can advance SDK animation
between observations at profile/thermal limits: approximately 30/20/15 scheduled
draws per second for Fast response/Balanced/Maximum detail before heat limits and
actual processing cost. Drawing time consumes that interval instead of adding a
second full delay. A per-mesh exact weight cache skips redundant CPU morph work
and vertex uploads while bones and SDK animation still advance; invalid weights
are rejected and failed uploads invalidate the cache. Loaded-camera timing and
the wearer comparison are **pending**. These are rendering targets, not measured
camera, inference or display rates.

Regular 2D Meta sample still estimates movement from Horizon OS avatar video.
The additional image-analysis stage and ambiguous hand depth/fingers prevent a
promise of full-screen direct-sensor smoothness or accuracy. Animate mirror uses
the focused XR sensor path; 1.1.12 keeps the ordinary 2D window.

Quest **1.1.11 (24)** keeps **Meta sample** selected inside the regular mirror window,
including missed detections, model startup and camera recovery. The Horizon
camera is a hidden animation input; its pixels are never shown or streamed while
Meta sample is selected. Face and hand results publish separately. Missing or old
observations clear the affected controls to neutral, with freshness checks and
automatic inference recovery. This route uses video estimates and does not expose
raw sensors in the 2D window. Validation results are recorded in the report below.
The 1.1.11 implementation uses a persistent GPU inference worker, smaller hidden
camera inputs, rendering only for new observations, elapsed animation timing and
bounded neutral settling. It publishes face/eye estimates before the hand model
finishes and retries missing permitted direct-sensor resources and hand bind
meshes. Version 1.1.12 changes the worker and animation scheduling described
above. Direct sensor/sample output remains 1920 × 1080; the regular sample
now offers Fast response (540p), Balanced (720p) and Maximum detail (1080p).
Versions 1.1.3 and 1.1.4
failed the user's live tracking checks; synthetic tests alone do not establish
reliability. The original Horizon camera was confirmed to animate face, eyes and
hands in the regular window, but the 1.1.5 live sample test still failed. Version
1.1.6 also waits for native rig assets before marking the model ready. Regular
Meta sample tracking is labeled experimental; the current image-model route is
not verified reliable for all three channels. The signed 1.1.6 release passed 190
unit tests, the public image replay and two cold-start gap/recovery checks; its
wearer check still failed. The requested live reliability remains unresolved.
The combined public animation check subsequently passed while the real camera
and GPU estimators ran. A measured timing defect forced live estimates back to
neutral before the next inference: 1.1.7 gives local video input a bounded one
second capture-age budget, preserving its real timestamp and the separate LAN
deadline. Detector presence and actual estimated movement are checked separately.
The signed 1.1.7 release passed 207 unit checks and the public sample
gap/recovery check, and is installed on Quest Pro. This repair still requires
live wearer validation of accuracy. The 1.1.7 wearer check did show following
movement but reported noticeable delay. Version 1.1.8 reduces sample drawing
load with Fast response as its default and shares one input image between face
and hand models. New stage timings identify the remaining processing cost;
207 unit checks, the six public GPU fixtures and all three render profiles passed.
Measured public checks confirm lower pixel-drawing cost, but image estimation
remains the main delay. The 1.1.8 wearer comparison reported no improvement.
Version 1.1.9 selects a supported native stream supplying the models' 640 × 360
input, samples independently of the LAN/inference budget with thermal limits,
and schedules a draw immediately after a new estimate arrives. The signed
release passed 215 unit checks and is installed. The combined public camera/SDK
check passed 3/3 captures, but the 1.1.9 wearer check failed: "Nothing follows."
Coarse normal-run data still shows only about 3.7 rendered frames/sec, intermittent
face/eye observations and no sampled hand candidates. Less queueing does not
establish reliable live sample tracking in an ordinary OS window.
Version 1.1.10 restores the previous sample source-size policy (1024 × 576 on
the connected Quest), retaining 640 × 360 model input, 33 ms sampling with thermal
limits and immediate result publication. It also adds an explicit public face-only
diagnostic to isolate animation when hand input is unavailable. The signed release
passed 217 unit checks and was installed; these changes are not a live tracking
pass. Subsequent My Horizon avatar checks reported frozen or delayed face/eye
and hand motion; after an explicit clean original-avatar comparison, the wearer
reported that only hands still need improvement. That source comparison does not
establish a 1.1.10 Meta sample pass. Current validation is recorded in the report.
See [SAMPLE_2D_TRACKING.md](SAMPLE_2D_TRACKING.md).

## October 8 video quality upgrade

Quest **1.1.1 (14)** and Windows Webcam **1.3.0** preserve native camera detail,
default to Maximum detail with JPEG quality 97, and provide 1080p/720p/540p webcam
formats. The connected Quest Pro advertises 1600 × 1200 avatar images, giving a
1600 × 900 unzoomed widescreen crop. A 1080p camera output enlarges that feed;
the SDK sample itself renders at 1080p with up to 4× multisample antialiasing.
See [VIDEO_QUALITY_REPORT.md](VIDEO_QUALITY_REPORT.md) for changes and test results.
The complete installer is `windows/dist/AlterEgoMR-Webcam-Setup-1.3.0.exe`.

## Earlier October 8 upgrade

Quest **1.1.0 (13)** and Windows Webcam **1.2.0** add a four-tab mirror,
animated preset gallery, face-fit controls, conservative hand-overlap protection,
adaptive video scheduling, nearby-PC discovery with matching-code approval on
both devices, and explicit receipts for fresh video. The Windows camera supports
RGB32, NV12 and YUY2, with repeated-start and reopen repairs for MediaCapture.
The complete offline installer is `windows/dist/AlterEgoMR-Webcam-Setup-1.2.0.exe`.

See [UPGRADE_REPORT.md](UPGRADE_REPORT.md) for the eight upgrade areas, current
verification and remaining physical-device/call-app checks. The installed Quest
Pro's regular Horizon mirror was confirmed working by the user; the installed
Windows component passed capture and reopen checks. Presets remain procedural,
stylized models, and this build does not guarantee photorealism or 30 new pictures
per second. No usable trusted publisher certificate was found on this PC.

AlterEgo MR is a standalone mixed reality app intended to place animated 3D faces over other people. **Eye tracking is recommended, not required.** Quest 3 and Quest 3S use head direction to select faces. Devices with valid eye tracking automatically use real gaze and can prioritize the detail of the selected face. Detection, expressions, selection, and rendering run on the headset; no PC, phone, or cloud service is needed during use.

The app uses Kotlin, Meta Spatial SDK 0.14.0, an optional native OpenXR eye-tracking bridge, and on-device MediaPipe Face Landmarker. Accessible, calibrated outward camera images remain essential for live face replacement.

## Current hardware limitation

**Live face replacement cannot run on Quest Pro through Meta's Passthrough Camera API.** Quest Pro supplies real eye gaze and can display passthrough, but the API does not expose its outward camera images for the face detector. Showing passthrough is different from receiving camera pixels.

Meta's [Passthrough Camera API overview](https://developers.meta.com/horizon/documentation/unity/unity-pca-overview/) lists Quest 3 and Quest 3S. This build targets Quest Pro, Quest 3, and Quest 3S, with the eye-tracking manifest feature marked optional. It requests eye permissions only on devices with eye hardware. Denied eye permission does not block camera tracking or head-direction selection.

| Device | Eye tracking | Camera pipeline / current build |
| --- | --- | --- |
| Quest Pro | Verified with physical sensors on the connected headset | No outward PCA camera; displays an explicit limitation message |
| Quest 3 / 3S | Uses real head direction instead | PCA available; standalone camera/tracking path enabled in this build, pending physical-device QA |
| Meta VR Glasses | Documented by Meta | No Glasses hardware tested; PCA capability and complete app compatibility remain unverified |

Meta's [Glasses guidance](https://developers.meta.com/vr/essentials/get-started-with-glasses/) confirms eye tracking. Its statement about unavailable camera-based facial tracking concerns wearer face animation; it is not evidence that an outward-camera detector of other people works. This build must not be advertised as verified Glasses support. Camera discovery checks actual vendor metadata before reported device names, because [compatibility mode](https://developers.meta.com/vr/essentials/compatibility-mode/) can report an older Quest model.

The connected Quest Pro was used to verify real eye sensors. A Quest 3/3S was not available for physical camera testing. The app reports unavailable cameras instead of presenting simulated faces as real tracking.

## Selecting faces and rendering quality

- **Quest 3/3S:** Point the centre-view aiming dot at a person's face. A 120 ms dwell selects the nearest intersected head. The dot turns green on selection. Head direction also targets the floating menu; direct hand/controller interaction remains available.
- **Eye-enabled devices:** Valid, fresh hardware gaze takes priority over head direction. The menu identifies the active input. A brief tracking gap hides the Focus overlay rather than switching people during a blink. After 750 ms of continued eye loss, head direction takes over; missing hardware, denied permission, or unavailable eye runtime use head direction immediately. Switching input restarts dwell.
- **Focus mode:** Shows only the selected face. **Party mode:** Shows up to four fresh, calibrated detections even without eye sensors or an active selection. Camera tracking is independent of eye validity.
- **Avatar quality:** High uses detailed meshes and updates expressions on each tracked frame. Balanced uses moderate detail with updates up to 30 times per second. Performance uses lighter meshes with updates up to 15 times per second. Head poses still update on scene ticks.
- **Prioritize face selected by eye gaze:** With a valid eye-selected face, its geometry gets full detail and expressions update on each tracked frame; other visible faces use lighter skull geometry and expression updates up to 10 times per second. The toggle can be disabled. This is application-level mesh scheduling, not compositor eye-tracked foveation, and no measured FPS improvement is claimed.

## Core repairs

- Real eye samples come from XR_FB_eye_tracking_social using the Spatial SDK's existing OpenXR session. Permission denial, missing hardware, invalid samples, and unavailable runtimes are handled explicitly.
- Eye or head-direction selection intersects the world-space ray with each oriented head volume, picks the nearest hit, and requires 120 ms of dwell. Stale input clears selection.
- Camera discovery uses Meta's passthrough source and position metadata. Output sizes are negotiated at runtime. Exposure metadata is matched to image timestamps; cropped and resized intrinsics, distortion, and camera lens pose are carried with each frame.
- MediaPipe tracks up to four people. Temporary geometric IDs remain stable across detection reordering and short misses. IDs are not biometric identities and cannot guarantee recovery through long occlusions or ambiguous crossings.
- Pose fitting uses calibrated image rays and a canonical face model. Distance is an **approximate monocular estimate**, not stereo depth or a measured personal face size.
- Image capture time selects a historical full viewer pose, including pitch and roll. Camera coordinates are converted into Spatial SDK coordinates before composing the world pose. Missing calibration, unreliable timing, stale data, or unavailable history hide overlays.
- Face overlays now use native stereoscopic scene meshes with individual expression and hair state rather than flat face panels. Focus mode shows the selected person; Party mode can show up to four. Zero opacity hides all avatars.
- Model initialization, camera sessions, in-flight image ownership, pause/resume, OpenXR teardown, and UI target disposal have explicit cleanup. Camera images are neither written to debug files nor uploaded by this pipeline.
- Unsupported-camera and tracking failures have a readable status panel with retry and close controls. Photo buttons no longer claim that an image was saved.

## Build and install

Requirements: JDK 17, Android SDK 34, NDK 27.2.12479018, CMake 3.22.1, and the model asset at app/src/main/assets/face_landmarker.task.

Run these commands from the project folder in PowerShell:

    .\gradlew.bat assembleDebug testDebugUnitTest
    .\install_to_quest.ps1

With multiple connected devices, pass -Serial <device-serial> to the installation script. It builds and installs the signed release APK and launches the app. Use -DebugBuild only for development. Accept the required Headset Camera request. Eye tracking is optional and offered only on eye-enabled devices. The script does not grant permissions automatically. Package: com.misterslee.alterego.mr. The resulting APK is app/build/outputs/apk/release/app-release.apk. Release signing is described in SIGNING.md.

## Historical verification and remaining work

The optional-eye update initially passed 53 unit checks. The avatar-camera build on October 5, 2026 passed 89 unit checks with no failures or errors. Native regression checks verify expression mapping, parent-relative finger motion, both wrist/palm axes, invariance under wrist rotation, and invalid/lost hands. The packaged manifest targets Quest Pro / Quest 3 / Quest 3S with optional eye tracking and separate 2D/immersive tasks. The earlier webcam stability revision was Quest **1.0.11 (12)** with Windows companion **1.1.1**; its results are recorded in [PC_WEBCAM_STABILITY.md](PC_WEBCAM_STABILITY.md). Initial webcam deployment evidence remains in [PC_WEBCAM_REPAIRS.md](PC_WEBCAM_REPAIRS.md). The signed Quest APK is app/build/outputs/apk/release/app-release.apk.

The mirror build was installed and launched on the connected Quest Pro. While the user wore it, runtime samples confirmed real head pose, both hand trackers, and valid hardware eye gaze. That test exposed missing body/face extension startup configuration, which was repaired using optional manifest features from Meta's body-tracking sample. The final installed build's logs confirm XR_FB_body_tracking and XR_FB_face_tracking2 are enabled. The subsequent cooperative-window revision adds a separate sampling loop because Spatial SDK skips application scene callbacks when its Android activity is paused. Subsequent continuous logs captured a focused cooperative session with valid head pose, upper-body joints, physical face weights, and both hands. The user confirmed the movable OS window; the later live flat-panel mode restored face, eye, and hand motion, with wrist/finger direction requiring the subsequent correction. A longer animation/focus test is still needed. No Quest 3/3S was available for outward-camera or aiming-dot testing.

**End-to-end camera tracking and visible face alignment remain unverified**, because that headset cannot provide the required images. Real-camera QA must cover movement, latency, crop calibration, scale variation between people, glasses, lighting, occlusion, and people crossing paths. Viewer pose history reduces delayed-frame errors but does not establish a measured latency or precise exposure-time alignment on hardware.

Passthrough face replacement and the fallback presets use a shared procedural face rig and persona colors. The immersive mirror also contains an SDK avatar entity and custom GLES renderer, verified with Meta's official sample. Personal Horizon avatar loading through that SDK remains blocked by Meta account-data approval. The new regular 2D mirror instead reads the OS avatar camera, which does not use that account-loading path. Photorealistic celebrity likenesses, imported photo textures, custom 3D stickers, compositor eye-tracked foveation, and capture/export are not complete features. Old preview components are not proof of native feature support. Do not claim zero lag, exact metric depth, complete Glasses compatibility, or production readiness from unit tests alone.

## Wearer mirror and Windows webcam

**Added in 1.0.9 and retained in 1.0.10:** the regular OS-managed mirror opens the system Avatar Selfie Camera without starting an XR session. My Horizon avatar shows the OS-rendered feed; presets attempt experimental face replacement estimated from the video and retain Horizon's body and hands. Framing controls an upper-body crop, Retry camera restarts capture, and webcam sharing sends that rendered 960 × 540 picture. No usable face detection leaves the original avatar visible. Camera-source selection rejects outward cameras; pause, loss of frames and mode exit stop sharing. Quest Pro logs confirm the camera delivered a frame, and the user reported their avatar and preset face both move. The installed 1.0.9 frame-ownership correction subsequently passed six public-sample video-overlay poses; sustained live expression accuracy and visual alignment need further physical testing. Version 1.0.10 replaces the original webcam connection with secure pairing. See [avatar-camera mirror behavior and validation](AVATAR_CAMERA_MIRROR.md).

The app launches through **MirrorActivity**, a real OS-managed 2D panel using the system avatar camera. **Animate mirror** opens a movable flat panel within the app's passthrough session, keeping OpenXR input focus for live wearer animation. **Open mirror window** closes that immersive task and returns to the camera mirror. **Full-screen passthrough** opens outward-camera face replacement. The immersive mirror reads a separate OpenXR pipeline for the wearer: head pose, XR_FB_body_tracking upper-body joints, XR_EXT_hand_tracking fingers, optional real eye gaze, and XR_FB_face_tracking2 visual expressions. The sample's fingers use the runtime XR_FB_hand_tracking_mesh bind skeleton and SDK custom-hand retargeting. Visual face tracking is explicitly requested on Quest Pro. Missing permissions, absent sensors, invalid joints, or paused sessions are visible in the status; neutral expressions are used when physical face tracking is unavailable. The outward face detector does not supply self-tracking data.

The immersive preview draws a procedural upper-body avatar from approximately the belly upward, using available runtime body/hand joints and the existing animated face rig. Body tracking can be inferred by the headset runtime; it is not a camera photograph or a claim of exact measured anatomy. Immersive preset selection changes the shared rig's colors. The viewer can recenter the virtual mirror camera. Avatar frames are generated at up to 15 Hz, independent of the outward-camera pipeline. The regular OS mirror instead retains the system avatar video and applies any selected face overlay to it.

**Reset view:** Version 1.0.8 selects Meta's recenterable `LOCAL_FLOOR` scene space and queues its recenter callback even during a Quick Settings pause, then places the app's flat panels using two consistent fresh frames from a focused session. Entering Animate mirror hides the previous layout and waits for live-session focus before automatically placing the mirror ahead. The main panel is level, 0.82 m ahead and 4 cm below eye height. Its tabs and dialogs share that placement. The dock sits below it; the side mirror faces the viewer. Centre view, menu opening, and mode changes use the same layout. Reset preserves which content is open. Automatic distance-based jumps and unconditional repositioning on resume have been removed. Regular OS mirror startup now keeps the original Android window/task instead of destroying and recreating it. Horizon OS still controls that window's physical placement. Quest Pro logs from version 1.0.7 confirm a user-initiated system reset followed by panel placement; the user reported incorrect placement on first Animate mirror entry. Version 1.0.8 is installed, with physical visual confirmation of first entry and both mirror modes pending.

The earlier exclusive Home transition stopped the OpenXR session and froze the preview. A separate sampling loop keeps head tracking available behind the cooperative OS window, but does not preserve raw input focus. The October 3 live test reproduced head-only motion when that window took focus, despite granted permissions. The user authorized **Animate mirror** as a live flat-panel alternative. Its installed Quest Pro test receives fresh head/body/face data, both hands and both eyes, and loads both hand bind skeletons. The user confirmed live face and eye animation, then reported twisted wrists/fingers. Version 1.0.5 maps finger motion into Meta's official reference skeleton and separately corrects the provider wrist basis. Controlled sample images show upright open hands, opposing thumbs, and closed fists; final physical hand-direction confirmation and a longer session test remain pending. Preset, recenter, and webcam controls operate on the sensor activity. Taking off the headset, switching immersive apps, session loss, and mode exit stop sharing.

For the older cooperative window, raw Home-only and background tracking are not verified features. The OpenXR [body-joint contract](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrBodyJointLocationsFB.html) and [face-weight contract](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrFaceExpressionWeights2FB.html) disable their data when the application loses input focus. The actual Quest Pro logs show focus and these sources becoming valid together, and becoming unavailable during focus loss. The new regular mirror avoids this raw-sensor path by consuming Horizon OS's animated avatar video. It does not obtain an immersive session after that session is closed. Invalid sources remain unavailable instead of showing cached poses as live motion.

The supplied ID **1322338430965742** was a Rift/PC VR registration. With user approval, the standalone Quest registration AlterEgo MR was created under MistersLee with ID **1331229656743286** and package **com.misterslee.alterego.mr**. Its fresh headset check passes Platform initialization and entitlement, then Meta rejects account identity with **error 2**; no token or personal avatar is requested afterward. Organization verification and User ID, User Profile, and Avatars approval remain pending. The mirror offers **My Horizon avatar**, **Meta sample**, and presets, with distinct labels and retry/status handling. The official sample renders without account access; synthetic images verify head/wrist and face/eye animation. The SDK entity receives head/wrist, physical face, eye input and retargeted optical fingers. Full body-joint retargeting and the complete Meta Unity material effects remain unfinished. Personal avatar appearance and sustained physical animation remain unverified. No account ID/token enters logs, preferences, Kotlin, or the LAN protocol. See [avatar integration and Meta setup](META_AVATAR_INTEGRATION.md).

The [Windows companion](windows/README.md) is the single supported native Windows 11 x64 receiver and Media Foundation software camera. **pc_avatar_webcam/dist/pc_avatar_webcam.exe** is now byte-identical to **windows/dist/AlterEgoWebcam.exe**; the incompatible UDP face-rendering prototype is archived and its external DLL downloader is removed. The headset no longer automatically sends tracking to a fixed PC address. Start the installed receiver, enter its private LAN address and **full pairing key** in My mirror, then select **AlterEgo MR Avatar Windows Virtual Camera** in a video-call app. The key combines a six-digit code with the complete 64-hex SHA-256 fingerprint of that receiver's certificate. A short code alone does not connect. Reset pairing in the receiver rotates its identity and code.

The current **AEMR0005** connection uses TLS 1.2 and verifies the pinned receiver fingerprint before sending the pairing code or avatar JPEGs. Webcam sharing preserves the displayed source resolution up to 1920 × 1080, without Meta account credentials. Older encrypted senders retain their fixed 960 × 540 format. The regular OS camera may include a system-rendered environment background; sharing includes that visible background. Outward camera sources are rejected by this mirror. Brief camera gaps retain the recent picture; after two seconds, pixels clear while authenticated no-image records maintain pairing. Sharing closes when the mirror is paused or closed, on mode exit, and on app destruction; the immersive sender also requires a valid tracking session. Sequence and capture-time metadata reject obsolete or malformed input. Disconnects clear both camera and preview; unauthenticated clients cannot take over an active stream.

Desktop frames use `ProgramData/AlterEgoMR/<Windows-user-SID>/frames.dat`. That Windows user can update the channel, Windows LocalService/FrameServer can read it, and ordinary other Windows users cannot read or overwrite it; administrators and SYSTEM retain administrative access. A random channel token additionally prevents a camera activation from obtaining another user's video merely by supplying their file path. Setup removes the former global frame file and its broad Users permissions. The receiver's transport key is stored with per-user Windows DPAPI protection.

**Install webcam** installs the complete EXE, DLL, installer and documentation in **Program Files/AlterEgo MR Webcam**, creates a Start menu shortcut, registers the COM source, secures the user's frame directory, and adds a Private-profile/local-subnet firewall rule pointing at the installed EXE. Windows administrator consent is required; the intended user's SID is captured before elevation. Updates stage the bundle and attempt rollback if registration fails. Removal works without the original extracted bundle and removes this source, receiver files, frame directories, shortcut and firewall rule. Setup downloads no DLL. The complete offline ZIP is **windows/dist/AlterEgoMR-Webcam-1.1.1-Windows11-x64.zip**. Its checksums detect corruption; they do not authenticate a replaced bundle. This development build is unsigned unless a real trusted publisher certificate is configured explicitly. Windows 10 and ARM64 FrameServer are unsupported. Closing the receiver removes its session camera; the call uses a separate microphone.

The earlier 1.0.9 headset build passed **89 unit checks**, including wearer packets, coordinate conversion, sensor validity, expression mapping, stale-data suppression, avatar-access statuses, camera source selection and video-face geometry. The 1.1.0 Windows EXE/DLL build and secure loopback suite pass: TLS/fingerprint and PIN checks, old-protocol rejection, initial sequence zero, exact 3 MiB JPEG bounds, malformed images/dimensions, sequence and capture-clock ordering, active-owner protection, delayed/future-frame dropping, recovery, 45 confirmed frame publications, disconnect clearing and authentication throttling. Native checks verify QPC timestamps, activation metadata, services, orientation and channel-token protection. Packaging checks verify hashes, private ACLs, installer validation and identical compatibility EXE bytes. The production Java sender also delivered five encrypted frames to the real receiver. These synthetic/local checks do not establish the complete Quest-to-call-app chain; current system registration and Quest/Teams/Zoom/browser verification results are tracked in [the repair report](PC_WEBCAM_REPAIRS.md). Window-lifecycle checks cover sampling while the sensor activity is paused, stopped sessions, closed windows, and scene/mode changes. Synthetic SDK sample images verify rendering/animation; they do not establish physical tracking, sustained OS focus, or the actual account avatar.

The original installed APK and previous README/MainActivity copies are retained locally in scratch/ for recovery. They are not release artifacts.

The earlier **1.0.10 (11)** release passed **108 unit checks** and was installed on
Quest Pro with Windows **1.1.0**. The user confirmed live avatar video
in the PC preview, and the Windows service delivered changing real avatar frames
as both RGB32 and converted NV12 with valid timestamps. See
[the repair report](PC_WEBCAM_REPAIRS.md) for the results and remaining call-app
and long-session checks.
