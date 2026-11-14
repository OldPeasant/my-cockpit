# Test Report

Date: 2026-11-14

Task: and-mail-task-archive - Android: mail → Next action & archive (read view, New view, mail list)

Scope: base 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0 .. HEAD 45d06ed93ff2f8ac09d479a241ba7cef7073de86 (task code commits 3583cdba9, 2423fddea, 7e7686f37, 7590d0ebe, fix commit 45d06ed93; board commits only touch `tasks/`), plus one uncommitted test change in this run. Re-run on the final code after the fix commit; the previous report described the pre-fix revision 7590d0ebe and is replaced.

Requirements:
- `tasks/and-mail-task-archive.md` acceptance criteria and recorded assumptions (CR-4/TW-1 is accepted as an assumption, not tested as a defect)
- `reviews/and-mail-task-archive/fixes.md` (CR-1/TW-2, CR-3, TW-3)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailNextActionInAppTest.kt`

## Tests added or updated

Count: 1 (added in this run). The task's earlier test files (51 tests from the first round plus the fixer's two TW-3 tests and the extended CR-1 in-app test) were re-run unchanged.

Added: `MailNextActionInAppTest` "back from the task of a mail that was not archived shows the mail with its notice and the new task in its links" (CR-3). In the Hilt activity with fake repositories, the mail's links return an empty list first and the new task on the next call; after "Next action & archive" with `archived = false`, Back from the task detail shows the mail, the notice "Next action created, but “Subject 1” could not be archived." and the link to the new task. Limitation: it was not run against the pre-fix code (production code may not be changed here), so it is not proven that it fails without the fix; it would only fail if the links view model survives the task overlay and is not reloaded.

## Requirement coverage

Command A: `cd android && ./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport` (full unit suite).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Read view action next to "To task", task detail opens, Back returns to origin | `MailReadNextActionViewModelTest`, `MailReadNextActionRouteTest`, `MailReadScreenTest`, `MailNextActionInAppTest` "the read view opens the new task, whose links show the mail, and back returns to the list without it" | Command A | Pass |
| 2. New view mail rows offer the same action | `NewNextActionViewModelTest`, `NewNextActionScreenTest` | Command A | Pass |
| 3. Mail list multi-select: one Next action per mail, mails leave, snackbar "N next actions created" opens Next Actions | `MailNextActionsViewModelTest`, `MailNextActionsScreenTest`, `MailNextActionsRepositoryTest`, `MailNextActionInAppTest` "the multi-selection's next actions leave the list and the snackbar opens Next Actions" | Command A | Pass |
| 4. Archive failure keeps the task and names the mail; failed creation leaves the mail with an error | VM tests of read view, New view and mail list (including TW-3 "a failed archive of a mail without subject names it by the task's title" and `aFailedArchiveOfAMailWithoutSubjectNamesItByTheTaskTitle`), `MailNextActionInAppTest` new CR-3 test | Command A | Pass |
| 5. Task detail's linked items show the mail; opening it shows the archived mail | `MailNextActionInAppTest` "opening the linked mail from the new task's detail shows the archived mail" (CR-1/TW-2, includes Back to the task) | Command A | Pass |
| CR-3: mail links reload after a not-archived Next action | `MailNextActionInAppTest` "back from the task of a mail that was not archived shows the mail with its notice and the new task in its links" | Command A | Pass |
| 6. EN/DE strings, touch targets ≥ 48dp, actions fit at 320dp | `MailReadScreenTest`, `MailNextActionsScreenTest`, `NewNextActionScreenTest` (size and width checks); DE completeness is enforced by `lintDebug` | Command A | Pass (lintDebug not run by this helper; the fixer reports it passing on 45d06ed93) |
| 7. Unit/Compose tests; ktlintCheck testDebugUnitTest lintDebug and check.sh pass | full suite | Command A | Pass for ktlintCheck and testDebugUnitTest; lintDebug and `sh scripts/check.sh` Not run: left to the parent worker |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailNextActionInAppTest' -q`

Result:

Pass (after one ktlint line-wrapping fix in the new test).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport`

Result:

Pass. BUILD SUCCESSFUL in 3m 29s; `testDebugUnitTest` executed (not up to date); test result XMLs: 5492 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` is run by the parent worker before `done`; the parent asked this helper for the Android suite only).

## Bugs found

None in this run. The earlier findings CR-1/TW-2, CR-3 and TW-3 are fixed in 45d06ed93 and their tests pass. CR-4/TW-1 (partial success over several 100-mail batches) is an accepted assumption and is not reported again.

## Coverage

