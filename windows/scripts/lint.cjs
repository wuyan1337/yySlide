const { spawnSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const javascriptFiles = [
  "electron/main.cjs",
  "electron/preload.cjs",
  "electron/security.cjs",
  "scripts/lint.cjs",
  "scripts/start-electron.cjs"
];

for (const relativePath of javascriptFiles) {
  const result = spawnSync(process.execPath, ["--check", relativePath], {
    cwd: root,
    encoding: "utf8"
  });
  if (result.status !== 0) {
    process.stderr.write(result.stderr || result.stdout);
    process.exit(result.status || 1);
  }
}

const main = fs.readFileSync(path.join(root, "electron/main.cjs"), "utf8");
const packageJson = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
const indexHtml = fs.readFileSync(path.join(root, "index.html"), "utf8");

const failures = [];
if (/passwordSecret\)\s*\|\|\s*decryptSecret\(connection\.passphraseSecret/.test(main)) {
  failures.push("private-key passphrases must never answer keyboard-interactive prompts");
}
if (!main.includes("normalizeVaultSnapshot(snapshot)")) {
  failures.push("vault snapshots must be schema validated before import");
}
if (!main.includes("replaceFileFromTemp(localTempPath, localPath)")) {
  failures.push("downloads must be committed atomically");
}
if (packageJson.scripts.dist.includes("stamp:win-icon")) {
  failures.push("the executable must not be modified after electron-builder signs it");
}
if (packageJson.build?.electronFuses?.runAsNode !== false || packageJson.build?.electronFuses?.onlyLoadAppFromAsar !== true) {
  failures.push("release Electron fuses are not hardened");
}
if (!indexHtml.includes("https://yyslide.ccwu.cc") || indexHtml.includes("https://yyslide.yyhvh.com")) {
  failures.push("the production cloud CSP endpoint is stale");
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`lint: ${failure}`);
  }
  process.exit(1);
}
