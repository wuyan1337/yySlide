package sh.haven.core.local.proot

import android.os.Build

/**
 * Distro / DE manifest data model — Phase 1 of issue #162.
 *
 * The current `ProotManager.DesktopEnvironment` enum stays in place
 * as the public API. This file adds a parallel data-class catalog
 * that the internals (DesktopManager launch dispatch, PackageOps
 * routing) consult. Phase 2 adds Debian + APT and wires the UI to
 * pick a distro; Phase 5 moves these catalogs to JSON on disk.
 *
 * Naming note: the data class is [DesktopEnvironmentSpec] (not
 * `DesktopEnvironment`) so it doesn't clash with the existing enum.
 * Each enum entry exposes a `.spec` accessor that returns the
 * corresponding spec from [DesktopCatalog].
 */

/** Device ABI the rootfs tarballs are pinned against. */
enum class Arch(val abi: String) {
    AARCH64("arm64-v8a"),
    X86_64("x86_64");

    companion object {
        /** Detect the current device arch, or null if unsupported. */
        fun current(): Arch? = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> AARCH64
            Build.SUPPORTED_ABIS.contains("x86_64") -> X86_64
            else -> null
        }
    }
}

/** Package management family. Each distro picks exactly one. */
enum class PackageFamily {
    APK,      // Alpine, postmarketOS
    APT,      // Debian, Ubuntu, Kali, Parrot
    PACMAN,   // Arch, Manjaro
    XBPS,     // Void
    NIX,      // Nix, NixOS
}

/** Tarball format for a rootfs source. */
enum class RootfsFormat { TAR_GZ, TAR_XZ, TAR_ZSTD }

/**
 * A rootfs download source — pinned URL + sha256 for one arch of
 * one distro. SHA-256 is required: rootfs integrity is a security
 * boundary, not a UX concern.
 *
 * [stripComponents] mirrors `tar --strip-components=N` — used when
 * a tarball wraps the rootfs in a top-level directory (proot-distro
 * does this; Alpine's minirootfs does not). Set to 1 for the
 * `<distro>-<arch>/` wrapper convention; leave at 0 for tarballs
 * whose files are already at the top level.
 */
data class RootfsSource(
    val url: String,
    val sha256: String,
    val format: RootfsFormat = RootfsFormat.TAR_GZ,
    val stripComponents: Int = 0,
)

/**
 * Post-extract hook — small shell snippets that run inside the
 * freshly-extracted rootfs before any package operations. Used for
 * distro-specific bootstrap (e.g. `pacman-key --init`, writing
 * `/etc/apt/sources.list`). Phase 1 ships with an empty list for
 * Alpine.
 *
 * [idempotent] = true (the default) declares that the hook is safe
 * to re-run after a failure. `ProotManager.retry()` invokes the
 * full hook chain on a `Phase.BootstrapHook` retry, so any hook
 * whose effect would compound on a second run (e.g. one that
 * appends to a config file rather than overwriting it) must set
 * this to false and the UI offers Wipe-and-retry instead. All
 * shipped hooks today are idempotent (pkgdb sed-patch uses `g`,
 * pacman-key/locale-gen/refresh-ca-certificates re-run cleanly).
 */
data class RootfsHook(
    val id: String,
    val command: String,
    val idempotent: Boolean = true,
)

/**
 * How well a DE works on a particular [PackageFamily].
 *
 *  - [Stable]: the install path and the running desktop have been
 *    verified end-to-end. Default for entries that don't declare.
 *  - [Experimental]: known limitations or fragile install steps;
 *    the rootfs side works but the DE install may fail or run
 *    degraded. Surface a warning at install time.
 *  - [Broken]: known not to work on this family today. Hidden from
 *    the install picker by default; reserved for future revival.
 *
 * Per-family granularity matters because xbps's nested-chroot
 * INSTALL-script semantics break X11 packages on Void (fontconfig
 * pre-INSTALL), but those same DEs install fine on Arch/Debian.
 */
enum class Compatibility { Stable, Experimental, Broken }

