import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import { SearchAddon } from "@xterm/addon-search";
import { WebLinksAddon } from "@xterm/addon-web-links";
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import {
  Activity,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  Edit3,
  Folder,
  FolderOpen,
  FolderPlus,
  Home,
  KeyRound,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
  Server,
  Settings,
  Trash2,
  Upload,
  X
} from "lucide-react";
import "@xterm/xterm/css/xterm.css";
import "monaco-editor/min/vs/editor/editor.main.css";
import logoUrl from "./assets/logo.png";
import type {
  AccountState,
  AppSettings,
  AppDialogType,
  ConnectionProfile,
  DownloadProgress,
  MonitorSnapshot,
  SessionTab,
  SftpItem,
  SftpListing,
  SshState,
  UploadProgress
} from "./types";

(self as unknown as { MonacoEnvironment: monaco.Environment }).MonacoEnvironment = {
  getWorker: () => new EditorWorker()
};

interface EditorDocument {
  id: string;
  tabId: string;
  remotePath: string;
  filename: string;
  content: string;
  savedContent: string;
  language: string;
  dirty: boolean;
  saving: boolean;
  error?: string;
  savedAt?: number;
}

interface RemoteFileDrag {
  tabId: string;
  remotePath: string;
  items: Array<{ filename: string; isDirectory: boolean }>;
}

interface FilePanelCache {
  paths: Record<string, string>;
  listings: Record<string, SftpListing>;
  listingsByPath: Record<string, Record<string, SftpListing>>;
  treeByTab: Record<string, Record<string, SftpItem[]>>;
  expandedByTab: Record<string, string[]>;
  editorsByTab: Record<string, EditorDocument | null>;
}

type FileColumnKey = "name" | "size" | "type" | "mtime" | "mode" | "owner";

const DEFAULT_CLOUD_SERVER_URL = "https://yyslide.ccwu.cc";

function savedCloudServerUrl() {
  const saved = localStorage.getItem("yySlide.cloudServerUrl") || "";
  if (
    !saved ||
    saved === "http://127.0.0.1:8787" ||
    saved === "https://sync.your-domain.com" ||
    saved === "https://yyslide.yyhvh.com"
  ) {
    return DEFAULT_CLOUD_SERVER_URL;
  }
  return saved;
}

const fileColumnDefaults: Array<{ key: FileColumnKey; label: string; width: number; min: number }> = [
  { key: "name", label: "文件名", width: 240, min: 150 },
  { key: "size", label: "大小", width: 112, min: 74 },
  { key: "type", label: "类型", width: 122, min: 86 },
  { key: "mtime", label: "修改时间", width: 206, min: 150 },
  { key: "mode", label: "权限", width: 118, min: 92 },
  { key: "owner", label: "用户/用户组", width: 150, min: 110 }
];

const fallbackSettings: AppSettings = {
  terminalBackground: "#000000",
  terminalForeground: "#f3f7fb",
  terminalBackgroundImage: "",
  terminalBackgroundImageOpacity: 1,
  accentColor: "#2c7ac9"
};

const emptyConnection: ConnectionProfile = {
  name: "",
  host: "",
  port: 22,
  username: "",
  authType: "password",
  privateKeyPath: "",
  group: "",
  password: "",
  passphrase: ""
};

