# AGENTS.md - ADOS Android GCS

Agentic coding instructions for the native Android ground control station.

## Purpose

Work in this repository as an engineering agent for the Android GCS app. Keep
changes idiomatic Kotlin, testable through ViewModels and repositories, and
optimized for dark, landscape-first operator workflows.

This file is self-contained for public repository work. Do not rely on
instructions outside this repository when writing code, docs, comments, tests,
examples, logs, or commit messages here.

## Read First

- Check `git status --short` before edits and preserve unrelated changes.
- Inspect the nearest screen, ViewModel, repository, data model, or test before
  adding new structure.
- Keep MAVLink parsing and command encoding in the data layer.
- Keep hardware, networking, video, and protocol integrations behind testable
  interfaces.
- Prefer focused Gradle tasks before broader builds when they prove the change.

## Stack and Commands

- Kotlin 2.0, Android Gradle Plugin 8.7, Jetpack Compose, Material 3, Hilt,
  StateFlow, Navigation Compose.
- Min SDK 29, target SDK 34, compile SDK 35, JDK 17.
- Common commands:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

- Useful focused commands:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Run the focused app task first when it covers the touched surface. Use
`assembleDebug` for navigation, manifest, DI, resource, and build-config
changes.

## Architecture Map

- App code: `app/src/main/`
- Unit tests: `app/src/test/`
- Instrumented tests: `app/src/androidTest/`
- Gradle app config: `app/build.gradle.kts`
- Root Gradle versions: `build.gradle.kts`
- MAVLink Java package input: `io/dronefleet/mavlink/`

Within app code, keep the architecture split into UI, domain, and data layers.
Screens render state. ViewModels coordinate use cases. Repositories and data
sources own protocol, persistence, and network details.

## Coding Rules

- Architecture is MVVM with UI, domain, and data layers.
- ViewModels expose `StateFlow`. Composables collect with
  `collectAsStateWithLifecycle()`.
- Prefer stateless composables. Hoist state to ViewModels.
- All ViewModels use `@HiltViewModel`. Repositories use constructor injection.
- UI screens end in `Screen`, ViewModels end in `ViewModel`, repositories end in
  `Repository`.
- Do not expose raw MAVLink types to UI components.
- Use sealed classes or explicit result types for connection, command, and
  telemetry states where failure handling matters.
- Keep coroutine scopes lifecycle-aware. Avoid long-running work in composables.

## UI Rules

- User-visible strings belong in resources, not inline Compose text.
- The app is dark-theme and landscape-first. Layouts must work in sensor
  landscape on tablets and controller displays.
- Keep command, telemetry, video, and map surfaces stable under live updates.
- Use Material 3 components and existing theme tokens before adding custom UI.
- Provide explicit disconnected, loading, denied-permission, and no-data states.
- Guard flight-affecting actions with disabled states or confirmation flows that
  match nearby UI.

## Public Boundary

Keep this repository self-contained and technical. Document behavior through
architecture, APIs, commands, resource handling, hardware interfaces, build
steps, and operator workflows.

Do not include non-public company context, named customers, financial context,
internal planning labels, attribution trails, or source-path hints from outside
this repository. Use neutral placeholders such as `example-oem`,
`cloud.example.com`, and public protocol names.

Comments, examples, fixtures, test names, logs, errors, PR titles, and commit
messages should be bland and technical. Do not write messages that describe a
cleanup of sensitive wording.

## Verification

- ViewModel or domain behavior: add or update unit tests and run
  `./gradlew :app:testDebugUnitTest`.
- Compose UI, resources, manifest, navigation, Hilt, or build config: run
  `./gradlew :app:assembleDebug`; add `./gradlew :app:lintDebug` when lintable
  UI or Android API behavior changed.
- Data-layer protocol, network, video, or hardware abstraction: test the
  interface behavior and fallback states.
- User-visible text changes: verify strings live in resources.

Before finalizing, run `git diff --check` and targeted scans on changed public
files for non-public context, named customers, internal planning labels,
attribution-trail wording, and financial context. Report any skipped checks.

## Review Expectations

When reviewing, list findings first and focus on lifecycle leaks, Compose
recomposition churn, ViewModel state bugs, unsafe command flows, missing
resource strings, data-layer boundary violations, and missing tests. Cite file
and line references.

For implementation work, keep changes scoped to the affected screen or data
flow and verify with the smallest Gradle task that proves it.

## Cross-Repo Impact

- Drone Agent API, telemetry, capability, and command changes may require
  repository or data-source updates here.
- Shared operator workflows should stay conceptually aligned with Mission
  Control where the same feature exists.
- Setup and troubleshooting behavior changes may require Documentation updates.

## Related Public Projects

- [ADOS Drone Agent](https://github.com/altnautica/ADOSDroneAgent) - companion
  and ground-node agent this Android app can connect to.
- [ADOS Mission Control](https://github.com/altnautica/ADOSMissionControl) -
  browser ground control station with shared product concepts and protocols.
