# yySlide Privacy Overview

Last updated: 2026-07-27

This overview covers the yySlide Windows client, Android client, project
website, and optional yySlide Cloud service. The Android app-store policy in
`android/yyslide-android/PRIVACY_POLICY.md` provides additional Android-specific
detail.

yySlide does not include advertising, usage analytics, tracking, or automatic
crash-reporting SDKs. The static project website does not set analytics or
advertising cookies. Clients store connection profiles, credentials, settings,
logs, and user-selected files locally as needed for the features you use.

The clients connect to hosts and providers you configure or explicitly use,
including SSH and remote-desktop hosts, storage providers, tunnels, certificate
services, download mirrors, and an optional compatible yySlide Cloud server.
Those operators can receive the protocol data needed for the operation and
ordinary network metadata such as IP address, time, and traffic volume.

Cloud synchronization is opt-in. Supported configuration data is encrypted on
the client before upload; the server receives the encrypted vault, account and
authentication records, synchronization revisions/events, and ordinary server
logs. The default public service is `https://yyslide.ccwu.cc`, and clients can
be configured to use another compatible server.

When you explicitly choose SPA **Resolve public IP**, yySlide uses
`https://api.ipify.org`. On Android, ipify receives the device's public IP and
ordinary request metadata. On Windows, the lookup is executed through the
selected SSH host, so ipify receives that remote host's public IP and request
metadata. The result is used to construct the SPA request; no lookup occurs in
Source IP or Explicit IP modes.

Optional Android Linux-environment installs fetch Alpine artifacts from
`dl-cdn.alpinelinux.org` and Debian, Arch, or Void root filesystems from release
assets published through `github.com/termux/proot-distro`. User-opened external
links and user-configured providers are governed by their respective privacy
practices.

Data exported, copied, transferred, or shared at your request is governed by
the receiving app, host, or provider. No software or network service can
guarantee absolute security; review host identities and certificates, protect
credentials, avoid sharing sensitive logs, and remove data you no longer need.

For privacy questions or deletion help for the default yySlide Cloud service,
open an issue at <https://github.com/wuyan1337/yySlide/issues>. Never include
passwords, private keys, access tokens, TOTP secrets, or sensitive logs in a
public issue.
