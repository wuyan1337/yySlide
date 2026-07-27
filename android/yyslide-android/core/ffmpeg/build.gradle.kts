plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.ffmpeg"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Native executables are packaged by :app, where the ABI flavor is
    // known. Packaging them from this unflavoured library forced every
    // app variant to prepare both ABIs and made an arm64-only Windows
    // build try to cross-compile x86_64 FFmpeg.
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

// Keep both app ABI flavors functional. The previous task always copied
// arm64 output, so an x64 APK built successfully but shipped without
// ffmpeg/ffprobe. A missing ABI is now built from source on Linux and the
// generated files are attached by :app only to that ABI flavor.
val ffmpegBinaryNames = listOf("libffmpeg.so", "libffprobe.so", "libc++_shared.so")
val generatedFfmpegJniDir = layout.buildDirectory.dir("generated/ffmpegJniLibs")
val hostIsLinux = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
val verifyFfmpegSourcePins = tasks.register<Exec>("verifyFfmpegSourcePins") {
    group = "verification"
    description = "Verify immutable FFmpeg source commits, archive hashes, and cache guards"
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("build-ffmpeg/verify-source-pins.cjs").absolutePath)
    inputs.files(
        rootProject.file("build-ffmpeg/build.sh"),
        rootProject.file("build-ffmpeg/verify-source-pins.cjs"),
    )
}

mapOf(
    "Arm64" to "arm64-v8a",
    "X64" to "x86_64",
).forEach { (flavorName, androidAbi) ->
    val sourceBinDir = rootProject.file("build-ffmpeg/build-$androidAbi/install/bin")
    val committedJniDir = file("src/main/jniLibs/$androidAbi")
    val sourceFiles = ffmpegBinaryNames.map(sourceBinDir::resolve)
    val missingCommittedBinaryNames = ffmpegBinaryNames.filter { binaryName ->
        !committedJniDir.resolve(binaryName).isFile
    }

    val buildNative = tasks.register<Exec>("buildFfmpeg$flavorName") {
        dependsOn(verifyFfmpegSourcePins)
        group = "build"
        description = "Build FFmpeg executables for $androidAbi when no packaged copy exists"
        workingDir(rootProject.file("build-ffmpeg"))
        commandLine("bash", "build.sh")
        environment("ABI", androidAbi)
        inputs.file(rootProject.file("build-ffmpeg/build.sh"))
        outputs.files(sourceFiles)
        onlyIf {
            ffmpegBinaryNames.any { binaryName ->
                !committedJniDir.resolve(binaryName).isFile &&
                    !sourceBinDir.resolve(binaryName).isFile
            }
        }
        doFirst {
            if (!hostIsLinux) {
                throw GradleException(
                    "FFmpeg binaries for $androidAbi are missing. " +
                        "Build them on Linux with `ABI=$androidAbi bash build-ffmpeg/build.sh` " +
                        "before assembling the $flavorName flavor.",
                )
            }
        }
    }

    tasks.register("stageFfmpeg$flavorName") {
        dependsOn(buildNative)
        val stagedAbiDir = generatedFfmpegJniDir.map { it.dir(androidAbi) }
        val missingSourceFiles = missingCommittedBinaryNames.map(sourceBinDir::resolve)
        inputs.files(missingSourceFiles)
        outputs.dir(stagedAbiDir)
        // Always reconcile this tiny staging directory. Gradle directory
        // snapshots may otherwise ignore an extra stale file when the
        // expected output set is empty, recreating duplicate JNI inputs.
        outputs.upToDateWhen { false }
        doLast {
            val outputDir = stagedAbiDir.get().asFile
            project.delete(outputDir)
            outputDir.mkdirs()
            missingSourceFiles.forEach { sourceFile ->
                if (!sourceFile.isFile) {
                    throw GradleException("Missing staged FFmpeg binary: $sourceFile")
                }
            }
            project.copy {
                from(missingSourceFiles)
                into(outputDir)
            }
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
