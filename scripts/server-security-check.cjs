"use strict";

const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn, spawnSync } = require("child_process");
const { DatabaseSync } = require("node:sqlite");

const root = path.resolve(__dirname, "..");
const serverPath = path.join(root, "server", "yycloud.cjs");
const restorePath = path.join(root, "scripts", "decrypt-legacy-archive.cjs");

function base32Decode(input) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of String(input).toUpperCase().replace(/=+$/g, "")) {
    const value = alphabet.indexOf(char);
    if (value >= 0) {
      bits += value.toString(2).padStart(5, "0");
    }
  }
  const bytes = [];
  for (let index = 0; index + 8 <= bits.length; index += 8) {
    bytes.push(parseInt(bits.slice(index, index + 8), 2));
  }
  return Buffer.from(bytes);
}

function hotp(secret, counter) {
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const hmac = crypto.createHmac("sha1", base32Decode(secret)).update(message).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const code = ((hmac[offset] & 0x7f) << 24)
    | ((hmac[offset + 1] & 0xff) << 16)
    | ((hmac[offset + 2] & 0xff) << 8)
    | (hmac[offset + 3] & 0xff);
  return String(code % 1000000).padStart(6, "0");
}

function totp(secret) {
  return hotp(secret, Math.floor(Date.now() / 30000));
}

function dummyBox(seed, ciphertextBytes) {
  return {
    type: "aes-256-gcm",
    salt: Buffer.from("salt-" + seed).toString("base64"),
    iv: Buffer.from(("iv-" + seed).padEnd(12, ".").slice(0, 12)).toString("base64"),
    ciphertext: Buffer.alloc(ciphertextBytes || 16, seed.charCodeAt(0) || 65).toString("base64"),
    tag: Buffer.from(("tag-" + seed).padEnd(16, ".").slice(0, 16)).toString("base64")
  };
}

function dummyVault(seed, ciphertextBytes) {
  const box = dummyBox(seed, ciphertextBytes);
  delete box.salt;
  return box;
}

function recoveryKeys(seed, count) {
  return Array.from({ length: count == null ? 10 : count }, (_, index) => ({
    code: ("A" + seed + String(index).padStart(3, "0")).slice(0, 5).padEnd(5, "X")
      + "-"
      + ("B" + seed + String(index).padStart(3, "0")).slice(0, 5).padEnd(5, "Y"),
    keyBox: dummyBox(seed + "-recovery-" + index)
  }));
}

function availablePort() {
  return 20000 + Math.floor(Math.random() * 20000);
}

function childEnvironment(tempDir, port, extra) {
  const env = {
    ...process.env,
    NODE_ENV: "test",
    YYSLIDE_CLOUD_HOST: "127.0.0.1",
    YYSLIDE_CLOUD_PORT: String(port),
    YYSLIDE_CLOUD_DATA: tempDir,
    YYSLIDE_CLOUD_DB: path.join(tempDir, "yycloud-test.sqlite"),
    YYSLIDE_SERVER_SECRET: "server-security-check-secret",
    YYSLIDE_INSECURE_DEV: "0",
    ...(extra || {})
  };
  delete env.YYSLIDE_SERVER_SECRET_FILE;
  return env;
}

async function api(baseUrl, method, pathname, body, token) {
  const response = await fetch(baseUrl + pathname, {
    method,
    headers: {
      ...(body == null ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: "Bearer " + token } : {})
    },
    body: body == null ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  let data = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { raw: text };
    }
  }
  return { response, status: response.status, data };
}

async function waitForServer(child, baseUrl, output) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (child.exitCode != null) {
      throw new Error("server exited early with " + child.exitCode + "\n" + output());
    }
    try {
      const result = await api(baseUrl, "GET", "/health");
      if (result.status === 200) {
        return;
      }
    } catch {
      // Retry while the listener starts.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("server did not become ready\n" + output());
}

function stopChild(child) {
  if (child.exitCode != null) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      if (child.exitCode == null) {
        child.kill();
      }
      resolve();
    }, 6000);
    child.once("exit", () => {
      clearTimeout(timer);
      resolve();
    });
    child.kill();
  });
}

