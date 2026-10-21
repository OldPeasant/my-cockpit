# Test Report

Date: 2026-10-21

Task: gtd-convert-note - Convert task to reference note

Scope: `git diff 5fe373f5a76a9f53ffee29b5302874653bfdf0f1..38f8b95f3589bc986c82ee65f9949cf609843162` (implementation commit 38f8b95f, given by the parent worker). Measured at HEAD 7eeeca5dd2b9b698997e252be894b3fca59e2866 (38f8b95f plus a commit that only adds `reviews/gtd-convert-note/review.md`) on branch `main`, plus the uncommitted test file `TaskToNoteEdgeCaseTests.kt` (written by an earlier, interrupted test-writer run and finished in this run). Production files in scope: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToNoteController.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt`, both new in this task. `api/openapi.yaml` also changed, but it is a generated contract file, not instrumentable code.

Requirements:
- tasks/gtd-convert-note.md: the acceptance criteria and the three recorded assumptions (soft delete to the Trash; links copied as MANUAL links with the same direction and label, links to missing or trashed items skipped; only title and notes carried over, 201 + Location, optional If-Match)
- SPEC.md §4.2 (ETag/If-Match), §4.3 (links), §5.1 (Reference converts into a Note)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- reviews/gtd-convert-note/review.md, finding CR-1 (P3: no regression test for multiple or inline links to the same item)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteEdgeCaseTests.kt` (new, uncommitted)

The committed `TaskToNoteWebTests.kt` (8 tests) and `TaskToNoteRollbackTests.kt` (1 test) were not changed.

## Tests added or updated

Count: 17

I reviewed the 16 tests the interrupted run left in `TaskToNoteEdgeCaseTests.kt`. They are correct and deterministic, use fictitious data, and pass, so all 16 were kept. This run added 1 test: `an item with an inline backlink, a manual link and an inline link from the task notes converts without a conflict`. It covers CR-1 end to end through the real `NoteService` and `LinkService`, with no links saved directly through the repository.

## Requirement coverage

All rows were run with `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` (26 tests, 0 failures, 0 skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: POST creates a note with the task's title and its notes as body; 201 + Location + ETag | `creates a note from title and notes and moves the task to the Trash`, `a task without notes gives an empty body`, `the ETag and the body describe the new note as GET returns it`, `a completed task converts like an open one` | see above | Pass |
| AC1: links carried over (direction, label, as MANUAL; missing/trashed/unresolvable items) | `carries links over in their direction with their label`, `skips links to items that no longer exist`, `links to other tasks are carried over, links to trashed tasks are skipped`, `inline links in the task notes become synced links of the note` | see above | Pass |
| AC1: task deleted (to the Trash, keeping its links; restorable) | `creates a note ... moves the task to the Trash`, `restoring the converted task from the Trash brings it back with its links and keeps the note`, `the task leaves the search index and the note enters it ...`, `announces the new note as CREATED and the task as DELETED` | see above | Pass |
| AC1: one transaction | `a failing link copy rolls back the note and the task deletion` (TaskToNoteRollbackTests) | see above | Pass |
| AC1: If-Match optional / 412 / 404 / auth / CSRF / malformed id | `a stale If-Match changes nothing`, `a matching If-Match converts`, `If-Match star, a blank header and a list containing the current tag convert`, `a weak If-Match tag never matches`, `a stale If-Match leaves links, events and the search index untouched`, `unknown and trashed tasks are not found`, `converting twice gives one note, the second call is not found`, `requires authentication`, `an anonymous request converts nothing`, `without a CSRF token nothing is converted`, `a malformed task id is a bad request` | see above | Pass |
| CR-1 case 1: manual link task -> X plus X's SYNC link back -> 201, note has exactly one MANUAL link to X | `an item linked to the task in both directions is linked to the note once, by its manual link`, `an item with an inline backlink, a manual link and an inline link from the task notes converts without a conflict` | see above | Pass |
| CR-1 case 2: task notes contain `[[note:X]]` plus a manual link to X -> 201, note has exactly a SYNC and a MANUAL link to X | `an inline link and a manual link to the same item both survive without a conflict`, `an item with an inline backlink, a manual link and an inline link from the task notes converts without a conflict` | see above | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Postgres via Testcontainers) | all 26 tests of the package | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` | Pass |
| AC3: OpenApiContractTest passes without the flag | `OpenApiContractTest` (part of the full server suite) | `cd server && ./gradlew --quiet check` (inside `sh scripts/check.sh`) | Pass (as part of the 2805-test server suite). I did not run the `UPDATE_OPENAPI=1` regeneration. |

The CR-1 tests would catch the regression the review describes. If `LinkService.linksOf` returned every link instead of one per item, the SYNC link X -> task would lead to a second `LinkService.create(X, note)`. That call throws `ConflictException` because a manual link of the pair already exists in the other direction, and the conversion would answer 409 instead of 201.

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` (also run once without `jacocoTestReport` before the new test was added: 25 passed)

