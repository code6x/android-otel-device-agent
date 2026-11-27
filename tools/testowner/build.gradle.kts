// A test device owner, for the emulator only: sets the agent's managed configurations over adb
// (README, "Managed configurations"). Not released; never install it on a phone in use.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.code6x.testowner"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.code6x.testowner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
}

kotlin {
    jvmToolchain(17)
}
