import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pocketgammu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pocketgammu"
        minSdk = 26          // Android 8.0 Oreo
        targetSdk = 36
        versionCode = 10
        versionName = "1.0"
        // 64-bit only (the app itself has no native code; this trims AndroidX's helper libraries).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release signing: keystore.properties in the project root (never committed), or the
    // POCKETGAMMU_KEYSTORE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD environment
    // variables (for CI). Without either, release builds fall back to the debug key so
    // anyone can build and sideload their own copy.
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        System.getenv("POCKETGAMMU_KEYSTORE")?.let {
            setProperty("storeFile", it)
            setProperty("storePassword", System.getenv("POCKETGAMMU_STORE_PASSWORD"))
            setProperty("keyAlias", System.getenv("POCKETGAMMU_KEY_ALIAS") ?: "pocketgammu")
            setProperty("keyPassword", System.getenv("POCKETGAMMU_KEY_PASSWORD") ?: System.getenv("POCKETGAMMU_STORE_PASSWORD"))
        }
    }
    val hasReleaseKey = keyProps.getProperty("storeFile") != null
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // README screenshots (ui tests in .../shots) only run when asked for:
        // ./gradlew :app:testDebugUnitTest --tests '*ReadmeShots*' -PreadmeShots
        unitTests.all { if (!project.hasProperty("readmeShots")) it.exclude("**/shots/**") }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:nokia"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:gammu"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
