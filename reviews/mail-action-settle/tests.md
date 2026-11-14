# Test Report

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: base 27e0adeb471ce5293b9c420635eb3b807596d5b8 to HEAD d1c6b807c052fa1263e00a1191d217ce5c150881. Task commits 279194eea, 9a8fc9e88, 9441010af, 33fdebc17, eeb3acb13, efb91651c, d1c6b807c (the parent supplied this list; it matches the task's `touches`). The working tree was clean, so there are no uncommitted inputs. This re-run replaces the earlier report, which was written at 1f4e045f8. The newest commit, d1c6b807c (fixes "round 2"), changed only KDoc and log wording in `MailActionService.kt` and `MailFolderResync.kt`, added two `requestVerified` not-called asserts in `MailActionServiceTests`, and changed the e2e `list.spec.ts`.

Requirements:
- `tasks/mail-action-settle.md` acceptance criteria
- `docs/ARCHITECTURE.md` "Coverage"
- `reviews/mail-action-settle/fixes.md` (rounds 1 and 2)

## Test files changed

- None in this run. The tests come from the task commits, including efb91651c (the earlier test-writer tightening) and d1c6b807c (the fixer's asserts).

## Tests added or updated

Count: 0

The coverage counters match the earlier run exactly, and the only production change since then was wording. The new code has no meaningful uncovered branch left (see Coverage), so no test was needed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a server test reproduces the stale source row and fails before the fix | `MailActionServiceTests`: `a move whose cache update gives up after conflicts still resyncs the source by UID set so the row cannot stay` (cache-conflict path). `MailHeaderSyncServiceTests`: `after a racing sync covered an expunge by the cached HIGHESTMODSEQ, the verified resync after the action removes the row` (QRESYNC race). The real-server root cause was not reproduced; the task file records this as an ASSUMPTION. | focused Gradle command below | Pass |
| AC2: background resync of the source (and target) after move/archive/spam/delete, with UID-set vanished detection | `MailActionServiceTests`: the move / delete outside Trash / delete in Trash / archive and spam resync tests, `a flag change starts no UID-set resync, and only a plain resync when its message is gone`, plus the flag-path `requestVerified` not-called asserts (round 2 CR-2). `MailFolderResyncTests`: verified request in the background; plain and verified requests merge in either order. `MailHeaderSyncServiceTests`: the `verifyFolder` tests (QRESYNC reports nothing vanished, CONDSTORE-only reads the UID set once, an empty folder reads no UID set) | focused Gradle command below | Pass |
| AC2: announces `MAIL_FOLDER` | The existing announce tests in `MailHeaderSyncServiceTests` (the verify path reuses `announce`) and the action-announce tests in `MailActionServiceTests` | focused Gradle command below | Pass |
| AC3: a sync never brings back a row the action removed (regression for the race) | `after a racing sync covered an expunge by the cached HIGHESTMODSEQ, the verified resync after the action removes the row` | focused Gradle command below | Pass |
| AC4: web e2e mail-list (refetch + reload, archive from the read view with in-app back) | `web/e2e/mail-list/list.spec.ts` | none by me | Not run: the parent told me not to. The fixer reports `npm run e2e -- e2e/mail-list/ --project=chromium -> 36 passed`; the parent records it |
| AC5: `./gradlew check`, `npm run check`, `scripts/check.sh`, GreenMail mail tests | none | none | Not run: left to the parent as instructed |

## Focused test execution

Command (cwd `server/`):

`./gradlew ktlintCheck test --tests '*MailActionServiceTests' --tests '*MailFolderResyncTests' --tests '*MailHeaderSyncServiceTests' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 27s; `:test` re-executed and was not up-to-date. MailActionServiceTests 51 tests / 0 failures / 0 errors / 0 skipped; MailFolderResyncTests 8/0/0/0; MailHeaderSyncServiceTests 48/0/0/0, 107 in total. ktlintCheck passed (UP-TO-DATE against the current sources).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run (the parent runs it, per instructions).

## .taskcheck

Result:

Not run (left to the parent worker).

## Bugs found

None.

## Coverage

Status: verified for the focused selection (the three test classes above)

Metric: whole-file coverage of task-changed production files. Old code in these files counts too.
Changed-line coverage: not measured with tooling. A manual check of the JaCoCo line data against the task diff found no missed instruction in `settle` (`MailActionService.kt:234-241`). In `MailHeaderSyncService.kt`, the verified comparison (lines 351-352) is covered except for the unreachable null branch described below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-action-settle; base 27e0adeb471ce5293b9c420635eb3b807596d5b8; HEAD d1c6b807c052fa1263e00a1191d217ce5c150881. The task commits come from the parent and were checked against the task's `touches`; `git diff --stat base..HEAD` shows only task files.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d1c6b807c with a clean working tree (`git status --short` showed nothing after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`. Excluded: test files, `web/e2e/mail-list/list.spec.ts` (test code) and `reviews/` (reports). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1), with the command above, in `server/`. `build/test-results/test/` held only these three suites.
- Run start/completion time and exit status: suite timestamps 2026-11-14T20:48:23Z to 20:48:36Z; report written at 2026-11-14 21:48:40 +0100; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 2bd16d1a14abb4675c0f9728a9d585ceeb281770b8ef5cc2dbc67dccc74c25e6.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `:test` and `:jacocoTestReport` executed in this run ("2 executed"), and the working tree was clean after the run.
- Final run after latest test additions and production fixes: yes (HEAD d1c6b807c, which includes the round-2 fixes).

Values below use covered/total (percentage). JaCoCo has no statement counter, so instructions are shown in its place.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` | 1491/1641 (90.9%) | 138/162 (85.2%) | 39/43 (90.7%) | 208/211 (98.6%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` | 176/186 (94.6%) | 11/12 (91.7%) | 9/10 (90.0%) | 35/36 (97.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | 1360/1392 (97.7%) | 114/116 (98.3%) | 36/40 (90.0%) | 181/183 (98.9%) |
| Aggregate (summed counters; all 3 scoped files measured, none missing or excluded) | 3027/3219 (94.0%) | 263/290 (90.7%) | 84/93 (90.3%) | 424/430 (98.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailHeaderSyncService.kt:352` (null branch of `checkNotNull(lowest)`) | Unreachable, because line 351 already requires `lowest != null` | Justified gap |
| `MailHeaderSyncService.kt:354` (`folder.uidNext ?: 1`), lines 38/44/48, 326, 330 | Code from before the task (constructor defaults, unrelated helpers) | Outside the task |
| `MailFolderResync.kt:69` (`pending.remove(folderId) ?: false`) | Defensive: the entry always exists when `run` starts, because only `enqueue` schedules runs | Justified gap |
| `MailFolderResync.kt:28` (Spring constructor with the virtual-thread executor) | Bootstrap wiring; the tests use the internal constructor with a direct executor | Justified gap |
| `MailActionService.kt` partial branches at 76-78, 154-193, 251-263, 292, 323, 379, 444-448, 485-486 | Code from before the task (validation, grouping, UID mapping, the conflict-retry log branch) | Outside the changed `settle`, which has no missed lines; the give-up path is exercised by the cache-conflict test |

## Unable to test

- AC4 web e2e: not run by me, per instructions. The fixer reports 36 passed; the parent records it.
- AC5 full checks and the GreenMail suites: not run, per instructions; the parent runs them.
- The race against the owner's real IMAP server cannot be reproduced with GreenMail or the unit fakes. The unit tests simulate it, and the task file records this as an ASSUMPTION.

## Summary

Tests and task-scoped coverage are sufficient, and no production defects were found. The focused suites (107 tests) and ktlintCheck pass at HEAD d1c6b807c. The e2e, GreenMail and full-check criteria still depend on the parent's runs.
