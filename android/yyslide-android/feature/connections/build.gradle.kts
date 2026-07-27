plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.feature.connections"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:ssh"))
    implementation(project(":core:security"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:knock"))
    implementation(project(":core:spa"))
    implementation(project(":feature:tunnel"))
    implementation(project(":feature:settings"))
    implementation(project(":core:reticulum"))
    implementation(project(":core:mosh"))
    implementation(project(":core:et"))
    implementation(project(":core:data"))
    implementation(project(":core:smb"))
    implementation(project(":core:rclone"))
    implementation(project(":core:fido"))
    implementation(project(":core:local"))
    implementation(project(":core:wayland"))
    implementation(project(":core:stepca"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.viewmodel)

    testImplementation(project(":core:rdp"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
