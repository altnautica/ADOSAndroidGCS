# AGENTS.md - ADOS Android GCS

Agentic coding instructions for the native Android ground control station.

## Stack and Commands

- Kotlin 2.0, Jetpack Compose, Material 3, Hilt, StateFlow, Navigation Compose.
- Min SDK 29, target SDK 34, JDK 17.
- Common commands:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

## Architecture Guidelines

- Architecture is MVVM with UI, domain, and data layers.
- ViewModels expose `StateFlow`. Composables collect with
  `collectAsStateWithLifecycle()`.
- Prefer stateless composables. Hoist state to ViewModels.
- All ViewModels use `@HiltViewModel`. Repositories use constructor injection.
- UI screens end in `Screen`, ViewModels end in `ViewModel`, repositories end in
  `Repository`.
- MAVLink parsing and command encoding stay in the data layer. Do not expose
  raw MAVLink types to UI components.

## UI and Testing

- User-visible strings belong in resources, not inline Compose text.
- The app is dark-theme and landscape-first. Layouts must work in sensor
  landscape on tablets and controllers.
- Add unit tests for ViewModels and data behavior touched by a change.
- Keep hardware, networking, and video integrations behind interfaces that can
  be exercised in tests.

## Repository Boundary

Keep repo instructions, docs, comments, tests, and examples self-contained and
technical. Document behavior through code architecture, APIs, commands, resource
handling, hardware interfaces, build steps, and operator workflows. Keep this
repository self-contained. Describe integrations through documented APIs,
package names, public protocols, and public project links.

## Related Public Projects

- [ADOS Drone Agent](https://github.com/altnautica/ADOSDroneAgent) - companion
  and ground-node agent this Android app can connect to.
- [ADOS Mission Control](https://github.com/altnautica/ADOSMissionControl) -
  browser ground control station with shared product concepts and protocols.
