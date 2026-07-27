# yySlide

yySlide is a monorepo for the Windows client, Cloud sync service, Android
client, and project website. The public Cloud endpoint is
`https://yyslide.ccwu.cc`.

## Clone

Some Android native and upstream dependencies use Git submodules, so clone
recursively:

```powershell
git clone --recurse-submodules https://github.com/wuyan1337/yySlide.git
cd yySlide
```

For an existing checkout:

```powershell
git submodule update --init --recursive
```

## Layout

- `windows/` - Electron Windows client.
- `server/` - yySlide cloud sync/auth server.
- `android/yyslide-android/` - Android client based on GlassHaven/Haven; it contains upstream submodules.
- `website/` - website source/deployment assets.
- `releases/` - local generated installers, APKs, and unpacked builds; this
  directory is intentionally ignored by Git.

## Privacy

See [PRIVACY.md](PRIVACY.md) for the cross-platform privacy overview. The
Android app-store policy is maintained separately at
`android/yyslide-android/PRIVACY_POLICY.md`.

## Requirements

- Node.js 24 or newer for the root scripts, Windows client, and Cloud server.
- JDK 17 for Android builds.
- Android SDK Platform 36 and the matching platform/build tools used by the
  Android Gradle project.
- Android NDK `29.0.14206865` for native Android components.
- CMake `3.31.6`, Go `1.26.1` or newer, Rust `1.97.1`, and `cargo-ndk`
  `4.1.2` for the Android native libraries.

Release signing additionally requires a private Android keystore and a Windows
code-signing certificate. Neither belongs in this repository.

## Common Commands

```powershell
npm.cmd run dist
```

Builds the Windows client and writes output to `releases/windows/`.

```powershell
npm.cmd run server:start
```

Starts the local yySlide server.

The server requires Node.js 24 or newer. Copy `.env.example` into your
deployment configuration and provide either `YYSLIDE_SERVER_SECRET` or the
preferred `YYSLIDE_SERVER_SECRET_FILE`; never commit the real secret.

Run the root server, sync, and website regressions before release:

```powershell
npm.cmd test
```

```powershell
npm.cmd run android:dist
```

Builds both Android debug ABI packages from `android/yyslide-android` and
copies the smoke-test artifacts to `releases/android/debug/`. Use
`npm.cmd run android:assembleDebug` only when the Gradle-local outputs are
sufficient. Signed release artifacts are copied to `releases/android/` by
`npm.cmd run android:release`.

See `RELEASE.md` for signed builds and `server/DEPLOY.md` for production
Cloud deployment and secure data migration.

## Licensing

The Android module is distributed under the license in
`android/yyslide-android/LICENSE` (AGPL). That license statement applies to
the Android module itself; it does not automatically grant a license for the
Windows client, Cloud server, website, or other repository content. Content
outside the Android module is not separately licensed unless a file states
otherwise.
