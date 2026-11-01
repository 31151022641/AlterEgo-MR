# AlterEgo MR layout decision

Updated October 13, 2026. The user selected the **second concept: Quick launcher**.
It is implemented in signed Quest **1.2.0 (26)** and installed on Quest Pro.
The original Floating menu concept remains an alternative discussed during
design; it is not the selected layout.

The mirror or passthrough scene remains the main view. A visible Menu button
opens an opaque menu with four large choices:

| Destination | Implemented content |
| --- | --- |
| Faces | Eight built-in previews, the saved photo library and Add face. |
| Avatar | My Horizon avatar, Meta sample, framing and explicit regular/live mirror choices. |
| PC Link | PC discovery, manual address/code entry, code confirmation, Start webcam, Stop sharing and Forget pairing in the regular mirror. Passthrough offers an explicit link to My mirror. |
| Add face | Choose PNG/JPG, preview its actual centre square, name it and save it to the local library. |

Settings holds picture quality, sample response/detail, face fit and optional
tracking details. Passthrough settings retain multi-person mode, different faces,
voice announcements, size, opacity, hair and targeting controls.

Opening starts at the launcher. Back from a destination returns to the launcher;
Back at the launcher closes it. Closing preserves the avatar/face selection and
pairing. Mirror and passthrough keep separate saved face choices. Opening and
closing controls does not stop tracking or the preview.

In a focused immersive session, the short left ≡ press toggles the menu. Held
presses, release edges, duplicate SDK/Android events and focus changes cannot
repeatedly toggle it. Opening the passthrough menu places only that panel ahead
of the current viewer; the live mirror menu is an overlay on its existing panel.
The regular OS window has a visible Menu button and an Android menu handler,
whose physical shortcut delivery still requires a wearer check.

The right Meta button retains its system function. Controller ray/touch uses
the SDK's pointer route. App gaze/controller focus is highlighted; clipped,
empty and invalid targets are removed from app selection. Eye tracking remains
optional. Physical input and placement verification is pending.

Meta's controller guidance supports the left menu-button convention:
[VRC.Quest.Input.1](https://developers.meta.com/vr/resources/vrc-quest-input-1/)
and [Controller design](https://developers.meta.com/vr/design/controllers/).
Regular-window pointer/Back behavior is described in
[AOSP features on Horizon OS](https://developers.meta.com/vr/documentation/android-apps/features-overview/).

The former file importer only kept URI metadata and mapped it to a built-in
persona/tint; its failure path produced a placeholder. The reachable Quick
launcher path now validates and renders actual imported image pixels. A photo
is explicitly a **static head-following mask**. It cannot blink or speak, and
does not create an animated 3D rig. Arbitrary GLB/glTF and scanned-mesh import
remain future work; the UI does not advertise them as supported.

The native deployment passed 312 JVM tests, seven synthetic photo checks and
six visually inspected native menu captures. Browser concept checks at 736,
560, 360 and 320 pixels are historical design checks, not proof of native Quest
input or tracking behavior. The 1.1.12 live sample timing comparison remains
pending. Detailed evidence and test limits are in
[LAYOUT_DEPLOYMENT.md](LAYOUT_DEPLOYMENT.md).
