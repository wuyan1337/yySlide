const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  accountScopeId,
  atomicWriteJson,
  createTransferBudget,
  normalizeVaultSnapshot,
  safeLocalName,
  safeStorageKey,
  uniqueRelativeChildName
} = require("../electron/security.cjs");

test("Windows download names avoid devices, traversal and case-insensitive collisions", () => {
  assert.equal(safeLocalName("CON"), "_CON");
  assert.equal(safeLocalName("../a:b?.txt"), ".._a_b_.txt");
  assert.equal(safeLocalName("file. "), "file");
  const used = new Set();
  assert.equal(uniqueRelativeChildName("", "Readme.txt", used), "Readme.txt");
  assert.equal(uniqueRelativeChildName("", "README.TXT", used), "README-2.TXT");
});

test("vault schema strips local paths and rejects oversized or unsupported payloads", () => {
  const snapshot = normalizeVaultSnapshot({
    version: 3,
    exportedAt: "2026-01-01T00:00:00.000Z",
    groups: [],
    deletedConnections: [],
    deletedGroups: [],
    connections: [{
      id: "../../outside",
      name: "server",
      host: "example.com",
      port: 22,
      username: "alice",
      authType: "key",
      privateKeyPath: "C:\\secret.key",
      privateKey: "key-data",
      updatedAt: "2026-01-01T00:00:00.000Z"
    }]
  });
  assert.equal(snapshot.connections[0].privateKeyPath, undefined);
  assert.match(safeStorageKey(snapshot.connections[0].id), /^[a-f0-9]{64}$/);
  assert.ok(!safeStorageKey(snapshot.connections[0].id).includes(".."));
  assert.throws(() => normalizeVaultSnapshot({ version: 99 }), /版本/);
  assert.throws(() => normalizeVaultSnapshot({ version: 3, connections: new Array(5001).fill({}) }), /too many/i);
});

test("account scopes are stable and separate by server and identity", () => {
  const first = accountScopeId("https://one.example/path/", { id: "user-1" });
  assert.equal(first, accountScopeId("https://one.example/path", { id: "user-1" }));
  assert.notEqual(first, accountScopeId("https://one.example", { id: "user-1" }));
  assert.notEqual(first, accountScopeId("https://two.example", { id: "user-1" }));
  assert.notEqual(first, accountScopeId("https://one.example", { id: "user-2" }));
});

test("recursive transfer budgets reject symlinks, depth, count and byte growth", () => {
  const makeBudget = () => createTransferBudget({ maxEntries: 2, maxDepth: 2, maxFileBytes: 10, maxTotalBytes: 12 });
  assert.throws(() => makeBudget().add({ depth: 0, symbolicLink: true, label: "link" }), /Symbolic links/);
  assert.throws(() => makeBudget().add({ depth: 3, label: "deep" }), /depth/);
  assert.throws(() => makeBudget().add({ depth: 0, size: 11, label: "large" }), /too large/);
  const countBudget = makeBudget();
  countBudget.add({ depth: 0 });
  countBudget.add({ depth: 1 });
  assert.throws(() => countBudget.add({ depth: 1 }), /Too many/);
  const byteBudget = makeBudget();
  byteBudget.add({ depth: 0, size: 7 });
  assert.throws(() => byteBudget.add({ depth: 0, size: 6 }), /total size/);
});

test("JSON writes replace complete files and leave no partial siblings", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "yyslide-security-"));
  try {
    const filePath = path.join(directory, "state.json");
    atomicWriteJson(filePath, { version: 1 });
    atomicWriteJson(filePath, { version: 2, value: "complete" });
    assert.deepEqual(JSON.parse(fs.readFileSync(filePath, "utf8")), { version: 2, value: "complete" });
    assert.deepEqual(fs.readdirSync(directory), ["state.json"]);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("release configuration pins the supported runtime and hardened build flow", () => {
  const root = path.join(__dirname, "..");
  const packageJson = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
  const appSource = fs.readFileSync(path.join(root, "src/App.tsx"), "utf8");
  const mainSource = fs.readFileSync(path.join(root, "electron/main.cjs"), "utf8");
  assert.match(packageJson.devDependencies.electron, /42\.7\.1/);
  assert.match(packageJson.devDependencies["@types/node"], /^\^24\./);
  assert.equal(packageJson.build.electronFuses.enableEmbeddedAsarIntegrityValidation, true);
  assert.equal(packageJson.build.win.publisherName, undefined);
  assert.equal(packageJson.build.win.signtoolOptions.publisherName, "yyslide");
  assert.ok(!packageJson.scripts.dist.includes("stamp"));
  assert.match(appSource, /DEFAULT_CLOUD_SERVER_URL = "https:\/\/yyslide\.ccwu\.cc"/);
  assert.match(appSource, /saved === "https:\/\/yyslide\.yyhvh\.com"/);
  assert.match(appSource, /current\?\.id === doc\.id/);
  assert.match(appSource, /requestId !== editorRequestRef\.current/);
  assert.match(mainSource, /event\.senderFrame === mainWindow\.webContents\.mainFrame/);
  assert.match(mainSource, /path\.join\(profileRoot\(\), "legacy-profile", legacyName\)/);
  assert.match(mainSource, /\["yySlide-downloads", "yySlide-drag", "yySlide-open"\]/);
});
