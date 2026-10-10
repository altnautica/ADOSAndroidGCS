# AGENTS.md - ADOS Android GCS

Agentic coding instructions for the native Android ground control station.

## Purpose

Work in this repository as an engineering agent for the Android GCS app. Keep
changes idiomatic Kotlin, testable through ViewModels and repositories, and
optimized for operator workflows on phones, tablets and handheld controllers.

## Read First

- Check `git status --short` before edits and preserve unrelated changes.
- Inspect the nearest screen, ViewModel, repository, data model, or test before
  adding new structure.
- Keep MAVLink parsing and command encoding in `:core:mavlink` / `:core:link`.
- Keep hardware, networking, video, and protocol integrations behind testable
  interfaces.
- Prefer focused Gradle tasks before broader builds when they prove the change.

## Stack and Commands

- Gradle 9.6, Android Gradle Plugin 9.4 with built-in Kotlin, Kotlin 2.4, KSP,
  Hilt, Jetpack Compose (BOM), Material 3 + material3-adaptive, Navigation 3,
  Room, DataStore, OkHttp 5 + kotlinx.serialization, MapLibre Native, WebRTC
  Android SDK, usb-serial-for-android.
- Min SDK 29, target SDK 36, compile SDK 37.0 (`platforms;android-37.0`),
  JDK 17, NDK 26.3.11579264 for modules with native code.
- Versions live only in `gradle/libs.versions.toml`. SDK levels, toolchain and
  common dependencies come from the `build-logic/` convention plugins
  (`ados.android.application`, `ados.android.library`, `ados.android.compose`,
  `ados.android.hilt`, `ados.jvm.library`); module build files do not repeat them.
- Common commands:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

- Useful focused commands (replace the module path):

```bash
./gradlew :core:link:testDebugUnitTest
./gradlew :core:model:test
./gradlew :feature:cockpit:lintDebug
./gradlew :app:assembleDebug
```

Run the focused module task first when it covers the touched surface. Use
`:app:assembleDebug` for navigation, manifest, DI, resource, and build-config
changes.

## Architecture Map

Multi-module Gradle build; module `:a:b` uses package `com.altnautica.gcs.a.b`.
Sources live in `<module>/src/main/kotlin/`, unit tests in
`<module>/src/test/kotlin/`.

- `:app`: activity, application, navigation graph, DI root.
- `:core:model`: pure Kotlin contracts every other module compiles against
  (nodes and reaches, `MavlinkByteTransport`, per-topic `VehicleTelemetry`,
  `VehicleCommand`/`CommandResult`, skills, video, alerts). Change it only
  deliberately; every module depends on it.
- `:core:designsystem`: `AdosTheme`, tokens, fonts, base components.
- `:core:mavlink`, `:core:link`: codecs, framing, transports, vehicle, protocols.
- `:core:data`: settings and the credential vault. `:core:database`: Room.
- `:core:agent`: ADOS agent client, discovery, pairing, Wi-Fi join.
- `:core:video`, `:core:map`, `:core:skills`, `:core:extensions`, `:core:cloud`.
- `:radio:rx`, `:radio:usb`: phone radio receive path and USB radio service.
- `:feature:{connect,fleet,node,settings,cockpit,plan,configure,logs}`: screens.
- `:baselineprofile`: baseline profile generator.

Link families plug in through Hilt set multibindings of `NodeSource`,
`TransportProvider`, `VideoSourceFactory` and `SkillContributor`, wired in
`:app`. Screens render state. ViewModels coordinate. Repositories and data
sources own protocol, persistence, and network details.

## Coding Rules

- Architecture is MVVM: feature modules hold screens and ViewModels; core
  modules hold data sources, protocols and repositories.
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
- The app supports portrait and landscape on phones, tablets and controller
  displays; adapt with `currentWindowAdaptiveInfo()` width classes.
- Keep command, telemetry, video, and map surfaces stable under live updates.
- Colours, radius, spacing and motion come from `AdosTheme` and the generated
  `AdosTokens.kt` (`core/designsystem/.../tokens/AdosTokens.kt`). That file is
  generated from the shared ADOS design tokens: never edit it by hand. No
  dynamic colour. Icons are Material Symbols Outlined.
- Status is never colour-only: pair colour with an icon and a label.
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

- ViewModel or domain behavior: add or update unit tests and run the owning
  module's `testDebugUnitTest` (`test` for `:core:model` and `:core:mavlink`).
- Compose UI, resources, manifest, navigation, Hilt, or build config: run
  `./gradlew :app:assembleDebug`; add the module's `lintDebug` when lintable
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
