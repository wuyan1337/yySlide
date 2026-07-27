#!/bin/bash
# Build the haven-usb guest artifacts per-ABI against glibc/musl and stage them
# into core/local's assets dir for APK packaging.
#
# Artifacts:
#   haven-usb-probe   — Slice-2 reachability gate (standalone, static)
#   libhaven_usb.so   — Slice-3 LD_PRELOAD/DllMap hidraw shim (shared)
#   haven-hidraw-test — Slice-3 verification harness (dynamic, for LD_PRELOAD)
#
# Why the GNU cross toolchain (not the NDK / bionic): these run inside the proot
# rootfs (Arch/Alpine/Debian/Void), which links against glibc or musl, not
# bionic. _FORTIFY_SOURCE is disabled so the output depends only on plain libc
# symbols present on both glibc and musl (same reasoning as the wayvnc shim).
#
# Required toolchains (standard Debian/Ubuntu packages; on F-Droid's buildserver):
#   gcc-aarch64-linux-gnu  (arm64-v8a)   ·   gcc-x86-64-linux-gnu  (x86_64)
#
# Usage: ./build-haven-usb.sh [arm64-v8a|x86_64]   (no arg = both)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src/main/cpp/haven-usb"
ASSET_ROOT="$SCRIPT_DIR/src/main/assets/haven-usb"

build_one() {
    local abi="$1" cc strip
    case "$abi" in
        arm64-v8a) cc="aarch64-linux-gnu-gcc"; strip="aarch64-linux-gnu-strip" ;;
        x86_64)    cc="x86_64-linux-gnu-gcc";  strip="x86_64-linux-gnu-strip" ;;
        *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    command -v "$cc" >/dev/null 2>&1 || {
        echo "$cc not found — install gcc-aarch64-linux-gnu / gcc-x86-64-linux-gnu" >&2
        exit 1
    }
    local out_dir="$ASSET_ROOT/$abi"
    mkdir -p "$out_dir"

    echo "=== haven-usb-probe for $abi ==="
    # Static so the probe runs in any distro's rootfs regardless of its libc.
    "$cc" -O2 -D_FORTIFY_SOURCE=0 -Wall -Wextra -static \
        -o "$out_dir/haven-usb-probe" "$SRC_DIR/haven-usb-probe.c"
    command -v "$strip" >/dev/null && "$strip" "$out_dir/haven-usb-probe" || true

    echo "=== libhaven_usb.so for $abi ==="
    # Shared, fortify off (loads under both glibc and musl). Links pthread + dl.
    "$cc" -shared -fPIC -O2 -D_FORTIFY_SOURCE=0 -Wall -Wextra \
        -o "$out_dir/libhaven_usb.so" "$SRC_DIR/libhaven_usb.c" -lpthread -ldl
    command -v "$strip" >/dev/null && "$strip" "$out_dir/libhaven_usb.so" || true

    echo "=== haven-hidraw-test for $abi ==="
    # DYNAMIC (no -static) so LD_PRELOAD can interpose its libc calls.
    "$cc" -O2 -D_FORTIFY_SOURCE=0 -Wall -Wextra \
        -o "$out_dir/haven-hidraw-test" "$SRC_DIR/haven-hidraw-test.c"
    command -v "$strip" >/dev/null && "$strip" "$out_dir/haven-hidraw-test" || true

    ls -la "$out_dir"
}

if [ $# -eq 0 ]; then
    build_one arm64-v8a
    build_one x86_64
else
    build_one "$1"
fi
