# Test Report

Date: 2026-11-02

Task: test-tmp-in-build - Server and Android tests keep temp files in build/, not /tmp

Scope: `git diff a0a707e35 9be7b78c5 -- server android docs` (base a0a707e353ce04569afd18367d058f9ff6a3bccb = origin/main at claim; measured HEAD 9be7b78c5b4b056f53df63b9fdb7b760be952614 on branch main of this clone). Task-owned commits: e68c61ff0 (build change), 9d93051af (earlier reports), f7b9b772c (merge of origin/wip/test-tmp-in-build), 9be7b78c5 (removes the earlier task and-test-tmpdir's `temporaryDir.resolve("jvm")` / `systemProperty("java.io.tmpdir", ...)` / `doFirst { mkdirs() }` lines in android, which after the merge duplicated `val testTmpDir`). Working tree clean before this report.

Changed files: `server/build.gradle.kts` (+10), `android/app/build.gradle.kts` (+10/-5), `docs/ARCHITECTURE.md` (+3). No production source (`src/main`) and no test source changed.

Requirements:
- tasks/test-tmp-in-build.md acceptance criteria #1-#6 and Notes ("no test code changes unless a test hard-codes `/tmp`")
- AGENTS.md, docs/ARCHITECTURE.md (Testing / Coverage sections)

## Test files changed

- None (only this report was written).

## Tests added or updated

Count: 0

Reason: the change is Gradle build configuration only. Every `Test` task of `server/` and `android/app` gets, in `tasks.withType<Test>().configureEach`, a `jvmArgumentProviders` entry `-Djava.io.tmpdir=build/tmp/test-jvm/{task name}` (lazy `layout.buildDirectory`, so the path follows the build dir) and a `doFirst` that runs `deleteRecursively()` then `mkdirs()` on that folder. The project has no build-logic test infrastructure (no Gradle TestKit / functional tests), and adding one would be new test infrastructure, which is out of scope. A unit test asserting `System.getProperty("java.io.tmpdir")` inside the test JVM was considered and rejected: it would pin a build detail rather than product behavior, and the criteria explicitly name the observable evidence (what appears in `/tmp` and under `build/tmp/test-jvm` across normal, repeated and killed runs), which the parent ran directly.

Inspection results:
- After the merge, android has exactly one tmpdir setting (lines 106-115); the superseded and-test-tmpdir lines (`temporaryDir.resolve("jvm")`, `systemProperty("java.io.tmpdir", ...)`) are gone, so there is no conflicting second `-Djava.io.tmpdir` and no duplicate `val testTmpDir` declaration.
- Server: the block is in `tasks.withType<Test>()`, so it applies to `test` and the registered `integrationTest` task alike (criterion #4).
- No test hard-codes a host `/tmp` path: `grep -rn '"/tmp' server/src android/app/src --include=*.kt` finds only `HeaderSyncDovecotTests.kt:68`, which is a path inside the Dovecot container (`withCopyToContainer(..., "/tmp/90-test.conf")`), unaffected by the host JVM's tmpdir.
- Tests using `Files.createTempDirectory` / `@TempDir` resolve against `java.io.tmpdir` and therefore land in the per-task folder, which exists before the JVM starts (`mkdirs()` in `doFirst`).
- The folder is under the module's `build/`, so `./gradlew clean` removes it.

## Requirement coverage

Commands below were run by the parent worker (sub-agents cannot run Gradle in this sandbox) and are recorded as evidence in tasks/test-tmp-in-build.md; this helper did not rerun them.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| #1 Every Test task uses build/tmp/test-jvm/{task name} as java.io.tmpdir, emptied at task start, removed by clean; temp-dir tests keep working | existing server and android unit suites, server integrationTest subset; build-file inspection (this report) | parent: `cd server && ./gradlew test --rerun`, `cd android && ./gradlew testDebugUnitTest` (x2), `cd server && ./gradlew integrationTest ...` | Pass (parent-recorded; inspection by this helper confirms the configuration) |
| #2 No new /tmp entries; second run does not grow build/tmp/test-jvm | existing suites | parent: /tmp listing before/after `cd android && ./gradlew testDebugUnitTest` (2 runs, 2nd `--rerun`) and `cd server && ./gradlew test --rerun` | Pass (parent-recorded: /tmp lists identical; folder 8K after run 1 and run 2) |
| #3 Killed run leaves nothing in /tmp; next run empties leftovers | existing android suite | parent: `testDebugUnitTest --rerun`, `pkill -9 -f 'agent-1/android/app/build/tmp/test-jvm'` mid-run, then rerun | Pass (parent-recorded: 205M left under build, /tmp unchanged, next run back to 4K; kill pattern narrowed, recorded as assumption) |
| #4 integrationTest gets the setting; Testcontainers still works | HeaderSyncDovecotTests, ExternalToolingSmokeTests | parent: `cd server && ./gradlew integrationTest --tests *HeaderSyncDovecotTests --tests *ExternalToolingSmokeTests --rerun` | Pass (parent-recorded: 4 tests, 0 failures) |
| #5 `sh scripts/check.sh` passes, duration not noticeably longer | full .taskcheck | parent: `sh scripts/check.sh` (running at the time of this report) | Not run by this helper: Gradle not runnable in the sub-agent sandbox; parent records the result and durations |
| #6 ARCHITECTURE.md one-line note | Not testable by an automated test (documentation) | `grep -n test-jvm docs/ARCHITECTURE.md` (this helper) | Pass: lines 150-151 |

## Focused test execution

Command:

`grep -n 'tmpdir\|test-jvm\|temporaryDir' android/app/build.gradle.kts server/build.gradle.kts` and `grep -rn '"/tmp' server/src android/app/src --include=*.kt`

Result:

Pass (static inspection only; no new tests to run).

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run by this helper (sandbox cannot run Gradle); the parent worker is running it and records the result for criterion #5.

## .taskcheck

Result:

Not run by this helper (same reason); see criterion #5 in the task file.

## Bugs found

None.

## Coverage

Status: not applicable - the task changes no production source. The only changed files are Gradle build scripts and documentation, which the project's coverage tooling (Kover / JaCoCo-style reports for `src/main`, see docs/ARCHITECTURE.md "Coverage") does not instrument.

Metric: whole-file coverage of task-changed production files - N/A (empty production scope; this is not reported as 100%).
Changed-line coverage: not measured (no executable production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: test-tmp-in-build; base a0a707e353ce04569afd18367d058f9ff6a3bccb; HEAD 9be7b78c5b4b056f53df63b9fdb7b760be952614; scope from the parent's prompt and task `touches` (server/build.gradle.kts, android/app/build.gradle.kts, docs/ARCHITECTURE.md), confirmed with `git diff --stat a0a707e35 HEAD -- server android docs`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 9be7b78c5, clean working tree (no uncommitted inputs).
- Exact included files; renamed/deleted/excluded files and reasons: included production files - none. Excluded: `server/build.gradle.kts`, `android/app/build.gradle.kts` (build configuration, not instrumented); `docs/ARCHITECTURE.md` (documentation).
- Provider/version, command, working directory, and test selection: no coverage run (nothing to measure).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A; no coverage output used.
- Final run after latest test additions and production fixes: N/A (no tests added, no production fixes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `server/build.gradle.kts:113-130`, `android/app/build.gradle.kts:106-115` | tmpdir redirection, per-run emptying | Covered by the parent's executed runs for criteria #1-#4 (observed /tmp and build/tmp/test-jvm contents); no build-logic test framework exists |

## Unable to test

- Running Gradle (unit suites, integrationTest, check.sh) from this sub-agent: the sandbox does not allow it. The parent ran #1-#4 and is running #5; no follow-up needed beyond recording #5.
- Build-cache relocatability of the absolute `-Djava.io.tmpdir` argument from the `CommandLineArgumentProvider` lambda: not in the acceptance criteria and the project does not use a remote build cache; no follow-up required.

## Summary

No additional tests were necessary for the reviewed scope.
