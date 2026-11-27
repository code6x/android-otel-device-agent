pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-otel-device-agent"
include(":app")
// Emulator test tool only (README, "Managed configurations").
include(":testowner")
project(":testowner").projectDir = file("tools/testowner")
