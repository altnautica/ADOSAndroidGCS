<p align="center">
  <a href="https://altnautica.com">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset=".github/assets/brand/altnautica-wordmark-dark.svg">
      <img src=".github/assets/brand/altnautica-wordmark-light.svg" alt="Altnautica" width="240">
    </picture>
  </a>
</p>
<h1 align="center">ADOS Android GCS</h1>
<p align="center"><em>Native Android ground control for ADOS drones, ground nodes and flight controllers.</em></p>
<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License: GPL-3.0"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg" alt="Kotlin 2.4"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84.svg" alt="Android 10+">
  <a href="https://discord.gg/uxbvuD4d5q"><img src="https://img.shields.io/badge/Discord-Join-5865F2.svg" alt="Discord"></a>
</p>
<p align="center">
  <strong><a href="https://github.com/altnautica/ADOSMissionControl">Mission Control</a></strong> |
  <strong><a href="https://github.com/altnautica/ADOSDroneAgent">Drone Agent</a></strong> |
  <strong><a href="https://github.com/altnautica/ADOSAndroidGCS">Android GCS</a></strong> |
  <strong><a href="https://github.com/altnautica/ADOSExtensions">Extensions</a></strong> |
  <strong><a href="https://github.com/altnautica/ADOS-MCP">MCP</a></strong> |
  <strong><a href="https://github.com/altnautica/ADOSDroneAgent/tree/main/crates#published-crates">Rust crates</a></strong> |
  <strong><a href="https://docs.altnautica.com">Docs</a></strong> |
  <strong><a href="https://altnautica.com">Website</a></strong> |
  <strong><a href="https://discord.gg/uxbvuD4d5q">Discord</a></strong>
</p>
<p align="center">
  <a href="https://docs.altnautica.com/android/overview">Docs</a> ·
  <a href="https://github.com/altnautica/ADOSAndroidGCS/issues">Issues</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/scenes/ground-node-dark.webp">
    <img src=".github/assets/scenes/ground-node-light.webp" alt="An ADOS ground node in the field" width="100%">
  </picture>
</p>

ADOS Android GCS is the native Android ground station of ADOS, an open-source platform for software-defined drones. It is written in Kotlin with Jetpack Compose for phones, tablets and handheld controllers running Android 10 (API 29) or later, in either screen orientation. It reaches an ADOS Drone Agent on the LAN, a flight controller directly over USB, UDP or TCP, and your fleet through the opt-in cloud.

