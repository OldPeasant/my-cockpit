# Test Report

Date: 2026-11-08

Task: and-review-flow - Android weekly review: start, steps, resume, reflection

Scope: `git diff fdd5513d678901dc58963738a9d1e5e05ab2166f..72df82ee649f1f9f8cab75b852e9acb89f152bf7` (task commits 76b39ca6f, 26134ab8d, 7f3729b5d, 66ab0159d, 72df82ee6 plus two `assume` commits), with this pass's uncommitted test changes on top. Branch `main`, HEAD 72df82ee649f1f9f8cab75b852e9acb89f152bf7. This is the final code after the fixer resolved CR-1 to CR-5 and TEST-1.

Requirements:
- tasks/and-review-flow.md acceptance criteria 1-7 and recorded assumptions
- SPEC.md §8.6, §9.2 (as referenced by the task and review)
- AGENTS.md project notes, docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md
- reviews/and-review-flow/review.md and fixes.md (findings the tests must protect)

A previous test-writer pass added ReviewFlowEdgeCasesTest, ReviewWizardEdgeCasesTest, ReviewWizardRouteInAppTest and extra cases in ReflectionSaverTest and ReviewRepositoryFlowTest, but did not write a report. The fixer then added ReviewViewModelRaceTest and extended ReflectionSaverTest and ReviewWizardTest. This pass checked the final code against the criteria and added tests only where real gaps remained.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewDraftsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewViewModelRaceTest.kt` (1 test added)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewWizardEdgeCasesTest.kt` (1 test added)

## Tests added or updated

Count: 6 (all new, no existing test changed)

- `ReviewDraftsTest`: `a review without a draft has an empty reflection`, `drafts are kept per review, and emptying one forgets only that one`, `clearing the completed review forgets its draft and its open wizard`, `clearing another review keeps the open wizard and the draft of this one`. The CR-2 holder had no direct unit test before. Its "clear another review" branch was uncovered.
- `ReviewViewModelRaceTest.an overview load answering after a new review was started keeps the wizard open`: protects CR-1 for the start path. Without the generation counter, a stale "no review open" answer would close the new wizard. Before this, only move and complete had tests for this.
- `ReviewWizardEdgeCasesTest.a failed past calendar retries the past step, not the upcoming one`: step 2's failure row retries `PAST_CALENDAR`. Only the step 3 variant had a test, and that branch (`ReviewWizard.kt:286`) was uncovered.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Start / continue an open review (same server session, resumable across devices) | ReviewFlowViewModelTest: `start without an open review starts one on the server...`, `an open review is continued at its step...`, `a failed start offline...`; ReviewFlowEdgeCasesTest: `a double tap on start...`, `a start refused as a conflict reloads...`, `a refresh that finds the review moved elsewhere...`; ReviewRepositoryFlowTest: `start posts and returns the open review`, `starting without a connection is offline`; ReviewViewModelRaceTest: tab-switch/draft tests, `an overview load answering after a new review was started keeps the wizard open` (new); ReviewWizardRouteInAppTest `start opens the wizard, and system back returns to the overview...` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass |
| 2. Step navigation: next/previous, jump, mark complete; progress shown | ReviewWizardTest: `the header shows the step and the track marks done, current and ahead`, `next, previous, a track segment and the step menu move the review`, `the first step has no previous...`; ReviewFlowViewModelTest `next, previous and jumping save the step on the server`, `previous on the first step and next on the last do nothing`, conflict reload tests; ReviewFlowEdgeCasesTest `jumping to the step the review is on saves nothing`, `a second tap on next...`; ReviewWizardEdgeCasesTest `the current step can't be jumped to...`, `while a change is saved...`; ReviewScreenTest step-list tests; ReviewRepositoryFlowTest `move patches the step with the review's version as If-Match`; ReviewViewModelRaceTest move/complete race tests | same | Pass |
| 3. Step 1: embedded inbox processing plus a Mail link | ReviewWizardTest `get clear embeds the inbox and links to Mail`; ReviewWizardRouteInAppTest `continuing at get clear embeds the real inbox screen and links to Mail`, `the Mail link of get clear opens Mail, and back returns to the wizard` | same | Pass |
| 4. Steps 2/3: past/upcoming calendar with quick-add capture | ReviewWizardTest `the calendar step lists the events by day and captures into the inbox`, `the quick-add button is disabled while a capture is stored`, `an empty or failed calendar says so, with retry`; ReviewWizardEdgeCasesTest empty/loading/failed-reload/quick-add tests, `a failed past calendar retries the past step, not the upcoming one` (new); ReviewFlowViewModelTest quick-add and calendar-load tests; ReviewFlowEdgeCasesTest blank/failed capture, step-load race; ReviewViewModelRaceTest double quick-add and typing-during-capture; ReviewRepositoryFlowTest event mapping (local day, all-day, fallbacks, endpoints) | same | Pass |
| 5. Step 8: reflection saved as a journal entry, habit stats shown | ReflectionSaverTest (11 cases: new/appended entry, local date at a UTC/Zurich day boundary, conflict retry, offline/refused, idempotent retry CR-4, retry cap); ReviewFlowViewModelTest `complete saves the reflection in the journal first...`, `without a reflection nothing goes to the journal`, `a reflection that could not be saved...`, `a failed completion after the reflection was saved does not append it again on retry`, `the reflection step loads the habits`; ReviewFlowEdgeCasesTest offline reflection, draft and habit failure; ReviewWizardTest `the reflection step shows the habit stats...`; ReviewWizardEdgeCasesTest habit failure/empty/weekly/rounding; ReviewDraftsTest (new) | same | Pass |
| 6. New strings in English and German | Android lint `MissingTranslation` (part of `lintDebug`); key-set comparison of `res/values/strings_review.xml` and `res/values-de/strings_review.xml` (66 names each, identical); Compose tests assert English texts | same, plus `grep -o 'name="[^"]*"'` on both files, sorted and diffed | Pass |
| 7. Unit/Compose tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` and `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.review.ReviewDraftsTest' --tests 'ch.sonensei.cockpit.review.ReviewViewModelRaceTest' --tests 'ch.sonensei.cockpit.review.ReviewWizardEdgeCasesTest'`

