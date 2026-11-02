import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.makealauncher.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.makealauncher"
        // Android 12: public RenderNode, GPU blur (RenderEffect) always available.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release speed (R8-optimized, not debuggable) for measuring performance and battery:
        // debug builds run noticeably slower. Signed with the debug key so anyone can install it,
        // and given its own app ID so it installs next to the debug build.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core"))
}
