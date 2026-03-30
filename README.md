# ADOS Android GCS

Native Android ground control station for the ADOS drone ecosystem. Built with Kotlin 2.0 and Jetpack Compose for tablets and phones running Android 10+.

## What it does

- **Video feed** with low-latency decoding (WebRTC from ADOS Ground Station, direct USB WFB-ng, cloud relay fallback)
- **HUD overlay** on live video: attitude, altitude, speed, battery, GPS, flight mode
- **Ground control** interface: arming, mode switching, takeoff/land, RTL, mission upload
- **Map view** with Mapbox (online) and OSMDroid (offline) showing drone position, mission waypoints, geofence
- **Agriculture suite** for spray operations: field boundary drawing, spray pattern generation, flow rate config, coverage tracking
- **Ground station management** panel: WFB-ng link stats, FEC config, recording controls, multi-camera switching, OTA updates
- **MAVLink** over USB serial, WiFi UDP, and cloud relay (MQTT + Convex)
- **Dark theme** matching the Altnautica brand. Landscape-first for field use

## Build

Requirements: Android Studio Ladybug or later, JDK 17, Android SDK 34.

```bash
git clone https://github.com/altnautica/ADOSAndroidGCS.git
cd ADOSAndroidGCS
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

To run tests:

```bash
./gradlew test
```

## Project structure

```
app/src/main/java/com/altnautica/gcs/
  ui/              Compose screens and components
    video/         Video feed + HUD overlay
    gcs/           Flight controls, arming, mode switching
    agriculture/   Spray operations UI
    groundstation/ Ground station management panel
    settings/      App settings
    theme/         Colors, typography, Material 3 dark theme
    navigation/    Nav routes
  data/            Data layer
    mavlink/       MAVLink connection, parsing, command encoding
    groundstation/ WFB-ng link management, ground station API
    video/         Video stream handling (WebRTC, WFB-ng, MSE)
    telemetry/     Telemetry state, ring buffers
  domain/          Business logic, use cases
  util/            Extensions, helpers
```

## Related projects

- [ADOS Mission Control](https://github.com/altnautica/ADOSMissionControl) - Web-based GCS (Next.js)
- [ADOS Drone Agent](https://github.com/altnautica/ADOSDroneAgent) - Onboard drone software (Python)

## License

GPL-3.0-only. See [LICENSE](LICENSE) for details.
