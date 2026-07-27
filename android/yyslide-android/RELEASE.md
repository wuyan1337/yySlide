# yySlide Android Release Process

Android releases use their own version and tag sequence. The authoritative CI
and release definitions live at the monorepo root:

- [Android CI](../../.github/workflows/android-ci.yml)
- [Android Release](../../.github/workflows/android-release.yml)

## 1. Prepare the version

Update `app/build.gradle.kts`:

```kotlin
versionCode = <incremented integer>
versionName = "<x.y.z>"
```

Increment `versionCode` for every APK or AAB distributed outside a developer
machine. ABI-specific codes are generated as `versionCode * 10 + 1` for arm64
and `versionCode * 10 + 2` for x86_64.

The release tag must exactly match `android-v<versionName>`. The root release
workflow reads `versionName` from Gradle and fails before building when the tag
is empty or different.

Prepare concise release notes for Android-visible changes and any security or
data-migration impact. GitHub generates a starting release body from commits;
edit it after publishing when more context is required.

## 2. Verify the release candidate

Use Node.js 24 or newer and run from the monorepo root:

```bash
npm run android:copy-audit
cd android/yyslide-android
./gradlew testArm64ReleaseUnitTest testX64ReleaseUnitTest
```

The supported native toolchain is JDK 17, Android SDK Platform 36, NDK
`29.0.14206865`, CMake `3.31.6`, Go 1.26.1 or newer, Rust 1.97.1, and
`cargo-ndk` 4.1.2. Install `gomobile` and `gobind` only from the revision used
by CI:

```bash
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260709172247-6129f5bee9d5
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260709172247-6129f5bee9d5
```

Do not replace either pin with a floating version.

## 3. Test local signing

Place the private keystore at:

```text
android/yyslide-android/yyslide-release.jks
```

The file is ignored by Git and must never be committed. Configure the signing
passwords and build both release APKs from the monorepo root:

```powershell
$env:KEYSTORE_PASSWORD = "..."
$env:KEY_ALIAS = "..."
$env:KEY_PASSWORD = "..."
npm run android:release
```

The script copies the signed APKs to `releases/android/`. GitHub Actions also
builds the corresponding AABs.

## 4. Configure GitHub signing secrets

The repository must define all four secrets before a release run:

- `KEYSTORE_BASE64`: base64-encoded contents of `yyslide-release.jks`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The release workflow validates these values, decodes the keystore with
owner-only permissions, and removes the decoded file after every build attempt.
Never export signing secrets through an artifact or workflow input.

## 5. Tag and publish

Commit the version bump and release changes, then create the component tag:

```bash
git add android/yyslide-android/app/build.gradle.kts <release-note-files>
git commit -m "Release Android <x.y.z>"
git tag android-v<x.y.z>
git push origin main android-v<x.y.z>
```

The root Android Release workflow then:

1. Audits UI copy and runs both ABI release unit-test tasks.
2. Builds signed arm64 and x86_64 APKs and AABs in parallel.
3. Publishes all four artifacts to the matching GitHub Release.

A manual workflow run builds downloadable Actions artifacts but publishes a
GitHub Release only when its ref is an `android-v*` tag.

If the generated release body needs editing:

```bash
gh release edit android-v<x.y.z> --repo wuyan1337/yySlide --notes-file <notes.md>
```

## F-Droid status

The `com.yyslide.android` application is not currently listed on F-Droid.
There is no yySlide auto-update recipe or publication SLA. A future submission
must use yySlide's package ID, signing expectations, source-build recipe, and
current pinned toolchain rather than an upstream application's metadata.

## Final checklist

- [ ] `versionCode` increased and `versionName` matches the Android tag.
- [ ] Android copy audit and both release unit-test tasks pass.
- [ ] Local release APKs are signed with `yyslide-release.jks`.
- [ ] The root Android CI workflow is green for the tagged commit.
- [ ] The GitHub Release contains arm64 and x86_64 APK and AAB assets.
- [ ] Release notes describe user-visible and security-impacting changes.
- [ ] No keystore, signing password, token, or generated secret bundle is committed.
