package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.haven.core.local.proot.LaunchSpec
import sh.haven.core.wayland.WaylandBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DesktopManager"

/**
 * Manages multiple desktop environment processes running simultaneously.
 * Each desktop gets its own X11 display number and VNC port.
 * Native Wayland is limited to one instance (WaylandBridge is singleton).
 *
 * X11/VNC desktops use software rendering to avoid virgl contention
 * with the native Wayland compositor.
 */
@Singleton
class DesktopManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class DesktopState { STOPPED, STARTING, RUNNING, ERROR }

    data class DesktopInstance(
        val de: ProotManager.DesktopEnvironment,
        val displayNumber: Int,
        val vncPort: Int,
        val state: DesktopState,
        val errorMessage: String? = null,
    )

    private val _desktops = MutableStateFlow<Map<ProotManager.DesktopEnvironment, DesktopInstance>>(emptyMap())
    val desktops: StateFlow<Map<ProotManager.DesktopEnvironment, DesktopInstance>> = _desktops.asStateFlow()

    // Internal process tracking (not exposed in DesktopInstance)
    private val processes = mutableMapOf<ProotManager.DesktopEnvironment, Process>()

    // Rolling tail of stdout/stderr per DE (most recent 20 lines), so that
    // a script-exit-during-startup can be reported with context rather
    // than just "didn't come up". Used by the exit thread when state is
    // still STARTING at process exit (#162 bug B, Arch silent fail —
    // there is no early signal otherwise).
    private val logTails = mutableMapOf<ProotManager.DesktopEnvironment, ArrayDeque<String>>()
    private val logTailLimit = 20

    // Display number allocation for X11 desktops
    private val usedDisplays = mutableSetOf<Int>()

    // Per-(distro, DE) VNC port preference. Empty when the user accepted
    // the default at install time; non-zero when the install dialog's
    // port field was edited. Reads/writes go through getPortPreference /
    // setPortPreference so the SharedPreferences instance is consulted
    // once per startDesktop rather than once per accessor.
    private val portPrefs by lazy {
        context.getSharedPreferences("desktop-port-prefs", Context.MODE_PRIVATE)
    }

    init {
        // Kill orphaned Xvnc processes from previous app instances
        killAllOrphanedXvnc()
    }

    private fun allocateDisplay(): Int {
        var display = 1
        while (display in usedDisplays) display++
        usedDisplays.add(display)
        return display
    }

    private fun releaseDisplay(display: Int) {
        usedDisplays.remove(display)
    }

    /**
     * Per-(distro, DE) port preference. Returns 0 when no preference is
     * stored — caller falls back to [allocateDisplay] + 5900. Stored
     * keys are `<distroId>_<deId>` so the same DE on different distros
     * (Alpine xfce4 vs Debian xfce4) keeps independent ports.
     */
    fun getPortPreference(distroId: String, deId: String): Int {
        return portPrefs.getInt("${distroId}_${deId}", 0)
    }

    fun setPortPreference(distroId: String, deId: String, port: Int) {
        portPrefs.edit().putInt("${distroId}_${deId}", port).apply()
    }

    /**
     * Suggest the next free VNC port for a new install on [distroId].
     * Considers ports currently in use by running DEs and ports already
     * pinned by other installed DEs' preferences. Returns the first
     * unused 5900+N. Defaults to 5901 when nothing is in play.
     */
    fun suggestNextVncPort(distroId: String): Int {
        val takenByRunning = _desktops.value.values.map { it.vncPort }.toSet()
        val takenByPrefs = portPrefs.all.entries
            .asSequence()
            .filter { it.key.startsWith("${distroId}_") }
            .mapNotNull { (it.value as? Int)?.takeIf { v -> v > 0 } }
            .toSet()
        val taken = takenByRunning + takenByPrefs
        var port = 5901
        while (port in taken) port++
        return port
    }

    /**
     * Start a desktop environment.
     * X11/VNC desktops use software rendering (no virgl).
     * Native Wayland uses the JNI compositor + virgl for GPU acceleration.
     */
    fun startDesktop(
        de: ProotManager.DesktopEnvironment,
        shellCommand: String = "/bin/sh -l",
    ) {
        // Stop any existing instance first (handles stale state from crashes)
        if (_desktops.value.containsKey(de)) {
            stopDesktop(de)
        }

        when (de.spec.launch) {
            is LaunchSpec.NativeCompositor -> {
                if (!WaylandBridge.isAvailable) {
                    _desktops.update { it + (de to DesktopInstance(
                        de, 0, 0, DesktopState.ERROR,
                        errorMessage = "Native compositor is unavailable for this device ABI",
                    )) }
                    return
                }
                if (WaylandBridge.isRunning()) {
                    _desktops.update { it + (de to DesktopInstance(
                        de, 0, 0, DesktopState.ERROR,
                        errorMessage = "Native compositor already running",
                    )) }
                    return
                }
                startNativeCompositor(de, shellCommand)
                return
            }
            is LaunchSpec.NestedWayland -> {
                // Phase 4: launch is structurally identical to X11Vnc up to
                // the per-DE process-spawn step. Allocate display + port
                // through the same code, then call launchNestedWayland().
                // Fall through to the shared X11/Nested branch below; the
                // per-DE process spawner is dispatched on launch type.
                //
                // Migrate a superseded Haven config first (e.g. the
                // fuzzel→foot autostart swap, #162) so existing installs
                // with a frozen write-if-absent config pick up the fix on
                // the next start rather than only on a fresh install.
                prootManager.migrateDesktopConfigs(de)
            }
            is LaunchSpec.X11Vnc -> {
                // Falls through to the X11/VNC launch below.
            }
        }

        // Honour the user's per-DE port preference (set at install time
        // in the Manage view) when present. The preference is stored as
        // an absolute port (5900-base); display = port - 5900 maps it
        // back into the X11 display-number space the Xvnc command line
        // expects. Falls back to allocateDisplay when unset OR when the
        // preferred display is already in use (multiple DEs pinned to
        // the same port — Manage view should prevent this but defend).
        val preferredPort = getPortPreference(prootManager.activeDistroId, de.spec.id)
        val display: Int
        val port: Int
        if (preferredPort in 5901..5999) {
            val candidateDisplay = preferredPort - 5900
            if (candidateDisplay !in usedDisplays) {
                display = candidateDisplay
                port = preferredPort
                usedDisplays.add(display)
            } else {
                Log.w(TAG, "preferred port $preferredPort for ${de.spec.id} already in use; falling back")
                display = allocateDisplay()
                port = 5900 + display
            }
        } else {
            display = allocateDisplay()
            port = 5900 + display
        }
        _desktops.update { it + (de to DesktopInstance(de, display, port, DesktopState.STARTING)) }
        synchronized(logTails) { logTails[de] = ArrayDeque() }

        try {
            val process = when (de.spec.launch) {
                is LaunchSpec.NestedWayland -> launchNestedWayland(de, display, port)
                is LaunchSpec.X11Vnc -> launchX11Desktop(de, display, shellCommand)
                is LaunchSpec.NativeCompositor ->
                    error("NativeCompositor handled above; unreachable")
            }
            processes[de] = process
            // Stays at STARTING — flips to RUNNING via `markRunning(de)`
            // once the caller (DesktopViewModel.startDesktop) confirms the
            // VNC port is listening. If the proot script exits before
            // that signal arrives, the exit thread transitions to ERROR
            // with the log tail attached, so the UI gets a real diagnosis
            // instead of an 8-second silent timeout (#162 bug B).

            // Log output on a background thread, also tee'd into the
            // rolling tail buffer so the exit handler can attach context.
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "${de.label}[:$display]: $line")
                        synchronized(logTails) {
                            val tail = logTails[de] ?: return@synchronized
                            tail.addLast(line)
                            while (tail.size > logTailLimit) tail.removeFirst()
                        }
                    }
                } catch (_: Exception) {}
                val exitCode = process.waitFor()
                Log.d(TAG, "${de.label}[:$display] exited: $exitCode")
                _desktops.update { current ->
                    val instance = current[de] ?: return@update current
                    when (instance.state) {
                        DesktopState.RUNNING -> {
                            releaseDisplay(display)
                            processes.remove(de)
                            synchronized(logTails) { logTails.remove(de) }
                            current - de
                        }
                        DesktopState.STARTING -> {
                            releaseDisplay(display)
                            processes.remove(de)
                            val tail = synchronized(logTails) {
                                logTails.remove(de)?.toList().orEmpty()
                            }
                            val message = buildString {
                                append("${de.label} exited during startup (code $exitCode)")
                                if (tail.isNotEmpty()) {
                                    append(":\n")
                                    append(tail.takeLast(8).joinToString("\n"))
                                }
                            }
                            current + (de to DesktopInstance(
                                de, display, port, DesktopState.ERROR, errorMessage = message,
                            ))
                        }
                        else -> current
                    }
                }
            }, "desktop-${de.name}-log").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ${de.label}", e)
            releaseDisplay(display)
            synchronized(logTails) { logTails.remove(de) }
            _desktops.update { it + (de to DesktopInstance(
                de, display, port, DesktopState.ERROR,
                errorMessage = e.message,
            )) }
        }
    }

    /**
     * Caller (DesktopViewModel) signals that the desktop has reached a
     * usable state — VNC port listening for X11Vnc/NestedWayland, or
     * the native compositor reporting alive. Flips state from STARTING
     * to RUNNING. No-op if the entry is no longer STARTING (e.g. the
     * exit thread already transitioned it to ERROR because the script
     * crashed in the same window).
     */
    fun markRunning(de: ProotManager.DesktopEnvironment) {
        _desktops.update { current ->
            val instance = current[de] ?: return@update current
            if (instance.state == DesktopState.STARTING) {
                current + (de to instance.copy(state = DesktopState.RUNNING))
            } else current
        }
    }

    /**
     * Runtime log of a nested-Wayland desktop's compositor — the contents
     * of the compositor.log that [launchNestedWayland] redirects the
     * sway/Hyprland/niri process's stdout+stderr into
     * (`$XDG_RUNTIME_DIR/compositor.log`, where XDG_RUNTIME_DIR is
     * `/tmp/xdg-runtime-<display>` and /tmp is bound to the app cacheDir).
     * Returns null for non-nested-Wayland DEs, or when the instance/log
     * isn't present. Exposed for the read_desktop_log MCP endpoint so
     * grey-screen / no-frames diagnosis doesn't need a proot shell.
     */
    fun compositorLogFor(de: ProotManager.DesktopEnvironment): String? {
        if (de.spec.launch !is LaunchSpec.NestedWayland) return null
        val display = _desktops.value[de]?.displayNumber ?: return null
        val logFile = File(context.cacheDir, "xdg-runtime-$display/compositor.log")
        return if (logFile.exists()) logFile.readText() else null
    }

    /**
     * Rolling tail (last [logTailLimit] lines) of the launch process's
     * own stdout/stderr — the `[yySlide]` progress markers, wayvnc output,
     * and any pre-compositor errors. Captured live in [startDesktop]'s
     * reader thread; cleared when the process exits cleanly.
     */
    fun capturedOutputFor(de: ProotManager.DesktopEnvironment): List<String> =
        synchronized(logTails) { logTails[de]?.toList().orEmpty() }

    /**
     * Stop a running desktop environment.
     */
    fun stopDesktop(de: ProotManager.DesktopEnvironment) {
        val instance = _desktops.value[de] ?: return
        val launch = de.spec.launch
        if (launch is LaunchSpec.NativeCompositor) {
            if (WaylandBridge.isRunning()) {
                WaylandBridge.stopIfAvailable()
            }
            WaylandBridge.stopVirglServerIfAvailable()
            WaylandSocketHelper.tryRemoveSymlink()
        }
        processes[de]?.destroyForcibly()
        processes.remove(de)
        if (launch !is LaunchSpec.NativeCompositor) {
            // destroyForcibly() only kills the proot launch shell —
            // children (Xvnc / sway / Hyprland / niri / wayvnc / foot)
            // become orphans reparented to the app, kept running until
            // they exit on their own. Sweep them explicitly so a
            // subsequent start_desktop doesn't hit "Another wayvnc
            // process is already running" on the control socket or a
            // wedged port 590x.
            if (launch is LaunchSpec.X11Vnc) {
                killOrphanedXvnc(instance.displayNumber)
            } else if (launch is LaunchSpec.NestedWayland) {
                killOrphanedNestedWayland(launch.compositorCmd)
            }
            releaseDisplay(instance.displayNumber)
        }
        _desktops.update { it - de }
    }

    /**
     * Stop all running desktops.
     */
    fun stopAll() {
        _desktops.value.keys.toList().forEach { stopDesktop(it) }
    }

    /**
     * Get the VNC port allocated for a desktop. Returns the port for
     * both STARTING and RUNNING entries — callers polling the socket
     * to confirm readiness (DesktopViewModel.startDesktop) need to know
     * the target port before the state has flipped to RUNNING. Returns
     * null for STOPPED / ERROR / missing entries.
     */
    fun getVncPort(de: ProotManager.DesktopEnvironment): Int? =
        _desktops.value[de]?.takeIf {
            it.state == DesktopState.RUNNING || it.state == DesktopState.STARTING
        }?.vncPort

    // ---- Agent capture / window enumeration (X11/VNC desktops) ----

    /** A visible top-level X11 window on a running desktop. */
    data class WindowInfo(
        val id: String,
        val title: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Resolve a running X11/VNC desktop's display number, throwing a
     * caller-friendly message otherwise. Capture and enumeration only
     * make sense for X11Vnc desktops driven by Xvnc — nested-Wayland
     * desktops render through wayvnc and have no X display to talk to.
     */
    private fun requireX11Display(de: ProotManager.DesktopEnvironment): Int {
        val instance = _desktops.value[de]
            ?: throw IllegalStateException("Desktop '${de.spec.id}' is not running")
        if (de.spec.launch !is LaunchSpec.X11Vnc) {
            throw IllegalStateException(
                "Capture/enumeration is only supported for X11/VNC desktops; " +
                    "'${de.spec.id}' is ${de.spec.launch::class.simpleName}",
            )
        }
        return instance.displayNumber
    }

    /**
     * Enumerate visible top-level windows on a running X11 desktop using
     * `xdotool`. Each window is emitted as a single pipe-delimited line
     * (window names have '|' and newlines squashed to spaces) so the
     * output parses unambiguously. Installs the capture toolset on first
     * use via [ProotManager.ensureCaptureTools].
     */
    suspend fun listWindows(de: ProotManager.DesktopEnvironment): List<WindowInfo> {
        val display = requireX11Display(de)
        val (ready, detail) = prootManager.ensureCaptureTools()
        if (!ready) throw IllegalStateException(detail)
        // For each visible named window: eval the --shell geometry (sets
        // X/Y/WIDTH/HEIGHT), squash the name to one field, emit one line.
        val script = buildString {
            append("export DISPLAY=:$display; export XAUTHORITY=/root/.Xauthority; ")
            append("for id in \$(xdotool search --onlyvisible --name '.*' 2>/dev/null); do ")
            append("g=\$(xdotool getwindowgeometry --shell \$id 2>/dev/null) || continue; ")
            append("eval \"\$g\"; ")
            append("n=\$(xdotool getwindowname \$id 2>/dev/null | tr '|\\n' '  '); ")
            append("echo \"WIN|\$id|\$X|\$Y|\$WIDTH|\$HEIGHT|\$n\"; ")
            append("done")
        }
        val (out, _) = prootManager.runCommandInProot(script)
        return out.lineSequence()
            .filter { it.startsWith("WIN|") }
            .mapNotNull { line ->
                val p = line.split("|")
                if (p.size < 7) return@mapNotNull null
                val x = p[2].toIntOrNull() ?: return@mapNotNull null
                val y = p[3].toIntOrNull() ?: return@mapNotNull null
                val w = p[4].toIntOrNull() ?: return@mapNotNull null
                val h = p[5].toIntOrNull() ?: return@mapNotNull null
                WindowInfo(
                    id = p[1],
                    title = p.drop(6).joinToString("|").trim(),
                    x = x, y = y, width = w, height = h,
                )
            }
            .toList()
    }

    /**
     * Capture the whole root window of a running X11 desktop as PNG bytes
     * via ImageMagick's `import`. Window-level cropping is done by the
     * caller (which has the window geometry from [listWindows]) so this
     * stays a single, reliable code path — `import -window root` always
     * works on bare Xvnc, whereas per-window-id capture is finicky.
     *
     * The PNG is written to `/tmp` (bound to the app cacheDir), read back
     * directly off the app's filesystem, then deleted.
     */
    suspend fun capture(de: ProotManager.DesktopEnvironment): ByteArray {
        val display = requireX11Display(de)
        val (ready, detail) = prootManager.ensureCaptureTools()
        if (!ready) throw IllegalStateException(detail)
        val fname = "haven-cap-${System.currentTimeMillis()}.png"
        val script =
            "export DISPLAY=:$display; export XAUTHORITY=/root/.Xauthority; " +
                "rm -f /tmp/$fname; " +
                "import -window root +repage /tmp/$fname > /tmp/$fname.log 2>&1; echo EXIT:\$?"
        val (out, _) = prootManager.runCommandInProot(script)
        val file = File(context.cacheDir, fname)
        val logFile = File(context.cacheDir, "$fname.log")
        try {
            if (!file.exists() || file.length() == 0L) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw IllegalStateException(
                    "Capture produced no image (import: ${log.take(400).trim()}; $out)",
                )
            }
            return file.readBytes()
        } finally {
            file.delete()
            logFile.delete()
        }
    }

    // Apps launched into a running desktop via launch_app_in_desktop,
    // keyed by an opaque appId. We hold the Process so its reader thread
    // keeps draining the combined stdout+stderr (so a chatty GL app never
    // blocks on a full pipe); proot stays alive for the app's lifetime
    // because the command runs in the foreground (exec), so --kill-on-exit
    // doesn't reap the GUI app.
    private val appProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    /**
     * Launch a GUI [command] into the running X11 desktop [de] with
     * DISPLAY/XAUTHORITY/HOME and the software-GL fallback exported, so
     * GPU-less GL apps (KiCad/eeschema) don't crash their canvas. Returns
     * an opaque appId. Fire-and-forget: callers poll [listWindows] to learn
     * when the window appears. Throws if [de] is not a running X11 desktop.
     */
    fun launchApp(de: ProotManager.DesktopEnvironment, command: String): String {
        val display = requireX11Display(de)
        val appId = "deapp-${System.currentTimeMillis()}"
        val env =
            "export DISPLAY=:$display; export XAUTHORITY=/root/.Xauthority; export HOME=/root; " +
                "export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe; "
        val proc = prootManager.startCommandInProot("$env exec $command")
        appProcesses[appId] = proc
        Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "$appId[:$display]: $line")
                }
            } catch (_: Exception) {
            } finally {
                appProcesses.remove(appId)
            }
        }, appId).apply { isDaemon = true }.start()
        return appId
    }

    // ---- X11/VNC desktop launch (software rendering, no virgl) ----

    private fun launchX11Desktop(
        de: ProotManager.DesktopEnvironment,
        display: Int,
        shellCommand: String,
    ): Process {
        val prootBin = prootManager.prootBinary
            ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(
            context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
        ).absolutePath
        val rootfsDir = prootManager.activeRootfsDir
        val rootHome = File(rootfsDir, "root").apply { mkdirs() }

        val launch = de.spec.launch as LaunchSpec.X11Vnc

        // Clean lock files for this display
        File(context.cacheDir, ".X${display}-lock").delete()
        File(rootHome, ".ICEauthority").apply { if (!exists()) createNewFile() }
        File(rootHome, ".Xauthority").apply { if (!exists()) createNewFile() }

        val passwdFile = File(rootfsDir, "root/.vnc/passwd")
        val useAuth = passwdFile.exists() && passwdFile.length() >= 8
        val securityArg = if (useAuth) {
            "-SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd"
        } else {
            "-SecurityTypes None"
        }
        Log.d(TAG, "Starting Xvnc :$display: useAuth=$useAuth")

        val shellCmd =
            "rm -f /tmp/.X${display}-lock /tmp/.X11-unix/X${display} && " +
                "Xvnc :${display} -geometry 1280x720 " +
                "$securityArg " +
                "-BlacklistThreshold 10000 " +
                "-localhost 0 & " +
                "sleep 3; " +
                "export DISPLAY=:${display}; " +
                "export HOME=/root; " +
                // NO virgl — software rendering for VNC desktops. Force the
                // Mesa software rasteriser so GPU-less GL apps (KiCad/eeschema)
                // don't crash their OpenGL canvas, and paint a solid root so a
                // never-drawn desktop isn't a pure-black capture. Both
                // best-effort — never fail the launch if the tool is absent.
                "export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe; " +
                "xsetroot -solid '#202830' 2>/dev/null || true; " +
                "${launch.startCommands} " +
                "wait"

        val prootArgs = mutableListOf(
            prootBin, "-0", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            // Writable /dev/shm (Android /dev is read-only, no shm) so GL/Mono
            // desktop apps that use POSIX shared memory work. See ProotManager.
            "-b", "${prootManager.ensureDevShm()}:/dev/shm",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
        )
        // /bin/sh works on both Alpine (symlink to busybox) and Debian
        // (symlink to dash). See ProotManager.runCommandInProot.
        prootArgs.addAll(listOf("-w", "/root", "/bin/sh", "-c", shellCmd))

        return ProcessBuilder(prootArgs).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
            }
            redirectErrorStream(true)
        }.start()
    }

    // ---- Nested Wayland (wlroots headless backend + wayvnc) ----

    /**
     * Launch a wlroots-based compositor (Sway / Hyprland / niri) on
     * the headless backend inside the proot rootfs, exposed to the
     * outside world via `wayvnc` listening on [port]. The in-app VNC
     * client connects to localhost:port through the same path it uses
     * for Xvnc desktops, so the connect-side UI is unchanged.
     *
     * Auth model: this initial Phase 4 launches wayvnc with no
     * password (matches the existing X11 "-SecurityTypes None" fallback
     * when no .vnc/passwd exists). wayvnc's username/password auth
     * landed in 0.7+; supported distro packages range from 0.5 to 0.9,
     * so wiring auth is deferred until the version floor lifts.
     *
     * Singleton-friendly: each running NestedWayland DE gets its own
     * XDG_RUNTIME_DIR under `/tmp/xdg-runtime-<display>`. Multiple
     * nested compositors can run concurrently as long as their
     * displays differ.
     */
    private fun launchNestedWayland(
        de: ProotManager.DesktopEnvironment,
        display: Int,
        port: Int,
    ): Process {
        val prootBin = prootManager.prootBinary
            ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(
            context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
        ).absolutePath
        val rootfsDir = prootManager.activeRootfsDir
        File(rootfsDir, "root").mkdirs()

        val launch = de.spec.launch as LaunchSpec.NestedWayland
        val xdgInProot = "/tmp/xdg-runtime-$display"

        // wlroots' SHM allocator (the only buffer path on the headless
        // backend with WLR_RENDERER=pixman) calls POSIX shm_open() which
        // glibc maps to "/dev/shm/<random>". Android's /dev tmpfs has no
        // /dev/shm entry, so every buffer allocation fails:
        //   [wlr] [render/swapchain.c:105] Failed to allocate buffer
        // and the headless output never moves out of OUTPUT_POWER_OFF —
        // wayvnc connects but "Pauses frame capture" and the VNC client
        // sees only a grey rectangle. Bind a writable directory from
        // the app cache as /dev/shm inside the proot.
        val devShmHost = File(context.cacheDir, "dev-shm").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }

        // Wlroots headless setup:
        //  - WLR_BACKENDS=headless: skip DRM/libinput entirely.
        //  - WLR_HEADLESS_OUTPUTS=1: create one virtual output at boot.
        //    Without this, wlroots starts with zero outputs and the
        //    compositor's config-time `output HEADLESS-1 ...` directive
        //    has nothing to apply to.
        //  - WLR_LIBINPUT_NO_DEVICES=1: belt-and-braces for the input
        //    side; headless backend already provides virtual seat.
        //  - XKB_DEFAULT_LAYOUT=us: avoid xkbcommon falling back to
        //    /etc/default/keyboard which proot environments lack.
        //
        // niri uses smithay rather than wlroots and reads only some of
        // the above; passing them is harmless and the niri-specific
        // setup (auto-detect headless when no Wayland/DRM is found)
        // does the rest.
        //
        // Script structure: statements are separated by `;`, NOT `&&`.
        // An `&&`-chain that ends with `& ` (background operator) is
        // parsed by POSIX sh as "background the whole list", which would
        // background the env exports as well — so wayvnc would later run
        // with no XDG_RUNTIME_DIR. The semicolons keep each statement in
        // the foreground; only the compositor itself is backgrounded.
        val shellCmd = buildString {
            append("set -e ; ")
            // The compositor proot runs as uid 1000 (`-i 1000:1000`, so
            // Sway/Hyprland's anti-root check passes), but no distro rootfs
            // ships a uid-1000 user. getpwuid(1000) then returns NULL,
            // which foot survives ("falling back to 'sh'") but fuzzel
            // dereferenced and segfaulted (#162 grey screen). Ensure a
            // uid-1000 passwd/group entry exists — idempotent (grep-guarded)
            // so it appends once and works for existing installs too. The
            // real Android app uid owns the rootfs, so the guest can write.
            append("grep -q '^haven:' /etc/passwd 2>/dev/null || echo 'haven:x:1000:1000:yySlide:/root:/bin/bash' >> /etc/passwd ; ")
            append("grep -q '^haven:' /etc/group 2>/dev/null || echo 'haven:x:1000:' >> /etc/group ; ")
            append("mkdir -p $xdgInProot ; chmod 700 $xdgInProot ; ")
            append("export XDG_RUNTIME_DIR=$xdgInProot ; ")
            append("export HOME=/root ; ")
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ; ")
            append("export WLR_BACKENDS=headless ; ")
            append("export WLR_HEADLESS_OUTPUTS=1 ; ")
            append("export WLR_LIBINPUT_NO_DEVICES=1 ; ")
            // Proot environments don't have DRM nodes, so the GLES2
            // renderer can't allocate DMA-buf-backed framebuffers and
            // wayvnc fails with "No supported buffer formats were found"
            // on the ext-image-copy-capture-v1 protocol. The pixman
            // software renderer exposes plain SHM buffers (ARGB8888 /
            // XRGB8888) that wayvnc can capture without GPU help.
            append("export WLR_RENDERER=pixman ; ")
            // Some wlroots versions also honour WLR_NO_HARDWARE_CURSORS
            // and refuse to start without it when running headless on
            // a system that lacks DRM nodes.
            append("export WLR_NO_HARDWARE_CURSORS=1 ; ")
            append("export XKB_DEFAULT_LAYOUT=us ; ")
            append("export XKB_DEFAULT_RULES=evdev ; ")
            append("export XDG_SESSION_TYPE=wayland ; ")
            // Clean any stale wayland-1 from a previous launch so the
            // wait loop polls for a fresh socket and the "did the
            // compositor start?" branch is honest.
            append("rm -f $xdgInProot/wayland-1 $xdgInProot/wayland-1.lock ; ")
            // `set +e` around the compositor launch so a non-zero exit
            // doesn't kill the script before the wait/diagnostic block.
            append("set +e ; ")
            append("${launch.compositorCmd} > $xdgInProot/compositor.log 2>&1 & ")
            append("comp_pid=\$! ; ")
            // Wait up to ~10 s for the wayland socket to appear.
            append("i=0 ; while [ ! -e $xdgInProot/wayland-1 ] && [ \$i -lt 20 ]; do sleep 0.5; i=\$((i+1)); done ; ")
            append("if [ ! -e $xdgInProot/wayland-1 ]; then ")
            append("echo '[yySlide] compositor did not create $xdgInProot/wayland-1 — log tail:' ; ")
            append("tail -n 50 $xdgInProot/compositor.log 2>&1 ; ")
            append("kill \$comp_pid 2>/dev/null ; ")
            append("exit 1 ; ")
            append("fi ; ")
            append("echo '[yySlide] compositor up, starting wayvnc on $port' ; ")
            append("export WAYLAND_DISPLAY=wayland-1 ; ")
            // ~2 s grace period after the wayland socket appears, then
            // launch wayvnc. wayvnc 0.5 (Debian Bookworm) doesn't accept
            // --max-fps; restrict to flags supported across 0.5–0.9.
            // wayvnc exits when the compositor dies.
            append("sleep 2 ; ")
            // Capture-fallback shim: hides ext-image-copy-capture-v1
            // from wayvnc so it falls back to zwlr_screencopy_manager_v1.
            // The wlroots-0.19 headless backend reports zero buffer
            // formats on the ext path and wayvnc would bail with
            // "No supported buffer formats were found". The shim is
            // extracted in ProotManager.setupDesktop when a nested
            // Wayland DE is installed; a missing file does not block
            // the launch (LD_PRELOAD silently ignores nonexistent
            // libraries via the ld.so error path — wayvnc will still
            // come up, just back on the ext path that may grey-screen).
            append("export LD_PRELOAD=/usr/local/lib/haven/libhaven_wayvnc_shim.so ; ")
            append("exec wayvnc --render-cursor 0.0.0.0 $port")
        }

        // Present a non-root uid/gid (1000) instead of the usual `-0`
        // fake-root. Sway's drop_permissions() refuses to start unless
        // `setuid(0)` FAILS (proof that root can't be regained); under
        // `-0` setuid(0) is a no-op success, so Sway bails with "Unable
        // to drop root … refusing to start" (sway/main.c:170) — the #162
        // bug-B failure. `-i 1000:1000` makes the guest a normal user so
        // setuid(0) fails and the check passes. proot doesn't enforce DAC
        // (the real Android app uid owns the whole rootfs), so /root + the
        // seeded config stay readable, and the headless wlroots/pixman
        // path needs no real privileges. Verified: Sway now boots and
        // creates its HEADLESS-1 output.
        val prootArgs = mutableListOf(
            prootBin, "-i", "1000:1000", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            "-b", "${devShmHost.absolutePath}:/dev/shm",
        )
        prootArgs.addAll(listOf("-w", "/root", "/bin/sh", "-c", shellCmd))

        Log.d(TAG, "Starting ${de.label} (nested wayland) on port $port (display $display)")

        return ProcessBuilder(prootArgs).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
            }
            redirectErrorStream(true)
        }.start()
    }

    // ---- Native Wayland compositor (uses JNI + virgl) ----

    private fun startNativeCompositor(
        de: ProotManager.DesktopEnvironment,
        shellCommand: String = "/bin/sh -l",
    ) {
        _desktops.update { it + (de to DesktopInstance(de, 0, 0, DesktopState.STARTING)) }

        try {
            val bridge = WaylandBridge

            // Prepare XDG runtime dir (must be mode 0700, owned by app)
            val xdgDir = File(context.cacheDir, "wayland-xdg").apply {
                mkdirs()
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(true, true)
            }
            // Clean stale sockets
            File(xdgDir, "wayland-0").delete()
            File(xdgDir, "wayland-0.lock").delete()

            // Extract XKB data from assets on first use
            val xkbDir = File(context.filesDir, "xkb")
            if (!File(xkbDir, "rules/evdev").exists()) {
                Log.d(TAG, "Extracting XKB data...")
                extractAssetsDir(context, "xkb", xkbDir)
            }

            // Fontconfig pointing to system fonts
            val fontconfFile = File(context.cacheDir, "fonts.conf")
            if (!fontconfFile.exists()) {
                fontconfFile.writeText("""
                    <?xml version="1.0"?>
                    <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
                    <fontconfig>
                      <dir>/system/fonts</dir>
                      <cachedir>${context.cacheDir.absolutePath}/fontconfig-cache</cachedir>
                    </fontconfig>
                """.trimIndent())
                File(context.cacheDir, "fontconfig-cache").mkdirs()
            }

            // Set up native XWayland wrapper binary
            val xwaylandWrapper = File(
                context.applicationInfo.nativeLibraryDir, "libxwayland_wrapper.so",
            )
            val loaderPathXw = File(
                context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
            ).absolutePath
            val rootfsDir = prootManager.activeRootfsDir
            android.system.Os.setenv("WLR_XWAYLAND", xwaylandWrapper.absolutePath, true)
            android.system.Os.setenv("HAVEN_PROOT_BIN", prootManager.prootBinary ?: "", true)
            android.system.Os.setenv("HAVEN_PROOT_LOADER", loaderPathXw, true)
            android.system.Os.setenv("HAVEN_PROOT_ROOTFS", rootfsDir.absolutePath, true)
            android.system.Os.setenv("HAVEN_CACHE_DIR", context.cacheDir.absolutePath, true)
            android.system.Os.setenv("HAVEN_XDG_DIR", xdgDir.absolutePath, true)
            // Clean stale X11 sockets before compositor starts XWayland
            val x11UnixDir = File(context.cacheDir, ".X11-unix")
            x11UnixDir.deleteRecursively()
            x11UnixDir.mkdirs()
            Log.d(TAG, "Starting native compositor: XDG_RUNTIME_DIR=${xdgDir.absolutePath}")
            bridge.nativeStart(
                xdgRuntimeDir = xdgDir.absolutePath,
                xkbConfigRoot = xkbDir.absolutePath,
                fontconfigFile = fontconfFile.absolutePath,
            )

            // Wait for socket to appear
            val socket = File(xdgDir, "wayland-0")
            var waited = 0
            while (!socket.exists() && waited < 10) {
                Thread.sleep(500)
                waited++
            }
            if (socket.exists()) {
                Log.d(TAG, "Native compositor started, socket: ${socket.absolutePath}")
                WaylandSocketHelper.tryCreateSymlink(socket.absolutePath)
            } else {
                Log.e(TAG, "Native compositor socket not created after ${waited * 500}ms")
            }

            // Start virgl_test_server for GPU-accelerated OpenGL in PRoot apps
            val virglBin = File(context.applicationInfo.nativeLibraryDir, "libvirgl_test_server.so")
            val virglSocket = File(context.cacheDir, ".virgl_test")
            virglSocket.delete()
            if (virglBin.canExecute()) {
                Log.d(TAG, "Starting virgl_test_server...")
                bridge.nativeStartVirglServer(virglBin.absolutePath, virglSocket.absolutePath)
            }

            // Start PRoot with Wayland clients, bind-mounting the native socket
            val prootBin = prootManager.prootBinary ?: run {
                _desktops.update { it + (de to DesktopInstance(
                    de, 0, 0, DesktopState.ERROR,
                    errorMessage = "PRoot not available",
                )) }
                return
            }
            val loaderPath = File(
                context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
            ).absolutePath
            val rootHome = File(rootfsDir, "root").apply { mkdirs() }

            val process = ProcessBuilder(
                prootBin, "-0", "--link2symlink",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${context.cacheDir.absolutePath}:/tmp",
                "-b", "${xdgDir.absolutePath}:/tmp/xdg-runtime",
                "-w", "/root",
                "/bin/sh", "-c",
                "export HOME=/root; " +
                    "export XDG_RUNTIME_DIR=/tmp/xdg-runtime; " +
                    "export XDG_DATA_HOME=/root/.local/share; " +
                    "export XDG_DATA_DIRS=/usr/local/share:/usr/share; " +
                    "export GDK_BACKEND=wayland,x11; " +
                    "export WAYLAND_DISPLAY=wayland-0; " +
                    "unset FONTCONFIG_FILE; " +
                    "unset XKB_CONFIG_ROOT; " +
                    "export TERM=xterm-256color; " +
                    "export SHELL=${shellCommand.split(" ").first()}; " +
                    // virgl GPU passthrough env vars
                    "export GALLIUM_DRIVER=virpipe; " +
                    "export VTEST_SOCKET=/tmp/.virgl_test; " +
                    // App launcher wrapper — invoked via fuzzel's
                    // `launch-prefix=/usr/local/bin/launch` and by labwc's
                    // menu Execute actions. Records each invocation +
                    // launched PID + the spawned command's own stderr to
                    // /tmp/haven-launch.log so we can diagnose tap-launch
                    // failures (see GlassHaven/Haven#161 follow-up: blank
                    // glyphs / fuzzel-doesn't-launch-on-tap). Without
                    // this, fuzzel forks the prefix script with no record
                    // of whether the click was even received.
                    "mkdir -p /usr/local/bin; cat > /usr/local/bin/launch <<'LAUNCHEOF'\n#!/bin/sh\nLOG=/tmp/haven-launch.log\necho \"[\$(date +%H:%M:%S)] launch \$*\" >> \"\$LOG\" 2>/dev/null\n\"\$@\" >> \"\$LOG\" 2>&1 &\necho \"[\$(date +%H:%M:%S)] launched pid=\$!\" >> \"\$LOG\" 2>/dev/null\nLAUNCHEOF\nchmod +x /usr/local/bin/launch; " +
                    // Set up XWayland for X11 app compatibility
                    "mkdir -p /tmp/.X11-unix; " +
                    "i=0; while ! ls /tmp/.X11-unix/X* >/dev/null 2>&1 && [ \$i -lt 5 ]; do sleep 1; i=\$((i+1)); done; " +
                    "if ls /tmp/.X11-unix/X* >/dev/null 2>&1; then " +
                        "XDISP=\$(ls /tmp/.X11-unix/ | sort -r | head -1 | sed 's/X//'); " +
                        "export DISPLAY=:\$XDISP; " +
                        "mkdir -p /etc/profile.d; echo \"export DISPLAY=:\$XDISP\" > /etc/profile.d/display.sh; " +
                    "fi; " +
                    // Auto-start desktop components if installed
                    "if [ -x /usr/bin/waybar ]; then " +
                        "dbus-run-session waybar >/tmp/waybar.log 2>&1 & sleep 2; " +
                    "fi; " +
                    "[ -x /usr/bin/thunar ] && thunar --daemon & " +
                    "foot -e $shellCommand 2>&1; " +
                    "wait",
            ).apply {
                environment().apply {
                    put("HOME", "/root")
                    put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                    put("PROOT_LOADER", loaderPath)
                    remove("FONTCONFIG_FILE")
                    remove("XKB_CONFIG_ROOT")
                }
                redirectErrorStream(true)
            }.start()

            processes[de] = process
            _desktops.update { it + (de to DesktopInstance(de, 0, 0, DesktopState.RUNNING)) }

            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "NativeWayland: $line")
                    }
                } catch (_: Exception) {}
                Log.d(TAG, "NativeWayland PRoot exited: ${process.waitFor()}")
                processes.remove(de)
                _desktops.update { it - de }
            }, "native-wayland-log").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native compositor", e)
            _desktops.update { it + (de to DesktopInstance(
                de, 0, 0, DesktopState.ERROR,
                errorMessage = e.message,
            )) }
        }
    }

    // ---- Helpers ----

    /** Kill orphaned Xvnc process for a specific display number. */
    private fun killOrphanedXvnc(display: Int) {
        try {
            val proc = ProcessBuilder("sh", "-c",
                "ps -A 2>/dev/null | grep 'Xvnc' | grep ':$display' | grep -v grep | awk '{print \$2}'"
            ).redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing orphaned Xvnc[:$display] PIDs: $pids")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanedXvnc($display) failed: ${e.message}")
        }
    }

    /**
     * Sweep the rootfs's compositor + wayvnc + foot leftovers after the
     * proot launcher has been destroyForcibly()'d. Without this they
     * survive as orphans and block the next start_desktop on the
     * wayvncctl control socket. compositorCmd is the binary name from
     * LaunchSpec.NestedWayland (sway / Hyprland / niri); foot is the
     * auto-launched terminal; swaynag pops up on sway config errors.
     *
     * Matched via /proc/<pid>/cmdline rather than COMM because Android's
     * toybox `ps -A -o comm` wraps proot-launched binaries in [brackets]
     * (the kernel sees libproot_loader.so as the loaded ELF and only
     * sets the comm via prctl), so an exact equality match on COMM
     * misses them. cmdline always reflects the program's own argv[0]
     * (e.g. "/usr/sbin/wayvnc --render-cursor 0.0.0.0 5901").
     */
    private fun killOrphanedNestedWayland(compositorCmd: String) {
        // Match against the basename of cmdline argv[0]. The targets
        // include both the active compositor and its standard children
        // (wayvnc, foot, swaynag) so the next launch starts clean.
        val targets = listOf(compositorCmd, "wayvnc", "foot", "swaynag")
        val caseArm = targets.joinToString("|") { "\"$it\"" }
        val scanScript = """
            for p in /proc/[0-9]*; do
                [ -r ${'$'}p/cmdline ] || continue
                first=${'$'}(tr '\0' ' ' < ${'$'}p/cmdline 2>/dev/null | awk '{print ${'$'}1}')
                case "${'$'}{first##*/}" in $caseArm) echo "${'$'}{p##*/}";; esac
            done
        """.trimIndent()
        try {
            val proc = ProcessBuilder("sh", "-c", scanScript)
                .redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing nested-wayland orphans ($compositorCmd tree): ${pids.replace('\n', ' ')}")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            } else {
                Log.d(TAG, "No nested-wayland orphans for $compositorCmd")
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanedNestedWayland($compositorCmd) failed: ${e.message}")
        }
    }

    /** Kill all orphaned Xvnc processes. */
    fun killAllOrphanedXvnc() {
        try {
            val proc = ProcessBuilder("sh", "-c",
                "ps -A 2>/dev/null | grep 'Xvnc' | grep -v grep | awk '{print \$2}'"
            ).redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing all orphaned Xvnc PIDs: $pids")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killAllOrphanedXvnc failed: ${e.message}")
        }
    }

    /** Recursively extract an assets directory to the filesystem. */
    private fun extractAssetsDir(ctx: Context, assetPath: String, destDir: File) {
        val assets = ctx.assets
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            for (child in list) {
                extractAssetsDir(ctx, "$assetPath/$child", File(destDir, child))
            }
        }
    }
}
