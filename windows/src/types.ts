export type AuthType = "password" | "key";
export type SshState = "idle" | "connecting" | "ready" | "closed" | "error";

export interface ConnectionProfile {
  id?: string;
  name: string;
  host: string;
  port: number;
  username: string;
  authType: AuthType;
  privateKeyPath?: string;
  group?: string;
  updatedAt?: string;
  hasPassword?: boolean;
  hasPassphrase?: boolean;
  password?: string;
  passphrase?: string;
  clearPassword?: boolean;
  clearPassphrase?: boolean;
}

export interface AppSettings {
  terminalBackground: string;
  terminalForeground: string;
  terminalBackgroundImage: string;
  terminalBackgroundImageOpacity: number;
  accentColor: string;
}

export interface AccountState {
  loggedIn: boolean;
  serverUrl?: string;
  username?: string;
  syncEnabled: boolean;
  lastSyncAt?: string | null;
  user?: {
    id: string;
    username: string;
    createdAt?: string;
    vaultUpdatedAt?: string | null;
  } | null;
}

export interface SessionTab {
  id: string;
  kind: "new" | "ssh";
  connectionId?: string;
  title: string;
  host?: string;
  state: SshState;
  error?: string;
}

export interface MonitorSnapshot {
  sampledAt: number;
  host: string;
  ip: string;
  privateIp?: string;
  publicIp?: string;
  uptimeSeconds: number;
  cpu?: {
    percent: number;
  };
  load: {
    one: number;
    five: number;
    fifteen: number;
  };
  memory: {
    total: number;
    used: number;
    available: number;
    percent: number;
  };
  swap: {
    total: number;
    used: number;
    percent: number;
  };
  disks: Array<{
    mount: string;
    total: number;
    used: number;
    available: number;
    percent: number;
  }>;
  network: Array<{
    name: string;
    rx: number;
    tx: number;
    rxRate: number;
    txRate: number;
  }>;
  processes: Array<{
    pid: number;
    command: string;
    cpu: number;
    mem: number;
    rss: number;
  }>;
}

export interface SftpItem {
  filename: string;
  longname: string;
  size: number;
  mode: number;
  uid: number;
  gid: number;
  mtime: number;
  atime: number;
  isDirectory: boolean;
  isFile: boolean;
  isSymbolicLink: boolean;
}

export interface SftpListing {
  path: string;
  parent: string;
  items: SftpItem[];
}

export type UploadProgressState = "running" | "done" | "error" | "canceled";

export interface UploadProgress {
  uploadId: string;
  tabId: string;
  remotePath: string;
  filename: string;
  index: number;
  totalFiles: number;
  fileBytes: number;
  fileTransferred: number;
  totalBytes: number;
  transferredBytes: number;
  percent: number;
  bytesPerSecond: number;
  etaSeconds: number;
  state: UploadProgressState;
  error?: string;
}

export type DownloadProgressState = "running" | "done" | "error" | "canceled";

export interface DownloadProgress {
  downloadId: string;
  tabId: string;
  remotePath: string;
  filename: string;
  index: number;
  totalFiles: number;
  fileBytes: number;
  fileTransferred: number;
  totalBytes: number;
  transferredBytes: number;
  percent: number;
  bytesPerSecond: number;
  etaSeconds: number;
  state: DownloadProgressState;
  error?: string;
}

export type AppDialogType = "none" | "info" | "error" | "question" | "warning";

export interface AppDialogPayload {
  title?: string;
  message: string;
  detail?: string;
  type?: AppDialogType;
  buttons?: string[];
  defaultId?: number;
  cancelId?: number;
  confirmId?: number;
}

