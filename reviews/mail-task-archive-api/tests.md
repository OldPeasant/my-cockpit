# Test Report

Date: 2026-11-14

Task: mail-task-archive-api - Mail → Next action & archive: server call

Scope: `git diff e7419167baed7fc2a04732c5ca0a764e975ffa6d..fcd6b0a21b8ae2c322853352ea8560a23ffc300b -- server SPEC.md api` (6 task commits 0c5b460bb..fcd6b0a21 on `main`), plus the uncommitted test change listed below. Production code at HEAD fcd6b0a21b8ae2c322853352ea8560a23ffc300b, no uncommitted production changes.

Requirements:
- `tasks/mail-task-archive-api.md` acceptance criteria and recorded assumption (ids 1..100, list NEXT/INBOX/SOMEDAY, unknown id gives 404 and nothing created, ids resolving to the same message share one task)
- SPEC.md §7.3 / §7.4 (as changed by the task), §4.2 (Problem Details)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md
- Parent instructions (check single, multiple, partial archive failure, link after move incl. no-MOVE, unknown id, list validation, archive=false, existing to-task unchanged)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveWebTests.kt` (uncommitted; sha256 c76650887a4b94e228ea05fcedf70b3a4c0df079cd5b2f17144e7c325bba072c)

## Tests added or updated

Count: 4 added (plus a `messageId` parameter on the test's `message` helper). Existing tests unchanged.

- `the old id of a moved mail and its current id share one task linked to the current copy` - assumption "ids resolving to the same message share one task" (old id of a moved row and the copy's id give 2 results, same task id, messageId = copy, 1 task, 1 link, link points at the copy)
- `a mail that is no longer on the server among known ones gives 404 and creates nothing` - a cached but detached message (uid null) is "inaccessible": Problem Details 404, no task, no link
- `SOMEDAY and an explicit NEXT are accepted lists` - the two allowed lists not yet exercised (INBOX and default NEXT were)
- `exactly 100 ids pass validation and are resolved` - size boundary: 100 ids are not rejected with 400 (101 already gives 400)

Gaps checked and found already covered by the existing tests (no test added): single mail (web + GreenMail), multiple mails (web + GreenMail), partial archive failure without Archive folder (web + GreenMail, second account without Archive), link resolution after the move incl. no-MOVE COPY+EXPUNGE path (GreenMail), unknown id gives 404 and creates nothing for the known ids (web), list/ids validation (empty, missing, null element, malformed uuid, WAITING, SCHEDULED, unknown enum, 101 ids), archive=false (web, `archiveErrorCode` null proves no archive attempt), CSRF, existing `to-task` unchanged (MailGtdWebTests, 47 tests incl. Inbox list, link, moves, 404).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: one call, ids 1..n, list default NEXT, archive default true; task titled with subject, linked like to-task, archived via ARCHIVE | MailTaskArchiveWebTests: `without an Archive folder the task is created in Next...`, `several mails, one already archived...`, `archive false and list INBOX...`, `SOMEDAY and an explicit NEXT are accepted lists`, `the old id of a moved mail and its current id share one task...`; MailTaskArchiveGreenMailTests: `one mail becomes a Next task and moves to Archive...`, `several mails each get a task and are archived` | `cd server && ./gradlew test --tests '*MailTaskArchiveWebTests' --tests '*MailGtdWebTests'`; `cd server && ./gradlew integrationTest --tests '*MailTaskArchiveGreenMailTests'` | Pass (10/10 web, 4/4 GreenMail) |
| AC2: per-mail result (task id/title/list, archived, reason); failed archive keeps task; unknown/inaccessible id gives Problem Details without creating anything | `without an Archive folder ...`, `a mail of an account without Archive keeps its task and reports why...` (GreenMail), `an unknown id gives 404 and creates nothing, also for the known ids`, `a mail that is no longer on the server among known ones gives 404 and creates nothing`, `invalid requests give 400 and create nothing`, `exactly 100 ids pass validation and are resolved` | same as above | Pass |
| AC3: link still resolves to the moved mail, also without MOVE (GreenMail) | `one mail becomes a Next task and moves to Archive, and the link resolves to the moved mail`, `several mails each get a task and are archived`, `without MOVE the mail is copied and expunged, and the link still resolves to the archived mail` | `cd server && ./gradlew integrationTest --tests '*MailTaskArchiveGreenMailTests'` | Pass (4/4) |
| AC4: existing to-task (Inbox, no archive) unchanged | MailGtdWebTests (47 tests, e.g. `to-task creates an Inbox task linked both ways to the mail`, `a message that is gone gives 404 and creates nothing`) | `cd server && ./gradlew test --tests '*MailGtdWebTests'` and within `./gradlew check` | Pass (47/47) |
| AC5: openapi.yaml + generated web client regenerated, SPEC text | `OpenApiContractTest` (compares `api/openapi.yaml` with the running server's contract); the web generated client compiles in the web build of `scripts/check.sh`; SPEC text is documentation | `cd server && ./gradlew check jacocoTestReport -q` (OpenApiContractTest 1/1); `sh scripts/check.sh` | Pass for the contract; generated web client freshness against openapi.yaml not compared by a test; SPEC wording Not testable |
| AC6: tests cover single, multiple, partial archive failure, link; `./gradlew check` and `sh scripts/check.sh` pass | all of the above | `cd server && ./gradlew check jacocoTestReport -q`; `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat -q` (no change to the test file), then `cd server && ./gradlew test --tests '*MailTaskArchiveWebTests' --tests '*MailGtdWebTests'`

Result:

Pass - MailTaskArchiveWebTests tests="10" failures="0" errors="0"; MailGtdWebTests tests="47" failures="0" errors="0" (from `build/test-results/test/*.xml`).

`cd server && ./gradlew integrationTest --tests '*MailTaskArchiveGreenMailTests' -q`

Result:

Pass - tests="4" skipped="0" failures="0" errors="0".

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport -q`

Result:

Pass - no failure output; no JUnit XML under `server/build/test-results/test/` has a failure or error (`grep -L 'failures="0" errors="0"'` lists none).

## .taskcheck

Result:

Pass - `sh scripts/check.sh` ended with "== all checks passed" (server, web, android). Web build printed the pre-existing initial bundle budget warning only.

## Bugs found

None.

## Coverage

Status: verified (unit `test` task only; the GreenMail `integrationTest` task is not instrumented into the JaCoCo report, so the real IMAP move path's contribution is not reflected in these numbers).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 74/74 executable added lines covered (100%). Method: added line numbers from `git diff -U0 e7419167b..fcd6b0a21` (MailGtdService.kt 7, 13-15, 47-57, 75, 85-125, 220-222; MailTaskArchiveController.kt 1-135) intersected with the JaCoCo XML line entries (covered when the covered-instruction count `ci` is positive). Changed-branch coverage not separately derived.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-task-archive-api; base e7419167baed7fc2a04732c5ca0a764e975ffa6d; HEAD fcd6b0a21b8ae2c322853352ea8560a23ffc300b; commits 0c5b460bb, 5804e0186, c82d480d0, dcb18d7df, 6cc27fe4c, fcd6b0a21 (supplied by the parent, matched against `git log` and the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD fcd6b0a21 plus MailTaskArchiveWebTests.kt sha256 c76650887a4b94e228ea05fcedf70b3a4c0df079cd5b2f17144e7c325bba072c (identical before and after the runs); MailTaskArchiveGreenMailTests.kt unchanged at HEAD (sha256 c3fc13b905875447a15255b9614df281e7f8efa7d26bdffac758c505d523b808).
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveController.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `SPEC.md` (documentation), test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via Gradle `jacoco` plugin; `./gradlew check jacocoTestReport -q` in `server/`; full unit `test` task.
- Run start/completion time and exit status: completed 2026-11-14 08:46 +01:00 (report written 08:46:11); build finished without failure output and all test XMLs report 0 failures/errors.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 c43753aaa63b267af868085a5ce93216521be7fd484d9acb4c20084e46bee525.
- Evidence outputs are fresh and inputs stayed unchanged: report timestamp is from this run, after the new tests were added; test file hash unchanged after the runs; no production changes.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` | 1582/1616 (97.9%) | 122/134 (91.0%) | 47/49 (95.9%) | 172/172 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveController.kt` | 222/231 (96.1%) | 15/16 (93.8%) | 21/21 (100%) | 50/50 (100%) |
| Aggregate (summed counters, both files measured, none missing) | 1804/1847 (97.7%) | 137/150 (91.3%) | 68/70 (97.1%) | 222/222 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailTaskArchiveController.kt:55` (1 branch, `checkNotNull(request.ids)` null path) | none: `@NotEmpty` rejects a missing `ids` with 400 before the method runs (tested in `invalid requests give 400...`) | justified gap, unreachable defensive check |
| `MailGtdService.kt:115` (1 branch, `checkNotNull(byMessage[...])` null path) | none: every located message gets an entry in the loop above | justified gap, unreachable defensive check |
| `MailGtdService.kt:222` (2 instructions, companion initializer) | JaCoCo artefact of the Kotlin companion object; the line itself is covered | justified gap |
| `MailGtdService.kt:163, 179, 180, 183, 216, 224, 293, 294, 338, 340` | pre-existing code (to-waiting / to-note / transaction helper), not changed by this task | outside task scope |

## Unable to test

- AC5 SPEC.md wording: documentation, reviewed by reading, not by tests.
- Archive failure codes that need a misbehaving IMAP server (`NOT_FOUND` after a concurrent delete, `REJECTED`, mail server errors) are not exercised for this endpoint; the endpoint passes the outcome of the existing `MailActionService.apply` through unchanged, whose codes are covered by that service's own tests. No follow-up needed for this task.
- GreenMail `integrationTest` coverage is not merged into the JaCoCo report (existing tooling measures `test` only).

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
