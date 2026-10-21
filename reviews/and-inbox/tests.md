# Test Report

Date: 2026-10-21

Task: and-inbox - Android inbox processing

Scope: task commits `39be79a53c7cdac4b6cc37a2b8303a8f340e4ca8..d2f7717f444a77a651b0bdae96b8b88110a34294`
(08443938..d2f7717f, 8 commits on `main`; base and commit list supplied by the parent worker and checked against
`git log 39be79a5..HEAD`). Production files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/*.kt` and
`android/app/src/main/res/values/strings_inbox.xml`. Tests: `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/`.
Before this run the working tree was clean at HEAD d2f7717f. The new tests are uncommitted (snapshot hashes below).

Requirements:
- `tasks/and-inbox.md` acceptance criteria and recorded assumptions (actions shown: Do it, Next, To project, Waiting,
  Schedule, Tickler, Someday, Trash; hand-written PATCH; 'To project' sets the next step of a stalled project, and a
  failed next-step call still counts as processed; placeholder outside a Hilt activity)
- SPEC.md §5.3 (inbox processing), docs/ARCHITECTURE.md "Coverage"
- `reviews/and-inbox/review.md` and `fixes.md` (CR-1 retry, CR-2 stale/double-tap guard, CR-4 reuse of the created
  project by title) and commit d2f7717f (makeNextStepIfStalled tolerates an unreadable answer)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRepositoryTest.kt` (5 tests added; `retrofit` became a field so a test can swap in a failing `ProjectsApi`)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModelTest.kt` (2 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRouteTest.kt` (1 test added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxGraphTest.kt` (new, 2 tests)

## Tests added or updated

Count: 10 added (90 existing inbox tests unchanged; 100 in total now)

Added:
- InboxRepositoryTest: `a project answer with an unreadable date after the move still counts as processed` (fails, TW-1);
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
| CR-1: retry after offline uses the saved clarification/version | InboxRepositoryTest `a retry after a saved clarification uses the saved version...`, `a retry with a further change patches only that change against the saved version` (new), `trash after a saved clarification...`, `a newer cached version wins...`; InboxViewModelTest retry tests | F | Pass |
| CR-2: stale/double tap guard | InboxViewModelTest `a second tap for an item that already left the queue...`, `a quick second tap...`, `the double-tap guard ends exactly 500 ms...`, `a stale prompt confirmation is dropped`, `a prompt opened on an item that left the inbox meanwhile...` (new), `inline changes made on an item that left the inbox meanwhile...` (new) | F | Pass |
| CR-4: created project reused by title on retry | InboxRepositoryTest `to a new project creates it once...`, `a retry with another new project title...`, `choosing an existing project forgets...`, `processing the item another way forgets...` | F | Pass |
| Assumption: failed next-step call still counts as processed (d2f7717f: no connection, error status, unreadable answer) | InboxRepositoryTest `a failed next step...`, `a refused next step...`, `a malformed project answer...`, `a project answer with a malformed id...` (new), `a connection lost during the stalled check...` (new) | F | Pass |
| Same assumption, unreadable date in the project answer | InboxRepositoryTest `a project answer with an unreadable date after the move still counts as processed` (new) | F | **Fail (TW-1)** |
| Assumption: placeholder outside a Hilt activity, real Inbox inside the app | InboxRouteTest `outside a Hilt activity...` (new); InboxGraphTest `the inbox tab of the app shows the inbox, not the placeholder` (new) | F | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --rerun-tasks`
(2026-10-21 10:39:16 to 10:40:17 +02:00)

Result:

Fail: 100 tests, 99 passed, 1 failed (TW-1). ktlintCheck passed. Per class: InboxActionsTest 11/11,
InboxViewModelTest 28/28, InboxScreenTest 21/21, InboxGraphTest 2/2, InboxRouteTest 4/4, InboxRepositoryTest 33/34.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug --continue` (10:40:44 to 10:42:31)

Result:

Fail: 577 Android tests, 1 failed. The only failure is the TW-1 test. ktlintCheck and lintDebug passed.

## .taskcheck

Result:

Fail: `sh scripts/check.sh` (10:42:39 to 10:49:20). The TFP gate tests passed. Then the server step (`./gradlew check`)
failed on 1 of 3060 tests: `ch.sonensei.cockpit.core.events.EventsStreamTests` "an event published inside a
transaction is not sent before the commit()". It read a truncated SSE body (`"op":"DELE` instead of `"op":"DELETED"`),
which looks like a timing race in the server's event stream test. This task changed no server files
(`git diff --stat 39be79a5..HEAD -- server` is empty), so the failure is unrelated to it. I could not rerun the class
because running the server's Gradle wrapper from this sandbox needs approval. Because of `set -e`, the web and Android
steps did not run in this invocation; the Android step's exact command was run separately above. The parent should
rerun `.taskcheck` after TW-1 is fixed and check whether the server test is flaky.

## Bugs found

### Unreadable date in the project answer after a successful move reports the item as refused

Finding ID:

`TW-1`

Test:

`InboxRepositoryTest` > `a project answer with an unreadable date after the move still counts as processed`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt:261-275` (`makeNextStepIfStalled`)

Requirement:

Recorded assumption "a failed next-step call still counts as processed", plus the KDoc of `makeNextStepIfStalled`:
the item has moved even when the stalled check fails with "no connection, an error status or an unreadable answer".
Commit d2f7717f added this for `SerializationException` and `IllegalArgumentException`.

Expected behavior:

The move succeeds, then `GET /projects/{id}` answers with a malformed date-time (here `"updatedAt":"yesterday"`).
`process` should return `ProcessResult.Processed`.

Actual behavior:

It returns `ProcessResult.Rejected(status=null)`. The generated `OffsetDateTimeAdapter` throws
`java.time.format.DateTimeParseException`, which is a `DateTimeException`. It is neither a `SerializationException` nor
an `IllegalArgumentException`, so it escapes `makeNextStepIfStalled`. `GtdConnectivity.refreshing` then maps it to
`RefreshResult.Failed(null)`. In the UI the moved item disappears after the reload, but the user sees "The server
refused this action", the processed count does not go up, and `clarified`/`createdProjects` keep their entries.
The same applies to a malformed `LocalDate` field (`dueDate`, `reviewDate`).

Severity:

Low. It needs a malformed server answer, and the item does reach the right project. It is the same class of defect
that d2f7717f fixed, and the fix there is incomplete.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The figures come from a fresh JaCoCo run of the focused suite in which 1 test failed (TW-1).
They are diagnostic. Measure again after TW-1 is fixed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All four Kotlin files are new in this task (added by 08443938, except
`InboxScreen.kt`, which replaced a 1-line placeholder), so whole-file coverage is close to changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-inbox; base
  39be79a53c7cdac4b6cc37a2b8303a8f340e4ca8; HEAD d2f7717f444a77a651b0bdae96b8b88110a34294; commits 08443938..d2f7717f
  from the parent, checked with `git log`. File scope from `git diff --name-only 39be79a5..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): production files unchanged at HEAD (sha256 prefixes:
  InboxActions.kt 92c3fd69840aecac, InboxRepository.kt 21412fc556e06df3, InboxScreen.kt d66673ea0df1e014,
  InboxViewModel.kt 658c4aae0f80dd4c, strings_inbox.xml 196f52ca12a08708). Uncommitted tests: InboxActionsTest.kt
  307afa3258cf24a0 (unchanged), InboxGraphTest.kt 9681e5cd558c9aca (new), InboxRepositoryTest.kt 3b6f141b4e7d49c0,
  InboxRouteTest.kt 54ed3489f78e627e, InboxScreenTest.kt 486026c22ff53d24 (unchanged), InboxViewModelTest.kt
  d3a964d3b57169eb. Hashes were the same before and after the runs.
- Exact included files; renamed/deleted/excluded files and reasons: included InboxActions.kt, InboxRepository.kt,
  InboxScreen.kt, InboxViewModel.kt. Excluded: `strings_inbox.xml` (a resource that JaCoCo does not instrument; the
  screen tests use its strings) and the Hilt/Dagger-generated Java classes in the same package
  (`InboxViewModel_HiltModules*`, `*_Factory`), which are generated. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage`, in
  `android/`. Step 1: `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --rerun-tasks`,
  which writes `app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`. Step 2:
  `./gradlew createDebugUnitTestCoverageReport -x testDebugUnitTest`, because the report task depends on the failing
  test task. Step 2 only turns the exec from step 1 into the report.
- Run start/completion time and exit status: step 1 from 10:39:16 to 10:40:17 +02:00, exit nonzero (1 failing test).
  Step 2 finished 10:40:32, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`
  (sha256 795d5217328925853af7584259e2559c303fd240ec70fad70568c4d67748d5d1, mtime 10:40:32). The exec file has
  sha256 bc8bcb08bc085676c979ac9d23f270040a533b7e609910ed6df55dd2af1c1c88 and mtime 10:40:16, which is inside the
  step-1 run.
- Evidence outputs are fresh and inputs stayed unchanged: yes, `--rerun-tasks` and the exec mtime show this, and the
  input hashes matched after the runs.
- Final run after latest test additions and production fixes: yes for the test additions. TW-1 is still open, so a run
  is needed after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no separate
statement counter, so its INSTRUCTION counter is shown under Statements.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxActions.kt` | 305/308 (99.0%) | 24/24 (100.0%) | 23/24 (95.8%) | 29/29 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt` | 1002/1009 (99.3%) | 67/68 (98.5%) | 15/16 (93.8%) | 75/77 (97.4%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt` | 1402/1416 (99.0%) | 106/109 (97.2%) | 62/63 (98.4%) | 138/138 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt` | 4386/4511 (97.2%) | 114/124 (91.9%) | 95/109 (87.2%) | 368/369 (99.7%) |
| **Aggregate (4 files, summed counters)** | 7095/7244 (97.9%) | 311/325 (95.7%) | 195/212 (92.0%) | 610/613 (99.5%) |

The aggregate leaves out `strings_inbox.xml` (not instrumented) and the generated Hilt Java classes. No scoped file is
missing from the report. Before the new tests, the report generated from the parent's cached run of the same 90 tests
gave: instructions 6989/7244 (96.5%), branches 300/325 (92.3%), methods 192/212 (90.6%), lines 599/613 (97.7%). The
uncovered code this run newly reached: `InboxModule.taskPatchApi`, the IOException and IllegalArgumentException
catches in `makeNextStepIfStalled`, `Waiting(null)`, the saved-answer creativity mapping, the placeholder branch of
`InboxScreen`, and the Hilt branch through `InboxRoute`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `InboxRepository.kt:172-173` (`catch (e: CancellationException) { throw e }`) | Rethrows cancellation | Not covered. It is defensive and needs a cancelled coroutine in the middle of the project call; no test added. |
| `InboxRepository.kt:261-275` (no `DateTimeException` catch) | A failed next step still counts as processed | Test added. It fails: TW-1. |
| `InboxRepository.kt:197` (1 branch: `energy` null in a saved answer) | Maps a saved answer back to a clarification | Not material. The non-null and creativity paths are covered by the new CR-1 test. |
| `InboxViewModel.kt:137, 204, 213` (1 branch each: no current item for an open prompt, `updatePrompt` without a prompt, `confirmPrompt` without a prompt) | Null guards | Not material. These are guard branches with no reachable user path. |
| `InboxScreen.kt:105` and 14 methods (the default no-op lambdas of `InboxCallbacks`, compose lambdas) | Defaults for previews and tests | Not material. |
| `InboxScreen.kt:81` (`hiltViewModel()` default argument), `211` (loading text when offline), `266` (progress when the total is 0), `298` (blank notes), `335/336/343` (deselecting creativity, selecting an estimate), `434` (prompt `else ->` for kinds without a prompt), `526` (picker confirmed with no selection) | Rendering variants | Not material. Each is a cosmetic branch or cannot be reached (434: such a prompt is never built; 526: the picker always starts with a selection). The Hilt path is covered by InboxGraphTest. |

## Unable to test

- Running the processing flow against a real server (the Android app against the Spring Boot backend) was not run.
  The acceptance criteria name only the unit/Robolectric suite, and the network layer is covered against
  MockWebServer at the Retrofit level, using the generated client and the real serializer.
- Rerunning the server's `EventsStreamTests` to confirm it is flaky was not run, because the sandbox requires approval
  to run the server's Gradle wrapper. This is outside this task's scope and is for the parent to follow up.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
TW-1 (Low): `makeNextStepIfStalled` does not tolerate a `DateTimeException`, which the generated client throws for a
malformed date. A successful move to a project is then reported as refused. All other acceptance criteria and the
CR-1, CR-2 and CR-4 fixes are covered by passing tests. After the fix, rerun the focused suite, the coverage and
`.taskcheck` (whose server step also failed on an unrelated, apparently flaky `EventsStreamTests` case).