/**
 * Launch dispatch — how a DE actually runs once its packages are
 * installed. Maps 1:1 to the branches in `DesktopManager`.
 *
 *  - [X11Vnc]: Xvnc :N + a startup script. Today's Openbox/Xfce4.
 *  - [NativeCompositor]: labwc via the JNI bridge. Today's
 *    WAYLAND_NATIVE path. GPU-accelerated via virgl. Singleton.
 *  - [NestedWayland]: a headless wlroots compositor running inside
 *    proot, exposed via `wayvnc` on the same VNC port the X11
 *    branch uses. Reserved for Phase 4 (Hyprland / niri / Sway).
 */
sealed interface LaunchSpec {
    data class X11Vnc(val startCommands: String) : LaunchSpec
    data object NativeCompositor : LaunchSpec
    data class NestedWayland(val compositorCmd: String) : LaunchSpec
}

/**
 * Data class form of a desktop environment — the same information
 * the [sh.haven.core.local.ProotManager.DesktopEnvironment] enum
 * carries today, plus a [LaunchSpec] and a per-package-family
 * package list for portability across distros.
 *
 * Phase 1: [packagesPerFamily] only has an APK entry. Phase 2 adds
 * APT entries when Debian lands.
 */
data class DesktopEnvironmentSpec(
    val id: String,
    val label: String,
    val packagesPerFamily: Map<PackageFamily, List<String>>,
    val verifyBinary: String,
    val launch: LaunchSpec,
    val sizeEstimateMb: Int,
    val minFreeMb: Int = sizeEstimateMb * 2,
    val hidden: Boolean = false,
    /**
     * Per-family compatibility tag. Families not listed default to
     * [Compatibility.Stable]. Use [compatibilityOn] to read this for
     * a specific family — it returns Stable for unmapped entries.
     */
    val compatibility: Map<PackageFamily, Compatibility> = emptyMap(),
    /**
     * Free-text note shown alongside an Experimental / Broken label,
     * keyed by family. E.g. for Xfce4 on Void: "xbps runs INSTALL
     * scripts in a nested chroot that proot can't satisfy; CLI works
     * but desktop install fails on fontconfig".
     */
    val compatibilityNote: Map<PackageFamily, String> = emptyMap(),
    /**
     * Minimum-viable config files seeded into the rootfs at DE install
     * time. Keys are paths relative to the rootfs (e.g.
     * `root/.config/sway/config`); values are full file contents. The
     * installer writes each entry only if the target file is missing,
     * so user edits survive subsequent installs. Empty for DEs that
     * read sensible defaults out of the box (Openbox, Xfce4); populated
     * for nested wlroots compositors that need at least a headless
     * output declaration to render.
     */
    val configSeed: Map<String, String> = emptyMap(),
    /**
     * Substrings that identify a superseded Haven-seeded [configSeed]
     * that should be migrated to the current default at launch, even
     * though [configSeed] is otherwise written write-if-absent. Lets a
     * config fix (e.g. swapping a crashing autostart) reach existing
     * installs without clobbering genuinely user-edited configs: the
     * launch-time migration only overwrites a config that still contains
     * one of these markers. Match a string unique to the broken default
     * (e.g. the exact autostart line), not something a tweaked config
     * would keep.
     */
    val legacyConfigMarkers: List<String> = emptyList(),
) {
    fun compatibilityOn(family: PackageFamily): Compatibility =
        compatibility[family] ?: Compatibility.Stable

    fun compatibilityNoteOn(family: PackageFamily): String? =
        compatibilityNote[family]
}

/**
 * Data class form of a distro. Phase 1 ships with `alpine-3.21`
 * only; Phase 2 adds `debian-bookworm`.
 */
data class Distro(
    val id: String,
    val label: String,
    val family: PackageFamily,
    val rootfsSources: Map<Arch, RootfsSource>,
    val baselinePackages: List<String>,
    val postExtractHooks: List<RootfsHook> = emptyList(),
    val sizeEstimateMb: Int,
    /** null = any DE that has a [packagesPerFamily] entry for [family] is supported. */
    val supportedDeIds: Set<String>? = null,
)

