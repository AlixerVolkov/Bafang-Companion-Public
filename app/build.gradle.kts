import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alixer.bafang"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.alixer.bafang"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.19.0"
    }
    // Stable signature for personal GitHub releases. The beta signing material is kept
    // in the PRIVATE repository only; migrate it to GitHub Actions Secrets before public distribution.
    val betaKey = rootProject.file(".ci/beta-signing.p12")
    val betaProperties = rootProject.file(".ci/beta-signing.properties")
    if (betaKey.exists() && betaProperties.exists()) {
        val props = Properties()
        betaProperties.inputStream().use { props.load(it) }
        signingConfigs {
            create("privateBeta") {
                storeFile = betaKey
                storePassword = System.getenv("BAFANG_STORE_PASSWORD") ?: props.getProperty("storePassword")
                keyPassword = System.getenv("BAFANG_KEY_PASSWORD") ?: props.getProperty("keyPassword")
                keyAlias = System.getenv("BAFANG_KEY_ALIAS") ?: props.getProperty("keyAlias")
            }
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("privateBeta")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core:1.15.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}