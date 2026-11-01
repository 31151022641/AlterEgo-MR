# Regular 2D Meta sample tracking

## Installed smoother presentation update — Quest 1.1.12 (25), October 13

The wearer rejected 1.1.11 as still noticeably delayed and requested movement as
smooth as full-screen mode. The earlier public publication lead did not establish
acceptable live behavior. The latest retained device excerpt had only one cold
model-warm-up row, so it is not a usable steady-state latency trace.

The installed update gives face and hands independently owned persistent
model workers and bounded latest-only queues, with one coordinator for packet
merging. Opposite-channel late results keep their own acquisition clocks while
the publication clock stays monotonic. The regular sample accepts observations
without copying source camera bitmaps to the UI. The serial face-first path is
retained behind a developer diagnostic switch for a controlled comparison.

Presentation advances existing SDK animation between fresh observations at a
profile/thermal-bounded cadence; no observation times or confidence flags are
refreshed by drawing. Scheduling subtracts time already spent rendering instead
of adding a second full interval afterward. Exact unchanged morph weights can
reuse their successfully uploaded vertices, while SDK updates, bones and drawing
still advance. This exposes existing SDK easing, not newly predicted movement.

Recoverable worker publication/cleanup failures are contained, rejected results
are disposed, and the next pending input can continue. Fault metadata contains
only the fixed phase and exception class, at most once per worker every five
seconds. Model inference failures retain their five-second retry cooldown without
fabricating fresh detections during that cooldown.

The native morph-cache host check passed. The final signed Android build passed
**278 unit tests, zero failures/errors**, and the installed package was confirmed
as **1.1.12 (25)**. The existing Spatial SDK Kotlin-metadata analyzer diagnostics
still appeared; Gradle completed the required build/check tasks successfully.
Independent code review found no blocking ownership, clock, reset or scheduling
issue. Normal 2D Meta sample is open, with no synthetic controls, and its startup
log confirms `parallelSample=true` and the 1024 × 576 source stream. At the first
post-install check Quest reported asleep. The subsequent 120-second coarse watch
contained startup/selection/stream metadata only, with no acquisition, inference
or SDK-render timing rows. It is retained as `sample-live-1.1.12-inactive.log`;
this does not establish performance or reinterpret the wearer's 1.1.11 result.
The loaded-camera serial/parallel comparison and wearer
smoothness check remain pending. GPU contention can limit or reverse parallel
gains; no full-screen-equivalent latency is promised without measurements and the
wearer's confirmation.

## Installed repair — Quest 1.1.11 (24), October 12

After the recovery check, the wearer reported some movement in both routes and
the hand-frequency override as None or Low. The first follow-up log was actually
sample mode: five retained timing observations took 125–420 ms overall, with
51–213 ms face work and 52–296 ms hand work. One observation accepted both hands
while the face was unavailable; most sampled observations accepted no hands.
These sparse flags do not establish anatomical tracking accuracy. That buffer
was not retained as a full live trace; use the recorded coarse observations only.

A fresh original-Horizon activity was then explicitly opened. The log confirmed
`sample=false preset=false`. In that clean comparison the wearer reported that
**only hand tracking still needs improvement**. This supersedes the earlier
blanket source-freeze report; the original source's remaining hand behavior
cannot be attributed to Meta sample inference.

The sample repair publishes the completed face/eye estimate before
waiting for hand inference on the existing GPU worker. Each channel keeps its
own real acquisition time and smoothing state. Missing detections clear their
channel, retained channels expire independently within the existing local limit,
and revisions let a same-frame hand refinement render after its face. This removes
the measured hand-model publication dependency; it does not claim a measured
live speed gain or improved hand recognition yet. No concurrent GPU workers or
lower confidence gates were introduced.

Webcam images use the oldest active channel's capture time with the existing
750 ms limit. Same-time refinements are allowed by revision, backward image
clocks are skipped, and neutral output uses its actual source capture time.
Wire revisions identify successful SDK renders, so same-input easing pictures
can be sent without falsely refreshing capture. Removed channels settle at
200 ms cadence for at most two seconds, and recovering a channel cancels only
its settlement. The current label reports pruned availability rather than the
older publication-time status.

