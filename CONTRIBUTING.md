# Contributing to ADOS Android GCS

Thanks for your interest in contributing. This guide covers setup, code style, and the PR process.

## Dev setup

1. Install Android Studio Ladybug (2024.2.1) or later
2. Install JDK 17 (Android Studio bundles one)
3. Clone the repo and open it in Android Studio
4. Let Gradle sync finish (first sync downloads ~500MB of dependencies)
5. Connect a device or start an emulator (API 29+)
6. Run the app from Android Studio or `./gradlew installDebug`

## Code style

- Kotlin official style guide. Android Studio default formatting
- 4-space indentation, no tabs
- Trailing commas on multi-line parameter lists
- Explicit return types on public functions
- No wildcard imports
- Max line length: 120 characters

Run the linter before submitting:

```bash
./gradlew lint
```

## Pull request guidelines

1. Fork the repo and create a feature branch from `main`
2. Keep commits focused. One logical change per commit
3. Write a clear PR description explaining what changed and why
4. Add or update tests for any new logic
5. Make sure `./gradlew test` and `./gradlew lint` pass
6. Screenshots or screen recordings for UI changes are helpful

## What to work on

Check the GitHub Issues tab for open items. Issues tagged `good first issue` are a good starting point. If you want to work on something not yet tracked, open an issue first so we can discuss the approach.

## License

By contributing, you agree that your contributions will be licensed under the GPL-3.0-only license that covers this project.
