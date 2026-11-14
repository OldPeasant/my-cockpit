# Test Report

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: task commits 480868337, 3aa5c98f1, d2df50384 (reports only), 1f4e045f8 (review fixes) on main; base 480868337^; measured HEAD 1f4e045f8db5ae1711a323f7c2453f9045a678ce plus one uncommitted test edit by this run (see Coverage). This is a re-run on the final code; the earlier tests.md was stale.

Requirements:
- `tasks/mail-action-settle.md` acceptance criteria
- `docs/ARCHITECTURE.md` "Coverage"
- `reviews/mail-action-settle/fixes.md` (what CR-1..CR-5 changed)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`

## Tests added or updated

Count: 1 (strengthened, not weakened)

`verifyFolder of a folder without cached messages reads no UID set` now also sets `remote.qresyncChanges`. Before, the QRESYNC changes were null, so the test never reached the `lowest != null` guard of the verified UID-set comparison (`MailHeaderSyncService.kt:351`). JaCoCo showed that branch as missed. Now the test fails if the guard is dropped, because `checkNotNull(lowest)` would throw for an empty folder on a QRESYNC server. The existing tests from the task and the fixer already cover every other criterion, so I added nothing else.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: reproduce the stale source row with a server test that fails before the fix (cache-conflict path or race) | `MailActionServiceTests`: `a move whose cache update gives up after conflicts still resyncs the source by UID set so the row cannot stay` (cache-conflict path, no resync was requested on it before the fix). `MailHeaderSyncServiceTests`: `after a racing sync covered an expunge by the cached HIGHESTMODSEQ, the verified resync after the action removes the row` (the race with QRESYNC). The root cause on the real server was not reproduced; that is recorded as an ASSUMPTION in the task file. | focused Gradle command below | Pass |
| AC2: after move/archive/spam/delete, resync the source (and the target of a move) in the background, with vanished detection that does not depend on modseq | `a move resyncs the source and the target folder by UID set in the background`, `a delete outside Trash resyncs the source and Trash by UID set in the background`, `a delete in Trash resyncs only Trash by UID set in the background`, `archive and spam resync the source and their role folder by UID set in the background`, `a flag change starts no UID-set resync, and only a plain resync when its message is gone`, plus 4 updated existing tests (CR-1). `MailFolderResyncTests`: `a verified request runs the UID-set sync in the background`, `a plain and a verified request for a pending folder merge into one verified sync, in either order`. `MailHeaderSyncServiceTests`: `verifyFolder removes cached UIDs missing on the server even when QRESYNC reports nothing vanished`, `verifyFolder with CONDSTORE but no QRESYNC reads the UID set only once`, `verifyFolder of a folder without cached messages reads no UID set` | focused Gradle command below | Pass |
| AC2: announces `MAIL_FOLDER` | Covered by the existing `MailHeaderSyncService` announce tests (the verify path reuses `announce(result)`) and by the existing action-announce tests in `MailActionServiceTests` | focused Gradle command below | Pass |
| AC3: a sync never brings back a row the action removed (regression for the race) | `after a racing sync covered an expunge by the cached HIGHESTMODSEQ, the verified resync after the action removes the row` | focused Gradle command below | Pass |
| AC4: web e2e for mail-list (refetch + reload, archive from the read view) | `web/e2e/mail-list/list.spec.ts` (commit 3aa5c98f1) | none | Not run: the parent told me not to run e2e; the parent runs it |
| AC5: `./gradlew check`, `npm run check`, `scripts/check.sh`, GreenMail mail tests | none | none | Not run: left to the parent as instructed (GreenMail and the full checks are out of my scope) |

## Focused test execution

Command (cwd `server/`):

`./gradlew test --tests '*MailActionServiceTests' --tests '*MailFolderResyncTests' --tests '*MailHeaderSyncServiceTests' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL. MailActionServiceTests 51/0 failures, MailFolderResyncTests 8/0, MailHeaderSyncServiceTests 48/0, no skips. ktlintCheck passed, including the edited test file.