Result:

Pass. ReviewDraftsTest 4/4, ReviewViewModelRaceTest 10/10, ReviewWizardEdgeCasesTest 18/18, ktlint clean.

## Full test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (same as `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`, plus the coverage report)

Result:

Pass. BUILD SUCCESSFUL in 4m 7s (2026-11-08 15:01:02 to 15:05:10 +01:00, exit 0). From the JUnit XML: 3532 Android unit tests, 0 failures, 0 errors, 0 skipped. The review package has 130 of them, all passed.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran from 2026-11-08 15:05:24 to 15:06:54 +01:00, exit 0, "all checks passed".

## Bugs found

None. No production defect found in the final revision. Every test from the earlier passes and the new ones pass against 72df82ee6.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not formally measured. Spot check: in JaCoCo's per-line data, the task-changed lines of `ui/CockpitShell.kt` (183, 187, 318) have no missed instructions or branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-review-flow. Base fdd5513d678901dc58963738a9d1e5e05ab2166f, given by the parent (`git merge-base HEAD origin/main`). Measured HEAD 72df82ee649f1f9f8cab75b852e9acb89f152bf7. Task commits are `git log fdd5513d6..HEAD` (all prefixed `and-review-flow` or `assume: and-review-flow`). File scope comes from `git diff --name-only fdd5513d6..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 72df82ee6 with no production changes in the working tree, plus these uncommitted tests (sha256): ReviewDraftsTest.kt af1442b9c8cb39a31c2711d1acd6596aa8fb4e3b8a0dfa629f9d060020904292, ReviewViewModelRaceTest.kt 79db1bb7dc90ed722ef3eca9815fe16c23b9d96383c7627a3f85a9bc2bbb3bab, ReviewWizardEdgeCasesTest.kt 00e46b4aa615e2e02b2bb714b018fc66ee1280f117a8c31235eb0c7c9a035bd3. The hashes were the same before and after the run. `reviews/and-review-flow/review.md` was also modified in the working tree during this pass, but not by this test-writer. It is not a coverage input.
- Exact included files; renamed/deleted/excluded files and reasons: included are `review/ReflectionSaver.kt`, `review/ReviewDrafts.kt`, `review/ReviewRepository.kt`, `review/ReviewScreen.kt`, `review/ReviewViewModel.kt`, `review/ReviewWizard.kt` and `ui/CockpitShell.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). `res/values/strings_review.xml` and `res/values-de/strings_review.xml` are excluded because they are resources, not executable code. They are covered by lint and the key comparison. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` (Gradle `jacoco` plugin, default tool version). Command `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`, run from the repo root (project dir `android/`). All debug unit tests ran.
- Run start/completion time and exit status: 2026-11-08 15:01:02 to 15:05:10 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-08 15:04:25 +01:00, sha256 58b946edc8e1db8494a5800dd1b89729cc044a08f0e765780ee1efbe0c6cd1b0), plus `index.html` next to it. Counters were read with a local script from the package and sourcefile counters of the XML.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test inputs changed before this run, so Gradle reran `testDebugUnitTest` (5 tasks executed), and the report mtime falls inside the run. The input hashes were the same before and after.
- Final run after latest test additions and production fixes: yes. It ran on the post-fix HEAD 72df82ee6 with all tests from this pass.

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `review/ReflectionSaver.kt` | 273/280 (97.5%) | 32/32 (100.0%) | 6/7 (85.7%) | 27/27 (100.0%) |
| `review/ReviewDrafts.kt` | 61/61 (100.0%) | 8/8 (100.0%) | 6/6 (100.0%) | 10/10 (100.0%) |
| `review/ReviewRepository.kt` | 732/735 (99.6%) | 30/30 (100.0%) | 49/50 (98.0%) | 97/97 (100.0%) |
| `review/ReviewScreen.kt` | 2471/2511 (98.4%) | 97/101 (96.0%) | 55/67 (82.1%) | 240/241 (99.6%) |
| `review/ReviewViewModel.kt` | 1796/1815 (99.0%) | 106/117 (90.6%) | 59/59 (100.0%) | 199/201 (99.0%) |
| `review/ReviewWizard.kt` | 3900/3965 (98.4%) | 101/116 (87.1%) | 54/56 (96.4%) | 320/322 (99.4%) |
| **Review package aggregate (6 files above, summed counters)** | 9233/9367 (98.6%) | 374/404 (92.6%) | 229/245 (93.5%) | 893/898 (99.4%) |
| `ui/CockpitShell.kt` (whole file; the task changed 1 statement) | 1965/2056 (95.6%) | 107/127 (84.3%) | 48/52 (92.3%) | 167/169 (98.8%) |

