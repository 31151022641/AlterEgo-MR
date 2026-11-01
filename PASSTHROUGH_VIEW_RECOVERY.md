# Menu and mirror reset recovery

## Window recovery — 1.2.17 (43)

The wearer reports that passthrough windows remain difficult to find and Reset
view does not bring the mirror into view. Inspection found several concrete
recovery problems:

- Reset always reopened QuickLauncher. In LIVE_MIRROR that covered the actual
  mirror with an opaque menu. Reset now closes that overlay to reveal the mirror;
  an explicit Menu request still opens the menu. Passthrough Reset opens Home.
- The previous layout discarded head pitch, placing its primary panel at level
  eye height even when the wearer looked down at Reset. The window now follows
  the current sightline, removes roll and limits only the near-vertical pitch
  singularity to 80 degrees. It is centered at 1.10 metres, with default scale
  restored. Both seated and reclined orientations are covered by regressions.
- The closer dock overlapped the primary window's lower edge in view. The new
  dock has an angular gap below the window. Shared physical dimensions drive
  panel registration and the corner-projection checks.
- Two stable VISIBLE frames could finish recovery before a later system
  reference/focus transition. A system Reset now also allows one fresh placement
  when input focus returns, expiring after 30 seconds. The original early
  VISIBLE placement remains available. No tracking/focus data is fabricated.
- Returning to the app or wearing the headset again requests fresh placement.
  The primary surface has a clearer opaque frame. Removing its Grabbable
  component keeps button pinches from dragging the window; Reset controls its
  placement. Native panel pointer/touch handling remains active. Meta documents
  these as separate [panel interactions](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-isdk-panels/).
- The controller mirror shortcut previously left `liveMirrorPanel=false`, which
  could open a regular OS window and finish the immersive task. It now opens
  the recenterable live panel; explicit Exit passthrough still returns to the
  regular My mirror window. The recovery dock is also available in live-panel
  mode.

The actual current live mirror is `MirrorScreen` on `MENU_PANEL_ID`. The legacy
`MagicMirrorView` side entity stays hidden; enabling that old surface would
show the wrong content. Recovery targets the active central window.

Validation: signed release build and **406 JVM tests**, zero failures/errors/
skips. Coverage includes seated/reclined sightlines, vertical-gaze stability,
front-facing surfaces, actual panel/dock corner separation, fresh placement
after a late reference/focus transition, bounded focus refinement, and mirror
content recovery across every launcher page/open state. APK Signature Scheme v2
and both authored portraits/credits packaging checks pass. Build log:
`scratch/quest-v17-final-build.log`.

Installed on USB Quest Pro `YOUR_QUEST_SERIAL`, preserving data. Package manager
confirms **1.2.17 (43)**. The live immersive mirror was launched successfully
with `live_mirror_panel=true`, ready for the wearer test. Device checks still
show asleep/unmounted, activity paused/stopped and zero input-system executions.
This verifies installation/startup, not awake rendering. Evidence:
`scratch/menu-diagnosis/v17-recovery-installed-activity.txt`.

The wearer was asked to wear the headset, look where the window should appear,
use the Quest's Reset view, close Quick Settings and report whether the mirror
is centered. Wearer confirmation remains necessary for actual window visibility
and hand selection; automated geometry checks alone do not prove the experience.

APK: `app/build/outputs/apk/release/app-release.apk` (141,412,621 bytes).
SHA-256: `26543BD9A98E6CCF71AFFEBA6B14657812B638E000340DED84FC7ECAF31117D9`.

## Previous repair — 1.2.12 (38)

## Awake-headset finding and correction

On 2026-10-23, the wearer clarified that the failing control is used with hand
tracking. They confirmed that nothing appeared after system Reset View in
1.2.11. The device was actually rendering, and the app received:

```
08:30:04.674 System panel reset requested; userInitiated=true
08:30:04.678 Recover requested source=system-reset menu=true placed=true focused=true
08:30:04.814 Flat panels reset in front of current viewer
```

This rules out a missing Reset View callback for this reproduction. It does not
prove that a hand Menu click reached the app; no such click was logged.

