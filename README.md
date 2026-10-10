# ADOS Android GCS

Native Android ground control station for the ADOS drone ecosystem, written in
Kotlin with Jetpack Compose for phones, tablets and handheld controllers
running Android 10 (API 29) or later. Portrait and landscape are both
supported.

## Link families

The app reaches nodes through every ADOS link family. Each family is a
separate module behind the shared contracts in `:core:model`:

- **Agent on the LAN**: REST on port 8080, MAVLink over WebSocket on port
  8765, WHEP video, ground-station event streams, and the ground-station relay
  to the drones it carries.
- **Direct flight controller**: USB serial, UDP and TCP.
- **Cloud** (opt-in): sign-in, MQTT v5 control, WebRTC over MQTT and the
  fMP4 video relay.
- **Phone radio**: a receive-only WFB link through a USB radio dongle attached
  to the phone.

## Build

Requirements: JDK 17, Android SDK platform 37.0 (`platforms;android-37.0`),
build-tools 37 and NDK 26.3.11579264 (for the radio modules). The app
compiles against API 37 and targets API 36.

```bash
git clone https://github.com/altnautica/ADOSAndroidGCS.git
cd ADOSAndroidGCS
cp local.properties.example local.properties   # optional build-time endpoints
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

Toolchain: Gradle 9.6, Android Gradle Plugin 9.4 with built-in Kotlin,
Kotlin 2.4, KSP, Hilt, Compose (BOM 2026.09), Material 3 adaptive, Navigation
3, Room, DataStore, OkHttp 5 with kotlinx.serialization, MapLibre Native and
the WebRTC Android SDK. Versions live in `gradle/libs.versions.toml`; shared
build configuration lives in the `build-logic/` convention plugins.

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

`core/designsystem/src/main/kotlin/com/altnautica/gcs/core/designsystem/tokens/AdosTokens.kt`
is generated from the shared ADOS design tokens and is never edited by hand.

## Related projects

- [ADOS Mission Control](https://github.com/altnautica/ADOSMissionControl) - browser ground control station
- [ADOS Drone Agent](https://github.com/altnautica/ADOSDroneAgent) - companion and ground-node agent

## License

GPL-3.0-only. See [LICENSE](LICENSE). Third-party components and their
licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
