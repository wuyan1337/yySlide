const { app, BrowserWindow, dialog, ipcMain, Menu, nativeImage, nativeTheme, safeStorage, shell } = require("electron");
const { clipboard } = require("electron");
const fs = require("fs");
const http = require("http");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { fileURLToPath, pathToFileURL } = require("url");
const { TextDecoder } = require("util");
const { Client } = require("ssh2");
const {
  accountScopeId,
  atomicWriteFile,
  atomicWriteJson,
  createTransferBudget,
  isPlainObject,
  isSafeScopeId,
  normalizeVaultSnapshot,
  replaceFileFromTemp,
  safeLocalName,
  safeStorageKey,
  uniqueRelativeChildName
} = require("./security.cjs");

let mainWindow = null;
let nodePty = null;
const sessions = new Map();
const watchedEdits = new Map();
const localTerminals = new Map();
const activeUploads = new Map();
const activeDownloads = new Map();
const dragDownloads = new Map();
let dragDownloadServer = null;
let dragDownloadServerPort = 0;
let cloudEventsAbort = null;
let cloudEventsKey = "";
let cloudEventsReconnectTimer = null;
let cloudSyncPromise = Promise.resolve();
let accountSessionGeneration = 0;
let accountAuthAttemptGeneration = 0;
let accountSessionAbort = new AbortController();
let activeDataScopeId = null;
let allowWindowClose = false;
let rendererHasUnsavedChanges = false;
let closePromptOpen = false;

const appId = "com.yyslide.client";
const cloudClientId = `windows-${crypto.randomUUID()}`;
const gotSingleInstanceLock = app.requestSingleInstanceLock();
const CLOUD_REQUEST_TIMEOUT_MS = 20_000;
const CLOUD_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
const CLOUD_SSE_IDLE_TIMEOUT_MS = 90_000;
const CLOUD_SSE_MAX_BUFFER_BYTES = 512 * 1024;
const MAX_TRANSFER_FILES = 20_000;
const MAX_TRANSFER_DEPTH = 64;
const MAX_TRANSFER_BYTES = 16 * 1024 * 1024 * 1024;
const MAX_SINGLE_TRANSFER_BYTES = 8 * 1024 * 1024 * 1024;

if (!gotSingleInstanceLock) {
  app.exit(0);
}

const defaultSettings = {
  terminalBackground: "#000000",
  terminalForeground: "#f3f7fb",
  terminalBackgroundImage: "",
  terminalBackgroundImageOpacity: 1,
  accentColor: "#2c7ac9"
};

function appAssetPath(...segments) {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, "app.asar", ...segments);
  }
  return path.join(__dirname, "..", ...segments);
}

function iconPath() {
  if (app.isPackaged) {
    const resourceIcon = path.join(process.resourcesPath, "icon.ico");
    if (fs.existsSync(resourceIcon)) {
      return resourceIcon;
    }
  }
  const filePath = appAssetPath("build", "icon.ico");
  return fs.existsSync(filePath) ? filePath : undefined;
}

function windowIcon() {
  const filePath = iconPath();
  if (!filePath) {
    return undefined;
  }
  const image = nativeImage.createFromPath(filePath);
  return image.isEmpty() ? filePath : image;
}

function focusMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return;
  }
  if (mainWindow.isMinimized()) {
    mainWindow.restore();
  }
  mainWindow.show();
  mainWindow.focus();
}

function trustedRendererUrl(rawUrl) {
  try {
    const parsed = new URL(rawUrl || "");
    if (!app.isPackaged && parsed.origin === "http://127.0.0.1:5173") {
      return parsed.pathname === "/" || parsed.pathname === "/index.html";
    }
    if (parsed.protocol !== "file:") {
      return false;
    }
    const rendererPath = path.resolve(fileURLToPath(parsed));
    const appIndexPath = path.resolve(__dirname, "..", "dist", "index.html");
    return rendererPath === appIndexPath;
  } catch {
    return false;
  }
}

function assertTrustedIpc(event) {
  const frameUrl = event.senderFrame?.url || event.sender?.getURL?.() || "";
  const isMainRenderer = mainWindow
    && !mainWindow.isDestroyed()
    && event.sender === mainWindow.webContents
    && event.senderFrame === mainWindow.webContents.mainFrame;
  if (!isMainRenderer || !trustedRendererUrl(frameUrl)) {
    throw new Error("拒绝来自非 yySlide 页面 的 IPC 请求。");
  }
}

function handleIpc(channel, listener) {
  ipcMain.handle(channel, (event, ...args) => {
    assertTrustedIpc(event);
    return listener(event, ...args);
  });
}

function createWindow() {
  allowWindowClose = false;
  rendererHasUnsavedChanges = false;
  const settings = readSettings();
  const icon = windowIcon();
  Menu.setApplicationMenu(null);
  nativeTheme.themeSource = "light";
  mainWindow = new BrowserWindow({
    width: 1500,
    height: 920,
    minWidth: 1120,
    minHeight: 720,
    frame: false,
    backgroundColor: "#ffffff",
    backgroundMaterial: "none",
    icon,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  if (icon) {
    mainWindow.setIcon?.(icon);
  }

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) {
      void shell.openExternal(url);
    }
    return { action: "deny" };
  });
  mainWindow.webContents.on("will-navigate", (event, url) => {
    if (trustedRendererUrl(url)) {
      return;
    }
    event.preventDefault();
    if (/^https?:\/\//i.test(url)) {
      void shell.openExternal(url);
    }
  });
  mainWindow.webContents.session.setPermissionRequestHandler((_webContents, _permission, callback) => {
    callback(false);
  });

  if (!app.isPackaged && process.env.npm_lifecycle_event === "dev") {
    mainWindow.loadURL("http://127.0.0.1:5173");
  } else {
    mainWindow.loadFile(path.join(__dirname, "..", "dist", "index.html"));
  }

  mainWindow.on("close", (event) => {
    if (allowWindowClose || !rendererHasUnsavedChanges) {
      return;
    }
    event.preventDefault();
    if (closePromptOpen) {
      return;
    }
    closePromptOpen = true;
    void dialog.showMessageBox(mainWindow, {
      type: "warning",
      title: "存在未保存文件",
      message: "仍有远端文件没有保存，确定退出 yySlide？",
      detail: "退出后未保存的编辑内容会丢失。",
      buttons: ["继续编辑", "放弃并退出"],
      defaultId: 0,
      cancelId: 0
    }).then((result) => {
      if (result.response === 1 && mainWindow && !mainWindow.isDestroyed()) {
        allowWindowClose = true;
        mainWindow.close();
      }
    }).finally(() => {
      closePromptOpen = false;
    });
  });

  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

function userDataPath(...segments) {
  return path.join(app.getPath("userData"), ...segments);
}

function ensureUserDataDir() {
  fs.mkdirSync(app.getPath("userData"), { recursive: true });
}

function readJson(filePath, fallback, normalize) {
  try {
    if (!fs.existsSync(filePath)) {
      return fallback;
    }
    const stats = fs.statSync(filePath);
    if (!stats.isFile() || stats.size > CLOUD_MAX_RESPONSE_BYTES) {
      throw new Error("JSON file is not a regular file or is too large.");
    }
    const parsed = JSON.parse(fs.readFileSync(filePath, "utf8"));
    return typeof normalize === "function" ? normalize(parsed) : parsed;
  } catch (error) {
    console.error("Failed to read JSON", filePath, error);
    return fallback;
  }
}

function writeJson(filePath, value) {
  ensureUserDataDir();
  atomicWriteJson(filePath, value);
}

function profileRoot(scopeId = currentDataScopeId()) {
  if (!isSafeScopeId(scopeId)) {
    throw new Error("本地数据空间无效。");
  }
  return userDataPath("profiles", scopeId);
}

function currentDataScopeId() {
  if (activeDataScopeId) {
    return activeDataScopeId;
  }
  const store = readJson(accountPath(), null);
  if (store?.dataScopeId && isSafeScopeId(store.dataScopeId)) {
    activeDataScopeId = store.dataScopeId;
  } else if (store?.serverUrl && store?.user) {
    try {
      activeDataScopeId = accountScopeId(store.serverUrl, store.user);
    } catch {
      activeDataScopeId = "local";
    }
  } else {
    activeDataScopeId = "local";
  }
  return activeDataScopeId;
}

function setActiveDataScope(scopeId) {
  if (!isSafeScopeId(scopeId)) {
    throw new Error("本地数据空间无效。");
  }
  activeDataScopeId = scopeId;
  fs.mkdirSync(profileRoot(scopeId), { recursive: true });
}

function connectionsPath() {
  return path.join(profileRoot(), "connections.json");
}

function connectionGroupsPath() {
  return path.join(profileRoot(), "connection-groups.json");
}

function settingsPath() {
  return userDataPath("settings.json");
}

function accountPath() {
  return userDataPath("account.json");
}

function knownHostsPath() {
  return path.join(profileRoot(), "known-hosts.json");
}

function syncStatePath() {
  return path.join(profileRoot(), "sync-state.json");
}

function normalizeStoredSecret(secret) {
  if (!isPlainObject(secret) || !["safeStorage", "base64"].includes(secret.type) || typeof secret.value !== "string" || secret.value.length > 1024 * 1024) {
    return null;
  }
  return { type: secret.type, value: secret.value };
}

function upgradeLegacySecret(secret) {
  const normalized = normalizeStoredSecret(secret);
  if (!normalized || normalized.type === "safeStorage") {
    return normalized;
  }
  const plaintext = decryptSecret(normalized);
  return plaintext ? encryptSecret(plaintext) : null;
}

function normalizeStoredConnection(connection) {
  if (!isPlainObject(connection)) {
    return null;
  }
  const id = String(connection.id || "").trim();
  const host = String(connection.host || "").trim();
  const username = String(connection.username || "").trim();
  const port = Number(connection.port || 22);
  if (!id || id.length > 160 || !host || host.length > 1024 || !username || username.length > 512 || !Number.isInteger(port) || port < 1 || port > 65535) {
    return null;
  }
  return {
    id,
    name: String(connection.name || host).slice(0, 512),
    host,
    port,
    username,
    authType: connection.authType === "key" ? "key" : "password",
    privateKeyPath: String(connection.privateKeyPath || "").slice(0, 32768),
    group: String(connection.group || "").trim().slice(0, 512),
    updatedAt: Number.isFinite(Date.parse(connection.updatedAt || "")) ? connection.updatedAt : new Date(0).toISOString(),
    passwordSecret: normalizeStoredSecret(connection.passwordSecret),
    passphraseSecret: normalizeStoredSecret(connection.passphraseSecret)
  };
}

function readConnections() {
  return readJson(connectionsPath(), [], (value) => (
    Array.isArray(value)
      ? value.slice(0, 5000).map(normalizeStoredConnection).filter(Boolean)
      : []
  ));
}

function writeConnections(connections) {
  const normalized = (Array.isArray(connections) ? connections : [])
    .slice(0, 5000)
    .map(normalizeStoredConnection)
    .filter(Boolean);
  writeJson(connectionsPath(), normalized);
}

function groupLabelKey(label) {
  return String(label || "").trim().toLocaleLowerCase();
}

function normalizeConnectionGroups(groups, existingGroups = []) {
  const existingByLabel = new Map(existingGroups.map((group) => [groupLabelKey(group.label), group]));
  const byLabel = new Map();
  const now = new Date().toISOString();
  for (const item of Array.isArray(groups) ? groups : []) {
    const label = String(typeof item === "string" ? item : item?.label || "").trim().slice(0, 512);
    if (!label) {
      continue;
    }
    const key = groupLabelKey(label);
    const existing = existingByLabel.get(key);
    const source = typeof item === "object" && item ? item : {};
    byLabel.set(key, {
      id: String(source.id || existing?.id || "").trim().slice(0, 160) || crypto.randomUUID(),
      label,
      sortOrder: Number.isFinite(Number(source.sortOrder)) ? Number(source.sortOrder) : (existing?.sortOrder ?? byLabel.size),
      updatedAt: Number.isFinite(Date.parse(source.updatedAt || existing?.updatedAt || ""))
        ? String(source.updatedAt || existing?.updatedAt)
        : now
    });
  }
  return Array.from(byLabel.values()).sort((a, b) => {
    const order = Number(a.sortOrder || 0) - Number(b.sortOrder || 0);
    return order || a.label.localeCompare(b.label);
  });
}

function readConnectionGroups() {
  return normalizeConnectionGroups(readJson(connectionGroupsPath(), [], (value) => Array.isArray(value) ? value.slice(0, 1000) : []));
}

function writeConnectionGroups(groups) {
  writeJson(connectionGroupsPath(), normalizeConnectionGroups(groups, readConnectionGroups()));
}

function readSettings() {
  const stored = readJson(settingsPath(), {}, (value) => isPlainObject(value) ? value : {});
  const color = (value, fallback) => /^#[0-9a-f]{6}$/i.test(String(value || "")) ? String(value) : fallback;
  return {
    terminalBackground: color(stored.terminalBackground, defaultSettings.terminalBackground),
    terminalForeground: color(stored.terminalForeground, defaultSettings.terminalForeground),
    terminalBackgroundImage: typeof stored.terminalBackgroundImage === "string" ? stored.terminalBackgroundImage.slice(0, 32768) : "",
    terminalBackgroundImageOpacity: Number.isFinite(Number(stored.terminalBackgroundImageOpacity))
      ? Math.max(0, Math.min(1, Number(stored.terminalBackgroundImageOpacity)))
      : defaultSettings.terminalBackgroundImageOpacity,
    accentColor: color(stored.accentColor, defaultSettings.accentColor)
  };
}

function writeSettings(settings) {
  const next = readSettingsFromInput(settings);
  writeJson(settingsPath(), next);
  return next;
}

function readSettingsFromInput(settings) {
  const source = { ...readSettings(), ...(isPlainObject(settings) ? settings : {}) };
  const color = (value, fallback) => /^#[0-9a-f]{6}$/i.test(String(value || "")) ? String(value) : fallback;
  return {
    terminalBackground: color(source.terminalBackground, defaultSettings.terminalBackground),
    terminalForeground: color(source.terminalForeground, defaultSettings.terminalForeground),
    terminalBackgroundImage: typeof source.terminalBackgroundImage === "string" ? source.terminalBackgroundImage.slice(0, 32768) : "",
    terminalBackgroundImageOpacity: Number.isFinite(Number(source.terminalBackgroundImageOpacity))
      ? Math.max(0, Math.min(1, Number(source.terminalBackgroundImageOpacity)))
      : defaultSettings.terminalBackgroundImageOpacity,
    accentColor: color(source.accentColor, defaultSettings.accentColor)
  };
}

function migrateLegacyProfileStorage() {
  const account = readAccountStore();
  const scopeId = account?.dataScopeId || "local";
  setActiveDataScope(scopeId);
  if (account) {
    writeAccountStore({
      ...account,
      tokenSecret: upgradeLegacySecret(account.tokenSecret),
      vaultKeySecret: upgradeLegacySecret(account.vaultKeySecret)
    });
  }
  const migrations = [
    ["connections.json", connectionsPath(), (value) => Array.isArray(value) ? value.slice(0, 5000).map(normalizeStoredConnection).filter(Boolean).map((connection) => ({
      ...connection,
      passwordSecret: upgradeLegacySecret(connection.passwordSecret),
      passphraseSecret: upgradeLegacySecret(connection.passphraseSecret)
    })) : []],
    ["connection-groups.json", connectionGroupsPath(), (value) => normalizeConnectionGroups(Array.isArray(value) ? value.slice(0, 1000) : [])],
    ["known-hosts.json", knownHostsPath(), (value) => isPlainObject(value) ? value : {}],
    ["sync-state.json", syncStatePath(), (value) => isPlainObject(value) ? value : {}]
  ];
  for (const [legacyName, destination, normalize] of migrations) {
    const source = userDataPath(legacyName);
    if (!fs.existsSync(source)) {
      continue;
    }
    const value = readJson(source, null, normalize);
    if (value !== null) {
      if (!fs.existsSync(destination)) {
        writeJson(destination, value);
      } else {
        const archivePath = path.join(profileRoot(), "legacy-profile", legacyName);
        if (!fs.existsSync(archivePath)) {
          writeJson(archivePath, value);
        }
      }
      fs.rmSync(source, { force: true });
      if (fs.existsSync(source)) {
        throw new Error(`无法移除已经迁移的旧数据文件：${legacyName}`);
      }
    }
  }
  const scopedConnections = readConnections();
  if (scopedConnections.some((connection) => connection.passwordSecret?.type === "base64" || connection.passphraseSecret?.type === "base64")) {
    writeConnections(scopedConnections.map((connection) => ({
      ...connection,
      passwordSecret: upgradeLegacySecret(connection.passwordSecret),
      passphraseSecret: upgradeLegacySecret(connection.passphraseSecret)
    })));
  }
}

function cleanupTemporaryFiles() {
  const tempRoot = path.resolve(app.getPath("temp"));
  for (const name of ["yySlide-downloads", "yySlide-drag", "yySlide-open"]) {
    const target = path.resolve(tempRoot, name);
    if (path.dirname(target) === tempRoot) {
      try {
        fs.rmSync(target, { recursive: true, force: true });
      } catch {
        // A previous download may still be held by another process.
      }
    }
  }
}

function publicConnection(connection) {
  const {
    passwordSecret,
    passphraseSecret,
    ...safeConnection
  } = connection;
  return {
    ...safeConnection,
    hasPassword: Boolean(passwordSecret),
    hasPassphrase: Boolean(passphraseSecret)
  };
}

