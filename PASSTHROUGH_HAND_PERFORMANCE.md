# Passthrough hand responsiveness

## 1.2.15 (41): configuration correction and retained test evidence

On 2026-10-24 the wearer clarified that Quest Home hands are smooth and the lag
occurs only inside AlterEgo. The wearer can test later. The symptom remains
unresolved; no awake result from 1.2.14 has been measured.

Four existing metadata entries were placed directly under `manifest`, where
Android does not expose them as application metadata. Hand version, passthrough
environment/requirement and the passthrough splash setting now live under
`application`. `com.oculus.vr.focusaware=true` was also added, matching
[Meta's Spatial SDK manifest example](https://developers.meta.com/vr/documentation/spatial-sdk/add-spatial-sdk-to-app/).
[Android's metadata reference](https://developer.android.com/guide/topics/manifest/meta-data-element)
lists component/application parents, not `manifest`. This is a concrete
configuration correction, not proof of the lag's cause. No HIGH/Fast Motion
override or new hand tracker was added.

`HandPerformanceLog` retains aggregate evidence for a later test, without a live
computer capture. On each visible, mounted scene callback it aggregates elapsed
callback timing and the existing SDK input query's source counts/Menu actions.
It never acquires additional hand samples. It emits a summary about every five
seconds, on context changes, or when the session pauses/unmounts. Pause/unmount
break the interval chain, excluding Home/sleep gaps. Main SDK-system time and
app scene work are reported separately. These are callback elapsed times,
**not compositor FPS, dropped-frame counts or motion-to-photon latency**.
Input-source presence/active input is not a measure of pose tracking quality.

The scene thread updates primitive counters; a bounded single background worker
formats and writes summaries. There are at most two roughly 64 KiB files in
the app's external-files `diagnostics` directory. The log contains version,
coarse state, counts and timing only; no hand/eye/body poses, camera content,
account details or network upload. It is not shown in the user interface.

For the later wearer test: open AlterEgo passthrough, move hands for 20 seconds,
try the tracked-hand **☰** once, then close the app normally. Note whether hands
lag with the Faces menu open and closed. Reconnect USB afterward; the test does
not require a live computer connection. Collect the stored data with:

```powershell
.\scripts\collect-hand-performance.ps1 -Serial YOUR_QUEST_SERIAL
```

The collector only reads app/device status and copies the bounded logs into a
new `scratch/hand-performance-*` folder; it does not launch or wake the app.
It reports explicitly when there are no recorded visible scene frames.

Validation: signed release build and **393 JVM tests**, zero
failures/errors/skips. Six new timing regressions cover SDK/app time separation,
sleep gaps, focus/menu context changes, long observed intervals, inactive hand
Menu input, and invalid/non-increasing clock measurements. APK XML inspection
confirms the corrected application metadata and focus-aware boolean. APK
Signature Scheme v2 and both portraits/credits packaging checks pass.

Installed successfully on USB Quest Pro `YOUR_QUEST_SERIAL`, preserving app data.
Package manager confirms **1.2.15 (41)** and passthrough launch returns `Status:
ok`. The collector was run successfully and retrieved the correct-version
session header. It explicitly reported zero visible scene summaries, matching
the asleep/unmounted activity and zero SDK/input-system executions. This verifies
background log creation and retrieval, not awake rendering or hand smoothness.
Evidence is in `scratch/hand-performance-20261024-074734-262/` and
`scratch/menu-diagnosis/v15-manifest-tree.txt`.

Current APK: `app/build/outputs/apk/release/app-release.apk` (141,412,627 bytes).
SHA-256: `2DBD411C17E0F8D7DD577787E5FD1416BC412E8BE9EE19EEF34ABCCC940147D1`.

## 1.2.14 (40): SDK hand ownership and less per-frame work

The wearer reported lagging control hands in passthrough after 1.2.13. During
the awake-headset capture on 2026-10-24, Quest telemetry reported 90/90 FPS,
but the app also logged a hand predictor warning about a non-incrementing
timestamp and reusing an earlier pose. Startup included a 72-frame UI stall.
These observations do not establish a single cause or prove that the GPU was
missing its display target.

The preceding update added two independent OpenXR hand trackers, sampled at
wall-clock time on every app scene frame. That duplicated SDK hand acquisition
and used a different timestamp from its predicted display frame. The duplicate
path has been removed entirely, including its native bridge, signal decoder and
obsolete tests. The SDK remains the owner of control-hand tracking/prediction.

`ImmersiveInputSystem` reads the SDK's existing local `Controller` components
after built-in controller acquisition and before ISDK processing/end-of-frame
cleanup. It uses a reused query and primitive frame state. A hand's fresh Menu
edge is accepted even when its pointing ray is inactive. Inactive physical
controllers are ignored; ordinary hand pinches cannot become Back or mirror
commands. Android/SDK duplicate delivery and lost-key-up recovery remain guarded.

Other work removed from the interaction frame:

- Eye permission names are discovered once for the installation. Grants refresh
  on start/resume and at most once per second, rather than performing package
  manager and permission IPC on every frame. The OS still enforces native access.
- Without an outward camera feed, app eye/gaze work runs only for a visible
  menu's existing 30 Hz overlay. SDK hands, input and panel recovery continue at
  display rate. Stale outward overlays are hidden when the camera feed stops.
- The live-mirror loop owns its own eye sampling, without a second scene-tick
  sample. Passthrough no longer enables hand-microgesture virtual locomotion.

SDK input handling was checked against Meta's
[MRUK input sample](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/blob/main/MrukSample/app/src/main/java/com/meta/spatial/samples/mruksample/common/MrukInputSystem.kt)
and [Interaction SDK overview](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-isdk-overview/),
plus the cached 0.14.0 API and system scheduling implementation.

Validation: signed release builds successfully; **387 JVM tests**, zero
failures/errors/skips. Eight input regressions cover an inactive hand ray,
inactive physical controllers, held Menu, duplicate sources, lost Android key-up,
focus loss, physical Back/mirror and ordinary hand pinches. Signature Scheme v2
and portrait/credit packaging checks pass. The release shared library was checked
to ensure the duplicate native hand tracker exports are absent.

Version 1.2.14 (40) was installed on the connected Quest Pro with app data
retained. The installed version was read back and passthrough launched
successfully. The wearer has been asked to repeat a 20-second hand movement and
hand ☰ test. Awake post-install measurements and wearer confirmation are pending.

The initial post-install activity check and bounded 55-second capture had no
awake scene frames: the activity was paused/stopped and both ISDK and the new
input system had zero executions. No crash was recorded, but this is not proof
of either input-stage execution or improved hand smoothness. A wearer test is
still required before making a before/after performance claim.

Follow-up: the wearer answered "Hands still lag." The subsequent device checks
still showed 1.2.14 paused/stopped, zero scene/input-system executions and zero
predicted display period. Power service reported `Asleep`. USB and Wi-Fi were
confirmed to address the same Quest Pro serial. The wearer has been asked to
keep the connected headset worn with AlterEgo passthrough visible and compare
hands in Quest Home. The reported symptom remains unresolved; this follow-up
has not changed the installed APK or established a new performance cause.

The packaged manifest also contains hand-version metadata at manifest root,
outside the application metadata scope. This configuration finding does not
establish the cause of lag. A HIGH/Fast Motion override was not added: Meta's
current [FMM compatibility guidance](https://developers.meta.com/vr/documentation/unity/fast-motion-mode/)
states that Quest Pro cannot combine FMM with eye/face tracking, lip sync or
foveated rendering, all relevant to the app's existing tracking features.

APK: `app/build/outputs/apk/release/app-release.apk` (141,396,206 bytes).
SHA-256: `931612486E0F7593F369125D8730991AEAB5A229DE27349EBC140D087B5F53AB`.

Useful baseline evidence:
`scratch/menu-diagnosis/lag-app-before.txt`, `lag-app-before-end.txt`,
`lag-awake-before.txt`, `lag-awake-before-end.txt`, `lag-threads-before.txt`.
