const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const MAX_CONNECTIONS = 5000;
const MAX_GROUPS = 1000;
const MAX_TOMBSTONES = 10000;
const MAX_PRIVATE_KEY_BYTES = 1024 * 1024;
const WINDOWS_RESERVED_NAME = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i;

function isPlainObject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function boundedString(value, label, maxLength, { required = false } = {}) {
  if (value == null) {
    if (required) {
      throw new Error(`${label} is required.`);
    }
    return "";
  }
  if (typeof value !== "string") {
    throw new Error(`${label} must be a string.`);
  }
  const normalized = value.normalize("NFC");
  if (required && !normalized.trim()) {
    throw new Error(`${label} is required.`);
  }
  if (normalized.length > maxLength) {
    throw new Error(`${label} is too long.`);
  }
  if (normalized.includes("\0")) {
    throw new Error(`${label} contains an invalid character.`);
  }
  return normalized;
}

function boundedArray(value, label, maxLength) {
  if (value == null) {
    return [];
  }
  if (!Array.isArray(value)) {
    throw new Error(`${label} must be an array.`);
  }
  if (value.length > maxLength) {
    throw new Error(`${label} contains too many items.`);
  }
  return value;
}

function normalizedTimestamp(value, label) {
  if (!value) {
    return "";
  }
  const text = boundedString(value, label, 64);
  if (!Number.isFinite(Date.parse(text))) {
    throw new Error(`${label} is invalid.`);
  }
  return text;
}

function normalizeVaultId(value, label = "id") {
  const id = boundedString(value, label, 160, { required: true }).trim();
  if (/^[.]+$/.test(id) || /[\x00-\x1f\x7f]/.test(id)) {
    throw new Error(`${label} is invalid.`);
  }
  return id;
}

function normalizeVaultConnection(value, index) {
  if (!isPlainObject(value)) {
    throw new Error(`connections[${index}] must be an object.`);
  }
  const authType = value.authType === "key" ? "key" : value.authType === "password" ? "password" : null;
  if (!authType) {
    throw new Error(`connections[${index}].authType is invalid.`);
  }
  const port = Number(value.port ?? 22);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`connections[${index}].port is invalid.`);
  }
  const privateKey = boundedString(value.privateKey, `connections[${index}].privateKey`, MAX_PRIVATE_KEY_BYTES);
  if (Buffer.byteLength(privateKey, "utf8") > MAX_PRIVATE_KEY_BYTES) {
    throw new Error(`connections[${index}].privateKey is too large.`);
  }
  return {
    id: normalizeVaultId(value.id, `connections[${index}].id`),
    name: boundedString(value.name, `connections[${index}].name`, 512),
    host: boundedString(value.host, `connections[${index}].host`, 1024, { required: true }).trim(),
    port,
    username: boundedString(value.username, `connections[${index}].username`, 512, { required: true }).trim(),
    authType,
    group: boundedString(value.group, `connections[${index}].group`, 512).trim(),
    updatedAt: normalizedTimestamp(value.updatedAt, `connections[${index}].updatedAt`),
    password: boundedString(value.password, `connections[${index}].password`, 65536),
    passphrase: boundedString(value.passphrase, `connections[${index}].passphrase`, 65536),
    privateKey
  };
}

function normalizeVaultGroup(value, index) {
  if (!isPlainObject(value)) {
    throw new Error(`groups[${index}] must be an object.`);
  }
  const sortOrder = Number(value.sortOrder ?? index);
  if (!Number.isFinite(sortOrder) || Math.abs(sortOrder) > 1_000_000) {
    throw new Error(`groups[${index}].sortOrder is invalid.`);
  }
  return {
    id: normalizeVaultId(value.id, `groups[${index}].id`),
    label: boundedString(value.label ?? value.name, `groups[${index}].label`, 512, { required: true }).trim(),
    sortOrder,
    updatedAt: normalizedTimestamp(value.updatedAt, `groups[${index}].updatedAt`)
  };
}

