#!/usr/bin/env bash
# Builds BudsPopup.app with the Xcode Command Line Tools only (no Xcode project needed).
#   xcode-select --install   # once
#   ./build.sh && open build/BudsPopup.app
set -euo pipefail
cd "$(dirname "$0")"

APP=build/BudsPopup.app
ARCH="${ARCH:-$(uname -m)}"          # ARCH=universal for arm64 + x86_64
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

compile() {
  swiftc -O -target "$1-apple-macos13.0" \
    -framework AppKit -framework SwiftUI -framework IOBluetooth -framework ServiceManagement \
    Sources/*.swift -o "$2"
}

if [ "$ARCH" = "universal" ]; then
  compile arm64 build/bp-arm64
  compile x86_64 build/bp-x86_64
  lipo -create build/bp-arm64 build/bp-x86_64 -output "$APP/Contents/MacOS/BudsPopup"
  rm build/bp-arm64 build/bp-x86_64
else
  compile "$ARCH" "$APP/Contents/MacOS/BudsPopup"
fi

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>by.dzianis.budspopup</string>
  <key>CFBundleName</key><string>BudsPopup</string>
  <key>CFBundleDisplayName</key><string>Buds Popup</string>
  <key>CFBundleExecutable</key><string>BudsPopup</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>Нужно, чтобы читать заряд наушников FreeBuds.</string>
</dict>
</plist>
PLIST

# ad-hoc signature: required for the Bluetooth permission prompt to stick
codesign --force --deep --sign - "$APP"
echo "Built $APP"