Result:

Pass: TaskToNoteEdgeCaseTests 17, TaskToNoteWebTests 8, TaskToNoteRollbackTests 1; 0 failures, 0 errors, 0 skipped. BUILD SUCCESSFUL.

`cd server && ./gradlew ktlintCheck`: Pass (BUILD SUCCESSFUL, the test source set was re-checked).

## Full test execution

Command:

`sh scripts/check.sh`, server step `cd server && ./gradlew --quiet check`, run 2026-10-21 05:31-05:39 +02:00

Result:

Server: Pass. The suite has 219 test classes and 2805 tests, with 0 failures, 0 errors and 0 skipped (from `server/build/test-results/test`), and ktlint passes. The run includes `OpenApiContractTest`. The web step failed, see `.taskcheck`.

## .taskcheck

Result:

Fail. I ran `sh scripts/check.sh` twice (05:31 and 05:39 +02:00). Both times the completion-gate tests passed (20 tests OK) and the server check passed. The web step failed:

```
src/app/api/generated is out of date with ../api/openapi.yaml:
  missing: fn/tasks/convert-task-to-note.ts
  changed: functions.ts
  changed: index.ts
Run `npm run api:gen` and commit the result.
```

This failure comes from this task, see TW-1. The Android step did not run because the script stops at the first failure.

## Bugs found

### Generated web API client not regenerated after the openapi.yaml change

Finding ID:

`TW-1`

Test:

`.taskcheck` (`sh scripts/check.sh`, web step `npm run -s check`, its generated-client freshness check)

Production source:

`api/openapi.yaml` (the `/api/v1/tasks/{id}/convert-to-note` path added in 38f8b95f); `web/src/app/api/generated/` was not regenerated

Requirement:

AGENTS.md: "API changes: server first, regenerate `api/openapi.yaml`, then clients". `tasks.py done` needs `.taskcheck` to pass.

Expected behavior:

`web/src/app/api/generated/` matches `api/openapi.yaml`, so `sh scripts/check.sh` passes.

Actual behavior:

The web check reports `fn/tasks/convert-task-to-note.ts` as missing and `functions.ts` and `index.ts` as changed. `.taskcheck` fails. The last commit touching the generated client is e9e63b64, from another task. The base 5fe373f5 has no `convert-to-note` path, so the drift comes from this task.

Severity:

High (blocks `tasks.py done`; the server behavior itself is correct)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Run `cd web && npm run api:gen` and commit the regenerated files. `web/src/app/api/generated/` is outside the task's `touches`, but AGENTS.md requires this regeneration for API changes.

## Coverage