It is the handheld counterpart to [ADOS Mission Control](https://github.com/altnautica/ADOSMissionControl): the same drones and the same agents, from a device you carry into the field. The app grows with each release, and contributions are welcome (see [Contributing](#contributing)).

## Quick start

Requirements: JDK 17, Android SDK platform 37.0 (`platforms;android-37.0`), build-tools 37 and NDK 26.3.11579264 (for the radio modules). The app compiles against API 37 and targets API 36.

```bash
git clone https://github.com/altnautica/ADOSAndroidGCS.git
cd ADOSAndroidGCS
cp local.properties.example local.properties   # optional build-time endpoints
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. See the [install guide](https://docs.altnautica.com/android/install) for side-loading it onto a device.

## In the app

<table>
  <tr>
    <td width="50%">
      <img src=".github/assets/loops/android-demo.gif" alt="Take-off of a demo drone in the cockpit">
      <p align="center"><a href="https://docs.altnautica.com/android/overview">Fly from the cockpit</a></p>
    </td>
    <td width="50%">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset=".github/assets/screens/android-home-dark.webp">
        <img src=".github/assets/screens/android-home-light.webp" alt="The fleet list">
      </picture>
      <p align="center"><a href="https://docs.altnautica.com/android/overview">Every node in one fleet list</a></p>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset=".github/assets/screens/android-hud-dark.webp">
        <img src=".github/assets/screens/android-hud-light.webp" alt="The cockpit flight display">
      </picture>
      <p align="center"><a href="https://docs.altnautica.com/android/overview">Cockpit flight display</a></p>
    </td>
    <td width="50%">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset=".github/assets/screens/android-map-dark.webp">
        <img src=".github/assets/screens/android-map-light.webp" alt="A waypoint mission on the Plan tab">
      </picture>
      <p align="center"><a href="https://docs.altnautica.com/android/overview">Plan and upload missions</a></p>
    </td>
  </tr>
</table>

## How ADOS fits together

```mermaid
flowchart LR
  subgraph air["On the drone"]
    FC["Flight controller<br/>ArduPilot · PX4 · Betaflight · iNav"]
    DA["ADOS Drone Agent"]
  end
  subgraph ground["On the ground"]
    GA["ADOS Ground Agent"]
    MC["ADOS Mission Control<br/>browser · desktop"]
    AG["ADOS Android GCS"]
    MCP["ADOS MCP<br/>AI clients"]
  end
  EXT["ADOS Extensions"]
  FC <-- MAVLink --> DA
  DA <-- "long-range radio link" --> GA
  MC <-- "LAN or cloud relay" --> DA
  MC <-- LAN --> GA
  AG <-- "LAN or cloud" --> DA
  MCP <-- "LAN or cloud" --> DA
  EXT -. "agent plugins" .-> DA
  EXT -. "GCS panels" .-> MC
```

ADOS Ground Agent is the Drone Agent running its ground-station profile.

<!-- ados-family:repos -->
| Repo | Runs on | What it does | Link |
|---|---|---|---|
| ADOS Mission Control | Browser, desktop (Electron) | Ground control station: FC setup, mission planning, flight, 3D simulation, fleet | [ADOSMissionControl](https://github.com/altnautica/ADOSMissionControl) |
| ADOS Drone Agent | Companion computer on the drone, or a ground node | Onboard agent: MAVLink routing, HD video, long-range radio link, plugins, remote control | [ADOSDroneAgent](https://github.com/altnautica/ADOSDroneAgent) |
| ADOS Android GCS | Android phones and tablets | Native ground control app for agents on the LAN, direct flight controllers and the cloud | [ADOSAndroidGCS](https://github.com/altnautica/ADOSAndroidGCS) |
| ADOS Extensions | Drone Agent and Mission Control | First-party signed plugins, each with an agent half and a GCS half | [ADOSExtensions](https://github.com/altnautica/ADOSExtensions) |
| ADOS MCP | Your machine | Model Context Protocol server that lets AI clients operate ADOS drones under scopes and a safety gate | [ADOS-MCP](https://github.com/altnautica/ADOS-MCP) |
| Documentation | docs.altnautica.com | Public docs for every ADOS product | [Documentation](https://github.com/altnautica/Documentation) |
<!-- /ados-family:repos -->

### ADOS Rust crates

Reusable libraries from the ADOS Drone Agent, published on crates.io. Versioning and licences: [crates index](https://github.com/altnautica/ADOSDroneAgent/tree/main/crates#published-crates).

<!-- ados-family:crates -->
**Platform (versioned with the ADOS Drone Agent)**

| Crate | What it does | Docs |
|---|---|---|
| [`ados-protocol`](https://crates.io/crates/ados-protocol) | Wire contracts of the ADOS Drone Agent: IPC framing, plugin RPC envelope, capability tokens and the vision frame bus. | [docs.rs](https://docs.rs/ados-protocol) |
| [`ados-sdk`](https://crates.io/crates/ados-sdk) | Write Rust plugins for the ADOS Drone Agent: IPC client, plugin context, driver traits and lifecycle runner. | [docs.rs](https://docs.rs/ados-sdk) |
| [`ados-vision`](https://crates.io/crates/ados-vision) | Single-object visual tracker: Kalman motion model, appearance re-identification and YOLOv8 head decoding. | [docs.rs](https://docs.rs/ados-vision) |
| [`ados-gpio`](https://crates.io/crates/ados-gpio) | Drive status buzzers and LEDs on Linux GPIO lines, with bounded software-PWM beep patterns. | [docs.rs](https://docs.rs/ados-gpio) |
| [`ados-hal-probe`](https://crates.io/crates/ados-hal-probe) | Find out what a board's hardware can really do by probing it, plus bundled board profiles. | [docs.rs](https://docs.rs/ados-hal-probe) |

**Standalone**

| Crate | What it does | Docs |
|---|---|---|
| [`ados-sensorhub`](https://crates.io/crates/ados-sensorhub) | Sensor hub wire protocol: COBS framing, CRC-16 and fixed-layout IMU, baro, mag and GNSS packets. no_std. | [docs.rs](https://docs.rs/ados-sensorhub) |
| [`ados-offload`](https://crates.io/crates/ados-offload) | Choose where perception runs (onboard, offloaded or hybrid) and hold the vehicle when results go stale. | [docs.rs](https://docs.rs/ados-offload) |
| [`ados-macpin`](https://crates.io/crates/ados-macpin) | Stable MAC addresses for Linux network adapters that randomize theirs every boot. | [docs.rs](https://docs.rs/ados-macpin) |
| [`ados-lora`](https://crates.io/crates/ados-lora) | LoRa transmit governor with cited regional band plans, duty cycle, dwell time and airtime accounting. | [docs.rs](https://docs.rs/ados-lora) |
| [`ados-crsf-codec`](https://crates.io/crates/ados-crsf-codec) | no_std CRSF / ExpressLRS codec for RC channels, telemetry and parameter frames. | [docs.rs](https://docs.rs/ados-crsf-codec) |
| [`ados-positioning`](https://crates.io/crates/ados-positioning) | Split mixed UBX, NMEA and RTCM3 GNSS streams, configure u-blox receivers and run an RTK base survey-in. | [docs.rs](https://docs.rs/ados-positioning) |
| [`ados-rtcm`](https://crates.io/crates/ados-rtcm) | RTCM3 framing, CRC-24Q and MAVLink GPS_RTCM_DATA fragmentation for RTK corrections. | [docs.rs](https://docs.rs/ados-rtcm) |
| [`ados-clock`](https://crates.io/crates/ados-clock) | Map remote clocks onto the host clock: least-squares and envelope fits, TIMESYNC and a chrony sink. | [docs.rs](https://docs.rs/ados-clock) |
| [`ados-swarm`](https://crates.io/crates/ados-swarm) | Swarm control laws: separation, flocking, formations, CBBA task allocation and a point-mass simulator. | [docs.rs](https://docs.rs/ados-swarm) |
| [`ados-mavlink-tunnel`](https://crates.io/crates/ados-mavlink-tunnel) | Send large messages over MAVLink TUNNEL frames with chunking and bounded reassembly. | [docs.rs](https://docs.rs/ados-mavlink-tunnel) |
| [`ados-display-driver`](https://crates.io/crates/ados-display-driver) | Small-display output on Linux: SSD1306/SH1106 OLED over I2C, pixel packers and a framebuffer writer. | [docs.rs](https://docs.rs/ados-display-driver) |
| [`ados-hmi`](https://crates.io/crates/ados-hmi) | Front-panel input: touchscreen calibration and gestures, button presses and rotary encoders. | [docs.rs](https://docs.rs/ados-hmi) |
<!-- /ados-family:crates -->

## Link families

The app reaches nodes through every ADOS link family. Each family is a separate module behind the shared contracts in `:core:model`:

- **Agent on the LAN**: REST on port 8080, MAVLink over WebSocket on port 8765, WHEP video, ground-station event streams, and the ground-station relay to the drones it carries.
- **Direct flight controller**: USB serial, UDP and TCP.
- **Cloud** (opt-in): sign-in, MQTT v5 control, WebRTC over MQTT and the fMP4 video relay.
- **Phone radio**: a receive-only WFB link through a USB radio dongle attached to the phone.

## Toolchain

Gradle 9.6, Android Gradle Plugin 9.4 with built-in Kotlin, Kotlin 2.4, KSP, Hilt, Compose (BOM 2026.09), Material 3 adaptive, Navigation 3, Room, DataStore, OkHttp 5 with kotlinx.serialization, MapLibre Native and the WebRTC Android SDK. Versions live in `gradle/libs.versions.toml`; shared build configuration lives in the `build-logic/` convention plugins.

## Modules

| Module | Purpose |
|---|---|
| `:app` | Activity, application, navigation graph, dependency injection root |
| `:core:model` | Pure Kotlin contracts shared by every module (nodes, reaches, transports, telemetry, commands, skills, video, alerts) |
| `:core:designsystem` | Theme, generated design tokens, bundled fonts, base components |
| `:core:mavlink` | MAVLink codecs generated from the XML definitions, framing, signing |
| `:core:link` | Vehicle model, MAVLink protocols, USB/UDP/TCP/demo transports |
| `:core:data` | Settings storage and the encrypted credential vault |
| `:core:database` | Room database: flight sessions, tlog index, saved plans |
| `:core:agent` | ADOS agent REST/WebSocket client, discovery, pairing, Wi-Fi join |
| `:core:video` | WHEP client, renderers, stream descriptors, H.264 decode path |
| `:core:map` | MapLibre wrapper, tile providers, offline packs |
| `:core:skills` | Skill registry, activation pipeline, bindings, input routing |
| `:core:extensions` | Extension manifests, extension skills and parameters, WebView bridge |
| `:core:cloud` | Cloud sign-in, fleet, commands, MQTT and cloud video |
| `:radio:rx` | WFB receive path for the phone radio |
| `:radio:usb` | USB radio service running in its own process |
| `:feature:*` | Connect, fleet, node detail, settings, cockpit, plan, configure, logs |
| `:baselineprofile` | Baseline profile generator for app start and the cockpit |

`core/designsystem/src/main/kotlin/com/altnautica/gcs/core/designsystem/tokens/AdosTokens.kt` is generated from the shared ADOS design tokens and is never edited by hand.

## Community

Questions and show-and-tell go in [Discord](https://discord.gg/uxbvuD4d5q). Bugs and feature requests go in [Issues](https://github.com/altnautica/ADOSAndroidGCS/issues).

## Contributing

Issues and pull requests are welcome. Run `./gradlew assembleDebug testDebugUnitTest lintDebug` before opening a pull request, and keep contributions technical and free of private or third-party content.

## License

GPL-3.0-only. See [LICENSE](LICENSE). Third-party components and their licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