export interface YySlideApi {
  account: {
    state: () => Promise<AccountState>;
    registerStart: (payload: { serverUrl: string; username: string }) => Promise<{ username: string; totpSecret: string; otpauthUrl: string }>;
    registerFinish: (payload: { serverUrl: string; username: string; password: string; totpCode: string; syncEnabled: boolean }) => Promise<AccountState & { recoveryCodes: string[] }>;
    login: (payload: { serverUrl: string; username: string; password: string; totpCode: string; syncEnabled: boolean }) => Promise<AccountState>;
    resetStart: (payload: { serverUrl: string; username: string; recoveryCode: string }) => Promise<{ resetId: string; totpSecret: string; otpauthUrl: string; recoveryKeyBox: Record<string, unknown> }>;
    resetFinish: (payload: { serverUrl: string; username: string; password: string; totpCode: string; recoveryCode: string; resetId: string; recoveryKeyBox: Record<string, unknown>; syncEnabled: boolean }) => Promise<AccountState & { recoveryCodes: string[] }>;
    logout: () => Promise<AccountState>;
    setSyncEnabled: (enabled: boolean) => Promise<AccountState>;
    syncNow: () => Promise<AccountState>;
    pullNow: () => Promise<AccountState>;
  };
  connections: {
    list: () => Promise<ConnectionProfile[]>;
    listGroups: () => Promise<string[]>;
    saveGroups: (groups: string[]) => Promise<string[]>;
    save: (connection: ConnectionProfile) => Promise<ConnectionProfile>;
    delete: (id: string) => Promise<boolean>;
    chooseKey: () => Promise<string | null>;
  };
  settings: {
    get: () => Promise<AppSettings>;
    save: (settings: Partial<AppSettings>) => Promise<AppSettings>;
    chooseBackgroundImage: () => Promise<string | null>;
  };
  dialog: {
    confirm: (payload: AppDialogPayload) => Promise<boolean>;
    message: (payload: Omit<AppDialogPayload, "confirmId">) => Promise<boolean>;
  };
  window: {
    minimize: () => Promise<boolean>;
    maximizeToggle: () => Promise<boolean>;
    close: () => Promise<boolean>;
    setUnsaved: (hasUnsavedChanges: boolean) => Promise<boolean>;
  };
  ssh: {
    connect: (payload: { tabId: string; connectionId: string; cols?: number; rows?: number }) => Promise<{ ok: boolean; error?: string }>;
    write: (payload: { tabId: string; data: string }) => Promise<boolean>;
    resize: (payload: { tabId: string; cols: number; rows: number }) => Promise<boolean>;
    disconnect: (tabId: string) => Promise<boolean>;
  };
  localTerminal: {
    open: (payload: { id: string; cols?: number; rows?: number }) => Promise<{ ok: boolean; error?: string; shell?: string }>;
    write: (payload: { id: string; data: string }) => Promise<boolean>;
    resize: (payload: { id: string; cols: number; rows: number }) => Promise<boolean>;
    close: (id: string) => Promise<boolean>;
  };
  sftp: {
    list: (payload: { tabId: string; remotePath?: string }) => Promise<SftpListing>;
    upload: (payload: { tabId: string; remotePath: string }) => Promise<{ canceled: boolean; count?: number }>;
    uploadPaths: (payload: { tabId: string; remotePath: string; localPaths: string[] }) => Promise<{ canceled: boolean; count?: number }>;
    cancelUpload: (uploadId: string) => Promise<boolean>;
    cancelDownload: (downloadId: string) => Promise<boolean>;
    download: (payload: { tabId: string; remotePath: string; filename: string }) => Promise<{ canceled: boolean; path?: string }>;
    downloadMany: (payload: { tabId: string; remotePath: string; filenames: string[] }) => Promise<{ canceled: boolean; count?: number }>;
    downloadZip: (payload: { tabId: string; remotePath: string; items: Array<{ filename: string; isDirectory: boolean }> }) => Promise<{ canceled: boolean; count?: number; path?: string }>;
    createDragDownload: (payload: { tabId: string; remotePath: string; items: Array<{ filename: string; isDirectory: boolean }> }) => { filename: string; mime: string; url: string } | { error: string } | null;
    open: (payload: { tabId: string; remotePath: string; filename: string; isDirectory: boolean }) => Promise<{ directory: boolean; path: string }>;
    readFile: (payload: { tabId: string; remotePath: string; filename: string }) => Promise<{ path: string; filename: string; content: string; size: number; mtime: number }>;
    saveFile: (payload: { tabId: string; remotePath: string; content: string }) => Promise<boolean>;
    delete: (payload: { tabId: string; remotePath: string; filename: string; isDirectory: boolean }) => Promise<boolean>;
    deleteMany: (payload: { tabId: string; remotePath: string; items: Array<{ filename: string; isDirectory: boolean }> }) => Promise<boolean>;
    quickDelete: (payload: { tabId: string; remotePath: string; filenames: string[] }) => Promise<boolean>;
    mkdir: (payload: { tabId: string; remotePath: string; name: string }) => Promise<boolean>;
    createFile: (payload: { tabId: string; remotePath: string; name: string }) => Promise<boolean>;
    rename: (payload: { tabId: string; remotePath: string; oldName: string; newName: string }) => Promise<boolean>;
    moveMany: (payload: { tabId: string; remotePath: string; targetPath: string; items: Array<{ filename: string; isDirectory: boolean }> }) => Promise<boolean>;
  };
  events: {
    onTerminalData: (callback: (payload: { tabId: string; data: string }) => void) => () => void;
    onSshState: (callback: (payload: { tabId: string; state: SshState; error?: string }) => void) => () => void;
    onMonitorUpdate: (callback: (payload: { tabId: string; data: MonitorSnapshot }) => void) => () => void;
    onMonitorError: (callback: (payload: { tabId: string; error: string }) => void) => () => void;
    onSftpUploadProgress: (callback: (payload: UploadProgress) => void) => () => void;
    onSftpDownloadProgress: (callback: (payload: DownloadProgress) => void) => () => void;
    onSftpFileSaved: (callback: (payload: { tabId: string; remotePath: string }) => void) => () => void;
    onSftpFileSaveError: (callback: (payload: { tabId: string; remotePath: string; error: string }) => void) => () => void;
    onLocalTerminalData: (callback: (payload: { id: string; data: string }) => void) => () => void;
    onLocalTerminalExit: (callback: (payload: { id: string; exitCode?: number; signal?: number }) => void) => () => void;
    onAccountVaultChanged: (callback: (payload: { account: AccountState; event?: { type?: string; updatedAt?: string; sourceClientId?: string } }) => void) => () => void;
  };
  files: {
    getPathForFile: (file: File) => string;
  };
  clipboard: {
    readText: () => Promise<string>;
    writeText: (text: string) => Promise<boolean>;
  };
}
