"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { isDeepStrictEqual } = require("util");
const { DatabaseSync } = require("node:sqlite");

process.umask(0o077);

const cliArgs = new Set(process.argv.slice(2));
const migrateOnly = cliArgs.has("--migrate-only");
const isProduction = process.env.NODE_ENV === "production";

function configurationError(message) {
  console.error("yySlide Cloud configuration error: " + message);
  process.exit(1);
}

function integerEnv(name, fallback, minimum, maximum) {
  const raw = process.env[name];
  if (raw == null || raw === "") {
    return fallback;
  }
  if (!/^\d+$/.test(raw)) {
    configurationError(name + " must be an integer.");
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    configurationError(name + " must be between " + minimum + " and " + maximum + ".");
  }
  return value;
}

function loadServerSecret() {
  const inlineSecret = process.env.YYSLIDE_SERVER_SECRET || "";
  const secretFile = process.env.YYSLIDE_SERVER_SECRET_FILE || "";
  if (inlineSecret && secretFile) {
    configurationError("set only one of YYSLIDE_SERVER_SECRET and YYSLIDE_SERVER_SECRET_FILE.");
  }
  if (!secretFile) {
    return inlineSecret;
  }
  let stat;
  try {
    stat = fs.statSync(secretFile);
  } catch {
    configurationError("YYSLIDE_SERVER_SECRET_FILE cannot be read.");
  }
  if (!stat.isFile() || stat.size < 1 || stat.size > 4096) {
    configurationError("YYSLIDE_SERVER_SECRET_FILE must be a regular file no larger than 4096 bytes.");
  }
  if (isProduction && process.platform !== "win32" && (stat.mode & 0o077) !== 0) {
    configurationError("YYSLIDE_SERVER_SECRET_FILE must not be readable or writable by group/other users (chmod 600).");
  }
  try {
    return fs.readFileSync(secretFile, "utf8").trim();
  } catch {
    configurationError("YYSLIDE_SERVER_SECRET_FILE cannot be read.");
  }
}

const nodeMajor = Number(process.versions.node.split(".")[0]);
if (!Number.isInteger(nodeMajor) || nodeMajor < 24) {
  configurationError("Node.js 24 or newer is required.");
}

const unknownArgs = [...cliArgs].filter((arg) => arg !== "--migrate-only");
if (unknownArgs.length) {
  configurationError("unknown argument: " + unknownArgs[0]);
}

if (isProduction && !process.env.YYSLIDE_CLOUD_DATA) {
  configurationError("YYSLIDE_CLOUD_DATA must be set explicitly in production.");
}
if (isProduction && !path.isAbsolute(process.env.YYSLIDE_CLOUD_DATA)) {
  configurationError("YYSLIDE_CLOUD_DATA must be an absolute path in production.");
}
if (isProduction && process.env.YYSLIDE_CLOUD_DB && !path.isAbsolute(process.env.YYSLIDE_CLOUD_DB)) {
  configurationError("YYSLIDE_CLOUD_DB must be an absolute path in production.");
}
if (isProduction && process.env.YYSLIDE_SERVER_SECRET_FILE
    && !path.isAbsolute(process.env.YYSLIDE_SERVER_SECRET_FILE)) {
  configurationError("YYSLIDE_SERVER_SECRET_FILE must be an absolute path in production.");
}

const dataDir = path.resolve(process.env.YYSLIDE_CLOUD_DATA || path.join(__dirname, "data"));
const legacyJsonPath = path.join(dataDir, "db.json");
const dbPath = path.resolve(process.env.YYSLIDE_CLOUD_DB || path.join(dataDir, "yycloud.sqlite"));
const host = process.env.YYSLIDE_CLOUD_HOST || "127.0.0.1";
const port = integerEnv("YYSLIDE_CLOUD_PORT", 8787, 1, 65535);
const sessionDays = integerEnv("YYSLIDE_SESSION_DAYS", 30, 1, 365);
const sessionTtlMs = sessionDays * 24 * 60 * 60 * 1000;
const maxUsers = integerEnv("YYSLIDE_MAX_USERS", 10000, 1, 1000000);
const maxPendingRegistrations = integerEnv("YYSLIDE_MAX_PENDING_REGISTRATIONS", 5000, 1, 100000);
const maxSessionsPerUser = integerEnv("YYSLIDE_MAX_SESSIONS_PER_USER", 20, 1, 100);
const maxVaultBytes = integerEnv("YYSLIDE_MAX_VAULT_BYTES", 2 * 1024 * 1024, 1024, 32 * 1024 * 1024);
const maxSsePerUser = integerEnv("YYSLIDE_MAX_SSE_PER_USER", 5, 1, 50);
const maxSsePerIp = integerEnv("YYSLIDE_MAX_SSE_PER_IP", 20, 1, 500);
const maxSseTotal = integerEnv("YYSLIDE_MAX_SSE_TOTAL", 1000, 1, 100000);
const maxSseBufferBytes = integerEnv("YYSLIDE_MAX_SSE_BUFFER_BYTES", 64 * 1024, 4096, 1024 * 1024);
const maxRateBuckets = integerEnv("YYSLIDE_MAX_RATE_BUCKETS", 10000, 100, 1000000);
const scryptConcurrency = integerEnv("YYSLIDE_SCRYPT_CONCURRENCY", 2, 1, 16);
const scryptQueueLimit = integerEnv("YYSLIDE_SCRYPT_QUEUE_LIMIT", 32, 1, 1000);
const serverSecret = loadServerSecret();
const trustedProxyValue = process.env.YYSLIDE_TRUST_PROXY || "0";
if (trustedProxyValue !== "0" && trustedProxyValue !== "1") {
  configurationError("YYSLIDE_TRUST_PROXY must be 0 or 1.");
}
const trustedProxy = trustedProxyValue === "1";
const corsOrigins = String(process.env.YYSLIDE_CORS_ORIGIN || "")
  .split(",")
  .map((item) => item.trim())
  .filter(Boolean);

if (isProduction && process.env.YYSLIDE_INSECURE_DEV === "1") {
  configurationError("YYSLIDE_INSECURE_DEV cannot be enabled in production.");
}
if (!serverSecret && process.env.YYSLIDE_INSECURE_DEV !== "1") {
  configurationError("YYSLIDE_SERVER_SECRET or YYSLIDE_SERVER_SECRET_FILE is required. For throwaway local testing only, set YYSLIDE_INSECURE_DEV=1.");
}
if (isProduction && Buffer.byteLength(serverSecret, "utf8") < 32) {
  configurationError("the production server secret must contain at least 32 bytes.");
}
if (isProduction && corsOrigins.includes("*")) {
  configurationError("YYSLIDE_CORS_ORIGIN cannot contain * in production.");
}
for (const origin of corsOrigins) {
  if (origin === "*") {
    continue;
  }
  let parsedOrigin;
  try {
    parsedOrigin = new URL(origin);
  } catch {
    configurationError("YYSLIDE_CORS_ORIGIN contains an invalid origin.");
  }
  if (parsedOrigin.origin !== origin || parsedOrigin.username || parsedOrigin.password
      || isProduction && parsedOrigin.protocol !== "https:") {
    configurationError("YYSLIDE_CORS_ORIGIN entries must be exact HTTPS origins in production.");
  }
}
if (migrateOnly && !serverSecret) {
  configurationError("--migrate-only requires a server secret so plaintext TOTP values can be encrypted.");
}
if (!serverSecret) {
  console.warn("WARNING: YYSLIDE_INSECURE_DEV=1 is enabled. TOTP secrets will remain plaintext.");
}

const MAX_RECOVERY_KEYS = 10;
const PENDING_TTL_MS = 10 * 60 * 1000;
const SESSION_TOUCH_INTERVAL_MS = 5 * 60 * 1000;
const serverSecretKeyValue = serverSecret
  ? crypto.createHash("sha256").update(serverSecret).digest()
  : null;
const defaultScryptParams = {
  N: 16384,
  r: 8,
  p: 1,
  maxmem: 64 * 1024 * 1024
};
const dummyPasswordHash = {
  alg: "scrypt",
  salt: "yyslide-invalid-user",
  hash: "A".repeat(86),
  N: defaultScryptParams.N,
  r: defaultScryptParams.r,
  p: defaultScryptParams.p
};

let sqlDb = null;
let migrationSummary = { legacy: "none", encryptedTotpValues: 0 };
let lastCleanupAt = 0;
let activeScryptJobs = 0;
const scryptWaiters = [];
const rateBuckets = new Map();
const vaultEventClients = new Map();
const sseCountsByIp = new Map();
let totalSseClients = 0;

function nowIso() {
  return new Date().toISOString();
}

function nextIsoAfter(previous) {
  const previousMs = Date.parse(previous || "");
  return new Date(Math.max(Date.now(), Number.isFinite(previousMs) ? previousMs + 1 : 0)).toISOString();
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value)
    && (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
}

function parseJson(value, fallback) {
  if (!value) {
    return fallback;
  }
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function stringifyJson(value) {
  return JSON.stringify(value == null ? null : value);
}

function hardenPath(target, mode) {
  if (process.platform === "win32") {
    return;
  }
  try {
    fs.chmodSync(target, mode);
  } catch (error) {
    if (isProduction) {
      throw error;
    }
    console.warn("Could not harden local data permissions.");
  }
}

function ensureDataDir() {
  fs.mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  fs.mkdirSync(path.dirname(dbPath), { recursive: true, mode: 0o700 });
  hardenPath(dataDir, 0o700);
  if (path.dirname(dbPath) !== dataDir) {
    hardenPath(path.dirname(dbPath), 0o700);
  }
}

function encryptBox(key, plaintext, aad) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(aad));
  const encrypted = Buffer.concat([cipher.update(String(plaintext), "utf8"), cipher.final()]);
  return {
    type: "aes-256-gcm",
    iv: iv.toString("base64"),
    ciphertext: encrypted.toString("base64"),
    tag: cipher.getAuthTag().toString("base64")
  };
}

function decryptBox(key, box, aad) {
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(box.iv, "base64"));
  decipher.setAAD(Buffer.from(aad));
  decipher.setAuthTag(Buffer.from(box.tag, "base64"));
  return Buffer.concat([
    decipher.update(Buffer.from(box.ciphertext, "base64")),
    decipher.final()
  ]).toString("utf8");
}

