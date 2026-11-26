plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.lunatranslator.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.lunatranslator.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.6.5-preview"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(rootProject.file("engine_bridge"))

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// Keep APK intermediates off the nearly-full C: drive too.
layout.buildDirectory.set(rootProject.file("build/app"))

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Deliberately conservative AndroidX versions: compatible with AGP 8.7.x / compileSdk 35.
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Bundled/offline OCR models: Latin + Japanese.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}
