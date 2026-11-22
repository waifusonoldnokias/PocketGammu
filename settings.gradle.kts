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
        maven("https://jitpack.io") // usb-serial-for-android
    }
}

rootProject.name = "PocketGammu"

include(":app")
include(":core:model")
include(":core:nokia")
include(":core:engine")
include(":core:designsystem")
include(":core:data")
include(":core:gammu")
