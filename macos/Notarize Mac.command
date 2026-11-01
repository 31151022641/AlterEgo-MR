#!/bin/bash
# Explicit optional release action: uploads this signed app to Apple's notary service.
set -euo pipefail
cd "$(dirname "$0")"
if [ "$(uname -s)" != Darwin ]; then echo 'Run on the signing Mac.'; exit 1; fi
APP="${1:-}"
if [ -z "$APP" ]; then
    APP="$(osascript -e 'POSIX path of (choose file with prompt "Choose the signed AlterEgo MR Webcam app" of type {"com.apple.application-bundle"})')"
fi
APP="${APP%/}"
if [ ! -d "$APP/Contents" ] || [ "$(basename "$APP")" != 'AlterEgo MR Webcam.app' ]; then echo 'Choose the signed AlterEgo MR Webcam.app.'; exit 1; fi
PROFILE="${ALTEREGO_NOTARY_PROFILE:-}"
if [ -z "$PROFILE" ]; then printf 'Existing notarytool Keychain profile name: '; read -r PROFILE; fi
if [ -z "$PROFILE" ]; then echo 'Create a notarytool Keychain profile on this Mac first. Never put Apple passwords in this project.'; exit 1; fi
codesign --verify --strict --deep --verbose=2 "$APP"
JOB="$(mktemp -d "$(dirname "$APP")/notary-XXXXXXXX")"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$JOB/upload.zip"
printf 'Submitting this signed app to Apple for notarization.\n'
xcrun notarytool submit "$JOB/upload.zip" --keychain-profile "$PROFILE" --wait
xcrun stapler staple "$APP"
xcrun stapler validate "$APP"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$JOB/AlterEgoMR-1.0.0-AppleSilicon-Notarized.zip"
shasum -a 256 "$JOB/AlterEgoMR-1.0.0-AppleSilicon-Notarized.zip" > "$JOB/SHA256SUMS.txt"
printf '\nNotarized ZIP: %s\nThe earlier DMG is not changed; distribute this new ZIP.\n' "$JOB/AlterEgoMR-1.0.0-AppleSilicon-Notarized.zip"
open "$JOB"
