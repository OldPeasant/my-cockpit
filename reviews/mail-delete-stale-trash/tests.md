# Test Report

Date: 2026-11-01

Task: mail-delete-stale-trash - Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up

Scope: `git diff 8e0f005156e60f8ee3813dd1a45748afa70f0704..32f302f076685218d1117cd691bb873d54fef7fb` (6 task commits, 48aee449f to 32f302f07, on branch main), plus one uncommitted test added by this run in `MailActionServiceTests.kt`. Production files changed: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` (new). The final code includes the CR-1 fix (`applyToRows` only touches rows still at the UID the server command used).

Requirements:
- tasks/mail-delete-stale-trash.md (4 acceptance criteria, notes, 2 recorded assumptions)
- reviews/mail-delete-stale-trash/review.md (CR-1)
- AGENTS.md project notes, docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt` (this run: 1 test added)

## Tests added or updated

Count: 1

- `MailActionServiceTests` > `a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync`

Why: AC2 covers "any other action", but no unit test had a MOVE with some messages gone. That is the path where the COPYUID list from the server has to line up with the present UIDs, not with all requested UIDs (`remote()` passes `present` to `move()`). The test checks that the present message follows its COPYUID to Projects, that the gone message fails with NOT_FOUND and keeps its row, that only the source folder is resynced, and that nothing is deleted.

