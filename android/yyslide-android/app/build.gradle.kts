plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yyslide.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 438
        versionName = "5.51.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += listOf("abi")
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("yyslide-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo.include = false
        }
    }

    // Version code scheme: base * 10 + abiOffset
    val abiCodes = mapOf("arm64" to 1, "x64" to 2)

    applicationVariants.all {
        val variant = this
        val abi = variant.productFlavors.first { it.dimension == "abi" }.name
        val abiCode = abiCodes[abi]
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (abiCode != null) {
                output.versionCodeOverride = (defaultConfig.versionCode ?: 0) * 10 + abiCode
            }
            output.outputFileName = "yyslide-${variant.versionName}-$abi-${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf(
            "en", "zh", "ar", "bn", "de", "es",
            "fr", "hi", "ja", "ko", "pt", "ru",
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/*/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // FFmpeg executables are application assets (they are execve'd from
    // nativeLibraryDir), and this module owns the ABI flavor. Pull both the
    // intentional prebuilt release assets and Linux-generated replacements
    // from :core:ffmpeg; abiFilters selects the matching directory.
    sourceSets.getByName("main").jniLibs.srcDirs(
        rootProject.file("core/ffmpeg/src/main/jniLibs"),
        rootProject.file("core/ffmpeg/build/generated/ffmpegJniLibs"),
    )

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

mapOf(
    "Arm64" to ":core:ffmpeg:stageFfmpegArm64",
    "X64" to ":core:ffmpeg:stageFfmpegX64",
).forEach { (flavorName, stageTask) ->
    tasks.configureEach {
        if (name.startsWith("merge$flavorName") && name.endsWith("JniLibFolders")) {
            dependsOn(stageTask)
        }
    }
}


dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:ssh"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(project(":core:reticulum"))

    // Native Kotlin Reticulum transport (rnsh-kt + reticulum-kt)
    implementation("tech.torlando:rnsh-core:0.1.0-SNAPSHOT")
    implementation("network.reticulum:rns-core:0.1.0-SNAPSHOT")
    implementation("network.reticulum:rns-interfaces:0.1.0-SNAPSHOT")
    implementation(project(":core:mosh"))
    implementation(project(":core:et"))
    implementation(project(":core:vnc"))
    implementation(project(":core:rdp"))
    implementation(project(":core:smb"))
    implementation(project(":core:rclone"))
    implementation(project(":core:ffmpeg"))
    implementation(project(":core:fido"))
    implementation(project(":core:usb"))
    implementation(project(":core:local"))
    implementation(project(":core:wayland"))
    implementation(project(":core:terminal-haven"))
    // Direct termlib pull-in so the MCP agent transport can name termlib
    // public types (TerminalEmulator, ScrollController, SelectionController,
    // SelectionRange, AgentSnapshot) by class. core:terminal-haven uses
    // `implementation(libs.termlib)` so the dependency doesn't leak.
    implementation(libs.termlib)
    implementation(project(":core:stepca"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:knock"))
    implementation(project(":core:spa"))

    implementation(project(":feature:connections"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sftp"))
    implementation(project(":feature:keys"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:imagetools"))
    implementation(project(":feature:vnc"))
    implementation(project(":feature:rdp"))
    // The app manifest registers CloudflareAccessLoginActivity directly, so
    // the owning module must be on the app compile/lint classpath rather than
    // only arriving transitively through :feature:connections.
    implementation(project(":feature:tunnel"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.biometric)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
