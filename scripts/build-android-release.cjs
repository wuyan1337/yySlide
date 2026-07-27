const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

const root = path.resolve(__dirname, "..");
const androidRoot = path.join(root, "android", "yyslide-android");
const releaseDir = path.join(root, "releases", "android");
const buildType = process.argv.includes("--release") ? "release" : "debug";
const outputDir = buildType === "release" ? releaseDir : path.join(releaseDir, "debug");

function firstExisting(paths) {
  return paths.find((item) => item && fs.existsSync(item));
}

function findJavaHome() {
  if (process.env.JAVA_HOME && fs.existsSync(path.join(process.env.JAVA_HOME, "bin", "java.exe"))) {
    return process.env.JAVA_HOME;
  }
  return firstExisting([
    path.join(process.env.ProgramFiles || "C:\\Program Files", "Android", "Android Studio", "jbr"),
    path.join(process.env.ProgramFiles || "C:\\Program Files", "Android", "Android Studio", "jre")
  ]);
}

function findAndroidHome() {
  if (process.env.ANDROID_HOME && fs.existsSync(process.env.ANDROID_HOME)) {
    return process.env.ANDROID_HOME;
  }
  if (process.env.ANDROID_SDK_ROOT && fs.existsSync(process.env.ANDROID_SDK_ROOT)) {
    return process.env.ANDROID_SDK_ROOT;
  }
  return firstExisting([
    path.join(os.homedir(), "AppData", "Local", "Android", "Sdk"),
    path.join(process.env.LOCALAPPDATA || "", "Android", "Sdk")
  ]);
}

function latestApk(variant) {
  const dir = path.join(androidRoot, "app", "build", "outputs", "apk", variant, buildType);
  const files = fs.existsSync(dir)
    ? fs.readdirSync(dir)
      .filter((name) => name.toLowerCase().endsWith(".apk"))
      .map((name) => path.join(dir, name))
    : [];
  files.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  return files[0];
}

const javaHome = findJavaHome();
const androidHome = findAndroidHome();
if (buildType === "release") {
  const keystorePath = path.join(androidRoot, "yyslide-release.jks");
  const missing = ["KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"].filter((name) => !process.env[name]);
  if (!fs.existsSync(keystorePath) || missing.length) {
    console.error("Android release signing is not configured.");
    console.error(`Expected keystore: ${path.relative(root, keystorePath)}`);
    if (missing.length) {
      console.error(`Missing environment variables: ${missing.join(", ")}`);
    }
    process.exit(1);
  }
}
const env = {
  ...process.env,
  ...(javaHome ? { JAVA_HOME: javaHome } : {}),
  ...(androidHome ? { ANDROID_HOME: androidHome, ANDROID_SDK_ROOT: androidHome } : {})
};
if (javaHome) {
  env.PATH = `${path.join(javaHome, "bin")}${path.delimiter}${env.PATH || ""}`;
}
if (androidHome) {
  env.PATH = `${path.join(androidHome, "platform-tools")}${path.delimiter}${env.PATH || ""}`;
}

const gradleSuffix = buildType === "release" ? "Release" : "Debug";
const gradleArgs = [`:app:assembleX64${gradleSuffix}`, `:app:assembleArm64${gradleSuffix}`, "--console=plain"];
const command = process.platform === "win32" ? "cmd.exe" : "./gradlew";
const args = process.platform === "win32"
  ? ["/d", "/s", "/c", "gradlew.bat", ...gradleArgs]
  : gradleArgs;
const result = spawnSync(command, args, { cwd: androidRoot, env, stdio: "inherit" });

if (result.error) {
  throw result.error;
}
if (result.status !== 0) {
  process.exit(result.status || 1);
}

fs.mkdirSync(outputDir, { recursive: true });

for (const variant of ["x64", "arm64"]) {
  const source = latestApk(variant);
  if (!source) {
    throw new Error(`Cannot find ${variant} ${buildType} APK.`);
  }
  const target = path.join(outputDir, path.basename(source));
  const temporaryTarget = target + "." + process.pid + ".tmp";
  const backupTarget = target + "." + process.pid + ".previous";
  try {
    fs.copyFileSync(source, temporaryTarget);
    if (fs.existsSync(target)) {
      fs.renameSync(target, backupTarget);
    }
    fs.renameSync(temporaryTarget, target);
    fs.rmSync(backupTarget, { force: true });
  } catch (error) {
    if (!fs.existsSync(target) && fs.existsSync(backupTarget)) {
      fs.renameSync(backupTarget, target);
    }
    throw error;
  } finally {
    fs.rmSync(temporaryTarget, { force: true });
  }
  console.log(`Copied ${variant} ${buildType} APK: ${path.relative(root, target)}`);
}
