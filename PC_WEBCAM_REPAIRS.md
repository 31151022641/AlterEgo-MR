# AlterEgo MR webcam repair release

Repair date: **6 October 2026**. Quest **1.0.10 (11)**; Windows **1.1.0**.
The original findings remain in [PC_WEBCAM_REVIEW.md](PC_WEBCAM_REVIEW.md).
The later flashing/connection repair is documented in
[PC_WEBCAM_STABILITY.md](PC_WEBCAM_STABILITY.md).

## Implemented repairs

| Original high-priority findings | Repair |
| --- | --- |
| 1–4: incompatible companions, automatic fixed-address telemetry, wrong wearer source, face-only legacy renderer | One supported native companion receives the rendered Quest mirror, including its avatar, body and hands. The compatibility EXE is byte-identical. The UDP prototype is archived and its active headset calls removed. |
| 7: EXE did not install a webcam | Complete offline bundle installs the receiver and its own Windows 11 camera source, with a Start menu shortcut and stable firewall path. No OBS or UnityCapture installation is needed. |
| 8–10: malformed/unauthenticated input and stale output | The supported route uses TLS, full certificate pinning, PIN validation and bounded JPEG/sequence/clock checks. Only the authenticated owner can publish. Disconnects clear both camera and preview. The faulty legacy UDP parser is outside the supported route. |
| 17: unverified privileged DLL download | Removed the downloader. Setup uses the bundled source, checks payload hashes, and stages updates with rollback. Publisher signing requires a real configured signing certificate. |
| 18–19: invalid sample clock and lost activation metadata | Native samples/events use QPC-correlated Media Foundation time. All activation attributes reach the source; required service/control interfaces and stream lifecycle contracts are implemented. |
| 20: globally writable video | Per-user SID frame directories have restricted ACLs. A fresh random channel token binds camera activation to the receiving user's frames. Another ordinary Windows user cannot read or replace them. |
| 21: plaintext PIN/video and unauthenticated PC identity | TLS 1.2 with full SHA-256 certificate fingerprint; verified before sending the PIN or video. PC identity/PIN persist through per-user Windows DPAPI. Pairing reset revokes the old identity. Attempts are throttled and concurrent handshakes are bounded. |
| 30: missing end-to-end validation | Actual Windows service activation and actual Quest-device TLS authentication now pass. Live wearer video and named call-app verification are recorded below; synthetic tests do not establish those results. |

Additional repairs include reconnects after temporary network loss or a busy PC,
sequence-zero compatibility, capture-time freshness, image orientation, preview
clearing, user-specific single-instance protection, named network adapters,
complete removal, and current documentation. Quest accepts the PC's bare IPv4
address with default port 38991, as well as an explicit address and port. Its
mirror controls scroll in short windows, explain missing live input and display
connection feedback alongside the pairing controls.

## Verification and deployment

| Check | Result |
| --- | --- |
| Signed Quest release and unit tests | Final 1.0.10 (11) built; 108 tests passed with zero failures/errors. |
| Native contract and orientation checks | Passed: QPC timing, activation attributes, service/control interfaces, lifecycle, top-down RGB32 rows, private path filtering and token authentication. |
| Secure Windows loopback suite | Passed: TLS/full pin, wrong PIN/protocol, initial sequence zero, exact 3 MiB bounds, malformed JPEG/dimensions/sequence/time, active owner protection, stale/future drops, 45 confirmed publications, disconnect clearing and per-IP throttling. |
| Production Android trust/protocol classes running in Java against the actual receiver | Passed: TLS 1.2, certificate pin, authentication and five 960 × 540 JPEG frames; receiver reported RECEIVED 5. |
| Offline bundle and installer plan | Passed: payload hashes, identical compatibility EXE, private ACL contract and validation without mutation. |
| Actual Windows installation | Passed with the user's administrator approval. Complete product installed in Program Files; the approved Wi-Fi profile was marked Private. The installed firewall rule targets the installed EXE and local subnet. Actual private directory ACLs were checked. |
| Actual Windows FrameServer camera test | Passed, HRESULT 0x00000000. The registered service camera enumerated, activated and delivered the synthetic blue fixture through SourceReader. |
| Actual Quest-device network/TLS probe | Passed: CONNECT_OK, TLS_OK TLSv1.2, PAIRING_OK_NO_FRAMES using production classes from the installed APK. No wearer video sent by that probe. |
| Existing session camera format observer | Passed: eight RGB32 and eight converted NV12 samples at 960 × 540 through the running service camera. All timestamps were QPC-aligned and monotonic. Input was the disconnected placeholder at this stage; this test establishes formats/timing, not wearer animation. |
| Live wearer video through the GUI and Windows camera | Passed after the address/UI correction. The user confirmed the avatar appears in the PC preview. A real local-network connection from Quest to PC carried changing avatar images through the Windows camera service: eight RGB32 and eight converted NV12 frames at 960 × 540, QPC-aligned and monotonic, all RGB pixels outside the disconnected placeholder. No video was saved. |
| Actual live-stream disconnect | The GUI reported the headset disconnected and its shared-channel live timestamp returned to zero. The tested loopback recovery/clearing cases additionally cover malformed, interrupted and delayed input. |
| Teams, Zoom and browser calls; long sessions/sleep/resume | Not yet verified. |

## Practical limits

- Windows 11 x64, build 22000 or newer. Windows 10 and ARM64 are not supported.
- One 960 × 540 RGB32 source mode at 30 sample FPS; new Quest mirror pictures are
  produced at up to 15 FPS. Windows SourceReader conversion to NV12 passed with
  real avatar frames; named call-client compatibility still needs client testing.
- Video only; use a PC microphone. Keep the receiver and headset mirror active.
  Closing the receiver removes its session camera.
- Horizon OS chooses its avatar camera's background. Existing experimental face
  overlays retain their inference and occlusion limitations.
- The release is unsigned because no trusted publisher signing certificate was
  supplied. The pinned TLS identity protects transport; it is not publisher signing.
- Administrators and SYSTEM retain administrative access to the private frame
  directories. Installer checksums detect corruption, not a maliciously replaced
  entire bundle.

The ready-to-install offline bundle is
`windows/dist/AlterEgoMR-Webcam-1.1.0-Windows11-x64.zip`. Normal users can continue
using the installed **AlterEgo MR Webcam** shortcut. Select **AlterEgo MR Avatar
Windows Virtual Camera** in the video-call app once the receiver is running.
