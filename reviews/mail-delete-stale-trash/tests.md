# Test Report

Date: 2026-11-01

Task: mail-delete-stale-trash - Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up

Scope: `git diff 8e0f005156e60f8ee3813dd1a45748afa70f0704..c63607475ac02cae3f31999bea8ee1b22990758e` on branch main: the 6 task code commits (48aee449f to 32f302f07) plus c63607475, which commits the test added by this run and the first version of this report. Production files changed: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` (new). The final code includes the CR-1 fix (`applyToRows` only touches rows still at the UID the server command used).

Requirements:
- tasks/mail-delete-stale-trash.md (4 acceptance criteria, notes, 2 recorded assumptions)
- reviews/mail-delete-stale-trash/review.md (CR-1)
- AGENTS.md project notes, docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt` (this run: 1 test added, committed by the parent in c63607475)

## Tests added or updated

Count: 1

- `MailActionServiceTests`: `a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync`

Why: AC2 covers "any other action", but no unit test had a MOVE with some messages gone. That is the path where the COPYUID list from the server has to line up with the present UIDs, not with all requested UIDs (`remote()` passes `present` to `move()`). The test checks that the present message follows its COPYUID to Projects, that the gone message fails with NOT_FOUND and keeps its row, that only the source folder is resynced, and that nothing is deleted.

Existing task tests reviewed and judged sufficient (no change needed): the task-specific tests in `MailActionServiceTests` (Trash gone: all, mixed, linked, Trash/Inbox mix, UIDVALIDITY changed, folder missing, no cached UIDVALIDITY; stale resync: READ mixed, DELETE outside Trash, per-folder only, none when all found, source UIDVALIDITY changed; CR-1: rows relocated or detached meanwhile for delete, gone-delete, flag and move), 6 tests in `MailFolderResyncTests`, and the GreenMail tests (a), (b), (c) plus the Trash UIDVALIDITY-change test in `MailActionGreenMailTests`.

## Requirement coverage

All commands were run by the parent worker on HEAD c63607475 (this agent cannot run Gradle).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: DELETE in Trash of a UID gone there (folder exists, UIDVALIDITY matches) is ok; row removed like a permanent delete (detached when linked, else deleted, unindexed); MAIL_FOLDER event for Trash | `a delete in Trash of a message already gone from Trash is ok and removes the row like an expunge`, `a delete in Trash of present and already gone messages expunges the present ones and reports all ok`, `a linked message already gone from Trash is detached by a delete, not deleted`, `one delete of messages gone from Trash and from the Inbox is ok for Trash and NOT_FOUND with a resync for the Inbox`; GreenMail (a), (b) | `cd server && ./gradlew ktlintFormat check jacocoTestReport`; `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'` | Pass |
| AC1: changed UIDVALIDITY or missing folder still NOT_FOUND | `a delete in Trash whose UIDVALIDITY changed still fails with NOT_FOUND, keeps the row and starts a resync`, `a delete in a Trash folder missing on the server still fails with NOT_FOUND, keeps the row and starts no resync`, `a delete in a Trash folder without a cached UIDVALIDITY treats a missing message as stale, not deleted` (assumption); GreenMail Trash UIDVALIDITY-change test | same as above | Pass |
| AC2: other actions report NOT_FOUND for missing ids and start a background resync of that folder | `messages gone from the server fail with NOT_FOUND while the others of the folder are acted on`, `when no requested message is on the server any more nothing is sent and the cache is left to the next sync`, `messages gone from their folder start a background resync of that folder only`, `an action that finds all its messages starts no resync`, `a source folder with another UIDVALIDITY than cached fails the ids with NOT_FOUND and starts a resync`, new: `a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync` | `cd server && ./gradlew ktlintFormat check jacocoTestReport` | Pass (MailActionServiceTests 45 tests, 0 failures) |
| AC2: resync runs in the background through MailHeaderSyncService, merges pending requests, a failing sync is logged and does not fail the action | `MailFolderResyncTests` (6 tests: background, merging, request during running sync, failing sync swallowed, after shutdown, shutdown) | same as above | Pass (6 tests, 0 failures) |
| AC2: stale rows disappear after the sync, list refreshes via the folder event | GreenMail (c) `a move of a message another client deleted fails with NOT_FOUND and a background sync drops the row` | `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'` | Pass (20 tests, 0 failures) |
| AC3: GreenMail tests (a), (b), (c) | (a) `a delete in Trash of a message another client already deleted there is ok and removes the row`, (b) `a delete in Trash of present and already gone messages reports all ok`, (c) as above | same as above | Pass (20 tests, 0 failures) |
| AC4: unit tests in MailActionServiceTests; `./gradlew check` passes | all unit tests above; MailActionControllerTests 11/0 and MailActionWebTests 15/0 also passed | `cd server && ./gradlew ktlintFormat check jacocoTestReport` | Pass (BUILD SUCCESSFUL, ktlintFormat changed nothing) |
| CR-1: the cache update only touches rows still at the UID the server command used | `a delete in Trash of a UID gone from Trash leaves a row a sync relocated to another UID in Trash meanwhile`, `a delete in Trash removes the rows still at the expunged UID and leaves a relocated one alone`, `a delete in Trash of a UID gone from Trash leaves a row a sync detached meanwhile`, `a flag action leaves a row a sync relocated to another UID in the same folder meanwhile`, `a move leaves a row a sync relocated to another UID in the source folder meanwhile` | `cd server && ./gradlew ktlintFormat check jacocoTestReport` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat check jacocoTestReport` (run by the parent on c63607475)

Result:

Pass. MailActionServiceTests 45 tests, 0 failures, including the new partial-MOVE test. MailFolderResyncTests 6/0.

## Full test execution

Command:

`cd server && ./gradlew ktlintFormat check jacocoTestReport` (the whole server lint and unit suite) and `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'`