function encryptSecret(value) {
  if (!value) {
    return null;
  }
  if (safeStorage.isEncryptionAvailable()) {
    return {
      type: "safeStorage",
      value: safeStorage.encryptString(value).toString("base64")
    };
  }
  throw new Error("系统安全存储不可用，不能保存密码、token 或同步密钥。");
}

function decryptSecret(secret) {
  if (!secret || !secret.value) {
    return undefined;
  }
  try {
    if (secret.type === "safeStorage") {
      return safeStorage.decryptString(Buffer.from(secret.value, "base64"));
    }
    if (secret.type === "base64") {
      return Buffer.from(secret.value, "base64").toString("utf8");
    }
  } catch (error) {
    console.error("Failed to decrypt secret", error);
  }
  return undefined;
}

function normalizeAccountStore(store) {
  if (!isPlainObject(store) || typeof store.serverUrl !== "string") {
    return null;
  }
  let serverUrl;
  try {
    serverUrl = normalizeServerUrl(store.serverUrl);
  } catch {
    return null;
  }
  const user = isPlainObject(store.user) && String(store.user.id || store.user.username || "").trim()
    ? {
        id: String(store.user.id || "").slice(0, 160),
        username: String(store.user.username || "").slice(0, 512),
        createdAt: Number.isFinite(Date.parse(store.user.createdAt || "")) ? store.user.createdAt : undefined,
        vaultUpdatedAt: Number.isFinite(Date.parse(store.user.vaultUpdatedAt || "")) ? store.user.vaultUpdatedAt : null
      }
    : null;
  const tokenSecret = normalizeStoredSecret(store.tokenSecret);
  const vaultKeySecret = normalizeStoredSecret(store.vaultKeySecret);
  if (!user || !tokenSecret || !vaultKeySecret) {
    return null;
  }
  let dataScopeId = isSafeScopeId(store.dataScopeId) ? store.dataScopeId : null;
  if (!dataScopeId) {
    try {
      dataScopeId = accountScopeId(serverUrl, user);
    } catch {
      return null;
    }
  }
  return {
    serverUrl,
    tokenSecret,
    vaultKeySecret,
    user,
    dataScopeId,
    sessionId: typeof store.sessionId === "string" && /^[a-f0-9-]{16,80}$/i.test(store.sessionId)
      ? store.sessionId
      : crypto.randomUUID(),
    syncEnabled: Boolean(store.syncEnabled),
    savedAt: Number.isFinite(Date.parse(store.savedAt || "")) ? store.savedAt : new Date().toISOString(),
    lastSyncAt: Number.isFinite(Date.parse(store.lastSyncAt || "")) ? store.lastSyncAt : null
  };
}

function readAccountStore() {
  return readJson(accountPath(), null, normalizeAccountStore);
}

function writeAccountStore(store) {
  const normalized = normalizeAccountStore(store);
  if (!normalized) {
    throw new Error("账号会话格式无效。");
  }
  writeJson(accountPath(), normalized);
}

function clearAccountStore() {
  try {
    fs.rmSync(accountPath(), { force: true });
  } catch (error) {
    try {
      atomicWriteJson(accountPath(), { revokedAt: new Date().toISOString() });
    } catch {
      throw new Error("无法清除本地登录凭据，请关闭程序后重试。", { cause: error });
    }
  }
  if (fs.existsSync(accountPath())) {
    const remaining = readJson(accountPath(), null);
    if (remaining?.tokenSecret || remaining?.vaultKeySecret) {
      throw new Error("无法清除本地登录凭据，请关闭程序后重试。");
    }
  }
}

function accountPublicState() {
  const store = readAccountStore();
  if (!store?.serverUrl || !store?.tokenSecret || !store?.vaultKeySecret) {
    return { loggedIn: false, syncEnabled: false };
  }
  return {
    loggedIn: true,
    serverUrl: store.serverUrl,
    username: store.user?.username || "",
    user: store.user || null,
    syncEnabled: Boolean(store.syncEnabled),
    lastSyncAt: store.lastSyncAt || null
  };
}

function rotateAccountSession() {
  accountSessionGeneration += 1;
  accountAuthAttemptGeneration += 1;
  accountSessionAbort.abort();
  accountSessionAbort = new AbortController();
  cloudSyncPromise = Promise.resolve();
}

function beginAccountAuthAttempt() {
  accountAuthAttemptGeneration += 1;
  return {
    authAttemptGeneration: accountAuthAttemptGeneration,
    sessionGeneration: accountSessionGeneration
  };
}

function assertCurrentAccountAuthAttempt(attempt) {
  if (
    attempt.authAttemptGeneration !== accountAuthAttemptGeneration
    || attempt.sessionGeneration !== accountSessionGeneration
  ) {
    const error = new Error("账号认证请求已经过期，结果已丢弃。");
    error.name = "AbortError";
    throw error;
  }
}

function assertCurrentAccountSession(session) {
  const store = readAccountStore();
  if (
    session.signal.aborted ||
    session.generation !== accountSessionGeneration ||
    !store ||
    store.sessionId !== session.sessionId ||
    store.dataScopeId !== session.dataScopeId ||
    currentDataScopeId() !== session.dataScopeId
  ) {
    const error = new Error("账号会话已经变化，操作已取消。");
    error.name = "AbortError";
    throw error;
  }
  return store;
}

function updateAccountStoreForSession(session, update) {
  const latest = assertCurrentAccountSession(session);
  const next = typeof update === "function" ? update(latest) : { ...latest, ...update };
  assertCurrentAccountSession(session);
  writeAccountStore(next);
}

function normalizeServerUrl(serverUrl) {
  const value = String(serverUrl || "").trim().replace(/\/+$/g, "");
  if (!/^https?:\/\/[^\s/$.?#].[^\s]*$/i.test(value)) {
    throw new Error("同步服务器地址无效。");
  }
  const parsed = new URL(value);
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error("同步服务器地址不能包含账号、查询参数或片段。");
  }
  const isLocalHttp = parsed.protocol === "http:"
    && ["127.0.0.1", "localhost", "[::1]"].includes(parsed.hostname);
  if (parsed.protocol !== "https:" && !isLocalHttp) {
    throw new Error("同步服务器必须使用 HTTPS。");
  }
  return value;
}

function unwrapLocalSecret(secret, label) {
  const value = decryptSecret(secret);
  if (!value) {
    throw new Error(`${label} 不可用，请重新登录。`);
  }
  return value;
}

function storeAccountSession({ serverUrl, token, vaultKey, user, syncEnabled = false }) {
  const dataScopeId = accountScopeId(serverUrl, user);
  const nextStore = {
    serverUrl,
    tokenSecret: encryptSecret(token),
    vaultKeySecret: encryptSecret(vaultKey),
    user,
    dataScopeId,
    sessionId: crypto.randomUUID(),
    syncEnabled,
    savedAt: new Date().toISOString(),
    lastSyncAt: null
  };
  fs.mkdirSync(profileRoot(dataScopeId), { recursive: true });
  rotateAccountSession();
  writeAccountStore(nextStore);
  activeDataScopeId = dataScopeId;
}

function currentAccountSession(expectedSessionId) {
  const store = readAccountStore();
  if (!store?.serverUrl || !store?.tokenSecret || !store?.vaultKeySecret) {
    throw new Error("还没有登录。");
  }
  if (expectedSessionId && store.sessionId !== expectedSessionId) {
    const error = new Error("账号会话已经变化，操作已取消。");
    error.name = "AbortError";
    throw error;
  }
  if (currentDataScopeId() !== store.dataScopeId) {
    setActiveDataScope(store.dataScopeId);
  }
  return {
    store,
    serverUrl: store.serverUrl,
    token: unwrapLocalSecret(store.tokenSecret, "登录 token"),
    vaultKey: unwrapLocalSecret(store.vaultKeySecret, "同步密钥"),
    sessionId: store.sessionId,
    dataScopeId: store.dataScopeId,
    generation: accountSessionGeneration,
    signal: accountSessionAbort.signal
  };
}

function randomBytesBase64(bytes = 32) {
  return crypto.randomBytes(bytes).toString("base64");
}

function pbkdf2Key(password, salt) {
  return crypto.pbkdf2Sync(String(password), Buffer.from(salt, "base64"), 310000, 32, "sha256");
}

function aesGcmEncrypt(key, plaintext, aad = "yySlide") {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(aad));
  const encrypted = Buffer.concat([cipher.update(String(plaintext), "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return {
    alg: "AES-256-GCM",
    iv: iv.toString("base64"),
    ciphertext: encrypted.toString("base64"),
    tag: tag.toString("base64")
  };
}

function aesGcmDecrypt(key, box, aad = "yySlide") {
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(box.iv, "base64"));
  decipher.setAAD(Buffer.from(aad));
  decipher.setAuthTag(Buffer.from(box.tag, "base64"));
  return Buffer.concat([
    decipher.update(Buffer.from(box.ciphertext, "base64")),
    decipher.final()
  ]).toString("utf8");
}

function wrapVaultKey(vaultKey, password) {
  const salt = randomBytesBase64(16);
  const key = pbkdf2Key(password, salt);
  return {
    ...aesGcmEncrypt(key, vaultKey, "yySlide:vault-key"),
    kdf: "PBKDF2-SHA256",
    iterations: 310000,
    salt
  };
}

function unwrapVaultKey(wrappedVaultKey, password) {
  const key = pbkdf2Key(password, wrappedVaultKey.salt);
  return aesGcmDecrypt(key, wrappedVaultKey, "yySlide:vault-key");
}

function makeRecoveryCodes() {
  return Array.from({ length: 10 }, () => (
    `${crypto.randomBytes(5).toString("base64url").slice(0, 5)}-${crypto.randomBytes(5).toString("base64url").slice(0, 5)}`.toUpperCase()
  ));
}

function wrapVaultKeyForRecovery(vaultKey, recoveryCode) {
  const salt = randomBytesBase64(16);
  const key = pbkdf2Key(String(recoveryCode).toUpperCase(), salt);
  return {
    ...aesGcmEncrypt(key, vaultKey, "yySlide:recovery-key"),
    kdf: "PBKDF2-SHA256",
    iterations: 310000,
    salt
  };
}

function unwrapVaultKeyFromRecovery(recoveryKeyBox, recoveryCode) {
  const key = pbkdf2Key(String(recoveryCode).toUpperCase(), recoveryKeyBox.salt);
  return aesGcmDecrypt(key, recoveryKeyBox, "yySlide:recovery-key");
}

function recoveryKeysForVault(vaultKey, recoveryCodes) {
  return recoveryCodes.map((code) => ({
    code,
    keyBox: wrapVaultKeyForRecovery(vaultKey, code)
  }));
}

function encryptVaultPayload(vaultKey, payload) {
  const key = Buffer.from(vaultKey, "base64");
  return {
    ...aesGcmEncrypt(key, JSON.stringify(payload), "yySlide:vault"),
    version: 1
  };
}

function decryptVaultPayload(vaultKey, vault) {
  if (!vault) {
    return null;
  }
  const key = Buffer.from(vaultKey, "base64");
  return JSON.parse(aesGcmDecrypt(key, vault, "yySlide:vault"));
}

function timestampValue(value) {
  const parsed = Date.parse(value || "");
  return Number.isFinite(parsed) ? parsed : 0;
}

function readSyncState() {
  const state = readJson(syncStatePath(), {});
  return {
    deletedConnections: Array.isArray(state.deletedConnections) ? normalizeDeletedConnections(state.deletedConnections.slice(-10000)) : [],
    deletedGroups: Array.isArray(state.deletedGroups) ? normalizeDeletedGroups(state.deletedGroups.slice(-10000)) : []
  };
}

function normalizeDeletedConnections(items) {
  const byId = new Map();
  for (const item of Array.isArray(items) ? items : []) {
    const id = String(item?.id || "").trim().slice(0, 160);
    if (!id) {
      continue;
    }
    const deletedAt = Number.isFinite(Date.parse(item.deletedAt || "")) ? item.deletedAt : new Date().toISOString();
    const existing = byId.get(id);
    if (!existing || timestampValue(deletedAt) > timestampValue(existing.deletedAt)) {
      byId.set(id, { id, deletedAt });
    }
  }
  return Array.from(byId.values())
    .sort((a, b) => timestampValue(a.deletedAt) - timestampValue(b.deletedAt))
    .slice(-1000);
}

function normalizeDeletedGroups(items) {
  const byId = new Map();
  const byLabel = new Map();
  for (const item of Array.isArray(items) ? items : []) {
    const id = String(item?.id || "").trim().slice(0, 160);
    const label = String(item?.label || "").trim().slice(0, 512);
    if (!id && !label) {
      continue;
    }
    const deletedAt = Number.isFinite(Date.parse(item.deletedAt || "")) ? item.deletedAt : new Date().toISOString();
    const next = { id, label, deletedAt };
    const existing = id ? byId.get(id) : byLabel.get(groupLabelKey(label));
    if (!existing || timestampValue(deletedAt) > timestampValue(existing.deletedAt)) {
      if (id) {
        byId.set(id, next);
      } else {
        byLabel.set(groupLabelKey(label), next);
      }
    }
  }
  return [...byId.values(), ...byLabel.values()]
    .sort((a, b) => timestampValue(a.deletedAt) - timestampValue(b.deletedAt))
    .slice(-1000);
}

function writeSyncState(state) {
  writeJson(syncStatePath(), {
    deletedConnections: normalizeDeletedConnections(state.deletedConnections),
    deletedGroups: normalizeDeletedGroups(state.deletedGroups)
  });
}

function recordDeletedConnection(id) {
  if (!id) {
    return;
  }
  const state = readSyncState();
  writeSyncState({
    ...state,
    deletedConnections: [
      ...state.deletedConnections,
      { id, deletedAt: new Date().toISOString() }
    ]
  });
}

function recordDeletedGroup(group) {
  if (!group?.id && !group?.label) {
    return;
  }
  const state = readSyncState();
  writeSyncState({
    ...state,
    deletedGroups: [
      ...state.deletedGroups,
      {
        id: group.id || "",
        label: group.label || "",
        deletedAt: new Date().toISOString()
      }
    ]
  });
}

function readKnownHosts() {
  return readJson(knownHostsPath(), {}, (hosts) => {
    if (!isPlainObject(hosts)) {
      return {};
    }
    const normalized = {};
    for (const [id, host] of Object.entries(hosts).slice(0, 5000)) {
      if (!isPlainObject(host) || typeof host.fingerprint !== "string" || !/^SHA256:[A-Za-z0-9+/]{20,100}$/.test(host.fingerprint)) {
        continue;
      }
      normalized[String(id).slice(0, 1200)] = {
        host: String(host.host || "").slice(0, 1024),
        port: Number.isInteger(Number(host.port)) ? Number(host.port) : 22,
        fingerprint: host.fingerprint,
        trustedAt: Number.isFinite(Date.parse(host.trustedAt || "")) ? host.trustedAt : new Date(0).toISOString(),
        updatedAt: Number.isFinite(Date.parse(host.updatedAt || "")) ? host.updatedAt : undefined
      };
    }
    return normalized;
  });
}

function writeKnownHosts(hosts) {
  writeJson(knownHostsPath(), hosts && typeof hosts === "object" ? hosts : {});
}

function knownHostId(connection) {
  return `${String(connection.host || "").trim().toLowerCase()}:${Number(connection.port || 22)}`;
}

function sshKeyFingerprint(key) {
  const buffer = Buffer.isBuffer(key) ? key : Buffer.from(String(key), "hex");
  return `SHA256:${crypto.createHash("sha256").update(buffer).digest("base64").replace(/=+$/g, "")}`;
}

