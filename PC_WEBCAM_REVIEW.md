# AlterEgo MR Windows webcam review

**Historical audit:** the subsequent repair release and current validation are
documented in [PC_WEBCAM_REPAIRS.md](PC_WEBCAM_REPAIRS.md). The findings below
describe the reviewed versions before that repair.

Reviewed **6 October 2026**, against the current project source and existing Windows binaries. This is a review, not a repair release. Production source, camera registration and firewall settings were not changed. Small diagnostics were added under `scratch/` and run locally.

## Main finding

The project has **two separate companions**. The current Quest **Connect webcam** control uses the native program in `windows/`. The executable in `pc_avatar_webcam/dist/` is an older Python program with a different input protocol and its own face renderer. They cannot substitute for one another.

| Component | `pc_avatar_webcam` | `windows` |
| --- | --- | --- |
| Executable | `dist/pc_avatar_webcam.exe` | `dist/AlterEgoWebcam.exe` with `AlterEgoCamera.dll` |
| Desktop implementation | Python packaged with PyInstaller; console and OpenCV preview | C# Windows Forms with C++ Media Foundation source |
| Network input | JSON tracking datagrams, UDP port 9005 | Rendered avatar JPEG frames, TCP port 38991 |
| Pairing | No pairing code, discovery or selected-peer authentication | Manual PC address and six-digit code |
| Avatar | Its own procedural face mask | The rendered Quest mirror, including Horizon avatar or selected preset |
| Virtual camera | Requires installed UnityCapture or OBS backend | Own Windows 11 software camera; source registration requires administrator consent |
| Video-call device name | Unity Video or OBS Virtual Camera | AlterEgo MR Avatar Windows Virtual Camera |

The intended workflow for the current app is:

1. Quest renders My mirror from the OS avatar camera, or renders the immersive mirror.
2. The user explicitly connects to the Windows receiver address and code.
3. Quest sends 960 × 540 JPEG frames over TCP.
4. The receiver decodes them and publishes pixels through a shared frame file.
5. The registered Media Foundation source supplies those pixels to a video-call app.
6. The user selects the virtual camera in Teams, Zoom or the browser; the receiver stays open.

The older UDP path is different: `MainActivity` automatically sends tracking to a fixed private-network address on port 9005, and the Python companion renders a new face from that telemetry. Its current caller supplies the outward-face detector's result, not the wearer snapshot. Ordinary `MirrorActivity` does not start that UDP sender.

## Verification performed

| Check | Result | What it establishes |
| --- | --- | --- |
| Packaged `pc_avatar_webcam.exe --help` | Passed | Actual switches match the Python source; no pairing or eye-mouse switch |
| `pc_avatar_webcam/verify_pipeline.py` | Two checks passed; throughput check failed | UDP/smoothing and rendering work on this PC; 120-frame run measured **44.4 FPS**, below its 45 FPS assertion |
| `windows/verify.py` | Passed | In-process source sample, PIN/protocol rejection, frame-size and dimension bounds, JPEG delivery, disconnect clearing |
| Extra UDP loopback probes | Faults reproduced | Malformed-packet exceptions, misleading connected status, restart/reordering errors, stale expressions, nonfinite values |
| Legacy expression comparison | Faults reproduced | `mouthPucker`, `mouthFunnel`, `cheekPuff` changed **zero pixels**; `jawOpen` changed pixels |
| Legacy neutral render | Visually inspected | Face-only output, vertically misplaced facial texture |
| Native media contract probe | Faults reproduced | First sample timestamp is **0**, rather than current QPC time; activation symbolic-link attribute is not copied |
| Native connection probe | Fault reproduced | Unauthenticated first client delayed a correctly paired second client by **3.0 seconds** |
| Windows binary signatures | Checked | Both EXEs and the native DLL are **NotSigned** |

No camera was installed or registered during this review. A real Quest-to-PC-to-video-call session, FrameServer activation, Teams, Zoom and browser camera selection were **not tested**. Passing an in-process source test is not proof of successful camera registration or call compatibility.

