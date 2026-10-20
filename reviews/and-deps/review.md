# Code Review

Date: 2026-10-20

Task: and-deps - Android dependencies and test setup

Scope: committed changes of task and-deps on the final revision, `git diff ffc40318e7627602ff62f8833f6a70a717ac5f73..177102607c993ae08492e66819ee02b7a0d7ac73` (build files, Android unit tests, `docs/ARCHITECTURE.md` stack row; `reviews/and-deps/fixes.md` and `tests.md` read as input). Re-review after the CR-1 fix. The only uncommitted change in the working tree is the refreshed `reviews/and-deps/tests.md` (a helper report, not code).

Requirements:
- `tasks/and-deps.md` acceptance criteria and recorded assumptions (Tink instead of security-crypto; versions from known stable releases; Hilt/Room plugins applied without Application/database)
- `SPEC.md` §2.1, §9.2
- `docs/ARCHITECTURE.md` (stack table, Install, Coverage)
- `docs/QUALITY.md` (report contract)
- `AGENTS.md` project notes (lint as contract, build-file rules, stack table update)
- `reviews/and-deps/fixes.md` (CR-1 / TW-1 fix to verify)

Git range:
- Branch: main
- HEAD: 177102607c993ae08492e66819ee02b7a0d7ac73
- Base/start: ffc40318e7627602ff62f8833f6a70a717ac5f73 (`git merge-base HEAD origin/main`)
- Commits reviewed: 4 (00eab55 "add Android libraries and Robolectric Compose UI test", 881d4c3 "test-library smoke tests and test report", 266d6cd "code review report", 1771026 "record JaCoCo coverage for Robolectric tests (CR-1)")

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of the earlier finding

- CR-1 (P2, JaCoCo recorded no coverage for Robolectric tests): fixed. `android/app/build.gradle.kts` now applies the Gradle core `jacoco` plugin (line 12) and configures `JacocoTaskExtension` on every `Test` task with `isIncludeNoLocationClasses = true` and `excludes = listOf("jdk.internal.*")` (lines 58-64). The fix adds no new dependency or version, and JaCoCo was already in the ARCHITECTURE.md stack row. The current `android/app/build/reports/coverage/test/debug/report.xml` (written 10:57:18, after the HEAD commit at 10:56:26; session `dell26-4cfebf09`) shows `PlaceholderScreen.kt` with LINE 10 covered / 1 missed. Before the fix it was 0 covered. This matches `fixes.md`.

## Acceptance criteria check

- AC1 (libraries): every named library is in `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`. That covers Hilt with `ksp(hilt-compiler)`, Navigation Compose, Retrofit + kotlinx.serialization converter + OkHttp, Room with `ksp(room-compiler)` and the Room plugin, WorkManager, DataStore, Tink (allowed by "(or Tink)"; the choice is recorded as an assumption), Credential Manager, Glance, Coil 3, Robolectric + Compose `ui-test-junit4` (with `ui-test-manifest` as `debugImplementation`), MockK, kotlinx-coroutines-test and Turbine. The root build declares the plugins with `apply false`. Met.
- AC2 (a Robolectric Compose UI test runs in `testDebugUnitTest`): `PlaceholderScreenTest` uses `@RunWith(AndroidJUnit4::class)` and the v2 `createComposeRule`, and has a positive and a negative semantics assertion. `isIncludeAndroidResources = true` is set. The current test result XML shows `PlaceholderScreenTest` tests=3 and `TestLibrariesSmokeTest` tests=3, with 0 failures, 0 errors and 0 skipped (timestamp 08:57Z, after HEAD). The new coverage data also confirms the screen code runs. Met.
- AC3 (`./gradlew ktlintCheck testDebugUnitTest lintDebug` passes; stack table updated): I ran `cd android && ./gradlew --offline ktlintCheck testDebugUnitTest lintDebug` on HEAD, and it ended with BUILD SUCCESSFUL. ktlint and test tasks were up to date from the fixer's run on the same inputs, lint analysis was re-run, and `lint-results-debug.txt` reads "No issues found.". The Android stack row in `docs/ARCHITECTURE.md` lists every added library with a version that matches the catalog. Met.

Gradle prints one deprecation warning (`Configuration.setVisible`, to be removed in Gradle 11). It comes from a plugin, has no effect on Gradle 9.8.1, and is not a lint or ktlint failure, so it is not a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Other observations (not findings)

- The recorded assumptions still hold. Applying the Hilt and Room plugins without an `@HiltAndroidApp` Application or a `@Database` builds cleanly (`hiltJavaCompileDebug NO-SOURCE`, `copyRoomSchemas NO-SOURCE`).
- `okhttp-logging-interceptor` is on the production classpath. Whichever task adds networking must keep it at a level that never logs tokens or mail bodies (AGENTS.md logging rule). Nothing uses it yet.
- The diff contains no secrets, credentials or personal data. Test data is fictitious.

## Review conclusion

No material issues found.