function encodeTotpSecret(secret) {
  if (!serverSecretKeyValue) {
    return String(secret || "");
  }
  return stringifyJson(encryptBox(serverSecretKeyValue, secret, "yySlide:totp-secret"));
}

function decodeTotpSecret(value) {
  const parsed = parseJson(value, null);
  if (!parsed || parsed.type !== "aes-256-gcm") {
    return String(value || "");
  }
  if (!serverSecretKeyValue) {
    throw new Error("encrypted TOTP secret cannot be opened without the server secret");
  }
  return decryptBox(serverSecretKeyValue, parsed, "yySlide:totp-secret");
}

function runTransaction(databaseHandle, action) {
  databaseHandle.exec("BEGIN IMMEDIATE");
  try {
    const result = action();
    databaseHandle.exec("COMMIT");
    return result;
  } catch (error) {
    try {
      databaseHandle.exec("ROLLBACK");
    } catch {
      // Preserve the original error.
    }
    throw error;
  }
}

function ensureSchema(databaseHandle) {
  databaseHandle.exec([
    "PRAGMA journal_mode = WAL;",
    "PRAGMA foreign_keys = ON;",
    "PRAGMA busy_timeout = 5000;",
    "CREATE TABLE IF NOT EXISTS meta (",
    "  key TEXT PRIMARY KEY,",
    "  value TEXT NOT NULL",
    ");",
    "CREATE TABLE IF NOT EXISTS users (",
    "  id TEXT PRIMARY KEY,",
    "  username TEXT NOT NULL UNIQUE,",
    "  password_hash TEXT NOT NULL,",
    "  totp_secret TEXT NOT NULL,",
    "  wrapped_vault_key TEXT NOT NULL,",
    "  vault TEXT,",
    "  created_at TEXT NOT NULL",
    ");",
    "CREATE TABLE IF NOT EXISTS sessions (",
    "  id TEXT PRIMARY KEY,",
    "  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,",
    "  token_hash TEXT NOT NULL UNIQUE,",
    "  created_at TEXT NOT NULL,",
    "  last_used_at TEXT NOT NULL,",
    "  expires_at TEXT NOT NULL,",
    "  user_agent TEXT NOT NULL DEFAULT '',",
    "  ip TEXT NOT NULL DEFAULT ''",
    ");",
    "CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id);",
    "CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions(expires_at);",
    "CREATE TABLE IF NOT EXISTS recovery_codes (",
    "  id INTEGER PRIMARY KEY AUTOINCREMENT,",
    "  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,",
    "  code_order INTEGER NOT NULL,",
    "  hash TEXT NOT NULL,",
    "  key_box TEXT NOT NULL,",
    "  used_at TEXT",
    ");",
    "CREATE INDEX IF NOT EXISTS idx_recovery_codes_user_id ON recovery_codes(user_id);",
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_codes_order ON recovery_codes(user_id, code_order);",
    "CREATE TABLE IF NOT EXISTS pending_registrations (",
    "  username TEXT PRIMARY KEY,",
    "  secret TEXT NOT NULL,",
    "  created_at TEXT NOT NULL",
    ");",
    "CREATE INDEX IF NOT EXISTS idx_pending_registrations_created ON pending_registrations(created_at);",
    "CREATE TABLE IF NOT EXISTS pending_resets (",
    "  id TEXT PRIMARY KEY,",
    "  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,",
    "  recovery_index INTEGER NOT NULL,",
    "  secret TEXT NOT NULL,",
    "  created_at TEXT NOT NULL",
    ");",
    "CREATE INDEX IF NOT EXISTS idx_pending_resets_user_id ON pending_resets(user_id);",
    "CREATE INDEX IF NOT EXISTS idx_pending_resets_created ON pending_resets(created_at);"
  ].join("\n"));
}

function metaValue(databaseHandle, key) {
  const row = databaseHandle.prepare("SELECT value FROM meta WHERE key = ?").get(key);
  return row ? row.value : null;
}

function setMetaValue(databaseHandle, key, value) {
  databaseHandle.prepare([
    "INSERT INTO meta (key, value) VALUES (?, ?)",
    "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
  ].join("\n")).run(key, value);
}

function readLegacyJson() {
  const stat = fs.lstatSync(legacyJsonPath);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1 || stat.size > 100 * 1024 * 1024) {
    throw new Error("legacy db.json is not a regular file or is unexpectedly large");
  }
  const plaintext = fs.readFileSync(legacyJsonPath);
  if (plaintext.length > 100 * 1024 * 1024) {
    plaintext.fill(0);
    throw new Error("legacy db.json grew beyond the migration size limit while being read");
  }
  let value;
  try {
    value = JSON.parse(plaintext.toString("utf8"));
  } catch (error) {
    plaintext.fill(0);
    throw error;
  }
  if (!isPlainObject(value)) {
    plaintext.fill(0);
    throw new Error("legacy db.json root must be an object");
  }
  for (const field of ["users", "pendingRegistrations", "pendingResets"]) {
    if (value[field] != null && !Array.isArray(value[field])) {
      plaintext.fill(0);
      throw new Error("legacy db.json field is invalid: " + field);
    }
  }
  return {
    plaintext,
    legacy: {
      users: value.users || [],
      pendingRegistrations: value.pendingRegistrations || [],
      pendingResets: value.pendingResets || []
    }
  };
}

function databaseHasApplicationData(databaseHandle) {
  return ["users", "sessions", "recovery_codes", "pending_registrations", "pending_resets"]
    .some((table) => Number(databaseHandle.prepare("SELECT COUNT(*) AS count FROM " + table).get().count) > 0);
}

function serializedJsonEquals(serialized, expected) {
  if (serialized == null) {
    return expected == null;
  }
  try {
    return isDeepStrictEqual(JSON.parse(serialized), expected);
  } catch {
    return false;
  }
}

function optionalLegacyValueEquals(actual, expected) {
  return expected == null || expected === "" || String(actual) === String(expected);
}

function legacyDataCoveredBySql(databaseHandle, legacy) {
  const findUser = databaseHandle.prepare([
    "SELECT password_hash, totp_secret, wrapped_vault_key, vault, created_at",
    "FROM users WHERE id = ? AND username = ?"
  ].join("\n"));
  const findSession = databaseHandle.prepare([
    "SELECT token_hash, created_at, last_used_at, expires_at, user_agent, ip",
    "FROM sessions WHERE id = ? AND user_id = ?"
  ].join("\n"));
  const findRecoveryCode = databaseHandle.prepare([
    "SELECT hash, key_box, used_at FROM recovery_codes",
    "WHERE user_id = ? AND code_order = ?"
  ].join("\n"));
  const findRegistration = databaseHandle.prepare([
    "SELECT secret, created_at FROM pending_registrations WHERE username = ?"
  ].join("\n"));
  const findReset = databaseHandle.prepare([
    "SELECT user_id, recovery_index, secret, created_at FROM pending_resets WHERE id = ?"
  ].join("\n"));

  try {
    for (const user of legacy.users) {
      if (!isPlainObject(user) || !user.id || !user.username || !user.passwordHash
          || !user.totpSecret || !user.wrappedVaultKey
          || user.sessions != null && !Array.isArray(user.sessions)
          || user.recoveryCodes != null && !Array.isArray(user.recoveryCodes)) {
        return false;
      }
      const row = findUser.get(String(user.id), String(user.username));
      if (!row || !serializedJsonEquals(row.password_hash, user.passwordHash)
          || String(decodeTotpSecret(row.totp_secret)) !== String(user.totpSecret)
          || !serializedJsonEquals(row.wrapped_vault_key, user.wrappedVaultKey)
          || !serializedJsonEquals(row.vault, user.vault || null)
          || !optionalLegacyValueEquals(row.created_at, user.createdAt)) {
        return false;
      }
      for (const session of user.sessions || []) {
        if (!isPlainObject(session) || !session.id || !session.tokenHash || !session.expiresAt) {
          return false;
        }
        const stored = findSession.get(String(session.id), String(user.id));
        const expectedCreatedAt = session.createdAt || null;
        const expectedLastUsedAt = session.lastUsedAt || session.createdAt || null;
        if (!stored || stored.token_hash !== String(session.tokenHash)
            || !optionalLegacyValueEquals(stored.created_at, expectedCreatedAt)
            || !optionalLegacyValueEquals(stored.last_used_at, expectedLastUsedAt)
            || stored.expires_at !== String(session.expiresAt)
            || stored.user_agent !== String(session.userAgent || "").slice(0, 512)
            || stored.ip !== String(session.ip || "").slice(0, 128)) {
          return false;
        }
      }
      for (const [index, code] of (user.recoveryCodes || []).entries()) {
        if (!isPlainObject(code) || !code.hash || !code.keyBox) {
          return false;
        }
        const stored = findRecoveryCode.get(String(user.id), index);
        if (!stored || !serializedJsonEquals(stored.hash, code.hash)
            || !serializedJsonEquals(stored.key_box, code.keyBox)
            || (stored.used_at || null) !== (code.usedAt || null)) {
          return false;
        }
      }
    }

    for (const item of legacy.pendingRegistrations) {
      if (!isPlainObject(item) || !item.username || !item.secret) {
        return false;
      }
      const stored = findRegistration.get(String(item.username));
      if (!stored || String(decodeTotpSecret(stored.secret)) !== String(item.secret)
          || !optionalLegacyValueEquals(stored.created_at, item.createdAt)) {
        return false;
      }
    }

    for (const item of legacy.pendingResets) {
      if (!isPlainObject(item) || !item.id || !item.userId || !item.secret) {
        return false;
      }
      const stored = findReset.get(String(item.id));
      if (!stored || stored.user_id !== String(item.userId)
          || stored.recovery_index !== Number(item.recoveryIndex || 0)
          || String(decodeTotpSecret(stored.secret)) !== String(item.secret)
          || !optionalLegacyValueEquals(stored.created_at, item.createdAt)) {
        return false;
      }
    }
    return true;
  } catch {
    return false;
  }
}