function removeTempDir(tempDir) {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      fs.rmSync(tempDir, { recursive: true, force: true });
      return;
    } catch (error) {
      if (attempt === 4) {
        throw error;
      }
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 100);
    }
  }
}

function testProductionConfiguration() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "yyslide-config-"));
  try {
    const env = {
      ...process.env,
      NODE_ENV: "production",
      YYSLIDE_CLOUD_DATA: tempDir,
      YYSLIDE_CLOUD_DB: path.join(tempDir, "config.sqlite"),
      YYSLIDE_SERVER_SECRET: "short",
      YYSLIDE_INSECURE_DEV: "0"
    };
    delete env.YYSLIDE_SERVER_SECRET_FILE;
    const result = spawnSync(process.execPath, [serverPath, "--migrate-only"], {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 10000
    });
    assert.notStrictEqual(result.status, 0, "production must reject a short server secret");
    assert.match(result.stderr, /at least 32 bytes/);
    assert.strictEqual(fs.existsSync(path.join(tempDir, "config.sqlite")), false,
      "invalid production configuration must fail before opening the database");

    env.YYSLIDE_SERVER_SECRET = "a-production-secret-that-is-longer-than-thirty-two-bytes";
    env.YYSLIDE_CORS_ORIGIN = "*";
    const wildcardCors = spawnSync(process.execPath, [serverPath, "--migrate-only"], {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 10000
    });
    assert.notStrictEqual(wildcardCors.status, 0, "production must reject wildcard CORS");
    assert.match(wildcardCors.stderr, /cannot contain \*/);
    assert.strictEqual(fs.existsSync(path.join(tempDir, "config.sqlite")), false);
  } finally {
    removeTempDir(tempDir);
  }
}

