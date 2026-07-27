---
layout: default
title: Features
---

# Features

Full feature detail for yySlide Android. The [landing page](index.html) has a short summary.

## Terminal

VT100/xterm emulator with multi-tab sessions, [Mosh](https://mosh.org) (Mobile Shell) for roaming connections and [Eternal Terminal](https://eternalterminal.dev) (ET) for persistent sessions — both with pure Kotlin protocol implementations (no native binaries), tmux/zellij/screen auto-attach with **session restore** (remembers previously open sessions and offers to reopen them), tab reordering via long-press menu, color-coded tabs matching connection profiles, mouse mode for TUI apps, configurable keyboard toolbar (Esc, Tab, Ctrl, Alt, AltGr, arrows, Delete, Insert, Home/End/PgUp/PgDn, F1–F12, custom macro keys with presets for common combos like Ctrl+C/D/Z and Ctrl+Alt+Delete), text selection with copy and Open URL, OSC 133 shell integration with one-tap "copy last command output", configurable font size, and six color schemes.

### OSC escape sequences

Remote programs can interact with Android through standard terminal escape sequences:

| OSC | Function | Example |
|-----|----------|---------|
| 52 | Set clipboard | `printf '\e]52;c;%s\a' "$(echo -n text \| base64)"` |
| 8 | Hyperlinks | `printf '\e]8;;https://example.com\aClick\e]8;;\a'` |
| 9 | Notification | `printf '\e]9;Build complete\a'` |
| 777 | Notification (with title) | `printf '\e]777;notify;CI;Pipeline green\a'` |
| 7 | Working directory | `printf '\e]7;file:///home/user\a'` |

Notifications appear as a toast in the foreground or as an Android notification in the background.

## Desktop (VNC)

Remote desktop viewer with RFB 3.8 protocol support. Pinch-to-zoom, two-finger pan and scroll, single-finger drag for window management, soft keyboard with X11 KeySym mapping. Fullscreen mode with NoMachine-style corner hotspot for session controls. Connect directly or tunnel through SSH. Supports Raw, CopyRect, RRE, Hextile, and ZLib encodings. Security types: **None**, classic **VncAuth** (DES), and **VeNCrypt** (security type 19) with TLSPlain/X509Plain/TLSVnc/X509Vnc/TLSNone/X509None/Plain sub-types — connects to wayvnc, TigerVNC, libvncserver, x11vnc and other servers that require a username alongside a password, wrapping the socket in TLS after sub-type negotiation.

## Native Wayland Desktop

GPU-accelerated Wayland compositor (labwc) running natively inside yySlide. Full interactive terminal with keyboard input, mouse interaction, server-side window decorations, pinch-to-zoom, and fullscreen mode with corner overlay menu. The GPU pipeline renders via GLES2 on the device's GPU (AHardwareBuffer allocator, ASurfaceControl zero-copy presentation). Native Wayland clients can render 3D content — includes a built-in GLES2 benchmark (rotating lit cube at 60fps on Mali-G715). **Display-scale / resolution control** — pick the compositor's output resolution from the toolbar or fullscreen menu; it reflows windows at the new resolution rather than just visually zooming. Configurable shell (/bin/sh, bash, zsh, fish) and shared keyboard toolbar (Esc, Tab, Ctrl, Alt, arrows, function keys, plus a Super/Mod4 key for compositor keybinds). External Wayland clients can connect via Shizuku, which uses the legacy compatibility socket directory `/data/local/tmp/haven-wayland/`. No root required — runs in PRoot with an Alpine Linux rootfs.

## Local Desktops (multi-distro manager)

A **Desktop → Manage** view installs and runs full Linux desktops on-device via PRoot, with no root. Pick a distro — **Alpine** (APK), **Debian 12** (APT), **Arch Linux ARM** (PACMAN), or **Void** (XBPS) — and install them side-by-side; each carries its native package manager. For each installed distro you can install, start, and stop desktop environments, open a shell into it, and read a Room-backed install log that names the layer that broke if a package install fails.

Desktop environments:

- **X11 (via Xvnc)** — Xfce4 or Openbox, each on its own VNC port, viewed through the in-app VNC client.
- **Nested Wayland (via wayvnc)** — a headless wlroots compositor inside the rootfs, surfaced over VNC. **Sway** is the supported, working option. Hyprland and niri are offered but GPU-limited: their renderers (aquamarine / smithay) are GLES-only with no software fallback, and the Android GPU isn't driveable by Mesa inside PRoot, so they can't currently initialise a backend (tracked on #162). For a GPU-accelerated local desktop, use the Native Wayland Desktop above.

## Desktop (RDP)

Remote Desktop Protocol client built on [IronRDP](https://github.com/Devolutions/IronRDP) via UniFFI Kotlin bindings. Connects to Windows Remote Desktop, xrdp (Linux), and GNOME Remote Desktop. **EGFX (MS-RDPEGFX) graphics-pipeline support** — ClearCodec and RemoteFX Progressive decoders light up the fast graphics path on modern Windows (verified against Windows Server 2025), with a slow-path fallback for servers that don't negotiate it. Pinch-to-zoom, pan, keyboard with scancode mapping, mouse input. SSH tunnel support with auto-connect through saved SSH profiles. Saved connection profiles with optional stored password.

## Files

Unified file browser with SFTP, SMB, and cloud storage tabs. Browse remote directories, upload files or entire folders, download, delete, rename, create directories, copy path, toggle hidden files, sort by name/size/date. **Multi-select** — long-press or tap **Select** to enter selection mode with a contextual action bar (copy, cut, permissions, delete). **Permissions editor** — octal field plus a 3×3 rwx checkbox grid, supported on SFTP/SCP/local (not SMB/rclone). **Built-in text editor** with syntax highlighting, find/replace, and terminal-matched theme. **Image tools** — view, crop, rotate, perspective-correct. **Cross-filesystem copy/move** — copy files between any backends (e.g. Google Drive → SFTP server) with clipboard model: long-press → Copy/Cut, switch tab, Paste. Conflict resolution (skip/replace) for existing files. Path preserved when switching between tabs.

## Media Convert

Convert media files between formats directly on-device using a custom [FFmpeg 8.0](https://ffmpeg.org) build with the full codec/format/filter set. Long-press any media file and tap Convert. Separate dropdown selectors for container format (MP4, MKV, WebM, MOV, AVI, MPEG-TS, MP3, WAV, OGG, Opus, FLAC, M4A), video encoder (H.264, H.265, VP9, VP8, MPEG-4, stream copy), and audio encoder (AAC, MP3, Opus, Vorbis, FLAC, PCM, FLAC, stream copy). **Copy-remux by default** — container auto-matches the source extension so tapping Convert on most files gives an instant lossless remux. **Frame preview** — see filter effects on a single frame before committing, with seek slider and tap-to-fullscreen. **Audio preview** — play a 5-second clip with current filters applied. Video filters: brightness, contrast, saturation, gamma, sharpen, denoise, stabilize (deshake), auto color correction, speed, rotation. Audio filters: volume, loudness normalization (EBU R128). One-tap presets (Stabilize, Fix Colors, Enhance, Normalize Audio). Live CLI preview shows the exact ffmpeg command being built. Audio-only files auto-detected — video UI hidden, only audio formats shown. **Save to** picker: Downloads folder or back to the source folder (uploads to cloud/SFTP/SMB with live progress). **Works on cloud files without downloading** — for rclone profiles, ffmpeg streams the source over HTTP via the rclone VFS so transcode starts in seconds regardless of file size (falls back to full download for offline/reliability via a toggle). **HLS streaming** — stream any local or rclone media file to other devices on the network via an HTML5 player; URL auto-copied to clipboard and opened at the device's LAN IP for easy sharing.

## Cloud Storage

Browse, upload, download, and manage files on 60+ cloud providers via [rclone](https://rclone.org) — Google Drive, Dropbox, OneDrive, Amazon S3, Backblaze B2, and more. OAuth authentication with automatic browser flow. Server-side copy between cloud remotes (no temp file needed). **Share link** — generate public URLs for files on supported backends. **Folder size** — fast recursive size calculation. **Folder sync** — copy, mirror, or move between remotes with include/exclude filters, size limits, bandwidth throttling, and dry-run preview. **Media streaming** — stream audio/video to VLC or any player via local HTTP server with M3U playlists and seeking. **DLNA server** — stream cloud media to smart TVs and Chromecast on the local network.

## SSH Keys

Generate Ed25519, RSA, and ECDSA keys on-device. Import keys from file (PEM/OpenSSH/Dropbear format) or paste from clipboard. FIDO2/SK hardware key support (ed25519-sk, ecdsa-sk) via NFC or USB security keys. One-tap public key copy and deploy key dialog for `authorized_keys` setup. Assign specific keys to individual connections.

## SMB

Browse Windows/Samba file shares with optional SSH tunneling for secure access over the internet.

## Connections

Saved profiles with transport selection (SSH, Mosh, Eternal Terminal, VNC, RDP, SMB, Cloud Storage, Reticulum), host key TOFU verification, fingerprint change detection, auto-reconnect with backoff, password fallback, local/remote/**dynamic** port forwarding (-L/-R/-D — the dynamic type runs a built-in SOCKS5 proxy that tunnels traffic through the SSH session), ProxyJump multi-hop tunneling (-J) with tree view, SOCKS5/SOCKS4/HTTP proxy support (Tor .onion compatible), RDP-over-SSH tunnel profiles, DNS resolution with 5s timeout, and connection error safety nets (20s UI watchdog, post-connect shell verification, session manager detection).

### Port knocking

Optional per-profile knock sequence sent immediately before the real socket open. Format is whitespace- or comma-separated `port[/proto]` tokens — e.g. `7000 8000 9000` (all TCP) or `7000/tcp 8000/udp 9000/tcp` for mixed sequences that match a `knockd`/`fwknop`-style configuration. The sequence is fired from the device using ordinary TCP `Socket.connect()` and `DatagramSocket.send()` calls (no root, no raw sockets), with a configurable inter-knock delay (default 100 ms) and a fixed 200 ms post-knock settle so the firewall has time to install its rule before SSH/VNC/RDP/SMB connects. Wired into all direct-dial paths; skipped on SSH-tunneled, WireGuard/Tailscale, and SOCKS-routed paths since the knock packet wouldn't reach the right firewall from the device. Each knock attempt — success or failure — appears in the Connection Log entry's verbose output as a `[knock] ... -> ok in Nms` line so post-hoc debugging is possible. The connection-edit dialog includes a **Test knock** button that runs the sequence once without committing or connecting, returning the result inline.

### Single Packet Authorization (fwknop)

The cryptographic alternative to port knocking, interoperable with [fwknop](https://github.com/mrash/fwknop)/`fwknopd`. Where a knock sequence is a series of cleartext SYNs that a passive observer can record and replay in order, SPA is a single encrypted, HMAC-authenticated UDP packet — it can't be read, and the per-packet HMAC plus random/timestamp fields defeat replay. yySlide builds the packet itself, natively in Kotlin (no `fwknop` binary), and sends it on the same pre-connect hook as the knock; if both are configured the SPA goes first.

The implementation follows the fwknop wire format: an SHA-256-digested message encrypted with AES-256-CBC (OpenSSL `Salted__` / EVP-BytesToKey-MD5 key derivation) and authenticated with encrypt-then-MAC HMAC-SHA256 — the modern fwknop defaults. The Kotlin builder is verified byte-for-byte against `fwknop 2.6.11`, and a live `fwknopd` accepts yySlide's packets and opens the firewall. Per profile you set the Rijndael key and (recommended) HMAC key — each as a passphrase or base64 (`--key-base64-rijndael` / `--key-base64-hmac`) — the access spec (`tcp/22`, or comma-separated for multiple ports), the destination UDP port (default 62201), and the allow-IP mode: **Source IP** (sends `0.0.0.0`, so `fwknopd` opens for the packet's own source IP — the right default for a phone behind changing/CGNAT addresses, with no extra round-trip), **Resolve public IP** (looks up the egress IP through `https://api.ipify.org`, like `fwknop -R`), or **Explicit IP**. Choosing **Resolve public IP** sends the device's public IP address and ordinary request metadata to ipify; use Source IP or Explicit IP if you do not want that lookup. Like the knock it runs only on direct-dial paths, logs a `[spa] ... -> sent NB in Nms` line to the Connection Log, and has a **Test SPA** button that sends one packet without committing or connecting. Keys are stored encrypted at rest (Android Keystore-backed AEAD), and the `set_spa`/`test_spa` MCP tools never echo key material back. v1 is AES-CBC + HMAC-SHA256 over UDP only — no GPG mode, alternate cipher/digest modes, or NAT-access.

## Local Shell (PRoot)

Run a real Linux terminal directly on your phone, no root required. Select "Local Shell (PRoot)" when creating a connection and yySlide downloads a minimal [Alpine Linux](https://alpinelinux.org/) rootfs (~4 MB) on first use, giving you a full `apk` package manager — install Python, Node.js, git, build tools, or anything in Alpine's [package repository](https://pkgs.alpinelinux.org/packages). Beyond Alpine, the Desktop → Manage view can install **Debian 12** (`apt`), **Arch Linux ARM** (`pacman`), and **Void** (`xbps`) rootfs side-by-side, each with its own package manager and a one-tap shell.

PRoot works by intercepting system calls in userspace (no kernel modifications), so it runs on **any unrooted Android device**. It does not require or use root access — the name "PRoot" stands for "ptrace-based root", meaning it *emulates* a root filesystem without actual superuser privileges. Think of it as a lightweight container that runs entirely within yySlide's app sandbox.

How it compares to alternatives:

- **Rooted phones (Magisk/su)**: Root gives full system access. PRoot is sandboxed — it can't modify your system, but it also doesn't need root to work.
- **[Android Terminal VM](https://developer.android.com/studio/run/managing-avds)** (Pixel 8+): Google's official Linux VM runs a full kernel via [pKVM](https://source.android.com/docs/core/virtualization). It's more capable but only available on Pixel 8 and newer. PRoot runs on any device back to Android 8. yySlide can SSH into an Android Terminal VM if you have one — see the connection settings.
- **[Termux](https://termux.dev/)**: A standalone terminal emulator with its own package ecosystem. PRoot is lighter (4 MB vs ~100 MB) and integrated into yySlide alongside your SSH/cloud sessions.

See [PRoot documentation](https://proot-me.github.io/) for technical details.

## USB devices (to the agent and the Linux guest)

On an unrooted Android phone, only an app holding the runtime USB permission can
open a device — `/dev/bus/usb` is denied to both the proot guest and the adb
shell. yySlide turns that into a feature: it **brokers** the USB device through
Android's `UsbManager` and re-exposes it two ways, with no root.

- **To the agent (MCP):** `list_usb_devices`, `request_usb_permission`,
  `usb_control_transfer`, and `usb_bulk_transfer` let an AI agent enumerate the
  attached USB/OTG devices and run raw control/bulk transfers. Each device-open
  and transfer asks for consent.
- **To the Linux guest:** with **Settings → "Expose USB devices to the Linux
  guest"** turned on (off by default), `usb_attach_to_guest` opens the device and
  binds a small userspace USB proxy on a socket the proot can reach. A bundled
  `haven-usb` shim (an upstream-compatible internal component name) then makes
  the device appear inside the guest as a normal
  `/dev/hidraw*` HID node:
  - **native Linux apps** via `LD_PRELOAD` (the shim interposes
    `open`/`ioctl`/`read`/`write`);
  - **Mono / .NET apps** (anything built on HidSharp) via a Mono **DllMap**
    config — the shim fakes the `libudev` enumeration and a non-crashing hotplug
    monitor and routes the HID I/O over the proxy. `usb_attach_to_guest` returns
    the ready-to-use config to drop beside the app's `HidSharp.dll`.

All USB I/O stays in the Android layer (`UsbDeviceConnection`), so the guest
never needs a real device node or root. The shim is built for both glibc and
musl, so it works the same on every distro yySlide offers. The feature is
HID/hidraw-focused today. This is the same pattern as the rest of yySlide's bridge
work: when the guest can't reach a capability directly, yySlide brokers the
Android privilege and re-exposes it (see [Vision](../VISION.md)).

## Reticulum

Connect over [Reticulum](https://reticulum.network) mesh networks with native Kotlin transport (reticulum-kt + rnsh-kt). Two-way terminal sessions over IFAC-protected TCP gateways, announce-based rnsh node discovery via scan button, configurable IFAC network name and passphrase. No Python runtime or Chaquopy dependency — pure Kotlin implementation with Flow-based I/O.

## Agent transport (MCP)

An optional local-loopback **MCP** (Model Context Protocol) server exposes yySlide's read and write surfaces as tools, so an AI agent can drive the same primitives a human taps — and the user watches every action happen in the same UI. Disabled by default; toggled under **Settings → Agent endpoint**. ~80 tools span connections and sessions, the unified file browser, terminal I/O, media convert/stream, port forwards and tunnels, rclone sync, and the full multi-distro PRoot + desktop lifecycle (`install_distro`, `install_desktop`, `start_desktop`, `read_desktop_log`, …). Every write action surfaces a non-skippable consent prompt before it runs, and every call is recorded to an in-app audit log. The endpoint binds loopback only; a one-tap "Tunnel through SSH profile…" shortcut adds a reverse forward so an MCP client on your workstation reaches yySlide over an existing SSH session.

## Security

Screen lock with biometric or device PIN/password/pattern, configurable timeout (immediate/30s/1m/5m/never), no telemetry or analytics, local storage only. Keyboard security: all credential fields set `IME_FLAG_NO_PERSONALIZED_LEARNING` to prevent keyboard apps from recording passwords, with a warning when the active keyboard has internet access. Encrypted backup/restore with AES-256-GCM. See the [privacy policy](privacy-policy.html).
