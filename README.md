# Zenith AutoMount

A lightweight [ZenithProxy](https://github.com/rfresh2/ZenithProxy) 1.21.4 plugin that remembers the normal Minecart the bot is riding and automatically attempts to remount that same Minecart if the bot becomes dismounted.

## Features

- Remembers the actually ridden standard Minecart directly from server passenger updates, with a client-tick fallback.
- Attempts to remount that exact Minecart immediately after an ejection and on subsequent client ticks while it remains reachable.
- Continues recovery for a moving Minecart using ZenithProxy's current cached entity position.
- Never scans for or mounts arbitrary nearby Minecarts.
- Stops recovery on remount, normal-reach loss, removal, disconnect, respawn, or world transition while retaining the enabled setting across restarts.
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

A processed dismount triggers an immediate ordinary `INTERACT` with that cart, using the main hand without sneaking. If it remains reachable and unoccupied, each subsequent client tick retries (normally about 20 times per second). Reach is measured from the current cached player eye position to the cart's current bounding box, using the server's entity-interaction-range attribute (3 blocks by default), without extra reach or rotation. An occupied cart is skipped until free; a cart outside reach ends that recovery episode, even if it later returns. A new real ride arms recovery again and replaces any older target.

Queued interactions are cancelled when recovery stops or AutoMount is disabled. There is no movement, pathfinding, chasing, or separate retry scheduler.

Version 1.1.0 logs `[AutoMount]` transitions: enabled, mounted minecart, dismount detected, first remount attempt, remount confirmed, target occupied, target out of range, target removed/invalid, lifecycle cancellation, and disabled. Cart messages include the entity ID. Retries do not log every tick.

Real-world Minecraft/2b2t behavior still requires user testing; a successful build alone does not verify it.

## Author

OffsetPanda
