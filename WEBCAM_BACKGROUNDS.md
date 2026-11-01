# Webcam backgrounds — Windows 1.4.0

October 14, 2026. The user confirmed that their Horizon avatar has a plain-colour
backdrop. The optional Windows receiver now removes that backdrop and composites
the avatar over a selected local background. The Quest app remains 1.2.0; no
headset update is needed for this feature.

## Controls

Open **AlterEgo MR Webcam → Background…**. Choose Original, Dark studio, Light
studio, Custom colour, My picture or Green screen. **Choose colour…** opens a
colour picker. **Choose PNG/JPG…** validates and saves a normalized private image
on this PC. The picture fills the video frame with a centre crop. Settings and
the image survive a receiver restart. Lower Edge strength if clothing or a hand
matching the original backdrop loses detail. Original disables processing.

The PC preview and webcam use the same composed frame. The effect applies to
the received Horizon avatar, presets and Meta sample alike; it does not retarget
tracking or alter the avatar mesh. The headset's own mirror retains its original
picture, and the Quest continues to work standalone.

## Processing and limits

The current project reads the system avatar camera as YUV, converts it to opaque
RGB and sends JPEG. Meta documents access to the avatar selfie camera through
the front Camera2/CameraX route:
[Avatar Selfie Camera](https://developers.meta.com/horizon/documentation/android-apps/camera/).
The current RGB/YUV Windows camera does not carry a usable transparency channel.
This feature replaces the original backdrop with an opaque colour or picture;
green screen is available for other video tools to key themselves.

The effect is a colour matte for **plain backdrops**, rather than AI person
segmentation. Robust small-corner medians estimate a simple backdrop; agreeing
corners repair an occluded corner. A flood fill marks matching pixels connected
to the image edges, preserving enclosed details even when they match the backdrop.
A softened colour transition blends the foreground into the replacement.
Ambiguous clothing/hair colours and fine boundary details can still need edge
adjustment. Detailed scenery and exact semantic separation are not guaranteed.
If no plain backdrop can be inferred, the original image is retained with a
visible explanation; an effect error also retains video instead of forcing a
transport disconnect.

Everything runs locally on the PC. The encrypted transport, source timestamps,
receipt checks, pause clearing and disconnect clearing are unchanged. Processing
is checked against the existing freshness deadline before publication. No old
frame is given a new capture timestamp. The background is recomposited on incoming
frames and cached at the current source size. No new network service or model
download is required.

Images are validated before decoder allocation: PNG/JPG only, at most 16 MiB,
8192 pixels per side and 32 million pixels. JPEG orientation is handled; PNG
transparency is flattened against a studio colour. The bounded private copy is
1920 × 1080. Preferences and `background.png` live in `%LOCALAPPDATA%\AlterEgoMR`;
the original file is no longer required. The initial mode is Original.

## Verification

- Sixteen synthetic checks passed: original-mode ownership, replacement colour,
  retained hair/face/hands/clothing, enclosed matching-colour detail, opaque
  output, gradients, corner occlusion, persisted preferences, picture placement
  and reload, invalid/oversized-file rejection, pure green, detailed-corner
  fallback, processing budget and concurrent settings changes.
- Ten warmed synthetic 1920 × 1080 composites averaged **19.1 ms** on this PC.
  This is local processing time, not measured end-to-end wearer latency or a
  performance guarantee for other PCs.
- A synthetic encrypted stream retained 960/1280/1600/1920 source widths and
  avatar detail, confirmed **588 1080p images over 60 seconds**, and passed
  pause/recovery and disconnect clearing with the effect enabled.
- The existing native media checks, high-detail reception, encrypted protocol,
  sequence/timestamp validation, active-owner protection, clock drift/jitter,
  no-image keepalives, pairing/discovery and receipt regressions passed. Their
  sustained regression confirmed **575 images over 60 seconds**.
- Both native Windows menu views were captured using isolated test state. No
  account avatar, headset video, user background image or installed frame channel
  was acquired for these checks.

Source and distribution: `windows/AvatarBackground.cs`,
`windows/AlterEgoWebcam.cs`, `windows/verify_background.py`, and the Windows
1.4.0 offline setup/ZIP in `windows/dist`. The compatibility EXE in
`pc_avatar_webcam/dist` remains byte-identical to the receiver.

The final bundle checksums, byte-identical compatibility EXE, private frame ACL
contract, guarded upgrade cleanup and single-file setup validation passed.
Setup finished. The installed receiver reports **1.4.0.0**, the Windows uninstall
record reports **1.4.0**, and all installed payload hashes match the verified
bundle. The background feature is installed in the normal Windows application.

Installer SHA-256:
`BA260ABC43C617FA6C2D9FA4E981DC374FF1CA4F6D513A43E493AD8AAC3B1FEC`

Real-avatar edge quality and real call-app output still require the wearer/PC
check; synthetic passes do not establish perfect live removal.
