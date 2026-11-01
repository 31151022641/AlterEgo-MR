# AlterEgo MR Avatar Webcam 1.5.0

Windows 11 x64 companion for Quest **1.2.0 (26)**; the existing 1.1.1+ encrypted-video protocol remains compatible. Quest stays standalone;
this optional LAN receiver exposes its rendered avatar as a Windows virtual camera.
No OBS, PC VR connection, cloud relay or downloaded camera driver is required.

## Windows layout

Version 1.5.0 places the large live preview on the left and clear connection,
background and camera setup cards on the right. The receiver status at the top
shows Receiver off, Starting, Waiting for Quest or Video live. Source resolution
appears above the preview. These describe this receiver; select the virtual
camera in your call app separately.

**Start receiver** is the main action. **Show manual pairing** expands the PC
address/key controls only when needed. **Change background…** shows your current
background and opens the matching colour/picture and edge-adjustment window.
**Check Windows camera** remains visible; **More camera options** holds installation,
repair and Reset pairing. The code-comparison window uses the same theme and
preserves the existing Codes match / Cancel decisions.

The window resizes down to 920 × 640; the control sidebar scrolls vertically
when space is limited. The layout check covers ready/waiting/live states, expanded
controls, compact sizing and simulated 150% layout plus text scaling. It uses
generated pictures and documentation-only network addresses, never wearer video.

## Install and pair

The shareable **AlterEgoMR-Webcam-Install-1.5.0-Windows11-x64.zip** contains the
complete setup EXE, a short `INSTALL.txt` guide and `SHA256SUMS.txt` checksums.
Extract the ZIP, then double-click its setup EXE. The setup EXE also works alone.

1. Run **AlterEgoMR-Webcam-Setup-1.5.0.exe** and accept Windows administrator consent.
   Close the old receiver and camera/call apps first. Setup contains the complete
   offline payload. An extracted ZIP can also use **Install webcam**.
2. Open **AlterEgo MR Webcam** from Start, select the network shared with Quest,
   and choose **Start receiver**.
3. On Quest, open **My mirror → Menu → PC Link → Find PC** and select your PC.
4. Compare the exact `XXXX-XXXX-XXXX` code on both devices. Choose **Codes match**
   on both only if they agree. Authenticated pairing is remembered.
5. Select **AlterEgo MR Avatar (Windows Virtual Camera)** in your call app.
   Use your normal PC microphone. Keep Quest's mirror and the receiver active.

**Manual pairing** remains available: enter the private IPv4 address (port 38991
is optional) and the full six-digit-PIN/certificate-fingerprint key. A PIN alone
does not authenticate a PC. **Reset pairing** rotates its identity; **Forget pairing**
on Quest removes trust. Neither action is needed for an ordinary upgrade.

## Status and camera check

Quest reports **Live avatar video** only after the PC acknowledges publishing a
fresh image. Paired/waiting, camera paused, delayed-image rejection and reconnecting
are separate states. A receipt confirms the receiver, not the call app's selection.

**Check camera** opens only this virtual camera through Windows MediaCapture,
reads at least eight 1920 × 1080 samples with advancing timestamps and saves no pictures. Start the receiver first. A passed
component check may show a placeholder if Quest is asleep.

The source supports **1920 × 1080, 1280 × 720 and 960 × 540**, each in **RGB32,
NV12 and YUY2**, at 30 sample frames/sec. The receiver preserves the source resolution; the camera resizes once to the format chosen by the calling app. Repeated
samples reuse a resized picture to reduce CPU work. HD YUV formats use BT.709.

Quest's **Quality → Maximum detail** is the default for this upgrade. It chooses
the highest supported useful camera crop (up to 1920 × 1080), preserves all luma
pixels and uses JPEG quality 97. The connected Quest Pro advertises a 1600 × 1200
avatar camera; its unzoomed 16:9 crop is 1600 × 900. A 1080p webcam picture from
that feed is enlarged, not genuine extra camera detail. The SDK sample renderer
creates its own 1920 × 1080 images with up to 4× multisample antialiasing.

Maximum detail reduces cadence under processing/network/thermal load instead of
lowering JPEG quality. Automatic and Smooth motion cap the useful crop at 720p;
Save battery caps it at 540p. The tab shows requested camera size, output size and
measured processing rate. New-image rate depends on the headset, tracking and LAN;
30 new images/sec is not guaranteed. Digital zoom crops away source detail.

## Webcam background

Choose **Change background…** in the Windows receiver. Available choices are **Original**,
**Dark studio**, **Light studio**, **Custom colour**, **My picture** and **Green screen**.
Use **Choose colour…** or **Choose PNG/JPG…** for your own background. Images fill
and centre-crop to the video frame; JPEG orientation is applied. The chosen image
is copied to this PC, so its original file is no longer required. Your choice and
edge strength survive restart. Background effects do not require a Quest update.