async function testServerBoundaries() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "yyslide-server-"));
  const port = availablePort();
  const baseUrl = "http://127.0.0.1:" + port;
  let outputText = "";
  const child = spawn(process.execPath, [serverPath], {
    cwd: root,
    env: childEnvironment(tempDir, port, {
      YYSLIDE_MAX_SESSIONS_PER_USER: "2",
      YYSLIDE_MAX_SSE_PER_USER: "2",
      YYSLIDE_MAX_VAULT_BYTES: "1024"
    }),
    stdio: ["ignore", "pipe", "pipe"]
  });
  child.stdout.on("data", (chunk) => {
    outputText += chunk.toString();
  });
  child.stderr.on("data", (chunk) => {
    outputText += chunk.toString();
  });

  const abortControllers = [];
  try {
    await waitForServer(child, baseUrl, () => outputText);
    const unexpected = await api(baseUrl, "POST", "/v1/auth/register/start", {
      username: "security_user",
      ignored: true
    });
    assert.strictEqual(unexpected.status, 400);

    const start = await api(baseUrl, "POST", "/v1/auth/register/start", {
      username: "security_user"
    });
    assert.strictEqual(start.status, 200);

    const invalidStartedAt = Date.now();
    const tooManyRecoveryKeys = await api(baseUrl, "POST", "/v1/auth/register/finish", {
      username: "security_user",
      password: "TestPass123",
      totpCode: totp(start.data.totpSecret),
      wrappedVaultKey: dummyBox("wrapped"),
      recoveryKeys: recoveryKeys("Q", 11)
    });
    assert.strictEqual(tooManyRecoveryKeys.status, 400);
    assert.ok(Date.now() - invalidStartedAt < 2000,
      "invalid recovery-key counts must be rejected before doing unbounded scrypt work");

    const finish = await api(baseUrl, "POST", "/v1/auth/register/finish", {
      username: "security_user",
      password: "TestPass123",
      totpCode: totp(start.data.totpSecret),
      wrappedVaultKey: dummyBox("wrapped"),
      recoveryKeys: recoveryKeys("R", 10)
    });
    assert.strictEqual(finish.status, 200, JSON.stringify(finish.data));
    const originalToken = finish.data.token;

    const firstMe = await api(baseUrl, "GET", "/v1/me", null, originalToken);
    const secondMe = await api(baseUrl, "GET", "/v1/me", null, originalToken);
    assert.strictEqual(firstMe.status, 200);
    assert.strictEqual(secondMe.status, 200);
    assert.deepStrictEqual(
      firstMe.data.devices.map((item) => item.lastUsedAt),
      secondMe.data.devices.map((item) => item.lastUsedAt),
      "lastUsedAt should be throttled instead of rewriting the session on every request"
    );

    const loginOne = await api(baseUrl, "POST", "/v1/auth/login", {
      username: "security_user",
      password: "TestPass123",
      totpCode: totp(start.data.totpSecret)
    });
    const loginTwo = await api(baseUrl, "POST", "/v1/auth/login", {
      username: "security_user",
      password: "TestPass123",
      totpCode: totp(start.data.totpSecret)
    });
    assert.strictEqual(loginOne.status, 200);
    assert.strictEqual(loginTwo.status, 200);
    const activeToken = loginTwo.data.token;
    const cappedDevices = await api(baseUrl, "GET", "/v1/me", null, activeToken);
    assert.strictEqual(cappedDevices.status, 200);
    assert.strictEqual(cappedDevices.data.devices.length, 2,
      "the server must cap active sessions per user");
    const evicted = await api(baseUrl, "GET", "/v1/me", null, originalToken);
    assert.strictEqual(evicted.status, 401);

    const firstVault = await api(baseUrl, "PUT", "/v1/vault", {
      vault: dummyVault("small"),
      baseUpdatedAt: null
    }, activeToken);
    assert.strictEqual(firstVault.status, 200, JSON.stringify(firstVault.data));
    const stale = await api(baseUrl, "PUT", "/v1/vault", {
      vault: dummyVault("stale"),
      baseUpdatedAt: null
    }, activeToken);
    assert.strictEqual(stale.status, 409);
    assert.ok(stale.data.vault.updatedAt);
    const oversizedVault = await api(baseUrl, "PUT", "/v1/vault", {
      vault: dummyVault("large", 1100),
      baseUpdatedAt: firstVault.data.vault.updatedAt
    }, activeToken);
    assert.strictEqual(oversizedVault.status, 413);

    for (let index = 0; index < 2; index += 1) {
      const controller = new AbortController();
      abortControllers.push(controller);
      const response = await fetch(baseUrl + "/v1/vault/events", {
        headers: { Authorization: "Bearer " + activeToken },
        signal: controller.signal
      });
      assert.strictEqual(response.status, 200);
    }
    const thirdEventStream = await fetch(baseUrl + "/v1/vault/events", {
      headers: { Authorization: "Bearer " + activeToken }
    });
    assert.strictEqual(thirdEventStream.status, 429,
      "SSE connections must be capped before response headers are committed");
    await thirdEventStream.text();
    for (const controller of abortControllers.splice(0)) {
      controller.abort();
    }
    await new Promise((resolve) => setTimeout(resolve, 100));

    const malformed = await fetch(baseUrl + "/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{"
    });
    assert.strictEqual(malformed.status, 400);
    const stillHealthy = await api(baseUrl, "GET", "/health");
    assert.strictEqual(stillHealthy.status, 200,
      "a rejected request must not become an unhandled promise rejection");

    const databaseHandle = new DatabaseSync(path.join(tempDir, "yycloud-test.sqlite"));
    databaseHandle.prepare("UPDATE users SET totp_secret = ? WHERE username = ?")
      .run(JSON.stringify({
        type: "aes-256-gcm",
        iv: Buffer.alloc(12).toString("base64"),
        ciphertext: Buffer.from("corrupt").toString("base64"),
        tag: Buffer.alloc(16).toString("base64")
      }), "security_user");
    databaseHandle.close();
    const internalFailure = await api(baseUrl, "POST", "/v1/auth/login?access_token=do-not-log-query-marker", {
      username: "security_user",
      password: "TestPass123",
      totpCode: "123456"
    });
    assert.strictEqual(internalFailure.status, 500);
    assert.strictEqual(internalFailure.data.error, "服务器内部错误。");
    assert.ok(internalFailure.data.requestId);
    assert.doesNotMatch(JSON.stringify(internalFailure.data), /authenticate|decipher|Unsupported|stack/i);
    assert.doesNotMatch(outputText, /do-not-log-query-marker/,
      "internal-error logs must not copy query strings that may contain credentials");
    assert.strictEqual(outputText.includes(tempDir), false,
      "development logs must not expose the absolute database path");
  } finally {
    for (const controller of abortControllers) {
      controller.abort();
    }
    await stopChild(child);
    removeTempDir(tempDir);
  }
}

