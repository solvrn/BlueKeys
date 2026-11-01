#!/bin/bash
# Builds phone/build/bluekeys.apk with plain Android SDK tools (no Gradle, no downloads).
set -euo pipefail
cd "$(dirname "$0")"

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
OUT=build
read -r VNAME VCODE < ../version.txt   # "name code", e.g. "1.7 8"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 1. Compile resources (layout, drawables, strings) and link with the manifest.
#    Outputs base.apk (resources only) and R.java (so code can reference res IDs).
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 34 --target-sdk-version 36 --version-code "$VCODE" --version-name "$VNAME" \
    --java "$OUT/gen" "$OUT/res.zip"

# 2. Compile all Java source files (app code + generated R.java) to .class files,
#    then convert to classes.dex (the format Android actually runs).
if ! javac -nowarn -Xlint:none -g:none -source 17 -target 17 -cp "$JAR" -d "$OUT/classes" \
        $(find src "$OUT/gen" -name '*.java') > "$OUT/javac.log" 2>&1; then
    cat "$OUT/javac.log"
    echo "ERROR: Java compile failed — see above"; exit 1
fi
"$BT/d8" --min-api 34 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

# 3. Pack the dex into the APK, align it (Android's requirement), and sign it.
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f debug.keystore ]; then
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias bluekeys -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=BlueKeys" >/dev/null 2>&1
fi

# Real releases are signed with the key in ~/.bluekeys-signing (never in git; back it up!).
# Without it, fall back to the throwaway debug key so anyone can still build.
KEYDIR="$HOME/.bluekeys-signing"
if [ -f "$KEYDIR/release.jks" ]; then
    "$BT/apksigner" sign --ks "$KEYDIR/release.jks" --ks-pass "file:$KEYDIR/password.txt" \
        --ks-key-alias bluekeys --out "$OUT/bluekeys.apk" "$OUT/aligned.apk"
else
    "$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
        --ks-key-alias bluekeys --out "$OUT/bluekeys.apk" "$OUT/aligned.apk"
fi

echo "Built phone/$OUT/bluekeys.apk"
