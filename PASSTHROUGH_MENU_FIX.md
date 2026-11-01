# Passthrough tracked-hand ☰ Menu — 1.2.13 (39)

**Superseded in 1.2.14:** the wearer subsequently reported lagging control hands.
The independent native hand tracker path described below was removed. Menu now
uses the SDK's existing hand/controller data during its input phase; see
[the performance report](PASSTHROUGH_HAND_PERFORMANCE.md). The reset/layout
and separate-dock repairs remain.

The wearer confirmed on 2026-10-24 that 1.2.12 Reset View shows the launcher.
They clarified that the failing Menu is the ☰ displayed on the tracked hand
itself, not the separate lower panel button. The actual hand-menu gesture now
has a direct input path.

- Request `XR_FB_hand_tracking_aim` with its hand-tracking/time dependencies.
  Read `XR_HAND_TRACKING_AIM_MENU_PRESSED_BIT_FB` from the runtime hand-aim
  state. This does not depend on an active pointing ray or an Android key.
- Filter out the dominant hand's Meta/system gesture. The runtime indicates
  which hand is dominant, so the app does not assume that the left hand is the
  non-dominant hand.
- The gesture reveals launcher home and recentres all flat panels. Each hand
  must supply a tracked, focused release before a fresh press; held gestures,
  missing aim data, occlusion and focus recovery do not replay a press.
- Merge duplicate SDK/Android delivery. A fresh runtime gesture can recover
  after an earlier Android key-up was lost.
- Sample only during focused, mounted passthrough with hand permission.
  Release native hand trackers/reference space on pause, shutdown or destroy.
  Only discrete availability/menu bits cross the native bridge; no hand joint
  or aiming poses are exposed by this input path.
- The separate dock no longer depends on a non-observable placement flag to
  build its Compose contents. It has larger buttons, an opaque background and
  native hover/press feedback. Its panel cannot be grabbed while selecting a
  button. SDK pointer and Compose press/release diagnostics observe events
  without consuming them or dispatching a second click.

Implementation follows Meta's
[palm-up non-dominant-hand menu guidance](https://developers.meta.com/vr/design/hands-ui-best-practices/)
and the Khronos
[hand-aim state](https://registry.khronos.org/OpenXR/specs/1.0/man/html/XrHandTrackingAimStateFB.html)
and [gesture flags](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrHandTrackingAimFlagBitsFB.html).

Validation: signed release build succeeds; **386 JVM tests**, zero
failures/errors/skips. Seven added tests cover discrete palm-menu edges,
either hand, held gestures, occlusion, focus recovery and duplicate/lost-key-up
delivery. All **11 native signal assertions passed on the Quest**, including
Menu with no valid aiming ray, ordinary pinch exclusion and dominant-hand
system gesture exclusion. Signature Scheme v2 and portrait/credit packaging
checks pass. Version 1.2.13 (39) was installed on the connected Quest Pro with
app data retained, the installed version was read back, and passthrough launched
successfully. The wearer has been asked to activate the ☰ attached to the
tracked hand, without pointing at the dock. Physical gesture confirmation is
pending; the automated assertions do not establish that the actual gesture
works on the headset.

The post-install device check showed the activity paused/stopped, its window
invisible and all scene-system execution counts still zero. No awake palm Menu
event was received in the bounded app-only capture. This is not a negative
gesture test; the wearer must wake the Quest to exercise the new native path.
All three native bridge exports were confirmed in the built shared library.

APK: `app/build/outputs/apk/release/app-release.apk` (141,396,212 bytes).
SHA-256: `7FDAD2B0DD14304A313AE068B134339D838039FEB67888B2B82125C842415FBC`.

## 1.2.10 implementation and historical validation

The user reported that Menu did not work with hand controls in passthrough.

Menu now explicitly opens the launcher home and requests placement ahead of the
viewer. An already-open/off-screen menu is no longer toggled closed by this
command. Repeated Menu requests remain open; Close and Back retain their normal
navigation. The menu stays visible at its last valid position while a fresh pose
is pending, and controller selection is allowed during repositioning.

Controller polling ignores inactive sources, so a disconnected controller cannot
keep the shared Menu state held down. A later fresh Android DOWN recovers from
a missing key-up, while repeat events and cross-source duplicate delivery remain
guarded. Android Menu input can open the launcher while the activity is resumed
even before XR focus is ready. `AlterEgoMenu` logs identify received button edges,
accepted open commands and completed placement without recording camera images.

Passthrough also requests hand-tracking permission independently of live-mirror
body/face tracking. The connected Quest already had hand permission, so this
permission omission was not established as the cause of this device's failure.
Meta Spatial SDK 0.14.0's locally inspected `VRFeature` already enables its
Interaction SDK by default; no additional interaction feature was added.

Verification:

- Signed release build succeeds; **371 JVM tests**, zero failures/errors/skips.
- Added regressions for repeated physical Menu opening, recovery after a lost
  Android key-up, and rejection of held-key repeat events.
- APK Signature Scheme v2 and packaged portrait/license checks pass.
- Installed 1.2.10 (36) on the connected Quest Pro with `adb install -r`, retaining
  application data. The installed version was read back and confirmed.
- Started the updated passthrough activity. Wearer confirmation of the Menu
  action is pending; synthetic input on a sleeping headset does not prove it.

APK: `app/build/outputs/apk/release/app-release.apk`.
SHA-256: `1BEBADA92C208EB2976A6DE591C708C438205B2800E1479471C57E22AA02AC96`.