function makeId() {
  return globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function createNewTab(): SessionTab {
  return {
    id: makeId(),
    kind: "new",
    title: "新标签页",
    state: "idle"
  };
}

function formatBytes(value = 0) {
  if (!value) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size >= 10 || unit === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unit]}`;
}

function formatRate(value = 0) {
  return `${formatBytes(value)}/s`;
}

function formatDuration(seconds = 0) {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return "-";
  }
  const rounded = Math.max(1, Math.round(seconds));
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const secs = rounded % 60;
  if (hours) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes) {
    return `${minutes}m ${secs}s`;
  }
  return `${secs}s`;
}

function connectionGroupValue(connection: ConnectionProfile) {
  return String(connection.group || "").trim();
}

function normalizeConnectionGroupLabels(groups: string[]) {
  const byKey = new Map<string, string>();
  for (const group of groups) {
    const label = String(group || "").trim();
    if (!label) {
      continue;
    }
    const key = label.toLocaleLowerCase();
    if (!byKey.has(key)) {
      byKey.set(key, label);
    }
  }
  return Array.from(byKey.values()).sort((a, b) => a.localeCompare(b));
}

function connectionGroupLabel(group: string) {
  return group || "未分类";
}

function keepOverlayFocus(event: React.PointerEvent<HTMLElement>) {
  event.stopPropagation();
  const target = event.target;
  if (
    target instanceof HTMLInputElement ||
    target instanceof HTMLSelectElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLButtonElement
  ) {
    window.requestAnimationFrame(() => target.focus());
  }
}

const MENU_VIEWPORT_MARGIN = 8;
const FILE_CONTEXT_MENU_WIDTH = 224;
const FILE_SUBMENU_WIDTH = 150;

function clampMenuPosition(x: number, y: number, width: number, height: number) {
  return {
    left: Math.round(Math.max(
      MENU_VIEWPORT_MARGIN,
      Math.min(x, window.innerWidth - width - MENU_VIEWPORT_MARGIN)
    )),
    top: Math.round(Math.max(
      MENU_VIEWPORT_MARGIN,
      Math.min(y, window.innerHeight - height - MENU_VIEWPORT_MARGIN)
    ))
  } as React.CSSProperties;
}

function useClampedMenuPosition(x: number, y: number, fallbackWidth: number, fallbackHeight: number) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [style, setStyle] = useState<React.CSSProperties>(() => (
    clampMenuPosition(x, y, fallbackWidth, fallbackHeight)
  ));

  const updatePosition = useCallback(() => {
    const rect = menuRef.current?.getBoundingClientRect();
    setStyle(clampMenuPosition(
      x,
      y,
      Math.ceil(rect?.width || fallbackWidth),
      Math.ceil(rect?.height || fallbackHeight)
    ));
  }, [fallbackHeight, fallbackWidth, x, y]);

  useLayoutEffect(() => {
    updatePosition();
    window.addEventListener("resize", updatePosition);
    return () => window.removeEventListener("resize", updatePosition);
  }, [updatePosition]);

  return { menuRef, style };
}

function formatUptime(seconds = 0) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days) {
    return `${days} 天`;
  }
  if (hours) {
    return `${hours} 小时`;
  }
  return `${minutes} 分钟`;
}

function formatDate(timestamp = 0) {
  if (!timestamp) {
    return "-";
  }
  return new Date(timestamp * 1000).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function remoteJoin(basePath: string, filename: string) {
  if (!basePath || basePath === ".") {
    return filename;
  }
  if (basePath === "/") {
    return `/${filename}`;
  }
  return `${basePath.replace(/\/$/, "")}/${filename}`;
}

function remoteAncestors(remotePath: string) {
  if (!remotePath || remotePath === "." || !remotePath.startsWith("/")) {
    return ["/"];
  }
  const parts = remotePath.split("/").filter(Boolean);
  const ancestors = ["/"];
  let current = "";
  for (const part of parts) {
    current += `/${part}`;
    ancestors.push(current);
  }
  return ancestors;
}

function normalizeNewRemoteName(value: string | null) {
  const name = (value || "").trim();
  if (!name) {
    return "";
  }
  if (name.includes("/") || name.includes("\\")) {
    return "";
  }
  return name;
}

function remoteNameValidationError(value: string) {
  const name = value.trim();
  if (!name) {
    return "请输入名称。";
  }
  if (name.includes("/") || name.includes("\\")) {
    return "名称不能包含 / 或 \\。";
  }
  return "";
}

function friendlyRemoteError(error: unknown) {
  const raw = error instanceof Error ? error.message : String(error);
  return raw
    .replace(/^Error invoking remote method '[^']+':\s*/i, "")
    .replace(/^Error:\s*/i, "")
    .replace(/^Error:\s*/i, "")
    .trim();
}

async function confirmDialog(
  message: string,
  options: {
    title?: string;
    detail?: string;
    type?: AppDialogType;
    confirmText?: string;
    cancelText?: string;
    dangerous?: boolean;
  } = {}
) {
  return window.yySlide.dialog.confirm({
    title: options.title || "确认",
    message,
    detail: options.detail,
    type: options.type || (options.dangerous ? "warning" : "question"),
    buttons: [options.confirmText || "确定", options.cancelText || "取消"],
    defaultId: options.dangerous ? 1 : 0,
    cancelId: 1,
    confirmId: 0
  });
}

async function messageDialog(
  message: string,
  options: { title?: string; detail?: string; type?: AppDialogType } = {}
) {
  await window.yySlide.dialog.message({
    title: options.title || "提示",
    message,
    detail: options.detail,
    type: options.type || "info",
    buttons: ["确定"],
    defaultId: 0,
    cancelId: 0
  });
}

async function copyText(text: string) {
  await window.yySlide.clipboard.writeText(text);
}

function passwordPolicyIssues(password: string) {
  const issues: string[] = [];
  if (password.length < 8) {
    issues.push("至少 8 位");
  }
  if (!/[0-9]/.test(password)) {
    issues.push("包含数字");
  }
  if (!/[a-z]/.test(password)) {
    issues.push("包含小写字母");
  }
  if (!/[A-Z]/.test(password)) {
    issues.push("包含大写字母");
  }
  return issues;
}

const qrTotalCodewords = [0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346];
const qrEccCodewordsPerBlock = [0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18];
const qrBlockCount = [0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4];
const qrAlignmentPositions = [
  [],
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
  [6, 28, 50]
];

function qrMultiply(x: number, y: number) {
  let z = 0;
  for (let i = 7; i >= 0; i -= 1) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z & 0xff;
}

function qrReedSolomonDivisor(degree: number) {
  const result = new Uint8Array(degree);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i += 1) {
    for (let j = 0; j < degree; j += 1) {
      result[j] = qrMultiply(result[j], root);
      if (j + 1 < degree) {
        result[j] ^= result[j + 1];
      }
    }
    root = qrMultiply(root, 2);
  }
  return result;
}

function qrReedSolomonRemainder(data: number[], divisor: Uint8Array) {
  const result = new Uint8Array(divisor.length);
  for (const byte of data) {
    const factor = byte ^ result[0];
    result.copyWithin(0, 1);
    result[result.length - 1] = 0;
    for (let i = 0; i < result.length; i += 1) {
      result[i] ^= qrMultiply(divisor[i], factor);
    }
  }
  return Array.from(result);
}

function qrDataCodewordCount(version: number) {
  return qrTotalCodewords[version] - qrEccCodewordsPerBlock[version] * qrBlockCount[version];
}

function qrAppendBits(bits: boolean[], value: number, length: number) {
  for (let i = length - 1; i >= 0; i -= 1) {
    bits.push(((value >>> i) & 1) !== 0);
  }
}

function qrMakeDataCodewords(text: string, version: number) {
  const bytes = Array.from(new TextEncoder().encode(text));
  const countBits = version < 10 ? 8 : 16;
  const capacityBits = qrDataCodewordCount(version) * 8;
  const bits: boolean[] = [];
  qrAppendBits(bits, 0x4, 4);
  qrAppendBits(bits, bytes.length, countBits);
  for (const byte of bytes) {
    qrAppendBits(bits, byte, 8);
  }
  qrAppendBits(bits, 0, Math.min(4, capacityBits - bits.length));
  while (bits.length % 8 !== 0) {
    bits.push(false);
  }
  const padBytes = [0xec, 0x11];
  let padIndex = 0;
  while (bits.length < capacityBits) {
    qrAppendBits(bits, padBytes[padIndex % 2], 8);
    padIndex += 1;
  }
  const result: number[] = [];
  for (let i = 0; i < bits.length; i += 8) {
    let value = 0;
    for (let j = 0; j < 8; j += 1) {
      value = (value << 1) | (bits[i + j] ? 1 : 0);
    }
    result.push(value);
  }
  return result;
}

function qrInterleaveCodewords(dataCodewords: number[], version: number) {
  const totalCodewords = qrTotalCodewords[version];
  const blockCount = qrBlockCount[version];
  const eccLength = qrEccCodewordsPerBlock[version];
  const divisor = qrReedSolomonDivisor(eccLength);
  const shortBlockLength = Math.floor(totalCodewords / blockCount);
  const shortBlockCount = blockCount - (totalCodewords % blockCount);
  const shortDataLength = shortBlockLength - eccLength;
  const blocks: Array<{ data: number[]; ecc: number[] }> = [];
  let offset = 0;
  for (let i = 0; i < blockCount; i += 1) {
    const dataLength = shortDataLength + (i >= shortBlockCount ? 1 : 0);
    const data = dataCodewords.slice(offset, offset + dataLength);
    offset += dataLength;
    blocks.push({ data, ecc: qrReedSolomonRemainder(data, divisor) });
  }
  const result: number[] = [];
  const maxDataLength = Math.max(...blocks.map((block) => block.data.length));
  for (let i = 0; i < maxDataLength; i += 1) {
    for (const block of blocks) {
      if (i < block.data.length) {
        result.push(block.data[i]);
      }
    }
  }
  for (let i = 0; i < eccLength; i += 1) {
    for (const block of blocks) {
      result.push(block.ecc[i]);
    }
  }
  return result;
}

function qrBchRemainder(value: number, poly: number, length: number) {
  let result = value;
  for (let i = length - 1; i >= 0; i -= 1) {
    if (((result >>> (i + length)) & 1) !== 0) {
      result ^= poly << i;
    }
  }
  return result & ((1 << length) - 1);
}

function createQrMatrix(text: string) {
  const byteLength = new TextEncoder().encode(text).length;
  let version = 1;
  for (; version <= 10; version += 1) {
    const countBits = version < 10 ? 8 : 16;
    if (4 + countBits + byteLength * 8 <= qrDataCodewordCount(version) * 8) {
      break;
    }
  }
  if (version > 10) {
    throw new Error("2FA 二维码内容过长。");
  }

  const size = 17 + version * 4;
  const modules = Array.from({ length: size }, () => Array(size).fill(false) as boolean[]);
  const functions = Array.from({ length: size }, () => Array(size).fill(false) as boolean[]);
  const setFunction = (x: number, y: number, black: boolean) => {
    if (x < 0 || y < 0 || x >= size || y >= size) {
      return;
    }
    modules[y][x] = black;
    functions[y][x] = true;
  };

  const drawFinder = (cx: number, cy: number) => {
    for (let dy = -4; dy <= 4; dy += 1) {
      for (let dx = -4; dx <= 4; dx += 1) {
        const distance = Math.max(Math.abs(dx), Math.abs(dy));
        setFunction(cx + dx, cy + dy, distance !== 2 && distance !== 4);
      }
    }
  };

  const drawAlignment = (cx: number, cy: number) => {
    for (let dy = -2; dy <= 2; dy += 1) {
      for (let dx = -2; dx <= 2; dx += 1) {
        setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }
  };

  drawFinder(3, 3);
  drawFinder(size - 4, 3);
  drawFinder(3, size - 4);
  for (let i = 0; i < size; i += 1) {
    if (!functions[6][i]) {
      setFunction(i, 6, i % 2 === 0);
    }
    if (!functions[i][6]) {
      setFunction(6, i, i % 2 === 0);
    }
  }
  for (const x of qrAlignmentPositions[version]) {
    for (const y of qrAlignmentPositions[version]) {
      if (!functions[y][x]) {
        drawAlignment(x, y);
      }
    }
  }
  setFunction(8, size - 8, true);

  const formatBits = ((1 << 3) | 0) << 10;
  const finalFormat = (formatBits | qrBchRemainder(formatBits, 0x537, 10)) ^ 0x5412;
  for (let i = 0; i <= 5; i += 1) {
    setFunction(8, i, ((finalFormat >>> i) & 1) !== 0);
  }
  setFunction(8, 7, ((finalFormat >>> 6) & 1) !== 0);
  setFunction(8, 8, ((finalFormat >>> 7) & 1) !== 0);
  setFunction(7, 8, ((finalFormat >>> 8) & 1) !== 0);
  for (let i = 9; i < 15; i += 1) {
    setFunction(14 - i, 8, ((finalFormat >>> i) & 1) !== 0);
  }
  for (let i = 0; i < 8; i += 1) {
    setFunction(size - 1 - i, 8, ((finalFormat >>> i) & 1) !== 0);
  }
  for (let i = 8; i < 15; i += 1) {
    setFunction(8, size - 15 + i, ((finalFormat >>> i) & 1) !== 0);
  }

  if (version >= 7) {
    const versionValue = version << 12;
    const versionBits = versionValue | qrBchRemainder(versionValue, 0x1f25, 12);
    for (let i = 0; i < 18; i += 1) {
      const bit = ((versionBits >>> i) & 1) !== 0;
      setFunction(size - 11 + (i % 3), Math.floor(i / 3), bit);
      setFunction(Math.floor(i / 3), size - 11 + (i % 3), bit);
    }
  }

  const codewords = qrInterleaveCodewords(qrMakeDataCodewords(text, version), version);
  const bits: boolean[] = [];
  for (const byte of codewords) {
    qrAppendBits(bits, byte, 8);
  }
  let bitIndex = 0;
  let upward = true;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) {
      right -= 1;
    }
    for (let vert = 0; vert < size; vert += 1) {
      const y = upward ? size - 1 - vert : vert;
      for (let j = 0; j < 2; j += 1) {
        const x = right - j;
        if (!functions[y][x] && bitIndex < bits.length) {
          modules[y][x] = bits[bitIndex];
          bitIndex += 1;
        }
      }
    }
    upward = !upward;
  }

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      if (!functions[y][x] && (x + y) % 2 === 0) {
        modules[y][x] = !modules[y][x];
      }
    }
  }

  return modules;
}

const textFileExtensions = new Set([
  "bash",
  "bat",
  "c",
  "cc",
  "cfg",
  "conf",
  "cpp",
  "cs",
  "css",
  "csv",
  "env",
  "go",
  "h",
  "hpp",
  "htm",
  "html",
  "ini",
  "java",
  "js",
  "json",
  "jsx",
  "log",
  "lua",
  "md",
  "php",
  "properties",
  "py",
  "rb",
  "rs",
  "sh",
  "sql",
  "toml",
  "ts",
  "tsx",
  "txt",
  "vue",
  "xml",
  "yaml",
  "yml"
]);

const textFileNames = new Set([
  ".bash_history",
  ".bash_logout",
  ".bash_profile",
  ".bashrc",
  ".gitignore",
  ".profile",
  ".vimrc",
  "dockerfile",
  "makefile"
]);

function fileExtension(filename: string) {
  const clean = filename.toLowerCase();
  const dot = clean.lastIndexOf(".");
  return dot > -1 ? clean.slice(dot + 1) : "";
}

function isEditableFile(item: SftpItem) {
  if (item.isDirectory || item.size > 8 * 1024 * 1024) {
    return false;
  }
  const name = item.filename.toLowerCase();
  const extension = fileExtension(name);
  return textFileNames.has(name) || textFileExtensions.has(extension) || (!extension && item.size <= 256 * 1024);
}

function languageForFile(filename: string) {
  const name = filename.toLowerCase();
  const extension = fileExtension(name);
  if (name === "dockerfile") {
    return "dockerfile";
  }
  if (name === "makefile") {
    return "makefile";
  }
  const map: Record<string, string> = {
    bash: "shell",
    bat: "bat",
    c: "c",
    cc: "cpp",
    conf: "ini",
    cpp: "cpp",
    cs: "csharp",
    css: "css",
    go: "go",
    h: "cpp",
    hpp: "cpp",
    htm: "html",
    html: "html",
    ini: "ini",
    java: "java",
    js: "javascript",
    json: "json",
    jsx: "javascript",
    log: "plaintext",
    lua: "lua",
    md: "markdown",
    php: "php",
    properties: "ini",
    py: "python",
    rb: "ruby",
    rs: "rust",
    sh: "shell",
    sql: "sql",
    toml: "ini",
    ts: "typescript",
    tsx: "typescript",
    txt: "plaintext",
    vue: "html",
    xml: "xml",
    yaml: "yaml",
    yml: "yaml"
  };
  return map[extension] || "plaintext";
}

export default function App() {
  const [connections, setConnections] = useState<ConnectionProfile[]>([]);
  const [tabs, setTabs] = useState<SessionTab[]>(() => {
    const tab = createNewTab();
    return [tab];
  });
  const [activeTabId, setActiveTabId] = useState<string | null>(() => tabs[0]?.id || null);
  const [monitors, setMonitors] = useState<Record<string, MonitorSnapshot>>({});
  const [settings, setSettings] = useState<AppSettings>(fallbackSettings);
  const [account, setAccount] = useState<AccountState>({ loggedIn: false, syncEnabled: false });
  const [accountOpen, setAccountOpen] = useState(false);
  const [accountModalVersion, setAccountModalVersion] = useState(0);
  const [connectionEditor, setConnectionEditor] = useState<ConnectionProfile | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [connectionPanelOpen, setConnectionPanelOpen] = useState(false);
  const [manualConnectionGroups, setManualConnectionGroups] = useState<string[]>(() => {
    try {
      const groups = JSON.parse(localStorage.getItem("yySlide.connectionGroups") || "[]");
      return Array.isArray(groups) ? normalizeConnectionGroupLabels(groups) : [];
    } catch {
      return [];
    }
  });
  const [recentConnectionIds, setRecentConnectionIds] = useState<string[]>(() => {
    try {
      return JSON.parse(localStorage.getItem("yySlide.recentConnections") || "[]");
    } catch {
      return [];
    }
  });
  const [filePanelHeight, setFilePanelHeight] = useState(306);
  const [dirtyEditorTabs, setDirtyEditorTabs] = useState<Set<string>>(() => new Set());

  const activeTab = useMemo(
    () => tabs.find((tab) => tab.id === activeTabId),
    [activeTabId, tabs]
  );
  const activeMonitor = activeTabId && activeTab?.kind === "ssh" ? monitors[activeTabId] : undefined;
  const terminalLogsRef = useRef<Record<string, string>>({});
  const backgroundSyncInFlightRef = useRef(false);
  const filePanelCacheRef = useRef<FilePanelCache>({
    paths: {},
    listings: {},
    listingsByPath: {},
    treeByTab: {},
    expandedByTab: {},
    editorsByTab: {}
  });
  const quickConnections = useMemo(
    () => recentConnectionIds
      .map((id) => connections.find((connection) => connection.id === id))
      .filter((connection): connection is ConnectionProfile => Boolean(connection)),
    [connections, recentConnectionIds]
  );
  const connectionGroups = useMemo(() => (
    Array.from(new Set([
      ...manualConnectionGroups,
      ...connections.map(connectionGroupValue).filter(Boolean)
    ]))
      .sort((a, b) => a.localeCompare(b))
  ), [connections, manualConnectionGroups]);

  const handleEditorDirtyChange = useCallback((tabId: string, dirty: boolean) => {
    setDirtyEditorTabs((current) => {
      const alreadyDirty = current.has(tabId);
      if (alreadyDirty === dirty) {
        return current;
      }
      const next = new Set(current);
      if (dirty) {
        next.add(tabId);
      } else {
        next.delete(tabId);
      }
      return next;
    });
  }, []);

  useEffect(() => {
    localStorage.setItem("yySlide.recentConnections", JSON.stringify(recentConnectionIds));
  }, [recentConnectionIds]);

  useEffect(() => {
    void window.yySlide.window.setUnsaved(dirtyEditorTabs.size > 0);
  }, [dirtyEditorTabs]);

  useEffect(() => {
    localStorage.setItem("yySlide.connectionGroups", JSON.stringify(manualConnectionGroups));
  }, [manualConnectionGroups]);

  const refreshConnections = useCallback(async () => {
    const list = await window.yySlide.connections.list();
    setConnections(list.sort((a, b) => (a.name || a.host).localeCompare(b.name || b.host)));
  }, []);

  const refreshConnectionGroups = useCallback(async (migrateLegacy = false) => {
    let groups = normalizeConnectionGroupLabels(await window.yySlide.connections.listGroups());
    if (migrateLegacy && groups.length === 0 && !localStorage.getItem("yySlide.connectionGroupsMigrated")) {
      try {
        const legacy = JSON.parse(localStorage.getItem("yySlide.connectionGroups") || "[]");
        const legacyGroups = Array.isArray(legacy) ? normalizeConnectionGroupLabels(legacy) : [];
        if (legacyGroups.length > 0) {
          groups = normalizeConnectionGroupLabels(await window.yySlide.connections.saveGroups(legacyGroups));
        }
      } catch (error) {
        console.error("Legacy connection group migration failed", error);
      } finally {
        localStorage.setItem("yySlide.connectionGroupsMigrated", "1");
      }
    }
    setManualConnectionGroups(groups);
  }, []);

  const refreshConnectionData = useCallback(async (migrateLegacy = false) => {
    await Promise.all([
      refreshConnections(),
      refreshConnectionGroups(migrateLegacy)
    ]);
  }, [refreshConnections, refreshConnectionGroups]);

  const saveConnectionGroups = useCallback(async (groups: string[]) => {
    const saved = normalizeConnectionGroupLabels(await window.yySlide.connections.saveGroups(groups));
    setManualConnectionGroups(saved);
    return saved;
  }, []);

  useEffect(() => {
    let disposed = false;
    refreshConnectionData(true);
    window.yySlide.settings.get().then(setSettings).catch(() => setSettings(fallbackSettings));
    window.yySlide.account.state().then(async (state) => {
      if (disposed) {
        return;
      }
      setAccount(state);
      if (state.loggedIn && state.syncEnabled) {
        try {
          const synced = await window.yySlide.account.syncNow();
          if (!disposed) {
            setAccount(synced);
            await refreshConnectionData();
          }
        } catch (syncError) {
          console.error("Startup cloud sync failed", syncError);
        }
      }
    }).catch(() => {
      if (!disposed) {
        setAccount({ loggedIn: false, syncEnabled: false });
      }
    });
    return () => {
      disposed = true;
    };
  }, [refreshConnectionData]);

  useEffect(() => {
    if (!account.loggedIn || !account.syncEnabled) {
      return;
    }
    let disposed = false;
    const runBackgroundSync = async () => {
      if (backgroundSyncInFlightRef.current) {
        return;
      }
      backgroundSyncInFlightRef.current = true;
      try {
        const synced = await window.yySlide.account.syncNow();
        if (!disposed) {
          setAccount(synced);
          await refreshConnectionData();
        }
      } catch (syncError) {
        console.error("Background cloud sync failed", syncError);
      } finally {
        backgroundSyncInFlightRef.current = false;
      }
    };
    const offRealtimeSync = window.yySlide.events.onAccountVaultChanged(async ({ account: nextAccount }) => {
      if (disposed) {
        return;
      }
      if (nextAccount) {
        setAccount(nextAccount);
      }
      try {
        await refreshConnectionData();
      } catch (syncError) {
        console.error("Realtime cloud refresh failed", syncError);
      }
    });
    const timer = window.setInterval(runBackgroundSync, 30000);
    return () => {
      disposed = true;
      offRealtimeSync();
      window.clearInterval(timer);
    };
  }, [account.loggedIn, account.syncEnabled, refreshConnectionData]);

  useEffect(() => {
    const offState = window.yySlide.events.onSshState(({ tabId, state, error }) => {
      setTabs((current) => current.map((tab) => (
        tab.id === tabId ? { ...tab, state, error } : tab
      )));
    });
    const offMonitor = window.yySlide.events.onMonitorUpdate(({ tabId, data }) => {
      setMonitors((current) => ({ ...current, [tabId]: data }));
    });
    return () => {
      offState();
      offMonitor();
    };
  }, []);

  const openConnection = useCallback((profile: ConnectionProfile) => {
    if (!profile.id) {
      return;
    }
    setRecentConnectionIds((current) => [profile.id!, ...current.filter((id) => id !== profile.id)].slice(0, 50));
    const tabId = makeId();
    const tab: SessionTab = {
      id: tabId,
      kind: "ssh",
      connectionId: profile.id,
      title: profile.name || profile.host,
      host: profile.host,
      state: "connecting"
    };
    setTabs((current) => [...current, tab]);
    setActiveTabId(tabId);
    window.yySlide.ssh.connect({ tabId, connectionId: profile.id, cols: 120, rows: 32 }).then((result) => {
      if (!result.ok) {
        setTabs((current) => current.map((item) => (
          item.id === tabId ? { ...item, state: "error", error: result.error } : item
        )));
      }
    });
  }, []);

  const createConnectionGroup = useCallback((groupName: string) => {
    const name = groupName.trim();
    if (!name) {
      return;
    }
    const next = normalizeConnectionGroupLabels([...manualConnectionGroups, name]);
    setManualConnectionGroups(next);
    void saveConnectionGroups(next).catch((error) => {
      console.error("Create connection group failed", error);
      void refreshConnectionGroups();
    });
    setConnectionPanelOpen(true);
  }, [manualConnectionGroups, refreshConnectionGroups, saveConnectionGroups]);

  const deleteConnectionGroup = useCallback(async (groupName: string) => {
    const name = groupName.trim();
    if (!name) {
      return false;
    }

    const groupedConnections = connections.filter((connection) => connectionGroupValue(connection) === name);
    const message = groupedConnections.length
      ? `删除分类「${name}」？其中 ${groupedConnections.length} 个连接会移到未分类，不会删除连接。`
      : `删除分类「${name}」？`;
    if (!(await confirmDialog(message, {
      title: "删除分类",
      confirmText: "删除分类",
      dangerous: true
    }))) {
      return false;
    }

    try {
      for (const connection of groupedConnections) {
        await window.yySlide.connections.save({ ...connection, group: "" });
      }
      await saveConnectionGroups(manualConnectionGroups.filter((group) => group !== name));
      try {
        const saved = JSON.parse(localStorage.getItem("yySlide.collapsedConnectionGroups") || "[]");
        const next = Array.isArray(saved) ? saved.filter((group) => String(group) !== name) : [];
        localStorage.setItem("yySlide.collapsedConnectionGroups", JSON.stringify(next));
      } catch {
        localStorage.removeItem("yySlide.collapsedConnectionGroups");
      }
      await refreshConnectionData();
      return true;
    } catch (error) {
      console.error("Delete connection group failed", error);
      await messageDialog("删除分类失败，请稍后重试。", { title: "删除分类失败", type: "error" });
      await refreshConnectionData();
      return false;
    }
  }, [connections, manualConnectionGroups, refreshConnectionData, saveConnectionGroups]);

  const closeTab = useCallback(async (tabId: string) => {
    if (dirtyEditorTabs.has(tabId) && !(await confirmDialog("这个标签页还有未保存的远端文件，确定关闭？", {
      title: "关闭标签页",
      confirmText: "放弃并关闭",
      dangerous: true
    }))) {
      return;
    }
    const tab = tabs.find((item) => item.id === tabId);
    if (tab?.kind === "ssh") {
      await window.yySlide.ssh.disconnect(tabId);
    }
    setTabs((current) => {
      const filtered = current.filter((item) => item.id !== tabId);
      const next = filtered.length ? filtered : [createNewTab()];
      if (activeTabId === tabId) {
        setActiveTabId(next.length ? next[next.length - 1].id : null);
      }
      return next;
    });
    setMonitors((current) => {
      const next = { ...current };
      delete next[tabId];
      return next;
    });
    setDirtyEditorTabs((current) => {
      const next = new Set(current);
      next.delete(tabId);
      return next;
    });
    delete terminalLogsRef.current[tabId];
    const cache = filePanelCacheRef.current;
    delete cache.paths[tabId];
    delete cache.listings[tabId];
    delete cache.listingsByPath[tabId];
    delete cache.treeByTab[tabId];
    delete cache.expandedByTab[tabId];
    delete cache.editorsByTab[tabId];
  }, [activeTabId, dirtyEditorTabs, tabs]);

  const openNewTab = useCallback(() => {
    const tab = createNewTab();
    setTabs((current) => [...current, tab]);
    setActiveTabId(tab.id);
  }, []);

  const moveTab = useCallback((sourceTabId: string, targetTabId: string) => {
    if (sourceTabId === targetTabId) {
      return;
    }
    setTabs((current) => {
      const sourceIndex = current.findIndex((tab) => tab.id === sourceTabId);
      const targetIndex = current.findIndex((tab) => tab.id === targetTabId);
      if (sourceIndex < 0 || targetIndex < 0) {
        return current;
      }
      const next = [...current];
      const [source] = next.splice(sourceIndex, 1);
      next.splice(targetIndex, 0, source);
      return next;
    });
  }, []);

  const saveConnection = useCallback(async (profile: ConnectionProfile) => {
    await window.yySlide.connections.save({ ...profile, port: Number(profile.port) || 22 });
    const group = String(profile.group || "").trim();
    if (group) {
      await saveConnectionGroups([...manualConnectionGroups, group]);
    }
    setConnectionEditor(null);
    await refreshConnectionData();
  }, [manualConnectionGroups, refreshConnectionData, saveConnectionGroups]);

  const deleteConnection = useCallback(async (profile: ConnectionProfile) => {
    if (!profile.id || !(await confirmDialog(`删除连接「${profile.name || profile.host}」？`, {
      title: "删除连接",
      confirmText: "删除连接",
      dangerous: true
    }))) {
      return;
    }
    await window.yySlide.connections.delete(profile.id);
    setRecentConnectionIds((current) => current.filter((id) => id !== profile.id));
    await refreshConnectionData();
  }, [refreshConnectionData]);

  const saveSettings = useCallback(async (next: AppSettings) => {
    const saved = await window.yySlide.settings.save(next);
    setSettings(saved);
    setSettingsOpen(false);
  }, []);

  useEffect(() => {
    if ((connectionEditor || settingsOpen || accountOpen) && connectionPanelOpen) {
      setConnectionPanelOpen(false);
    }
  }, [accountOpen, connectionEditor, connectionPanelOpen, settingsOpen]);

  const handleAuthenticated = useCallback(async (next: AccountState) => {
    let state = next;
    if (next.loggedIn && !next.syncEnabled && await confirmDialog("是否开启云同步？开启后会同步服务器列表和密钥。", {
      title: "开启云同步",
      confirmText: "开启"
    })) {
      state = await window.yySlide.account.setSyncEnabled(true);
    }
    setAccount(state);
    await refreshConnectionData();
  }, [refreshConnectionData]);

  const handleSyncNow = useCallback(async () => {
    const state = await window.yySlide.account.syncNow();
    setAccount(state);
    await refreshConnectionData();
  }, [refreshConnectionData]);

  const openAccountModal = useCallback(() => {
    setAccountModalVersion((version) => version + 1);
    setAccountOpen(true);
  }, []);

  const closeAccountModal = useCallback(() => {
    setAccountOpen(false);
    setAccountModalVersion((version) => version + 1);
  }, []);

  const handleLogout = useCallback(async () => {
    const unsavedWarning = dirtyEditorTabs.size ? "当前还有未保存的远端文件；退出并重载后这些编辑内容会丢失。\n\n" : "";
    if (!(await confirmDialog(`${unsavedWarning}退出登录？该账号的本地连接会保留并与其他账号隔离，云同步会停止。`, {
      title: "退出登录",
      confirmText: "退出登录"
    }))) {
      return null;
    }
    setAccountOpen(false);
    setAccountModalVersion((version) => version + 1);
    const next = await window.yySlide.account.logout();
    setAccount(next);
    setAccountModalVersion((version) => version + 1);
    window.setTimeout(() => {
      window.location.reload();
    }, 30);
    return next;
  }, [dirtyEditorTabs]);

  const styleVars = {
    "--accent": settings.accentColor,
    "--terminal-bg": settings.terminalBackground,
    "--terminal-fg": settings.terminalForeground,
    "--terminal-bg-image": settings.terminalBackgroundImage ? `url("${settings.terminalBackgroundImage}")` : "none",
    "--terminal-bg-image-opacity": String(settings.terminalBackgroundImage ? (settings.terminalBackgroundImageOpacity ?? 1) : 0)
  } as React.CSSProperties;
  const mainPaneStyle = {
    gridTemplateRows: `minmax(220px, 1fr) 6px ${filePanelHeight}px`
  } as React.CSSProperties;

  const startFilePanelResize = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    document.body.classList.add("resizing-vertical");

    const onMove = (moveEvent: MouseEvent) => {
      const maxHeight = Math.max(220, window.innerHeight - 220);
      const nextHeight = Math.round(window.innerHeight - moveEvent.clientY);
      setFilePanelHeight(Math.max(190, Math.min(maxHeight, nextHeight)));
    };
    const onUp = () => {
      document.body.classList.remove("resizing-vertical");
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, []);

  return (
    <div className="app-shell" style={styleVars}>
      <div className="app-titlebar">
        <div className="titlebar-brand">
          <img className="titlebar-logo" src={logoUrl} alt="" />
          <span>yySlide</span>
        </div>
        <div className="titlebar-account">
          <button title={account.loggedIn ? "账号与同步" : "登录 / 注册"} onClick={openAccountModal}>
            <KeyRound size={14} />
            <span>{account.loggedIn ? account.username : "未登录"}</span>
            {account.loggedIn && <i className={account.syncEnabled ? "on" : ""}>{account.syncEnabled ? "同步" : "本地"}</i>}
          </button>
        </div>
        <div className="titlebar-controls">
          <button title="最小化" onClick={() => window.yySlide.window.minimize()}>-</button>
          <button title="最大化" onClick={() => window.yySlide.window.maximizeToggle()}>□</button>
          <button className="close" title="关闭" onClick={() => window.yySlide.window.close()}>×</button>
        </div>
      </div>
      <aside className="sidebar">
        <Sidebar
          activeTab={activeTab}
          monitor={activeMonitor}
          onSettings={() => setSettingsOpen(true)}
        />
      </aside>

      <TabStrip
        tabs={tabs}
        connections={connections}
        connectionGroups={connectionGroups}
        connectionPanelOpen={connectionPanelOpen}
        activeTabId={activeTabId}
        onSelect={setActiveTabId}
        onClose={closeTab}
        onNewTab={openNewTab}
        onMoveTab={moveTab}
        onToggleConnections={() => setConnectionPanelOpen((open) => !open)}
        onCloseConnections={() => setConnectionPanelOpen(false)}
        onOpenConnection={(profile) => {
          setConnectionPanelOpen(false);
          openConnection(profile);
        }}
        onNewConnection={(group) => {
          setConnectionPanelOpen(false);
          setConnectionEditor({ ...emptyConnection, group: group || "" });
        }}
        onNewGroup={createConnectionGroup}
        onDeleteGroup={deleteConnectionGroup}
        onEditConnection={(profile) => {
          setConnectionPanelOpen(false);
          setConnectionEditor(profile);
        }}
        onDeleteConnection={deleteConnection}
      />

      <main className={`main-pane ${activeTab?.kind === "ssh" ? "" : "hidden-panel"}`} style={mainPaneStyle}>
          <section className="terminal-zone">
            <TerminalPane activeTab={activeTab?.kind === "ssh" ? activeTab : undefined} settings={settings} logsRef={terminalLogsRef} />
          </section>
          <div
            className="pane-splitter"
            title="拖动调整终端和文件管理区域"
            onMouseDown={startFilePanelResize}
            onDoubleClick={() => setFilePanelHeight(306)}
          />
          <FilePanel
            activeTab={activeTab?.kind === "ssh" ? activeTab : undefined}
            cacheRef={filePanelCacheRef}
            onDirtyChange={handleEditorDirtyChange}
          />
        </main>
      {activeTab?.kind !== "ssh" && (
        <main className="main-pane new-tab-main">
          <NewTabPage
            connections={quickConnections}
            onOpenConnection={openConnection}
            onRemove={(connectionId) => setRecentConnectionIds((current) => current.filter((id) => id !== connectionId))}
            onClear={async () => {
              if (recentConnectionIds.length && await confirmDialog("清空最近连接记录？", {
                title: "清空最近连接",
                confirmText: "清空",
                dangerous: true
              })) {
                setRecentConnectionIds([]);
              }
            }}
          />
        </main>
      )}

      {connectionEditor && (
        <ConnectionModal
          profile={connectionEditor}
          groups={connectionGroups}
          onCancel={() => setConnectionEditor(null)}
          onSave={saveConnection}
        />
      )}

      {settingsOpen && (
        <SettingsModal
          settings={settings}
          onCancel={() => setSettingsOpen(false)}
          onSave={saveSettings}
        />
      )}

      {accountOpen && (
        <AccountModal
          key={`account-modal:${accountModalVersion}`}
          account={account}
          onClose={closeAccountModal}
          onAuthenticated={handleAuthenticated}
          onSyncNow={handleSyncNow}
          onLogout={handleLogout}
          onAccountChange={setAccount}
        />
      )}
    </div>
  );
}

function Sidebar(props: {
  activeTab?: SessionTab;
  monitor?: MonitorSnapshot;
  onSettings: () => void;
}) {
  const { activeTab, monitor, onSettings } = props;
  const [now, setNow] = useState(() => new Date());
  const networkSummary = useMemo(() => {
    const networks = monitor?.network || [];
    const rxRate = networks.reduce((sum, net) => sum + net.rxRate, 0);
    const txRate = networks.reduce((sum, net) => sum + net.txRate, 0);
    const primary = [...networks].sort((a, b) => (b.rxRate + b.txRate) - (a.rxRate + a.txRate))[0];
    return {
      name: primary?.name || "本机",
      rxRate,
      txRate
    };
  }, [monitor?.network]);
  const [networkHistory, setNetworkHistory] = useState<Array<{ rx: number; tx: number }>>([]);

  useEffect(() => {
    if (!monitor) {
      setNetworkHistory([]);
      return;
    }
    setNetworkHistory((current) => [
      ...current.slice(-35),
      { rx: networkSummary.rxRate, tx: networkSummary.txRate }
    ]);
  }, [monitor?.sampledAt, monitor, networkSummary.rxRate, networkSummary.txRate]);

  const networkPeak = Math.max(
    1,
    ...networkHistory.flatMap((sample) => [sample.rx, sample.tx])
  );
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const timeText = now.toLocaleTimeString("zh-CN", { hour12: false });

  return (
    <div className="sidebar-inner">
      <div className="sidebar-tools">
        <span>当前时间：{timeText}</span>
        <button className="icon-button" title="设置" onClick={onSettings}>
          <Settings size={16} />
        </button>
      </div>

      <section className="sidebar-section status-section">
        <InfoRow label="IP" value={monitor?.ip || activeTab?.host || "-"} copyable />
        <InfoRow label="主机" value={monitor?.host || activeTab?.host || "-"} />
        <button className="wide-button">系统信息</button>
        <InfoRow label="运行" value={monitor ? formatUptime(monitor.uptimeSeconds) : "-"} />
        <MetricBar label="CPU" value={monitor?.cpu?.percent || 0} />
        <MetricBar
          label="内存"
          value={monitor?.memory.percent || 0}
          detail={monitor ? `${formatBytes(monitor.memory.used)}/${formatBytes(monitor.memory.total)}` : undefined}
        />
        <MetricBar
          label="交换"
          value={monitor?.swap.percent || 0}
          detail={monitor ? `${formatBytes(monitor.swap.used)}/${formatBytes(monitor.swap.total)}` : undefined}
        />
      </section>

      <section className="sidebar-section table-section">
        <div className="mini-tabs">
          <span className="active">内存</span>
          <span>CPU</span>
          <span>命令</span>
        </div>
        <div className="process-list">
          {(monitor?.processes || []).slice(0, 6).map((process) => (
            <div className="process-row" key={`${process.pid}-${process.command}`}>
              <span>{formatBytes(process.rss)}</span>
              <span>{process.cpu.toFixed(1)}</span>
              <strong title={process.command}>{process.command}</strong>
            </div>
          ))}
          {!monitor && <div className="empty-note">连接后显示进程排行。</div>}
        </div>
      </section>

      <section className="sidebar-section network-section">
        <div className="section-title plain">
          <span>实时占用</span>
          <Activity className="realtime-icon" size={22} strokeWidth={1.8} />
        </div>
        <div className="network-card">
          <div className="network-head">
            <div>
              <strong>{networkSummary.name}</strong>
              <small>{monitor ? "网络吞吐" : "等待连接"}</small>
            </div>
            <div className="network-rates">
              <span className="rate-up">↑ {formatRate(networkSummary.txRate)}</span>
              <span className="rate-down">↓ {formatRate(networkSummary.rxRate)}</span>
            </div>
          </div>
          <div className="network-chart" aria-label="实时网络占用图表">
            {(networkHistory.length ? networkHistory : Array.from({ length: 36 }, () => ({ rx: 0, tx: 0 }))).map((sample, index) => (
              <span className="network-bar" key={`${index}-${sample.rx}-${sample.tx}`}>
                <i className="tx" style={{ height: `${Math.max(2, (sample.tx / networkPeak) * 100)}%` }} />
                <i className="rx" style={{ height: `${Math.max(2, (sample.rx / networkPeak) * 100)}%` }} />
              </span>
            ))}
          </div>
        </div>
      </section>

      <section className="sidebar-section disk-section">
        <div className="disk-header">
          <span>路径</span>
          <span>可用/大小</span>
        </div>
        <div className="disk-list">
          {(monitor?.disks || []).slice(0, 9).map((disk) => (
            <DiskRow disk={disk} key={disk.mount} />
          ))}
          {!monitor && <div className="empty-note">文件系统信息等待连接。</div>}
        </div>
      </section>
    </div>
  );
}

function InfoRow({ label, value, copyable }: { label: string; value: string; copyable?: boolean }) {
  const canCopy = copyable && value && value !== "-";
  const copyValue = async () => {
    if (!canCopy) {
      return;
    }
    await copyText(value);
  };

  return (
    <div className={`info-row ${copyable ? "copy-row" : ""}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      {copyable && (
        <button className="copy-button" disabled={!canCopy} title="复制 IP" onClick={copyValue}>
          <Copy size={12} />
          复制
        </button>
      )}
    </div>
  );
}