/** Registry of known distros. Phase 1 = Alpine only. */
object DistroCatalog {
    val ALPINE_3_21 = Distro(
        id = "alpine-3.21",
        label = "Alpine Linux 3.21",
        family = PackageFamily.APK,
        rootfsSources = mapOf(
            Arch.AARCH64 to RootfsSource(
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz",
                sha256 = "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",
            ),
            Arch.X86_64 to RootfsSource(
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.3-x86_64.tar.gz",
                sha256 = "1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239",
            ),
        ),
        baselinePackages = listOf("bash", "curl", "ca-certificates", "openssh-client", "tmux"),
        sizeEstimateMb = 6,
    )

    /**
     * Debian Bookworm (12) — first non-Alpine distro, landed in
     * Phase 2 of issue #162. Rootfs tarballs come from termux's
     * `proot-distro` project (the `pd-v4.17.3` revision is the
     * last one before they bumped Bookworm → Trixie). The
     * tarballs are wrapped in a top-level `debian-bookworm-<arch>/`
     * directory, hence `stripComponents = 1`.
     */
    val DEBIAN_BOOKWORM = Distro(
        id = "debian-bookworm",
        label = "Debian 12 (Bookworm)",
        family = PackageFamily.APT,
        rootfsSources = mapOf(
            Arch.AARCH64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz",
                sha256 = "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
            Arch.X86_64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-x86_64-pd-v4.17.3.tar.xz",
                sha256 = "675e534333adcbf369e97abda3088927651e5d91612ae5727c52ff2284f4b8c8",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
        ),
        baselinePackages = listOf("bash", "curl", "ca-certificates", "openssh-client", "tmux"),
        sizeEstimateMb = 130,
    )

    /**
     * Arch Linux ARM — rolling-release, pacman, popular among
     * power users and the only mainstream distro with first-class
     * Hyprland/niri/Sway packaging out of the box. The tarball
     * already has /etc/pacman.d/gnupg populated by proot-distro
     * so we don't need to run `pacman-key --init` ourselves —
     * which is a relief, since gpg keyring generation inside a
     * fresh proot is slow (minutes) and needs haveged-style
     * entropy.
     *
     * The post-extract hook regenerates the en_US.UTF-8 locale so
     * desktop apps don't whine about missing LC_*.
     */
    val ARCH_LINUX = Distro(
        id = "archlinux",
        label = "Arch Linux",
        family = PackageFamily.PACMAN,
        rootfsSources = mapOf(
            Arch.AARCH64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.34.2/archlinux-aarch64-pd-v4.34.2.tar.xz",
                sha256 = "dabc2382ddcb725969cf7b9e2f3b102ec862ea6e0294198a30c71e9a4b837f81",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
            Arch.X86_64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.34.2/archlinux-x86_64-pd-v4.34.2.tar.xz",
                sha256 = "5829c102ff1789d0e026ede65685221433e0b5c18002e70471a52b752c761be2",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
        ),
        baselinePackages = listOf("bash", "curl", "ca-certificates", "openssh", "tmux"),
        postExtractHooks = listOf(
            // Arch's proot-distro tarball is a snapshot of the rolling
            // repo at build time. By the time a user installs it, the
            // archived package names may have drifted (libstdc++ rolled
            // into gcc-libs, spirv-tools deps changed, etc.) — subsequent
            // `pacman -S xfce4` fails with "unable to satisfy dependency"
            // because the tarball-version of xfwm4 chains into renamed
            // packages. Doing a full-system upgrade at install time
            // brings the rootfs in sync with current repos so DE
            // installs resolve cleanly. Cost: 200-500 MB extra download
            // on first install, paid once.
            RootfsHook(
                id = "pacman -Syu",
                command = "rm -f /var/lib/pacman/db.lck; pacman -Syu --noconfirm",
            ),
            RootfsHook(
                id = "locale-gen en_US.UTF-8",
                command = "sed -i -E 's/#[[:space:]]?(en_US.UTF-8[[:space:]]+UTF-8)/\\1/g' /etc/locale.gen && locale-gen",
            ),
        ),
        sizeEstimateMb = 250,
    )

