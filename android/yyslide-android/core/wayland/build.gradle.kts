plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sh.haven.core.wayland"
    compileSdk = 36

    defaultConfig {
        minSdk = 26 // Runtime API check guards features needing 28+
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Native .so built by wayland-android prototype (will become includeBuild later)
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:toolbar"))
    implementation(project(":core:data"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
