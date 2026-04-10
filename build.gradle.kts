plugins {
    kotlin("multiplatform") version "2.1.0"
    id("com.android.library") version "8.7.0"
}

group = "com.github.Rockets0003"
version = "1.0.0"

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }
    }
}

android {
    namespace = "com.smartapplinks.track"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
}
