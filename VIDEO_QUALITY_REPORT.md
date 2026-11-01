# AlterEgo MR video quality upgrade

Release: Quest **1.1.1 (14)** and Windows Webcam **1.3.0**, October 8, 2026.

## Changes

| Stage | Earlier limitation | Upgraded behavior |
| --- | --- | --- |
| Horizon avatar camera | Images wider than 960 pixels were decimated to half width and height before displaying/sharing | Every source luma pixel is converted in native code, respecting crop, individual plane strides, chroma layout and declared color space |
| Capture selection | A small stream was preferred | Maximum detail chooses the largest useful supported widescreen crop, capped at 1920 × 1080 |
| Compression | JPEG quality 82, dropping to 70 under load | Maximum detail keeps JPEG quality 97; other modes use 92 normally and 82 under load |
| Windows reception | Every image was converted/resized to fixed 960 × 540 | Native source size is retained without JPEG recompression |
| Virtual camera | 960 × 540 in three pixel formats | 1920 × 1080, 1280 × 720 and 960 × 540, each in RGB32, NV12 and YUY2 |
| Resizing | Source detail was already lost before delivery | One bilinear resize when a consumer requests a different format; unchanged frames reuse the resized result |
| SDK sample rendering | 540p offscreen target, lower mesh/material request, no multisampling | 1080p target, highest LOD request, Standard quality, up to 4× MSAA with a single-sample fallback |
| SDK material detail | Normal texture was unused | SDK tangents and Meta's Android G/A normal-texture encoding produce smooth detailed shading |
| Alternate procedural mirror | Software picture started at 540p | Vector geometry is drawn directly at the selected resolution, including 1080p in Maximum detail |

Maximum detail becomes the default once on upgrading. Later user choices remain
saved. Processing, thermal load and network delay reduce cadence instead of lowering
Maximum detail's resolution or JPEG setting. The pending-video queue remains bounded;
old pictures are discarded instead of building up latency. Digital zoom still crops
away detail. The regular OS Horizon camera remains the source for the user's personal
avatar; the offscreen SDK changes apply to the SDK sample/immersive path, not to
Horizon OS's internal materials.

The SDK sample's intermittent Error 7 was also repaired: mesh completion sometimes
preceded asynchronous rig-behavior registration. The app now waits briefly for that
registration, with a ten-second deadline, without querying a retired model-load ID.
Actual load failures still surface as errors.

## Resolution and motion limits

The connected Quest Pro advertises **1600 × 1200** for its avatar camera. At zoom 1,
the useful 16:9 crop is **1600 × 900**. The 1080p Windows output enlarges that camera
feed; it does not create missing source detail. The SDK sample instead renders at
1920 × 1080 directly. This upgrade is not 4K, lossless video, new photorealistic
avatar assets, or guaranteed 30 new images per second.

Windows emits timed samples at 30/sec; a recent image may repeat between genuine
headset updates. Actual motion rate depends on OS camera availability, tracking,
rendering, presets, heat and LAN performance. Call software may request 720p/540p or
apply additional compression. The Quality tab distinguishes requested camera size,
sent size and measured processed-image rate. The webcam sends video only.

## Compatibility and privacy

Quest's high-detail sender uses **AEMR0005** with certificate-pinned TLS and
publication receipts. Update both ends. Windows 1.3.0 also accepts older encrypted
AEMR0003/AEMR0004 senders at their original fixed 960 × 540. Saved pairing identities
are preserved. Source sizes must be even 16:9 images within 320 × 180–1920 × 1080;
the JPEG byte bound is 8 MiB. Dimensions are parsed and bounded before decoding.

Private frame storage is bounded to one 1080p RGBA image plus its header. Explicit
clear, disconnection and expiry invalidate cached/resized images. The receiver
continues to use its user-isolated frame channel, token authentication, bounded
diagnostics and Private/LocalSubnet network rules. No camera/wearer pictures are
added to logs by the quality upgrade. Tests use synthetic pixels and Meta's public
sample with synthetic tracking. Windows remains an unsigned development build.

## Verification

- Signed Quest release build and **127 unit tests**, zero failures/errors.
- On connected Quest Pro, native conversion preserved alternating single-pixel
  columns in a 1600 × 1200 synthetic image. Conversion took 12 ms in that run.
  Odd crop, padded/interleaved chroma, full-range 601/709 and short-buffer rejection
  checks passed. That timing measures synthetic conversion, not complete live FPS.
- Windows preserved synthetic source images at 1024 × 576, 1280 × 720,
  1600 × 900 and 1920 × 1080. Fine-stripe contrast, orientation, image receipts,
  invalid aspect/oversized-header rejection and disconnect clearing passed.
- Native source checks covered all nine advertised formats, sample sizes,
  orientation, YUV color matrices, repeated Start, timestamps, pause/stop,
  writer contention, private paths, tokens and cached-image clearing.
- Encrypted legacy regression: **60 seconds / 574 confirmed images**, including
  jitter, delays, brief gaps, pause keepalives, recovery and owner isolation.
- LAN discovery/dual-approval pairing and accepted/paused/delayed image receipts
  passed, with 120 confirmed images in the receipt recovery test.
- Offline payload checksums, compatibility-EXE equality, installer extraction/
  validation and locked-component cleanup/rollback checks passed.
- Installed Windows receiver, DLL, probe and installer-script hashes matched the
  final build. Its registered 1080p RGB camera returned the expected synthetic color.
- Installed Windows MediaCapture enumerated **nine formats** and delivered
  **eight 1920 × 1080 frames with seven advancing timestamps**, saving no pictures.
- Quest's final SDK sample loaded and produced inspected 1920 × 1080 public-sample
  images with corrected smooth shading. Synthetic face/eye/finger input stages
  succeeded; this is not a recording of physical wearer tracking.
- The user confirmed the upgraded regular Horizon avatar still appeared and moved,
  then confirmed its moving picture in the installed PC receiver. Live metadata
  confirmed 1600 × 1200 camera frames and a 1600 × 900 received source. One logged
  acquisition-to-receipt age was 224 ms; this excludes the OS camera's internal
  latency and is not complete call-app latency.
- Windows MediaCapture also reopened successfully after the live connection,
  again delivering eight 1080p samples. A subsequent 30-second frame-header check
  observed a cleared channel, so it does not establish sustained live delivery.
  The receiver's displayed live status alone is not proof of fresh camera pixels.

Final long-duration live high-detail webcam performance and Teams/Zoom/browser
negotiation remain physical end-to-end checks. Quest 3/3S were unavailable for
this device test. Automatic checks do not establish every headset/OS combination.

## Use the maximum-quality path

Open **My mirror → Quality → Maximum detail** on Quest, keep zoom near 1 for the
most retained detail, start **AlterEgo MR Webcam 1.3.0** on the same LAN, then connect
using saved pairing. Select **AlterEgo MR Avatar (Windows Virtual Camera)** and
1920 × 1080 in the calling app if it offers a resolution setting. Keep the headset
mirror and Windows receiver active. A stable local Wi-Fi connection or a wired PC
connection helps transport capacity; it cannot increase the camera's source detail.

Artifacts: `app/build/outputs/apk/release/app-release.apk`,
`windows/dist/AlterEgoMR-Webcam-Setup-1.3.0.exe`, and
`windows/dist/AlterEgoMR-Webcam-1.3.0-Windows11-x64.zip`.
