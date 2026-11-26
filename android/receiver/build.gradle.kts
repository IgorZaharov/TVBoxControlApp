plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing (docs/RELEASE_SIGNING.md): the release workflow signs the receiver with the upload key, so that
// a newer receiver.apk installs over an older one. Without ANDROID_KEYSTORE_FILE the release build is not signed.
val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")

android {
    namespace = "com.tvboxremote.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvboxremote.receiver"
        minSdk = 24
        targetSdk = 35
        // Grows from build to build, so each build upgrades the one before it (android/build.gradle.kts).
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    // Real org.json for JVM unit tests. The android.jar copy is only a stub.
    testImplementation(libs.org.json)
}
