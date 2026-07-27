#!/usr/bin/env node

const fs = require("node:fs");
const path = require("node:path");

const buildScriptPath = path.join(__dirname, "build.sh");
const source = fs.readFileSync(buildScriptPath, "utf8");

function fail(message) {
  process.stderr.write(`FFmpeg source pin audit failed: ${message}\n`);
  process.exitCode = 1;
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

const gitPins = {
  FFMPEG_COMMIT: "140fd653aed8cad774f991ba083e2d01e86420c7",
  X264_COMMIT: "b35605ace3ddf7c1a5d67a2eb553f034aef41d55",
  VPX_COMMIT: "12f3a2ac603e8f10742105519e0cd03c3b8f71dd",
  X265_COMMIT: "1d117bed4747758b51bd2c124d738527e30392cb",
};

const archivePins = {
  LAME_SHA256: "ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e",
  OPUS_SHA256: "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1",
  OGG_SHA256: "0eb4b4b9420a0f51db142ba3f9c64b333f826532dc0f48c6410ae51f4799b664",
  VORBIS_SHA256: "0e982409a9c3fc82ee06e08205b1355e5c6aa4c36bca58146ef399621b0ce5ab",
  FREETYPE_SHA256: "0550350666d427c74daeb85d5ac7bb353acba5f76956395995311a9c6f063289",
  FRIBIDI_SHA256: "1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c",
  HARFBUZZ_SHA256: "77e4f7f98f3d86bf8788b53e6832fb96279956e1c3961988ea3d4b7ca41ddc27",
  MBEDTLS_SHA256: "8b54fb9bcf4d5a7078028e0520acddefb7900b3e66fec7f7175bb5b7d85ccdca",
  LIBASS_SHA256: "eae425da50f0015c21f7b3a9c7262a910f0218af469e22e2931462fed3c50959",
};

for (const [name, expected] of Object.entries({ ...gitPins, ...archivePins })) {
  const match = source.match(new RegExp(`readonly\\s+${name}="([0-9a-f]+)"`));
  if (!match) {
    fail(`missing immutable ${name} assignment`);
  } else if (match[1] !== expected) {
    fail(`${name} changed without updating the audited lock (${match[1]})`);
  }
}

const forbiddenMutablePatterns = ["FFMPEG_REF", "fetch_git_tag", "--branch", '"stable"'];
for (const pattern of forbiddenMutablePatterns) {
  if (source.includes(pattern)) {
    fail(`mutable Git selector remains in build.sh: ${pattern}`);
  }
}

const requiredGitCalls = [
  ["ffmpeg", "FFMPEG_COMMIT", "refs/tags/n8.0"],
  ["x264", "X264_COMMIT", "refs/heads/stable"],
  ["vpx", "VPX_COMMIT", "refs/tags/v1.14.1"],
  ["x265", "X265_COMMIT", "refs/tags/4.1"],
];
for (const [name, pin, fetchRef] of requiredGitCalls) {
  const call = new RegExp(`fetch_git_commit\\s+(?:"\\$\\{?name\\}?"|${escapeRegExp(name)})[^\\n]*"\\$${pin}"[^\\n]*"${escapeRegExp(fetchRef)}"`);
  if (name === "x264") {
    if (!source.includes('fetch_git_commit "$name" "https://code.videolan.org/videolan/x264.git" "$X264_COMMIT" "refs/heads/stable"')) {
      fail("x264 fetch does not use X264_COMMIT");
    }
  } else if (!call.test(source)) {
    fail(`${name} fetch does not use ${pin}`);
  }
}

const requiredArchiveCalls = {
  lame: "LAME_SHA256",
  opus: "OPUS_SHA256",
  ogg: "OGG_SHA256",
  vorbis: "VORBIS_SHA256",
  freetype: "FREETYPE_SHA256",
  fribidi: "FRIBIDI_SHA256",
  harfbuzz: "HARFBUZZ_SHA256",
  mbedtls: "MBEDTLS_SHA256",
  libass: "LIBASS_SHA256",
};
for (const [name, pin] of Object.entries(requiredArchiveCalls)) {
  const call = new RegExp(`fetch_tarball\\s+${name}\\s+[\\s\\S]*?"\\$${pin}"`, "m");
  if (!call.test(source)) {
    fail(`${name} archive fetch does not use ${pin}`);
  }
}

const helperRequirements = [
  'git -C "$dst" fetch --depth 1 --no-tags origin "$fetch_ref"',
  'git -C "$dst" cat-file -e "$commit^{commit}"',
  'git -C "$dst" checkout --detach --force "$commit"',
  'actual="$(git -C "$dst" rev-parse HEAD)"',
  'verify_sha256 "$archive" "$expected_sha256"',
  'verify_sha256 "$partial" "$expected_sha256"',
  'rm -rf "$dst"',
  'DEPS_CACHE_KEY=',
  'rm -rf "$DEPS_SYSROOT"',
  'X265_CROSS_COMPILE_ARM64="ON"',
  'X265_CROSS_COMPILE_ARM64="OFF"',
  '-DCROSS_COMPILE_ARM64="$X265_CROSS_COMPILE_ARM64"',
];
for (const requirement of helperRequirements) {
  if (!source.includes(requirement)) {
    fail(`missing verification/clean-cache guard: ${requirement}`);
  }
}

if (!process.exitCode) {
  process.stdout.write("FFmpeg source pins and cache guards verified.\n");
}
