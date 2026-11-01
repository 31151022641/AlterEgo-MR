#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$(uname -s)" != Darwin ] || [ "$(uname -m)" != arm64 ]; then echo 'Run on an Apple-silicon Mac, outside Rosetta.'; exit 1; fi
mkdir -p build
TEST_DIR="$(mktemp -d "$PWD/build/test-XXXXXXXX")"
xcodebuild test -project AlterEgoMac.xcodeproj -scheme AlterEgoMac \
    -configuration Debug -destination 'platform=macOS,arch=arm64' \
    -derivedDataPath "$TEST_DIR/derived" -resultBundlePath "$TEST_DIR/Tests.xcresult" \
    CODE_SIGNING_ALLOWED=NO
printf '\nNative test results: %s\nPress Return to close. ' "$TEST_DIR/Tests.xcresult"
read -r _ || true
