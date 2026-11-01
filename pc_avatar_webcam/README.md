# AlterEgo MR Windows Webcam 1.5.0

This folder now distributes the **same native Windows Forms receiver** as
`../windows`. Its `dist/pc_avatar_webcam.exe` is byte-identical to
`dist/AlterEgoWebcam.exe`. The previous UDP face-rendering prototype has been
archived under `legacy/`; it is no longer the application or the build route.

The supported flow is **Quest My mirror → encrypted LAN video → Windows receiver
→ AlterEgo MR Avatar Windows Virtual Camera → Teams, Zoom or browser call**.
It preserves the Quest-rendered Horizon avatar or preset, including its upper body,
clothing and hands. Quest 3/3S/Pro use the headset's available avatar/tracking
features. The headset does not need a PC connection for its own mirror.
Use the receiver with **AlterEgo MR 1.1.1 (14)**, which uses **AEMR0005 over TLS**
with publication receipts and source images up to 1920 × 1080. The receiver also
accepts previous encrypted AEMR0003/AEMR0004 senders at 960 × 540. Update both sides
together for high-detail video. Maximum detail is the upgraded Quest default;
actual source size depends on the camera. Windows supports 1080p/720p/540p output.

Run the complete `windows/dist/AlterEgoMR-Webcam-Setup-1.5.0.exe`, or extract the
full bundle, open `dist/pc_avatar_webcam.exe`, choose **Install webcam**,
and accept administrator elevation. Then open **AlterEgo MR Webcam** from the Start
menu. Start its receiver and use Quest **My mirror → Menu → PC Link → Find PC**. Compare
the exact code and approve on both devices. Manual pairing still accepts the PC
address and full pairing key in **Connect webcam**. The key includes a six-digit code and complete
certificate fingerprint; the previous short-code/plaintext connection is rejected.
Keep the receiver open and select **AlterEgo MR Avatar Windows Virtual Camera** in
the call app. It carries video only; select a separate microphone.

Short camera gaps retain the last accepted image for at most two seconds.
Unavailable-camera controls clear the current image once and send keepalives about
every 500 ms, keeping pairing ready for images to resume. They do not refresh stale
video. Late packets preserve a still-fresh image, and the receiver adapts when a
slow initial network delay improves. An actual disconnect or app/mode exit clears
video immediately. See the companion documentation for the timing limits.

No Python, OBS, UnityCapture or online DLL download is needed. Windows 11 x64 is
required. Every payload file must remain beside the EXE until installation. Setup
places the complete product in Program Files, secures a separate frame channel for
each Windows user, and uses a Private-network/local-subnet firewall rule.

Build `../windows/build.ps1` to refresh both distributions and produce the complete
offline `AlterEgoMR-Webcam-1.5.0-Windows11-x64.zip` and single-file setup.
`main.py` is only a compatibility launcher for that native receiver;
`install_virtualcam.ps1` delegates to its bundled installer and downloads nothing.
The old PyInstaller specification refuses to rebuild the incompatible prototype.
`verify_pipeline.py` delegates to the supported native checks.

For detailed installation, pairing, privacy, update/removal, signing and test limits,
read [the companion documentation](../windows/README.md). The release is unsigned
unless a real trusted publisher certificate is explicitly supplied during build.
Reset pairing in the receiver to invalidate its previous transport identity.

Windows 1.5.0 adds **Change background…**: original, dark/light studio, custom colour, PNG/JPG picture or green screen. Plain avatar backdrops are removed locally before preview and webcam publication. Settings and pictures are saved on this PC. This is a colour matte for plain backdrops, with adjustable edge strength; it does not provide arbitrary-scene AI segmentation or a transparent RGB/YUV webcam. The Quest mirror stays unchanged. See the companion documentation for test evidence and limits.


Windows 1.5.0 adds a large preview, a clear receiver status, grouped connection/background/camera controls, expandable manual pairing and camera options, and matching background and pairing windows. The sidebar scrolls in smaller windows. The streaming and saved pairing/background formats are preserved.