function importLegacyJson(databaseHandle, legacy) {
  if (databaseHasApplicationData(databaseHandle)) {
    throw new Error("refusing to import db.json into a non-empty SQLite database");
  }
  runTransaction(databaseHandle, () => {
    const insertUser = databaseHandle.prepare([
      "INSERT INTO users (id, username, password_hash, totp_secret, wrapped_vault_key, vault, created_at)",
      "VALUES (?, ?, ?, ?, ?, ?, ?)"
    ].join("\n"));
    const insertSession = databaseHandle.prepare([
      "INSERT INTO sessions (id, user_id, token_hash, created_at, last_used_at, expires_at, user_agent, ip)",
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    ].join("\n"));
    const insertRecoveryCode = databaseHandle.prepare([
      "INSERT INTO recovery_codes (user_id, code_order, hash, key_box, used_at)",
      "VALUES (?, ?, ?, ?, ?)"
    ].join("\n"));
    const insertRegistration = databaseHandle.prepare([
      "INSERT INTO pending_registrations (username, secret, created_at)",
      "VALUES (?, ?, ?)"
    ].join("\n"));
    const insertReset = databaseHandle.prepare([
      "INSERT INTO pending_resets (id, user_id, recovery_index, secret, created_at)",
      "VALUES (?, ?, ?, ?, ?)"
    ].join("\n"));

    for (const user of legacy.users) {
      if (!user || !user.id || !user.username || !user.passwordHash || !user.totpSecret || !user.wrappedVaultKey) {
        throw new Error("legacy user record is incomplete");
      }
      insertUser.run(
        String(user.id),
        String(user.username),
        stringifyJson(user.passwordHash),
        encodeTotpSecret(user.totpSecret),
        stringifyJson(user.wrappedVaultKey),
        user.vault ? stringifyJson(user.vault) : null,
        user.createdAt || nowIso()
      );
      for (const session of user.sessions || []) {
        insertSession.run(
          String(session.id),
          String(user.id),
          String(session.tokenHash),
          session.createdAt || nowIso(),
          session.lastUsedAt || session.createdAt || nowIso(),
          session.expiresAt,
          String(session.userAgent || "").slice(0, 512),
          String(session.ip || "").slice(0, 128)
        );
      }
      (user.recoveryCodes || []).forEach((code, index) => {
        insertRecoveryCode.run(
          String(user.id),
          index,
          stringifyJson(code.hash),
          stringifyJson(code.keyBox),
          code.usedAt || null
        );
      });
    }
    for (const pending of legacy.pendingRegistrations) {
      insertRegistration.run(
        String(pending.username),
        encodeTotpSecret(pending.secret),
        pending.createdAt || nowIso()
      );
    }
    for (const reset of legacy.pendingResets) {
      insertReset.run(
        String(reset.id),
        String(reset.userId),
        Number(reset.recoveryIndex || 0),
        encodeTotpSecret(reset.secret),
        reset.createdAt || nowIso()
      );
    }
    setMetaValue(databaseHandle, "schemaVersion", "2");
  });
}

function fsyncDirectory(directory) {
  if (process.platform === "win32") {
    return;
  }
  const handle = fs.openSync(directory, "r");
  try {
    fs.fsyncSync(handle);
  } finally {
    fs.closeSync(handle);
  }
}

function securelyRemoveFile(target) {
  const stat = fs.lstatSync(target);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1) {
    throw new Error("refusing to erase a non-regular legacy plaintext file");
  }
  const handle = fs.openSync(target, "r+");
  try {
    const zeros = Buffer.alloc(Math.min(1024 * 1024, Math.max(1, stat.size)));
    let offset = 0;
    while (offset < stat.size) {
      const length = Math.min(zeros.length, stat.size - offset);
      fs.writeSync(handle, zeros, 0, length, offset);
      offset += length;
    }
    fs.fsyncSync(handle);
  } finally {
    fs.closeSync(handle);
  }
  fs.unlinkSync(target);
}

function recoverLegacyPlaintextRemnants() {
  if (!fs.existsSync(dataDir)) {
    return;
  }
  const remnants = fs.readdirSync(dataDir)
    .filter((name) => name.startsWith("db.json.migration-plaintext-delete-"));
  if (!remnants.length) {
    return;
  }
  if (remnants.length !== 1 || fs.existsSync(legacyJsonPath)) {
    throw new Error("legacy migration plaintext remnants require manual reconciliation");
  }
  const remnantPath = path.join(dataDir, remnants[0]);
  const stat = fs.lstatSync(remnantPath);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1) {
    throw new Error("legacy migration plaintext remnant is not a regular file");
  }
  fs.renameSync(remnantPath, legacyJsonPath);
  fsyncDirectory(dataDir);
}

function archiveLegacyJson(databaseHandle, plaintext) {
  if (!fs.existsSync(legacyJsonPath)) {
    return null;
  }
  if (!serverSecretKeyValue) {
    throw new Error("a server secret is required before legacy db.json can be archived safely");
  }
  const stamp = nowIso().replace(/[-:.]/g, "").replace("Z", "Z");
  let archivePath = path.join(dataDir, "db.json.migrated-" + stamp + ".enc");
  let suffix = 1;
  while (fs.existsSync(archivePath)) {
    archivePath = path.join(dataDir, "db.json.migrated-" + stamp + "-" + suffix + ".enc");
    suffix += 1;
  }
  const encrypted = encryptBox(
    serverSecretKeyValue,
    plaintext.toString("utf8"),
    "yySlide:legacy-db-json-archive:v1"
  );
  const envelope = Buffer.from(JSON.stringify({
    format: "yyslide-legacy-db-json",
    version: 1,
    createdAt: nowIso(),
    cipher: encrypted
  }) + "\n", "utf8");
  const tempPath = archivePath + "." + process.pid + "." + randomId(6) + ".tmp";
  let tempHandle;
  let writeError = null;
  try {
    tempHandle = fs.openSync(tempPath, "wx", 0o600);
    fs.writeFileSync(tempHandle, envelope);
    fs.fsyncSync(tempHandle);
  } catch (error) {
    writeError = error;
  } finally {
    if (tempHandle != null) {
      fs.closeSync(tempHandle);
    }
  }
  if (writeError) {
    try {
      fs.rmSync(tempPath, { force: true });
    } catch {
      // The startup error remains the primary failure.
    }
    throw writeError;
  }
  fs.renameSync(tempPath, archivePath);
  hardenPath(archivePath, 0o600);
  fsyncDirectory(dataDir);
  const stagedPlaintextPath = path.join(
    dataDir,
    "db.json.migration-plaintext-delete-" + process.pid + "-" + randomId(6)
  );
  fs.renameSync(legacyJsonPath, stagedPlaintextPath);
  fsyncDirectory(dataDir);
  let stagedPlaintext;
  try {
    stagedPlaintext = fs.readFileSync(stagedPlaintextPath);
    const expectedDigest = crypto.createHash("sha256").update(plaintext).digest();
    const stagedDigest = crypto.createHash("sha256").update(stagedPlaintext).digest();
    if (!crypto.timingSafeEqual(expectedDigest, stagedDigest)) {
      if (!fs.existsSync(legacyJsonPath)) {
        fs.renameSync(stagedPlaintextPath, legacyJsonPath);
        fsyncDirectory(dataDir);
      }
      throw new Error("legacy db.json changed while it was being migrated");
    }
  } finally {
    if (stagedPlaintext) {
      stagedPlaintext.fill(0);
    }
  }
  securelyRemoveFile(stagedPlaintextPath);
  fsyncDirectory(dataDir);
  setMetaValue(databaseHandle, "legacyJsonArchive", path.basename(archivePath));
  return archivePath;
}

function leaveUnresolvedLegacyJson(message) {
  console.warn(message);
  if (isProduction || migrateOnly) {
    throw new Error("legacy db.json requires manual reconciliation before the service can start");
  }
  return "left-in-place";
}

function migrateLegacyJson(databaseHandle) {
  const marker = metaValue(databaseHandle, "legacyJsonMigrated");
  if (!fs.existsSync(legacyJsonPath)) {
    if (!marker) {
      setMetaValue(databaseHandle, "legacyJsonMigrated", "none:" + nowIso());
    }
    return "none";
  }

  const snapshot = readLegacyJson();
  const legacy = snapshot.legacy;
  try {
    if (!marker) {
      if (!databaseHasApplicationData(databaseHandle)) {
        importLegacyJson(databaseHandle, legacy);
        setMetaValue(databaseHandle, "legacyJsonMigrated", "imported:" + nowIso());
        archiveLegacyJson(databaseHandle, snapshot.plaintext);
        return "imported-and-archived";
      }
      if (legacyDataCoveredBySql(databaseHandle, legacy)) {
        setMetaValue(databaseHandle, "legacyJsonMigrated", "verified-existing:" + nowIso());
        archiveLegacyJson(databaseHandle, snapshot.plaintext);
        return "verified-and-archived";
      }
      return leaveUnresolvedLegacyJson(
        "Legacy db.json was left in place because the SQLite database is non-empty and does not cover all legacy records."
      );
    }

    if (marker.startsWith("none:")) {
      if (!databaseHasApplicationData(databaseHandle)) {
        importLegacyJson(databaseHandle, legacy);
        setMetaValue(databaseHandle, "legacyJsonMigrated", "imported-late:" + nowIso());
        archiveLegacyJson(databaseHandle, snapshot.plaintext);
        return "imported-and-archived";
      }
      if (legacyDataCoveredBySql(databaseHandle, legacy)) {
        setMetaValue(databaseHandle, "legacyJsonMigrated", "verified-late:" + nowIso());
        archiveLegacyJson(databaseHandle, snapshot.plaintext);
        return "verified-and-archived";
      }
      return leaveUnresolvedLegacyJson(
        "Legacy db.json was left in place because it appeared after a non-empty SQLite database was initialized."
      );
    }
    if (!legacyDataCoveredBySql(databaseHandle, legacy)) {
      return leaveUnresolvedLegacyJson(
        "Legacy db.json was left in place because its records could not be verified in SQLite."
      );
    }
    archiveLegacyJson(databaseHandle, snapshot.plaintext);
    return "archived";
  } finally {
    snapshot.plaintext.fill(0);
  }
}