function verifySshHostKey(connection) {
  return async (key, callback) => {
    const hostId = knownHostId(connection);
    const fingerprint = sshKeyFingerprint(key);
    const knownHosts = readKnownHosts();
    const known = knownHosts[hostId];

    if (known?.fingerprint === fingerprint) {
      callback(true);
      return;
    }

    try {
      if (!known) {
        const result = await dialog.showMessageBox(mainWindow, {
          type: "warning",
          buttons: ["信任并连接", "取消"],
          defaultId: 1,
          cancelId: 1,
          title: "确认 SSH 主机指纹",
          message: `首次连接 ${hostId}`,
          detail: `请确认服务器指纹可信：\n\n${fingerprint}\n\n如果这个指纹不对，可能存在中间人攻击。`
        });
        if (result.response === 0) {
          const latestHosts = readKnownHosts();
          latestHosts[hostId] = {
            host: connection.host,
            port: Number(connection.port || 22),
            fingerprint,
            trustedAt: new Date().toISOString()
          };
          writeKnownHosts(latestHosts);
          callback(true);
          return;
        }
        callback(false);
        return;
      }

      const result = await dialog.showMessageBox(mainWindow, {
        type: "error",
        buttons: ["拒绝连接", "更新指纹并连接"],
        defaultId: 0,
        cancelId: 0,
        title: "SSH 主机指纹已变化",
        message: `${hostId} 的 SSH 指纹和之前保存的不一致。`,
        detail: `已保存：${known.fingerprint}\n当前：${fingerprint}\n\n除非你确认服务器刚重装或更换了主机密钥，否则应拒绝连接。`
      });
      if (result.response === 1) {
        const latestHosts = readKnownHosts();
        latestHosts[hostId] = {
          ...known,
          host: connection.host,
          port: Number(connection.port || 22),
          fingerprint,
          trustedAt: known.trustedAt || new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        writeKnownHosts(latestHosts);
        callback(true);
        return;
      }
      callback(false);
    } catch (error) {
      console.error("SSH host key verification failed", error);
      callback(false);
    }
  };
}

async function readResponseTextLimited(response, maxBytes = CLOUD_MAX_RESPONSE_BYTES) {
  const declaredLength = Number(response.headers.get("content-length") || 0);
  if (declaredLength > maxBytes) {
    throw new Error("同步服务器响应过大。");
  }
  if (!response.body?.getReader) {
    const text = await response.text();
    if (Buffer.byteLength(text, "utf8") > maxBytes) {
      throw new Error("同步服务器响应过大。");
    }
    return text;
  }
  const chunks = [];
  let size = 0;
  const reader = response.body.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    size += value.byteLength;
    if (size > maxBytes) {
      await reader.cancel("response too large").catch(() => undefined);
      throw new Error("同步服务器响应过大。");
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function cloudRequest(serverUrl, method, pathname, body, token, options = {}) {
  const requestBody = body == null ? undefined : JSON.stringify(body);
  if (requestBody && Buffer.byteLength(requestBody, "utf8") > CLOUD_MAX_RESPONSE_BYTES) {
    throw new Error("同步服务器请求内容过大。");
  }
  const headers = {
    "Accept": "application/json",
    "Content-Type": "application/json",
    "X-yySlide-Client-Id": cloudClientId
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const controller = new AbortController();
  const abortFromParent = () => controller.abort(options.signal?.reason);
  if (options.signal?.aborted) {
    abortFromParent();
  } else {
    options.signal?.addEventListener("abort", abortFromParent, { once: true });
  }
  const timeout = setTimeout(() => controller.abort(new Error("同步服务器请求超时。")), options.timeoutMs || CLOUD_REQUEST_TIMEOUT_MS);
  timeout.unref?.();
  let response;
  try {
    response = await fetch(`${serverUrl}${pathname}`, {
      method,
      headers,
      body: requestBody,
      redirect: "error",
      signal: controller.signal
    });
  } catch (error) {
    clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abortFromParent);
    if (controller.signal.aborted && !options.signal?.aborted) {
      throw new Error("同步服务器请求超时或已取消。", { cause: error });
    }
    throw error;
  }
  let text;
  try {
    text = await readResponseTextLimited(response, options.maxResponseBytes || CLOUD_MAX_RESPONSE_BYTES);
  } finally {
    clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abortFromParent);
  }
  const contentType = response.headers.get("content-type") || "";
  if (text && !/\bapplication\/(?:[a-z0-9.+-]+\+)?json\b/i.test(contentType)) {
    throw new Error("同步服务器返回了非 JSON 响应。");
  }
  let data = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (error) {
      throw new Error("同步服务器返回的 JSON 无效。", { cause: error });
    }
  }
  if (!isPlainObject(data)) {
    throw new Error("同步服务器响应格式无效。");
  }
  if (!response.ok) {
    const error = new Error(data.error || `同步服务器请求失败：${response.status}`);
    error.status = response.status;
    error.data = data;
    throw error;
  }
  return data;
}

function exportConnectionsForVault() {
  return readConnections().map((connection) => {
    const { privateKeyPath, ...publicFields } = publicConnection(connection);
    const exported = {
      ...publicFields,
      password: decryptSecret(connection.passwordSecret) || "",
      passphrase: decryptSecret(connection.passphraseSecret) || ""
    };
    if (connection.authType === "key" && privateKeyPath && fs.existsSync(privateKeyPath)) {
      try {
        const stats = fs.lstatSync(privateKeyPath);
        if (stats.isFile() && !stats.isSymbolicLink() && stats.size <= 1024 * 1024) {
          exported.privateKey = fs.readFileSync(privateKeyPath, "utf8");
        }
      } catch {
        // A local path is never synchronized; inaccessible key material stays local.
      }
    }
    delete exported.hasPassword;
    delete exported.hasPassphrase;
    return exported;
  });
}

function exportConnectionGroupsForVault() {
  return readConnectionGroups().map((group) => ({
    id: group.id,
    label: group.label,
    sortOrder: Number(group.sortOrder || 0),
    updatedAt: group.updatedAt || new Date().toISOString()
  }));
}

function importConnectionGroupsFromVault(remoteGroups, remoteDeletedGroups = []) {
  const localGroups = readConnectionGroups();
  const syncState = readSyncState();
  const deletedGroups = normalizeDeletedGroups([
    ...syncState.deletedGroups,
    ...remoteDeletedGroups
  ]);
  const deletedById = new Map(deletedGroups.filter((item) => item.id).map((item) => [item.id, item]));
  const deletedByLabel = new Map(deletedGroups.filter((item) => item.label).map((item) => [groupLabelKey(item.label), item]));
  const byId = new Map(localGroups.map((group) => [group.id, group]));
  const byLabel = new Map(localGroups.map((group) => [groupLabelKey(group.label), group]));

  for (const remote of Array.isArray(remoteGroups) ? remoteGroups : []) {
    const label = String(remote?.label || remote?.name || "").trim();
    if (!label) {
      continue;
    }
    const id = String(remote.id || "").trim() || crypto.randomUUID();
    const remoteUpdatedAt = timestampValue(remote.updatedAt);
    const labelKey = groupLabelKey(label);
    const deleted = deletedById.get(id) || deletedByLabel.get(labelKey);
    if (deleted && timestampValue(deleted.deletedAt) >= remoteUpdatedAt) {
      const existing = byId.get(id) || byLabel.get(labelKey);
      if (existing) {
        byId.delete(existing.id);
        byLabel.delete(groupLabelKey(existing.label));
      }
      continue;
    }
    const existing = byId.get(id) || byLabel.get(labelKey);
    if (existing && timestampValue(existing.updatedAt) > remoteUpdatedAt) {
      continue;
    }
    const next = {
      id: existing?.id || id,
      label,
      sortOrder: Number.isFinite(Number(remote.sortOrder)) ? Number(remote.sortOrder) : (existing?.sortOrder ?? byId.size),
      updatedAt: remote.updatedAt || new Date().toISOString()
    };
    if (existing && existing.id !== next.id) {
      byId.delete(existing.id);
      byLabel.delete(groupLabelKey(existing.label));
    }
    byId.set(next.id, next);
    byLabel.set(groupLabelKey(next.label), next);
  }

  for (const deleted of deletedGroups) {
    const existing = (deleted.id && byId.get(deleted.id)) ||
      (deleted.label && byLabel.get(groupLabelKey(deleted.label)));
    if (existing && timestampValue(deleted.deletedAt) >= timestampValue(existing.updatedAt)) {
      byId.delete(existing.id);
      byLabel.delete(groupLabelKey(existing.label));
    }
  }

  const mergedGroups = normalizeConnectionGroups(Array.from(byId.values()));
  writeConnectionGroups(mergedGroups);
  const activeDeletedGroups = deletedGroups.filter((deleted) => {
    const group = (deleted.id && byId.get(deleted.id)) ||
      (deleted.label && byLabel.get(groupLabelKey(deleted.label)));
    return !group || timestampValue(deleted.deletedAt) >= timestampValue(group.updatedAt);
  });
  writeSyncState({ ...syncState, deletedGroups: activeDeletedGroups });
}

function importConnectionsFromVault(remoteConnections, remoteDeletedConnections = []) {
  const local = readConnections();
  const byId = new Map(local.map((connection) => [connection.id, connection]));
  const syncState = readSyncState();
  const deletedConnections = normalizeDeletedConnections([
    ...syncState.deletedConnections,
    ...remoteDeletedConnections
  ]);
  const deletedById = new Map(deletedConnections.map((item) => [item.id, item]));
  const keyDir = path.join(profileRoot(), "synced-keys");
  const staleManagedKeys = [];
  fs.mkdirSync(keyDir, { recursive: true });

  for (const remote of Array.isArray(remoteConnections) ? remoteConnections : []) {
    if (!remote?.id || !remote.host || !remote.username) {
      continue;
    }
    const remoteUpdatedAt = timestampValue(remote.updatedAt);
    const deleted = deletedById.get(remote.id);
    if (deleted && timestampValue(deleted.deletedAt) >= remoteUpdatedAt) {
      byId.delete(remote.id);
      continue;
    }
    const existing = byId.get(remote.id);
    if (existing && timestampValue(existing.updatedAt) > remoteUpdatedAt) {
      continue;
    }
    const nextInput = {
      ...remote,
      password: remote.password || "",
      passphrase: remote.passphrase || ""
    };
    if (remote.authType === "key" && remote.privateKey) {
      const keyPath = path.join(keyDir, `${safeStorageKey(remote.id)}.key`);
      atomicWriteFile(keyPath, remote.privateKey, { encoding: "utf8", mode: 0o600 });
      nextInput.privateKeyPath = keyPath;
    }
    const nextConnection = normalizeConnectionInput(nextInput, existing, { updatedAt: remote.updatedAt, trustedId: true });
    if (existing?.privateKeyPath && existing.privateKeyPath !== nextConnection.privateKeyPath) {
      staleManagedKeys.push(existing.privateKeyPath);
    }
    byId.set(remote.id, nextConnection);
  }

  for (const deleted of deletedConnections) {
    const existing = byId.get(deleted.id);
    if (existing && timestampValue(deleted.deletedAt) >= timestampValue(existing.updatedAt)) {
      byId.delete(deleted.id);
    }
  }

  const mergedConnections = Array.from(byId.values()).sort((a, b) => (a.name || a.host).localeCompare(b.name || b.host));
  const activeDeletedConnections = deletedConnections.filter((deleted) => {
    const connection = byId.get(deleted.id);
    return !connection || timestampValue(deleted.deletedAt) >= timestampValue(connection.updatedAt);
  });
  writeConnections(mergedConnections);
  writeSyncState({ ...syncState, deletedConnections: activeDeletedConnections });
  for (const connection of local) {
    if (!byId.has(connection.id)) {
      cleanupManagedPrivateKey(connection.privateKeyPath);
    }
  }
  for (const keyPath of staleManagedKeys) {
    cleanupManagedPrivateKey(keyPath);
  }
}

function exportVaultSnapshot(session) {
  if (session) {
    assertCurrentAccountSession(session);
  }
  return {
    version: 3,
    exportedAt: new Date().toISOString(),
    groups: exportConnectionGroupsForVault(),
    connections: exportConnectionsForVault(),
    deletedConnections: readSyncState().deletedConnections,
    deletedGroups: readSyncState().deletedGroups
  };
}

function importVaultSnapshot(snapshot) {
  if (!snapshot) {
    return;
  }
  const normalized = normalizeVaultSnapshot(snapshot);
  importConnectionGroupsFromVault(normalized.groups, normalized.deletedGroups);
  importConnectionsFromVault(normalized.connections, normalized.deletedConnections);
}

function runCloudOperation(expectedSessionId, action) {
  const operation = cloudSyncPromise.catch(() => undefined).then(() => {
    const session = currentAccountSession(expectedSessionId);
    assertCurrentAccountSession(session);
    return action(session);
  });
  cloudSyncPromise = operation;
  return operation;
}

async function performCloudPull(expectedSessionId) {
  return runCloudOperation(expectedSessionId, async (session) => {
    const { serverUrl, token, vaultKey } = session;
    const remote = await cloudRequest(serverUrl, "GET", "/v1/vault", null, token, { signal: session.signal });
    assertCurrentAccountSession(session);
    if (remote.vault) {
      importVaultSnapshot(decryptVaultPayload(vaultKey, remote.vault));
    }
    assertCurrentAccountSession(session);
    updateAccountStoreForSession(session, (latestStore) => ({
      ...latestStore,
      user: {
        ...(latestStore.user || {}),
        vaultUpdatedAt: remote.vault?.updatedAt || latestStore.user?.vaultUpdatedAt || null
      },
      lastSyncAt: new Date().toISOString()
    }));
    return accountPublicState();
  });
}

async function performCloudSync(expectedSessionId) {
  return runCloudOperation(expectedSessionId, async (session) => {
    const { serverUrl, token, vaultKey } = session;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const remote = await cloudRequest(serverUrl, "GET", "/v1/vault", null, token, { signal: session.signal });
      assertCurrentAccountSession(session);
      const baseUpdatedAt = remote.vault?.updatedAt || null;
      if (remote.vault) {
        importVaultSnapshot(decryptVaultPayload(vaultKey, remote.vault));
      }
      assertCurrentAccountSession(session);
      const encrypted = encryptVaultPayload(vaultKey, exportVaultSnapshot(session));
      try {
        const saved = await cloudRequest(serverUrl, "PUT", "/v1/vault", { vault: encrypted, baseUpdatedAt }, token, { signal: session.signal });
        assertCurrentAccountSession(session);
        updateAccountStoreForSession(session, (latestStore) => ({
          ...latestStore,
          user: {
            ...(latestStore.user || {}),
            vaultUpdatedAt: saved.vault?.updatedAt || new Date().toISOString()
          },
          lastSyncAt: new Date().toISOString()
        }));
        return accountPublicState();
      } catch (error) {
        if (error.status === 409 && attempt === 0) {
          continue;
        }
        throw error;
      }
    }
    return accountPublicState();
  });
}

function send(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload);
  }
}

function stopCloudEvents() {
  if (cloudEventsReconnectTimer) {
    clearTimeout(cloudEventsReconnectTimer);
    cloudEventsReconnectTimer = null;
  }
  if (cloudEventsAbort) {
    cloudEventsAbort.abort();
    cloudEventsAbort = null;
  }
  cloudEventsKey = "";
}

function scheduleCloudEventsReconnect(delayMs = 5000) {
  if (cloudEventsReconnectTimer) {
    return;
  }
  cloudEventsReconnectTimer = setTimeout(() => {
    cloudEventsReconnectTimer = null;
    startCloudEvents();
  }, delayMs);
}

function handleCloudSseFrame(frame, expectedSessionId) {
  let event = "message";
  let data = "";
  for (const line of String(frame || "").split("\n")) {
    if (!line || line.startsWith(":")) {
      continue;
    }
    if (line.startsWith("event:")) {
      event = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      data += `${data ? "\n" : ""}${line.slice("data:".length).trimStart()}`;
    }
  }
  if (event !== "vault.updated" || !data) {
    return;
  }
  let payload = null;
  try {
    payload = JSON.parse(data);
  } catch {
    return;
  }
  if (!isPlainObject(payload) || (payload.sourceClientId && payload.sourceClientId === cloudClientId)) {
    return;
  }
  const store = readAccountStore();
  if (!store || store.sessionId !== expectedSessionId || !store.syncEnabled) {
    return;
  }
  void performCloudPull(expectedSessionId)
    .then((account) => {
      send("account:vault-changed", { account, event: payload });
    })
    .catch((error) => {
      console.error("Cloud pull after event failed", error);
    });
}

async function readCloudEventStream(response, signal, expectedSessionId) {
  if (!response.body?.getReader) {
    throw new Error("同步服务器没有返回可读取的事件流。");
  }
  const decoder = new TextDecoder();
  const reader = response.body.getReader();
  let buffer = "";
  while (!signal.aborted) {
    let idleTimer;
    const { done, value } = await Promise.race([
      reader.read(),
      new Promise((_resolve, reject) => {
        idleTimer = setTimeout(() => reject(new Error("同步事件流长时间没有响应。")), CLOUD_SSE_IDLE_TIMEOUT_MS);
        idleTimer.unref?.();
      })
    ]).finally(() => clearTimeout(idleTimer));
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true }).replace(/\r/g, "");
    if (Buffer.byteLength(buffer, "utf8") > CLOUD_SSE_MAX_BUFFER_BYTES) {
      throw new Error("同步事件帧过大。");
    }
    let separatorIndex = buffer.indexOf("\n\n");
    while (separatorIndex >= 0) {
      const frame = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      handleCloudSseFrame(frame, expectedSessionId);
      separatorIndex = buffer.indexOf("\n\n");
    }
  }
}

function startCloudEvents() {
  const store = readAccountStore();
  if (!store?.serverUrl || !store?.tokenSecret || !store?.syncEnabled) {
    stopCloudEvents();
    return;
  }

  let session;
  try {
    session = currentAccountSession(store.sessionId);
  } catch (error) {
    console.error("Cloud events token unavailable", error);
    stopCloudEvents();
    return;
  }

  const nextKey = store.sessionId;
  if (cloudEventsAbort && cloudEventsKey === nextKey) {
    return;
  }
  stopCloudEvents();
  cloudEventsKey = nextKey;

  const controller = new AbortController();
  const abortFromSession = () => controller.abort(session.signal.reason);
  session.signal.addEventListener("abort", abortFromSession, { once: true });
  cloudEventsAbort = controller;
  let headerTimedOut = false;
  const headerTimer = setTimeout(() => {
    headerTimedOut = true;
    controller.abort(new Error("同步事件连接超时。"));
  }, CLOUD_REQUEST_TIMEOUT_MS);
  headerTimer.unref?.();
  void fetch(`${store.serverUrl}/v1/vault/events`, {
    method: "GET",
    headers: {
      "Accept": "text/event-stream",
      "Authorization": `Bearer ${session.token}`,
      "X-yySlide-Client-Id": cloudClientId
    },
    signal: controller.signal
  })
    .then(async (response) => {
      clearTimeout(headerTimer);
      if (!response.ok) {
        throw new Error(`同步事件连接失败：${response.status}`);
      }
      if (!/^text\/event-stream\b/i.test(response.headers.get("content-type") || "")) {
        throw new Error("同步服务器没有返回事件流。");
      }
      await readCloudEventStream(response, controller.signal, session.sessionId);
      if (!controller.signal.aborted) {
        throw new Error("同步事件连接已断开。");
      }
    })
    .catch((error) => {
      clearTimeout(headerTimer);
      session.signal.removeEventListener("abort", abortFromSession);
      if (controller.signal.aborted && !headerTimedOut) {
        return;
      }
      controller.abort();
      console.error("Cloud event stream failed", error);
      cloudEventsAbort = null;
      cloudEventsKey = "";
      scheduleCloudEventsReconnect();
    });
}

