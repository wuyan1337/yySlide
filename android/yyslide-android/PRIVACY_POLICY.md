# yySlide Privacy Policy

Last updated: 2026-07-27

This policy applies to the yySlide Android application (`com.yyslide.android`). yySlide is a remote-access, file-transfer, local Linux, cloud-storage, and optional encrypted-sync client. It does not include advertising, analytics, tracking, or automatic crash-reporting SDKs. It does make the network connections described below when you enable or use the corresponding features.

## Data stored on your device

Depending on the features you use, yySlide stores the following in Android app-private storage:

- Connection profiles, groups, host names, ports, user names, proxy/tunnel settings, known-host fingerprints, workspaces, and bookmarks.
- Credentials and security material you provide or generate, such as passwords, SSH private keys and certificates, TOTP secrets, cloud-provider tokens, and step-ca configuration. Android Keystore-backed encryption or biometric protection is used where the relevant feature supports it.
- App preferences, yySlide Cloud server address, encrypted authentication token and vault key, and synchronization state.
- Local connection history, MCP consent/audit records, installation logs, and optional verbose protocol logs. Verbose logs can contain server messages, paths, and other session details, so enable and share them carefully.
- Files you explicitly download, cache, edit, export, or import, including optional Linux root filesystems, fonts, and media output.

Android's app sandbox normally prevents other apps from reading this data. Clearing yySlide's storage or uninstalling the app removes its local private data, subject to Android backup/device-management behavior. Files you exported to shared storage are not removed automatically.

## Network activity

yySlide connects only as needed for a feature you choose or configure. Those connections can include:

- SSH, Mosh, Eternal Terminal, SFTP/SCP, SMB, VNC, RDP, Reticulum, Tor, WireGuard, Tailscale/Headscale, Cloudflare Access, step-ca, port-knocking/SPA, and other user-configured hosts or tunnels.
- Cloud-storage providers configured through rclone, such as Google Drive, Dropbox, OneDrive, S3-compatible services, or WebDAV. Each provider receives the requests and credentials needed for the operations you initiate and applies its own privacy policy.
- Downloads you request, including Linux distributions/root filesystems, packages, fonts, media components, and other resources from their respective project mirrors or a URL you provide. Optional Linux-environment installs fetch Alpine artifacts from `dl-cdn.alpinelinux.org` and Debian, Arch, or Void root filesystems from release assets published through `github.com/termux/proot-distro`; those services receive ordinary request and network metadata.
- External links open only when you choose them and are then governed by the destination site's privacy practices.
- If you explicitly choose the SPA **Resolve public IP** mode, yySlide sends an HTTPS request to `api.ipify.org`. ipify receives your device's public IP address and ordinary network metadata and returns the detected address for the SPA request.
- Optional local-network discovery, which probes the local subnet when you explicitly start a scan.
- The optional MCP agent endpoint. It listens on device loopback by default, requires a per-install bearer token, and can be exposed through a reverse SSH or WireGuard tunnel only when you enable that configuration. Authorized MCP clients can receive data and perform consent-gated actions within the capabilities you enable.

Remote hosts and service operators can ordinarily observe connection metadata such as your IP address, time, destination account, and traffic volume, and may receive the content required by the protocol or operation. Their retention and privacy practices are outside yySlide's control.

## Optional yySlide Cloud synchronization

Cloud synchronization is off until you sign in. The default service address is `https://yyslide.ccwu.cc`, and you may enter a different compatible server.

When you sign in, yySlide sends the server address, user name, password, and optional TOTP code to the selected server over HTTPS for authentication. The password and TOTP code are not retained by the Android client after authentication. The client stores the returned authentication token and vault key locally using its credential-encryption helper.

When you synchronize, supported yySlide configuration data is encrypted on the device with AES-256-GCM before upload. The sync server receives the encrypted vault payload, account/authentication records, revision/event information needed for synchronization, and ordinary server logs/connection metadata; it is not sent the plaintext vault payload by the Android client. Signing out removes the local Cloud credentials but does not necessarily delete the remote account or encrypted vault. Remote retention and deletion are controlled by the selected server operator.

## Permissions and user actions

yySlide requests Android permissions only for enabled features, for example Internet/network access, notifications and foreground services for long-running sessions, biometric authentication, NFC/USB hardware-key or USB-device access, camera/photo picker for QR/image features, and user-selected file access. Android permission prompts and system pickers control access where applicable.

Backups, clipboard operations, file transfers, shares, and exports occur when you request them. Data you send to another app, remote host, storage provider, or MCP client is then governed by that recipient.

## Data sharing and third-party services

yySlide does not sell personal data and does not send usage analytics, advertising identifiers, or telemetry to the yySlide maintainers. It does not bundle advertising or third-party analytics/crash-reporting services. Data is shared with configured hosts and providers only as required to carry out the features you use, as described above. The built-in ipify request is made only when you select **Resolve public IP** for SPA; ipify's handling of that request is governed by its own privacy practices.

## Security and your choices

You can reduce data exposure by leaving Cloud sync and MCP disabled, avoiding verbose logs, using biometric/key protection, reviewing host keys and certificates, and removing profiles, credentials, logs, exported files, and remote service data you no longer need. No software or network service can guarantee absolute security.

## Open source and contact

yySlide is licensed under the GNU Affero General Public License v3.0. Source code is available at <https://github.com/wuyan1337/yySlide>.

For privacy questions or deletion help for the default yySlide Cloud service, open an issue at <https://github.com/wuyan1337/yySlide/issues>. Do not include passwords, private keys, access tokens, TOTP secrets, or sensitive logs in a public issue.

Material changes to this policy will be published in the repository and may be noted in release notes.
