plugins {
    id("zenithproxy.plugin.dev") version "1.2.+"
}

group = property("maven_group") as String
version = property("plugin_version") as String

val mc = property("mc") as String
val pluginId = property("plugin_id") as String

// Matches the current ZenithProxy 1.21.4 example-plugin template.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

zenithProxyPlugin {
    buildConstants {
        fields = mapOf(
            "VERSION" to project.version.toString(),
            "MC_VERSION" to mc,
            "PLUGIN_ID" to pluginId,
            "MAVEN_GROUP" to project.group.toString(),
        )
    }
    // ZenithProxy 1.21.4 plugins run on Java 21 or newer.
    javaReleaseVersion = JavaLanguageVersion.of(21)
    runTaskMixinLauncher = true
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")
}