function testEncryptedLegacyMigration() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "yyslide-migrate-"));
  try {
    const secretFile = path.join(tempDir, "server-secret");
    const serverSecret = "migration-secret-that-is-long-enough-for-production";
    const plaintextTotp = "JBSWY3DPEHPK3PXP";
    fs.writeFileSync(secretFile, serverSecret + "\n", { mode: 0o600 });
    const legacy = {
      users: [{
        id: "legacy-user-id",
        username: "legacy_user",
        passwordHash: dummyPasswordHashForMigration(),
        totpSecret: plaintextTotp,
        wrappedVaultKey: dummyBox("legacy-wrapped"),
        vault: null,
        sessions: [],
        recoveryCodes: [],
        createdAt: "2026-01-01T00:00:00.000Z"
      }],
      pendingRegistrations: [],
      pendingResets: []
    };
    fs.writeFileSync(path.join(tempDir, "db.json"), JSON.stringify(legacy, null, 2), { mode: 0o600 });
    const env = {
      ...process.env,
      NODE_ENV: "production",
      YYSLIDE_CLOUD_DATA: tempDir,
      YYSLIDE_CLOUD_DB: path.join(tempDir, "migrated.sqlite"),
      YYSLIDE_SERVER_SECRET_FILE: secretFile,
      YYSLIDE_INSECURE_DEV: "0"
    };
    delete env.YYSLIDE_SERVER_SECRET;
    const result = spawnSync(process.execPath, [serverPath, "--migrate-only"], {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 30000
    });
    assert.strictEqual(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /"plaintextTotpValues": 0/);
    assert.strictEqual(fs.existsSync(path.join(tempDir, "db.json")), false,
      "the plaintext legacy JSON must be removed after its encrypted archive is durable");
    const archives = fs.readdirSync(tempDir).filter((name) => /^db\.json\.migrated-.*\.enc$/.test(name));
    assert.strictEqual(archives.length, 1);
    const archivePath = path.join(tempDir, archives[0]);
    const archiveBytes = fs.readFileSync(archivePath);
    assert.strictEqual(archiveBytes.includes(Buffer.from(plaintextTotp)), false);
    assert.strictEqual(archiveBytes.includes(Buffer.from("legacy_user")), false);

    const plaintextDatabase = new DatabaseSync(path.join(tempDir, "migrated.sqlite"));
    plaintextDatabase.prepare("UPDATE users SET totp_secret = ? WHERE username = ?")
      .run(plaintextTotp, "legacy_user");
    plaintextDatabase.close();
    const encryptExisting = spawnSync(process.execPath, [serverPath, "--migrate-only"], {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 30000
    });
    assert.strictEqual(encryptExisting.status, 0, encryptExisting.stdout + encryptExisting.stderr);
    assert.match(encryptExisting.stdout, /"encryptedTotpValues": 1/);
    assert.match(encryptExisting.stdout, /"plaintextTotpValues": 0/);

    for (const candidate of [
      path.join(tempDir, "migrated.sqlite"),
      path.join(tempDir, "migrated.sqlite-wal"),
      path.join(tempDir, "migrated.sqlite-shm")
    ]) {
      if (fs.existsSync(candidate)) {
        assert.strictEqual(fs.readFileSync(candidate).includes(Buffer.from(plaintextTotp)), false,
          "SQLite files must not retain plaintext TOTP values after secure migration");
      }
    }
    const databaseHandle = new DatabaseSync(path.join(tempDir, "migrated.sqlite"), { readOnly: true });
    const row = databaseHandle.prepare("SELECT totp_secret FROM users WHERE username = ?").get("legacy_user");
    databaseHandle.close();
    assert.strictEqual(JSON.parse(row.totp_secret).type, "aes-256-gcm");

    const restoredPath = path.join(tempDir, "restored-db.json");
    const restore = spawnSync(process.execPath, [restorePath, archivePath, restoredPath], {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 10000
    });
    assert.strictEqual(restore.status, 0, restore.stdout + restore.stderr);
    const restored = JSON.parse(fs.readFileSync(restoredPath, "utf8"));
    assert.strictEqual(restored.users[0].totpSecret, plaintextTotp,
      "the encrypted legacy archive must remain recoverable with the server secret");

    const conflictingLegacyPath = path.join(tempDir, "db.json");
    const conflictingLegacy = {
      users: [{
        id: "legacy-user-id",
        username: "legacy_user",
        passwordHash: dummyPasswordHashForMigration(),
        totpSecret: "AAAAAAAAAAAAAAAA",
        wrappedVaultKey: dummyBox("conflicting-wrapped"),
        vault: null,
        sessions: [],
        recoveryCodes: [],
        createdAt: "2026-01-01T00:00:00.000Z"
      }],
      pendingRegistrations: [],
      pendingResets: []
    };
    fs.writeFileSync(conflictingLegacyPath, JSON.stringify(conflictingLegacy), { mode: 0o600 });
    const conflict = spawnSync(process.execPath, [serverPath, "--migrate-only"], {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 30000
    });
    assert.notStrictEqual(conflict.status, 0,
      "a conflicting legacy JSON file must stop production migration");
    assert.deepStrictEqual(JSON.parse(fs.readFileSync(conflictingLegacyPath, "utf8")), conflictingLegacy,
      "unverified legacy user data must remain untouched for manual reconciliation");
    assert.strictEqual(
      fs.readdirSync(tempDir).filter((name) => /^db\.json\.migrated-.*\.enc$/.test(name)).length,
      1,
      "a same-name but content-mismatched legacy database must not be archived as covered"
    );
  } finally {
    removeTempDir(tempDir);
  }
}