## Problems found

Priority: **High** affects the intended workflow, correctness or data protection; **Medium** affects reliability, usability or deployment. Evidence is marked **reproduced**, **source-confirmed**, or **validation gap**. A confirmed implementation defect does not mean every video-call app has already been shown to fail.

### Integration and avatar correctness

| ID | Priority / evidence | Problem and user impact | Repair direction |
| --- | --- | --- | --- |
| 1 | High · source-confirmed | **Two incompatible companion applications.** Quest's address/PIN control sends TCP JPEGs, while the EXE in the named folder expects UDP JSON. Pairing to it through that control cannot work. | Choose one supported product and one protocol; package and label the matching companion. |
| 2 | High · source-confirmed | **Fixed PC address and automatic UDP transmission.** Every immersive activity creation starts sending to a fixed private-network address on port 9005. Most users' PCs have another address. The selected webcam address/PIN does not control this route. | Remove the automatic route or require explicit peer configuration and Start/Stop controls. |
| 3 | High · source-confirmed | **Wrong tracking source for the legacy webcam.** The caller sends the selected outward-detected face, with `wearer=null`. It does not mirror the wearer. The new ordinary 2D mirror sends no UDP telemetry. On Quest Pro, outward PCA detection is unavailable, so that route also cannot produce genuine wearer expressions from it. | Stream the actual rendered wearer mirror; keep outward face replacement separate. |
| 4 | High · reproduced + source-confirmed | **Legacy avatar does not meet the requested appearance.** It is a 23-vertex face surface, with no Horizon identity, body, belly-up rig, clothing or hand/finger animation. The neutral image puts lips near the forehead and brows below the eyes because texture coordinates and texture orientation disagree. | Retain the Quest-rendered Horizon/body/hand video; if keeping this renderer, repair UV orientation and add a complete rig. |
| 5 | Medium · reproduced | **Advertised expression shapes are ignored.** Pucker, funnel and cheek puff produce identical pixels to neutral. Left/right smile weights are also averaged, losing asymmetry. | Implement the declared shapes or narrow the supported-feature claims. |
| 6 | Medium · source-confirmed | **Telemetry validity and coordinate conventions are wrong/incomplete.** Eye confidence is always 0.98, even without valid hardware gaze. The dormant wearer-quaternion conversion labels Y rotation as pitch and Z rotation as yaw, while the PC uses pitch X/yaw Y/roll Z. It also lacks a wearer-relative origin. The current caller never uses that wearer branch. | Carry real source/validity flags; standardize axes and calibration before enabling wearer telemetry. |

### Older `pc_avatar_webcam` reliability and installation

