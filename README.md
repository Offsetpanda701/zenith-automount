# Zenith AutoMount

A lightweight [ZenithProxy](https://github.com/rfresh2/ZenithProxy) 1.21.4 plugin that remembers the normal Minecart the bot is riding and automatically attempts to remount that same Minecart if the bot becomes dismounted.

## Features

- Remembers the current standard, rideable Minecart only.
- Attempts to remount that exact Minecart immediately after an ejection and on subsequent client ticks while it remains reachable.
- Continues recovery for a moving Minecart using ZenithProxy's current cached entity position.
- Never scans for or mounts arbitrary nearby Minecarts.
- Clears stale targets on removal and disconnect while retaining the enabled setting across restarts.
- Has exactly two commands and no external services or dependencies.

## Requirements

- ZenithProxy 1.21.4
- Minecraft 1.21.4
- Java 21 or newer at runtime, as required by the ZenithProxy 1.21.4 plugin template

The Gradle build follows the current template's Java 25 toolchain configuration while producing a Java 21-compatible plugin.

## Installation

1. Build the plugin with `./gradlew build`, or download the built JAR.
2. Place the JAR from `build/libs/` in ZenithProxy's `plugins` folder.
3. Start or restart ZenithProxy.

ZenithProxy loads plugins through its documented plugin mechanism. Plugin hot-reloading is not supported.

## Usage

```text
.automount on
.automount off
```

`.automount on` enables recovery and immediately remembers the currently ridden standard Minecart, if there is one. Otherwise it waits for the bot to enter a standard Minecart naturally.

`.automount off` stops all recovery attempts immediately and clears the remembered Minecart. The enabled setting is saved in ZenithProxy's normal plugin configuration.

## Behavior

Zenith AutoMount keeps the exact Minecart entity that the bot actually mounted. It does not search for, select, or enter other nearby Minecarts. If the remembered Minecart is removed, unloaded, or belongs to a previous connection, the target is cleared and the plugin waits for the bot to mount a new standard Minecart.

## Author

OffsetPanda
