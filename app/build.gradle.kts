plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wazetoggle"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wazetoggle"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // CI signs with a fixed key from the repo secrets (SIGNING_KEYSTORE_BASE64 / SIGNING_KEYSTORE_PASSWORD),
    // so each new APK installs over the previous one. Without them, fall back to the debug key.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val keystorePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
    val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && file(keystorePath).exists()
    if (hasReleaseKey) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath!!)
            storePassword = keystorePassword
            keyAlias = "wazetoggle"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
