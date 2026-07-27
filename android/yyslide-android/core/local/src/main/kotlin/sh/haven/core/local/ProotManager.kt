package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.haven.core.data.repository.ProotInstallLogRepository
import sh.haven.core.local.proot.Arch
import sh.haven.core.local.proot.DesktopCatalog
import sh.haven.core.local.proot.DesktopEnvironmentSpec
import sh.haven.core.local.proot.Distro
import sh.haven.core.local.proot.DistroCatalog
import sh.haven.core.local.proot.LaunchSpec
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.PackageOps
import sh.haven.core.local.proot.RootfsFormat
import sh.haven.core.local.proot.RootfsSource
import sh.haven.core.security.CredentialEncryption
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProotManager"
private const val PREF_ACTIVE_DISTRO_ID = "active_distro_id"

/**
 * Delete [root] and everything under it, even when in-proot package
 * scripts left directories read-only.
 *
 * Plain [File.deleteRecursively] can't unlink a file whose parent
 * directory lacks the write bit — e.g. Arch's `ca-certificates`
 * /`update-ca-trust` makes `etc/ca-certificates/extracted/cadir/`
 * mode 0555. That trapped a broken rootfs forever and broke the next
 * install (#174). So restore owner rwx on every directory first;
 * removing a file needs write on its *parent* dir, not on the file.
 *
 * `walkTopDown()` does not follow symlinks, so we never chmod or
 * descend through links pointing outside the rootfs; the delete pass
 * unlinks symlinks without following them.
 */
internal fun forceDeleteRecursively(root: File): Boolean {
    if (!root.exists()) return true
    root.walkTopDown()
        .onEnter { dir ->
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            dir.setWritable(true, false)
            true
        }
        .forEach { /* side effect is the onEnter chmod; files need no chmod to unlink */ }
    return root.deleteRecursively()
}

/**
 * Manages the PRoot binary and Alpine Linux rootfs.
 *
 * PRoot is bundled as libproot.so in jniLibs (extracted to nativeLibraryDir
 * by Android, executable on Android 14+).
 *
 * The Alpine rootfs is downloaded on first use (~3MB compressed) and
 * extracted to filesDir/proot/rootfs/alpine/.
 */