function isEncryptedTotp(value) {
  const parsed = parseJson(value, null);
  return Boolean(parsed && parsed.type === "aes-256-gcm" && parsed.iv && parsed.ciphertext && parsed.tag);
}

function encryptPlainTotpSecrets(databaseHandle) {
  if (!serverSecretKeyValue) {
    return 0;
  }
  const targets = [
    {
      rows: databaseHandle.prepare("SELECT id AS record_key, totp_secret AS secret FROM users").all(),
      statement: databaseHandle.prepare("UPDATE users SET totp_secret = ? WHERE id = ?")
    },
    {
      rows: databaseHandle.prepare("SELECT username AS record_key, secret FROM pending_registrations").all(),
      statement: databaseHandle.prepare("UPDATE pending_registrations SET secret = ? WHERE username = ?")
    },
    {
      rows: databaseHandle.prepare("SELECT id AS record_key, secret FROM pending_resets").all(),
      statement: databaseHandle.prepare("UPDATE pending_resets SET secret = ? WHERE id = ?")
    }
  ];
  let changed = 0;
  runTransaction(databaseHandle, () => {
    for (const target of targets) {
      for (const row of target.rows) {
        if (!isEncryptedTotp(row.secret)) {
          target.statement.run(encodeTotpSecret(row.secret), row.record_key);
          changed += 1;
        }
      }
    }
    if (changed) {
      setMetaValue(databaseHandle, "plaintextTotpEncryptedAt", nowIso());
    }
  });
  return changed;
}

function database() {
  if (sqlDb) {
    return sqlDb;
  }
  ensureDataDir();
  recoverLegacyPlaintextRemnants();
  sqlDb = new DatabaseSync(dbPath);
  ensureSchema(sqlDb);
  sqlDb.exec("PRAGMA wal_checkpoint(TRUNCATE);");
  sqlDb.exec("PRAGMA secure_delete = ON;");
  migrationSummary.legacy = migrateLegacyJson(sqlDb);
  migrationSummary.encryptedTotpValues = encryptPlainTotpSecrets(sqlDb);
  hardenPath(dbPath, 0o600);
  for (const suffix of ["-wal", "-shm"]) {
    if (fs.existsSync(dbPath + suffix)) {
      hardenPath(dbPath + suffix, 0o600);
    }
  }
  return sqlDb;
}

function inspectDatabase(databaseHandle) {
  const count = (table) => Number(databaseHandle.prepare("SELECT COUNT(*) AS count FROM " + table).get().count);
  const plaintext = [
    ...databaseHandle.prepare("SELECT totp_secret AS secret FROM users").all(),
    ...databaseHandle.prepare("SELECT secret FROM pending_registrations").all(),
    ...databaseHandle.prepare("SELECT secret FROM pending_resets").all()
  ].filter((row) => !isEncryptedTotp(row.secret)).length;
  return {
    database: path.basename(dbPath),
    users: count("users"),
    sessions: count("sessions"),
    recoveryCodes: count("recovery_codes"),
    pendingRegistrations: count("pending_registrations"),
    pendingResets: count("pending_resets"),
    plaintextTotpValues: plaintext,
    legacyJsonArchive: metaValue(databaseHandle, "legacyJsonArchive"),
    migration: migrationSummary
  };
}

function randomId(bytes) {
  return crypto.randomBytes(bytes == null ? 32 : bytes).toString("base64url");
}

function normalizeUsername(username) {
  if (typeof username !== "string") {
    throw httpError(400, "用户名无效。");
  }
  const value = username.trim().toLowerCase();
  if (!/^[a-z0-9_.@-]{3,80}$/.test(value)) {
    throw httpError(400, "用户名只能包含字母、数字、点、下划线、横线或 @，长度 3-80。");
  }
  return value;
}

function httpError(status, message, headers) {
  const error = new Error(message);
  error.status = status;
  error.expose = true;
  error.headers = headers || {};
  return error;
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a || ""));
  const right = Buffer.from(String(b || ""));
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function passwordValidationError(password) {
  if (typeof password !== "string") {
    return "密码无效。";
  }
  if (password.length < 8) {
    return "密码至少 8 位。";
  }
  if (password.length > 256 || Buffer.byteLength(password, "utf8") > 1024) {
    return "密码过长。";
  }
  if (!/[0-9]/.test(password)) {
    return "密码必须包含数字。";
  }
  if (!/[a-z]/.test(password)) {
    return "密码必须包含小写字母。";
  }
  if (!/[A-Z]/.test(password)) {
    return "密码必须包含大写字母。";
  }
  return "";
}

function validScryptParams(stored) {
  const N = Number(stored && stored.N || defaultScryptParams.N);
  const r = Number(stored && stored.r || defaultScryptParams.r);
  const p = Number(stored && stored.p || defaultScryptParams.p);
  return Number.isInteger(N) && N >= 16384 && N <= 262144 && (N & (N - 1)) === 0
    && Number.isInteger(r) && r >= 1 && r <= 32
    && Number.isInteger(p) && p >= 1 && p <= 8
    && 128 * N * r <= defaultScryptParams.maxmem;
}

function acquireScryptSlot() {
  if (activeScryptJobs < scryptConcurrency) {
    activeScryptJobs += 1;
    return Promise.resolve();
  }
  if (scryptWaiters.length >= scryptQueueLimit) {
    return Promise.reject(httpError(503, "服务器繁忙，请稍后再试。", { "Retry-After": "2" }));
  }
  return new Promise((resolve) => scryptWaiters.push(resolve));
}

function releaseScryptSlot() {
  const next = scryptWaiters.shift();
  if (next) {
    next();
    return;
  }
  activeScryptJobs -= 1;
}

async function deriveScrypt(secret, salt, params) {
  await acquireScryptSlot();
  try {
    return await new Promise((resolve, reject) => {
      crypto.scrypt(String(secret), salt, 64, {
        N: params.N,
        r: params.r,
        p: params.p,
        maxmem: params.maxmem
      }, (error, result) => {
        if (error) {
          reject(error);
        } else {
          resolve(result.toString("base64url"));
        }
      });
    });
  } finally {
    releaseScryptSlot();
  }
}

async function hashSecret(secret, salt, params) {
  const actualSalt = salt || crypto.randomBytes(16).toString("base64url");
  const actualParams = params || defaultScryptParams;
  const hash = await deriveScrypt(secret, actualSalt, actualParams);
  return {
    alg: "scrypt",
    salt: actualSalt,
    hash,
    N: actualParams.N,
    r: actualParams.r,
    p: actualParams.p
  };
}

async function verifySecret(secret, stored) {
  if (!stored || stored.alg && stored.alg !== "scrypt" || !stored.salt || !stored.hash || !validScryptParams(stored)) {
    return false;
  }
  const next = await hashSecret(secret, stored.salt, {
    N: Number(stored.N || defaultScryptParams.N),
    r: Number(stored.r || defaultScryptParams.r),
    p: Number(stored.p || defaultScryptParams.p),
    maxmem: defaultScryptParams.maxmem
  });
  return safeEqual(next.hash, stored.hash);
}

const base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function base32Encode(buffer) {
  let bits = 0;
  let value = 0;
  let output = "";
  for (const byte of buffer) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += base32Alphabet[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) {
    output += base32Alphabet[(value << (5 - bits)) & 31];
  }
  return output;
}

