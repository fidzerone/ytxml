import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// yt-dlp wrapper library (bundles Python + yt-dlp). Bump this one line to update it.
val youtubedlAndroidVersion = "0.18.1"

android {
    namespace = "com.example.ytchat2xml"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ytchat2xml"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Real phones only. Add "x86_64" here if you want to run it in a PC emulator (bigger APK).
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // yt-dlp runs as a native executable, so the native libs must be extracted to disk.
    packaging {
        jniLibs {
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
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroidVersion")
    implementation("com.google.code.gson:gson:2.11.0")
}
