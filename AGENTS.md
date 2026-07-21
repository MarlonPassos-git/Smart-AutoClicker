# Repository Guidelines

## Project Structure & Module Organization

Klick'r is a multi-module Android application built with Kotlin and Gradle. `smartautoclicker/` contains the application entry point, manifests, flavor resources, and app-level tests. Reusable platform and automation logic lives under `core/common/`, `core/dumb/`, and `core/smart/`. User-facing capabilities are isolated in `feature/<name>/`. Shared Gradle conventions belong in `build-logic/`; dependency versions belong in `gradle/libs.versions.toml`. Android resources use each module's `src/main/res/`, while flavor-specific files use directories such as `src/fDroid/` and `src/playStore/`.

## Build, Test, and Development Commands

Use JDK 21 and the checked-in Gradle wrapper. `mise install` configures the declared Java toolchain when mise is available.

- `./gradlew assembleFDroidDebug` builds FDroid debug APKs.
- `./gradlew testFDroidDebugUnitTest` runs the complete local unit-test suite; this is the CI test command.
- `./gradlew :feature:tutorial:testFDroidDebugUnitTest` tests one module while iterating.
- `mise run install-emulator` builds and installs the x86_64 APK on an active emulator.

## Coding Style & Naming Conventions

Follow standard Kotlin and Android formatting with four-space indentation. Keep functions focused and short, favor early returns, and limit nesting. Give modules and functions one responsibility; use specific names rather than generic terms such as `data`, `handler`, or `Manager`. Kotlin types use `PascalCase`, functions and properties use `camelCase`, and Android resources use `snake_case`. Preserve intent comments; add comments for reasons or constraints, not obvious mechanics. Public functions require a short intent docstring and usage example. Inject dependencies through constructors or parameters, and wrap third-party APIs behind project-owned interfaces.

## Testing Guidelines

Tests use JUnit 4, MockK, and Robolectric where Android behavior is needed. Every code adjustment requires a regression test. Place new tests in the module's `src/test/.../__tests__/` package and name them `{subject}.{unit|integration|e2e}.spec.kt`. Keep each test deterministic and focused on observable behavior. Run `./gradlew testFDroidDebugUnitTest` before submitting.

## Commit & Pull Request Guidelines

History uses concise imperative subjects, including Conventional Commit forms such as `feat(android): ...` and issue-prefixed fixes such as `[#975] Fix ...`. Keep each commit scoped to one intent and reference the issue when applicable. Pull requests must explain what changed and why, provide reproducible test steps, link relevant issues, and include screenshots or a demo for visible UI changes. Confirm the FDroid debug build and unit tests pass.