function base32Decode(value) {
  const clean = String(value || "").replace(/=+$/g, "").replace(/\s+/g, "").toUpperCase();
  let bits = 0;
  let current = 0;
  const output = [];
  for (const char of clean) {
    const index = base32Alphabet.indexOf(char);
    if (index < 0) {
      continue;
    }
    current = (current << 5) | index;
    bits += 5;
    if (bits >= 8) {
      output.push((current >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(output);
}

function generateTotpSecret() {
  return base32Encode(crypto.randomBytes(20));
}

function totpCode(secret, step) {
  const key = base32Decode(secret);
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const digest = crypto.createHmac("sha1", key).update(counter).digest();
  const offset = digest[digest.length - 1] & 0xf;
  const value = ((digest[offset] & 0x7f) << 24)
    | ((digest[offset + 1] & 0xff) << 16)
    | ((digest[offset + 2] & 0xff) << 8)
    | (digest[offset + 3] & 0xff);
  return String(value % 1000000).padStart(6, "0");
}

function validTotpCode(code) {
  return typeof code === "string" && /^\d{6}$/.test(code.replace(/\s+/g, ""));
}

function verifyTotp(secret, code) {
  const clean = String(code || "").replace(/\s+/g, "");
  if (!/^\d{6}$/.test(clean)) {
    return false;
  }
  const currentStep = Math.floor(Date.now() / 30000);
  for (let offset = -1; offset <= 1; offset += 1) {
    if (safeEqual(totpCode(secret, currentStep + offset), clean)) {
      return true;
    }
  }
  return false;
}

function hashToken(token) {
  return crypto.createHash("sha256").update(String(token)).digest("base64url");
}

function clientIp(request) {
  const raw = trustedProxy && request.headers["x-forwarded-for"]
    ? String(request.headers["x-forwarded-for"]).split(",")[0].trim()
    : request.socket.remoteAddress || "";
  return raw.replace(/[^\w:.%-]/g, "").slice(0, 128);
}

function sweepRateBuckets(now, force) {
  if (!force && now - sweepRateBuckets.lastRun < 30000) {
    return;
  }
  sweepRateBuckets.lastRun = now;
  for (const [key, bucket] of rateBuckets) {
    if (bucket.resetAt <= now) {
      rateBuckets.delete(key);
    }
  }
}
sweepRateBuckets.lastRun = 0;

function enforceRateLimit(request, action, subject, limit, windowMs) {
  const now = Date.now();
  sweepRateBuckets(now, false);
  const key = action + ":" + clientIp(request) + ":" + String(subject || "").toLowerCase().slice(0, 128);
  let bucket = rateBuckets.get(key);
  if (!bucket || bucket.resetAt <= now) {
    if (!bucket && rateBuckets.size >= maxRateBuckets) {
      sweepRateBuckets(now, true);
      if (rateBuckets.size >= maxRateBuckets) {
        throw httpError(503, "服务器繁忙，请稍后再试。", { "Retry-After": "2" });
      }
    }
    bucket = { count: 0, resetAt: now + windowMs };
    rateBuckets.set(key, bucket);
  }
  if (bucket.count >= limit) {
    const retryAfter = Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
    throw httpError(429, "请求过于频繁，请稍后再试。", { "Retry-After": String(retryAfter) });
  }
  bucket.count += 1;
}

function assertBodyObject(body) {
  if (!isPlainObject(body)) {
    throw httpError(400, "JSON 请求体必须是对象。");
  }
}

function assertAllowedFields(body, allowed) {
  assertBodyObject(body);
  const allowedSet = new Set(allowed);
  const unexpected = Object.keys(body).find((key) => !allowedSet.has(key));
  if (unexpected) {
    throw httpError(400, "请求包含不支持的字段。");
  }
}

function readBody(request, maxBytes) {
  return new Promise((resolve, reject) => {
    const contentType = String(request.headers["content-type"] || "").toLowerCase();
    if (contentType && !contentType.startsWith("application/json")) {
      reject(httpError(415, "请求必须使用 application/json。"));
      request.resume();
      return;
    }
    const declaredLength = Number(request.headers["content-length"]);
    if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
      reject(httpError(413, "请求太大。"));
      request.resume();
      return;
    }
    const chunks = [];
    let size = 0;
    let settled = false;
    request.on("data", (chunk) => {
      if (settled) {
        return;
      }
      size += chunk.length;
      if (size > maxBytes) {
        settled = true;
        chunks.length = 0;
        reject(httpError(413, "请求太大。"));
        request.resume();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      if (settled) {
        return;
      }
      settled = true;
      const text = Buffer.concat(chunks).toString("utf8");
      if (!text) {
        resolve({});
        return;
      }
      try {
        const body = JSON.parse(text);
        assertBodyObject(body);
        resolve(body);
      } catch (error) {
        reject(error && error.status ? error : httpError(400, "JSON 无效。"));
      }
    });
    request.on("aborted", () => {
      if (!settled) {
        settled = true;
        reject(httpError(400, "请求已中止。"));
      }
    });
    request.on("error", (error) => {
      if (!settled) {
        settled = true;
        reject(error);
      }
    });
  });
}

function validateBase64(value, label, minimumBytes, maximumBytes) {
  if (typeof value !== "string" || value.length > Math.ceil(maximumBytes * 4 / 3) + 8
      || !/^[A-Za-z0-9+/_-]+={0,2}$/.test(value)) {
    throw httpError(400, label + " 无效。");
  }
  const size = Buffer.from(value, "base64").length;
  if (size < minimumBytes || size > maximumBytes) {
    throw httpError(400, label + " 大小无效。");
  }
}

const encryptionBoxFields = new Set([
  "type", "salt", "iv", "ciphertext", "tag", "kdf", "iterations", "version"
]);

function validateEncryptionBox(box, label, requireSalt, maximumCiphertextBytes) {
  if (!isPlainObject(box) || Object.keys(box).length > encryptionBoxFields.size) {
    throw httpError(400, label + " 无效。");
  }
  if (Object.keys(box).some((key) => !encryptionBoxFields.has(key))) {
    throw httpError(400, label + " 包含不支持的字段。");
  }
  const serializedSize = Buffer.byteLength(JSON.stringify(box), "utf8");
  if (serializedSize > maximumCiphertextBytes * 2 + 4096) {
    throw httpError(413, label + " 太大。");
  }
  validateBase64(box.iv, label + " IV", 8, 32);
  validateBase64(box.tag, label + " tag", 12, 32);
  validateBase64(box.ciphertext, label + " 密文", 1, maximumCiphertextBytes);
  if (requireSalt) {
    validateBase64(box.salt, label + " salt", 4, 128);
  } else if (box.salt != null) {
    validateBase64(box.salt, label + " salt", 4, 128);
  }
  if (box.type != null && (typeof box.type !== "string" || box.type.length > 64)) {
    throw httpError(400, label + " type 无效。");
  }
  if (box.kdf != null && (typeof box.kdf !== "string" || box.kdf.length > 64)) {
    throw httpError(400, label + " KDF 无效。");
  }
  if (box.iterations != null && (!Number.isInteger(box.iterations) || box.iterations < 1 || box.iterations > 10000000)) {
    throw httpError(400, label + " iterations 无效。");
  }
  return box;
}

function validateRecoveryKeys(value) {
  if (!Array.isArray(value) || value.length !== MAX_RECOVERY_KEYS) {
    throw httpError(400, "必须提供恰好 " + MAX_RECOVERY_KEYS + " 个恢复码。");
  }
  const seen = new Set();
  return value.map((item) => {
    if (!isPlainObject(item)) {
      throw httpError(400, "恢复码信息无效。");
    }
    assertAllowedFields(item, ["code", "keyBox"]);
    if (typeof item.code !== "string") {
      throw httpError(400, "恢复码无效。");
    }
    const code = item.code.trim().toUpperCase();
    if (!/^[A-Z0-9_-]{5}-[A-Z0-9_-]{5}$/.test(code) || seen.has(code)) {
      throw httpError(400, "恢复码格式无效或存在重复。");
    }
    seen.add(code);
    validateEncryptionBox(item.keyBox, "恢复码 key box", true, 4096);
    return { code, keyBox: item.keyBox };
  });
}

async function hashRecoveryKeys(recoveryKeys) {
  const result = [];
  for (const item of recoveryKeys) {
    result.push({
      hash: await hashSecret(item.code),
      keyBox: item.keyBox,
      usedAt: null
    });
  }
  return result;
}

function validateVault(vault) {
  if (!isPlainObject(vault)) {
    throw httpError(400, "vault 无效。");
  }
  const bytes = Buffer.byteLength(JSON.stringify(vault), "utf8");
  if (bytes > maxVaultBytes) {
    throw httpError(413, "vault 超过服务器配额。");
  }
  validateEncryptionBox(vault, "vault", false, maxVaultBytes);
  return vault;
}

function cleanExpiredRecords(databaseHandle, force) {
  const now = Date.now();
  if (!force && now - lastCleanupAt < 60000) {
    return;
  }
  lastCleanupAt = now;
  const nowValue = new Date(now).toISOString();
  const pendingCutoff = new Date(now - PENDING_TTL_MS).toISOString();
  const expiredSessionIds = databaseHandle.prepare("SELECT id FROM sessions WHERE expires_at <= ?").all(nowValue)
    .map((row) => row.id);
  runTransaction(databaseHandle, () => {
    databaseHandle.prepare("DELETE FROM sessions WHERE expires_at <= ?").run(nowValue);
    databaseHandle.prepare("DELETE FROM pending_registrations WHERE created_at <= ?").run(pendingCutoff);
    databaseHandle.prepare("DELETE FROM pending_resets WHERE created_at <= ?").run(pendingCutoff);
  });
  closeSseForSessionIds(expiredSessionIds);
}

function userFromRow(row, includeTotp) {
  if (!row) {
    return null;
  }
  return {
    id: row.id,
    username: row.username,
    passwordHash: parseJson(row.password_hash, null),
    totpSecret: includeTotp ? decodeTotpSecret(row.totp_secret) : undefined,
    wrappedVaultKey: parseJson(row.wrapped_vault_key, null),
    vault: parseJson(row.vault, null),
    createdAt: row.created_at
  };
}

function getUserByUsername(databaseHandle, username, includeTotp) {
  const columns = includeTotp
    ? "id, username, password_hash, totp_secret, wrapped_vault_key, vault, created_at"
    : "id, username, NULL AS password_hash, NULL AS totp_secret, NULL AS wrapped_vault_key, NULL AS vault, created_at";
  const row = databaseHandle.prepare("SELECT " + columns + " FROM users WHERE username = ?").get(username);
  return userFromRow(row, includeTotp);
}

function makeSession(request) {
  const createdAt = nowIso();
  const token = randomId(36);
  return {
    token,
    id: crypto.randomUUID(),
    tokenHash: hashToken(token),
    createdAt,
    lastUsedAt: createdAt,
    expiresAt: new Date(Date.now() + sessionTtlMs).toISOString(),
    userAgent: String(request.headers["user-agent"] || "").slice(0, 512),
    ip: clientIp(request)
  };
}

function insertSession(databaseHandle, userId, session) {
  databaseHandle.prepare([
    "INSERT INTO sessions (id, user_id, token_hash, created_at, last_used_at, expires_at, user_agent, ip)",
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
  ].join("\n")).run(
    session.id,
    userId,
    session.tokenHash,
    session.createdAt,
    session.lastUsedAt,
    session.expiresAt,
    session.userAgent,
    session.ip
  );
}

function pruneSessionsForNewSession(databaseHandle, userId) {
  databaseHandle.prepare("DELETE FROM sessions WHERE user_id = ? AND expires_at <= ?").run(userId, nowIso());
  const rows = databaseHandle.prepare([
    "SELECT id FROM sessions WHERE user_id = ?",
    "ORDER BY last_used_at ASC, created_at ASC"
  ].join("\n")).all(userId);
  const removeCount = Math.max(0, rows.length - maxSessionsPerUser + 1);
  const removed = rows.slice(0, removeCount).map((row) => row.id);
  const remove = databaseHandle.prepare("DELETE FROM sessions WHERE id = ?");
  for (const id of removed) {
    remove.run(id);
  }
  return removed;
}

function authenticateRequest(request) {
  const auth = String(request.headers.authorization || "");
  const match = auth.match(/^Bearer\s+([A-Za-z0-9_-]{32,256})$/i);
  if (!match) {
    throw httpError(401, "需要登录。");
  }
  const databaseHandle = database();
  const row = databaseHandle.prepare([
    "SELECT",
    "  u.id, u.username, u.wrapped_vault_key, u.vault, u.created_at,",
    "  s.id AS session_id, s.created_at AS session_created_at,",
    "  s.last_used_at AS session_last_used_at, s.expires_at AS session_expires_at,",
    "  s.user_agent AS session_user_agent, s.ip AS session_ip",
    "FROM sessions s",
    "JOIN users u ON u.id = s.user_id",
    "WHERE s.token_hash = ? AND s.expires_at > ?"
  ].join("\n")).get(hashToken(match[1]), nowIso());
  if (!row) {
    throw httpError(401, "登录已失效。");
  }
  const touchAt = Date.now();
  let lastUsedAt = row.session_last_used_at;
  if (!Number.isFinite(Date.parse(lastUsedAt)) || Date.parse(lastUsedAt) <= touchAt - SESSION_TOUCH_INTERVAL_MS) {
    lastUsedAt = new Date(touchAt).toISOString();
    databaseHandle.prepare([
      "UPDATE sessions SET last_used_at = ?",
      "WHERE id = ? AND last_used_at = ?"
    ].join("\n")).run(lastUsedAt, row.session_id, row.session_last_used_at);
  }
  return {
    user: {
      id: row.id,
      username: row.username,
      wrappedVaultKey: parseJson(row.wrapped_vault_key, null),
      vault: parseJson(row.vault, null),
      createdAt: row.created_at
    },
    session: {
      id: row.session_id,
      createdAt: row.session_created_at,
      lastUsedAt,
      expiresAt: row.session_expires_at,
      userAgent: row.session_user_agent,
      ip: row.session_ip
    }
  };
}

function publicUser(user) {
  return {
    id: user.id,
    username: user.username,
    createdAt: user.createdAt,
    vaultUpdatedAt: user.vault && user.vault.updatedAt || null
  };
}

function publicDevice(session) {
  return {
    id: session.id,
    createdAt: session.createdAt,
    lastUsedAt: session.lastUsedAt,
    expiresAt: session.expiresAt,
    userAgent: session.userAgent,
    ip: session.ip
  };
}

function sessionFromRow(row) {
  return {
    id: row.id,
    createdAt: row.created_at,
    lastUsedAt: row.last_used_at,
    expiresAt: row.expires_at,
    userAgent: row.user_agent,
    ip: row.ip
  };
}

function setResponseSecurityHeaders(response) {
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("Referrer-Policy", "no-referrer");
  response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
}

function sendJson(response, status, body, extraHeaders) {
  if (response.headersSent || response.writableEnded || response.destroyed) {
    return;
  }
  setResponseSecurityHeaders(response);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    ...(extraHeaders || {})
  });
  response.end(JSON.stringify(body));
}

function corsOriginForRequest(request) {
  const origin = request.headers.origin || "";
  if (!origin || !corsOrigins.length) {
    return "";
  }
  if (corsOrigins.includes("*") || corsOrigins.includes(origin)) {
    return origin;
  }
  return "";
}

function setCorsHeaders(request, response) {
  const origin = corsOriginForRequest(request);
  if (!origin) {
    return;
  }
  response.setHeader("Access-Control-Allow-Origin", origin);
  response.setHeader("Vary", "Origin");
  response.setHeader("Access-Control-Allow-Headers", "content-type, authorization, x-yyslide-client-id");
  response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
  response.setHeader("Access-Control-Max-Age", "600");
}

function clientIdFromRequest(request) {
  return String(request.headers["x-yyslide-client-id"] || "")
    .trim()
    .replace(/[^\w:.-]/g, "")
    .slice(0, 128);
}

function removeVaultEventClient(userId, client) {
  if (client.closed) {
    return;
  }
  client.closed = true;
  clearInterval(client.heartbeat);
  const clients = vaultEventClients.get(userId);
  if (clients) {
    clients.delete(client);
    if (clients.size === 0) {
      vaultEventClients.delete(userId);
    }
  }
  totalSseClients = Math.max(0, totalSseClients - 1);
  const ipCount = sseCountsByIp.get(client.ip) || 0;
  if (ipCount <= 1) {
    sseCountsByIp.delete(client.ip);
  } else {
    sseCountsByIp.set(client.ip, ipCount - 1);
  }
}

function writeSse(response, event, data) {
  return response.write("event: " + event + "\n"
    + "data: " + JSON.stringify(data) + "\n\n");
}

function assertSseCapacity(userId, ip) {
  const userCount = vaultEventClients.get(userId) ? vaultEventClients.get(userId).size : 0;
  if (userCount >= maxSsePerUser || (sseCountsByIp.get(ip) || 0) >= maxSsePerIp || totalSseClients >= maxSseTotal) {
    throw httpError(429, "实时同步连接过多，请关闭其他连接后重试。", { "Retry-After": "30" });
  }
}

function addVaultEventClient(request, response, auth, clientId) {
  const userId = auth.user.id;
  const ip = clientIp(request);
  assertSseCapacity(userId, ip);
  const client = {
    response,
    clientId,
    sessionId: auth.session.id,
    ip,
    closed: false,
    heartbeat: null
  };
  client.heartbeat = setInterval(() => {
    try {
      const active = database().prepare([
        "SELECT 1 AS ok FROM sessions WHERE id = ? AND user_id = ? AND expires_at > ?"
      ].join("\n")).get(client.sessionId, userId, nowIso());
      if (!active) {
        writeSse(response, "auth.expired", { type: "auth.expired" });
        response.end();
        removeVaultEventClient(userId, client);
        return;
      }
      if (response.writableLength > maxSseBufferBytes) {
        response.end();
        removeVaultEventClient(userId, client);
        return;
      }
      response.write(": heartbeat\n\n");
    } catch {
      response.end();
      removeVaultEventClient(userId, client);
    }
  }, 25000);
  if (typeof client.heartbeat.unref === "function") {
    client.heartbeat.unref();
  }
  let clients = vaultEventClients.get(userId);
  if (!clients) {
    clients = new Set();
    vaultEventClients.set(userId, clients);
  }
  clients.add(client);
  totalSseClients += 1;
  sseCountsByIp.set(ip, (sseCountsByIp.get(ip) || 0) + 1);
  const cleanup = () => removeVaultEventClient(userId, client);
  request.once("close", cleanup);
  response.once("close", cleanup);
  response.write(": connected\n\n");
}

function closeSseForSessionIds(sessionIds) {
  if (!sessionIds || !sessionIds.length) {
    return;
  }
  const ids = new Set(sessionIds);
  for (const [userId, clients] of vaultEventClients) {
    for (const client of [...clients]) {
      if (ids.has(client.sessionId)) {
        try {
          writeSse(client.response, "auth.expired", { type: "auth.expired" });
          client.response.end();
        } catch {
          // Cleanup below is sufficient.
        }
        removeVaultEventClient(userId, client);
      }
    }
  }
}

function closeAllSseForUser(userId) {
  const clients = vaultEventClients.get(userId);
  if (!clients) {
    return;
  }
  for (const client of [...clients]) {
    try {
      writeSse(client.response, "auth.expired", { type: "auth.expired" });
      client.response.end();
    } catch {
      // Cleanup below is sufficient.
    }
    removeVaultEventClient(userId, client);
  }
}

function broadcastVaultChanged(userId, vault, sourceClientId) {
  const clients = vaultEventClients.get(userId);
  if (!clients) {
    return;
  }
  const payload = {
    type: "vault.updated",
    updatedAt: vault && vault.updatedAt || nowIso(),
    sourceClientId: sourceClientId || ""
  };
  for (const client of [...clients]) {
    if (sourceClientId && client.clientId === sourceClientId) {
      continue;
    }
    try {
      if (client.response.writableLength > maxSseBufferBytes
          || !writeSse(client.response, "vault.updated", payload)
            && client.response.writableLength > maxSseBufferBytes) {
        client.response.end();
        removeVaultEventClient(userId, client);
      }
    } catch {
      removeVaultEventClient(userId, client);
    }
  }
}

function routeKey(method, pathname) {
  return String(method || "GET").toUpperCase() + " " + pathname;
}

const preBodyRatePolicies = {
  "POST /v1/auth/register/start": [30, 15 * 60 * 1000],
  "POST /v1/auth/register/finish": [20, 15 * 60 * 1000],
  "POST /v1/auth/login": [50, 15 * 60 * 1000],
  "POST /v1/auth/reset/start": [20, 60 * 60 * 1000],
  "POST /v1/auth/reset/finish": [20, 15 * 60 * 1000],
  "PUT /v1/vault": [120, 60 * 1000],
  "GET /v1/vault/events": [30, 60 * 1000]
};

function enforcePreBodyLimits(request, key) {
  enforceRateLimit(request, "all-requests", "", key === "GET /health" ? 600 : 240, 60 * 1000);
  const policy = preBodyRatePolicies[key];
  if (policy) {
    enforceRateLimit(request, "route-" + key, "", policy[0], policy[1]);
  }
}

async function handle(request, response) {
  const url = new URL(request.url || "/", "http://127.0.0.1");
  const key = routeKey(request.method, url.pathname);
  enforcePreBodyLimits(request, key);

  if (request.method === "OPTIONS") {
    response.writeHead(204);
    response.end();
    return;
  }

  if (key === "GET /health") {
    database().prepare("SELECT 1 AS ok").get();
    sendJson(response, 200, { ok: true, now: nowIso() });
    return;
  }

  const databaseHandle = database();
  cleanExpiredRecords(databaseHandle, false);

  if (key === "POST /v1/auth/register/start") {
    const body = await readBody(request, 8 * 1024);
    assertAllowedFields(body, ["username"]);
    const username = normalizeUsername(body.username);
    enforceRateLimit(request, "register-start-user", username, 8, 15 * 60 * 1000);
    const secret = generateTotpSecret();
    runTransaction(databaseHandle, () => {
      if (databaseHandle.prepare("SELECT 1 AS ok FROM users WHERE username = ?").get(username)) {
        throw httpError(409, "用户名已存在。");
      }
      const pendingExists = databaseHandle.prepare("SELECT 1 AS ok FROM pending_registrations WHERE username = ?").get(username);
      if (!pendingExists) {
        const count = Number(databaseHandle.prepare("SELECT COUNT(*) AS count FROM pending_registrations").get().count);
        if (count >= maxPendingRegistrations) {
          throw httpError(503, "注册服务繁忙，请稍后再试。", { "Retry-After": "60" });
        }
      }
      databaseHandle.prepare([
        "INSERT INTO pending_registrations (username, secret, created_at) VALUES (?, ?, ?)",
        "ON CONFLICT(username) DO UPDATE SET secret = excluded.secret, created_at = excluded.created_at"
      ].join("\n")).run(username, encodeTotpSecret(secret), nowIso());
    });
    sendJson(response, 200, {
      username,
      totpSecret: secret,
      otpauthUrl: "otpauth://totp/yySlide:" + encodeURIComponent(username)
        + "?secret=" + secret + "&issuer=yySlide"
    });
    return;
  }

  if (key === "POST /v1/auth/register/finish") {
    const body = await readBody(request, 64 * 1024);
    assertAllowedFields(body, ["username", "password", "totpCode", "wrappedVaultKey", "recoveryKeys"]);
    const username = normalizeUsername(body.username);
    enforceRateLimit(request, "register-finish-user", username, 12, 15 * 60 * 1000);
    const passwordError = passwordValidationError(body.password);
    if (passwordError) {
      throw httpError(400, passwordError);
    }
    if (!validTotpCode(body.totpCode)) {
      throw httpError(400, "2FA 验证码格式无效。");
    }
    validateEncryptionBox(body.wrappedVaultKey, "加密 vault key", true, 4096);
    const recoveryKeys = validateRecoveryKeys(body.recoveryKeys);
    if (databaseHandle.prepare("SELECT 1 AS ok FROM users WHERE username = ?").get(username)) {
      throw httpError(409, "用户名已存在。");
    }
    if (Number(databaseHandle.prepare("SELECT COUNT(*) AS count FROM users").get().count) >= maxUsers) {
      throw httpError(503, "服务器账号配额已满。");
    }
    const pendingRow = databaseHandle.prepare("SELECT secret FROM pending_registrations WHERE username = ?").get(username);
    if (!pendingRow) {
      throw httpError(400, "注册会话已过期，请重新开始。");
    }
    const pendingSecret = decodeTotpSecret(pendingRow.secret);
    if (!verifyTotp(pendingSecret, body.totpCode)) {
      throw httpError(400, "2FA 验证码不正确。");
    }
    const passwordHash = await hashSecret(body.password);
    const recoveryCodes = await hashRecoveryKeys(recoveryKeys);
    const user = {
      id: crypto.randomUUID(),
      username,
      wrappedVaultKey: body.wrappedVaultKey,
      vault: null,
      createdAt: nowIso()
    };
    const session = makeSession(request);
    runTransaction(databaseHandle, () => {
      if (databaseHandle.prepare("SELECT 1 AS ok FROM users WHERE username = ?").get(username)) {
        throw httpError(409, "用户名已存在。");
      }
      const currentPending = databaseHandle.prepare("SELECT secret FROM pending_registrations WHERE username = ?").get(username);
      if (!currentPending || !safeEqual(decodeTotpSecret(currentPending.secret), pendingSecret)) {
        throw httpError(409, "注册会话已更新，请重新开始。");
      }
      const userCount = Number(databaseHandle.prepare("SELECT COUNT(*) AS count FROM users").get().count);
      if (userCount >= maxUsers) {
        throw httpError(503, "服务器账号配额已满。");
      }
      databaseHandle.prepare([
        "INSERT INTO users (id, username, password_hash, totp_secret, wrapped_vault_key, vault, created_at)",
        "VALUES (?, ?, ?, ?, ?, NULL, ?)"
      ].join("\n")).run(
        user.id,
        username,
        stringifyJson(passwordHash),
        encodeTotpSecret(pendingSecret),
        stringifyJson(body.wrappedVaultKey),
        user.createdAt
      );
      const insertRecovery = databaseHandle.prepare([
        "INSERT INTO recovery_codes (user_id, code_order, hash, key_box, used_at)",
        "VALUES (?, ?, ?, ?, NULL)"
      ].join("\n"));
      recoveryCodes.forEach((code, index) => {
        insertRecovery.run(user.id, index, stringifyJson(code.hash), stringifyJson(code.keyBox));
      });
      insertSession(databaseHandle, user.id, session);
      databaseHandle.prepare("DELETE FROM pending_registrations WHERE username = ?").run(username);
    });
    sendJson(response, 200, {
      token: session.token,
      user: publicUser(user),
      wrappedVaultKey: user.wrappedVaultKey
    });
    return;
  }

  if (key === "POST /v1/auth/login") {
    const body = await readBody(request, 8 * 1024);
    assertAllowedFields(body, ["username", "password", "totpCode"]);
    const username = normalizeUsername(body.username);
    enforceRateLimit(request, "login-user", username, 8, 15 * 60 * 1000);
    if (typeof body.password !== "string" || body.password.length > 256
        || Buffer.byteLength(body.password, "utf8") > 1024 || !validTotpCode(body.totpCode)) {
      throw httpError(401, "登录信息不正确。");
    }
    const row = databaseHandle.prepare([
      "SELECT id, username, password_hash, totp_secret, wrapped_vault_key, vault, created_at",
      "FROM users WHERE username = ?"
    ].join("\n")).get(username);
    const user = userFromRow(row, false);
    const passwordHash = user && user.passwordHash || dummyPasswordHash;
    const passwordOk = await verifySecret(body.password, passwordHash);
    if (!user || !passwordOk) {
      throw httpError(401, "登录信息不正确。");
    }
    const totpSecret = decodeTotpSecret(row.totp_secret);
    if (!verifyTotp(totpSecret, body.totpCode)) {
      throw httpError(401, "登录信息不正确。");
    }
    const session = makeSession(request);
    const removedSessions = runTransaction(databaseHandle, () => {
      const current = databaseHandle.prepare("SELECT password_hash FROM users WHERE id = ?").get(user.id);
      if (!current || current.password_hash !== row.password_hash) {
        throw httpError(401, "登录信息已更新，请重试。");
      }
      const removed = pruneSessionsForNewSession(databaseHandle, user.id);
      insertSession(databaseHandle, user.id, session);
      return removed;
    });
    closeSseForSessionIds(removedSessions);
    sendJson(response, 200, {
      token: session.token,
      user: publicUser(user),
      wrappedVaultKey: user.wrappedVaultKey
    });
    return;
  }

  if (key === "POST /v1/auth/reset/start") {
    const body = await readBody(request, 8 * 1024);
    assertAllowedFields(body, ["username", "recoveryCode"]);
    const username = normalizeUsername(body.username);
    enforceRateLimit(request, "reset-start-user", username, 5, 60 * 60 * 1000);
    if (typeof body.recoveryCode !== "string") {
      throw httpError(401, "账号或恢复码不正确。");
    }
    const recoveryCode = body.recoveryCode.trim().toUpperCase();
    if (!/^[A-Z0-9_-]{5}-[A-Z0-9_-]{5}$/.test(recoveryCode)) {
      throw httpError(401, "账号或恢复码不正确。");
    }
    const user = getUserByUsername(databaseHandle, username, false);
    if (!user) {
      await verifySecret(recoveryCode, dummyPasswordHash);
      throw httpError(401, "账号或恢复码不正确。");
    }
    const rows = databaseHandle.prepare([
      "SELECT code_order, hash, key_box, used_at FROM recovery_codes",
      "WHERE user_id = ? ORDER BY code_order ASC LIMIT ?"
    ].join("\n")).all(user.id, MAX_RECOVERY_KEYS + 1);
    if (rows.length !== MAX_RECOVERY_KEYS) {
      throw httpError(409, "恢复码配置异常，请联系管理员。");
    }
    let matchedRow = null;
    for (const row of rows) {
      const matches = !row.used_at && await verifySecret(recoveryCode, parseJson(row.hash, null));
      if (matches) {
        matchedRow = row;
      }
    }
    if (!matchedRow) {
      throw httpError(401, "账号或恢复码不正确。");
    }
    const recoveryKeyBox = parseJson(matchedRow.key_box, null);
    if (!recoveryKeyBox || !recoveryKeyBox.ciphertext) {
      throw httpError(409, "这个恢复码不能用于重置，请使用新版恢复码。");
    }
    const secret = generateTotpSecret();
    const reset = {
      id: crypto.randomUUID(),
      userId: user.id,
      recoveryIndex: matchedRow.code_order,
      secret,
      createdAt: nowIso()
    };
    runTransaction(databaseHandle, () => {
      const currentRecovery = databaseHandle.prepare([
        "SELECT hash, key_box, used_at FROM recovery_codes WHERE user_id = ? AND code_order = ?"
      ].join("\n")).get(user.id, matchedRow.code_order);
      if (!currentRecovery || currentRecovery.used_at
          || currentRecovery.hash !== matchedRow.hash
          || currentRecovery.key_box !== matchedRow.key_box) {
        throw httpError(409, "恢复码状态已更新，请重试。");
      }
      databaseHandle.prepare("DELETE FROM pending_resets WHERE user_id = ?").run(user.id);
      databaseHandle.prepare([
        "INSERT INTO pending_resets (id, user_id, recovery_index, secret, created_at)",
        "VALUES (?, ?, ?, ?, ?)"
      ].join("\n")).run(reset.id, user.id, reset.recoveryIndex, encodeTotpSecret(secret), reset.createdAt);
    });
    sendJson(response, 200, {
      resetId: reset.id,
      totpSecret: secret,
      otpauthUrl: "otpauth://totp/yySlide:" + encodeURIComponent(username)
        + "?secret=" + secret + "&issuer=yySlide",
      recoveryKeyBox
    });
    return;
  }

  if (key === "POST /v1/auth/reset/finish") {
    const body = await readBody(request, 64 * 1024);
    assertAllowedFields(body, ["username", "password", "totpCode", "resetId", "wrappedVaultKey", "recoveryKeys"]);
    const username = normalizeUsername(body.username);
    enforceRateLimit(request, "reset-finish-user", username, 10, 15 * 60 * 1000);
    const passwordError = passwordValidationError(body.password);
    if (passwordError) {
      throw httpError(400, passwordError);
    }
    if (!validTotpCode(body.totpCode) || typeof body.resetId !== "string" || body.resetId.length > 64) {
      throw httpError(400, "重置验证信息无效。");
    }
    validateEncryptionBox(body.wrappedVaultKey, "加密 vault key", true, 4096);
    const recoveryKeys = validateRecoveryKeys(body.recoveryKeys);
    const row = databaseHandle.prepare([
      "SELECT r.id AS reset_id, r.user_id, r.secret AS reset_secret, r.recovery_index,",
      "u.id, u.username, u.vault, u.created_at",
      "FROM pending_resets r JOIN users u ON u.id = r.user_id",
      "WHERE r.id = ? AND u.username = ?"
    ].join("\n")).get(body.resetId, username);
    if (!row) {
      throw httpError(400, "重置会话已过期，请重新开始。");
    }
    const resetSecret = decodeTotpSecret(row.reset_secret);
    if (!verifyTotp(resetSecret, body.totpCode)) {
      throw httpError(400, "2FA 验证码不正确。");
    }
    const passwordHash = await hashSecret(body.password);
    const recoveryCodes = await hashRecoveryKeys(recoveryKeys);
    const session = makeSession(request);
    const oldSessionIds = runTransaction(databaseHandle, () => {
      const currentReset = databaseHandle.prepare([
        "SELECT secret FROM pending_resets WHERE id = ? AND user_id = ?"
      ].join("\n")).get(row.reset_id, row.user_id);
      if (!currentReset || !safeEqual(decodeTotpSecret(currentReset.secret), resetSecret)) {
        throw httpError(409, "重置会话已更新，请重新开始。");
      }
      const sessionIds = databaseHandle.prepare("SELECT id FROM sessions WHERE user_id = ?").all(row.user_id)
        .map((item) => item.id);
      databaseHandle.prepare([
        "UPDATE users SET password_hash = ?, totp_secret = ?, wrapped_vault_key = ? WHERE id = ?"
      ].join("\n")).run(
        stringifyJson(passwordHash),
        encodeTotpSecret(resetSecret),
        stringifyJson(body.wrappedVaultKey),
        row.user_id
      );
      databaseHandle.prepare("DELETE FROM sessions WHERE user_id = ?").run(row.user_id);
      databaseHandle.prepare("DELETE FROM recovery_codes WHERE user_id = ?").run(row.user_id);
      const insertRecovery = databaseHandle.prepare([
        "INSERT INTO recovery_codes (user_id, code_order, hash, key_box, used_at)",
        "VALUES (?, ?, ?, ?, NULL)"
      ].join("\n"));
      recoveryCodes.forEach((code, index) => {
        insertRecovery.run(row.user_id, index, stringifyJson(code.hash), stringifyJson(code.keyBox));
      });
      insertSession(databaseHandle, row.user_id, session);
      databaseHandle.prepare("DELETE FROM pending_resets WHERE user_id = ?").run(row.user_id);
      return sessionIds;
    });
    closeSseForSessionIds(oldSessionIds);
    const user = {
      id: row.user_id,
      username: row.username,
      wrappedVaultKey: body.wrappedVaultKey,
      vault: parseJson(row.vault, null),
      createdAt: row.created_at
    };
    sendJson(response, 200, {
      token: session.token,
      user: publicUser(user),
      wrappedVaultKey: user.wrappedVaultKey
    });
    return;
  }

  if (key === "GET /v1/me") {
    const auth = authenticateRequest(request);
    const devices = databaseHandle.prepare([
      "SELECT id, created_at, last_used_at, expires_at, user_agent, ip",
      "FROM sessions WHERE user_id = ? AND expires_at > ?",
      "ORDER BY created_at ASC"
    ].join("\n")).all(auth.user.id, nowIso()).map(sessionFromRow);
    sendJson(response, 200, {
      user: publicUser(auth.user),
      devices: devices.map(publicDevice)
    });
    return;
  }

  if (key === "POST /v1/auth/logout") {
    const auth = authenticateRequest(request);
    databaseHandle.prepare("DELETE FROM sessions WHERE id = ? AND user_id = ?").run(auth.session.id, auth.user.id);
    closeSseForSessionIds([auth.session.id]);
    sendJson(response, 200, { ok: true });
    return;
  }

  if (key === "GET /v1/vault/events") {
    const auth = authenticateRequest(request);
    assertSseCapacity(auth.user.id, clientIp(request));
    setResponseSecurityHeaders(response);
    response.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-store, no-transform",
      "Connection": "keep-alive",
      "X-Accel-Buffering": "no"
    });
    if (typeof response.flushHeaders === "function") {
      response.flushHeaders();
    }
    addVaultEventClient(request, response, auth, clientIdFromRequest(request));
    return;
  }

  if (key === "GET /v1/vault") {
    const auth = authenticateRequest(request);
    sendJson(response, 200, {
      vault: auth.user.vault || null,
      wrappedVaultKey: auth.user.wrappedVaultKey
    });
    return;
  }

  if (key === "PUT /v1/vault") {
    const body = await readBody(request, maxVaultBytes + 32 * 1024);
    assertAllowedFields(body, ["vault", "baseUpdatedAt"]);
    const auth = authenticateRequest(request);
    validateVault(body.vault);
    if (!Object.prototype.hasOwnProperty.call(body, "baseUpdatedAt")
        || body.baseUpdatedAt != null
          && (typeof body.baseUpdatedAt !== "string" || body.baseUpdatedAt.length > 40
            || !Number.isFinite(Date.parse(body.baseUpdatedAt)))) {
      throw httpError(409, "同步客户端版本过旧，请升级后重新同步。");
    }
    const result = runTransaction(databaseHandle, () => {
      const activeSession = databaseHandle.prepare([
        "SELECT 1 AS ok FROM sessions WHERE id = ? AND user_id = ? AND expires_at > ?"
      ].join("\n")).get(auth.session.id, auth.user.id, nowIso());
      if (!activeSession) {
        throw httpError(401, "登录已失效。");
      }
      const row = databaseHandle.prepare("SELECT vault, wrapped_vault_key FROM users WHERE id = ?").get(auth.user.id);
      if (!row) {
        throw httpError(401, "登录已失效。");
      }
      const currentVault = parseJson(row.vault, null);
      const currentUpdatedAt = currentVault && currentVault.updatedAt || null;
      if ((body.baseUpdatedAt || null) !== currentUpdatedAt) {
        return {
          conflict: true,
          vault: currentVault,
          wrappedVaultKey: parseJson(row.wrapped_vault_key, null)
        };
      }
      const nextVault = {
        ...body.vault,
        updatedAt: nextIsoAfter(currentUpdatedAt)
      };
      databaseHandle.prepare("UPDATE users SET vault = ? WHERE id = ?")
        .run(stringifyJson(nextVault), auth.user.id);
      return { conflict: false, vault: nextVault };
    });
    if (result.conflict) {
      sendJson(response, 409, {
        error: "云端数据已更新，请重新同步。",
        vault: result.vault,
        wrappedVaultKey: result.wrappedVaultKey
      });
      return;
    }
    sendJson(response, 200, { vault: result.vault });
    broadcastVaultChanged(auth.user.id, result.vault, clientIdFromRequest(request));
    return;
  }

  throw httpError(404, "接口不存在。");
}

