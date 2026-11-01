#!/bin/bash
# Run on the Apple-silicon Mac that will build/sign this application.
set -euo pipefail
cd "$(dirname "$0")"
PROJECT_DIR="$PWD"
finish() { local result=$?; if [ "$result" -ne 0 ]; then printf '\nBuild stopped. Read the error above; no app was installed.\n'; fi; printf '\nPress Return to close. '; read -r _ || true; }
trap finish EXIT
if [ "$(uname -s)" != "Darwin" ] || [ "$(uname -m)" != "arm64" ]; then
    printf 'This build is for an Apple-silicon Mac. Run it on that Mac, outside Rosetta.\n'; exit 1
fi
if ! xcodebuild -version >/dev/null 2>&1; then
    printf 'Install the full Xcode app, open it once, then select it in Xcode Settings → Locations → Command Line Tools.\n'; exit 1
fi
TEAM_ID="${ALTEREGO_APPLE_TEAM_ID:-}"
if [ -z "$TEAM_ID" ]; then
    printf 'Apple Developer Team ID (10 characters, visible in your developer account): '
    read -r TEAM_ID
fi
if ! [[ "$TEAM_ID" =~ ^[A-Z0-9]{10}$ ]]; then printf 'Enter a valid 10-character Team ID.\n'; exit 1; fi
printf '\nThis runs native tests, archives the app and exports a Developer ID release.\n'
printf 'Xcode may contact Apple to obtain your team provisioning profiles.\n'
printf 'Your Developer ID Application certificate must already be available in Keychain.\n\n'
mkdir -p build dist
BUILD_DIR="$(mktemp -d "$PROJECT_DIR/build/release-XXXXXXXX")"
TEST_DIR="$BUILD_DIR/tests"
ARCHIVE="$BUILD_DIR/AlterEgo.xcarchive"
EXPORT="$BUILD_DIR/export"

# XCTest's app host is explicitly isolated from real pairing/background state.
xcodebuild test -project AlterEgoMac.xcodeproj -scheme AlterEgoMac \
    -configuration Debug -destination 'platform=macOS,arch=arm64' \
    -derivedDataPath "$TEST_DIR" -resultBundlePath "$BUILD_DIR/Tests.xcresult" \
    CODE_SIGNING_ALLOWED=NO

xcodebuild archive -project AlterEgoMac.xcodeproj -scheme AlterEgoMac \
    -configuration Release -destination 'generic/platform=macOS' \
    -archivePath "$ARCHIVE" -derivedDataPath "$BUILD_DIR/derived" \
    -allowProvisioningUpdates DEVELOPMENT_TEAM="$TEAM_ID"

cat > "$BUILD_DIR/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>method</key><string>developer-id</string>
<key>signingStyle</key><string>automatic</string>
<key>teamID</key><string>$TEAM_ID</string>
</dict></plist>
EOF
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$BUILD_DIR/ExportOptions.plist" \
    -exportPath "$EXPORT" -allowProvisioningUpdates
APP="$EXPORT/AlterEgo MR Webcam.app"
EXTENSION="$APP/Contents/Library/SystemExtensions/com.misterslee.alterego.mac.camera-extension.systemextension"
test -d "$APP" && test -d "$EXTENSION"
codesign --verify --strict --verbose=2 "$EXTENSION"
codesign --verify --strict --deep --verbose=2 "$APP"
codesign -dv --verbose=4 "$APP" 2>&1 | grep -F "TeamIdentifier=$TEAM_ID"
codesign -dv --verbose=4 "$EXTENSION" 2>&1 | grep -F "TeamIdentifier=$TEAM_ID"

STAMP="$(date +%Y%m%d-%H%M%S)"
RELEASE_DIR="$PROJECT_DIR/dist/AlterEgoMR-Mac-1.0.0-$STAMP"
mkdir "$RELEASE_DIR"
ditto "$APP" "$RELEASE_DIR/AlterEgo MR Webcam.app"
ditto -c -k --sequesterRsrc --keepParent "$RELEASE_DIR/AlterEgo MR Webcam.app" "$RELEASE_DIR/AlterEgoMR-1.0.0-AppleSilicon.zip"
STAGING="$BUILD_DIR/dmg"
mkdir "$STAGING"
ditto "$APP" "$STAGING/AlterEgo MR Webcam.app"
ln -s /Applications "$STAGING/Applications"
hdiutil create -volname 'AlterEgo MR Webcam' -srcfolder "$STAGING" -ov -format UDZO "$RELEASE_DIR/AlterEgoMR-1.0.0-AppleSilicon.dmg"
shasum -a 256 "$RELEASE_DIR"/*.zip "$RELEASE_DIR"/*.dmg > "$RELEASE_DIR/SHA256SUMS.txt"
printf '\nSigned app and disk image created in:\n%s\n' "$RELEASE_DIR"
printf 'Notarization has not run. Use Notarize Mac.command before sharing a release.\n'
printf 'To test locally: move the app to Applications, open it, then choose Enable camera.\n'
open "$RELEASE_DIR"
