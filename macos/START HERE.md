# AlterEgo MR Webcam for Apple-silicon Macs

This is the **Mac source/build package**, not an already compiled or signed app.
It is based on the current Windows webcam application, with a native Mac window
and built-in camera extension. OBS is not required. Windows software is unchanged.

You need an Apple-silicon Mac with macOS 13 or newer, full Xcode 16 or newer,
and an Apple Developer team configured for signing. A shareable release also
needs a Developer ID Application certificate and Apple notarization.

1. Extract the whole ZIP on your Mac. Open Xcode once and complete its setup.
2. In Xcode Settings → Accounts, add your developer account. Make sure the team
   has its Developer ID Application certificate available in Keychain.
3. Double-click **Build Mac.command**. Enter your 10-character Apple Team ID.
   This first runs the native tests, then builds/signs the app and camera
   extension together and creates an app, ZIP and DMG in `dist`.
4. Move **AlterEgo MR Webcam.app** into **Applications**. Open it and choose
   **Enable camera**, then approve macOS's camera-extension prompt in System
   Settings. Restart the Mac if macOS requests it.
5. Choose the network shared with Quest, then **Start receiver**. On Quest use
   **My mirror → Menu → PC Link → Find PC**. Compare and approve the matching
   code on **both** devices.
6. In Zoom, Teams or your other calling app, choose **AlterEgo MR Avatar** as the
   camera. Use the Mac's normal microphone. Keep the Quest mirror active.

Before sharing a release, use **Notarize Mac.command** with the signed app and
an existing `notarytool` Keychain profile. It explicitly uploads that app to
Apple. No Apple password or signing key belongs in this folder.

If build/signing fails, open `AlterEgoMac.xcodeproj` in Xcode, choose the same
team for **AlterEgoMac** and **AlterEgoCamera** under Signing & Capabilities, then
inspect Xcode's error. Do not disable macOS security protections or system
integrity checks. **Test Mac.command** can run the isolated native tests before
the signing setup is complete.

The app receives your existing Quest avatar video, supports the Windows app's
background choices and preserves pairing in Keychain. Stop/close, sleep, user
session changes, image expiry and disconnect clear personal output. Switching
focus to a calling app does not stop the receiver. No avatar video is recorded
to disk or uploaded to a server. The camera uses in-memory system queues.

Mac compilation, signing, camera activation and a real call still need to be
verified on a Mac. See `README.md` for exact implementation and validation status.