A separate direct-sensor startup repair retries only missing permitted/supported
tracker handles and hand bind meshes every two seconds. Successful resources
remain intact, and occlusion never recreates them. The standalone native policy
check passed five recovery scenarios. The signed release build passed **255 unit
tests, zero failures/errors**, and was installed as version 1.1.11 (24). The known
Spatial SDK Kotlin-metadata analyzer diagnostics remained in the build output;
Gradle nevertheless completed the required build/check tasks successfully.
The corrected combined public check subsequently passed **4/4 captures** with
camera and inference active. Neutral, combined face/hands, recovery, and face-only
outputs were retrieved; visual inspection confirmed blink/jaw/eye changes with
arms lowered in the face-only output. Only synthetic controls on the bundled
public SDK sample were exported. The initial attempt timed out before camera
input was acquired; after the wearer confirmed visibility, the rerun passed.

Four warm synthetic-load observations published face controls at 169/245/245/383
ms after acquisition; their completed hand packets arrived at 324/410/318/453 ms.
Thus face publication led same-frame completion by 155/165/73/70 ms (mean 116 ms).
This establishes removal of a serial publication wait under load, not wearer
accuracy or actual screen latency. Synthetic render ages must not be reported as
live tracking latency. Normal regular-window Meta sample was then reopened. Its
120-second coarse watch contained only startup/selection/stream-size metadata,
with no camera-frame, inference or SDK-render rows; a subsequent status check
confirmed Quest asleep. The trace is preserved as `sample-live-1.1.11-inactive.log`.
Thus actual wearer latency, hand accuracy and reacquisition were unverified at
that point; the wearer subsequently reported that the regular sample remained
really delayed. Normal Meta sample mode remains
open, with no synthetic controls. This repair does not give ordinary Android
windows access to raw sensor streams.

## Current recovery check — October 12

The wearer most recently reported that the original Horizon avatar also lacked
hand movement and blinking despite global tracking being enabled. This is an
upstream animation-source failure, not a passed sample-retargeting check.

A fresh direct comparison was launched with the explicit VR category. Its coarse
log reached READY and FOCUSED and reported eye API success with freshness false,
but provided no usable wearer-channel confirmation. A later snapshot was asleep
with proximity false. These results do not establish sensor failure while worn;
the eye freshness flag alone also cannot distinguish missing or old timestamps
from per-eye validity. No FMM status was present in the retained service log, so
the developer hand-frequency override remains unverified.

Quest was restarted once as a controlled recovery experiment. A fresh regular
2D **My Horizon avatar** activity was then opened, and the avatar camera delivered
frames at 1600 × 1200 before Quest slept again. Frame delivery establishes camera
recovery, not expression or joint movement. The temporary direct panel is closed.
Wearer confirmation after the restart is pending; the corrected public face-only
check and normal Meta sample comparison remain pending too. No new tracking fix
or live-reliability result is claimed, and the installed version remains 1.1.10.

The read-only lifecycle review found no camera request that disables only face or
hands and no estimator on the original-Horizon preview path. Meta documents no
Avatar Selfie Camera expression-reset API. Changing camera exposure controls or
model thresholds is therefore not justified by this source failure. The next
sample comparison must first establish that the original source animates.

## Source-detail restoration — Quest 1.1.10 (23), October 12

The 1.1.9 wearer check failed. Version 1.1.10 restores the prior sample camera
source-size policy: PERFORMANCE selects 1024 × 576 on the observed Quest stream
list, falling back only to supported sizes. Model input remains 640 × 360. The
33 ms sample capture interval (thermal limits retained), latest-input queue and
immediate publication remain. This isolates a possible detail/framing regression
from native 640 capture without claiming equivalent OS-rendered input detail.

The combined public diagnostic has an optional additional face-only phase: head,
explicit blink/jaw/gaze controls remain active while body, wrist, finger and bind
input are cleared. It exports only explicit synthetic controls on the public
sample and identifies its display as an automatic test. This tests a coverage
gap in earlier public checks, where active expressions coincided with active
hands. Production wearer input and camera pixels remain excluded from exports.