Existing task tests reviewed and judged sufficient (no change needed): 16 task-specific tests in `MailActionServiceTests` (Trash gone: all, mixed, linked, Trash/Inbox mix, UIDVALIDITY changed, folder missing, no cached UIDVALIDITY; stale resync: READ mixed, DELETE outside Trash, per-folder only, none when all found, source UIDVALIDITY changed; CR-1: rows relocated or detached meanwhile for delete, gone-delete, flag and move), 6 tests in `MailFolderResyncTests`, and the GreenMail tests (a), (b), (c) plus the Trash UIDVALIDITY-change test in `MailActionGreenMailTests`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: DELETE in Trash of a UID gone there (folder exists, UIDVALIDITY matches) is ok; row removed like a permanent delete (detached when linked, else deleted, unindexed); MAIL_FOLDER event for Trash | `a delete in Trash of a message already gone from Trash is ok and removes the row like an expunge`, `a delete in Trash of present and already gone messages expunges the present ones and reports all ok`, `a linked message already gone from Trash is detached by a delete, not deleted`, `one delete of messages gone from Trash and from the Inbox is ok for Trash and NOT_FOUND with a resync for the Inbox` | parent: `cd server && ./gradlew ktlintFormat test --tests '*MailActionServiceTests*'` | Pass (run by the parent before this run's addition) |
| AC1: changed UIDVALIDITY or missing folder still NOT_FOUND | `a delete in Trash whose UIDVALIDITY changed still fails with NOT_FOUND, keeps the row and starts a resync`, `a delete in a Trash folder missing on the server still fails with NOT_FOUND, keeps the row and starts no resync`, `a delete in a Trash folder without a cached UIDVALIDITY treats a missing message as stale, not deleted` (assumption) | same as above | Pass (parent run) |
| AC2: other actions report NOT_FOUND for missing ids and start a background resync of that folder | `messages gone from the server fail with NOT_FOUND while the others of the folder are acted on`, `when no requested message is on the server any more nothing is sent and the cache is left to the next sync`, `messages gone from their folder start a background resync of that folder only`, `an action that finds all its messages starts no resync`, `a source folder with another UIDVALIDITY than cached fails the ids with NOT_FOUND and starts a resync`, new: `a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync` | `cd server && ./gradlew test --tests '*MailActionServiceTests*'` | Pass for the existing tests (parent run). Not run yet for the new test: this agent cannot run Gradle, so the parent must run it |
| AC2: resync runs in the background through MailHeaderSyncService, merges pending requests, a failing sync is logged and does not fail the action | `MailFolderResyncTests` (6 tests: background, merging, request during running sync, failing sync swallowed, after shutdown, shutdown) | parent: `cd server && ./gradlew check` | Pending the parent's `check` run |
| AC2: stale rows disappear after the sync, list refreshes via the folder event | GreenMail (c) `a move of a message another client deleted fails with NOT_FOUND and a background sync drops the row` | `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'` | Pass at aedab811d (recorded in the task file); the parent is re-running it on the final code |
| AC3: GreenMail tests (a), (b), (c) | (a) `a delete in Trash of a message another client already deleted there is ok and removes the row`, (b) `a delete in Trash of present and already gone messages reports all ok`, (c) as above | same as above | Pass at aedab811d; the parent is re-running it on the final code |
| AC4: unit tests in MailActionServiceTests; `./gradlew check` passes | all of the above | parent: `cd server && ./gradlew check` | Pending the parent's run, after this run's addition |
| CR-1: the cache update only touches rows still at the UID the server command used | `a delete in Trash of a UID gone from Trash leaves a row a sync relocated to another UID in Trash meanwhile`, `a delete in Trash removes the rows still at the expunged UID and leaves a relocated one alone`, `a delete in Trash of a UID gone from Trash leaves a row a sync detached meanwhile`, `a flag action leaves a row a sync relocated to another UID in the same folder meanwhile`, `a move leaves a row a sync relocated to another UID in the source folder meanwhile` | parent: `cd server && ./gradlew ktlintFormat test --tests '*MailActionServiceTests*'` | Pass (parent run) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat test --tests '*MailActionServiceTests*'` (run by the parent before this run's addition)

Result:

Pass. The new test `a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync` has not been run: Gradle cannot run in this sub-agent's environment. The parent must run the same command again.

## Full test execution

Command:

`cd server && ./gradlew check` and `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'`

Result:

Not run by this agent (Gradle is unavailable here). The parent is running both and will run them again after this edit.

## .taskcheck

Result:

Not run by this agent. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. I read the code paths in `MailActionService.applyInFolder`, `remote`, `updateCache`, `applyToRows` and `MailFolderResync` against AC1 and AC2 and found no behavior that violates the criteria.

## Coverage

Status: unavailable to this agent. Task-scoped coverage is measured by the parent with the existing JaCoCo setup (`cd server && ./gradlew check`, which includes `jacocoTestReport`; report at `server/build/reports/jacoco/test/jacocoTestReport.xml`). This agent cannot run Gradle, so no numbers are reported here and none are inferred.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-delete-stale-trash; base 8e0f005156e60f8ee3813dd1a45748afa70f0704 (given by the parent); HEAD 32f302f076685218d1117cd691bb873d54fef7fb; task commits from `git log 8e0f00515..HEAD` (6 commits, all prefixed with the task id)
- Source/test snapshot identifier (including uncommitted inputs): sha256 MailActionService.kt a25e7399daffabdf6c71e57920bc048ecb1a524fd5f04e3cbcf35c96615077ed, MailFolderResync.kt 0604a673da3c1078151329f8b5f6b4149beeb47eaeaf34f8f7c621a9673b26b8, MailActionServiceTests.kt (with the new uncommitted test) 793e84b91dabbb1744b45bffab7a30c363e85d521284f0571f683da46e50ed04
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt`. Excluded: test files and `reviews/` reports (not production code). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin; `./gradlew check` in `server/`; full server unit test suite. Run by the parent, not by this agent.
- Run start/completion time and exit status: not available to this agent
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` from the parent's run
- Evidence outputs are fresh and inputs stayed unchanged: cannot be confirmed by this agent; the parent's run must come after this run's test addition
- Final run after latest test additions and production fixes: no; the parent's rerun after this edit is still pending

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` | N/A (not measured here) | N/A (not measured here) | N/A (not measured here) | N/A (not measured here) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` | N/A (not measured here) | N/A (not measured here) | N/A (not measured here) | N/A (not measured here) |

Coverage gaps found by reading the code (each changed branch was traced to a test):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailActionService.kt:204-208` (`Remote.Invalidated`) | AC1/AC2: changed UIDVALIDITY gives NOT_FOUND plus a resync | Covered by the two UIDVALIDITY tests (Inbox READ, Trash DELETE) |
| `MailActionService.kt:213-216` (`goneCountsAsDone` true/false, `stale` empty/non-empty) | AC1 and AC2 | Covered by Trash gone (true, empty), Trash without UIDVALIDITY (false through null), non-Trash gone (false through operation), all found (empty) |
| `MailActionService.kt:275, 282` (MOVE with some UIDs missing, COPYUID matched to present UIDs) | AC2 for MOVE; a wrong alignment would move a row to another message's UID | Before this run, covered only by GreenMail (c) with a single message. Added `a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync` |
| `MailActionService.kt:382` (filter: folder differs / uid null / uid differs) | CR-1 | Covered by `a row a sync relocated between lookup and cache update is not overwritten` (folder), `... leaves a row a sync detached meanwhile` (uid null), and the relocated tests (uid differs) |
| `MailFolderResync.kt:27` (Spring constructor with a real virtual-thread executor) | Wiring only | Not unit tested; exercised by the GreenMail tests that wait for background resyncs (`awaitUncached`, `awaitUidValidity`) |
| `MailFolderResync.kt:49-55` (log levels and contents) | AGENTS.md: no server responses at INFO or above | Not asserted by a test, because the project has no log-capture convention; checked by reading the code (only the error enum or exception class name at INFO/WARN) |

## Unable to test

- Running any Gradle task (unit, integration, coverage): Gradle cannot run in this sub-agent's environment. The parent runs `./gradlew test --tests '*MailActionServiceTests*'`, `./gradlew check` (with JaCoCo) and `./gradlew integrationTest --tests '*MailActionGreenMailTests*'`. Follow-up: the parent must run the new test and record the JaCoCo numbers for the two files above.
- A real race between a full sync and an action (CR-1): the unit tests simulate it by returning relocated or detached rows from `findAllById`. A deterministic integration test of the race is not practical, and the task notes say to fix such a race only if tests show it.

## Summary

Tests added; no production defects found. Reading the code, every changed branch has a test, but the JaCoCo numbers come from the parent's run. One unit test was added for a MOVE where some of the messages are gone. The parent still has to run it, along with `./gradlew check` (JaCoCo) and the GreenMail suite, on the final code.
