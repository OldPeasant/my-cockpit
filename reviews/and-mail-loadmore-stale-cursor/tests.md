# Test Report

Date: 2026-11-14

Task: and-mail-loadmore-stale-cursor: Android: drop a stale load-more answer after a quiet first-page reload replaced the list (New, Mail list)

Scope: `git diff a6f7dfc35a0e618353dbe4f2711bcc0ca9a6180a..0fa420fc0dd497c734546c42c95d8a07788dd6a3` (task base = claim commit a6f7dfc35, single task commit 0fa420fc0 on main, HEAD = 0fa420fc0), plus the uncommitted test additions of this run in the two task test files. No other uncommitted changes.

Requirements:
- tasks/and-mail-loadmore-stale-cursor.md (acceptance criteria 1-3)
- reviews/and-mail-live-refresh/review.md CR-4 (origin of the task, as referenced in the task goal)
- docs/ARCHITECTURE.md "Coverage"; parent worker instructions (test folders of the two packages only, focused Gradle runs)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListLiveRefreshTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewMailLiveRefreshTest.kt`

## Tests added or updated

Count: 3 added by this run (the 4 race tests of the task commit were reviewed and kept unchanged).

Reviewed (task commit):
- `MailListLiveRefreshTest`: `a load more answering after a full page of new mail replaced the list is dropped`, `a failed load more answering after the list was replaced shows no message`
- `NewMailLiveRefreshTest`: `aLoadMoreAnsweringAfterAFullPageOfNewMailReplacedTheListIsDropped`, `aFailedLoadMoreAnsweringAfterTheListWasReplacedRaisesNoBanner`

These reproduce the race: without the new guard the pending page would be appended behind the replaced list (ids 8, 9, 3 and cursor c2 in the mail list; receipt appended and cursor page-3 in New), so the assertions fail on the base revision by construction.

Added:
- `MailListLiveRefreshTest`: `a load more answering after an offline event reload kept the cursor is still appended` (a quiet reload that fails leaves the cursor, so the pending page is applied normally).
- `MailListLiveRefreshTest`: `a load more of the unified inbox never lands in a folder opened meanwhile with an equal cursor` (mailbox switch during load more; the new folder's first page carries the same opaque cursor text, the unified-inbox page must not be appended to the folder).
- `NewMailLiveRefreshTest`: `aLoadMoreAnsweringAfterAFailedEventReloadKeptTheCursorIsStillAppended` (same as the first one for the New view, including the offline cache content).

The case "a quiet reload that keeps the cursor still appends" was already covered by the existing tests `an event during load more still reloads the first page` and `anEventDuringALoadMoreStillReloadsTheNewestMails` (the merged first page keeps the old cursor; the answer is appended). No duplicate was added.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a load-more answer is applied only while the cursor is still the one the request used; otherwise it only ends loading-more (New view) | `aLoadMoreAnsweringAfterAFullPageOfNewMailReplacedTheListIsDropped`, `aFailedLoadMoreAnsweringAfterTheListWasReplacedRaisesNoBanner`, `aLoadMoreAnsweringAfterAFailedEventReloadKeptTheCursorIsStillAppended`, `anEventDuringALoadMoreStillReloadsTheNewestMails` | `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.NewMailLiveRefreshTest' --tests 'ch.sonensei.cockpit.mail.list.MailListLiveRefreshTest'` (in android/) | Pass |
| AC1 (Mail list), including the mailbox part of the guard | `a load more answering after a full page of new mail replaced the list is dropped`, `a failed load more answering after the list was replaced shows no message`, `a load more answering after an offline event reload kept the cursor is still appended`, `a load more of the unified inbox never lands in a folder opened meanwhile with an equal cursor`, `an event during load more still reloads the first page` | same command | Pass |
| AC2: unit tests reproduce the race (full page of new mail during a pending load more) for both view models | the two "replaced the list is dropped" tests above | same command | Pass |
| AC3: `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | ktlintCheck only | `./gradlew ktlintCheck -q` (in android/) | ktlintCheck Pass. Full `testDebugUnitTest`, `lintDebug` and `scripts/check.sh`: Not run: the parent asked for focused Gradle runs only; the parent runs them |

## Focused test execution

Command:

`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.NewMailLiveRefreshTest' --tests 'ch.sonensei.cockpit.mail.list.MailListLiveRefreshTest' -q` (in android/)

Result:

Pass (MailListLiveRefreshTest 20 tests, NewMailLiveRefreshTest 17 tests, 0 failures, 0 skipped).

`./gradlew ktlintCheck -q` (in android/): Pass (exit 0).

