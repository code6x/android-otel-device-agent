plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.code6x.otelspike"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.code6x.otelspike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // opentelemetry-android 1.8.1 is built on the SDK 1.66.0; the OpAMP client pulls API 1.67.0.
    // Mixed versions crash at start (ConfigUtil moved), so all core artifacts are held at 1.66.0.
    implementation(enforcedPlatform("io.opentelemetry:opentelemetry-bom:1.66.0"))
    implementation("io.opentelemetry.android:android-agent:1.8.1")
    implementation("io.opentelemetry.android:core:1.8.1-alpha")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry.contrib:opentelemetry-opamp-client:1.62.1-alpha")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // For the own queue: the OTLP protobuf marshaler and OkHttp.
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-common")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
}

tasks.register("spikeListJars") {
    val files = configurations.named("releaseRuntimeClasspath").map { c ->
        c.incoming.artifactView { attributes { attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), "android-classes-jar") } }.files
    }
    doLast { files.get().forEach { println("JAR " + it) } }
}

// AGP 8.10 compiles against API 36. Newer androidx.core and okhttp-android ask for API 37.
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.16.0", "androidx.core:core-ktx:1.16.0")
        force("com.squareup.okhttp3:okhttp:5.1.0", "com.squareup.okhttp3:okhttp-android:5.1.0")
    }
}
