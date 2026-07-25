import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "flix-jetbrains-plugin"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension.
        intellijPlatform {
            defaultRepositories()
        }
    }
}
