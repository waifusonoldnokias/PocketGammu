import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: Nokia formats (OTA bitmaps, Smart Messaging, RTTTL, MIDI, NRT, dithering).
// No Android dependencies, so it is fast to unit-test on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