Result:

Pass. Both were BUILD SUCCESSFUL. GreenMail: 20 tests, 0 failures. Run by the parent on c63607475.

## .taskcheck

Result:

Not run by this agent. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. I read the code paths in `MailActionService.applyInFolder`, `remote`, `updateCache`, `applyToRows` and `MailFolderResync` against AC1 and AC2 and found no behavior that violates the criteria. All tests pass.

## Coverage

Status: verified. The parent measured it; this agent checked the numbers against the report XML.

Metric: whole-file coverage of task-changed production files. Counted from the unit-test run (`test` task) only: the GreenMail integration tests are not part of this JaCoCo report.
Changed-line coverage: not measured as a separate metric. Every executable line of both files is covered (see below), so every changed executable line is covered too.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-delete-stale-trash; base 8e0f005156e60f8ee3813dd1a45748afa70f0704 (given by the parent); measured HEAD c63607475ac02cae3f31999bea8ee1b22990758e; task commits from `git log 8e0f00515..HEAD`, all prefixed with the task id
- Source/test snapshot identifier (including uncommitted inputs): sha256 MailActionService.kt a25e7399daffabdf6c71e57920bc048ecb1a524fd5f04e3cbcf35c96615077ed, MailFolderResync.kt 0604a673da3c1078151329f8b5f6b4149beeb47eaeaf34f8f7c621a9673b26b8, MailActionServiceTests.kt 793e84b91dabbb1744b45bffab7a30c363e85d521284f0571f683da46e50ed04. These are identical before and after the coverage run, and the working tree is clean at c63607475
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt`; both are present in the report. Excluded: test files and `reviews/` reports (not production code). No renames or deletions
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin; `./gradlew ktlintFormat check jacocoTestReport` in `server/`; full server unit test suite
- Run start/completion time and exit status: completed 2026-11-01 09:29:51 +0100 (report file mtime); exit BUILD SUCCESSFUL
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 d0665f768e8f330000be01e2c14b59cc4d492db498b178443a2ab649c2f9632e
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was written after this run's test was added, and the source hashes match the measured snapshot
- Final run after latest test additions and production fixes: yes (c63607475 includes the CR-1 fix and the new test)

Values below use covered/total (percentage). JaCoCo has no statement metric, so bytecode instructions are shown in its place.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` | 1533/1604 (95.6%) | 145/158 (91.8%) | 42/42 (100%) | 204/204 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` | 110/110 (100%) | 2/2 (100%) | 6/6 (100%) | 27/27 (100%) |
| Aggregate (summed counters, both files, none unmeasured) | 1643/1714 (95.9%) | 147/160 (91.9%) | 48/48 (100%) | 231/231 (100%) |

Missed branches (13, all in MailActionService.kt, taken from the per-line `mb` counters in the report):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailActionService.kt:154, 191, 241, 356, 425` (1 branch each: the failing side of `checkNotNull`) | None. These are invariants: a located row always has a folder and a UID, MOVE always has a target. Lines 191 and 356 are task-touched, but only the null branch is missed | Justified gap. These branches are not reachable without breaking the locator's contract |
| `MailActionService.kt:270` (1 of 6: UIDVALIDITY check, task-changed line) | AC1/AC2: changed UIDVALIDITY gives `Invalidated`. Both outcomes are tested (match, mismatch) as well as a null cached UIDVALIDITY | Justified gap. The missed branch comes from Kotlin's boxed `Long?` comparison, and every behavior of the line has a test |
| `MailActionService.kt:301` (4 of 8: `runCatching { if (folder.isOpen) folder.close(false) }`) | Pre-existing cleanup, not changed by this task; the failing close is tested | Not in task scope; no test added |
| `MailActionService.kt:421-423` (3: COPYUID with uid 0, target folder row gone, one UIDVALIDITY sub-branch in `relocate`) | Pre-existing COPYUID trust rules, not changed by this task | Not in task scope; no test added |

There are no significant branch gaps in task-changed behavior. All new branches have tests: `Remote.Invalidated`, `goneCountsAsDone` true/false, `stale` empty/non-empty, and the `applyToRows` filter (folder differs, uid null, uid differs).

Remaining notes on risk:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailFolderResync.kt:27` (Spring constructor with a real virtual-thread executor) | Wiring only | Covered by line counters. The background path is also exercised end to end by the GreenMail tests that wait for resyncs (`awaitUncached`, `awaitUidValidity`) |
| `MailFolderResync.kt:49-55` (log levels and contents) | AGENTS.md: no server responses at INFO or above | Not asserted by a test, because the project has no log-capture convention. Checked by reading the code: only the error enum or exception class name is logged at INFO/WARN |

## Unable to test

- A real race between a full sync and an action (CR-1): the unit tests simulate it by returning relocated or detached rows from `findAllById`. A deterministic integration test of the race is not practical, and the task notes say to fix such a race only if tests show it. No follow-up needed.
- This agent could not run Gradle itself. All results above come from the parent's runs on c63607475, and the coverage numbers were checked against the report XML.

## Summary

Tests added and all validation passed. One unit test was added, for a MOVE where some of the messages are gone. No production defects were found. Task-scoped coverage: 100% of lines in both changed files. The 13 missed branches are `checkNotNull` invariants, a compiler-generated comparison branch, or pre-existing code outside the task.
