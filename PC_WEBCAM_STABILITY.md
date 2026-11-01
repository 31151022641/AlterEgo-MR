# Avatar webcam stability repair

6 October 2026. Matching releases: **Quest 1.0.11 (12)** and **Windows 1.1.1**.
This follows the initial repair and live-image verification in
[PC_WEBCAM_REPAIRS.md](PC_WEBCAM_REPAIRS.md). The reported fault was flashing
avatar video while Quest still displayed Connected, with occasional reconnects.

## Causes and repairs

| Cause established in code or regression | Repair |
| --- | --- |
| PC cleared both output and preview for every rejected late/future frame. | Drop the packet while preserving the most recent image until its actual two-second expiry. |
| A single initial clock calibration treated subsequent faster delivery as future input. | Track the smallest observed arrival/capture offset, allowing latency improvement and bounded drift. Keep calibration across image pauses so queued old pictures cannot become fresh again. Recheck freshness after decoding and before publication. |
| Quest's 750 ms no-image watchdog stopped the sender and discarded pairing. | Use a two-second image grace. Longer image gaps clear pixels while authenticated no-image controls maintain pairing every 500 ms. Windows allows five seconds between records. |
| Canceled socket/handshake deadline callbacks could run after successful completion. | Guard timeout ownership atomically, so a queued callback cannot close a completed operation. Blocked operations still have a deadline. |
| Quest LAN delivery waited behind UI preview work. | Deliver independently of UI rendering and retain at most one pending preview bitmap. Foreground camera recovery uses bounded retries. |
| Native camera could revive cached pixels after observing an explicit clear during the next unfinished write. | Invalidate the cache on clear, invalid header/token and placeholder output. Normal publication contention preserves a still-fresh image. |
| Upgrade cleanup of a DLL still loaded by Windows rolled back an otherwise successful installation. | Retire old backup files after the installation transaction. Locked retired files produce a warning and cannot undo the installed update. |

Closing the mirror, leaving its mode, stopping sharing or closing the receiver
still clears the webcam. A pause lasting two seconds legitimately shows a blank
placeholder; it does not pretend to have fresh live video. New video resumes on
the same authenticated connection. Protocol **AEMR0003** distinguishes no-image
records from JPEG records; update both applications together.

## Verification

- Quest release build: **116 tests passed**, zero failures/errors. Signing
  certificate matches the existing installed release. Quest 1.0.11 (12) installed
  successfully; existing camera permissions remained granted.
- Windows production native DLL and receiver compiled successfully. Offline
  manifests describe version 1.1.1 / AEMR0003-TLS1.2; the legacy filename contains
  the same receiver. Installer validation passed without changing Windows.
- Final encrypted receiver suite passed: TLS/PIN/certificate and old-protocol
  rejection, malformed bounds/JPEG/clocks, exclusive ownership, late/future
  packet handling, expiry, stale queued-image rejection, keepalive and throttling.
  A 60-second same-connection test confirmed **574 published images**, with
  alternating latency, bounded drift, repeated 500 ms gaps and late bursts. A
  subsequent six-second image pause cleared once and resumed fresh video on the
  same connection.
- Eleven focused capture-clock/slow-decode assertions passed. Native cache
  regressions passed, including 120/120 busy reads retaining fresh pixels and
  explicit clear/invalid token preventing old-pixel resurrection.
- Locked-DLL installer regression passed: simulated cleanup failure cannot
  trigger rollback, cleanup runs after commit, and unrelated paths are rejected.
- Windows 1.1.1 installed successfully. All installed payload hashes match the
  final offline bundle. The repair restarted Windows FrameServer to unload its
  previous component; the existing pairing key remained unchanged.
- Actual registered Windows camera test passed, HRESULT **0x00000000**, through
  the camera service. The running GUI's session also delivered eight RGB32 and
  eight converted NV12 samples at 960 × 540 with QPC-aligned, monotonic timestamps.
  Quest was asleep, so this latter test consumed the cleared placeholder, not
  animated wearer images.
- Sustained live wearer verification remains pending: the headset reported
  **Asleep**, and no Quest connection was active at the final check. The receiver
  is listening on the selected Wi-Fi interface and ready for Connect webcam.
  The prior initial live-avatar test is historical evidence, not a claim that
  this stability repair passed sustained headset operation.

## Deployment and remaining verification

Offline Windows package:
`windows/dist/AlterEgoMR-Webcam-1.1.1-Windows11-x64.zip`.
Existing pairing identity is preserved during an ordinary update.

The Windows bundle remains unsigned because no trusted publisher signing
certificate was supplied. Transport still uses pinned TLS. Named Teams, Zoom and
browser video calls and extended physical headset sessions require separate
verification. Diagnostics record bounded timing/state/error metadata; no video,
pairing key, certificate or peer address is saved by the new diagnostic logger.
