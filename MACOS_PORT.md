# Native Apple-silicon Mac application

2026-10-27. The requested Mac counterpart is implemented as a separate native
SwiftUI application and CoreMediaIO camera extension. The user selected Apple
silicon and a built-in extension, rather than an OBS bridge. Windows receiver
1.5.0 and the existing Quest APK have not been changed by this port.

## Implemented source

- Preview/control-sidebar layout, receiver status, network selection, Start/Stop,
  manual pairing, comparison-code sheet and camera Enable/Check/Disable controls.
- Existing TLS 1.2, discovery, AEPAIR01 and AEMR0003/4/5 protocols. Fresh nonces,
  separate approvals, per-attempt UI cancellation, authentication limits,
  bounded image headers, sequencing/capture-time checks and accepted/paused/
  rejected receipts preserve Windows behavior.
- Keychain-backed transport private key and pairing manifest. The self-signed
  certificate is generated using native Security RSA signing; no temporary
  private-key file or OpenSSL subprocess is used.
- Original/studio/colour/picture/green-screen background processing, synthetic
  foreground-protection regressions and private persistent background settings.
- A native signed-host-only CMIO sink feeds the camera's source stream in memory.
  No frame file, foreground-user file sharing with the extension role account,
  cloud relay, OBS or legacy Windows camera DLL is used. Source output supports
  1080p, 720p and 540p BGRA at30 sample frames/sec.
- Independent two-second extension expiry, queued-image age/sleep checks,
  stop/disconnect clearing, Mac sleep/session shutdown and async application
  termination. Ordinary app focus loss does not interrupt a call.
- Shared Xcode scheme, embedded extension signing phase, isolated XCTest host,
  native app-icon assets, native test/build/export/notarization command scripts,
  and an Apple-silicon source/build ZIP with executable script permissions.

## Checks actually completed on Windows

The complete17-file Swift source tree passes a Swift grammar syntax check.
The validator explicitly accounts for the parser's empty-tuple-literal grammar
limitation; this is not an Apple compiler or typecheck. XML/plist and OpenStep
project parsers verify target source membership, source paths, extension embed
location, signing/group settings, test isolation, icon dimensions and excluded
Windows/signing material. All three command scripts pass Bash syntax checking.
Python packaging tools compile and the source ZIP is CRC checked.

An independent Python calculation matches the Quest/Windows comparison-code
fixture `DC7A-4801-4FC1`. Native test sources contain **25 XCTest methods** for
protocol/bounds/timing, background image behavior and certificate validity.
Those XCTest methods have **not** been run here. Primary Apple API inspection
also corrected CMIO integer-type conversions and dynamic-code signing-team
validation; it does not substitute for compiling against the Mac SDK.

## Required Mac completion and acceptance

This development host is Windows and has no Xcode, Mac execution host or Apple
signing identity. A compiled/signed `.app` or `.dmg` has therefore **not** been
produced or verified. The delivered ZIP is explicitly named **BuildKit**.

On the Apple-silicon Mac, run Test Mac.command, configure the Apple signing team,
and run Build Mac.command. The latter runs tests, archives/exports a Developer
ID app, checks host/extension signatures and produces app/ZIP/DMG artifacts.
Notarize Mac.command is a separate explicit Apple upload using a Mac-local
Keychain profile. Scripts do not disable Gatekeeper/SIP or embed credentials.

Install the app in Applications and approve its normal camera-extension prompt.
Verify live Quest pairing, real avatar preview, camera enumeration/capture at
all three sizes, image orientation, backgrounds, expiry/recovery/stop/quit,
sleep/session transitions and an actual Zoom/Teams/browser call. These hardware
and OS-integration results remain pending. The Mac project/build guide is
`macos/START HERE.md`; machine-readable source-only checks are in
`macos/VALIDATION.json`.

## Delivered source/build artifact

`macos/dist/AlterEgoMR-Mac-AppleSilicon-1.0.0-BuildKit.zip`
(549,548 bytes). ZIP CRC and executable/LF command-script checks pass.
SHA-256: `4E735E16E5F7ED71A8CC625220EDA7E5788A40C5B0963721187B54996BBFD16E`.
Final source/package validation: **111 checks**,17 Swift files,25 native XCTest
methods authored and not executed. No `.app` or `.dmg` is claimed as delivered.