function handleRequestError(request, response, error) {
  if (response.headersSent) {
    if (!response.writableEnded) {
      response.end();
    }
    return;
  }
  const status = Number.isInteger(error && error.status) ? error.status : 500;
  if (status >= 500 && !(error && error.expose)) {
    const requestId = crypto.randomUUID();
    const errorType = error && (error.code || error.name) || "Error";
    console.error("[" + requestId + "] " + String(request.method || "REQUEST").slice(0, 16)
      + " failed with internal error (" + errorType + ").");
    sendJson(response, 500, { error: "服务器内部错误。", requestId });
    return;
  }
  sendJson(
    response,
    status,
    { error: error && error.expose ? error.message : "服务器内部错误。" },
    error && error.headers
  );
}

function closeDatabase() {
  if (sqlDb) {
    try {
      sqlDb.close();
    } catch {
      // The process is already shutting down.
    }
    sqlDb = null;
  }
}

function sanitizeSqliteStorage(databaseHandle) {
  databaseHandle.exec("PRAGMA wal_checkpoint(TRUNCATE);");
  databaseHandle.exec("PRAGMA secure_delete = ON;");
  databaseHandle.exec("VACUUM;");
  databaseHandle.exec("PRAGMA wal_checkpoint(TRUNCATE);");
}

