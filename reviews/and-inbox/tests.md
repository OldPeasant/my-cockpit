# Test Report

Date: 2026-10-21

Task: and-inbox - Android inbox processing

Scope: task commits `f30fca1b4a52d8a040d3eb6dfcd2bb4a1b9b18d9..ec920897ba95669b650cc8efc06c6f703dc00812`
(78109e57..ec920897, 10 commits on `main`; the base and commit list come from the parent worker and were checked against
`git log f30fca1b..HEAD`). This is the second run, on the final code after the TW-1 fix (ec920897). The first run
measured the same task before a rebase, as `39be79a5..d2f7717f`. Production files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/*.kt` and
`android/app/src/main/res/values/strings_inbox.xml`. Tests: `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/`.
The working tree was clean at HEAD ec920897 before this run, except for the test added below. The only other
uncommitted file, `reviews/and-inbox/review.md`, is another helper's report, and this run did not touch it.

Requirements:
- `tasks/and-inbox.md` acceptance criteria and recorded assumptions (actions shown: Do it, Next, To project, Waiting,
  Schedule, Tickler, Someday, Trash; hand-written PATCH; 'To project' sets the next step of a stalled project, and a
  failed next-step call still counts as processed; placeholder outside a Hilt activity)
- SPEC.md §5.3 (inbox processing), docs/ARCHITECTURE.md "Coverage"
- `reviews/and-inbox/review.md` and `fixes.md` (CR-1 retry, CR-2 stale/double-tap guard, CR-4 reuse of the created
  project by title, TW-1 `DateTimeException` in `makeNextStepIfStalled`)

## Test files changed

First run (committed in a0c32f7b):
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRepositoryTest.kt` (5 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModelTest.kt` (2 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRouteTest.kt` (1 test added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxGraphTest.kt` (new, 2 tests)

This run (uncommitted):
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRepositoryTest.kt` (1 test added)

## Tests added or updated

Count: 1 added in this run (11 over both runs; 101 inbox tests in total)

Added in this run:
- InboxRepositoryTest `a next-step answer with an unreadable due date still counts as processed`. The project is
  stalled, so the next step is set, but the server's answer to `POST /projects/{id}/next-step` has
  `"dueDate":"someday"`. Two paths had no test yet: an unreadable answer from the *second* call in
  `makeNextStepIfStalled`, and a malformed `LocalDate` field. The first report said the TW-1 defect also applied to a
  malformed `LocalDate`, so this test checks that the fix covers it too. The test checks that the result is
  `Processed`, that the next-step request carried the item's id, and that the views are not offline.

TW-1 fix check: the fix (ec920897) adds `catch (_: DateTimeException) {}` to `makeNextStepIfStalled`
(`InboxRepository.kt:179`). `DateTimeParseException` is a subclass, so this catch covers both the
`OffsetDateTimeAdapter` and the `LocalDateAdapter` of the generated client. The TW-1 regression test
`a project answer with an unreadable date after the move still counts as processed` now passes. The new test passes
too. `CancellationException` is still rethrown first. I found no other unchecked exception the generated client can
throw while decoding a project. Malformed JSON and missing fields raise `SerializationException`. A malformed UUID
raises `IllegalArgumentException`. A malformed enum raises `SerializationException`. Dates raise `DateTimeException`.
All four are caught.

Added in the first run (all still pass):
- InboxRepositoryTest: `a project answer with an unreadable date after the move still counts as processed` (TW-1);
  `a project answer with a malformed id after the move still counts as processed`;
  `a connection lost during the stalled check after the move still counts as processed`;
  `waiting without anyone named sends no waiting-for text`;
  `a retry with a further change patches only that change against the saved version` (CR-1)
- InboxViewModelTest: `a prompt opened on an item that left the inbox meanwhile is neither shown nor run on the next item` (CR-2);
  `inline changes made on an item that left the inbox meanwhile do not apply to the next item`
- InboxRouteTest: `outside a Hilt activity the inbox tab shows the placeholder instead of failing` (assumption)
- InboxGraphTest: `the graph provides the inbox repository with its patch client`;
  `the inbox tab of the app shows the inbox, not the placeholder` (real MainActivity + production Hilt graph)

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --rerun-tasks`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: inbox list | InboxScreenTest `the list shows the items, their count and starts processing`, `an empty inbox says so...`, `before the first load it says it is loading...` | F | Pass |
| AC1: one-at-a-time processing | InboxScreenTest `processing shows one item, how many are left, and every action`, `when everything is processed it says so`; InboxViewModelTest `the item leaves the queue while its action runs...` | F | Pass |
| AC1: same actions as web except calendar/reference | InboxScreenTest `processing shows one item...` (all 8 `InboxActionKind`s shown and enabled); InboxRepositoryTest per action (Done/complete, Next/Someday/Waiting/Schedule/Tickler move, ToProject existing/new, Trash/delete) | F | Pass |
| AC1: setting energy/creativity/contexts/estimate | InboxScreenTest `attributes are set inline`, `creativity and a chosen estimate toggle inline`; InboxViewModelTest `inline attributes are passed with the action`; InboxRepositoryTest `a changed clarification is patched first, alone...`, `clearing an attribute sends null for it`; InboxActionsTest (patch rules) | F | Pass |
| AC2: each action calls the repository | InboxViewModelTest `each direct action calls the repository with the current item`, `each prompted action calls the repository with the prompt's value`; InboxRouteTest `starting processing and tapping an action...`, `a prompted action runs with the prompt's value` | F | Pass |
| AC2: offline state disables actions | InboxViewModelTest `offline disables every action`, `offline blocks an open prompt from running`; InboxScreenTest `offline shows the banner and disables processing and every action`, `offline also disables a prompt's confirmation`; InboxRouteTest `offline disables processing and the banner's retry reloads the inbox` | F | Pass |
| AC2: Robolectric/Compose UI tests for screens | InboxScreenTest (21), InboxRouteTest (4), InboxGraphTest (2) | F | Pass |
| CR-1: retry after offline uses the saved clarification/version | InboxRepositoryTest `a retry after a saved clarification uses the saved version...`, `a retry with a further change patches only that change against the saved version`, `trash after a saved clarification...`, `a newer cached version wins...`; InboxViewModelTest retry tests | F | Pass |
| CR-2: stale/double tap guard | InboxViewModelTest `a second tap for an item that already left the queue...`, `a quick second tap...`, `the double-tap guard ends exactly 500 ms...`, `a stale prompt confirmation is dropped`, `a prompt opened on an item that left the inbox meanwhile...`, `inline changes made on an item that left the inbox meanwhile...` | F | Pass |
| CR-4: created project reused by title on retry | InboxRepositoryTest `to a new project creates it once...`, `a retry with another new project title...`, `choosing an existing project forgets...`, `processing the item another way forgets...` | F | Pass |
| Assumption: failed next-step call still counts as processed (no connection, error status, unreadable answer) | InboxRepositoryTest `a failed next step...`, `a refused next step...`, `a malformed project answer...`, `a project answer with a malformed id...`, `a connection lost during the stalled check...` | F | Pass |
| Same assumption, unreadable date (TW-1, fixed in ec920897) | InboxRepositoryTest `a project answer with an unreadable date after the move still counts as processed`, `a next-step answer with an unreadable due date still counts as processed` (new) | F | Pass |
| Assumption: placeholder outside a Hilt activity, real Inbox inside the app | InboxRouteTest `outside a Hilt activity...`; InboxGraphTest `the inbox tab of the app shows the inbox, not the placeholder` | F | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --rerun-tasks`
(2026-10-21 10:54:07 to about 10:55:20 +02:00, BUILD SUCCESSFUL, 49 tasks executed)

Result:

Pass: 101 tests, 0 failures, 0 errors, 0 skipped. ktlintCheck passed. Per class: InboxActionsTest 11/11,
InboxViewModelTest 28/28, InboxScreenTest 21/21, InboxGraphTest 2/2, InboxRouteTest 4/4, InboxRepositoryTest 35/35.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest lintDebug --continue` (10:55:30 to 10:57:07)

Result:

Pass: 578 Android unit tests, 0 failures, 0 errors, 0 skipped (counted from the JUnit XML in
`app/build/test-results/testDebugUnitTest`). lintDebug passed.

## .taskcheck

Result:

Not run in this run. The sandbox denied running `sh scripts/check.sh`. The first run had to stop at the server step
for the same reason. In the first run, `.taskcheck` failed on an unrelated server test, `EventsStreamTests`
("an event published inside a transaction is not sent before the commit()", a truncated SSE body). It looked like a
timing race, and this task changes no server files (`git diff --stat f30fca1b..HEAD -- server` is empty). The parent
must run `.taskcheck` itself. Its Android step is the same as the ktlint/test/lint commands above, which pass.

## Bugs found

### Unreadable date in the project answer after a successful move reports the item as refused (fixed)

Finding ID:

`TW-1`

Test:

`InboxRepositoryTest` > `a project answer with an unreadable date after the move still counts as processed`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt:166-181` (`makeNextStepIfStalled`)

Requirement:

Recorded assumption "a failed next-step call still counts as processed", plus the KDoc of `makeNextStepIfStalled`:
the item has moved even when the stalled check fails with "no connection, an error status or an unreadable answer".

Expected behavior:

The move succeeds, then `GET /projects/{id}` answers with a malformed date-time (`"updatedAt":"yesterday"`).
`process` returns `ProcessResult.Processed`.

Actual behavior:

In the first run, at d2f7717f, `process` returned `ProcessResult.Rejected(status=null)`. The `DateTimeParseException`
from the generated `OffsetDateTimeAdapter` was not caught. The user then saw "The server refused this action" for an
item that had moved.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Status: fixed in ec920897, which adds
`catch (_: DateTimeException)`. The fixer's record is in `reviews/and-inbox/fixes.md`. The test passes in this run.
The new test checks the same fix for a malformed `LocalDate` in the next-step answer, and it passes too.

No new production defects were found in this run.

## Coverage

Status: verified. The figures come from a fresh JaCoCo run of the focused suite at the final revision, and all
101 tests passed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All four Kotlin files are new in this task (added by 78109e57; `InboxScreen.kt`
replaced a 1-line placeholder), so whole-file coverage is close to changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-inbox; base
  f30fca1b4a52d8a040d3eb6dfcd2bb4a1b9b18d9; HEAD ec920897ba95669b650cc8efc06c6f703dc00812; commits 78109e57..ec920897
  from the parent, checked with `git log f30fca1b..HEAD`. File scope from `git diff --stat f30fca1b..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 prefixes of the production files at HEAD:
  InboxActions.kt 92c3fd69840aecac, InboxRepository.kt 5929ca8420a8a281 (includes the TW-1 fix),
  InboxScreen.kt d66673ea0df1e014, InboxViewModel.kt 658c4aae0f80dd4c, strings_inbox.xml 196f52ca12a08708. Tests:
  InboxActionsTest.kt 307afa3258cf24a0, InboxGraphTest.kt 9681e5cd558c9aca, InboxRepositoryTest.kt
  bb54d41e267d3d22 (uncommitted: the new test), InboxRouteTest.kt 54ed3489f78e627e, InboxScreenTest.kt
  486026c22ff53d24, InboxViewModelTest.kt d3a964d3b57169eb. The hashes were taken at 10:54:07 and were the same after
  the full-suite run (10:57).
- Exact included files; renamed/deleted/excluded files and reasons: included InboxActions.kt, InboxRepository.kt,
  InboxScreen.kt, InboxViewModel.kt. Excluded: `strings_inbox.xml`, a resource that JaCoCo does not instrument (the
  screen tests use its strings). Also excluded are the Hilt/Dagger-generated Java classes in the same package
  (`InboxViewModel_HiltModules*`, `*_Factory`). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage`, run in
  `android/`. Step 1: `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --rerun-tasks`.
  Step 2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' createDebugUnitTestCoverageReport`.
  In step 2 the test task was UP-TO-DATE, so only the report was built from the exec of step 1.
- Run start/completion time and exit status: step 1 ran from 10:54:07 to about 10:55:20 +02:00 and exited 0. Step 2
  finished at 10:55:24 and exited 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`
  (sha256 dc2cd28e15e6bae1d0e4e7bc18f49e25ae380138c825c3ac67bf717bb4c13b58, mtime 10:55:24). The exec
  `app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` had sha256
  4c6184e0ca41c025cad007c42af0033c80e1fba8fb6033442626f205b07102bb and mtime 10:55:17, which is inside step 1. The
  later full-suite run overwrote the exec. The report above was read before that.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun-tasks` was used, the exec and report mtimes
  fall inside this run, and the input hashes matched afterwards.
- Final run after latest test additions and production fixes: yes. The run was at HEAD ec920897 (with the TW-1 fix),
  including the test added in this run.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no separate
statement counter, so its INSTRUCTION counter is shown under Statements.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxActions.kt` | 305/308 (99.0%) | 24/24 (100.0%) | 23/24 (95.8%) | 29/29 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt` | 1004/1011 (99.3%) | 67/68 (98.5%) | 15/16 (93.8%) | 76/78 (97.4%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt` | 1402/1416 (99.0%) | 106/109 (97.2%) | 62/63 (98.4%) | 138/138 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt` | 4386/4511 (97.2%) | 114/124 (91.9%) | 95/109 (87.2%) | 368/369 (99.7%) |
| **Aggregate (4 files, summed counters)** | 7097/7246 (97.9%) | 311/325 (95.7%) | 195/212 (92.0%) | 611/614 (99.5%) |

The aggregate leaves out `strings_inbox.xml` (not instrumented) and the generated Hilt Java classes. Every scoped
file is in the report. Compared with the first run, `InboxRepository.kt` has 2 more instructions and 1 more line,
from the new `DateTimeException` catch, and that catch is now covered. The first run's partial figures were
7095/7244 instructions and 610/613 lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `InboxRepository.kt:173-174` (`catch (e: CancellationException) { throw e }`) | Rethrows cancellation | Not covered. It is defensive, and a test would need a coroutine cancelled during the project call; no test added. |
| `InboxRepository.kt:179` (`DateTimeException` catch) | A failed next step still counts as processed (TW-1) | Covered by the TW-1 test and the new next-step test. |
| `InboxRepository.kt:199` (1 branch: `energy` null in a saved answer) | Maps a saved answer back to a clarification | Not material. The non-null and creativity paths are covered by the CR-1 test. |
| `InboxViewModel.kt:137, 204, 213` (1 branch each: no current item for an open prompt, `updatePrompt` without a prompt, `confirmPrompt` without a prompt) | Null guards | Not material. These are guard branches with no reachable user path. |
| `InboxScreen.kt:105` and 14 methods (the default no-op lambdas of `InboxCallbacks`, compose lambdas) | Defaults for previews and tests | Not material. |
| `InboxScreen.kt:81` (`hiltViewModel()` default argument), `211` (loading text when offline), `266` (progress when the total is 0), `298` (blank notes), `335/336/343` (deselecting creativity, selecting an estimate), `434` (prompt `else ->` for kinds without a prompt), `526` (picker confirmed with no selection) | Rendering variants | Not material. Each is a cosmetic branch or cannot be reached: line 434 needs a prompt that is never built, and at 526 the picker always starts with a selection. InboxGraphTest covers the Hilt path. |

## Unable to test

- Running the processing flow against a real server (the Android app against the Spring Boot backend) was not done.
  The acceptance criteria name only the unit/Robolectric suite. The network layer is tested against MockWebServer at
  the Retrofit level, with the generated client and the real serializer.
- `.taskcheck` (`sh scripts/check.sh`) was not run because the sandbox denied the command. The parent must run it, and
  should check whether the server `EventsStreamTests` failure from the first run comes back. That failure is unrelated
  to this task.

## Summary

Tests added and all validation passed. TW-1 is fixed: its regression test passes, and a new test confirms that the
same catch handles an unreadable `LocalDate` in the next-step answer. The focused suite (101 tests, ktlint) and the
full Android suite (578 tests, lintDebug) pass at ec920897. Task-scoped coverage at the final revision is 97.9%
instructions, 95.7% branches and 99.5% lines, and the remaining gaps are justified above. No production defects
remain open. `.taskcheck` has not run in this run and must be run by the parent.