| ID | Priority / evidence | Problem and user impact | Repair direction |
| --- | --- | --- | --- |
| 7 | High · source-confirmed | **The EXE alone does not install a virtual webcam.** Its distribution folder contains only the EXE; no installer/driver is embedded by the spec. It requires UnityCapture or OBS. With neither installed it keeps running as a local preview, so a user can see an avatar but have no camera in their call app. | Supply a complete installer and reliable device availability check; clearly distinguish preview from webcam readiness. |
| 8 | High · reproduced | **Malformed packets crash the receive/render loop.** A JSON array raises `AttributeError`, a nonnumeric pose raises `ValueError`, and a string sequence raises `TypeError`. NaN/infinite coordinates reach the renderer; an invalid gaze can also fail the preview HUD's integer conversion. | Validate the whole schema, types, finiteness and ranges; reject invalid packets without leaving the loop. |
| 9 | High · source-confirmed | **No authenticated pairing on UDP.** It listens on all interfaces by default, accepts arbitrary senders and has no encryption. Another reachable sender can replace the animation or trigger the parser failures. | Authenticate a selected peer and protect transport; default to the chosen LAN interface. |
| 10 | High · reproduced | **Disconnects leave stale animation visible.** After disconnection the code changes only the status label; it keeps the last pose/weights. Removing `jawOpen` from subsequent packets also retains its old weight. The probe retained jawOpen=0.65 after disconnect. | Timeout to a clearly disconnected/neutral frame and reset missing weights and peer state. |
| 11 | Medium · reproduced | **Restart and reordering logic loses valid data.** After sequence 100, a restarted sender's sequence 1 was ignored. Queued packets 102 then 101 selected 101 because only the last-arriving datagram is parsed. | Track a stream/session identity and select the highest valid sequence; reset on reconnect. |
| 12 | Medium · reproduced | **Connected status does not mean usable tracking.** Even `not JSON` refreshed last_packet_time and displayed connected. Frame timestamps are not used to reject old tracking. | Update health only after validating and accepting a fresh frame. |
| 13 | Medium · source-confirmed | **Webcam backend recovery is missing.** Startup failures are swallowed. Installing a driver afterward requires restarting; send failures keep the active flag set and repeatedly print errors. With no preview there may be no visible indication that only a local renderer is running. | Show actionable backend errors, clear readiness on failure and offer bounded recovery/retry. |
| 14 | Medium · reproduced + validation gap | **60 FPS and compatibility claims exceed the tests.** The throughput run failed at 44.4 FPS. That test adds a full sleep after render work, and never opens a camera backend. Its render assertions are also weak: nonzero background/alpha can satisfy the nonblack check, and the expression comparison changes head pose at the same time. | Measure frame deadlines and latency correctly; separately test expressions, real virtual-camera output and target call apps. |
| 15 | Medium · source-confirmed | **Desktop eye-control documentation is obsolete.** README promises dwell/wink clicks, F9 and `--no-eye-mouse`; the current entry point imports no eye controller and the EXE exposes no such switch. There is no controller `.py` file in the folder. | Remove stale claims or implement and separately test the feature. |
| 16 | Medium · source-confirmed | **Startup/deployment robustness is incomplete.** GPU creation assumes usable OpenGL; its fallback requires an existing context. Startup initialization happens before the cleanup block. FPS and dimensions lack bounds, dependencies use open-ended minimum versions, and there is no reproducible build/version information or UDP firewall setup. | Validate settings, report graphics requirements, cover partial initialization and lock the release dependencies. |
| 17 | High · source-confirmed | **Legacy installer executes an unverified downloaded DLL as administrator.** It downloads from GitHub's mutable `master` branch and checks neither a pinned release hash nor signature. It registers a 64-bit filter from the project's user-writable directory and supplies no uninstall flow. | Pin and verify the payload, install it in a stable protected location, and provide upgrade/uninstall handling. |

### Native `windows` companion problems