function testInterruptedLegacyRecovery() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "yyslide-remnant-"));
  try {
    const secretFile = path.join(tempDir, "server-secret");
    fs.writeFileSync(secretFile, "migration-secret-that-is-long-enough-for-production\n", { mode: 0o600 });
    const legacy = {
      users: [{
        id: "recovered-user-id",
        username: "recovered_user",
        passwordHash: dummyPasswordHashForMigration(),
        totpSecret: "JBSWY3DPEHPK3PXP",
        wrappedVaultKey: dummyBox("recovered-wrapped"),
        vault: null,
        sessions: [],
        recoveryCodes: [],
        createdAt: "2026-01-01T00:00:00.000Z"
      }],
      pendingRegistrations: [],
      pendingResets: []
    };
    const remnant = path.join(tempDir, "db.json.migration-plaintext-delete-test-remnant");
    fs.writeFileSync(remnant, JSON.stringify(legacy), { mode: 0o600 });
    const env = {
      ...process.env,
      NODE_ENV: "production",
      YYSLIDE_CLOUD_DATA: tempDir,
      YYSLIDE_CLOUD_DB: path.join(tempDir, "recovered.sqlite"),
      YYSLIDE_SERVER_SECRET_FILE: secretFile,
      YYSLIDE_INSECURE_DEV: "0"
    };
    delete env.YYSLIDE_SERVER_SECRET;
    const result = spawnSync(process.execPath, [serverPath, "--migrate-only"], {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 30000
    });
    assert.strictEqual(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /"users": 1/,
      "an interrupted plaintext staging file must be recovered and migrated, not erased");
    assert.strictEqual(fs.existsSync(remnant), false);
    assert.strictEqual(fs.existsSync(path.join(tempDir, "db.json")), false);
    assert.strictEqual(
      fs.readdirSync(tempDir).filter((name) => /^db\.json\.migrated-.*\.enc$/.test(name)).length,
      1
    );
  } finally {
    removeTempDir(tempDir);
  }
}

function dummyPasswordHashForMigration() {
  return {
    alg: "scrypt",
    salt: "legacy-salt",
    hash: "A".repeat(86),
    N: 16384,
    r: 8,
    p: 1
  };
}

async function main() {
  testProductionConfiguration();
  await testServerBoundaries();
  testEncryptedLegacyMigration();
  testInterruptedLegacyRecovery();
  console.log("server security checks ok");
}

main().catch((error) => {
  console.error(error.stack || error.message || String(error));
  process.exit(1);
});
