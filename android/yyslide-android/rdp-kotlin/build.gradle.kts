import java.util.Properties
import java.security.MessageDigest
import java.util.Locale

plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // Keep version in sync with [versions.jna] in gradle/libs.versions.toml
    compileOnly("net.java.dev.jna:jna:5.19.1")

    testImplementation("junit:junit:4.13.2")
}

// Generated Kotlin sources live under kotlin/ (see tools/build-android.sh)
sourceSets {
    main {
        kotlin.srcDir("kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build IronRDP native library from Rust source via cargo-ndk.
// Prerequisites: rustup, cargo-ndk, aarch64-linux-android + x86_64-linux-android targets.
// The .so files are NOT committed — they're built as part of the Gradle build.
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val checkoutHash = MessageDigest.getInstance("SHA-256")
    .digest(projectDir.absoluteFile.normalize().path.lowercase(Locale.ROOT).toByteArray())
    .joinToString("") { "%02x".format(it) }
    .take(16)
val explicitCargoTargetDir = System.getenv("YYSLIDE_RDP_TARGET_DIR")
    ?.takeIf { it.isNotBlank() }
    ?.let(::file)
val windowsCargoTargetDir = System.getenv("LOCALAPPDATA")
    ?.takeIf { it.isNotBlank() }
    ?.let { file("$it/yySlide/rdp-cargo-target/$checkoutHash") }
val cargoTargetDir: File = explicitCargoTargetDir
    ?: (if (isWindowsHost) windowsCargoTargetDir else null)
    ?: layout.buildDirectory.dir("cargo-target").get().asFile

val verifyRdpCargoTargetPath by tasks.registering {
    group = "verification"
    description = "Verify that Windows Rust linker inputs use an ASCII-only target path"
    inputs.property("cargoTargetDir", cargoTargetDir.absolutePath)
    doLast {
        if (isWindowsHost && cargoTargetDir.absolutePath.any { it.code > 0x7f }) {
            throw GradleException(
                "IronRDP's Windows linker requires an ASCII CARGO_TARGET_DIR. " +
                    "Set YYSLIDE_RDP_TARGET_DIR to an ASCII-only writable path; " +
                    "resolved path was ${cargoTargetDir.absolutePath}",
            )
        }
        logger.lifecycle("IronRDP Cargo target: ${cargoTargetDir.absolutePath}")
    }
}

val buildRdpNative by tasks.registering(Exec::class) {
    dependsOn(verifyRdpCargoTargetPath)
    val rustDir = file("rust")
    val jniDir = file("jniLibs")

    inputs.dir(rustDir.resolve("src"))
    inputs.dir(rustDir.resolve("vendor"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    inputs.file(rustDir.resolve("rust-toolchain.toml"))
    inputs.file(rustDir.resolve("build.rs"))
    inputs.property("cargoTargetDir", cargoTargetDir.absolutePath)
    outputs.files(
        jniDir.resolve("arm64-v8a/librdp_transport.so"),
        jniDir.resolve("x86_64/librdp_transport.so"),
    )

    workingDir = rustDir

    val localSdkRoot = sequenceOf(
        projectDir.resolve("local.properties"),
        projectDir.parentFile.resolve("local.properties"),
    ).firstNotNullOfOrNull { propertiesFile ->
        if (!propertiesFile.isFile) return@firstNotNullOfOrNull null
        Properties().apply { propertiesFile.inputStream().use(::load) }
            .getProperty("sdk.dir")
            ?.takeIf { it.isNotBlank() }
    }
    val sdkRoot = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: localSdkRoot

    // Resolve the exact r29 revision used by every Android module rather
    // than whichever extra NDK happens to sort last on this machine.
    val requiredNdkVersion = "29.0.14206865"
    fun hasRequiredNdkVersion(candidate: File): Boolean {
        val sourceProperties = candidate.resolve("source.properties")
        if (!sourceProperties.isFile) return false
        return Properties().apply { sourceProperties.inputStream().use(::load) }
            .getProperty("Pkg.Revision") == requiredNdkVersion
    }
    val ndkHome = sequenceOf(
        System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let(::file),
        sdkRoot?.let { file("$it/ndk/$requiredNdkVersion") },
    ).filterNotNull().firstOrNull(::hasRequiredNdkVersion)
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome.absolutePath)
    }
    environment("CARGO_TARGET_DIR", cargoTargetDir.absolutePath)

    val windowsCargo = File(System.getProperty("user.home"), ".cargo/bin/cargo.exe")
        .takeIf(File::isFile)
    val cargoExecutable = System.getenv("CARGO")?.takeIf { it.isNotBlank() }
        ?: windowsCargo?.absolutePath
        ?: "cargo"
    commandLine(cargoExecutable, "ndk",
        "-o", jniDir.absolutePath,
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "build", "--release")
    doFirst {
        if (ndkHome == null) {
            throw GradleException(
                "Android NDK $requiredNdkVersion is required. Install it with " +
                    "sdkmanager \"ndk;$requiredNdkVersion\" or set ANDROID_NDK_HOME to that revision.",
            )
        }
    }
}

// No publishing block needed — consumed via includeBuild() in settings.gradle.kts
