# Test Report

Date: 2026-11-02

Task: test-tmp-in-build - Server and Android tests keep temp files in build/, not /tmp

Scope: task commit 5adf18af8fa5dc4b382438a2f96309578be3c488 against immutable base bd4b11a35abd0ea6a52080d0d49edbba82b09215 (`git diff bd4b11a35..5adf18af8`), as supplied by the parent worker. Task-owned code paths in that range: `server/build.gradle.kts`, `android/app/build.gradle.kts`, `docs/ARCHITECTURE.md` (the other paths in the range are `tasks/*.md` board files of other tasks and are excluded). Inspected HEAD b66b1c98b80cfae7d505c28e096cd68ef3247942; `git diff 5adf18af8 HEAD` on the three task files is empty, so HEAD has the same task content. Working tree had no uncommitted changes to those files.

Requirements:
- `tasks/test-tmp-in-build.md` acceptance criteria and notes
- `AGENTS.md` project notes (test layout, "no test code changes unless a test hard-codes /tmp")
- Parent worker instructions (Gradle is run only by the parent)

## Test files changed

- None

## Tests added or updated

Count: 0

Reason: the change is Gradle build configuration only. Each `tasks.withType{Test}().configureEach` block adds a `jvmArgumentProvider` that passes `-Djava.io.tmpdir=build/tmp/test-jvm/{task name}` to the test JVMs and a `doFirst` that deletes and recreates that folder. This behavior belongs to the Gradle build, not to application code; the project has no build-logic test setup (no Gradle TestKit / functional tests for build scripts), and adding one would be new test infrastructure outside this task. The criteria themselves name manual, run-the-build evidence (counting `/tmp` entries before and after the test tasks, killing a test JVM mid-run, running `integrationTest`, timing `scripts/check.sh`), which the parent worker gathers. A unit test inside the suite could at most assert `System.getProperty("java.io.tmpdir")` ends in `build/tmp/test-jvm/...`, which duplicates the configuration and does not prove the criteria (no `/tmp` growth, emptied on next run, survives a killed JVM), so none was added.

## Static checks performed

1. Hard-coded `/tmp` in test or source code: `grep -rnE '"/tmp|/tmp/|java\.io\.tmpdir' server/src android/app/src`.
   - `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/HeaderSyncDovecotTests.kt:68,72`: `/tmp/90-test.conf` is a path inside the Dovecot container (`withCopyToContainer`, then `cp` inside the container), not a host path; unaffected by the host JVM's `java.io.tmpdir`. No change needed.
   - `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWorkDirCleanupTests.kt:80`: reads `System.getProperty("java.io.tmpdir")` and lists entries there, the same directory the production code under test uses via `Files.createTempDirectory`; it follows the new setting automatically. No change needed.
   - Two `main` matches (`MailAttachmentFilesService.kt:28`, `BlobStore.kt:36`) are KDoc comments about `{data-dir}/tmp` folders, not the system temp dir.
   - `android/app/src`: no matches.
   Result: no test hard-codes the host `/tmp`, so the task note "no test code changes unless a test hard-codes /tmp" requires no test edits.
