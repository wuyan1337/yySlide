---
layout: default
title: yySlide Android
---

# yySlide Android

[![Android CI](https://github.com/wuyan1337/yySlide/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/wuyan1337/yySlide/actions/workflows/android-ci.yml?query=branch%3Amain)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-orange)](https://github.com/wuyan1337/yySlide/blob/main/android/yyslide-android/LICENSE)

yySlide Android is the `com.yyslide.android` mobile client in the
[yySlide monorepo](https://github.com/wuyan1337/yySlide). It supports Android
8.0 (API 26) and newer.

## Download status

No signed yySlide Android release has been published in the target GitHub
repository yet. Future `android-v<versionName>` tags will use the root release
workflow to publish arm64 and x86_64 APKs and AABs on
[GitHub Releases](https://github.com/wuyan1337/yySlide/releases) after signing
secrets are configured.

`com.yyslide.android` is not currently published on F-Droid. No upstream
application listing should be presented as a yySlide package or update source.

## Highlights

- SSH, Mosh, Eternal Terminal, SFTP, VNC, and RDP clients.
- SMB and rclone-backed cloud file access.
- Per-connection WireGuard, Tailscale, proxy, and jump-host support.
- Local Alpine, Debian, Arch, and Void workspaces through PRoot.
- Biometric lock, encrypted backup and restore, and no telemetry.
- Optional yySlide Cloud synchronization with the Windows client.

See the [feature reference](FEATURES.md) for detailed capabilities.

## Build from source

Use JDK 17, Android SDK Platform 36, NDK `29.0.14206865`, CMake `3.31.6`,
Go 1.26.1 or newer, Rust 1.97.1, and `cargo-ndk` 4.1.2. The Go Android
bindings must use the same pinned x/mobile revision as CI:

```bash
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260709172247-6129f5bee9d5
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260709172247-6129f5bee9d5

git clone --recurse-submodules https://github.com/wuyan1337/yySlide.git
cd yySlide/android/yyslide-android
./gradlew :app:assembleArm64Debug :app:assembleX64Debug
```

On Windows, use `gradlew.bat`. Debug APKs are written under
`app/build/outputs/apk/<abi>/debug/`.

## Project links

- [Source code](https://github.com/wuyan1337/yySlide)
- [Issues and feature requests](https://github.com/wuyan1337/yySlide/issues)
- [Android CI](https://github.com/wuyan1337/yySlide/actions/workflows/android-ci.yml)
- [Android release workflow](https://github.com/wuyan1337/yySlide/actions/workflows/android-release.yml)
- [Release process](https://github.com/wuyan1337/yySlide/blob/main/android/yyslide-android/RELEASE.md)
- [Backup format](backup-format.md)
- [Privacy policy](privacy-policy.html)
- [AGPL-3.0 license](https://github.com/wuyan1337/yySlide/blob/main/android/yyslide-android/LICENSE)

## Upstream attribution

yySlide Android is derived from the AGPL-3.0-licensed
[GlassHaven/Haven](https://github.com/GlassHaven/Haven) project. yySlide's
package ID, branding, releases, and support channels are independent.
