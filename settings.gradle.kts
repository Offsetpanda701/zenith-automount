pluginManagement {
    repositories {
        maven("https://maven.2b2t.vc/releases")
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = providers.gradleProperty("plugin_name").get()
