# Code Review

Date: 2026-10-20

Task: and-deps - Android dependencies and test setup

Scope: committed changes of task and-deps, `git diff b6054b9217ac518865f0def3e161c25783097e41..c48b333ba3cde772d162ff5b95dc8513d6dff9a2` (build files, Android unit tests, `docs/ARCHITECTURE.md` stack row; `reviews/and-deps/tests.md` read as input). No uncommitted changes were present.

Requirements:
- `tasks/and-deps.md` acceptance criteria and recorded assumptions (Tink instead of security-crypto; versions from known stable releases; Hilt/Room plugins applied without Application/database)
- `SPEC.md` §2.1 (Android stack row), §9.2 (Android client), §9.3 (Room capture queue, WorkManager)
- `docs/ARCHITECTURE.md` (stack table, Install, Testing, Coverage, conventions)
- `docs/QUALITY.md` (report contract)
- `AGENTS.md` project notes (lint as contract, build-file rules, stack table update)
- `reviews/and-deps/tests.md` (TW-1 to confirm or refute)

Git range:
- Branch: main
- HEAD: c48b333ba3cde772d162ff5b95dc8513d6dff9a2
- Base/start: b6054b9217ac518865f0def3e161c25783097e41
- Commits reviewed: 2 (9c52fa9 "and-deps: add Android libraries and Robolectric Compose UI test", c48b333 "and-deps: test-library smoke tests and test report")

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Acceptance criteria check

- AC1 (libraries): all named libraries are in `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`: Hilt with `ksp(hilt-compiler)` (plus androidx.hilt navigation-compose/work and its KSP compiler), Navigation Compose, Retrofit + `converter-kotlinx-serialization` + OkHttp (+ kotlinx-serialization-json and the serialization plugin), Room with `ksp(room-compiler)` and the Room Gradle plugin, WorkManager, DataStore preferences, Tink (`tink-android`, allowed by the "(or Tink)" wording; choice recorded as an assumption), Credential Manager (+ play-services-auth for passkeys on older devices), Glance appwidget/material3, Coil 3 compose + okhttp network, Robolectric + Compose `ui-test-junit4` (+ `ui-test-manifest` as `debugImplementation`), MockK, kotlinx-coroutines-test, Turbine. Plugins are declared `apply false` in the root build. Met.
- AC2 (Robolectric Compose UI test in `testDebugUnitTest`): `PlaceholderScreenTest` runs with `@RunWith(AndroidJUnit4::class)` and the v2 `createComposeRule`, sets `PlaceholderScreen()` and asserts the text is displayed, plus a negative assertion. `isIncludeAndroidResources = true` is set. The existing test result `android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.ui.PlaceholderScreenTest.xml` (2026-10-20T08:51Z) shows tests=3, failures=0, errors=0. Met.
- AC3 (`./gradlew ktlintCheck testDebugUnitTest lintDebug` passes; stack table updated): the existing `android/app/build/reports/lint-results-debug.txt` from the same run reads "No issues found"; the stack row in `docs/ARCHITECTURE.md` lists every added library and version, matching the catalog. I could not re-run Gradle myself in this sandbox (command required approval); the parent must record its own run as evidence. Met per available artifacts.

No criterion evidence was recorded yet in `tasks/and-deps.md` at review time; recording it with `tasks.py verify` is the parent's job.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | JaCoCo records no coverage for Robolectric-run tests (confirms TW-1)**
    - File: `android/app/build.gradle.kts:39`
    - Category: Tests
    - Problem: `enableUnitTestCoverage = true` (line 26) attaches the JaCoCo agent to `testDebugUnitTest`, but no JaCoCo task extension sets `isIncludeNoLocationClasses = true`. Robolectric loads app classes through its instrumenting class loader without a code source location, which JaCoCo ignores by default. Evidence: the current `android/app/build/reports/coverage/test/debug/report.xml` (session `dell26-6a5dc7fe`, same run as the passing test result) reports 0 covered of 137 instructions. That includes `placeholderText` (`PlaceholderScreen.kt:14`), which `placeholderTextNamesTheApp` calls directly and which passed in that run. The test class moved to `@RunWith(AndroidJUnit4::class)` in this task (`PlaceholderScreenTest.kt:12`). Before that, the plain JUnit test was counted, so this task caused the regression. The test-writer's probe (a plain JUnit test made line 14 covered) points to the same cause. TW-1 is confirmed.
    - Why it matters: `docs/ARCHITECTURE.md` "Coverage" names `./gradlew createDebugUnitTestCoverageReport` as the Android coverage source for task-scoped coverage. Every later Android task tests Compose UI and ViewModels under Robolectric, so its reported coverage would always be 0 and its `tests.md` coverage numbers would mean nothing. Coverage is not a gate, so this is P2, not P1.
    - Suggested fix: in `android/app/build.gradle.kts` (inside this task's `touches`), configure the JaCoCo extension of the unit-test tasks, e.g. `tasks.withType<Test>().configureEach { extensions.configure<JacocoTaskExtension> { isIncludeNoLocationClasses = true; excludes = listOf("jdk.internal.*") } }` (import `org.gradle.testing.jacoco.plugins.JacocoTaskExtension`). Then re-run `createDebugUnitTestCoverageReport` and check that `PlaceholderScreen.kt` shows covered lines. The fix stays valid only while ktlint and lint pass.

## P3 Low

None.

## Other observations (not findings)

- Recorded assumptions hold up. Hilt and Room plugins without an `@HiltAndroidApp` Application or a `@Database` do not break the build (the lint and test outputs above came after these plugins were applied). `room { schemaDirectory("$projectDir/schemas") }` points to a folder that does not exist yet and that ARCHITECTURE.md already documents; the first Room database task creates it.
- `okhttp-logging-interceptor` is on the production classpath. The future networking task must keep it at a level that never logs bearer tokens or mail bodies (AGENTS.md logging rule). This task does not use it, so there is nothing to fix here.
- No secrets, credentials or personal data in the diff. Test data is fictitious ("Ada Example").
- The Install section needs no change: every addition is a Gradle dependency the wrapper resolves.

## Review conclusion

Material issues found; fix before completing the task. (CR-1 / TW-1, P2: add the JaCoCo `isIncludeNoLocationClasses` configuration so Robolectric tests produce coverage, then re-run the test-writer and code-reviewer on the fixed revision.)