function normalizeDeletedConnection(value, index) {
  if (!isPlainObject(value)) {
    throw new Error(`deletedConnections[${index}] must be an object.`);
  }
  return {
    id: normalizeVaultId(value.id, `deletedConnections[${index}].id`),
    deletedAt: normalizedTimestamp(value.deletedAt, `deletedConnections[${index}].deletedAt`)
  };
}

function normalizeDeletedGroup(value, index) {
  if (!isPlainObject(value)) {
    throw new Error(`deletedGroups[${index}] must be an object.`);
  }
  const id = value.id ? normalizeVaultId(value.id, `deletedGroups[${index}].id`) : "";
  const label = boundedString(value.label, `deletedGroups[${index}].label`, 512).trim();
  if (!id && !label) {
    throw new Error(`deletedGroups[${index}] needs an id or label.`);
  }
  return {
    id,
    label,
    deletedAt: normalizedTimestamp(value.deletedAt, `deletedGroups[${index}].deletedAt`)
  };
}

function normalizeVaultSnapshot(snapshot) {
  if (!isPlainObject(snapshot)) {
    throw new Error("同步数据格式无效。");
  }
  const version = Number(snapshot.version ?? 1);
  if (!Number.isInteger(version) || version < 1 || version > 3) {
    throw new Error("同步数据版本不受支持。");
  }
  const groups = boundedArray(snapshot.groups, "groups", MAX_GROUPS).map(normalizeVaultGroup);
  const connections = boundedArray(snapshot.connections, "connections", MAX_CONNECTIONS).map(normalizeVaultConnection);
  if (new Set(groups.map((group) => group.id)).size !== groups.length) {
    throw new Error("同步数据包含重复的分类 ID。");
  }
  if (new Set(connections.map((connection) => connection.id)).size !== connections.length) {
    throw new Error("同步数据包含重复的连接 ID。");
  }
  return {
    version,
    exportedAt: normalizedTimestamp(snapshot.exportedAt, "exportedAt"),
    groups,
    connections,
    deletedConnections: boundedArray(snapshot.deletedConnections, "deletedConnections", MAX_TOMBSTONES).map(normalizeDeletedConnection),
    deletedGroups: boundedArray(snapshot.deletedGroups, "deletedGroups", MAX_TOMBSTONES).map(normalizeDeletedGroup)
  };
}

