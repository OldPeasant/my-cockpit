# Test Report

Date: 2026-11-14

Task: and-mail-live-refresh - Android: mail views reload when new mail syncs (New, Mail list, Today), not only on resume

Scope: round 2, on the final code. Base `2c363a763cb34a2fce40ad68afafa0778036c597` (given by the parent) .. task commits `ee50ac07f` (implementation), `7fffc4fd0` (round-1 tests), fixer commit `094d34bda9ab79d99b44ee89af7614050f43af4a` (TW-1, CR-2, CR-3 fixes). While this round ran, the parent committed `52fa95d17` (only `reviews/and-mail-live-refresh/review.md`), so the production code at HEAD is identical to `094d34bda`. Branch main. Uncommitted inputs: two edited test files (see "Test files changed").

Requirements:
- `tasks/and-mail-live-refresh.md` acceptance criteria 1 to 5 and 7 (criterion 6 is a manual device check, already skipped by the parent)
- `tasks/and-mail-live-refresh.md` assumptions (the newest one: mail events reload only the dashboard's Inbox widget; an event during a first-page load is deferred to one follow-up quiet reload; during paging it reloads at once)
- `reviews/and-mail-live-refresh/fixes.md` (what the fixer changed)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

Round 2 (uncommitted):
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListLiveRefreshTest.kt` (1 test added), sha256 `4de29e3039bd01103f8a5cc601a9f8786740b9416d5e5ed132281a0f525fa134`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardMailRefreshTest.kt` (1 test added), sha256 `d19a609e65c2f56e2118afbda514b487314350b2d1ebd5a0976748f2bb7fe3ef`

Task test files as a whole (round 1, fixer, round 2):
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/MailRefreshTriggersTest.kt` (7 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewMailLiveRefreshTest.kt` (14 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListLiveRefreshTest.kt` (16 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/FollowMailChangesTest.kt` (2 tests, Robolectric + Compose)
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardMailRefreshTest.kt` (8 tests)

No existing test outside these files was changed. No test was weakened, skipped or disabled.

## Tests added or updated

Count: 2 added in this round (47 task tests in total).

- `MailListLiveRefreshTest."an event while a newly opened folder loads reloads that folder once it ends, not the unified inbox"`: a `MAIL_FOLDER` event during the first load of a folder just selected starts no second load and, once that load ends, reloads exactly that folder once (the deferred reload follows the mailbox shown, AC2 "current mailbox"; fixer CR-3).
- `DashboardMailRefreshTest."a refresh during an event reload drops the event reload's older answer"`: a manual refresh while an Inbox event reload is still waiting cancels it, so its older answer does not overwrite the count the refresh loaded (fixer change to `refresh()`, which now cancels `mailJob`).

The fixer's own tests already cover the other changed behavior: the Inbox widget reload (TW-1), the separate first-page job (CR-2: `aRefreshAfterLoadMoreDropsTheAnswerOfAnEarlierEventReload`, "an event reload outrun by load more and a folder switch does not overwrite the new folder") and the deferred reload (CR-3: "an event during load more ...", "an event during a running load ... reloads once it ends", "an event during an event reload reloads the count once more afterwards"). I read those tests against the final code; they assert the intended behavior and needed no change.

All task tests use virtual time (`runTest`, `StandardTestDispatcher`/`UnconfinedTestDispatcher`, `advanceTimeBy`) and `CompletableDeferred` for answer order; no real delays. Data is fictitious (`me@example.org`, "Anna", generated UUIDs).

## Requirement coverage

Command F (focused): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.dashboard.*' --tests 'ch.sonensei.cockpit.gtd.data.*'` (run as `android/gradlew -p android ...` from the clone root).
Command A (full): `cd android && ./gradlew testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: an `EMAIL`/`MAIL_FOLDER` event or a reconnect reloads the New view's first mail page, new mail at the top, filter All and Mail | `NewMailLiveRefreshTest.anEmailEventShowsTheNewMailAtTheTopWithFilterAll`, `aMailFolderEventShowsTheNewMailAtTheTopWithFilterMail`, `aReconnectReloadsTheMailBecauseMissedEventsAreNotReplayed` | F, A | Pass |
| AC1: pages loaded behind the first stay (scroll position kept) | `NewMailLiveRefreshTest.anEventReloadKeepsThePagesLoadedBehindTheFirst`, `MailListLiveRefreshTest.an email event shows the new mail at the top of the unified inbox` | F, A | Pass |
| AC1: a load in progress is not started twice; an event during it reloads once afterwards | `NewMailLiveRefreshTest.anEventDuringARunningLoadDoesNotStartASecondOneButReloadsOnceItEnds` | F, A | Pass |
| AC1: an event during paging still reloads the newest mails; a later refresh drops an older event reload's answer | `NewMailLiveRefreshTest.anEventDuringALoadMoreStillReloadsTheNewestMails`, `aRefreshAfterLoadMoreDropsTheAnswerOfAnEarlierEventReload` | F, A | Pass |
| AC1: one shared stream; only mail types and Reconnected trigger | `MailRefreshTriggersTest` (7 tests) | F, A | Pass |
| AC2: Mail list reloads the current mailbox (unified inbox, a folder) on events and on a reconnect | `MailListLiveRefreshTest`: `an email event shows the new mail at the top of the unified inbox`, `a folder change reloads the folder shown, not the unified inbox`, `a reconnect reloads the list`, `an event while a newly opened folder loads reloads that folder once it ends, not the unified inbox` (new) | F, A | Pass |
| AC2: Mail list running load not started twice; event during load more; an older reload never writes into a newly opened folder | `MailListLiveRefreshTest`: `an event during a running load does not start a second one but reloads once it ends`, `an event during load more still reloads the first page`, `an event reload outrun by load more and a folder switch does not overwrite the new folder` | F, A | Pass |
| AC2: Mail list search results are not reloaded; selection and action messages kept | `MailListLiveRefreshTest`: `the open search is not reloaded while the mailbox behind it is`, `an event reload keeps the selection of mails still listed`, `a successful event reload ends an outdated message but keeps an action's message` | F, A | Pass |
| AC2: the dashboard's unread-mail count (Inbox widget) updates on a mail event | `DashboardMailRefreshTest.an email event updates the unread mail count` | F, A | Pass (TW-1 fixed) |
| AC2: dashboard: no second load while loading, one follow-up; event during an event reload; refresh drops an older event reload; no Inbox widget, no load | `DashboardMailRefreshTest`: `an event while the dashboard loads starts no second load but reloads the count once it ends`, `an event during an event reload reloads the count once more afterwards`, `a refresh during an event reload drops the event reload's older answer` (new), `a dashboard without mail widgets loads nothing on a mail event` | F, A | Pass |
| AC3: a burst gives one reload (New, Mail list, Dashboard, trigger) | `NewMailLiveRefreshTest.aBurstOfMailEventsGivesOneReload`, `MailListLiveRefreshTest.a burst of mail events gives one reload`, `DashboardMailRefreshTest.a burst of mail events gives at most one widget reload`, `MailRefreshTriggersTest.a sync bringing many mails fires once, after the last of them` | F, A | Pass |
| AC3: reload after the quiet time only | `NewMailLiveRefreshTest.theMailReloadsOnlyAfterTheQuietTime`, `MailListLiveRefreshTest.the list reloads only after the quiet time`, `MailRefreshTriggersTest.a new mail fires once the quiet time has passed, not before` | F, A | Pass |
| AC3: events reload only while the screen is shown; nothing in the background | `FollowMailChangesTest` (2 tests), `NewMailLiveRefreshTest.noEventReloadsOnceTheScreenStoppedFollowing`, `MailListLiveRefreshTest.no event reloads once the screen stopped following` | F, A | Pass |
| AC4: an offline or failed event reload keeps what is shown, no notice, no loading indicator | `NewMailLiveRefreshTest.anOfflineOrFailedEventReloadKeepsTheMailsAndRaisesNoBanner`, `anEventReloadShowsNoLoadingIndicatorWhileItRuns`, `aSuccessfulEventReloadEndsAnEarlierFailureBanner`; `MailListLiveRefreshTest.an offline or failed event reload keeps the list and shows no message`, `an event reload shows no loading indicator while it runs`; `DashboardMailRefreshTest.a failed event reload keeps the unread mail count and shows no error` | F, A | Pass |
| AC5: a `TASK` signal does not reload mail (New, Mail list, Dashboard) | `NewMailLiveRefreshTest.aTaskEventDoesNotReloadTheMail`, `MailListLiveRefreshTest.a task event does not reload the list`, `DashboardMailRefreshTest.a task event reloads no widget` | F, A | Pass |
| AC6: manual check on an emulator or device | none | none | Not testable here (no emulator, device or mail delivery; the parent already skipped it) |
| AC7: `ktlintCheck testDebugUnitTest lintDebug` | whole Android unit suite | F (ktlintCheck), A | Pass |
| AC7: `sh scripts/check.sh` | none | none | Not run: the parent runs `.taskcheck` |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.dashboard.*' --tests 'ch.sonensei.cockpit.gtd.data.*'`

Result:

Pass. BUILD SUCCESSFUL, exit 0: 76 suites, 762 tests, 0 failures, 0 errors, 0 skipped (JUnit XML in `android/app/build/test-results/testDebugUnitTest/`). DashboardMailRefreshTest 8/8, MailRefreshTriggersTest 7/7, FollowMailChangesTest 2/2, MailListLiveRefreshTest 16/16, NewMailLiveRefreshTest 14/14. ktlintCheck clean, including the two edited test files.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run as `android/gradlew -p android ... --console=plain -q`)

Result:

Pass. Exit 0, 2026-11-14 20:49:32 to 20:54:12 +01:00: 459 suites, 5595 tests, 0 failures, 0 errors, 0 skipped; lintDebug passed (no error output, `android/app/build/reports/lint-results-debug.xml` written 20:54:04).

## .taskcheck

Result:

Not run (`sh scripts/check.sh` is run by the parent before `done`). Its Android part (ktlint, unit tests, lint) passed in the two runs above.

## Bugs found

None in the final code. TW-1 from round 1 (dashboard mail events reloaded the Today widget instead of the Inbox widget with the unread-mail count) is fixed in `094d34bda`; its two tests pass now.

## Coverage

Status: verified (fresh full-suite run, exit 0, on the final production code).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Executable lines in the JaCoCo XML (`line nr`, `ci > 0` = covered) intersected with the added lines of `git diff -U0 2c363a763 094d34bda` per file: **125/126 changed executable lines covered**. Branches on those lines: 92/96 (JaCoCo `cb`/`mb` on changed lines; not changed-branch coverage in the strict sense).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-live-refresh; base `2c363a763cb34a2fce40ad68afafa0778036c597` (from the parent; `fef29f976`, the claim commit, lies between it and the task commits and touches only `tasks/`); production revision `094d34bda9ab79d99b44ee89af7614050f43af4a` (HEAD at run start; HEAD later `52fa95d17`, which changes only `reviews/.../review.md`). Task-owned production commits `ee50ac07f` and `094d34bda`. Scope = `git diff --name-only 2c363a763..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): production at `094d34bda`, plus the two edited test files with the sha256 hashes listed under "Test files changed". The hashes were the same before (20:49:32) and after (20:54:12) the run; `git status` showed no other source change.
- Exact included files (all under `android/app/src/main/java/ch/sonensei/cockpit/`): `dashboard/DashboardScreen.kt`, `dashboard/DashboardViewModel.kt`, `gtd/data/GtdRefresh.kt`, `mail/list/FollowMailChanges.kt` (new), `mail/list/MailListViewModel.kt`, `mail/list/MailScreen.kt`, `newitems/NewScreen.kt`, `newitems/NewViewModel.kt`. No renames, deletions or exclusions; all 8 are in the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`), Gradle 9.8.1, JDK 21; `android/gradlew -p android testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` from the clone root; the whole debug unit suite (no filter).
- Run start/completion time and exit status: 2026-11-14T20:49:32+01:00 to 20:54:12+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-14 20:54:08, sha256 `bf0c1ca174468aab5da4430db5cd691a3fed819ff84516f1a147709a4ca5dc01`. Counters extracted with `.tmp/cov_and_mail_live_refresh.py` (Git-ignored scratch file).
- Evidence outputs are fresh and inputs stayed unchanged: yes (report written inside the run window; test hashes unchanged; no production change during the run).
- Final run after latest test additions and production fixes: yes. It includes the fixer's commit and both round-2 tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no statement counter; "Statements" shows JaCoCo INSTRUCTION.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `dashboard/DashboardScreen.kt` | 5315/5394 (98.5%) | 219/244 (89.8%) | 111/115 (96.5%) | 425/429 (99.1%) |
| `dashboard/DashboardViewModel.kt` | 1351/1365 (99.0%) | 87/92 (94.6%) | 53/53 (100.0%) | 131/131 (100.0%) |
| `gtd/data/GtdRefresh.kt` | 569/573 (99.3%) | 21/22 (95.5%) | 22/22 (100.0%) | 93/93 (100.0%) |
| `mail/list/FollowMailChanges.kt` | 96/99 (97.0%) | N/A (0 branches) | 3/3 (100.0%) | 6/7 (85.7%) |
| `mail/list/MailListViewModel.kt` | 5329/5403 (98.6%) | 434/456 (95.2%) | 132/132 (100.0%) | 503/503 (100.0%) |
| `mail/list/MailScreen.kt` | 8866/9095 (97.5%) | 324/368 (88.0%) | 181/216 (83.8%) | 707/716 (98.7%) |
| `newitems/NewScreen.kt` | 3902/4048 (96.4%) | 139/159 (87.4%) | 68/79 (86.1%) | 294/298 (98.7%) |
| `newitems/NewViewModel.kt` | 1651/1655 (99.8%) | 113/114 (99.1%) | 65/65 (100.0%) | 192/192 (100.0%) |
| **Aggregate (summed counters, 8 of 8 files, none excluded or missing)** | 27079/27632 (98.0%) | 1337/1455 (91.9%) | 635/685 (92.7%) | 2351/2369 (99.2%) |

