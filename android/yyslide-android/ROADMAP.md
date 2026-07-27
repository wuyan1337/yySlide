# Haven Roadmap

## Completed

### Terminal
- [x] **Paste into terminal** — clipboard paste on toolbar/selection bar
- [x] **Bracket paste mode** — `ESC[200~`/`ESC[201~` wrapping for safe multi-line paste
- [x] **Highlighter-style text selection** — long-press drag with edge-scroll
- [x] **Terminal rendering fix** — main-thread emulator writes prevent resize corruption
- [x] **Keyboard toolbar customization** — JSON layout, configurable rows and keys
- [x] **Terminal color schemes** — Haven, Solarized Dark/Light, Monokai, Dracula, Nord, Gruvbox
- [x] **Arrow key fix for TUI apps** — correct VT key dispatch in termlib
- [x] **OSC sequence support** — OSC 8 hyperlinks, OSC 9/777 notifications, OSC 7 CWD tracking
- [x] **Smart clipboard** — strip TUI borders and unwrap soft-wrapped lines on copy
- [x] **Session manager search** — magnifying glass sends native search keys (tmux/zellij/screen/byobu), falls back to shell Ctrl+R
- [x] **Copy last command output** — OSC 133 semantic shell integration, one-tap copy of last command's output with setup dialog
- [x] **OSC 133-safe prompt detection** — session manager commands (tmux/zellij) work alongside shell integration escape sequences

### Wayland Desktop
- [x] **Desktop addons** — install and launch GUI apps within the Wayland compositor
- [x] **GTK Wayland preference** — GTK apps use Wayland backend by default
- [x] **Display-scale / resolution control** — pick the labwc-native compositor's output resolution (reflows windows, not just a visual zoom) from the toolbar / fullscreen menu (#161)

### Connections
- [x] **Import SSH keys** — PEM/OpenSSH/PuTTY PPK format with passphrase support
- [x] **FIDO2 SSH keys** — ecdsa-sk, ed25519-sk hardware key support
- [x] **Network discovery** — mDNS/broadcast LAN scanning for SSH hosts
- [x] **Port forwarding** — local (`-L`), remote (`-R`), and dynamic (`-D` SOCKS5 proxy) with visual flow diagrams, live add/edit/remove
- [x] **ProxyJump / multi-hop** — `ssh -J` style jump hosts via direct-tcpip channels
- [x] **Custom session commands** — override tmux/screen/zellij template with `{name}` placeholder
- [x] **Per-connection SSH options** — freeform ssh_config-style key-value pairs
- [x] **Drag-to-reorder connections** — manual ordering of connection list
- [x] **Fresh DNS resolution** — re-resolve hostnames on each connection attempt
- [x] **Background notification** — persistent notification with disconnect action while sessions active
- [x] **Mosh support** — UDP-based mobile shell for unreliable connections
- [x] **Eternal Terminal** — ET protocol support
- [x] **Reticulum** — mesh network transport
- [x] **Network-aware reconnect** — ConnectivityManager detects WiFi/cellular/VPN changes, triggers immediate SSH reconnect (2s debounce) instead of waiting for TCP timeout
- [x] **WireGuard tunnels** — per-app userspace WireGuard tunnel (wireguard-go + gVisor netstack via gomobile), wg-quick config per profile, no system VPN slot
- [x] **Tailscale auto-discovery** — detect Tailscale peers via local API and show as discovered hosts
- [x] **Cloudflare Access SSH** — per-hostname WebSocket tunnel via `cdn-cgi/access/ssh-gateway` with `Cookie: CF_Authorization=<jwt>`. In-app WebView captures the JWT from the team's IdP login; paste-JWT path for `cloudflared access token` headless setups. Experimental — wire protocol reverse-engineered from cloudflared, awaiting confirmation against live tenants (v5.35.0-rc1, #154).
- [x] **Workspace profiles** — savable session bundles open SSH tabs + port forwards + SFTP in one tap; also exposed as MCP `compose_workspace` verb

### Security
- [x] **Encrypted password storage** — AES-256-GCM encrypted stored passwords
- [x] **Encrypted SSH keys at rest** — Android Keystore wrapping
- [x] **Prevent screenshots** — optional FLAG_SECURE
- [x] **Zero passwords from memory** — wipe credential buffers after auth
- [x] **Screen lock** — biometric, PIN, password, or pattern unlock on launch
- [x] **Backup & restore** — encrypted export/import of all data (AES-256-GCM, PBKDF2)