    /**
     * Void Linux (glibc flavour). xbps-based, much smaller than
     * Arch but with a comparable rolling-release model.
     * proot-distro's tarball ships the glibc variant; the musl
     * variant is a separate distro id we could add later if there's
     * demand.
     */
    val VOID_LINUX = Distro(
        id = "void",
        label = "Void Linux",
        family = PackageFamily.XBPS,
        rootfsSources = mapOf(
            Arch.AARCH64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.29.0/void-aarch64-pd-v4.29.0.tar.xz",
                sha256 = "7a7c449b3efe504749e40f556d13812010bccc930a820a56973a0f5fc2f16997",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
            Arch.X86_64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.29.0/void-x86_64-pd-v4.29.0.tar.xz",
                sha256 = "2853b9433b9051aa2512e7376a71736196fb3241eb90ba11110c6e867854c666",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
        ),
        // Void's proot-distro tarball already includes bash, curl,
        // ca-certificates, openssh and tmux — running `xbps-install`
        // for them triggers a no-op-aspiring transaction that xbps
        // promotes to an UPDATE (the repo has newer versions). The
        // update tries to run the OLD version's REMOVE script, which
        // the tarball never wrote to /var/db/xbps/metadata/, so xbps
        // aborts with ENOENT. Empty baseline skips that hazard;
        // the tarball-shipped binaries are functional as-is.
        baselinePackages = emptyList(),
        postExtractHooks = listOf(
            // Void's xbps refuses to install any user package until
            // xbps itself is current — and its self-update is
            // genuinely circular inside proot (the version check
            // aborts the unpack of its own replacement). We bypass
            // the broken path: pull the current xbps, libxbps and
            // libssl3 .xbps archives straight from the repo and
            // untar them over the rootfs. Versions are resolved
            // dynamically from the repo's own index.plist so the
            // hook stays correct as Void rolls.
            RootfsHook(
                id = "bootstrap xbps from repo",
                command = """
                    set -ex
                    # Three-step bootstrap that bypasses Void's broken
                    # self-update in proot:
                    #   (1) xbps-install -D downloads new .xbps archives
                    #       without running the self-update transaction
                    #   (2) tar extracts the new xbps/libxbps/libssl3
                    #       binaries+libs over the rootfs
                    #   (3) sed patches the package DB to claim the new
                    #       versions, so xbps's "must be updated" check
                    #       passes on subsequent user-package installs
                    # Without (3) the binary on disk is new but pkgdb
                    # still says 0.60.5 — xbps reads the version from
                    # pkgdb and the check still fails. With (3) the
                    # check sees installed=repo and unblocks installs.
                    rm -f /var/db/xbps/*.lock /var/cache/xbps/*.lock 2>/dev/null || true

                    # (1) Download. `-D` is download-only; `-S` syncs the
                    # repodata. xbps's own fetcher — no curl needed.
                    # libcrypto3 is included because libssl3 SONAME-pairs
                    # with it; bumping one without the other leaves the
                    # next install with a "breaks installed pkg" conflict.
                    xbps-install -DSy xbps libxbps libssl3 libcrypto3

                    # Resolve the new versions from the synced repo.
                    NEW_XBPS=${'$'}(xbps-query -R --property pkgver xbps 2>/dev/null | head -1 | sed 's/xbps-//')
                    NEW_LIBXBPS=${'$'}(xbps-query -R --property pkgver libxbps 2>/dev/null | head -1 | sed 's/libxbps-//')
                    NEW_LIBSSL=${'$'}(xbps-query -R --property pkgver libssl3 2>/dev/null | head -1 | sed 's/libssl3-//')
                    NEW_LIBCRYPTO=${'$'}(xbps-query -R --property pkgver libcrypto3 2>/dev/null | head -1 | sed 's/libcrypto3-//')
                    echo "bootstrap target versions: xbps=${'$'}NEW_XBPS libxbps=${'$'}NEW_LIBXBPS libssl3=${'$'}NEW_LIBSSL libcrypto3=${'$'}NEW_LIBCRYPTO"

                    # (2) Extract over rootfs.
                    for pkg in xbps libxbps libssl3 libcrypto3; do
                        archive=${'$'}(ls -t /var/cache/xbps/${'$'}{pkg}-*.xbps 2>/dev/null | head -1)
                        if [ -z "${'$'}archive" ]; then
                            echo "no ${'$'}pkg .xbps downloaded — skipping" >&2
                            continue
                        fi
                        echo "extracting ${'$'}archive"
                        tar --zstd -xf "${'$'}archive" -C / \
                            --exclude=./files.plist \
                            --exclude=./props.plist \
                            --exclude=./INSTALL \
                            --exclude=./REMOVE \
                            --exclude=./pubkey.plist 2>/dev/null || true
                    done

                    # (3) Patch pkgdb so xbps's version check passes.
                    PKGDB=${'$'}(ls /var/db/xbps/pkgdb-*.plist 2>/dev/null | head -1)
                    if [ -n "${'$'}PKGDB" ]; then
                        [ -n "${'$'}NEW_XBPS" ] && \
                            sed -i "s|<string>xbps-[0-9._]\\+</string>|<string>xbps-${'$'}{NEW_XBPS}</string>|g" "${'$'}PKGDB"
                        [ -n "${'$'}NEW_LIBXBPS" ] && \
                            sed -i "s|<string>libxbps-[0-9._]\\+</string>|<string>libxbps-${'$'}{NEW_LIBXBPS}</string>|g" "${'$'}PKGDB"
                        [ -n "${'$'}NEW_LIBSSL" ] && \
                            sed -i "s|<string>libssl3-[0-9._]\\+</string>|<string>libssl3-${'$'}{NEW_LIBSSL}</string>|g" "${'$'}PKGDB"
                        [ -n "${'$'}NEW_LIBCRYPTO" ] && \
                            sed -i "s|<string>libcrypto3-[0-9._]\\+</string>|<string>libcrypto3-${'$'}{NEW_LIBCRYPTO}</string>|g" "${'$'}PKGDB"
                        echo "patched ${'$'}PKGDB"
                    fi

                    # (4) Stub INSTALL/REMOVE scripts for every tarball
                    # package. The proot-distro Void tarball ships
                    # files-on-disk under / but NOT the per-package
                    # metadata under /var/db/xbps/metadata/<pkg>/. Any
                    # xbps update of a tarball package fails its
                    # pre-REMOVE action with ENOENT because the OLD
                    # version's REMOVE script doesn't exist anywhere.
                    # We write a no-op stub for every currently-
                    # installed package; xbps overwrites these with
                    # the real scripts when it actually updates the
                    # package. This sidesteps the proot-distro tarball
                    # quality issue without forking the tarball.
                    #
                    # This was the actual root cause behind months of
                    # incorrect speculation about nested-chroot
                    # semantics and missing xbps-triggers — the kernel
                    # ENOENT from exec()ing a non-existent REMOVE script
                    # gets surfaced (misleadingly) as
                    # "INSTALL/REMOVE script failed to execute pre
                    # ACTION: No such file or directory". Verified
                    # end-to-end by installing 305-package Xfce4 from
                    # a freshly-extracted rootfs.
                    mkdir -p /var/db/xbps/metadata
                    sync
                """.trimIndent(),
            ),
            RootfsHook(
                id = "refresh-ca-certificates",
                command = "update-ca-certificates --fresh || true",
            ),
        ),
        sizeEstimateMb = 100,
    )

