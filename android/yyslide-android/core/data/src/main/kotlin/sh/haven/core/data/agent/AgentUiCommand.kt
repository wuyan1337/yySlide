package sh.haven.core.data.agent

/**
 * UI-level commands the agent transport can post to drive the user's
 * existing surfaces. Distinct from [ConsentRequest] — these are
 * tap-equivalent navigation / state-promotion actions that reveal the
 * same UI the user already has access to. The agent layer publishes,
 * the UI layer collects (`HavenNavHost`, [sh.haven.feature.sftp.SftpViewModel]).
 *
 * Sealed so the collector exhaustively handles every variant. Adding a
 * new verb is intentionally a multi-site change: a new variant here, a
 * matching MCP tool, and a collector branch in whichever screen owns
 * the surface.
 */
sealed class AgentUiCommand {
    /**
     * Open the SFTP file browser for [profileId] at [path]. The
     * collector switches the pager to the SFTP page; the
     * [sh.haven.feature.sftp.SftpViewModel] reacts in parallel by
     * selecting the profile (if not already active) and calling
     * `navigateTo(path)`.
     *
     * Tap-equivalent: same effect as the user tapping the SFTP tab and
     * entering the path manually. [path] is interpreted by whichever
     * backend the profile resolves to — POSIX absolute for SSH and
     * Local, share-relative for SMB, remote-relative for rclone.
     */
    data class NavigateToSftpPath(
        val profileId: String,
        val path: String,
    ) : AgentUiCommand()

    /**
     * Bring the terminal tab for [sessionId] to the front. The
     * collector switches the pager to the Terminal page; the
     * [sh.haven.feature.terminal.TerminalViewModel] finds the matching
     * tab by sessionId and calls `selectTab(index)`.
     *
     * Tap-equivalent: same effect as the user tapping the Terminal tab
     * and tapping the right session header. No effect when no tab
     * matches — agents discover live sessionIds via `list_sessions`,
     * so a stale ID drops silently.
     */
    data class FocusTerminalSession(
        val sessionId: String,
    ) : AgentUiCommand()

    /**
     * Stage a conversion job in the SFTP screen's convert dialog with
     * the given form-field defaults. The user reviews and taps Convert
     * to actually run ffmpeg — the agent suggests, the user confirms.
     * Tap-equivalent because the destructive action (transcode) still
     * requires the user's tap on the dialog's Convert button.
     *
     * Any of [container] / [videoEncoder] / [audioEncoder] may be null,
     * in which case the dialog uses its existing defaults (extension-
     * based for container, "copy" for encoders). The dialog's audio-
     * only auto-correct still runs, so a video container suggested for
     * an audio-only source self-corrects on probe.
     *
     * VISION.md §1a names this verb explicitly as the example for
     * cross-tab agent-driven UI — opening a primitive's dialog with
     * prefilled args.
     */
    data class OpenConvertDialog(
        val profileId: String,
        val sourcePath: String,
        val container: String? = null,
        val videoEncoder: String? = null,
        val audioEncoder: String? = null,
    ) : AgentUiCommand()

    /**
     * Open a new terminal tab for [profileId]. The transport (SSH /
     * Mosh / ET / Reticulum / Local) derives from the profile's
     * `connectionType` and flags — this verb is profile-shaped, not
     * transport-shaped, mirroring how the user opens a terminal by
     * tapping the profile row.
     *
     * Used by the workspace launcher to materialise terminal items.
     * Same constraint as [NavigateToSftpPath]: collector must be a live
     * `TerminalViewModel`. Drops silently otherwise.
     */
    data class OpenTerminalSession(
        val profileId: String,
    ) : AgentUiCommand()

    /**
     * Open a new remote-desktop tab for [profileId]. The kind (VNC /
     * RDP) derives from the profile's `connectionType`. Wayland tabs go
     * through [OpenWaylandDesktop] instead since they have no profile.
     *
     * Collector is `DesktopViewModel`, which is nav-scoped, so emissions
     * always land regardless of which tab the user is currently viewing.
     */
    data class OpenRemoteDesktop(
        val profileId: String,
    ) : AgentUiCommand()

    /**
     * Add a Wayland desktop tab. The compositor lifecycle stays lazy —
     * the tab is added but the compositor only starts on user
     * interaction, matching how `addWaylandTab` works for direct user
     * taps.
     */
    data object OpenWaylandDesktop : AgentUiCommand()

    /**
     * Re-mint a step-ca-signed SSH cert for [keyId]. Posted by
     * `MainActivity` when the user taps the "cert expiring soon"
     * notification (#133 phase 2b). HavenNavHost switches to the Keys
     * tab; KeysViewModel collects the same bus, finds the key by id,
     * and runs the OIDC + sign flow against the same CA the original
     * cert was minted from.
     *
     * Tap-equivalent: same effect as the user opening the key row's
     * overflow menu and choosing Regenerate.
     */
    data class RegenerateStepCaCert(
        val keyId: String,
    ) : AgentUiCommand()

    /**
     * Open [path] (under [profileId]) in Haven's text editor. The
     * collector switches the pager to the SFTP page; SftpViewModel
     * selects the profile if needed, navigates to the parent directory,
     * then calls `openInEditor` with a synthetic entry for [path].
     *
     * Tap-equivalent: same effect as the user navigating to the file's
     * parent directory and tapping the file row's "Open in editor"
     * action.
     */
    data class OpenInEditor(
        val profileId: String,
        val path: String,
    ) : AgentUiCommand()

    /**
     * Initiate a connection for a saved profile. Picked up by
     * [ConnectionsViewModel], which dispatches to the right transport
     * (SSH / Mosh / ET / VNC / RDP / SMB / rclone / Reticulum / local)
     * using the same code path a UI tap would, so route-through,
     * stored passwords, and key auth all apply identically.
     *
     * Used by the MCP `connect_profile` tool — the only sanctioned
     * way for the agent to drive a connect, since this re-uses the
     * existing UI password-prompt fallback when neither a stored
     * password nor a key is configured.
     */
    data class ConnectProfile(
        val profileId: String,
    ) : AgentUiCommand()
}