function errorMessage(error) {
  if (!error) {
    return "Unknown error";
  }
  return error.message || String(error);
}

class UploadCanceledError extends Error {
  constructor() {
    super("Upload canceled.");
    this.name = "UploadCanceledError";
    this.code = "UPLOAD_CANCELED";
  }
}

function isUploadCanceled(error) {
  return error?.code === "UPLOAD_CANCELED" || error?.name === "UploadCanceledError";
}

class DownloadCanceledError extends Error {
  constructor() {
    super("Download canceled.");
    this.name = "DownloadCanceledError";
    this.code = "DOWNLOAD_CANCELED";
  }
}

function isDownloadCanceled(error) {
  return error?.code === "DOWNLOAD_CANCELED" || error?.name === "DownloadCanceledError";
}

function normalizeConnectionInput(input, existing, options = {}) {
  input = isPlainObject(input) ? input : {};
  const trimmedHost = String(input.host || "").trim();
  const trimmedUser = String(input.username || "").trim();
  if (!trimmedHost || trimmedHost.length > 1024) {
    throw new Error("Host is required.");
  }
  if (!trimmedUser || trimmedUser.length > 512) {
    throw new Error("Username is required.");
  }
  const port = Number(input.port || 22);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("Port must be between 1 and 65535.");
  }

  const authType = input.authType === "key" ? "key" : "password";
  const group = Object.prototype.hasOwnProperty.call(input, "group") ? input.group : existing?.group;
  const next = {
    id: existing?.id || (options.trustedId ? String(input.id || "").trim() : "") || crypto.randomUUID(),
    name: String(input.name || trimmedHost).trim().slice(0, 512),
    host: trimmedHost,
    port,
    username: trimmedUser,
    authType,
    privateKeyPath: authType === "key" ? String(input.privateKeyPath || existing?.privateKeyPath || "").slice(0, 32768) : "",
    group: String(group || "").trim().slice(0, 512),
    updatedAt: timestampValue(options.updatedAt) ? options.updatedAt : new Date().toISOString()
  };

  if (authType === "password") {
    next.passwordSecret = input.clearPassword
      ? null
      : input.password ? encryptSecret(String(input.password).slice(0, 65536)) : existing?.passwordSecret || null;
    next.passphraseSecret = null;
  } else {
    next.passwordSecret = null;
    next.passphraseSecret = input.clearPassphrase
      ? null
      : input.passphrase ? encryptSecret(String(input.passphrase).slice(0, 65536)) : existing?.passphraseSecret || null;
  }

  return next;
}

function cleanupManagedPrivateKey(privateKeyPath) {
  if (!privateKeyPath) {
    return;
  }
  const managedRoot = path.resolve(profileRoot(), "synced-keys");
  const target = path.resolve(privateKeyPath);
  const relative = path.relative(managedRoot, target);
  if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
    return;
  }
  try {
    fs.rmSync(target, { force: true });
  } catch {
    // Keeping an orphaned encrypted-vault key is safer than deleting an unrelated file.
  }
}

function resolveConnection(connectionId) {
  const connection = readConnections().find((item) => item.id === connectionId);
  if (!connection) {
    throw new Error("Connection profile was not found.");
  }
  return connection;
}

function buildSshConfig(connection) {
  const config = {
    host: connection.host,
    port: Number(connection.port || 22),
    username: connection.username,
    readyTimeout: 20000,
    keepaliveInterval: 15000,
    keepaliveCountMax: 4,
    tryKeyboard: connection.authType === "password",
    hostVerifier: verifySshHostKey(connection)
  };

  if (connection.authType === "key") {
    if (!connection.privateKeyPath) {
      throw new Error("Private key path is required.");
    }
    const keyStats = fs.lstatSync(connection.privateKeyPath);
    if (!keyStats.isFile() || keyStats.isSymbolicLink() || keyStats.size > 1024 * 1024) {
      throw new Error("Private key file is invalid or too large.");
    }
    config.privateKey = fs.readFileSync(connection.privateKeyPath);
    const passphrase = decryptSecret(connection.passphraseSecret);
    if (passphrase) {
      config.passphrase = passphrase;
    }
  } else {
    const password = decryptSecret(connection.passwordSecret);
    if (password) {
      config.password = password;
    }
  }

  config.tryKeyboard = connection.authType === "password";

  return config;
}

function cleanupSession(tabId, notify = true, expectedSession = null) {
  const session = sessions.get(tabId);
  if (!session || (expectedSession && session !== expectedSession)) {
    return false;
  }

  if (session.monitorTimer) {
    clearInterval(session.monitorTimer);
  }
  session.monitorTimer = null;

  try {
    session.shell?.end();
  } catch {
    // Ignore shutdown races.
  }
  try {
    session.conn?.end();
  } catch {
    // Ignore shutdown races.
  }

  sessions.delete(tabId);
  for (const task of [...activeUploads.values(), ...activeDownloads.values()]) {
    if (task.tabId === tabId) {
      task.canceled = true;
      task.cancelCurrent?.();
    }
  }
  for (const [token, task] of dragDownloads) {
    if (task.tabId === tabId) {
      dragDownloads.delete(token);
    }
  }
  for (const [key, edit] of watchedEdits) {
    if (edit.tabId === tabId) {
      clearTimeout(edit.timer);
      edit.watcher.close();
      watchedEdits.delete(key);
    }
  }
  if (notify) {
    send("ssh:state", { tabId, state: "closed" });
  }
  return true;
}

function cleanupAllRemoteSessions() {
  for (const tabId of Array.from(sessions.keys())) {
    cleanupSession(tabId, false);
  }
}

function execCommand(session, command) {
  return new Promise((resolve, reject) => {
    session.conn.exec(command, (error, stream) => {
      if (error) {
        reject(error);
        return;
      }
      let output = "";
      let stderr = "";
      stream.on("data", (chunk) => {
        output += chunk.toString("utf8");
      });
      stream.stderr.on("data", (chunk) => {
        stderr += chunk.toString("utf8");
      });
      stream.on("close", (code) => {
        if (code && !output) {
          reject(new Error(stderr || `Command exited with ${code}`));
          return;
        }
        resolve(output);
      });
    });
  });
}

function buildMonitorCommand(includePublicIp) {
  const publicIpBlock = includePublicIp ? `
echo __PUBLIC_IP__
(command -v curl >/dev/null 2>&1 && curl -4 -fsS --max-time 2 https://api.ipify.org 2>/dev/null) || (command -v wget >/dev/null 2>&1 && wget -4 -qO- -T 2 https://api.ipify.org 2>/dev/null) || true
echo
` : `
echo __PUBLIC_IP__
echo
`;

  return `
LC_ALL=C
echo __UPTIME__
awk '{print int($1)}' /proc/uptime 2>/dev/null
echo __LOAD__
cat /proc/loadavg 2>/dev/null
echo __CPU__
awk '/^cpu / {print $2,$3,$4,$5,$6,$7,$8,$9,$10}' /proc/stat 2>/dev/null
echo __MEM__
free -b 2>/dev/null | awk '/^Mem:/ {print $2,$3,$7} /^Swap:/ {print $2,$3}'
echo __DISK__
df -B1 -P 2>/dev/null | awk 'NR>1 {print $6 "|" $2 "|" $3 "|" $4 "|" $5}'
echo __NET__
awk -F: 'NR>2 {iface=$1; gsub(/^[ \t]+|[ \t]+$/, "", iface); data=$2; gsub(/^[ \t]+/, "", data); split(data,a,/[ \t]+/); print iface,a[1],a[9]}' /proc/net/dev 2>/dev/null
echo __PROC__
ps -eo pid,comm,pcpu,pmem,rss --sort=-pcpu 2>/dev/null | awk 'NR>1 && NR<=8 {print $1 "|" $2 "|" $3 "|" $4 "|" $5}'
echo __HOST__
hostname 2>/dev/null
${publicIpBlock}
echo __IP__
hostname -I 2>/dev/null | awk '{print $1}'
`;
}

function splitMonitorSections(output) {
  const sections = {};
  let current = null;
  for (const rawLine of output.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) {
      continue;
    }
    const match = line.match(/^__(.+)__$/);
    if (match) {
      current = match[1].toLowerCase();
      sections[current] = [];
      continue;
    }
    if (current) {
      sections[current].push(line);
    }
  }
  return sections;
}

function parseMonitorOutput(output, session) {
  const sections = splitMonitorSections(output);
  const now = Date.now();
  const loadParts = (sections.load?.[0] || "").split(/\s+/);
  const cpuParts = (sections.cpu?.[0] || "").split(/\s+/).map(Number);
  const memLine = sections.mem?.[0]?.split(/\s+/).map(Number) || [];
  const swapLine = sections.mem?.[1]?.split(/\s+/).map(Number) || [];
  const uptimeSeconds = Number(sections.uptime?.[0] || 0);

  const disks = (sections.disk || []).map((line) => {
    const [mount, total, used, available, percent] = line.split("|");
    return {
      mount,
      total: Number(total || 0),
      used: Number(used || 0),
      available: Number(available || 0),
      percent: Number(String(percent || "0").replace("%", ""))
    };
  });

  const netRaw = (sections.net || []).map((line) => {
    const [name, rx, tx] = line.split(/\s+/);
    return { name, rx: Number(rx || 0), tx: Number(tx || 0) };
  }).filter((item) => item.name && item.name !== "lo");

  const rates = netRaw.map((item) => {
    const previous = session.lastNet?.[item.name];
    const seconds = previous ? Math.max((now - previous.at) / 1000, 0.1) : 1;
    return {
      name: item.name,
      rx: item.rx,
      tx: item.tx,
      rxRate: previous ? Math.max(0, (item.rx - previous.rx) / seconds) : 0,
      txRate: previous ? Math.max(0, (item.tx - previous.tx) / seconds) : 0
    };
  });
  session.lastNet = Object.fromEntries(netRaw.map((item) => [item.name, { ...item, at: now }]));

  const processes = (sections.proc || []).map((line) => {
    const [pid, command, cpu, mem, rss] = line.split("|");
    return {
      pid: Number(pid || 0),
      command: command || "-",
      cpu: Number(cpu || 0),
      mem: Number(mem || 0),
      rss: Number(rss || 0) * 1024
    };
  });

  const cpuTotal = cpuParts.reduce((sum, value) => sum + (Number.isFinite(value) ? value : 0), 0);
  const cpuIdle = (cpuParts[3] || 0) + (cpuParts[4] || 0);
  let cpuPercent = 0;
  if (session.lastCpu && cpuTotal > session.lastCpu.total) {
    const totalDelta = cpuTotal - session.lastCpu.total;
    const idleDelta = cpuIdle - session.lastCpu.idle;
    cpuPercent = Math.round(Math.max(0, Math.min(100, (1 - idleDelta / totalDelta) * 100)));
  }
  session.lastCpu = { total: cpuTotal, idle: cpuIdle };
  const privateIp = sections.ip?.[0] || "";
  const publicIp = sections.public_ip?.[0] || session.publicIp || "";
  if (sections.public_ip?.[0]) {
    session.publicIp = sections.public_ip[0];
  }
  session.publicIpChecked = true;
  const configuredHost = session.host || "";
  const displayIp = publicIp || configuredHost || privateIp;

  return {
    sampledAt: now,
    host: sections.host?.[0] || "",
    ip: displayIp,
    privateIp,
    publicIp,
    uptimeSeconds,
    load: {
      one: Number(loadParts[0] || 0),
      five: Number(loadParts[1] || 0),
      fifteen: Number(loadParts[2] || 0)
    },
    cpu: {
      percent: cpuPercent
    },
    memory: {
      total: memLine[0] || 0,
      used: memLine[1] || 0,
      available: memLine[2] || 0,
      percent: memLine[0] ? Math.round((memLine[1] / memLine[0]) * 100) : 0
    },
    swap: {
      total: swapLine[0] || 0,
      used: swapLine[1] || 0,
      percent: swapLine[0] ? Math.round((swapLine[1] / swapLine[0]) * 100) : 0
    },
    disks,
    network: rates,
    processes
  };
}

async function pollMonitor(tabId) {
  const session = sessions.get(tabId);
  if (!session || session.monitorBusy || !session.ready) {
    return;
  }
  session.monitorBusy = true;
  try {
    const output = await execCommand(session, buildMonitorCommand(!session.publicIpChecked));
    send("monitor:update", { tabId, data: parseMonitorOutput(output, session) });
  } catch (error) {
    send("monitor:error", { tabId, error: errorMessage(error) });
  } finally {
    session.monitorBusy = false;
  }
}

function startMonitor(tabId) {
  const session = sessions.get(tabId);
  if (!session) {
    return;
  }
  if (session.monitorTimer) {
    clearInterval(session.monitorTimer);
  }
  pollMonitor(tabId);
  session.monitorTimer = setInterval(() => pollMonitor(tabId), 2500);
}

function ensureSftp(tabId, expectedSessionId = null) {
  const session = sessions.get(tabId);
  if (!session || !session.ready || (expectedSessionId && session.id !== expectedSessionId)) {
    return Promise.reject(new Error("SSH session is not ready."));
  }
  if (session.sftp) {
    return Promise.resolve(session.sftp);
  }
  if (session.sftpPromise) {
    return session.sftpPromise;
  }

  session.sftpPromise = new Promise((resolve, reject) => {
    session.conn.sftp((error, sftp) => {
      session.sftpPromise = null;
      if (error) {
        reject(error);
        return;
      }
      if (sessions.get(tabId) !== session || !session.ready) {
        try {
          sftp?.end?.();
        } catch {
          // Ignore disposal races for a stale SFTP channel.
        }
        reject(new Error("SSH session changed while opening SFTP."));
        return;
      }
      session.sftp = sftp;
      resolve(sftp);
    });
  });
  return session.sftpPromise;
}

function normalizeRemotePath(remotePath) {
  if (!remotePath || remotePath === ".") {
    return ".";
  }
  const value = String(remotePath);
  if (value.length > 8192 || /[\0\r\n]/.test(value)) {
    throw new Error("远端路径无效。");
  }
  return value.replace(/\\/g, "/").replace(/\/+/g, "/");
}

function joinRemotePath(basePath, name) {
  const cleanBase = normalizeRemotePath(basePath);
  if (cleanBase === "." || cleanBase === "/") {
    return cleanBase === "/" ? `/${name}` : name;
  }
  return `${cleanBase.replace(/\/$/, "")}/${name}`;
}

function normalizeRemoteName(name) {
  const cleanName = String(name || "").trim();
  if (!cleanName || cleanName === "." || cleanName === ".." || cleanName.length > 1024 || /[\0\r\n/\\]/.test(cleanName)) {
    throw new Error("名称无效。");
  }
  return cleanName;
}

function quoteShellPath(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

function pathFromRelative(rootPath, relativePath) {
  const parts = String(relativePath || "")
    .split("/")
    .filter(Boolean);
  if (!parts.length || parts.some((part) => part === "." || part === ".." || /[\0/\\]/.test(part))) {
    throw new Error("本地下载路径无效。");
  }
  const root = path.resolve(rootPath);
  const target = path.resolve(root, ...parts);
  const relative = path.relative(root, target);
  if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error("本地下载路径越界。");
  }
  return target;
}

function parentRemotePath(remotePath) {
  const cleanPath = normalizeRemotePath(remotePath);
  if (cleanPath === "." || cleanPath === "/") {
    return cleanPath;
  }
  const parts = cleanPath.split("/").filter(Boolean);
  parts.pop();
  return cleanPath.startsWith("/") ? `/${parts.join("/")}` || "/" : parts.join("/") || ".";
}

function isSameOrChildRemotePath(parentPath, childPath) {
  const parent = normalizeRemotePath(parentPath).replace(/\/+$/, "") || "/";
  const child = normalizeRemotePath(childPath).replace(/\/+$/, "") || "/";
  if (parent === "/") {
    return child === "/";
  }
  return child === parent || child.startsWith(`${parent}/`);
}

function sftpRealPath(sftp, remotePath) {
  return new Promise((resolve) => {
    sftp.realpath(remotePath, (error, absolutePath) => {
      resolve(error ? normalizeRemotePath(remotePath) : normalizeRemotePath(absolutePath));
    });
  });
}

function sftpReaddir(sftp, remotePath) {
  return new Promise((resolve, reject) => {
    sftp.readdir(remotePath, (error, list) => {
      if (error) {
        reject(error);
        return;
      }
      resolve(list || []);
    });
  });
}

async function sftpFastPut(sftp, localPath, remotePath) {
  const tempPath = `${remotePath}.yyslide-${crypto.randomUUID()}.part`;
  try {
    await new Promise((resolve, reject) => {
      sftp.fastPut(localPath, tempPath, { mode: 0o600 }, (error) => {
        if (error) {
          reject(error);
          return;
        }
        resolve();
      });
    });
    await sftpAtomicReplace(sftp, tempPath, remotePath);
  } catch (error) {
    await sftpUnlink(sftp, tempPath).catch(() => undefined);
    throw error;
  }
}

