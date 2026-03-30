# CLAUDE.md - ADOS Android GCS

Agentic coding instructions for the Android ground control station.

## Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (Dagger) |
| State | StateFlow + ViewModel |
| Navigation | Navigation Compose |
| MAVLink | dronefleet/mavlink 1.1.11 |
| Network | Retrofit 2.11 + OkHttp 4.12 + Ktor 2.3 (WebSocket) |
| Maps | Mapbox 11.8 (online) + OSMDroid 6.1 (offline) |
| Async | Kotlin Coroutines + Flow |
| Tests | JUnit 4 + MockK 1.13 |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |

## Conventions

| Rule | Detail |
|------|--------|
| Theme | Dark only. Background #0A0A0F, surface #141414, primary #3A82FF, accent #DFF140 |
| Orientation | Landscape-first (sensorLandscape). All layouts must work in landscape |
| State | ViewModels expose StateFlow. Composables collect with collectAsStateWithLifecycle() |
| DI | All ViewModels use @HiltViewModel. All repositories use @Inject constructor |
| Naming | Screens end in "Screen", ViewModels end in "ViewModel", repositories end in "Repository" |
| Packages | ui/ for composables, data/ for repositories and data sources, domain/ for use cases |
| Testing | Every ViewModel gets a unit test. Use MockK for mocking, turbine for Flow testing |
| Compose | Stateless composables preferred. Hoist state to ViewModel. No side effects in composables |
| MAVLink | All MAVLink parsing in data/mavlink/. Never expose raw MAVLink types to UI layer |
| Strings | All user-visible strings in strings.xml. Support Hindi (values-hi/) and regional languages |

## Build

```bash
./gradlew assembleDebug    # Debug APK
./gradlew test             # Unit tests
./gradlew lint             # Lint checks
```

## Architecture

MVVM with clean-ish layers:
- **UI** (Compose screens + ViewModels) depends on Domain
- **Domain** (use cases, models) depends on nothing
- **Data** (repositories, data sources, MAVLink) implements Domain interfaces

Single Activity (MainActivity), all navigation via Navigation Compose.
