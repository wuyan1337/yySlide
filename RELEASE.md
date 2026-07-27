# yySlide Release Notes

## Version Rule

- Version Android and Windows independently; neither component is required to
  adopt the other component's version number.
- Tag Android releases as `android-v<versionName>` (for example,
  `android-v5.51.0`) and keep the tag version aligned with Android
  `versionName`.
- Increment Android `versionCode` for every APK/AAB uploaded or shared outside the developer machine.
- Tag Windows releases as `windows-v<packageVersion>` (for example,
  `windows-v0.1.0`) and keep the tag version aligned with `windows/package.json`.
- A component tag releases only that component. Every release must include a
  short changelog entry for the affected component and any security-impacting
  changes.

## Android

Push an `android-v<versionName>` tag to run the authoritative GitHub Android
release workflow. The workflow can also be started manually without creating a
GitHub Release; tag-triggered runs publish the signed APK and AAB artifacts.

The native Android release build requires JDK 17, Android SDK Platform 36,
NDK `29.0.14206865`, CMake 3.31.6, Go 1.26.1 or newer, Rust 1.97.1, and
`cargo-ndk` 4.1.2.

Debug packages:

```powershell
npm.cmd run android:dist
```

Debug APKs are copied to `releases/android/debug/` so they cannot overwrite
or be mistaken for signed release artifacts.

Release packages require a private keystore that must not be committed:

```powershell
# Put the keystore here:
# android/yyslide-android/yyslide-release.jks
$env:KEYSTORE_PASSWORD="..."
$env:KEY_ALIAS="..."
$env:KEY_PASSWORD="..."
npm.cmd run android:release
```

The release script builds both ABI packages and copies them to:

- `releases/android/yyslide-5.51.0-x64-release.apk`
- `releases/android/yyslide-5.51.0-arm64-release.apk`

The copy step replaces only the two artifacts being built. It does not clear
older releases or unrelated files from `releases/android/`.

The keystore file is ignored by `.gitignore`.

Before sharing a release APK:

- Run `npm.cmd run android:copy-audit` and review any remaining hardcoded Android UI strings.
- Run `npm.cmd run android:dist` for debug smoke builds.
- Run `npm.cmd run android:release` only with the release keystore and signing environment configured.
- Verify both copied APKs exist in `releases/android/`.
- Do not distribute debug APKs to end users unless the release is explicitly marked as a test build.

## Windows

Use a `windows-v<packageVersion>` tag for a Windows release. Windows package
versions are independent from Android `versionName` values.

Unsigned development package:

```powershell
npm.cmd run windows:dist
```

For signed builds, configure the normal `electron-builder` signing environment before running the same command, for example `CSC_LINK` and `CSC_KEY_PASSWORD` for a `.p12/.pfx` certificate. The Windows builder is configured to allow signing when a certificate is available.

Outputs:

- `releases/windows/yySlide-Setup-0.1.0-x64.exe`
- `releases/windows/win-unpacked/yySlide.exe`

Before sharing a Windows installer:

- Configure code signing with the production certificate (`CSC_LINK` and `CSC_KEY_PASSWORD`, or the equivalent local certificate store setup).
- Run `npm.cmd run windows:dist`.
- Confirm the installer signature in Windows file properties or with `signtool verify /pa`.
- Install on a clean Windows profile and confirm login, sync, SSH, SFTP, and update-safe local data paths.

## Combined Release

```powershell
npm.cmd run release:all
```

This command builds the Windows distribution and signed Android release APKs.
It intentionally fails when Android release signing is not configured; it
never substitutes debug APKs into a release.

## Sync Contract Test

Use Node.js 24 or newer and run all service-side security and stale-write
contract tests before releasing:

```powershell
npm.cmd test
```

This starts a temporary local yySlide Cloud server, registers a throwaway account, verifies that stale vault writes are rejected with HTTP 409, then verifies that a retry using the current `updatedAt` succeeds.

## Sync E2E Release Checklist

Run this with one Windows client, one Android client, and the same yySlide Cloud account:

- Windows adds a connection. Android receives the SSE refresh or refreshes manually and shows the new connection.
- Windows deletes that connection. Android removes it and does not resurrect it after another sync.
- Android adds a connection. Windows receives the update and shows it in the correct category.
- Android deletes that connection. Windows removes it and does not resurrect it after another sync.
- Create an empty category on Windows. Android shows the empty category.
- Delete that empty category on Android. Windows removes it and does not restore it from an older vault.
- Edit the same connection on both clients while both are online. Confirm one side receives a conflict-safe refresh and no duplicate connection appears.
- Take Android offline, change Windows data, then bring Android online. Confirm old Android data does not overwrite the newer cloud vault.
- Take Windows offline, change Android data, then bring Windows online. Confirm old Windows data does not overwrite the newer cloud vault.

## Error Message Checklist

User-visible errors should be understandable and should not expose stack traces, tokens, passwords, private keys, recovery codes, or raw encrypted vault data.

Before release, manually check:

- Login failure, wrong password, wrong 2FA code, and recovery-code failure.
- Sync failure from network loss and from stale vault conflict.
- Vault-key unlock failure and vault decrypt failure.
- SSH authentication failure, host-key changed failure, and SFTP open/download/install failure.
- App lock unavailable and failed biometric/device credential paths.

## Security Checklist

- Tokens are stored only in the platform-protected credential store or encrypted app storage.
- Vault keys are wrapped/encrypted at rest and never logged.
- Passwords, SSH private keys, TOTP secrets, recovery codes, bearer tokens, and vault ciphertext are redacted from logs.
- Android verbose logs and connection logs do not include secrets before sharing/exporting.
- Server has exactly one of `YYSLIDE_SERVER_SECRET_FILE` or
  `YYSLIDE_SERVER_SECRET` configured in production and backed up.
- Prefer a mode-600 `YYSLIDE_SERVER_SECRET_FILE` over an inline environment
  value, and back it up separately from the database.
- Server has rate limiting or upstream protection for auth, 2FA, recovery, and vault endpoints.
- 2FA enrollment, login, recovery-code login, and sign-out are tested once on the release candidate.
- Release signing keys and Windows signing certificates are not committed and are stored outside the repo.
- If legacy server data is present, stop the service and run the documented
  `server:migrate` procedure; require `plaintextTotpValues: 0` before restart.

## Do Not Ship

Do not ship or commit these local files:

- `server/data/`
- `*.sqlite`, `*.sqlite3`
- `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.key`, `*.ppk`
- SSH private keys such as `id_rsa`, `id_ed25519`, `id_ecdsa`, or `id_dsa`
- `.env`, `*.secret`, and production server-secret files
- `*.jsonl`
