# Passthrough face detection check — Quest 1.2.21 (47)

## Result on the connected headset

Live detection of people in front of the connected Quest Pro cannot start with
the current camera interface. This is a camera-access limitation, not a face
asset or sunglasses problem.

Checked on 28 October 2026:

- ADB identifies the connected device as Quest Pro (`seacliff`).
- Both Camera and Headset Camera permissions are granted to AlterEgo.
- Camera service exposes one virtual camera, ID `1`, with
  `com.meta.extra_metadata.camera_source = 1`.
- No outward camera with source `0` is exposed. The app requires source `0`
  and a valid left/right position, calibration and capture timestamp.
- The headset is asleep. No awake live-detection result was obtained.

[Meta's current Spatial SDK camera documentation](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-pca-overview/)
lists Quest 3 and Quest 3S as supported devices for outward camera access and
documents source `0` for passthrough cameras. Displaying passthrough does not
itself grant an application access to its camera images.

The virtual avatar camera and wearer face/eye tracking used by My mirror are
separate from outward detection of other people. They do not establish that
passthrough face detection is receiving images.

## Code review

No demonstrated normal-path detection, association, pose-conversion or target
selection defect was found. The detector uses MediaPipe landmarks and temporary
geometry-based track IDs. It follows faces; it does not identify who a person is.

Capture timestamps are preserved. Missing calibration, stale observations,
missing capture-time headset poses and ambiguous overlapping detections hide
the effect. In focus mode a valid eye/head-direction target needs 120 ms of
dwell; All people bypasses this selection requirement.

An error/recovery inconsistency was found: camera-only retry could not recover
failed detector initialization, and lifecycle clearing could hide detector
failure status. Version 1.2.21 addresses this with:

- A detection status section on every passthrough menu page.
- No misleading retry button for an unsupported camera/device.
- Background model-initialization retry, with duplicate requests disabled.
- Instructions to close and reopen the app for stalled native inference.
- Initialization failure retained through lifecycle clearing, cleared after
  successful initialization, and current timeout status when an old native
  task still blocks new input. The task retains its image until native use ends.

The detection algorithm, capture timestamps and rendering validity checks are
unchanged. This does not enable outward camera access on Quest Pro.

## Release checks

- Release build succeeded. All 444 JVM tests passed with no failures, errors or
  skipped tests.
- Required authored portraits and credits remain packaged.
- APK Signature Scheme v2 verified. Version 1.2.21, code 47.
- APK size: 141,461,770 bytes.
- SHA-256: `B4416A762B77E2BCF65BB574D09A36A719D0BBF5784E75BA521D9B9308835921`.
- Installed successfully on the connected Quest Pro. Installed version/code
  confirmed, and launching passthrough returned `Status: ok`. The headset
  remained asleep.

These checks do not establish live detection accuracy. Testing real people,
lighting, pose, latency and sunglasses alignment requires supported outward
camera images. Model-failure and native-timeout recovery were reviewed in code;
they were not triggered by altering user data or camera images.
