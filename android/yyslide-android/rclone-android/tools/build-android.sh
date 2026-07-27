#!/usr/bin/env bash
# Build rclone Go bridge for Android via gomobile.
# Produces jniLibs/{arm64-v8a,x86_64}/libgojni.so
#
# Prerequisites:
#   - Go 1.26.1 (on PATH, or /usr/local/go/bin)
#   - gomobile/gobind from golang.org/x/mobile@v0.0.0-20260709172247-6129f5bee9d5
#   - Android NDK 29.0.14206865 (ANDROID_NDK_HOME or installed SDK copy)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GO_DIR="$PROJECT_DIR/go"
JNI_DIR="$PROJECT_DIR/jniLibs"
AAR_DIR="$PROJECT_DIR/build"

# Ensure Go is on PATH (supports /usr/local/go and GOPATH/bin)
export PATH="/usr/local/go/bin:${GOPATH:-$HOME/go}/bin:${PATH}"

# On Windows this script is launched through Git Bash by Gradle. Go/gomobile
# looks up javac through the Unix-style PATH, so add JAVA_HOME/bin explicitly.
if [ -n "${JAVA_HOME:-}" ]; then
    if command -v cygpath >/dev/null 2>&1; then
        export PATH="$(cygpath -u "$JAVA_HOME")/bin:${PATH}"
    else
        export PATH="$JAVA_HOME/bin:${PATH}"
    fi
fi

# gomobile invokes javac without an explicit -encoding flag. On Windows that
# otherwise inherits the system ANSI code page (for example GBK) and fails on
# UTF-8 Go doc comments copied into generated Java sources.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dfile.encoding=UTF-8"

# gobind needs -mod=mod to resolve golang.org/x/mobile/bind from the module cache
export GOFLAGS="${GOFLAGS:+$GOFLAGS }-mod=mod"

# Detect Android SDK/NDK from environment
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must be set" >&2
    exit 1
fi
export ANDROID_HOME

NDK_VERSION="29.0.14206865"
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
    ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
fi
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
if [ ! -f "$ANDROID_NDK_HOME/source.properties" ] ||
   ! grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*$NDK_VERSION$" "$ANDROID_NDK_HOME/source.properties"; then
    echo "ERROR: Android NDK $NDK_VERSION is required; got '${ANDROID_NDK_HOME:-unset}'" >&2
    exit 1
fi
export ANDROID_NDK_HOME

echo "=== rclone-android build ==="
echo "Go:           $(go version)"
echo "ANDROID_HOME: $ANDROID_HOME"
echo "NDK:          $ANDROID_NDK_HOME"
echo "Source:       $GO_DIR"
echo "Output:       $JNI_DIR"
echo ""

# Initialize gomobile (downloads NDK toolchain bindings)
echo ">>> gomobile init"
gomobile init

# Build AAR via gomobile bind
echo ">>> gomobile bind (arm64 + amd64)"
mkdir -p "$AAR_DIR"
cd "$GO_DIR"

gomobile bind \
    -target=android/arm64,android/amd64 \
    -javapkg=sh.haven.rclone.binding \
    -androidapi=26 \
    -o "$AAR_DIR/rcbridge.aar" \
    . ./wgbridge ./tsbridge

echo ">>> Extracting native libraries from AAR"
mkdir -p "$JNI_DIR"

# AAR is a zip; extract jni/ subdirectories
cd "$AAR_DIR"
unzip -o rcbridge.aar "jni/*/*" -d extracted

# Map Android ABI names
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/x86_64"
cp extracted/jni/arm64-v8a/libgojni.so "$JNI_DIR/arm64-v8a/"
cp extracted/jni/x86_64/libgojni.so    "$JNI_DIR/x86_64/"

# Also extract the Java/Kotlin bindings JAR from the AAR
unzip -o rcbridge.aar "classes.jar" -d extracted
cp extracted/classes.jar "$AAR_DIR/rcbridge-bindings.jar"

# Clean up
rm -rf extracted

echo ""
echo "=== Build complete ==="
ls -lh "$JNI_DIR/arm64-v8a/libgojni.so"
ls -lh "$JNI_DIR/x86_64/libgojni.so"
ls -lh "$AAR_DIR/rcbridge-bindings.jar"