function sftpFastGetRaw(sftp, remotePath, localPath) {
  return new Promise((resolve, reject) => {
    sftp.fastGet(remotePath, localPath, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

function sendUploadProgress({
  uploadId,
  tabId,
  remotePath,
  filename,
  index,
  totalFiles,
  fileBytes,
  fileTransferred,
  totalBytes,
  transferredBytes,
  startedAt,
  state = "running",
  error = ""
}) {
  const elapsedSeconds = Math.max((Date.now() - startedAt) / 1000, 0.001);
  const bytesPerSecond = transferredBytes / elapsedSeconds;
  const remainingBytes = Math.max(totalBytes - transferredBytes, 0);
  send("sftp:upload-progress", {
    uploadId,
    tabId,
    remotePath,
    filename,
    index,
    totalFiles,
    fileBytes,
    fileTransferred,
    totalBytes,
    transferredBytes,
    percent: totalBytes ? Math.min(100, Math.round((transferredBytes / totalBytes) * 1000) / 10) : 100,
    bytesPerSecond,
    etaSeconds: bytesPerSecond > 0 && state === "running" ? remainingBytes / bytesPerSecond : 0,
    state,
    error
  });
}

function uploadFileWithProgress(sftp, options) {
  const {
    uploadId,
    tabId,
    remotePath,
    localPath,
    remoteFilePath,
    filename,
    index,
    totalFiles,
    totalBytes,
    transferredBefore,
    startedAt,
    task
  } = options;
  const stat = fs.lstatSync(localPath);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    throw new Error("上传源必须是普通文件，不能是符号链接。");
  }
  const fileBytes = stat.size || 0;
  const remoteTempPath = `${remoteFilePath}.yyslide-${crypto.randomUUID()}.part`;

  return new Promise((resolve, reject) => {
    let settled = false;
    let fileTransferred = 0;
    let lastSentAt = 0;
    let source = null;
    let target = null;

    const notify = (force = false) => {
      const now = Date.now();
      if (!force && now - lastSentAt < 160) {
        return;
      }
      lastSentAt = now;
      const progress = {
        uploadId,
        tabId,
        remotePath,
        filename,
        index,
        totalFiles,
        fileBytes,
        fileTransferred,
        totalBytes,
        transferredBytes: transferredBefore + fileTransferred,
        startedAt
      };
      if (task) {
        task.lastProgress = progress;
      }
      sendUploadProgress(progress);
    };

    const done = (error) => {
      if (settled) {
        return;
      }
      settled = true;
      if (task?.cancelCurrent === cancelCurrent) {
        task.cancelCurrent = null;
      }
      if (task?.canceled && !error) {
        error = new UploadCanceledError();
      }
      if (!error && fileTransferred !== fileBytes) {
        error = new Error("上传源文件在传输过程中发生变化。");
      }
      if (error) {
        try {
          source?.destroy();
        } catch {
          // Ignore stream shutdown races.
        }
        try {
          target?.destroy();
        } catch {
          // Ignore stream shutdown races.
        }
        reject(error);
        return;
      }
      fileTransferred = fileBytes;
      notify(true);
      resolve(fileBytes);
    };

    const cancelCurrent = () => {
      if (settled) {
        return;
      }
      const error = new UploadCanceledError();
      try {
        source?.destroy(error);
      } catch {
        // Ignore stream shutdown races.
      }
      try {
        target?.destroy(error);
      } catch {
        // Ignore stream shutdown races.
      }
      done(error);
    };

    try {
      if (task?.canceled) {
        throw new UploadCanceledError();
      }
      if (task) {
        task.cancelCurrent = cancelCurrent;
      }
      source = fs.createReadStream(localPath);
      target = sftp.createWriteStream(remoteTempPath, { flags: "wx", mode: 0o600 });
      source.on("data", (chunk) => {
        fileTransferred += chunk.length;
        if (fileTransferred > fileBytes || fileTransferred > MAX_SINGLE_TRANSFER_BYTES) {
          done(new Error("上传源文件大小超过安全限制或在传输过程中发生变化。"));
          return;
        }
        notify(false);
      });
      source.on("error", done);
      target.on("error", done);
      target.on("close", () => done());
      notify(true);
      source.pipe(target);
    } catch (error) {
      done(error);
    }
  }).then(async (bytes) => {
    if (task?.canceled) {
      throw new UploadCanceledError();
    }
    await sftpAtomicReplace(sftp, remoteTempPath, remoteFilePath);
    return bytes;
  }).catch(async (error) => {
    await sftpUnlink(sftp, remoteTempPath).catch(() => undefined);
    throw error;
  });
}

async function uploadFilesWithProgress({ tabId, remotePath, localPaths }) {
  const sftp = await ensureSftp(tabId);
  const files = [];
  const remoteNames = new Set();
  for (const localPath of Array.isArray(localPaths) ? localPaths : []) {
    if (!localPath || !fs.existsSync(localPath)) {
      continue;
    }
    const stat = fs.lstatSync(localPath);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_SINGLE_TRANSFER_BYTES) {
      continue;
    }
    const filename = normalizeRemoteName(path.basename(localPath));
    if (remoteNames.has(filename)) {
      throw new Error(`多个上传文件会写入同一个远端名称：${filename}`);
    }
    remoteNames.add(filename);
    files.push({
      localPath,
      filename,
      size: stat.size || 0
    });
  }

  if (!files.length) {
    return { canceled: false, count: 0 };
  }
  if (files.length > MAX_TRANSFER_FILES) {
    throw new Error(`一次最多上传 ${MAX_TRANSFER_FILES} 个文件。`);
  }

  const uploadId = crypto.randomUUID();
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
  if (!Number.isSafeInteger(totalBytes) || totalBytes > MAX_TRANSFER_BYTES) {
    throw new Error("上传总大小超过安全限制。");
  }
  const startedAt = Date.now();
  const task = {
    tabId,
    canceled: false,
    cancelCurrent: null,
    lastProgress: null
  };
  let transferredBytes = 0;
  let currentIndex = 0;
  activeUploads.set(uploadId, task);

  try {
    for (currentIndex = 0; currentIndex < files.length; currentIndex += 1) {
      if (task.canceled) {
        throw new UploadCanceledError();
      }
      const file = files[currentIndex];
      transferredBytes += await uploadFileWithProgress(sftp, {
        uploadId,
        tabId,
        remotePath,
        localPath: file.localPath,
        remoteFilePath: joinRemotePath(remotePath, file.filename),
        filename: file.filename,
        index: currentIndex + 1,
        totalFiles: files.length,
        totalBytes,
        transferredBefore: transferredBytes,
        startedAt,
        task
      });
    }
    sendUploadProgress({
      uploadId,
      tabId,
      remotePath,
      filename: files[files.length - 1].filename,
      index: files.length,
      totalFiles: files.length,
      fileBytes: files[files.length - 1].size,
      fileTransferred: files[files.length - 1].size,
      totalBytes,
      transferredBytes: totalBytes,
      startedAt,
      state: "done"
    });
    return { canceled: false, count: files.length };
  } catch (error) {
    if (isUploadCanceled(error)) {
      const file = files[Math.max(0, Math.min(files.length - 1, currentIndex))];
      sendUploadProgress({
        ...(task.lastProgress || {
          uploadId,
          tabId,
          remotePath,
          filename: file?.filename || "",
          index: Math.max(1, Math.min(files.length, currentIndex + 1)),
          totalFiles: files.length,
          fileBytes: file?.size || 0,
          fileTransferred: 0,
          totalBytes,
          transferredBytes,
          startedAt
        }),
        state: "canceled",
        error: "Upload canceled."
      });
      return { canceled: true, count: currentIndex };
    }
    const file = files[Math.max(0, Math.min(files.length - 1, currentIndex))];
    sendUploadProgress({
      uploadId,
      tabId,
      remotePath,
      filename: file?.filename || "",
      index: Math.max(1, Math.min(files.length, currentIndex + 1)),
      totalFiles: files.length,
      fileBytes: file?.size || 0,
      fileTransferred: 0,
      totalBytes,
      transferredBytes,
      startedAt,
      state: "error",
      error: errorMessage(error)
    });
    throw error;
  } finally {
    activeUploads.delete(uploadId);
  }
}

async function sftpFastGet(sftp, remotePath, localPath) {
  fs.mkdirSync(path.dirname(localPath), { recursive: true });
  const tempPath = path.join(path.dirname(localPath), `.${path.basename(localPath)}.${crypto.randomUUID()}.part`);
  try {
    await sftpFastGetRaw(sftp, remotePath, tempPath);
    replaceFileFromTemp(tempPath, localPath);
  } catch (error) {
    try {
      fs.rmSync(tempPath, { force: true });
    } catch {
      // Ignore partial download cleanup failures.
    }
    throw error;
  }
}

function sendDownloadProgress({
  downloadId,
  tabId,
  remotePath,
  filename,
  index,
  totalFiles,
  fileBytes,
  fileTransferred,
  totalBytes,
  transferredBytes,
  startedAt,
  state = "running",
  error = ""
}) {
  const elapsedSeconds = Math.max((Date.now() - startedAt) / 1000, 0.001);
  const bytesPerSecond = transferredBytes / elapsedSeconds;
  const remainingBytes = Math.max(totalBytes - transferredBytes, 0);
  send("sftp:download-progress", {
    downloadId,
    tabId,
    remotePath,
    filename,
    index,
    totalFiles,
    fileBytes,
    fileTransferred,
    totalBytes,
    transferredBytes,
    percent: totalBytes ? Math.min(100, Math.round((transferredBytes / totalBytes) * 1000) / 10) : 100,
    bytesPerSecond,
    etaSeconds: bytesPerSecond > 0 && state === "running" ? remainingBytes / bytesPerSecond : 0,
    state,
    error
  });
}

function downloadFileWithProgress(sftp, options) {
  const {
    downloadId,
    tabId,
    remotePath,
    remoteFilePath,
    localPath,
    filename,
    index,
    totalFiles,
    fileBytes,
    totalBytes,
    transferredBefore,
    startedAt,
    task
  } = options;
  const localTempPath = path.join(
    path.dirname(localPath),
    `.${path.basename(localPath)}.${process.pid}.${crypto.randomUUID()}.part`
  );

  return new Promise((resolve, reject) => {
    let settled = false;
    let fileTransferred = 0;
    let lastSentAt = 0;
    let source = null;
    let target = null;

    const notify = (force = false) => {
      const now = Date.now();
      if (!force && now - lastSentAt < 160) {
        return;
      }
      lastSentAt = now;
      const progress = {
        downloadId,
        tabId,
        remotePath,
        filename,
        index,
        totalFiles,
        fileBytes,
        fileTransferred,
        totalBytes,
        transferredBytes: transferredBefore + fileTransferred,
        startedAt
      };
      if (task) {
        task.lastProgress = progress;
      }
      sendDownloadProgress(progress);
    };

    const done = (error) => {
      if (settled) {
        return;
      }
      settled = true;
      if (task?.cancelCurrent === cancelCurrent) {
        task.cancelCurrent = null;
      }
      if (task?.canceled && !error) {
        error = new DownloadCanceledError();
      }
      if (!error && fileTransferred !== fileBytes) {
        error = new Error("远端文件在下载过程中发生变化。");
      }
      if (error) {
        try {
          source?.destroy();
        } catch {
          // Ignore stream shutdown races.
        }
        try {
          target?.destroy();
        } catch {
          // Ignore stream shutdown races.
        }
        try {
          if (fs.existsSync(localTempPath)) {
            fs.rmSync(localTempPath, { force: true });
          }
        } catch {
          // Ignore partial file cleanup errors.
        }
        reject(error);
        return;
      }
      fileTransferred = fileBytes;
      notify(true);
      resolve(fileBytes);
    };

    const cancelCurrent = () => {
      if (settled) {
        return;
      }
      const error = new DownloadCanceledError();
      try {
        source?.destroy(error);
      } catch {
        // Ignore stream shutdown races.
      }
      try {
        target?.destroy(error);
      } catch {
        // Ignore stream shutdown races.
      }
      done(error);
    };

    try {
      if (task?.canceled) {
        throw new DownloadCanceledError();
      }
      if (task) {
        task.cancelCurrent = cancelCurrent;
      }
      fs.mkdirSync(path.dirname(localPath), { recursive: true });
      source = sftp.createReadStream(remoteFilePath);
      target = fs.createWriteStream(localTempPath, { flags: "wx", mode: 0o600 });
      source.on("data", (chunk) => {
        fileTransferred += chunk.length;
        if (fileTransferred > fileBytes || transferredBefore + fileTransferred > MAX_TRANSFER_BYTES) {
          done(new Error("远端文件大小超过下载安全限制。"));
          return;
        }
        notify(false);
      });
      source.on("error", done);
      target.on("error", done);
      target.on("close", () => done());
      notify(true);
      source.pipe(target);
    } catch (error) {
      done(error);
    }
  }).then((bytes) => {
    replaceFileFromTemp(localTempPath, localPath);
    return bytes;
  }).catch((error) => {
    try {
      fs.rmSync(localTempPath, { force: true });
    } catch {
      // Ignore partial download cleanup failures.
    }
    throw error;
  });
}

async function downloadFilesWithProgress({ tabId, sessionId, remotePath, files, targetPathForFile }) {
  const sftp = await ensureSftp(tabId, sessionId);
  if (!Array.isArray(files) || files.length > MAX_TRANSFER_FILES) {
    throw new Error(`一次最多下载 ${MAX_TRANSFER_FILES} 个文件。`);
  }
  const downloadId = crypto.randomUUID();
  const totalBytes = files.reduce((sum, file) => sum + (file.size || 0), 0);
  if (!Number.isSafeInteger(totalBytes) || totalBytes > MAX_TRANSFER_BYTES || files.some((file) => !Number.isSafeInteger(file.size || 0) || (file.size || 0) > MAX_SINGLE_TRANSFER_BYTES)) {
    throw new Error("下载大小超过安全限制。");
  }
  const startedAt = Date.now();
  const task = {
    tabId,
    canceled: false,
    cancelCurrent: null,
    lastProgress: null
  };
  let transferredBytes = 0;
  let currentIndex = 0;
  activeDownloads.set(downloadId, task);

  try {
    if (!files.length) {
      sendDownloadProgress({
        downloadId,
        tabId,
        remotePath,
        filename: "download",
        index: 0,
        totalFiles: 0,
        fileBytes: 0,
        fileTransferred: 0,
        totalBytes: 0,
        transferredBytes: 0,
        startedAt,
        state: "done"
      });
      return { canceled: false, count: 0 };
    }
    for (currentIndex = 0; currentIndex < files.length; currentIndex += 1) {
      if (task.canceled) {
        throw new DownloadCanceledError();
      }
      const file = files[currentIndex];
      transferredBytes += await downloadFileWithProgress(sftp, {
        downloadId,
        tabId,
        remotePath,
        remoteFilePath: file.remotePath,
        localPath: targetPathForFile(file),
        filename: file.relativePath || file.filename,
        index: currentIndex + 1,
        totalFiles: files.length,
        fileBytes: file.size || 0,
        totalBytes,
        transferredBefore: transferredBytes,
        startedAt,
        task
      });
    }
    const lastFile = files[files.length - 1];
    sendDownloadProgress({
      downloadId,
      tabId,
      remotePath,
      filename: lastFile.relativePath || lastFile.filename,
      index: files.length,
      totalFiles: files.length,
      fileBytes: lastFile.size || 0,
      fileTransferred: lastFile.size || 0,
      totalBytes,
      transferredBytes: totalBytes,
      startedAt,
      state: "done"
    });
    return { canceled: false, count: files.length };
  } catch (error) {
    if (isDownloadCanceled(error)) {
      const file = files[Math.max(0, Math.min(files.length - 1, currentIndex))];
      sendDownloadProgress({
        ...(task.lastProgress || {
          downloadId,
          tabId,
          remotePath,
          filename: file?.relativePath || file?.filename || "",
          index: Math.max(1, Math.min(files.length, currentIndex + 1)),
          totalFiles: files.length,
          fileBytes: file?.size || 0,
          fileTransferred: 0,
          totalBytes,
          transferredBytes,
          startedAt
        }),
        state: "canceled",
        error: "Download canceled."
      });
      return { canceled: true, count: currentIndex };
    }
    const file = files[Math.max(0, Math.min(files.length - 1, currentIndex))];
    sendDownloadProgress({
      downloadId,
      tabId,
      remotePath,
      filename: file?.relativePath || file?.filename || "",
      index: Math.max(1, Math.min(files.length, currentIndex + 1)),
      totalFiles: files.length,
      fileBytes: file?.size || 0,
      fileTransferred: 0,
      totalBytes,
      transferredBytes,
      startedAt,
      state: "error",
      error: errorMessage(error)
    });
    throw error;
  } finally {
    activeDownloads.delete(downloadId);
  }
}

function sftpReadTextFile(sftp, remotePath, maxBytes = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    const stream = sftp.createReadStream(remotePath);
    stream.on("data", (chunk) => {
      size += chunk.length;
      if (size > maxBytes) {
        stream.destroy(new Error("文件太大，内置编辑器最多打开 8 MB。"));
        return;
      }
      chunks.push(chunk);
    });
    stream.on("error", reject);
    stream.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

async function sftpWriteFile(sftp, remotePath, content = "") {
  const value = String(content);
  if (Buffer.byteLength(value, "utf8") > 8 * 1024 * 1024) {
    throw new Error("文件太大，内置编辑器最多保存 8 MB。");
  }
  const tempPath = `${remotePath}.yyslide-${crypto.randomUUID()}.part`;
  try {
    await new Promise((resolve, reject) => {
    let settled = false;
    const done = (error) => {
      if (settled) {
        return;
      }
      settled = true;
      if (error) {
        reject(error);
        return;
      }
      resolve();
    };
    const stream = sftp.createWriteStream(tempPath, { flags: "wx", mode: 0o600 });
    stream.on("error", done);
    stream.on("close", () => done());
      stream.end(value);
    });
    await sftpAtomicReplace(sftp, tempPath, remotePath);
  } catch (error) {
    await sftpUnlink(sftp, tempPath).catch(() => undefined);
    throw error;
  }
}

function sftpCreateEmptyFile(sftp, remotePath) {
  return new Promise((resolve, reject) => {
    sftp.open(remotePath, "wx", (openError, handle) => {
      if (openError) {
        reject(openError);
        return;
      }
      sftp.close(handle, (closeError) => {
        if (closeError) {
          reject(closeError);
          return;
        }
        resolve();
      });
    });
  });
}

function sftpStat(sftp, remotePath) {
  return new Promise((resolve, reject) => {
    sftp.stat(remotePath, (error, stats) => {
      if (error) {
        reject(error);
        return;
      }
      resolve(stats);
    });
  });
}

function sftpLstat(sftp, remotePath) {
  return new Promise((resolve, reject) => {
    sftp.lstat(remotePath, (error, stats) => {
      if (error) {
        reject(error);
        return;
      }
      resolve(stats);
    });
  });
}

async function collectRemoteDownloadEntries(sftp, baseRemotePath, items) {
  const files = [];
  const directories = [];
  const usedPaths = new Set();
  const budget = createTransferBudget({
    maxEntries: MAX_TRANSFER_FILES,
    maxDepth: MAX_TRANSFER_DEPTH,
    maxFileBytes: Math.min(MAX_SINGLE_TRANSFER_BYTES, 0xffffffff),
    maxTotalBytes: Math.min(MAX_TRANSFER_BYTES, 0xd0000000)
  });

  const visit = async (remotePath, relativePath, depth) => {
    const stats = await sftpLstat(sftp, remotePath);
    const isDirectory = Boolean(stats.isDirectory?.());
    const size = isDirectory ? 0 : Number(stats.size || 0);
    budget.add({
      depth,
      size,
      symbolicLink: Boolean(stats.isSymbolicLink?.()),
      label: relativePath
    });
    if (stats.isDirectory && stats.isDirectory()) {
      const directoryPath = relativePath.endsWith("/") ? relativePath : `${relativePath}/`;
      directories.push({ remotePath, relativePath: directoryPath });
      const children = await sftpReaddir(sftp, remotePath);
      if (children.length > MAX_TRANSFER_FILES - budget.snapshot().entries) {
        throw new Error(`递归下载最多包含 ${MAX_TRANSFER_FILES} 个项目。`);
      }
      for (const child of children) {
        if (!child?.filename || child.filename === "." || child.filename === "..") {
          continue;
        }
        const remoteName = normalizeRemoteName(child.filename);
        const childName = uniqueRelativeChildName(relativePath, remoteName, usedPaths);
        await visit(joinRemotePath(remotePath, remoteName), path.posix.join(relativePath, childName), depth + 1);
      }
      return;
    }
    if (stats.isFile && !stats.isFile()) {
      return;
    }
    files.push({
      remotePath,
      relativePath,
      filename: path.posix.basename(relativePath),
      size
    });
  };

  const selectedItems = Array.isArray(items) ? items : [];
  if (selectedItems.length > MAX_TRANSFER_FILES) {
    throw new Error(`一次最多选择 ${MAX_TRANSFER_FILES} 个项目。`);
  }
  for (const item of selectedItems) {
    if (!item?.filename) {
      continue;
    }
    const filename = normalizeRemoteName(item.filename);
    const relativeName = uniqueRelativeChildName("", filename, usedPaths);
    await visit(joinRemotePath(baseRemotePath, filename), relativeName, 0);
  }

  return { files, directories };
}

const crc32Table = Array.from({ length: 256 }, (_unused, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) {
    value = value & 1 ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
  }
  return value >>> 0;
});
function updateCrc32(crc, chunk) {
  let value = crc ^ -1;
  for (let index = 0; index < chunk.length; index += 1) {
    value = crc32Table[(value ^ chunk[index]) & 0xff] ^ (value >>> 8);
  }
  return (value ^ -1) >>> 0;
}

async function crc32File(filePath) {
  const handle = await fs.promises.open(filePath, "r");
  const buffer = Buffer.allocUnsafe(1024 * 512);
  let crc = 0;
  try {
    while (true) {
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
      if (!bytesRead) {
        break;
      }
      crc = updateCrc32(crc, buffer.subarray(0, bytesRead));
    }
  } finally {
    await handle.close();
  }
  return crc >>> 0;
}

function dosDateTime(date) {
  const year = Math.max(1980, date.getFullYear());
  const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  const dosDate = ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { dosDate, dosTime };
}

function normalizeZipEntryName(name, directory = false) {
  let entryName = String(name || "")
    .replace(/\\/g, "/")
    .replace(/^\/+/, "")
    .replace(/\/+/g, "/");
  const parts = entryName.split("/").filter(Boolean);
  if (!parts.length || parts.some((part) => part === "." || part === ".." || part.includes("\0"))) {
    throw new Error("ZIP 项目路径无效。");
  }
  if (directory && !entryName.endsWith("/")) {
    entryName += "/";
  }
  return entryName;
}

function makeZipLocalHeader(entryName, entry) {
  const nameBuffer = Buffer.from(entryName, "utf8");
  const header = Buffer.alloc(30);
  header.writeUInt32LE(0x04034b50, 0);
  header.writeUInt16LE(20, 4);
  header.writeUInt16LE(0x0800, 6);
  header.writeUInt16LE(0, 8);
  header.writeUInt16LE(entry.dosTime, 10);
  header.writeUInt16LE(entry.dosDate, 12);
  header.writeUInt32LE(entry.crc >>> 0, 14);
  header.writeUInt32LE(entry.size, 18);
  header.writeUInt32LE(entry.size, 22);
  header.writeUInt16LE(nameBuffer.length, 26);
  header.writeUInt16LE(0, 28);
  return Buffer.concat([header, nameBuffer]);
}

function makeZipCentralHeader(entryName, entry) {
  const nameBuffer = Buffer.from(entryName, "utf8");
  const header = Buffer.alloc(46);
  header.writeUInt32LE(0x02014b50, 0);
  header.writeUInt16LE(20, 4);
  header.writeUInt16LE(20, 6);
  header.writeUInt16LE(0x0800, 8);
  header.writeUInt16LE(0, 10);
  header.writeUInt16LE(entry.dosTime, 12);
  header.writeUInt16LE(entry.dosDate, 14);
  header.writeUInt32LE(entry.crc >>> 0, 16);
  header.writeUInt32LE(entry.size, 20);
  header.writeUInt32LE(entry.size, 24);
  header.writeUInt16LE(nameBuffer.length, 28);
  header.writeUInt16LE(0, 30);
  header.writeUInt16LE(0, 32);
  header.writeUInt16LE(0, 34);
  header.writeUInt16LE(0, 36);
  header.writeUInt32LE(entry.directory ? 0x10 : 0x20, 38);
  header.writeUInt32LE(entry.offset, 42);
  return Buffer.concat([header, nameBuffer]);
}

async function copyFileToZipHandle(filePath, output) {
  const input = await fs.promises.open(filePath, "r");
  const buffer = Buffer.allocUnsafe(1024 * 512);
  try {
    while (true) {
      const { bytesRead } = await input.read(buffer, 0, buffer.length, null);
      if (!bytesRead) {
        break;
      }
      await output.write(buffer, 0, bytesRead, null);
    }
  } finally {
    await input.close();
  }
}

async function writeStoredZip(zipPath, rootPath, files, directories = []) {
  await fs.promises.mkdir(path.dirname(zipPath), { recursive: true });
  const output = await fs.promises.open(zipPath, "wx", 0o600);
  const central = [];
  let offset = 0;
  const writtenNames = new Set();

  const writeEntry = async (entryName, entry, localPath) => {
    if (!entryName || writtenNames.has(entryName)) {
      return;
    }
    if (entry.size > 0xffffffff || offset > 0xffffffff) {
      throw new Error("ZIP64 is not supported for this download.");
    }
    writtenNames.add(entryName);
    entry.offset = offset;
    const localHeader = makeZipLocalHeader(entryName, entry);
    await output.write(localHeader, 0, localHeader.length, null);
    offset += localHeader.length;
    if (localPath) {
      await copyFileToZipHandle(localPath, output);
      offset += entry.size;
    }
    central.push({ entryName, entry: { ...entry } });
  };

  try {
    const now = dosDateTime(new Date());
    for (const directory of directories) {
      const entryName = normalizeZipEntryName(directory.relativePath, true);
      await writeEntry(entryName, {
        crc: 0,
        size: 0,
        directory: true,
        ...now
      });
    }
    for (const file of files) {
      const entryName = normalizeZipEntryName(file.relativePath);
      const localPath = pathFromRelative(rootPath, file.relativePath);
      const stats = fs.statSync(localPath);
      const timestamp = dosDateTime(stats.mtime || new Date());
      await writeEntry(entryName, {
        crc: await crc32File(localPath),
        size: stats.size,
        directory: false,
        ...timestamp
      }, localPath);
    }
    const centralOffset = offset;
    for (const item of central) {
      const centralHeader = makeZipCentralHeader(item.entryName, item.entry);
      await output.write(centralHeader, 0, centralHeader.length, null);
      offset += centralHeader.length;
    }
    const centralSize = offset - centralOffset;
    if (central.length > 0xffff || centralOffset > 0xffffffff || centralSize > 0xffffffff) {
      throw new Error("ZIP64 is not supported for this download.");
    }
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(0, 4);
    end.writeUInt16LE(0, 6);
    end.writeUInt16LE(central.length, 8);
    end.writeUInt16LE(central.length, 10);
    end.writeUInt32LE(centralSize, 12);
    end.writeUInt32LE(centralOffset, 16);
    end.writeUInt16LE(0, 20);
    await output.write(end, 0, end.length, null);
    await output.sync();
  } finally {
    await output.close();
  }
}

async function createZipDownloadFile({ tabId, sessionId, remotePath, items, zipPath }) {
  const sftp = await ensureSftp(tabId, sessionId);
  const selected = Array.isArray(items) ? items : [];
  if (!selected.length) {
    throw new Error("No files selected.");
  }
  const stagingRoot = path.join(app.getPath("temp"), "yySlide-downloads", crypto.randomUUID());
  const zipTempPath = path.join(path.dirname(zipPath), `.${path.basename(zipPath)}.${crypto.randomUUID()}.part`);
  fs.mkdirSync(stagingRoot, { recursive: true });
  try {
    const { files, directories } = await collectRemoteDownloadEntries(sftp, remotePath, selected);
    const result = await downloadFilesWithProgress({
      tabId,
      sessionId,
      remotePath,
      files,
      targetPathForFile: (file) => pathFromRelative(stagingRoot, file.relativePath)
    });
    if (result.canceled) {
      return result;
    }
    await writeStoredZip(zipTempPath, stagingRoot, files, directories);
    replaceFileFromTemp(zipTempPath, zipPath);
    return { canceled: false, count: selected.length, path: zipPath };
  } finally {
    fs.rmSync(stagingRoot, { recursive: true, force: true });
    fs.rmSync(zipTempPath, { force: true });
  }
}

async function downloadRemoteItemToTemp({ tabId, sessionId, remotePath, item, tempPath }) {
  const sftp = await ensureSftp(tabId, sessionId);
  const filename = normalizeRemoteName(item.filename);
  const target = joinRemotePath(remotePath, normalizeRemoteName(filename));
  const stats = await sftpLstat(sftp, target);
  if (stats.isSymbolicLink?.() || !stats.isFile?.()) {
    throw new Error("拖拽下载只支持普通文件，不能下载符号链接。");
  }
  const result = await downloadFilesWithProgress({
    tabId,
    sessionId,
    remotePath,
    files: [{
      remotePath: target,
      relativePath: safeLocalName(filename, "download"),
      filename,
      size: stats.size || 0
    }],
    targetPathForFile: () => tempPath
  });
  return { ...result, size: stats.size || 0 };
}

function contentDisposition(filename) {
  const fallback = safeLocalName(filename, "download").replace(/[^\x20-\x7e]/g, "_");
  return `attachment; filename="${fallback.replace(/"/g, "_")}"; filename*=UTF-8''${encodeURIComponent(filename)}`;
}

function registerDragDownload({ tabId, remotePath, items }) {
  if (!dragDownloadServerPort) {
    throw new Error("Drag download server is not ready.");
  }
  const session = sessions.get(tabId);
  if (!session?.ready) {
    throw new Error("SSH session is not ready.");
  }
  const selected = Array.isArray(items)
    ? items.slice(0, 1001).filter((item) => item?.filename).map((item) => ({
        filename: normalizeRemoteName(item.filename),
        isDirectory: item.isDirectory === true
      }))
    : [];
  if (selected.length > 1000) {
    throw new Error("一次最多拖拽 1000 个项目。");
  }
  if (!selected.length) {
    return null;
  }
  const shouldZip = selected.length !== 1 || selected[0].isDirectory;
  const filename = shouldZip
    ? `${selected.length === 1 ? safeLocalName(selected[0].filename, "download") : safeLocalName(path.posix.basename(normalizeRemotePath(remotePath)) || "download", "download")}.zip`
    : safeLocalName(selected[0].filename, "download");
  const token = crypto.randomUUID();
  dragDownloads.set(token, {
    tabId,
    sessionId: session.id,
    remotePath,
    items: selected,
    filename,
    shouldZip,
    createdAt: Date.now()
  });
  setTimeout(() => {
    dragDownloads.delete(token);
  }, 10 * 60 * 1000).unref?.();
  return {
    filename,
    mime: shouldZip ? "application/zip" : "application/octet-stream",
    url: `http://127.0.0.1:${dragDownloadServerPort}/drag/${token}/${encodeURIComponent(filename)}`
  };
}

async function serveDragDownload(request, response) {
  try {
    if (request.method !== "GET" && request.method !== "HEAD") {
      response.writeHead(405);
      response.end();
      return;
    }
    const url = new URL(request.url || "/", "http://127.0.0.1");
    const [, route, token] = url.pathname.split("/");
    if (route !== "drag" || !token || !dragDownloads.has(token)) {
      response.writeHead(404);
      response.end();
      return;
    }
    const task = dragDownloads.get(token);
    if (request.method === "HEAD") {
      response.writeHead(200, {
        "Content-Type": task.shouldZip ? "application/zip" : "application/octet-stream",
        "Content-Disposition": contentDisposition(task.filename)
      });
      response.end();
      return;
    }
    dragDownloads.delete(token);
    if (sessions.get(task.tabId)?.id !== task.sessionId) {
      response.writeHead(410);
      response.end();
      return;
    }

    if (task.shouldZip) {
      const zipPath = path.join(app.getPath("temp"), "yySlide-drag", `${token}.zip`);
      const result = await createZipDownloadFile({
        tabId: task.tabId,
        sessionId: task.sessionId,
        remotePath: task.remotePath,
        items: task.items,
        zipPath
      });
      if (result.canceled) {
        response.writeHead(499);
        response.end();
        return;
      }
      const stats = fs.statSync(zipPath);
      response.writeHead(200, {
        "Content-Type": "application/zip",
        "Content-Length": stats.size,
        "Content-Disposition": contentDisposition(task.filename)
      });
      const stream = fs.createReadStream(zipPath);
      stream.on("close", () => fs.rmSync(zipPath, { force: true }));
      stream.pipe(response);
      return;
    }

    const tempRoot = path.join(app.getPath("temp"), "yySlide-drag", token);
    const tempPath = path.join(tempRoot, safeLocalName(task.filename, "download"));
    fs.mkdirSync(tempRoot, { recursive: true });
    const item = task.items[0];
    const result = await downloadRemoteItemToTemp({
      tabId: task.tabId,
      sessionId: task.sessionId,
      remotePath: task.remotePath,
      item,
      tempPath
    });
    if (result.canceled) {
      fs.rmSync(tempRoot, { recursive: true, force: true });
      response.writeHead(499);
      response.end();
      return;
    }
    const stats = fs.statSync(tempPath);
    response.writeHead(200, {
      "Content-Type": "application/octet-stream",
      "Content-Length": stats.size || 0,
      "Content-Disposition": contentDisposition(task.filename)
    });
    const source = fs.createReadStream(tempPath);
    source.on("close", () => fs.rmSync(tempRoot, { recursive: true, force: true }));
    source.on("error", () => {
      fs.rmSync(tempRoot, { recursive: true, force: true });
      response.destroy();
    });
    source.pipe(response);
  } catch (error) {
    if (!response.headersSent) {
      response.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
    }
    response.end(errorMessage(error));
  }
}

function startDragDownloadServer() {
  if (dragDownloadServer) {
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    dragDownloadServer = http.createServer((request, response) => {
      void serveDragDownload(request, response);
    });
    dragDownloadServer.on("error", reject);
    dragDownloadServer.listen(0, "127.0.0.1", () => {
      dragDownloadServerPort = dragDownloadServer.address().port;
      resolve();
    });
  });
}

function watchEditedFile({ tabId, localPath, remotePath }) {
  const key = `${tabId}:${remotePath}:${localPath}`;
  const existing = watchedEdits.get(key);
  if (existing) {
    clearTimeout(existing.timer);
    existing.watcher.close();
  }

  let uploading = false;
  let pending = false;
  let timer = null;
  const uploadBack = async () => {
    if (uploading) {
      pending = true;
      return;
    }
    if (!fs.existsSync(localPath)) {
      return;
    }
    uploading = true;
    try {
      const stats = fs.lstatSync(localPath);
      if (!stats.isFile() || stats.isSymbolicLink() || stats.size > MAX_SINGLE_TRANSFER_BYTES) {
        throw new Error("本地编辑缓存不是可上传的普通文件或文件过大。");
      }
      const sftp = await ensureSftp(tabId);
      await sftpFastPut(sftp, localPath, remotePath);
      send("sftp:file-saved", { tabId, remotePath });
    } catch (error) {
      send("sftp:file-save-error", { tabId, remotePath, error: errorMessage(error) });
    } finally {
      uploading = false;
      if (pending) {
        pending = false;
        timer = setTimeout(uploadBack, 100);
      }
    }
  };

  const watcher = fs.watch(localPath, () => {
    if (timer) {
      clearTimeout(timer);
    }
    timer = setTimeout(uploadBack, 450);
  });
  watchedEdits.set(key, {
    watcher,
    tabId,
    localPath,
    remotePath,
    get timer() {
      return timer;
    }
  });
}

function sftpUnlink(sftp, remotePath) {
  return new Promise((resolve, reject) => {
    sftp.unlink(remotePath, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

function sftpRmdir(sftp, remotePath) {
  return new Promise((resolve, reject) => {
    sftp.rmdir(remotePath, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

function sftpMkdir(sftp, remotePath) {
  return new Promise((resolve, reject) => {
    sftp.mkdir(remotePath, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

function sftpRename(sftp, oldPath, newPath) {
  return new Promise((resolve, reject) => {
    sftp.rename(oldPath, newPath, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

function sftpPosixRename(sftp, oldPath, newPath) {
  return new Promise((resolve, reject) => {
    if (typeof sftp.ext_openssh_rename !== "function") {
      reject(new Error("Atomic rename extension is unavailable."));
      return;
    }
    sftp.ext_openssh_rename(oldPath, newPath, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

async function sftpAtomicReplace(sftp, tempPath, targetPath) {
  try {
    await sftpPosixRename(sftp, tempPath, targetPath);
    return;
  } catch {
    // Fall through for servers without the OpenSSH posix-rename extension.
  }
  try {
    await sftpRename(sftp, tempPath, targetPath);
    return;
  } catch (renameError) {
    const backupPath = `${targetPath}.yyslide-${crypto.randomUUID()}.backup`;
    try {
      await sftpRename(sftp, targetPath, backupPath);
    } catch {
      throw renameError;
    }
    try {
      await sftpRename(sftp, tempPath, targetPath);
    } catch (replaceError) {
      await sftpRename(sftp, backupPath, targetPath).catch(() => undefined);
      throw replaceError;
    }
    await sftpUnlink(sftp, backupPath).catch(() => undefined);
  }
}

function loadNodePty() {
  if (!nodePty) {
    nodePty = require("node-pty");
  }
  return nodePty;
}

function clampTerminalSize(value, fallback, max) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return fallback;
  }
  return Math.max(2, Math.min(max, Math.floor(number)));
}

function localTerminalEnv() {
  const env = {
    ...process.env,
    TERM: "xterm-256color",
    COLORTERM: "truecolor"
  };
  delete env.ELECTRON_RUN_AS_NODE;
  return env;
}

function resolveLocalShell() {
  if (process.platform === "win32") {
    return {
      file: process.env.YYSLIDE_LOCAL_SHELL || "powershell.exe",
      args: ["-NoLogo"]
    };
  }
  if (process.platform === "darwin") {
    return {
      file: process.env.SHELL || "/bin/zsh",
      args: ["-l"]
    };
  }
  return {
    file: process.env.SHELL || "/bin/bash",
    args: ["-l"]
  };
}

function createLocalTerminal({ id, cols, rows }) {
  const pty = loadNodePty();
  const shellInfo = resolveLocalShell();
  const options = {
    name: "xterm-256color",
    cols: clampTerminalSize(cols, 100, 500),
    rows: clampTerminalSize(rows, 28, 200),
    cwd: os.homedir(),
    env: localTerminalEnv()
  };
  if (process.platform === "win32") {
    options.useConpty = true;
  }

  const terminal = pty.spawn(shellInfo.file, shellInfo.args, options);
  terminal.onData((data) => {
    send("local-terminal:data", { id, data });
  });
  terminal.onExit(({ exitCode, signal }) => {
    localTerminals.delete(id);
    send("local-terminal:exit", { id, exitCode, signal });
  });

  return {
    terminal,
    shell: shellInfo.file
  };
}

handleIpc("account:state", () => accountPublicState());

handleIpc("account:register-start", async (_event, { serverUrl, username }) => (
  cloudRequest(normalizeServerUrl(serverUrl), "POST", "/v1/auth/register/start", { username })
));

handleIpc("account:register-finish", async (_event, { serverUrl, username, password, totpCode, syncEnabled }) => {
  const authAttempt = beginAccountAuthAttempt();
  const url = normalizeServerUrl(serverUrl);
  const vaultKey = randomBytesBase64(32);
  const wrappedVaultKey = wrapVaultKey(vaultKey, password);
  const recoveryCodes = makeRecoveryCodes();
  const result = await cloudRequest(url, "POST", "/v1/auth/register/finish", {
    username,
    password,
    totpCode,
    wrappedVaultKey,
    recoveryKeys: recoveryKeysForVault(vaultKey, recoveryCodes)
  });
  assertCurrentAccountAuthAttempt(authAttempt);
  storeAccountSession({
    serverUrl: url,
    token: result.token,
    vaultKey,
    user: result.user,
    syncEnabled: Boolean(syncEnabled)
  });
  if (syncEnabled) {
    await performCloudSync(readAccountStore().sessionId);
    startCloudEvents();
  } else {
    stopCloudEvents();
  }
  return { ...accountPublicState(), recoveryCodes };
});

handleIpc("account:login", async (_event, { serverUrl, username, password, totpCode, syncEnabled }) => {
  const authAttempt = beginAccountAuthAttempt();
  const url = normalizeServerUrl(serverUrl);
  const result = await cloudRequest(url, "POST", "/v1/auth/login", {
    username,
    password,
    totpCode
  });
  const vaultKey = unwrapVaultKey(result.wrappedVaultKey, password);
  assertCurrentAccountAuthAttempt(authAttempt);
  storeAccountSession({
    serverUrl: url,
    token: result.token,
    vaultKey,
    user: result.user,
    syncEnabled: Boolean(syncEnabled)
  });
  if (syncEnabled) {
    await performCloudSync(readAccountStore().sessionId);
    startCloudEvents();
  } else {
    stopCloudEvents();
  }
  return accountPublicState();
});

handleIpc("account:reset-start", async (_event, { serverUrl, username, recoveryCode }) => (
  cloudRequest(normalizeServerUrl(serverUrl), "POST", "/v1/auth/reset/start", { username, recoveryCode })
));

handleIpc("account:reset-finish", async (_event, { serverUrl, username, password, totpCode, recoveryCode, resetId, recoveryKeyBox, syncEnabled }) => {
  const authAttempt = beginAccountAuthAttempt();
  const url = normalizeServerUrl(serverUrl);
  const vaultKey = unwrapVaultKeyFromRecovery(recoveryKeyBox, recoveryCode);
  const wrappedVaultKey = wrapVaultKey(vaultKey, password);
  const recoveryCodes = makeRecoveryCodes();
  const result = await cloudRequest(url, "POST", "/v1/auth/reset/finish", {
    username,
    password,
    totpCode,
    resetId,
    wrappedVaultKey,
    recoveryKeys: recoveryKeysForVault(vaultKey, recoveryCodes)
  });
  assertCurrentAccountAuthAttempt(authAttempt);
  storeAccountSession({
    serverUrl: url,
    token: result.token,
    vaultKey,
    user: result.user,
    syncEnabled: Boolean(syncEnabled)
  });
  if (syncEnabled) {
    await performCloudSync(readAccountStore().sessionId);
    startCloudEvents();
  } else {
    stopCloudEvents();
  }
  return { ...accountPublicState(), recoveryCodes };
});

handleIpc("account:logout", async () => {
  let remoteSession = null;
  try {
    const { serverUrl, token } = currentAccountSession();
    remoteSession = { serverUrl, token };
  } catch {
    // The local session is cleared even if its encrypted token is unavailable.
  }
  stopCloudEvents();
  rotateAccountSession();
  cleanupAllRemoteSessions();
  clearAccountStore();
  setActiveDataScope("local");
  if (remoteSession) {
    try {
      await cloudRequest(remoteSession.serverUrl, "POST", "/v1/auth/logout", {}, remoteSession.token, { timeoutMs: 8000 });
    } catch {
      // Local logout must not depend on server availability.
    }
  }
  return accountPublicState();
});

handleIpc("account:set-sync-enabled", async (_event, enabled) => {
  const store = readAccountStore();
  if (!store) {
    return accountPublicState();
  }
  stopCloudEvents();
  rotateAccountSession();
  const nextStore = { ...store, sessionId: crypto.randomUUID(), syncEnabled: Boolean(enabled) };
  writeAccountStore(nextStore);
  if (enabled) {
    const state = await performCloudSync(nextStore.sessionId);
    startCloudEvents();
    return state;
  }
  stopCloudEvents();
  return accountPublicState();
});

handleIpc("account:sync-now", async () => {
  const store = readAccountStore();
  const state = await performCloudSync(store?.sessionId);
  if (readAccountStore()?.syncEnabled) {
    startCloudEvents();
  }
  return state;
});

handleIpc("account:pull-now", async () => performCloudPull(readAccountStore()?.sessionId));

handleIpc("connections:list", () => readConnections().map(publicConnection));

handleIpc("connections:list-groups", () => readConnectionGroups().map((group) => group.label));

handleIpc("connections:save-groups", (_event, labels) => {
  const existingGroups = readConnectionGroups();
  const existingByLabel = new Map(existingGroups.map((group) => [groupLabelKey(group.label), group]));
  const nextGroups = normalizeConnectionGroups(labels, existingGroups).map((group, index) => {
    const existing = existingByLabel.get(groupLabelKey(group.label));
    return {
      ...group,
      sortOrder: index,
      updatedAt: existing && existing.label === group.label && Number(existing.sortOrder || 0) === index
        ? existing.updatedAt
        : new Date().toISOString()
    };
  });
  const nextLabels = new Set(nextGroups.map((group) => groupLabelKey(group.label)));
  for (const group of existingGroups) {
    if (!nextLabels.has(groupLabelKey(group.label))) {
      recordDeletedGroup(group);
    }
  }
  writeConnectionGroups(nextGroups);
  const store = readAccountStore();
  if (store?.syncEnabled) {
    void performCloudSync(store.sessionId).catch((error) => console.error("Connection group sync failed", error));
  }
  return nextGroups.map((group) => group.label);
});

handleIpc("connections:save", (_event, input) => {
  input = isPlainObject(input) ? input : {};
  const connections = readConnections();
  const existingIndex = connections.findIndex((item) => item.id === input.id);
  const existing = existingIndex >= 0 ? connections[existingIndex] : null;
  const next = normalizeConnectionInput(input, existing);

  if (existingIndex >= 0) {
    connections[existingIndex] = next;
  } else {
    connections.push(next);
  }
  writeConnections(connections);
  if (existing?.privateKeyPath && (next.authType !== "key" || next.privateKeyPath !== existing.privateKeyPath)) {
    cleanupManagedPrivateKey(existing.privateKeyPath);
  }
  const store = readAccountStore();
  if (store?.syncEnabled) {
    void performCloudSync(store.sessionId).catch((error) => console.error("Connection sync failed", error));
  }
  return publicConnection(next);
});

handleIpc("connections:delete", (_event, id) => {
  const connections = readConnections();
  const deleted = connections.find((item) => item.id === id);
  writeConnections(connections.filter((item) => item.id !== id));
  cleanupManagedPrivateKey(deleted?.privateKeyPath);
  recordDeletedConnection(id);
  const store = readAccountStore();
  if (store?.syncEnabled) {
    void performCloudSync(store.sessionId).catch((error) => console.error("Connection sync failed", error));
  }
  return true;
});

handleIpc("connections:choose-key", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "选择私钥文件",
    properties: ["openFile"],
    filters: [
      { name: "Private keys", extensions: ["pem", "key", "ppk", "txt", "*"] }
    ]
  });
  return result.canceled ? null : result.filePaths[0];
});

handleIpc("settings:get", () => readSettings());

handleIpc("settings:save", (_event, input) => {
  const next = writeSettings(input || {});
  try {
    mainWindow?.setBackgroundMaterial?.("none");
  } catch {
    // Older Electron/Windows combinations can ignore this.
  }
  return next;
});

handleIpc("settings:choose-background-image", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "选择终端背景图片",
    properties: ["openFile"],
    filters: [
      { name: "Images", extensions: ["png", "jpg", "jpeg", "webp", "bmp", "gif"] }
    ]
  });
  if (result.canceled || !result.filePaths[0]) {
    return null;
  }
  const source = result.filePaths[0];
  const ext = path.extname(source) || ".png";
  const target = userDataPath(`terminal-background${ext}`);
  ensureUserDataDir();
  fs.copyFileSync(source, target);
  return pathToFileURL(target).toString();
});

handleIpc("clipboard:write-text", (_event, text) => {
  clipboard.writeText(String(text || ""));
  return true;
});

handleIpc("clipboard:read-text", () => clipboard.readText());

function normalizedDialogPayload(payload, fallbackType, fallbackTitle, fallbackButtons, fallbackIds = {}) {
  const input = payload && typeof payload === "object" ? payload : {};
  const allowedTypes = new Set(["none", "info", "error", "question", "warning"]);
  const buttons = Array.isArray(input.buttons) && input.buttons.length
    ? input.buttons.slice(0, 4).map((button) => String(button || "").trim() || "确定")
    : fallbackButtons;
  return {
    type: allowedTypes.has(input.type) ? input.type : fallbackType,
    title: String(input.title || fallbackTitle),
    message: String(input.message || ""),
    detail: input.detail ? String(input.detail) : undefined,
    buttons,
    defaultId: Number.isInteger(input.defaultId) ? input.defaultId : (fallbackIds.defaultId ?? Math.max(0, buttons.length - 1)),
    cancelId: Number.isInteger(input.cancelId) ? input.cancelId : (fallbackIds.cancelId ?? 0),
    confirmId: Number.isInteger(input.confirmId) ? input.confirmId : (fallbackIds.confirmId ?? Math.max(0, buttons.length - 1))
  };
}

async function showAppMessageBox(options) {
  const owner = mainWindow && !mainWindow.isDestroyed() ? mainWindow : undefined;
  try {
    if (owner) {
      return await dialog.showMessageBox(owner, options);
    }
    return await dialog.showMessageBox(options);
  } finally {
    if (owner && !owner.isDestroyed()) {
      owner.focus();
      owner.webContents.focus();
    }
  }
}

handleIpc("dialog:confirm", async (_event, payload) => {
  const input = normalizedDialogPayload(payload, "question", "确认", ["确定", "取消"], {
    defaultId: 0,
    cancelId: 1,
    confirmId: 0
  });
  const result = await showAppMessageBox({
    type: input.type,
    title: input.title,
    message: input.message,
    detail: input.detail,
    buttons: input.buttons,
    defaultId: input.defaultId,
    cancelId: input.cancelId,
    noLink: true
  });
  return result.response === input.confirmId;
});

handleIpc("dialog:message", async (_event, payload) => {
  const input = normalizedDialogPayload(payload, "info", "提示", ["确定"]);
  await showAppMessageBox({
    type: input.type,
    title: input.title,
    message: input.message,
    detail: input.detail,
    buttons: input.buttons,
    defaultId: 0,
    cancelId: 0,
    noLink: true
  });
  return true;
});

handleIpc("window:minimize", () => {
  mainWindow?.minimize();
  return true;
});

handleIpc("window:maximize-toggle", () => {
  if (!mainWindow) {
    return false;
  }
  if (mainWindow.isMaximized()) {
    mainWindow.unmaximize();
  } else {
    mainWindow.maximize();
  }
  return true;
});

handleIpc("window:close", () => {
  mainWindow?.close();
  return true;
});

handleIpc("window:set-unsaved", (_event, hasUnsavedChanges) => {
  rendererHasUnsavedChanges = hasUnsavedChanges === true;
  return true;
});

handleIpc("ssh:connect", (_event, payload) => {
  const { tabId, connectionId, cols = 120, rows = 32 } = payload;
  cleanupSession(tabId, false);
  const connection = resolveConnection(connectionId);
  const conn = new Client();
  const session = {
    id: crypto.randomUUID(),
    tabId,
    connectionId,
    host: connection.host,
    conn,
    shell: null,
    sftp: null,
    sftpPromise: null,
    ready: false,
    monitorTimer: null,
    monitorBusy: false,
    lastNet: null,
    publicIp: "",
    publicIpChecked: false
  };
  sessions.set(tabId, session);

  send("ssh:state", { tabId, state: "connecting" });

  return new Promise((resolve) => {
    let settled = false;
    const settle = (result) => {
      if (!settled) {
        settled = true;
        resolve(result);
      }
    };

    conn.on("ready", () => {
      if (sessions.get(tabId) !== session) {
        conn.end();
        return;
      }
      conn.shell({ term: "xterm-256color", cols, rows }, (error, stream) => {
        if (sessions.get(tabId) !== session) {
          stream?.end?.();
          settle({ ok: false, error: "SSH session was replaced." });
          return;
        }
        if (error) {
          send("ssh:state", { tabId, state: "error", error: errorMessage(error) });
          cleanupSession(tabId, false, session);
          settle({ ok: false, error: errorMessage(error) });
          return;
        }

        session.shell = stream;
        session.ready = true;
        stream.on("data", (chunk) => {
          if (sessions.get(tabId) === session) {
            send("terminal:data", { tabId, data: chunk.toString("utf8") });
          }
        });
        stream.stderr.on("data", (chunk) => {
          if (sessions.get(tabId) === session) {
            send("terminal:data", { tabId, data: chunk.toString("utf8") });
          }
        });
        stream.on("close", () => {
          if (sessions.get(tabId) === session) {
            send("ssh:state", { tabId, state: "closed" });
          }
        });
        send("ssh:state", { tabId, state: "ready" });
        startMonitor(tabId);
        settle({ ok: true });
      });
    });

    conn.on("keyboard-interactive", (_name, _instructions, _lang, prompts, finish) => {
      if (connection.authType !== "password" || sessions.get(tabId) !== session) {
        finish(prompts.map(() => ""));
        return;
      }
      const password = decryptSecret(connection.passwordSecret) || "";
      finish(prompts.map((prompt) => /password|passcode/i.test(String(prompt?.prompt || "")) ? password : ""));
    });

    conn.on("error", (error) => {
      if (sessions.get(tabId) !== session) {
        settle({ ok: false, error: errorMessage(error) });
        return;
      }
      send("ssh:state", { tabId, state: "error", error: errorMessage(error) });
      cleanupSession(tabId, false, session);
      settle({ ok: false, error: errorMessage(error) });
    });

    conn.on("close", () => {
      if (cleanupSession(tabId, false, session)) {
        send("ssh:state", { tabId, state: "closed" });
      }
    });

    try {
      conn.connect(buildSshConfig(connection));
    } catch (error) {
      send("ssh:state", { tabId, state: "error", error: errorMessage(error) });
      cleanupSession(tabId, false, session);
      settle({ ok: false, error: errorMessage(error) });
    }
  });
});

handleIpc("ssh:write", (_event, { tabId, data }) => {
  const session = sessions.get(tabId);
  if (!session?.shell || session.shell.destroyed) {
    return false;
  }
  session.shell.write(data);
  return true;
});

handleIpc("ssh:resize", (_event, { tabId, cols, rows }) => {
  const session = sessions.get(tabId);
  if (!session?.shell || session.shell.destroyed) {
    return false;
  }
  session.shell.setWindow(rows, cols, 0, 0);
  return true;
});

handleIpc("ssh:disconnect", (_event, tabId) => {
  cleanupSession(tabId);
  return true;
});

handleIpc("local-terminal:open", (_event, { id, cols = 100, rows = 28 }) => {
  if (!id) {
    return { ok: false, error: "Local terminal id is required." };
  }
  if (localTerminals.has(id)) {
    return { ok: true };
  }

  try {
    const session = createLocalTerminal({ id, cols, rows });
    localTerminals.set(id, session);
    return { ok: true, shell: session.shell };
  } catch (error) {
    return { ok: false, error: errorMessage(error) };
  }
});

handleIpc("local-terminal:write", (_event, { id, data }) => {
  const session = localTerminals.get(id);
  if (!session?.terminal) {
    return false;
  }
  try {
    session.terminal.write(String(data || ""));
    return true;
  } catch {
    return false;
  }
});

handleIpc("local-terminal:resize", (_event, { id, cols, rows }) => {
  const session = localTerminals.get(id);
  if (!session?.terminal) {
    return false;
  }
  try {
    session.terminal.resize(
      clampTerminalSize(cols, session.terminal.cols || 100, 500),
      clampTerminalSize(rows, session.terminal.rows || 28, 200)
    );
    return true;
  } catch {
    return false;
  }
});

handleIpc("local-terminal:close", (_event, id) => {
  const session = localTerminals.get(id);
  if (!session?.terminal) {
    return true;
  }
  try {
    session.terminal.kill();
  } catch {
    // Ignore shutdown races.
  }
  localTerminals.delete(id);
  return true;
});

handleIpc("sftp:list", async (_event, { tabId, remotePath = "." }) => {
  const sftp = await ensureSftp(tabId);
  const absolutePath = await sftpRealPath(sftp, normalizeRemotePath(remotePath));
  const list = await sftpReaddir(sftp, absolutePath);
  if (list.length > MAX_TRANSFER_FILES) {
    throw new Error(`目录项目超过 ${MAX_TRANSFER_FILES} 个，拒绝加载。`);
  }
  return {
    path: absolutePath,
    parent: parentRemotePath(absolutePath),
    items: list.filter((item) => item?.filename && item.filename !== "." && item.filename !== ".." && !/[\0\r\n/\\]/.test(item.filename)).map((item) => ({
      filename: item.filename,
      longname: item.longname,
      size: item.attrs.size || 0,
      mode: item.attrs.mode || 0,
      uid: item.attrs.uid || 0,
      gid: item.attrs.gid || 0,
      mtime: item.attrs.mtime || 0,
      atime: item.attrs.atime || 0,
      isDirectory: item.attrs.isDirectory(),
      isFile: item.attrs.isFile(),
      isSymbolicLink: item.attrs.isSymbolicLink()
    })).sort((a, b) => Number(b.isDirectory) - Number(a.isDirectory) || a.filename.localeCompare(b.filename))
  };
});

handleIpc("sftp:upload", async (_event, { tabId, remotePath }) => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "上传文件",
    properties: ["openFile", "multiSelections"]
  });
  if (result.canceled) {
    return { canceled: true };
  }
  return uploadFilesWithProgress({ tabId, remotePath, localPaths: result.filePaths });
});

handleIpc("sftp:upload-paths", async (_event, { tabId, remotePath, localPaths }) => (
  uploadFilesWithProgress({ tabId, remotePath, localPaths })
));

handleIpc("sftp:cancel-upload", (_event, uploadId) => {
  const task = activeUploads.get(uploadId);
  if (!task) {
    return false;
  }
  task.canceled = true;
  if (typeof task.cancelCurrent === "function") {
    task.cancelCurrent();
  }
  return true;
});

handleIpc("sftp:cancel-download", (_event, downloadId) => {
  const task = activeDownloads.get(downloadId);
  if (!task) {
    return false;
  }
  task.canceled = true;
  if (typeof task.cancelCurrent === "function") {
    task.cancelCurrent();
  }
  return true;
});

handleIpc("sftp:download", async (_event, { tabId, remotePath, filename }) => {
  const sessionId = sessions.get(tabId)?.id;
  const sftp = await ensureSftp(tabId, sessionId);
  const cleanFilename = normalizeRemoteName(filename);
  const result = await dialog.showSaveDialog(mainWindow, {
    title: "下载文件",
    defaultPath: cleanFilename
  });
  if (result.canceled || !result.filePath) {
    return { canceled: true };
  }
  const target = joinRemotePath(remotePath, cleanFilename);
  const stats = await sftpLstat(sftp, target);
  if (stats.isSymbolicLink?.() || !stats.isFile?.()) {
    throw new Error("只能下载普通文件，不能下载符号链接。");
  }
  const download = await downloadFilesWithProgress({
    tabId,
    sessionId,
    remotePath,
    files: [{
      remotePath: target,
      relativePath: cleanFilename,
      filename: cleanFilename,
      size: stats.size || 0
    }],
    targetPathForFile: () => result.filePath
  });
  return { ...download, path: result.filePath };
});

handleIpc("sftp:download-many", async (_event, { tabId, remotePath, filenames }) => {
  const sessionId = sessions.get(tabId)?.id;
  const sftp = await ensureSftp(tabId, sessionId);
  const names = Array.isArray(filenames) ? filenames.slice(0, MAX_TRANSFER_FILES + 1).map((name) => normalizeRemoteName(name)).filter(Boolean) : [];
  if (names.length > MAX_TRANSFER_FILES) {
    throw new Error(`一次最多下载 ${MAX_TRANSFER_FILES} 个文件。`);
  }
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "下载到文件夹",
    properties: ["openDirectory", "createDirectory"]
  });
  if (result.canceled || !result.filePaths[0]) {
    return { canceled: true };
  }
  const files = [];
  const usedPaths = new Set();
  for (const filename of names) {
    const target = joinRemotePath(remotePath, filename);
    const stats = await sftpLstat(sftp, target);
    if (stats.isSymbolicLink?.() || !stats.isFile?.()) {
      throw new Error(`只能下载普通文件：${filename}`);
    }
    const relativePath = uniqueRelativeChildName("", filename, usedPaths);
    files.push({
      remotePath: target,
      relativePath,
      filename,
      size: stats.size || 0
    });
  }
  return downloadFilesWithProgress({
    tabId,
    sessionId,
    remotePath,
    files,
    targetPathForFile: (file) => path.join(result.filePaths[0], file.relativePath)
  });
});

handleIpc("sftp:download-zip", async (_event, { tabId, remotePath, items }) => {
  const sessionId = sessions.get(tabId)?.id;
  await ensureSftp(tabId, sessionId);
  const selected = Array.isArray(items) ? items.filter((item) => item?.filename) : [];
  if (selected.length > MAX_TRANSFER_FILES) {
    throw new Error(`一次最多选择 ${MAX_TRANSFER_FILES} 个项目。`);
  }
  if (!selected.length) {
    return { canceled: false, count: 0 };
  }
  const baseName = selected.length === 1
    ? safeLocalName(selected[0].filename, "download")
    : safeLocalName(path.posix.basename(normalizeRemotePath(remotePath)) || "download", "download");
  const result = await dialog.showSaveDialog(mainWindow, {
    title: "打包下载",
    defaultPath: `${baseName}.zip`,
    filters: [{ name: "Zip", extensions: ["zip"] }]
  });
  if (result.canceled || !result.filePath) {
    return { canceled: true };
  }
  const zipPath = result.filePath.toLowerCase().endsWith(".zip") ? result.filePath : `${result.filePath}.zip`;
  return createZipDownloadFile({ tabId, sessionId, remotePath, items: selected, zipPath });
});

ipcMain.on("sftp:create-drag-download", (event, payload) => {
  try {
    assertTrustedIpc(event);
  } catch (error) {
    event.returnValue = { error: errorMessage(error) };
    return;
  }
  try {
    event.returnValue = registerDragDownload(payload || {});
  } catch (error) {
    event.returnValue = { error: errorMessage(error) };
  }
});

handleIpc("sftp:open", async (_event, { tabId, remotePath, filename, isDirectory }) => {
  if (isDirectory) {
    return { directory: true, path: joinRemotePath(remotePath, normalizeRemoteName(filename)) };
  }
  const sftp = await ensureSftp(tabId);
  const openDir = path.join(app.getPath("temp"), "yySlide-open");
  fs.mkdirSync(openDir, { recursive: true });
  const cleanFilename = normalizeRemoteName(filename);
  const localPath = path.join(openDir, `${crypto.randomUUID()}-${safeLocalName(cleanFilename, "open")}`);
  const target = joinRemotePath(remotePath, cleanFilename);
  const stats = await sftpLstat(sftp, target);
  if (stats.isSymbolicLink?.() || !stats.isFile?.() || Number(stats.size || 0) > MAX_SINGLE_TRANSFER_BYTES) {
    throw new Error("只能打开安全大小的普通文件，不能打开符号链接。");
  }
  await sftpFastGet(sftp, target, localPath);
  const error = await shell.openPath(localPath);
  if (error) {
    throw new Error(error);
  }
  watchEditedFile({ tabId, localPath, remotePath: target });
  return { directory: false, path: localPath };
});

handleIpc("sftp:read-file", async (_event, { tabId, remotePath, filename }) => {
  const sftp = await ensureSftp(tabId);
  const target = joinRemotePath(remotePath, normalizeRemoteName(filename));
  const stats = await sftpLstat(sftp, target);
  if (stats.isDirectory && stats.isDirectory()) {
    throw new Error("目录不能用文本编辑器打开。");
  }
  if (stats.isSymbolicLink?.() || !stats.isFile?.()) {
    throw new Error("内置编辑器不能打开符号链接或特殊文件。");
  }
  const content = await sftpReadTextFile(sftp, target);
  return {
    path: target,
    filename,
    content,
    size: stats.size || Buffer.byteLength(content, "utf8"),
    mtime: stats.mtime || 0
  };
});

handleIpc("sftp:save-file", async (_event, { tabId, remotePath, content }) => {
  const sftp = await ensureSftp(tabId);
  await sftpWriteFile(sftp, normalizeRemotePath(remotePath), String(content || ""));
  return true;
});

handleIpc("sftp:delete", async (_event, { tabId, remotePath, filename, isDirectory }) => {
  const sftp = await ensureSftp(tabId);
  const target = joinRemotePath(remotePath, normalizeRemoteName(filename));
  const stats = await sftpLstat(sftp, target);
  if (stats.isDirectory?.() && !stats.isSymbolicLink?.()) {
    await sftpRmdir(sftp, target);
  } else {
    await sftpUnlink(sftp, target);
  }
  return true;
});

handleIpc("sftp:delete-many", async (_event, { tabId, remotePath, items }) => {
  const sftp = await ensureSftp(tabId);
  const targets = Array.isArray(items) ? items : [];
  if (targets.length > 1000) {
    throw new Error("一次最多删除 1000 个项目。");
  }
  for (const item of targets) {
    if (!item?.filename) {
      continue;
    }
    const target = joinRemotePath(remotePath, normalizeRemoteName(item.filename));
    const stats = await sftpLstat(sftp, target);
    if (stats.isDirectory?.() && !stats.isSymbolicLink?.()) {
      await sftpRmdir(sftp, target);
    } else {
      await sftpUnlink(sftp, target);
    }
  }
  return true;
});

handleIpc("sftp:quick-delete", async (_event, { tabId, remotePath, filenames }) => {
  const session = sessions.get(tabId);
  if (!session || !session.ready) {
    throw new Error("SSH session is not ready.");
  }
  const names = Array.isArray(filenames) ? filenames.map(normalizeRemoteName) : [];
  if (names.length > 1000) {
    throw new Error("快速删除一次最多处理 1000 个项目。");
  }
  if (!names.length) {
    return true;
  }
  const targets = names.map((filename) => quoteShellPath(joinRemotePath(remotePath, filename))).join(" ");
  await execCommand(session, `rm -rf -- ${targets}`);
  return true;
});

handleIpc("sftp:mkdir", async (_event, { tabId, remotePath, name }) => {
  const sftp = await ensureSftp(tabId);
  await sftpMkdir(sftp, joinRemotePath(remotePath, normalizeRemoteName(name)));
  return true;
});

handleIpc("sftp:create-file", async (_event, { tabId, remotePath, name }) => {
  const sftp = await ensureSftp(tabId);
  const target = joinRemotePath(remotePath, normalizeRemoteName(name));
  await sftpCreateEmptyFile(sftp, target);
  return true;
});

handleIpc("sftp:rename", async (_event, { tabId, remotePath, oldName, newName }) => {
  const sftp = await ensureSftp(tabId);
  await sftpRename(sftp, joinRemotePath(remotePath, normalizeRemoteName(oldName)), joinRemotePath(remotePath, normalizeRemoteName(newName)));
  return true;
});

handleIpc("sftp:move-many", async (_event, { tabId, remotePath, targetPath, items }) => {
  const sftp = await ensureSftp(tabId);
  const sourceBase = normalizeRemotePath(remotePath);
  const targetBase = normalizeRemotePath(targetPath);
  const selected = Array.isArray(items) ? items : [];
  if (!selected.length || sourceBase === targetBase) {
    return true;
  }

  for (const item of selected) {
    if (!item?.filename) {
      continue;
    }
    const filename = normalizeRemoteName(item.filename);
    const source = joinRemotePath(sourceBase, filename);
    const target = joinRemotePath(targetBase, filename);
    if (normalizeRemotePath(source) === normalizeRemotePath(target)) {
      continue;
    }
    if (item.isDirectory && isSameOrChildRemotePath(source, targetBase)) {
      throw new Error("不能把文件夹移动到自身或子目录。");
    }
    await sftpRename(sftp, source, target);
  }
  return true;
});

app.on("second-instance", () => {
  focusMainWindow();
});

app.whenReady().then(async () => {
  app.setName("yySlide");
  if (process.platform === "win32") {
    app.setAppUserModelId(appId);
  }
  migrateLegacyProfileStorage();
  cleanupTemporaryFiles();
  await startDragDownloadServer();
  createWindow();
  startCloudEvents();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("before-quit", () => {
  stopCloudEvents();
  try {
    dragDownloadServer?.close();
  } catch {
    // Ignore local server shutdown races.
  }
  dragDownloadServer = null;
  dragDownloads.clear();
  for (const edit of watchedEdits.values()) {
    try {
      clearTimeout(edit.timer);
      edit.watcher.close();
    } catch {
      // Ignore watcher shutdown races.
    }
  }
  watchedEdits.clear();
  for (const session of localTerminals.values()) {
    try {
      session.terminal?.kill();
    } catch {
      // Ignore terminal shutdown races.
    }
  }
  localTerminals.clear();
  cleanupAllRemoteSessions();
  cleanupTemporaryFiles();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