Whole-file numbers include old code in the screens (most of their misses are pre-existing UI branches outside this task).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail/list/MailListViewModel.kt:766` (1 of 2 branches: the answer for a mailbox no longer shown is dropped) | CR-2: an older first-page answer must not land in a newly selected mailbox | Behavior covered by "an event reload outrun by load more and a folder switch does not overwrite the new folder", but there the cancellation of `firstPageJob` already stops the stale answer, so the guard itself never runs. Only `select()` changes the mailbox and it always cancels the running first-page job first, so the guard is a defensive second line not reachable from the public API. Left uncovered. |
| `newitems/NewViewModel.kt:174-176`, `mail/list/MailListViewModel.kt:233-235`, `dashboard/DashboardViewModel.kt:141-142,147` (1-2 instructions each; 1 branch on 175/234/142) | none material | The missed instructions are the completion path of `collect` (the trigger flow never completes in production). The missed branch is the null job (an event before any load), which cannot happen because the screen's resume refresh runs first. Left uncovered. |
| `mail/list/FollowMailChanges.kt:24` (3 instructions) | none | Closing brace of the `LaunchedEffect` lambda after `repeatOnLifecycle` returns, which only happens at DESTROYED. The lifecycle gating is covered by `FollowMailChangesTest`. |
| `dashboard/DashboardScreen.kt:167`, `mail/list/MailScreen.kt:158` (3 instructions each) | none | Same completion path of the `follow` lambda passed to `FollowMailChanges`. |
| `dashboard/DashboardViewModel.kt:215,222,223` (layout save) | outside this task | Pre-existing layout-save code, not changed by the task. |

## Unable to test

- AC6, the live behavior on a device against a running server (new mail delivered, it appears in the open New view): no emulator, device or mail delivery here. The SSE path to the shared `GtdChangeStream` is assumed (recorded by the parent); the ViewModel and lifecycle logic is unit-tested with a fake signal flow. Follow-up: a human check on a device.
- `sh scripts/check.sh` was not run by me; the parent runs it.

## Summary

Tests added and all validation passed. The fixer's changes (Inbox widget reload, separate first-page job, deferred reload) are covered by the fixer's tests plus 2 round-2 tests; no production defects found in the final code. Note for the parent: the 2 round-2 test additions are test-only changes made after the round-2 review started.
