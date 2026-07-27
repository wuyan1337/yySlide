const { contextBridge, ipcRenderer, webUtils } = require("electron");

function subscribe(channel, callback) {
  const listener = (_event, payload) => callback(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
}

contextBridge.exposeInMainWorld("yySlide", {
  account: {
    state: () => ipcRenderer.invoke("account:state"),
    registerStart: (payload) => ipcRenderer.invoke("account:register-start", payload),
    registerFinish: (payload) => ipcRenderer.invoke("account:register-finish", payload),
    login: (payload) => ipcRenderer.invoke("account:login", payload),
    resetStart: (payload) => ipcRenderer.invoke("account:reset-start", payload),
    resetFinish: (payload) => ipcRenderer.invoke("account:reset-finish", payload),
    logout: () => ipcRenderer.invoke("account:logout"),
    setSyncEnabled: (enabled) => ipcRenderer.invoke("account:set-sync-enabled", enabled),
    syncNow: () => ipcRenderer.invoke("account:sync-now"),
    pullNow: () => ipcRenderer.invoke("account:pull-now")
  },
  connections: {
    list: () => ipcRenderer.invoke("connections:list"),
    listGroups: () => ipcRenderer.invoke("connections:list-groups"),
    saveGroups: (groups) => ipcRenderer.invoke("connections:save-groups", groups),
    save: (connection) => ipcRenderer.invoke("connections:save", connection),
    delete: (id) => ipcRenderer.invoke("connections:delete", id),
    chooseKey: () => ipcRenderer.invoke("connections:choose-key")
  },
  settings: {
    get: () => ipcRenderer.invoke("settings:get"),
    save: (settings) => ipcRenderer.invoke("settings:save", settings),
    chooseBackgroundImage: () => ipcRenderer.invoke("settings:choose-background-image")
  },
  dialog: {
    confirm: (payload) => ipcRenderer.invoke("dialog:confirm", payload),
    message: (payload) => ipcRenderer.invoke("dialog:message", payload)
  },
  window: {
    minimize: () => ipcRenderer.invoke("window:minimize"),
    maximizeToggle: () => ipcRenderer.invoke("window:maximize-toggle"),
    close: () => ipcRenderer.invoke("window:close"),
    setUnsaved: (hasUnsavedChanges) => ipcRenderer.invoke("window:set-unsaved", hasUnsavedChanges === true)
  },
  ssh: {
    connect: (payload) => ipcRenderer.invoke("ssh:connect", payload),
    write: (payload) => ipcRenderer.invoke("ssh:write", payload),
    resize: (payload) => ipcRenderer.invoke("ssh:resize", payload),
    disconnect: (tabId) => ipcRenderer.invoke("ssh:disconnect", tabId)
  },
  localTerminal: {
    open: (payload) => ipcRenderer.invoke("local-terminal:open", payload),
    write: (payload) => ipcRenderer.invoke("local-terminal:write", payload),
    resize: (payload) => ipcRenderer.invoke("local-terminal:resize", payload),
    close: (id) => ipcRenderer.invoke("local-terminal:close", id)
  },
  sftp: {
    list: (payload) => ipcRenderer.invoke("sftp:list", payload),
    upload: (payload) => ipcRenderer.invoke("sftp:upload", payload),
    uploadPaths: (payload) => ipcRenderer.invoke("sftp:upload-paths", payload),
    cancelUpload: (uploadId) => ipcRenderer.invoke("sftp:cancel-upload", uploadId),
    cancelDownload: (downloadId) => ipcRenderer.invoke("sftp:cancel-download", downloadId),
    download: (payload) => ipcRenderer.invoke("sftp:download", payload),
    downloadMany: (payload) => ipcRenderer.invoke("sftp:download-many", payload),
    downloadZip: (payload) => ipcRenderer.invoke("sftp:download-zip", payload),
    createDragDownload: (payload) => ipcRenderer.sendSync("sftp:create-drag-download", payload),
    open: (payload) => ipcRenderer.invoke("sftp:open", payload),
    readFile: (payload) => ipcRenderer.invoke("sftp:read-file", payload),
    saveFile: (payload) => ipcRenderer.invoke("sftp:save-file", payload),
    delete: (payload) => ipcRenderer.invoke("sftp:delete", payload),
    deleteMany: (payload) => ipcRenderer.invoke("sftp:delete-many", payload),
    quickDelete: (payload) => ipcRenderer.invoke("sftp:quick-delete", payload),
    mkdir: (payload) => ipcRenderer.invoke("sftp:mkdir", payload),
    createFile: (payload) => ipcRenderer.invoke("sftp:create-file", payload),
    rename: (payload) => ipcRenderer.invoke("sftp:rename", payload),
    moveMany: (payload) => ipcRenderer.invoke("sftp:move-many", payload)
  },
  events: {
    onTerminalData: (callback) => subscribe("terminal:data", callback),
    onSshState: (callback) => subscribe("ssh:state", callback),
    onMonitorUpdate: (callback) => subscribe("monitor:update", callback),
    onMonitorError: (callback) => subscribe("monitor:error", callback),
    onSftpUploadProgress: (callback) => subscribe("sftp:upload-progress", callback),
    onSftpDownloadProgress: (callback) => subscribe("sftp:download-progress", callback),
    onSftpFileSaved: (callback) => subscribe("sftp:file-saved", callback),
    onSftpFileSaveError: (callback) => subscribe("sftp:file-save-error", callback),
    onLocalTerminalData: (callback) => subscribe("local-terminal:data", callback),
    onLocalTerminalExit: (callback) => subscribe("local-terminal:exit", callback),
    onAccountVaultChanged: (callback) => subscribe("account:vault-changed", callback)
  },
  files: {
    getPathForFile: (file) => webUtils.getPathForFile(file)
  },
  clipboard: {
    readText: () => ipcRenderer.invoke("clipboard:read-text"),
    writeText: (text) => ipcRenderer.invoke("clipboard:write-text", text)
  }
});