The signed release passed **217 unit tests, zero failures/errors**, including
two restored source-selection/fallback cases, and was installed. The combined
face-only check and subsequent live comparison are pending. The first attempt
selected 1024 × 576 but aborted without camera acquisition while Quest was
asleep, exporting no outputs. The ordinary mirror was restored.
The first active diagnostic completed the three existing public captures but
missed the new fourth capture because its requested 17.5-second window fell in
a synthetic neutral interval. That diagnostic scheduling mistake was corrected
to the active 19.5-second interval; it is not evidence of a face-only SDK failure.
The subsequent original-Horizon source check reported **hands do not move and
eyes do not blink** in the ordinary 2D mirror. Therefore the previously confirmed
fully animated OS source is not currently available; image retargeting cannot
reproduce movement absent from its input. App permissions for hand, eye, face
and avatar camera were still granted. The headset was asleep when the later
status snapshot was read, which does not establish its state during the test.
Global tracking configuration/source recovery must be checked before judging
the source-detail restoration or promising reliable sample movement.
The wearer then reported that all global tracking settings were enabled but
the original source remained frozen. A temporary direct-sensor panel was opened;
its coarse 60-second capture contained only extension requests and no sensor
availability or hardware-eye acquisition result, so it is inconclusive. New
headset status/recovery commands were not executed when automatic approval
review failed because of an account usage limit. This was a review availability
failure, not a decision that device inspection was unsafe. A later account-status
check reported ordinary usage available; the same inspection was retried through
approval review and executed successfully, reporting Quest asleep. The latest confirmed
state at that point was: original-source recovery and face-only/live sample
validation were unfinished. The subsequent recovery check above restored the
ordinary mirror; device actions are available again.