## Full test execution

Command:

`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.mail.list.*' createDebugUnitTestCoverageReport -q` (in android/)

Result:

Pass for both affected packages (49 test classes, 0 failures/errors, exit 0). The whole Android suite, `lintDebug` and `scripts/check.sh`: Not run (parent instruction: focused Gradle runs only).

## .taskcheck

Result:

Not run (parent instruction; the parent runs `sh scripts/check.sh` before `done`).

## Bugs found

None.

## Coverage

Status: verified (whole-file, for both task-changed production files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the JaCoCo XML line entries with the added/modified lines of the task diff. Executable changed lines: `MailListViewModel.kt` 264, 265 and `NewViewModel.kt` 247; 3/3 covered (100%). Branches on those lines (JaCoCo per-line counters): `MailListViewModel.kt:264` 3/4, `NewViewModel.kt:247` 2/2. The other changed lines are comments (KDoc lines 253 and 236, code comments 263 and 246) and not executable.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-loadmore-stale-cursor; base a6f7dfc35a0e618353dbe4f2711bcc0ca9a6180a (claim commit, given by the parent); HEAD 0fa420fc0dd497c734546c42c95d8a07788dd6a3 = the single task commit; scope from `git diff a6f7dfc35..0fa420fc0 --stat` (2 production files, 2 test files), consistent with the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 NewViewModel.kt aeb26724f57880c106395b773b45d3cb2cceb588c24e6ee20c0da27683556198, MailListViewModel.kt 22788f5b64b4269fc647e20a888e8b90576461f7b4bb0cbff33deecfbe1ba829, NewMailLiveRefreshTest.kt d87d39190be0e0d6e97cbf0d058a1fc532e512d2e23cb318ea2d9eed79fc7e76, MailListLiveRefreshTest.kt 7c9a06866b13d2da3c6766b29ff4a28a3b29e5c4138808946c0215e8cd1cf103 (identical before and after the coverage run).
- Exact included files; renamed/deleted/excluded files and reasons: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`, `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt`. No renames or deletions; nothing excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.mail.list.*' createDebugUnitTestCoverageReport -q` in android/; selection = all unit tests of the two packages (49 classes), not the whole suite.
- Run start/completion time and exit status: started after 2026-11-14T20:09:46Z, report written 2026-11-14T20:10:20Z; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 8bd7720eb1f1ff822145ee6b617dfe1f047a4a8f9770dd7a5bc1cec5646eebab.
- Evidence outputs are fresh and inputs stayed unchanged: report mtime is after the run start; input hashes unchanged after the run.
- Final run after latest test additions and production fixes: yes (no production fix was made; the run includes the 3 added tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt` | 1674/1678 (99.8%) | 115/116 (99.1%) | 65/65 (100%) | 193/193 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt` | 5367/5441 (98.6%) | 437/460 (95.0%) | 132/132 (100%) | 505/505 (100%) |
| Aggregate (summed counters, both files, none unmeasured) | 7041/7119 (98.9%) | 552/576 (95.8%) | 197/197 (100%) | 698/698 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailListViewModel.kt:264` (1 of 4 branches: `it.mailbox != current.mailbox` true) | Mailbox part of the new guard | Not reachable today: `select()` runs a non-quiet `load()` which cancels `loadJob`, so a load-more answer never resumes in another mailbox. The behavior (no unified page lands in a newly opened folder, even with an equal cursor text) is pinned by the added test `a load more of the unified inbox never lands in a folder opened meanwhile with an equal cursor`; the branch stays as defense in depth. Justified gap. |
| `NewViewModel.kt:174-176`, `MailListViewModel.kt:233-235` | `followMailChanges` collect lambda | Pre-existing code outside this task (JaCoCo reports a partially missed synthetic branch in the suspend lambda); not changed by the task. |
| `MailListViewModel.kt` other partial branches (151, 166, 289, 307, 309, 506, 529, 609, 611, 670, 674, 723, 728, 770, 807, 827, 867, 887, 888) | Pre-existing folder edit, search, load-older and restore code | Outside the task's changed lines; not addressed here. |

## Unable to test

- AC3 in full (whole `testDebugUnitTest`, `lintDebug`, `sh scripts/check.sh`): not run by this helper on parent instruction (focused runs only); the parent worker must run them for `tasks.py verify`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The task's race tests reproduce the bug for both view models; 3 edge-case tests were added (a failed or offline quiet reload keeps the cursor so the answer is still appended, in both view models; a mailbox switch during load more with an equal cursor text never appends the old mailbox's page). All focused tests and ktlintCheck pass.
