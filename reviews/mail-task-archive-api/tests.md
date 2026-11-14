# Test Report

Date: 2026-11-14

Task: mail-task-archive-api - Mail → Next action & archive: server call

Scope: `git diff 6d395d2bd84636c15ff3750b26c75f8016031359..8a2a1bdb9be5438b0901bd4b505a66bb4c80ca41` (task commits eb4075cc9..8a2a1bdb9 on `main`, supplied by the parent and matched against `git log` and the task's `touches`). This round re-validates the final code after the review fixes 6f65ebca5 and 8a2a1bdb9 (request schema: `ids` `@NotNull @Size(min = 1, max = 100)`, `list` `allowableValues` NEXT/INBOX/SOMEDAY; regenerated `api/openapi.yaml` and web client; SPEC.md §7.4 response shape; Problem Details assertions for empty/missing ids). No uncommitted changes. During the run HEAD advanced to ff9d10df01eff6a0a9b43082db10dac46243873d, which only changes `reviews/mail-task-archive-api/review.md`; production and test code are identical to 8a2a1bdb9.

Requirements:
- `tasks/mail-task-archive-api.md` acceptance criteria and recorded assumption (ids 1..100, list NEXT/INBOX/SOMEDAY with WAITING/SCHEDULED giving 400, unknown id gives 404 and nothing created, ids of the same message share one task)
- SPEC.md §7.3 / §7.4 (as changed by the task), §4.2 (Problem Details)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md; docs/QUALITY.md
- Parent instructions for this round (inspect the delta, add tests only if missing, focused + integration + JaCoCo runs, no `scripts/check.sh`)

## Test files changed

None in this round. The task's test files at HEAD are:

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveWebTests.kt` (committed; last changed by the fixer in 8a2a1bdb9; sha256 8da1cc6a353e5ecadefe5803c57ca302ce487df6c5af9e3cc2f53bb242756ba8)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveGreenMailTests.kt` (committed; sha256 c3fc13b905875447a15255b9614df281e7f8efa7d26bdffac758c505d523b808)

## Tests added or updated

Count: 0 in this round.

Delta review (6f65ebca5, 8a2a1bdb9) and why no test was added:

- `ids` changed from `@NotEmpty` to `@NotNull @Size(min = 1, max = 100)`: the runtime behavior (missing or empty ids give 400) is unchanged and is asserted by `invalid requests give 400 and create nothing`, which since 8a2a1bdb9 also checks `application/problem+json` and `errors[0].field == "ids"` for both the empty and the missing case. The 100/101 boundary is covered by `exactly 100 ids pass validation and are resolved` and the 101-id case in `invalid requests give 400 and create nothing`.
- `list` `allowableValues`: affects only the OpenAPI schema (enum NEXT, INBOX, SOMEDAY). Runtime acceptance of all three lists (`archive false and list INBOX...`, `SOMEDAY and an explicit NEXT are accepted lists`, default NEXT) and rejection of WAITING, SCHEDULED and an unknown value (`invalid requests give 400...`) are already tested. The generated schema (`minItems: 1`, `maxItems: 100`, `enum`) is checked against the running server by `OpenApiContractTest`.
- SPEC.md response shape text: documentation; the response fields it lists (`id`, `messageId`, `linkId`, `task {id, title, list}`, `archived`, `archiveErrorCode`, `archiveDetail`) are all asserted by the web tests.

The earlier round's tests (single, multiple, partial archive failure without Archive folder, link after the move incl. the no-MOVE COPY+EXPUNGE path, unknown and detached ids giving 404 with nothing created, shared task for ids of one message, archive=false, CSRF, validation) remain in place and pass on the final code.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: one call, ids 1..n, list default NEXT, archive default true; task titled with subject, linked like to-task, archived via ARCHIVE | MailTaskArchiveWebTests: `without an Archive folder the task is created in Next...`, `several mails, one already archived...`, `archive false and list INBOX...`, `SOMEDAY and an explicit NEXT are accepted lists`, `the old id of a moved mail and its current id share one task linked to the current copy`; MailTaskArchiveGreenMailTests: `one mail becomes a Next task and moves to Archive...`, `several mails each get a task and are archived` | `cd server && ./gradlew test --rerun --tests '*MailTaskArchive*' --tests '*OpenApiContractTest' ktlintCheck`; `cd server && ./gradlew integrationTest --rerun --tests '*MailTaskArchive*'` | Pass (10/10 web, 4/4 GreenMail) |
| AC2: per-mail result (task id/title/list, archived, reason); failed archive keeps task; unknown/inaccessible id gives Problem Details without creating anything | `without an Archive folder ...`, `a mail of an account without Archive keeps its task and reports why...` (GreenMail), `an unknown id gives 404 and creates nothing, also for the known ids`, `a mail that is no longer on the server among known ones gives 404 and creates nothing`, `invalid requests give 400 and create nothing`, `exactly 100 ids pass validation and are resolved` | same as above | Pass |
| AC3: link still resolves to the moved mail, also without MOVE (GreenMail) | `one mail becomes a Next task and moves to Archive, and the link resolves to the moved mail`, `several mails each get a task and are archived`, `without MOVE the mail is copied and expunged, and the link still resolves to the archived mail` | `cd server && ./gradlew integrationTest --rerun --tests '*MailTaskArchive*'` | Pass (4/4) |
| AC4: existing to-task (Inbox, no archive) unchanged | MailGtdWebTests (e.g. `to-task creates an Inbox task linked both ways to the mail`, `a message that is gone gives 404 and creates nothing`) | `cd server && ./gradlew test --rerun jacocoTestReport` (full unit suite) | Pass (0 failures in the full suite) |
| AC5: openapi.yaml + generated web client regenerated, SPEC text | `OpenApiContractTest` (compares `api/openapi.yaml`, incl. the new `minItems`/`enum`, with the running server's contract) | `cd server && ./gradlew test --rerun --tests '*OpenApiContractTest' ...` | Pass (1/1) for the contract. Generated web client freshness against openapi.yaml is not compared by a test (it compiles in the web part of `scripts/check.sh`, run by the parent). SPEC wording: Not testable (documentation) |
| AC6: tests cover single, multiple, partial archive failure, link; `./gradlew check` and `sh scripts/check.sh` pass | all of the above | `./gradlew test --rerun jacocoTestReport` and `ktlintCheck` run here | Pass for the unit suite and ktlint. `./gradlew check` as a whole and `sh scripts/check.sh`: Not run in this round (parent runs `scripts/check.sh` per instructions) |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests '*MailTaskArchive*' --tests '*OpenApiContractTest' ktlintCheck`

(A first invocation without `--rerun` reported the test task UP-TO-DATE; it was rerun with `--rerun` so the results are from this round.)

Result:

Pass - BUILD SUCCESSFUL. `TEST-ch.sonensei.cockpit.mail.gtd.MailTaskArchiveWebTests.xml` tests="10" failures="0" errors="0" (timestamp 2026-11-14T08:00:52Z); `TEST-ch.sonensei.cockpit.core.web.OpenApiContractTest.xml` tests="1" failures="0" errors="0" (08:00:37Z). ktlintCheck up to date on unchanged inputs (passes).

Command:

`cd server && ./gradlew integrationTest --rerun --tests '*MailTaskArchive*'`

Result:

Pass - BUILD SUCCESSFUL. `TEST-ch.sonensei.cockpit.mail.gtd.MailTaskArchiveGreenMailTests.xml` tests="4" skipped="0" failures="0" errors="0" (timestamp 2026-11-14T08:01:09Z).

## Full test execution

Command:

`cd server && ./gradlew test --rerun jacocoTestReport`

Result:

Pass - BUILD SUCCESSFUL in 3m 29s; 396 JUnit XML files, 5274 tests, none with a failure or error (`grep -L 'failures="0" errors="0"'` over `server/build/test-results/test/` lists none).

## .taskcheck

Result:

Not run - by parent instruction the parent runs `sh scripts/check.sh` itself.

## Bugs found

None.

## Coverage

Status: verified (unit `test` task only; the GreenMail `integrationTest` task is not merged into the JaCoCo report by the existing tooling, so the real IMAP move path's contribution is not reflected in these numbers).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 74/74 executable added lines covered (100%). Method: added line numbers from `git diff -U0 6d395d2bd..8a2a1bdb9 -- server/src/main` (MailGtdService.kt 7, 13-15, 47-57, 75, 85-125, 220-222; MailTaskArchiveController.kt 1-138) intersected with the JaCoCo XML line entries (covered when `ci` is positive); every added executable line has `ci` greater than 0. Changed-branch coverage not separately derived; the only partially covered branches on added lines are listed below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-task-archive-api; base 6d395d2bd84636c15ff3750b26c75f8016031359; measured code at 8a2a1bdb9be5438b0901bd4b505a66bb4c80ca41 (HEAD ff9d10df0 at the end of the run differs only in `reviews/mail-task-archive-api/review.md`); commits eb4075cc9, 240c0e31d, 5bff30f56, b18de39c9, 0d6c4a47b, 27f232fb8, 8764922ca, f3ad794b2, 6f65ebca5, 8a2a1bdb9 (supplied by the parent, matched against `git log 6d395d2bd..HEAD` and the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): no uncommitted inputs. sha256 before and after the runs: MailGtdService.kt 0ef7ad47890de8a38db25b24d8210b739b0b6ad1f3824f118c8215675b0de059, MailTaskArchiveController.kt fe160fb3bcfd335e47035798bed019db1f5d3dee6829e0651fc14fef8484e0da, MailTaskArchiveWebTests.kt 8da1cc6a353e5ecadefe5803c57ca302ce487df6c5af9e3cc2f53bb242756ba8, MailTaskArchiveGreenMailTests.kt c3fc13b905875447a15255b9614df281e7f8efa7d26bdffac758c505d523b808.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveController.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `SPEC.md` (documentation), `reviews/**` (reports), test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1); `./gradlew test --rerun jacocoTestReport` in `server/`; full unit `test` task.
- Run start/completion time and exit status: 2026-11-14, report written 09:05:00 +01:00; BUILD SUCCESSFUL (exit 0), no failed tests.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 c14c953cb2242332a1e8da76603a481f9e2868253c101dbb5cc8ab9b94854c2f.
- Evidence outputs are fresh and inputs stayed unchanged: the test task was forced to rerun (`--rerun`) and the report timestamp is from this run; source/test hashes identical before and after.
- Final run after latest test additions and production fixes: yes (measured on the final code including fixes 6f65ebca5 and 8a2a1bdb9; no test additions in this round).

Values below use covered/total (percentage). Counters are JaCoCo instructions (statements), branches, methods (functions), lines.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` | 1582/1616 (97.9%) | 122/134 (91.0%) | 47/49 (95.9%) | 172/172 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailTaskArchiveController.kt` | 222/231 (96.1%) | 15/16 (93.8%) | 21/21 (100%) | 50/50 (100%) |
| Aggregate (summed counters; both files measured, none missing or excluded) | 1804/1847 (97.7%) | 137/150 (91.3%) | 68/70 (97.1%) | 222/222 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailTaskArchiveController.kt:55` (1 branch, `checkNotNull(request.ids)` null path) | none: `@NotNull` now rejects a missing `ids` with 400 before the method runs (asserted as Problem Details on field `ids` in `invalid requests give 400 and create nothing`) | justified gap, unreachable defensive check |
| `MailGtdService.kt:115` (1 branch, `checkNotNull(byMessage[...])` null path) | none: every located message gets an entry in the loop just above | justified gap, unreachable defensive check |
| `MailGtdService.kt:222` (2 instructions, companion initializer) | JaCoCo artefact of the Kotlin companion object; the line itself is covered | justified gap |
| `MailGtdService.kt:163, 179, 180, 183, 216, 224, 293, 294, 338, 340` | pre-existing code (to-waiting / to-note / transaction helper), not changed by this task | outside task scope |

## Unable to test

- AC5 SPEC.md wording: documentation, reviewed by reading, not by tests.
- Archive failure codes that need a misbehaving IMAP server (`NOT_FOUND` after a concurrent delete, `REJECTED`, mail server errors) are not exercised for this endpoint; it passes the outcome of the existing `MailActionService.apply` through unchanged, whose codes are covered by that service's own tests. No follow-up needed for this task.
- GreenMail `integrationTest` coverage is not merged into the JaCoCo report (existing tooling measures `test` only).
- `sh scripts/check.sh` and the full `./gradlew check` were not run in this round by parent instruction.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No additional tests were necessary for the delta reviewed in this round.