Status: verified (whole-file coverage of both task-changed production files)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: both production files are new in this task, so every executable line is a changed line. Changed-line coverage therefore equals the whole-file line coverage below (33/33). Method: `git diff --name-only 5fe373f5..38f8b95f -- server/src/main` lists only these two added files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-convert-note. Base 5fe373f5a76a9f53ffee29b5302874653bfdf0f1 and implementation commit 38f8b95f3589bc986c82ee65f9949cf609843162 come from the parent worker. Measured HEAD 7eeeca5dd2b9b698997e252be894b3fca59e2866 (its extra commit touches only `reviews/`). The task-owned paths match the task's `touches` (`gtd/convert` in main and test).
- Source/test snapshot identifier (including uncommitted inputs), sha256 before and after the coverage run (unchanged): TaskToNoteEdgeCaseTests.kt `f19f0f821dbb19c078d7f8716ff0adb9afbb17ca400fae011b1880cc0dd366d6` (uncommitted); TaskToNoteRollbackTests.kt `b6515b7bc5994763241acc98a20d9c37b78da9f55930775824e4b9f5dbe4a318`; TaskToNoteWebTests.kt `1808ea66a6c9837b5056489c34c76aca391844b048fc40dfa705a7e203e053a4`; TaskToNoteController.kt `268a1ee37a512cb4ff1dcd3451bfb9e1a0faebedd33db3b07fb43b69c17fa339`; TaskToNoteService.kt `46044698d0a4936eff6a3f73702edc0255557fe1d40326368dd210f220deaf45`
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToNoteController.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt`. Excluded: `api/openapi.yaml` (generated contract, not executable code) and `reviews/` (documentation). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1); `./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` in `server/`; selection: the 3 classes of package `ch.sonensei.cockpit.gtd.convert` (26 tests)
- Run start/completion time and exit status: 2026-10-21 05:30:17 +02:00 to about 05:31:26 +02:00; BUILD SUCCESSFUL (exit 0)
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 05:31:25.9, sha256 `78aba33ada3c269e4b0c4e3e5f7c10548b0fb64b853978edf712c9892f32d9cd`) from `server/build/jacoco/test.exec` (written 05:31:14.8)
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle re-ran `test` and `jacocoTestReport` in this invocation because the test inputs had changed (new test). The report timestamps fall inside the run, it includes the new test (17 tests in the EdgeCase suite XML), and the source hashes were the same before and after the run. A later `scripts/check.sh` run overwrote `test.exec` but not the XML report used here.
- Final run after latest test additions and production fixes: yes for the test additions. No production fixes were made. TW-1 needs a fix to generated web files only, which does not change these two server files.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt` | 135/135 (100%) | 10/10 (100%) | 3/3 (100%) | 20/20 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToNoteController.kt` | 65/65 (100%) | 6/6 (100%) | 3/3 (100%) | 13/13 (100%) |
| Aggregate (summed counters, both scoped files; none unmeasured) | 200/200 (100%) | 16/16 (100%) | 6/6 (100%) | 33/33 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | No uncovered lines or branches in the scoped files. The documented 400 response ("task content is not a valid note") cannot be triggered: task and note limits match (title 500, body/notes 100 000), as code review also noted. It has no instrumented branch of its own in the scoped files. |

## Unable to test

- The 400 "task content is not a valid note" response: task validation prevents a task whose title or notes would be invalid for a note, so the response is a safeguard only. No follow-up needed.
- `UPDATE_OPENAPI=1` regeneration of `api/openapi.yaml` (AC3, first half): not run by me. The parent worker records it. `OpenApiContractTest` without the flag passed inside the full server suite.
- The Android step of `.taskcheck` did not run because the script stops at the failing web step (TW-1). This task does not touch Android.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. The server behavior meets all acceptance criteria, and code-review finding CR-1 is now covered by regression tests. 26/26 focused tests pass, the 2805-test server suite passes, ktlint passes, and task-scoped coverage is 100% on all counters. `.taskcheck` fails because the generated web API client was not regenerated after the `api/openapi.yaml` change (TW-1, High). That has to be fixed before `tasks.py done`.
