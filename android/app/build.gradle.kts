plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val semanticVersion = rootProject.file("../VERSION").readText().trim()
val versionParts = semanticVersion.split(".").map { it.toInt() }
require(versionParts.size == 3 && versionParts.all { it in 0..99 }) {
    "VERSION must contain major.minor.patch with values from 0 to 99"
}

android {
    namespace = "com.miradesktop.sunglasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miradesktop.sunglasses"
        minSdk = 26
        targetSdk = 34
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = semanticVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
