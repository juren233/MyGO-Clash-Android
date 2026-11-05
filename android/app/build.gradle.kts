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

/** The ABIs of libmygo.aar (build-aar.sh): see android/README.md. */
val abis = listOf("arm64-v8a", "x86_64")

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
    }

    // The release key comes from the environment (the CI's secrets):
    // MYGO_ANDROID_KEYSTORE is the path of the keystore. Without it, release
    // builds are signed with the debug key, so that they still install.
    val keystore = System.getenv("MYGO_ANDROID_KEYSTORE")?.takeIf { it.isNotEmpty() }?.let(::file)
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("MYGO_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MYGO_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("MYGO_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("MYGO_ANDROID_KEYSTORE is not set: the release APKs are signed with the debug key")
            }
        }
    }

    // An APK per ABI, smaller to download, and a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
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

// The release APKs under the names of the desktop app's files, for the
// release: mygo-clash-<version>-android-<arm64|amd64|universal>.apk.
val dist by tasks.registering(Copy::class) {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(layout.buildDirectory.dir("dist"))
    val arch = mapOf("arm64-v8a" to "arm64", "x86_64" to "amd64", "universal" to "universal")
    rename { name ->
        val abi = Regex("^app-(.+)-release\\.apk$").find(name)?.groupValues?.get(1) ?: return@rename name
        "mygo-clash-$appVersion-android-${arch[abi] ?: abi}.apk"
    }
}
