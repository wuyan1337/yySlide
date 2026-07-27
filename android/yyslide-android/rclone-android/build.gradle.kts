import java.util.Properties

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
    // gomobile-generated Java bindings (produced by build-android.sh).
    // Use `api` so downstream consumers (core:rclone, core:tunnel) can
    // reference the bound classes directly — the Kotlin wrappers in
    // kotlin/sh/haven/rclone/bridge/ cover rcbridge, but the wgbridge
    // classes are consumed raw from core:tunnel.
    api(files("build/rcbridge-bindings.jar"))
    testImplementation("junit:junit:4.13.2")
}

// Kotlin wrapper sources generated alongside the Go → gomobile build
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

// Build rclone native library from Go source via gomobile.
// Prerequisites: Go 1.26.1, pinned gomobile/gobind, Android NDK r29.
// The .so files are NOT committed — they're built as part of the Gradle build.
val buildRcloneNative by tasks.registering(Exec::class) {
    val goDir = file("go")
    val jniDir = file("jniLibs")
    val toolsDir = file("tools")
    val bindingsJar = layout.buildDirectory.file("rcbridge-bindings.jar")

    inputs.dir(goDir)
    inputs.file(toolsDir.resolve("build-android.sh"))
    outputs.files(
        jniDir.resolve("arm64-v8a/libgojni.so"),
        jniDir.resolve("x86_64/libgojni.so"),
        bindingsJar,
    )

    workingDir = projectDir
    val gitBash = System.getenv("ProgramFiles")
        ?.let { File(it, "Git/bin/bash.exe") }
        ?.takeIf(File::isFile)
    val bashExecutable = System.getenv("BASH")?.takeIf { it.isNotBlank() }
        ?: gitBash?.absolutePath
        ?: "bash"
    commandLine(bashExecutable, toolsDir.resolve("build-android.sh").absolutePath)

    // Ensure Go toolchain is on PATH for the build script
    val goRoot = "/usr/local/go"
    val goPath = System.getenv("GOPATH") ?: "${System.getProperty("user.home")}/go"
    val windowsGoBin = System.getenv("GOROOT")
        ?.let { File(it, "bin") }
        ?.takeIf { File(it, "go.exe").isFile }
        ?: System.getenv("ProgramFiles")
            ?.let { File(it, "Go/bin") }
            ?.takeIf { File(it, "go.exe").isFile }
    val javaHome = System.getenv("JAVA_HOME")
    val javaBin = javaHome?.let { "$it/bin" }
    environment(
        "PATH",
        listOfNotNull(
            windowsGoBin?.absolutePath,
            goRoot + "/bin",
            goPath + "/bin",
            javaBin,
            System.getenv("PATH"),
        ).joinToString(File.pathSeparator),
    )
    if (javaHome != null) {
        environment("JAVA_HOME", javaHome)
    }

    // Included builds do not inherit the parent build's local.properties.
    // Resolve it explicitly so a normal Android Studio checkout works even
    // when ANDROID_HOME is not exported in the user's shell.
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

    // Use the same exact NDK revision as the Android modules. Selecting
    // the newest installed NDK made native output depend on runner state.
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
    if (sdkRoot != null) {
        environment("ANDROID_HOME", sdkRoot)
        environment("ANDROID_SDK_ROOT", sdkRoot)
    }
    doFirst {
        if (ndkHome == null) {
            throw GradleException(
                "Android NDK $requiredNdkVersion is required. Install it with " +
                    "sdkmanager \"ndk;$requiredNdkVersion\" or set ANDROID_NDK_HOME to that revision.",
            )
        }
    }
}

// Ensure the Go native build completes before Kotlin compilation,
// because compileKotlin needs rcbridge-bindings.jar produced by gomobile.
tasks.named("compileKotlin") {
    dependsOn(buildRcloneNative)
}
