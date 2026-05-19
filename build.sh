#!/bin/bash
# Build the F50 SIP Bridge APK.

set -e

SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
PLATFORM="$SDK_ROOT/platforms/android-33/android.jar"
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$APP_DIR/src"
OUT_DIR="$APP_DIR/build"
APK_NAME="F50SipBridge.apk"

echo "=== Building F50 SIP Bridge ==="
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/gen" "$OUT_DIR/classes" "$OUT_DIR/apk"

mkdir -p "$APP_DIR/res/values"
cat > "$APP_DIR/res/values/strings.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">F50 SIP</string>
</resources>
EOF

"$AAPT2" compile --dir "$APP_DIR/res" -o "$OUT_DIR/res-compiled.zip"
"$AAPT2" link \
    --auto-add-overlay \
    -o "$OUT_DIR/apk/base.apk" \
    -I "$PLATFORM" \
    --manifest "$APP_DIR/AndroidManifest.xml" \
    --java "$OUT_DIR/gen" \
    -R "$OUT_DIR/res-compiled.zip"

JAVA_FILES=$(find "$SRC_DIR" -name '*.java')
GEN_FILES=$(find "$OUT_DIR/gen" -name '*.java' 2>/dev/null || true)
javac -encoding UTF-8 \
    -source 1.8 -target 1.8 \
    -bootclasspath "$PLATFORM" \
    -d "$OUT_DIR/classes" \
    $JAVA_FILES $GEN_FILES \
    -cp "$PLATFORM"

CLASS_FILES=$(find "$OUT_DIR/classes" -name '*.class')
"$D8" --output "$OUT_DIR/apk" --lib "$PLATFORM" $CLASS_FILES

( cd "$OUT_DIR/apk" && zip -u base.apk classes.dex )

"$ZIPALIGN" -f -p 4 "$OUT_DIR/apk/base.apk" "$OUT_DIR/$APK_NAME.unsigned"
"$APKSIGNER" sign --ks "$APP_DIR/debug.keystore" \
    --ks-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT_DIR/$APK_NAME" \
    "$OUT_DIR/$APK_NAME.unsigned"

echo
echo "Built: $OUT_DIR/$APK_NAME"
ls -lh "$OUT_DIR/$APK_NAME"