function MetricBar({ label, value, detail }: { label: string; value: number; detail?: string }) {
  const safeValue = Math.max(0, Math.min(100, Math.round(value || 0)));
  return (
    <div className="metric-row">
      <span>{label}</span>
      <div className="metric-track">
        <i style={{ width: `${safeValue}%` }} />
        {detail && <em>{detail}</em>}
      </div>
      <strong>{safeValue}%</strong>
    </div>
  );
}

function DiskRow({ disk }: { disk: MonitorSnapshot["disks"][number] }) {
  const usedPercent = Math.max(0, Math.min(100, Math.round(disk.percent || 0)));
  const danger = usedPercent >= 85;
  const warning = usedPercent >= 72 && usedPercent < 85;

  return (
    <div className="disk-row">
      <span title={disk.mount}>{disk.mount}</span>
      <div className={`disk-usage ${danger ? "danger" : warning ? "warning" : ""}`}>
        <i style={{ width: `${usedPercent}%` }} />
        <strong>{formatBytes(disk.available)}/{formatBytes(disk.total)}</strong>
      </div>
    </div>
  );
}

function TabStrip(props: {
  tabs: SessionTab[];
  connections: ConnectionProfile[];
  connectionGroups: string[];
  connectionPanelOpen: boolean;
  activeTabId: string | null;
  onSelect: (tabId: string) => void;
  onClose: (tabId: string) => void;
  onNewTab: () => void;
  onMoveTab: (sourceTabId: string, targetTabId: string) => void;
  onToggleConnections: () => void;
  onCloseConnections: () => void;
  onOpenConnection: (connection: ConnectionProfile) => void;
  onNewConnection: (group?: string) => void;
  onNewGroup: (group: string) => void;
  onDeleteGroup: (group: string) => boolean | Promise<boolean>;
  onEditConnection: (connection: ConnectionProfile) => void;
  onDeleteConnection: (connection: ConnectionProfile) => void;
}) {
  const {
    tabs,
    connections,
    connectionGroups,
    connectionPanelOpen,
    activeTabId,
    onSelect,
    onClose,
    onNewTab,
    onMoveTab,
    onToggleConnections,
    onCloseConnections,
    onOpenConnection,
    onNewConnection,
    onNewGroup,
    onDeleteGroup,
    onEditConnection,
    onDeleteConnection
  } = props;
  const [draggedTabId, setDraggedTabId] = useState<string | null>(null);
  const [dragOverTabId, setDragOverTabId] = useState<string | null>(null);
  const connectionPanelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!connectionPanelOpen) {
      return;
    }

    const closeOnOutsidePointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null;
      if (!target) {
        return;
      }
      if (target instanceof Element) {
        if (target.closest(".connection-panel") || target.closest(".folder-tab") || target.closest(".modal-backdrop")) {
          return;
        }
      }
      if (connectionPanelRef.current?.contains(target)) {
        return;
      }
      onCloseConnections();
    };

    window.addEventListener("pointerdown", closeOnOutsidePointerDown, true);
    return () => window.removeEventListener("pointerdown", closeOnOutsidePointerDown, true);
  }, [connectionPanelOpen, onCloseConnections]);

  return (
    <nav className="tab-strip">
      <button className="folder-tab" title="连接管理" onClick={onToggleConnections}>
        <FolderOpen size={21} />
      </button>
      {connectionPanelOpen && (
        <ConnectionPanel
          panelRef={connectionPanelRef}
          connections={connections}
          groups={connectionGroups}
          onOpen={onOpenConnection}
          onNew={onNewConnection}
          onNewGroup={onNewGroup}
          onDeleteGroup={onDeleteGroup}
          onEdit={onEditConnection}
          onDelete={onDeleteConnection}
        />
      )}
      <div className="tabs-scroll">
        {tabs.map((tab) => (
          <button
            className={`session-tab ${tab.id === activeTabId ? "active" : ""} ${dragOverTabId === tab.id ? "drag-over" : ""}`}
            key={tab.id}
            draggable
            onClick={() => onSelect(tab.id)}
            onDragStart={(event) => {
              setDraggedTabId(tab.id);
              event.dataTransfer.effectAllowed = "move";
              event.dataTransfer.setData("text/plain", tab.id);
            }}
            onDragOver={(event) => {
              event.preventDefault();
              event.dataTransfer.dropEffect = "move";
              if (draggedTabId !== tab.id) {
                setDragOverTabId(tab.id);
              }
            }}
            onDragLeave={() => {
              if (dragOverTabId === tab.id) {
                setDragOverTabId(null);
              }
            }}
            onDrop={(event) => {
              event.preventDefault();
              const sourceId = draggedTabId || event.dataTransfer.getData("text/plain");
              setDraggedTabId(null);
              setDragOverTabId(null);
              if (sourceId && sourceId !== tab.id) {
                onMoveTab(sourceId, tab.id);
              }
            }}
            onDragEnd={() => {
              setDraggedTabId(null);
              setDragOverTabId(null);
            }}
            title={tab.error || tab.host || tab.title}
          >
            <span className={`state-dot ${tab.state}`} style={{ background: tab.state === "ready" ? "#19a858" : tab.kind === "new" ? "#4f8ecf" : undefined }} />
            <span>{tab.title}</span>
            <i
              role="button"
              tabIndex={0}
              title="关闭"
              onClick={(event) => {
                event.stopPropagation();
                onClose(tab.id);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.stopPropagation();
                  onClose(tab.id);
                }
              }}
            >
              <X size={14} />
            </i>
          </button>
        ))}
        <button className="tab-add-button" title="新标签页 / 最近连接" onClick={onNewTab}>
          +
        </button>
      </div>
    </nav>
  );
}

