# Fix Report

Date: 2026-10-20

Task: and-deps (Android libraries and test libraries)

Scope: current task; approved finding CR-1 / TW-1 from `reviews/and-deps/review.md` and `reviews/and-deps/tests.md`

## Fixed findings

### CR-1 / TW-1 — JaCoCo records no coverage for Robolectric-run tests

Files changed:
- `android/app/build.gradle.kts`

Problem:
With `enableUnitTestCoverage = true`, the JaCoCo agent was attached to `testDebugUnitTest`, but no JaCoCo task extension set `isIncludeNoLocationClasses = true`. Robolectric loads app classes through its instrumenting class loader without a code source location, and JaCoCo skips those by default. `report.xml` showed 0 covered instructions for `PlaceholderScreen.kt` even though the 3 Robolectric tests passed.

Fix:
- Configured `JacocoTaskExtension` on every `Test` task (`tasks.withType<Test>().configureEach { extensions.configure<JacocoTaskExtension> { ... } }`) with `isIncludeNoLocationClasses = true` and `excludes = listOf("jdk.internal.*")`, and imported `org.gradle.testing.jacoco.plugins.JacocoTaskExtension`.
- Added the Gradle core `jacoco` plugin to the `plugins {}` block. Without it the configuration failed: `Could not create task ':app:testDebugUnitTest'. Extension of type 'JacocoTaskExtension' does not exist.` AGP adds the extension only after the build script's `configureEach` action has run. Applying the plugin up front registers the extension on every Test task first. `jacoco` ships with Gradle, so no new dependency or version was added.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` — Pass (BUILD SUCCESSFUL, 54 tasks executed; 6 unit tests in 2 classes, 0 failures, 0 errors, 0 skipped)
- `android/app/build/reports/coverage/test/debug/report.xml`, sourcefile `PlaceholderScreen.kt` — Pass: INSTRUCTION 95 covered / 9 missed, LINE 10 covered / 1 missed, METHOD 5 covered / 1 missed, CLASS 2 covered / 0 missed. Before the fix it was 0 covered.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` — Pass; PlaceholderScreen.kt coverage is now recorded (10/11 lines).

Full test suite:
- Android: the debug unit suite (6 tests) passed in the run above. Server and web: not run (this fix does not touch them).

Lint/type-check/build:
- `ktlintCheck` and `lintDebug` (warnings as errors) — Pass, in the same run.

`.taskcheck`:
- Not run. The sandbox did not allow `sh scripts/check.sh` for this helper, so the parent worker must run it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