Status: verified (whole-file, Android JaCoCo, full unit suite).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-mail-task-archive`; base 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0 (given by the parent); HEAD 45d06ed93ff2f8ac09d479a241ba7cef7073de86; scope from `git diff --name-only 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0..HEAD -- android/app/src/main`, checked against the task's commits and `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 45d06ed93 plus uncommitted `MailNextActionInAppTest.kt` sha256 2af448480acc9871edde8a90d462ac0684127f127277b42fe5dd870d69915c05 (same hash before and after the run; no production file modified). `reviews/and-mail-task-archive/review.md` was changed by the parallel code-reviewer during the run; it is not a measured input.
- Exact included files: `mail/list/MailApi.kt`, `mail/list/MailListViewModel.kt`, `mail/list/MailModels.kt`, `mail/list/MailRepository.kt`, `mail/list/MailScreen.kt`, `mail/read/MailReadScreen.kt`, `mail/read/MailReadViewModel.kt`, `newitems/NewScreen.kt`, `newitems/NewViewModel.kt`, `ui/CockpitShell.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: the four `strings_*.xml` resource files (no executable code). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport` in `android/`; full debug unit suite.
- Run start/completion time and exit status: started after 2026-11-14T09:43:23+01:00, finished before 09:47:21+01:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 6404c229b93b5669a88331ae1a71bf0382ec6a6e80e19f9f365d434014acba09; per-file counters read with a script in `.tmp/cov.py` from the XML `sourcefile` counters.
- Evidence outputs are fresh and inputs stayed unchanged: the report was written by this run (mtime inside the run window; the coverage task executed after the test task ran) and the measured inputs had the same hash before and after.
- Final run after latest test additions and production fixes: yes (fix commit 45d06ed93 and the CR-3 test are included).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `mail/list/MailApi.kt` | 750/1324 (56.6%) | N/A (0/0) | 95/141 (67.4%) | 109/157 (69.4%) |
| `mail/list/MailListViewModel.kt` | 5137/5205 (98.7%) | 412/432 (95.4%) | 127/127 (100.0%) | 482/482 (100.0%) |
| `mail/list/MailModels.kt` | 1036/1038 (99.8%) | 97/98 (99.0%) | 70/70 (100.0%) | 123/123 (100.0%) |
| `mail/list/MailRepository.kt` | 1227/1236 (99.3%) | 91/98 (92.9%) | 33/33 (100.0%) | 127/127 (100.0%) |
| `mail/list/MailScreen.kt` | 8824/9050 (97.5%) | 324/368 (88.0%) | 180/215 (83.7%) | 706/715 (98.7%) |
| `mail/read/MailReadScreen.kt` | 6524/6864 (95.0%) | 320/375 (85.3%) | 134/153 (87.6%) | 491/500 (98.2%) |
| `mail/read/MailReadViewModel.kt` | 1520/1527 (99.5%) | 125/128 (97.7%) | 66/66 (100.0%) | 147/147 (100.0%) |
| `newitems/NewScreen.kt` | 3859/4004 (96.4%) | 140/159 (88.1%) | 67/78 (85.9%) | 293/297 (98.7%) |
| `newitems/NewViewModel.kt` | 1544/1544 (100.0%) | 96/96 (100.0%) | 60/60 (100.0%) | 177/177 (100.0%) |
| `ui/CockpitShell.kt` | 2104/2203 (95.5%) | 113/135 (83.7%) | 50/55 (90.9%) | 176/178 (98.9%) |
| Aggregate (summed counters, all 10 files, none missing) | 32525/33995 (95.7%) | 1718/1889 (90.9%) | 882/998 (88.4%) | 2831/2903 (97.5%) |

JaCoCo reports instructions rather than statements. Whole-file numbers include pre-existing code in these files.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail/list/MailApi.kt:204-235` (new to-tasks DTOs) | Generated data-class members (equals, hashCode, copy, componentN) of request/response DTOs; most other missed lines are pre-existing DTOs | Justified gap: repository tests mock `MailApi` and exercise the DTO fields used by `MailRepository.toNextActions` (100% lines there) |
| `mail/read/MailReadScreen.kt:231` (`onRemoved` of the linked mail opened from the new task) | Archiving or deleting the linked mail again from inside the task overlay returns to the task | Justified gap: not in the acceptance criteria; would need a further in-app flow |
| `mail/read/MailReadScreen.kt:235` (non-mail linked item from the new task) | Opening a non-mail link from the new task uses `SearchItemScreen` | Justified gap: a freshly created Next action links only its mail |
| `mail/list/MailScreen.kt`, `newitems/NewScreen.kt`, `ui/CockpitShell.kt` partial branches | Mostly Compose-generated default-argument and recomposition branches and pre-existing code | Not material for this task; task flows are covered by screen and in-app tests |

## Unable to test

- `lintDebug` and `sh scripts/check.sh` (criterion 7): not run by this helper; the parent worker runs them.
- Regression strength of the CR-3 test against the pre-fix code: not demonstrated, because production code may not be reverted here.

## Summary

Tests added and all validation passed.