The aggregate leaves out `ui/CockpitShell.kt`, a pre-existing shell file where the task changed only a few lines, so its whole-file figures mostly measure code outside the task. No in-scope file is missing from the report.

Before this pass's tests (cached run on the same HEAD, 14:59): ReviewDrafts branches 7/8, ReviewWizard branches 100/116, package branches 372/404.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReviewDrafts.kt:36` (clear another review) | CR-2: completing one review must not close another's wizard | Added `ReviewDraftsTest`; now 8/8 |
| `ReviewWizard.kt:286` (past-step retry) | Criterion 4: the failure row of step 2 retries step 2 | Added `a failed past calendar retries the past step, not the upcoming one` |
| `ReviewViewModel.kt:125` (stale load while `overview == null`), `:162` (start before any overview) | Start and saves are only reachable once an overview is shown | Not reachable from the UI; left uncovered. The start-race behavior itself is covered by the new race test. |
| `ReviewViewModel.kt:183, 200-201, 207-208, 214` (`current ?: return` / `?.step`) | Next/previous/goTo/complete without an open review | Defensive guards: the buttons exist only in the wizard of an open review. Left uncovered. |
| `ReviewViewModel.kt:189, 238` (`it.overview?.` null side) | Moving or completing while no overview is loaded | Unreachable while `current` is non-null; left uncovered |
| `ReviewViewModel.kt:267-268` (rethrow `CancellationException` in capture) | Scope cancellation while a capture runs | Standard coroutine hygiene; not material. Left uncovered. |
| `ReviewViewModel.kt:338` (`is ReviewResult.Ok` in `save`'s failure `when`) | None: the change lambdas never return Ok | Exhaustiveness arm only; left uncovered |
| `ReviewWizard.kt:75, 90, 99, 199, 203, 278, 386, 400, 413`; `ReviewScreen.kt:63, 75, 303, 307, 315` | Compose compiler restart/skip branches and `when` exhaustiveness arms; all user-visible branches (each step, each step state, failed/empty/loading) are exercised | Not material; left uncovered |
| `ReviewScreen.kt` methods 55/67, `ReflectionSaver.kt` 6/7, `ReviewRepository.kt` 49/50 | Unexecuted Compose/Hilt-generated synthetic methods and the production `@Inject` constructors (tests use the internal constructors with fixed clock and zone) | Wiring is exercised by the Hilt in-app route tests; not material |
| `ui/CockpitShell.kt:246, 321` and its missed branches | Outside the task's changed lines (search title fallback, `when` arm end) | Not part of this task |

## Unable to test

- Real cross-device resumption against a running server and the web client (criterion 1). Unit tests cover the shared server API calls: start, move with If-Match, complete, and the overview/status endpoints. There is no end-to-end run with Android and web on a live server. Follow-up only if the parent wants a manual or integration check; the criteria name unit/Compose tests.
- Reflection draft surviving app death: by design it does not (recorded assumption). Not a gap.
- Instrumented (device/emulator) UI tests: none exist for this feature. The Compose tests run under Robolectric (`testDebugUnitTest`), which is what criterion 7 names.

## Summary

Tests added and all validation passed. The final code meets criteria 1-7, and no production defects were found. Task-scoped coverage of the review package is 98.6% of instructions and 92.6% of branches. The remaining gaps are defensive guards or compiler-generated branches, explained above.
