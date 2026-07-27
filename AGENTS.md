# AGENTS.md - ADOS Android GCS

Agentic coding instructions for the native Android ground control station.

## Purpose

Work in this repository as an engineering agent for the Android GCS app. Keep
changes idiomatic Kotlin, testable through ViewModels and repositories, and
optimized for dark, landscape-first operator workflows.

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

## Working in the Open

This is a public, open-source repository. Every commit, diff, and branch is
visible the moment it is pushed and stays in history permanently, so a mistake
cannot be un-published by deleting it later. Review what a change actually
contains before committing.

- **Never commit secrets.** API keys, tokens, deploy keys, passwords, private
  certificates, and `.env` files stay out of the tree. Generated secrets belong
  only in gitignored files. If a secret does land in a commit, treat it as
  compromised and rotate it.
- **Never commit real deployment detail.** Hostnames, IP addresses, tunnel
  names, device identifiers, and account names from a live setup are an attack
  surface. Use placeholders such as `example-oem`, `cloud.example.com`,
  `192.168.1.50`, and `mycompany-fleet`.
- **Never commit other people's data.** Personal names, email addresses,
  customer or employer names, real flight logs and GPS traces, and raw log
  dumps that contain any of the above do not belong in a public repository.
- **Tests and resources are published too.** Fixtures, sample payloads, and
  string resources get the same care as source.
- **Respect licensing when bringing in outside code.** Third-party source is
  vendored into a vendor directory with its license intact and is never pasted
  into our own modules.
- **Keep contributions technical.** Architecture, APIs, commands, schemas,
  configuration, hardware interfaces, deployment, and troubleshooting.
  Commercial, pricing, or roadmap commentary does not belong in the codebase.
- **Comments, log strings, commit messages, and PR titles are public too.** Keep
  them bland, factual, and technical.

## Verification

- ViewModel or domain behavior: add or update unit tests and run
  `./gradlew :app:testDebugUnitTest`.
- Compose UI, resources, manifest, navigation, Hilt, or build config: run
  `./gradlew :app:assembleDebug`; add `./gradlew :app:lintDebug` when lintable
  UI or Android API behavior changed.
- Data-layer protocol, network, video, or hardware abstraction: test the
  interface behavior and fallback states.
- User-visible text changes: verify strings live in resources.

Before finalizing, run `git diff --check` and report any skipped checks.

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