The panel layout used a +Z-facing normal, rotating the UI 180 degrees away from
the wearer. Meta's official
[UiPanelUpdateSystem](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/blob/main/MixedRealitySample/app/src/main/java/com/meta/spatial/samples/mixedrealitysample/UiPanelUpdateSystem.kt)
places a panel along the head's +Z direction and gives it the same heading, so
its local -Z display surface faces the wearer. The official
[MenuPlacementSystem](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/blob/main/MrukSample/app/src/main/java/com/meta/spatial/samples/mruksample/qr_code_scanner/MenuPlacementSystem.kt)
likewise uses panel position minus viewer position for its look rotation.

1.2.12 corrects menu, dock and side-mirror orientation. The dock's visible face
tilts upward. Regression coverage checks each actual -Z panel face toward the
wearer at four room headings, the dock's upward tilt and screen-right direction.
Recovery logs now include presentation mode to distinguish the immersive UI
from a separate mirror window.

Validation: the signed 1.2.12 release builds successfully; **379 JVM tests**, zero
failures/errors/skips. APK Signature Scheme v2 and compressed portrait/credit
packaging checks pass. Version 1.2.12 (38) was installed on the connected Quest
Pro with app data retained; the package version was read back and passthrough
launched successfully. On 2026-10-24, the wearer confirmed that **Reset View now
shows the menu**, but the hand Menu button still fails. The orientation/reset
repair is wearer-confirmed; the remaining hand-dock work is recorded in
[PASSTHROUGH_MENU_FIX.md](PASSTHROUGH_MENU_FIX.md).

APK: `app/build/outputs/apk/release/app-release.apk` (141,379,826 bytes).
SHA-256: `201CB9BA056E5E59FAB368E4FFE1A3A547910B9391003065A9709AB9C89AAD0B`.

## 1.2.11 implementation and historical validation

The wearer reported that passthrough still did not bring up the menu and made
mirror reset difficult after 1.2.10. This update treats those as a combined
recovery action rather than moving only one panel.

- Menu and the app's Reset view request placement of the menu, mirror and dock
  together using the current viewer heading. They reveal the launcher home.
- User-initiated system Reset View now also reopens the menu. Automatic reference
  resets preserve the menu's open/closed state.
- Reset view is available next to Menu in the dock, in the launcher footer and
  in the live mirror header. The ordinary OS camera mirror is unchanged.
- Direct app Menu/reset requests do not wait for the OS reference-change delay.
  System recenter still waits 120 ms before sampling fresh poses.
- Placement requires two fresh, valid visible-session poses. It prefers a stable
  pair, but uses the latest valid pose after 600 ms of continued movement rather
  than waiting indefinitely. Invalid/missing tracking never produces an arbitrary
  placement. Focus loss, pause and unmount invalidate old samples while retaining
  a pending recovery request.
- Repositioning can occur while XR is VISIBLE; it no longer requires controller
  FOCUSED state to return after the system reset overlay.
- Menu reads the local player's left-hand changed-button press. A trusted fresh
  SDK edge can rearm after focus loss without requiring an extra release/press.
  Stale held-state polling, repeats and duplicate Android delivery remain guarded.

The input path was checked against Meta's official
[MRUK input sample](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/blob/main/MrukSample/app/src/main/java/com/meta/spatial/samples/mruksample/common/MrukInputSystem.kt).
The existing LOCAL_FLOOR setup was checked against the official
[starter activity](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/blob/main/StarterSample/app/src/main/java/com/meta/spatial/samples/startersample/StarterSampleActivity.kt)
and the app's cached SDK 0.14.0 API. No tracking validity or input focus is fabricated.

Validation: signed release build succeeds; **378 JVM tests**, zero failures,
errors or skipped tests. The additional tests cover direct recovery timing,
continued head movement, invalid tracking, pause/resume freshness and fresh Menu
edges after focus loss, including Android/SDK deduplication. APK Signature Scheme
v2 and the packaged portrait/license checks pass.

The final 1.2.11 APK was installed on the connected Quest Pro with app data
retained; installed version 1.2.11 (37) was confirmed and passthrough was opened.
The initial installation pass had no awake-headset events. The subsequent
wearer test and its unsuccessful visual result are recorded above; the tests
did not catch the incorrect panel-front convention.

APK: `app/build/outputs/apk/release/app-release.apk`.
SHA-256: `0429B3E314A2F5FBC5D33A2991DDE01BD454D3A2F55D39DAB0221E2F558325BE`.
