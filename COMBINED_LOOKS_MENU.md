# Combined Faces & Avatars — Quest 1.2.22 (48)

The home menu has a single **Faces & Avatars** choice. Opening it shows My
Horizon avatar, Meta sample, preset faces and supported photo-face choices in
one scrollable page. Passthrough also includes the separate Sunglasses effect.

The separate Avatar page is removed in passthrough and My mirror. Avatar
selection appears first, followed by the existing face gallery. Framing,
camera retry and mirror view controls remain below the choices where relevant.

PC Link, file import and Settings remain accessible from the home/footer
navigation. Back, Reset view, Exit passthrough and the face-detection status
section retain their existing behavior. Each choice still uses its appropriate
tracking and rendering path; this menu change does not enable outward camera
access on Quest Pro.

Existing navigation tests were adjusted to the combined page. No additional
tests were added for this presentation change.

Verified on 28 October 2026:

- Release build succeeded. All 444 JVM tests passed, with no failures, errors
  or skipped tests.
- Both authored portraits and required credits remain packaged.
- APK Signature Scheme v2 verified. Version 1.2.22, code 48.
- APK size: 141,461,768 bytes.
- SHA-256: `73D264B23ADE50DE6B556615832CD26EF34D886594FF997D5BA7AF88156061A7`.

The build no longer contains a separate Avatar navigation page. The two active
launcher callers both provide one combined picker.

Installed successfully on the connected Quest Pro. Package version 1.2.22 (48)
was confirmed and launching passthrough returned `Status: ok`. The headset
remained asleep, so presentation and hand selection still need a check while
the headset is worn.
