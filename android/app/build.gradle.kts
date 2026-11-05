import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version is the desktop app's, from mygo.json.
@Suppress("UNCHECKED_CAST")
val appVersion = (JsonSlurper().parse(rootDir.resolve("../mygo.json")) as Map<String, Any>)["version"] as String

/** versionCode of a version such as 0.2.28-beta: 0 * 1e6 + 2 * 1e3 + 28. */
fun codeOf(version: String): Int {
    val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 1_000_000 + parts.getOrElse(1) { 0 } * 1_000 + parts.getOrElse(2) { 0 }
}

android {
    namespace = "io.mygo.clash.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.mygo.clash.android"
        minSdk = 24
        targetSdk = 37
        versionCode = codeOf(appVersion)
        versionName = appVersion
        ndk {
            // The ABIs of libmygo.aar: see android/README.md.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Until the project has a release key, release builds are
            // signed with the debug key, so that they install.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // The Go library is large; keep it compressed in the APK.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // gomobile bind output of ../mobile (android/build-aar.sh).
    implementation(files("libs/libmygo.aar"))

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
