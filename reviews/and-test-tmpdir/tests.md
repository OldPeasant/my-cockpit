# Test Report

Date: 2026-11-02

Task: and-test-tmpdir - Android: unit tests write Robolectric temp files into build/, not the shared /tmp

Scope: immutable base f3d3b5bd248e4d9f7aa735cfe856c2b2f458767f..HEAD d6e5b032d80b849a45eae59477ddf1c82f7fd1ed on branch main.
Task commits 59195dba0 and d6e5b032d (rebased copies of a850a466b and 55ffdf03f named by the parent; `git patch-id --stable`
of both ranges is identical, 65c8a6a12d77f5df534dc0fac4317e5803be78d4). Changed file: `android/app/build.gradle.kts`
(+5 lines). No uncommitted changes before this report.

Requirements:
- tasks/and-test-tmpdir.md acceptance criteria
- AGENTS.md (Evidence, quality gate), docs/QUALITY.md artifact contract

## Test files changed

- None.

## Tests added or updated

Count: 0

Reason: the change only configures the Gradle `Test` task (system property `java.io.tmpdir` set to
`temporaryDir.resolve("jvm")`, created in `doFirst`). There is no production Kotlin code to unit-test. A JUnit test asserting
`System.getProperty("java.io.tmpdir")` inside the test JVM would only restate the build script and would not prove the
real criterion (no Robolectric files in /tmp); the meaningful evidence is running the Robolectric suite and observing where
its temp files land, which the parent did (below). Adding such a test was judged low-value and was not done.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. `tasks.withType{Test}` sets `java.io.tmpdir` to a dir under the build directory, created before the run | Inspection of diff; observed runtime behaviour of existing Robolectric tests SessionCacheTest, CockpitThemeTest | `git diff f3d3b5bd2..HEAD -- android` (sets property to `temporaryDir/jvm` = android/app/build/tmp/testDebugUnitTest/jvm, `doFirst { mkdirs() }`); parent: `./gradlew testDebugUnitTest --tests '*SessionCacheTest' --tests '*CockpitThemeTest' --rerun` while polling that dir every 0.5 s | Pass: robolectric-nativeruntime dirs (3, up to 157336 KB) and robolectric-{TestName} dirs appeared under build/tmp/testDebugUnitTest/jvm; this test-writer confirmed a leftover robolectric-CockpitThemeTest_... dir there |
| 1b. No new files appear in /tmp during testDebugUnitTest | none (direct observation needed) | listing /tmp | Not run: listing /tmp is blocked for agents by sandbox policy; indirect evidence only (Robolectric output observed in the build dir, java.io.tmpdir redirected for every Test task) |
| 2. `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | full existing Android unit suite | parent: `cd android && ./gradlew clean ktlintCheck testDebugUnitTest lintDebug --console=plain` | Pass: BUILD SUCCESSFUL, 61 tasks; test-results XML 2865 tests, 0 skipped, 0 failures, 0 errors |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests '*SessionCacheTest' --tests '*CockpitThemeTest' --rerun` (run by the parent;
sub-agents cannot run Gradle in this environment)

Result:

Pass (temp files observed under android/app/build/tmp/testDebugUnitTest/jvm)

## Full test execution

Command:

`cd android && ./gradlew clean ktlintCheck testDebugUnitTest lintDebug --console=plain` (run by the parent at HEAD d6e5b032d)

Result:

Pass (2865 tests, 0 failures, 0 errors, 0 skipped)

## .taskcheck

Result:

Not run by the test-writer (Gradle not runnable by sub-agents); the parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None.

Observation (not a defect): android/app/build/tmp/testDebugUnitTest also holds Gradle's own jar_extract_*_tmp files next to
`jvm/`; both live in the build dir and are removed by `./gradlew clean`, as the task intends.

## Coverage

Status: not applicable - the task changes only a Gradle build script (`android/app/build.gradle.kts`); no production Kotlin
source changed, so there is nothing for the project's coverage tooling (JaCoCo/Kover per docs/ARCHITECTURE.md "Coverage")
to instrument.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no executable production lines in scope).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-test-tmpdir; base f3d3b5bd248e4d9f7aa735cfe856c2b2f458767f; HEAD d6e5b032d80b849a45eae59477ddf1c82f7fd1ed; commits 59195dba0, d6e5b032d (from parent, verified by patch-id and task `touches`).
- Source/test snapshot identifier (including uncommitted inputs): sha256 of android/app/build.gradle.kts 64cb84403c8e777246702609e8fc5052d39eb7592518e139530eb948147eddcc; no uncommitted source/test changes.
- Exact included files; renamed/deleted/excluded files and reasons: none included; `android/app/build.gradle.kts` excluded as build configuration, not instrumentable production code.
- Provider/version, command, working directory, and test selection: N/A (no coverage run).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A.
- Final run after latest test additions and production fixes: N/A (no tests added, no fixes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| none in scope | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `android/app/build.gradle.kts:93-97` | test JVM temp dir redirected into build/ | Verified by running the Robolectric suite and observing the temp dir (see Requirement coverage); not unit-testable |

## Unable to test

- Direct check that /tmp receives no new files during `testDebugUnitTest`: listing /tmp is denied by the agent sandbox.
  Follow-up: a human may confirm once (e.g. `ls -d /tmp/robolectric-* ` before and after a run) and delete the stale
  /tmp/robolectric-nativeruntime directories noted in the task.

## Summary

No additional tests were necessary for the reviewed scope.
