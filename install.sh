#!/bin/bash
# Builds BlueKeys and installs it on the connected phone (USB or Wi-Fi debugging).
set -euo pipefail
cd "$(dirname "$0")/phone"
chmod +x build.sh
./build.sh
echo "Installing on phone…"
adb install -r build/bluekeys.apk
echo "Done. Open BlueKeys on the phone."
