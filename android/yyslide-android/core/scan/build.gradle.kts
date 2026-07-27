import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.scan"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ZXing core is pure Java — no Android plumbing, decodes a luminance
    // array. We adapt a Bitmap → RGBLuminanceSource inside BarcodeDecoder.
    implementation(libs.zxing.core)

    // Tesseract4Android wraps Tesseract 5.x + leptonica via JNI. Ships
    // native libs for arm64 + x86_64 — matches the app's ABI flavors.
    //
    // The published parent artifact transitively pulls both the plain and
    // -openmp child AARs, which carry the same Java classes and trigger
    // checkDuplicateClasses. We depend on the child artifact directly and
    // pick the openmp build (faster on multi-core phones, ~2× recognition
    // throughput vs. the non-openmp build).
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android-openmp:${libs.versions.tesseract4android.get()}")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// Tesseract trained-data fetcher.
//
// The asset is the English "fast" LSTM model from tesseract-ocr/tessdata_fast
// (Apache 2.0). It's ~4 MB — small enough to bundle but big enough that we
// don't want it living in git, so we pull it during the build with a pinned
// SHA-256. The F-Droid recipe runs `assembleRelease` which depends on
// `preBuild`, so the bot picks the file up the same way a local build does.
//
// Pin the version + checksum here. Bump in lockstep when upgrading the
// trained-data set; see tessdata_fast tags at
// https://github.com/tesseract-ocr/tessdata_fast
// ---------------------------------------------------------------------------
val tessdataVersion = "4.1.0"
// Pinned SHA-256 of the tessdata_fast 4.1.0 eng.traineddata. Set to the
// empty string the first time you bump tessdataVersion; the task will
// download the file, print the actual SHA, and fail with a one-liner you
// paste back here. Subsequent runs verify against it. F-Droid's recipe
// must see a non-empty SHA or the build will refuse to fetch.
val tessdataSha256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
val tessdataUrl = "https://github.com/tesseract-ocr/tessdata_fast/raw/$tessdataVersion/eng.traineddata"
val tessdataTarget = layout.projectDirectory.file("src/main/assets/tessdata/eng.traineddata")

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { b -> "%02x".format(b) }

val fetchTessdata = tasks.register("fetchTessdata") {
    description = "Downloads the English Tesseract fast LSTM model into assets/tessdata/."
    val target = tessdataTarget.asFile
    inputs.property("url", tessdataUrl)
    inputs.property("sha256", tessdataSha256)
    outputs.file(target)
    doLast {
        val expected = tessdataSha256.lowercase()
        if (target.exists() && expected.isNotEmpty()) {
            val actual = sha256Hex(target.readBytes())
            if (actual == expected) {
                logger.lifecycle("tessdata up to date ($actual)")
                return@doLast
            }
            logger.lifecycle("tessdata checksum mismatch ($actual != $expected) — re-downloading")
            target.delete()
        } else if (target.exists() && expected.isEmpty()) {
            // No pin set yet — leave whatever's on disk in place and print
            // its checksum so the developer can paste it in.
            val actual = sha256Hex(target.readBytes())
            logger.warn(
                "tessdata SHA-256 unpinned. File present: sha256=$actual. " +
                    "Paste this into tessdataSha256 in core/scan/build.gradle.kts.",
            )
            return@doLast
        }
        target.parentFile?.mkdirs()
        logger.lifecycle("Downloading $tessdataUrl …")
        URI(tessdataUrl).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256Hex(target.readBytes())
        if (expected.isEmpty()) {
            logger.warn(
                "tessdata downloaded (${target.length()} bytes). " +
                    "Pin tessdataSha256 = \"$actual\" in core/scan/build.gradle.kts " +
                    "to make the build reproducible (F-Droid will require this).",
            )
            return@doLast
        }
        if (actual != expected) {
            target.delete()
            throw GradleException(
                "tessdata SHA-256 mismatch: got $actual, expected $expected. " +
                    "Update tessdataSha256 in core/scan/build.gradle.kts if you " +
                    "intentionally bumped tessdataVersion.",
            )
        }
        logger.lifecycle("tessdata downloaded (${target.length()} bytes, sha256=$actual)")
    }
}

// Pull the asset before any merge step touches assets/, so unit tests
// and release builds both find the file.
afterEvaluate {
    tasks.matching {
        it.name == "preBuild" ||
            it.name.startsWith("merge") && it.name.endsWith("Assets")
    }.configureEach {
        dependsOn(fetchTessdata)
    }
}