function start() {
  const databaseHandle = database();
  cleanExpiredRecords(databaseHandle, true);
  const migratedSensitiveData = migrationSummary.encryptedTotpValues > 0
    || !["none", "left-in-place"].includes(migrationSummary.legacy);
  if (migrateOnly || migratedSensitiveData) {
    sanitizeSqliteStorage(databaseHandle);
  }
  if (migrateOnly) {
    console.log(JSON.stringify(inspectDatabase(databaseHandle), null, 2));
    closeDatabase();
    return;
  }

  const server = http.createServer((request, response) => {
    setCorsHeaders(request, response);
    Promise.resolve(handle(request, response)).catch((error) => {
      handleRequestError(request, response, error);
    });
  });
  server.requestTimeout = 30000;
  server.headersTimeout = 15000;
  server.keepAliveTimeout = 5000;
  server.maxHeadersCount = 64;
  server.on("clientError", (_error, socket) => {
    if (socket.writable) {
      socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
    }
  });
  server.listen(port, host, () => {
    console.log("yySlide Cloud listening on http://" + host + ":" + port);
    if (!isProduction) {
      console.log("database: " + path.basename(dbPath));
    }
  });

  let shuttingDown = false;
  const shutdown = () => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    for (const userId of [...vaultEventClients.keys()]) {
      closeAllSseForUser(userId);
    }
    server.close(() => {
      closeDatabase();
      process.exit(0);
    });
    const timer = setTimeout(() => {
      closeDatabase();
      process.exit(1);
    }, 5000);
    if (typeof timer.unref === "function") {
      timer.unref();
    }
  };
  process.once("SIGTERM", shutdown);
  process.once("SIGINT", shutdown);
}

try {
  start();
} catch (error) {
  const errorType = error && (error.code || error.name) || "Error";
  console.error("yySlide Cloud failed to start (" + errorType + ").");
  if (!isProduction && error && error.message) {
    console.error(error.message);
  }
  closeDatabase();
  process.exitCode = 1;
}
