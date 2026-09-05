#!/bin/bash
# Packages the SPM executable into a proper VoiceDictationMenuBar.app bundle.
#
# A raw `swift build` binary can request the microphone and be granted
# Accessibility access, but macOS is far more consistent about *remembering*
# those grants (and about showing the right name/icon in System Settings)
# when the binary lives inside a real .app bundle with its own bundle
# identifier. Run this after building in Release.
#
# Usage:
#   ./Scripts/build_app_bundle.sh
#
# Output: ./dist/VoiceDictationMenuBar.app

set -euo pipefail
cd "$(dirname "$0")/.."

APP_NAME="VoiceDictationMenuBar"
BUILD_CONFIG="release"
DIST_DIR="dist"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"

echo "==> Building $BUILD_CONFIG"
swift build -c "$BUILD_CONFIG"

BIN_PATH=$(swift build -c "$BUILD_CONFIG" --show-bin-path)

echo "==> Assembling $APP_BUNDLE"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"

cp "$BIN_PATH/$APP_NAME" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
cp "Sources/$APP_NAME/Supporting/Info.plist" "$APP_BUNDLE/Contents/Info.plist"

# Bundle resources (SPM's own resource bundle, produced alongside the binary).
if [ -d "$BIN_PATH/${APP_NAME}_${APP_NAME}.bundle" ]; then
  cp -R "$BIN_PATH/${APP_NAME}_${APP_NAME}.bundle" "$APP_BUNDLE/Contents/Resources/"
fi

echo "==> Ad-hoc code signing (required for TCC — mic/Accessibility grants are keyed off the signature)"
codesign --force --deep --sign - "$APP_BUNDLE"

echo "==> Done: $APP_BUNDLE"
echo "    Move it to /Applications, launch it once, then grant Microphone"
echo "    and Accessibility access when prompted."