### Remote Desktop
- [x] **VNC viewer** — embedded VNC client over SSH port forwarding
- [x] **VNC VeNCrypt** — RFB security type 19 with TLSPlain/X509Plain/TLSVnc/X509Vnc/TLSNone/X509None/Plain sub-types; connects to wayvnc, TigerVNC, libvncserver, x11vnc with username + password over TLS
- [x] **RDP** — Windows remote desktop via IronRDP (Rust/UniFFI), with **EGFX** graphics-pipeline support (ClearCodec + RemoteFX Progressive), verified against Windows Server 2025 (#106, #109)

### File Transfer
- [x] **SFTP browser** — full file browser with upload/download/open
- [x] **SMB/CIFS** — Windows file share browsing
- [x] **DocumentsProvider** — expose SFTP as Android storage provider
- [x] **rclone folder sync** — copy / mirror / move between remotes with include/exclude filters, size limits, bandwidth throttling, and dry-run preview; saved, reusable sync profiles; errors persisted to the connection log (#157, #158, #159)

### Media tools
- [x] **ffmpeg integration** — custom FFmpeg 8.0 build (full codec/format/filter set) wired into the file browser. Long-press any media file to convert; works on local, SFTP, SMB, and rclone. Container + video + audio encoder dropdowns with copy-remux defaults. Frame preview with filter live-update, fullscreen view, and seek slider. Audio preview (5-second playback with filters applied). Video filters (brightness, contrast, saturation, gamma, sharpen, denoise, deshake, auto color, speed, rotation) and audio filters (volume, EBU R128 normalize). "Save to" picker: Downloads or back to source folder with upload progress.
- [x] **Rclone cloud media streaming** — ffmpeg reads source files from rclone via HTTP + Range requests through the rclone VFS, so convert/preview/stream all start in seconds regardless of file size. Optional "Download first" toggle for offline conversion.
- [x] **HLS streaming server** — transcode any local or rclone media file to HLS segments and serve to other devices on the network; URL auto-copied to clipboard, muted-autoplay HTML5 player with "Tap to unmute", force H.264 Main/4.0 + 30fps + 1920×1080 cap for Android MediaCodec compatibility.
- [x] **Tap-to-play** — single tap on a rclone or local media file launches the system player (VLC, MX Player, etc.) via the rclone HTTP media server or FileProvider URI.
- [x] **DLNA server** — stream cloud media to smart TVs and Chromecast on the local network (via rclone's built-in DLNA serve).

### PRoot / Local
- [x] **Local Linux terminal (multi-distro)** — PRoot-based local shell with Alpine, Debian 12, Arch Linux ARM, and Void installable side-by-side (APK/APT/PACMAN/XBPS), switchable from the Desktop → Manage view
- [x] **Local-desktop manager** — per-distro install/start/stop of desktop environments with a Room-backed install log that names the failing layer; one-tap "Open shell" into any installed distro
- [x] **X11 desktops** — one-tap Xfce4 or Openbox via Xvnc, per-DE VNC port
- [x] **Nested Wayland desktops** — headless wlroots compositor (Sway) inside the rootfs, surfaced via wayvnc on the VNC client (Hyprland/niri offered but GPU-limited in proot — see #162)

## Near-term

### Cloud & file sync
- [ ] **rclone bisync** — two-way sync with change detection for keeping folders in lockstep (one-way copy / mirror / move already shipped — see Completed)

### Media tools (further work)
- [ ] **AV1 decoder (libdav1d)** — add AV1 decoding support to the bundled ffmpeg so the next class of modern files (YouTube / new cameras) decodes without falling back to native Android MediaCodec
- [ ] **libvidstab two-pass stabilization** — replace the built-in `deshake` filter with the much higher-quality two-pass vidstab in the "Stabilize" preset
- [ ] **librubberband speed changes** — pitch-preserving time-stretch so the Speed slider doesn't produce chipmunk audio when slowing down a video
- [ ] **libwebp image support** — frame preview could save as WebP instead of the current 1-frame-MP4 + MediaMetadataRetriever workaround
- [ ] **libssh in ffmpeg** — lets ffmpeg read directly from SFTP servers without going through the download-to-cache path (HTTP-stream trick for SFTP profiles)
- [ ] **Media trim/cut UI** — start/end seek points in the convert dialog to extract a portion of a file (infrastructure for seekTo/duration already in TranscodeCommand)
- [ ] **Resolution preset picker** — quick 720p / 480p / Original chips in the convert dialog that drive `-vf scale`
- [ ] **Estimated output size** — `(bitrate × duration)` hint next to the Convert button so users know what they're about to create

### Encryption
- [ ] **age file encryption** — encrypt/decrypt files in the SFTP/rclone browser using [age](https://age-encryption.org) (Go library via gomobile), with key management in Haven's key store

### Terminal depth
- [ ] **Split panes** — horizontal/vertical splits within a tab, independent SSH sessions per pane
- [ ] **Prompt-to-prompt navigation** — jump between commands using OSC 133 markers (infrastructure exists in termlib accessibility layer)

### PRoot development
- [ ] **Curated dev stacks** — one-tap Python/Node.js/Rust/Go installation following the existing DesktopEnvironment enum pattern
- [ ] **sshfs mounts** — mount remote filesystems inside PRoot

### Security
- [x] **Agent forwarding** — SSH agent for `git push` from remote servers (v4.51.0, #75)

## Longer-term

- [ ] **X11 forwarding** — lightweight X11 server for individual GUI applications
- [ ] **Connection groups/folders** — organize by project or environment
- [ ] **Snippet/command library** — save and recall frequent commands
- [ ] **Per-profile auth unlock** — require authentication for high-security connections
- [ ] **Audit log UI** — surface ConnectionLog entity for security-conscious users