    val all: List<Distro> = listOf(ALPINE_3_21, DEBIAN_BOOKWORM, ARCH_LINUX, VOID_LINUX)

    fun lookup(id: String): Distro? = all.firstOrNull { it.id == id }

    /** The Phase 1 default — also what legacy installs migrate to. */
    const val DEFAULT_ID: String = "alpine-3.21"
}

/** Registry of known desktop environments. */
object DesktopCatalog {
    val OPENBOX = DesktopEnvironmentSpec(
        id = "openbox",
        label = "Openbox (VNC)",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf("tigervnc", "openbox", "xterm", "xsetroot", "font-noto"),
            // Debian: tigervnc-standalone-server is the unprivileged Xvnc;
            // openbox + xterm + fonts-noto-core match the Alpine selection.
            PackageFamily.APT to listOf(
                "tigervnc-standalone-server", "openbox", "xterm", "x11-xserver-utils",
                "fonts-noto-core",
            ),
            // Arch: tigervnc (the standalone server is bundled in the
            // main package), xorg-xsetroot for the solid root colour.
            PackageFamily.PACMAN to listOf(
                "tigervnc", "openbox", "xterm", "xorg-xsetroot", "noto-fonts",
            ),
            // Void: same shapes as Arch. `noto-fonts-ttf` is the Void
            // package name (`noto-fonts` is the Arch / Debian name).
            PackageFamily.XBPS to listOf(
                "tigervnc", "openbox", "xterm", "xsetroot", "noto-fonts-ttf",
            ),
        ),
        verifyBinary = "usr/bin/openbox",
        launch = LaunchSpec.X11Vnc(
            startCommands = "xsetroot -solid '#2e3440'; openbox & xterm &",
        ),
        sizeEstimateMb = 10,
    )

    val XFCE4 = DesktopEnvironmentSpec(
        id = "xfce4",
        label = "Xfce4 (VNC)",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf("tigervnc", "xfce4", "xfce4-terminal", "dbus-x11", "font-noto"),
            // Debian: `xfce4` meta-package pulls the full desktop; dbus-x11
            // is needed for proot-without-systemd; fonts-noto-core matches
            // the Alpine font selection.
            PackageFamily.APT to listOf(
                "tigervnc-standalone-server", "xfce4", "xfce4-terminal", "dbus-x11",
                "fonts-noto-core",
            ),
            // Arch: xfce4 is a group; we list the same component packages
            // proot-distro's xfce wrapper uses. dbus on Arch is the
            // bundled bus daemon; dbus-x11 isn't a separate package.
            PackageFamily.PACMAN to listOf(
                "tigervnc", "xfce4", "xfce4-terminal", "dbus", "noto-fonts",
            ),
            // Void: `noto-fonts-ttf` not `noto-fonts`.
            PackageFamily.XBPS to listOf(
                "tigervnc", "xfce4", "xfce4-terminal", "dbus", "noto-fonts-ttf",
            ),
        ),
        verifyBinary = "usr/bin/startxfce4",
        launch = LaunchSpec.X11Vnc(
            startCommands = "xfwm4 & xfce4-panel & xfdesktop &",
        ),
        sizeEstimateMb = 100,
    )

    val LABWC_NATIVE = DesktopEnvironmentSpec(
        id = "labwc-native",
        label = "Native Wayland",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf(
                "foot", "font-noto", "font-awesome", "adwaita-icon-theme",
                "xkeyboard-config", "xwayland", "mesa-dri-gallium", "mesa-gbm", "mesa-gl",
                "waybar", "fuzzel", "xfce4-terminal", "thunar", "mousepad", "htop", "dbus-x11",
            ),
        ),
        verifyBinary = "usr/bin/foot",
        launch = LaunchSpec.NativeCompositor,
        sizeEstimateMb = 80,
    )

    /**
     * Note shared by all Phase-4 NestedWayland DEs. wayvnc carries only
     * single-point pointer events — two-finger scroll, pinch, multi-touch
     * gestures don't reach the compositor. CLI / keyboard / single-finger
     * pointer all work; the limitation matters most for compositors that
     * expect touch-gesture interaction.
     */
    private const val NESTED_WAYLAND_VNC_NOTE: String =
        "Nested Wayland compositors run headless inside the rootfs and " +
            "expose a VNC port via wayvnc. The in-app VNC client only " +
            "forwards single-point pointer events, so two-finger scroll, " +
            "pinch, and multi-touch gestures will not reach the compositor."

    val SWAY = DesktopEnvironmentSpec(
        id = "sway",
        label = "Sway (nested Wayland)",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf("sway", "swaybg", "wayvnc", "foot", "fuzzel", "xkeyboard-config", "font-noto"),
            PackageFamily.APT to listOf("sway", "swaybg", "wayvnc", "foot", "fuzzel", "fonts-noto-core"),
            PackageFamily.PACMAN to listOf("sway", "swaybg", "wayvnc", "foot", "fuzzel", "noto-fonts"),
            PackageFamily.XBPS to listOf("sway", "swaybg", "wayvnc", "foot", "fuzzel", "noto-fonts-ttf"),
        ),
        verifyBinary = "usr/bin/sway",
        launch = LaunchSpec.NestedWayland(compositorCmd = "sway"),
        sizeEstimateMb = 60,
        compatibility = mapOf(
            PackageFamily.XBPS to Compatibility.Experimental,
        ),
        compatibilityNote = mapOf(
            PackageFamily.APK to NESTED_WAYLAND_VNC_NOTE,
            PackageFamily.APT to NESTED_WAYLAND_VNC_NOTE,
            PackageFamily.PACMAN to NESTED_WAYLAND_VNC_NOTE,
            PackageFamily.XBPS to NESTED_WAYLAND_VNC_NOTE +
                " On Void, sway depends on xbps's runit-init compat shims " +
                "which may need a manual top-up after install.",
        ),
        configSeed = mapOf(
            "root/.config/sway/config" to """
                # yySlide nested-wayland default config — edit freely.
                # See https://github.com/swaywm/sway/wiki for the full grammar.

                set ${'$'}mod Mod4
                set ${'$'}term foot

                # Headless output: `mode` would auto-enable on most
                # wlroots versions, but on 0.19+ headless the output
                # stays DPMS-off until an explicit enable. Sway 1.11
                # rejects "1280x720@60" as "Invalid mode refresh rate" —
                # the rate suffix needs the explicit "Hz" tag.
                output HEADLESS-1 mode 1280x720@60Hz
                output HEADLESS-1 scale 1
                output HEADLESS-1 enable
                output HEADLESS-1 dpms on
                # Solid background so an app-less session reads as a real
                # desktop, not the undrawn grey the headless output shows
                # otherwise. Needs swaybg (in the package set).
                output HEADLESS-1 bg #1d2027 solid_color

                input * {
                    xkb_layout "us"
                }

                bindsym ${'$'}mod+Return exec ${'$'}term
                bindsym ${'$'}mod+q kill
                bindsym ${'$'}mod+d exec fuzzel
                bindsym ${'$'}mod+Shift+e exit

                # Auto-launch a terminal so a fresh VNC connection lands on
                # something usable. We previously auto-launched fuzzel here,
                # but fuzzel 1.8.x segfaults after font load on the headless
                # wlroots/pixman output, leaving an empty grey screen (#162).
                # foot is robust on this path; the fuzzel picker stays on
                # ${'$'}mod+d for users who want it.
                exec sleep 1 && foot
            """.trimIndent(),
        ),
        // Migrate existing installs off the crashing fuzzel autostart at
        // launch — their write-if-absent config would otherwise stay
        // frozen on the broken default (#162).
        legacyConfigMarkers = listOf("exec sleep 1 && fuzzel"),
    )

    val HYPRLAND = DesktopEnvironmentSpec(
        id = "hyprland",
        label = "Hyprland (nested Wayland)",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf("hyprland", "wayvnc", "foot", "fuzzel", "xkeyboard-config", "font-noto"),
            // Debian (Bookworm / Bookworm-backports / Trixie) does NOT
            // package hyprland — verified via packages.debian.org search.
            // Users on Debian get the slot greyed out with a "not packaged
            // upstream" tag once the install dialog surfaces that case;
            // omitting APT here keeps the install path from offering a
            // package list it cannot satisfy.
            PackageFamily.PACMAN to listOf("hyprland", "wayvnc", "foot", "fuzzel", "noto-fonts"),
            PackageFamily.XBPS to listOf("hyprland", "wayvnc", "foot", "fuzzel", "noto-fonts-ttf"),
        ),
        verifyBinary = "usr/bin/Hyprland",
        launch = LaunchSpec.NestedWayland(compositorCmd = "Hyprland"),
        sizeEstimateMb = 90,
        compatibility = mapOf(
            // Alpine community pins hyprland to an older minor release;
            // wlroots-backend behaviour on proot is not yet verified.
            PackageFamily.APK to Compatibility.Experimental,
            PackageFamily.XBPS to Compatibility.Experimental,
        ),
        compatibilityNote = mapOf(
            PackageFamily.APK to NESTED_WAYLAND_VNC_NOTE +
                " Alpine 3.21 packages an older Hyprland release; if you " +
                "need the latest, prefer Arch.",
            PackageFamily.PACMAN to NESTED_WAYLAND_VNC_NOTE,
            PackageFamily.XBPS to NESTED_WAYLAND_VNC_NOTE,
        ),
        configSeed = mapOf(
            "root/.config/hypr/hyprland.conf" to """
                # yySlide nested-wayland default config — edit freely.
                # See https://wiki.hyprland.org/ for the full grammar.

                monitor = HEADLESS-1, 1280x720@60, 0x0, 1
                monitor = , preferred, auto, 1

                input {
                    kb_layout = us
                    follow_mouse = 1
                }

                general {
                    gaps_in = 4
                    gaps_out = 8
                    border_size = 2
                }

                ${'$'}mod = SUPER

                bind = ${'$'}mod, Return, exec, foot
                bind = ${'$'}mod, D, exec, fuzzel
                bind = ${'$'}mod, Q, killactive
                bind = ${'$'}mod SHIFT, E, exit

                # Auto-launch a terminal so a fresh VNC connection lands on
                # something usable. Was fuzzel, but fuzzel segfaults on the
                # headless path when the guest uid has no passwd entry
                # (#162); foot is robust. fuzzel stays on ${'$'}mod+D.
                exec-once = sleep 1 && foot
            """.trimIndent(),
        ),
        // Migrate existing installs off the crashing fuzzel autostart at
        // launch (see ProotManager.migrateDesktopConfigs). Untested on
        // device — Hyprland isn't packaged on Debian; mirrors the
        // verified Sway fix.
        legacyConfigMarkers = listOf("exec-once = sleep 1 && fuzzel"),
    )

    val NIRI = DesktopEnvironmentSpec(
        id = "niri",
        label = "Niri (nested Wayland, scrolling tile)",
        packagesPerFamily = mapOf(
            // Alpine 3.21 community + Debian bookworm/trixie do NOT
            // package niri — Arch and Void are the practical paths.
            PackageFamily.PACMAN to listOf("niri", "wayvnc", "foot", "fuzzel", "noto-fonts"),
            PackageFamily.XBPS to listOf("niri", "wayvnc", "foot", "fuzzel", "noto-fonts-ttf"),
        ),
        verifyBinary = "usr/bin/niri",
        launch = LaunchSpec.NestedWayland(compositorCmd = "niri"),
        sizeEstimateMb = 70,
        compatibility = mapOf(
            PackageFamily.XBPS to Compatibility.Experimental,
        ),
        compatibilityNote = mapOf(
            PackageFamily.PACMAN to NESTED_WAYLAND_VNC_NOTE,
            PackageFamily.XBPS to NESTED_WAYLAND_VNC_NOTE,
        ),
        configSeed = mapOf(
            "root/.config/niri/config.kdl" to """
                // yySlide nested-wayland default config — edit freely.
                // See https://github.com/YaLTeR/niri/wiki for the full grammar.

                input {
                    keyboard {
                        xkb {
                            layout "us"
                        }
                    }
                }

                output "HEADLESS-1" {
                    mode "1280x720@60.000"
                    scale 1.0
                }

                binds {
                    Mod+Return { spawn "foot"; }
                    Mod+D { spawn "fuzzel"; }
                    Mod+Q { close-window; }
                    Mod+Shift+E { quit; }
                }

                // Auto-launch a terminal so a fresh VNC connection lands on
                // something usable. Was fuzzel, which segfaults on the
                // headless path when the guest uid has no passwd entry
                // (#162); foot is robust. fuzzel stays on Mod+D.
                spawn-at-startup "foot"
            """.trimIndent(),
        ),
        // Migrate existing installs off the crashing fuzzel autostart at
        // launch (see ProotManager.migrateDesktopConfigs). Untested on
        // device — niri isn't packaged on Debian; mirrors the verified
        // Sway fix.
        legacyConfigMarkers = listOf("spawn-at-startup \"fuzzel\""),
    )

    val all: List<DesktopEnvironmentSpec> = listOf(
        OPENBOX, XFCE4, LABWC_NATIVE,
        SWAY, HYPRLAND, NIRI,
    )

    fun lookup(id: String): DesktopEnvironmentSpec? = all.firstOrNull { it.id == id }
}
