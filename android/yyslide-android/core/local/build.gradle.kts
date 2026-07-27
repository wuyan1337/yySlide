plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.local"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake {}
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:wayland"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Pure-Java XZ decoder for tar.xz rootfs tarballs (issue #162 phase 2).
    implementation(libs.xz)

    testImplementation(libs.junit)
}

fun commandOnPath(command: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val suffixes = if (windows) listOf("", ".exe", ".cmd", ".bat") else listOf("")
    return path.split(File.pathSeparator).any { dir ->
        suffixes.any { suffix -> File(dir, command + suffix).isFile }
    }
}

fun hasLinuxGuestCrossToolchains(): Boolean =
    commandOnPath("aarch64-linux-gnu-gcc") && commandOnPath("x86_64-linux-gnu-gcc")

fun isWindowsHost(): Boolean =
    System.getProperty("os.name").lowercase().contains("win")

fun hasPrebuiltProotLibraries(jniLibsDir: File): Boolean =
    listOf("arm64-v8a", "x86_64").all { abi ->
        jniLibsDir.resolve("$abi/libproot.so").isFile &&
            jniLibsDir.resolve("$abi/libproot_loader.so").isFile
    }

val buildProot by tasks.registering(Exec::class) {
    val prootScript = rootProject.file("build-proot/build.sh")
    val prootSrc = rootProject.file("build-proot/proot-termux/src")
    val tallocSrc = rootProject.file("build-proot/talloc")
    val jniLibsDir = file("src/main/jniLibs")

    inputs.file(prootScript)
    inputs.dir(prootSrc)
    inputs.dir(tallocSrc)
    outputs.dir(jniLibsDir)
    onlyIf {
        !(isWindowsHost() && hasPrebuiltProotLibraries(jniLibsDir))
    }

    workingDir = rootProject.file("build-proot")
    commandLine("bash", "build.sh")
    // Let build.sh auto-detect the newest NDK (needs r28+ for the ARM64
    // TLS alignment fix; we ship against r29 stable in CI).
    environment("PROOT_OUTPUT", jniLibsDir.absolutePath)
}

// Build the wayvnc capture-fallback shim (one .so per ABI) and stage
// it under src/main/assets/wayvnc-shim/<abi>/ so the APK packager
// picks it up. The shim is loaded inside the proot rootfs via
// LD_PRELOAD by the nested-Wayland launch script — see
// DesktopManager.launchNestedWayland for the call site, and
// wayland-android/wayvnc-shim/libhaven_wayvnc_shim.c for the why.
//
// Required toolchains: gcc-aarch64-linux-gnu and gcc-x86-64-linux-gnu
// (standard Debian/Ubuntu packages — F-Droid's buildserver has them).
val buildWayvncShim by tasks.registering(Exec::class) {
    val script = rootProject.file("wayland-android/build-wayvnc-shim.sh")
    val src = rootProject.file("wayland-android/wayvnc-shim/libhaven_wayvnc_shim.c")
    val assetsDir = file("src/main/assets/wayvnc-shim")

    // The release.yml `test` job intentionally skips the wayland-android
    // submodule (its freedesktop chain intermittently 5xxs and breaks
    // unrelated test runs). Tolerate the source files being absent
    // there — they'll be present in the `build` job that does a
    // recursive init, and on F-Droid (which always inits submodules).
    // `inputs.files()` (plural) accepts missing entries; `onlyIf`
    // skips the Exec entirely when there's nothing to compile.
    inputs.files(script, src)
    outputs.dir(assetsDir)
    onlyIf { script.exists() && src.exists() && hasLinuxGuestCrossToolchains() }

    workingDir = rootProject.file("wayland-android")
    commandLine("bash", "build-wayvnc-shim.sh")
}

// Build the haven-usb guest artifacts (Slice 2: the reachability probe; Slice 3
// adds the LD_PRELOAD/DllMap shim). Same glibc/musl cross toolchain as the
// wayvnc shim. Source files may be absent in the release.yml `test` job (which
// skips heavy native sources); onlyIf tolerates that.
val buildHavenUsb by tasks.registering(Exec::class) {
    val script = file("build-haven-usb.sh")
    val srcDir = file("src/main/cpp/haven-usb")
    val probeSrc = file("src/main/cpp/haven-usb/haven-usb-probe.c")
    val assetsDir = file("src/main/assets/haven-usb")

    inputs.files(script).withPropertyName("script")
    inputs.dir(srcDir).withPropertyName("sources")
    outputs.dir(assetsDir)
    onlyIf { script.exists() && probeSrc.exists() && hasLinuxGuestCrossToolchains() }

    workingDir = projectDir
    commandLine("bash", "build-haven-usb.sh")
}

tasks.named("preBuild") {
    dependsOn(buildProot, buildWayvncShim, buildHavenUsb)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
