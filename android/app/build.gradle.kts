import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from `keystore.properties` (gitignored) when present, so a real signing key
// never lives in the repo. Without it, the release variant falls back to the debug key so it stays
// installable for on-device testing. Shipping to users needs a real keystore.
//
// This must be a real key before v1.0 ships. The debug key has a published password and is
// machine-local: anyone could sign an update that installs over a debug-signed app, and if the
// debug keystore is ever regenerated no future update can install in place — every user would have
// to uninstall first. Android has no way to re-key an installed app.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

android {
    namespace = "fi.palonkorpi.sidehome"
    compileSdk = 36

    defaultConfig {
        // SideHome Pixel Edition: its own app id, so it never collides with upstream SideHome. The
        // Kotlin namespace stays `fi.palonkorpi.sidehome` on purpose — it is the one thing that keeps
        // merges from upstream cheap, and nothing user-visible reads it.
        applicationId = "io.github.dnsmith124.sidehome"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // The debug build installs beside a release build (its own app id and label), so a
        // development copy never has to replace the launcher you are actually living on.
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "SideHome PE dev")
        }
        release {
            resValue("string", "app_name", "SideHome Pixel Edition")
            isMinifyEnabled = false
            // Real key when keystore.properties is present; debug key otherwise, so the release
            // variant stays installable for perf testing. Check which one you got before publishing:
            //   apksigner verify --print-certs app-release.apk
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
