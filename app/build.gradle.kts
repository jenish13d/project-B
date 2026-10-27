import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// sherpa-onnx (github.com/k2-fsa/sherpa-onnx) runs the Kokoro neural voice on the phone. It's only
// published on GitHub releases, so fetch the AAR once and check it against a known hash.
val sherpaVersion = "1.12.40"
val sherpaSha256 = "a7699d6a2e638a38791b2459cb0099c7577e6e5fca6b8ae916742c872a618b82"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile

if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    val bytes = URI(url).toURL().openStream().use { it.readBytes() }
    val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(actual == sherpaSha256) { "sherpa-onnx AAR checksum mismatch: $actual" }
    sherpaAar.writeBytes(bytes)
}

android {
    namespace = "com.blecky.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blecky.assistant"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // 64-bit ARM covers every phone from the last several years and keeps the APK small.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
