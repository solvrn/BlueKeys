#!/bin/bash
# release.sh <name> <code>   e.g. ./release.sh 1.9 10   (code must go up every release)
# Builds, commits, tags, and makes dist/BlueKeys.apk. The file keeps the SAME name every
# release: that is what makes the one-click link in the README always serve the newest.
set -euo pipefail
cd "$(dirname "$0")"
[ $# -eq 2 ] || { echo "usage: ./release.sh <versionName> <versionCode>"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first."; exit 1; }
echo "$1 $2" > version.txt
./phone/build.sh
mkdir -p dist && cp phone/build/bluekeys.apk dist/BlueKeys.apk
git commit -qam "Release $1" && git tag "v$1"
echo "Built dist/BlueKeys.apk (tag v$1). To publish:"
echo "  git push origin main --tags"
echo "  gh release create v$1 dist/BlueKeys.apk --title \"BlueKeys $1\" --notes \"...\""
