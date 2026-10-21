# Test Report

Date: 2026-10-21

Task: gtd-convert-note - Convert task to reference note

Scope: `git diff 038a11c41105013a0ade49b5b16827f8eb8f89f0..561ff9c63c69e764712ecfd96bfe0623eeb5bbbb` on branch `main`. The base is `git merge-base HEAD origin/main` (= `origin/main` at measurement time). HEAD is 561ff9c63c69e764712ecfd96bfe0623eeb5bbbb, and the working tree was clean.

The history above the base is linear, with no merge commit in it: the task commits were rebased onto main. The task commits at HEAD are:
- 69b3dff7 (endpoint)
- 4fd8708a (edge-case tests and the earlier report)
- 823e3d89 (regenerated web API client)
- fc312e2a, 561ff9c6 (`reviews/` only)

The parent worker named the pre-rebase hashes 1b012e2e, ab4845ad and d569b3cc. Their `git patch-id --stable` values match 69b3dff7, 4fd8708a and 823e3d89 one to one (e8d587c7..., 3d25ecf4..., 38c5271c...), so the code is the same.

Production files in scope:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToNoteController.kt` (new)
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt` (new)

Generated, non-instrumented files that also changed: `api/openapi.yaml`, `web/src/app/api/generated/{fn/tasks/convert-task-to-note.ts,functions.ts,index.ts}`.

Requirements:
- tasks/gtd-convert-note.md: the 3 acceptance criteria and the 3 recorded assumptions (soft delete to the Trash; links copied as MANUAL links with the same direction and label, links to missing or trashed items skipped; only title and notes carried over, 201 + Location, If-Match optional)
- SPEC.md §4.2 (ETag/If-Match), §4.3 (links), §5.1 (Reference converts into a Note)
- AGENTS.md project notes ("API changes: server first, regenerate `api/openapi.yaml`, then clients")
- docs/ARCHITECTURE.md "Coverage"
- Earlier reports: reviews/gtd-convert-note/tests.md (pre-fix revision, finding TW-1), review.md (CR-1), fixes.md

## Test files changed

None in this run. The task's test files are all committed and unchanged since the previous report. Their sha256 hashes are identical to the ones recorded there:
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteEdgeCaseTests.kt` (17 tests, committed in 4fd8708a)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteWebTests.kt` (8 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteRollbackTests.kt` (1 test)

## Tests added or updated

Count: 0

No new tests were needed. The only code change since the previous report is the regenerated web client (TW-1 fix). It is generated code, and the web check's generated-client freshness check (`api:check`, part of `npm run check`) covers it. Both server production files still have 100% coverage on every counter, and every acceptance criterion and CR-1 case already has a passing test (table below).

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` -> 26 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESSFUL.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: POST creates a note with the task's title and its notes as body; 201 + Location + ETag | `creates a note from title and notes and moves the task to the Trash`, `a task without notes gives an empty body`, `the ETag and the body describe the new note as GET returns it`, `a completed task converts like an open one` | F | Pass |
| AC1: links carried over (direction, label, as MANUAL; missing/trashed items skipped) | `carries links over in their direction with their label`, `skips links to items that no longer exist`, `links to other tasks are carried over, links to trashed tasks are skipped`, `inline links in the task notes become synced links of the note` | F | Pass |
| AC1: task deleted (to the Trash, keeping its links; restorable; search/events) | `creates a note ... moves the task to the Trash`, `restoring the converted task from the Trash brings it back with its links and keeps the note`, `the task leaves the search index and the note enters it ...`, `announces the new note as CREATED and the task as DELETED` | F | Pass |
| AC1: one transaction | `a failing link copy rolls back the note and the task deletion` (TaskToNoteRollbackTests) | F | Pass |
| AC1: If-Match optional / 412 / 404 / auth / CSRF / malformed id | `a stale If-Match changes nothing`, `a matching If-Match converts`, `If-Match star, a blank header and a list containing the current tag convert`, `a weak If-Match tag never matches`, `a stale If-Match leaves links, events and the search index untouched`, `unknown and trashed tasks are not found`, `converting twice gives one note, the second call is not found`, `requires authentication`, `an anonymous request converts nothing`, `without a CSRF token nothing is converted`, `a malformed task id is a bad request` | F | Pass |
| CR-1: same item linked several ways (manual + SYNC backlink, inline + manual) converts without 409 | `an item linked to the task in both directions is linked to the note once, by its manual link`, `an inline link and a manual link to the same item both survive without a conflict`, `an item with an inline backlink, a manual link and an inline link from the task notes converts without a conflict` | F | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Postgres via Testcontainers) | all 26 tests of the package | F (with `--rerun` so Gradle actually executed them) | Pass |
| AC3 (second half): `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | `sh scripts/check.sh` (server step `./gradlew --quiet check`) | Pass (1/1) |
| AC3 (first half): `UPDATE_OPENAPI=1` regeneration | - | not run by me | Not run: the parent records it (task file: no diff to `api/openapi.yaml`) |
| TW-1 fix: generated web client matches `api/openapi.yaml` | `api:check` freshness check in `npm run check` | `npm --prefix web run check --silent` | Pass: "src/app/api/generated is up to date." |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport`

