import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin app-wide models shared by data, designsystem and features.
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