function safeLocalName(name, fallback = "download") {
  let cleanName = String(name || "")
    .normalize("NFC")
    .replace(/[<>:"/\\|?*\x00-\x1f]/g, "_")
    .replace(/\s+/g, " ")
    .replace(/[. ]+$/g, "")
    .trim();
  if (!cleanName || cleanName === "." || cleanName === "..") {
    cleanName = fallback;
  }
  if (WINDOWS_RESERVED_NAME.test(cleanName)) {
    cleanName = `_${cleanName}`;
  }
  if (cleanName.length > 180) {
    const extension = path.extname(cleanName).slice(0, 32);
    cleanName = `${cleanName.slice(0, Math.max(1, 180 - extension.length))}${extension}`;
  }
  return cleanName || fallback;
}

function uniqueRelativeChildName(parentPath, name, usedPaths) {
  const safeName = safeLocalName(name, "item");
  const extension = path.posix.extname(safeName);
  const stem = extension ? safeName.slice(0, -extension.length) : safeName;
  let candidate = safeName;
  let index = 2;
  const keyFor = (value) => path.posix.join(parentPath, value).normalize("NFC").toLocaleLowerCase("en-US");
  while (usedPaths.has(keyFor(candidate))) {
    candidate = `${stem}-${index}${extension}`;
    index += 1;
  }
  usedPaths.add(keyFor(candidate));
  return candidate;
}

function safeStorageKey(id) {
  return crypto.createHash("sha256").update(normalizeVaultId(id)).digest("hex");
}

function accountScopeId(serverUrl, user) {
  const identity = String(user?.id || user?.username || "").trim().toLocaleLowerCase("en-US");
  if (!identity) {
    throw new Error("账号身份无效。");
  }
  const parsed = new URL(serverUrl);
  const pathname = parsed.pathname.replace(/\/+$/g, "");
  const normalizedUrl = `${parsed.origin.toLocaleLowerCase("en-US")}${pathname}`;
  return `account-${crypto.createHash("sha256").update(`${normalizedUrl}\0${identity}`).digest("hex")}`;
}

function isSafeScopeId(value) {
  return value === "local" || /^account-[a-f0-9]{64}$/.test(String(value || ""));
}

function createTransferBudget({ maxEntries, maxDepth, maxFileBytes, maxTotalBytes }) {
  let entries = 0;
  let totalBytes = 0;
  return {
    add({ depth, size = 0, symbolicLink = false, label = "item" }) {
      entries += 1;
      if (entries > maxEntries) {
        throw new Error(`Too many transfer entries (maximum ${maxEntries}).`);
      }
      if (!Number.isInteger(depth) || depth < 0 || depth > maxDepth) {
        throw new Error(`Transfer depth exceeds ${maxDepth}: ${label}`);
      }
      if (symbolicLink) {
        throw new Error(`Symbolic links are not allowed in recursive transfers: ${label}`);
      }
      if (!Number.isSafeInteger(size) || size < 0 || size > maxFileBytes) {
        throw new Error(`Transfer file is too large: ${label}`);
      }
      totalBytes += size;
      if (!Number.isSafeInteger(totalBytes) || totalBytes > maxTotalBytes) {
        throw new Error("Transfer total size exceeds the safety limit.");
      }
      return { entries, totalBytes };
    },
    snapshot() {
      return { entries, totalBytes };
    }
  };
}

function replaceFileFromTemp(tempPath, targetPath) {
  const backupPath = `${targetPath}.backup-${process.pid}-${crypto.randomUUID()}`;
  const hadTarget = fs.existsSync(targetPath);
  try {
    if (hadTarget) {
      fs.renameSync(targetPath, backupPath);
    }
    fs.renameSync(tempPath, targetPath);
    if (hadTarget) {
      try {
        fs.rmSync(backupPath, { force: true });
      } catch {
        // The committed target is valid; a stale backup can be cleaned later.
      }
    }
  } catch (error) {
    if (!fs.existsSync(targetPath) && hadTarget && fs.existsSync(backupPath)) {
      try {
        fs.renameSync(backupPath, targetPath);
      } catch {
        // Keep the backup when restoration is not possible.
      }
    }
    throw error;
  } finally {
    try {
      fs.rmSync(tempPath, { force: true });
    } catch {
      // Ignore temporary-file cleanup failures.
    }
  }
}

function atomicWriteFile(filePath, content, options = {}) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tempPath = path.join(path.dirname(filePath), `.${path.basename(filePath)}.${process.pid}.${crypto.randomUUID()}.tmp`);
  const handle = fs.openSync(tempPath, "wx", options.mode ?? 0o600);
  try {
    fs.writeFileSync(handle, content, { encoding: options.encoding || "utf8" });
    fs.fsyncSync(handle);
  } finally {
    fs.closeSync(handle);
  }
  replaceFileFromTemp(tempPath, filePath);
}

function atomicWriteJson(filePath, value) {
  atomicWriteFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
}

module.exports = {
  MAX_CONNECTIONS,
  MAX_GROUPS,
  MAX_PRIVATE_KEY_BYTES,
  accountScopeId,
  atomicWriteFile,
  atomicWriteJson,
  createTransferBudget,
  isPlainObject,
  isSafeScopeId,
  normalizeVaultId,
  normalizeVaultSnapshot,
  replaceFileFromTemp,
  safeLocalName,
  safeStorageKey,
  uniqueRelativeChildName
};