Result:

Pass, run 2026-10-21 06:22-06:23 +02:00. TaskToNoteEdgeCaseTests 17/17, TaskToNoteWebTests 8/8 and TaskToNoteRollbackTests 1/1 passed, with 0 failures, 0 errors and 0 skipped. BUILD SUCCESSFUL. `--rerun` forced the test task to run even though its inputs had not changed.

Command:

`npm --prefix web run check --silent` (equivalent to `cd web && npm run check`)

Result:

Pass (exit 0). "src/app/api/generated is up to date.", "All files pass linting.", and Vitest passed 83 files and 1513 tests. The production build succeeded. It warns that the initial bundle is over its budget (651.07 kB against a 500 kB budget). That warning has nothing to do with this task: the task adds only an unused generated function, which the build tree-shakes away. It does not fail the check.

## Full test execution

Command:

`sh scripts/check.sh`, run 2026-10-21 06:25:02 to 06:39:11 +02:00

Result:

Pass. The script runs with `set -eu` and ended with "== all checks passed".

The server step (`./gradlew --quiet check`) passed: 227 test classes and 2907 tests, with 0 failures, 0 errors and 0 skipped (from `server/build/test-results/test`). That includes `OpenApiContractTest` 1/1 and the 26 convert tests. The web step and the Android step (ktlint + unit tests + lint) also passed.

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, see above).

The run took about 14 minutes, with other agents' builds on the machine at the same time. That is close to the 15-minute `TASK_CHECK_TIMEOUT`. The parent worker should know this before running `done`. It is not a problem caused by this task.

## Bugs found

No open production defects.

### TW-1 (from the previous report): generated web API client not regenerated

Finding ID:

`TW-1`

Disposition:

Resolved by 823e3d89 (pre-rebase d569b3cc, "regenerate web API client"). `web/src/app/api/generated/fn/tasks/convert-task-to-note.ts` exists. It has `POST`, PATH `/api/v1/tasks/{id}/convert-to-note`, a required path `id`, an optional `If-Match` header and a `NoteResponse` result, which matches operation `convertTaskToNote` in `api/openapi.yaml`. `functions.ts` and `index.ts` export it. `npm run check` reports "src/app/api/generated is up to date", and `.taskcheck` passes.

## Coverage