2. Temp-directory API users: `grep -rlE 'createTempDirectory|createTempFile|@TempDir|TemporaryFolder' server/src android/app/src` lists 31 server test files, 3 android test files and 8 production files. All use the JDK/JUnit default temp location (derived from `java.io.tmpdir`), so they keep working unchanged and now write below `build/tmp/test-jvm/{task name}` (criterion 1, "Existing tests ... keep working unchanged"; the actual pass is shown by the parent's Gradle runs).
3. Testcontainers host mounts of temp paths: `grep -rnE 'withFileSystemBind|MountableFile|withCopyFileToContainer' server/src/test` -> no matches, so moving the JVM temp dir into `build/` cannot break a bind mount (relevant to criterion 4).
4. Build-script review of the diff: the provider is a lambda `CommandLineArgumentProvider` evaluated at execution time; `doFirst` runs before the test JVMs fork; the folder is per task name (`test`, `integrationTest`, `testDebugUnitTest`, ...), so parallel test tasks of one module do not empty each other's folder; it lives under `layout.buildDirectory`, so `clean` removes it. `integrationTest` is registered with `tasks.register{Test}` and is therefore covered by `withType{Test}().configureEach` in `server/build.gradle.kts`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Every Test task sets `java.io.tmpdir` under `build/`, emptied at start, removed by `clean`; existing temp-dir tests keep working | Static review of both build scripts; grep checks 1-3 above; existing suites | grep commands above (this agent); Gradle runs by the parent | Static checks pass; Gradle execution not run by this agent (sub-agents cannot run Gradle here) - parent evidence: `cd server && ./gradlew test --tests '*PageFetcherSizeLimit*'` passed and the second run was UP-TO-DATE |
| 2. No new `/tmp` entries after `testDebugUnitTest` and server `test`; second run does not grow `build/tmp/test-jvm` | None (manual build evidence) | Parent: `cd android && ./gradlew testDebugUnitTest` with `/tmp` counts | Not run by this agent: requires Gradle. Parent reports `/tmp/robolectric-nativeruntime*` count stayed at 10 (pre-existing) while `android/app/build/tmp/test-jvm/testDebugUnitTest` grew to 681M; server `test` counts and second-run check are the parent's to record |
| 3. Killed run leaves nothing in `/tmp`; next run empties leftovers | None (manual build evidence) | Parent: `pkill -f "Gradle Test Executor"` during `testDebugUnitTest` | Not run by this agent: requires Gradle and process control |
| 4. `integrationTest` gets the same setting; Testcontainers still works | Static: `integrationTest` is a `Test` task covered by `withType`; no host bind mounts of temp paths | Parent: `cd server && ./gradlew integrationTest --tests '*BackupRoundTrip*'` | Not run by this agent: requires Gradle and a container runtime |
| 5. `sh scripts/check.sh` passes, duration not noticeably longer | None | Parent: `sh scripts/check.sh` (timed, before and after) | Not run by this agent: check runs Gradle |
| 6. ARCHITECTURE.md testing section has one line about `build/tmp/test-jvm` | Read of the diff | `git diff bd4b11a35..5adf18af8 -- docs/ARCHITECTURE.md` | Pass: the line "Test temp files: every Gradle `Test` task of `server/` and `android/app` sets the test JVMs' `java.io.tmpdir` to `build/tmp/test-jvm/{task name}` ..." is present (wrapped over two source lines) |

## Focused test execution

Command:

None run by this agent (no tests added; Gradle is run only by the parent in this environment).

Result:

Not run

## Full test execution

Command:

`sh scripts/check.sh` (to be run by the parent)

Result:

Not run by this agent

## .taskcheck

Result:

Not run by this agent: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle; the parent runs it and records the result and duration (criterion 5).

## Bugs found

None.

## Coverage

Status: not applicable - the task changes no production code.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production source lines in scope).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: test-tmp-in-build; base bd4b11a35abd0ea6a52080d0d49edbba82b09215; task commit 5adf18af8fa5dc4b382438a2f96309578be3c488; inspected HEAD b66b1c98b80cfae7d505c28e096cd68ef3247942; scope from the parent worker and the task's `touches` list.
- Source/test snapshot identifier (including uncommitted inputs): HEAD b66b1c98b; the three task files are identical to 5adf18af8; no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: no production source files in scope. Excluded: `server/build.gradle.kts` and `android/app/build.gradle.kts` (Gradle build scripts, executed by Gradle at configuration/execution time, never loaded into the JaCoCo/Kover-instrumented application or test JVM, so they cannot be instrumented); `docs/ARCHITECTURE.md` (documentation); `tasks/*.md` (board files of this and other tasks).
- Provider/version, command, working directory, and test selection: none run - the project's coverage tooling (ARCHITECTURE.md "Coverage") measures application classes under `server/src/main` and `android/app/src/main`, none of which changed.
- Run start/completion time and exit status: N/A, no coverage run.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A, no coverage output used.
- Final run after latest test additions and production fixes: N/A (no tests added, no production fixes).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production source files changed) | N/A | N/A | N/A | N/A |

No aggregate is reported: the measured scope is empty, which is not 100% coverage. The task's behavior is verified by the manual build runs named in criteria 2-5, not by coverage.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `server/build.gradle.kts` (test tmpdir block) | Criteria 1, 2, 4 | Build script, not instrumentable; verified by parent's Gradle runs |
| `android/app/build.gradle.kts` (test tmpdir block) | Criteria 1, 2, 3 | Build script, not instrumentable; verified by parent's Gradle runs |

## Unable to test

- All Gradle-executed behavior (tmpdir set for every Test task, folder emptied at start, `clean` removes it, no `/tmp` growth, killed-run cleanup, `integrationTest` with Testcontainers, `check.sh` duration): sub-agents cannot run Gradle in this environment. Follow-up: the parent worker runs these and records them with `tasks.py verify` (partly done per the parent: server `PageFetcherSizeLimit` pass + UP-TO-DATE second run; android `/tmp` Robolectric count unchanged at 10 while `build/tmp/test-jvm/testDebugUnitTest` reached 681M).

## Summary

No additional tests were necessary for the reviewed scope. The change is build configuration only; no test or production code hard-codes the host `/tmp` (the only literal `/tmp` in tests is a path inside a Dovecot container), and every temp-file user relies on `java.io.tmpdir`, so it follows the new setting. Task-scoped coverage is not applicable because no production source changed; acceptance criteria 1-5 depend on the parent's Gradle runs.
