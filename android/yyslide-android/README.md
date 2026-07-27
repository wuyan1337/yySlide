# yySlide Android

yySlide Android is the mobile client in the
[yySlide monorepo](https://github.com/wuyan1337/yySlide). It provides SSH,
Mosh, Eternal Terminal, SFTP, VNC, RDP, cloud storage, tunnels, and local Linux
workspaces on Android 8.0 and newer.

[![Android CI](https://github.com/wuyan1337/yySlide/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/wuyan1337/yySlide/actions/workflows/android-ci.yml?query=branch%3Amain)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-orange)](LICENSE)

The Android application ID is `com.yyslide.android`. Android versions and
release tags are independent from the Windows package version.

## Release availability

No signed yySlide Android release has been published in the target GitHub
repository yet. Future `android-v<versionName>` tags will run the root Android
release workflow and, after signing secrets are configured, publish separate
arm64 and x86_64 APKs and AABs on the
[yySlide GitHub Releases page](https://github.com/wuyan1337/yySlide/releases).

`com.yyslide.android` is not currently published on F-Droid. Do not use an
upstream application's F-Droid listing as a yySlide download source.

## Build from source

The supported build environment uses:

- Node.js 24 or newer for monorepo scripts.
- JDK 17 and Android SDK Platform 36.
- Android NDK `29.0.14206865` and CMake `3.31.6`.
- Go 1.26.1 or newer.
- Rust 1.97.1 and `cargo-ndk` 4.1.2.
- `gomobile` and `gobind` from the pinned x/mobile revision
  `v0.0.0-20260709172247-6129f5bee9d5`.

Install the non-Android native toolchain:

```bash
rustup toolchain install 1.97.1
rustup default 1.97.1
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked --version 4.1.2
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260709172247-6129f5bee9d5
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260709172247-6129f5bee9d5
```

Clone and build from the monorepo root:

```bash
git clone --recurse-submodules https://github.com/wuyan1337/yySlide.git
cd yySlide
npm run android:copy-audit
cd android/yyslide-android
./gradlew :app:assembleArm64Debug :app:assembleX64Debug
```

On Windows, use `gradlew.bat` instead of `./gradlew`. APKs are written under
`app/build/outputs/apk/<abi>/debug/` with `yyslide-` filenames.

## Documentation

- [Feature reference](docs/FEATURES.md)
- [Backup format](docs/backup-format.md)
- [Release process](RELEASE.md)
- [Privacy policy](PRIVACY_POLICY.md)
- [Issues and feature requests](https://github.com/wuyan1337/yySlide/issues)

## Upstream attribution

yySlide Android is derived from the AGPL-3.0-licensed
[GlassHaven/Haven](https://github.com/GlassHaven/Haven) project and retains its
copyright and license notices. yySlide's package ID, branding, releases, and
support channels are maintained independently in this monorepo.