| ID | Priority / evidence | Problem and user impact | Repair direction |
| --- | --- | --- | --- |
| 18 | High · reproduced | **Camera timestamps violate the FrameServer contract.** `sampleTime` starts/resets at zero and increments by a nominal duration; it is unrelated to current QPC time. Microsoft requires correlated QPC timestamps. This is a confirmed contract issue; whether it causes dropped frames or sync errors in each call app remains untested. | Timestamp delivered frames using the required clock and cover pause/restart/gaps. |
| 19 | High · reproduced | **Activation metadata is lost.** `AvatarActivate` has a separate attribute store but does not copy it into `AvatarSource`; a symbolic-link attribute set on activation was absent from source attributes. Microsoft requires copying when separate objects are used. Actual FrameServer consequences are not yet tested. | Preserve activation attributes through source creation, then test service-hosted activation. |
| 20 | High · source-confirmed | **All Windows users share a writable frame file.** Installer grants the Users group Modify access to `ProgramData/AlterEgoMR`. Other local users can read or replace video. The single-instance mutex is session-local while the file is machine-wide, so two signed-in sessions can overwrite each other's frames. | Isolate frame channels per user/receiver; restrict permissions while preserving FrameServer access. |
| 21 | High · source-confirmed | **PIN and JPEG video are sent without encryption.** Six digits are checked in plaintext, with no authenticated server identity or lasting device binding. The small bad-attempt delay is not a pairing lockout. Private LAN use reduces exposure but does not protect against an untrusted peer on that LAN. | Protect the connection and bind paired devices, with bounded authentication attempts and revocation. |
| 22 | Medium · reproduced | **An unpaired connection can block pairing.** A single worker waits for the first client's handshake for 3 seconds before accepting the legitimate one. The probe reproduced a 3.0-second delay; repeating idle connections can keep disrupting availability. | Bound and isolate handshake processing without letting unauthenticated clients monopolize the receiver. |
| 23 | Medium · source-confirmed | **No automatic recovery after Wi-Fi loss or sleep.** Quest's TCP sender exits on connection failure and does not retry. PC can accept another connection, but the headset requires the user to reconnect. Address and code entry is manual, and the headset fields are not persisted across process restarts. | Add visible connection states, safe reconnection and saved peer/address handling. |
| 24 | Medium · source-confirmed | **PC preview can remain stale.** Disconnect clears the shared camera file but does not clear the PictureBox or pending preview bitmap. A pending bitmap can also reappear after Stop receiver clears the displayed image. This UI issue is separate from the successful shared-file clearing test. | Clear both displayed and pending frames on every loss/stop, using a generation token. |
| 25 | Medium · source-confirmed | **Installation is partly portable and fragile.** Only the DLL is copied into Program Files. The firewall rule points to the receiver in the extraction folder, so moving it can break incoming connections. There is no monitored installation result or rollback. Uninstall first requires the original bundle DLL to exist and leaves installed files/shared data behind. | Install the complete product at a stable location; verify success and provide complete update/uninstall handling. |
| 26 | Medium · source-confirmed + validation gap | **Format and OS compatibility are narrow.** The source advertises only 960 × 540 RGB32 at 30 FPS; native build is x64 and uses Windows 11 APIs. There is no 720p/1080p or alternative-format negotiation. Some clients may need converted formats; actual Teams/Zoom/browser behavior is untested. | Establish a target compatibility matrix and negotiate common sizes/formats where needed. |
| 27 | Medium · source-confirmed | **Motion rate and freshness are not end-to-end controlled.** Current Quest mirrors produce roughly 15 new images per second while the source emits 30 samples, duplicating images. JPEG packets carry no capture timestamp/sequence; arrival time is treated as freshness, so delayed queued frames cannot be distinguished from fresh tracking. | Carry timing information, measure delay and drop obsolete frames before publication; make motion-rate claims explicit. |
| 28 | Medium · source-confirmed | **Network selection and setup errors are poorly explained.** The first private IPv4 address can belong to a VPN/virtual adapter rather than Wi-Fi. Only addresses are shown; no adapter names, discovery or network-profile diagnostics. Firewall permits only Private-profile local-subnet TCP 38991, so a Public-profile home network can appear paired incorrectly or fail connection. | Label adapters, diagnose route/profile/firewall failures and guide users through pairing. |
| 29 | Medium · reproduced + source-confirmed | **Release packaging and documentation need repair.** Both EXEs/DLL are unsigned. Build paths are machine-specific, with no signed/versioned installer. `windows/README.md` and its distributed copy still describe the old OpenXR-only mirror and old Rift App ID, rather than the current OS avatar-camera route. | Ship a signed, versioned package with matching current documentation and reproducible build checks. |
| 30 | High · validation gap | **The complete webcam promise is not verified.** Existing tests do not exercise COM registration, Windows FrameServer loading, a real headset stream, call-app camera enumeration/format selection, multiple clients, sleep/resume or long sessions. These are missing validations, not proof every one fails. | After the confirmed defects are fixed, validate the whole chain in Windows Camera, Teams, Zoom and browser calls. |

## Explicit product limits

