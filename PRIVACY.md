# AlterEgo MR Privacy Policy

**Effective date:** November 12, 2026

This policy covers MistersLee’s AlterEgo MR app for Meta Quest and the AlterEgo MR Webcam companion for Windows.

## Data used on the headset

- **Avatar video.** AlterEgo MR reads the Horizon OS avatar camera to display your animated avatar. It can use this video to fit a selected look and estimate facial or hand movement for an alternate avatar.
- **Headset camera images.** On supported headsets, passthrough effects process outward camera images on the headset. The app uses face landmarks and expressions to place and animate effects. The images may include people in view. The app does not use this processing to identify them by name or match them to a face database.
- **Head, hand, body, face, and eye tracking.** Depending on the headset and your permissions, the app uses poses, joint positions, expressions, and gaze to control the interface, animate avatars, select effects, and adjust rendering detail. Normal use processes these inputs on the headset without keeping a recording of the raw tracking data.
- **Meta account and avatar services.** If you choose to load your personal Horizon avatar in the immersive mode, the app may check your access through Meta and use the Meta user ID and authorization provided by the headset to request your avatar from Meta. Access depends on Meta’s permissions and service availability. Meta handles the account and avatar services under its own privacy policy.
- **Files and preferences.** The app stores your settings, favorites, imported 3D head models, and any photo masks you create from images you select. It keeps local copies and the names you assign so you can use them again. If you enable spoken announcements, the app passes brief detection and status text to the device’s text-to-speech service.

## Sharing with your PC

When you pair your Quest and start webcam sharing, AlterEgo MR sends the selected avatar video and information needed to run the stream to the paired Windows PC over an encrypted local network connection. The avatar video may include a background supplied by Horizon OS. The app does not use an AlterEgo cloud relay. Nearby devices may receive the receiver’s name, local network address, and certificate fingerprint during discovery. The app saves pairing details so you can reconnect.

If you turn on **Keep avatar webcam live when minimized**, an active avatar stream can continue after you minimize the mirror. Use **Stop webcam** in the app, the small webcam controls, or the sharing notification to end the stream.

If you approve headset casting in the system’s screen-sharing prompt, the paired PC can receive the headset view that Horizon OS makes available. That view can contain app content or your surroundings. Use **Stop casting** to end that session. The companion forwards video and does not forward microphone audio.

If you select the AlterEgo virtual camera in another app, that app receives the video. It may record, store, or transmit the feed under its own settings and privacy policy.

## Local storage and retention

The Windows companion uses a protected temporary frame buffer to supply the virtual camera. Each new frame replaces the previous one. Normal stops and disconnects clear the buffer. If the companion exits unexpectedly, the last buffer contents can remain until the companion restarts or is uninstalled. The companion does not keep a video history.

The headset and PC keep local preferences and pairing records until you remove them. The PC also keeps any background image you import. Both apps can keep local diagnostic logs about timing, state, and errors. Developer diagnostic modes can save test images locally when explicitly enabled. The app does not send these diagnostics to an AlterEgo analytics service.

## Permissions and deletion

You can change camera and tracking permissions in your headset settings. Turning a permission off can disable features that need it.

- **Quest.** Stop any active sharing and use **Forget pairing** in PC Link to remove saved trust for a PC. You can remove imported models and favorites from their in-app libraries. Clear AlterEgo MR’s app data in your headset settings, where available, or uninstall it to remove the app’s local data. Files you imported from elsewhere remain in their original location.
- **Windows.** Stop the receiver and use **Reset pairing** to revoke the previous pairing. Uninstall AlterEgo MR Webcam from **Installed apps** to remove the virtual camera and its frame files. After uninstalling, delete the `AlterEgoMR` folder under `%LOCALAPPDATA%` to remove remaining settings, backgrounds, pairing data, and diagnostic logs.
- **Other services.** Manage your Meta account and avatar data through Meta. To delete a copy created by another app that used the virtual camera, use that app’s controls or contact its provider.

## Privacy contact

For privacy questions or help with a deletion request, email [tonduongjr.lee@gmail.com](mailto:tonduongjr.lee@gmail.com). We use your contact address and the information you provide to respond and handle your request. Include the app name and describe the data you want help removing. Do not include passwords, pairing codes, or camera footage in the request.
