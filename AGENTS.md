# Agent Guide

## Project overview

NuntiusAndroid is an Android app written in Kotlin. The app currently uses
Jetpack Compose for UI, Hilt for dependency injection, DataStore for auth and
settings preferences, Room for local database support, and Retrofit for HTTP.
The project is early-stage, so follow the existing structure and keep changes
focused instead of introducing a new architecture without a concrete need.

## Build and verify

- Use the checked-in Gradle wrapper: `./gradlew ...`.
- The Gradle daemon JVM is pinned to Java 27 in
  `gradle/gradle-daemon-jvm.properties`; app Java source/target compatibility is
  Java 11. Do not change either setting just to work around a local IDE setup.
- Build the app with `./gradlew :app:assembleDebug`.
- Compile Kotlin with `./gradlew :app:compileDebugKotlin`.
- Run local unit tests with `./gradlew :app:testDebugUnitTest`.
- Run instrumentation tests only when an Android device or emulator is
  available: `./gradlew :app:connectedDebugAndroidTest`.
- Do not edit generated build output under `app/build/`.

## Source layout

- `app/src/main/java/dev/mikadore/nuntius/` contains activities and application
  entry points.
- `lib/` contains shared helpers and API-facing service code, including
  `PairingRequest` and `AuthService` in `lib/auth.kt`.
- `repos/` contains repository abstractions. `AuthRepository` owns persisted
  auth state; `SettingsRepository` owns persisted settings.
- `di/` contains Hilt modules; `database/` contains Room setup.
- `ui/theme/` contains Compose theme definitions.
- `app/openapi/openapi.json` is the tracked API source of truth.

## Kotlin and UI conventions

- Keep Kotlin code in the existing `dev.mikadore.nuntius` package tree and use
  the existing `PascalCase` type and `camelCase` member naming.
- UI is built with Compose and `NuntiusTheme`. Keep composables driven by
  observable state, and keep network or persistence work out of composable
  bodies.
- Activities that use Hilt should have `@AndroidEntryPoint`; application-level
  Hilt setup is in `NuntiusApplication`.
- Prefer existing lifecycle-aware coroutine scopes and Flow patterns when
  connecting UI to repositories.
- Add dependencies through `gradle/libs.versions.toml` where practical.

## API and pairing flow

- Retrofit interfaces and models are generated from `app/openapi/openapi.json`
  into `app/build/generated/openapi/`. Do not hand-edit generated files; change
  the spec or generator configuration in `app/build.gradle.kts` instead.
- `./gradlew :app:openApiGenerate` regenerates the client. App compile tasks are
  configured to run generation first.
- `AuthService` creates Retrofit API interfaces for the host in a
  `PairingRequest`. Use the generated APIs there rather than duplicating their
  endpoint paths in UI code.
- Pairing data and API credentials are secrets. Do not log, display, or persist
  them beyond what the pairing flow requires. Keep auth persistence behind
  `AuthRepository`, and only store credentials after the server completes the
  pairing flow successfully.
- The app needs the `INTERNET` permission for network calls; it is declared in
  `app/src/main/AndroidManifest.xml`.

## Tests and changes

- Add or update focused tests when changing parser, repository, or API behavior.
- Before finishing a code change, run the narrowest relevant Gradle build or
  test task and report any checks that could not run.
- Keep generated files, local SDK settings, and machine-specific IDE state out
  of source control.
