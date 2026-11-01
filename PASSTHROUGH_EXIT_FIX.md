# Passthrough exit verification

## Exit label — 1.2.16 (42)

Renamed both user-facing **Back to mirror** buttons to **Exit passthrough** in
the launcher and lower control dock. Both retain the existing mirror handoff.
Earlier sections below describe the labels at the time of their checks.

Signed release build and all 393 existing JVM tests pass. APK contents contain
**Exit passthrough** and no remaining **Back to mirror** label. Portrait/credit
packaging and signature verification pass. Installed on the USB Quest Pro with
app data retained; package manager confirms 1.2.16 (42), and passthrough launch
returns `Status: ok`. This wording update does not constitute a new live exit test.

## Debug check — 2026-10-25, installed 1.2.15 (41)

The connected USB Quest Pro package manager reports 1.2.15 (41). Both the
launcher and lower dock's **Back to mirror** actions call `openMirrorHome()`.
Android Back/controller B first return a visible submenu to the launcher home;
Back from home or a closed launcher calls the same explicit exit. The explicit
handoff still bypasses camera/permission/XR-readiness gates, starts the separate
`MirrorActivity` task and removes the immersive task. No code change or APK
replacement was needed for this inspection.

Reran the targeted release unit checks: **40 tests, zero failures/errors/skips**
(28 navigation/input-deduplication, 8 SDK input, 4 mirror-handoff policy).
Build log: `scratch/menu-diagnosis/exit-v15-unit-check.log`.

Live exit remains unverified for this build. Two device checks showed
`mWakefulness=Asleep`, no proximity detection, activity paused/stopped and zero
SDK/input-system executions. There was no focused input window to receive a
Back test. No injected key or direct activity launch was substituted for a
successful app exit. The wearer was asked to wake/wear the Quest and keep
AlterEgo passthrough visible for the test. Initial activity evidence:
`scratch/menu-diagnosis/exit-v15-initial-activity.txt`.

Wearer exit options: select **Back to mirror**, or press controller **B**
(twice if currently inside a Faces/Avatar/etc. submenu). If using hand tracking
and the panels are out of view, use the system's **Reset view** to recover the
launcher, then select **Back to mirror**. Confirm that the ordinary **My mirror**
window opens and the immersive passthrough activity closes.

## Original repair — 1.2.9 (35)

The user could remain in passthrough after pressing Back. Previously, controller
B was handled only while the launcher was visible, and Back at the launcher's
home screen only closed that menu. The mirror handoff also waited for scene
readiness, XR visibility and camera permission processing, even after an explicit
request to leave. A failure or delay in those systems could prevent navigation.

The passthrough launcher now has a persistent **Back to mirror** action, and the
dock uses the same label. Controller B and Android Back go from a visible submenu
to the launcher home; Back from home or with the menu closed opens the regular
mirror window. Regular 2D mirror menu behavior is unchanged.

Explicit navigation bypasses automatic scene/camera/permission readiness gates.
The handoff cancels the mirror timer, stops outward tracking and wearer tracking,
stops webcam sharing, opens `MirrorActivity`, then finishes/removes the immersive
task. Repeated requests are ignored once handoff starts. If the mirror launch
fails, the app retains its task, restores the menu and shows a retry message.
Android's activity Back fallback is handled as well as SDK controller polling
and Android key delivery.

Validation:

- Signed release build succeeds; **368 JVM tests**, zero failures/errors/skips.
- Four navigation regressions cover closed-menu exit, launcher-root exit,
  submenu-first navigation and duplicate SDK/Android delivery.
- Four handoff-policy regressions cover explicit exit before initialization,
  automatic readiness, duplicate requests and closing-activity rejection.
- APK Signature Scheme v2 verifies; the packaged portrait/license checks pass.
- Updated the connected Quest Pro from 1.2.8 (34) to 1.2.9 (35) with `adb install
  -r`; existing application data is retained.
- Started the updated passthrough activity successfully. An injected Back could
  not be delivered because the headset display was inactive, the activity was
  stopped and the input dispatcher had no focused window. This is not a passing
  live navigation test.
- The wearer subsequently woke/wore the headset and confirmed that B/Back returns
  from passthrough to My mirror. The visible return button has not been separately
  confirmed on-device.

APK: `app/build/outputs/apk/release/app-release.apk` (141,363,443 bytes).
SHA-256: `9FD489B860B5E8BF0775B625B0C915E929BAB04E0B452BDA680494DF17148138`.
