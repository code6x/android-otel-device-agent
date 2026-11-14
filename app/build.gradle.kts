plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release version: -PversionName=0.1.0 (the release workflow passes the tag).
// versionCode is made from it: 0.1.0 -> 100, 1.2.3 -> 10203.
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0-dev"
val appVersionCode = appVersionName.substringBefore('-').split('.').let { p ->
    (p.getOrNull(0)?.toIntOrNull() ?: 0) * 10000 + (p.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
        (p.getOrNull(2)?.toIntOrNull() ?: 0)
}.coerceAtLeast(1)

// Release signing comes only from the environment (see docs/release.md). No key is in git.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "dev.code6x.otelagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.code6x.otelagent"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures { buildConfig = true }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // All OpenTelemetry core artifacts are held at one version (the spike: mixed versions
    // crashed at start). 1.66.0 is the version opentelemetry-android 1.8.1 is built on.
    implementation(enforcedPlatform("io.opentelemetry:opentelemetry-bom:1.66.0"))
    implementation("io.opentelemetry:opentelemetry-sdk-metrics")
    implementation("io.opentelemetry:opentelemetry-sdk-logs")
    // The OTLP protobuf marshalers, for the own file queue.
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-common")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in unit tests; use the real one there.
    testImplementation("org.json:json:20240303")
    testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.8.0-alpha")
}
