#!/usr/bin/env bash
# Build rdp-transport for Android targets and generate Kotlin bindings.
#
# Prerequisites:
#   cargo install cargo-ndk --locked --version 4.1.2
#   rustup target add aarch64-linux-android x86_64-linux-android
#   Android NDK 29.0.14206865 via ANDROID_NDK_HOME or the SDK install

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="$SCRIPT_DIR/../rust"
OUT_DIR="$SCRIPT_DIR/../jniLibs"
KOTLIN_DIR="$SCRIPT_DIR/../kotlin"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$SCRIPT_DIR/../build/cargo-target}"

# Resolve and validate the pinned NDK.
NDK_VERSION="29.0.14206865"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    for SDK_ROOT in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        [ -n "$SDK_ROOT" ] || continue
        [ -d "$SDK_ROOT/ndk/$NDK_VERSION" ] || continue
        ANDROID_NDK_HOME="$SDK_ROOT/ndk/$NDK_VERSION"
        break
    done
fi
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
if [ ! -f "$ANDROID_NDK_HOME/source.properties" ] ||
   ! grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*$NDK_VERSION$" "$ANDROID_NDK_HOME/source.properties"; then
    echo "ERROR: Android NDK $NDK_VERSION is required; got '${ANDROID_NDK_HOME:-unset}'" >&2
    exit 1
fi
export ANDROID_NDK_HOME
echo "Using NDK: $ANDROID_NDK_HOME"

cd "$RUST_DIR"

echo "==> Building for arm64-v8a..."
cargo ndk -t arm64-v8a -o "$OUT_DIR" build --release

echo "==> Building for x86_64..."
cargo ndk -t x86_64 -o "$OUT_DIR" build --release

echo "==> Generating Kotlin bindings..."
mkdir -p "$KOTLIN_DIR"

# Host (debug) build for binding generation — debug so metadata isn't stripped
echo "==> Building host library (debug) for uniffi-bindgen..."
cargo build

LIBPATH=""
if [ -f "$CARGO_TARGET_DIR/debug/librdp_transport.so" ]; then
    LIBPATH="$CARGO_TARGET_DIR/debug/librdp_transport.so"
elif [ -f "$CARGO_TARGET_DIR/debug/librdp_transport.dylib" ]; then
    LIBPATH="$CARGO_TARGET_DIR/debug/librdp_transport.dylib"
elif [ -f "$CARGO_TARGET_DIR/debug/rdp_transport.dll" ]; then
    LIBPATH="$CARGO_TARGET_DIR/debug/rdp_transport.dll"
fi

if [ -z "$LIBPATH" ]; then
    echo "ERROR: Host build succeeded but library not found in $CARGO_TARGET_DIR/debug/" >&2
    exit 1
fi

cargo run --bin uniffi-bindgen -- generate --library "$LIBPATH" \
    --language kotlin --out-dir "$KOTLIN_DIR" \
    --config uniffi.toml

# Verify generated bindings exist
if [ ! -f "$KOTLIN_DIR/sh/haven/rdp/rdp_transport.kt" ]; then
    echo "ERROR: uniffi-bindgen did not produce rdp_transport.kt" >&2
    exit 1
fi

echo "==> Kotlin bindings generated in $KOTLIN_DIR"

echo "==> Done. Libraries in $OUT_DIR:"
find "$OUT_DIR" -name "*.so" -type f