- The native companion requires **Windows 11**; Microsoft's API minimum is build 22000. This build is x64. Windows 10 is not supported by this route.
- The receiver must be running and the user must select the virtual camera in the call app. The camera uses **Session lifetime** and is removed when the receiver stops; it does not automatically launch when a call asks for video.
- The protocol carries **video only**. The call needs a separate PC microphone/audio device; Quest microphone forwarding and audio/video synchronization are not implemented.
- The regular Quest mirror deliberately stops sharing when it pauses or loses frames. Calling from a PC still depends on keeping the headset/avatar camera active. Background and headset-off continuous streaming are not established features.
- A system avatar-camera frame can include an OS-selected background. The current companion streams the preview content; it does not isolate the avatar with a mask or guarantee a plain background.
- A procedural preset's expression estimates are still inferred from rendered video. Streaming them does not improve their tracking accuracy or provide direct raw sensors.

## Recommended repair order

1. Consolidate the supported companion around **rendered Quest video**, using the native Windows program as the starting point. Remove or clearly quarantine the automatic legacy telemetry path.
2. Correct native timestamps and activation attributes, then test actual Windows service activation and camera enumeration.
3. Fix authenticated connection/recovery, frame privacy and disconnect/UI clearing.
4. Deliver one stable installer with accurate documentation, signatures and uninstall behavior.
5. Test a real Quest mirror in Teams, Zoom and a browser, including facial/hand motion, reconnect, headset sleep, 30-minute sessions, image orientation and latency.

## Evidence map and reproducibility

- `app/src/main/java/com/oculus/camerademo/MainActivity.kt:725` — automatic fixed-address UDP startup.
- `MainActivity.kt:1115` — outward detector result passed to UDP with null wearer.
- `app/src/main/java/com/oculus/camerademo/mirror/AvatarLanSender.kt:23` — current address/PIN TCP JPEG protocol.
- `app/src/main/java/com/oculus/camerademo/MirrorActivity.kt:67` — ordinary OS mirror's TCP sender.
- `pc_avatar_webcam/main.py:38` — legacy UDP receiver and local avatar renderer.
- `pc_avatar_webcam/udp_receiver.py:137` — health updated before packet acceptance; sequence/parser behavior.
- `pc_avatar_webcam/avatar_renderer.py:167` — face-only mesh and procedural texture.
- `pc_avatar_webcam/webcam_feed.py:34` — external backends and recovery behavior.
- `pc_avatar_webcam/install_virtualcam.ps1:29` — mutable DLL download and administrator registration.
- `pc_avatar_webcam/pc_avatar_webcam.spec:7` — empty bundled data/binaries lists.
- `windows/AlterEgoCamera.cpp:111` — zero-based sample clock; `:239` — activation store not copied.
- `windows/AlterEgoWebcam.cs:31` — shared frame path; `:97` — single handshake worker; `:123` — file-only disconnect clear; `:219` — session-local mutex.
- `windows/InstallWebcam.ps1:19` — Users Modify ACL; `:32` — extraction-path firewall rule.

Diagnostics: `scratch/pc_webcam_audit_probe.py`, `scratch/pc_webcam_renderer_probe.py`, `scratch/webcam_media_contract_probe.cpp`, `scratch/native_webcam_connection_probe.py`. The C++ probe includes the production implementation and activates it **in process**, without calling the virtual-camera creation or registration APIs. The other probes use only loopback networking or offscreen rendering.

## Primary documentation checked

- [Microsoft MFCreateVirtualCamera](https://learn.microsoft.com/en-us/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera): platform minimum, privacy controls and session/current-user lifetime.
- [Microsoft Frame Server Custom Media Source](https://learn.microsoft.com/en-us/windows-hardware/drivers/stream/frame-server-custom-media-source): QPC timing, activation-attribute copying and media-format compatibility guidance. The stub-driver installation sections describe a different installation route from the Windows 11 virtual-camera API; no separate kernel/stub driver requirement is inferred for this implementation.
- [pyvirtualcam project](https://github.com/letmaik/pyvirtualcam) and [API documentation](https://letmaik.github.io/pyvirtualcam/): external Windows backends and frame delivery.
- [UnityCapture project](https://github.com/schellingb/UnityCapture): external DirectShow capture-device implementation.
