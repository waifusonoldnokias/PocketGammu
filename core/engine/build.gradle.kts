plugins {
    alias(libs.plugins.android.library)
}

// GammuDroid's engine: a pure-Java port of Gammu's Nokia protocols (FBUS, Phonet, N6110 /
// N7110 / N6510, Series 40 filesystem), its userspace IrDA stack and USB drivers
// (PL2303, STIr4200, Nokia USB). Ported from GammuDroid 1.0.
android {
    namespace = "com.pocketgammu.engine"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.usb.serial)
    testImplementation(libs.junit)
}
