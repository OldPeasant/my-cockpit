# Test Report

Date: 2026-11-09

Task: cal-invitation-respond-android (answer invitations in the Android event editor). This is a re-validation on the final code (b663ca0dc). Since the previous report (written at 66f95e5dc, before a rebase), only KDoc comments changed (CR-1 in `fixes.md`).

Scope: immutable base 0d79924c8cfdc1218629b425f6aa284dc77fcc21 (`git merge-base HEAD origin/main`). The task commits are the ones with the task id in their message: 81324b87b (implementation), a501d1597 (review), b4c468cea (tests), 403feb486 (CR-1/CR-2 fixes), e22f5685d (re-review), 3dd6114fd (more tests, test report) and b663ca0dc (KDoc fix, CR-1). Measured HEAD: b663ca0dc567e0174ba4aae42606fd12245d616b. The working tree was clean (no uncommitted inputs). `git diff 66f95e5dc..HEAD -- android/` changes only two KDoc blocks in `EventEditorViewModel.kt` (`EditorMessage.RESPOND_FAILED` and `STALE`). They add 3 lines, so line numbers below the first block moved by +3. Behavior is unchanged.
Changed production files (`git diff --name-only 0d79924c8..HEAD -- android/app/src/main`): `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/{CalendarWithEditor,EventEditRepository,EventEditorScreen,EventEditorSections,EventEditorViewModel,EventExtrasRepository}.kt`, `android/app/src/main/res/values{,-de}/strings_calendar_edit.xml`.

Requirements:
- `tasks/cal-invitation-respond-android.md` (3 acceptance criteria, plus the assumption that the same rules apply as in the web subtask)
- `reviews/cal-invitation-respond-android/review.md` and `fixes.md` (the behavior fixed for CR-1 and CR-2)
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/`

## Test files changed

- None in this run. The task's test files are all committed: `EventEditRepositoryTest.kt`, `EventEditorExtrasScreenTest.kt`, `EventEditorViewModelExtrasTest.kt`, `EventEditorViewModelRespondTest.kt` and `EventExtrasRepositoryTest.kt` under `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/`.

## Tests added or updated

Count: 0 in this run. A comment-only change needs no new tests, and coverage found no new gap. The task still has its 26 tests from the earlier runs.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Accept / Maybe / Decline appear only for existing events with guests in Google/Microsoft calendars | `an existing event with guests in a Google or Microsoft calendar can be answered...`, `a local event, one without guests or a new one can't be answered`, `while the invitation calendars are unknown or failed to load nothing can be answered`, `no answers are offered for a local calendar, a new event or one without guests` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' -q` | Pass |
| AC1: the current status is shown | `the current answer is named and selected`, `an unanswered invitation offers accept, maybe and decline...`, `a delegated or unknown own answer counts as not answered...`, `without a matching own address there is no current answer...` | same | Pass |
| AC1: sending an answer updates the status (version and guests updated, unsaved edits kept) | `an answer is sent with the event version, then the guests and version are updated and unsaved changes stay`, `only accept, maybe and decline are sent`, `while an answer is sent nothing else can be answered or saved`, the respond tests in EventEditRepositoryTest | same | Pass |
| AC1: error mapping (offline, 412 conflict, 404 gone, other refusals) | `no connection shows offline...`, `a stale version is a conflict...`, `a missing event is gone`, `other refusals, like not invited or organizer, report that the answer failed...`, `the answer to a closed editor never shows in the next event`, `a reload while an answer is sent drops the answer's result` | same | Pass |
| CR-1: neutral refusal text with a Reload action | `a refused answer is explained` | same | Pass |
| CR-2: Retry/Reload fetches the invitation calendars and own addresses again | `a retry after the sources failed to load fetches them again, so the invitation can be answered`, `a reload of a new event neither fetches the sources again nor drops the form` | same | Pass |
| AC2: strings in English and German | `CalendarEditStringsTest` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport -q` | Pass (lint `MissingTranslation` / `lintDebug` was not rerun: no resource changed since the last run, where it passed) |
| AC3: unit tests cover the new behavior; `sh scripts/check.sh` passes | the whole Android unit suite | `cd android && ./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport -q` | Fail: 4871/4872 pass. The one failure is outside the task (see "Full test execution"). ktlintCheck passed. check.sh: Not run, left to the parent |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' -q`

Result:

Pass (exit 0)

Also: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport -q` gave Pass (exit 0).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport -q`

Result:

Fail, twice (about 4m35s each, run 2026-11-08 23:4x to 2026-11-09 00:05 +0100): 4872 tests, 1 failed. ktlintCheck passed (it ran before the tests, and the build stopped at `:app:testDebugUnitTest`).
The failing test is `ch.sonensei.cockpit.auth.AppStartRoutingTest > logging out from settings clears the cached files`: `ComposeTimeoutException: Condition still not satisfied after 5000 ms` at `AppStartRoutingTest.kt:167` (`waitUntil(WAIT_MS) { !cached.exists() }`). It also failed when run alone (`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AppStartRoutingTest' -q`, 9 tests, 1 failed).
This is the same test that was flaky in the previous run, where it failed and passed on identical code. The task changes no auth, settings, logout or cache code: all its production files are in `calendar/edit` and the two calendar-edit string files. The code changed since the last full pass is only KDoc. So this is a timing-dependent test outside the task, made worse by machine load (other agents run builds in parallel). I did not check it on the base commit, because that would need a checkout or worktree, which is outside my authority. It will probably also fail `.taskcheck` (it runs `testDebugUnitTest`). The parent should rerun it, or a follow-up task could make it stable (a longer wait, or waiting on the logout's file deletion directly).

## .taskcheck

Result:

Not run (left to the parent worker). Its Android unit-test step will probably hit the auth test failure described above.

## Bugs found

None in the task's code.

## Coverage

Status: verified for the task's files, with a reduced test selection. The full-suite coverage run could not finish because the unrelated auth test failed, so `createDebugUnitTestCoverageReport` was blocked. Coverage was measured from a fresh run of all `ch.sonensei.cockpit.calendar.*` tests, which passed. Tests outside `calendar` can only add hits, so these numbers are a lower bound. They match the previous full-suite numbers exactly.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. JaCoCo XML `<line>` entries with `ci>0` were intersected with the added lines of `git diff -U0 0d79924c8..HEAD` for each file (script `.tmp/tw-respond-cov.py`, Git-ignored). Lines JaCoCo doesn't count as executable are left out. Result: 112/112 changed executable lines covered (100%). Branches on changed lines: 95/103 (92.2%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitation-respond-android; base 0d79924c8cfdc1218629b425f6aa284dc77fcc21; HEAD b663ca0dc567e0174ba4aae42606fd12245d616b; the commits come from the task id in their message, as the parent said (listed in Scope)
- Source/test snapshot identifier (including uncommitted inputs): HEAD b663ca0dc with a clean working tree (`git status --short` was empty before and after the runs)
- Exact included files; renamed/deleted/excluded files and reasons: the 6 Kotlin files above. The 2 string XML files are excluded because resources are not instrumented; CalendarEditStringsTest covers them. No files were renamed or deleted
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport -q` in `android/`; all calendar unit tests
- Run start/completion time and exit status: finished 2026-11-09 00:06:45 +0100, exit 0
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-09 00:06:45 +0100, sha256 3e41c27963af25851d99c8edf34f875ae6a524c55e8382146b1ff1940ccb8b8e)
- Evidence outputs are fresh and inputs stayed unchanged: yes. This run wrote the report (new hash and mtime), on a clean tree at b663ca0dc
- Final run after latest test additions and production fixes: yes (covers the final revision b663ca0dc)

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `CalendarWithEditor.kt` | 1239/1265 (97.9%) | 21/24 (87.5%) | 10/11 (90.9%) | 57/57 (100%) |
| `EventEditRepository.kt` | 841/857 (98.1%) | 51/56 (91.1%) | 42/43 (97.7%) | 111/111 (100%) |
| `EventEditorScreen.kt` | 4545/4731 (96.1%) | 91/97 (93.8%) | 122/155 (78.7%) | 311/313 (99.4%) |
| `EventEditorSections.kt` | 5865/5933 (98.9%) | 204/218 (93.6%) | 112/118 (94.9%) | 437/438 (99.8%) |
| `EventEditorViewModel.kt` | 4797/4919 (97.5%) | 402/464 (86.6%) | 100/101 (99.0%) | 462/462 (100%) |
| `EventExtrasRepository.kt` | 890/900 (98.9%) | 34/36 (94.4%) | 38/40 (95.0%) | 80/80 (100%) |
| **Aggregate (summed counters, 6 files; string XML excluded)** | 18177/18605 (97.7%) | 803/895 (89.7%) | 424/468 (90.6%) | 1458/1461 (99.8%) |

All paths are under `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/`. The file-level numbers include code that existed before the task in those files.

Partial branches on changed lines (line numbers at b663ca0dc):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditorViewModel.kt:155` (`form != null` is false while not creating) | defensive: an open edited event always has a form | Justified gap |
| `EventEditorViewModel.kt:163,164,166` (`canRespond` is false because of loading / saving / an open invite dialog) | no answer can be sent during other work | Partly covered: the tests check the blocks while an answer is sent and during a conflict. Loading, saving and the invite dialog are plain boolean conjuncts, so the risk is low and no test was added |
| `EventEditorViewModel.kt:646` (`respond` with no occurrence) | defensive: `canRespond` is already false for new events | Justified gap |
| `EventEditorViewModel.kt:655,659` (`initial?` / `it.form?` are null after an answer) | defensive null-safety after the editor closes. `the answer to a closed editor never shows in the next event` covers the closed-editor case | Justified gap |
| `EventEditorSections.kt:304` (1 of 2 branches of `answer == current`) | `the current answer is named and selected` draws both button kinds | Probably a branch in the Compose bytecode, not a missing behavior; no test added |

## Unable to test

- I did not run `sh scripts/check.sh` / `.taskcheck`; the parent runs it.
- I did not rerun `lintDebug` here. No resource or code behavior changed since the previous run, where it passed.
- The real server round trip (`POST /api/v1/calendar/events/{id}/respond` against the server) is outside these unit tests. EventEditRepositoryTest covers the request shape with a mock HTTP engine. The criteria name only unit tests.
- The full Android suite is red because of `auth.AppStartRoutingTest > logging out from settings clears the cached files`, a test outside the task (see "Full test execution"). It needs a rerun or a fix in another task.

## Summary

The final revision changes only KDoc comments. The calendar.edit tests and ktlintCheck pass. Task-scoped coverage is unchanged (112/112 changed executable lines; 95/103 changed branches). No production defects were found in the task. Validation is still blocked by an unrelated test outside the task: `auth.AppStartRoutingTest > logging out from settings clears the cached files` failed in both full runs and when run alone. The parent must rerun it or get it fixed before `.taskcheck` can pass.
