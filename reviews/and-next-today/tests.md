# Test Report

Date: 2026-10-21

Task: and-next-today: Android Next and Today

Scope: final revision after the fixer. Base `368c5c235f79092773075db7d82fcb714b1a94f9` (`git merge-base HEAD origin/main`), HEAD `5c88483792d9e04a42d4d070d030526132a1df62`. Task commits touching `android/`: 7ec33c6e, 2a194564, 18888f1c, 2a20a47a, 840eb686 (fixer: lint fixes CR-1/CR-2, CR-3 hide/unhide logic, CR-5/CR-6 cleanups). 8dcdb92d and 5c884837 touch only `reviews/` and `tasks/`. Plus the uncommitted test edits listed below (working tree, branch `main`). This report replaces the earlier one, which described the pre-fix revision 6f721d1f.

Requirements:
- `tasks/and-next-today.md`: acceptance criteria and the 5 recorded assumptions (filter semantics, Today sections only, no next-step prompt, shared UI in `gtd/next`, CR-4 deferred to `and-gtd-load-error`)
- `SPEC.md` §5.3 (Next filters, Today), §5.1 (estimate quick picks), §9.3 (offline read-only)
- `reviews/and-next-today/review.md` (CR-1..CR-6) and `reviews/and-next-today/fixes.md`
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenTest.kt` (2 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/TaskCompleterTest.kt` (2 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayViewModelTest.kt` (1 test added)

Only lines were added (`git diff --stat`: 62 insertions, 0 deletions). No production file was changed.

## Tests added or updated

Count: 5. The focused suite grew from 100 (after the fixer) to 105 tests.

Re-check of the fixer's changes:
- CR-3 (hide/unhide by version): the fixer's 5 regression tests cover the main paths (hidden up to the completion version, hidden while an undo is pending, reopened elsewhere shows again in Next and Today, refused undo then reload). Gaps I closed:
  - `TaskCompleterTest.a successful undo stops hiding every cached copy of the task`: after `Reopened`, the `completed` entry is gone, so neither the pre-completion copy nor the completed version is hidden.
  - `TaskCompleterTest.a task reopened elsewhere whose second completion is refused shows again`: a newer version may be completed again past the old `completed` entry, and a refusal brings that newer copy back while the old copy stays hidden.
  - `TodayViewModelTest.a reopened task shows once even while the other view still holds it as it was before the completion`: Today merges two cached views. Hiding happens before `distinctBy`, so a stale pre-completion copy in one view never hides or replaces the reopened copy in the other, in either direction.
- CR-2 (plurals): the "other" text was already tested ("1 of 2", "0 of 0"). The singular form and the choice of quantity (the total, not the number of matches) were not. Added:
  - `NextScreenTest.the filtered count of a single next action is singular` ("1 of 1 next action"; this fails against the old `<string>` resource).
  - `NextScreenTest.the filtered count follows the total, not the number of matches` ("0 of 1 next action", then "1 of 3 next actions").
- CR-1 (`LocalResources`), CR-5 (`remember` context map), CR-6 (`toggleCreativity` reformat): behavior is unchanged. The existing snackbar, row-details and filter-toggle tests cover it, and they pass. No new tests needed.

## Requirement coverage

Command F = `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'` (105 tests, 0 failures, 0 skipped, lint "No issues").

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Next: context filter (one context, matches any of the task's contexts, toggles off, dropped when the context is deleted) | `NextFilterTest.a context filter matches any of the task's contexts`; `NextViewModelTest.filters by context`, `drops the filter of a context deleted elsewhere`, `a context filter stays while its context still exists`; `NextScreenTest.each filter chip calls its toggle`; `NextRouteTest.each filter chip filters the list and clearing shows everything again` | F | Pass |
| Next: energy and creativity filters (exact match) | `NextFilterTest.energy and creativity must match exactly and are not ranges`; `NextViewModelTest.filters by energy and creativity`, `toggling a chosen value again clears only that filter`; `NextScreenTest.every energy, creativity and estimate chip is labelled with the value it toggles`; `NextRouteTest.each filter chip ...` | F | Pass |
| Next: estimate filter (max minutes, inclusive, needs an estimate; quick picks 5/15/30/60/120) | `NextFilterTest.the estimate filter is inclusive and needs an estimate`, `the estimate quick picks are those of the spec`; `NextViewModelTest.filters by maximum estimate, only matching estimated tasks`; `NextRouteTest.each filter chip ...` | F | Pass |
| Next: filters combine, show their count (plural-correct), and clear | `NextFilterTest.combined filters need every one to match`; `NextViewModelTest.combines filters and clears them`, `the filter stays when the list reloads, ...`; `NextScreenTest.shows the active filter and a way to clear it`, `the filtered count of a single next action is singular` (new), `the filtered count follows the total, not the number of matches` (new), `no match under a filter offers to clear it`, `a filter on an empty Next list says the list is empty, ...` | F | Pass |
| Next: list states (loading, empty, offline read-only with banner) | `NextViewModelTest.is loading until ...`, `offline with nothing cached is not loading`, `cached tasks without a refresh time show at once ...`, `offline the list is read-only ...`; `NextScreenTest.shows loading ...`, `an empty Next list says so ...`, offline tests (3) | F | Pass |
| Today: overdue / due today / scheduled sections | `TodayViewModelTest.groups overdue, due today and scheduled tasks, each task once`, `leaves out empty sections`, `sections follow the date rules`, `due today wins over scheduled earlier, ...`, `the date rules follow the clock's day`, `an overdue Next task missing from the cached Today view ...`, `a task in both views keeps the Today version`; `TodayScreenTest.shows the overdue, due and scheduled sections with their tasks`, `section headers count their tasks ...` | F | Pass |
| Today: loading, empty, offline states | `TodayViewModelTest.is loading until both views were loaded`, `refreshedAt ...` (2), `offline in either view ...`, `offline in the Next view also makes Today read-only`, `offline with nothing cached is not loading`; `TodayScreenTest` empty/loading/offline tests | F | Pass |
| Complete with undo snackbar (hidden at once, undo offer, undo sends the returned version, refusal comes back with a reason, timeout dismisses) | `TaskCompleterTest` (13); `NextViewModelTest.completing hides the task and offers undo`, `the task is hidden while the server answers ...`, `a failed undo ...`, `dismissing an older snackbar keeps the newer one`; `TodayViewModelTest.completing hides the task and offers undo`, `a completed task leaves its section and a refused one comes back`; `NextScreenTest`/`TodayScreenTest` snackbar tests; `NextRouteTest.completing hides the task, and undo in the snackbar brings it back`; `TodayRouteTest.completing empties the section, and undo brings the task back` | F | Pass |
| CR-3: a completed task shows again once reopened or changed elsewhere; stays hidden while a stale copy is cached or an undo is pending | `TaskCompleterTest.a completed task is hidden only up to the version ...`, `a task is hidden while its undo is pending, whatever its version`, `a successful undo stops hiding every cached copy of the task` (new), `a task reopened elsewhere whose second completion is refused shows again` (new); `NextViewModelTest.a completed task reopened elsewhere shows again once the list reloads it`, `after a refused undo the reloaded list shows the task as the server has it`; `TodayViewModelTest.a completed task reopened elsewhere comes back to its section ...`, `a reopened task shows once even while the other view still holds it ...` (new) | F | Pass |
| Completion against the server (If-Match, reload Next+Today, 404/409/412 conflict, other 4xx failed, 5xx offline without reload, unreadable answer failed) | `TaskCompletionRepositoryTest` (9, MockWebServer + Room-backed `TaskRepository`) | F | Pass |
| Screens wired into the app (Robolectric + Hilt) | `NextScreenInAppTest` (2); placeholder fallback tests in `NextScreenTest`/`TodayScreenTest` | F | Pass |
| AC2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` passes covering filters with Robolectric/Compose UI tests; same for `gtd.today` | all of the above | F | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'` (run from the repository root as `android/gradlew -p android ...`)

Result:

Pass. BUILD SUCCESSFUL in 44s. JUnit XML (timestamps 2026-10-21T09:03:57Z to 09:04:28Z): NextFilterTest 6, NextRouteTest 2, NextScreenInAppTest 2, NextScreenTest 24, NextViewModelTest 20, TaskCompleterTest 13, TaskCompletionRepositoryTest 9, TodayRouteTest 1, TodayScreenTest 11, TodayViewModelTest 17. Total 105, 0 failures, 0 errors, 0 skipped. `ktlintTestSourceSetCheck` and `lintDebug` ran and passed. The cold-run lint/Hilt race the fixer saw did not happen.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole Android `testDebugUnitTest` suite), then `sh scripts/check.sh`

Result:

Pass. Android: 582 tests, 0 failures, 0 errors, 0 skipped. Server and web: Pass inside `.taskcheck` (web 106 files, 2095 tests).

## .taskcheck

Result:

Pass. `sh scripts/check.sh` from 2026-10-21T09:06:33Z to 09:07:41Z printed "== all checks passed". It ran the TFP gate tests, server ktlint + tests, web eslint + tests + build, and android ktlint + unit tests + lint.

## Bugs found

None. TW-1 and TW-2 from the previous report are fixed: `lintDebug` reports no issues, and the new plural tests pass.

Known and deferred (not a test-writer finding): CR-4 (a non-offline first-load failure stays on "Loading") needs a change in `gtd/data`. It is recorded as an assumption, with follow-up task `and-gtd-load-error`. I added no failing test for it, because the fix lies outside this task's touches.

## Coverage

Status: verified (whole-file and changed-line, 6 Kotlin files in scope). The command passed. The report was freshly generated on a stable snapshot that includes the final production code and the new tests.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 483/487 executable added lines (99.2%) over the whole task (`368c5c23..HEAD`), and 27/27 (100%) over the fixer's commit alone (`8dcdb92d..HEAD`). Measured by intersecting JaCoCo `<line>` entries (`ci > 0`) with the `+` hunks of `git diff -U0 <base>..HEAD` per file (`.tmp/tw/changed_lines.py`, Git-ignored). The task created all 6 files, so whole-task changed-line coverage equals whole-file line coverage. Changed-branch coverage is not inferred.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-next-today`. Base `368c5c235f79092773075db7d82fcb714b1a94f9` (merge-base with `origin/main`, which equals it). HEAD `5c88483792d9e04a42d4d070d030526132a1df62`. The parent named the task commits as `git log origin/main..HEAD` touching `android/`. File scope is `git diff --name-only 368c5c23..HEAD -- android/app/src/main`, consistent with the task's `touches` plus `strings_next_today.xml`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of the sha256 list of all `gtd/next` and `gtd/today` main and test `.kt` files plus `strings_next_today.xml` is `207d4aae8daaf51a43efd0567e9c69746d6e54bd0ef8f6a1d6c0d02a65f9d0a3`. It is identical before the coverage run and after `.taskcheck`. The uncommitted tests are NextScreenTest `a8da1c6e…`, TaskCompleterTest `9f4584c7…`, TodayViewModelTest `cb6f5e2d…`. Production files equal HEAD.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/{NextScreen,NextViewModel,TaskCompletion,TaskListUi}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/{TodayScreen,TodayViewModel}.kt`. Excluded: `android/app/src/main/res/values/strings_next_today.xml` (a resource that JaCoCo cannot instrument; the screen tests that assert its texts, including both plural forms, cover it indirectly). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` (Gradle 9.8.1; the JaCoCo version is not pinned in the build). Command `android/gradlew -p android createDebugUnitTestCoverageReport` from the repository root. Test selection: the whole debug unit suite (582 tests).
- Run start/completion time and exit status: 2026-10-21T09:04:41Z to 09:06:13Z. Success: no build failure output, `testDebugUnitTest` re-executed (fresh JUnit XML 09:04:56Z to 09:06:03Z), and the report was regenerated.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `689476af337a0bc5525281527154008a3080c0390c22322b1337f98dd683917a`, JaCoCo session `dell26-39d3f6f` (start 1792573495548, dump 1792573571675), mtime 2026-10-21 11:06:13 +0200.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The session id and report hash differ from the pre-fix run (`dell26-cf0ae10e`, `2debd58a…`), and the snapshot is unchanged.
- Final run after latest test additions and production fixes: yes (it includes fixer commit 840eb686 and the 5 new tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" is the JaCoCo INSTRUCTION counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `gtd/next/NextScreen.kt` | 1854/1925 (96.3%) | 59/60 (98.3%) | 46/55 (83.6%) | 156/157 (99.4%) |
| `gtd/next/NextViewModel.kt` | 575/580 (99.1%) | 40/40 (100%) | 33/33 (100%) | 53/53 (100%) |
| `gtd/next/TaskCompletion.kt` | 720/737 (97.7%) | 28/34 (82.4%) | 37/38 (97.4%) | 73/75 (97.3%) |
| `gtd/next/TaskListUi.kt` | 1084/1090 (99.4%) | 62/69 (89.9%) | 13/13 (100%) | 84/84 (100%) |
| `gtd/today/TodayScreen.kt` | 851/882 (96.5%) | 32/33 (97.0%) | 15/19 (78.9%) | 70/71 (98.6%) |
| `gtd/today/TodayViewModel.kt` | 364/369 (98.6%) | 28/28 (100%) | 21/21 (100%) | 47/47 (100%) |
| **Total (summed counters, 6 files)** | 5448/5583 (97.6%) | 249/264 (94.3%) | 165/179 (92.2%) | 483/487 (99.2%) |

The total covers all 6 Kotlin files in scope. Excluded: `strings_next_today.xml` (not instrumentable). No scoped file is missing from the report. The CR-3 `hides()` line (`TaskCompletion.kt:114`) has all of its branches covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskCompletion.kt:132,141,143` and `:157,159,161` (`CompletionResult.Reopened -> Unit` after complete, `Completed -> Unit` after undo; the missed branches are those `when` arms) | None: exhaustive-`when` arms that the repository never returns for that call | Left: unreachable through the `TaskCompletionRepository` contract. Not material |
| `TaskCompletion.kt:81` (2 instructions of the `CONFLICTS` companion) | Conflict status mapping | The behavior is covered (404/409/412 tests). The missed instructions are synthetic companion init. Not material |
| `NextScreen.kt:61`, `TodayScreen.kt:54` (`hiltViewModel()` default-argument branch) | Hilt wiring | Left: `NextScreenInAppTest` exercises the real Hilt path. The miss is the synthetic `$default` mask branch. Not material |
| `NextScreen.kt:70-79`, `TodayScreen.kt:63-67` (default no-op lambdas of `NextCallbacks`/`TodayCallbacks`) | None: preview/test defaults | Left. Not material |
| `TaskListUi.kt:55,79` (modifier defaults), `:149` (LaunchedEffect state-machine branches), `:172` (ActionPerformed on a `Failed` snackbar, which has no action) | Snackbar handling | Undo action, undo timeout and failure timeout are tested. Not material |
| `NextViewModel.kt:61`, `TodayViewModel.kt:40` (`today = LocalDate.MIN` default) | None | Left. Not material |

## Unable to test

- CR-4 (a failed first load stays on "Loading") is deferred to `and-gtd-load-error` because it needs a `gtd/data` change. No test was written for it here.
- Instrumented device/emulator tests (`androidTest`) were not run. The criterion names Robolectric/Compose UI tests, and those ran. No follow-up is needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

On the final code (HEAD 5c884837 plus 5 new tests), the focused command (ktlint + lint + 105 tests), the full Android suite (582 tests) and `.taskcheck` all pass. Whole-file line coverage of the 6 task files is 483/487 (99.2%), and the fixer's changed lines are 27/27 covered.
