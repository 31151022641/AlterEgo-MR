# AlterEgo MR Webcam — native Mac target 1.0.0

Native SwiftUI receiver and CoreMediaIO Camera Extension for Apple silicon,
based on Windows receiver 1.5.0. Minimum macOS 13, Xcode 16, Swift 5 language
mode. This is a separate target; the Windows EXE/DLL and Quest APK are unchanged.
Start with [START HERE.md](START%20HERE.md).

The window keeps the Windows preview/sidebar layout, receiver controls, manual
pairing, code comparison, background settings and camera tools. The virtual
camera advertises 1920×1080, 1280×720 and 960×540 BGRA at 30 sample frames/sec;
new-image cadence still depends on Quest and network delivery.

## Receiver and privacy

- Existing TCP 38991 / pinned TLS 1.2 protocol and UDP 38992 discovery work with
  Quest's AEMR0003/4/5 and AEPAIR01. Discovery is an untrusted hint, never a PIN
  broadcast. Fresh transcript nonces and separate user approvals protect pairing.
- The transport RSA key and pairing manifest are held in login Keychain. A
  self-signed transport certificate authenticates the LAN endpoint; it does not
  replace Apple's app/extension signing certificate.
- Before decoding, headers, lengths and 16:9 JPEG dimensions are bounded. Only
  one stream owns output. Sequence/timing checks, admission limits, deadlines,
  transactional publication and receipts follow the Windows behavior.
- Decode/composition runs away from the UI. Stop or expiry can invalidate work
  in progress. A stale decode cannot recommit pixels after clearing. UI previews
  retain only the newest processed frame and refresh at most 15 times/sec.
- The compositor replaces border-connected plain backdrops, preserving enclosed
  matching details. Original, studio, colour, copied PNG/JPG and green-screen
  settings persist locally. It does not claim person/scene segmentation.
- Frames use Apple's CMIO sink/source queues, with no frame file, cloud service,
  OBS dependency or physical Mac camera capture. The extension accepts sink input
  only from this app's bundle identifier and matching Apple signing team.
- Two-second expiry, sink stop, connection loss, Mac sleep and session changes
  clear personal output. Ordinary application focus changes keep calls working.

## Native project and release

`AlterEgoMac.xcodeproj` contains app, embedded system camera-extension and XCTest
targets. Both executable targets use the same team, sandbox and TeamID-prefixed
App Group. The CMIO Mach service is prefixed by that entitled group. Frames do
not use an app-group container or shared filesystem; the group is retained for
CMIO's extension/service registration requirements.

The app must be installed in `/Applications` before requesting camera activation.
`OSSystemExtensionManager` presents Apple's normal approval flow. Build/signing
scripts do not switch off Gatekeeper, SIP, extension validation or system policy.
Build Mac.command runs tests, archives, exports a Developer ID app, verifies both
signatures, and makes a ZIP/DMG. Notarize Mac.command is a separate explicit
upload action using a Mac-local Keychain profile. No credentials are bundled.

## Verification status

The development host is Windows. Swift syntax, project linkage, property lists,
assets and package contents can be checked here; Apple framework typechecking,
native XCTest execution, signing, extension activation, video-buffer consumption,
and Zoom/Teams/browser camera capture cannot be established on this host.

XCTest sources cover protocol transcripts, authentication/bounds, image metadata,
capture timelines, matte edges/foreground protection, saved backgrounds and the
native transport certificate. Test mode prevents the app host from opening real
pairing identity or saved background state. Fixtures use synthetic images only.

Run **Test Mac.command** on the Apple-silicon Mac, then **Build Mac.command**.
After installation, verify camera enumeration, all three output sizes, native
Quest pairing, pause/expiry/recovery, stop/quit clearing, sleep/session changes,
background choices and a real video call. Do not infer those results from source
parsing or package validation.

## Primary references

- [Apple: Create a camera extension with Core Media I/O](https://developer.apple.com/documentation/coremediaio/creating-a-camera-extension-with-core-media-i-o)
- [Apple WWDC22: Create camera extensions with Core Media IO](https://developer.apple.com/videos/play/wwdc2022/10022/)
- [Apple: Distributing software on macOS](https://developer.apple.com/macos/distribution/)
- [Apple: Creating an identity for local network TLS](https://developer.apple.com/documentation/network/creating-an-identity-for-local-network-tls)

The app icon reuses this project's existing AlterEgo artwork. No Windows runtime,
obsolete Python receiver, downloaded third-party camera driver or signing key is
included in the Mac source package.
