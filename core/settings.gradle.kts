// Lets the pure-Kotlin core be built and tested on its own, without the Android SDK:
//   gradle -p core test
// When building from the repository root this file is ignored and the root settings apply.
// Keep the Kotlin version in sync with the root build.gradle.kts.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "core"