function LegacyConnectionPanel(props: {
  connections: ConnectionProfile[];
  onOpen: (connection: ConnectionProfile) => void;
  onNew: () => void;
  onEdit: (connection: ConnectionProfile) => void;
  onDelete: (connection: ConnectionProfile) => void;
}) {
  return (
    <div className="connection-panel">
      <div className="connection-panel-header">
        <strong>连接列表</strong>
        <button onClick={props.onNew}>新建</button>
      </div>
      <div className="connection-panel-list">
        {props.connections.length === 0 && <div className="empty-note">还没有连接。</div>}
        {props.connections.map((connection) => (
          <div className="connection-row" key={connection.id} onDoubleClick={() => props.onOpen(connection)}>
            <button className="connection-main" onClick={() => props.onOpen(connection)} title="连接">
              <span>
                <strong>{connection.name || connection.host}</strong>
                <small>{connection.username || "-"}@{connection.host}:{connection.port}</small>
              </span>
            </button>
            <button className="icon-button tiny" title="编辑连接" onClick={() => props.onEdit(connection)}>
              <Edit3 size={14} />
            </button>
            <button className="icon-button tiny danger" title="删除连接" onClick={() => props.onDelete(connection)}>
              <Trash2 size={14} />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

function LegacyNewTabPage(props: {
  connections: ConnectionProfile[];
  onOpenConnection: (connection: ConnectionProfile) => void;
  onClear: () => void;
}) {
  return (
    <section className="new-tab-page">
      <div className="quick-connect">
        <div className="quick-header">
          <span>最近连接</span>
          <button onClick={props.onClear} disabled={props.connections.length === 0}>清空</button>
        </div>
        <div className="quick-list">
          {props.connections.length === 0 && (
            <div className="quick-empty">点击左上角文件夹图标添加连接。</div>
          )}
          {props.connections.map((connection) => (
            <button className="quick-row" key={connection.id} onClick={() => props.onOpenConnection(connection)}>
              <span className="quick-icon" />
              <strong>{connection.name || connection.host}</strong>
              <span>/</span>
              <em>{connection.username || "-"}</em>
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

function ConnectionPanel(props: {
  panelRef: React.Ref<HTMLDivElement>;
  connections: ConnectionProfile[];
  groups: string[];
  onOpen: (connection: ConnectionProfile) => void;
  onNew: (group?: string) => void;
  onNewGroup: (group: string) => void;
  onDeleteGroup: (group: string) => boolean | Promise<boolean>;
  onEdit: (connection: ConnectionProfile) => void;
  onDelete: (connection: ConnectionProfile) => void;
}) {
  const [query, setQuery] = useState("");
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem("yySlide.collapsedConnectionGroups") || "[]");
      return new Set(Array.isArray(saved) ? saved.map(String) : []);
    } catch {
      return new Set();
    }
  });
  const [groupDraftOpen, setGroupDraftOpen] = useState(false);
  const [groupDraft, setGroupDraft] = useState("");
  const [groupDraftError, setGroupDraftError] = useState("");
  const [deletingGroup, setDeletingGroup] = useState<string | null>(null);
  const groupInputRef = useRef<HTMLInputElement | null>(null);
  const normalizedQuery = query.trim().toLowerCase();
  const groups = useMemo(() => {
    const map = new Map<string, ConnectionProfile[]>();
    for (const group of props.groups) {
      if (!normalizedQuery || connectionGroupLabel(group).toLowerCase().includes(normalizedQuery)) {
        map.set(group, []);
      }
    }
    for (const connection of props.connections) {
      const group = connectionGroupValue(connection);
      const searchable = [
        connection.name,
        connection.host,
        connection.username,
        String(connection.port),
        group
      ].join(" ").toLowerCase();
      if (normalizedQuery && !searchable.includes(normalizedQuery)) {
        continue;
      }
      map.set(group, [...(map.get(group) || []), connection]);
    }
    return Array.from(map.entries())
      .sort(([a], [b]) => connectionGroupLabel(a).localeCompare(connectionGroupLabel(b)))
      .map(([group, connections]) => ({
        group,
        label: connectionGroupLabel(group),
        connections: connections.sort((a, b) => (a.name || a.host).localeCompare(b.name || b.host))
      }));
  }, [normalizedQuery, props.connections, props.groups]);

  const toggleGroup = (group: string) => {
    setCollapsedGroups((current) => {
      const next = new Set(current);
      if (next.has(group)) {
        next.delete(group);
      } else {
        next.add(group);
      }
      return next;
    });
  };

  const deleteGroup = async (group: string) => {
    if (deletingGroup) {
      return;
    }
    setDeletingGroup(group);
    try {
      const deleted = await props.onDeleteGroup(group);
      if (!deleted) {
        setDeletingGroup(null);
        return;
      }
      setCollapsedGroups((current) => {
        const next = new Set(current);
        next.delete(group);
        return next;
      });
    } catch (error) {
      console.error("Delete connection group failed", error);
    } finally {
      setDeletingGroup(null);
    }
  };

  useEffect(() => {
    localStorage.setItem("yySlide.collapsedConnectionGroups", JSON.stringify(Array.from(collapsedGroups)));
  }, [collapsedGroups]);

  useEffect(() => {
    if (groupDraftOpen) {
      groupInputRef.current?.focus();
    }
  }, [groupDraftOpen]);

  const submitGroup = () => {
    const name = groupDraft.trim();
    if (!name) {
      setGroupDraftError("请输入分类名称");
      return;
    }
    if (props.groups.some((group) => group.localeCompare(name, undefined, { sensitivity: "accent" }) === 0)) {
      setGroupDraftError("分类已存在");
      return;
    }
    props.onNewGroup(name);
    setGroupDraft("");
    setGroupDraftError("");
    setGroupDraftOpen(false);
    setQuery("");
    setCollapsedGroups((current) => {
      const next = new Set(current);
      next.delete(name);
      return next;
    });
  };

  return (
    <div className="connection-panel" ref={props.panelRef} onPointerDownCapture={(event) => event.stopPropagation()}>
      <div className="connection-panel-header">
        <strong>连接管理器</strong>
        <div className="connection-header-actions">
          <button
            type="button"
            className="connection-new-button"
            onClick={(event) => {
              event.preventDefault();
              event.stopPropagation();
              props.onNew();
            }}
            title="新建连接"
          >
            <Plus size={14} />
            <span>新建</span>
          </button>
          <button
            type="button"
            className="connection-new-button"
            onPointerDown={(event) => event.stopPropagation()}
            onClick={(event) => {
              event.preventDefault();
              event.stopPropagation();
              setGroupDraftOpen(true);
              setGroupDraftError("");
              window.requestAnimationFrame(() => groupInputRef.current?.focus());
            }}
            title="新建分类"
          >
            <FolderPlus size={14} />
            <span>新建分类</span>
          </button>
        </div>
      </div>
      {groupDraftOpen && (
        <div className="connection-group-create" onPointerDownCapture={(event) => event.stopPropagation()}>
          <input
            ref={groupInputRef}
            autoFocus
            value={groupDraft}
            onPointerDown={(event) => event.stopPropagation()}
            onClick={() => groupInputRef.current?.focus()}
            onChange={(event) => {
              setGroupDraft(event.target.value);
              setGroupDraftError("");
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                submitGroup();
              }
              if (event.key === "Escape") {
                setGroupDraftOpen(false);
                setGroupDraft("");
                setGroupDraftError("");
              }
            }}
            placeholder="分类名称"
          />
          {groupDraftError && <span>{groupDraftError}</span>}
          <button
            type="button"
            onClick={(event) => {
              event.preventDefault();
              event.stopPropagation();
              submitGroup();
            }}
          >
            确定
          </button>
          <button
            type="button"
            onClick={(event) => {
              event.preventDefault();
              event.stopPropagation();
              setGroupDraftOpen(false);
              setGroupDraft("");
              setGroupDraftError("");
            }}
          >
            取消
          </button>
        </div>
      )}
      <div className="connection-panel-tools">
        <div className="connection-search">
          <Search size={14} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索连接" />
        </div>
      </div>
      <div className="connection-table-head">
        <span />
        <span>名称</span>
        <span>主机</span>
        <span>端口</span>
        <span>用户</span>
        <span />
      </div>
      <div className="connection-panel-list">
        {props.connections.length === 0 && props.groups.length === 0 && <div className="empty-note">还没有连接。</div>}
        {props.connections.length > 0 && groups.length === 0 && <div className="empty-note">没有匹配的连接。</div>}
        {groups.map(({ group, label, connections }) => {
          const expanded = Boolean(normalizedQuery) || !collapsedGroups.has(group);
          return (
            <div className="connection-group" key={group || "__default"}>
              <div className="connection-group-row">
                <button type="button" className="connection-group-toggle" onClick={() => toggleGroup(group)} title={expanded ? "收起" : "展开"}>
                  {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  {expanded ? <FolderOpen size={15} /> : <Folder size={15} />}
                  <strong>{label}</strong>
                  <span>{connections.length}</span>
                </button>
                {group ? (
                  <button
                    type="button"
                    className="icon-button tiny danger"
                    title="删除分类"
                    aria-label={`删除分类 ${label}`}
                    disabled={Boolean(deletingGroup)}
                    onPointerDown={(event) => event.stopPropagation()}
                    onClick={(event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      void deleteGroup(group);
                    }}
                  >
                    <Trash2 size={13} />
                  </button>
                ) : (
                  <span className="connection-group-action-spacer" aria-hidden="true" />
                )}
                <button
                  type="button"
                  className="icon-button tiny"
                  title="在此分类中新建连接"
                  disabled={Boolean(deletingGroup)}
                  onPointerDown={(event) => event.stopPropagation()}
                  onClick={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    props.onNew(group);
                  }}
                >
                  <Plus size={13} />
                </button>
              </div>
              {expanded && connections.map((connection) => (
                <div className="connection-row" key={connection.id} onDoubleClick={() => props.onOpen(connection)}>
                  <button className="connection-main" onClick={() => props.onOpen(connection)} title="连接">
                    <Server className="connection-server-icon" size={14} />
                    <strong title={connection.name || connection.host}>{connection.name || connection.host}</strong>
                    <span title={connection.host}>{connection.host}</span>
                    <span>{connection.port}</span>
                    <span title={connection.username}>{connection.username || "-"}</span>
                  </button>
                  <button className="icon-button tiny" title="编辑连接" onClick={() => props.onEdit(connection)}>
                    <Edit3 size={14} />
                  </button>
                  <button className="icon-button tiny danger" title="删除连接" onClick={() => props.onDelete(connection)}>
                    <Trash2 size={14} />
                  </button>
                </div>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function NewTabPage(props: {
  connections: ConnectionProfile[];
  onOpenConnection: (connection: ConnectionProfile) => void;
  onRemove: (connectionId: string) => void;
  onClear: () => void;
}) {
  return (
    <section className="new-tab-page">
      <div className="quick-connect">
        <div className="quick-header">
          <span>最近连接</span>
          <button onClick={props.onClear} disabled={props.connections.length === 0}>清空</button>
        </div>
        <div className="quick-list">
          {props.connections.length === 0 && (
            <div className="quick-empty">点击左上角文件夹图标添加连接。</div>
          )}
          {props.connections.map((connection) => (
            <div className="quick-row" key={connection.id}>
              <button
                className="quick-remove"
                title="移除这条最近连接"
                onClick={() => connection.id && props.onRemove(connection.id)}
              >
                <X size={13} />
              </button>
              <button className="quick-open" onClick={() => props.onOpenConnection(connection)}>
                <span className="quick-icon" />
                <strong>{connection.name || connection.host}</strong>
                <span>{connectionGroupLabel(connectionGroupValue(connection))}</span>
                <em>{connection.username || "-"}</em>
              </button>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function TerminalPane({ activeTab, settings, logsRef }: {
  activeTab?: SessionTab;
  settings: AppSettings;
  logsRef: React.MutableRefObject<Record<string, string>>;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const searchAddonRef = useRef<SearchAddon | null>(null);
  const activeTabIdRef = useRef<string | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; canCopy: boolean } | null>(null);
  const [terminalSearchOpen, setTerminalSearchOpen] = useState(false);
  const [terminalSearch, setTerminalSearch] = useState("");
  const terminalSearchInputRef = useRef<HTMLInputElement | null>(null);

  const fitAndSendSize = useCallback(() => {
    const terminal = terminalRef.current;
    const fitAddon = fitAddonRef.current;
    const tabId = activeTabIdRef.current;
    if (!terminal || !fitAddon || !containerRef.current) {
      return;
    }
    try {
      fitAddon.fit();
      if (tabId) {
        window.yySlide.ssh.resize({ tabId, cols: terminal.cols, rows: terminal.rows });
      }
    } catch {
      // Fit can throw while the element is hidden during layout changes.
    }
  }, []);

  const appendToTerminal = useCallback((tabId: string, data: string) => {
    const previous = logsRef.current[tabId] || "";
    const next = `${previous}${data}`;
    logsRef.current[tabId] = next.length > 1_200_000 ? next.slice(-900_000) : next;
    if (activeTabIdRef.current === tabId) {
      terminalRef.current?.write(data);
    }
  }, []);

  useEffect(() => {
    if (!containerRef.current) {
      return;
    }

    const terminal = new Terminal({
      cursorBlink: true,
      convertEol: false,
      fontFamily: "Consolas, Cascadia Mono, Menlo, monospace",
      fontSize: 13,
      lineHeight: 1.2,
      scrollback: 12000,
      theme: {
        background: "#00000000",
        foreground: settings.terminalForeground,
        cursor: settings.accentColor,
        selectionBackground: "#4f8ecf66"
      }
    });
    const fitAddon = new FitAddon();
    const searchAddon = new SearchAddon();
    terminal.loadAddon(fitAddon);
    terminal.loadAddon(searchAddon);
    terminal.loadAddon(new WebLinksAddon());
    terminal.open(containerRef.current);
    terminalRef.current = terminal;
    fitAddonRef.current = fitAddon;
    searchAddonRef.current = searchAddon;

    terminal.onData((data) => {
      const tabId = activeTabIdRef.current;
      if (tabId) {
        window.yySlide.ssh.write({ tabId, data });
      }
    });

    const resizeObserver = new ResizeObserver(() => fitAndSendSize());
    resizeObserver.observe(containerRef.current);

    const offData = window.yySlide.events.onTerminalData(({ tabId, data }) => {
      appendToTerminal(tabId, data);
    });
    const offState = window.yySlide.events.onSshState(({ tabId, state, error }) => {
      if (state === "connecting") {
        appendToTerminal(tabId, "\r\n\x1b[38;5;75m连接主机...\x1b[0m\r\n");
      }
      if (state === "ready") {
        appendToTerminal(tabId, "\x1b[38;5;46m连接主机成功\x1b[0m\r\n");
      }
      if (state === "error") {
        appendToTerminal(tabId, `\r\n\x1b[38;5;196m连接失败：${error || "未知错误"}\x1b[0m\r\n`);
      }
      if (state === "closed") {
        appendToTerminal(tabId, "\r\n\x1b[38;5;244m连接已关闭\x1b[0m\r\n");
      }
    });

    setTimeout(fitAndSendSize, 50);

    return () => {
      offData();
      offState();
      resizeObserver.disconnect();
      terminal.dispose();
      terminalRef.current = null;
      fitAddonRef.current = null;
      searchAddonRef.current = null;
    };
  }, [appendToTerminal, fitAndSendSize]);

  useEffect(() => {
    if (!terminalRef.current) {
      return;
    }
    terminalRef.current.options.theme = {
      background: "#00000000",
      foreground: settings.terminalForeground,
      cursor: settings.accentColor,
      selectionBackground: "#4f8ecf66"
    };
  }, [settings.accentColor, settings.terminalForeground]);

  useEffect(() => {
    activeTabIdRef.current = activeTab?.id || null;
    const terminal = terminalRef.current;
    if (!terminal) {
      return;
    }
    terminal.reset();
    if (activeTab?.id) {
      terminal.write(logsRef.current[activeTab.id] || "");
      terminal.focus();
    }
    setTimeout(fitAndSendSize, 30);
  }, [activeTab?.id, fitAndSendSize]);

  useEffect(() => {
    if (terminalSearchOpen) {
      window.requestAnimationFrame(() => {
        terminalSearchInputRef.current?.focus();
        terminalSearchInputRef.current?.select();
      });
    }
  }, [terminalSearchOpen]);

  const copySelection = async () => {
    const selection = terminalRef.current?.getSelection() || "";
    if (selection) {
      await copyText(selection);
    }
  };

  const pasteClipboard = async () => {
    const text = await window.yySlide.clipboard.readText();
    const tabId = activeTabIdRef.current;
    if (text && tabId) {
      window.yySlide.ssh.write({ tabId, data: text });
    }
  };

  const findInTerminal = () => {
    setTerminalSearchOpen(true);
  };

  const runTerminalSearch = () => {
    const keyword = terminalSearch.trim();
    if (keyword) {
      searchAddonRef.current?.findNext(keyword, { caseSensitive: false });
    }
  };

  const clearTerminalCache = () => {
    const tabId = activeTabIdRef.current;
    if (tabId) {
      logsRef.current[tabId] = "";
    }
    terminalRef.current?.clear();
  };

  return (
    <div
      className="terminal-wrap"
      onContextMenu={(event) => {
        event.preventDefault();
        setContextMenu({
          x: event.clientX,
          y: event.clientY,
          canCopy: Boolean(terminalRef.current?.getSelection())
        });
      }}
    >
      <div className="terminal-canvas" ref={containerRef} />
      {contextMenu && (
        <TerminalContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          canCopy={contextMenu.canCopy}
          onCopy={copySelection}
          onPaste={pasteClipboard}
          onFind={findInTerminal}
          onClear={clearTerminalCache}
          onClose={() => setContextMenu(null)}
        />
      )}
      {terminalSearchOpen && (
        <form
          className="terminal-search-box"
          onSubmit={(event) => {
            event.preventDefault();
            runTerminalSearch();
          }}
          onPointerDownCapture={(event) => event.stopPropagation()}
        >
          <Search size={14} />
          <input
            ref={terminalSearchInputRef}
            value={terminalSearch}
            onChange={(event) => setTerminalSearch(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                setTerminalSearchOpen(false);
                terminalRef.current?.focus();
              }
            }}
            placeholder="查找"
          />
          <button type="submit">查找</button>
          <button
            type="button"
            className="icon-button"
            title="关闭查找"
            onClick={() => {
              setTerminalSearchOpen(false);
              terminalRef.current?.focus();
            }}
          >
            <X size={14} />
          </button>
        </form>
      )}
    </div>
  );
}

function TerminalContextMenu(props: {
  x: number;
  y: number;
  canCopy: boolean;
  onCopy: () => void;
  onPaste: () => void;
  onFind: () => void;
  onClear: () => void;
  onClose: () => void;
}) {
  const { menuRef, style } = useClampedMenuPosition(props.x, props.y, 246, 250);

  useEffect(() => {
    const close = () => props.onClose();
    window.addEventListener("pointerdown", close);
    return () => window.removeEventListener("pointerdown", close);
  }, [props]);

  const item = (label: string, shortcut: string, onClick: () => void, disabled = false) => (
    <button
      type="button"
      disabled={disabled}
      onClick={(event) => {
        event.stopPropagation();
        props.onClose();
        onClick();
      }}
    >
      <span>{label}</span>
      {shortcut && <em>{shortcut}</em>}
    </button>
  );

  return (
    <div
      ref={menuRef}
      className="terminal-context-menu"
      style={style}
      onPointerDownCapture={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      {item("复制", "Ctrl+Shift+C", props.onCopy, !props.canCopy)}
      {item("粘贴", "Ctrl+Shift+V", props.onPaste)}
      {item("粘贴选中", "", () => undefined, true)}
      <div className="menu-separator" />
      {item("查找", "Ctrl+Shift+F", props.onFind)}
      <div className="menu-separator" />
      {item("清空缓存", "", props.onClear)}
    </div>
  );
}

function FilePanel({ activeTab, cacheRef, onDirtyChange }: {
  activeTab?: SessionTab;
  cacheRef: React.MutableRefObject<FilePanelCache>;
  onDirtyChange: (tabId: string, dirty: boolean) => void;
}) {
  const [listing, setListing] = useState<SftpListing | null>(null);
  const [treeCache, setTreeCache] = useState<Record<string, SftpItem[]>>({});
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(() => new Set(["/"]));
  const [selectedNames, setSelectedNames] = useState<Set<string>>(() => new Set());
  const [lastSelectedIndex, setLastSelectedIndex] = useState<number | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [fileView, setFileView] = useState<"files" | "terminal" | "uploads" | "downloads">("files");
  const [localTerminalStarted, setLocalTerminalStarted] = useState(false);
  const [uploadProgresses, setUploadProgresses] = useState<Record<string, UploadProgress & { updatedAt: number }>>({});
  const [downloadProgresses, setDownloadProgresses] = useState<Record<string, DownloadProgress & { updatedAt: number }>>({});
  const [editorDoc, setEditorDoc] = useState<EditorDocument | null>(null);
  const [createDialog, setCreateDialog] = useState<{ kind: "file" | "directory"; name: string; saving: boolean; error: string } | null>(null);
  const [renameDialog, setRenameDialog] = useState<{ item: SftpItem; name: string; saving: boolean; error: string } | null>(null);
  const [remoteDropTarget, setRemoteDropTarget] = useState<string | null>(null);
  const [fileTreeWidth, setFileTreeWidth] = useState(270);
  const [fileColumns, setFileColumns] = useState<Record<FileColumnKey, number>>(() => (
    Object.fromEntries(fileColumnDefaults.map((column) => [column.key, column.width])) as Record<FileColumnKey, number>
  ));
  const cache = cacheRef.current;
  const treeCacheRef = useRef<Record<string, SftpItem[]>>({});
  const remoteDragRef = useRef<RemoteFileDrag | null>(null);
  const remoteDropHandledRef = useRef(false);
  const activeTabIdRef = useRef(activeTab?.id || null);
  const listingRequestRef = useRef(0);
  const editorRequestRef = useRef(0);
  activeTabIdRef.current = activeTab?.id || null;

  useEffect(() => {
    if (!editorDoc?.tabId) {
      return;
    }
    cache.editorsByTab[editorDoc.tabId] = editorDoc;
    onDirtyChange(editorDoc.tabId, editorDoc.dirty);
  }, [cache, editorDoc, onDirtyChange]);

  const rememberTreeListing = useCallback((next: SftpListing, tabId = activeTabIdRef.current) => {
    if (!tabId) {
      return;
    }
    const previousTree = cache.treeByTab[tabId] || {};
    const directories = next.items.filter((item) => item.isDirectory);
    const nextTreeCache = {
      ...previousTree,
      [next.path]: directories
    };
    cache.treeByTab[tabId] = nextTreeCache;
    if (activeTabIdRef.current === tabId) {
      treeCacheRef.current = nextTreeCache;
      setTreeCache(nextTreeCache);
    }
  }, [cache]);

  const loadTreePath = useCallback(async (remotePath: string) => {
    if (!activeTab || activeTab.state !== "ready") {
      return null;
    }
    const tabId = activeTab.id;
    if (remotePath.startsWith("/") && treeCacheRef.current[remotePath]) {
      return null;
    }
    try {
      const next = await window.yySlide.sftp.list({
        tabId,
        remotePath
      });
      rememberTreeListing(next, tabId);
      return next;
    } catch {
      return null;
    }
  }, [activeTab, rememberTreeListing]);

  const updateExpandedPaths = useCallback((updater: (current: Set<string>) => Set<string>) => {
    setExpandedPaths((current) => {
      const next = updater(current);
      if (activeTab?.id) {
        cache.expandedByTab[activeTab.id] = Array.from(next);
      }
      return next;
    });
  }, [activeTab?.id, cache]);

  const hydrateTreePath = useCallback((remotePath: string) => {
    for (const pathToLoad of remoteAncestors(remotePath)) {
      void loadTreePath(pathToLoad);
    }
    updateExpandedPaths((current) => {
      const next = new Set(current);
      for (const pathToExpand of remoteAncestors(remotePath)) {
        next.add(pathToExpand);
      }
      return next;
    });
  }, [loadTreePath, updateExpandedPaths]);

  const loadPath = useCallback(async (remotePath?: string, options: { force?: boolean } = {}) => {
    if (!activeTab || activeTab.state !== "ready") {
      setListing(null);
      setSelectedNames(new Set());
      setLastSelectedIndex(null);
      return;
    }
    const tabId = activeTab.id;
    const requestId = ++listingRequestRef.current;
    const requestedPath = remotePath || cache.paths[tabId] || ".";
    const cachedListing = remotePath
      ? cache.listingsByPath[tabId]?.[requestedPath]
      : cache.listings[tabId];
    if (!options.force && cachedListing) {
      cache.paths[tabId] = cachedListing.path;
      setListing(cachedListing);
      rememberTreeListing(cachedListing, tabId);
      hydrateTreePath(cachedListing.path);
      setSelectedNames(new Set());
      setLastSelectedIndex(null);
      return;
    }
    setLoading(true);
    setError("");
    try {
      const next = await window.yySlide.sftp.list({
        tabId,
        remotePath: requestedPath
      });
      if (activeTabIdRef.current !== tabId || requestId !== listingRequestRef.current) {
        return;
      }
      cache.paths[tabId] = next.path;
      cache.listings[tabId] = next;
      cache.listingsByPath[tabId] = {
        ...(cache.listingsByPath[tabId] || {}),
        [requestedPath]: next,
        [next.path]: next
      };
      rememberTreeListing(next, tabId);
      setListing(next);
      hydrateTreePath(next.path);
      setSelectedNames(new Set());
      setLastSelectedIndex(null);
    } catch (loadError) {
      if (activeTabIdRef.current === tabId && requestId === listingRequestRef.current) {
        setError(loadError instanceof Error ? loadError.message : String(loadError));
      }
    } finally {
      if (activeTabIdRef.current === tabId && requestId === listingRequestRef.current) {
        setLoading(false);
      }
    }
  }, [activeTab, cache, hydrateTreePath, rememberTreeListing]);

  useEffect(() => {
    if (!activeTab?.id) {
      setListing(null);
      treeCacheRef.current = {};
      setTreeCache({});
      setExpandedPaths(new Set(["/"]));
      return;
    }
    const tabTreeCache = cache.treeByTab[activeTab.id] || {};
    treeCacheRef.current = tabTreeCache;
    setTreeCache(tabTreeCache);
    const restoredExpanded = cache.expandedByTab[activeTab.id] || ["/"];
    setExpandedPaths(new Set(restoredExpanded));
    const cachedListing = cache.listings[activeTab.id] || null;
    setListing(cachedListing);
    setSelectedNames(new Set());
    setLastSelectedIndex(null);
    setEditorDoc(cache.editorsByTab[activeTab.id] || null);
    setCreateDialog(null);
    setRenameDialog(null);
    if (activeTab.state === "ready" && !cachedListing) {
      void loadPath(undefined, { force: true });
    }
  }, [activeTab?.id, activeTab?.state, cache, loadPath]);

  useEffect(() => {
    const offSaved = window.yySlide.events.onSftpFileSaved(({ tabId }) => {
      if (activeTab?.id === tabId) {
        loadPath(listing?.path, { force: true });
      }
    });
    const offError = window.yySlide.events.onSftpFileSaveError(({ tabId, error }) => {
      if (activeTab?.id === tabId) {
        setError(`自动上传失败：${error}`);
      }
    });
    return () => {
      offSaved();
      offError();
    };
  }, [activeTab?.id, listing?.path, loadPath]);

  useEffect(() => {
    const timers = new Map<string, number>();
    const offProgress = window.yySlide.events.onSftpUploadProgress((progress) => {
      setUploadProgresses((current) => ({
        ...current,
        [progress.uploadId]: {
          ...progress,
          updatedAt: Date.now()
        }
      }));
      if (progress.state === "done" || progress.state === "error" || progress.state === "canceled") {
        const existingTimer = timers.get(progress.uploadId);
        if (existingTimer) {
          window.clearTimeout(existingTimer);
        }
        timers.set(progress.uploadId, window.setTimeout(() => {
          setUploadProgresses((current) => {
            const next = { ...current };
            delete next[progress.uploadId];
            return next;
          });
          timers.delete(progress.uploadId);
        }, 12000));
      }
    });
    return () => {
      offProgress();
      for (const timer of timers.values()) {
        window.clearTimeout(timer);
      }
    };
  }, []);

  useEffect(() => {
    const timers = new Map<string, number>();
    const offProgress = window.yySlide.events.onSftpDownloadProgress((progress) => {
      setDownloadProgresses((current) => ({
        ...current,
        [progress.downloadId]: {
          ...progress,
          updatedAt: Date.now()
        }
      }));
      if (progress.state === "done" || progress.state === "error" || progress.state === "canceled") {
        const existingTimer = timers.get(progress.downloadId);
        if (existingTimer) {
          window.clearTimeout(existingTimer);
        }
        timers.set(progress.downloadId, window.setTimeout(() => {
          setDownloadProgresses((current) => {
            const next = { ...current };
            delete next[progress.downloadId];
            return next;
          });
          timers.delete(progress.downloadId);
        }, 12000));
      }
    });
    return () => {
      offProgress();
      for (const timer of timers.values()) {
        window.clearTimeout(timer);
      }
    };
  }, []);

  const requireListing = () => {
    if (!activeTab || !listing) {
      return null;
    }
    return { tabId: activeTab.id, remotePath: listing.path };
  };

  const selectedItems = useMemo(() => {
    if (!listing) {
      return [];
    }
    return listing.items.filter((item) => selectedNames.has(item.filename));
  }, [listing, selectedNames]);
  const visibleUploads = useMemo(() => (
    Object.values(uploadProgresses)
      .filter((upload) => upload.tabId === activeTab?.id)
      .sort((a, b) => {
        const stateWeight = (state: UploadProgress["state"]) => state === "running" ? 0 : state === "error" ? 1 : 2;
        return stateWeight(a.state) - stateWeight(b.state) || b.updatedAt - a.updatedAt;
      })
      .slice(0, 6)
  ), [activeTab?.id, uploadProgresses]);
  const visibleDownloads = useMemo(() => (
    Object.values(downloadProgresses)
      .filter((download) => download.tabId === activeTab?.id)
      .sort((a, b) => {
        const stateWeight = (state: DownloadProgress["state"]) => state === "running" ? 0 : state === "error" ? 1 : 2;
        return stateWeight(a.state) - stateWeight(b.state) || b.updatedAt - a.updatedAt;
      })
      .slice(0, 6)
  ), [activeTab?.id, downloadProgresses]);
  const selectedFiles = selectedItems.filter((item) => !item.isDirectory);
  const singleSelected = selectedItems.length === 1 ? selectedItems[0] : null;

  const runFileAction = async (action: () => Promise<unknown>, refresh = true) => {
    try {
      setError("");
      await action();
      if (refresh) {
        await loadPath(listing?.path, { force: true });
      }
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : String(actionError));
    }
  };

  const closeContextMenu = () => setContextMenu(null);
  useEffect(() => {
    if (!contextMenu) {
      return;
    }
    const close = () => closeContextMenu();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        closeContextMenu();
      }
    };
    window.addEventListener("click", close);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("click", close);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [contextMenu]);

  const selectItem = (item: SftpItem, index: number, event: React.MouseEvent) => {
    if (!listing) {
      return;
    }
    if (event.shiftKey && lastSelectedIndex !== null) {
      const start = Math.min(lastSelectedIndex, index);
      const end = Math.max(lastSelectedIndex, index);
      setSelectedNames(new Set(listing.items.slice(start, end + 1).map((entry) => entry.filename)));
      return;
    }
    if (event.ctrlKey || event.metaKey) {
      setSelectedNames((current) => {
        const next = new Set(current);
        if (next.has(item.filename)) {
          next.delete(item.filename);
        } else {
          next.add(item.filename);
        }
        return next;
      });
      setLastSelectedIndex(index);
      return;
    }
    setSelectedNames(new Set([item.filename]));
    setLastSelectedIndex(index);
  };

  const toggleTreePath = async (remotePath: string) => {
    const isExpanded = expandedPaths.has(remotePath);
    updateExpandedPaths((current) => {
      const next = new Set(current);
      if (isExpanded) {
        next.delete(remotePath);
      } else {
        next.add(remotePath);
      }
      return next;
    });
    if (!isExpanded) {
      await loadTreePath(remotePath);
    }
  };

  const openTextEditor = async (item: SftpItem) => {
    const args = requireListing();
    if (!args) {
      return;
    }
    if (!isEditableFile(item)) {
      setError("这个文件不适合内置编辑器打开，可以用下载保存到本地。");
      return;
    }
    const requestId = ++editorRequestRef.current;
    await runFileAction(async () => {
      const result = await window.yySlide.sftp.readFile({ ...args, filename: item.filename });
      if (activeTabIdRef.current !== args.tabId || requestId !== editorRequestRef.current) {
        return;
      }
      setEditorDoc({
        id: `${activeTab?.id || "tab"}:${result.path}:${Date.now()}`,
        tabId: args.tabId,
        remotePath: result.path,
        filename: result.filename,
        content: result.content,
        savedContent: result.content,
        language: languageForFile(result.filename),
        dirty: false,
        saving: false
      });
    }, false);
  };

  const openItem = async (item: SftpItem | null) => {
    const args = requireListing();
    if (!args || !item) {
      return;
    }
    if (item.isDirectory) {
      editorRequestRef.current += 1;
      await loadPath(remoteJoin(args.remotePath, item.filename));
      return;
    }
    await openTextEditor(item);
  };

  const saveEditor = async (content: string) => {
    const doc = editorDoc;
    if (!doc) {
      return;
    }
    setEditorDoc((current) => current ? { ...current, content, saving: true, error: "" } : current);
    try {
      await window.yySlide.sftp.saveFile({
        tabId: doc.tabId,
        remotePath: doc.remotePath,
        content
      });
      setEditorDoc((current) => current?.id === doc.id ? {
        ...current,
        savedContent: content,
        dirty: current.content !== content,
        saving: false,
        savedAt: Date.now(),
        error: ""
      } : current);
      await loadPath(listing?.path, { force: true });
    } catch (saveError) {
      setEditorDoc((current) => current?.id === doc.id ? {
        ...current,
        saving: false,
        error: saveError instanceof Error ? saveError.message : String(saveError)
      } : current);
    }
  };

  const closeEditor = async () => {
    if (editorDoc?.dirty && !(await confirmDialog("文件还没保存，确定关闭？", {
      title: "关闭编辑器",
      confirmText: "关闭",
      dangerous: true
    }))) {
      return;
    }
    editorRequestRef.current += 1;
    if (editorDoc?.tabId) {
      cache.editorsByTab[editorDoc.tabId] = null;
      onDirtyChange(editorDoc.tabId, false);
    }
    setEditorDoc(null);
  };

  const copySelectedPaths = async () => {
    if (!listing) {
      return;
    }
    const paths = selectedItems.length
      ? selectedItems.map((item) => remoteJoin(listing.path, item.filename))
      : [listing.path];
    await copyText(paths.join("\n"));
  };

  const downloadSelected = () => {
    const args = requireListing();
    if (!args || selectedFiles.length === 0) {
      return;
    }
    setFileView("downloads");
    if (selectedFiles.length === 1) {
      runFileAction(() => window.yySlide.sftp.download({ ...args, filename: selectedFiles[0].filename }), false);
      return;
    }
    runFileAction(() => window.yySlide.sftp.downloadMany({
      ...args,
      filenames: selectedFiles.map((item) => item.filename)
    }), false);
  };

  const downloadZipSelected = () => {
    const args = requireListing();
    if (!args || selectedItems.length === 0) {
      return;
    }
    setFileView("downloads");
    runFileAction(() => window.yySlide.sftp.downloadZip({
      ...args,
      items: selectedItems.map((item) => ({ filename: item.filename, isDirectory: item.isDirectory }))
    }), false);
  };

  const uploadFiles = () => {
    const args = requireListing();
    if (args) {
      setFileView("uploads");
      runFileAction(() => window.yySlide.sftp.upload(args));
    }
  };

  const cancelUpload = (uploadId: string) => {
    void window.yySlide.sftp.cancelUpload(uploadId).catch((cancelError) => {
      setError(cancelError instanceof Error ? cancelError.message : String(cancelError));
    });
  };

  const cancelDownload = (downloadId: string) => {
    void window.yySlide.sftp.cancelDownload(downloadId).catch((cancelError) => {
      setError(cancelError instanceof Error ? cancelError.message : String(cancelError));
    });
  };

  const createDirectory = () => {
    if (requireListing()) {
      setRenameDialog(null);
      setCreateDialog({ kind: "directory", name: "新建文件夹", saving: false, error: "" });
    }
  };

  const createFile = () => {
    if (requireListing()) {
      setRenameDialog(null);
      setCreateDialog({ kind: "file", name: "新建文件.txt", saving: false, error: "" });
    }
  };

  const submitCreateEntry = async () => {
    const args = requireListing();
    const dialog = createDialog;
    if (!args || !dialog) {
      return;
    }
    const validationError = remoteNameValidationError(dialog.name);
    if (validationError) {
      setCreateDialog({ ...dialog, error: validationError });
      return;
    }
    const name = dialog.name.trim();
    setCreateDialog({ ...dialog, saving: true, error: "" });
    try {
      if (dialog.kind === "directory") {
        await window.yySlide.sftp.mkdir({ ...args, name });
      } else {
        await window.yySlide.sftp.createFile({ ...args, name });
      }
      setCreateDialog(null);
      await loadPath(args.remotePath, { force: true });
    } catch (createError) {
      setCreateDialog((current) => current ? {
        ...current,
        saving: false,
        error: createError instanceof Error ? createError.message : String(createError)
      } : current);
    }
  };

  const renameSelected = () => {
    const args = requireListing();
    const item = singleSelected;
    if (args && item) {
      setCreateDialog(null);
      setRenameDialog({ item, name: item.filename, saving: false, error: "" });
    }
  };

  const submitRenameEntry = async () => {
    const args = requireListing();
    const dialog = renameDialog;
    if (!args || !dialog) {
      return;
    }
    const validationError = remoteNameValidationError(dialog.name);
    if (validationError) {
      setRenameDialog({ ...dialog, error: validationError });
      return;
    }
    const newName = dialog.name.trim();
    if (newName === dialog.item.filename) {
      setRenameDialog({ ...dialog, error: "名称没有变化。" });
      return;
    }
    setRenameDialog({ ...dialog, saving: true, error: "" });
    try {
      await window.yySlide.sftp.rename({ ...args, oldName: dialog.item.filename, newName });
      setRenameDialog(null);
      await loadPath(args.remotePath, { force: true });
    } catch (renameError) {
      setRenameDialog((current) => current ? {
        ...current,
        saving: false,
        error: renameError instanceof Error ? renameError.message : String(renameError)
      } : current);
    }
  };

  const deleteSelected = async () => {
    const args = requireListing();
    if (!args || selectedItems.length === 0) {
      return;
    }
    if (await confirmDialog(`删除选中的 ${selectedItems.length} 项？`, {
      title: "删除文件",
      confirmText: "删除",
      dangerous: true
    })) {
      runFileAction(() => window.yySlide.sftp.deleteMany({
        ...args,
        items: selectedItems.map((item) => ({ filename: item.filename, isDirectory: item.isDirectory }))
      }));
    }
  };

  const quickDeleteSelected = async () => {
    const args = requireListing();
    if (!args || selectedItems.length === 0) {
      return;
    }
    if (await confirmDialog(`使用 rm -rf 快速删除选中的 ${selectedItems.length} 项？`, {
      title: "快速删除",
      detail: "这个操作会递归删除目录，远端通常不能恢复。",
      confirmText: "快速删除",
      dangerous: true
    })) {
      runFileAction(() => window.yySlide.sftp.quickDelete({
        ...args,
        filenames: selectedItems.map((item) => item.filename)
      }));
    }
  };

  const handleDrop = (event: React.DragEvent) => {
    event.preventDefault();
    setDragActive(false);
    setRemoteDropTarget(null);
    const args = requireListing();
    if (!args) {
      return;
    }
    const localPaths = Array.from(event.dataTransfer.files)
      .map((file) => window.yySlide.files.getPathForFile(file))
      .filter(Boolean);
    if (localPaths.length) {
      setFileView("uploads");
      runFileAction(() => window.yySlide.sftp.uploadPaths({ ...args, localPaths }));
    }
  };

  const remoteDragItemsFor = (item: SftpItem, index: number) => {
    const dragItems = selectedNames.has(item.filename) && selectedItems.length ? selectedItems : [item];
    if (!selectedNames.has(item.filename)) {
      setSelectedNames(new Set([item.filename]));
      setLastSelectedIndex(index);
    }
    return dragItems;
  };

  const startRemoteDownloadDrag = (event: React.DragEvent<HTMLTableRowElement>, item: SftpItem, index: number) => {
    if (!activeTab || !listing || !canUseFiles) {
      event.preventDefault();
      return;
    }
    event.stopPropagation();
    const dragItems = remoteDragItemsFor(item, index);
    const dragPayload: RemoteFileDrag = {
      tabId: activeTab.id,
      remotePath: listing.path,
      items: dragItems.map((entry) => ({ filename: entry.filename, isDirectory: entry.isDirectory }))
    };
    remoteDragRef.current = dragPayload;
    remoteDropHandledRef.current = false;
    setRemoteDropTarget(null);
    event.dataTransfer.effectAllowed = "copyMove";
    event.dataTransfer.setData("application/x-yyslide-remote-files", JSON.stringify(dragPayload));
    event.dataTransfer.setData("text/plain", dragItems.map((entry) => remoteJoin(listing.path, entry.filename)).join("\n"));

    const registration = window.yySlide.sftp.createDragDownload({
      tabId: activeTab.id,
      remotePath: listing.path,
      items: dragPayload.items
    });
    if (!registration || "error" in registration) {
      setError(registration?.error || "拖拽下载初始化失败。");
      return;
    }
    event.dataTransfer.setData("DownloadURL", `${registration.mime}:${registration.filename}:${registration.url}`);
  };

  const finishRemoteDownloadDrag = (event: React.DragEvent<HTMLTableRowElement>) => {
    const didDropOutside = Boolean(remoteDragRef.current) && !remoteDropHandledRef.current && event.dataTransfer.dropEffect === "copy";
    remoteDragRef.current = null;
    remoteDropHandledRef.current = false;
    setRemoteDropTarget(null);
    if (didDropOutside) {
      setFileView("downloads");
    }
  };

  const canDropRemoteItemsInto = (targetItem: SftpItem) => {
    if (!listing || !activeTab || !targetItem.isDirectory || !remoteDragRef.current || remoteDragRef.current.tabId !== activeTab.id) {
      return false;
    }
    const targetPath = remoteJoin(listing.path, targetItem.filename);
    return remoteDragRef.current.items.some((entry) => remoteJoin(remoteDragRef.current?.remotePath || "", entry.filename) !== targetPath);
  };

  const moveRemoteItemsInto = async (targetItem: SftpItem) => {
    const args = requireListing();
    const drag = remoteDragRef.current;
    if (!args || !drag || drag.tabId !== args.tabId || !targetItem.isDirectory) {
      if (drag && args && drag.tabId !== args.tabId) {
        setError("只能在同一个 SSH 标签页内移动远端文件。");
      }
      return;
    }
    const targetPath = remoteJoin(args.remotePath, targetItem.filename);
    const itemsToMove = drag.items.filter((entry) => remoteJoin(drag.remotePath, entry.filename) !== targetPath);
    if (!itemsToMove.length) {
      return;
    }
    remoteDropHandledRef.current = true;
    setRemoteDropTarget(null);
    await runFileAction(() => window.yySlide.sftp.moveMany({
      tabId: drag.tabId,
      remotePath: drag.remotePath,
      targetPath,
      items: itemsToMove
    }));
  };

  const openContextMenu = (event: React.MouseEvent, item?: SftpItem, index?: number) => {
    event.preventDefault();
    event.stopPropagation();
    if (item && !selectedNames.has(item.filename)) {
      setSelectedNames(new Set([item.filename]));
      setLastSelectedIndex(index ?? null);
    }
    setContextMenu({ x: event.clientX, y: event.clientY });
  };

  const canUseFiles = Boolean(activeTab && activeTab.state === "ready" && listing);
  const tableWidth = useMemo(
    () => fileColumnDefaults.reduce((sum, column) => sum + fileColumns[column.key], 0),
    [fileColumns]
  );

  const startTreeResize = (event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    const originX = event.clientX;
    const originWidth = fileTreeWidth;
    document.body.classList.add("resizing-columns");

    const onMove = (moveEvent: MouseEvent) => {
      setFileTreeWidth(Math.max(160, Math.min(520, originWidth + moveEvent.clientX - originX)));
    };
    const onUp = () => {
      document.body.classList.remove("resizing-columns");
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  const startColumnResize = (event: React.MouseEvent<HTMLSpanElement>, key: FileColumnKey) => {
    event.preventDefault();
    event.stopPropagation();
    const column = fileColumnDefaults.find((item) => item.key === key);
    const originX = event.clientX;
    const originWidth = fileColumns[key];
    document.body.classList.add("resizing-columns");

    const onMove = (moveEvent: MouseEvent) => {
      const nextWidth = Math.max(column?.min || 70, originWidth + moveEvent.clientX - originX);
      setFileColumns((current) => ({ ...current, [key]: nextWidth }));
    };
    const onUp = () => {
      document.body.classList.remove("resizing-columns");
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  return (
    <section
      className={`file-panel ${dragActive ? "drag-active" : ""}`}
      onDragOver={(event) => {
        if (canUseFiles && Array.from(event.dataTransfer.types).includes("Files")) {
          event.preventDefault();
          setDragActive(true);
        }
      }}
      onDragLeave={(event) => {
        if (event.currentTarget === event.target) {
          setDragActive(false);
        }
      }}
      onDrop={handleDrop}
      onContextMenu={(event) => openContextMenu(event)}
    >
      <div className="file-tabs">
        <button className={fileView === "files" ? "active" : ""} onClick={() => setFileView("files")}>文件</button>
        <button
          className={fileView === "terminal" ? "active" : ""}
          onClick={() => {
            setLocalTerminalStarted(true);
            setFileView("terminal");
          }}
        >
          本地终端
        </button>
        <button className={fileView === "uploads" ? "active" : ""} onClick={() => setFileView("uploads")}>上传队列</button>
        <button className={fileView === "downloads" ? "active" : ""} onClick={() => setFileView("downloads")}>下载队列</button>
      </div>
      <div className="file-toolbar">
        <div className="path-line">
          <span>{listing?.path || "/home"}</span>
        </div>
        <div className="file-actions">
          <button title="返回上级" disabled={!canUseFiles} onClick={() => listing && loadPath(listing.parent)}>
            <ArrowUp size={16} />
          </button>
          <button title="主目录" disabled={!activeTab || activeTab.state !== "ready"} onClick={() => loadPath(".")}>
            <Home size={16} />
          </button>
          <button title="刷新" disabled={!activeTab || activeTab.state !== "ready"} onClick={() => loadPath(listing?.path, { force: true })}>
            <RefreshCw size={16} />
          </button>
          <button title="上传文件" disabled={!canUseFiles} onClick={() => {
            uploadFiles();
          }}>
            <Upload size={16} />
          </button>
          <button title="下载文件" disabled={!canUseFiles || selectedFiles.length === 0} onClick={downloadSelected}>
            <Download size={16} />
          </button>
          <button title="新建文件夹" disabled={!canUseFiles} onClick={createDirectory}>
            <FolderPlus size={16} />
          </button>
          <button title="重命名" disabled={!canUseFiles || selectedItems.length !== 1} onClick={renameSelected}>
            <Edit3 size={16} />
          </button>
          <button title="删除" disabled={!canUseFiles || selectedItems.length === 0} onClick={deleteSelected}>
            <Trash2 size={16} />
          </button>
        </div>
      </div>
      {error && <div className="file-error">{error}</div>}
      <div className={`file-browser ${fileView === "files" ? "" : "hidden-panel"}`} style={{ gridTemplateColumns: `${fileTreeWidth}px 6px minmax(0, 1fr)` }}>
        <FileTree
          activePath={listing?.path || ""}
          treeCache={treeCache}
          expandedPaths={expandedPaths}
          onOpenPath={loadPath}
          onTogglePath={toggleTreePath}
        />
        <div className="file-tree-splitter" title="拖动调整目录树宽度" onMouseDown={startTreeResize} />
        <div className="file-table-wrap" onClick={() => setSelectedNames(new Set())}>
          <table className="file-table" style={{ width: tableWidth }}>
            <colgroup>
              {fileColumnDefaults.map((column) => (
                <col key={column.key} style={{ width: fileColumns[column.key] }} />
              ))}
            </colgroup>
            <thead>
              <tr>
                {fileColumnDefaults.map((column) => (
                  <th key={column.key}>
                    <span>{column.label}</span>
                    <span
                      className="column-resizer"
                      title="拖动调整列宽"
                      onMouseDown={(event) => startColumnResize(event, column.key)}
                    />
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={6} className="file-empty">正在读取远端文件...</td>
                </tr>
              )}
              {!loading && listing?.items.map((item, index) => (
                <tr
                  key={item.filename}
                  className={`${selectedNames.has(item.filename) ? "selected" : ""} ${remoteDropTarget === item.filename ? "drop-target" : ""}`}
                  draggable={canUseFiles}
                  onClick={(event) => {
                    event.stopPropagation();
                    selectItem(item, index, event);
                  }}
                  onDragStart={(event) => startRemoteDownloadDrag(event, item, index)}
                  onDragEnd={finishRemoteDownloadDrag}
                  onDragOver={(event) => {
                    if (!canDropRemoteItemsInto(item)) {
                      return;
                    }
                    event.preventDefault();
                    event.stopPropagation();
                    event.dataTransfer.dropEffect = "move";
                    setRemoteDropTarget(item.filename);
                  }}
                  onDragLeave={() => {
                    if (remoteDropTarget === item.filename) {
                      setRemoteDropTarget(null);
                    }
                  }}
                  onDrop={(event) => {
                    if (!canDropRemoteItemsInto(item)) {
                      return;
                    }
                    event.preventDefault();
                    event.stopPropagation();
                    void moveRemoteItemsInto(item);
                  }}
                  onContextMenu={(event) => openContextMenu(event, item, index)}
                  onDoubleClick={() => openItem(item)}
                >
                  <td>
                    {item.isDirectory ? <span className="file-folder-icon" /> : <span className="file-icon" />}
                    <span>{item.filename}</span>
                  </td>
                  <td>{item.isDirectory ? "" : formatBytes(item.size)}</td>
                  <td>{item.isDirectory ? "文件夹" : item.isSymbolicLink ? "链接" : "文件"}</td>
                  <td>{formatDate(item.mtime)}</td>
                  <td>{item.longname.split(/\s+/)[0] || "-"}</td>
                  <td>{item.uid}/{item.gid}</td>
                </tr>
              ))}
              {!loading && !listing && (
                <tr>
                  <td colSpan={6} className="file-empty">连接成功后这里会显示 SFTP 文件。</td>
                </tr>
              )}
              {!loading && listing && listing.items.length === 0 && (
                <tr>
                  <td colSpan={6} className="file-empty">目录为空。</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
      {localTerminalStarted && (
        <div className={`local-terminal-panel ${fileView === "terminal" ? "" : "hidden-panel"}`}>
          <LocalTerminalPane />
        </div>
      )}
      <div className={`upload-queue-panel ${fileView === "uploads" ? "" : "hidden-panel"}`}>
        <UploadProgressPanel uploads={visibleUploads} onCancel={cancelUpload} />
      </div>
      <div className={`download-queue-panel ${fileView === "downloads" ? "" : "hidden-panel"}`}>
        <DownloadProgressPanel downloads={visibleDownloads} onCancel={cancelDownload} />
      </div>
      {dragActive && <div className="drop-overlay">释放后上传到当前目录</div>}
      {editorDoc && (
        <BuiltInEditor
          doc={editorDoc}
          onChange={(content) => setEditorDoc((current) => current ? { ...current, content, dirty: content !== current.savedContent } : current)}
          onSave={saveEditor}
          onClose={closeEditor}
        />
      )}
      {createDialog && (
        <CreateEntryDialog
          title={`新建${createDialog.kind === "directory" ? "文件夹" : "文件"}`}
          confirmLabel="创建"
          savingLabel="创建中..."
          name={createDialog.name}
          saving={createDialog.saving}
          error={createDialog.error}
          onNameChange={(name) => setCreateDialog((current) => current ? { ...current, name, error: "" } : current)}
          onSubmit={submitCreateEntry}
          onCancel={() => setCreateDialog(null)}
        />
      )}
      {renameDialog && (
        <CreateEntryDialog
          title="重命名"
          confirmLabel="重命名"
          savingLabel="重命名中..."
          name={renameDialog.name}
          saving={renameDialog.saving}
          error={renameDialog.error}
          onNameChange={(name) => setRenameDialog((current) => current ? { ...current, name, error: "" } : current)}
          onSubmit={submitRenameEntry}
          onCancel={() => setRenameDialog(null)}
        />
      )}
      {contextMenu && (
        <FileContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          canUseFiles={canUseFiles}
          selectedCount={selectedItems.length}
          selectedFileCount={selectedFiles.length}
          canOpen={Boolean(singleSelected)}
          canRename={selectedItems.length === 1}
          onRefresh={() => loadPath(listing?.path, { force: true })}
          onOpen={() => openItem(singleSelected)}
          onCopyPath={copySelectedPaths}
          onDownload={downloadSelected}
          onPackDownload={downloadZipSelected}
          onUpload={uploadFiles}
          onCreateFile={createFile}
          onCreateDirectory={createDirectory}
          onRename={renameSelected}
          onDelete={deleteSelected}
          onQuickDelete={quickDeleteSelected}
          onClose={closeContextMenu}
        />
      )}
    </section>
  );
}

function FileTree(props: {
  activePath: string;
  treeCache: Record<string, SftpItem[]>;
  expandedPaths: Set<string>;
  onOpenPath: (path: string) => void;
  onTogglePath: (path: string) => void;
}) {
  return (
    <div className="file-tree" onClick={(event) => event.stopPropagation()}>
      <FileTreeNode
        name="/"
        path="/"
        depth={0}
        activePath={props.activePath}
        treeCache={props.treeCache}
        expandedPaths={props.expandedPaths}
        onOpenPath={props.onOpenPath}
        onTogglePath={props.onTogglePath}
      />
    </div>
  );
}

function FileTreeNode(props: {
  name: string;
  path: string;
  depth: number;
  activePath: string;
  treeCache: Record<string, SftpItem[]>;
  expandedPaths: Set<string>;
  onOpenPath: (path: string) => void;
  onTogglePath: (path: string) => void;
}) {
  const children = props.treeCache[props.path] || [];
  const isExpanded = props.expandedPaths.has(props.path);
  const isActive = props.activePath === props.path;

  return (
    <div className="tree-node">
      <div
        className={`tree-row ${isActive ? "active" : ""}`}
        style={{ paddingLeft: 8 + props.depth * 20 }}
        onDoubleClick={() => props.onOpenPath(props.path)}
      >
        <button
          className={`tree-toggle ${isExpanded ? "expanded" : ""}`}
          title={isExpanded ? "折叠" : "展开"}
          onClick={() => props.onTogglePath(props.path)}
        />
        <button className="tree-name" title={props.path} onClick={() => props.onOpenPath(props.path)}>
          <span className="file-folder-icon" />
          <span>{props.name}</span>
        </button>
      </div>
      {isExpanded && children.map((child) => {
        const childPath = remoteJoin(props.path, child.filename);
        return (
          <FileTreeNode
            key={childPath}
            name={child.filename}
            path={childPath}
            depth={props.depth + 1}
            activePath={props.activePath}
            treeCache={props.treeCache}
            expandedPaths={props.expandedPaths}
            onOpenPath={props.onOpenPath}
            onTogglePath={props.onTogglePath}
          />
        );
      })}
    </div>
  );
}

function CreateEntryDialog(props: {
  title: string;
  confirmLabel: string;
  savingLabel: string;
  name: string;
  saving: boolean;
  error: string;
  onNameChange: (name: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      const input = inputRef.current;
      if (!input) {
        return;
      }
      input.focus();
      input.select();
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  return (
    <div className="name-dialog-backdrop" onPointerDownCapture={keepOverlayFocus} onMouseDown={(event) => event.stopPropagation()}>
      <div className="name-dialog">
        <div className="name-dialog-title">{props.title}</div>
        <input
          ref={inputRef}
          autoFocus
          value={props.name}
          disabled={props.saving}
          onPointerDown={(event) => event.stopPropagation()}
          onChange={(event) => props.onNameChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              props.onSubmit();
            }
            if (event.key === "Escape") {
              props.onCancel();
            }
          }}
        />
        {props.error && <div className="name-dialog-error">{props.error}</div>}
        <div className="name-dialog-actions">
          <button type="button" onClick={props.onCancel} disabled={props.saving}>取消</button>
          <button type="button" className="primary-button" onClick={props.onSubmit} disabled={props.saving || !props.name.trim()}>
            {props.saving ? props.savingLabel : props.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

function UploadProgressPanel(props: {
  uploads: Array<UploadProgress & { updatedAt: number }>;
  onCancel: (uploadId: string) => void;
}) {
  return (
    <aside className="upload-progress-panel">
      <div className="upload-progress-header">
        <strong>上传队列</strong>
        <span>{props.uploads.filter((upload) => upload.state === "running").length}</span>
      </div>
      <div className="upload-progress-list">
        {props.uploads.length === 0 && (
          <div className="upload-progress-empty">暂无上传任务</div>
        )}
        {props.uploads.map((upload) => {
          const filePercent = upload.fileBytes
            ? Math.min(100, Math.round((upload.fileTransferred / upload.fileBytes) * 1000) / 10)
            : upload.percent;
          return (
            <div className={`upload-progress-item ${upload.state}`} key={upload.uploadId}>
              <div className="upload-progress-title">
                <strong title={upload.filename}>{upload.filename || "上传任务"}</strong>
                <div className="upload-progress-title-actions">
                  <span>{upload.index}/{upload.totalFiles}</span>
                  {upload.state === "running" && (
                    <button
                      className="upload-cancel-button"
                      type="button"
                      title="终止上传"
                      onClick={() => props.onCancel(upload.uploadId)}
                    >
                      <X size={13} />
                    </button>
                  )}
                </div>
              </div>
              <div className="upload-progress-track">
                <i style={{ width: `${Math.max(0, Math.min(100, upload.percent))}%` }} />
              </div>
              <div className="upload-progress-stats">
                <span>{upload.percent.toFixed(upload.percent % 1 ? 1 : 0)}%</span>
                <span>{formatRate(upload.bytesPerSecond)}</span>
                <span>ETA {formatDuration(upload.etaSeconds)}</span>
              </div>
              <div className="upload-progress-detail">
                <span>{formatBytes(upload.transferredBytes)} / {formatBytes(upload.totalBytes)}</span>
                <span>当前 {filePercent.toFixed(filePercent % 1 ? 1 : 0)}%</span>
              </div>
              {upload.state === "done" && <div className="upload-progress-state">上传完成</div>}
              {upload.state === "canceled" && <div className="upload-progress-state canceled">上传已终止</div>}
              {upload.state === "error" && <div className="upload-progress-state error">{upload.error || "上传失败"}</div>}
            </div>
          );
        })}
      </div>
    </aside>
  );
}

function DownloadProgressPanel(props: {
  downloads: Array<DownloadProgress & { updatedAt: number }>;
  onCancel: (downloadId: string) => void;
}) {
  return (
    <aside className="upload-progress-panel">
      <div className="upload-progress-header">
        <strong>下载队列</strong>
        <span>{props.downloads.filter((download) => download.state === "running").length}</span>
      </div>
      <div className="upload-progress-list">
        {props.downloads.length === 0 && (
          <div className="upload-progress-empty">暂无下载任务</div>
        )}
        {props.downloads.map((download) => {
          const filePercent = download.fileBytes
            ? Math.min(100, Math.round((download.fileTransferred / download.fileBytes) * 1000) / 10)
            : download.percent;
          return (
            <div className={`upload-progress-item ${download.state}`} key={download.downloadId}>
              <div className="upload-progress-title">
                <strong title={download.filename}>{download.filename || "下载任务"}</strong>
                <div className="upload-progress-title-actions">
                  <span>{download.index}/{download.totalFiles}</span>
                  {download.state === "running" && (
                    <button
                      className="upload-cancel-button"
                      type="button"
                      title="终止下载"
                      onClick={() => props.onCancel(download.downloadId)}
                    >
                      <X size={13} />
                    </button>
                  )}
                </div>
              </div>
              <div className="upload-progress-track">
                <i style={{ width: `${Math.max(0, Math.min(100, download.percent))}%` }} />
              </div>
              <div className="upload-progress-stats">
                <span>{download.percent.toFixed(download.percent % 1 ? 1 : 0)}%</span>
                <span>{formatRate(download.bytesPerSecond)}</span>
                <span>ETA {formatDuration(download.etaSeconds)}</span>
              </div>
              <div className="upload-progress-detail">
                <span>{formatBytes(download.transferredBytes)} / {formatBytes(download.totalBytes)}</span>
                <span>当前 {filePercent.toFixed(filePercent % 1 ? 1 : 0)}%</span>
              </div>
              {download.state === "done" && <div className="upload-progress-state">下载完成</div>}
              {download.state === "canceled" && <div className="upload-progress-state canceled">下载已终止</div>}
              {download.state === "error" && <div className="upload-progress-state error">{download.error || "下载失败"}</div>}
            </div>
          );
        })}
      </div>
    </aside>
  );
}

function LocalTerminalPane() {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const localIdRef = useRef(makeId());

  const fitAndSendSize = useCallback(() => {
    const terminal = terminalRef.current;
    const fitAddon = fitAddonRef.current;
    if (!terminal || !fitAddon || !containerRef.current) {
      return;
    }
    try {
      fitAddon.fit();
      window.yySlide.localTerminal.resize({
        id: localIdRef.current,
        cols: terminal.cols,
        rows: terminal.rows
      });
    } catch {
      // The element can be temporarily hidden while switching bottom tabs.
    }
  }, []);

  useEffect(() => {
    if (!containerRef.current) {
      return;
    }

    const terminal = new Terminal({
      cursorBlink: true,
      fontFamily: "Consolas, Cascadia Mono, Menlo, monospace",
      fontSize: 13,
      lineHeight: 1.2,
      scrollback: 6000,
      theme: {
        background: "#000000",
        foreground: "#f2f5f8",
        cursor: "#f2f5f8",
        selectionBackground: "#496b9a66"
      }
    });
    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.loadAddon(new WebLinksAddon());
    terminal.open(containerRef.current);
    terminalRef.current = terminal;
    fitAddonRef.current = fitAddon;

    terminal.onData((data) => {
      window.yySlide.localTerminal.write({ id: localIdRef.current, data });
    });

    const resizeObserver = new ResizeObserver(() => fitAndSendSize());
    resizeObserver.observe(containerRef.current);

    const offData = window.yySlide.events.onLocalTerminalData(({ id, data }) => {
      if (id === localIdRef.current) {
        terminal.write(data);
      }
    });
    const offExit = window.yySlide.events.onLocalTerminalExit(({ id, exitCode }) => {
      if (id === localIdRef.current) {
        terminal.writeln(`\r\n[本地终端已退出${typeof exitCode === "number" ? `，代码 ${exitCode}` : ""}]`);
      }
    });

    setTimeout(async () => {
      fitAndSendSize();
      const result = await window.yySlide.localTerminal.open({
        id: localIdRef.current,
        cols: terminal.cols || 100,
        rows: terminal.rows || 28
      });
      if (!result.ok) {
        terminal.writeln(`本地终端启动失败：${result.error || "未知错误"}`);
      }
    }, 50);

    return () => {
      offData();
      offExit();
      resizeObserver.disconnect();
      window.yySlide.localTerminal.close(localIdRef.current);
      terminal.dispose();
      terminalRef.current = null;
      fitAddonRef.current = null;
    };
  }, [fitAndSendSize]);

  return (
    <div className="local-terminal-wrap">
      <div className="local-terminal-canvas" ref={containerRef} />
    </div>
  );
}

function BuiltInEditor(props: {
  doc: EditorDocument;
  onChange: (content: string) => void;
  onSave: (content: string) => void;
  onClose: () => void;
}) {
  const editorHostRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const modelRef = useRef<monaco.editor.ITextModel | null>(null);
  const latestSaveRef = useRef(props.onSave);
  const [frame, setFrame] = useState(() => ({
    x: Math.max(390, Math.round(window.innerWidth * 0.27)),
    y: 76,
    width: Math.min(980, Math.max(720, window.innerWidth - 440)),
    height: Math.min(660, Math.max(440, window.innerHeight - 130))
  }));

  useEffect(() => {
    latestSaveRef.current = props.onSave;
  }, [props.onSave]);

  const startDrag = (event: React.MouseEvent<HTMLDivElement>) => {
    if (event.button !== 0 || (event.target as HTMLElement).closest("button")) {
      return;
    }
    event.preventDefault();
    const origin = {
      mouseX: event.clientX,
      mouseY: event.clientY,
      x: frame.x,
      y: frame.y,
      width: frame.width,
      height: frame.height
    };
    document.body.classList.add("dragging-editor");

    const onMove = (moveEvent: MouseEvent) => {
      const maxX = Math.max(10, window.innerWidth - origin.width - 10);
      const maxY = Math.max(42, window.innerHeight - origin.height - 10);
      setFrame((current) => ({
        ...current,
        x: Math.max(10, Math.min(maxX, origin.x + moveEvent.clientX - origin.mouseX)),
        y: Math.max(42, Math.min(maxY, origin.y + moveEvent.clientY - origin.mouseY))
      }));
    };
    const onUp = () => {
      document.body.classList.remove("dragging-editor");
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  useEffect(() => {
    if (!editorHostRef.current) {
      return;
    }
    const uri = monaco.Uri.from({ scheme: "yyslide-remote", path: props.doc.remotePath });
    const model = monaco.editor.createModel(props.doc.content, props.doc.language, uri);
    const editor = monaco.editor.create(editorHostRef.current, {
      model,
      automaticLayout: true,
      fontFamily: "Consolas, Cascadia Mono, Microsoft YaHei UI, monospace",
      fontSize: 13,
      lineHeight: 20,
      minimap: { enabled: false },
      renderLineHighlight: "line",
      roundedSelection: false,
      scrollBeyondLastLine: false,
      tabSize: 2,
      theme: "vs",
      wordWrap: "off"
    });
    const contentDisposable = model.onDidChangeContent(() => {
      const content = model.getValue();
      props.onChange(content);
    });
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      latestSaveRef.current(model.getValue());
    });
    editor.focus();

    editorRef.current = editor;
    modelRef.current = model;

    return () => {
      contentDisposable.dispose();
      editor.dispose();
      model.dispose();
      editorRef.current = null;
      modelRef.current = null;
    };
  }, [props.doc.id]);

  return (
    <div
      className="editor-panel editor-float"
      style={{ left: frame.x, top: frame.y, width: frame.width, height: frame.height }}
    >
      <div className="editor-header" onMouseDown={startDrag}>
        <div className="editor-title">
          <strong>{props.doc.filename}</strong>
          <span>{props.doc.remotePath}</span>
        </div>
        <div className="editor-actions">
          <span className={props.doc.error ? "editor-status error" : "editor-status"}>
            {props.doc.error || (props.doc.saving ? "保存中..." : props.doc.dirty ? "未保存" : props.doc.savedAt ? "已保存" : "已同步")}
          </span>
          <button className="primary-button" disabled={props.doc.saving || !props.doc.dirty} onClick={() => props.onSave(modelRef.current?.getValue() || props.doc.content)}>
            <Save size={15} />
            保存
          </button>
          <button className="icon-button" title="关闭" onClick={props.onClose}>
            <X size={17} />
          </button>
        </div>
      </div>
      <div className="editor-body" ref={editorHostRef} />
    </div>
  );
}

function FileContextMenu(props: {
  x: number;
  y: number;
  canUseFiles: boolean;
  selectedCount: number;
  selectedFileCount: number;
  canOpen: boolean;
  canRename: boolean;
  onRefresh: () => void;
  onOpen: () => void;
  onCopyPath: () => void;
  onDownload: () => void;
  onPackDownload: () => void;
  onUpload: () => void;
  onCreateFile: () => void;
  onCreateDirectory: () => void;
  onRename: () => void;
  onDelete: () => void;
  onQuickDelete: () => void;
  onClose: () => void;
}) {
  const { menuRef, style } = useClampedMenuPosition(props.x, props.y, FILE_CONTEXT_MENU_WIDTH, 440);
  const menuLeft = typeof style.left === "number" ? style.left : props.x;
  const submenuLeftClass = menuLeft + FILE_CONTEXT_MENU_WIDTH + FILE_SUBMENU_WIDTH + MENU_VIEWPORT_MARGIN > window.innerWidth
    ? " submenu-left"
    : "";
  const item = (label: string, onClick: () => void, disabled = false, danger = false, arrow = false) => (
    <button
      type="button"
      className={`${danger ? "danger" : ""}`}
      disabled={disabled}
      onClick={(event) => {
        event.stopPropagation();
        if (!disabled) {
          props.onClose();
          onClick();
        }
      }}
    >
      <span>{label}</span>
      {arrow && <b>›</b>}
    </button>
  );

  return (
    <div
      ref={menuRef}
      className={`file-context-menu${submenuLeftClass}`}
      style={style}
      onPointerDownCapture={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      {item("刷新", props.onRefresh, !props.canUseFiles)}
      <div className="menu-separator" />
      {item("打开", props.onOpen, !props.canOpen)}
      {item("复制路径", props.onCopyPath, props.selectedCount === 0)}
      <div className="menu-separator" />
      {item("下载", props.onDownload, props.selectedFileCount === 0)}
      {item("上传...", props.onUpload, !props.canUseFiles)}
      <div className="menu-separator" />
      {item("打包...", props.onPackDownload, props.selectedCount === 0)}
      <div className="menu-separator" />
      <div className={`file-context-submenu ${!props.canUseFiles ? "disabled" : ""}`}>
        <button type="button" disabled={!props.canUseFiles}>
          <span>新建</span>
          <b>›</b>
        </button>
        {props.canUseFiles && (
          <div className="file-submenu">
            {item("文件", props.onCreateFile)}
            {item("文件夹", props.onCreateDirectory)}
          </div>
        )}
      </div>
      <div className="menu-separator" />
      {item("重命名", props.onRename, !props.canRename)}
      {item("删除", props.onDelete, props.selectedCount === 0, true)}
      {item("快速删除 (rm命令)", props.onQuickDelete, props.selectedCount === 0, true)}
    </div>
  );
}

function AccountModal(props: {
  account: AccountState;
  onClose: () => void;
  onAuthenticated: (account: AccountState) => Promise<void>;
  onSyncNow: () => Promise<void>;
  onLogout: () => Promise<AccountState | null>;
  onAccountChange: (account: AccountState) => void;
}) {
  const [mode, setMode] = useState<"login" | "register" | "reset">(props.account.loggedIn ? "login" : "login");
  const [serverUrl, setServerUrl] = useState(() => props.account.serverUrl || savedCloudServerUrl());
  const [username, setUsername] = useState(props.account.username || "");
  const [password, setPassword] = useState("");
  const [twoFactor, setTwoFactor] = useState("");
  const [recoveryCode, setRecoveryCode] = useState("");
  const [pendingTotp, setPendingTotp] = useState<{ kind: "register" | "reset"; totpSecret: string; otpauthUrl: string; resetId?: string; recoveryKeyBox?: Record<string, unknown> } | null>(null);
  const [totpCopyState, setTotpCopyState] = useState<"" | "copied" | "error">("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [postAuthState, setPostAuthState] = useState<AccountState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const firstInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      firstInputRef.current?.focus();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [mode, pendingTotp]);

  const keepAccountFocus = keepOverlayFocus;

  const rememberServer = (value: string) => {
    localStorage.setItem("yySlide.cloudServerUrl", value);
  };

  const showPasswordPolicy = mode === "register" || mode === "reset";
  const passwordIssues = useMemo(() => passwordPolicyIssues(password), [password]);
  const passwordIsValid = !showPasswordPolicy || passwordIssues.length === 0;

  const switchMode = (nextMode: "login" | "register" | "reset") => {
    setMode(nextMode);
    setPendingTotp(null);
    setTotpCopyState("");
    setRecoveryCodes([]);
    setPostAuthState(null);
    setTwoFactor("");
    setRecoveryCode("");
    setError("");
  };

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (actionError) {
      setError(friendlyRemoteError(actionError));
    } finally {
      setBusy(false);
    }
  };

  const startRegister = () => run(async () => {
    if (passwordIssues.length) {
      setError(`密码需要：${passwordIssues.join("、")}。`);
      return;
    }
    rememberServer(serverUrl);
    const result = await window.yySlide.account.registerStart({ serverUrl, username });
    setPendingTotp({ kind: "register", totpSecret: result.totpSecret, otpauthUrl: result.otpauthUrl });
    setTotpCopyState("");
  });

  const finishRegister = () => run(async () => {
    if (passwordIssues.length) {
      setError(`密码需要：${passwordIssues.join("、")}。`);
      return;
    }
    rememberServer(serverUrl);
    const result = await window.yySlide.account.registerFinish({
      serverUrl,
      username,
      password,
      totpCode: twoFactor.trim(),
      syncEnabled: false
    });
    setRecoveryCodes(result.recoveryCodes || []);
    setPostAuthState(result);
  });

  const login = () => run(async () => {
    rememberServer(serverUrl);
    const result = await window.yySlide.account.login({
      serverUrl,
      username,
      password,
      totpCode: twoFactor.trim(),
      syncEnabled: false
    });
    await props.onAuthenticated(result);
    props.onClose();
  });

  const startReset = () => run(async () => {
    if (passwordIssues.length) {
      setError(`密码需要：${passwordIssues.join("、")}。`);
      return;
    }
    if (!recoveryCode.trim()) {
      setError("请输入恢复码。");
      return;
    }
    rememberServer(serverUrl);
    const result = await window.yySlide.account.resetStart({
      serverUrl,
      username,
      recoveryCode
    });
    setPendingTotp({
      kind: "reset",
      totpSecret: result.totpSecret,
      otpauthUrl: result.otpauthUrl,
      resetId: result.resetId,
      recoveryKeyBox: result.recoveryKeyBox
    });
    setTotpCopyState("");
  });

  const finishReset = () => run(async () => {
    if (passwordIssues.length) {
      setError(`密码需要：${passwordIssues.join("、")}。`);
      return;
    }
    if (!pendingTotp?.resetId || !pendingTotp.recoveryKeyBox) {
      setError("重置会话无效，请重新开始。");
      return;
    }
    rememberServer(serverUrl);
    const result = await window.yySlide.account.resetFinish({
      serverUrl,
      username,
      password,
      totpCode: twoFactor.trim(),
      recoveryCode,
      resetId: pendingTotp.resetId,
      recoveryKeyBox: pendingTotp.recoveryKeyBox,
      syncEnabled: false
    });
    setRecoveryCodes(result.recoveryCodes || []);
    setPostAuthState(result);
  });

  if (recoveryCodes.length > 0) {
    return (
      <div className="modal-backdrop account-modal-backdrop">
        <div className="account-modal" onPointerDownCapture={keepAccountFocus}>
          <div className="modal-title">保存恢复码</div>
          <div className="account-help">恢复码只显示这一次。它用于重置密码和 2FA；每次重置后都会生成新恢复码，旧恢复码会作废。</div>
          <div className="recovery-grid">
            {recoveryCodes.map((code) => <code key={code}>{code}</code>)}
          </div>
          {error && <div className="dialog-error">{error}</div>}
          <div className="modal-actions">
            <button
              type="button"
              onClick={async () => {
                const text = recoveryCodes.join("\n");
                await copyText(text);
              }}
              disabled={busy}
            >
              复制恢复码
            </button>
            <button
              type="button"
              className="primary-button"
              onClick={() => run(async () => {
                await props.onAuthenticated(postAuthState || await window.yySlide.account.state());
                props.onClose();
              })}
              disabled={busy}
            >
              我已保存
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (props.account.loggedIn) {
    return (
      <div className="modal-backdrop account-modal-backdrop">
        <div className="account-modal" onPointerDownCapture={keepAccountFocus}>
          <div className="modal-title">账号与同步</div>
          <div className="account-status-card">
            <strong>{props.account.username}</strong>
            <span>{props.account.serverUrl}</span>
            <span>{props.account.syncEnabled ? `云同步已开启${props.account.lastSyncAt ? ` · ${new Date(props.account.lastSyncAt).toLocaleString("zh-CN")}` : ""}` : "当前仅本地使用"}</span>
          </div>
          {error && <div className="dialog-error">{error}</div>}
          <div className="modal-actions">
            <button type="button" onClick={props.onClose}>关闭</button>
            <button
              type="button"
              onClick={() => run(async () => {
                const next = await window.yySlide.account.setSyncEnabled(!props.account.syncEnabled);
                props.onAccountChange(next);
              })}
              disabled={busy}
            >
              {props.account.syncEnabled ? "关闭同步" : "开启同步"}
            </button>
            <button type="button" className="primary-button" onClick={() => run(props.onSyncNow)} disabled={busy || !props.account.syncEnabled}>立即同步</button>
            <button
              type="button"
              className="danger-button"
              onClick={() => run(async () => {
                await props.onLogout();
              })}
              disabled={busy}
            >
              退出登录
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="modal-backdrop account-modal-backdrop">
      <div className="account-modal" onPointerDownCapture={keepAccountFocus}>
        <div className="modal-title">{mode === "login" ? "登录 yySlide Cloud" : mode === "register" ? "注册 yySlide Cloud" : "重置账号"}</div>
        <div className="account-mode-tabs">
          <button type="button" className={mode === "login" ? "active" : ""} onClick={() => switchMode("login")}>登录</button>
          <button type="button" className={mode === "register" ? "active" : ""} onClick={() => switchMode("register")}>注册</button>
          <button type="button" className={mode === "reset" ? "active" : ""} onClick={() => switchMode("reset")}>重置</button>
        </div>
        <div className="account-form-body">
            <label>
              <span>同步服务器</span>
              <input ref={firstInputRef} value={serverUrl} onChange={(event) => setServerUrl(event.target.value)} placeholder={DEFAULT_CLOUD_SERVER_URL} disabled={busy || Boolean(pendingTotp && mode !== "login")} />
            </label>
            <label>
              <span>用户名</span>
              <input value={username} onChange={(event) => setUsername(event.target.value)} disabled={busy || Boolean(pendingTotp && mode !== "login")} />
            </label>
            <label>
              <span>{mode === "reset" ? "新密码" : "密码"}</span>
              <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} />
            </label>
            {showPasswordPolicy && (
              <div className="password-policy">
                {[
                  ["至少 8 位", password.length >= 8],
                  ["数字", /[0-9]/.test(password)],
                  ["小写字母", /[a-z]/.test(password)],
                  ["大写字母", /[A-Z]/.test(password)]
                ].map(([label, passed]) => (
                  <span className={passed ? "passed" : ""} key={String(label)}>{label}</span>
                ))}
              </div>
            )}
            {mode === "reset" && (
              <label>
                <span>恢复码</span>
                <input value={recoveryCode} onChange={(event) => setRecoveryCode(event.target.value)} disabled={busy || Boolean(pendingTotp)} placeholder="ABCDE-FGHIJ" />
              </label>
            )}
            {pendingTotp && (
              <div className="totp-box">
                <span>{pendingTotp.kind === "reset" ? "新的 2FA 密钥" : "2FA 密钥"}</span>
                <TotpQrCode value={pendingTotp.otpauthUrl} />
                <button
                  type="button"
                  onClick={async () => {
                    try {
                      await copyText(pendingTotp.otpauthUrl);
                      setTotpCopyState("copied");
                      window.setTimeout(() => setTotpCopyState(""), 1600);
                    } catch (copyError) {
                      console.error("Copy otpauth failed", copyError);
                      setTotpCopyState("error");
                    }
                  }}
                >
                  {totpCopyState === "copied" ? "已复制" : "复制 otpauth"}
                </button>
                {totpCopyState === "error" && <small>复制失败</small>}
              </div>
            )}
            {(mode === "login" || pendingTotp) && (
              <label>
                <span>{pendingTotp?.kind === "reset" ? "新 2FA 验证码" : "2FA 验证码"}</span>
                <input value={twoFactor} onChange={(event) => setTwoFactor(event.target.value)} disabled={busy} placeholder="123456" />
              </label>
            )}
            {error && <div className="dialog-error">{error}</div>}
            <div className="modal-actions">
              <button type="button" onClick={props.onClose} disabled={busy}>取消</button>
              {mode === "login" && (
                <button type="button" className="primary-button" onClick={login} disabled={busy || !serverUrl.trim() || !username.trim() || !password || !twoFactor.trim()}>
                  {busy ? "登录中..." : "登录"}
                </button>
              )}
              {mode === "register" && !pendingTotp && (
                <button type="button" className="primary-button" onClick={startRegister} disabled={busy || !serverUrl.trim() || !username.trim() || !passwordIsValid}>
                  {busy ? "处理中..." : "下一步"}
                </button>
              )}
              {mode === "register" && pendingTotp && (
                <button type="button" className="primary-button" onClick={finishRegister} disabled={busy || !twoFactor.trim()}>
                  {busy ? "注册中..." : "完成注册"}
                </button>
              )}
              {mode === "reset" && !pendingTotp && (
                <button type="button" className="primary-button" onClick={startReset} disabled={busy || !serverUrl.trim() || !username.trim() || !passwordIsValid || !recoveryCode.trim()}>
                  {busy ? "验证中..." : "重置 2FA"}
                </button>
              )}
              {mode === "reset" && pendingTotp && (
                <button type="button" className="primary-button" onClick={finishReset} disabled={busy || !twoFactor.trim()}>
                  {busy ? "重置中..." : "完成重置"}
                </button>
              )}
            </div>
        </div>
      </div>
    </div>
  );
}

function TotpQrCode({ value }: { value: string }) {
  const modules = useMemo(() => createQrMatrix(value), [value]);
  const quiet = 4;
  const size = modules.length + quiet * 2;

  return (
    <svg className="totp-qr" viewBox={`0 0 ${size} ${size}`} role="img" aria-label="2FA 二维码">
      <rect width={size} height={size} fill="#fff" />
      {modules.flatMap((row, y) => row.map((black, x) => (
        black ? <rect key={`${x}-${y}`} x={x + quiet} y={y + quiet} width="1" height="1" fill="#000" /> : null
      )))}
    </svg>
  );
}

function ConnectionModal(props: {
  profile: ConnectionProfile;
  groups: string[];
  onCancel: () => void;
  onSave: (profile: ConnectionProfile) => Promise<void>;
}) {
  const { profile, groups, onCancel, onSave } = props;
  const [form, setForm] = useState<ConnectionProfile>(() => ({
    ...emptyConnection,
    ...profile,
    password: "",
    passphrase: ""
  }));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const firstInputRef = useRef<HTMLInputElement | null>(null);

  const update = (patch: Partial<ConnectionProfile>) => setForm((current) => ({ ...current, ...patch }));

  useEffect(() => {
    const timer = window.setTimeout(() => {
      firstInputRef.current?.focus();
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  const submit = async () => {
    setSaving(true);
    setError("");
    try {
      await onSave(form);
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : String(submitError));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-backdrop">
      <div className="modal" onPointerDownCapture={keepOverlayFocus}>
        <div className="modal-header">
          <h2>{profile.id ? "编辑连接" : "新建连接"}</h2>
          <button type="button" className="icon-button" onClick={onCancel} title="关闭">
            <X size={18} />
          </button>
        </div>
        <div className="form-grid">
          <label className="wide">
            <span>IP / 主机</span>
            <input ref={firstInputRef} value={form.host} onChange={(event) => update({ host: event.target.value })} />
          </label>
          <label>
            <span>名称</span>
            <input value={form.name} onChange={(event) => update({ name: event.target.value })} />
          </label>
          <label>
            <span>端口</span>
            <input type="number" value={form.port} onChange={(event) => update({ port: Number(event.target.value) })} />
          </label>
          <label>
            <span>用户名</span>
            <input value={form.username} onChange={(event) => update({ username: event.target.value })} />
          </label>
          <label>
            <span>认证方式</span>
            <select value={form.authType} onChange={(event) => update({
              authType: event.target.value as ConnectionProfile["authType"],
              clearPassword: false,
              clearPassphrase: false
            })}>
              <option value="password">密码</option>
              <option value="key">私钥</option>
            </select>
          </label>
          <label>
            <span>分类</span>
            <input list="connection-groups" value={form.group || ""} onChange={(event) => update({ group: event.target.value })} />
            <datalist id="connection-groups">
              {groups.map((group) => <option value={group} key={group} />)}
            </datalist>
          </label>
          {form.authType === "password" ? (
            <label className="wide key-path">
              <span>密码{form.clearPassword ? "（将清除）" : profile.hasPassword ? "（留空保留）" : ""}</span>
              <input type="password" value={form.password || ""} onChange={(event) => update({ password: event.target.value, clearPassword: false })} />
              {profile.hasPassword && (
                <button type="button" onClick={() => update({ password: "", clearPassword: true })}>清除凭据</button>
              )}
            </label>
          ) : (
            <>
              <label className="wide key-path">
                <span>私钥路径</span>
                <input value={form.privateKeyPath || ""} onChange={(event) => update({ privateKeyPath: event.target.value })} />
                <button type="button" onClick={async () => {
                  const keyPath = await window.yySlide.connections.chooseKey();
                  if (keyPath) {
                    update({ privateKeyPath: keyPath });
                  }
                }}>
                  <KeyRound size={15} />
                  选择
                </button>
              </label>
              <label className="wide key-path">
                <span>密钥口令（可选{form.clearPassphrase ? "，将清除" : profile.hasPassphrase ? "，留空保留" : ""}）</span>
                <input type="password" value={form.passphrase || ""} onChange={(event) => update({ passphrase: event.target.value, clearPassphrase: false })} />
                {profile.hasPassphrase && (
                  <button type="button" onClick={() => update({ passphrase: "", clearPassphrase: true })}>清除口令</button>
                )}
              </label>
            </>
          )}
        </div>
        {error && <div className="modal-error">{error}</div>}
        <div className="modal-actions">
          <button type="button" onClick={onCancel}>取消</button>
          <button type="button" className="primary-button" onClick={submit} disabled={saving || !form.host || !form.username}>
            <Save size={16} />
            保存
          </button>
        </div>
      </div>
    </div>
  );
}

function SettingsModal(props: {
  settings: AppSettings;
  onCancel: () => void;
  onSave: (settings: AppSettings) => Promise<void>;
}) {
  const { settings, onCancel, onSave } = props;
  const [form, setForm] = useState<AppSettings>(() => ({
    ...fallbackSettings,
    ...settings,
    terminalBackgroundImageOpacity: settings.terminalBackgroundImageOpacity ?? 1
  }));
  const [saving, setSaving] = useState(false);
  const firstInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      firstInputRef.current?.focus();
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  const submit = async () => {
    setSaving(true);
    try {
      await onSave(form);
    } finally {
      setSaving(false);
    }
  };

  const restoreDefaults = () => {
    setForm({ ...fallbackSettings });
  };

  return (
    <div className="modal-backdrop">
      <div className="modal small-modal" onPointerDownCapture={keepOverlayFocus}>
        <div className="modal-header">
          <h2>外观设置</h2>
          <button type="button" className="icon-button" onClick={onCancel} title="关闭">
            <X size={18} />
          </button>
        </div>
        <div className="settings-list">
          <label>
            <span>终端纯色背景</span>
            <input ref={firstInputRef} type="color" value={form.terminalBackground} onChange={(event) => setForm({ ...form, terminalBackground: event.target.value })} />
          </label>
          <div className="settings-row wide">
            <span>终端背景图片</span>
            <div className="background-image-controls">
              <button
                type="button"
                onClick={async () => {
                  const image = await window.yySlide.settings.chooseBackgroundImage();
                  if (image) {
                    setForm({ ...form, terminalBackgroundImage: image, terminalBackgroundImageOpacity: form.terminalBackgroundImageOpacity ?? 1 });
                  }
                }}
              >
                选择图片
              </button>
              <button
                type="button"
                disabled={!form.terminalBackgroundImage}
                onClick={() => setForm({ ...form, terminalBackgroundImage: "" })}
              >
                清除图片
              </button>
              <em>{form.terminalBackgroundImage ? "已设置" : "未设置"}</em>
            </div>
          </div>
          <label className="wide">
            <span>图片透明度</span>
            <input
              type="range"
              min={0}
              max={100}
              value={Math.round((form.terminalBackgroundImageOpacity ?? 1) * 100)}
              disabled={!form.terminalBackgroundImage}
              onChange={(event) => setForm({ ...form, terminalBackgroundImageOpacity: Number(event.target.value) / 100 })}
            />
          </label>
          <label>
            <span>终端文字</span>
            <input type="color" value={form.terminalForeground} onChange={(event) => setForm({ ...form, terminalForeground: event.target.value })} />
          </label>
          <label>
            <span>强调色</span>
            <input type="color" value={form.accentColor} onChange={(event) => setForm({ ...form, accentColor: event.target.value })} />
          </label>
        </div>
        <div className="modal-actions">
          <button className="reset-settings-button" type="button" onClick={restoreDefaults}>
            <RotateCcw size={15} />
            恢复默认设置
          </button>
          <button type="button" onClick={onCancel}>取消</button>
          <button type="button" className="primary-button" onClick={submit} disabled={saving}>
            <Save size={16} />
            应用
          </button>
        </div>
      </div>
    </div>
  );
}