The October 12 official API review found no documented direct wearer face/eye/
articulated-hand stream for an independent SDK avatar inside an ordinary Android
2D window. Meta's [AOSP features](https://developers.meta.com/vr/documentation/android-apps/features-overview/)
describe Avatar Selfie Camera as rendered Horizon avatar images;
[Look and Pinch](https://developers.meta.com/vr/documentation/android-apps/gaze-and-hands/)
withholds gaze data from 2D apps; and documented
[native face tracking](https://developers.meta.com/vr/documentation/native/android/move-face-tracking/)
requires an initialized OpenXR session. The new virtual-camera producer API does
not provide expression/joint input metadata or let an app substitute its sample
in the OS-only AVATAR camera source. This public-API limitation explains why
permission changes cannot replace image estimation in regular sample mode.

## Capture and publication timing — Quest 1.1.9 (22)

The 1.1.8 wearer check reported **no improvement**. Coarse live observations
still showed about 237–438 ms processing and 277–465 ms input age at completion,
with occasional larger rendering spikes. Face and hand model calls dominate;
lower drawing resolution alone did not satisfy the requested speed.

Version 1.1.9 chooses a supported native camera stream supplying the models'
640 × 360 useful crop with the least YUV conversion work. Original Horizon and
preset video quality selection is unchanged. Sample capture uses 33 ms sampling
independently of the LAN/inference processing budget, slowing to 66/100 ms at
thermal limits. The bounded latest-input queue retains the original acquisition
timestamp. Completed sample estimates request a draw immediately; the fallback
poll is 33 ms. This removes avoidable capture/publication waits without claiming
to make image inference equivalent to direct sensors.

The signed release passed **215 unit tests, zero failures/errors**, including
eight camera selection/cadence checks, and was installed on Quest Pro. Its actual
camera request selected 640 × 360. The initial combined public check timed out
without camera acquisition while Quest reported Asleep, exporting no outputs.
The script restored the ordinary mirror. On October 12, after the wearer activated
Quest, the combined public check completed **3/3 captures**, with current camera
and inference input throughout. Public output showed face/eye controls and both
articulated hands at 960 × 540. Every exported image used explicit synthetic
public-sample controls; no wearer camera frame was exported.

The subsequent ordinary Meta sample check **failed**: the wearer reported
**"Nothing follows."** Coarse live metadata shows face/expression and gaze
estimates changing, but every sampled hand inference in the retained window
reported zero candidates/accepted hands. The native renderer accepted face/eye
input and published new sample frames. This does not prove recognition of the
wearer's intended movements.

The retained comparison windows cover roughly 33 seconds each with sparse
five-second logging (six 1.1.8 inference rows, seven 1.1.9 rows). Sampled queue
medians were 49/13 ms, processing 344.5/308 ms and capture-to-inference completion
406/314 ms respectively. Normal render-count intervals remained about 3.7 fps
in both versions. These are not physical movement-to-display measurements, and
the unequal hand workload prevents a controlled speed claim. Brief face/eye
unavailable transitions recovered after 312–718 ms; no camera restart or failed
render appears in this retained window. Reliable regular 2D Meta sample tracking
remains unresolved despite the passing automated rendering checks.

The coarse watch script now accumulates unique metadata rows rather than replacing
the file on each poll, preventing Android's rolling log buffer from silently
shortening future capture windows. It also tolerates a missing process during
startup. These diagnostic changes do not alter the APK or live tracking.

## Response-speed upgrade — Quest 1.1.8 (21), October 11

The 1.1.7 wearer check confirmed following movements, but reported noticeable
delay. Coarse live flags recorded changes in all four channels. It therefore
improved animation availability without satisfying the speed/reliability goal.

Version 1.1.8 adds sample-specific Fast response (960 × 540, single sample),
Balanced (1280 × 720, up to 2× antialiasing) and Maximum detail (1920 × 1080,
up to 4× antialiasing) choices in Quality. Fast response is the initial setting
for the inferred sample. Original Horizon video and direct sensor rendering
retain their existing settings. A profile change reallocates only framebuffer
attachments/readback storage, preserving the loaded SDK entity and animation.
Face and hand models share one owned input image during their synchronous calls,
removing a duplicate copy; their failure/retry paths remain independent.
Coarse stage timings now distinguish queue/preprocessing, face, hand, retargeting,
SDK animation and pixel drawing/readback. This will test whether lowering sample
rendering load actually reduces inference delay instead of assuming it does.
The signed release passed 207 unit tests with no failures/errors and was
installed on Quest Pro. The first combined profile check could not acquire the
camera while the headset was inactive and timed out without exporting outputs.
After the wearer activated the headset, the combined check completed 3/3
public captures at 960 × 540, 1280 × 720 and 1920 × 1080, preserving visible
expression, eye and articulated-hand animation across profile changes. The
shared-input models continued processing the real camera, with independent
coarse channels and no input pixels exported. Sample pixel rendering/readback
was about 7 ms in settled Fast response observations and 24–28 ms in sampled
Maximum detail observations. SDK animation updates were 2–7 ms in those samples.
Face inference (about 139–223 ms) and hand inference (57–104 ms) remained the
main costs; these diagnostic timings are not physical movement-to-screen delay.

The separate public GPU replay passed 6/6 fixtures at Full HD with both curled
hands and blinking visible. A public CPU comparison also passed 6/6 but was
slower: 24 warmed public input ages averaged 314.9 ms (199–486 ms), versus
145.1 ms (100–218 ms) for GPU. These are synthetic replay measurements, not a
live source comparison. Production therefore retains GPU with CPU fallback.
The ordinary regular Meta sample was reopened in Fast response without automatic
test flags. The wearer reported **no improvement**, so the speed goal remains
unresolved. Coarse live flags changed in all four channels, but do not prove
accurate, smooth visible tracking. The capture/publication repair above follows
that failed live comparison.

## Timing repair — Quest 1.1.7 (20)

The 1.1.6 wearer test failed. A combined check subsequently kept the real
Horizon camera and GPU inference running while supplying only explicit public
synthetic poses to the SDK. It completed all three captures with current camera
and inference input. The public sample visibly returned to neutral, blinked,
opened its mouth, looked aside and raised both articulated hands, including the
recovery phase. This rules out a general inability to animate the SDK sample
while the camera and estimators run. It does not validate wearer recognition.
The first attempt timed out with no camera acquisition while the headset was
idle; it exported no images. No wearer camera image or numeric landmark was
exported in either attempt.

The live 1.1.6 log exposed a separate timing defect: estimates arrived 362–479 ms
after capture and the SDK draw took about 49 ms, against a 500 ms deadline. An
accepted hand arriving at 479 ms could expire before publication. Face/eye inputs
cycled active to neutral 12 times, remaining active only 108–200 ms per cycle,
before the next 295–429 ms inference completed. Version 1.1.7 retains the real
capture timestamp and bounds local video observations at 1000 ms, allowing the
draw and next estimate to arrive without forcing a neutral gap. Explicit missing
channels, run/selection changes and pause still clear observations. Network
frames retain the separate 750 ms transport limit; raw sensor timing is unchanged.
This repair does not create faster inference or prove accurate motion recognition.
New coarse motion checks distinguish detector presence from changes in expression,
gaze and each hand without exporting wearer values. Comparison anchors detect
gradual movements as well as brief changes, reset at each report window and clear
on missing/invalid input.

The signed 1.1.7 release passed **207 unit tests, zero failures/errors**, and was
installed; the connected Quest reports version 1.1.7, version code 20. The public
180-render gap/recovery check passed, including hands continuing when the face
was absent. Final neutral/baseline face, eyes and hands RGB mean differences were
0.00221, 0.00273 and 0.00065 respectively (0–255 scale). This verifies neutral
clearing and SDK consumption, not live wearer accuracy. The ordinary regular 2D
Meta sample was then explicitly reopened, with no automatic test flag. A live
wearer check reported following movements with noticeable delay. Coarse live metadata records changing expression,
gaze, left hand and right hand estimates, including a frame with both accepted
hands and native SDK inputs enabling both fingers. Detections remain intermittent,
and these flags describe changing estimates rather than verified accuracy or
smooth visible animation. Only coarse movement/timing data was saved. The
previous live failures remain recorded; requested reliable tracking is not yet
a pass; the response-speed upgrade above addresses the next measured problem.

## Earlier update — Quest 1.1.6 (19)

The **1.1.5 live Meta sample test failed**: the wearer still reported unreliable
tracking. Live metadata contained intermittent hand detection and face/eye
active-to-neutral transitions. Thus the lower-resolution GPU pipeline and public
image success do not satisfy the request for reliable tracking in the OS window.
The original Horizon camera remains the wearer-confirmed regular 2D option.

Version 1.1.6 adds the SDK 40 loading prerequisite from
`OvrAvatarEntity_Loading.cs`: wait until
`ovrAvatar2Asset_GetEntityPendingCount(entityId)` is zero before enabling the rig
and its output pose. The earlier renderer could accept a loaded mesh with a back
facing bind pose as ready. An intermittent public gap test exposed that failure;
its rerun passed, so the loading race could not be dismissed as tracking noise.
The new wait retains resource acknowledgements and a bounded 10-second deadline.
A tested but unproven `Tracking_GetPoseValid` render guard rejected every phase
and was removed; the pinned SDK does not use that flag to gate rig rendering.
The app labels this sample mode experimental rather than promising raw sensor
tracking in the OS window.

The signed **1.1.6 (19)** release passed **190 unit tests, zero failures/errors**
and was installed on Quest Pro. Its held public-image replay rendered all six
fixtures through four freshly
acquired inference/render rounds per fixture; final images visibly showed raised
hands, curled fingers, mouth movement and blinking. Public inference still missed
some open-hand views, and these images do not verify physical-user accuracy.
Two consecutive cold-start gap/recovery checks passed using the production
cadence, explicit neutral providers and elapsed animation timing. Final neutral
versus baseline face/eyes/hands RGB mean differences were respectively
0.00182/0.00625/0.00482 in run 1 and 0.00156/0.00352/0.00156 in run 2 (0–255 scale).
Fresh hands continued independently during face/eye loss. These tests verify
SDK consumption and loading/recovery for the public sample, not live recognition.

After the diagnostics, the ordinary 2D mirror was reopened explicitly in Meta
sample mode. The 1.1.6 wearer check **failed**: the user reported a frozen sample
or no following movement. The combined camera/inference/SDK public-input check
above passed, without upgrading that wearer result to a pass. The user-confirmed original
Horizon camera remains the reliable observed option in the regular window.
Meta documents that 2D apps receive standard interaction events rather than
raw gaze, while the Avatar Selfie Camera supplies rendered avatar images:
[Look and Pinch](https://developers.meta.com/vr/documentation/android-apps/gaze-and-hands/)
and [AOSP camera/input features](https://developers.meta.com/vr/documentation/android-apps/features-overview/).

### 1.1.5 processing and recovery changes

The user's 1.1.3 and 1.1.4 live checks **failed**: face, eye and hand animation did
not follow reliably. The original Horizon avatar has now been explicitly selected
and confirmed by the wearer to follow all three channels in the regular 2D window.
This confirms that the OS camera can supply the animation; it does not prove that
image estimation can reconstruct it accurately for the SDK sample.

The production inference worker retains one active and one latest queued image.
Face and hand models use GPU acceleration, with same-thread creation, inference
and close plus CPU fallback. Sample capture selects Performance input size;
Quest Pro selected 1024 × 576 instead of 1600 × 1200 in the first 1.1.5 check.
Sample inference images are drawn directly at at most 640 × 360. Original Horizon
and preset mirrors keep their video-quality setting; SDK sample output remains
1920 × 1080 with the existing antialiasing.

The SDK draws each new observation once. Repeated observations reuse pixels,
without refreshing their acquisition timestamp. Video animation advances by real
elapsed render time, capped at 250 ms and divided into bounded SDK update steps.
Initial neutral poses and tracking-loss transitions draw about every 200 ms for
2 seconds before caching. An exported first-neutral public sample showed that
one update cleared face/eyes but retained raised hands, so a single neutral draw
was insufficient. The gap diagnostic now uses the production settling policy
and does not secretly advance animation through ready-state SDK polling.

Camera errors are tied to their original capture run. A current processing failure
stops that run and reports once, so repeated errors cannot postpone recovery or
interrupt a replacement run. The recovery watchdog watches image acquisition
as well as completed processing, preventing model warmup from restarting capture.

The public image diagnostic now produces all immutable source images before
inference starts. Earlier interleaving of raw-source generation and SDK retargeting
contaminated the test source and produced identical pictures. The isolated test
recognized blinking, mouth motion and both curled hands, rendering 6/6 public
fixtures with warmed inference age 78–140 ms in its first 1.1.5 run. Only one open
hand was recognized. A one-render output can precede the SDK's eased hand pose,
so final rendered motion and physical reliability require additional validation.

No personal camera image, avatar image or numeric wearer landmark was exported.
Only status/timing/channel-count metadata was read for wearer checks. Public
synthetic images are identified separately. No Windows component was changed.

## Earlier implementation and verification

Quest 1.1.3 (16) repairs the alternating Horizon/sample display introduced by
the 1.1.2 fallback. Meta sample stays visible when detections fail or arrive late.
The Horizon camera is a hidden input; the sample render is the only picture
published to the mirror and optional webcam. Missing channels return to neutral.
Camera loss preserves the local sample and clears unavailable webcam images.

Quest 1.1.2 (15) originally added SDK sample animation to MirrorActivity, using the OS
avatar camera as an image source. Selecting Meta sample no longer launches
the immersive activity.

## Input and rendering

FaceLandmarker estimates rotation, expressions and eye-look controls. HandLandmarker
estimates two hands with semantic side labels, image landmarks and metric-relative
landmarks. A video mapper constructs the existing native 1060-field packet;
physical sensor availability remains false. The fixed open-hand reference avoids
calibrating a fist as an open hand. Head, expressions and hand rotations use
time-based smoothing; missing observations clear the corresponding history.
Gaze estimates are disabled while both eyes are closed.

Camera inference stays on its worker. A bounded latest-frame queue hands inputs
to the main thread, which owns all Meta SDK lifecycle and rendering calls. Camera
run and avatar selection epochs reject results from earlier modes. Both preview
and webcam use the acquisition timestamp; SDK rendering does not refresh it.
Sample inputs older than 1000 ms become neutral SDK input. No camera pixels are
shown as a fallback in sample mode. Face and hand inference each receive every
camera frame and have separate retries; a missing face no longer blocks hands.
Missing face clears face rotation, expression and gaze histories, while fresh
hand observations can continue with a neutral head placeholder. Only framing
calibration is retained briefly for estimated hand projection. A separate SDK
render loop keeps sample presentation independent of inference timing. Its render
rate and camera observation rate are reported separately. Camera errors clear
queued observations immediately. Pause clears preview/sharing and removes the
render loop. Sample close/resume starts a new SDK owner.

Video inputs use fixed frontal coordinates. Initial head yaw does not recenter
the hand coordinate system. Input X stays in camera-image direction, and the
regular self-preview applies its existing single horizontal reflection.

## Validation

The earlier 1.1.2 signed release build passed **151 unit tests, zero failures/errors**. New
checks cover head axes and scale, left/right hand identity with crossed hands,
wrist rotation, finger curl beyond 90 degrees, fixed neutral calibration, malformed
and ambiguous inputs, blink/gaze gating, immediate loss/reset, shortest quaternion
interpolation, acquisition-age limits, selection changes and paused camera runs.

Quest Pro ran the public-video test on October 10, 2026. Five warmed input frames
rendered through the SDK, at 201–322 ms inference age in the final run. The initial
539 ms model-startup frame was correctly discarded. Mouth motion and blinking
reached the SDK; closing both eyes disabled the unreliable inferred gaze. Both
curled-hand observations reached articulated finger overrides. The open-hand
fixture recognized only one hand, so full detection reliability is not established.

The test exposed and fixed native calibration reuse when alternating direct
sensor fixtures and video reconstruction. Reinitializing the hand reference for
the new input kind changed inverted palms to upright palms. Final public images
were inspected for open hands, curled fingers and blinking. Direct sensor input
keeps its existing calibration behavior within its own source kind.

Quest **1.1.2 (15)** was installed and the ordinary MirrorActivity was reopened
in Meta sample mode. Live physical tracking, prolonged cadence, hidden-hand
recovery and the new sample's optional webcam path still require user validation;
public synthetic fixtures do not establish those results. No Windows code or
protocol change is needed by this update.

### 1.1.3 validation

The presentation policy now checks channel metadata and packet ownership,
neutralizes malformed/old inputs, and keeps hand observations when face/eye
channels disappear. Regression checks cover hands-only frames, missing-face
history reset, recovery, negative timestamps and placeholder-head labeling.
The synthetic `avatar_render_diagnostic` plus `avatar_sample_gap_test` checks
public sample rendering across active inputs, neutral gaps and recovery without
using any personal camera frames.

The signed **1.1.3 (16)** release passed **173 unit tests, zero failures/errors**
and was installed on Quest Pro. The 180-render public SDK diagnostic initially
found that returning an inactive face/eye provider left the SDK's neutral pose
different from its baseline. The renderer now explicitly supplies zero face
controls with valid confidence and head-relative forward eye poses for video
gaps. UI observation/physical-sensor flags remain false during those gaps.

The final Quest diagnostic passed every input-channel check and all rendered
comparison thresholds: neutral versus baseline RGB mean differences were
0.0013 for face, 0.0043 for eyes and 0.0017 for hands (0–255 scale). Active and
recovered controls visibly differed from neutral; hands remained active during
face/eye loss. The public images were visually inspected: neutral eyes open,
mouth neutral and arms lowered, versus active blinking/open mouth/raised fists;
the hands-only phase kept raised fists with a neutral face. This verifies SDK
control consumption, not recognition accuracy for every Horizon avatar.

The diagnostic was stopped and the ordinary 2D mirror reopened with Meta sample
selected. Quest reported asleep at that status check. The later wearer check
reported unreliable animation; that failure led to the updates above. No personal video was recorded or exported,
and no Windows component or protocol was changed by this repair.

The diagnostic `avatar_render_diagnostic` plus `avatar_2d_sample_test` drives
public SDK sample images through the exact video mapper and SDK renderer. It
contains only synthetic inputs, never camera images, account identity or actual
wearer tracking. Production frames stay in memory.

## Practical limits

This is camera-derived animation, not access to raw sensors in the OS window.
The image models can miss stylized faces/hands. Hidden fingers, absolute wrist
depth and sensor-accurate gaze cannot be reconstructed reliably from a single
rendered image. Confidence in the hand model is a handedness classification
score, not physical sensor confidence. Full shoulder/torso motion is not retargeted;
the SDK supplies body pose from head and wrist inputs.

Quest 3/3S use only the motion represented by their system avatar camera. They do
not acquire physical eye/face sensors through this feature. Sustained physical
hand direction and frame cadence need headset validation. Animate mirror remains
available for direct supported wearer sensors.

Meta's [2D gaze and hand guidance](https://developers.meta.com/vr/documentation/android-apps/gaze-and-hands/)
states that 2D apps do not receive gaze data. Standard hand input is represented
as touch interaction, not the full articulated sensor stream used by OpenXR.
There is no supported independent raw face/eye/full-hand route in this regular
OS window. Independent inference channels do not change that platform limit.

Primary references: Meta's [2D camera/input guidance](https://developers.meta.com/vr/documentation/android-apps/features-overview/),
Google's [hand landmark model guidance](https://github.com/google-ai-edge/mediapipe/blob/master/docs/solutions/hands.md),
and the installed Meta Avatars SDK 40.0.1 declarations and reference hand prefabs.
