# Test Report

Date: 2026-11-14

Task: and-mail-live-refresh - Android: mail views reload when new mail syncs (New, Mail list, Today), not only on resume

Scope: base `fef29f97665731ff3675310fe7d8338ca46b762f` .. measured HEAD `43fdac8fb4dce6310a36d9b1759efd7aa465c36e` (branch main). Task-owned production commit `31835c3a8` (the rebased form of `e3b261d19` named by the parent; `git diff --stat e3b261d19 HEAD -- android` is empty, so the Android code is identical). The other commits in the range touch only `tasks/` and `reviews/`. Uncommitted inputs: only the 5 new test files listed below (untracked).

Requirements:
- `tasks/and-mail-live-refresh.md` acceptance criteria 1 to 5 (criteria 6 and 7 are a manual device check and the full check, which the parent owns)
- Task Goal section (the cause names `DashboardRepository` and the dashboard's unread-mail count)
- Parent instructions (test dirs, virtual time, fakes to reuse)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/MailRefreshTriggersTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewMailLiveRefreshTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListLiveRefreshTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/FollowMailChangesTest.kt` (new, Robolectric + Compose)
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardMailRefreshTest.kt` (new)

No existing test was changed. Note: `gtd/data` test files are outside the task's `touches` list, but the parent named that directory and the file is new, so it conflicts with nothing.

## Tests added or updated

Count: 40 (7 + 12 + 13 + 2 + 6)

All of them use virtual time (`runTest`, `StandardTestDispatcher`/`UnconfinedTestDispatcher`, `advanceTimeBy`). Signals come from a `MutableSharedFlow` of `ChangeSignal` passed to the `internal` constructor `MailRefreshTriggers(signals, 1_000)`. No test uses real delays.

## Requirement coverage

Focused command F: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.MailRefreshTriggersTest' --tests 'ch.sonensei.cockpit.newitems.NewMailLiveRefreshTest' --tests 'ch.sonensei.cockpit.mail.list.MailListLiveRefreshTest' --tests 'ch.sonensei.cockpit.dashboard.DashboardMailRefreshTest'`, plus `--tests 'ch.sonensei.cockpit.mail.list.FollowMailChangesTest'` run separately.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: an `EMAIL`/`MAIL_FOLDER` event or a reconnect reloads the New view's first mail page, new mail at the top, for filter All and Mail | `NewMailLiveRefreshTest.anEmailEventShowsTheNewMailAtTheTopWithFilterAll`, `aMailFolderEventShowsTheNewMailAtTheTopWithFilterMail`, `aReconnectReloadsTheMailBecauseMissedEventsAreNotReplayed` | F | Pass |
| AC1: pages loaded behind the first stay | `NewMailLiveRefreshTest.anEventReloadKeepsThePagesLoadedBehindTheFirst` | F | Pass |
| AC1: a load in progress is not started twice | `NewMailLiveRefreshTest.anEventDuringARunningLoadDoesNotStartASecondOne` | F | Pass |
| AC1: one shared stream; only mail types and Reconnected trigger; first Connected does not | `MailRefreshTriggersTest` (7 tests: quiet time, MAIL_FOLDER + Reconnected, GTD types + Connected ignored, burst, two separate syncs, GTD event does not extend the quiet time, `NONE` never fires) | F | Pass |
| AC2: Mail list reloads the current mailbox (unified inbox and a folder) on events and on a reconnect | `MailListLiveRefreshTest`: `an email event shows the new mail at the top of the unified inbox`, `a folder change reloads the folder shown, not the unified inbox`, `a reconnect reloads the list` | F | Pass |
| AC2: Mail list search results are not reloaded | `MailListLiveRefreshTest.the open search is not reloaded while the mailbox behind it is` | F | Pass |
| AC2: Mail list keeps the selection and action messages; a load in progress is not started twice | `MailListLiveRefreshTest`: `an event reload keeps the selection of mails still listed`, `a successful event reload ends an outdated message but keeps an action's message`, `an event during a running load does not start a second one` | F | Pass |
| AC2: the dashboard's unread-mail count updates on a mail event | `DashboardMailRefreshTest.an email event updates the unread mail count` | F | **Fail: TW-1** |
| AC2: dashboard without the mail widget, or while it loads, starts nothing | `DashboardMailRefreshTest`: `a dashboard without mail widgets loads nothing on a mail event`, `an event while the dashboard loads starts no second load` | F | Pass |
| AC3: a burst gives one reload (New, Mail list, Dashboard) | `NewMailLiveRefreshTest.aBurstOfMailEventsGivesOneReload`, `MailListLiveRefreshTest.a burst of mail events gives one reload`, `DashboardMailRefreshTest.a burst of mail events gives at most one widget reload`, `MailRefreshTriggersTest.a sync bringing many mails fires once, after the last of them` | F | Pass |
| AC3: the reload waits for the quiet time | `NewMailLiveRefreshTest.theMailReloadsOnlyAfterTheQuietTime`, `MailListLiveRefreshTest.the list reloads only after the quiet time`, `MailRefreshTriggersTest.a new mail fires once the quiet time has passed, not before` | F | Pass |
| AC3: events reload only while the screen is shown (lifecycle), nothing in the background | `FollowMailChangesTest` (2 tests: started/stopped/started again; not followed before STARTED), `NewMailLiveRefreshTest.noEventReloadsOnceTheScreenStoppedFollowing`, `MailListLiveRefreshTest.no event reloads once the screen stopped following` | F + FollowMailChangesTest | Pass |
| AC4: an offline or failed event reload keeps the shown mails and raises no notice (New, Mail list) | `NewMailLiveRefreshTest.anOfflineOrFailedEventReloadKeepsTheMailsAndRaisesNoBanner`, `MailListLiveRefreshTest.an offline or failed event reload keeps the list and shows no message` | F | Pass |
| AC4: no loading indicator during an event reload | `NewMailLiveRefreshTest.anEventReloadShowsNoLoadingIndicatorWhileItRuns`, `MailListLiveRefreshTest.an event reload shows no loading indicator while it runs` | F | Pass |
| AC4: a successful event reload clears an earlier stale banner | `NewMailLiveRefreshTest.aSuccessfulEventReloadEndsAnEarlierFailureBanner` (and the Mail list message test above) | F | Pass |
| AC4: a failed event reload keeps the dashboard's unread count and shows no error | `DashboardMailRefreshTest.a failed event reload keeps the unread mail count and shows no error` | F | **Fail: TW-1** (no reload of the count happens at all) |
| AC5: a `TASK` signal does not reload mail (New, Mail list, Dashboard) | `NewMailLiveRefreshTest.aTaskEventDoesNotReloadTheMail`, `MailListLiveRefreshTest.a task event does not reload the list`, `DashboardMailRefreshTest.a task event reloads no widget` | F | Pass |
| AC6 manual device check | none | none | Not testable here (no emulator or device; the parent already skipped it) |
| AC7 `ktlintCheck testDebugUnitTest lintDebug` + `check.sh` | none | see below | Not run in full: the 2 TW-1 tests fail by design until the fix |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.MailRefreshTriggersTest' --tests 'ch.sonensei.cockpit.newitems.NewMailLiveRefreshTest' --tests 'ch.sonensei.cockpit.mail.list.MailListLiveRefreshTest' --tests 'ch.sonensei.cockpit.dashboard.DashboardMailRefreshTest'`

Result:

Fail (expected, TW-1). MailRefreshTriggersTest 7/7, NewMailLiveRefreshTest 12/12, MailListLiveRefreshTest 13/13 passed. DashboardMailRefreshTest 4/6 passed. The 2 failures are TW-1.

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.FollowMailChangesTest' ktlintCheck` -> Pass (2/2, ktlint clean).

`cd android && ./gradlew ktlintCheck` -> Pass (after one wrapping fix in a new test file).

## Full test execution

Command (affected packages, all their existing tests plus the new ones, without the 2 known TW-1 failures):

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.gtd.data.*' --tests` (each of the 15 other `ch.sonensei.cockpit.dashboard.Dashboard*Test` classes by name) `--tests '*DashboardMailRefreshTest.a task*' --tests '*DashboardMailRefreshTest.a burst*' --tests '*DashboardMailRefreshTest.an event while*' --tests '*DashboardMailRefreshTest.a dashboard without*' createDebugUnitTestCoverageReport`

Result:

Pass: 77 suites, 753 tests, 0 failures, 0 errors (exit 0). That includes the existing NewViewModelTest (44), MailListViewModelTest, MailListViewModelEdgeCasesTest, DashboardViewModelTest, NewRouteTest, MailRouteInAppTest, DashboardRouteInAppTest and GtdRefreshTriggersTest. The whole Android unit suite (all packages) was not run: it would fail on the 2 TW-1 tests, and the parent runs it with `.taskcheck` after the fix.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs the full Android unit suite, which fails on the 2 TW-1 tests until the production fix. The parent must run it after the fix.

## Bugs found

### Dashboard mail events reload the Today widget, not the unread-mail count

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.dashboard.DashboardMailRefreshTest."an email event updates the unread mail count"` and `"a failed event reload keeps the unread mail count and shows no error"`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt:136-140` (`followMailChanges` checks `WidgetId.TODAY in widgets` and reloads `dashboard.load(WidgetId.TODAY)`)

Requirement:

AC2: "Same for ... the Today dashboard's unread-mail count". Task Goal: the dashboard's unread-mail count (`DashboardRepository`) is loaded only on resume.

Expected behavior:

A debounced `EMAIL`/`MAIL_FOLDER`/Reconnected signal reloads the widget that carries the unread-mail count. That is `WidgetId.INBOX`: `WidgetData.Inbox.unreadMail`, built in `DashboardRepository.inbox()` (`DashboardRepository.kt:330-341`) and rendered in `DashboardScreen.kt:591-594` (`R.plurals.dashboard_inbox_mail`). The reload is skipped when INBOX is hidden. A failed reload keeps the shown count.

Actual behavior:

The event reloads `WidgetId.TODAY`, which is `WidgetData.Today` (tasks, events, habits; no mail field, see `DashboardRepository.kt:316-328`). The unread-mail count never changes while the dashboard is shown. The test's MockK record shows `layout, load(TODAY), load(INBOX), load(TODAY)`, and the test expected a second `load(INBOX)`. The task assumption "the Today dashboard reloads only its Today widget (the one with the unread count)" is factually wrong for this code. The code review (`review.md`) repeats the same reading.

Severity:

Medium. One of the three views named in AC2 does not update. Nothing breaks, but the criterion is not met.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Most likely `WidgetId.TODAY` becomes `WidgetId.INBOX` in both places in `followMailChanges` (and the KDoc). The 4 passing DashboardMailRefreshTest tests check only behavior that holds either way (total load counts, hidden widgets, a running load), so they stay valid after the fix.

## Coverage

Status: verified (fresh run, exit 0). It excludes the 2 failing TW-1 tests, so the dashboard's failed-reload branch is measured as uncovered. See the gaps below.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Executable lines in the JaCoCo XML (`line nr`, `ci > 0` = covered) intersected with the added lines of `git diff -U0 fef29f976 43fdac8fb` per file: **54/55 changed executable lines covered**. Branches on those lines: 42/52 (JaCoCo `cb`/`mb` on the changed lines; this is not changed-branch coverage in the strict sense).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-live-refresh; base `fef29f97665731ff3675310fe7d8338ca46b762f` (from the parent); HEAD `43fdac8fb4dce6310a36d9b1759efd7aa465c36e`. The task-owned production commit `31835c3a8` equals the parent's `e3b261d19` in Android code (empty diff). Scope = `git diff --name-only fef29f976..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus untracked test files (sha256): MailRefreshTriggersTest `a3482a9d...cd0c`, NewMailLiveRefreshTest `4675067e...ae47`, MailListLiveRefreshTest `b51210b8...99b3`, FollowMailChangesTest `9070f0b6...2aae`, DashboardMailRefreshTest `4d8ca22f...2d6d`. These were the same before and after the run. No production file changed.
- Exact included files: `dashboard/DashboardScreen.kt`, `dashboard/DashboardViewModel.kt`, `gtd/data/GtdRefresh.kt`, `mail/list/FollowMailChanges.kt` (new), `mail/list/MailListViewModel.kt`, `mail/list/MailScreen.kt`, `newitems/NewScreen.kt`, `newitems/NewViewModel.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). No renames, deletions or exclusions, and every file is present in the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`); working directory `android/`; command and selection as in "Full test execution" (packages newitems, mail.list, gtd.data, all dashboard classes, and the 4 passing DashboardMailRefreshTest methods).
- Run start/completion time and exit status: 2026-11-14T20:37:43+01:00 to 20:38:41+01:00, exit 0. An earlier identical run (20:35:26 to 20:36:39) before `FollowMailChangesTest` was added is superseded.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-14 20:38:40, sha256 `eff8a0ac5719724cea0bcf31bdf18fb823d09847078a697ab77184346443a6c6`. Counters were extracted with `.tmp/cov_and_mail_live_refresh.py` (scratch file, Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was rewritten during the run (mtime inside the run window), and the test hashes were the same before and after.
- Final run after latest test additions and production fixes: yes for the test additions. No production fix exists yet: after the TW-1 fix, coverage and the tests must be run again.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no separate statement counter; "Statements" shows JaCoCo INSTRUCTION.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `dashboard/DashboardScreen.kt` | 5295/5394 (98.2%) | 218/244 (89.3%) | 111/115 (96.5%) | 424/429 (98.8%) |
| `dashboard/DashboardViewModel.kt` | 1312/1342 (97.8%) | 76/88 (86.4%) | 52/52 (100.0%) | 122/122 (100.0%) |
| `gtd/data/GtdRefresh.kt` | 569/573 (99.3%) | 21/22 (95.5%) | 22/22 (100.0%) | 93/93 (100.0%) |
| `mail/list/FollowMailChanges.kt` | 96/99 (97.0%) | N/A (0 branches) | 3/3 (100.0%) | 6/7 (85.7%) |
| `mail/list/MailListViewModel.kt` | 5247/5319 (98.6%) | 427/448 (95.3%) | 131/131 (100.0%) | 492/492 (100.0%) |
| `mail/list/MailScreen.kt` | 8750/9095 (96.2%) | 319/368 (86.7%) | 177/216 (81.9%) | 705/716 (98.5%) |
| `newitems/NewScreen.kt` | 3880/4048 (95.8%) | 137/159 (86.2%) | 68/79 (86.1%) | 293/298 (98.3%) |
| `newitems/NewViewModel.kt` | 1611/1615 (99.8%) | 107/108 (99.1%) | 64/64 (100.0%) | 183/183 (100.0%) |
| **Aggregate (summed counters, 8 of 8 files, none excluded or missing)** | 26760/27485 (97.4%) | 1305/1437 (90.8%) | 628/682 (92.1%) | 2318/2340 (99.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `dashboard/DashboardViewModel.kt:139` (failed `as? DashboardResult.Ok ?: return@launch` branch), `:135` (5 of 12 branches: `loadJob`/`mailJob` null or active combinations), `:105` (`mailJob?.cancel()` with a job) | AC4 for the dashboard; skipping while a mail reload runs | The failure branch is covered by the TW-1 test `a failed event reload keeps the unread mail count and shows no error`, which is excluded from this run because it fails. The test should be measured again after the fix. The remaining combinations (a second event during a still-running mail reload, a refresh cancelling one) are minor and left uncovered. |
| `mail/list/FollowMailChanges.kt:24` (3 instructions) | none | This is the closing brace of the `LaunchedEffect` lambda after `repeatOnLifecycle` returns, which happens only when the lifecycle is DESTROYED. The class that calls `follow` (`FollowMailChangesKt$FollowMailChanges$1$1$1`) is 12/12 covered by `FollowMailChangesTest`, which was added for this gap. |
| `newitems/NewViewModel.kt:165-167`, `mail/list/MailListViewModel.kt:224-226`, `dashboard/DashboardViewModel.kt:134,142` (1-2 instructions each; 1 branch on 166/225) | none material | The missed instructions are the completion path of `collect` (the trigger flow never completes in production). The missed branch is `loadJob == null` (an event before any load, which cannot happen because the screen's resume refresh runs first). Left uncovered. |
| `DashboardScreen.kt:167`, `MailScreen.kt:158` (3 instructions each) | none | Same completion path of the `follow` lambda passed to `FollowMailChanges`, inside composables. The lifecycle gating itself is covered by `FollowMailChangesTest`. |

## Unable to test

- AC6: delivering a real mail and seeing it appear on an emulator or device. No emulator, device or mail server delivery is available here. The parent has already skipped it with a reason.
- The real Hilt wiring of `MailRefreshTriggers(GtdChangeStream)` against the live SSE endpoint was not exercised end to end. It compiles (`hiltJavaCompileDebug`), and the existing in-app Robolectric tests of the three screens pass. That the server's `EMAIL`/`MAIL_FOLDER` events reach the stream is the parent's recorded assumption, not something these tests verify.
- AC7 (`check.sh`, full `testDebugUnitTest`, `lintDebug`): not run, because the 2 TW-1 tests fail until the fix. Follow-up: the parent runs it after the fixer.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