Note: the first attempt failed with `NoSuchFileException .../build/test-results/test/binary/in-progress-results-generic.bin`. Another Gradle daemon was busy in the same `server/build` directory at the time (probably the parent's integration run), so this was an environmental conflict, not a test failure. The retry before the edit (unmodified HEAD) passed with the same counts.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run. That was out of scope per the parent's instructions, and a concurrent Gradle build was using the same build directory. The parent runs it.

## .taskcheck

Result:

Not run (left to the parent worker).

## Bugs found

None.

## Coverage

Status: verified (focused selection: the three test classes above; JaCoCo report from the final run after the test edit)

Metric: whole-file coverage of task-changed production files. Old code in these files counts too.
Changed-line coverage: not measured with tooling. A manual check of the JaCoCo line data shows the changed hunks fully covered: `MailActionService.kt` `settle` lines 234-242, `MailHeaderSyncService.kt` lines 108-131 dispatch and 350-353. The only exceptions are the partial branches listed below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-action-settle; base 480868337^ (the parent of the first task commit); HEAD 1f4e045f8db5ae1711a323f7c2453f9045a678ce. Task ownership comes from the parent's commit list (480868337, 3aa5c98f1, d2df50384, 1f4e045f8), checked against the `touches` list in the task file.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 1f4e045f8 + uncommitted `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt` sha256 45b6365735917fb0c812446ce9e4a4cbdfa88d6a2cb3cfcb87e20d4394479232. The `reviews/mail-action-settle/review.md` working-tree change belongs to the parent and is not a code input.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`. Excluded: `server/.tmp_dummy` (an empty file, not code), `web/e2e/mail-list/list.spec.ts` (test code), `reviews/` (reports). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1). Command `./gradlew test --tests '*MailActionServiceTests' --tests '*MailFolderResyncTests' --tests '*MailHeaderSyncServiceTests' jacocoTestReport ktlintCheck`, run in `server/`. The test-results directory contained only these three suites.
- Run start/completion time and exit status: about 2026-11-14T21:11:00+01:00 to 21:12:00+01:00 (suite timestamps 20:11:27Z-20:11:42Z); exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 d8b801453f8e091ee5c0ea4318218a97767797fca9c3c7f8696b5601d3767467 (from `server/build/jacoco/test.exec`).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task re-executed because of the edited test, and the report was regenerated in the same build (the hash differs from the pre-edit run fdaf155f...). No code inputs changed during the run.
- Final run after latest test additions and production fixes: yes (it includes fixes 1f4e045f8 and this run's test edit).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` | 1491/1641 (90.9%) | 138/162 (85.2%) | 39/43 (90.7%) | 208/211 (98.6%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` | 176/186 (94.6%) | 11/12 (91.7%) | 9/10 (90.0%) | 35/36 (97.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | 1360/1392 (97.7%) | 114/116 (98.3%) | 36/40 (90.0%) | 181/183 (98.9%) |
| Aggregate (summed counters, all 3 scoped files measured, none missing) | 3027/3219 (94.0%) | 263/290 (90.7%) | 84/93 (90.3%) | 424/430 (98.6%) |

JaCoCo has no separate statement counter; instructions are shown in its place.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailHeaderSyncService.kt:351` (`lowest != null` guard of the verified comparison) | Verified resync of an empty folder on a QRESYNC server must not throw | Now covered: the strengthened `verifyFolder of a folder without cached messages reads no UID set` test |
| `MailHeaderSyncService.kt:352` (null branch of `checkNotNull(lowest)`) | Unreachable: line 351 already guarantees `lowest != null` | Justified gap |
| `MailHeaderSyncService.kt:354` (`folder.uidNext ?: 1`), lines 38/44/48, 326 | Code from before the task (constructor defaults, unrelated helpers) | Outside the task |
| `MailFolderResync.kt:68` (`pending.remove(folderId) ?: false`) | Defensive: the entry is always present when `run` starts, because only `enqueue` schedules runs | Justified gap |
| `MailFolderResync.kt:27` (public constructor with the virtual-thread executor) | Spring wiring; the tests use the internal constructor with a direct executor | Justified gap (bootstrap) |
| `MailActionService.kt` partial branches at 76-78, 154-193, 251-263, 292, 323, 378, 443-447, 484-485 | Code from before the task (validation, grouping, UID mapping) that is outside the changed `settle` / `updateCache` call | Outside the task; the changed `settle` (lines 234-242) is fully covered |

## Unable to test

- AC4 web e2e (`web/e2e/mail-list/list.spec.ts`): not run. The parent told me not to, and the parent runs it.
- AC5 full checks and the GreenMail suites (`MailActionGreenMailTests`, `HeaderSyncGreenMailTests`, `MailIdle*`): not run, per the parent's instructions.
- The race against a real IMAP server: GreenMail and the unit fakes cannot reproduce the owner's real-server timing. Unit tests cover it with a simulated racing sync; the task file records this as an ASSUMPTION.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I strengthened one test to cover the last meaningful new branch. The focused suites (107 tests) and ktlintCheck pass. The e2e, GreenMail and full-check criteria still need the parent's runs.
