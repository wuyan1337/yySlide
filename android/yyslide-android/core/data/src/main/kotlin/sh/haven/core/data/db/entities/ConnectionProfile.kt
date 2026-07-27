package sh.haven.core.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "connection_profiles")
data class ConnectionProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val sshPassword: String? = null,
    val authType: AuthType = AuthType.PASSWORD,
    val keyId: String? = null,
    /**
     * Ordered list of auth methods to attempt in a single connect, encoded
     * one-per-token (newline-separated): `PASSWORD`, `KEY:<keyId>`, or
     * `KEYBOARD_INTERACTIVE`. Empty string = "derive from the legacy
     * [authType]/[keyId] fields" (pre-#166 profiles, before the 55→56
     * migration backfills them). Lets a profile satisfy multi-factor SSH
     * chains like `publickey,password` (#166). Parse via [authMethodSpecs].
     */
    @ColumnInfo(defaultValue = "")
    val authMethods: String = "",
    /**
     * When true, a TOTP auth element shows the keyboard-interactive dialog
     * with the generated code pre-filled for one-tap confirm/edit instead
     * of auto-submitting it (#178). Default false = auto-submit.
     */
    @ColumnInfo(defaultValue = "0")
    val totpConfirmBeforeSend: Boolean = false,
    val colorTag: Int = 0,
    val lastConnected: Long? = null,
    val sortOrder: Int = 0,
    val connectionType: String = "SSH",
    val destinationHash: String? = null,
    val reticulumHost: String = "127.0.0.1",
    val reticulumPort: Int = 37428,
    val jumpProfileId: String? = null,
    val sshOptions: String? = null,
    val vncPort: Int? = null,
    val vncUsername: String? = null,
    val vncPassword: String? = null,
    val vncSshForward: Boolean = true,
    val vncSshProfileId: String? = null,
    /**
     * VNC pixel format negotiated with the server. One of "BPP_24_TRUE"
     * (default), "BPP_16_TRUE", "BPP_8_TRUE", or "BPP_8_INDEXED". Lower
     * depths use less bandwidth on slow links — RealVNC uses 8-bit
     * indexed for mobile data, which is what users on 3 Mbps paths
     * need to make Haven usable (Nesos-ita, #107).
     */
    val vncColorDepth: String = "BPP_24_TRUE",
    val sessionManager: String? = null,
    val useMosh: Boolean = false,
    val useEternalTerminal: Boolean = false,
    val etPort: Int = 2022,
    val rdpPort: Int = 3389,
    val rdpUsername: String? = null,
    val rdpDomain: String? = null,
    val rdpPassword: String? = null,
    val rdpSshForward: Boolean = false,
    val rdpSshProfileId: String? = null,
    /**
     * Request Network Level Authentication (CredSSP) during the RDP
     * handshake. Default true. Turn off to force SSL-only security —
     * needed for some servers where ironrdp's CredSSP doesn't interop
     * (seen with Windows Server 2025 Datacenter on #109). The server
     * must have "Require Network Level Authentication" disabled for
     * SSL-only mode to connect.
     */
    val rdpUseNla: Boolean = true,
    /**
     * RDP colour depth in bits per pixel. Default 32 — required by
     * Windows when the EGFX `SUPPORT_DYN_VC_GFX_PROTOCOL` early-cap
     * flag is set (v5.24.69+). 16 worked against every server type
     * before EGFX, but Windows now TCP-FINs the connection mid-MCS
     * if the legacy color_depth in the GCC core data is `Bpp8`.
     *
     * Empirical matrix as of v5.24.69:
     *   16: Windows ✗ (server TCP-FIN after MCS Connect with EGFX
     *        flag set), xrdp ✓
     *   24: Windows ✗ (server resets connection), xrdp ✓
     *   32: Windows ✓ (EGFX path), xrdp untested visually under
     *        the patched ironrdp — pre-EGFX matrix said xrdp ✗
     *        (custom RLE).
     *
     * Migration 40_41 auto-bumps existing 16-valued profiles to 32
     * iff `rdpUseNla = 1`, since NLA-on strongly correlates with
     * Windows-class servers. Profiles with NLA off keep 16, since
     * those tend to be xrdp / Linux-RDP setups.
     */
    val rdpColorDepth: Int = 32,
    val smbPort: Int = 445,
    val smbShare: String? = null,
    val smbDomain: String? = null,
    val smbPassword: String? = null,
    val smbSshForward: Boolean = false,
    val smbSshProfileId: String? = null,
    val proxyType: String? = null,       // "SOCKS5", "SOCKS4", "HTTP", or null (none)
    val proxyHost: String? = null,
    val proxyPort: Int = 1080,
    val groupId: String? = null,
    /** Last session manager session name used (for group launch restore). */
    val lastSessionName: String? = null,
    /** Disable alternate screen buffer (DECSET 1049) so scrollback works in screen/vim. */
    val disableAltScreen: Boolean = false,
    /** rclone remote name (e.g. "gdrive"). */
    val rcloneRemoteName: String? = null,
    /** rclone provider type (e.g. "drive", "s3", "dropbox"). */
    val rcloneProvider: String? = null,
    /** Use native Android shell instead of PRoot for local connections. */
    val useAndroidShell: Boolean = false,
    /** Custom mosh-server command (overrides the default `mosh-server new -s -c 256 -l LANG=en_US.UTF-8`). */
    val moshServerCommand: String? = null,
    /** Enable SSH agent forwarding (OpenSSH `ForwardAgent`) — exposes non-encrypted stored SSH keys to the remote session. */
    val forwardAgent: Boolean = false,
    /**
     * Address-family preference for hostname resolution (#137).
     *  - "AUTO": dual-stack, system resolver picks (default).
     *  - "IPV4_ONLY": skip AAAA records, dial first A.
     *  - "IPV6_ONLY": skip A records, dial first AAAA.
     * Per-connection — networks with broken IPv4 vs broken IPv6 are
     * both real cases (pannal, #137 thread).
     */
    val addressFamily: String = "AUTO",
    /** IFAC network name for Reticulum gateway isolation (maps to ifacNetname). */
    val reticulumNetworkName: String? = null,
    /** IFAC passphrase for Reticulum gateway isolation (maps to ifacNetkey). */
    val reticulumPassphrase: String? = null,
    /** Command to send automatically after SSH login (e.g. "cd /app && clear"). */
    val postLoginCommand: String? = null,
    /** When true, post-login command runs before the session manager (default); when false, runs inside it. */
    val postLoginBeforeSessionManager: Boolean = true,
    /**
     * Preferred file-transfer transport for SSH profiles. "AUTO" (default)
     * tries SFTP and falls back to legacy SCP when the sftp-server subsystem
     * is unavailable; "SFTP" forces SFTP; "SCP" forces legacy scp -t/-f.
     * Ignored for non-SSH profiles.
     */
    val fileTransport: String = "AUTO",
    /**
     * Optional id of a [TunnelConfig] to route this profile's connection
     * through (per-app WireGuard / Tailscale — see #102). Null means
     * "connect directly via the system network".
     */
    val tunnelConfigId: String? = null,
    /**
     * Per-profile terminal colour-scheme override (#144). Null = inherit
     * the user's global preference. Non-null = the name of one of the
     * `UserPreferencesRepository.TerminalColorScheme` enum entries
     * (e.g. "DRACULA", "NORD"). Lets users distinguish servers at a
     * glance instead of relying on the tab-colour dot alone. Unknown
     * names fall back to inherit so an old client reading a future
     * scheme doesn't error.
     */
    val terminalColorScheme: String? = null,
    /**
     * Per-profile auto-reconnect controls (#150). Today the SSH session
     * manager auto-reconnects unconditionally on transport drop with a
     * hard-coded 5-attempt cap. These three columns expose that policy
     * to users:
     *
     *  - [autoReconnect] gates the on-disconnect retry loop. Default
     *    true preserves existing behaviour.
     *  - [reconnectMaxAttempts] caps the loop. 0 means unlimited —
     *    useful for tunnel-only profiles holding port forwards alive.
     *  - [reconnectOnNetworkChange] gates the NetworkMonitor-driven
     *    "WiFi/cellular flip → reconnect" path independently. Some
     *    users want on-disconnect reconnect but not the network-flip
     *    one (and vice versa).
     */
    val autoReconnect: Boolean = true,
    val reconnectMaxAttempts: Int = 5,
    val reconnectOnNetworkChange: Boolean = true,
    /**
     * Tunnel-only mode (#150 Phase B). When true, a connect to this
     * profile brings up the SSH transport and registers the
     * configured port-forward rules but DOES NOT allocate a PTY or
     * open a terminal tab. The session lives in the background
     * carrying just the forwards — equivalent to `ssh -N -L … host`.
     *
     * Defaults to false so existing profiles keep their interactive
     * behaviour. Compose with autoReconnect + maxAttempts=0 to get
     * autossh-style indefinite-keepalive port forwards.
     *
     * Only meaningful for SSH-family profiles. Ignored on
     * VNC/RDP/SMB/RCLONE/RETICULUM/LOCAL.
     */
    val tunnelOnly: Boolean = false,
    /**
     * Optional port-knock sequence sent before the real socket open.
     * Format: whitespace- or comma-separated `port[/proto]` tokens, e.g.
     * `"7000 8000 9000"` (all TCP) or `"7000/tcp 8000/udp 9000/tcp"`.
     * Empty / null = knocking disabled. Parsed by `KnockSequence.parse`.
     * Only meaningful for protocols with a remote TCP host (SSH, Mosh,
     * ET, VNC, RDP, SMB) — ignored for LOCAL/RCLONE/RETICULUM.
     */
    val portKnockSequence: String? = null,
    /**
     * Delay between knock packets, in milliseconds. Matches `knockd`'s
     * default `seq_timeout` window — too short and the firewall may
     * mis-order the packets; too long and `seq_timeout` expires.
     */
    val portKnockDelayMs: Int = 100,
    /**
     * fwknop Single Packet Authorization (#156) — the cryptographic
     * alternative to port knocking. When [spaKey] and [spaAccessSpec] are
     * both set, Haven sends one AES-encrypted, HMAC-authenticated UDP packet
     * before the real connect (same `preConnect` hook as the knock). Parsed
     * by `SpaConfig.parse`; empty [spaKey]/[spaAccessSpec] = SPA disabled.
     *
     * [spaKey] is the Rijndael/AES key (encrypted at rest like passwords).
     */
    val spaKey: String? = null,
    /** When true, [spaKey] is base64 (fwknop `--key-base64-rijndael`); else a passphrase. */
    val spaKeyBase64: Boolean = false,
    /** Optional HMAC-SHA256 key (encrypt-then-MAC). Empty/null = legacy no-HMAC mode. Encrypted at rest. */
    val spaHmacKey: String? = null,
    /** When true, [spaHmacKey] is base64 (fwknop `--key-base64-hmac`); else a passphrase. */
    val spaHmacKeyBase64: Boolean = false,
    /** Access request after the allow-IP: `proto/port` token(s), e.g. `"tcp/22"`. */
    val spaAccessSpec: String? = null,
    /** Allow-IP strategy: `SOURCE` (0.0.0.0, default), `RESOLVE` (public IP), or `EXPLICIT`. */
    val spaAllowMode: String = "SOURCE",
    /** Fixed allow-IP/CIDR when [spaAllowMode] is `EXPLICIT`. */
    val spaExplicitIp: String? = null,
    /** Destination UDP port for the SPA packet (fwknopd default 62201). */
    val spaPort: Int = 62201,
) {
    enum class AuthType {
        PASSWORD,
        KEY,
    }

    /**
     * Parsed [authMethods], in attempt order. Falls back to a one-element
     * list derived from the legacy [authType]/[keyId] when [authMethods] is
     * blank (pre-#166 rows the migration hasn't touched, or in-memory
     * profiles built without setting it).
     */
    val authMethodSpecs: List<AuthMethodSpec>
        get() = AuthMethodSpec.parseList(authMethods).ifEmpty {
            listOf(
                when (authType) {
                    AuthType.KEY -> AuthMethodSpec.Key(keyId)
                    AuthType.PASSWORD -> AuthMethodSpec.Password
                },
            )
        }

    /**
     * One auth method in a connect attempt. Serialised one-per-line via
     * [serialize]; the ordered list satisfies multi-factor SSH chains
     * (e.g. `publickey,password`) in a single connect. (#166)
     */
    sealed interface AuthMethodSpec {
        fun serialize(): String

        /** Static password (stored in [ConnectionProfile.sshPassword]). */
        data object Password : AuthMethodSpec {
            override fun serialize() = "PASSWORD"
        }

        /** Public-key auth using the saved key [keyId] (null = any usable key). */
        data class Key(val keyId: String?) : AuthMethodSpec {
            override fun serialize() = if (keyId.isNullOrEmpty()) "KEY" else "KEY:$keyId"
        }

        /** Interactive PAM prompts (OTP etc.) — answered live, never stored. */
        data object KeyboardInteractive : AuthMethodSpec {
            override fun serialize() = "KEYBOARD_INTERACTIVE"
        }

        /**
         * OATH-TOTP auto-fill (#178): the keyboard-interactive "Verification
         * code:" prompt is answered from the stored TOTP secret [secretId]
         * (null = any single stored secret). Like [KeyboardInteractive] this
         * is handled by the live prompter, not registered as a JSch
         * credential — so it contributes no entry to the resolved auth list.
         */
        data class Totp(val secretId: String?) : AuthMethodSpec {
            override fun serialize() = if (secretId.isNullOrEmpty()) "TOTP" else "TOTP:$secretId"
        }

        companion object {
            fun parseList(text: String?): List<AuthMethodSpec> =
                text?.lineSequence()
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.mapNotNull(::parse)
                    ?.toList()
                    .orEmpty()

            fun serializeList(specs: List<AuthMethodSpec>): String =
                specs.joinToString("\n") { it.serialize() }

            private fun parse(token: String): AuthMethodSpec? = when {
                token == "PASSWORD" -> Password
                token == "KEYBOARD_INTERACTIVE" -> KeyboardInteractive
                token == "KEY" -> Key(null)
                token.startsWith("KEY:") -> Key(token.removePrefix("KEY:").ifEmpty { null })
                token == "TOTP" -> Totp(null)
                token.startsWith("TOTP:") -> Totp(token.removePrefix("TOTP:").ifEmpty { null })
                else -> null
            }
        }
    }

    /** Typed view of [fileTransport]. Unknown values fall back to [FileTransport.AUTO]. */
    enum class FileTransport { AUTO, SFTP, SCP }

    val fileTransportEnum: FileTransport
        get() = runCatching { FileTransport.valueOf(fileTransport) }.getOrDefault(FileTransport.AUTO)

    enum class AddressFamily { AUTO, IPV4_ONLY, IPV6_ONLY }

    val addressFamilyEnum: AddressFamily
        get() = runCatching { AddressFamily.valueOf(addressFamily) }.getOrDefault(AddressFamily.AUTO)

    val isSsh: Boolean get() = connectionType == "SSH"
    val isReticulum: Boolean get() = connectionType == "RETICULUM"
    val isMosh: Boolean get() = isSsh && useMosh
    val isEternalTerminal: Boolean get() = isSsh && useEternalTerminal
    val isVnc: Boolean get() = connectionType == "VNC"
    val isRdp: Boolean get() = connectionType == "RDP"
    val isSmb: Boolean get() = connectionType == "SMB"
    val isLocal: Boolean get() = connectionType == "LOCAL"
    val isRclone: Boolean get() = connectionType == "RCLONE"
    val isDesktop: Boolean get() = isVnc || isRdp
    val isTerminal: Boolean get() = !isDesktop && !isSmb && !isRclone
}