The receiver removes the avatar's **plain colour backdrop** using a border-connected
colour matte. Enclosed details with the same colour, such as a shirt logo, remain
inside the foreground. A covered corner is repaired from the agreeing corners.
Lower **Edge strength** if pale clothing or a hand loses detail. This is not AI
person segmentation: detailed scenes and ambiguous matching clothing are not
promised. If the corners do not support a plain backdrop, the receiver keeps the
original image and explains why in its status area.

The effect runs locally before both preview and virtual-camera publication. No
cloud upload, external service or extra headset inference is used. The protocol,
source timestamp, pause/recovery and stale-frame clearing remain unchanged.
The camera's RGB/YUV formats are opaque; “remove background” means replacing the
old backdrop with your selected background. Green screen is provided for tools
that apply their own key. PNG transparency is flattened against a studio colour.
The Quest's own mirror and standalone operation are unchanged.

PNG/JPG inputs are bounded to 16 MiB, 8192 pixels per side and 32 million pixels,
with dimensions checked before decoding. A private normalized 1920 × 1080 PNG
and small settings file are stored in `%LOCALAPPDATA%\AlterEgoMR`. The original
background choice is the default; saved images/settings may remain after uninstall
with the app's existing local pairing state.

## Pairing and privacy

UDP discovery on 38992 advertises the name, endpoint and certificate fingerprint,
never a PIN or authorization to share. Each easy-pairing attempt uses fresh
32-byte nonces from both ends. Its 48-bit comparison code binds both nonces and
the TLS certificate. Both independent approvals are required before releasing
the PIN. Pairing has deadlines and request limits. Discovered names are untrusted hints.

**AEMR0005** uses certificate-pinned TLS 1.2, sequence/capture-time validation,
bounded 16:9 images up to 1920 × 1080, and accepted/paused/delayed receipts. Image
dimensions are checked before decoding. This receiver also accepts encrypted
AEMR0003/AEMR0004 senders at their original fixed 960 × 540 dimensions;
plaintext/AEMR0001/AEMR0002 are rejected. Update both sides for high-detail video.

Brief gaps preserve a recent frame. No-image controls or actual expiry clear
pixels while keeping foreground pairing ready to resume. Closing the mirror,
stopping sharing, leaving its mode or disconnecting clears personal video.
Headset-off/background streaming is not promised. The system avatar feed can
include a background. The optional Windows background effect changes the receiver preview and webcam; the Quest mirror keeps its original picture.
Preset expressions/hand overlap are video estimates rather than raw sensors.

Frames use `ProgramData\AlterEgoMR\<Windows-user-SID>\frames.dat`. That user can
update the channel; LocalService/FrameServer can read it; other ordinary Windows
users cannot access it. Administrators/SYSTEM retain administrative access.
A random channel token prevents activation using another user's path. Transport
keys are DPAPI protected. Diagnostics contain bounded timing/state/error metadata,
not video, pairing keys, certificates or peer addresses.

## Updates, signing and removal

Setup installs the complete app in Program Files, registers its COM source and
Start shortcut, and adds TCP 38991/UDP 38992 rules limited to **Private/LocalSubnet**.
It captures the intended user's SID before elevation. Updates verify/stage files,
unload FrameServer's old component, attempt rollback on failure and restart that
service if previously running. Locked retired backups cannot roll back a committed
update. Close other camera/call apps first; upgrading briefly interrupts camera users.

This is **unsigned development software** unless built with a trusted publisher
certificate. A transport certificate is not a publisher certificate. Bundle hashes
detect corruption, not replacement of an entire bundle. The build supports trusted
signing of the receiver, DLL, probe, script and single-file setup without inventing
a publisher identity.

Remove **AlterEgo MR Webcam** through Windows Installed Apps. Removal needs no
original bundle and removes this product's files, registration, shortcut, frame
channels and firewall rules. Pairing identity may be retained for reinstall;
Reset pairing revokes it. Windows 10/ARM64 and audio forwarding are unsupported.

## Build and checks

Run `windows/build.ps1` with Visual Studio C++ tools and a Windows 11 SDK.
The build also creates the installation pack ZIP. Run `windows/package_installer.ps1`
to repackage an existing build; it verifies every embedded installer payload
against the current bundle before creating the pack.
Optional `-CodeSigningThumbprint` and `-TimestampUrl` use an existing trusted
publisher certificate. `pc_avatar_webcam.exe` contains the same receiver bytes.

Run `verify_native.py`, `verify.py`, `verify_upgrade.py`, `verify_quality.py`, `verify_background.py`, `verify_packaging.ps1`
and `verify_installer_upgrade.ps1` for source formats/lifecycle, encrypted protocol,
stability, dual approval/receipts, integrity and rollback checks.
`Setup --validate-only` checks extraction without installing. These tests do not
establish live Quest-to-Teams/Zoom/browser performance; that requires physical QA.
See [VIDEO_QUALITY_REPORT.md](../VIDEO_QUALITY_REPORT.md) for quality changes and evidence.

References: [Microsoft virtual camera](https://learn.microsoft.com/en-us/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera),
[official source sample](https://github.com/microsoft/Windows-Camera/tree/master/Samples/VirtualCamera),
[Meta avatar camera](https://developers.meta.com/vr/documentation/android-apps/features-overview/).