@Singleton
class ProotManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installLogRepository: ProotInstallLogRepository,
) {
    /**
     * Which phase of the OS install pipeline the state belongs to.
     * Used by [SetupState.Error] to attribute failures, so the UI
     * can render a "Bootstrap hook: void-xbps-bootstrap" chip
     * instead of an anonymous "Installation failed". The five
     * phases correspond 1:1 to the steps in [installRootfs].
     */
    enum class Phase {
        RootfsDownload,
        RootfsExtract,
        BootstrapHook,
        Baseline,
    }

    sealed class SetupState {
        data object NotInstalled : SetupState()
        data class Downloading(val progress: Int) : SetupState()
        data object Extracting : SetupState()
        /**
         * Post-extract setup — running distro post-extract hooks
         * (locale-gen on Arch, ca-cert refresh on Void) and the
         * baseline-package install (pacman -Sy / apt-get install
         * tmux etc.). Distinct from [Extracting] so the UI doesn't
         * mislabel the slow distro-prep phase as "extraction".
         */
        data class Initializing(val step: String) : SetupState()
        data object Ready : SetupState()
        /**
         * Failure attributed to a specific [Phase]. [logTail] is the
         * last ~1500 chars of the failing command's output — empty
         * for failures that aren't shell-exec related (e.g. network
         * error during download). The UI renders [phase] as a chip
         * and [logTail] in a scroll box.
         */
        data class Error(
            val phase: Phase,
            val message: String,
            val logTail: String = "",
        ) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.NotInstalled)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val prefs by lazy {
        context.getSharedPreferences("proot-manager", Context.MODE_PRIVATE)
    }

    private val _activeDistroId = MutableStateFlow(
        prefs.getString(PREF_ACTIVE_DISTRO_ID, null) ?: DistroCatalog.DEFAULT_ID,
    )

    /**
     * Currently-active distro id. Backed by SharedPreferences so
     * the user's choice survives app restarts. Defaults to
     * [DistroCatalog.DEFAULT_ID] (Alpine 3.21) on fresh installs.
     */
    val activeDistroIdFlow: StateFlow<String> = _activeDistroId.asStateFlow()
    val activeDistroId: String
        get() = _activeDistroId.value

    /**
     * Set the active distro. The id must resolve in [DistroCatalog].
     * Persists immediately; subsequent calls to [activeRootfsDir] /
     * [installRootfs] / DE operations route to the new distro.
     *
     * Setting this to a distro that has no rootfs installed yet is
     * fine — the next [installRootfs] call will download it.
     */
    fun setActiveDistroId(id: String) {
        require(DistroCatalog.lookup(id) != null) { "Unknown distro id: $id" }
        if (id == _activeDistroId.value) return
        prefs.edit().putString(PREF_ACTIVE_DISTRO_ID, id).apply()
        _activeDistroId.value = id
        // Re-evaluate ready state for the new active rootfs
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
        Log.d(TAG, "Active distro switched to $id")
    }

    /** The active [Distro] looked up from [DistroCatalog]. */
    val activeDistro: Distro
        get() = DistroCatalog.lookup(activeDistroId)
            ?: error("Unknown distro id: $activeDistroId")

    /**
     * Distros that are installed on this device — derived from the
     * filesystem rather than persisted state, so it's robust to
     * marker-file drift. A rootfs counts as installed if its
     * `bin/sh` is present at `proot/rootfs/<id>/`.
     */
    val installedDistros: List<Distro>
        get() = DistroCatalog.all.filter { distro ->
            val binSh = File(rootfsDirFor(distro.id), "bin/sh").toPath()
            java.nio.file.Files.exists(binSh, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }

    /**
     * Catalog entries the user could install: arch-compatible, not
     * already installed. Used by the distro picker's
     * "+ Add another distro" affordance.
     */
    val availableDistros: List<Distro>
        get() {
            val arch = Arch.current() ?: return emptyList()
            val installed = installedDistros.map { it.id }.toSet()
            return DistroCatalog.all.filter { distro ->
                distro.id !in installed && distro.rootfsSources.containsKey(arch)
            }
        }

    /**
     * Resolve the rootfs directory for a specific distro id.
     *
     * For the default Alpine distro, falls back to the legacy
     * `proot/rootfs/alpine/` path if [migrateLegacyAlpineDir] could
     * not perform the rename — keeps existing installs working
     * even when the migration is blocked.
     */
    fun rootfsDirFor(distroId: String): File {
        val expected = File(context.filesDir, "proot/rootfs/$distroId")
        if (distroId == DistroCatalog.DEFAULT_ID && !expected.exists()) {
            val legacy = File(context.filesDir, "proot/rootfs/alpine")
            if (legacy.exists()) return legacy
        }
        return expected
    }

    /** Rootfs directory for the active distro. */
    val activeRootfsDir: File
        get() = rootfsDirFor(activeDistroId)

    /**
     * Back-compat alias for [activeRootfsDir]. Kept for callers
     * that have not yet migrated to the distro-aware API.
     */
    @Deprecated(
        "Use activeRootfsDir or rootfsDirFor(distroId).",
        ReplaceWith("activeRootfsDir"),
    )
    internal val rootfsDir: File
        get() = activeRootfsDir

    val isRootfsInstalled: Boolean
        get() = java.nio.file.Files.exists(
            File(activeRootfsDir, "bin/sh").toPath(),
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )

    val prootBinary: String?
        get() {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val proot = File(nativeDir, "libproot.so")
            return if (proot.canExecute()) proot.absolutePath else null
        }

    val isReady: Boolean
        get() = prootBinary != null && isRootfsInstalled

    val hasAnyDesktopInstalled: Boolean
        get() = installedDesktops.isNotEmpty()

    fun isDesktopInstalled(de: DesktopEnvironment): Boolean =
        de in installedDesktops && File(activeRootfsDir, de.verifyBinary).exists()

    /** Compat alias — true if any DE is installed. */
    val isDesktopInstalled: Boolean
        get() = hasAnyDesktopInstalled

    /**
     * Legacy desktop-environment enum. Each entry exposes a
     * stable [id] and a parallel [spec] in [DesktopCatalog] — new
     * internals route through the spec (see DesktopManager); UI
     * call-sites still consume the enum until Phase 2 introduces a
     * distro picker.
     */
    enum class DesktopEnvironment(
        val id: String,
        val label: String,
        val packages: String,
        val verifyBinary: String,
        val startCommands: String,
        val sizeEstimate: String,
        val isWayland: Boolean = false,
        val isNative: Boolean = false,
        /** Hidden DEs are not shown in the Desktop Manager UI. */
        val hidden: Boolean = false,
    ) {
        OPENBOX(
            id = "openbox",
            label = "Openbox (VNC)",
            packages = "tigervnc openbox xterm xsetroot font-noto",
            verifyBinary = "usr/bin/openbox",
            startCommands = "xsetroot -solid '#2e3440'; openbox & xterm &",
            sizeEstimate = "~10MB",
        ),
        XFCE4(
            id = "xfce4",
            label = "Xfce4 (VNC)",
            packages = "tigervnc xfce4 xfce4-terminal dbus-x11 font-noto",
            verifyBinary = "usr/bin/startxfce4",
            startCommands = "xfwm4 & xfce4-panel & xfdesktop &",
            sizeEstimate = "~100MB",
        ),
        WAYLAND_NATIVE(
            id = "labwc-native",
            label = "Native Wayland",
            packages = "foot font-noto font-awesome adwaita-icon-theme " +
                "xkeyboard-config xwayland mesa-dri-gallium mesa-gbm mesa-gl " +
                "waybar fuzzel xfce4-terminal thunar mousepad htop dbus-x11",
            verifyBinary = "usr/bin/foot",
            startCommands = "",
            sizeEstimate = "~80MB",
            isWayland = true,
            isNative = true,
        ),

        // Phase 4 nested wlroots compositors — run inside the rootfs on
        // wlroots' headless backend and surface via wayvnc on the same
        // VNC port a Xvnc desktop would. `isWayland = true` so callers
        // that route around the X11 startup script (e.g. setupDesktop's
        // xstartup writer) treat them like the labwc native path. Unlike
        // labwc, these are NOT `isNative` — they live entirely inside
        // the rootfs with no JNI bridge.
        //
        // [packages] mirrors the APK list for backwards-compat with
        // call-sites that still consult the legacy string; the
        // authoritative per-family lists live on the parallel
        // [DesktopEnvironmentSpec.packagesPerFamily].
        SWAY(
            id = "sway",
            label = "Sway (nested Wayland)",
            packages = "sway wayvnc foot xkeyboard-config font-noto",
            verifyBinary = "usr/bin/sway",
            startCommands = "",
            sizeEstimate = "~60MB",
            isWayland = true,
        ),
        HYPRLAND(
            id = "hyprland",
            label = "Hyprland (nested Wayland)",
            packages = "hyprland wayvnc foot xkeyboard-config font-noto",
            verifyBinary = "usr/bin/Hyprland",
            startCommands = "",
            sizeEstimate = "~90MB",
            isWayland = true,
        ),
        NIRI(
            id = "niri",
            label = "Niri (nested Wayland, scrolling tile)",
            packages = "niri wayvnc foot noto-fonts",
            verifyBinary = "usr/bin/niri",
            startCommands = "",
            sizeEstimate = "~70MB",
            isWayland = true,
        );

        /**
         * Look up the parallel [DesktopEnvironmentSpec] in
         * [DesktopCatalog]. Each enum [id] is guaranteed to
         * resolve — the catalog is the source of truth.
         */
        val spec: DesktopEnvironmentSpec
            get() = DesktopCatalog.lookup(id)
                ?: error("No spec registered for DE id=$id")
    }

    enum class DesktopAddon(
        val label: String,
        val description: String,
        val packages: String,
        val sizeEstimate: String,
    ) {
        PANEL(
            label = "Panel",
            description = "Taskbar with clock and app launcher",
            packages = "waybar fuzzel dbus font-awesome font-noto adwaita-icon-theme",
            sizeEstimate = "~40MB",
        ),
        FILE_MANAGER(
            label = "File Manager",
            description = "Graphical file browser",
            packages = "thunar",
            sizeEstimate = "~10MB",
        ),
        APPS(
            label = "Desktop Apps",
            description = "Text editor, image viewer, media player",
            packages = "mousepad imv mpv",
            sizeEstimate = "~15MB",
        ),
        STARTER_PACK(
            label = "Starter Pack",
            description = "Panel + file manager + editor + terminal + browser + calculator",
            packages = "waybar fuzzel dbus thunar mousepad foot firefox gnome-calculator imv font-noto-emoji adwaita-icon-theme font-awesome",
            sizeEstimate = "~120MB",
        ),
    }

    /** Which add-ons are installed (persisted as a file in the rootfs). */
    val installedAddons: Set<DesktopAddon>
        get() {
            val marker = File(activeRootfsDir, "root/.haven-addons")
            if (!marker.exists()) return emptySet()
            return try {
                marker.readText().trim().lines().mapNotNull { line ->
                    try { DesktopAddon.valueOf(line) } catch (_: Exception) { null }
                }.toSet()
            } catch (_: Exception) { emptySet() }
        }

    /** Stored VNC password for desktop viewer (encrypted at rest via Tink/Android Keystore). */
    var storedVncPassword: String?
        get() {
            val file = File(activeRootfsDir, "root/.haven-vnc-password")
            if (!file.exists()) return null
            val stored = file.readText().trim().ifEmpty { return null }
            return try {
                CredentialEncryption.decrypt(context, stored)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt VNC password, treating as legacy plaintext", e)
                stored
            }
        }
        set(value) {
            val file = File(activeRootfsDir, "root/.haven-vnc-password")
            if (value != null) {
                file.writeText(CredentialEncryption.encrypt(context, value))
            } else {
                file.delete()
            }
        }

    /** All installed DEs on the active distro — verifyBinary on filesystem. */
    val installedDesktops: Set<DesktopEnvironment>
        get() = installedDesktopsFor(activeDistroId)

    /**
     * Installed DEs for a *specific* distro, detected from that distro's
     * own rootfs rather than the active one. Lets callers report which
     * desktops are installed on every distro without switching the active
     * distro first. Returns empty if the distro's rootfs isn't present.
     */
    fun installedDesktopsFor(distroId: String): Set<DesktopEnvironment> {
        val rootfs = rootfsDirFor(distroId)
        val binSh = File(rootfs, "bin/sh").toPath()
        if (!java.nio.file.Files.exists(binSh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return emptySet()
        }
        return DesktopEnvironment.entries.filter { de ->
            File(rootfs, de.verifyBinary).exists()
        }.toSet()
    }

    /** Compat alias — returns the first installed DE. */
    val installedDesktop: DesktopEnvironment?
        get() = installedDesktops.firstOrNull()

    /**
     * Which phase of the DE install pipeline the state belongs to.
     * Used by [DesktopSetupState.Error] to attribute failures so the
     * UI can distinguish "package install failed" (mirror flake,
     * xbps nested-chroot INSTALL script) from "VNC config failed"
     * (xstartup write, vncpasswd) from "marker file failed".
     */
    enum class DePhase {
        Packages,
        VncConfig,
        Marker,
    }

    sealed class DesktopSetupState {
        data object Idle : DesktopSetupState()
        data class Installing(val step: String) : DesktopSetupState()
        data object Complete : DesktopSetupState()
        /**
         * Failure attributed to a specific [DePhase]. [logTail] is
         * the last ~1500 chars of the failing command's output. The
         * UI renders [phase] as a chip and [logTail] in a scroll
         * box, so the user/maintainer can see which layer failed
         * without reading source.
         */
        data class Error(
            val phase: DePhase,
            val message: String,
            val logTail: String = "",
        ) : DesktopSetupState()
    }

    private val _desktopState = MutableStateFlow<DesktopSetupState>(DesktopSetupState.Idle)
    val desktopState: StateFlow<DesktopSetupState> = _desktopState.asStateFlow()

    init {
        migrateLegacyAlpineDir()
        reconcileActiveDistro()
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
    }

    /**
     * If the persisted active distro id points at a rootfs that
     * isn't installed (e.g. a previous addDistro failed mid-flight
     * and the revert-to-previous didn't fire), fall back to the
     * first installed distro — or to the default if none are
     * installed. Keeps users out of an "active but missing" hole
     * where the local shell silently falls back to Android shell.
     */
    private fun reconcileActiveDistro() {
        val current = _activeDistroId.value
        val currentInstalled = installedDistros.any { it.id == current }
        if (currentInstalled) return
        val fallback = installedDistros.firstOrNull()?.id ?: DistroCatalog.DEFAULT_ID
        if (fallback != current) {
            prefs.edit().putString(PREF_ACTIVE_DISTRO_ID, fallback).apply()
            _activeDistroId.value = fallback
            Log.d(TAG, "Active distro $current has no rootfs; falling back to $fallback")
        }
    }

    /**
     * One-shot migration for installs predating issue #162: rename
     * `proot/rootfs/alpine/ → proot/rootfs/alpine-3.21/` so the
     * directory layout matches the new distro-id scheme. No-op if
     * the legacy directory doesn't exist, or if the target already
     * exists, or if the legacy directory isn't a real rootfs
     * (bin/sh missing). If the rename fails for any reason the
     * fallback in [rootfsDirFor] keeps the legacy path usable.
     */
    private fun migrateLegacyAlpineDir() {
        val legacy = File(context.filesDir, "proot/rootfs/alpine")
        val target = File(context.filesDir, "proot/rootfs/${DistroCatalog.DEFAULT_ID}")
        if (!legacy.exists() || target.exists()) return
        val binSh = File(legacy, "bin/sh").toPath()
        if (!java.nio.file.Files.exists(binSh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        try {
            target.parentFile?.mkdirs()
            if (legacy.renameTo(target)) {
                Log.d(TAG, "Migrated legacy rootfs: alpine/ → ${DistroCatalog.DEFAULT_ID}/")
            } else {
                Log.w(TAG, "Legacy rootfs rename failed; falling back to alpine/ path")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy rootfs migration failed: ${e.message}")
        }
    }

    /**
     * Download and extract the rootfs for the active distro.
     * Safe to call if already installed — returns immediately.
     */
    suspend fun installRootfs() {
        if (isRootfsInstalled) {
            _state.value = SetupState.Ready
            return
        }

        // Track which phase is active so the outer catch can attribute
        // any thrown exception to the right step. Mutates as we move
        // through download → extract → hooks → baseline.
        var currentPhase: Phase = Phase.RootfsDownload
        val installStartedAt = System.currentTimeMillis()
        try {
            _state.value = SetupState.Downloading(0)

            val distro = activeDistro
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsDownload",
                message = "Starting download",
            )
            val arch = Arch.current()
                ?: throw IllegalStateException("Unsupported ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")
            val source = distro.rootfsSources[arch]
                ?: throw IllegalStateException(
                    "${distro.label} has no rootfs for $arch — supported: ${distro.rootfsSources.keys}"
                )
            val url = source.url
            val expectedSha256 = source.sha256
            val tarball = File(context.cacheDir, "${distro.id}-rootfs.tar")

            // Download
            withContext(Dispatchers.IO) {
                Log.d(TAG, "[download ${distro.id}] start url=$url")
                val conn = URL(url).openConnection()
                val totalSize = conn.contentLength
                BufferedInputStream(conn.getInputStream()).use { input ->
                    FileOutputStream(tarball).use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (totalSize > 0) {
                                _state.value = SetupState.Downloading(
                                    (downloaded * 100 / totalSize).toInt()
                                )
                            }
                        }
                    }
                }
                Log.d(TAG, "[download ${distro.id}] done bytes=${tarball.length()}")
            }

            // Verify SHA-256 checksum
            withContext(Dispatchers.IO) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                tarball.inputStream().buffered().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        digest.update(buf, 0, n)
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualSha256 != expectedSha256) {
                    tarball.delete()
                    throw SecurityException(
                        "Rootfs checksum mismatch — expected $expectedSha256 but got $actualSha256. " +
                            "Download deleted. This may indicate a corrupted download or tampered file."
                    )
                }
                Log.d(TAG, "[download ${distro.id}] sha256-ok $actualSha256")
            }

            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsDownload",
                exit = 0,
                ok = true,
                message = "Download + sha256 OK (${tarball.length()} bytes)",
            )

            // Extract
            currentPhase = Phase.RootfsExtract
            _state.value = SetupState.Extracting
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsExtract",
                message = "Starting extract",
            )
            val targetDir = File(context.filesDir, "proot/rootfs/${distro.id}")
            withContext(Dispatchers.IO) {
                Log.d(TAG, "[extract ${distro.id}] start")
                extractTarball(tarball, targetDir, source)
                tarball.delete()
                Log.d(TAG, "[extract ${distro.id}] done dir=${targetDir.absolutePath}")

                // Android doesn't have /etc/resolv.conf — write one with public DNS
                val resolvConf = File(targetDir, "etc/resolv.conf")
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                Log.d(TAG, "[extract ${distro.id}] wrote resolv.conf")

                // Drop a generic shell profile and a welcome README into
                // /root/. These are vendor-neutral — they explain what the
                // environment is, how to install tools, and how the "phone
                // is the point of presence, remote machines are the compute"
                // pattern composes with Haven's SSH primitives. No vendor
                // CLIs, no cloned repositories, no hardcoded hosts.
                seedRootHome(targetDir)
            }

            // Run distro-specific post-extract hooks before baseline:
            // locale-gen on Arch, ca-cert refresh on Void, etc. The
            // state.value advance to Initializing so the UI doesn't
            // keep saying "Extracting rootfs…" through a multi-minute
            // setup phase that has nothing to do with extraction.
            //
            // Hooks are now HARD-FAIL: a non-zero exit aborts the
            // install with Phase.BootstrapHook attribution. Previous
            // behaviour was silent-continue, which hid Void's three-
            // step xbps bootstrap fragility until a much later DE
            // install failed with a downstream symptom. The user
            // sees the hook id (e.g. "void-xbps-bootstrap") in the
            // error chip and the log tail of the failing script.
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "RootfsExtract",
                exit = 0,
                ok = true,
                message = "Extracted to ${targetDir.absolutePath}",
            )

            // Post-extract setup (hooks + baseline) — factored out so
            // `retry()` can re-run just these phases when the user
            // hits Retry on a BootstrapHook or Baseline error chip.
            // Hooks are required to be idempotent (RootfsHook.idempotent
            // defaults to true; documented contract). On hook failure,
            // SetupState.Error is set and we return early.
            if (!runPostExtractSetup(distro, installStartedAt)) return
        } catch (e: Exception) {
            Log.e(TAG, "[install ${currentPhase}] failed", e)
            installLogRepository.logEvent(
                distroId = activeDistro.id,
                phase = currentPhase.name,
                ok = false,
                message = e.message ?: "Installation failed",
            )
            _state.value = SetupState.Error(
                phase = currentPhase,
                message = e.message ?: "Installation failed",
                logTail = "",
            )
        }
    }

    /**
     * Run all post-extract bootstrap hooks, then the baseline package
     * install. Sets [_state] to [SetupState.Ready] on success, or
     * [SetupState.Error] on hook failure. Returns true if everything
     * succeeded, false if any hook failed (caller in [installRootfs]
     * uses this to return early so the outer catch doesn't fire).
     *
     * Idempotent contract: every hook is required to be safe to re-
     * run, so [retry] can call this method again after a failure
     * without wiping the rootfs. Hooks that aren't idempotent should
     * set [sh.haven.core.local.proot.RootfsHook.idempotent] = false
     * and the UI's retry path will offer Wipe & retry instead.
     */
    private suspend fun runPostExtractSetup(distro: Distro, installStartedAt: Long): Boolean {
        for (hook in distro.postExtractHooks) {
            _state.value = SetupState.Initializing(hook.id)
            Log.d(TAG, "[hook ${hook.id}] start")
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "BootstrapHook:${hook.id}",
                message = "Starting hook",
            )
            val (output, code) = runCommandInProot(hook.command)
            if (code == 0) {
                Log.d(TAG, "[hook ${hook.id}] ok bytes=${output.length}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "BootstrapHook:${hook.id}",
                    exit = 0,
                    ok = true,
                )
            } else {
                Log.w(TAG, "[hook ${hook.id}] exit=$code tail=${output.takeLast(1500)}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "BootstrapHook:${hook.id}",
                    exit = code,
                    ok = false,
                    message = "Hook failed (exit $code)",
                    logTail = output.takeLast(1500),
                )
                _state.value = SetupState.Error(
                    phase = Phase.BootstrapHook,
                    message = "Bootstrap hook '${hook.id}' failed (exit $code). " +
                        "This is usually a transient network issue (mirror down, " +
                        "stale package DB) — tap Retry. If it persists, the distro " +
                        "tarball may need a rebuild.",
                    logTail = output.takeLast(1500),
                )
                return false
            }
        }

        // Install a minimal baseline of packages so the environment is
        // immediately usable: bash (readline + .inputrc), curl +
        // ca-certificates (for the user to follow install instructions
        // over HTTPS), and openssh-client (for the remote-compute
        // composition pattern documented in /root/README.md). Best-
        // effort — if network is unavailable the rootfs is still fully
        // usable with busybox and the user can run `apk add` later.
        _state.value = SetupState.Initializing("baseline packages")
        installLogRepository.logEvent(
            distroId = distro.id,
            phase = "Baseline",
            message = "Installing baseline packages: ${distro.baselinePackages.joinToString(" ")}",
        )
        installBaseline()

        _state.value = SetupState.Ready
        installLogRepository.logEvent(
            distroId = distro.id,
            phase = "Ready",
            exit = 0,
            ok = true,
            message = "Rootfs install complete (${System.currentTimeMillis() - installStartedAt}ms total)",
        )
        return true
    }

    /**
     * Retry the failed phase of the most recent install attempt.
     *
     * Behaviour by phase:
     *  - [Phase.RootfsDownload], [Phase.RootfsExtract] — wipes the
     *    partial rootfs (the extract may have left a half-state) then
     *    re-runs the full install. Network-side retries can land in
     *    either bucket; in both cases the safest move is to start over
     *    from the tarball.
     *  - [Phase.BootstrapHook], [Phase.Baseline] — the rootfs is on
     *    disk, hooks are idempotent (see [RootfsHook.idempotent] —
     *    every shipped hook today qualifies). Re-runs hooks + baseline
     *    without touching the rootfs files.
     *
     * No-op if current state isn't Error (already Ready, or in-flight).
     * Issue #162 Phase 3d.
     */
    suspend fun retry() {
        val err = _state.value as? SetupState.Error ?: return
        val distro = activeDistro
        when (err.phase) {
            Phase.RootfsDownload, Phase.RootfsExtract -> {
                Log.d(TAG, "[retry] wipe + reinstall for phase=${err.phase}")
                deleteDistro(distro.id)
                setActiveDistroId(distro.id)
                installRootfs()
            }
            Phase.BootstrapHook, Phase.Baseline -> {
                if (!isRootfsInstalled) {
                    Log.d(TAG, "[retry] rootfs missing; falling through to full install")
                    installRootfs()
                    return
                }
                Log.d(TAG, "[retry] re-running post-extract setup for phase=${err.phase}")
                val started = System.currentTimeMillis()
                runPostExtractSetup(distro, started)
            }
        }
    }

    /**
     * Extract a tar.gz / tar.xz file to a directory using Java
     * streams. Implements minimal POSIX tar parsing (512-byte
     * headers, ustar format) and honours [RootfsSource.format] +
     * [RootfsSource.stripComponents].
     *
     * stripComponents removes the leading N path components from
     * each entry name, matching `tar --strip-components=N`. Used
     * for tarballs that wrap the rootfs in a top-level directory
     * (e.g. proot-distro's `debian-bookworm-aarch64/`).
     */
    private fun extractTarball(tarball: File, destDir: File, source: RootfsSource) {
        destDir.mkdirs()
        var fileCount = 0
        var symlinkCount = 0

        val rawInput = tarball.inputStream().buffered()
        val decompressed: java.io.InputStream = when (source.format) {
            RootfsFormat.TAR_GZ -> java.util.zip.GZIPInputStream(rawInput)
            RootfsFormat.TAR_XZ -> org.tukaani.xz.XZInputStream(rawInput)
            RootfsFormat.TAR_ZSTD -> error(
                "TAR_ZSTD support not yet wired — add a zstd decoder dep to core/local first.",
            )
        }
        decompressed.use { gzIn ->
            val header = ByteArray(512)
            var pendingLongName: String? = null

            while (true) {
                val headerRead = readFully(gzIn, header)
                if (headerRead < 512) break
                if (header.all { it == 0.toByte() }) break

                val name = extractString(header, 0, 100)
                if (name.isEmpty() && pendingLongName == null) break

                val modeStr = extractString(header, 100, 8)
                val sizeStr = extractString(header, 124, 12)
                val typeFlag = header[156]
                val linkTarget = extractString(header, 157, 100)

                val size = try {
                    sizeStr.trim().toLong(8)
                } catch (_: Exception) { 0L }

                // GNU long name: type 'L' means the data is a long filename
                // for the NEXT entry
                if (typeFlag == 'L'.code.toByte()) {
                    val nameBytes = ByteArray(size.toInt())
                    readFully(gzIn, nameBytes)
                    skipToBlock(gzIn, size)
                    pendingLongName = String(nameBytes).trimEnd('\u0000')
                    continue // next header is the actual entry
                }

                // Resolve final name
                val rawEntryName = pendingLongName ?: run {
                    val prefix = extractString(header, 345, 155)
                    if (prefix.isNotEmpty()) "$prefix/$name" else name
                }
                pendingLongName = null

                // Apply tar --strip-components=N: drop the first N
                // path components. If the entry has fewer than N
                // components left after stripping it's a no-op (the
                // wrapper-dir itself becomes the empty string, which
                // we skip).
                val entryName = if (source.stripComponents > 0) {
                    val parts = rawEntryName.trimEnd('/').split('/').drop(source.stripComponents)
                    if (parts.isEmpty()) {
                        // Wrapper directory itself — skip header data
                        if (size > 0) skipToBlock(gzIn, size)
                        continue
                    }
                    parts.joinToString("/")
                } else {
                    rawEntryName
                }
                if (entryName.isEmpty()) {
                    if (size > 0) skipToBlock(gzIn, size)
                    continue
                }

                val outFile = File(destDir, entryName)

                when (typeFlag) {
                    '5'.code.toByte() -> {
                        outFile.mkdirs()
                    }
                    '2'.code.toByte() -> {
                        // Symlink
                        outFile.parentFile?.mkdirs()
                        try {
                            outFile.delete()
                            java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(),
                                java.nio.file.Paths.get(linkTarget),
                            )
                            symlinkCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "Symlink failed: $entryName -> $linkTarget: ${e.message}")
                        }
                    }
                    '1'.code.toByte() -> {
                        // Hard link — copy the target file
                        outFile.parentFile?.mkdirs()
                        try {
                            val targetFile = File(destDir, linkTarget)
                            if (targetFile.exists()) {
                                targetFile.copyTo(outFile, overwrite = true)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Hard link failed: $entryName -> $linkTarget: ${e.message}")
                        }
                    }
                    '0'.code.toByte(), 0.toByte() -> {
                        // Regular file
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var remaining = size
                            val copyBuf = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(remaining.toInt(), copyBuf.size)
                                val n = gzIn.read(copyBuf, 0, toRead)
                                if (n <= 0) break
                                fos.write(copyBuf, 0, n)
                                remaining -= n
                            }
                        }
                        try {
                            val mode = modeStr.trim().toIntOrNull(8) ?: 0
                            if (mode and 0x49 != 0) {
                                outFile.setExecutable(true, false)
                            }
                        } catch (_: Exception) {}
                        skipToBlock(gzIn, size)
                        fileCount++
                    }
                    else -> {
                        // Skip unknown types (but still consume data)
                        if (size > 0) {
                            var remaining = size
                            val skipBuf = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(remaining.toInt(), skipBuf.size)
                                val n = gzIn.read(skipBuf, 0, toRead)
                                if (n <= 0) break
                                remaining -= n
                            }
                            skipToBlock(gzIn, size)
                        }
                    }
                }

                // Also handle directory entries without explicit type flag
                if (typeFlag != '5'.code.toByte() && entryName.endsWith("/")) {
                    outFile.mkdirs()
                }
            }
        }

        Log.d(TAG, "Extracted $fileCount files, $symlinkCount symlinks to ${destDir.absolutePath}")

        // Check bin/sh exists — it's a symlink to /bin/busybox so we must
        // not follow the link (the target is inside the rootfs, not the host)
        val binSh = File(destDir, "bin/sh").toPath()
        if (!java.nio.file.Files.exists(binSh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val binDir = File(destDir, "bin")
            val binContents = if (binDir.isDirectory) binDir.list()?.toList() else null
            throw RuntimeException(
                "Extracted $fileCount files, $symlinkCount symlinks but bin/sh not found. " +
                    "bin/ contents: $binContents"
            )
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun extractString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset + length
        for (i in offset until offset + length) {
            if (buf[i] == 0.toByte()) { end = i; break }
        }
        return String(buf, offset, end - offset, Charsets.US_ASCII).trim()
    }

    /** Skip remaining bytes in the current tar block (blocks are 512-byte aligned). */
    private fun skipToBlock(input: java.io.InputStream, dataSize: Long) {
        val remainder = (512 - (dataSize % 512)) % 512
        if (remainder > 0) {
            val skip = ByteArray(remainder.toInt())
            readFully(input, skip)
        }
    }

    /**
     * Seed a freshly-extracted rootfs with Haven's vendor-neutral
     * defaults:
     *
     *  - `/root/.profile`     — interactive shell defaults + rexec helper
     *  - `/root/README.md`    — welcome doc explaining the environment
     *  - `/root/.inputrc`     — readline config (honoured once bash is installed)
     *  - `/root/.ssh/`        — empty directory with 0700 permissions
     *  - `/etc/profile.d/haven.sh` — universal PATH for any future user
     *
     * All files are only written if they don't already exist, so
     * future rootfs refreshes never clobber user edits.
     */
    private fun seedRootHome(rootfsDir: File) {
        val rootHome = File(rootfsDir, "root").apply { mkdirs() }
        val rootAssets = mapOf(
            "proot/root/profile" to ".profile",
            "proot/root/README.md" to "README.md",
            "proot/root/inputrc" to ".inputrc",
        )
        for ((assetPath, targetName) in rootAssets) {
            copyAssetIfAbsent(assetPath, File(rootHome, targetName), "/root/$targetName")
        }

        // Create /root/.ssh with owner-only permissions so the user
        // doesn't have to remember chmod 700 before putting keys here.
        val sshDir = File(rootHome, ".ssh")
        if (!sshDir.exists()) {
            try {
                sshDir.mkdirs()
                // Java File API can't fully express 0700, so use the
                // POSIX chmod syscall from android.system.Os
                try {
                    android.system.Os.chmod(sshDir.absolutePath, 0b111_000_000) // 0700
                } catch (_: Throwable) {
                    // Fall back to best-effort via File API
                    sshDir.setReadable(true, true)
                    sshDir.setWritable(true, true)
                    sshDir.setExecutable(true, true)
                    sshDir.setReadable(false, false)
                    sshDir.setWritable(false, false)
                    sshDir.setExecutable(false, false)
                }
                Log.d(TAG, "Created /root/.ssh (0700)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create /root/.ssh: ${e.message}")
            }
        }

        // /etc/profile.d/haven.sh — system-wide snippet for any shell
        // account that ever gets created in this rootfs
        val profileD = File(rootfsDir, "etc/profile.d").apply { mkdirs() }
        copyAssetIfAbsent(
            "proot/etc/profile.d/haven.sh",
            File(profileD, "haven.sh"),
            "/etc/profile.d/haven.sh",
        )
    }

    /** Copy an asset into [target] unless [target] already exists. Best-effort. */
    private fun copyAssetIfAbsent(assetPath: String, target: File, displayPath: String) {
        if (target.exists()) {
            Log.d(TAG, "Preserving existing $displayPath")
            return
        }
        try {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Seeded $displayPath from $assetPath")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to seed $displayPath: ${e.message}")
        }
    }

    /**
     * Install a minimal baseline of packages into the fresh rootfs.
     * Best-effort — if network or mirrors are unavailable the rootfs
     * is still fully usable with just the busybox utilities Alpine's
     * minirootfs ships. Failure here never fails the overall install.
     *
     * The baseline is deliberately tiny: just enough for an interactive
     * session and to follow the SSH setup instructions in the README.
     * Everything else (git, vim, compilers, language runtimes) is the
     * user's choice and stays an explicit `apk add` away.
     *
     * tmux is the one exception: when the user picks a session manager
     * in Settings (default NONE), the local-session launcher needs the
     * binary to be present, otherwise it falls back to a plain shell
     * and the "survives Haven restarts" promise is silently broken.
     * tmux is ~250KB, so the cost of bundling it is negligible.
     */
    private suspend fun installBaseline() {
        val distro = activeDistro
        try {
            val ops = PackageOps.forFamily(distro.family)
            val install = ops.installCmd(distro.baselinePackages)
            // Don't pre-truncate with `| tail -N` — that hides the
            // actual error line on a failed install. Kotlin-side
            // takeLast() keeps the log size bounded.
            val (output, code) = runCommandInProot(
                "${ops.updateCmd()} >/dev/null 2>&1 && $install 2>&1",
            )
            if (code == 0) {
                Log.d(TAG, "[baseline ${distro.id}] ok pkgs=${distro.baselinePackages.size}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "Baseline",
                    exit = 0,
                    ok = true,
                    message = "Installed ${distro.baselinePackages.size} packages",
                )
            } else {
                Log.w(TAG, "[baseline ${distro.id}] exit=$code tail=${output.takeLast(1500)}")
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "Baseline",
                    exit = code,
                    ok = false,
                    message = "Baseline install failed (exit $code) — non-fatal, rootfs is usable",
                    logTail = output.takeLast(1500),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "[baseline ${distro.id}] threw (non-fatal): ${e.message}")
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "Baseline",
                ok = false,
                message = "Baseline install threw: ${e.message}",
            )
        }
    }

    /**
     * Run a command inside the PRoot rootfs (non-interactive).
     * Returns (stdout+stderr, exitCode).
     */
    suspend fun runCommandInProot(command: String): Pair<String, Int> = withContext(Dispatchers.IO) {
        val process = startCommandInProot(command)
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        Pair(output, exitCode)
    }

    /**
     * Ensure a writable host dir to overlay at the guest's `/dev/shm`. Android's
     * `/dev` (bound into the guest) is read-only and has no `shm`, so POSIX
     * shared memory (`shm_open`) fails for Mono/.NET, Chromium, PostgreSQL, etc.
     * cacheDir-backed and world-rwx so the fake-root guest process can use it.
     * Returns the absolute host path to bind at `/dev/shm`.
     */
    fun ensureDevShm(): String {
        val d = File(context.cacheDir, "devshm")
        if (!d.exists()) d.mkdirs()
        d.setReadable(true, false)
        d.setWritable(true, false)
        d.setExecutable(true, false)
        return d.absolutePath
    }

    /**
     * Build and start a PRoot process running [command] in the active
     * rootfs, with combined stdout+stderr on the returned Process's
     * inputStream. Callers that need to stream output incrementally (a
     * long apt install surfaced through an async MCP job) read the stream
     * themselves; [runCommandInProot] is the read-to-completion wrapper.
     */
    fun startCommandInProot(command: String): Process {
        val prootBin = prootBinary ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath
        // Mirror termux/proot-distro v4.29.0's `run_proot_cmd` invocation
        // (distro-plugins runner). Empirically that's the proven pattern
        // for running xbps-install / apt-get / pacman inside this exact
        // Void tarball — Termux users install Xfce4 on Void daily via
        // `proot-distro login void` + `xbps-install -Sy xfce4`. The
        // missing /dev/fd, /dev/std{in,out,err}, /dev/random bindings
        // and the fake /proc files are what breaks fontconfig's
        // pre-INSTALL when invoked via the bare ProcessBuilder we used
        // before. See proot-distro/proot-distro.sh:run_proot_cmd().
        val procDir = File(activeRootfsDir, "proc").apply { mkdirs() }
        val sysEmpty = File(activeRootfsDir, "sys/.empty").apply { mkdirs() }
        // Write minimal fake /proc replacements once per rootfs. proot
        // can't read the host's real /proc/{loadavg,stat,uptime,…}
        // through its bind mount, so apps that parse them (rpm post-
        // install, locale generation, etc.) need plausible-looking
        // values to make progress.
        fun writeIfMissing(name: String, content: String) {
            val f = File(procDir, name)
            if (!f.exists()) f.writeText(content)
        }
        writeIfMissing(".loadavg", "0.12 0.07 0.02 2/165 765\n")
        writeIfMissing(".uptime", "284.83 1391.42\n")
        writeIfMissing(".version", "Linux version 6.2.1 (proot@haven) #1 SMP PREEMPT_DYNAMIC\n")
        writeIfMissing(".vmstat", "nr_free_pages 1000\n")
        writeIfMissing(".stat", "cpu  1957 0 2877 93280 262 342 254 87 0 0\nbtime 1680020856\n")
        writeIfMissing(".sysctl_entry_cap_last_cap", "40\n")
        writeIfMissing(".sysctl_inotify_max_user_watches", "65536\n")

        val rootfsPath = activeRootfsDir.absolutePath
        val devShm = ensureDevShm()
        val args = mutableListOf(
            prootBin,
            "-L",
            "--kernel-release=6.2.1",
            "--link2symlink",
            "--kill-on-exit",
            "--rootfs=$rootfsPath",
            "--root-id",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            // Writable /dev/shm — Android's /dev (bound above) is read-only and
            // has no shm, so POSIX shared memory (shm_open) fails for Mono/.NET,
            // Chromium, etc. Overlay a cacheDir-backed dir so it works.
            "--bind=$devShm:/dev/shm",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=$rootfsPath/proc/.loadavg:/proc/loadavg",
            "--bind=$rootfsPath/proc/.stat:/proc/stat",
            "--bind=$rootfsPath/proc/.uptime:/proc/uptime",
            "--bind=$rootfsPath/proc/.version:/proc/version",
            "--bind=$rootfsPath/proc/.vmstat:/proc/vmstat",
            "--bind=$rootfsPath/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap",
            "--bind=$rootfsPath/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches",
            "--bind=${sysEmpty.absolutePath}:/sys/fs/selinux",
            "--bind=${context.cacheDir.absolutePath}:/tmp",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "/bin/sh", "-lc", command,
        )
        return ProcessBuilder(args).apply {
            // proot/PROOT_LOADER vars must live in the OUTER env so
            // the proot binary itself finds its loader. The tracee's
            // env is reset by `env -i` inside proot.
            environment().apply {
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
            }
            redirectErrorStream(true)
        }.start()
    }

    /**
     * Ensure the guest has the tools an agent needs to screenshot a
     * desktop and enumerate its windows: `xdotool` (window list +
     * geometry) and ImageMagick's `import` (root capture).
     *
     * Installed on demand the first time a capture is requested rather
     * than baked into every desktop's package list — adding packages to
     * an already-installed DE silently no-ops because the install marker
     * short-circuits setupDesktop. A presence check + targeted install is
     * the reliable path. Returns (ready, detail) where detail is a short
     * human-readable status (or the install-failure tail).
     */
    suspend fun ensureCaptureTools(): Pair<Boolean, String> {
        val probeCmd =
            "command -v xdotool >/dev/null 2>&1 && command -v import >/dev/null 2>&1 && echo HAVE || echo MISSING"
        val (probe, _) = runCommandInProot(probeCmd)
        if (probe.contains("HAVE")) return true to "capture tools already present"

        val family = activeDistro.family
        val ops = PackageOps.forFamily(family)
        // Void packages ImageMagick with a capital name; everyone else
        // ships lowercase `imagemagick`.
        val imagemagick = if (family == PackageFamily.XBPS) "ImageMagick" else "imagemagick"
        val pkgs = listOf("xdotool", imagemagick)
        val (installOut, _) = runCommandInProot("${ops.updateCmd()} && ${ops.installCmd(pkgs)}")
        val (recheck, _) = runCommandInProot(probeCmd)
        return if (recheck.contains("HAVE")) {
            true to "installed ${pkgs.joinToString(" ")}"
        } else {
            false to "capture-tool install failed: ${installOut.takeLast(800)}"
        }
    }

    /**
     * Ensure the headless render tools used by the `view_file` MCP tool are
     * present — `rsvg-convert` (SVG→PNG) and/or `pdftoppm` (PDF→PNG). Only the
     * binaries in [needed] are probed and, if missing, installed. Mirrors
     * [ensureCaptureTools]; returns (ready, detail). Package names differ per
     * family, so if a binary is still absent after the install attempt the
     * caller gets an honest failure rather than a silent miss. `kicad-cli` is
     * NOT handled here — it ships with KiCad and is too heavy to auto-install.
     */
    suspend fun ensureRenderTools(needed: List<String>): Pair<Boolean, String> {
        if (needed.isEmpty()) return true to "no render tools needed"
        val probeCmd = buildString {
            append("for b in ${needed.joinToString(" ")}; do ")
            append("command -v \$b >/dev/null 2>&1 || { echo MISSING; exit 0; }; ")
            append("done; echo HAVE")
        }
        val (probe, _) = runCommandInProot(probeCmd)
        if (probe.contains("HAVE")) return true to "render tools already present"

        val family = activeDistro.family
        fun pkgFor(bin: String): String? = when (bin) {
            "rsvg-convert" -> when (family) {
                PackageFamily.APT -> "librsvg2-bin"
                PackageFamily.PACMAN -> "librsvg"
                PackageFamily.XBPS -> "librsvg-utils"
                PackageFamily.APK -> "rsvg-convert"
                PackageFamily.NIX -> null
            }
            "pdftoppm" -> when (family) {
                PackageFamily.PACMAN -> "poppler"
                PackageFamily.APT, PackageFamily.XBPS, PackageFamily.APK -> "poppler-utils"
                PackageFamily.NIX -> null
            }
            else -> null
        }
        val pkgs = needed.mapNotNull { pkgFor(it) }.distinct()
        if (pkgs.isEmpty()) {
            return false to "no known package mapping for ${needed.joinToString(" ")} on $family"
        }
        val ops = PackageOps.forFamily(family)
        val (installOut, _) = runCommandInProot("${ops.updateCmd()} && ${ops.installCmd(pkgs)}")
        val (recheck, _) = runCommandInProot(probeCmd)
        return if (recheck.contains("HAVE")) {
            true to "installed ${pkgs.joinToString(" ")}"
        } else {
            false to "render-tool install failed (need ${needed.joinToString(" ")}): ${installOut.takeLast(600)}"
        }
    }

    /**
     * Run `update && install` inside the proot, and if the install
     * fails with the family's stale-package-DB signature, run a
     * full system upgrade once and retry the install. Self-repair
     * for the long-tail case where the rootfs tarball is from a
     * snapshot whose package names / dependency chains have moved
     * on in the live repos (e.g. Arch renamed libstdc++ → gcc-libs
     * weeks after the proot-distro tarball was built; subsequent
     * `pacman -S xfce4` fails until `pacman -Syu` realigns
     * everything).
     */
    private suspend fun runInstallWithSelfRepair(
        ops: PackageOps,
        pkgs: List<String>,
    ): Pair<String, Int> {
        val installCmd = "${ops.updateCmd()} && ${ops.installCmd(pkgs)}"
        val (output, code) = runCommandInProot(installCmd)
        if (code == 0 || !ops.looksLikeStaleDb(output)) {
            return Pair(output, code)
        }
        Log.d(TAG, "Install hit stale-DB symptom; auto-upgrading and retrying")
        _desktopState.value = DesktopSetupState.Installing(
            "Refreshing package database (one-time upgrade)…",
        )
        val (upgOut, upgCode) = runCommandInProot(ops.upgradeCmd())
        Log.d(TAG, "Auto-upgrade exit=$upgCode tail=${upgOut.takeLast(300)}")
        return runCommandInProot(installCmd)
    }

    /**
     * Install X11 + VNC + Xfce4 desktop inside the PRoot rootfs.
     */
    suspend fun setupDesktop(vncPassword: String, de: DesktopEnvironment = DesktopEnvironment.XFCE4) {
        try {
            // Ensure rootfs is installed first. If it failed, the
            // root cause already lives in _state as SetupState.Error
            // with a Phase. Don't shadow it with a vague DE-level
            // error — keep the OS-layer attribution visible so the
            // user sees "Bootstrap hook void-xbps-bootstrap failed"
            // rather than the misleading "Rootfs install failed"
            // under a Desktop installation banner.
            if (!isRootfsInstalled) {
                installRootfs()
                if (_state.value is SetupState.Error) {
                    _desktopState.value = DesktopSetupState.Idle
                    return
                }
            }

            // Install if this DE is not yet installed
            val needsInstall = !isDesktopInstalled(de)
            if (needsInstall) {
                _desktopState.value = DesktopSetupState.Installing(
                    "Installing ${de.label} (${de.sizeEstimate} download)..."
                )

            val distro = activeDistro
            val ops = PackageOps.forFamily(distro.family)
            val pkgs = de.spec.packagesPerFamily[distro.family]
                ?: error("${de.spec.id} has no package list for ${distro.family} — supported: ${de.spec.packagesPerFamily.keys}")
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "DePackage:${de.spec.id}",
                deId = de.spec.id,
                message = "Installing ${de.label}: ${pkgs.joinToString(" ")}",
            )
            val (installOutput, installExit) = runInstallWithSelfRepair(ops, pkgs)
            Log.d(TAG, "[de-package ${de.spec.id}] family=${distro.family} exit=$installExit tail=${installOutput.takeLast(1500)}")

            // Check if key binaries were installed — package managers may
            // return non-zero for non-fatal trigger errors (gtk icon cache,
            // fontscale, etc.). For marker-based DEs, fall back to the
            // family-specific success heuristic on stdout.
            val checkInstalled = if (de.verifyBinary.startsWith("root/.haven-")) {
                ops.installSucceeded(installOutput)
            } else {
                File(activeRootfsDir, de.verifyBinary).exists()
            }
            if (!checkInstalled) {
                // If the failure looks like a transient mirror sync
                // issue, surface a clearer "try again later" message
                // rather than dumping raw apt/pacman tail at the
                // user. Self-repair will have already retried once
                // with a forced DB refresh, so a remaining failure
                // means the mirror is genuinely out of sync — usually
                // resolves itself within minutes.
                val message = if (ops.looksLikeStaleDb(installOutput)) {
                    "Distro mirror is mid-sync (a package version listed in the " +
                        "index isn't on disk yet). Wait a few minutes and tap " +
                        "Install again — usually self-resolves."
                } else {
                    "Package install failed for ${de.label} on ${distro.label}."
                }
                installLogRepository.logEvent(
                    distroId = distro.id,
                    phase = "DePackage:${de.spec.id}",
                    deId = de.spec.id,
                    exit = installExit,
                    ok = false,
                    message = message,
                    logTail = installOutput.takeLast(1500),
                )
                _desktopState.value = DesktopSetupState.Error(
                    phase = DePhase.Packages,
                    message = message,
                    logTail = installOutput.takeLast(1500),
                )
                return
            }
            installLogRepository.logEvent(
                distroId = distro.id,
                phase = "DePackage:${de.spec.id}",
                deId = de.spec.id,
                exit = installExit,
                ok = true,
                message = "Packages installed",
            )

            // Update marker — append this DE to the installed set
            File(activeRootfsDir, "root").mkdirs()
            val updated = installedDesktops + de
            File(activeRootfsDir, "root/.haven-desktop").writeText(updated.joinToString("\n") { it.name })
            // Create DE-specific marker file for installedDesktops check
            if (de.verifyBinary.startsWith("root/.haven-")) {
                File(activeRootfsDir, de.verifyBinary).writeText(de.name)
            }
            Log.d(TAG, "${de.label} packages installed")
            }

            if (de.spec.launch is LaunchSpec.X11Vnc) {
                _desktopState.value = DesktopSetupState.Installing("Configuring VNC...")

                // Write VNC password — use a temp file to avoid leaking
                // the password in process arguments (visible in /proc)
                runCommandInProot("mkdir -p /root/.vnc")
                if (vncPassword.isNotEmpty()) {
                    val tmpPwd = File(activeRootfsDir, "root/.vnc/.pwd_tmp")
                    try {
                        tmpPwd.writeText(vncPassword)
                    } finally { /* deleted below after use */ }
                    val (pwdOut, pwdExit) = runCommandInProot(
                        "vncpasswd -f < /root/.vnc/.pwd_tmp > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd; rm -f /root/.vnc/.pwd_tmp"
                    )
                    tmpPwd.delete() // also delete from host side
                    Log.d(TAG, "vncpasswd exit=$pwdExit output=$pwdOut")
                    val passwdFile = File(activeRootfsDir, "root/.vnc/passwd")
                    Log.d(TAG, "passwd file exists=${passwdFile.exists()} size=${passwdFile.length()}")
                    // Store encrypted for the VNC viewer to use on subsequent starts
                    storedVncPassword = vncPassword
                } else {
                    // No password — remove any existing passwd file so server uses None
                    File(activeRootfsDir, "root/.vnc/passwd").delete()
                    File(activeRootfsDir, "root/.haven-vnc-password").delete()
                    Log.d(TAG, "No VNC password set, using SecurityTypes None")
                }

                // Write xstartup
                runCommandInProot("""cat > /root/.vnc/xstartup << 'XEOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
exec startxfce4
XEOF
chmod +x /root/.vnc/xstartup""")
            }

            // Seed minimum-viable config files (Phase 4 nested wlroots
            // compositors need at least a headless output declaration to
            // render anything). Write-if-absent so user edits survive a
            // re-install. NativeCompositor and X11Vnc DEs declare an
            // empty configSeed map today and skip this loop unchanged.
            for ((relPath, content) in de.spec.configSeed) {
                val target = File(activeRootfsDir, relPath)
                if (target.exists()) {
                    Log.d(TAG, "[de-config ${de.spec.id}] keeping existing $relPath")
                    continue
                }
                target.parentFile?.mkdirs()
                target.writeText(content)
                Log.d(TAG, "[de-config ${de.spec.id}] seeded $relPath (${content.length} bytes)")
            }

            // Nested-Wayland DEs (Sway / Hyprland / Niri) need the
            // wayvnc capture-fallback shim staged in the rootfs so the
            // launch script's LD_PRELOAD picks it up. The shim blocks
            // ext-image-copy-capture-v1 from wayvnc so it falls back to
            // zwlr_screencopy, which the wlroots headless backend
            // actually advertises buffer formats for. Overwrite on each
            // install so a Haven update can ship a newer shim without
            // user intervention.
            if (de.spec.launch is LaunchSpec.NestedWayland) {
                stageWayvncShim(de)

                // Seed fuzzel + foot configs and a small .desktop set so
                // the auto-launched fuzzel picker has something to show
                // on first connect. labwc-native uses writeDesktopConfigs
                // for the same purpose; the nested-Wayland flow doesn't
                // call that path because it pulls in waybar / labwc-menu
                // bits that don't apply.
                writeNestedWaylandFuzzelConfig()
            }

            Log.d(TAG, "[de-config ${de.spec.id}] complete")
            installLogRepository.logEvent(
                distroId = activeDistro.id,
                phase = "DeConfig:${de.spec.id}",
                deId = de.spec.id,
                exit = 0,
                ok = true,
                message = "${de.label} setup complete",
            )
            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "[de-setup ${de.spec.id}] failed", e)
            installLogRepository.logEvent(
                distroId = activeDistro.id,
                phase = "DeConfig:${de.spec.id}",
                deId = de.spec.id,
                ok = false,
                message = e.message ?: "Setup failed",
            )
            _desktopState.value = DesktopSetupState.Error(
                phase = DePhase.VncConfig,
                message = e.message ?: "Setup failed",
                logTail = "",
            )
        }
    }

    /**
     * Install optional desktop add-ons (panel, file manager, etc.) into the PRoot rootfs.
     * Packages are installed via apk and config files are written for labwc integration.
     */
    suspend fun installAddons(addons: Set<DesktopAddon>) {
        if (addons.isEmpty()) {
            // Remove addons marker and configs if user unchecked everything
            File(activeRootfsDir, "root/.haven-addons").delete()
            return
        }

        try {
            _desktopState.value = DesktopSetupState.Installing("Installing desktop features...")

            val ops = PackageOps.forFamily(activeDistro.family)
            val packages = addons.flatMap { it.packages.split(" ").filter { p -> p.isNotBlank() } }
            val (installOutput, installExit) = runInstallWithSelfRepair(ops, packages)
            Log.d(TAG, "addon install exit=$installExit output(last 300)=${installOutput.takeLast(300)}")

            writeDesktopConfigs()

            // Write marker
            File(activeRootfsDir, "root/.haven-addons")
                .writeText(addons.joinToString("\n") { it.name })
            Log.d(TAG, "Desktop addons installed: ${addons.map { it.name }}")

            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "[addon-install] failed", e)
            _desktopState.value = DesktopSetupState.Error(
                phase = DePhase.Packages,
                message = e.message ?: "Addon install failed",
                logTail = "",
            )
        }
    }

    /**
     * Uninstall a desktop environment from the PRoot rootfs.
     * Removes packages and updates the marker file.
     */
    /**
     * Re-seed a DE's [configSeed] files when the on-disk copy is missing
     * OR still carries a superseded Haven default (matched by
     * [DesktopEnvironmentSpec.legacyConfigMarkers]). Called at launch so
     * a config fix reaches existing installs whose write-if-absent config
     * would otherwise stay frozen — without clobbering user edits, since
     * a config that no longer contains a legacy marker is left alone.
     * No-op for DEs with an empty configSeed.
     */
    /**
     * Copy the wayvnc capture-fallback shim from APK assets into the rootfs,
     * overwriting any existing copy. Called both at install time and on every
     * desktop start (via [migrateDesktopConfigs]) so a Haven update that ships
     * a newer/fixed shim reaches existing installs without a reinstall — e.g.
     * the v5.43.8 musl-compat rebuild (#162): the install-time copy alone left
     * the old glibc-fortify shim in place on Alpine, where it failed to
     * relocate `__fprintf_chk`.
     */
    private fun stageWayvncShim(de: DesktopEnvironment) {
        if (de.spec.launch !is LaunchSpec.NestedWayland) return
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
        if (abi == null) {
            Log.w(TAG, "[de-config ${de.spec.id}] no wayvnc shim asset for ABI ${android.os.Build.SUPPORTED_ABIS.toList()}")
            return
        }
        val asset = "wayvnc-shim/$abi/libhaven_wayvnc_shim.so"
        val target = File(activeRootfsDir, "usr/local/lib/haven/libhaven_wayvnc_shim.so")
        target.parentFile?.mkdirs()
        try {
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            Log.d(TAG, "[de-config ${de.spec.id}] staged wayvnc shim from $asset (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "[de-config ${de.spec.id}] failed to stage wayvnc shim: ${e.message}")
        }
    }

    /**
     * Stage the haven-usb guest artifacts into the active rootfs:
     *  - `/usr/local/bin/haven-usb-probe`        (Slice-2 reachability probe)
     *  - `/usr/local/lib/haven/libhaven_usb.so`  (Slice-3 LD_PRELOAD/DllMap shim)
     *  - `/usr/local/bin/haven-hidraw-test`      (Slice-3 verification harness)
     *
     * Re-copies on every call so an app update refreshes them. Returns the
     * in-guest path of the probe (the Slice-2 entry point), or null if no asset
     * exists for this ABI. World-readable + executable so the fake-root guest
     * process can run them.
     */
    fun stageHavenUsbArtifacts(): String? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: run { Log.w(TAG, "[haven-usb] no artifact for ABI ${android.os.Build.SUPPORTED_ABIS.toList()}"); return null }

        fun copy(assetRel: String, target: File, executable: Boolean): Boolean = try {
            target.parentFile?.mkdirs()
            context.assets.open("haven-usb/$abi/$assetRel").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            if (executable) target.setExecutable(true, false)
            Log.d(TAG, "[haven-usb] staged $assetRel (${target.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[haven-usb] failed to stage $assetRel: ${e.message}")
            false
        }

        val probeOk = copy("haven-usb-probe", File(activeRootfsDir, "usr/local/bin/haven-usb-probe"), true)
        copy("libhaven_usb.so", File(activeRootfsDir, "usr/local/lib/haven/libhaven_usb.so"), false)
        copy("haven-hidraw-test", File(activeRootfsDir, "usr/local/bin/haven-hidraw-test"), true)
        return if (probeOk) "/usr/local/bin/haven-usb-probe" else null
    }

    /** Absolute in-guest path of the LD_PRELOAD/DllMap shim staged by [stageHavenUsbArtifacts]. */
    val havenUsbShimGuestPath: String get() = "/usr/local/lib/haven/libhaven_usb.so"

    fun migrateDesktopConfigs(de: DesktopEnvironment) {
        // Refresh the shim on every start so an app update's newer shim
        // replaces a stale one in an existing rootfs (#162).
        stageWayvncShim(de)
        for ((relPath, content) in de.spec.configSeed) {
            val target = File(activeRootfsDir, relPath)
            val existing = if (target.exists()) {
                runCatching { target.readText() }.getOrNull()
            } else null
            if (existing == content) continue
            val migrate = existing != null &&
                de.spec.legacyConfigMarkers.any { existing.contains(it) }
            if (existing == null || migrate) {
                target.parentFile?.mkdirs()
                target.writeText(content)
                Log.d(
                    TAG,
                    "[de-config ${de.spec.id}] ${if (migrate) "migrated" else "seeded"} " +
                        "$relPath (${content.length} bytes)",
                )
            }
        }
    }

    suspend fun uninstallDesktop(de: DesktopEnvironment) {
        try {
            _desktopState.value = DesktopSetupState.Installing("Removing ${de.label}...")
            val distro = activeDistro
            val ops = PackageOps.forFamily(distro.family)
            val pkgs = de.spec.packagesPerFamily[distro.family]
                ?: error("${de.spec.id} has no package list for ${distro.family}")
            val (output, exit) = runCommandInProot(ops.removeCmd(pkgs))
            Log.d(TAG, "remove ${de.label} exit=$exit output(last 300)=${output.takeLast(300)}")

            val remaining = installedDesktops - de
            val marker = File(activeRootfsDir, "root/.haven-desktop")
            if (remaining.isEmpty()) {
                marker.delete()
            } else {
                marker.writeText(remaining.joinToString("\n") { it.name })
            }
            // Remove DE-specific marker file
            if (de.verifyBinary.startsWith("root/.haven-")) {
                File(activeRootfsDir, de.verifyBinary).delete()
            }
            Log.d(TAG, "${de.label} uninstalled, remaining: ${remaining.map { it.name }}")
            _desktopState.value = DesktopSetupState.Complete
        } catch (e: Exception) {
            Log.e(TAG, "[de-uninstall ${de.spec.id}] failed", e)
            _desktopState.value = DesktopSetupState.Error(
                phase = DePhase.Packages,
                message = e.message ?: "Uninstall failed",
                logTail = "",
            )
        }
    }

    /** Write mobile-optimized config files for waybar, fuzzel, and labwc menu. */
    /**
     * Configs the Phase-4 nested-Wayland compositors (Sway / Hyprland /
     * Niri) need so the auto-launched `fuzzel` picker has visible
     * entries on first connect. Distinct from the labwc-native
     * [writeDesktopConfigs] path: no waybar, no labwc menu, no
     * `launch-prefix=/usr/local/bin/launch` (that script doesn't exist
     * in the rootfs and would crash the picker on a selection).
     *
     * Always-on entries: foot (terminal), htop. Conditional entries:
     * thunar, mousepad, imv-wayland, mpv if those binaries are
     * present in the rootfs at config time.
     */
    private fun writeNestedWaylandFuzzelConfig() {
        val rootfsDir = activeRootfsDir
        val root = File(rootfsDir, "root")

        // fuzzel — large enough text for VNC at 1280x720; no
        // launch-prefix because the labwc /usr/local/bin/launch hook
        // isn't shipped (would crash on every selection).
        File(root, ".config/fuzzel").mkdirs()
        File(root, ".config/fuzzel/fuzzel.ini").writeText(
            """
            |[main]
            |font=Noto Sans:size=14
            |width=30
            |lines=12
            |prompt=>
            |layer=overlay
            """.trimMargin()
        )

        // foot — borderless, mobile-friendly font size.
        File(root, ".config/foot").mkdirs()
        File(root, ".config/foot/foot.ini").writeText(
            """
            |[main]
            |font=monospace:size=11
            |pad=2x2
            |
            |[csd]
            |preferred=none
            """.trimMargin()
        )

        // .desktop entries — write to both user and system dirs so
        // fuzzel finds them regardless of $XDG_DATA_HOME / $XDG_DATA_DIRS.
        val appsDir = File(root, ".local/share/applications").apply { mkdirs() }
        val sysAppsDir = File(rootfsDir, "usr/share/applications").apply { mkdirs() }
        val entries = mutableMapOf(
            "foot.desktop" to """
                |[Desktop Entry]
                |Name=Terminal
                |Exec=foot
                |Icon=utilities-terminal
                |Type=Application
                |Categories=System;TerminalEmulator;
                """.trimMargin(),
            "htop.desktop" to """
                |[Desktop Entry]
                |Name=System Monitor
                |Exec=foot -e htop
                |Icon=utilities-system-monitor
                |Type=Application
                |Categories=System;Monitor;
                """.trimMargin(),
        )
        data class AppEntry(val file: String, val binary: String, val content: String)
        val conditional = listOf(
            AppEntry("thunar.desktop", "usr/bin/thunar", """
                |[Desktop Entry]
                |Name=File Manager
                |Exec=thunar
                |Icon=system-file-manager
                |Type=Application
                |Categories=System;FileManager;
                """.trimMargin()),
            AppEntry("mousepad.desktop", "usr/bin/mousepad", """
                |[Desktop Entry]
                |Name=Text Editor
                |Exec=mousepad
                |Icon=accessories-text-editor
                |Type=Application
                |Categories=Utility;TextEditor;
                """.trimMargin()),
            AppEntry("imv.desktop", "usr/bin/imv-wayland", """
                |[Desktop Entry]
                |Name=Image Viewer
                |Exec=imv-wayland
                |Icon=image-x-generic
                |Type=Application
                |Categories=Graphics;Viewer;
                """.trimMargin()),
            AppEntry("mpv.desktop", "usr/bin/mpv", """
                |[Desktop Entry]
                |Name=Media Player
                |Exec=mpv --player-operation-mode=pseudo-gui
                |Icon=multimedia-video-player
                |Type=Application
                |Categories=AudioVideo;Video;
                """.trimMargin()),
        )
        for (app in conditional) {
            if (File(rootfsDir, app.binary).exists()) entries[app.file] = app.content
        }
        for ((name, content) in entries) {
            File(appsDir, name).writeText(content)
            File(sysAppsDir, name).writeText(content)
        }
        Log.d(TAG, "Seeded fuzzel + ${entries.size} .desktop entries for nested-Wayland")
    }

    private fun writeDesktopConfigs() {
        val rootfsDir = activeRootfsDir
        val root = File(rootfsDir, "root")

        // waybar config — Xfce-style panel with quick-launch buttons and system info
        File(root, ".config/waybar").mkdirs()
        File(root, ".config/waybar/config").writeText(
            """
            |{
            |    "layer": "top",
            |    "position": "bottom",
            |    "height": 26,
            |    "spacing": 0,
            |    "modules-left": [
            |        "custom/apps",
            |        "custom/terminal",
            |        "custom/files",
            |        "custom/editor"
            |    ],
            |    "modules-center": [],
            |    "modules-right": [
            |        "cpu",
            |        "memory",
            |        "clock"
            |    ],
            |    "custom/apps": {
            |        "format": "Apps",
            |        "on-click": "fuzzel"
            |    },
            |    "custom/terminal": {
            |        "format": "Terminal",
            |        "on-click": "xfce4-terminal || foot"
            |    },
            |    "custom/files": {
            |        "format": "Files",
            |        "on-click": "thunar || foot -e ls"
            |    },
            |    "custom/editor": {
            |        "format": "Edit",
            |        "on-click": "mousepad"
            |    },
            |    "cpu": {
            |        "format": "CPU {usage}%",
            |        "interval": 5,
            |        "tooltip": true
            |    },
            |    "memory": {
            |        "format": "RAM {percentage}%",
            |        "interval": 5,
            |        "tooltip-format": "{used:0.1f}G / {total:0.1f}G"
            |    },
            |    "clock": {
            |        "format": "{:%H:%M}",
            |        "format-alt": "{:%a %d %b %H:%M}",
            |        "tooltip-format": "<tt>{calendar}</tt>"
            |    }
            |}
            """.trimMargin()
        )
        File(root, ".config/waybar/style.css").writeText(
            """
            |* {
            |    font-family: "Noto Sans", sans-serif;
            |    font-size: 13px;
            |    min-height: 0;
            |}
            |window#waybar {
            |    background-color: rgba(43, 48, 59, 0.95);
            |    color: #d8dee9;
            |    border-top: 1px solid rgba(100, 114, 125, 0.4);
            |}
            |button {
            |    border: none;
            |    border-radius: 0;
            |}
            |button:hover {
            |    background: rgba(255, 255, 255, 0.1);
            |}
            |#custom-apps {
            |    padding: 0 8px;
            |    font-weight: bold;
            |    background-color: rgba(94, 129, 172, 0.3);
            |    border-right: 1px solid rgba(100, 114, 125, 0.3);
            |}
            |#custom-apps:hover {
            |    background-color: rgba(94, 129, 172, 0.5);
            |}
            |#custom-terminal, #custom-files, #custom-editor {
            |    padding: 0 12px;
            |}
            |#cpu, #memory {
            |    padding: 0 10px;
            |    color: #a3be8c;
            |}
            |#memory {
            |    color: #ebcb8b;
            |}
            |#clock {
            |    padding: 0 8px;
            |    font-weight: bold;
            |}
            """.trimMargin()
        )

        // fuzzel config — large font for touch
        File(root, ".config/fuzzel").mkdirs()
        File(root, ".config/fuzzel/fuzzel.ini").writeText(
            """
            |[main]
            |font=Noto Sans:size=11
            |width=30
            |lines=15
            |prompt=>
            |layer=overlay
            |launch-prefix=/usr/local/bin/launch
            """.trimMargin()
        )

        // foot terminal config — no title bar (SSD wastes space on mobile)
        File(root, ".config/foot").mkdirs()
        File(root, ".config/foot/foot.ini").writeText(
            """
            |[main]
            |font=monospace:size=11
            |pad=2x2
            |
            |[csd]
            |preferred=none
            """.trimMargin()
        )

        writeLabwcMenu()

        // .desktop files so fuzzel has something to show.
        // Write to both user and system dirs — fuzzel may not resolve
        // $HOME inside dbus-run-session depending on environment.
        val appsDir = File(root, ".local/share/applications").apply { mkdirs() }
        val sysAppsDir = File(rootfsDir, "usr/share/applications").apply { mkdirs() }
        // Always-available entries
        val desktopEntries = mutableMapOf(
            "foot.desktop" to """
                |[Desktop Entry]
                |Name=Terminal
                |Exec=foot
                |Icon=utilities-terminal
                |Type=Application
                |Categories=System;TerminalEmulator;
                """.trimMargin(),
            "htop.desktop" to """
                |[Desktop Entry]
                |Name=System Monitor
                |Exec=foot -e htop
                |Icon=utilities-system-monitor
                |Type=Application
                |Categories=System;Monitor;
                """.trimMargin(),
        )

        // Conditional entries — only written if the binary exists
        data class AppEntry(val file: String, val binary: String, val content: String)
        val conditionalApps = listOf(
            AppEntry("thunar.desktop", "usr/bin/thunar", """
                |[Desktop Entry]
                |Name=File Manager
                |Exec=thunar
                |Icon=system-file-manager
                |Type=Application
                |Categories=System;FileManager;
                """.trimMargin()),
            AppEntry("mousepad.desktop", "usr/bin/mousepad", """
                |[Desktop Entry]
                |Name=Text Editor
                |Exec=mousepad
                |Icon=accessories-text-editor
                |Type=Application
                |Categories=Utility;TextEditor;
                """.trimMargin()),
            AppEntry("imv.desktop", "usr/bin/imv-wayland", """
                |[Desktop Entry]
                |Name=Image Viewer
                |Exec=imv-wayland
                |Icon=image-x-generic
                |Type=Application
                |Categories=Graphics;Viewer;
                |MimeType=image/png;image/jpeg;image/gif;image/bmp;image/webp;
                """.trimMargin()),
            AppEntry("mpv.desktop", "usr/bin/mpv", """
                |[Desktop Entry]
                |Name=Media Player
                |Exec=mpv --player-operation-mode=pseudo-gui
                |Icon=multimedia-video-player
                |Type=Application
                |Categories=AudioVideo;Video;
                |MimeType=video/mp4;video/webm;audio/mp3;audio/ogg;
                """.trimMargin()),
        )
        for (app in conditionalApps) {
            if (File(rootfsDir, app.binary).exists()) {
                desktopEntries[app.file] = app.content
            }
        }

        for ((name, content) in desktopEntries) {
            File(appsDir, name).writeText(content)
            File(sysAppsDir, name).writeText(content)
        }

        Log.d(TAG, "Desktop config files written")
    }

    /** Write labwc right-click desktop menu. References apps that may or may not be installed. */
    private fun writeLabwcMenu() {
        val labwcDir = File(activeRootfsDir, "root/.config/labwc")
        labwcDir.mkdirs()
        File(labwcDir, "menu.xml").writeText(
            """
            |<?xml version="1.0" ?>
            |<openbox_menu>
            |  <menu id="root-menu" label="">
            |    <item label="Terminal"><action name="Execute" command="foot"/></item>
            |    <item label="File Manager"><action name="Execute" command="thunar"/></item>
            |    <separator />
            |    <item label="Apps"><action name="Execute" command="fuzzel"/></item>
            |  </menu>
            |</openbox_menu>
            """.trimMargin()
        )
    }

    fun resetDesktopState() {
        _desktopState.value = DesktopSetupState.Idle
    }

    /**
     * Delete the rootfs to free space. Also cleans up any pre-issue-#162
     * legacy `alpine/` directory if the migration was skipped.
     */
    fun deleteRootfs() {
        if (!forceDeleteRecursively(activeRootfsDir)) {
            Log.w(TAG, "deleteRootfs: could not fully remove ${activeRootfsDir.absolutePath}")
        }
        // Belt-and-braces: if the legacy alpine/ dir was never renamed
        // (rare migration failure path), free it too.
        if (activeDistroId == DistroCatalog.DEFAULT_ID) {
            forceDeleteRecursively(File(context.filesDir, "proot/rootfs/alpine"))
        }
        _state.value = SetupState.NotInstalled
    }

    /**
     * Delete a specific distro's rootfs by id. If the deleted distro
     * was active, switches active to the first remaining installed
     * distro (or DEFAULT_ID if none remain) and re-evaluates ready
     * state. Used by the distro picker's per-row delete button —
     * the recovery path for a broken-state install.
     */
    fun deleteDistro(distroId: String) {
        val dir = rootfsDirFor(distroId)
        if (!forceDeleteRecursively(dir)) {
            Log.w(TAG, "deleteDistro: could not fully remove ${dir.absolutePath}")
        }
        // Belt-and-braces: nuke the legacy alpine/ path if we were
        // asked to delete the default distro and it still exists
        // there too (one-off migration edge case).
        if (distroId == DistroCatalog.DEFAULT_ID) {
            forceDeleteRecursively(File(context.filesDir, "proot/rootfs/alpine"))
        }
        if (activeDistroId == distroId) {
            reconcileActiveDistro()
        }
        _state.value = if (isReady) SetupState.Ready else SetupState.NotInstalled
        Log.d(TAG, "Deleted distro: $distroId")
    }
}