Status: verified (whole-file coverage of both task-changed production files, measured on the final code)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: both production files are new in this task (`git diff --name-only 038a11c4..HEAD -- server/src/main web/src android/app/src/main` lists only these two outside the generated client), so every executable line in them is a changed line. Changed-line coverage therefore equals the whole-file line coverage: 33/33.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-convert-note. Base 038a11c41105013a0ade49b5b16827f8eb8f89f0 (`git merge-base HEAD origin/main`). Measured HEAD 561ff9c63c69e764712ecfd96bfe0623eeb5bbbb. Task-owned commits are 69b3dff7, fc312e2a, 4fd8708a, 823e3d89 and 561ff9c6 (all commits in base..HEAD), matched by patch-id to the hashes the parent named. The production paths match the task's `touches` (`gtd/convert`).
- Source/test snapshot identifier (including uncommitted inputs): no uncommitted inputs (clean tree). sha256, the same before the runs (06:22:06) and after `.taskcheck` (06:39):
  - TaskToNoteController.kt `268a1ee37a512cb4ff1dcd3451bfb9e1a0faebedd33db3b07fb43b69c17fa339`
  - TaskToNoteService.kt `46044698d0a4936eff6a3f73702edc0255557fe1d40326368dd210f220deaf45`
  - TaskToNoteEdgeCaseTests.kt `f19f0f821dbb19c078d7f8716ff0adb9afbb17ca400fae011b1880cc0dd366d6`
  - TaskToNoteRollbackTests.kt `b6515b7bc5994763241acc98a20d9c37b78da9f55930775824e4b9f5dbe4a318`
  - TaskToNoteWebTests.kt `1808ea66a6c9837b5056489c34c76aca391844b048fc40dfa705a7e203e053a4`
  - api/openapi.yaml `416ee1942c5f8289cf31a0a737edd8c4fa095bade0529cc8bd6b2df8e45a4e2f`
  - convert-task-to-note.ts `69bff4d2709fd29ac8a23bed5a5c775c6ea44a0a92f0f168dbba968435552ee9`
  - functions.ts `70084e99a054afe02738a5ea2a7aa508ec8e1c9d9d468fee15b2d1110bc85baa`
  - index.ts `6424df13d636f6fc11d701dcf69c75d3ccd0c28aa74ec7528b3bf5ed80b9e8d7`
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToNoteController.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt`. Excluded:
  - `api/openapi.yaml` (generated contract, not executable)
  - `web/src/app/api/generated/**` (generated client; the `api:check` freshness check validates it, and no web code calls it yet, so it has no meaningful unit coverage)
  - `reviews/**` (documentation)

  No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1); `./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` in `server/`; selection: the 3 classes of package `ch.sonensei.cockpit.gtd.convert` (26 tests)
- Run start/completion time and exit status: 2026-10-21 about 06:22:10 to 06:23:05 +02:00 (Gradle: "BUILD SUCCESSFUL in 46s", exit 0)
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 06:23:05.33, sha256 `e86674be0d9900bac9db1575d96563a4dbb57b1fb9af26b812f64a969acedf53`) from `server/build/jacoco/test.exec` (written 06:23:01.41). JUnit XMLs were written at 06:23:02.99 (EdgeCase 17, Web 8, Rollback 1).
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the test task to execute, and Gradle reported "2 executed" (test, jacocoTestReport). The report timestamps fall inside this run, and the input hashes were the same before and after. The later `.taskcheck` run rewrote `test.exec` but not the XML report used here.
- Final run after latest test additions and production fixes: yes. This run measures HEAD 561ff9c6, which contains the TW-1 fix and all test additions. There were no production changes after it.

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt` | 135/135 (100%) | 10/10 (100%) | 3/3 (100%) | 20/20 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToNoteController.kt` | 65/65 (100%) | 6/6 (100%) | 3/3 (100%) | 13/13 (100%) |
| Aggregate (summed counters, both scoped files; none unmeasured; generated files excluded as listed above) | 200/200 (100%) | 16/16 (100%) | 6/6 (100%) | 33/33 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | No missed or partially covered lines or branches in the scoped files (JaCoCo `mi`/`mb` = 0 on every line). The documented 400 response ("task content is not a valid note") cannot be triggered, because task and note limits match. It has no branch of its own in the scoped files. |

## Unable to test

- The 400 "task content is not a valid note" response: task validation already prevents content that would be invalid for a note, so this response is a safeguard only. No follow-up needed.
- `UPDATE_OPENAPI=1` regeneration of `api/openapi.yaml` (AC3, first half): I did not run it. The parent worker recorded it in the task file. `OpenApiContractTest` without the flag passed.
- There is no web UI for the conversion in this task's scope, so I did not write a web test for the generated `convertTaskToNote` function (it is generated code).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. TW-1 is resolved: the generated client matches `api/openapi.yaml`, and `npm run check` passes. On the final code (HEAD 561ff9c6):
- 26/26 focused convert tests pass (Testcontainers Postgres);
- the full `sh scripts/check.sh` passes (server 2907 tests, web 1513 tests, Android);
- whole-file coverage of both task-changed production files is 100% on all counters.
